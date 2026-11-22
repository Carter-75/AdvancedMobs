package mod.advancedmobs.ability.turtle;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * TurtleAbilities: the Turtle's provider for the new ability
 * system. Supplies the turtle's 5 designed abilities
 * (ABILITY-IDEAS.md, Turtle): Shell Fortress, Tide Ram, Egg
 * Bastion, Ancient Wisdom, Shellshock Slam. The instances
 * are shared singletons; all per-activation state lives in
 * AbilityContext.state(), never in the ability objects.
 *
 * TURTLE FACTS (verified against the real 26.2 jar): the
 * Turtle class sits in net.minecraft.world.entity.animal.
 * turtle, extends Animal, and its createAttributes builds
 * on the Animal chain with MAX_HEALTH fixed at 30.0,
 * MOVEMENT_SPEED 0.25, and STEP_HEIGHT 1.0. The chain
 * carries NO ATTACK_DAMAGE attribute instance (the base
 * living set lacks it and Mob.createMobAttributes adds only
 * FOLLOW_RANGE), so every damage number in this package is
 * a flat code value dealt through a damage source credited
 * to the turtle. The turtle's sounds are plain SoundEvents:
 * TURTLE_AMBIENT_LAND, TURTLE_SWIM, TURTLE_HURT,
 * TURTLE_LAY_EGG, TURTLE_EGG_CRACK, and TURTLE_EGG_HATCH.
 * Turtle eggs are Blocks.TURTLE_EGG, whose block state
 * carries the TurtleEggBlock.EGGS (1 to 4) and HATCH (0 to
 * 2) integer properties.
 */
public final class TurtleAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new ShellFortressAbility(),
        new TideRamAbility(),
        new EggBastionAbility(),
        new AncientWisdomAbility(),
        new ShellshockSlamAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
