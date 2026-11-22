package mod.advancedmobs.ability.ghast;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * GhastAbilities: the Ghast's provider for the new ability
 * system. Supplies the ghast's 5 designed abilities
 * (ABILITY-IDEAS.md, Ghast): Wailing Shockwave, Colossal
 * Grief, Mirror Ghasts, Nether Rift Maw, Ember Wake Descent.
 * The instances are shared singletons; all per-activation
 * state lives in AbilityContext.state(), never in the ability
 * objects.
 */
public final class GhastAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new WailingShockwaveAbility(),
        new ColossalGriefAbility(),
        new MirrorGhastsAbility(),
        new NetherRiftMawAbility(),
        new EmberWakeDescentAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
