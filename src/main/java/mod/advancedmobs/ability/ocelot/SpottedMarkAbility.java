package mod.advancedmobs.ability.ocelot;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Spotted Mark (Ocelot ability 5 of 5, control / aura).
 *
 * WHAT IT DOES: the ocelot fixes its prey with a stare
 * and the prey lights up: a glowing outline visible
 * through walls, a mark that stays on it for the whole
 * ability. Marked prey is slowed, and every strike the
 * ocelot lands on it bites deeper than a normal strike.
 * The mark is the fight now: there is nowhere to hide and
 * no shaking it off.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the ocelot's current target within 16 blocks
 *   (distance squared 256).
 * - The marking (start()): the ocelot's current target
 *   becomes the marked prey. Its UUID is stored in the
 *   state tag ("marked"), it gains the scoreboard tag
 *   "advancedmobs_spotted_mark" (the design's tag for the
 *   marked state; removed in end()), and it is lit up at
 *   once: GLOWING and SLOWNESS I, each for 40 ticks (the
 *   refresh below keeps them up). 12 ENCHANT motes burst
 *   over the prey as the mark lands (the design's enchant
 *   particle on marking), with OCELOT_AMBIENT at pitch
 *   0.9, low and fixed.
 * - The refresh (tick(), every 20 ticks): the marked
 *   entity is resolved from its stored UUID (one level
 *   lookup, no area query). If it is dead or gone, the
 *   "mark_lost" flag is set and the ability ends: the
 *   mark dies with its prey. Otherwise GLOWING and
 *   SLOWNESS I are refreshed to 40 ticks each, and 2
 *   ENCHANT motes sparkle over the prey as the visible
 *   pulse of the mark renewing.
 * - The hunt (tick(), every 5 ticks): the ocelot is
 *   navigation-driven onto the marked prey at speed 1.3
 *   while it is more than 2.2 blocks away. The ocelot
 *   hunts the marked entity itself, resolved live, not
 *   whatever its current target happens to be: the mark
 *   is the target.
 * - The marked strike (tick(), every 5 ticks, in reach):
 *   within 2.2 blocks (distance squared 4.84), at most
 *   once per 20 ticks, the ocelot strikes the marked prey
 *   for its live ATTACK_DAMAGE attribute value PLUS a
 *   flat 2.0 mark bonus (a base ocelot: 5.0 per strike;
 *   the design's /damage bonus against tagged targets in
 *   code), dealt through a mob attack source credited to
 *   the ocelot, with 6 ENCHANT motes bursting at the prey.
 *   The bonus applies only to the marked entity: nothing
 *   else the ocelot might hit during the ability gains it.
 * - Duration: exactly 400 ticks (20 seconds), ending
 *   early when the marked prey dies or is removed. The
 *   framework cooldown runs from the end as usual.
 * - end(): the marked entity, if it can still be
 *   resolved, has its scoreboard tag removed and its
 *   GLOWING and SLOWNESS effects stripped, exactly the
 *   set start() and the refresh applied. Idempotent, and
 *   a no-op when the prey is already gone.
 *
 * WHAT IT LOOKS LIKE: a low stare, a shimmer of enchant
 * motes, and the prey lights up with a white outline that
 * shows through trees and walls, limping under a slowness
 * it cannot shake. The ocelot runs it down and every hit
 * lands with a burst of enchant sparkles and a deeper
 * bite than an ocelot has any right to deal. When the
 * prey dies, the light dies with it.
 *
 * PERFORMANCE: canTrigger is one distance check. tick()
 * returns immediately except every 5th tick (one UUID
 * resolve, one distance check, one navigation drive, at
 * most one damage call per 20 ticks) and every 20th tick
 * (one UUID resolve, two effect applies, 2 particles). No
 * area queries anywhere. Particle budget per activation:
 * 12 at start, refreshes at most 20 times 2 = 40, strikes
 * at most 20 times 6 = 120, total at most 172 particles.
 *
 * TERRAIN: this ability changes no blocks; the
 * mobGriefing gate does not apply.
 *
 * State tag keys: "marked" (the marked prey's UUID,
 * stored directly), "next_strike" (the next activation
 * tick a marked strike may land), "mark_lost" (1 once the
 * marked prey is confirmed dead or gone).
 */
public final class SpottedMarkAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int REFRESH_INTERVAL = 20;
    private static final int MARK_EFFECT_TICKS = 40;
    private static final int HUNT_INTERVAL = 5;
    private static final double STRIKE_RANGE_SQR = 4.84; // 2.2 blocks
    private static final int STRIKE_COOLDOWN_TICKS = 20;
    private static final double MARK_BONUS_DAMAGE = 2.0;
    private static final String MARK_TAG = "advancedmobs_spotted_mark";

    @Override
    public String id() {
        return "spotted_mark";
    }

    @Override
    public String displayName() {
        return "Spotted Mark";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ctx.state().putInt("next_strike", 0);
        LivingEntity target = ctx.target();
        ServerLevel level = ctx.serverLevel();
        if (target == null || level == null) {
            // No prey to mark: flag it lost so the ability ends at once.
            ctx.state().putInt("mark_lost", 1);
            return;
        }
        // The marking: tag, light, and slow, all on the one prey.
        ctx.state().store("marked", UUIDUtil.CODEC, target.getUUID());
        target.addTag(MARK_TAG);
        applyMarkEffects(target);
        level.sendParticles(ParticleTypes.ENCHANT, target.getX(), target.getY() + 0.8, target.getZ(),
            12, 0.5, 0.6, 0.5, 0.3);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.OCELOT_AMBIENT, SoundSource.HOSTILE, 1.0f, 0.9f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks % REFRESH_INTERVAL == 0) {
            refreshMark(ctx);
        }
        if (ticks % HUNT_INTERVAL == 0) {
            hunt(ctx, ticks);
        }
    }

    /** Resolves the marked prey from its stored UUID, or null when dead or gone. */
    private LivingEntity markedPrey(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return null;
        }
        Entity entity = OcelotAbilitySupport.resolveStored(level, ctx.state(), "marked");
        return entity instanceof LivingEntity living && living.isAlive() ? living : null;
    }

    /** The refresh: keep the prey lit and slowed, or end the mark with its prey. */
    private void refreshMark(AbilityContext ctx) {
        LivingEntity prey = markedPrey(ctx);
        if (prey == null) {
            ctx.state().putInt("mark_lost", 1);
            return;
        }
        applyMarkEffects(prey);
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.sendParticles(ParticleTypes.ENCHANT, prey.getX(), prey.getY() + 0.8, prey.getZ(),
                2, 0.4, 0.5, 0.4, 0.2);
        }
    }

    /** The hunt and the marked strike, every 5 ticks. */
    private void hunt(AbilityContext ctx, int ticks) {
        Mob caster = ctx.mob();
        LivingEntity prey = markedPrey(ctx);
        if (prey == null) {
            return;
        }
        if (caster.distanceToSqr(prey) > STRIKE_RANGE_SQR) {
            caster.getNavigation().moveTo(prey, 1.3);
            return;
        }
        if (ticks < ctx.state().getIntOr("next_strike", 0)) {
            return;
        }
        ctx.state().putInt("next_strike", ticks + STRIKE_COOLDOWN_TICKS);
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        // The marked strike: the ocelot's live attack damage plus the mark bonus.
        double damage = caster.getAttributeValue(Attributes.ATTACK_DAMAGE) + MARK_BONUS_DAMAGE;
        prey.hurtServer(level, level.damageSources().mobAttack(caster), (float) damage);
        level.sendParticles(ParticleTypes.ENCHANT, prey.getX(), prey.getY() + 0.6, prey.getZ(),
            6, 0.4, 0.5, 0.4, 0.3);
    }

    /** Applies the mark's two effects to the prey: lit up and slowed. */
    private void applyMarkEffects(LivingEntity prey) {
        prey.addEffect(new MobEffectInstance(MobEffects.GLOWING, MARK_EFFECT_TICKS, 0));
        prey.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, MARK_EFFECT_TICKS, 0));
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS || ctx.state().getIntOr("mark_lost", 0) == 1;
    }

    @Override
    public void end(AbilityContext ctx) {
        LivingEntity prey = markedPrey(ctx);
        if (prey == null) {
            return;
        }
        prey.removeTag(MARK_TAG);
        prey.removeEffect(MobEffects.GLOWING);
        prey.removeEffect(MobEffects.SLOWNESS);
    }
}
