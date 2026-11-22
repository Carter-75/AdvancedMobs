package mod.advancedmobs.ability.pig;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * PigAbilities: the Pig's provider for the new ability
 * system. Supplies the pig's 5 designed abilities
 * (ABILITY-IDEAS.md, Pig): Mudslide Charge, Truffle Tremor,
 * Stormstruck Revenant, Mud Bath Bulwark, Snort Shockwave.
 * The instances are shared singletons; all per-activation
 * state lives in AbilityContext.state(), never in the
 * ability objects.
 */
public final class PigAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new MudslideChargeAbility(),
        new TruffleTremorAbility(),
        new StormstruckRevenantAbility(),
        new MudBathBulwarkAbility(),
        new SnortShockwaveAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
