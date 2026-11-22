package mod.advancedmobs.ability.dolphin;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.List;
import java.util.UUID;

/**
 * Rescue Current (Dolphin ability 3 of 5, defense / support).
 *
 * WHAT IT DOES: the dolphin picks out an ally in trouble and
 * circles it, kicking up a BUBBLE_COLUMN_UP stream that lifts
 * the ally toward the surface while bubbles swarm around
 * them. The rescued ally breathes easy and mends under
 * WATER_BREATHING and REGENERATION, and the current shoves
 * enemies away from the rescue zone, buying the patient room
 * to recover.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the dolphin's current target within 16 blocks (distance
 *   squared 256), plus at least one ally (package rule)
 *   within 12 blocks of the dolphin at or below 60 percent
 *   health (the ally in trouble; one bounded query, the
 *   Guardian Stance trigger precedent). A dolphin with no
 *   hurt ally nearby never burns the cooldown on this.
 * - The patient (start()): the nearest qualifying ally from
 *   the same search is recorded by UUID. If the search comes
 *   up empty at start() (the patient died or healed in the
 *   gap), the ability fizzles: no effects, finished at once.
 * - Duration: 200 ticks (10 seconds), ending early the first
 *   pulse that finds the patient dead or gone.
 * - The circling (tick()): the dolphin is steered at 1.1
 *   blocks per tick toward a point orbiting the patient's
 *   live position at radius 3.0, the angle advancing 0.25
 *   radians per tick (a full circle about every 25 ticks),
 *   at the patient's own height plus 0.3. The steering is
 *   code velocity (the package helper); the dolphin's
 *   vanilla goals resume the moment the ability ends.
 * - The rescue pulse (every 10 ticks, 20 pulses): the
 *   patient gains WATER_BREATHING and REGENERATION I
 *   (amplifier 0), each refreshed to 60 ticks, so both run
 *   continuously through the rescue and bleed off over the
 *   3 seconds after the last pulse. Every enemy (package
 *   rule) within radius 5.0 of the PATIENT (round distance
 *   checked after the box query) is pushed directly away
 *   from the patient: velocity set to 0.9 blocks per tick
 *   horizontal away plus 0.25 up, hurtMarked so it syncs.
 *   6 BUBBLE_COLUMN_UP and 4 BUBBLE particles swarm up
 *   around the patient per pulse, and DOLPHIN_SWIM plays
 *   softly (volume 0.8, pitch 1.1) on the first pulse.
 *
 * WHAT IT LOOKS LIKE: the dolphin carving tight circles
 * around its hurt ally, a column of bubbles boiling up
 * around the patient the whole time, and anything hostile
 * that closes in getting washed backward out of the zone.
 *
 * PERFORMANCE: per tick the circling is one UUID resolve
 * (the patient), one steering write, and no queries; the
 * pulse every 10 ticks adds one bounded query around the
 * patient and at most a handful of effect and velocity
 * writes. Particle budget per activation: at most 20 pulses
 * times 10 = 200 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply. The BUBBLE_COLUMN_UP stream is particles, not
 * a placed bubble column block.
 *
 * CLEANUP: end() has nothing to undo: the patient's effects
 * bleed off on their own 60 tick clocks after the last
 * pulse (see above), and the steering stops with the
 * ability.
 *
 * State tag keys: "patient" (compound holding "id", the
 * rescued ally's UUID), "lost" (1 once the patient is dead
 * or gone and the rescue is over).
 */
public final class RescueCurrentAbility implements MobAbility {

    private static final int DURATION_TICKS = 200;
    private static final int PULSE_INTERVAL = 10;
    private static final int EFFECT_TICKS = 60;
    private static final double PATIENT_SEARCH_RADIUS = 12.0;
    private static final double PUSH_RADIUS = 5.0;
    private static final double ORBIT_RADIUS = 3.0;
    private static final double ORBIT_SPEED = 1.1;
    private static final double ORBIT_STEP = 0.25;
    private static final double HURT_FRACTION = 0.6;

    @Override
    public String id() {
        return "rescue_current";
    }

    @Override
    public String displayName() {
        return "Rescue Current";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0
            && findPatient(ctx) != null;
    }

    @Override
    public void start(AbilityContext ctx) {
        LivingEntity patient = findPatient(ctx);
        if (patient == null) {
            ctx.state().putInt("lost", 1);
            return;
        }
        CompoundTag entry = new CompoundTag();
        entry.store("id", UUIDUtil.CODEC, patient.getUUID());
        ctx.state().put("patient", entry);
        ctx.state().putInt("lost", 0);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        LivingEntity patient = resolvePatient(ctx, level);
        if (patient == null) {
            return; // The next pulse check or isFinished ends it; nothing to steer around.
        }
        Mob caster = ctx.mob();

        // The circling: an orbit point around the patient,
        // advancing every tick.
        double angle = ctx.ticksActive() * ORBIT_STEP;
        DolphinAbilitySupport.steerToward(caster,
            patient.getX() + Math.cos(angle) * ORBIT_RADIUS,
            patient.getY() + 0.3,
            patient.getZ() + Math.sin(angle) * ORBIT_RADIUS,
            ORBIT_SPEED);

        if (ctx.ticksActive() % PULSE_INTERVAL != 0) {
            return; // Cheap early-out: the rescue pulse runs every 10 ticks only.
        }
        rescuePulse(ctx, level, patient);
    }

    /** One rescue pulse: the patient's air and mending, and the push. See the class comment. */
    private void rescuePulse(AbilityContext ctx, ServerLevel level, LivingEntity patient) {
        patient.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, EFFECT_TICKS, 0));
        patient.addEffect(new MobEffectInstance(MobEffects.REGENERATION, EFFECT_TICKS, 0));

        List<LivingEntity> enemies = DolphinAbilitySupport.enemiesInBox(ctx,
            patient.getX(), patient.getY(), patient.getZ(), PUSH_RADIUS, 3.0, 3.0);
        for (LivingEntity enemy : enemies) {
            double dx = enemy.getX() - patient.getX();
            double dz = enemy.getZ() - patient.getZ();
            double distSqr = dx * dx + dz * dz + (enemy.getY() - patient.getY()) * (enemy.getY() - patient.getY());
            if (distSqr > PUSH_RADIUS * PUSH_RADIUS) {
                continue; // The box query is square; the zone is round.
            }
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 0.001) {
                dx = 1.0;
                len = 1.0;
            }
            enemy.setDeltaMovement(dx / len * 0.9, 0.25, dz / len * 0.9);
            enemy.hurtMarked = true;
        }

        // The bubble stream swarms up around the patient.
        level.sendParticles(ParticleTypes.BUBBLE_COLUMN_UP, patient.getX(), patient.getY() + 0.3, patient.getZ(),
            6, 0.7, 0.5, 0.7, 0.1);
        level.sendParticles(ParticleTypes.BUBBLE, patient.getX(), patient.getY() + 0.5, patient.getZ(),
            4, 0.8, 0.6, 0.8, 0.05);
        if (ctx.ticksActive() == PULSE_INTERVAL) {
            level.playSound(null, patient.getX(), patient.getY(), patient.getZ(),
                SoundEvents.DOLPHIN_SWIM, SoundSource.HOSTILE, 0.8f, 1.1f);
        }
    }

    /**
     * The nearest ally within the search radius at or below
     * the hurt fraction, or null. One bounded query; used by
     * canTrigger and start() only.
     */
    private LivingEntity findPatient(AbilityContext ctx) {
        Mob caster = ctx.mob();
        List<LivingEntity> allies = DolphinAbilitySupport.alliesInBox(ctx,
            caster.getX(), caster.getY(), caster.getZ(), PATIENT_SEARCH_RADIUS, 4.0, 4.0);
        LivingEntity best = null;
        double bestSqr = PATIENT_SEARCH_RADIUS * PATIENT_SEARCH_RADIUS;
        for (LivingEntity ally : allies) {
            if (ally.getHealth() > ally.getMaxHealth() * HURT_FRACTION) {
                continue;
            }
            double distSqr = ally.distanceToSqr(caster);
            if (distSqr < bestSqr) {
                bestSqr = distSqr;
                best = ally;
            }
        }
        return best;
    }

    /** Resolves the recorded patient, marking the rescue lost when it is dead or gone. */
    private LivingEntity resolvePatient(AbilityContext ctx, ServerLevel level) {
        CompoundTag entry = ctx.state().getCompoundOrEmpty("patient");
        if (!entry.contains("id")) {
            return null;
        }
        UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
        Entity entity = id != null ? level.getEntity(id) : null;
        if (entity instanceof LivingEntity living && living.isAlive()) {
            return living;
        }
        if (ctx.ticksActive() % PULSE_INTERVAL == 0) {
            ctx.state().putInt("lost", 1);
        }
        return null;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.state().getIntOr("lost", 0) != 0 || ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to undo: the patient's effects bleed off on
        // their own clocks and the steering stops here.
    }
}
