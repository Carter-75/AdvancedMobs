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
import net.minecraft.world.item.Items;

/**
 * Woolquake (Sheep ability 1 of 5, defense).
 *
 * WHAT IT DOES: the sheep puffs up into an enormous ball
 * of wool, white flecks and cloud bursting outward as the
 * coat inflates. In this state it is a padded fortress:
 * extra absorption hearts, total knockback immunity, and
 * a coat so thick that anything striking it gets tangled
 * in the wool and slowed.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the sheep's current target within 12 blocks
 *   (distance squared 144).
 * - Duration: exactly 300 ticks (15 seconds) from start().
 * - The puff (start()): a burst of 16 white wool ITEM
 *   particles and 10 CLOUD at the sheep's body, and
 *   SHEEP_AMBIENT at volume 1.2, pitch 0.8 (the deep baa
 *   of a sheep inflating). Three things are applied for
 *   the whole activation and removed exactly in end():
 *   SCALE add_multiplied_base +0.6 (1.6x size, the
 *   enormous wool ball, id advancedmobs:woolquake_scale),
 *   KNOCKBACK_RESISTANCE add_value +1.0 (clamps at the
 *   1.0 total: nothing moves the ball, id
 *   advancedmobs:woolquake_knockback), and ABSORPTION II
 *   (amplifier 1, 8 absorption points) as an effect for
 *   320 ticks.
 * - The drift (tick(), every 4 ticks): 2 CLOUD and 1
 *   white wool ITEM mote shed off the coat, so the ball
 *   visibly breathes wool dust while it lasts.
 * - The tangle (tick(), every tick, one integer compare):
 *   the sheep's last-hurt-by-mob timestamp is baselined
 *   in start(); when it advances, the attacker is read
 *   once. If it is a living enemy within 6 blocks
 *   (distance squared 36) and at least 10 ticks passed
 *   since the last tangle, the attacker gains SLOWNESS II
 *   (amplifier 1) for 60 ticks and 6 CLOUD burst at its
 *   body (wool wrapping its limbs). The tangle is a
 *   reaction to being struck, so it never fires on its
 *   own and never touches anyone who has not hit the
 *   sheep.
 * - end(): the two modifiers are removed and the
 *   ABSORPTION effect is removed (a sheep that somehow
 *   had absorption from another source would lose it at
 *   end(), an accepted edge case shared with every
 *   fixed-duration effect in this system, the Honey Coat
 *   precedent).
 *
 * WHAT IT LOOKS LIKE: a sheep suddenly twice its size, a
 * round white mountain shedding flecks and puffs of
 * cloud, unmoved by any blow; whatever hits it comes away
 * trailing cloud, dragging its feet through invisible
 * wool.
 *
 * PERFORMANCE: start() is one burst and three modifier or
 * effect writes. tick() is one timestamp compare per
 * tick, one emission every 4 ticks, and at most one
 * tangle (one distance check against an already-resolved
 * attacker reference, never an entity query). Particle
 * budget per activation: 26 at start, drift 75 times 3 =
 * 225, tangles at most 30 times 6 = 180, total at most
 * 431 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply. The wool flecks are item particles, not
 * placed blocks.
 *
 * State tag keys: "last_hurt_ts" (the baselined
 * last-hurt-by timestamp), "last_tangle" (tick of the
 * last tangle).
 */
public final class WoolquakeAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int TANGLE_COOLDOWN_TICKS = 10;
    private static final double TANGLE_RANGE_SQR = 36.0; // 6 blocks
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "woolquake_scale");
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "woolquake_knockback");
    private static final ItemParticleOption WOOL_FLECK =
        new ItemParticleOption(ParticleTypes.ITEM, Items.WOOL.white());

    @Override
    public String id() {
        return "woolquake";
    }

    @Override
    public String displayName() {
        return "Woolquake";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ctx.state().putInt("last_hurt_ts", caster.getLastHurtByMobTimestamp());
        ctx.state().putInt("last_tangle", -TANGLE_COOLDOWN_TICKS);

        SheepAbilitySupport.addModifier(caster, Attributes.SCALE,
            new AttributeModifier(SCALE_ID, 0.6, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        SheepAbilitySupport.addModifier(caster, Attributes.KNOCKBACK_RESISTANCE,
            new AttributeModifier(KNOCKBACK_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        caster.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 320, 1));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.sendParticles(WOOL_FLECK, caster.getX(), caster.getY() + 0.8, caster.getZ(),
                16, 0.5, 0.5, 0.5, 0.15);
            level.sendParticles(ParticleTypes.CLOUD, caster.getX(), caster.getY() + 0.8, caster.getZ(),
                10, 0.6, 0.5, 0.6, 0.08);
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.SHEEP_AMBIENT, SoundSource.HOSTILE, 1.2f, 0.8f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        int ticks = ctx.ticksActive();
        ServerLevel level = ctx.serverLevel();

        // The drift: the coat sheds wool dust as it breathes.
        if (level != null && ticks % 4 == 0) {
            level.sendParticles(ParticleTypes.CLOUD, caster.getX(), caster.getY() + 0.9, caster.getZ(),
                2, 0.55, 0.45, 0.55, 0.04);
            level.sendParticles(WOOL_FLECK, caster.getX(), caster.getY() + 0.9, caster.getZ(),
                1, 0.5, 0.45, 0.5, 0.05);
        }

        // The tangle: react to a fresh hit (see the class
        // comment). One integer compare on quiet ticks.
        int hurtTs = caster.getLastHurtByMobTimestamp();
        if (hurtTs == ctx.state().getIntOr("last_hurt_ts", 0)) {
            return;
        }
        ctx.state().putInt("last_hurt_ts", hurtTs);
        if (ticks - ctx.state().getIntOr("last_tangle", 0) < TANGLE_COOLDOWN_TICKS) {
            return;
        }
        LivingEntity attacker = caster.getLastHurtByMob();
        if (attacker == null || !attacker.isAlive()
            || !SheepAbilitySupport.isEnemy(ctx, attacker)
            || caster.distanceToSqr(attacker) > TANGLE_RANGE_SQR) {
            return;
        }
        ctx.state().putInt("last_tangle", ticks);
        attacker.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 1));
        if (level != null) {
            level.sendParticles(ParticleTypes.CLOUD, attacker.getX(), attacker.getY() + 0.7, attacker.getZ(),
                6, 0.4, 0.5, 0.4, 0.06);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        SheepAbilitySupport.removeModifier(caster, Attributes.SCALE, SCALE_ID);
        SheepAbilitySupport.removeModifier(caster, Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_ID);
        caster.removeEffect(MobEffects.ABSORPTION);
    }
}
