package mod.advancedmobs.ability.wolf;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Alpha Form (Wolf ability 3 of 5, transformation).
 *
 * WHAT IT DOES: the wolf swells to half again its size, fur
 * standing on end, eyes glowing red, breath puffing white
 * in the cold air. As an alpha it has a bigger health pool
 * and bites far harder, and allied wolves near it fight
 * harder simply for being near it.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the wolf's current target within 16 blocks (distance
 *   squared 256).
 * - Duration: 400 ticks (20 seconds), hard finish.
 * - The form (start()): three transient attribute modifiers
 *   under fixed ids, all removed in end():
 *     SCALE          add_multiplied_base  +0.5 (1.5x size)
 *     MAX_HEALTH     add_value            +20.0
 *     ATTACK_DAMAGE  add_value            +4.0 (doubles a
 *                    plain wolf's base 4.0 bite to 8.0)
 *   The +20 health arrives full, the Colossus Form pattern:
 *   right after the MAX_HEALTH modifier lands, current
 *   health rises by 20 (clamped to the new max). In end()
 *   the modifier comes off first and health is then clamped
 *   to the restored max, so the bonus health never lingers.
 * - The arrival (start()): the classic wolf variant's growl
 *   plays at volume 1.5, pitch 0.8 (resolved through
 *   WolfAbilitySupport.wolfSound; skipped if the variant
 *   map has no classic entry).
 * - The eyes (tick(), every 10 ticks): 3 red ENTITY_EFFECT
 *   motes (color 0.85, 0.10, 0.10, the design's red tint)
 *   puff at the wolf's head. There is no server-side
 *   retexture hook in 26.2, so the darkened raised fur of
 *   the design is carried by the 1.5x scale and these red
 *   motes; documented here so nobody hunts for a model
 *   change that does not exist.
 * - The breath (tick(), every 20 ticks): 4 white DUST motes
 *   (color 0xF2F2F2) puff from just in front of the wolf's
 *   mouth, along its look direction.
 * - The alpha's presence (tick(), every 20 ticks): one
 *   bounded query of radius 10 around the caster; every
 *   allied Wolf in it gains STRENGTH I (amplifier 0)
 *   refreshed for 40 ticks (the design's nearby wolves
 *   fighting harder). The caster itself does not get the
 *   effect; its harder bite is the ATTACK_DAMAGE modifier.
 *   The strength is an ordinary effect on its own clock and
 *   is not stripped in end().
 * - end(): removes the three modifiers and clamps health
 *   (see above). Nothing else persists.
 *
 * WHAT IT LOOKS LIKE: a wolf suddenly half again as big,
 * growling low, red sparks winking at its head and its
 * breath steaming white, while the wolves around it hit
 * harder.
 *
 * PERFORMANCE: tick() returns except every 10th tick (3
 * particles) and every 20th tick (one bounded query plus 4
 * particles and at most a handful of effect refreshes).
 * Modifiers are built once in start() and removed once in
 * end(). Particle budget per activation: 40 eye pulses
 * times 3 = 120, 20 breath puffs times 4 = 80, total at
 * most 200 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: none (the duration runs off the
 * framework's tick counter and the fixed modifier ids).
 */
public final class AlphaFormAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int EYE_INTERVAL = 10;
    private static final int AURA_INTERVAL = 20;
    private static final double AURA_RADIUS = 10.0;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "alpha_form_scale");
    private static final Identifier HEALTH_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "alpha_form_health");
    private static final Identifier DAMAGE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "alpha_form_damage");
    private static final ColorParticleOption EYE_GLOW =
        ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, 0.85f, 0.10f, 0.10f);
    private static final DustParticleOptions BREATH_DUST = new DustParticleOptions(0xF2F2F2, 1.0f);

    @Override
    public String id() {
        return "alpha_form";
    }

    @Override
    public String displayName() {
        return "Alpha Form";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        WolfAbilitySupport.addModifier(caster, Attributes.SCALE,
            new AttributeModifier(SCALE_ID, 0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        WolfAbilitySupport.addModifier(caster, Attributes.MAX_HEALTH,
            new AttributeModifier(HEALTH_ID, 20.0, AttributeModifier.Operation.ADD_VALUE));
        WolfAbilitySupport.addModifier(caster, Attributes.ATTACK_DAMAGE,
            new AttributeModifier(DAMAGE_ID, 4.0, AttributeModifier.Operation.ADD_VALUE));
        // The bonus health arrives full (see the class comment).
        caster.setHealth(Math.min(caster.getMaxHealth(), caster.getHealth() + 20.0f));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            Holder<SoundEvent> growl = WolfAbilitySupport.wolfSound(true);
            if (growl != null) {
                level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), growl,
                    SoundSource.HOSTILE, 1.5f, 0.8f);
            }
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        boolean eyes = ticks % EYE_INTERVAL == 0;
        boolean aura = ticks % AURA_INTERVAL == 0;
        if (!eyes && !aura) {
            return; // Cheap early-out: work only on the 10 and 20 tick pulses.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        if (eyes) {
            level.sendParticles(EYE_GLOW, caster.getX(), caster.getEyeY(), caster.getZ(),
                3, 0.25, 0.15, 0.25, 0.05);
        }
        if (aura) {
            // Breath: a white puff just in front of the mouth.
            Vec3 look = caster.getLookAngle();
            level.sendParticles(BREATH_DUST, caster.getX() + look.x * 0.7,
                caster.getEyeY() - 0.15, caster.getZ() + look.z * 0.7,
                4, 0.12, 0.08, 0.12, 0.03);
            // The alpha's presence: nearby allied wolves fight harder.
            List<LivingEntity> allies = WolfAbilitySupport.alliesInBox(ctx,
                caster.getX(), caster.getY(), caster.getZ(), AURA_RADIUS, 4.0, 4.0);
            for (LivingEntity ally : allies) {
                if (ally instanceof Wolf wolf) {
                    wolf.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 40, 0));
                }
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        WolfAbilitySupport.removeModifier(caster, Attributes.SCALE, SCALE_ID);
        WolfAbilitySupport.removeModifier(caster, Attributes.ATTACK_DAMAGE, DAMAGE_ID);
        WolfAbilitySupport.removeModifier(caster, Attributes.MAX_HEALTH, HEALTH_ID);
        // Clamp any health above the restored max (see the class comment).
        if (caster.getHealth() > caster.getMaxHealth()) {
            caster.setHealth(caster.getMaxHealth());
        }
    }
}
