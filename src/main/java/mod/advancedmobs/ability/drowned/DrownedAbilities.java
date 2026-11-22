package mod.advancedmobs.ability.drowned;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * DrownedAbilities: the Drowned's provider for the new ability system.
 * Supplies the drowned's 5 designed abilities (ABILITY-IDEAS.md,
 * Drowned): Trident Cyclone, Rising Tide, Call of the Deep, Kelp
 * Snare, Leviathan Form. The instances are shared singletons; all
 * per-activation state lives in AbilityContext.state(), never in the
 * ability objects.
 */
public final class DrownedAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new TridentCycloneAbility(),
        new RisingTideAbility(),
        new CallOfTheDeepAbility(),
        new KelpSnareAbility(),
        new LeviathanFormAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
