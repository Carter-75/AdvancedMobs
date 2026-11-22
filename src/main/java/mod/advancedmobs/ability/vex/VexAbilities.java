package mod.advancedmobs.ability.vex;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * VexAbilities: the Vex's provider for the new ability
 * system. Supplies the vex's 5 designed abilities
 * (ABILITY-IDEAS.md, Vex): Mirage Split, Wallphase Ambush,
 * Ironbound Duelist, Soul Tether, Screech Divebomb. The
 * instances are shared singletons; all per-activation state
 * lives in AbilityContext.state(), never in the ability
 * objects.
 */
public final class VexAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new MirageSplitAbility(),
        new WallphaseAmbushAbility(),
        new IronboundDuelistAbility(),
        new SoulTetherAbility(),
        new ScreechDivebombAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
