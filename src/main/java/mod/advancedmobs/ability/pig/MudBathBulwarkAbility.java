package mod.advancedmobs.ability.pig;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;

/**
 * Mud Bath Bulwark (Pig ability 4 of 5, defense).
 *
 * WHAT IT DOES: the pig drops and rolls in place, caking
 * itself in wet mud until it wears a dull brown coat.
 * Under the mud it is armored and padded with absorption
 * hearts, shrugging off hits until the layer is beaten
 * off or the bath ends.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the pig's current target within 12 blocks
 *   (distance squared 144).
 * - Duration: 300 ticks (15 seconds), in two phases.
 * - Phase 0, the roll (ticks 1 to 20): the pig stays in
 *   place (navigation stopped every tick, never NoAi)
 *   and cakes itself: every 2 ticks, 4 mud BLOCK particles
 *   fleck off its body and 2 DRIPPING_WATER particles drip
 *   down its flanks (the design's wet mud look: block
 *   flecks of the mud block state plus water drips). The
 *   pig's classic ambient sound plays at start, volume
 *   1.2, pitch 1.1 (a happy roll).
 * - The coat (applied in start(), removed in end()):
 *   ARMOR add_value +10.0 (id
 *   advancedmobs:mud_bath_armor) and ARMOR_TOUGHNESS
 *   add_value +4.0 (id advancedmobs:mud_bath_toughness),
 *   the design's armor and armor_toughness lines, plus
 *   the ABSORPTION effect at amplifier 1 (ABSORPTION II,
 *   8 absorption hearts) for 320 ticks, the design's
 *   absorption line. The absorption is the mud layer
 *   itself: hits eat the yellow hearts first, and when
 *   they are gone the coat is beaten off in spirit, though
 *   the armor modifiers run the full duration. end()
 *   removes both modifiers and the absorption effect (a
 *   permanent upgrade's own absorption, if any, is the
 *   accepted edge case shared with every fixed-duration
 *   effect in this system).
 * - Phase 1, the bulwark (ticks 21 to 300): the pig moves
 *   and fights normally under the coat. The mud keeps
 *   drying on it: 2 mud BLOCK particles fleck off every
 *   10 ticks. Nothing else runs per tick.
 * - end(): both modifiers and the absorption are removed,
 *   as above. Idempotent: removing an absent modifier or
 *   effect is a no-op.
 *
 * WHAT IT LOOKS LIKE: a pig flopping side to side in an
 * invisible wallow, mud flecks flying, water dripping off
 * its belly, then standing up wearing a matte brown coat
 * with eight yellow hearts over its health.
 *
 * PERFORMANCE: the roll does one navigation stop and one
 * particle emission every 2 ticks; the bulwark phase does
 * one emission every 10 ticks; zero entity queries in the
 * whole ability. Particle budget per activation: roll 10
 * times 6 = 60, bulwark 28 times 2 = 56, total at most
 * 116 particles. The mud block option is a pre-built
 * static final.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply. The mud is particles and an effect, never
 * placed blocks.
 *
 * State tag keys: none beyond the framework's own; the
 * phase is read from ticksActive() directly.
 */
public final class MudBathBulwarkAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int ROLL_TICKS = 20;
    private static final int EFFECT_TICKS = 320;
    private static final BlockParticleOption MUD_BLOCK =
        new BlockParticleOption(ParticleTypes.BLOCK, Blocks.MUD.defaultBlockState());
    private static final Identifier ARMOR_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "mud_bath_armor");
    private static final Identifier TOUGHNESS_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "mud_bath_toughness");

    @Override
    public String id() {
        return "mud_bath_bulwark";
    }

    @Override
    public String displayName() {
        return "Mud Bath Bulwark";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        PigAbilitySupport.addModifier(caster, Attributes.ARMOR,
            new AttributeModifier(ARMOR_ID, 10.0, AttributeModifier.Operation.ADD_VALUE));
        PigAbilitySupport.addModifier(caster, Attributes.ARMOR_TOUGHNESS,
            new AttributeModifier(TOUGHNESS_ID, 4.0, AttributeModifier.Operation.ADD_VALUE));
        caster.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, EFFECT_TICKS, 1));

        ServerLevel level = ctx.serverLevel();
        if (level != null && PigAbilitySupport.pigSound() != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                PigAbilitySupport.pigSound(), net.minecraft.sounds.SoundSource.HOSTILE, 1.2f, 1.1f);
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
        if (ticks <= ROLL_TICKS) {
            // The roll: rooted, mud flying.
            caster.getNavigation().stop();
            if (ticks % 2 == 0) {
                level.sendParticles(MUD_BLOCK,
                    caster.getX(), caster.getY() + 0.5, caster.getZ(),
                    4, 0.5, 0.4, 0.5, 0.12);
                level.sendParticles(ParticleTypes.DRIPPING_WATER,
                    caster.getX(), caster.getY() + 0.6, caster.getZ(),
                    2, 0.45, 0.3, 0.45, 0.05);
            }
        } else if (ticks % 10 == 0) {
            // The drying coat sheds a fleck now and then.
            level.sendParticles(MUD_BLOCK,
                caster.getX(), caster.getY() + 0.5, caster.getZ(),
                2, 0.5, 0.4, 0.5, 0.08);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        PigAbilitySupport.removeModifier(caster, Attributes.ARMOR, ARMOR_ID);
        PigAbilitySupport.removeModifier(caster, Attributes.ARMOR_TOUGHNESS, TOUGHNESS_ID);
        caster.removeEffect(MobEffects.ABSORPTION);
    }
}
