package mod.advancedmobs.ability.shulker;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * ShulkerAbilities: the Shulker's provider for the new
 * ability system. Supplies the shulker's 5 designed
 * abilities (ABILITY-IDEAS.md, Shulker): Petal Storm Spiral,
 * Endstone Bunker, Gravity Well Oscillation, Chorus Split,
 * Skybox Artillery Perch. The instances are shared
 * singletons; all per-activation state lives in
 * AbilityContext.state(), never in the ability objects.
 */
public final class ShulkerAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new PetalStormSpiralAbility(),
        new EndstoneBunkerAbility(),
        new GravityWellOscillationAbility(),
        new ChorusSplitAbility(),
        new SkyboxArtilleryPerchAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
