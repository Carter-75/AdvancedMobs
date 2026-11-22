package mod.advancedmobs.ability.wither;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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
 * Graveyard Bloom (Wither ability 3 of 5, terrain).
 *
 * WHAT IT DOES: the wither poisons the earth itself. The
 * ground in a wide disc around it converts to soul sand and
 * soul soil in a checker pattern, dotted with wither roses,
 * while pillars of SOUL_FIRE_FLAME gutter up between the
 * graves. Enemies crossing the graveyard are withered by the
 * field and slowed on the cursed soil, and the wither fights
 * faster and meaner while it stays over its own ground.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6 and the MobAbility contract):
 * terrain changes from abilities are PERMANENT and gated by
 * the vanilla mobGriefing game rule, checked through
 * AbilityContext.mobGriefingAllowed(). Two consequences:
 * - mobGriefing ON: the soul blocks and roses are placed and
 *   STAY. There is no restore: end() touches no blocks.
 * - mobGriefing OFF: nothing is converted and no roses are
 *   planted, so there are no graves and no flame pillars. The
 *   field pulse below still runs in full: the withering, the
 *   slowness on any natural soul sand or soul soil in the
 *   disc, and the wither's own buff over its claimed ground.
 * The design text's "the bloom fades from the edges inward
 * once the wither leaves or dies" is superseded by the
 * standing terrain rule, exactly as Magma Ring Rising's
 * crumbling ring was: placed terrain is permanent, and what
 * ends with the ability is the field, not the ground.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the wither's current target within 20 blocks (distance
 *   squared 400).
 * - The field: centered on the wither's block position at
 *   start() (the center is recorded and never moves), radius
 *   8 (Chebyshev), duration exactly 400 ticks (20 seconds).
 * - Conversion (mobGriefing mode only): the disc grows ring by
 *   ring, one Chebyshev ring every 8 ticks, ring 0 (the center
 *   column) at tick 1 out to ring 8 at tick 65. Each column's
 *   ground is found by WitherAbilitySupport.findBloomGround
 *   (the topmost convertible block between 4 above and 8 below
 *   the center height; plants and other non-solids are scanned
 *   past, containers and unbreakable blocks are refused) and
 *   replaced IN the ground with SOUL_SAND when (x + z) is even
 *   and SOUL_SOIL when it is odd, so the walking surface never
 *   rises or floats. Conversions are capped at 160 per
 *   activation. With a 1 in 6 roll per converted column, a
 *   WITHER_ROSE is planted on top when the space above is
 *   replaceable, holds no block entity, and the rose can
 *   survive there (its own canSurvive check, which accepts
 *   soul sand and soul soil); roses are capped at 16, and each
 *   rose position is recorded as a grave for the flame
 *   pillars.
 * - The field pulse, every 10 ticks: one bounded query for
 *   enemies (WitherAbilitySupport rules) in the disc (radius
 *   8.5 horizontally around the center, between 6 below and 6
 *   above the center height). Each enemy in the disc gains
 *   WITHER amplifier 0 for 40 ticks (the field withers anyone
 *   crossing, whatever they stand on). An enemy standing on
 *   soul sand or soul soil (the block below its feet) also
 *   gains SLOWNESS amplifier 1 for 30 ticks, on top of soul
 *   sand's own vanilla drag. If the wither itself is within
 *   radius 9 of the center and 8 blocks vertically, it is over
 *   its own cursed ground and refreshes SPEED amplifier 0 and
 *   STRENGTH amplifier 0, 30 ticks each: noticeably faster and
 *   meaner, per the design.
 * - The flame pillars, every 20 ticks (mobGriefing mode only,
 *   and only when graves exist): 1 SOUL_FIRE_FLAME particle
 *   rises at each recorded grave, 0.7 blocks above the ground.
 *
 * WHAT IT LOOKS AND SOUNDS LIKE: a WITHER_AMBIENT groan
 * (volume 1.2, pitch 0.6) and a 12 particle SOUL burst open
 * the bloom. Each converted column puffs 1 SOUL particle as
 * the curse takes it. The graveyard itself is real soul sand,
 * soul soil, and wither roses, with soul fire guttering over
 * the graves; the field's withering shows as the vanilla
 * WITHER effect on its victims.
 *
 * PERFORMANCE: conversion runs on 9 ring ticks only, each
 * ring scanning at most 64 columns with at most 13 block reads
 * per column, and stops entirely once the 160 conversion cap
 * is hit. The field pulse is one bounded entity query every
 * 10 ticks plus one block read per victim. The pillars are
 * one particle per recorded grave every 20 ticks. No
 * whole-level scans. Particle budget per activation: 12 at
 * start, at most 160 conversion puffs, at most 16 graves
 * times 20 pillar pulses = 320, total at most 492 particles.
 *
 * State tag keys: "cx", "cy", "cz" (the recorded field
 * center), "ring" (the next ring to convert), "converted"
 * (conversions so far, capped at 160), "roses" (roses planted
 * so far, capped at 16), "graves" (list of compounds, one per
 * rose: "x" "y" "z" the ground position under the rose). No
 * block list is stored beyond the graves: placed blocks are
 * permanent and never revisited.
 */
public final class GraveyardBloomAbility implements MobAbility {

    static final int DURATION_TICKS = 400;
    private static final int RADIUS = 8;
    private static final int RING_INTERVAL = 8;
    private static final int PULSE_INTERVAL = 10;
    private static final int PILLAR_INTERVAL = 20;
    private static final int MAX_CONVERSIONS = 160;
    private static final int MAX_ROSES = 16;

    @Override
    public String id() {
        return "graveyard_bloom";
    }

    @Override
    public String displayName() {
        return "Graveyard Bloom";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 400.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        BlockPos center = mob.blockPosition();
        CompoundTag state = ctx.state();
        state.putInt("cx", center.getX());
        state.putInt("cy", center.getY());
        state.putInt("cz", center.getZ());
        state.putInt("ring", 0);
        state.putInt("converted", 0);
        state.putInt("roses", 0);
        state.put("graves", new ListTag());

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.sendParticles(ParticleTypes.SOUL, mob.getX(), mob.getY() + 1.5, mob.getZ(),
                12, 1.2, 1.0, 1.2, 0.06);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.WITHER_AMBIENT, SoundSource.HOSTILE, 1.2f, 0.6f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        int ticks = ctx.ticksActive();

        // Conversion: one ring every 8 ticks until the disc is done.
        int ring = state.getIntOr("ring", 0);
        if (ring <= RADIUS && ticks % RING_INTERVAL == 1) {
            if (ctx.mobGriefingAllowed()) {
                convertRing(ctx, level, state, ring);
            }
            state.putInt("ring", ring + 1);
        }

        if (ticks % PULSE_INTERVAL == 0) {
            fieldPulse(ctx, level, state);
        }

        if (ticks % PILLAR_INTERVAL == 0) {
            flamePillars(level, state);
        }
    }

    /**
     * Converts one Chebyshev ring of the disc (see the class
     * comment for the placement rules and caps). Only called
     * when mobGriefing is on.
     */
    private void convertRing(AbilityContext ctx, ServerLevel level, CompoundTag state, int ring) {
        int cx = state.getIntOr("cx", 0);
        int cy = state.getIntOr("cy", 0);
        int cz = state.getIntOr("cz", 0);
        int converted = state.getIntOr("converted", 0);
        int roses = state.getIntOr("roses", 0);
        for (int dx = -ring; dx <= ring && converted < MAX_CONVERSIONS; dx++) {
            for (int dz = -ring; dz <= ring && converted < MAX_CONVERSIONS; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                    continue; // Only this ring's perimeter columns.
                }
                int x = cx + dx;
                int z = cz + dz;
                BlockPos ground = WitherAbilitySupport.findBloomGround(level, x, z, cy);
                if (ground == null) {
                    continue;
                }
                Block block = (x + z) % 2 == 0 ? Blocks.SOUL_SAND : Blocks.SOUL_SOIL;
                if (!level.setBlock(ground, block.defaultBlockState(), Block.UPDATE_ALL)) {
                    continue;
                }
                converted++;
                level.sendParticles(ParticleTypes.SOUL, ground.getX() + 0.5, ground.getY() + 1.1, ground.getZ() + 0.5,
                    1, 0.3, 0.15, 0.3, 0.03);
                // The graves: a wither rose dotted here and there
                // over the fresh cursed soil.
                if (roses < MAX_ROSES && ctx.random().nextInt(6) == 0) {
                    BlockPos above = ground.above();
                    BlockState aboveState = level.getBlockState(above);
                    BlockState rose = Blocks.WITHER_ROSE.defaultBlockState();
                    if (aboveState.canBeReplaced() && level.getBlockEntity(above) == null
                        && rose.canSurvive(level, above)
                        && level.setBlock(above, rose, Block.UPDATE_ALL)) {
                        roses++;
                        CompoundTag grave = new CompoundTag();
                        grave.putInt("x", ground.getX());
                        grave.putInt("y", ground.getY());
                        grave.putInt("z", ground.getZ());
                        state.getListOrEmpty("graves").add(grave);
                    }
                }
            }
        }
        state.putInt("converted", converted);
        state.putInt("roses", roses);
    }

    /**
     * The field pulse (see the class comment): the disc withers
     * its enemies, the cursed soil slows them, and the wither
     * draws strength from standing over its own ground. Runs
     * in both terrain modes.
     */
    private void fieldPulse(AbilityContext ctx, ServerLevel level, CompoundTag state) {
        int cx = state.getIntOr("cx", 0);
        int cy = state.getIntOr("cy", 0);
        int cz = state.getIntOr("cz", 0);
        List<LivingEntity> victims = WitherAbilitySupport.enemiesInBox(
            ctx, cx + 0.5, cy, cz + 0.5, RADIUS + 0.5, 6.0, 6.0);
        for (LivingEntity victim : victims) {
            victim.addEffect(new MobEffectInstance(MobEffects.WITHER, 40, 0));
            BlockState below = level.getBlockState(victim.blockPosition().below());
            if (below.is(Blocks.SOUL_SAND) || below.is(Blocks.SOUL_SOIL)) {
                victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 30, 1));
            }
        }
        Mob mob = ctx.mob();
        double dx = mob.getX() - (cx + 0.5);
        double dz = mob.getZ() - (cz + 0.5);
        if (dx * dx + dz * dz <= 81.0 && Math.abs(mob.getY() - cy) <= 8.0) {
            mob.addEffect(new MobEffectInstance(MobEffects.SPEED, 30, 0));
            mob.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 30, 0));
        }
    }

    /** The flame pillars: soul fire guttering over each recorded grave. */
    private void flamePillars(ServerLevel level, CompoundTag state) {
        ListTag graves = state.getListOrEmpty("graves");
        for (int i = 0; i < graves.size(); i++) {
            CompoundTag grave = graves.getCompoundOrEmpty(i);
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                grave.getIntOr("x", 0) + 0.5, grave.getIntOr("y", 0) + 1.7, grave.getIntOr("z", 0) + 0.5,
                1, 0.12, 0.3, 0.12, 0.02);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // The wither's own field buff ends with the ability (the
        // victims' short effects expire on their own within 2
        // seconds). The converted ground and the roses, when
        // placed, are permanent terrain and are deliberately NOT
        // touched here (terrain rule, see the class comment).
        Mob mob = ctx.mob();
        mob.removeEffect(MobEffects.SPEED);
        mob.removeEffect(MobEffects.STRENGTH);
    }
}
