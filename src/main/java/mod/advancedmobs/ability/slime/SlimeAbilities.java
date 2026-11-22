package mod.advancedmobs.ability.slime;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * SlimeAbilities: the Slime's provider for the new ability
 * system. Supplies the slime's 5 designed abilities
 * (ABILITY-IDEAS.md, Slime): Meteor Slam, Royal Jelly,
 * Gelatinous Split, Superball Mode, Slime Slick. The
 * instances are shared singletons; all per-activation state
 * lives in AbilityContext.state(), never in the ability
 * objects.
 */
public final class SlimeAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new MeteorSlamAbility(),
        new RoyalJellyAbility(),
        new GelatinousSplitAbility(),
        new SuperballModeAbility(),
        new SlimeSlickAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
