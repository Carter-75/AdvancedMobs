package mod.advancedmobs.ability.sulfurcube;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * SulfurCubeAbilities: the Sulfur Cube's provider for the new
 * ability system. Supplies the sulfur cube's 5 designed
 * abilities (ABILITY-IDEAS.md, Sulfur Cube): Geyser Core, Hot
 * Archetype, Frictionless Slide, Super Bounce Slam, Noxious
 * Split. The instances are shared singletons; all
 * per-activation state lives in AbilityContext.state(), never
 * in the ability objects.
 */
public final class SulfurCubeAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new GeyserCoreAbility(),
        new HotArchetypeAbility(),
        new FrictionlessSlideAbility(),
        new SuperBounceSlamAbility(),
        new NoxiousSplitAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
