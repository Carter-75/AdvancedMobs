package mod.advancedmobs.ability.enderman;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.UUID;

/**
 * Dimensional Anchor (Enderman ability 5 of 5, mobility).
 *
 * WHAT IT DOES: the enderman plants an anchor in the world,
 * an end stone monolith crowned with light and a column of
 * PORTAL motes, marking a point it owns. It fights on from
 * there, and when the fight turns against it, or when the
 * anchor's charge runs out, it snaps back across the
 * battlefield to its chosen ground in a burst of
 * REVERSE_PORTAL and strides back in from where it wants to
 * be.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists within 24 blocks
 *   (distance squared 576). The anchor is planted as a fight
 *   opens, on ground the enderman has chosen by standing on
 *   it.
 * - The anchor (start()): the enderman's position at start is
 *   recorded in the state tag (the design's "/tag state
 *   marking": the anchor point is marked in the mob's own
 *   persisted activation state, which is what the snap reads;
 *   no scoreboard tag is needed). The monolith is an end stone
 *   block display, uniform scale 1.3, at full brightness (set
 *   by the display helper), centered 0.65 above the anchor
 *   point so its base sits on the ground. It is a display,
 *   not a placed block: it changes no terrain, blocks
 *   nothing, and vanishes when the ability ends.
 * - While anchored: every 10 ticks, 2 END_ROD motes crown the
 *   monolith (the design's END_ROD beacon), and every 20
 *   ticks 3 PORTAL motes rise in a slow column over it. The
 *   enderman itself fights entirely normally: nothing holds
 *   it, slows it, or protects it while the anchor waits.
 * - The snap: the enderman returns to the anchor at the first
 *   of these moments: (a) its health drops below 40 percent
 *   of its maximum, checked every 10 ticks, the retreat of a
 *   losing fight; or (b) tick 160 (8 seconds) passes, the
 *   anchor's charge running out. The landing is the first
 *   collision-free spot among the anchor point itself and one
 *   block above it (the enderman's bounding box must collide
 *   with nothing there); if neither is free, the enderman
 *   stays where it is and the snap is spent anyway. The snap
 *   bursts 14 REVERSE_PORTAL particles at the departure point
 *   and 14 more at the arrival point, plays ENDERMAN_TELEPORT
 *   (volume 1.0, pitch 0.8) at the arrival, stops the
 *   enderman's navigation, and re-orders it onto its live
 *   target if it still has one, so it strides back into the
 *   fight from its chosen ground instead of standing confused
 *   on it.
 * - The ability finishes 10 ticks after the snap, or at tick
 *   220 at the latest (a hard finish in case a resumed
 *   activation's snap state is somehow inconsistent; the snap
 *   itself is guaranteed by the tick 160 rule).
 *
 * TERRAIN RULE: this ability changes NO real blocks (the
 * monolith is a display and the snap is a teleport of the
 * caster itself, not a block change), so the mobGriefing gate
 * does not apply. The enderman's vanilla carried block, if it
 * is holding one, is untouched by this ability. See
 * IMPLEMENTATION-RULES.md rule 6.
 *
 * WHAT IT LOOKS AND SOUNDS LIKE: an ENDERMAN_TELEPORT plant
 * (volume 0.8, pitch 0.7) and 8 END_ROD motes as the monolith
 * rises; a glowing end stone pillar with a crown of light and
 * a slow column of PORTAL motes for up to 8 seconds; then the
 * enderman vanishes from wherever it was losing and reappears
 * on its anchor in twin bursts of REVERSE_PORTAL.
 *
 * PERFORMANCE: tick() early-outs completely once the snap has
 * fired. Before the snap, the per-tick work is nothing except
 * every 10th tick (2 particles and one health read) and every
 * 20th tick (3 particles): no entity scans, no UUID resolves,
 * no allocations in the tick path. The snap itself is at most
 * 2 bounded collision checks and one teleport, once. Particle
 * budget per activation: 8 at start, END_ROD at most 16
 * pulses times 2 = 32, PORTAL at most 8 pulses times 3 = 24,
 * the snap 28, total at most 92 particles. The monolith is
 * the only extra entity, alive only for the ability.
 *
 * CLEANUP: end() discards the monolith display if it still
 * resolves. If the caster dies while anchored, the framework
 * termination path runs this same end(), so the monolith
 * never outlives the ability or its caster.
 *
 * State tag keys: "ax", "ay", "az" (the anchor point,
 * doubles), "display" (the monolith display's UUID),
 * "snapped" (whether the snap already fired), "snap_tick"
 * (the tick the snap fired).
 */
public final class DimensionalAnchorAbility implements MobAbility {

    static final int SNAP_TICK = 160;
    private static final int FINISH_AFTER_SNAP = 10;
    private static final int HARD_FINISH_TICKS = 220;
    private static final double SNAP_HEALTH_FRACTION = 0.4;
    private static final float ANCHOR_SCALE = 1.3f;

    @Override
    public String id() {
        return "dimensional_anchor";
    }

    @Override
    public String displayName() {
        return "Dimensional Anchor";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 576.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();

        // The anchor point: where the enderman stands now,
        // marked in its own activation state.
        state.putDouble("ax", mob.getX());
        state.putDouble("ay", mob.getY());
        state.putDouble("az", mob.getZ());
        state.putBoolean("snapped", false);
        state.putInt("snap_tick", -1);

        if (level == null) {
            return;
        }

        // The monolith: an end stone display on the anchor point.
        Entity monolith = EndermanAbilitySupport.createBlockDisplay(level,
            Blocks.END_STONE.defaultBlockState(), ANCHOR_SCALE, ANCHOR_SCALE, ANCHOR_SCALE, true,
            0.0f, 0.0f, 0.0f, 1.0f);
        if (monolith != null) {
            monolith.snapTo(mob.getX(), mob.getY() + ANCHOR_SCALE / 2.0, mob.getZ(), mob.getYRot(), 0.0f);
            level.addFreshEntity(monolith);
            state.store("display", UUIDUtil.CODEC, monolith.getUUID());
        }
        level.sendParticles(ParticleTypes.END_ROD, mob.getX(), mob.getY() + 1.6, mob.getZ(),
            8, 0.5, 0.4, 0.5, 0.05);
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
            SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 0.8f, 0.7f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        if (state.getBooleanOr("snapped", false)) {
            return; // Post-snap: nothing to do until the finish tick.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        double ax = state.getDoubleOr("ax", ctx.mob().getX());
        double ay = state.getDoubleOr("ay", ctx.mob().getY());
        double az = state.getDoubleOr("az", ctx.mob().getZ());

        if (ticks % 20 == 0) {
            // The column: PORTAL motes rising slowly over the monolith.
            level.sendParticles(ParticleTypes.PORTAL, ax, ay + 1.4, az,
                3, 0.3, 0.8, 0.3, 0.03);
        }
        if (ticks % 10 == 0) {
            // The crown of light.
            level.sendParticles(ParticleTypes.END_ROD, ax, ay + 1.6, az,
                2, 0.4, 0.2, 0.4, 0.02);
            // The retreat check: a losing fight snaps home early.
            Mob mob = ctx.mob();
            if (mob.getHealth() < mob.getMaxHealth() * SNAP_HEALTH_FRACTION) {
                snap(ctx, level);
                return;
            }
        }
        if (ticks >= SNAP_TICK) {
            snap(ctx, level);
        }
    }

    /** The snap: burst at the departure point, teleport to the anchor, burst at the arrival. */
    private void snap(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        Mob mob = ctx.mob();
        double ax = state.getDoubleOr("ax", mob.getX());
        double ay = state.getDoubleOr("ay", mob.getY());
        double az = state.getDoubleOr("az", mob.getZ());

        // Departure burst, where the enderman was losing.
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, mob.getX(), mob.getY() + 1.5, mob.getZ(),
            14, 0.6, 1.0, 0.6, 0.15);

        // The landing: the anchor point, or one block above it,
        // whichever first fits the enderman's body.
        double destY = Double.NaN;
        if (isFreeSpot(level, mob, ax, ay, az)) {
            destY = ay;
        } else if (isFreeSpot(level, mob, ax, ay + 1.0, az)) {
            destY = ay + 1.0;
        }
        if (!Double.isNaN(destY)) {
            mob.teleportTo(ax, destY, az);
        }
        mob.getNavigation().stop();
        LivingEntity target = ctx.target();
        if (target != null) {
            mob.setTarget(target);
        }

        // Arrival burst, on its chosen ground.
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, ax, ay + 1.5, az,
            14, 0.6, 1.0, 0.6, 0.15);
        level.playSound(null, ax, ay, az,
            SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0f, 0.8f);

        state.putBoolean("snapped", true);
        state.putInt("snap_tick", ctx.ticksActive());
    }

    /** Whether the enderman's bounding box at the given feet position collides with nothing. */
    private boolean isFreeSpot(ServerLevel level, Mob mob, double x, double y, double z) {
        double halfWidth = mob.getBbWidth() / 2.0;
        AABB box = new AABB(x - halfWidth, y, z - halfWidth, x + halfWidth, y + mob.getBbHeight(), z + halfWidth);
        return level.noCollision(mob, box);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        if (state.getBooleanOr("snapped", false)
            && ctx.ticksActive() >= state.getIntOr("snap_tick", 0) + FINISH_AFTER_SNAP) {
            return true;
        }
        return ctx.ticksActive() >= HARD_FINISH_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null || !ctx.state().contains("display")) {
            return;
        }
        UUID displayId = ctx.state().read("display", UUIDUtil.CODEC).orElse(null);
        if (displayId != null) {
            Entity monolith = level.getEntity(displayId);
            if (monolith != null) {
                monolith.discard();
            }
        }
    }
}
