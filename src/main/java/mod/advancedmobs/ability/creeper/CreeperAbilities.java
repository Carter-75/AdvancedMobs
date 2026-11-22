package mod.advancedmobs.ability.creeper;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * CreeperAbilities: the Creeper's provider for the new ability system.
 * Supplies the creeper's 5 designed abilities (ABILITY-IDEAS.md,
 * Creeper): Cluster Detonation, Fuse Rocket, Camouflage Bloom,
 * Gunpowder Sprites, Charged Stormform. The instances are shared
 * singletons; all per-activation state lives in
 * AbilityContext.state(), never in the ability objects.
 */
public final class CreeperAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new ClusterDetonationAbility(),
        new FuseRocketAbility(),
        new CamouflageBloomAbility(),
        new GunpowderSpritesAbility(),
        new ChargedStormformAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
