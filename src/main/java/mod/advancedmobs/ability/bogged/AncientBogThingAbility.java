package mod.advancedmobs.ability.bogged;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Ancient Bog Thing (Bogged ability 5 of 5, transformation).
 *
 * WHAT IT DOES: the bogged rises into a towering swamp horror:
 * its frame swells to nearly double height, mossy spores cascade
 * off it, its bow creaks with new STRENGTH, and its silhouette
 * glows faintly through the swamp murk. While it stands in water
 * or in mire, the bog knits it back together with REGENERATION.
 * When the duration runs out it settles back to a bogged, with
 * every bonus removed.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   bogged's current target within 16 blocks (distance squared
 *   256).
 * - Duration: exactly 300 ticks (15 seconds) from start().
 * - Attribute changes, transient modifiers added in start() and
 *   removed in end(), ids under advancedmobs:ancient_bog_thing_*:
 *     SCALE       add_value  +0.8  (total scale 1.8, near double height)
 *     MAX_HEALTH  add_value  +16.0 (a base bogged goes 16 to 32)
 *   On start the bogged is healed by the +16 max health the
 *   modifier adds, so the extra pool arrives full. On end, after
 *   the MAX_HEALTH modifier is removed, current health is
 *   clamped down to the restored maximum.
 * - Effects applied in start() for the full 300 ticks and
 *   removed in end(): STRENGTH II (amplifier 1), the bow-creaking
 *   strength the design names (it drives the bogged's melee if
 *   anything closes with it; its arrows are unaffected, as with
 *   any strength effect), and GLOWING (amplifier 0), the faint
 *   silhouette through the murk. Removing them in end() is the
 *   standing fixed-duration effect contract of this system: if
 *   the bogged somehow had either effect from another source,
 *   it loses it at end(), an accepted edge case shared with
 *   every effect ability in the mod.
 * - The knitting: every 20 ticks the ability checks the bogged's
 *   footing. If it is in water (isInWater), or the block at its
 *   feet is water, or the block under its feet is MUD (its own
 *   Bog Mire counts, as does any natural swamp mud), it gets
 *   REGENERATION I (amplifier 0) for 45 ticks, refreshed by the
 *   next check while it stays in the wet. Out of the wet, the
 *   regeneration simply lapses within those 45 ticks.
 * - No state tag entries are needed: the whole activation is the
 *   two modifiers, the two effects, and the tick counter, so a
 *   resumed activation after a chunk reload behaves identically.
 *
 * WHAT IT LOOKS AND SOUNDS LIKE: the model and hitbox swell via
 * the SCALE attribute, outlined by the GLOWING effect. On start,
 * 12 MYCELIUM particles burst off the frame with a deep
 * MUD_BREAK groan (volume 1.2, pitch 0.5). Every 5 ticks, 3
 * FALLING_SPORE_BLOSSOM and 2 MYCELIUM cascade off the body:
 * the mossy shedding the design describes.
 *
 * PERFORMANCE: tick() returns immediately except every 5th tick
 * (the particle cascade) and every 20th tick (the footing check:
 * two block reads and one effect application at most). No entity
 * scans at all, no allocations in the pulse path beyond the
 * pre-built particle references. Particle budget per activation:
 * 12 at start plus at most 60 cascades times 5 = 300, total at
 * most 312 particles.
 *
 * CLEANUP: end() removes both modifiers by id (removeModifier is
 * a no-op when absent), clamps health, and removes the STRENGTH
 * and GLOWING effects. If the bogged dies mid-transformation the
 * modifiers and effects die with the entity; nothing else was
 * created, so there is no leftover state in the world.
 */
public final class AncientBogThingAbility implements MobAbility {

    static final int DURATION_TICKS = 300;
    private static final int REGEN_CHECK_INTERVAL = 20;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "ancient_bog_thing_scale");
    private static final Identifier HEALTH_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "ancient_bog_thing_health");

    @Override
    public String id() {
        return "ancient_bog_thing";
    }

    @Override
    public String displayName() {
        return "Ancient Bog Thing";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        addModifier(mob, Attributes.SCALE, new AttributeModifier(SCALE_ID, 0.8, AttributeModifier.Operation.ADD_VALUE));
        addModifier(mob, Attributes.MAX_HEALTH, new AttributeModifier(HEALTH_ID, 16.0, AttributeModifier.Operation.ADD_VALUE));
        // The +16 max health arrives full.
        mob.setHealth(Math.min(mob.getMaxHealth(), mob.getHealth() + 16.0f));

        // The strength and the glow last exactly the transformation.
        mob.addEffect(new MobEffectInstance(MobEffects.STRENGTH, DURATION_TICKS, 1));
        mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, DURATION_TICKS, 0));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.sendParticles(ParticleTypes.MYCELIUM, mob.getX(), mob.getY() + 1.2, mob.getZ(),
                12, 0.6, 0.8, 0.6, 0.06);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.MUD_BREAK, SoundSource.HOSTILE, 1.2f, 0.5f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();

        if (ticks % 5 == 0) {
            // The mossy cascade: spore blossom and mycelium
            // shedding off the swollen frame.
            level.sendParticles(ParticleTypes.FALLING_SPORE_BLOSSOM, mob.getX(), mob.getY() + 1.8, mob.getZ(),
                3, 0.7, 0.8, 0.7, 0.02);
            level.sendParticles(ParticleTypes.MYCELIUM, mob.getX(), mob.getY() + 1.0, mob.getZ(),
                2, 0.7, 0.6, 0.7, 0.03);
        }

        if (ticks % REGEN_CHECK_INTERVAL == 0 && standsInTheWet(ctx, mob)) {
            // The bog knits its thing back together.
            mob.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 45, 0));
        }
    }

    /**
     * Whether the bogged is standing in water or mire: in water
     * by the entity's own check, standing in a water block at its
     * feet, or standing on mud (its own Bog Mire or natural swamp
     * mud alike).
     */
    private boolean standsInTheWet(AbilityContext ctx, Mob mob) {
        if (mob.isInWater()) {
            return true;
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return false;
        }
        BlockPos feet = mob.blockPosition();
        BlockState atFeet = level.getBlockState(feet);
        if (!atFeet.getFluidState().isEmpty()) {
            return true;
        }
        return level.getBlockState(feet.below()).is(Blocks.MUD);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        removeModifier(mob, Attributes.SCALE, SCALE_ID);
        removeModifier(mob, Attributes.MAX_HEALTH, HEALTH_ID);
        // Clamp health to the restored maximum if the bogged was
        // above it.
        if (mob.getHealth() > mob.getMaxHealth()) {
            mob.setHealth(mob.getMaxHealth());
        }
        mob.removeEffect(MobEffects.STRENGTH);
        mob.removeEffect(MobEffects.GLOWING);
    }

    private static void addModifier(Mob mob, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
                                    AttributeModifier modifier) {
        AttributeInstance instance = mob.getAttribute(attribute);
        if (instance != null) {
            instance.addOrUpdateTransientModifier(modifier);
        }
    }

    private static void removeModifier(Mob mob, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
                                       Identifier id) {
        AttributeInstance instance = mob.getAttribute(attribute);
        if (instance != null) {
            instance.removeModifier(id);
        }
    }
}
