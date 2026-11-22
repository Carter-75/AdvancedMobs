package mod.advancedmobs.ability.pillager;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Powder Keg Toss (Pillager ability 5 of 5, attack).
 *
 * WHAT IT DOES: the pillager heaves a primed keg of trouble
 * at the enemy line: a real primed TNT that arcs through the
 * air trailing smoke, lands, bounces and skids once on
 * vanilla physics, and detonates in the middle of the fight
 * with a full explosion whose crater is real. A pillager down
 * to his last trick holds the lit keg a heartbeat longer, so
 * it lands closer and bursts bigger.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the pillager's current target within 14 blocks (distance
 *   squared 196).
 * - The keg (start()): one real PrimedTnt owned by the
 *   pillager, spawned at his eye position pushed 0.8 blocks
 *   toward the target. Throw velocity: horizontal speed is
 *   the horizontal distance to the target times 0.085,
 *   clamped to [0.45, 1.05] blocks per tick, vertical 0.55:
 *   a lobbed arc that lands at or just past the target and
 *   skids on vanilla TNT physics (its bounce and slide are
 *   the design's "bounces once"; the code does not steer it
 *   after the throw).
 * - The fuse is CODE, not the entity's (the design's Uses
 *   list pairs the summoned TNT with "explosion in code"):
 *   the entity's own fuse is set to 200 ticks, far beyond the
 *   detonation moment, and the ability discards the keg an
 *   instant before running its own explosion, so vanilla's
 *   fixed power-4, always-destroys TNT blast never fires.
 *   The code detonation happens exactly 40 ticks after the
 *   throw, wherever the keg has come to rest, tracked by UUID
 *   with its last position recorded every tick.
 * - The blast: Level.explode at the keg's last position,
 *   power 4.0 (a full vanilla TNT's worth), fire off, with
 *   the explosion interaction chosen by the terrain gate:
 *   ExplosionInteraction.MOB when mobGriefing is on (the
 *   crater is real and permanent), NONE when it is off (the
 *   blast still wounds and hurls everyone in it; it just
 *   breaks nothing). One EXPLOSION_EMITTER particle is sent
 *   on top of the explosion's own visuals and sound.
 * - The held keg (the design's last trick): when the
 *   pillager is at or below 35 percent health at the throw,
 *   he holds it a heartbeat longer: detonation at 25 ticks
 *   instead of 40, throw speed multiplied by 0.6 (it lands
 *   closer, likely short of the line and nearer his own
 *   feet), and blast power 5.0 instead of 4.0. The numbers
 *   are fixed at the throw and stored in the state tag.
 * - Exactly once: a "detonated" flag in the state tag guards
 *   the blast. If the pillager dies or is removed while the
 *   keg is still in flight, the framework termination path
 *   runs end(), and end() detonates the keg at its last
 *   recorded position: a lit keg does not go out because its
 *   thrower fell (the Creeper self-detonation precedent). If
 *   the keg entity itself is somehow gone (it fell out of the
 *   world), the blast still runs at the last recorded
 *   position, harmlessly wherever that is.
 * - Duration: the activation finishes on the tick after the
 *   detonation.
 *
 * WHAT IT LOOKS LIKE: a flashing primed keg sailing out of
 * the pillager's hands on a smoking arc (2 SMOKE at the keg
 * every 2 ticks), a skid and a bounce on the ground, then a
 * full TNT-strength blast with a real crater when mobGriefing
 * allows one.
 *
 * TERRAIN: the explosion is this package's second terrain
 * effect after the perch tower, and it follows the standing
 * rule through the interaction mode above: MOB (permanent
 * crater) when mobGriefing is on, NONE (no block damage at
 * all) when it is off. Nothing else in the ability touches
 * blocks.
 *
 * PERFORMANCE: per tick the work is one UUID resolve and one
 * position write, plus 2 smoke particles every 2 ticks; the
 * detonation is a single explode call. Particle budget per
 * activation: smoke at most 40 ticks times 1 send of 2 = 40
 * (at most 25 for a held keg), plus 1 EXPLOSION_EMITTER,
 * total at most 41 sent by the ability (the explosion's own
 * particles and sound are the level's).
 *
 * State tag keys: "keg" (list holding the keg's entry),
 * "kx", "ky", "kz" (its last recorded position), "det_tick"
 * (the tick the blast runs), "power" (the blast power fixed
 * at the throw), "detonated" (1 once the blast has run).
 */
public final class PowderKegTossAbility implements MobAbility {

    private static final int FUSE_TICKS = 40;
    private static final int HELD_FUSE_TICKS = 25;
    private static final double BLAST_POWER = 4.0;
    private static final double HELD_BLAST_POWER = 5.0;
    private static final double HELD_HEALTH_FRACTION = 0.35;
    /** Deliberately far beyond the code fuse: the entity's own blast must never fire (see the class comment). */
    private static final int ENTITY_FUSE_TICKS = 200;

    @Override
    public String id() {
        return "powder_keg_toss";
    }

    @Override
    public String displayName() {
        return "Powder Keg Toss";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        boolean held = mob.getHealth() <= mob.getMaxHealth() * HELD_HEALTH_FRACTION;
        ctx.state().putInt("det_tick", held ? HELD_FUSE_TICKS : FUSE_TICKS);
        ctx.state().putDouble("power", held ? HELD_BLAST_POWER : BLAST_POWER);
        ctx.state().putInt("detonated", 0);
        ctx.state().putDouble("kx", mob.getX());
        ctx.state().putDouble("ky", mob.getEyeY());
        ctx.state().putDouble("kz", mob.getZ());

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        LivingEntity target = ctx.target();
        Vec3 dir;
        double horizontalDistance;
        if (target != null) {
            dir = new Vec3(target.getX() - mob.getX(), 0.0, target.getZ() - mob.getZ());
            horizontalDistance = dir.length();
            dir = horizontalDistance > 1.0e-6 ? dir.normalize()
                : mob.getLookAngle().multiply(1.0, 0.0, 1.0).normalize();
        } else {
            dir = mob.getLookAngle().multiply(1.0, 0.0, 1.0).normalize();
            horizontalDistance = 8.0;
        }
        double speed = Math.max(0.45, Math.min(1.05, horizontalDistance * 0.085));
        if (held) {
            speed *= 0.6;
        }
        Vec3 eye = mob.getEyePosition();
        PrimedTnt keg = new PrimedTnt(level, eye.x + dir.x * 0.8, eye.y, eye.z + dir.z * 0.8, mob);
        keg.setFuse(ENTITY_FUSE_TICKS);
        keg.setDeltaMovement(dir.x * speed, 0.55, dir.z * speed);
        if (level.addFreshEntity(keg)) {
            PillagerAbilitySupport.trackEntity(ctx.state(), "keg", keg);
            ctx.state().putDouble("kx", keg.getX());
            ctx.state().putDouble("ky", keg.getY());
            ctx.state().putDouble("kz", keg.getZ());
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Entity keg = PillagerAbilitySupport.resolveFirstTracked(level, ctx.state(), "keg");
        if (keg != null && keg.isAlive()) {
            ctx.state().putDouble("kx", keg.getX());
            ctx.state().putDouble("ky", keg.getY());
            ctx.state().putDouble("kz", keg.getZ());
            if (ctx.ticksActive() % 2 == 0) {
                level.sendParticles(ParticleTypes.SMOKE, keg.getX(), keg.getY() + 0.4, keg.getZ(),
                    2, 0.15, 0.15, 0.15, 0.02);
            }
        }
        if (ctx.state().getIntOr("detonated", 0) == 0
            && ctx.ticksActive() >= ctx.state().getIntOr("det_tick", FUSE_TICKS)) {
            detonate(ctx, level);
        }
    }

    /**
     * The one and only blast: discards the keg entity first so
     * its own vanilla explosion can never follow, then runs
     * the code explosion at the last recorded position with
     * the terrain gate's interaction mode.
     */
    private void detonate(AbilityContext ctx, ServerLevel level) {
        ctx.state().putInt("detonated", 1);
        Entity keg = PillagerAbilitySupport.resolveFirstTracked(level, ctx.state(), "keg");
        if (keg != null) {
            keg.discard();
        }
        double x = ctx.state().getDoubleOr("kx", ctx.mob().getX());
        double y = ctx.state().getDoubleOr("ky", ctx.mob().getEyeY());
        double z = ctx.state().getDoubleOr("kz", ctx.mob().getZ());
        double power = ctx.state().getDoubleOr("power", BLAST_POWER);
        Level.ExplosionInteraction interaction = ctx.mobGriefingAllowed()
            ? Level.ExplosionInteraction.MOB : Level.ExplosionInteraction.NONE;
        level.explode(ctx.mob(), x, y, z, (float) power, false, interaction);
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.state().getIntOr("detonated", 0) == 1;
    }

    @Override
    public void end(AbilityContext ctx) {
        // A lit keg goes off even when its thrower fell
        // mid-flight: detonate at the last recorded position
        // when the blast has not run yet (see the class
        // comment). After a natural finish this is a no-op.
        if (ctx.state().getIntOr("detonated", 0) == 1) {
            return;
        }
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            detonate(ctx, level);
        }
    }
}
