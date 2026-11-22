package mod.advancedmobs.ability.ghast;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Ember Wake Descent (Ghast ability 5 of 5, mobility attack).
 *
 * WHAT IT DOES: the ghast folds out of its high drift and
 * dives across the arena at rooftop height, trailing a wake
 * of flame and smoke and dropping fire charges as it goes.
 * Where each charge lands, fire takes hold on the ground, so
 * the pass draws a burning line across the arena that cuts it
 * in two: enemies cross the flames or bunch on one side. At
 * the end of the pass the ghast climbs back to its home
 * altitude on a burst of speed.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists within 24 blocks
 *   (distance squared 576).
 * - The dive plan (start()): the ghast's home position (where
 *   it started the pass) and the dive waypoint are recorded
 *   in the state tag. The waypoint is the target's position
 *   at cast time, at 2.0 blocks above its ground surface (the
 *   shared surface scan anchored on the target's block
 *   height). The plan is fixed at cast: the pass crosses
 *   where the fight was, which is what lays the line across
 *   the arena rather than chasing one enemy.
 * - The dive (tick(), phase 0): the ghast's velocity is set
 *   outright each tick to the normalized direction to the
 *   waypoint times 1.1 blocks per tick (about 22 blocks a
 *   second, a fast pass). The phase ends when the ghast is
 *   within 2.5 blocks of the waypoint or at tick 50, whichever
 *   comes first. No NoAi is used; the velocity writes are the
 *   steering, per the standing contract.
 * - The wake (every dive tick): 2 FLAME and 1 SMOKE particles
 *   at the ghast's position, half a block below its body.
 * - The fire charges (ticks 10, 20, 30, and 40 of the dive):
 *   one SmallFireball per drop, spawned at the ghast's belly
 *   with the ghast as owner, velocity straight down at 1.2
 *   blocks per tick and accelerationPower zeroed (the
 *   Cinder Meteor Volley pattern), re-held straight down by
 *   the tracker each tick. Vanilla behavior on the way down
 *   is part of the design: a charge that strikes an enemy
 *   deals its vanilla 5.0 fireball damage and lights it for
 *   5 seconds, and one that strikes a block with mobGriefing
 *   on sets that block on fire (vanilla checks the rule
 *   itself).
 * - The burning line (the tracker, every tick): each live
 *   charge's position is recorded into the state tag. When a
 *   charge is gone (landed, hit, or discarded by its 40-tick
 *   timeout in the tracker), its line segment is laid once at
 *   its last recorded position: the ground surface is found
 *   by the shared scan anchored on the last height, and up to
 *   2 fire blocks are placed (the surface spot, then one
 *   random offset inside radius 1.0) through the shared
 *   placeFire safety check, only when the terrain rule allows
 *   (see the TERRAIN RULE). With mobGriefing off, no line is
 *   laid and the pass is wake and charge hits only.
 * - The climb (tick(), phase 1): on entering the phase the
 *   ghast gains FLYING_SPEED add_multiplied_base +0.5 (id
 *   advancedmobs:ember_wake_descent_speed, removed in end();
 *   the shared helper tolerates a missing attribute instance)
 *   and its velocity is set each tick toward home at 1.2
 *   blocks per tick. The ability finishes when the ghast is
 *   within 3.0 blocks of home, with a hard finish at 110
 *   ticks overall.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6): the burning line is
 * PERMANENT terrain, gated by
 * AbilityContext.mobGriefingAllowed() at the moment each
 * segment is laid. The dropped charges' own block ignition is
 * vanilla SmallFireball behavior, which checks the same rule
 * itself. Nothing here ever overwrites a container or an
 * unbreakable block: placeFire refuses block entities and
 * requires dry, replaceable ground with a sturdy block
 * beneath.
 *
 * WHAT IT LOOKS LIKE: a ghast suddenly swooping low across
 * the fight on a ribbon of flame and smoke, fire charges
 * raining off its belly, a line of real fire kindling along
 * the ground behind it, then the ghast hauling back up to its
 * drift height and resuming the fight from above.
 *
 * PERFORMANCE: the dive and climb are one velocity write per
 * tick; the wake is 3 particles per dive tick; the tracker
 * resolves at most 4 charge UUIDs per tick. The only block
 * work is one surface scan (at most 19 reads) per landed
 * charge, 4 per activation. Particle budget per activation:
 * wake at most 50 dive ticks times 3 = 150, charge trails
 * none (the charges are fireballs, self-lit), line bursts
 * none, total at most 150 particles plus the charges' own
 * vanilla trails.
 *
 * CLEANUP: end() discards any charge still in flight (with
 * no line segment: the payload dies with the activation) and
 * removes the FLYING_SPEED modifier. Fire already laid is
 * permanent terrain and is deliberately NOT touched.
 *
 * State tag keys: "hx", "hy", "hz" (home, doubles), "wx",
 * "wy", "wz" (the dive waypoint, doubles), "phase" (0 dive,
 * 1 climb), "boosted" (1 once the climb modifier is on),
 * "charges" (list of compounds, one per dropped charge: "id"
 * the fireball's UUID, "x", "y", "z" its last recorded
 * position, "born" the tick it was dropped, "laid" 1 once
 * its line segment ran).
 */
public final class EmberWakeDescentAbility implements MobAbility {

    private static final int DIVE_TIMEOUT_TICKS = 50;
    private static final int HARD_FINISH_TICKS = 110;
    private static final int CHARGE_TIMEOUT_TICKS = 40;
    private static final int[] DROP_TICKS = {10, 20, 30, 40};
    private static final Identifier SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "ember_wake_descent_speed");

    @Override
    public String id() {
        return "ember_wake_descent";
    }

    @Override
    public String displayName() {
        return "Ember Wake Descent";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return ctx.target() != null && ctx.mob().distanceToSqr(ctx.target()) <= 576.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        Mob mob = ctx.mob();
        state.putDouble("hx", mob.getX());
        state.putDouble("hy", mob.getY());
        state.putDouble("hz", mob.getZ());
        state.putInt("phase", 0);
        state.putInt("boosted", 0);
        state.put("charges", new ListTag());
        LivingEntity target = ctx.target();
        double wx = target != null ? target.getX() : mob.getX();
        double wz = target != null ? target.getZ() : mob.getZ();
        int refY = target != null ? target.getBlockY() : mob.getBlockY();
        double wy = refY;
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            wy = GhastAbilitySupport.findSurfaceY(level, (int) Math.floor(wx), (int) Math.floor(wz), refY) + 2.0;
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.GHAST_SCREAM,
                SoundSource.HOSTILE, 1.0f, 1.1f);
        }
        state.putDouble("wx", wx);
        state.putDouble("wy", wy);
        state.putDouble("wz", wz);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        CompoundTag state = ctx.state();
        Mob mob = ctx.mob();
        int phase = state.getIntOr("phase", 0);

        if (phase == 0) {
            Vec3 waypoint = new Vec3(state.getDoubleOr("wx", mob.getX()),
                state.getDoubleOr("wy", mob.getY()), state.getDoubleOr("wz", mob.getZ()));
            Vec3 to = waypoint.subtract(mob.position());
            if (to.length() <= 2.5 || ticks >= DIVE_TIMEOUT_TICKS) {
                state.putInt("phase", 1);
            } else {
                mob.setDeltaMovement(to.normalize().scale(1.1));
                // The wake (see the class comment).
                level.sendParticles(ParticleTypes.FLAME, mob.getX(), mob.getY() - 0.5, mob.getZ(),
                    2, mob.getBbWidth() * 0.4, 0.3, mob.getBbWidth() * 0.4, 0.03);
                level.sendParticles(ParticleTypes.SMOKE, mob.getX(), mob.getY() - 0.5, mob.getZ(),
                    1, mob.getBbWidth() * 0.4, 0.3, mob.getBbWidth() * 0.4, 0.02);
                for (int dropTick : DROP_TICKS) {
                    if (ticks == dropTick) {
                        dropCharge(ctx, level);
                    }
                }
            }
        } else {
            if (state.getIntOr("boosted", 0) == 0) {
                GhastAbilitySupport.addModifier(mob, Attributes.FLYING_SPEED,
                    new AttributeModifier(SPEED_ID, 0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
                state.putInt("boosted", 1);
            }
            Vec3 home = new Vec3(state.getDoubleOr("hx", mob.getX()),
                state.getDoubleOr("hy", mob.getY()), state.getDoubleOr("hz", mob.getZ()));
            Vec3 to = home.subtract(mob.position());
            if (to.lengthSqr() > 1.0e-4) {
                mob.setDeltaMovement(to.normalize().scale(1.2));
            }
        }

        trackCharges(ctx, level);
    }

    /** Drops one fire charge off the ghast's belly (see the class comment). */
    private static void dropCharge(AbilityContext ctx, ServerLevel level) {
        Mob mob = ctx.mob();
        SmallFireball charge = new SmallFireball(level, mob, new Vec3(0.0, -1.2, 0.0));
        charge.snapTo(mob.getX(), mob.getY() - 0.5, mob.getZ(), 0.0f, 0.0f);
        charge.accelerationPower = 0.0;
        if (!level.addFreshEntity(charge)) {
            return;
        }
        CompoundTag entry = new CompoundTag();
        entry.store("id", UUIDUtil.CODEC, charge.getUUID());
        entry.putDouble("x", charge.getX());
        entry.putDouble("y", charge.getY());
        entry.putDouble("z", charge.getZ());
        entry.putInt("born", ctx.ticksActive());
        entry.putInt("laid", 0);
        ListTag charges = ctx.state().getListOrEmpty("charges");
        charges.add(entry);
        ctx.state().put("charges", charges);
    }

    /**
     * Tracks the dropped charges: holds their fall straight
     * down, records their positions, and lays the line segment
     * for each one that lands (see the class comment).
     */
    private static void trackCharges(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        ListTag charges = state.getListOrEmpty("charges");
        if (charges.isEmpty()) {
            return;
        }
        int ticks = ctx.ticksActive();
        for (int i = 0; i < charges.size(); i++) {
            CompoundTag entry = charges.getCompoundOrEmpty(i);
            if (entry.getIntOr("laid", 0) == 1) {
                continue;
            }
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            Entity charge = id != null ? level.getEntity(id) : null;
            if (charge != null && ticks - entry.getIntOr("born", ticks) < CHARGE_TIMEOUT_TICKS) {
                charge.setDeltaMovement(0.0, -1.2, 0.0);
                entry.putDouble("x", charge.getX());
                entry.putDouble("y", charge.getY());
                entry.putDouble("z", charge.getZ());
                charges.set(i, entry);
                continue;
            }
            if (charge != null) {
                charge.discard();
            }
            layLineSegment(ctx, level, entry);
            entry.putInt("laid", 1);
            charges.set(i, entry);
        }
        state.put("charges", charges);
    }

    /** Lays one landed charge's fire segment (see the TERRAIN RULE in the class comment). */
    private static void layLineSegment(AbilityContext ctx, ServerLevel level, CompoundTag entry) {
        if (!ctx.mobGriefingAllowed()) {
            return;
        }
        double x = entry.getDoubleOr("x", ctx.mob().getX());
        double y = entry.getDoubleOr("y", ctx.mob().getY());
        double z = entry.getDoubleOr("z", ctx.mob().getZ());
        int surfaceY = GhastAbilitySupport.findSurfaceY(level, (int) Math.floor(x), (int) Math.floor(z), (int) Math.floor(y));
        int placed = 0;
        if (GhastAbilitySupport.placeFire(level, new BlockPos((int) Math.floor(x), surfaceY, (int) Math.floor(z)))) {
            placed++;
        }
        for (int attempt = 0; attempt < 4 && placed < 2; attempt++) {
            double ox = ctx.random().nextDouble() * 2.0 - 1.0;
            double oz = ctx.random().nextDouble() * 2.0 - 1.0;
            if (GhastAbilitySupport.placeFire(level,
                    new BlockPos((int) Math.floor(x + ox), surfaceY, (int) Math.floor(z + oz)))) {
                placed++;
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        if (ctx.ticksActive() >= HARD_FINISH_TICKS) {
            return true;
        }
        CompoundTag state = ctx.state();
        if (state.getIntOr("phase", 0) != 1) {
            return false;
        }
        Mob mob = ctx.mob();
        double dx = mob.getX() - state.getDoubleOr("hx", mob.getX());
        double dy = mob.getY() - state.getDoubleOr("hy", mob.getY());
        double dz = mob.getZ() - state.getDoubleOr("hz", mob.getZ());
        return dx * dx + dy * dy + dz * dz <= 9.0;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        GhastAbilitySupport.removeModifier(mob, Attributes.FLYING_SPEED, SPEED_ID);
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        // Charges still in flight are discarded with no line
        // segment: the payload dies with the activation (see
        // the class comment). Fire already laid is permanent
        // terrain and is deliberately NOT touched.
        ListTag charges = ctx.state().getListOrEmpty("charges");
        for (int i = 0; i < charges.size(); i++) {
            CompoundTag entry = charges.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            Entity charge = id != null ? level.getEntity(id) : null;
            if (charge != null) {
                charge.discard();
            }
        }
    }
}
