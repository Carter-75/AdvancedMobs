package mod.advancedmobs.ability.mule;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Satchel Bombardier (Mule ability 3 of 5, attack).
 *
 * WHAT IT DOES: the mule shrugs a lit satchel charge off
 * its pack and the charge sails in a high arc toward the
 * enemy, fuse sparking smoke and flame. It comes down as
 * real primed TNT and detonates in a full explosion while
 * the mule watches from a safe, smug distance, braced
 * against its own blast.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the mule's current target between 6 and 16 blocks away
 *   (distance squared between 36 and 256): the mule only
 *   lobs a charge it can watch from outside the crater.
 * - The brace (start(), removed exactly in end()): one
 *   transient modifier under a fixed id,
 *   EXPLOSION_KNOCKBACK_RESISTANCE +1.0 ADD_VALUE (the
 *   design's explosion_knockback_resistance line: the
 *   attribute clamps at total resistance, so the mule's
 *   own blast cannot hurl it even if the charge falls
 *   short).
 * - The charge (start()): one real PrimedTnt owned by the
 *   mule (the owner is what credits its kills), spawned at
 *   the mule's eye position pushed 0.8 blocks toward the
 *   target, and tagged "advancedmobs_satchel" (the
 *   design's tag to credit the kill: the tag marks the
 *   charge as this ability's in the world, next to the
 *   owner credit). Throw velocity: horizontal speed is the
 *   horizontal distance to the target times 0.08, clamped
 *   to [0.5, 1.1] blocks per tick, vertical 0.62: the
 *   design's high arc, landing at or just past the target
 *   and skidding on vanilla TNT physics; the code does not
 *   steer it after the throw.
 * - The fuse is CODE, not the entity's (the Powder Keg
 *   Toss pattern): the entity's own fuse is set to 200
 *   ticks, far beyond the detonation moment, and the
 *   ability discards the charge an instant before running
 *   its own explosion, so vanilla's fixed power-4,
 *   always-destroys TNT blast never fires. The code
 *   detonation happens exactly 30 ticks after the throw
 *   (the design's short fuse), wherever the charge has
 *   come to rest, tracked by UUID with its last position
 *   recorded every tick.
 * - The blast: Level.explode at the charge's last
 *   position, power 4.0 (a full vanilla TNT's worth), fire
 *   off, credited to the mule, with the explosion
 *   interaction chosen by the terrain gate:
 *   ExplosionInteraction.MOB when mobGriefing is on (the
 *   crater is real and permanent), NONE when it is off
 *   (the blast still wounds and hurls everyone in it; it
 *   just breaks nothing). One EXPLOSION_EMITTER particle
 *   is sent on top of the explosion's own visuals and
 *   sound.
 * - Exactly once: a "detonated" flag in the state tag
 *   guards the blast. If the mule dies or is removed while
 *   the charge is still in flight, the framework
 *   termination path runs end(), and end() detonates the
 *   charge at its last recorded position: a lit satchel
 *   does not go out because its thrower fell (the Powder
 *   Keg Toss / Creeper self-detonation precedent). If the
 *   charge entity itself is somehow gone (it fell out of
 *   the world), the blast still runs at the last recorded
 *   position, harmlessly wherever that is.
 * - Duration: the activation finishes on the tick after
 *   the detonation.
 *
 * WHAT IT LOOKS LIKE: a flashing primed charge sailing off
 * the mule's back on a high smoking arc (2 SMOKE and 1
 * FLAME at the charge every 2 ticks, the design's sparking
 * fuse), a skid on the ground, then a full TNT-strength
 * blast with a real crater when mobGriefing allows one,
 * and the mule standing unshaken in the shockwave.
 *
 * TERRAIN: the explosion is this package's only block
 * change, and it follows the standing rule through the
 * interaction mode above: MOB (permanent crater) when
 * mobGriefing is on, NONE (no block damage at all) when
 * it is off. Nothing else in the ability touches blocks.
 *
 * PERFORMANCE: per tick the work is one UUID resolve and
 * one position write, plus 3 particles every 2 ticks; the
 * detonation is a single explode call; the brace modifier
 * is resolved at start() and removed in end(). Particle
 * budget per activation: fuse trail at most 15 sends of
 * 3 = 45, plus 1 EXPLOSION_EMITTER, total at most 46 sent
 * by the ability (the explosion's own particles and sound
 * are the level's).
 *
 * State tag keys: "satchel" (list holding the charge's
 * entry), "sx", "sy", "sz" (its last recorded position),
 * "detonated" (1 once the blast has run).
 */
public final class SatchelBombardierAbility implements MobAbility {

    private static final int FUSE_TICKS = 30;
    private static final double BLAST_POWER = 4.0;
    /** Deliberately far beyond the code fuse: the entity's own blast must never fire (see the class comment). */
    private static final int ENTITY_FUSE_TICKS = 200;
    private static final String SATCHEL_TAG = "advancedmobs_satchel";
    private static final Identifier BRACE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "satchel_bombardier_brace");

    @Override
    public String id() {
        return "satchel_bombardier";
    }

    @Override
    public String displayName() {
        return "Satchel Bombardier";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null) {
            return false;
        }
        double distanceSqr = ctx.mob().distanceToSqr(target);
        return distanceSqr >= 36.0 && distanceSqr <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        // The brace: the mule shrugs off its own shockwave.
        MuleAbilitySupport.addModifier(mob, Attributes.EXPLOSION_KNOCKBACK_RESISTANCE,
            new AttributeModifier(BRACE_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        ctx.state().putInt("detonated", 0);
        ctx.state().putDouble("sx", mob.getX());
        ctx.state().putDouble("sy", mob.getEyeY());
        ctx.state().putDouble("sz", mob.getZ());

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
            horizontalDistance = 10.0;
        }
        double speed = Math.max(0.5, Math.min(1.1, horizontalDistance * 0.08));
        Vec3 eye = mob.getEyePosition();
        PrimedTnt satchel = new PrimedTnt(level, eye.x + dir.x * 0.8, eye.y, eye.z + dir.z * 0.8, mob);
        satchel.setFuse(ENTITY_FUSE_TICKS);
        satchel.addTag(SATCHEL_TAG);
        satchel.setDeltaMovement(dir.x * speed, 0.62, dir.z * speed);
        if (level.addFreshEntity(satchel)) {
            MuleAbilitySupport.trackEntity(ctx.state(), "satchel", satchel);
            ctx.state().putDouble("sx", satchel.getX());
            ctx.state().putDouble("sy", satchel.getY());
            ctx.state().putDouble("sz", satchel.getZ());
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Entity satchel = MuleAbilitySupport.resolveFirstTracked(level, ctx.state(), "satchel");
        if (satchel != null && satchel.isAlive()) {
            ctx.state().putDouble("sx", satchel.getX());
            ctx.state().putDouble("sy", satchel.getY());
            ctx.state().putDouble("sz", satchel.getZ());
            if (ctx.ticksActive() % 2 == 0) {
                // The sparking fuse: smoke and flame on the arc.
                level.sendParticles(ParticleTypes.SMOKE, satchel.getX(), satchel.getY() + 0.4, satchel.getZ(),
                    2, 0.15, 0.15, 0.15, 0.02);
                level.sendParticles(ParticleTypes.FLAME, satchel.getX(), satchel.getY() + 0.4, satchel.getZ(),
                    1, 0.1, 0.1, 0.1, 0.02);
            }
        }
        if (ctx.state().getIntOr("detonated", 0) == 0 && ctx.ticksActive() >= FUSE_TICKS) {
            detonate(ctx, level);
        }
    }

    /**
     * The one and only blast: discards the charge entity
     * first so its own vanilla explosion can never follow,
     * then runs the code explosion at the last recorded
     * position with the terrain gate's interaction mode.
     */
    private void detonate(AbilityContext ctx, ServerLevel level) {
        ctx.state().putInt("detonated", 1);
        Entity satchel = MuleAbilitySupport.resolveFirstTracked(level, ctx.state(), "satchel");
        if (satchel != null) {
            satchel.discard();
        }
        double x = ctx.state().getDoubleOr("sx", ctx.mob().getX());
        double y = ctx.state().getDoubleOr("sy", ctx.mob().getEyeY());
        double z = ctx.state().getDoubleOr("sz", ctx.mob().getZ());
        Level.ExplosionInteraction interaction = ctx.mobGriefingAllowed()
            ? Level.ExplosionInteraction.MOB : Level.ExplosionInteraction.NONE;
        level.explode(ctx.mob(), x, y, z, (float) BLAST_POWER, false, interaction);
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.state().getIntOr("detonated", 0) == 1;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        MuleAbilitySupport.removeModifier(mob, Attributes.EXPLOSION_KNOCKBACK_RESISTANCE, BRACE_ID);
        // A lit satchel goes off even when its thrower fell
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
