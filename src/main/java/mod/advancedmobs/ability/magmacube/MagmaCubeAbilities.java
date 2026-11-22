package mod.advancedmobs.ability.magmacube;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * MagmaCubeAbilities: the Magma Cube's provider for the new
 * ability system. Supplies the magma cube's 5 designed
 * abilities (ABILITY-IDEAS.md, Magma Cube): Magma Mortar,
 * Lava Bloom, Obsidian Crust, Eruption Leap, Volcano Core.
 * The instances are shared singletons; all per-activation
 * state lives in AbilityContext.state(), never in the ability
 * objects.
 */
public final class MagmaCubeAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new MagmaMortarAbility(),
        new LavaBloomAbility(),
        new ObsidianCrustAbility(),
        new EruptionLeapAbility(),
        new VolcanoCoreAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
