package mod.advancedmobs.ability.turtle;

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

/**
 * Shell Fortress (Turtle ability 1 of 5, defense).
 *
 * WHAT IT DOES: the turtle pulls in and becomes a dome of
 * pure shell. Its armor and toughness jump to the top of
 * the scale, knockback simply stops applying to it, and it
 * cannot move at all: a planted dome that waits out enemy
 * cooldowns in near perfect safety while its own cooldowns
 * tick down elsewhere.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the turtle's current target within 12 blocks (distance
 *   squared 144).
 * - The brace (start(), all removed exactly in end()):
 *   four transient attribute modifiers under fixed ids:
 *   ARMOR +20.0 ADD_VALUE (the design's maxed armor line;
 *   the turtle's base armor is 0, so it fights at 20 armor
 *   points, the full diamond tier) and ARMOR_TOUGHNESS
 *   +12.0 ADD_VALUE (the design's maxed toughness line, the
 *   netherite tier), KNOCKBACK_RESISTANCE +1.0 ADD_VALUE
 *   (the attribute clamps at total resistance, so no hit
 *   can move the turtle at all), and MOVEMENT_SPEED -1.0
 *   ADD_MULTIPLIED_TOTAL (speed multiplied to exactly 0:
 *   the fortress is planted, held additionally by per-tick
 *   navigation stops, never NoAi). RESISTANCE I is applied
 *   for 320 ticks and removed in end(), stacking its 20
 *   percent cut on top of the armor.
 * - Entry and exit (start() and end()): a burst of 12 POOF
 *   particles around the shell (the design's poof line) and
 *   TURTLE_AMBIENT_LAND at volume 1.0, pitch 0.8 on entry,
 *   pitch 1.0 on exit.
 * - The wait (tick(), every tick): navigation is stopped.
 *   Every 20 ticks, 3 POOF motes puff off the shell rim as
 *   dust shakes loose.
 * - Duration: exactly 300 ticks (15 seconds), hard finish.
 * - end(): all four modifiers and the RESISTANCE effect are
 *   removed, then the exit burst. Idempotent: safe when
 *   start() never applied anything.
 *
 * WHAT IT LOOKS LIKE: a puff of dust, a low creaking grunt,
 * and the turtle is suddenly just a green dome on the
 * ground: arrows thock off it, swords ring, and nothing
 * shifts it an inch until it unfolds again in a second
 * puff.
 *
 * PERFORMANCE: start() and end() are four modifier writes,
 * one effect write, and one particle burst each. tick() is
 * one navigation stop and a 3-particle emission every 20
 * ticks; there are no entity queries anywhere in the
 * ability. Particle budget per activation: 12 at start,
 * 15 pulses times 3 = 45 during, 12 at end, total at most
 * 69 particles.
 *
 * TERRAIN: this ability changes no blocks; the mobGriefing
 * gate does not apply.
 *
 * State tag keys: none (the activation is pure timed state
 * on the caster itself, tracked by the framework's tick
 * counter).
 */
public final class ShellFortressAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final Identifier ARMOR_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "shell_fortress_armor");
    private static final Identifier TOUGHNESS_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "shell_fortress_toughness");
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "shell_fortress_knockback");
    private static final Identifier SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "shell_fortress_speed");

    @Override
    public String id() {
        return "shell_fortress";
    }

    @Override
    public String displayName() {
        return "Shell Fortress";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();

        // The brace: maxed shell, total knockback immunity, planted.
        TurtleAbilitySupport.addModifier(caster, Attributes.ARMOR,
            new AttributeModifier(ARMOR_ID, 20.0, AttributeModifier.Operation.ADD_VALUE));
        TurtleAbilitySupport.addModifier(caster, Attributes.ARMOR_TOUGHNESS,
            new AttributeModifier(TOUGHNESS_ID, 12.0, AttributeModifier.Operation.ADD_VALUE));
        TurtleAbilitySupport.addModifier(caster, Attributes.KNOCKBACK_RESISTANCE,
            new AttributeModifier(KNOCKBACK_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        TurtleAbilitySupport.addModifier(caster, Attributes.MOVEMENT_SPEED,
            new AttributeModifier(SPEED_ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        caster.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, DURATION_TICKS + 20, 0));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.sendParticles(ParticleTypes.POOF, caster.getX(), caster.getY() + 0.4, caster.getZ(),
                12, 0.6, 0.3, 0.6, 0.05);
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.TURTLE_AMBIENT_LAND, SoundSource.HOSTILE, 1.0f, 0.8f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        // Planted: hold position through navigation stops,
        // never NoAi (see the class comment).
        caster.getNavigation().stop();

        ServerLevel level = ctx.serverLevel();
        if (level != null && ctx.ticksActive() % 20 == 0) {
            level.sendParticles(ParticleTypes.POOF, caster.getX(), caster.getY() + 0.5, caster.getZ(),
                3, 0.6, 0.2, 0.6, 0.03);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        TurtleAbilitySupport.removeModifier(caster, Attributes.ARMOR, ARMOR_ID);
        TurtleAbilitySupport.removeModifier(caster, Attributes.ARMOR_TOUGHNESS, TOUGHNESS_ID);
        TurtleAbilitySupport.removeModifier(caster, Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_ID);
        TurtleAbilitySupport.removeModifier(caster, Attributes.MOVEMENT_SPEED, SPEED_ID);
        caster.removeEffect(MobEffects.RESISTANCE);

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.sendParticles(ParticleTypes.POOF, caster.getX(), caster.getY() + 0.4, caster.getZ(),
                12, 0.6, 0.3, 0.6, 0.05);
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.TURTLE_AMBIENT_LAND, SoundSource.HOSTILE, 1.0f, 1.0f);
        }
    }
}
