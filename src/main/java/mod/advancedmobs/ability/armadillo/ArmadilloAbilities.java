package mod.advancedmobs.ability.armadillo;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * ArmadilloAbilities: the Armadillo's provider for the new
 * ability system. Supplies the armadillo's 5 designed
 * abilities (ABILITY-IDEAS.md, Armadillo): Rollout, Scute
 * Shield, Burrow Blitz, Bug Buffet, Armored Convoy. The
 * instances are shared singletons; all per-activation state
 * lives in AbilityContext.state(), never in the ability
 * objects.
 *
 * ARMADILLO FACTS (verified against the real 26.2 jar): the
 * Armadillo class sits in net.minecraft.world.entity.
 * animal.armadillo, extends Animal, and its createAttributes
 * builds on the Animal chain with MAX_HEALTH fixed at 12.0
 * and MOVEMENT_SPEED at 0.14. The chain carries NO
 * ATTACK_DAMAGE attribute instance (the base living set
 * lacks it and Mob.createMobAttributes adds only
 * FOLLOW_RANGE), so every damage number in this package is
 * a flat code value dealt through a damage source credited
 * to the armadillo. The ball form is vanilla: a synced
 * Armadillo.ArmadilloState (IDLE, ROLLING, SCARED,
 * UNROLLING) switched through switchToState, with rollUp()
 * and rollOut() as the vanilla shortcuts. The scute is a
 * real item, Items.ARMADILLO_SCUTE. The armadillo's sounds
 * are plain SoundEvents: ARMADILLO_AMBIENT, ARMADILLO_EAT,
 * ARMADILLO_HURT, ARMADILLO_HURT_REDUCED, ARMADILLO_ROLL,
 * ARMADILLO_LAND, ARMADILLO_UNROLL_FINISH, and
 * ARMADILLO_SCUTE_DROP.
 */
public final class ArmadilloAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new RolloutAbility(),
        new ScuteShieldAbility(),
        new BurrowBlitzAbility(),
        new BugBuffetAbility(),
        new ArmoredConvoyAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
