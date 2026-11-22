package mod.advancedmobs.ability.goat;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * GoatAbilities: the Goat's provider for the new ability
 * system. Supplies the goat's 5 designed abilities
 * (ABILITY-IDEAS.md, Goat): Ram Catapult, Screaming Aura,
 * Cliff Master, Horn Shatter, Stubborn Stance. The
 * instances are shared singletons; all per-activation
 * state lives in AbilityContext.state(), never in the
 * ability objects.
 */
public final class GoatAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new RamCatapultAbility(),
        new ScreamingAuraAbility(),
        new CliffMasterAbility(),
        new HornShatterAbility(),
        new StubbornStanceAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
