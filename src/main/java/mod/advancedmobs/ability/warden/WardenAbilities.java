package mod.advancedmobs.ability.warden;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * WardenAbilities: the Warden's provider for the new
 * ability system. Supplies the warden's 5 designed
 * abilities (ABILITY-IDEAS.md, Warden): Cataclysm Boom,
 * Sculk Bloom, Heart of the Deep, Shrieking Brood, Burrow
 * Ambush. The instances are shared singletons; all
 * per-activation state lives in AbilityContext.state(),
 * never in the ability objects.
 */
public final class WardenAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new CataclysmBoomAbility(),
        new SculkBloomAbility(),
        new HeartOfTheDeepAbility(),
        new ShriekingBroodAbility(),
        new BurrowAmbushAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
