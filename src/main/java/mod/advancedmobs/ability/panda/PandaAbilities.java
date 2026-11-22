package mod.advancedmobs.ability.panda;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * PandaAbilities: the Panda's provider for the new ability
 * system. Supplies the panda's 5 designed abilities
 * (ABILITY-IDEAS.md, Panda): Rolling Boulder, Bamboo Feast,
 * Sneeze Bomb, Drowsy Aura, Kung Fu Form. The instances are
 * shared singletons; all per-activation state lives in
 * AbilityContext.state(), never in the ability objects.
 */
public final class PandaAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new RollingBoulderAbility(),
        new BambooFeastAbility(),
        new SneezeBombAbility(),
        new DrowsyAuraAbility(),
        new KungFuFormAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
