package mod.advancedmobs.ability.wither;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * WitherAbilities: the Wither's provider for the new ability
 * system. Supplies the wither's 5 designed abilities
 * (ABILITY-IDEAS.md, Wither): Cataclysm Skullstorm, Soul
 * Harvest Bulwark, Graveyard Bloom, Death Nova, Revenant
 * Champions. The instances are shared singletons; all
 * per-activation state lives in AbilityContext.state(), never
 * in the ability objects.
 */
public final class WitherAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new CataclysmSkullstormAbility(),
        new SoulHarvestBulwarkAbility(),
        new GraveyardBloomAbility(),
        new DeathNovaAbility(),
        new RevenantChampionsAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
