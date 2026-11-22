package mod.advancedmobs.ability.fox;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Berry Bombardier (Fox ability 1 of 5, attack).
 *
 * WHAT IT DOES: the fox shakes out the berry bush it has
 * been hoarding and lobs clusters of sweet berries in
 * high arcs at its target. Each cluster bursts in a spray
 * of red berries where it lands, hurting whatever it
 * catches and leaving them sticky and slowed in the
 * juice.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the fox's current target within 16 blocks
 *   (distance squared 256).
 * - The telegraph (ticks 1 to 5): the fox holds position
 *   through navigation stops (never NoAi) and shakes:
 *   2 sweet berry ITEM particles dribble at its mouth on
 *   ticks 2 and 4. The target's live position is recorded
 *   every tick it lives (state tag tx/ty/tz) so later
 *   shots still aim at the last known spot if it dies.
 * - The volley (ticks 6, 10, 14, 18): one berry cluster
 *   per shot tick, 4 clusters total. A cluster is a real
 *   vanilla Snowball projectile created from EntityTypes.
 *   SNOWBALL and owned by the fox through Projectile.
 *   setOwner (the Cud Cannon pattern): a snowball is the
 *   carrier because it flies a clean throwable arc and
 *   its own contact hit is harmless, so every point of
 *   damage comes from the burst below, in code, exactly
 *   as the design's "/summon projectile in code with
 *   berry visuals" line intends. The berry look rides on
 *   top: 1 sweet berry ITEM particle trails the cluster
 *   every 2 ticks while it flies. Each cluster starts at
 *   the fox's eye position pushed 0.7 blocks along its
 *   look direction and is shot at speed 1.5 with
 *   inaccuracy 2.0 toward an aim point above the target:
 *   the recorded target chest position raised by 0.25
 *   times the horizontal distance, which gives the lob
 *   its high arc. FOX_SPIT plays per shot at volume 1.0,
 *   pitch 1.1 plus 0.05 per shot index. Each cluster's
 *   UUID and last known position are tracked in the state
 *   tag.
 * - The burst (tick(), every tick): each tracked cluster
 *   that is still alive refreshes its recorded position;
 *   a cluster that has resolved (hit something or
 *   expired) bursts once at its last recorded position:
 *   10 sweet berry ITEM particles and 4 SPLASH particles,
 *   and every enemy within 2.0 blocks (vertical reach
 *   1.5) takes 3.0 damage through the package's THROWN
 *   typed damage source credited to the fox (the design's
 *   "/damage typed as projectile") and gains SLOWNESS I
 *   for 100 ticks, the sticky juice. Resolved clusters
 *   are pruned from the tracking list in the same pass.
 * - Duration: finishes at tick 30 at the earliest once
 *   all 4 shots are fired and every cluster has burst;
 *   hard finish at tick 100 whatever is still flying (an
 *   unresolved cluster then bursts nowhere: it is simply
 *   dropped from tracking, and end() needs no cleanup
 *   because the effects live on their own clocks).
 *
 * WHAT IT LOOKS LIKE: a fox shaking berries loose in a
 * red dribble, then four dark red clusters sailing up and
 * over in a quick rhythm, each one popping in a spray of
 * berries and juice that leaves its victims dragging
 * their feet.
 *
 * PERFORMANCE: the telegraph is one navigation stop plus
 * 2 particles on 2 ticks; a shot tick is one spawn and
 * one sound; the burst pass resolves at most 4 UUIDs per
 * tick, trails at most 4 particles every 2 ticks, and
 * runs one bounded radius-2.0 query only on a tick where
 * a cluster actually burst. Particle budget per
 * activation: telegraph 4, trails at most 4 times 20 =
 * 80, bursts at most 4 times 14 = 56, total at most 140
 * particles.
 *
 * TERRAIN: this ability changes no blocks (the clusters
 * are vanilla projectiles whose hits never alter
 * blocks); the mobGriefing gate does not apply.
 *
 * State tag keys: "shots" (clusters fired so far),
 * "tx"/"ty"/"tz" (last known target position),
 * "clusters" (list of compounds, one per live cluster:
 * "id", "x", "y", "z").
 */
public final class BerryBombardierAbility implements MobAbility {

    private static final int TELEGRAPH_TICKS = 5;
    private static final int FIRST_SHOT_TICK = 6;
    private static final int SHOT_INTERVAL = 4;
    private static final int SHOT_COUNT = 4;
    private static final int EARLY_FINISH_TICK = 30;
    private static final int HARD_FINISH_TICK = 100;
    private static final double CLUSTER_SPEED = 1.5;
    private static final double ARC_LIFT_PER_BLOCK = 0.25;
    private static final double BURST_RADIUS = 2.0;
    private static final double BURST_DAMAGE = 3.0;
    private static final int SLOWNESS_TICKS = 100;

    @Override
    public String id() {
        return "berry_bombardier";
    }

    @Override
    public String displayName() {
        return "Berry Bombardier";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().putInt("shots", 0);
        ctx.state().put("clusters", new ListTag());
        LivingEntity target = ctx.target();
        if (target != null) {
            recordTarget(ctx, target);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        int ticks = ctx.ticksActive();

        LivingEntity target = ctx.target();
        if (target != null) {
            recordTarget(ctx, target);
        }

        if (ticks <= TELEGRAPH_TICKS) {
            // The shake: hold position through navigation, never NoAi.
            caster.getNavigation().stop();
            if (ticks % 2 == 0) {
                Vec3 look = caster.getLookAngle();
                level.sendParticles(FoxAbilitySupport.BERRY,
                    caster.getX() + look.x * 0.7, caster.getEyeY() - 0.2, caster.getZ() + look.z * 0.7,
                    2, 0.08, 0.08, 0.08, 0.02);
            }
            return;
        }

        int shots = ctx.state().getIntOr("shots", 0);
        if (shots < SHOT_COUNT && ticks >= FIRST_SHOT_TICK
            && (ticks - FIRST_SHOT_TICK) % SHOT_INTERVAL == 0) {
            fireCluster(ctx, level, shots);
            ctx.state().putInt("shots", shots + 1);
        }

        trackClusters(ctx, level, ticks);
    }

    /** Fires one berry cluster on its high arc (see the class comment). */
    private void fireCluster(AbilityContext ctx, ServerLevel level, int shotIndex) {
        Mob caster = ctx.mob();
        double tx = ctx.state().getDoubleOr("tx", caster.getX());
        double ty = ctx.state().getDoubleOr("ty", caster.getEyeY());
        double tz = ctx.state().getDoubleOr("tz", caster.getZ());

        Vec3 origin = caster.getEyePosition().add(caster.getLookAngle().scale(0.7));
        double dx = tx - origin.x;
        double dz = tz - origin.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        // Aim above the target: the lift is what arcs the cluster over cover.
        double dy = (ty + 0.5 + horizontal * ARC_LIFT_PER_BLOCK) - origin.y;

        Snowball cluster = EntityTypes.SNOWBALL.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (cluster == null) {
            return;
        }
        cluster.setOwner(caster);
        cluster.setPos(origin.x, origin.y, origin.z);
        cluster.shoot(dx, dy, dz, (float) CLUSTER_SPEED, 2.0f);
        if (level.addFreshEntity(cluster)) {
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, cluster.getUUID());
            entry.putDouble("x", cluster.getX());
            entry.putDouble("y", cluster.getY());
            entry.putDouble("z", cluster.getZ());
            ListTag clusters = ctx.state().getListOrEmpty("clusters");
            clusters.add(entry);
            ctx.state().put("clusters", clusters);
        }
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.FOX_SPIT,
            SoundSource.HOSTILE, 1.0f, 1.1f + 0.05f * shotIndex);
    }

    /**
     * Refreshes live clusters' recorded positions, trails
     * them with berry particles, and bursts the ones that
     * resolved since the last tick (see the class comment).
     * Resolved clusters are pruned in the pass.
     */
    private void trackClusters(AbilityContext ctx, ServerLevel level, int ticks) {
        ListTag tracked = ctx.state().getListOrEmpty("clusters");
        if (tracked.isEmpty()) {
            return;
        }
        ListTag remaining = new ListTag();
        for (int i = 0; i < tracked.size(); i++) {
            CompoundTag entry = tracked.getCompoundOrEmpty(i);
            Entity entity = FoxAbilitySupport.resolveTracked(level, entry);
            if (entity != null && entity.isAlive()) {
                entry.putDouble("x", entity.getX());
                entry.putDouble("y", entity.getY());
                entry.putDouble("z", entity.getZ());
                if (ticks % 2 == 0) {
                    level.sendParticles(FoxAbilitySupport.BERRY, entity.getX(), entity.getY(), entity.getZ(),
                        1, 0.05, 0.05, 0.05, 0.01);
                }
                remaining.add(entry);
                continue;
            }
            // Resolved: the burst lands at its last known position.
            double x = entry.getDoubleOr("x", 0.0);
            double y = entry.getDoubleOr("y", 0.0);
            double z = entry.getDoubleOr("z", 0.0);
            level.sendParticles(FoxAbilitySupport.BERRY, x, y, z, 10, 0.35, 0.3, 0.35, 0.08);
            level.sendParticles(ParticleTypes.SPLASH, x, y, z, 4, 0.3, 0.2, 0.3, 0.05);
            List<LivingEntity> caught = FoxAbilitySupport.enemiesInBox(ctx, x, y, z,
                BURST_RADIUS, 1.5, 1.5);
            for (LivingEntity victim : caught) {
                victim.hurtServer(level, FoxAbilitySupport.thrownSource(ctx), (float) BURST_DAMAGE);
                victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, SLOWNESS_TICKS, 0));
            }
        }
        ctx.state().put("clusters", remaining);
    }

    /** Records the target's live position for aiming after it dies or is lost. */
    private void recordTarget(AbilityContext ctx, LivingEntity target) {
        ctx.state().putDouble("tx", target.getX());
        ctx.state().putDouble("ty", target.getY());
        ctx.state().putDouble("tz", target.getZ());
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks >= HARD_FINISH_TICK) {
            return true;
        }
        return ticks >= EARLY_FINISH_TICK
            && ctx.state().getIntOr("shots", 0) >= SHOT_COUNT
            && ctx.state().getListOrEmpty("clusters").isEmpty();
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to undo: live clusters are vanilla projectiles
        // that finish their own flight, and the burst effects
        // expire on their own clocks.
    }
}
