package mod.advancedmobs.ability.sheep;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.List;

/**
 * Static Wool (Sheep ability 3 of 5, aura).
 *
 * WHAT IT DOES: the sheep's wool crackles with static as
 * it shuffles about, enchant motes drifting up while the
 * charge builds. Anything hostile standing close gets
 * needled by small zaps of lightning-typed damage, and
 * anything that actually strikes the sheep gets a much
 * bigger jolt and a moment of weakness for its trouble.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the sheep's current target within 12 blocks
 *   (distance squared 144).
 * - Duration: exactly 300 ticks (15 seconds) from start().
 * - The charge look (tick(), every 4 ticks): 2 ENCHANT
 *   motes rise off the coat (the design's "enchant
 *   particle while charging"), and every 5 ticks 1
 *   ELECTRIC_SPARK snaps at the wool.
 * - The zap (tick(), every 10 ticks): one bounded query
 *   at radius 4.0 (2 below to 3 above) around the sheep;
 *   every enemy inside takes 2.0 damage from the typed
 *   zap source (see SheepAbilitySupport: the vanilla
 *   lightning bolt damage TYPE with the sheep credited
 *   as attacker, so the damage obeys the lightning
 *   type's rules and zap kills credit the sheep) and 2
 *   ELECTRIC_SPARK burst at its body. There is no
 *   per-victim cooldown beyond the pulse itself: standing
 *   in the field means a zap every half second.
 * - The jolt (tick(), every tick, one integer compare):
 *   the sheep's last-hurt-by-mob timestamp is baselined
 *   in start(); when it advances, the attacker is read
 *   once. If it is a living enemy within 8 blocks
 *   (distance squared 64) and at least 10 ticks passed
 *   since the last jolt, it takes 5.0 damage from the
 *   same typed source, gains WEAKNESS I (amplifier 0)
 *   for 100 ticks, and 8 ELECTRIC_SPARK burst at its
 *   body. The jolt is a reaction to being struck, so it
 *   never fires on its own.
 * - end(): nothing to clean up. The ability applies no
 *   modifiers and no effects to the sheep itself, and
 *   the zaps are instant damage; the WEAKNESS on a jolted
 *   attacker rides its own clock out (the Caravan Call
 *   precedent for effects that outlive an activation).
 *
 * WHAT IT LOOKS LIKE: a sheep trailing faint enchant
 * glyphs, sparks snapping off its coat every few ticks,
 * and every half second a crackle as everything close
 * gets needled; hit it and the return jolt is a fat blue
 * white snap that leaves the attacker sagging.
 *
 * PERFORMANCE: tick() is one timestamp compare per tick,
 * emissions on 4 and 5 tick intervals, and one bounded
 * radius-4.0 query every 10 ticks; the typed source is
 * built only on zap and jolt moments (it does a registry
 * lookup), never per tick. Particle budget per
 * activation: charge look 75 times 2 = 150, crackle 60,
 * zaps at most 30 pulses times 6 victims times 2 = 360,
 * jolts at most 30 times 8 = 240, total at most 810
 * particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply. The zaps are a damage source, not a real
 * lightning bolt entity, so they never strike or ignite
 * terrain.
 *
 * State tag keys: "last_hurt_ts" (the baselined
 * last-hurt-by timestamp), "last_jolt" (tick of the last
 * retaliation jolt).
 */
public final class StaticWoolAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int ZAP_INTERVAL = 10;
    private static final double ZAP_RADIUS = 4.0;
    private static final double ZAP_DAMAGE = 2.0;
    private static final double JOLT_DAMAGE = 5.0;
    private static final double JOLT_RANGE_SQR = 64.0; // 8 blocks
    private static final int JOLT_COOLDOWN_TICKS = 10;

    @Override
    public String id() {
        return "static_wool";
    }

    @Override
    public String displayName() {
        return "Static Wool";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().putInt("last_hurt_ts", ctx.mob().getLastHurtByMobTimestamp());
        ctx.state().putInt("last_jolt", -JOLT_COOLDOWN_TICKS);
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        int ticks = ctx.ticksActive();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }

        // The charge look: glyphs while it builds, sparks
        // while it crackles.
        if (ticks % 4 == 0) {
            level.sendParticles(ParticleTypes.ENCHANT, caster.getX(), caster.getY() + 0.9, caster.getZ(),
                2, 0.45, 0.45, 0.45, 0.05);
        }
        if (ticks % 5 == 0) {
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, caster.getX(), caster.getY() + 0.9, caster.getZ(),
                1, 0.45, 0.4, 0.45, 0.05);
        }

        // The zap pulse.
        if (ticks % ZAP_INTERVAL == 0) {
            DamageSource zap = SheepAbilitySupport.lightningSource(ctx);
            List<LivingEntity> victims = SheepAbilitySupport.enemiesAroundSelf(ctx, ZAP_RADIUS, 2.0, 3.0);
            for (LivingEntity victim : victims) {
                victim.hurtServer(level, zap, (float) ZAP_DAMAGE);
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK, victim.getX(), victim.getY() + 0.8, victim.getZ(),
                    2, 0.3, 0.5, 0.3, 0.08);
            }
        }

        // The jolt: react to a fresh hit (see the class
        // comment). One integer compare on quiet ticks.
        int hurtTs = caster.getLastHurtByMobTimestamp();
        if (hurtTs == ctx.state().getIntOr("last_hurt_ts", 0)) {
            return;
        }
        ctx.state().putInt("last_hurt_ts", hurtTs);
        if (ticks - ctx.state().getIntOr("last_jolt", 0) < JOLT_COOLDOWN_TICKS) {
            return;
        }
        LivingEntity attacker = caster.getLastHurtByMob();
        if (attacker == null || !attacker.isAlive()
            || !SheepAbilitySupport.isEnemy(ctx, attacker)
            || caster.distanceToSqr(attacker) > JOLT_RANGE_SQR) {
            return;
        }
        ctx.state().putInt("last_jolt", ticks);
        attacker.hurtServer(level, SheepAbilitySupport.lightningSource(ctx), (float) JOLT_DAMAGE);
        attacker.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 100, 0));
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, attacker.getX(), attacker.getY() + 0.9, attacker.getZ(),
            8, 0.4, 0.6, 0.4, 0.12);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to clean up (see the class comment).
    }
}
