package mod.advancedmobs.ability.frog;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * FrogAbilities: the Frog's provider for the new ability
 * system. Supplies the frog's 5 designed abilities
 * (ABILITY-IDEAS.md, Frog): Tongue Lash, Marsh Gas Croak,
 * Leapfrog Slam, Frogspawn Shield, Golden Hour Form. The
 * instances are shared singletons; all per-activation
 * state lives in AbilityContext.state(), never in the
 * ability objects.
 *
 * FROG FACTS (verified against the real 26.2 jar): the
 * Frog class sits in net.minecraft.world.entity.animal.
 * frog, extends Animal, and its createAttributes builds
 * on the Animal base with MOVEMENT_SPEED 1.0, MAX_HEALTH
 * fixed at 10.0, ATTACK_DAMAGE fixed at 10.0 (the vanilla
 * tongue-eat damage), and STEP_HEIGHT 1.0. The frog HAS
 * an ATTACK_DAMAGE attribute instance, so strike damage
 * in this package reads the live attribute value (the
 * mod's damage upgrades and the STRENGTH effect ride it)
 * scaled per ability, instead of flat constants. The
 * frog's sounds are plain SoundEvent constants in 26.2
 * (FROG_AMBIENT, FROG_HURT, FROG_TONGUE, FROG_LONG_JUMP,
 * FROG_LAY_SPAWN). The frog is a Brain mob in vanilla;
 * that changes nothing here, because abilities tick from
 * the AI step the same way for every mob (see the
 * MobAbility contract).
 */
public final class FrogAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new TongueLashAbility(),
        new MarshGasCroakAbility(),
        new LeapfrogSlamAbility(),
        new FrogspawnShieldAbility(),
        new GoldenHourFormAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
