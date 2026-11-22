package mod.advancedmobs.ability.llama;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * LlamaAbilities: the Llama's provider for the new ability
 * system. Supplies the llama's 5 designed abilities
 * (ABILITY-IDEAS.md, Llama): Spit Volley, War Carpet,
 * Caravan Call, Mule Kick, Mountain Stride. The instances
 * are shared singletons; all per-activation state lives in
 * AbilityContext.state(), never in the ability objects.
 */
public final class LlamaAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new SpitVolleyAbility(),
        new WarCarpetAbility(),
        new CaravanCallAbility(),
        new MuleKickAbility(),
        new MountainStrideAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
