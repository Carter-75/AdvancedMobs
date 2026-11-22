package mod.advancedmobs.ability.llama;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.equine.Llama;
import net.minecraft.world.entity.projectile.LlamaSpit;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Spit Volley (Llama ability 1 of 5, attack).
 *
 * WHAT IT DOES: the llama works its jaw for half a second,
 * pale DUST motes puffing from its nostrils, then unloads a
 * fanning volley of five real llama spit projectiles across
 * the fight. Each glob that lands splashes SPIT particles
 * and leaves every enemy it catches WEAKENED and NAUSEATED
 * (the design's "seeing spots"), and because the globs fan
 * out in a spread, the volley can clip several enemies
 * standing in a line.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the llama's current target within 18 blocks (distance
 *   squared 324), and the caster really is a Llama (the
 *   vanilla spit projectile needs one as its owner).
 * - Telegraph (ticks 1 to 10): the llama's navigation is
 *   stopped each tick (never NoAi) and 3 pale DUST motes
 *   (0xD8CDB4, scale 1.0) puff at its nostrils every 2 ticks.
 * - The volley (ticks 12, 16, 20, 24, 28): one real
 *   LlamaSpit projectile per shot tick, 5 globs total. Each
 *   glob is created with the vanilla LlamaSpit(Level, Llama)
 *   constructor (so the llama is its owner and vanilla runs
 *   its flight and its 1.0 contact damage), placed at the
 *   llama's eye position pushed 0.6 blocks along its look
 *   direction, and shot at speed 1.5 with inaccuracy 1.0
 *   toward the target's live chest position. The aim
 *   direction is rotated horizontally by a fan offset per
 *   shot: -0.16, -0.08, 0.0, +0.08, +0.16 radians, so the
 *   volley fans across a line of enemies instead of stacking
 *   on one point. If the target is gone on a shot tick, the
 *   glob is aimed at the last recorded target position (the
 *   state tag's tx/ty/tz, refreshed every tick the target
 *   lives). LLAMA_SPIT plays per shot at volume 1.0, pitch
 *   0.9 plus 0.05 per shot index. Each glob's UUID and last
 *   known position are tracked in the state tag.
 * - The splash (tick(), every tick): each tracked glob that
 *   is still alive refreshes its recorded position; a glob
 *   that has resolved (hit something or expired) splashes
 *   once at its last recorded position: 6 SPIT particles,
 *   and every enemy within 1.6 blocks (vertical reach 1.5)
 *   gains WEAKNESS I for 100 ticks and NAUSEA for 60 ticks,
 *   amplifier 0. The glob's own vanilla contact damage is
 *   separate and unchanged. Resolved globs are pruned from
 *   the tracking list in the same pass.
 * - Duration: finishes at tick 40 at the earliest once all
 *   5 shots are fired and every glob has resolved; hard
 *   finish at tick 100 whatever is still flying (an
 *   unresolved glob then splashes nowhere: it is simply
 *   dropped from tracking, and end() needs no cleanup
 *   because the effects live on their own clocks).
 *
 * WHAT IT LOOKS LIKE: a llama chewing with dust puffing
 * from its nostrils, then five green globs arcing out in a
 * fan with wet spit sounds, each bursting in SPIT spray
 * where it lands.
 *
 * PERFORMANCE: the telegraph is one navigation stop plus 3
 * particles every 2 ticks; a shot tick is one spawn and one
 * sound; the splash pass resolves at most 5 UUIDs per tick
 * and runs one bounded radius-1.6 query only on a tick where
 * a glob actually resolved. Particle budget per activation:
 * telegraph 5 pulses times 3 = 15, splashes at most 5 times
 * 6 = 30, total at most 45 particles.
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

    private static final int TELEGRAPH_TICKS = 10;
    private static final int FIRST_SHOT_TICK = 12;
    private static final int SHOT_INTERVAL = 4;
    private static final int SHOT_COUNT = 5;
    private static final int EARLY_FINISH_TICK = 40;
    private static final int HARD_FINISH_TICK = 100;
    private static final double SPIT_SPEED = 1.5;
    private static final double SPLASH_RADIUS = 1.6;
    private static final DustParticleOptions NOSTRIL_DUST = new DustParticleOptions(0xD8CDB4, 1.0f);
    private static final double[] FAN_OFFSETS = {-0.16, -0.08, 0.0, 0.08, 0.16};

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
        return target != null && ctx.mob() instanceof Llama
            && ctx.mob().distanceToSqr(target) <= 324.0;
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
                level.sendParticles(NOSTRIL_DUST,
                    caster.getX() + look.x * 0.7, caster.getEyeY() - 0.1, caster.getZ() + look.z * 0.7,
                    3, 0.08, 0.08, 0.08, 0.02);
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

    /** Fires one glob of the volley: shot index picks the fan offset. */
    private void fireGlob(AbilityContext ctx, ServerLevel level, int shotIndex) {
        if (!(ctx.mob() instanceof Llama llama)) {
            return;
        }
        double tx = ctx.state().getDoubleOr("tx", llama.getX());
        double ty = ctx.state().getDoubleOr("ty", llama.getEyeY());
        double tz = ctx.state().getDoubleOr("tz", llama.getZ());

        Vec3 origin = llama.getEyePosition().add(llama.getLookAngle().scale(0.6));
        double dx = tx - origin.x;
        double dy = (ty + 0.5) - origin.y;
        double dz = tz - origin.z;
        // Fan the shot horizontally around the direct aim line.
        double offset = FAN_OFFSETS[shotIndex];
        double cos = Math.cos(offset);
        double sin = Math.sin(offset);
        double rx = dx * cos - dz * sin;
        double rz = dx * sin + dz * cos;

        LlamaSpit spit = new LlamaSpit(level, llama);
        spit.setPos(origin.x, origin.y, origin.z);
        spit.shoot(rx, dy, rz, (float) SPIT_SPEED, 1.0f);
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
        level.playSound(null, llama.getX(), llama.getY(), llama.getZ(), SoundEvents.LLAMA_SPIT,
            SoundSource.HOSTILE, 1.0f, 0.9f + 0.05f * shotIndex);
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
        ListTag remaining = new ListTag();
        for (int i = 0; i < tracked.size(); i++) {
            CompoundTag entry = tracked.getCompoundOrEmpty(i);
            Entity entity = LlamaAbilitySupport.resolveTracked(level, entry);
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
            level.sendParticles(ParticleTypes.SPIT, x, y, z, 6, 0.25, 0.25, 0.25, 0.05);
            List<LivingEntity> caught = LlamaAbilitySupport.enemiesInBox(ctx, x, y, z,
                SPLASH_RADIUS, 1.5, 1.5);
            for (LivingEntity victim : caught) {
                victim.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 100, 0));
                victim.addEffect(new MobEffectInstance(MobEffects.NAUSEA, 60, 0));
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
