package mod.advancedmobs.ability.phantom;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * PhantomAbilities: the Phantom's provider for the new
 * ability system. Supplies the phantom's 5 designed
 * abilities (ABILITY-IDEAS.md, Phantom): Nightmare Dive,
 * Insomnia Aura, Moonlit Mend, Split Image, Veil of the
 * Night. The instances are shared singletons; all
 * per-activation state lives in AbilityContext.state(),
 * never in the ability objects.
 */
public final class PhantomAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new NightmareDiveAbility(),
        new InsomniaAuraAbility(),
        new MoonlitMendAbility(),
        new SplitImageAbility(),
        new VeilOfTheNightAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
