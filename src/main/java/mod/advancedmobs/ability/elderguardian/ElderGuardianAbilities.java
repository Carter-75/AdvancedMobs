package mod.advancedmobs.ability.elderguardian;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * ElderGuardianAbilities: the Elder Guardian's provider for the
 * new ability system. Supplies the elder guardian's 5 designed
 * abilities (ABILITY-IDEAS.md, Elder Guardian): Curse of the
 * Sunken Court, Tempest Beam Array, Monument Rising, Sponge
 * Pulse, Turret Brood. The instances are shared singletons; all
 * per-activation state lives in AbilityContext.state(), never in
 * the ability objects.
 */
public final class ElderGuardianAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new CurseOfTheSunkenCourtAbility(),
        new TempestBeamArrayAbility(),
        new MonumentRisingAbility(),
        new SpongePulseAbility(),
        new TurretBroodAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
