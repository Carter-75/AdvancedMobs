package mod.advancedmobs.ability.breeze;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * BreezeAbilities: the Breeze's provider for the new
 * ability system. Supplies the breeze's 5 designed
 * abilities (ABILITY-IDEAS.md, Breeze): Wind Lance Volley,
 * Cyclone Form, Crosswind Arena, Gust Step, Wind Wall.
 * The instances are shared singletons; all per-activation
 * state lives in AbilityContext.state(), never in the
 * ability objects.
 */
public final class BreezeAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new WindLanceVolleyAbility(),
        new CycloneFormAbility(),
        new CrosswindArenaAbility(),
        new GustStepAbility(),
        new WindWallAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
