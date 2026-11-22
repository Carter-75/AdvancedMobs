package mod.advancedmobs.ability.vex;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.ShriekParticleOption;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Screech Divebomb (Vex ability 5 of 5, attack).
 *
 * WHAT IT DOES: the vex climbs, lets out a SHRIEK that rolls
 * across the arena as a visible particle ring, and folds
 * its wings into a screaming dive directly at its target.
 * On impact it detonates a small blast of explosion
 * particles and knockback, and survives its own crash
 * thanks to a moment of RESISTANCE timed to the dive. A
 * missed divebomb leaves it briefly grounded and vulnerable,
 * flapping in the dirt.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the vex's current target within 18 blocks (distance
 *   squared 324).
 * - The shriek (start()): 16 SHRIEK particles (delay 0, the
 *   26.2 ShriekParticleOption form) on a ring of radius 2.0
 *   around the vex at its own height plus 0.4, and a
 *   VEX_CHARGE at volume 1.2, pitch 1.0.
 * - Phase 0, the climb (25 ticks): every tick the velocity
 *   is set toward a climb point 10 blocks above the target's
 *   live position at 0.8 blocks per tick (the step is capped
 *   at the remaining distance), so the vex spirals up over
 *   the fight. 1 SMOKE every 2 ticks. If the target dies
 *   during the climb, the climb point is above the vex
 *   itself and the dive phase then finds no target and
 *   misses on purpose (see below).
 * - Phase 1, the dive: at dive entry the vex gains
 *   RESISTANCE II (amplifier 1) for 70 ticks, removed in
 *   end() if it is somehow still running. Every tick the
 *   velocity is set straight at the target's live chest at
 *   1.6 blocks per tick, with 2 FLAME particles trailing.
 *   Impact is the first tick the vex is within 1.5 blocks of
 *   the target: the blast goes off at the vex's position.
 *   One bounded query finds enemies within radius 3.0; each
 *   takes 5.0 mob-attack damage credited to the vex and a
 *   shove away from the blast center of 1.1 horizontal and
 *   0.45 up (hurtMarked set, so player clients apply the
 *   shove). The vex itself is excluded by the enemy rule
 *   and shielded by its RESISTANCE. 1 EXPLOSION_EMITTER and
 *   10 EXPLOSION particles, and a GENERIC_EXPLODE (volume
 *   0.8, pitch 1.2). Then the recover phase.
 * - The miss: if the dive runs 45 ticks without impact, or
 *   the vex falls 3 blocks below the target's feet, or the
 *   target is gone at dive entry, the divebomb has missed.
 *   The vex gains WEAKNESS I for 45 ticks and enters the
 *   grounded phase: for 40 ticks its velocity is pinned to a
 *   slow sink (horizontal damped to 30 percent per tick,
 *   vertical held at -0.3), which drops it to the ground and
 *   holds it there flapping, an easy target. That is the
 *   design's vulnerable miss, priced in.
 * - Phase 3, the recover (after a hit): 12 ticks of damped
 *   hover (velocity times 0.6 per tick) while the vex
 *   shakes off the crash, then the ability ends.
 * - Duration: the phases above; hard finish at 130 ticks.
 * - end(): the RESISTANCE and the WEAKNESS are removed if
 *   still running (both normally expire on their own clocks;
 *   removing them keeps the reversal exact, and if either
 *   was also present from another source it is removed too,
 *   an accepted edge case shared with every fixed-duration
 *   effect in this system).
 *
 * WHAT IT LOOKS LIKE: a shriek ring rolling outward, a
 * small shape climbing fast, then a screaming streak of
 * flame coming straight down that bursts in a pop of
 * explosion smoke; or the same streak sailing past and a
 * vex sitting in the dirt, weak and flapping, while its
 * victim walks away.
 *
 * PERFORMANCE: per tick one distance computation, one
 * velocity write, and at most 2 particles; the blast is a
 * single bounded query on the one impact tick; no per-tick
 * entity scans. Particle budget per activation: shriek ring
 * 16, climb at most 13, dive at most 45 times 2 = 90, blast
 * 11, total at most 130 particles.
 *
 * TERRAIN: the blast is particles, damage and knockback
 * only; it places and breaks no blocks (it is not a real
 * explosion), so the mobGriefing gate does not apply.
 *
 * State tag keys: "phase" (0 climb, 1 dive, 2 grounded,
 * 3 recover), "phase_ticks", "done" (1 when the final phase
 * completes).
 */
public final class ScreechDivebombAbility implements MobAbility {

    private static final int CLIMB_TICKS = 25;
    private static final int DIVE_MAX_TICKS = 45;
    private static final int GROUNDED_TICKS = 40;
    private static final int RECOVER_TICKS = 12;
    private static final int HARD_FINISH_TICKS = 130;
    private static final int RESISTANCE_TICKS = 70;
    private static final double CLIMB_SPEED = 0.8;
    private static final double DIVE_SPEED = 1.6;
    private static final double BLAST_RADIUS = 3.0;
    private static final float BLAST_DAMAGE = 5.0f;
    // 26.2 SHRIEK takes a ShriekParticleOption carrying a
    // delay in ticks (0 here, so the ring shows at once).
    private static final ShriekParticleOption SHRIEK = new ShriekParticleOption(0);

    @Override
    public String id() {
        return "screech_divebomb";
    }

    @Override
    public String displayName() {
        return "Screech Divebomb";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 324.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ctx.state().putInt("phase", 0);
        ctx.state().putInt("phase_ticks", 0);
        ctx.state().putInt("done", 0);
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        // The shriek ring, rolling out from the vex.
        for (int i = 0; i < 16; i++) {
            double angle = (Math.PI * 2.0 * i) / 16.0;
            level.sendParticles(SHRIEK, caster.getX() + Math.cos(angle) * 2.0,
                caster.getY() + 0.4, caster.getZ() + Math.sin(angle) * 2.0,
                1, 0.0, 0.0, 0.0, 0.0);
        }
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.VEX_CHARGE,
            SoundSource.HOSTILE, 1.2f, 1.0f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        int phase = ctx.state().getIntOr("phase", 0);
        int phaseTicks = ctx.state().getIntOr("phase_ticks", 0) + 1;
        ctx.state().putInt("phase_ticks", phaseTicks);
        LivingEntity target = ctx.target();

        if (phase == 0) {
            double climbX = target != null ? target.getX() : caster.getX();
            double climbY = (target != null ? target.getY() : caster.getY()) + 10.0;
            double climbZ = target != null ? target.getZ() : caster.getZ();
            steerToward(ctx, climbX, climbY, climbZ, CLIMB_SPEED);
            if (level != null && ctx.ticksActive() % 2 == 0) {
                level.sendParticles(ParticleTypes.SMOKE, caster.getX(), caster.getY() + 0.3, caster.getZ(),
                    1, 0.1, 0.1, 0.1, 0.0);
            }
            if (phaseTicks >= CLIMB_TICKS) {
                caster.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, RESISTANCE_TICKS, 1, false, false, false));
                setPhase(ctx, 1);
            }
        } else if (phase == 1) {
            if (target == null) {
                miss(ctx);
                return;
            }
            double dist = steerToward(ctx, target.getX(), target.getY() + 0.8, target.getZ(), DIVE_SPEED);
            if (level != null) {
                level.sendParticles(ParticleTypes.FLAME, caster.getX(), caster.getY() + 0.3, caster.getZ(),
                    2, 0.1, 0.1, 0.1, 0.0);
            }
            if (dist <= 1.5) {
                detonate(ctx, level);
                setPhase(ctx, 3);
            } else if (phaseTicks >= DIVE_MAX_TICKS || caster.getY() < target.getY() - 3.0) {
                miss(ctx);
            }
        } else if (phase == 2) {
            // Grounded and vulnerable: pinned to a slow sink, flapping in the dirt.
            Vec3 velocity = caster.getDeltaMovement();
            caster.setDeltaMovement(velocity.x * 0.3, -0.3, velocity.z * 0.3);
            if (phaseTicks >= GROUNDED_TICKS) {
                ctx.state().putInt("done", 1);
            }
        } else {
            caster.setDeltaMovement(caster.getDeltaMovement().scale(0.6));
            if (phaseTicks >= RECOVER_TICKS) {
                ctx.state().putInt("done", 1);
            }
        }
    }

    /** The missed dive (see the class comment): weakness on, grounded phase next. */
    private static void miss(AbilityContext ctx) {
        ctx.mob().addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 45, 0, false, false, false));
        setPhase(ctx, 2);
    }

    /** The impact blast (see the class comment). Runs exactly once per activation. */
    private static void detonate(AbilityContext ctx, ServerLevel level) {
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        double cx = caster.getX();
        double cy = caster.getY();
        double cz = caster.getZ();
        List<LivingEntity> victims = VexAbilitySupport.enemiesInBox(ctx, cx, cy, cz, BLAST_RADIUS, 3.0, 3.0);
        for (LivingEntity victim : victims) {
            victim.hurtServer(level, level.damageSources().mobAttack(caster), BLAST_DAMAGE);
            double dx = victim.getX() - cx;
            double dz = victim.getZ() - cz;
            double len = Math.sqrt(dx * dx + dz * dz);
            double pushX = len > 0.001 ? dx / len * 1.1 : 0.0;
            double pushZ = len > 0.001 ? dz / len * 1.1 : 0.0;
            Vec3 velocity = victim.getDeltaMovement();
            victim.setDeltaMovement(velocity.x + pushX, 0.45, velocity.z + pushZ);
            victim.hurtMarked = true;
        }
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, cx, cy + 0.4, cz, 1, 0.0, 0.0, 0.0, 0.0);
        level.sendParticles(ParticleTypes.EXPLOSION, cx, cy + 0.4, cz, 10, 0.6, 0.5, 0.6, 0.1);
        level.playSound(null, cx, cy, cz, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 0.8f, 1.2f);
    }

    /** Sets the vex's velocity straight at a point and returns the distance to it before the move. */
    private static double steerToward(AbilityContext ctx, double x, double y, double z, double speed) {
        Mob caster = ctx.mob();
        double dx = x - caster.getX();
        double dy = y - caster.getY();
        double dz = z - caster.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 0.001) {
            caster.setDeltaMovement(0.0, 0.0, 0.0);
            return 0.0;
        }
        double step = Math.min(speed, dist);
        caster.setDeltaMovement(dx / dist * step, dy / dist * step, dz / dist * step);
        return dist;
    }

    private static void setPhase(AbilityContext ctx, int phase) {
        ctx.state().putInt("phase", phase);
        ctx.state().putInt("phase_ticks", 0);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.state().getIntOr("done", 0) == 1 || ctx.ticksActive() >= HARD_FINISH_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.removeEffect(MobEffects.RESISTANCE);
        caster.removeEffect(MobEffects.WEAKNESS);
    }
}
