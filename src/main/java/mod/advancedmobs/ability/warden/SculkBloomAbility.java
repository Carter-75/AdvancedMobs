package mod.advancedmobs.ability.warden;

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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Sculk Bloom (Warden ability 2 of 5, terrain).
 *
 * WHAT IT DOES: the warden slams both fists down and the deep
 * dark answers: a carpet of real sculk spreads across the
 * ground around it in three growing rings, sculk veins
 * creeping over the surface and a single sculk catalyst
 * blooming at the center. While the ability lasts, enemies
 * standing on the bloom are slowed by the clinging sculk,
 * and the warden regenerates while it fights standing on its
 * own ground. The patch is home ground, and it stays.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the warden's current target within 12 blocks (distance
 *   squared 144) and the warden on the ground. The bloom
 *   center is the warden's feet position at start() and does
 *   not move.
 * - The slam (start()): a WARDEN_AGITATED roar (volume 2.0,
 *   pitch 0.8) and a burst of 12 SCULK_CHARGE_POP sparks at
 *   the warden's feet.
 * - The growth (ticks 5, 15, 25): ring bands out to radius
 *   2.5, then 4.5, then 6.5 blocks from the center. Each
 *   column in the band finds its surface (the highest block
 *   between center height +2 and -4 that is not air and
 *   carries no fluid, with air above it; a column with no
 *   such block is skipped). For each surfaced column, in
 *   mobGriefing mode only (see the terrain rule below):
 *   - the center column (within 0.8 blocks of the center)
 *     converts to a SCULK_CATALYST instead of plain sculk,
 *   - otherwise, on a 1 in 5 roll the cell above the surface
 *     (when air) receives a floor sculk vein (a multiface
 *     state with only the DOWN face set, lying on the
 *     surface block),
 *   - otherwise the surface block itself converts to SCULK.
 *   Conversions go through the shared isDiggable check
 *   (never fluids, block entities, or unbreakable blocks)
 *   and are hard capped at 90 per activation, counted in
 *   the state tag. Each conversion pops 1 SCULK_CHARGE_POP
 *   spark at the surface.
 * - The zone (ticks 10 to 400, pulse every 10 ticks): one
 *   bounded query over radius 7.0 around the center (3 below
 *   to 3 above center height). Enemies (the package enemy
 *   rule) standing on a sculk family block (the block below
 *   their feet is SCULK, SCULK_VEIN, or SCULK_CATALYST) gain
 *   SLOWNESS amplifier 0 for 30 ticks. If the warden itself
 *   is standing on a sculk family block, it refreshes
 *   REGENERATION amplifier 0 for 30 ticks: the design's
 *   "regains health while it fights on its own sculk",
 *   delivered as the REGENERATION effect from the design's
 *   Uses list. Each pulse also drifts 2 SCULK_SOUL wisps at
 *   random points over the patch.
 * - Duration: 400 ticks (20 seconds). After it ends the
 *   pulse stops; the bloom itself remains as plain terrain.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6 and the MobAbility contract):
 * the bloom's sculk, veins, and catalyst are PERMANENT and
 * gated by AbilityContext.mobGriefingAllowed(), re-checked
 * at every growth tick:
 * - mobGriefing ON: the bloom converts as above and STAYS.
 *   end() touches no blocks. (The design text does not ask
 *   for the bloom to fade, so there is no conflict here.)
 * - mobGriefing OFF: no block is converted and no vein is
 *   laid. The slam, the growth sounds and sparks, and the
 *   zone pulse all run exactly the same; the pulse then only
 *   finds natural sculk to slow or regenerate on, and the
 *   world is left exactly as it was.
 *
 * WHAT IT LOOKS LIKE: a fist slam, then teal-black sculk
 * visibly creeping outward across the floor in three waves,
 * SCULK_CHARGE_POP sparks at each fresh patch, SCULK_SOUL
 * wisps curling up from the carpet, and enemies picking
 * their way across it at half pace while the warden's wounds
 * knit on its home ground.
 *
 * PERFORMANCE: all block work happens on the 3 growth ticks
 * (at most 169 columns per tick, each at most 7 block reads,
 * conversions capped at 90); the zone is one bounded query
 * every 10 ticks plus per-victim block reads for the handful
 * inside. Particle budget per activation: 12 slam sparks
 * plus at most 90 conversion pops plus 40 pulses times 2
 * wisps = at most 182.
 *
 * CLEANUP: end() removes the REGENERATION effect from the
 * warden (an accepted edge case shared with every
 * fixed-duration effect in this system: a warden that
 * somehow had regeneration from another source loses it
 * here; a permanent regeneration upgrade is re-applied by
 * ScalingSystem, the Living Stone Shell precedent). The
 * bloom blocks, when placed, are permanent terrain and are
 * deliberately NOT touched.
 *
 * State tag keys: "cx", "cy", "cz" (the bloom center, the
 * warden's feet at start), "placed" (conversions so far).
 */
public final class SculkBloomAbility implements MobAbility {

    static final int DURATION_TICKS = 400;
    private static final int[] GROWTH_TICKS = {5, 15, 25};
    private static final double[] GROWTH_RADII = {2.5, 4.5, 6.5};
    private static final int CONVERSION_CAP = 90;
    private static final int PULSE_INTERVAL = 10;
    private static final double ZONE_RADIUS = 7.0;

    @Override
    public String id() {
        return "sculk_bloom";
    }

    @Override
    public String displayName() {
        return "Sculk Bloom";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().onGround()
            && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        state.putDouble("cx", mob.getX());
        state.putDouble("cy", mob.getY());
        state.putDouble("cz", mob.getZ());
        state.putInt("placed", 0);

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.WARDEN_AGITATED,
                SoundSource.HOSTILE, 2.0f, 0.8f);
            level.sendParticles(ParticleTypes.SCULK_CHARGE_POP, mob.getX(), mob.getY() + 0.3, mob.getZ(),
                12, 1.2, 0.3, 1.2, 0.1);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        for (int step = 0; step < GROWTH_TICKS.length; step++) {
            if (ticks == GROWTH_TICKS[step]) {
                growRing(ctx, step);
                break;
            }
        }
        if (ticks % PULSE_INTERVAL == 0) {
            pulseZone(ctx);
        }
    }

    /** One growth wave: convert the columns in this ring band, mobGriefing mode only. */
    private void growRing(AbilityContext ctx, int step) {
        ServerLevel level = ctx.serverLevel();
        if (level == null || !ctx.mobGriefingAllowed()) {
            return; // Terrain rule: with mobGriefing off, no block changes at all.
        }
        CompoundTag state = ctx.state();
        double cx = state.getDoubleOr("cx", ctx.mob().getX());
        double cy = state.getDoubleOr("cy", ctx.mob().getY());
        double cz = state.getDoubleOr("cz", ctx.mob().getZ());
        double outer = GROWTH_RADII[step];
        double inner = step == 0 ? -1.0 : GROWTH_RADII[step - 1];
        int placed = state.getIntOr("placed", 0);
        int centerBlockX = (int) Math.floor(cx);
        int centerBlockY = (int) Math.floor(cy);
        int centerBlockZ = (int) Math.floor(cz);
        int reach = (int) Math.ceil(outer);

        BlockState veinState = WardenAbilitySupport.floorVeinState();
        for (int bx = centerBlockX - reach; bx <= centerBlockX + reach && placed < CONVERSION_CAP; bx++) {
            for (int bz = centerBlockZ - reach; bz <= centerBlockZ + reach && placed < CONVERSION_CAP; bz++) {
                double columnDistance = Math.sqrt(
                    (bx + 0.5 - cx) * (bx + 0.5 - cx) + (bz + 0.5 - cz) * (bz + 0.5 - cz));
                if (columnDistance > outer || columnDistance <= inner) {
                    continue;
                }
                // Find the surface: highest dry, non-air block
                // in the scan range with air above it.
                BlockPos surface = null;
                for (int dy = 2; dy >= -4; dy--) {
                    BlockPos pos = new BlockPos(bx, centerBlockY + dy, bz);
                    BlockState candidate = level.getBlockState(pos);
                    if (candidate.isAir() || !candidate.getFluidState().isEmpty()) {
                        continue;
                    }
                    if (!level.getBlockState(pos.above()).isAir()) {
                        continue;
                    }
                    surface = pos;
                    break;
                }
                if (surface == null) {
                    continue;
                }
                boolean converted = false;
                if (columnDistance <= 0.8) {
                    // The heart of the bloom: one catalyst.
                    if (WardenAbilitySupport.isDiggable(level, surface)
                        && !level.getBlockState(surface).is(Blocks.SCULK_CATALYST)) {
                        level.setBlock(surface, Blocks.SCULK_CATALYST.defaultBlockState(), Block.UPDATE_ALL);
                        converted = true;
                    }
                } else if (ctx.random().nextInt(5) == 0) {
                    // A vein creeping over the surface instead
                    // of a full conversion.
                    BlockPos above = surface.above();
                    if (level.getBlockState(above).isAir()
                        && WardenAbilitySupport.isDiggable(level, surface)) {
                        level.setBlock(above, veinState, Block.UPDATE_ALL);
                        converted = true;
                    }
                } else if (WardenAbilitySupport.isDiggable(level, surface)
                    && !level.getBlockState(surface).is(Blocks.SCULK)) {
                    level.setBlock(surface, Blocks.SCULK.defaultBlockState(), Block.UPDATE_ALL);
                    converted = true;
                }
                if (converted) {
                    placed++;
                    level.sendParticles(ParticleTypes.SCULK_CHARGE_POP,
                        surface.getX() + 0.5, surface.getY() + 1.1, surface.getZ() + 0.5,
                        1, 0.2, 0.1, 0.2, 0.02);
                }
            }
        }
        state.putInt("placed", placed);
    }

    /** One zone pulse: slow enemies on the bloom, regenerate the warden on it, drift wisps. */
    private void pulseZone(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        double cx = state.getDoubleOr("cx", ctx.mob().getX());
        double cy = state.getDoubleOr("cy", ctx.mob().getY());
        double cz = state.getDoubleOr("cz", ctx.mob().getZ());

        List<LivingEntity> victims = WardenAbilitySupport.enemiesInBox(ctx, cx, cy, cz, ZONE_RADIUS, 3.0, 3.0);
        for (LivingEntity victim : victims) {
            BlockState below = level.getBlockState(victim.blockPosition().below());
            if (WardenAbilitySupport.isSculkFamily(below)) {
                victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 30, 0));
            }
        }

        Mob mob = ctx.mob();
        BlockState ownGround = level.getBlockState(mob.blockPosition().below());
        if (WardenAbilitySupport.isSculkFamily(ownGround)) {
            mob.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 30, 0));
        }

        for (int i = 0; i < 2; i++) {
            double angle = ctx.random().nextDouble() * Math.PI * 2.0;
            double radius = ctx.random().nextDouble() * 6.0;
            level.sendParticles(ParticleTypes.SCULK_SOUL,
                cx + Math.cos(angle) * radius, cy + 0.4, cz + Math.sin(angle) * radius,
                1, 0.0, 0.15, 0.0, 0.02);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // The bloom stays (permanent terrain). Only the
        // warden's own regeneration is the ability's to end.
        ctx.mob().removeEffect(MobEffects.REGENERATION);
    }
}
