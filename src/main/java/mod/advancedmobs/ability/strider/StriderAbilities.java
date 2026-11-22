package mod.advancedmobs.ability.strider;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * StriderAbilities: the Strider's provider for the new
 * ability system. Supplies the strider's 5 designed
 * abilities (ABILITY-IDEAS.md, Strider): Lava Wake, Warped
 * Frenzy, Magma Spit, Obsidian Skin, Strider Stack. The
 * instances are shared singletons; all per-activation state
 * lives in AbilityContext.state(), never in the ability
 * objects.
 */
public final class StriderAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new LavaWakeAbility(),
        new WarpedFrenzyAbility(),
        new MagmaSpitAbility(),
        new ObsidianSkinAbility(),
        new StriderStackAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
