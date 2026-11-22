package mod.advancedmobs.ability.sniffer;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Rooted Colossus (Sniffer ability 3 of 5, transformation
 * / defense).
 *
 * WHAT IT DOES: the sniffer plants its feet and grows,
 * its scale climbing in steps until it towers over the
 * fight, an ancient hill with a nose. Friction is maxed
 * so the ground itself holds it, knockback simply stops
 * applying, armor plates the hide, and as long as it
 * stands rooted on grass or dirt it regenerates steadily.
 * It cannot move at all while rooted: it trades the whole
 * fight's mobility for being unkillable scenery that
 * still soaks aggro.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the sniffer's current target within 12 blocks
 *   (distance squared 144).
 * - Duration: 300 ticks (15 seconds).
 * - The rooting kit (start(), all removed exactly in
 *   end()): five transient attribute modifiers under
 *   fixed ids:
 *   - SCALE, grown in steps (the design's climbing
 *     scale): +0.15 ADD_MULTIPLIED_BASE at start, then
 *     refreshed to +0.30, +0.45, and +0.60 at ticks 10,
 *     20, and 30 (the same modifier id, updated in
 *     place), so the sniffer visibly swells to 1.6 times
 *     its size over the first second and a half.
 *   - FRICTION_MODIFIER +9.0 ADD_VALUE (the design's
 *     maxed friction line: the attribute's default is 1
 *     and high values are instant stops, so at 10 total
 *     the ground grips the colossus completely; the Goat
 *     and Donkey packages' stubborn-stance precedent).
 *   - KNOCKBACK_RESISTANCE +1.0 ADD_VALUE (total: no hit
 *     can shove it).
 *   - ARMOR +10.0 ADD_VALUE (the design's armor line;
 *     the sniffer's base armor is 0).
 *   - MOVEMENT_SPEED -1.0 ADD_MULTIPLIED_TOTAL (speed
 *     multiplied to exactly 0: rooted, held additionally
 *     by per-tick navigation stops, never NoAi).
 * - The hill's patience (tick()): navigation is stopped
 *   every tick. Every 20 ticks the ground check runs (the
 *   design's ground block check, in code): if the block
 *   directly under the sniffer is grass block or in the
 *   vanilla DIRT tag, REGENERATION I is refreshed to 40
 *   ticks; on stone, wood, or anything else, no
 *   regeneration that pulse. Every 4 ticks, 2 brown DUST
 *   motes shake off the hide at its feet.
 * - Sounds: SNIFFER_IDLE at volume 1.2, pitch 0.7 in
 *   start() (a deep settling rumble), and again at pitch
 *   1.0 in end() as it uproots.
 * - end(): all five modifiers are removed and
 *   REGENERATION is stripped. Idempotent.
 *
 * WHAT IT LOOKS LIKE: the old beast sighs, swells half
 * again as tall in four visible steps, and settles like a
 * mossy hill settling into the field, dust puffing off
 * its flanks, wounds closing while it stands on living
 * ground.
 *
 * PERFORMANCE: start() and end() are five modifier writes
 * each; tick() is one navigation stop, one modifier update
 * on 3 ticks total, one block read every 20 ticks, and 2
 * particle sends every 4 ticks. No entity queries anywhere.
 * Particle budget per activation: 75 sends times 2 = 150.
 *
 * TERRAIN: this ability changes no blocks; the ground
 * check only reads the one block under the caster. The
 * mobGriefing gate does not apply.
 *
 * State tag keys: none (the growth steps and pulses all
 * key off the framework's tick counter).
 */
public final class RootedColossusAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "rooted_colossus_scale");
    private static final Identifier FRICTION_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "rooted_colossus_friction");
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "rooted_colossus_knockback");
    private static final Identifier ARMOR_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "rooted_colossus_armor");
    private static final Identifier SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "rooted_colossus_speed");
    /** The growth steps: scale bonus applied at start and at ticks 10, 20, 30. */
    private static final double[] SCALE_STEPS = {0.15, 0.30, 0.45, 0.60};
    private static final DustParticleOptions HILL_DUST = new DustParticleOptions(0x8A6D3B, 1.0f);

    @Override
    public String id() {
        return "rooted_colossus";
    }

    @Override
    public String displayName() {
        return "Rooted Colossus";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        SnifferAbilitySupport.addModifier(caster, Attributes.SCALE,
            new AttributeModifier(SCALE_ID, SCALE_STEPS[0], AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        SnifferAbilitySupport.addModifier(caster, Attributes.FRICTION_MODIFIER,
            new AttributeModifier(FRICTION_ID, 9.0, AttributeModifier.Operation.ADD_VALUE));
        SnifferAbilitySupport.addModifier(caster, Attributes.KNOCKBACK_RESISTANCE,
            new AttributeModifier(KNOCKBACK_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        SnifferAbilitySupport.addModifier(caster, Attributes.ARMOR,
            new AttributeModifier(ARMOR_ID, 10.0, AttributeModifier.Operation.ADD_VALUE));
        SnifferAbilitySupport.addModifier(caster, Attributes.MOVEMENT_SPEED,
            new AttributeModifier(SPEED_ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.SNIFFER_IDLE, SoundSource.HOSTILE, 1.2f, 0.7f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        // Rooted: hold position through navigation stops,
        // never NoAi (see the class comment).
        caster.getNavigation().stop();

        int ticks = ctx.ticksActive();

        // The climb: scale steps at ticks 10, 20, and 30.
        if (ticks == 10 || ticks == 20 || ticks == 30) {
            double step = SCALE_STEPS[ticks / 10];
            SnifferAbilitySupport.addModifier(caster, Attributes.SCALE,
                new AttributeModifier(SCALE_ID, step, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }

        // Rooted on living ground, the hill mends.
        if (ticks % 20 == 0) {
            BlockState ground = level.getBlockState(BlockPos.containing(caster.getX(), caster.getY() - 0.5, caster.getZ()));
            if (ground.is(Blocks.GRASS_BLOCK) || ground.is(BlockTags.DIRT)) {
                caster.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 40, 0));
            }
        }

        if (ticks % 4 == 0) {
            level.sendParticles(HILL_DUST, caster.getX(), caster.getY() + 0.3, caster.getZ(),
                2, 0.8, 0.2, 0.8, 0.03);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        SnifferAbilitySupport.removeModifier(caster, Attributes.SCALE, SCALE_ID);
        SnifferAbilitySupport.removeModifier(caster, Attributes.FRICTION_MODIFIER, FRICTION_ID);
        SnifferAbilitySupport.removeModifier(caster, Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_ID);
        SnifferAbilitySupport.removeModifier(caster, Attributes.ARMOR, ARMOR_ID);
        SnifferAbilitySupport.removeModifier(caster, Attributes.MOVEMENT_SPEED, SPEED_ID);
        caster.removeEffect(MobEffects.REGENERATION);

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.SNIFFER_IDLE, SoundSource.HOSTILE, 1.0f, 1.0f);
        }
    }
}
