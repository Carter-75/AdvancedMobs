package mod.advancedmobs.ability.mooshroom;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * MooshroomAbilities: the Mooshroom's provider for the new
 * ability system. Supplies the mooshroom's 5 designed
 * abilities (ABILITY-IDEAS.md, Mooshroom): Spore Burst,
 * Fungal Plate Armor, Stew of Second Chances, Mycelium
 * Creep, Sporeling Swarm. The instances are shared
 * singletons; all per-activation state lives in
 * AbilityContext.state(), never in the ability objects.
 */
public final class MooshroomAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new SporeBurstAbility(),
        new FungalPlateArmorAbility(),
        new StewOfSecondChancesAbility(),
        new MyceliumCreepAbility(),
        new SporelingSwarmAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
