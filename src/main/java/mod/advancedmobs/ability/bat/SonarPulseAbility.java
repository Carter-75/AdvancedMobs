package mod.advancedmobs.ability.bat;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SculkChargeParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ambient.Bat;

import java.util.List;

/**
 * Sonar Pulse (Bat ability 1 of 5, control).
 *
 * WHAT IT DOES: the bat hangs in place, listens, and fires a
 * pulse of sound drawn as expanding rings of end rod sparks
 * and rolling sculk charges sweeping out through the cave.
 * Every enemy a ring passes over is stamped with GLOWING,
 * outlined through walls and darkness, and once the cave is
 * lit up the bat leaves its perch and hunts the revealed
 * targets one by one, biting each for bonus damage because
 * it can hear exactly where it is.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the bat's current target within 16 blocks (distance
 *   squared 256).
 * - The perch (start()): the pulse center is recorded as the
 *   bat's position at cast and never moves. A resting bat
 *   takes off (setResting(false), BAT_TAKEOFF, volume 1.0,
 *   pitch 1.0) and a BAT_AMBIENT screech (volume 1.2, pitch
 *   1.4) marks the pulse firing. During the pulse phase the
 *   bat is steered back onto the center point every tick at
 *   0.5 blocks per tick, so it hangs at the heart of its own
 *   rings instead of wandering off mid-cast.
 * - The rings (ticks 1 to 60, one ring every 5 ticks, 12
 *   rings): ring k has radius 1.5 times k (1.5 out to 18.0).
 *   Each ring is drawn as 12 points around the center at the
 *   center's height, alternating END_ROD (even points) and
 *   SCULK_CHARGE (odd points, SculkChargeParticleOptions
 *   whose roll is the point's angle around the ring, the
 *   design's roll angle line). On the same tick, one bounded
 *   query (box of the ring radius plus 1.0 around the
 *   center, 3.0 below to 3.0 above) finds the enemies the
 *   ring has reached: an enemy whose horizontal distance
 *   from the center is at most the ring radius plus 0.75 and
 *   who is not already revealed gains GLOWING for 200 ticks
 *   and is recorded in the revealed list (cap 12 entries;
 *   enemies past the cap still glow but are not hunted).
 * - The hunt (ticks 61 to 200): every 5 ticks the bat picks
 *   its quarry, the nearest living revealed enemy, and
 *   records its UUID and position. Every tick the bat steers
 *   at the quarry's live chest (feet plus 0.6) at 0.75
 *   blocks per tick. When the bat closes within 1.5 blocks
 *   and at least 12 ticks have passed since the last bite,
 *   the bite lands: a flat 4.0 damage through
 *   damageSources().mobAttack(caster), 4 CRIT particles at
 *   the victim's chest, and a BAT_AMBIENT (volume 0.8, pitch
 *   1.6). The 4.0 is the design's bonus damage against
 *   revealed targets carried in code: a bat has no
 *   ATTACK_DAMAGE attribute instance at all (see the support
 *   class comment), so there is no sheet value to modify;
 *   4.0 is double the 2.0 a plain code bite would use in
 *   this mod, and it is only ever dealt to revealed targets
 *   during the hunt. A quarry that dies or unloads is dropped
 *   at the next 5-tick pick; if a pick after tick 80 finds no
 *   living revealed enemy at all, the hunt is over and the
 *   ability finishes.
 * - Duration: the phases above; hard finish at 200 ticks.
 * - end(): nothing to reverse. The bat applied no attribute
 *   modifiers and no effects to itself; the victims' GLOWING
 *   is a fixed-duration effect that expires on its own clock,
 *   the standard treatment for victim effects in this system.
 *
 * WHAT IT LOOKS LIKE: a screech, then ring after ring of
 * pale sparks and teal charges rolling outward through the
 * dark, each ring lighting up the shapes it washes over;
 * then the bat peels off its perch and darts from outline to
 * outline, biting glowing targets out of the black.
 *
 * PERFORMANCE: the pulse phase is one steering write per
 * tick and, every 5 ticks, 12 particle sends plus one
 * bounded entity query; the hunt is one steering write per
 * tick, at most 12 UUID resolves every 5 ticks, and one
 * damage call per bite. Particle budget per activation:
 * rings 12 times 12 = 144, bites at most 11 times 4 = 44,
 * total at most 188 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "cx", "cy", "cz" (the fixed pulse center),
 * "revealed" (list of compounds, one per revealed enemy:
 * "id"), "quarry" (the hunted enemy's UUID), "last_bite"
 * (tick of the last bite), "done" (1 when the hunt ends
 * early).
 */
public final class SonarPulseAbility implements MobAbility {

    private static final int PULSE_TICKS = 60;
    private static final int RING_INTERVAL = 5;
    private static final int RING_POINTS = 12;
    private static final double RING_STEP = 1.5;
    private static final int HARD_FINISH_TICKS = 200;
    private static final int REVEAL_CAP = 12;
    private static final int GLOW_TICKS = 200;
    private static final double HUNT_SPEED = 0.75;
    private static final double BITE_DISTANCE = 1.5;
    private static final int BITE_INTERVAL = 12;
    private static final float BITE_DAMAGE = 4.0f;

    @Override
    public String id() {
        return "sonar_pulse";
    }

    @Override
    public String displayName() {
        return "Sonar Pulse";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        Mob caster = ctx.mob();
        state.putDouble("cx", caster.getX());
        state.putDouble("cy", caster.getY());
        state.putDouble("cz", caster.getZ());
        state.put("revealed", new ListTag());
        state.putInt("last_bite", -BITE_INTERVAL);
        state.putInt("done", 0);
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        if (caster instanceof Bat bat && bat.isResting()) {
            bat.setResting(false);
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.BAT_TAKEOFF,
                SoundSource.HOSTILE, 1.0f, 1.0f);
        }
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.BAT_AMBIENT,
            SoundSource.HOSTILE, 1.2f, 1.4f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        if (ticks <= PULSE_TICKS) {
            // Hang at the heart of the rings while they expand.
            BatAbilitySupport.steerToward(ctx,
                ctx.state().getDoubleOr("cx", ctx.mob().getX()),
                ctx.state().getDoubleOr("cy", ctx.mob().getY()),
                ctx.state().getDoubleOr("cz", ctx.mob().getZ()), 0.5);
            if (ticks % RING_INTERVAL == 0) {
                fireRing(ctx, level, ticks / RING_INTERVAL);
            }
        } else {
            hunt(ctx, level, ticks);
        }
    }

    /** One expanding ring: draw it, then reveal every enemy it has reached. */
    private static void fireRing(AbilityContext ctx, ServerLevel level, int ringIndex) {
        CompoundTag state = ctx.state();
        double cx = state.getDoubleOr("cx", ctx.mob().getX());
        double cy = state.getDoubleOr("cy", ctx.mob().getY());
        double cz = state.getDoubleOr("cz", ctx.mob().getZ());
        double radius = RING_STEP * ringIndex;
        for (int i = 0; i < RING_POINTS; i++) {
            double angle = (Math.PI * 2.0 * i) / RING_POINTS;
            double px = cx + Math.cos(angle) * radius;
            double pz = cz + Math.sin(angle) * radius;
            if (i % 2 == 0) {
                level.sendParticles(ParticleTypes.END_ROD, px, cy + 0.4, pz, 1, 0.0, 0.0, 0.0, 0.0);
            } else {
                level.sendParticles(new SculkChargeParticleOptions((float) angle),
                    px, cy + 0.4, pz, 1, 0.0, 0.0, 0.0, 0.0);
            }
        }
        List<LivingEntity> reached = BatAbilitySupport.enemiesInBox(ctx, cx, cy, cz, radius + 1.0, 3.0, 3.0);
        ListTag revealed = state.getListOrEmpty("revealed");
        for (LivingEntity enemy : reached) {
            double dx = enemy.getX() - cx;
            double dz = enemy.getZ() - cz;
            if (dx * dx + dz * dz > (radius + 0.75) * (radius + 0.75)) {
                continue; // Inside the query box but the ring has not reached it yet.
            }
            enemy.addEffect(new MobEffectInstance(MobEffects.GLOWING, GLOW_TICKS, 0));
            if (revealed.size() < REVEAL_CAP && !alreadyRevealed(revealed, enemy)) {
                BatAbilitySupport.trackEntity(state, "revealed", enemy);
                revealed = state.getListOrEmpty("revealed");
            }
        }
    }

    /** Whether the enemy's UUID is already in the revealed list. */
    private static boolean alreadyRevealed(ListTag revealed, LivingEntity enemy) {
        for (int i = 0; i < revealed.size(); i++) {
            CompoundTag entry = revealed.getCompoundOrEmpty(i);
            if (entry.contains("id")
                && enemy.getUUID().equals(entry.read("id", net.minecraft.core.UUIDUtil.CODEC).orElse(null))) {
                return true;
            }
        }
        return false;
    }

    /** The hunt phase: pick a revealed quarry every 5 ticks, chase it, bite on contact. */
    private static void hunt(AbilityContext ctx, ServerLevel level, int ticks) {
        CompoundTag state = ctx.state();
        Mob caster = ctx.mob();
        if (ticks % 5 == 0) {
            pickQuarry(ctx, level, ticks);
        }
        LivingEntity quarry = BatAbilitySupport.resolveByUuid(level, state, "quarry");
        if (quarry == null || !quarry.isAlive()) {
            return;
        }
        double dist = BatAbilitySupport.steerToward(ctx,
            quarry.getX(), quarry.getY() + 0.6, quarry.getZ(), HUNT_SPEED);
        int lastBite = state.getIntOr("last_bite", -BITE_INTERVAL);
        if (dist <= BITE_DISTANCE && ticks - lastBite >= BITE_INTERVAL) {
            state.putInt("last_bite", ticks);
            quarry.hurtServer(level, level.damageSources().mobAttack(caster), BITE_DAMAGE);
            level.sendParticles(ParticleTypes.CRIT, quarry.getX(), quarry.getY() + 0.6, quarry.getZ(),
                4, 0.25, 0.3, 0.25, 0.1);
            level.playSound(null, quarry.getX(), quarry.getY(), quarry.getZ(), SoundEvents.BAT_AMBIENT,
                SoundSource.HOSTILE, 0.8f, 1.6f);
        }
    }

    /** Picks the nearest living revealed enemy as the quarry; ends the hunt when none remain. */
    private static void pickQuarry(AbilityContext ctx, ServerLevel level, int ticks) {
        CompoundTag state = ctx.state();
        Mob caster = ctx.mob();
        ListTag revealed = state.getListOrEmpty("revealed");
        LivingEntity best = null;
        double bestSqr = Double.MAX_VALUE;
        for (int i = 0; i < revealed.size(); i++) {
            CompoundTag entry = revealed.getCompoundOrEmpty(i);
            if (BatAbilitySupport.resolveTracked(level, entry) instanceof LivingEntity living
                && living.isAlive()) {
                double sqr = caster.distanceToSqr(living);
                if (sqr < bestSqr) {
                    bestSqr = sqr;
                    best = living;
                }
            }
        }
        if (best != null) {
            state.store("quarry", net.minecraft.core.UUIDUtil.CODEC, best.getUUID());
        } else {
            state.remove("quarry");
            if (ticks > PULSE_TICKS + 20) {
                // Nothing left to hunt: the cave is clear.
                state.putInt("done", 1);
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.state().getIntOr("done", 0) == 1 || ctx.ticksActive() >= HARD_FINISH_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to reverse: no caster modifiers or effects
        // were applied (see the class comment).
    }
}
