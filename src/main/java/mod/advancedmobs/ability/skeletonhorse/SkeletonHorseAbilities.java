package mod.advancedmobs.ability.skeletonhorse;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * SkeletonHorseAbilities: the Skeleton Horse's provider for
 * the new ability system. Supplies the skeleton horse's 5
 * designed abilities (ABILITY-IDEAS.md, Skeleton Horse):
 * Gravecaller Host, Soulfire Hooves, Deathless Frame,
 * Thunder Herald, Bonechill Aura. The instances are shared
 * singletons; all per-activation state lives in
 * AbilityContext.state(), never in the ability objects.
 *
 * A note on the skeleton horse's numbers, verified against
 * the real 26.2 jar: SkeletonHorse.createAttributes builds
 * on AbstractHorse.createBaseHorseAttributes (JUMP_STRENGTH
 * base 0.7 randomized 0.4 to 1.0 by randomizeAttributes,
 * STEP_HEIGHT 1.0, SAFE_FALL_DISTANCE 6.0,
 * FALL_DAMAGE_MULTIPLIER 0.5) and fixes MAX_HEALTH at 15.0
 * and MOVEMENT_SPEED at 0.2. There is NO ATTACK_DAMAGE
 * instance anywhere in the chain (LivingEntity's base set
 * lacks it and Mob.createMobAttributes adds only
 * FOLLOW_RANGE), so every damage number in this package is
 * a flat code value dealt through a mob attack source
 * credited to the caster (the Horse treatment).
 *
 * A note on undeath: the skeleton horse is undead
 * (MobType.UNDEAD, inverted heal and harm). INSTANT_HEALTH
 * would HURT it and INSTANT_DAMAGE would heal it, so
 * Deathless Frame's healing surge is a direct heal() call
 * (raw health, no inversion) instead of the instant_health
 * effect the design's Uses line names; the absorption and
 * resistance effects it also names are not heal effects
 * and work on the undead normally.
 */
public final class SkeletonHorseAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new GravecallerHostAbility(),
        new SoulfireHoovesAbility(),
        new DeathlessFrameAbility(),
        new ThunderHeraldAbility(),
        new BonechillAuraAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
