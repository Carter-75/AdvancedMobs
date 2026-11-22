package mod.advancedmobs.ability.zombie;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * ZombieAbilities: the Zombie's provider for the new ability system.
 * Supplies the zombie's 5 designed abilities (ABILITY-IDEAS.md, Zombie):
 * Grave Colossus, Gravemud Mire, Seismic Slam, Rot Aura, Grave Eruption.
 * The instances are shared singletons; all per-activation state lives in
 * AbilityContext.state(), never in the ability objects.
 */
public final class ZombieAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new GraveColossusAbility(),
        new GravemudMireAbility(),
        new SeismicSlamAbility(),
        new RotAuraAbility(),
        new GraveEruptionAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
