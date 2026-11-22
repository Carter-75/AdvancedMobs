package mod.advancedmobs.ability.magmacube;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
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

import java.util.List;

/**
 * Volcano Core (Magma Cube ability 5 of 5, transformation).
 *
 * WHAT IT DOES: the magma cube cracks open along glowing
 * seams, SMALL_FLAME and LAVA boiling out of its core as it
 * swells by 60 percent and burns GLOWING, its outline visible
 * through smoke and walls. Radiant heat rolls off the core:
 * every second, every enemy standing close is set alight. And
 * the cube's STRENGTH rises with its temperature: STRENGTH I
 * for the first half of the form, STRENGTH II once the core
 * peaks at the halfway mark, so by the end it hits like a
 * landslide.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists within 10 blocks
 *   (distance squared 100).
 * - Duration: exactly 240 ticks (12 seconds) from start().
 * - The form (all applied in start(), removed in end()):
 *     SCALE add_multiplied_base +0.6 (the cube's scale climbs
 *       to 1.6 times its size for the whole form, modifier id
 *       advancedmobs:volcano_core_scale)
 *     GLOWING for 280 ticks, amplifier 0 (the burning outline;
 *       vanilla glowing renders the outline the design calls
 *       for, through walls)
 *     STRENGTH amplifier 0 for 280 ticks (STRENGTH I; the
 *       cube's melee damage climbs with its heat from the
 *       first second)
 * - The peak: at tick 120, the halfway mark, the temperature
 *   tops out: STRENGTH is re-applied at amplifier 1 (STRENGTH
 *   II) for the remaining 160 ticks, with a vent of 10 LAVA
 *   particles and a LAVA_POP (volume 0.9, pitch 0.5) at the
 *   cube. From here to the end of the form the cube hits at
 *   full landslide weight.
 * - The radiant heat: every 20 ticks, one bounded enemy query
 *   (MagmaCubeAbilitySupport rules) in radius 3.5 around the
 *   cube, from 1.5 below to 2.5 above it. Every enemy in the
 *   radius is ignited for 3 seconds (igniteForSeconds) and
 *   gets a 5 particle SMALL_FLAME burst at its own position:
 *   standing next to an open volcano core is standing in fire,
 *   and stepping away is the only counter. The cube itself is
 *   fire immune by type and takes nothing from its own heat.
 *
 * WHAT IT LOOKS LIKE: a magma cube half again as large,
 *   outlined in light, breathing 1 SMALL_FLAME every 5 ticks
 *   and a LAVA drip every 10, its seams flaring in a 10
 *   particle LAVA vent at the halfway peak, and every enemy
 *   that lingers beside it bursting into flame once a second.
 *
 * PERFORMANCE: the heat query runs every 20 ticks only, on a
 * tight box; the other ticks are two small particle pulses on
 * 5 and 10 tick intervals. The SCALE modifier is resolved at
 * start() and removed at end(), the effects are applied at
 * start() and at the peak, never per tick. Particle budget per
 * activation: breath 48 pulses times 1 = 48 SMALL_FLAME, drips
 * 24 times 1 = 24 LAVA, peak vent 10 LAVA, heat bursts at most
 * 12 pulses times 5 per enemy (a packed crowd of 4 enemies is
 * 240; a lone target is 60), total at most about 320 particles
 * in the worst crowded case and about 140 against a single
 * target.
 *
 * CLEANUP: end() removes the SCALE modifier and the GLOWING
 * and STRENGTH effects. If the caster dies mid-form, the
 * framework termination path runs this same end(), so the
 * form never outlives the ability or its caster. Nothing else
 * was created; ignitions already handed out burn on vanilla's
 * own schedule.
 *
 * State tag keys: none of its own (the framework's tick
 * counter drives every phase; the peak reads ticksActive()
 * directly).
 */
public final class VolcanoCoreAbility implements MobAbility {

    static final int DURATION_TICKS = 240;
    private static final int PEAK_TICK = 120;
    private static final int HEAT_INTERVAL = 20;
    private static final double HEAT_RADIUS = 3.5;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "volcano_core_scale");

    @Override
    public String id() {
        return "volcano_core";
    }

    @Override
    public String displayName() {
        return "Volcano Core";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return ctx.target() != null && ctx.mob().distanceToSqr(ctx.target()) <= 100.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob mob = ctx.mob();
        MagmaCubeAbilitySupport.addModifier(mob, Attributes.SCALE,
            new AttributeModifier(SCALE_ID, 0.6, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, DURATION_TICKS + 40, 0));
        mob.addEffect(new MobEffectInstance(MobEffects.STRENGTH, DURATION_TICKS + 40, 0));
        if (level != null) {
            level.sendParticles(ParticleTypes.LAVA, mob.getX(), mob.getY() + 0.5, mob.getZ(),
                8, mob.getBbWidth() * 0.5, 0.5, mob.getBbWidth() * 0.5, 0.08);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.LAVA_POP,
                SoundSource.HOSTILE, 0.9f, 0.7f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        int ticks = ctx.ticksActive();

        // The seams breathe flame and weep lava on their own
        // small intervals.
        if (ticks % 5 == 0) {
            level.sendParticles(ParticleTypes.SMALL_FLAME, mob.getX(), mob.getY() + mob.getBbHeight() * 0.5, mob.getZ(),
                1, mob.getBbWidth() * 0.45, 0.4, mob.getBbWidth() * 0.45, 0.03);
        }
        if (ticks % 10 == 0) {
            level.sendParticles(ParticleTypes.LAVA, mob.getX(), mob.getY() + 0.3, mob.getZ(),
                1, mob.getBbWidth() * 0.5, 0.3, mob.getBbWidth() * 0.5, 0.0);
        }

        // The peak: the core tops out and the strength follows.
        if (ticks == PEAK_TICK) {
            mob.addEffect(new MobEffectInstance(MobEffects.STRENGTH, DURATION_TICKS - PEAK_TICK + 40, 1));
            level.sendParticles(ParticleTypes.LAVA, mob.getX(), mob.getY() + 0.5, mob.getZ(),
                10, mob.getBbWidth() * 0.6, 0.6, mob.getBbWidth() * 0.6, 0.12);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.LAVA_POP,
                SoundSource.HOSTILE, 0.9f, 0.5f);
        }

        // The radiant heat: once a second, everything close burns.
        if (ticks % HEAT_INTERVAL == 0) {
            List<LivingEntity> victims = MagmaCubeAbilitySupport.enemiesInBox(ctx,
                mob.getX(), mob.getY(), mob.getZ(), HEAT_RADIUS, 1.5, 2.5);
            for (LivingEntity victim : victims) {
                victim.igniteForSeconds(3.0f);
                level.sendParticles(ParticleTypes.SMALL_FLAME,
                    victim.getX(), victim.getY() + 0.6, victim.getZ(),
                    5, 0.35, 0.5, 0.35, 0.05);
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        MagmaCubeAbilitySupport.removeModifier(mob, Attributes.SCALE, SCALE_ID);
        mob.removeEffect(MobEffects.GLOWING);
        mob.removeEffect(MobEffects.STRENGTH);
    }
}
