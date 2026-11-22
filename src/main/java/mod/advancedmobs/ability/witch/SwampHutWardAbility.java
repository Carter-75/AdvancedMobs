package mod.advancedmobs.ability.witch;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Swamp Hut Ward (Witch ability 4 of 5, terrain, defense).
 *
 * WHAT IT DOES: the witch stamps her foot and her hut
 * answers: a ring of spruce planks and a bubbling cauldron
 * rise around her as a warded campsite. While she stands
 * inside the ward she regenerates steadily behind RESISTANCE,
 * the cauldron boils over with BUBBLE particles, and any
 * enemy who steps into the ward is splashed with POISON from
 * the brew. The ward is small, so area attacks still reach
 * her, but crossing the threshold costs blood.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6 and the MobAbility contract):
 * terrain changes from abilities are PERMANENT and gated by
 * the vanilla mobGriefing game rule, checked through
 * AbilityContext.mobGriefingAllowed(). This ability has two
 * modes:
 * - mobGriefing ON: the plank ring and the cauldron are
 *   placed and STAY. There is no restore: end() touches no
 *   blocks. The ward's effects still last only the ability
 *   duration; afterwards the ring is just a strange little
 *   campsite someone built.
 * - mobGriefing OFF: no blocks are placed at all. The ward
 *   zone still runs in full (the inside buffs, the poison
 *   threshold, the boiling bubbles at the spot where the
 *   cauldron would stand), and the world is left exactly as
 *   it was.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists within 14 blocks
 *   (distance squared 196) and the witch is standing on the
 *   ground. The ward is built around the WITCH, not the
 *   target: it is ground she defends from.
 * - Duration: exactly 300 ticks (15 seconds) from start().
 *   This is the ward zone's lifetime; in mobGriefing mode the
 *   planks and cauldron outlive it.
 * - The campsite (mobGriefing mode only, all in start()): the
 *   ring is the 16 columns at Chebyshev distance 2 from the
 *   witch's block position (dx and dz in -2..2 with
 *   max(|dx|, |dz|) = 2), each getting one SPRUCE_PLANKS
 *   block at the witch's feet level, plus one CAULDRON at the
 *   inside-east cell (center + 1, 0). A cell is filled only
 *   when it is air or a replaceable block with no fluid, the
 *   block being replaced is not unbreakable (destroy speed
 *   not negative), the cell holds no block entity, and the
 *   block below is face-sturdy up (the campsite never floats
 *   and the cauldron always sits on solid ground). At most 17
 *   blocks change. Existing solid blocks are never
 *   overwritten: if the witch is already walled in, the ring
 *   simply fills the gaps.
 * - The cauldron spot (both modes): center + (1, 0) at feet
 *   level is recorded in the state tag whether or not the
 *   cauldron block was placed; the boil bubbles and sounds
 *   come from that spot in both modes.
 * - The inside buffs (every 20 ticks): if the witch is
 *   within 3.0 blocks horizontal of the ward center and
 *   within 2 blocks vertical of its floor, she is refreshed
 *   with REGENERATION I and RESISTANCE I for 30 ticks each
 *   (the 30 versus 20 gap is the bleed-off if she steps out).
 *   Outside the ward she gets nothing: the hut protects its
 *   own ground only.
 * - The threshold (every 10 ticks): enemies
 *   (WitchAbilitySupport rules) within horizontal radius 2.6
 *   of the center and between 1 below and 2.5 above its floor
 *   get POISON I (amplifier 0) for 60 ticks, refreshed by the
 *   next pulse while they stay inside, plus 2 green EFFECT
 *   sparkles each as the brew splashes them.
 * - The boil: every 4 ticks, 3 BUBBLE particles rise over
 *   the cauldron spot; every 60 ticks a BREWING_STAND_BREW
 *   sound (pitch 1.1) boils from it.
 * - end(): REGENERATION and RESISTANCE are removed from the
 *   witch (if she owns permanent versions from the upgrade
 *   system, ScalingSystem's refresh re-applies them, the
 *   Living Stone Shell precedent; the ward's timed instances
 *   are its own). The placed blocks are permanent terrain
 *   and are deliberately NOT touched.
 *
 * WHAT IT LOOKS LIKE: a rough spruce ring springs up around
 * the witch with a cauldron bubbling inside it, steam of
 * BUBBLEs rising, and a green splash over anything foolish
 * enough to step across the planks.
 *
 * PERFORMANCE: block work happens once in start() (at most
 * 17 positions, mobGriefing mode only). tick() returns
 * immediately except on the 4 tick bubble pulse (3
 * particles), the 10 tick threshold pulse (one bounded
 * entity query over a 5.2 by 3.5 by 5.2 box), the 20 tick
 * buff check (distance reads only), and the 60 tick boil
 * sound. Particle budget per activation: bubbles 75 pulses
 * times 3 = 225, threshold sparkles at most 30 pulses times
 * 4 victims times 2 = 240, total at most 465 particles.
 *
 * State tag keys: "cx", "cy", "cz" (ward center block pos,
 * the witch's feet level at start), "kx", "ky", "kz" (the
 * cauldron spot). No block list is stored: placed blocks are
 * permanent and never revisited.
 */
public final class SwampHutWardAbility implements MobAbility {

    static final int DURATION_TICKS = 300;
    private static final int THRESHOLD_INTERVAL = 10;
    private static final int BUFF_INTERVAL = 20;

    @Override
    public String id() {
        return "swamp_hut_ward";
    }

    @Override
    public String displayName() {
        return "Swamp Hut Ward";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().onGround() && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob witch = ctx.mob();
        BlockPos center = witch.blockPosition();
        CompoundTag state = ctx.state();
        state.putInt("cx", center.getX());
        state.putInt("cy", center.getY());
        state.putInt("cz", center.getZ());
        // The cauldron spot: inside the ring, east of the
        // witch. Recorded in both terrain modes.
        state.putInt("kx", center.getX() + 1);
        state.putInt("ky", center.getY());
        state.putInt("kz", center.getZ());

        // Terrain (mobGriefing mode only): the hut answers.
        // With mobGriefing off, no block is touched and the
        // ward is a zone only. See the TERRAIN RULE in the
        // class comment.
        if (ctx.mobGriefingAllowed()) {
            buildCampsite(level, center);
        }

        // The stamp and the first boil.
        level.playSound(null, center.getX() + 0.5, center.getY(), center.getZ() + 0.5,
            SoundEvents.WITCH_AMBIENT, SoundSource.HOSTILE, 1.0f, 0.8f);
        level.sendParticles(ParticleTypes.BUBBLE,
            center.getX() + 1.5, center.getY() + 0.9, center.getZ() + 0.5,
            6, 0.3, 0.2, 0.3, 0.03);
    }

    /**
     * Raises the permanent campsite (see the class comment
     * for the shape and the safety checks). The blocks are
     * placed for good: nothing records them for removal.
     */
    private static void buildCampsite(ServerLevel level, BlockPos center) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != 2) {
                    continue;
                }
                placeWardBlock(level, new BlockPos(center.getX() + dx, center.getY(), center.getZ() + dz),
                    Blocks.SPRUCE_PLANKS.defaultBlockState());
            }
        }
        placeWardBlock(level, new BlockPos(center.getX() + 1, center.getY(), center.getZ()),
            Blocks.CAULDRON.defaultBlockState());
    }

    /**
     * Places one ward block when the cell is safe: air or a
     * dry replaceable block, not unbreakable, no block entity,
     * and solid ground below so the campsite never floats.
     */
    private static void placeWardBlock(ServerLevel level, BlockPos pos, BlockState block) {
        BlockState existing = level.getBlockState(pos);
        if (!existing.isAir() && !existing.canBeReplaced()) {
            return;
        }
        if (!existing.getFluidState().isEmpty()) {
            return; // Never fill a fluid cell.
        }
        if (existing.getDestroySpeed(level, pos) < 0.0f) {
            return; // Never touch unbreakable blocks.
        }
        if (level.getBlockEntity(pos) != null) {
            return; // Never overwrite containers or other block entities.
        }
        BlockPos belowPos = pos.below();
        if (!level.getBlockState(belowPos).isFaceSturdy(level, belowPos, Direction.UP)) {
            return; // The campsite never floats.
        }
        level.setBlock(pos, block, Block.UPDATE_ALL);
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        double cx = state.getIntOr("cx", 0) + 0.5;
        double cy = state.getIntOr("cy", 0);
        double cz = state.getIntOr("cz", 0) + 0.5;
        double kx = state.getIntOr("kx", 0) + 0.5;
        double ky = state.getIntOr("ky", 0);
        double kz = state.getIntOr("kz", 0) + 0.5;

        // The boil: bubbles over the cauldron spot, and the
        // brew sound on the minute marks.
        if (ticks % 4 == 0) {
            level.sendParticles(ParticleTypes.BUBBLE, kx, ky + 0.9, kz, 3, 0.25, 0.15, 0.25, 0.03);
        }
        if (ticks % 60 == 0) {
            level.playSound(null, kx, ky, kz,
                SoundEvents.BREWING_STAND_BREW, SoundSource.HOSTILE, 0.8f, 1.1f);
        }

        // The threshold: crossing costs blood.
        if (ticks % THRESHOLD_INTERVAL == 0) {
            List<LivingEntity> victims = WitchAbilitySupport.enemiesInBox(ctx, cx, cy, cz, 2.6, 1.0, 2.5);
            for (LivingEntity victim : victims) {
                victim.addEffect(new MobEffectInstance(MobEffects.POISON, 60, 0));
                level.sendParticles(WitchAbilitySupport.DEBUFF_SPARKLES[0],
                    victim.getX(), victim.getY() + 0.8, victim.getZ(), 2, 0.3, 0.4, 0.3, 0.03);
            }
        }

        // The inside buffs: the hut protects its own ground.
        if (ticks % BUFF_INTERVAL == 0) {
            Mob witch = ctx.mob();
            double dx = witch.getX() - cx;
            double dz = witch.getZ() - cz;
            if (dx * dx + dz * dz <= 9.0 && Math.abs(witch.getY() - cy) <= 2.0) {
                witch.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 30, 0));
                witch.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 30, 0));
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // The ward's timed buffs come off (permanent upgrade
        // versions, if any, are re-applied by ScalingSystem;
        // see the class comment). The planks and cauldron, when
        // placed, are permanent terrain and are deliberately
        // NOT touched here.
        Mob witch = ctx.mob();
        witch.removeEffect(MobEffects.REGENERATION);
        witch.removeEffect(MobEffects.RESISTANCE);
    }
}
