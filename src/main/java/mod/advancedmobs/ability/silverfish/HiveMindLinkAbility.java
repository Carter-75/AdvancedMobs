package mod.advancedmobs.ability.silverfish;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.List;

/**
 * Hive Mind Link (Silverfish ability 5 of 5, aura).
 *
 * WHAT IT DOES: the silverfish becomes a relay for the swarm:
 * ENCHANT glyphs stream between it and every allied silverfish
 * and endermite in range in a visible web of light. Linked
 * allies gain SPEED and STRENGTH while they stay near the
 * relay, turning a scattered swarm into a coordinated pack
 * with one small fish at its heart.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the fish's current target within 14 blocks (distance
 *   squared 196), plus at least 1 swarm ally already inside
 *   the link radius (see below): a relay with no swarm to
 *   relay never starts, and its cooldown is never wasted on
 *   an empty web. The ally check is the one bounded query
 *   SilverfishAbilitySupport.swarmAllies runs; canTrigger may
 *   be polled every tick while the fish is idle and off
 *   cooldown, and the query is two typed lookups in a 20 block
 *   box, the same cost class as the pulse itself.
 * - Duration: exactly 200 ticks (10 seconds) from start().
 * - The link radius: 10 blocks horizontal, 3 below to 3 above
 *   the relay. A swarm ally is a silverfish or endermite,
 *   alive, allied with the relay in either direction (the
 *   SWARM RULE in SilverfishAbilitySupport: the war system's
 *   alliance records, the design's "/team team assignment").
 *   At most 8 allies are linked at once, nearest first: the
 *   cap is the particle and effect bound.
 * - The pulse, every 20 ticks starting at tick 20: the linked
 *   allies are re-found (allies wander in and out of range, so
 *   membership is per-pulse, never stored), and each gains
 *   SPEED I (amplifier 0) and STRENGTH I (amplifier 0) for 30
 *   ticks: the 30 tick application covers the 20 tick pulse
 *   interval with 10 ticks of slack, so a linked ally's buffs
 *   never flicker between pulses, and an ally that leaves the
 *   web keeps its last buff for at most 30 ticks before it
 *   fades. The relay itself gains nothing: it is the heart,
 *   not a limb.
 * - The web visual, on the same pulse: for each linked ally,
 *   3 ENCHANT particles are sent strung along the line from
 *   the relay to the ally, at one third and two thirds of the
 *   way across and at the ally itself, each at body height
 *   (0.3 above the feet) with a tight 0.15 spread: a visible
 *   thread of glyphs per link, redrawn every second.
 * - end(): nothing to clean up. The relay applies no attribute
 *   modifiers and spawns nothing; the allies' buffs are timed
 *   30 tick instances that expire on their own within a second
 *   and a half of the last pulse, the same fade-out every
 *   pulse-style aura in this system leaves.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026): this is a zone effect,
 * not block placement: no block is placed, broken, or moved,
 * and nothing here is gated on mobGriefing. The web is
 * particles and the buffs are effects; the world is left
 * exactly as it was.
 *
 * WHAT IT LOOKS LIKE: once a second, threads of glowing
 * ENCHANT glyphs stitch the little gray relay to every fish
 * and mite around it, a web of light with the swarm moving
 * visibly faster and hitting visibly harder inside it.
 *
 * PERFORMANCE: canTrigger is distance checks plus one bounded
 * two-query ally scan (see above). tick() returns immediately
 * except every 20th tick, where the pulse runs the same
 * bounded scan and applies at most 8 times 2 effects and 8
 * times 3 particles. No state is kept between pulses, so a
 * resumed activation after a chunk reload pulses correctly
 * from the tick counter alone. Particle budget per
 * activation: 10 pulses times at most 8 links times 3 = at
 * most 240 ENCHANT particles.
 *
 * State tag keys: none (membership is re-derived every pulse).
 */
public final class HiveMindLinkAbility implements MobAbility {

    static final int DURATION_TICKS = 200;
    private static final int PULSE_INTERVAL = 20;
    private static final double LINK_RADIUS = 10.0;
    private static final int MAX_LINKS = 8;
    private static final int BUFF_TICKS = 30;

    @Override
    public String id() {
        return "hive_mind_link";
    }

    @Override
    public String displayName() {
        return "Hive Mind Link";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null || ctx.mob().distanceToSqr(target) > 196.0) {
            return false;
        }
        // A relay with no swarm to relay never starts.
        return !SilverfishAbilitySupport.swarmAllies(ctx, LINK_RADIUS, MAX_LINKS).isEmpty();
    }

    @Override
    public void start(AbilityContext ctx) {
        // Nothing to initialize: membership is re-derived every
        // pulse, and the first pulse lands at tick 20.
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % PULSE_INTERVAL != 0) {
            return; // Cheap early-out: pulses run every 20 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob relay = ctx.mob();
        List<LivingEntity> allies = SilverfishAbilitySupport.swarmAllies(ctx, LINK_RADIUS, MAX_LINKS);
        for (LivingEntity ally : allies) {
            // The buffs: faster and harder while the link holds.
            ally.addEffect(new MobEffectInstance(MobEffects.SPEED, BUFF_TICKS, 0));
            ally.addEffect(new MobEffectInstance(MobEffects.STRENGTH, BUFF_TICKS, 0));

            // The thread: glyphs strung from the relay to the
            // ally, redrawn every pulse.
            double dx = ally.getX() - relay.getX();
            double dy = (ally.getY() + 0.3) - (relay.getY() + 0.3);
            double dz = ally.getZ() - relay.getZ();
            for (int i = 1; i <= 3; i++) {
                double fraction = i / 3.0;
                level.sendParticles(ParticleTypes.ENCHANT,
                    relay.getX() + dx * fraction, relay.getY() + 0.3 + dy * fraction, relay.getZ() + dz * fraction,
                    1, 0.15, 0.15, 0.15, 0.02);
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Intentionally empty: no modifiers, no spawns, and the
        // allies' timed buffs expire on their own (see the class
        // comment).
    }
}
