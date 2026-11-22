package mod.advancedmobs.ability.cat;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Night Prowl (Cat ability 5 of 5, transformation).
 *
 * WHAT IT DOES: when night falls the cat melts into the
 * dark, invisible and silent with night vision bright
 * eyes, moving faster and hitting harder. It strikes from
 * the shadows and fades again between attacks until dawn
 * or the fight ends.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the cat's current target within 16 blocks
 *   (distance squared 256), plus night: the level's day
 *   time within the 24000 tick day is in the vanilla night
 *   window, 13000 to 23000 (the design's /time query gate,
 *   in code; CatAbilitySupport.isNight). By day this
 *   ability is never offered.
 * - The melt (start()): four effects, each applied for
 *   1220 ticks and each removed exactly in end():
 *   INVISIBILITY (the melt itself), NIGHT_VISION (the
 *   bright eyes; on a mob it is the design's named effect
 *   and the visual tell is the invisibility it rides with),
 *   SPEED I and STRENGTH I, amplifier 0 (faster, harder:
 *   STRENGTH rides the cat's real ATTACK_DAMAGE attribute,
 *   so the strikes below hit harder without a flat bonus).
 *   10 ENCHANT motes shimmer over the cat as it fades.
 * - The hunt (tick(), every 5 ticks): the cat is
 *   navigation-driven onto its live target at speed 1.25
 *   while it is more than 2.2 blocks away. In reach (2.2
 *   blocks, distance squared 4.84), at most once per 20
 *   ticks, it strikes from the shadows: damage is the cat's
 *   live ATTACK_DAMAGE attribute value (a base cat: 3.0,
 *   raised by STRENGTH I and by the mod's damage upgrades),
 *   dealt through a mob attack source credited to the cat,
 *   with 8 ENCHANT motes bursting at the target (the
 *   design's enchant particle on each re-entry strike: the
 *   strike is the moment the shadow becomes visible) and
 *   the cat's hurt sound at pitch 1.2. Between strikes the
 *   cat simply stays invisible: the fade is the constant
 *   INVISIBILITY, and the strike burst is the re-entry.
 * - Dawn watch (tick(), every 20 ticks): if the night
 *   window has closed, the state tag's "dawn" flag is set
 *   and the ability ends (isFinished), even mid-fight: the
 *   prowl belongs to the night.
 * - Duration: at most 1200 ticks (60 seconds), ending
 *   early at dawn, whichever comes first. The framework
 *   cooldown runs from the end as usual.
 * - end(): all four effects are removed, exactly the set
 *   start() applied. Idempotent.
 *
 * WHAT IT LOOKS LIKE: the cat shimmers with enchant
 * sparkles and is gone. What follows is a fight against
 * nothing: periodic bursts of enchant motes at the victim,
 * a yowl pitched out of empty air, and damage arriving
 * from a shadow that is never where the last burst was.
 *
 * PERFORMANCE: canTrigger is one distance check and one
 * day-time read. tick() returns immediately except every
 * 5th tick (one distance check, one navigation drive, and
 * at most one damage call per 20 ticks) and every 20th
 * tick (one day-time read). No entity queries anywhere.
 * Particle budget per activation: 10 at start, strikes at
 * most 60 times 8 = 480, total at most 490 particles.
 *
 * TERRAIN: this ability changes no blocks; the
 * mobGriefing gate does not apply.
 *
 * State tag keys: "next_strike" (the next activation tick
 * a shadow strike may land), "dawn" (1 once the night
 * window has closed).
 */
public final class NightProwlAbility implements MobAbility {

    private static final int DURATION_TICKS = 1200;
    private static final int EFFECT_TICKS = DURATION_TICKS + 20;
    private static final int HUNT_INTERVAL = 5;
    private static final int DAWN_INTERVAL = 20;
    private static final double STRIKE_RANGE_SQR = 4.84; // 2.2 blocks
    private static final int STRIKE_COOLDOWN_TICKS = 20;

    @Override
    public String id() {
        return "night_prowl";
    }

    @Override
    public String displayName() {
        return "Night Prowl";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0
            && CatAbilitySupport.isNight(ctx.level());
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ctx.state().putInt("next_strike", 0);
        // The melt: unseen, bright-eyed, faster, harder.
        caster.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, EFFECT_TICKS, 0));
        caster.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, EFFECT_TICKS, 0));
        caster.addEffect(new MobEffectInstance(MobEffects.SPEED, EFFECT_TICKS, 0));
        caster.addEffect(new MobEffectInstance(MobEffects.STRENGTH, EFFECT_TICKS, 0));
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.sendParticles(ParticleTypes.ENCHANT, caster.getX(), caster.getY() + 0.5, caster.getZ(),
                10, 0.5, 0.5, 0.5, 0.3);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks % DAWN_INTERVAL == 0 && !CatAbilitySupport.isNight(ctx.level())) {
            ctx.state().putInt("dawn", 1);
            return;
        }
        if (ticks % HUNT_INTERVAL != 0) {
            return;
        }
        Mob caster = ctx.mob();
        LivingEntity target = ctx.target();
        if (target == null) {
            return;
        }
        if (caster.distanceToSqr(target) > STRIKE_RANGE_SQR) {
            caster.getNavigation().moveTo(target, 1.25);
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
        // The re-entry strike: the shadow lands one hit and fades again.
        double damage = caster.getAttributeValue(Attributes.ATTACK_DAMAGE);
        target.hurtServer(level, level.damageSources().mobAttack(caster), (float) damage);
        level.sendParticles(ParticleTypes.ENCHANT, target.getX(), target.getY() + 0.6, target.getZ(),
            8, 0.4, 0.5, 0.4, 0.3);
        CatAbilitySupport.playCatSound(level, caster.getX(), caster.getY(), caster.getZ(),
            CatAbilitySupport.CatSound.HURT, 1.0f, 1.2f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS || ctx.state().getIntOr("dawn", 0) == 1;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.removeEffect(MobEffects.INVISIBILITY);
        caster.removeEffect(MobEffects.NIGHT_VISION);
        caster.removeEffect(MobEffects.SPEED);
        caster.removeEffect(MobEffects.STRENGTH);
    }
}
