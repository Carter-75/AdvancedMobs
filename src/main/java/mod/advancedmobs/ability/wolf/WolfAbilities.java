package mod.advancedmobs.ability.wolf;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * WolfAbilities: the Wolf's provider for the new ability
 * system. Supplies the wolf's 5 designed abilities
 * (ABILITY-IDEAS.md, Wolf): Pack Howl, Hamstring Bite,
 * Alpha Form, Pounce, Guardian Stance. The instances are
 * shared singletons; all per-activation state lives in
 * AbilityContext.state(), never in the ability objects.
 */
public final class WolfAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new PackHowlAbility(),
        new HamstringBiteAbility(),
        new AlphaFormAbility(),
        new PounceAbility(),
        new GuardianStanceAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
