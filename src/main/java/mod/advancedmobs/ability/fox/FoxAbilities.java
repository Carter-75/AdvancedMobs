package mod.advancedmobs.ability.fox;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * FoxAbilities: the Fox's provider for the new ability
 * system. Supplies the fox's 5 designed abilities
 * (ABILITY-IDEAS.md, Fox): Berry Bombardier, Snow Pounce
 * Nova, Trickster Decoy, Ember Thief, Den Call. The
 * instances are shared singletons; all per-activation
 * state lives in AbilityContext.state(), never in the
 * ability objects.
 *
 * FOX FACTS (verified against the real 26.2 jar): the
 * Fox class sits in net.minecraft.world.entity.animal.
 * fox, extends Animal, and its createAttributes builds on
 * the Animal base with MAX_HEALTH fixed at 10.0,
 * MOVEMENT_SPEED 0.3, ATTACK_DAMAGE 2.0, SAFE_FALL_DISTANCE
 * 5.0, and FOLLOW_RANGE 32.0. Like the cat and the ocelot,
 * the fox HAS an ATTACK_DAMAGE attribute instance, so
 * strike damage in this package reads the live attribute
 * value (the mod's damage upgrades and the STRENGTH
 * effect ride it) instead of flat constants, except where
 * a class comment says a number is a flat code value. The
 * fox's sounds are plain SoundEvent constants in 26.2
 * (SoundEvents.FOX_AMBIENT, FOX_BITE, FOX_SCREECH,
 * FOX_SPIT, FOX_AGGRO, FOX_HURT), played directly. The
 * fox carries two synced pose flags the package uses:
 * setIsCrouching and setIsPouncing, the vanilla pounce
 * animation flags. A fox holds its carried item in the
 * MAINHAND equipment slot (its mouth), which is how the
 * Den Call pups get their weapons.
 */
public final class FoxAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new BerryBombardierAbility(),
        new SnowPounceNovaAbility(),
        new TricksterDecoyAbility(),
        new EmberThiefAbility(),
        new DenCallAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
