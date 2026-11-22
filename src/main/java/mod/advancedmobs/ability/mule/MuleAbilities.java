package mod.advancedmobs.ability.mule;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * MuleAbilities: the Mule's provider for the new ability
 * system. Supplies the mule's 5 designed abilities
 * (ABILITY-IDEAS.md, Mule): Hybrid Vigor, Surefoot Slam,
 * Satchel Bombardier, Iron Mule Endurance, Dust Devil Dash.
 * The instances are shared singletons; all per-activation
 * state lives in AbilityContext.state(), never in the
 * ability objects.
 *
 * A note on the mule's numbers, verified against the real
 * 26.2 jar: the Mule class declares no attribute supplier
 * of its own; it inherits the AbstractHorse chain through
 * AbstractChestedHorse (the same chain the Horse and Donkey
 * packages verified): MAX_HEALTH randomized 15 to 30 per
 * animal, MOVEMENT_SPEED base 0.225 randomized 0.1125 to
 * 0.3375, JUMP_STRENGTH base 0.7 randomized 0.4 to 1.0,
 * STEP_HEIGHT 1.0, SAFE_FALL_DISTANCE 6.0, and
 * FALL_DAMAGE_MULTIPLIER 0.5, but NO ATTACK_DAMAGE instance
 * anywhere in the chain. Every damage number in this
 * package is therefore a flat code value dealt through a
 * damage source credited to the mule (the Horse, Donkey,
 * Cow, Sheep, and Chicken treatment).
 */
public final class MuleAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new HybridVigorAbility(),
        new SurefootSlamAbility(),
        new SatchelBombardierAbility(),
        new IronMuleEnduranceAbility(),
        new DustDevilDashAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
