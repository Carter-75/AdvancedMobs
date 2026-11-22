package mod.advancedmobs.ability.cavespider;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.List;

/**
 * Venom Mist (Cave Spider ability 5 of 5, aura).
 *
 * WHAT IT DOES: the cave spider breathes out a low hanging
 * mist, ENTITY_EFFECT and WITCH motes in venom green pooling
 * around it and spilling across the cave floor. Enemies
 * standing in the mist gain NAUSEA as the fumes turn their
 * stomachs, and the longer they stay the more the mist eats
 * at them with a weak POISON that keeps refreshing.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the spider's current target within 8 blocks (distance
 *   squared 64). The mist is breathed into a close fight.
 * - The breath (start()): 8 venom green ENTITY_EFFECT motes
 *   (color 0.30, 0.80, 0.25) and 4 WITCH motes pool at the
 *   spider, low (0.3 blocks up), and a SPIDER_AMBIENT breath
 *   plays at volume 0.5, pitch 0.7, slow and wet.
 * - The mist (every 20 ticks for 240 ticks): every enemy
 *   within radius 4.0 of the spider (box reach 1.5 below,
 *   2.0 above) gets NAUSEA (amplifier 0) for 120 ticks and
 *   POISON (amplifier 0) for 80 ticks. Both are refreshed by
 *   every pulse while the enemy stays in the mist, so the
 *   poison never lapses mid-fight and keeps eating, and the
 *   nausea outlasts the mist by up to 6 seconds after the
 *   enemy escapes. The pulse also breathes the look: 10
 *   ENTITY_EFFECT motes and 4 WITCH motes pooled low around
 *   the spider (spread 3.2 horizontal, 0.3 vertical), the
 *   cloud visibly spilling across the floor.
 * - Duration: 240 ticks (12 seconds). The mist is a zone
 *   effect around the living spider, not block placement:
 *   no block is created or changed, so the terrain rule
 *   does not apply and mobGriefing is never consulted.
 *
 * WHAT IT LOOKS LIKE: a green fug rolling off the spider's
 * fangs and pooling ankle deep across the stone, witch
 * sparkles turning in it, while everything breathing it
 * sways green faced and keeps taking small green bites of
 * damage it cannot shake off.
 *
 * PERFORMANCE: the pulse runs every 20 ticks with one
 * bounded entity query (8 by 3.5 by 8 box). No per-tick
 * work at all outside the pulse. The particle option is a
 * static final, so the hot path allocates nothing but the
 * query result. Particle budget per activation: opening 12,
 * pulses 12 times 14 = 168, total at most 180 particles.
 *
 * CLEANUP: nothing to undo. No attribute is changed, no
 * block is touched, and the NAUSEA and POISON are short
 * timed effects that expire on their own. end() is
 * intentionally empty.
 *
 * State tag keys: none. The mist reads only live world
 * state on each pulse.
 */
public final class VenomMistAbility implements MobAbility {

    static final int DURATION_TICKS = 240;
    private static final int PULSE_INTERVAL = 20;
    private static final ColorParticleOption VENOM_SPARKLE =
        ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, 0.30f, 0.80f, 0.25f);

    @Override
    public String id() {
        return "venom_mist";
    }

    @Override
    public String displayName() {
        return "Venom Mist";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 64.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        level.sendParticles(VENOM_SPARKLE, mob.getX(), mob.getY() + 0.3, mob.getZ(),
            8, 1.2, 0.2, 1.2, 0.03);
        level.sendParticles(ParticleTypes.WITCH, mob.getX(), mob.getY() + 0.3, mob.getZ(),
            4, 1.2, 0.2, 1.2, 0.03);
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
            SoundEvents.SPIDER_AMBIENT, SoundSource.HOSTILE, 0.5f, 0.7f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % PULSE_INTERVAL != 0) {
            return; // Cheap early-out: the mist pulses every 20 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();

        // The look: the cloud pools low and spills outward.
        level.sendParticles(VENOM_SPARKLE, mob.getX(), mob.getY() + 0.3, mob.getZ(),
            10, 3.2, 0.3, 3.2, 0.02);
        level.sendParticles(ParticleTypes.WITCH, mob.getX(), mob.getY() + 0.3, mob.getZ(),
            4, 3.2, 0.3, 3.2, 0.02);

        // The fumes: nausea for the stomach, a weak poison
        // that keeps refreshing for as long as they stay.
        List<LivingEntity> victims = CaveSpiderAbilitySupport.enemiesAroundSelf(ctx, 4.0, 1.5, 2.0);
        for (LivingEntity victim : victims) {
            victim.addEffect(new MobEffectInstance(MobEffects.NAUSEA, 120, 0));
            victim.addEffect(new MobEffectInstance(MobEffects.POISON, 80, 0));
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Intentionally empty: the mist is a zone effect, the
        // effects it applies are timed and expire on their own.
        // See CLEANUP in the class comment.
    }
}
