package mod.advancedmobs.ability.magmacube;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;

/**
 * Lava Bloom (Magma Cube ability 2 of 5, terrain).
 *
 * WHAT IT DOES: the ground cracks open around the magma cube
 * and real lava wells up in a spreading bloom: a pool of lava
 * source blocks grows ring by ring out to radius 2, ringed at
 * radius 3 by a crust of magma blocks, DRIPPING_LAVA and
 * FALLING_LAVA sheeting off the edges as the pools form. The
 * cube bathes in its own bloom untouched (a magma cube is
 * fire immune by type; the FIRE_RESISTANCE below is the
 * design's stated bath, kept refreshed so the read is plain)
 * and gains SPEED I for racing across the crust, while enemies
 * must flee the growing lake or burn in it.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists within 10 blocks
 *   (distance squared 100) and the cube is on the ground. The
 *   bloom is a place, not an aura: start() records the cube's
 *   block position as the center, and the bloom grows there
 *   even if the cube hops away after casting.
 * - Growth: ring 0 (the center) and ring 1 land on the first
 *   tick, ring 2 at tick 20, ring 3 at tick 40. A ring is the
 *   Chebyshev ring (every column whose larger axis offset
 *   equals the radius): 1, 8, 16, and 24 columns. Each column's
 *   ground is found by the shared scan (topmost convertible
 *   block from one above the center height to two below it)
 *   and converted: rings 0 to 2 become LAVA source blocks,
 *   ring 3 becomes MAGMA_BLOCK, the crust at the pool's edge.
 *   At most 40 conversions per activation, so a bloom is one
 *   bounded lake, never a flood. A LAVA_POP (volume 0.7, pitch
 *   0.8) bubbles at the center on each growth tick.
 * - The bath: SPEED I and FIRE_RESISTANCE I are applied in
 *   start() for 30 ticks and refreshed for 30 more every 20
 *   ticks of the duration, so they run the whole 200 ticks and
 *   are removed in end() (see CLEANUP).
 * - Duration: exactly 200 ticks (10 seconds) from start(). The
 *   terrain the bloom made does NOT end with the ability: the
 *   lava and crust are permanent (see the TERRAIN RULE).
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6): the bloom's lava and magma
 * blocks are PERMANENT terrain changes, gated by
 * AbilityContext.mobGriefingAllowed(), which is checked again
 * at every growth tick (the rule could be toggled mid-bloom):
 * - mobGriefing ON: the ground converts as above and STAYS
 *   converted. Conversion safety comes from the shared ground
 *   check: never a container or other block entity, never an
 *   unbreakable block, never a fluid or a block with fluid
 *   above it, never a non-solid (the scan passes plants and
 *   stops at real ground; surface growth above converted
 *   ground pops off on the block update, which is the bloom
 *   cracking through, by design).
 * - mobGriefing OFF: no block changes at all. The bloom still
 *   casts: the growth ticks play their sounds and particles at
 *   the recorded center and the cube still gets its SPEED and
 *   FIRE_RESISTANCE for the duration. Only the lake is missing.
 *
 * WHAT IT LOOKS LIKE: the ground splitting open in widening
 * rings of lava over two seconds, 1 DRIPPING_LAVA particle at
 * each converted column as it turns, 3 FALLING_LAVA sheeting
 * at the bloom's edge every 20 ticks, and an opening burst of
 * 8 FALLING_LAVA and 6 DRIPPING_LAVA over the center with the
 * first LAVA_POP.
 *
 * PERFORMANCE: block work happens on 3 growth ticks only, at
 * most 49 column scans of 4 block reads each plus the capped
 * 40 conversions; the other ticks are an effect refresh every
 * 20 ticks and a particle pulse every 20 ticks. No entity
 * queries at all (the lava itself does the hurting, in the
 * vanilla block tick). Particle budget per activation:
 * opening burst 14, conversions at most 40 times 1 = 40, edge
 * sheets 10 pulses times 3 = 30, total at most 84 particles.
 *
 * CLEANUP: end() removes the SPEED and FIRE_RESISTANCE effects
 * from the cube. The lava and magma blocks are permanent
 * terrain and are deliberately NOT touched.
 *
 * State tag keys: "cx", "cz" (the bloom center column),
 * "cy" (the center height the ground scan anchors to),
 * "placed" (conversions so far, against the cap).
 */
public final class LavaBloomAbility implements MobAbility {

    static final int DURATION_TICKS = 200;
    private static final int MAX_PLACED = 40;
    private static final int BUFF_REFRESH_INTERVAL = 20;
    private static final int BUFF_TICKS = 30;

    @Override
    public String id() {
        return "lava_bloom";
    }

    @Override
    public String displayName() {
        return "Lava Bloom";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return ctx.target() != null
            && ctx.mob().onGround()
            && ctx.mob().distanceToSqr(ctx.target()) <= 100.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        BlockPos center = mob.blockPosition();
        state.putInt("cx", center.getX());
        state.putInt("cy", center.getY());
        state.putInt("cz", center.getZ());
        state.putInt("placed", 0);
        applyBuffs(mob);
        if (level != null) {
            level.sendParticles(ParticleTypes.FALLING_LAVA,
                center.getX() + 0.5, center.getY() + 1.0, center.getZ() + 0.5,
                8, 1.2, 0.4, 1.2, 0.08);
            level.sendParticles(ParticleTypes.DRIPPING_LAVA,
                center.getX() + 0.5, center.getY() + 1.0, center.getZ() + 0.5,
                6, 1.2, 0.4, 1.2, 0.0);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        CompoundTag state = ctx.state();
        int cx = state.getIntOr("cx", ctx.mob().getBlockX());
        int cy = state.getIntOr("cy", ctx.mob().getBlockY());
        int cz = state.getIntOr("cz", ctx.mob().getBlockZ());

        if (ticks == 1) {
            growRing(ctx, level, cx, cy, cz, 0);
            growRing(ctx, level, cx, cy, cz, 1);
            bubbleSound(level, cx, cy, cz);
        } else if (ticks == 20) {
            growRing(ctx, level, cx, cy, cz, 2);
            bubbleSound(level, cx, cy, cz);
        } else if (ticks == 40) {
            growRing(ctx, level, cx, cy, cz, 3);
            bubbleSound(level, cx, cy, cz);
        }

        if (ticks % BUFF_REFRESH_INTERVAL == 0) {
            applyBuffs(ctx.mob());
            level.sendParticles(ParticleTypes.FALLING_LAVA,
                cx + 0.5 + (ctx.random().nextDouble() * 6.0 - 3.0), cy + 1.2,
                cz + 0.5 + (ctx.random().nextDouble() * 6.0 - 3.0),
                3, 0.3, 0.3, 0.3, 0.0);
        }
    }

    /** The bath: SPEED I and FIRE_RESISTANCE I, refreshed for the bloom's duration. */
    private static void applyBuffs(Mob mob) {
        mob.addEffect(new MobEffectInstance(MobEffects.SPEED, BUFF_TICKS, 0, false, false, false));
        mob.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, BUFF_TICKS, 0, false, false, false));
    }

    /**
     * Converts one Chebyshev ring of columns around the center:
     * rings 0 to 2 to lava, ring 3 to magma crust. Does nothing
     * when the terrain rule forbids block changes; the cap and
     * the shared ground check bound and sanitize every
     * conversion (see the TERRAIN RULE in the class comment).
     */
    private static void growRing(AbilityContext ctx, ServerLevel level, int cx, int cy, int cz, int radius) {
        if (!ctx.mobGriefingAllowed()) {
            return;
        }
        CompoundTag state = ctx.state();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                    continue;
                }
                if (state.getIntOr("placed", 0) >= MAX_PLACED) {
                    return;
                }
                BlockPos ground = MagmaCubeAbilitySupport.findGround(level, cx + dx, cz + dz, cy);
                if (ground == null) {
                    continue;
                }
                level.setBlock(ground,
                    radius <= 2 ? Blocks.LAVA.defaultBlockState() : Blocks.MAGMA_BLOCK.defaultBlockState(),
                    net.minecraft.world.level.block.Block.UPDATE_ALL);
                state.putInt("placed", state.getIntOr("placed", 0) + 1);
                level.sendParticles(ParticleTypes.DRIPPING_LAVA,
                    ground.getX() + 0.5, ground.getY() + 1.1, ground.getZ() + 0.5,
                    1, 0.3, 0.1, 0.3, 0.0);
            }
        }
    }

    /** The bubbling pop that marks each growth ring. */
    private static void bubbleSound(ServerLevel level, int cx, int cy, int cz) {
        level.playSound(null, cx + 0.5, cy + 1.0, cz + 0.5, SoundEvents.LAVA_POP,
            SoundSource.HOSTILE, 0.7f, 0.8f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        mob.removeEffect(MobEffects.SPEED);
        mob.removeEffect(MobEffects.FIRE_RESISTANCE);
    }
}
