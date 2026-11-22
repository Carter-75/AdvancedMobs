package mod.advancedmobs.ability.camel;

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
import net.minecraft.world.entity.animal.camel.Camel;
import net.minecraft.world.entity.projectile.LlamaSpit;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Spit Volley (Camel ability 3 of 5, attack).
 *
 * WHAT IT DOES: the camel works its jaw, then lobs a rapid
 * volley of six spit globs in high arcs at its target. The
 * arcs sail clean over walls and cover that would stop a
 * flat shot, and every glob bursts in a sticky splash where
 * it lands: anything caught in the mess is hurt and slowed,
 * wading through camel spit.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the camel's current target within 16 blocks (distance
 *   squared 256), and the caster really is a Camel.
 * - Telegraph (ticks 1 to 5): the camel's navigation is
 *   stopped each tick (never NoAi) and 2 SPIT particles
 *   dribble at its mouth on ticks 2 and 4 while the jaw
 *   works up the volley.
 * - The volley (ticks 6, 9, 12, 15, 18, 21): one real
 *   LlamaSpit projectile per shot tick, 6 globs total. A
 *   camel cannot use the LlamaSpit(Level, Llama)
 *   constructor, so each glob is created from EntityTypes.
 *   LLAMA_SPIT and owned by the camel through Projectile.
 *   setOwner (the Cud Cannon pattern): the camel is credited
 *   for the glob's vanilla 1.0 contact damage, which is
 *   real projectile-typed damage. Each glob starts at the
 *   camel's eye position pushed 0.7 blocks along its look
 *   direction and is shot at speed 1.6 with inaccuracy 2.0
 *   toward an aim point above the target: the target's live
 *   chest position raised by 0.30 times the horizontal
 *   distance, which is what gives the volley its high,
 *   cover-clearing arc. If the target is gone on a shot
 *   tick, the glob aims at the last recorded target position
 *   (the state tag's tx/ty/tz, refreshed every tick the
 *   target lives). LLAMA_SPIT plays per shot at volume 1.0,
 *   pitch 0.85 plus 0.04 per shot index. Each glob's UUID
 *   and last known position are tracked in the state tag.
 * - The splash (tick(), every tick): each tracked glob that
 *   is still alive refreshes its recorded position; a glob
 *   that has resolved (hit something or expired) splashes
 *   once at its last recorded position: 6 SPIT and 4 SPLASH
 *   particles, and every enemy within 1.8 blocks (vertical
 *   reach 1.5) takes 2.0 flat damage through a mob attack
 *   source credited to the camel and gains SLOWNESS I for
 *   100 ticks, amplifier 0. Resolved globs are pruned from
 *   the tracking list in the same pass.
 * - Duration: finishes at tick 40 at the earliest once all
 *   6 shots are fired and every glob has resolved; hard
 *   finish at tick 100 whatever is still flying (an
 *   unresolved glob then splashes nowhere: it is simply
 *   dropped from tracking, and end() needs no cleanup
 *   because the effects live on their own clocks).
 *
 * WHAT IT LOOKS LIKE: a camel chewing with spit dribbling
 * from its mouth, then six green globs lobbing up and over
 * in a quick rhythm, each one bursting in a wet green
 * splash that leaves its victims dragging their feet.
 *
 * PERFORMANCE: the telegraph is one navigation stop plus 2
 * particles on 2 ticks; a shot tick is one spawn and one
 * sound; the splash pass resolves at most 6 UUIDs per tick
 * and runs one bounded radius-1.8 query only on a tick where
 * a glob actually resolved. Particle budget per activation:
 * telegraph 4, splashes at most 6 times 10 = 60, total at
 * most 64 particles.
 *
 * TERRAIN: this ability changes no blocks (the globs are
 * vanilla projectiles whose hits never alter blocks); the
 * mobGriefing gate does not apply.
 *
 * State tag keys: "shots" (globs fired so far), "tx"/"ty"/
 * "tz" (last known target position), "spits" (list of
 * compounds, one per live glob: "id", "x", "y", "z").
 */
public final class SpitVolleyAbility implements MobAbility {

    private static final int TELEGRAPH_TICKS = 5;
    private static final int FIRST_SHOT_TICK = 6;
    private static final int SHOT_INTERVAL = 3;
    private static final int SHOT_COUNT = 6;
    private static final int EARLY_FINISH_TICK = 40;
    private static final int HARD_FINISH_TICK = 100;
    private static final double SPIT_SPEED = 1.6;
    private static final double ARC_LIFT_PER_BLOCK = 0.30;
    private static final double SPLASH_RADIUS = 1.8;
    private static final double SPLASH_DAMAGE = 2.0;
    private static final int SLOWNESS_TICKS = 100;

    @Override
    public String id() {
        return "spit_volley";
    }

    @Override
    public String displayName() {
        return "Spit Volley";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob() instanceof Camel
            && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().putInt("shots", 0);
        ctx.state().put("spits", new ListTag());
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
            // The jaw works: hold position through navigation, never NoAi.
            caster.getNavigation().stop();
            if (ticks % 2 == 0) {
                Vec3 look = caster.getLookAngle();
                level.sendParticles(ParticleTypes.SPIT,
                    caster.getX() + look.x * 0.8, caster.getEyeY() - 0.2, caster.getZ() + look.z * 0.8,
                    2, 0.06, 0.06, 0.06, 0.02);
            }
            return;
        }

        int shots = ctx.state().getIntOr("shots", 0);
        if (shots < SHOT_COUNT && ticks >= FIRST_SHOT_TICK
            && (ticks - FIRST_SHOT_TICK) % SHOT_INTERVAL == 0) {
            fireGlob(ctx, level, shots);
            ctx.state().putInt("shots", shots + 1);
        }

        trackGlobs(ctx, level);
    }

    /** Fires one glob of the volley on its high arc (see the class comment). */
    private void fireGlob(AbilityContext ctx, ServerLevel level, int shotIndex) {
        Mob caster = ctx.mob();
        double tx = ctx.state().getDoubleOr("tx", caster.getX());
        double ty = ctx.state().getDoubleOr("ty", caster.getEyeY());
        double tz = ctx.state().getDoubleOr("tz", caster.getZ());

        Vec3 origin = caster.getEyePosition().add(caster.getLookAngle().scale(0.7));
        double dx = tx - origin.x;
        double dz = tz - origin.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        // Aim above the target: the lift is what arcs the glob over cover.
        double dy = (ty + 0.5 + horizontal * ARC_LIFT_PER_BLOCK) - origin.y;

        LlamaSpit spit = EntityTypes.LLAMA_SPIT.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (spit == null) {
            return;
        }
        spit.setOwner(caster);
        spit.setPos(origin.x, origin.y, origin.z);
        spit.shoot(dx, dy, dz, (float) SPIT_SPEED, 2.0f);
        if (level.addFreshEntity(spit)) {
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, spit.getUUID());
            entry.putDouble("x", spit.getX());
            entry.putDouble("y", spit.getY());
            entry.putDouble("z", spit.getZ());
            ListTag spits = ctx.state().getListOrEmpty("spits");
            spits.add(entry);
            ctx.state().put("spits", spits);
        }
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.LLAMA_SPIT,
            SoundSource.HOSTILE, 1.0f, 0.85f + 0.04f * shotIndex);
    }

    /**
     * Refreshes live globs' recorded positions and splashes
     * the ones that resolved since the last tick (see the
     * class comment). Resolved globs are pruned in the pass.
     */
    private void trackGlobs(AbilityContext ctx, ServerLevel level) {
        ListTag tracked = ctx.state().getListOrEmpty("spits");
        if (tracked.isEmpty()) {
            return;
        }
        Mob caster = ctx.mob();
        ListTag remaining = new ListTag();
        for (int i = 0; i < tracked.size(); i++) {
            CompoundTag entry = tracked.getCompoundOrEmpty(i);
            Entity entity = CamelAbilitySupport.resolveTracked(level, entry);
            if (entity != null && entity.isAlive()) {
                entry.putDouble("x", entity.getX());
                entry.putDouble("y", entity.getY());
                entry.putDouble("z", entity.getZ());
                remaining.add(entry);
                continue;
            }
            // Resolved: the splash lands at its last known position.
            double x = entry.getDoubleOr("x", 0.0);
            double y = entry.getDoubleOr("y", 0.0);
            double z = entry.getDoubleOr("z", 0.0);
            level.sendParticles(ParticleTypes.SPIT, x, y, z, 6, 0.3, 0.3, 0.3, 0.05);
            level.sendParticles(ParticleTypes.SPLASH, x, y, z, 4, 0.3, 0.2, 0.3, 0.05);
            List<LivingEntity> caught = CamelAbilitySupport.enemiesInBox(ctx, x, y, z,
                SPLASH_RADIUS, 1.5, 1.5);
            for (LivingEntity victim : caught) {
                victim.hurtServer(level, level.damageSources().mobAttack(caster), (float) SPLASH_DAMAGE);
                victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, SLOWNESS_TICKS, 0));
            }
        }
        ctx.state().put("spits", remaining);
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
            && ctx.state().getListOrEmpty("spits").isEmpty();
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to undo: live globs are vanilla projectiles
        // that finish their own flight, and the splash effects
        // expire on their own clocks.
    }
}
