package mod.advancedmobs.ability.frog;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Golden Hour Form (Frog ability 5 of 5, transformation).
 *
 * WHAT IT DOES: the frog basks and turns golden, glowing
 * dust motes orbiting it and pearlescent froglight
 * displays circling it like attendant lanterns. In this
 * radiant form it gains strength and speed, hunts its
 * target down, and the orbiting lights pulse blindness
 * onto nearby enemies.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the frog's current target within 14 blocks
 *   (distance squared 196).
 * - Duration: 400 ticks.
 * - The radiant buffs (start(), the design's strength
 *   and speed lines): GLOWING, STRENGTH I, and SPEED I,
 *   each for 420 ticks on the frog, all three removed
 *   exactly in end(). The GLOWING outline renders in
 *   vanilla team white (there is no server-side
 *   per-entity glow color for a teamless mob; the gold
 *   lives in the dust, the Parrot package precedent).
 * - The froglights (start()): 3 item displays of
 *   Items.PEARLESCENT_FROGLIGHT (the design's pearlescent
 *   froglight displays) at scale 0.45, full brightness,
 *   billboard mode "center", tracked in the state tag.
 *   The design says passengers; they are code-snapped
 *   instead, because a display riding as a true passenger
 *   stacks at the frog's ride position and cannot orbit
 *   (the Witch orbiting-display precedent, also the
 *   Parrot feather precedent). Each tick the orbit angle
 *   advances 0.10 radians and the three displays are
 *   snapped to radius 0.9 around the frog at 0.75 above
 *   its feet, spaced 120 degrees apart, with a gentle
 *   0.1 bob. end() discards all three.
 * - The gold dust (the design's gold dust motes): 1
 *   golden DUST mote (RGB 0xFFCF4D, scale 1.0, a
 *   pre-built static final) drifts at the frog every 2
 *   ticks, and a strike (below) bursts 6 more at the
 *   victim.
 * - The hunt: every 5 ticks the frog is navigation
 *   driven onto its live target at speed 1.25 while the
 *   target is beyond 2.2 blocks. In reach, at most once
 *   per 20 ticks, it strikes for its live ATTACK_DAMAGE
 *   attribute value (10.0 on a base frog, with STRENGTH I
 *   riding the attribute) through a mob attack source
 *   credited to the frog.
 * - The blindness pulse (the design's blindness pulses,
 *   every 40 ticks): one bounded query at radius 6.0
 *   gives every enemy BLINDNESS for 50 ticks. The effect
 *   rides its own clock after the pulse and after the
 *   ability ends.
 * - The sound: FROG_AMBIENT at volume 1.0, pitch 1.3 in
 *   start() (the bright basking croak).
 * - end(): the three buffs are removed and the displays
 *   are discarded. Idempotent.
 *
 * WHAT IT LOOKS LIKE: a small golden sun of a frog,
 * glowing, trailing gold dust, three pale lanterns
 * wheeling around it, hopping down its prey while the
 * light keeps flashing in everyone's eyes.
 *
 * PERFORMANCE: per tick at most 3 display resolves and
 * snaps plus one mote every 2 ticks; the hunt drive runs
 * every 5 ticks; the blindness pulse is one bounded query
 * every 40 ticks; strikes are one distance check per
 * tick with a damage call at most once per 20 ticks.
 * Particle budget per activation: 200 drift motes plus
 * at most 20 strikes times 6 plus 10 blindness pulses
 * times 8 END_ROD flashes, at most 400 particles.
 *
 * TERRAIN: this ability changes no blocks; the
 * froglights are display entities, not blocks, so the
 * mobGriefing gate does not apply.
 *
 * State tag keys: "angle" (the orbit angle in radians),
 * "last_strike" (the activation tick of the last strike),
 * "lights" (the tracked display list).
 */
public final class GoldenHourFormAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int BUFF_TICKS = 420;
    private static final int BLIND_INTERVAL = 40;
    private static final int STRIKE_INTERVAL = 20;
    private static final double ORBIT_RADIUS = 0.9;
    private static final DustParticleOptions GOLD_DUST = new DustParticleOptions(0xFFCF4D, 1.0f);

    @Override
    public String id() {
        return "golden_hour_form";
    }

    @Override
    public String displayName() {
        return "Golden Hour Form";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.addEffect(new MobEffectInstance(MobEffects.GLOWING, BUFF_TICKS, 0));
        caster.addEffect(new MobEffectInstance(MobEffects.STRENGTH, BUFF_TICKS, 0));
        caster.addEffect(new MobEffectInstance(MobEffects.SPEED, BUFF_TICKS, 0));
        ctx.state().putDouble("angle", 0.0);
        ctx.state().putInt("last_strike", -STRIKE_INTERVAL);
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.FROG_AMBIENT, SoundSource.HOSTILE, 1.0f, 1.3f);
        // The attendant lanterns: three pearlescent froglight displays.
        for (int i = 0; i < 3; i++) {
            Entity light = FrogAbilitySupport.createItemDisplay(level,
                new ItemStack(Items.PEARLESCENT_FROGLIGHT), 0.45f, "center");
            if (light == null) {
                continue;
            }
            light.snapTo(caster.getX(), caster.getY() + 0.75, caster.getZ(), 0.0f, 0.0f);
            if (level.addFreshEntity(light)) {
                FrogAbilitySupport.trackEntity(ctx.state(), "lights", light);
            }
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
        // The orbiting lanterns.
        double angle = ctx.state().getDoubleOr("angle", 0.0) + 0.10;
        ctx.state().putDouble("angle", angle);
        ListTag lights = ctx.state().getListOrEmpty("lights");
        for (int i = 0; i < lights.size(); i++) {
            CompoundTag entry = lights.getCompoundOrEmpty(i);
            Entity light = FrogAbilitySupport.resolveTracked(level, entry);
            if (light == null) {
                continue;
            }
            double a = angle + i * (Math.PI * 2.0 / 3.0);
            light.snapTo(caster.getX() + Math.cos(a) * ORBIT_RADIUS,
                caster.getY() + 0.75 + Math.sin(angle * 2.0 + i) * 0.1,
                caster.getZ() + Math.sin(a) * ORBIT_RADIUS, 0.0f, 0.0f);
        }
        if (ticks % 2 == 0) {
            level.sendParticles(GOLD_DUST, caster.getX(), caster.getY() + 0.4, caster.getZ(),
                1, 0.3, 0.25, 0.3, 0.02);
        }
        LivingEntity target = ctx.target();
        if (target != null && target.isAlive()) {
            double distanceSqr = caster.distanceToSqr(target);
            if (distanceSqr > 2.2 * 2.2) {
                if (ticks % 5 == 0) {
                    caster.getNavigation().moveTo(target, 1.25);
                }
            } else if (ticks - ctx.state().getIntOr("last_strike", -STRIKE_INTERVAL) >= STRIKE_INTERVAL) {
                // The radiant strike: live attack damage, STRENGTH riding it.
                double damage = caster.getAttributeValue(Attributes.ATTACK_DAMAGE);
                target.hurtServer(level, level.damageSources().mobAttack(caster), (float) damage);
                level.sendParticles(GOLD_DUST, target.getX(), target.getY() + 0.6, target.getZ(),
                    6, 0.35, 0.35, 0.35, 0.05);
                ctx.state().putInt("last_strike", ticks);
            }
        }
        if (ticks % BLIND_INTERVAL == 0) {
            // The light pulse: blindness on every enemy near the glow.
            List<LivingEntity> enemies = FrogAbilitySupport.enemiesAroundSelf(ctx, 6.0, 2.0, 3.0);
            for (LivingEntity enemy : enemies) {
                enemy.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 50, 0));
            }
            level.sendParticles(ParticleTypes.END_ROD, caster.getX(), caster.getY() + 0.6, caster.getZ(),
                8, 0.6, 0.4, 0.6, 0.05);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.removeEffect(MobEffects.GLOWING);
        caster.removeEffect(MobEffects.STRENGTH);
        caster.removeEffect(MobEffects.SPEED);
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag lights = ctx.state().getListOrEmpty("lights");
        for (int i = 0; i < lights.size(); i++) {
            Entity light = FrogAbilitySupport.resolveTracked(level, lights.getCompoundOrEmpty(i));
            if (light != null) {
                light.discard();
            }
        }
    }
}
