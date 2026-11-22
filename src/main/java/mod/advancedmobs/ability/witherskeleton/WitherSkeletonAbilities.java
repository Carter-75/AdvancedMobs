package mod.advancedmobs.ability.witherskeleton;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * WitherSkeletonAbilities: the Wither Skeleton's provider for
 * the new ability system. Supplies the wither skeleton's 5
 * designed abilities (ABILITY-IDEAS.md, Wither Skeleton):
 * Wither Brand, Obsidian Bones, Fallen Captain, Ashen Advance,
 * Wither Herald. The instances are shared singletons; all
 * per-activation state lives in AbilityContext.state(), never
 * in the ability objects.
 */
public final class WitherSkeletonAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new WitherBrandAbility(),
        new ObsidianBonesAbility(),
        new FallenCaptainAbility(),
        new AshenAdvanceAbility(),
        new WitherHeraldAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
