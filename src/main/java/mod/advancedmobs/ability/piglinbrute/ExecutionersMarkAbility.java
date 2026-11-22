package mod.advancedmobs.ability.piglinbrute;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.List;
import java.util.UUID;

/**
 * Executioner's Mark (Piglin Brute ability 4 of 5, attack).
 *
 * WHAT IT DOES: the brute studies the fight, picks the most
 * wounded enemy in reach, and marks it for death: blood-red
 * DAMAGE_INDICATOR particles bleed above the victim's head
 * while the brute hunts it down with a burst of SPEED and
 * STRENGTH aimed at that one target. Against a marked enemy
 * already below a quarter health, the brute's axe hits land
 * as executions. When the mark dies, the brute roars and
 * picks a new one.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   at least one enemy (PiglinBruteAbilitySupport rules)
 *   within 16 blocks of the brute (one bounded query). The
 *   mark need not be the framework's current target; the
 *   brute chooses its own victim.
 * - The pick (start(), and again on every re-mark): among
 *   enemies within 16 blocks, the one with the lowest health
 *   fraction (health over max health); ties break to the
 *   nearest. The pick's UUID is stored in the state tag.
 * - The hunt buffs: SPEED I and STRENGTH I on the brute,
 *   applied in start() for 320 ticks and refreshed every 20
 *   ticks at 30 ticks for as long as a mark lives (so a
 *   cleansed brute re-buffs within a second), and removed in
 *   end(). The buffs are general while they last, not
 *   target-locked: the design's "aimed at that one target"
 *   is carried by the execution rule below and by the
 *   brute's own targeting, which the ability does not steer.
 * - The bleeding tell: every 4 ticks, 4 DAMAGE_INDICATOR
 *   particles are sent above the mark's head (its feet plus
 *   its bounding box height plus 0.4, spread 0.3): the mark
 *   is visible to everyone, which is the point.
 * - The execution: landed melee hits are polled through the
 *   bite clock (primeHitClock and consumeLandedHit, primed
 *   in start()). When a fresh hit lands on the MARK and the
 *   mark's health fraction AFTER that hit is below 0.25, the
 *   hit was an execution: the mark takes a further 6.0
 *   points of typed mob-attack damage credited to the brute
 *   (roughly doubling the axe blow), with an 8-particle
 *   DAMAGE_INDICATOR burst at the mark and a PIGLIN_BRUTE_
 *   ANGRY snarl at pitch 1.3. At most one execution bonus
 *   lands per 10 ticks (the "exec_tick" guard): the bonus
 *   damage is itself a brute-credited hit and can advance
 *   the bite clock, and the guard keeps that from chaining
 *   into a second bonus on the following ticks.
 * - The re-mark: when the mark resolves to nothing (dead,
 *   removed, or unloaded), the brute roars (PIGLIN_BRUTE_
 *   ANGRY, volume 1.3, pitch 1.1, the design's roar) and
 *   picks a new mark by the same rule. The design names the
 *   dies-to-anyone-else case; the re-pick runs on any mark
 *   death, the brute's own executions included, because a
 *   brute that just executed its mark claiming the next
 *   weakest enemy is the same behavior from every angle a
 *   player can see. If no enemy is in reach, the activation
 *   ends early (the "done" flag) instead of idling out its
 *   duration.
 * - Duration: 300 ticks (15 seconds) at most, or until a
 *   mark dies with no replacement in reach.
 *
 * WHAT IT LOOKS LIKE: a PIGLIN_BRUTE_ANGRY roar (volume 1.2)
 * when the first mark is chosen, blood particles bleeding
 * steadily above the doomed enemy's head, the brute moving
 * and hitting visibly faster and harder (the vanilla SPEED
 * and STRENGTH presentation), a bigger blood burst and a
 * snarl on every execution, and a roar each time a new mark
 * is claimed.
 *
 * TERRAIN: this ability places and breaks no blocks in
 * either mobGriefing mode, so the terrain gate does not
 * apply.
 *
 * PERFORMANCE: the mark pick is one bounded query in start()
 * and on re-marks only; per tick the work is one UUID
 * resolve, a 4-particle send every 4th tick, a bite-clock
 * timestamp compare, and an effect refresh every 20th tick.
 * Particle budget per activation: tell 75 sends times 4 =
 * 300, executions generously 6 times 8 = 48, total at most
 * 348.
 *
 * CLEANUP: end() removes the SPEED and STRENGTH instances
 * from the brute (the hunt ends with the ability; a brute
 * that somehow had either effect from another source loses
 * it at end(), an accepted edge case shared with every
 * fixed-duration effect in this system). The mark itself is
 * only a stored UUID and needs no cleanup.
 *
 * State tag keys: "mark" (the marked enemy's UUID),
 * "done" (1 when the activation should finish early),
 * "exec_tick" (ticksActive of the last execution bonus),
 * "hit_ts" (the bite clock, owned by
 * PiglinBruteAbilitySupport).
 */
public final class ExecutionersMarkAbility implements MobAbility {

    static final int DURATION_TICKS = 300;
    private static final double MARK_RADIUS = 16.0;
    private static final double EXECUTION_FRACTION = 0.25;
    private static final float EXECUTION_DAMAGE = 6.0f;
    private static final int EXECUTION_INTERVAL = 10;

    @Override
    public String id() {
        return "executioners_mark";
    }

    @Override
    public String displayName() {
        return "Executioner's Mark";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return !PiglinBruteAbilitySupport.enemiesAroundSelf(ctx, MARK_RADIUS, 3.0, 3.0).isEmpty();
    }

    @Override
    public void start(AbilityContext ctx) {
        PiglinBruteAbilitySupport.primeHitClock(ctx);
        ctx.state().putInt("done", 0);
        ctx.state().putInt("exec_tick", -EXECUTION_INTERVAL);
        LivingEntity mark = pickMark(ctx);
        if (mark == null) {
            ctx.state().putInt("done", 1);
            return;
        }
        ctx.state().store("mark", UUIDUtil.CODEC, mark.getUUID());
        applyHuntBuffs(ctx, DURATION_TICKS + 20);
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            Mob mob = ctx.mob();
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.PIGLIN_BRUTE_ANGRY,
                SoundSource.HOSTILE, 1.2f, 1.0f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        LivingEntity mark = resolveMark(ctx, level);
        if (mark == null) {
            // The mark is gone: roar, and claim the next weakest enemy.
            LivingEntity next = pickMark(ctx);
            if (next == null) {
                ctx.state().putInt("done", 1);
                return;
            }
            ctx.state().store("mark", UUIDUtil.CODEC, next.getUUID());
            mark = next;
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.PIGLIN_BRUTE_ANGRY,
                SoundSource.HOSTILE, 1.3f, 1.1f);
        }
        // The hunt buffs stay up while a mark lives.
        if (ctx.ticksActive() % 20 == 0) {
            applyHuntBuffs(ctx, 30);
        }
        // The bleeding tell above the mark's head.
        if (ctx.ticksActive() % 4 == 0) {
            level.sendParticles(ParticleTypes.DAMAGE_INDICATOR,
                mark.getX(), mark.getY() + mark.getBbHeight() + 0.4, mark.getZ(),
                4, 0.3, 0.15, 0.3, 0.0);
        }
        // The execution: a fresh axe hit on a dying mark lands double.
        LivingEntity victim = PiglinBruteAbilitySupport.consumeLandedHit(ctx);
        if (victim != null && victim == mark
            && victim.getHealth() / victim.getMaxHealth() < EXECUTION_FRACTION
            && ctx.ticksActive() - ctx.state().getIntOr("exec_tick", -EXECUTION_INTERVAL) >= EXECUTION_INTERVAL) {
            ctx.state().putInt("exec_tick", ctx.ticksActive());
            victim.hurtServer(level, level.damageSources().mobAttack(mob), EXECUTION_DAMAGE);
            level.sendParticles(ParticleTypes.DAMAGE_INDICATOR,
                victim.getX(), victim.getY() + victim.getBbHeight() + 0.4, victim.getZ(),
                8, 0.4, 0.2, 0.4, 0.0);
            level.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
                SoundEvents.PIGLIN_BRUTE_ANGRY, SoundSource.HOSTILE, 0.8f, 1.3f);
        }
    }

    /** The most wounded enemy within reach: lowest health fraction, ties to the nearest. */
    private LivingEntity pickMark(AbilityContext ctx) {
        List<LivingEntity> enemies = PiglinBruteAbilitySupport.enemiesAroundSelf(ctx, MARK_RADIUS, 3.0, 3.0);
        LivingEntity best = null;
        double bestFraction = Double.MAX_VALUE;
        double bestDistanceSqr = Double.MAX_VALUE;
        for (LivingEntity enemy : enemies) {
            double fraction = enemy.getHealth() / enemy.getMaxHealth();
            double distanceSqr = ctx.mob().distanceToSqr(enemy);
            if (fraction < bestFraction || (fraction == bestFraction && distanceSqr < bestDistanceSqr)) {
                best = enemy;
                bestFraction = fraction;
                bestDistanceSqr = distanceSqr;
            }
        }
        return best;
    }

    /** Resolves the stored mark, or null when it is dead, gone, or was never stored. */
    private LivingEntity resolveMark(AbilityContext ctx, ServerLevel level) {
        UUID id = ctx.state().read("mark", UUIDUtil.CODEC).orElse(null);
        if (id != null && level.getEntity(id) instanceof LivingEntity living && living.isAlive()) {
            return living;
        }
        return null;
    }

    /** Applies or refreshes the hunt buffs on the brute. */
    private void applyHuntBuffs(AbilityContext ctx, int durationTicks) {
        Mob mob = ctx.mob();
        mob.addEffect(new MobEffectInstance(MobEffects.SPEED, durationTicks, 0));
        mob.addEffect(new MobEffectInstance(MobEffects.STRENGTH, durationTicks, 0));
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS || ctx.state().getIntOr("done", 0) == 1;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        mob.removeEffect(MobEffects.SPEED);
        mob.removeEffect(MobEffects.STRENGTH);
    }
}
