package mod.advancedmobs.ability.sheep;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Shear Terror (Sheep ability 5 of 5, transformation).
 *
 * WHAT IT DOES: the sheep explodes its wool off in a
 * blizzard of white flecks and emerges lean, fast, and
 * mean: a shorn sheep with nothing left to lose. Stripped
 * of its coat it gains speed and strength and attacks in
 * quick flurries, until the wool slowly grows back at the
 * end and the terror subsides.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the sheep's current target within 12 blocks
 *   (distance squared 144).
 * - Duration: exactly 400 ticks (20 seconds) from start().
 * - The shed (start()): 24 white wool ITEM particles and
 *   12 CLOUD burst off the body, SHEEP_SHEAR plays at
 *   volume 1.2, and the sheep's real sheared flag is set
 *   (Sheep.setSheared(true)): the model actually loses
 *   its wool, the one genuine retexture a sheep carries
 *   server-side. Whether the sheep was already sheared
 *   before the ability is recorded in the state tag
 *   ("was_sheared"); end() only grows the coat back for
 *   a sheep that had one, so an already shorn sheep is
 *   never handed free wool.
 * - The lean form (start(), removed exactly in end()):
 *   transient SCALE add_multiplied_base -0.15 (0.85x, the
 *   design's slightly smaller, id
 *   advancedmobs:shear_terror_scale), transient
 *   ATTACK_SPEED add_multiplied_base +0.5 (the design's
 *   ATTACK_SPEED line, id
 *   advancedmobs:shear_terror_attack_speed; documented
 *   as INERT on the sheep, which has no ATTACK_SPEED
 *   attribute instance anywhere in its attribute set,
 *   verified against the jar: the flurry rate below is
 *   carried by code strike timing, the Grand Hex Form
 *   precedent), and SPEED II (amplifier 1) plus STRENGTH
 *   I (amplifier 0) as effects for 420 ticks.
 * - The hunt (tick(), every 5 ticks): the sheep's
 *   navigation is driven onto its live target at speed
 *   multiplier 1.2 (a passive animal's own AI never
 *   chases a set target, so the drive is in code, the
 *   Sporeling Swarm precedent). SPEED II stacks on top,
 *   which is what makes the shorn sheep genuinely fast.
 * - The flurry (tick(), every tick, one distance check):
 *   when the target is within 2.4 blocks (distance
 *   squared 5.76) and at least 8 ticks passed since the
 *   last strike, the sheep strikes: 4.0 flat damage as a
 *   mob attack credited to the sheep (1.0 for the flurry
 *   itself plus 3.0 for the form's STRENGTH I, baked in
 *   because a code damage call does not read the effect;
 *   the sheep has no ATTACK_DAMAGE instance to modify,
 *   the Abominable Form precedent), a 0.4 horizontal /
 *   0.2 up nudge along the sheep's look direction with
 *   hurtMarked set, 1 SWEEP_ATTACK and 4 CRIT at the
 *   victim's chest, and PLAYER_ATTACK_SWEEP at volume
 *   0.6, pitch 1.3. Eight ticks between strikes is the
 *   flurry: two and a half strikes a second.
 * - The regrowth (tick(), the last 40 ticks, every 5
 *   ticks): 1 white wool ITEM fleck puffs off the body as
 *   the coat visibly comes back in, ahead of end()
 *   restoring the sheared flag.
 * - A speed trail: while the sheep's horizontal velocity
 *   is above 0.1 blocks per tick, 1 CLOUD puffs at its
 *   feet every 4 ticks.
 * - end(): the two modifiers are removed, SPEED and
 *   STRENGTH are removed, and the sheared flag is
 *   restored per the "was_sheared" record above. If the
 *   caster dies first, the framework termination path
 *   runs this same end(), so a killed terror drops as
 *   the sheep it was.
 *
 * WHAT IT LOOKS LIKE: a bang of wool, and a pink, skinny
 * sheep moving far too fast, headbutting in a blur of
 * swept air and crit sparkles; near the end, white flecks
 * start puffing off it again as the coat returns.
 *
 * PERFORMANCE: tick() is one distance check per tick,
 * one navigation drive every 5 ticks, and emissions on 4
 * and 5 tick intervals; a strike is one damage call. No
 * entity queries anywhere. Particle budget per
 * activation: 36 at start, strikes at most 50 times 5 =
 * 250, trail at most 100, regrowth 8, total at most 394
 * particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply. The sheared flag is a state of the sheep
 * itself, not a block change, and the wool flecks are
 * item particles.
 *
 * State tag keys: "was_sheared" (1 when the sheep was
 * already sheared at start), "last_strike" (tick of the
 * last flurry strike).
 */
public final class ShearTerrorAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int STRIKE_INTERVAL = 8;
    private static final double STRIKE_RANGE_SQR = 5.76; // 2.4 blocks
    private static final double STRIKE_DAMAGE = 4.0;
    private static final int REGROWTH_WINDOW = 40;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "shear_terror_scale");
    private static final Identifier ATTACK_SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "shear_terror_attack_speed");
    private static final ItemParticleOption WOOL_FLECK =
        new ItemParticleOption(ParticleTypes.ITEM, Items.WOOL.white());

    @Override
    public String id() {
        return "shear_terror";
    }

    @Override
    public String displayName() {
        return "Shear Terror";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ctx.state().putInt("last_strike", -STRIKE_INTERVAL);
        ctx.state().putInt("was_sheared", 0);
        if (caster instanceof Sheep sheep) {
            ctx.state().putInt("was_sheared", sheep.isSheared() ? 1 : 0);
            sheep.setSheared(true);
        }

        SheepAbilitySupport.addModifier(caster, Attributes.SCALE,
            new AttributeModifier(SCALE_ID, -0.15, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        SheepAbilitySupport.addModifier(caster, Attributes.ATTACK_SPEED,
            new AttributeModifier(ATTACK_SPEED_ID, 0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        caster.addEffect(new MobEffectInstance(MobEffects.SPEED, 420, 1));
        caster.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 420, 0));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.sendParticles(WOOL_FLECK, caster.getX(), caster.getY() + 0.8, caster.getZ(),
                24, 0.6, 0.6, 0.6, 0.2);
            level.sendParticles(ParticleTypes.CLOUD, caster.getX(), caster.getY() + 0.8, caster.getZ(),
                12, 0.6, 0.5, 0.6, 0.1);
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.SHEEP_SHEAR, SoundSource.HOSTILE, 1.2f, 1.0f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        int ticks = ctx.ticksActive();
        ServerLevel level = ctx.serverLevel();
        LivingEntity target = ctx.target();

        // The hunt: drive the chase in code (see the class
        // comment).
        if (ticks % 5 == 0 && target != null) {
            caster.getNavigation().moveTo(target, 1.2);
        }

        // The speed trail.
        if (level != null && ticks % 4 == 0) {
            Vec3 velocity = caster.getDeltaMovement();
            if (velocity.x * velocity.x + velocity.z * velocity.z > 0.01) {
                level.sendParticles(ParticleTypes.CLOUD, caster.getX(), caster.getY() + 0.15, caster.getZ(),
                    1, 0.3, 0.1, 0.3, 0.03);
            }
        }

        // The regrowth: the coat puffs back in near the end.
        if (level != null && ticks > DURATION_TICKS - REGROWTH_WINDOW && ticks % 5 == 0) {
            level.sendParticles(WOOL_FLECK, caster.getX(), caster.getY() + 0.8, caster.getZ(),
                1, 0.45, 0.45, 0.45, 0.05);
        }

        // The flurry (see the class comment).
        if (level == null || target == null
            || ticks - ctx.state().getIntOr("last_strike", 0) < STRIKE_INTERVAL
            || caster.distanceToSqr(target) > STRIKE_RANGE_SQR) {
            return;
        }
        ctx.state().putInt("last_strike", ticks);
        target.hurtServer(level, level.damageSources().mobAttack(caster), (float) STRIKE_DAMAGE);
        Vec3 look = caster.getLookAngle();
        Vec3 push = target.getDeltaMovement().add(look.x * 0.4, 0.2, look.z * 0.4);
        target.setDeltaMovement(push);
        target.hurtMarked = true;
        level.sendParticles(ParticleTypes.SWEEP_ATTACK, target.getX(), target.getY() + 0.9, target.getZ(),
            1, 0.2, 0.3, 0.2, 0.02);
        level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY() + 0.9, target.getZ(),
            4, 0.3, 0.4, 0.3, 0.1);
        level.playSound(null, target.getX(), target.getY(), target.getZ(),
            SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 0.6f, 1.3f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        SheepAbilitySupport.removeModifier(caster, Attributes.SCALE, SCALE_ID);
        SheepAbilitySupport.removeModifier(caster, Attributes.ATTACK_SPEED, ATTACK_SPEED_ID);
        caster.removeEffect(MobEffects.SPEED);
        caster.removeEffect(MobEffects.STRENGTH);
        // The wool grows back, but only for a sheep that
        // had a coat to lose (see the class comment).
        if (ctx.state().getIntOr("was_sheared", 0) == 0 && caster instanceof Sheep sheep) {
            sheep.setSheared(false);
        }
    }
}
