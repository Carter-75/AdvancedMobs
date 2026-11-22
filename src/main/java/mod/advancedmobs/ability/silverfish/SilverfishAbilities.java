package mod.advancedmobs.ability.silverfish;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * SilverfishAbilities: the Silverfish's provider for the new
 * ability system. Supplies the silverfish's 5 designed
 * abilities (ABILITY-IDEAS.md, Silverfish): Stoneborer Ambush,
 * Infested Network, Rubble Form, Tunnel Collapse, Hive Mind
 * Link. The instances are shared singletons; all
 * per-activation state lives in AbilityContext.state(), never
 * in the ability objects.
 */
public final class SilverfishAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new StoneborerAmbushAbility(),
        new InfestedNetworkAbility(),
        new RubbleFormAbility(),
        new TunnelCollapseAbility(),
        new HiveMindLinkAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
