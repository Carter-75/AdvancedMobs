package mod.advancedmobs.ability.fox;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Ember Thief (Fox ability 4 of 5, transformation).
 *
 * WHAT IT DOES: the fox snatches a coal from a campfire
 * and its whole coat takes on a smoldering edge, flame
 * particles curling off its tail. In this form it cannot
 * burn, it runs faster, and every bite sets its victim
 * alight, and the fire clings twice as long as it should.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the fox's current target within 14 blocks
 *   (distance squared 196).
 * - The form (start()): FIRE_RESISTANCE and SPEED I, both
 *   for 320 ticks (the design's fire resistance and
 *   speed), both removed exactly in end(). FOX_AGGRO at
 *   volume 1.2, pitch 1.1 (the snatch and the snarl) and
 *   an opening burst of 8 FLAME at the fox's body.
 * - The hunt (tick(), every 5 ticks): while the target is
 *   beyond 2.2 blocks the fox is navigation-driven onto
 *   it at speed 1.3 (its vanilla AI never chases in this
 *   mod's combat, so the drive is code, the package
 *   pattern); inside 2.2 blocks the drive stops.
 * - The bite (tick(), at most once per 20 ticks, the
 *   clock in the state tag): when the live target is
 *   within 2.2 blocks (distance squared 4.84), the fox
 *   bites for its live ATTACK_DAMAGE value (2.0 base;
 *   the mod's damage upgrades ride the attribute) through
 *   a mob attack source credited to the fox, with FOX_BITE
 *   at volume 1.0, pitch 1.2 and 4 FLAME at the victim.
 *   The ignition: the victim gains a transient BURNING_TIME
 *   modifier of +1.0 add_multiplied_base (id advancedmobs:
 *   ember_thief_burning, the design's "burning_time
 *   attribute on victims via code": the attribute scales
 *   how long an entity burns once lit, so the victim's
 *   fire lasts twice as long while the modifier rides)
 *   and is lit for 80 ticks of fire (4 seconds of base
 *   fire ticks, doubled in effect by the modifier while
 *   it lasts). The victim's UUID is tracked in the state
 *   tag (cap 8 victims) so end() can strip the modifier
 *   again; the fire itself is vanilla burning and is not
 *   put out by end(), it burns out on its own clock.
 * - The look (tick()): 2 FLAME curl off the fox's tail
 *   (behind its facing, at half height) every 4 ticks,
 *   and 1 CAMPFIRE_SIGNAL_SMOKE accent rises off its back
 *   every 20 ticks (the design's campfire smoke accents:
 *   the stolen coal still smokes).
 * - Duration: exactly 300 ticks (15 seconds).
 * - end(): FIRE_RESISTANCE and SPEED are stripped from
 *   the fox, and the BURNING_TIME modifier is removed
 *   from every tracked victim that still resolves.
 *   Idempotent.
 *
 * WHAT IT LOOKS LIKE: a fox with its hackles up and its
 * coat edged in living flame, smoke trailing off its
 * back, running its prey down faster than a fox should
 * run. Its bites land with a crackle and a gout of flame,
 * and whatever it bites keeps burning long after it
 * should have gone out.
 *
 * PERFORMANCE: the hunt is one distance check per tick
 * and one navigation call every 5 ticks; the bite is one
 * distance check per tick with a damage call at most
 * once per 20 ticks; particles are 2 every 4 ticks plus
 * 1 every 20. Zero entity queries (the bite reads the
 * context target directly). Particle budget per
 * activation: start 8, trail at most 75 times 2 = 150,
 * smoke at most 15, bites at most 15 times 4 = 60, total
 * at most 233 particles.
 *
 * TERRAIN: this ability changes no blocks; ignitions are
 * entity burning, never placed fire, so the mobGriefing
 * gate does not apply.
 *
 * State tag keys: "next_bite" (the next tick the bite
 * may fire), "burning" (list of compounds, one per
 * ignited victim: "id", its UUID; cap 8).
 */
public final class EmberThiefAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int EFFECT_TICKS = 320;
    private static final int HUNT_INTERVAL = 5;
    private static final double BITE_RANGE_SQR = 4.84; // 2.2 blocks
    private static final int BITE_COOLDOWN_TICKS = 20;
    private static final int FIRE_TICKS = 80;
    private static final int MAX_TRACKED_VICTIMS = 8;
    private static final Identifier BURNING_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "ember_thief_burning");

    @Override
    public String id() {
        return "ember_thief";
    }

    @Override
    public String displayName() {
        return "Ember Thief";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ctx.state().put("burning", new ListTag());
        caster.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, EFFECT_TICKS, 0));
        caster.addEffect(new MobEffectInstance(MobEffects.SPEED, EFFECT_TICKS, 0));
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.FOX_AGGRO, SoundSource.HOSTILE, 1.2f, 1.1f);
            level.sendParticles(ParticleTypes.FLAME, caster.getX(), caster.getY() + 0.4, caster.getZ(),
                8, 0.3, 0.3, 0.3, 0.05);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();

        // The smoldering coat: flame off the tail, smoke off the back.
        if (ticks % 4 == 0) {
            double tailX = caster.getX() - caster.getLookAngle().x * 0.5;
            double tailZ = caster.getZ() - caster.getLookAngle().z * 0.5;
            level.sendParticles(ParticleTypes.FLAME, tailX, caster.getY() + 0.3, tailZ,
                2, 0.12, 0.15, 0.12, 0.02);
        }
        if (ticks % 20 == 0) {
            level.sendParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, caster.getX(), caster.getY() + 0.6, caster.getZ(),
                1, 0.1, 0.1, 0.1, 0.02);
        }

        LivingEntity target = ctx.target();
        if (target == null) {
            return;
        }
        double distanceSqr = caster.distanceToSqr(target);
        if (distanceSqr > BITE_RANGE_SQR) {
            if (ticks % HUNT_INTERVAL == 0) {
                caster.getNavigation().moveTo(target, 1.3);
            }
            return;
        }
        // In reach: the ember bite, on its 20 tick clock.
        if (ticks < ctx.state().getIntOr("next_bite", 0)) {
            return;
        }
        ctx.state().putInt("next_bite", ticks + BITE_COOLDOWN_TICKS);
        double damage = caster.getAttributeValue(Attributes.ATTACK_DAMAGE);
        target.hurtServer(level, level.damageSources().mobAttack(caster), (float) damage);
        // The ignition: doubled burn time while the modifier rides, then lit.
        FoxAbilitySupport.addModifier(target, Attributes.BURNING_TIME,
            new AttributeModifier(BURNING_ID, 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        trackVictim(ctx, target);
        target.setRemainingFireTicks(FIRE_TICKS);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.FOX_BITE, SoundSource.HOSTILE, 1.0f, 1.2f);
        level.sendParticles(ParticleTypes.FLAME, target.getX(), target.getY() + 0.5, target.getZ(),
            4, 0.25, 0.3, 0.25, 0.05);
    }

    /** Tracks an ignited victim for end()'s modifier cleanup (cap 8, no duplicates). */
    private void trackVictim(AbilityContext ctx, LivingEntity victim) {
        ListTag burning = ctx.state().getListOrEmpty("burning");
        for (int i = 0; i < burning.size(); i++) {
            CompoundTag entry = burning.getCompoundOrEmpty(i);
            if (entry.read("id", UUIDUtil.CODEC).map(victim.getUUID()::equals).orElse(false)) {
                return;
            }
        }
        if (burning.size() >= MAX_TRACKED_VICTIMS) {
            return;
        }
        FoxAbilitySupport.trackEntity(ctx.state(), "burning", victim);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.removeEffect(MobEffects.FIRE_RESISTANCE);
        caster.removeEffect(MobEffects.SPEED);
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        // Strip the doubled burn time off every victim that still resolves.
        ListTag burning = ctx.state().getListOrEmpty("burning");
        for (int i = 0; i < burning.size(); i++) {
            Entity entity = FoxAbilitySupport.resolveTracked(level, burning.getCompoundOrEmpty(i));
            if (entity instanceof LivingEntity victim) {
                FoxAbilitySupport.removeModifier(victim, Attributes.BURNING_TIME, BURNING_ID);
            }
        }
    }
}
