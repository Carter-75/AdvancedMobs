package mod.advancedmobs.ability.horse;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * HorseAbilities: the Horse's provider for the new ability
 * system. Supplies the horse's 5 designed abilities
 * (ABILITY-IDEAS.md, Horse): Warhorse Charge, Ironshod
 * Bulwark, Sky Gallop, Herd Sovereign, Nightmare Steed.
 * The instances are shared singletons; all per-activation
 * state lives in AbilityContext.state(), never in the
 * ability objects.
 *
 * A note on the horse's numbers, verified against the real
 * 26.2 jar: a horse's attribute supplier carries MAX_HEALTH
 * (randomized 15 to 30 per horse), MOVEMENT_SPEED (base
 * 0.225, randomized 0.1125 to 0.3375), JUMP_STRENGTH (base
 * 0.7, randomized 0.4 to 1.0), STEP_HEIGHT 1.0,
 * SAFE_FALL_DISTANCE 6.0, and FALL_DAMAGE_MULTIPLIER 0.5,
 * but NO ATTACK_DAMAGE instance anywhere in its chain
 * (LivingEntity's base set lacks it and Mob.createMobAttributes
 * adds only FOLLOW_RANGE). Every damage number in this
 * package is therefore a flat code value dealt through a
 * mob attack source credited to the horse (the Cow, Sheep,
 * and Chicken treatment).
 */
public final class HorseAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new WarhorseChargeAbility(),
        new IronshodBulwarkAbility(),
        new SkyGallopAbility(),
        new HerdSovereignAbility(),
        new NightmareSteedAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
