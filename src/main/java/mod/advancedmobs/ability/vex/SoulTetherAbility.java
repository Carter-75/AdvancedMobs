package mod.advancedmobs.ability.vex;

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

/**
 * Soul Tether (Vex ability 4 of 5, aura, control).
 *
 * WHAT IT DOES: the vex latches a spectral chain onto its
 * target. A visible line of SOUL particles links the two for
 * as long as the tether holds; the victim is SLOWED, and
 * every point of damage the vex deals to it comes back to
 * the vex as healing, so the tether makes the vex a parasite
 * that must be peeled off, not ignored. The chain snaps in a
 * puff of SOUL wisps when the victim closes to melee range
 * of the vex, when line of sight stays broken for three
 * seconds, or when the victim gets too far away.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the vex's current target within 12 blocks (distance
 *   squared 144) AND in the vex's line of sight (a chain
 *   cannot latch through a wall).
 * - The latch (start()): the target's UUID is stored as the
 *   victim; its health and the vex's last-hurt-mob timestamp
 *   are primed for the lifesteal poll. The victim gains
 *   SLOWNESS I for 30 ticks immediately.
 * - The poll (tick(), every 5 ticks; other ticks return
 *   immediately): the victim is resolved by UUID. A victim
 *   that is dead or gone ends the tether silently. Otherwise,
 *   in order:
 *   1. Snap checks. Distance over 16.0 blocks snaps the
 *      chain (the victim outran it). Distance at or under
 *      2.5 blocks snaps it too: the design's rule is that
 *      closing to melee range breaks the latch, which is
 *      what makes peeling the vex off a positioning play
 *      rather than a damage race. Line of sight is checked
 *      with the vex's hasLineOfSight on the victim: a failed
 *      check adds 5 to the broken-sight counter, a passed
 *      check resets it to 0, and at 60 (three seconds of
 *      broken sight) the chain snaps.
 *   2. The slow: every 10 ticks the victim's SLOWNESS I is
 *      refreshed to 25 ticks, so it never lapses while the
 *      tether holds and bleeds off within about a second of
 *      the tether ending.
 *   3. The lifesteal: the vex's bite clock (its
 *      last-hurt-mob and that timestamp, the Endermite
 *      pattern) is compared with the primed values. When the
 *      timestamp has advanced and the last hurt mob is the
 *      victim, the vex landed a hit since the last poll, and
 *      the victim's observed health drop since the last poll
 *      is the damage window: the vex heals 50 percent of
 *      that drop, capped at 6.0 per poll, with a 4-SOUL
 *      burst at the vex. The victim's health is re-recorded
 *      every poll either way, so damage from other sources
 *      between two vex hits is never credited to the vex
 *      beyond the one poll window in which its hit landed;
 *      that small over-credit inside a shared window is an
 *      accepted approximation, stated here so it is not
 *      mistaken for a bug.
 *   4. The chain visual: 1 SOUL particle at each point along
 *      the straight line from the vex's center to the
 *      victim's center, points spaced 1.5 blocks apart.
 * - The snap: 12 SOUL particles burst at the midpoint of the
 *   chain and a VEX_AMBIENT plays (volume 1.0, pitch 0.7),
 *   then the activation ends.
 * - Duration: 300 ticks (15 seconds) at most.
 * - end(): the victim's SLOWNESS is removed if the victim
 *   still resolves (the effect would bleed off in about a
 *   second anyway; removing it keeps the reversal exact. If
 *   the victim had SLOWNESS from another source it is
 *   removed too, an accepted edge case shared with every
 *   fixed-duration effect in this system). If the caster
 *   dies first, the framework termination path runs this
 *   same end(), so the slow never outlives the parasite.
 *
 * WHAT IT LOOKS LIKE: a drifting chain of pale soul motes
 * strung between the vex and its victim, the victim dragging
 * as it moves, little soul bursts flowing back into the vex
 * each time its sword lands, and a wisp puff when the chain
 * breaks.
 *
 * PERFORMANCE: tick() returns immediately except every 5th
 * tick. The poll is one UUID resolve, at most two distance
 * computations, one line-of-sight check, one timestamp
 * compare, and the chain draw: no entity scans anywhere.
 * Particle budget per activation: the chain is at most 11
 * points (16 blocks at 1.5 spacing) every 5 ticks over 300
 * ticks = at most 660, lifesteal bursts at most 4 per poll
 * but only on polls where a hit landed (bounded by the same
 * 60 polls, worst case 240), the snap 12; the stated cap is
 * 660 chain motes plus bursts, about 900 worst case.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "victim" (the tethered target's UUID),
 * "last_health" (the victim's health at the last poll),
 * "hit_ts" (the primed bite-clock timestamp), "los_ticks"
 * (consecutive broken-sight ticks), "snapped" (1 once the
 * chain has snapped or the victim is gone).
 */
public final class SoulTetherAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int POLL_INTERVAL = 5;
    private static final double SNAP_FAR_DISTANCE = 16.0;
    private static final double SNAP_MELEE_DISTANCE = 2.5;
    private static final int SNAP_LOS_TICKS = 60;
    private static final double LIFESTEAL_FRACTION = 0.5;
    private static final float LIFESTEAL_CAP = 6.0f;

    @Override
    public String id() {
        return "soul_tether";
    }

    @Override
    public String displayName() {
        return "Soul Tether";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0
            && ctx.mob().hasLineOfSight(target);
    }

    @Override
    public void start(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        ctx.state().putInt("snapped", 0);
        ctx.state().putInt("los_ticks", 0);
        ctx.state().putInt("hit_ts", ctx.mob().getLastHurtMobTimestamp());
        if (target != null) {
            ctx.state().store("victim", UUIDUtil.CODEC, target.getUUID());
            ctx.state().putFloat("last_health", target.getHealth());
            target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 30, 0));
        }
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            Mob caster = ctx.mob();
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.VEX_AMBIENT,
                SoundSource.HOSTILE, 1.0f, 0.8f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % POLL_INTERVAL != 0) {
            return; // Cheap early-out: the tether polls every 5 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        LivingEntity victim = VexAbilitySupport.resolveByUuid(level, ctx.state(), "victim");
        if (victim == null || !victim.isAlive()) {
            ctx.state().putInt("snapped", 1); // The victim is gone; the tether ends silently.
            return;
        }

        double dist = caster.distanceTo(victim);
        boolean snap = dist > SNAP_FAR_DISTANCE || dist <= SNAP_MELEE_DISTANCE;
        if (!snap) {
            if (caster.hasLineOfSight(victim)) {
                ctx.state().putInt("los_ticks", 0);
            } else {
                int los = ctx.state().getIntOr("los_ticks", 0) + POLL_INTERVAL;
                ctx.state().putInt("los_ticks", los);
                snap = los >= SNAP_LOS_TICKS;
            }
        }
        if (snap) {
            double midX = (caster.getX() + victim.getX()) / 2.0;
            double midY = (caster.getY() + victim.getY()) / 2.0 + 0.5;
            double midZ = (caster.getZ() + victim.getZ()) / 2.0;
            level.sendParticles(ParticleTypes.SOUL, midX, midY, midZ, 12, 0.4, 0.4, 0.4, 0.05);
            level.playSound(null, midX, midY, midZ, SoundEvents.VEX_AMBIENT,
                SoundSource.HOSTILE, 1.0f, 0.7f);
            ctx.state().putInt("snapped", 1);
            return;
        }

        // The slow, refreshed on a 10-tick cadence inside the poll.
        if (ctx.ticksActive() % 10 == 0) {
            victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 25, 0));
        }

        // The lifesteal, off the bite clock (see the class comment).
        int timestamp = caster.getLastHurtMobTimestamp();
        float lastHealth = ctx.state().getFloatOr("last_health", victim.getHealth());
        if (timestamp != ctx.state().getIntOr("hit_ts", 0) && caster.getLastHurtMob() == victim) {
            float drop = lastHealth - victim.getHealth();
            if (drop > 0.0f) {
                float heal = Math.min(drop * (float) LIFESTEAL_FRACTION, LIFESTEAL_CAP);
                if (heal > 0.0f) {
                    caster.heal(heal);
                    level.sendParticles(ParticleTypes.SOUL, caster.getX(), caster.getY() + 0.5, caster.getZ(),
                        4, 0.25, 0.3, 0.25, 0.05);
                }
            }
        }
        if (timestamp != ctx.state().getIntOr("hit_ts", 0)) {
            ctx.state().putInt("hit_ts", timestamp);
        }
        ctx.state().putFloat("last_health", victim.getHealth());

        // The chain visual: soul motes strung between the two.
        double x0 = caster.getX();
        double y0 = caster.getY() + 0.4;
        double z0 = caster.getZ();
        double dx = victim.getX() - x0;
        double dy = (victim.getY() + 0.8) - y0;
        double dz = victim.getZ() - z0;
        int points = Math.max(1, (int) (dist / 1.5));
        for (int i = 1; i <= points; i++) {
            double f = (double) i / (points + 1);
            level.sendParticles(ParticleTypes.SOUL, x0 + dx * f, y0 + dy * f, z0 + dz * f,
                1, 0.05, 0.05, 0.05, 0.0);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.state().getIntOr("snapped", 0) == 1 || ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        LivingEntity victim = VexAbilitySupport.resolveByUuid(level, ctx.state(), "victim");
        if (victim != null) {
            victim.removeEffect(MobEffects.SLOWNESS);
        }
    }
}
