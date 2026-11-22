package mod.advancedmobs.ability.dolphin;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * DolphinAbilities: the Dolphin's provider for the new ability
 * system. Supplies the dolphin's 5 designed abilities
 * (ABILITY-IDEAS.md, Dolphin): Torpedo Ram, Echolocation
 * Pulse, Rescue Current, Aerial Slam, Pod Call. The instances
 * are shared singletons; all per-activation state lives in
 * AbilityContext.state(), never in the ability objects.
 */
public final class DolphinAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new TorpedoRamAbility(),
        new EcholocationPulseAbility(),
        new RescueCurrentAbility(),
        new AerialSlamAbility(),
        new PodCallAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
