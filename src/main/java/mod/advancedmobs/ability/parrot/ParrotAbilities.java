package mod.advancedmobs.ability.parrot;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * ParrotAbilities: the Parrot's provider for the new
 * ability system. Supplies the parrot's 5 designed
 * abilities (ABILITY-IDEAS.md, Parrot): Mimic Shriek,
 * Feather Cyclone, Flock Raid, Rainbow Plumage, Death
 * Squawk Revenge. The instances are shared singletons; all
 * per-activation state lives in AbilityContext.state(),
 * never in the ability objects.
 *
 * PARROT SHEET (verified against the real 26.2 jar,
 * Parrot.createAttributes): MAX_HEALTH 6.0, FLYING_SPEED
 * 0.4, MOVEMENT_SPEED 0.2, ATTACK_DAMAGE 3.0. The parrot
 * HAS an attack damage instance, so strikes in this
 * package read the live attribute value (upgrades and
 * effects ride it) instead of flat constants.
 */
public final class ParrotAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new MimicShriekAbility(),
        new FeatherCycloneAbility(),
        new FlockRaidAbility(),
        new RainbowPlumageAbility(),
        new DeathSquawkRevengeAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
