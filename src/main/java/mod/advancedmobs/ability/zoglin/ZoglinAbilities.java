package mod.advancedmobs.ability.zoglin;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * ZoglinAbilities: the Zoglin's provider for the new ability
 * system. Supplies the zoglin's 5 designed abilities
 * (ABILITY-IDEAS.md, Zoglin): Rotten Rampage, Undying Bulk,
 * Plague Breath, Frenzy Gore, Carrion Call. The instances
 * are shared singletons; all per-activation state lives in
 * AbilityContext.state(), never in the ability objects.
 */
public final class ZoglinAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new RottenRampageAbility(),
        new UndyingBulkAbility(),
        new PlagueBreathAbility(),
        new FrenzyGoreAbility(),
        new CarrionCallAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
