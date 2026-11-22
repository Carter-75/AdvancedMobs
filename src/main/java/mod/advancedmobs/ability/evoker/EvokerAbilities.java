package mod.advancedmobs.ability.evoker;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * EvokerAbilities: the Evoker's provider for the new ability
 * system. Supplies the evoker's 5 designed abilities
 * (ABILITY-IDEAS.md, Evoker): Fang Colosseum, Totem Tyrant,
 * Delayed Hex Fang, Crowned Vex Vanguard, Mansion Mirage
 * Decoys. The instances are shared singletons; all
 * per-activation state lives in AbilityContext.state(),
 * never in the ability objects.
 */
public final class EvokerAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new FangColosseumAbility(),
        new TotemTyrantAbility(),
        new DelayedHexFangAbility(),
        new CrownedVexVanguardAbility(),
        new MansionMirageDecoysAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
