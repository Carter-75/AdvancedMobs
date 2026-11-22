package mod.advancedmobs.ability.blaze;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * BlazeAbilities: the Blaze's provider for the new ability
 * system. Supplies the blaze's 5 designed abilities
 * (ABILITY-IDEAS.md, Blaze): Solar Crucible, Magma Ring
 * Rising, Cinder Meteor Volley, Ashen Veil, Rod Aegis.
 * The instances are shared singletons; all per-activation
 * state lives in AbilityContext.state(), never in the ability
 * objects.
 */
public final class BlazeAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new SolarCrucibleAbility(),
        new MagmaRingRisingAbility(),
        new CinderMeteorVolleyAbility(),
        new AshenVeilAbility(),
        new RodAegisAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
