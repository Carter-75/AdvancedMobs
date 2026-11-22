package mod.advancedmobs.ability.traderllama;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * TraderLlamaAbilities: the Trader Llama's provider for the
 * new ability system. Supplies the trader llama's 5 designed
 * abilities (ABILITY-IDEAS.md, Trader Llama): Caravan Guard,
 * Emerald Spit, Wanderer's Retreat, Pack Mule Form, Hard
 * Bargain Aura. The instances are shared singletons; all
 * per-activation state lives in AbilityContext.state(),
 * never in the ability objects.
 */
public final class TraderLlamaAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new CaravanGuardAbility(),
        new EmeraldSpitAbility(),
        new WanderersRetreatAbility(),
        new PackMuleFormAbility(),
        new HardBargainAuraAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
