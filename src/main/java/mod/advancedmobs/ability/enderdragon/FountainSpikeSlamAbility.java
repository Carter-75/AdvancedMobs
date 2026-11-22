package mod.advancedmobs.ability.enderdragon;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fountain Spike Slam (Ender Dragon ability 2 of 5, terrain,
 * attack).
 *
 * WHAT IT DOES: when the dragon perches on the exit fountain,
 * she slams it with both claws and rings of jagged obsidian
 * spikes erupt from the end stone in three widening circles
 * around her, hurling enemies skyward and shredding anyone
 * standing where a spike comes up. A DUST_PILLAR of obsidian
 * dust marks each spike spot a split second before it
 * erupts, giving sharp-eyed enemies a chance to move.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the dragon's current phase instance reporting isSitting()
 *   (she is perched: the sitting flaming, scanning, or
 *   attacking phases), plus her current target within 48
 *   blocks (distance squared 2304). This ability can only
 *   ever fire during a perch, by design.
 * - The rings: 3 waves at radii 4, 8, and 12 blocks around
 *   the slam center. The center is the dragon's position
 *   when the ability starts (a perched dragon sits on the
 *   fountain, so the rings radiate from the fountain), and
 *   the base height is the top solid block of the center
 *   column, found by scanning down at most 48 blocks from
 *   just above her feet. Spike spots sit every 2.2 blocks
 *   along each circumference (11, 22, and 34 spots, 67 in
 *   total), each ring rotated 0.35 radians past the last so
 *   the rings do not line up, with duplicate columns inside
 *   a ring removed.
 * - The telegraph: wave 1, 2, 3 are telegraphed at ticks 6,
 *   20, 34 and erupt at ticks 12, 26, 40 (a 6-tick warning,
 *   about a split second at fight speed). A telegraph finds
 *   each spot's ground (the top solid block in its column,
 *   scanning from 8 above the base height to 12 below it)
 *   and sends 3 DUST_PILLAR particles carrying the obsidian
 *   block state at 1.1 above that ground.
 * - The eruption: at each spot, every enemy
 *   (EnderDragonAbilitySupport rules) within 1.7 blocks
 *   horizontally of the spot, in the column from its ground
 *   level up to 5 blocks above it, takes 8.0 points of typed mob-attack damage
 *   credited to the dragon and is launched: velocity set to
 *   0.45 outward from the spot horizontally and 0.95 up,
 *   replacing its current motion, hurtMarked set so clients
 *   see the launch at once. The damage and the launch are
 *   not terrain effects and land in full whether or not
 *   mobGriefing is on. Each wave plays one
 *   ENDER_DRAGON_FLAP sound (volume 2.0, pitch 0.5) at the
 *   center, the claw slam coming down.
 * - Duration: the ability finishes at tick 50, 10 ticks
 *   after the last eruption, so the final ring reads before
 *   the cooldown starts.
 *
 * TERRAIN (the standing terrain rule, Carter, Nov 21, 2026):
 * the design text has the spikes "linger as temporary cover
 * before crumbling away", and that is superseded by the
 * standing rule, exactly as Gravemud Mire and Magma Ring
 * Rising were handled before: terrain changes from abilities
 * are PERMANENT and there is no restore machinery. With
 * mobGriefing on, each eruption spot grows a real obsidian
 * spike: 2 or 3 blocks tall (alternating by spot), placed
 * only into air cells with no block entity, on top of the
 * spot's solid ground, so containers and unbreakable blocks
 * are never overwritten (the bedrock fountain frame is
 * unbreakable and is never a placement cell anyway, since
 * spikes only ever replace air). With mobGriefing off, no
 * spike is placed at all; the telegraphs, damage, launches,
 * particles, and sounds all still run, and the slam is a
 * shockwave instead of a spike field. The crumble visual
 * still plays either way: 3 BLOCK_CRUMBLE particles carrying
 * the obsidian state per spot at the eruption.
 *
 * MOVEMENT INTERACTION: none. The ability never moves,
 * teleports, or steers the dragon; she stays on the fountain
 * under her phase controller for the whole slam, which is
 * why the trigger requires a sitting phase.
 *
 * PERFORMANCE: all work happens on 6 ticks (3 telegraphs, 3
 * eruptions); every other tick returns after one comparison
 * loop over 3 wave ticks. Each wave tick scans at most 34
 * columns of at most 21 block reads and runs one bounded
 * enemy query per spot. Spike positions are recomputed
 * deterministically from the stored center on each wave tick
 * instead of being stored, so the state tag stays tiny.
 * Particle budget per activation: telegraphs 67 spots times
 * 3 = 201, eruptions 67 spots times 3 = 201, total at most
 * 402 particles. Block placements: at most 67 spots times 3
 * = 201 obsidian blocks, and only into air above found
 * ground.
 *
 * State tag keys: "cx" and "cz" (the slam center), "base_y"
 * (the center column's ground height).
 */
public final class FountainSpikeSlamAbility implements MobAbility {

    static final int FINISH_TICKS = 50;
    private static final double[] RADII = {4.0, 8.0, 12.0};
    private static final int[] TELEGRAPH_TICKS = {6, 20, 34};
    private static final int[] ERUPT_TICKS = {12, 26, 40};
    private static final float SPIKE_DAMAGE = 8.0f;
    private static final BlockParticleOption OBSIDIAN_DUST =
        new BlockParticleOption(ParticleTypes.DUST_PILLAR, Blocks.OBSIDIAN.defaultBlockState());
    private static final BlockParticleOption OBSIDIAN_CRUMBLE =
        new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, Blocks.OBSIDIAN.defaultBlockState());

    @Override
    public String id() {
        return "fountain_spike_slam";
    }

    @Override
    public String displayName() {
        return "Fountain Spike Slam";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        if (!(ctx.mob() instanceof EnderDragon dragon)) {
            return false;
        }
        if (!dragon.getPhaseManager().getCurrentPhase().isSitting()) {
            return false;
        }
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 2304.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob dragon = ctx.mob();
        ctx.state().putDouble("cx", dragon.getX());
        ctx.state().putDouble("cz", dragon.getZ());
        int baseY = dragon.getBlockY() - 8;
        if (level != null) {
            int ground = EnderDragonAbilitySupport.topSolidY(level,
                dragon.getBlockX(), dragon.getBlockZ(), dragon.getBlockY() + 2, dragon.getBlockY() - 48);
            if (ground != Integer.MIN_VALUE) {
                baseY = ground;
            }
            level.playSound(null, dragon.getX(), dragon.getY(), dragon.getZ(),
                SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 2.0f, 0.6f);
        }
        ctx.state().putInt("base_y", baseY);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        for (int wave = 0; wave < RADII.length; wave++) {
            if (ticks == TELEGRAPH_TICKS[wave]) {
                telegraphWave(ctx, level, wave);
            } else if (ticks == ERUPT_TICKS[wave]) {
                eruptWave(ctx, level, wave);
            }
        }
    }

    /**
     * The spike columns of one ring: block (x, z) pairs every
     * 2.2 blocks along the circumference, deduplicated. Pure
     * arithmetic from the stored center, so telegraph and
     * eruption always agree on the spots.
     */
    private Set<Long> ringColumns(AbilityContext ctx, int wave) {
        double cx = ctx.state().getDoubleOr("cx", 0.0);
        double cz = ctx.state().getDoubleOr("cz", 0.0);
        double radius = RADII[wave];
        int count = Math.max(6, (int) Math.floor(2.0 * Math.PI * radius / 2.2));
        double offset = wave * 0.35;
        Set<Long> columns = new HashSet<>();
        for (int i = 0; i < count; i++) {
            double angle = offset + (i * 2.0 * Math.PI) / count;
            int x = (int) Math.round(cx + Math.cos(angle) * radius);
            int z = (int) Math.round(cz + Math.sin(angle) * radius);
            columns.add(BlockPos.asLong(x, 0, z));
        }
        return columns;
    }

    /** The ground height of one spike column, or Integer.MIN_VALUE when the scan window holds no ground. */
    private int columnGround(AbilityContext ctx, ServerLevel level, int x, int z) {
        int baseY = ctx.state().getIntOr("base_y", 0);
        return EnderDragonAbilitySupport.topSolidY(level, x, z, baseY + 8, baseY - 12);
    }

    /** The telegraph for one wave: DUST_PILLAR warnings over every spike spot that has ground. */
    private void telegraphWave(AbilityContext ctx, ServerLevel level, int wave) {
        for (long packed : ringColumns(ctx, wave)) {
            int x = BlockPos.getX(packed);
            int z = BlockPos.getZ(packed);
            int ground = columnGround(ctx, level, x, z);
            if (ground == Integer.MIN_VALUE) {
                continue;
            }
            level.sendParticles(OBSIDIAN_DUST, x + 0.5, ground + 1.1, z + 0.5,
                3, 0.2, 0.3, 0.2, 0.05);
        }
    }

    /**
     * The eruption for one wave: the slam sound, then per spot
     * the damage and launch (always) and the permanent
     * obsidian spike (only when the terrain rule allows it).
     */
    private void eruptWave(AbilityContext ctx, ServerLevel level, int wave) {
        Mob dragon = ctx.mob();
        double cx = ctx.state().getDoubleOr("cx", dragon.getX());
        double cz = ctx.state().getDoubleOr("cz", dragon.getZ());
        level.playSound(null, cx, dragon.getY(), cz,
            SoundEvents.ENDER_DRAGON_FLAP, SoundSource.HOSTILE, 2.0f, 0.5f);
        boolean placeSpikes = ctx.mobGriefingAllowed();
        int spotIndex = 0;
        for (long packed : ringColumns(ctx, wave)) {
            int x = BlockPos.getX(packed);
            int z = BlockPos.getZ(packed);
            int ground = columnGround(ctx, level, x, z);
            if (ground == Integer.MIN_VALUE) {
                spotIndex++;
                continue;
            }
            double spotX = x + 0.5;
            double spotZ = z + 0.5;

            // The shred and the launch: every enemy standing
            // where this spike comes up.
            List<LivingEntity> victims = EnderDragonAbilitySupport.enemiesInBox(
                ctx, spotX, ground + 1.0, spotZ, 1.7, 1.0, 4.0);
            for (LivingEntity victim : victims) {
                victim.hurtServer(level, level.damageSources().mobAttack(dragon), SPIKE_DAMAGE);
                Vec3 away = new Vec3(victim.getX() - spotX, 0.0, victim.getZ() - spotZ);
                if (away.lengthSqr() < 1.0e-4) {
                    away = new Vec3(1.0, 0.0, 0.0);
                }
                away = away.normalize();
                victim.setDeltaMovement(away.x * 0.45, 0.95, away.z * 0.45);
                victim.hurtMarked = true;
            }

            // The spike itself: permanent obsidian, air cells
            // only, and only under the terrain rule.
            if (placeSpikes) {
                int height = 2 + (spotIndex % 2);
                for (int dy = 1; dy <= height; dy++) {
                    BlockPos cell = new BlockPos(x, ground + dy, z);
                    if (level.getBlockState(cell).isAir() && level.getBlockEntity(cell) == null) {
                        level.setBlock(cell, Blocks.OBSIDIAN.defaultBlockState(), 3);
                    }
                }
            }
            level.sendParticles(OBSIDIAN_CRUMBLE, spotX, ground + 1.2, spotZ,
                3, 0.3, 0.4, 0.3, 0.1);
            spotIndex++;
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= FINISH_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to clean up: no modifiers, no summons, and
        // the spikes are permanent terrain by the standing
        // rule (or were never placed).
    }
}
