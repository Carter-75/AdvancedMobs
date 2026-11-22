package mod.advancedmobs.ability.cow;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
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
import net.minecraft.world.entity.projectile.LlamaSpit;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Cud Cannon (Cow ability 4 of 5, attack).
 *
 * WHAT IT DOES: the cow stops, works its jaw in slow
 * circles, green composter motes drifting off its muzzle
 * as the cud comes up, and then hawks a single fat wad of
 * cud in a high arc across the fight. Where the wad lands
 * it bursts in a sticky splash that damages, slows, and
 * weakens everything it catches.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the cow's current target within 18 blocks (distance
 *   squared 324).
 * - The chew (ticks 1 to 19): navigation stopped every tick
 *   (never NoAi). Every 2 ticks, 2 COMPOSTER motes and 1
 *   green DUST mote (0x6FA843, the design's faint green
 *   glow) puff at the cow's muzzle. The target's position
 *   is recorded every tick (the state tag's tx/ty/tz) so
 *   the shot still has an aim point if the target dies
 *   during the chew.
 * - The shot (tick 20, exactly once): one real LlamaSpit
 *   projectile entity (the design's llama_spit style
 *   entity), created from EntityTypes.LLAMA_SPIT and owned
 *   by the cow through Projectile.setOwner, because the
 *   LlamaSpit(Level, Llama) constructor needs a llama and a
 *   cow is not one. It spawns at the cow's eye position
 *   pushed 0.6 blocks along its look direction and is shot
 *   at speed 1.5 with inaccuracy 0.0 toward the target's
 *   chest (feet plus half its height), with the aim vector
 *   lifted by 0.07 per block of horizontal distance so the
 *   wad arcs instead of flying flat; vanilla gravity on
 *   the projectile brings it down the far side. LLAMA_SPIT
 *   plays at volume 1.2, pitch 0.7 (a deeper, wetter spit).
 *   The glob's own vanilla contact behavior runs unchanged
 *   with the cow as its owner; the burst below is this
 *   ability's own addition.
 * - Tracking (every tick after the shot): the glob's last
 *   known position is refreshed while it lives. The tick
 *   it resolves (hit something or expired) it bursts once
 *   at that position: 8 SPIT plus 6 SPLASH particles, and
 *   every enemy within 2.2 blocks (vertical reach 1.5)
 *   takes 3.0 damage as a mob attack credited to the cow
 *   and gains SLOWNESS I for 100 ticks and WEAKNESS I for
 *   100 ticks (the design's sticky and vulnerable).
 * - Duration: finishes once the burst has fired and tick
 *   25 has passed; hard finish at tick 90 whatever is
 *   still flying (an unresolved glob then bursts nowhere:
 *   it is simply dropped from tracking, and end() needs no
 *   cleanup because the splash effects live on their own
 *   clocks and a live glob is a vanilla projectile that
 *   finishes its own flight).
 *
 * WHAT IT LOOKS LIKE: a cow chewing in slow circles wreathed
 * in green motes, a wet hawk, one big green wad sailing in
 * an arc, and a sticky SPIT and SPLASH burst where it lands.
 *
 * PERFORMANCE: the chew is one navigation stop plus 3
 * particles every 2 ticks; the shot tick is one spawn and
 * one sound; tracking is one UUID resolve per tick and one
 * bounded radius-2.2 query only on the burst tick.
 * Particle budget per activation: chew 10 pulses times 3 =
 * 30, burst 14, total at most 44 particles. The dust option
 * is a pre-built static final.
 *
 * TERRAIN: this ability changes no blocks (the glob is a
 * vanilla projectile whose hits never alter blocks); the
 * mobGriefing gate does not apply.
 *
 * State tag keys: "fired" (0 until the shot, 1 after),
 * "burst_done" (0 until the burst, 1 after), "gid" (the
 * glob's UUID), "gx"/"gy"/"gz" (its last known position),
 * "tx"/"ty"/"tz" (last known target position).
 */
public final class CudCannonAbility implements MobAbility {

    private static final int FIRE_TICK = 20;
    private static final int EARLY_FINISH_TICK = 25;
    private static final int HARD_FINISH_TICK = 90;
    private static final double SPIT_SPEED = 1.5;
    private static final double ARC_LIFT_PER_BLOCK = 0.07;
    private static final double BURST_RADIUS = 2.2;
    private static final double BURST_DAMAGE = 3.0;
    private static final DustParticleOptions CUD_DUST = new DustParticleOptions(0x6FA843, 1.0f);

    @Override
    public String id() {
        return "cud_cannon";
    }

    @Override
    public String displayName() {
        return "Cud Cannon";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 324.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().putInt("fired", 0);
        ctx.state().putInt("burst_done", 0);
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

        if (ticks < FIRE_TICK) {
            // The chew: hold position through navigation, never NoAi.
            caster.getNavigation().stop();
            if (ticks % 2 == 0) {
                Vec3 look = caster.getLookAngle();
                double mx = caster.getX() + look.x * 0.8;
                double mz = caster.getZ() + look.z * 0.8;
                level.sendParticles(ParticleTypes.COMPOSTER,
                    mx, caster.getEyeY() - 0.15, mz,
                    2, 0.12, 0.12, 0.12, 0.03);
                level.sendParticles(CUD_DUST,
                    mx, caster.getEyeY() - 0.1, mz,
                    1, 0.1, 0.1, 0.1, 0.02);
            }
            return;
        }

        if (ctx.state().getIntOr("fired", 0) == 0) {
            fireGlob(ctx, level);
            ctx.state().putInt("fired", 1);
            return;
        }
        if (ctx.state().getIntOr("burst_done", 0) == 0) {
            trackGlob(ctx, level);
        }
    }

    /** Fires the one cud wad: a cow-owned LlamaSpit on an arcing aim line. */
    private void fireGlob(AbilityContext ctx, ServerLevel level) {
        Mob caster = ctx.mob();
        double tx = ctx.state().getDoubleOr("tx", caster.getX());
        double ty = ctx.state().getDoubleOr("ty", caster.getY());
        double tz = ctx.state().getDoubleOr("tz", caster.getZ());
        LivingEntity target = ctx.target();
        double aimY = ty + (target != null ? target.getBbHeight() * 0.5 : 0.8);

        Vec3 origin = caster.getEyePosition().add(caster.getLookAngle().scale(0.6));
        double dx = tx - origin.x;
        double dy = aimY - origin.y;
        double dz = tz - origin.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        dy += horizontal * ARC_LIFT_PER_BLOCK;

        LlamaSpit glob = EntityTypes.LLAMA_SPIT.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (glob == null) {
            ctx.state().putInt("burst_done", 1);
            return;
        }
        glob.setOwner(caster);
        glob.setPos(origin.x, origin.y, origin.z);
        glob.shoot(dx, dy, dz, (float) SPIT_SPEED, 0.0f);
        if (!level.addFreshEntity(glob)) {
            ctx.state().putInt("burst_done", 1);
            return;
        }
        ctx.state().store("gid", UUIDUtil.CODEC, glob.getUUID());
        ctx.state().putDouble("gx", glob.getX());
        ctx.state().putDouble("gy", glob.getY());
        ctx.state().putDouble("gz", glob.getZ());
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.LLAMA_SPIT,
            SoundSource.HOSTILE, 1.2f, 0.7f);
    }

    /**
     * Refreshes the glob's recorded position while it lives
     * and fires the burst once at its last known position
     * when it resolves (see the class comment).
     */
    private void trackGlob(AbilityContext ctx, ServerLevel level) {
        UUID id = ctx.state().read("gid", UUIDUtil.CODEC).orElse(null);
        Entity glob = id != null ? level.getEntity(id) : null;
        if (glob != null && glob.isAlive()) {
            ctx.state().putDouble("gx", glob.getX());
            ctx.state().putDouble("gy", glob.getY());
            ctx.state().putDouble("gz", glob.getZ());
            return;
        }
        // Resolved: the burst lands at its last known position.
        ctx.state().putInt("burst_done", 1);
        Mob caster = ctx.mob();
        double x = ctx.state().getDoubleOr("gx", caster.getX());
        double y = ctx.state().getDoubleOr("gy", caster.getY());
        double z = ctx.state().getDoubleOr("gz", caster.getZ());
        level.sendParticles(ParticleTypes.SPIT, x, y, z, 8, 0.3, 0.3, 0.3, 0.06);
        level.sendParticles(ParticleTypes.SPLASH, x, y, z, 6, 0.35, 0.25, 0.35, 0.08);
        List<LivingEntity> caught = CowAbilitySupport.enemiesInBox(ctx, x, y, z,
            BURST_RADIUS, 1.5, 1.5);
        for (LivingEntity victim : caught) {
            victim.hurtServer(level, level.damageSources().mobAttack(caster), (float) BURST_DAMAGE);
            victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 100, 0));
            victim.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 100, 0));
        }
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
            && ctx.state().getIntOr("fired", 0) == 1
            && ctx.state().getIntOr("burst_done", 0) == 1;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to undo: a live glob is a vanilla
        // projectile that finishes its own flight, and the
        // burst effects expire on their own clocks.
    }
}
