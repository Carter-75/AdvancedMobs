package mod.advancedmobs.ability.camel;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * CamelAbilities: the Camel's provider for the new ability
 * system. Supplies the camel's 5 designed abilities
 * (ABILITY-IDEAS.md, Camel): Desert Mirage, Two Hump
 * Fortress, Spit Volley, Oasis Blessing, Sandstorm Stride.
 * The instances are shared singletons; all per-activation
 * state lives in AbilityContext.state(), never in the
 * ability objects.
 *
 * CAMEL FACTS (verified against the real 26.2 jar): the
 * Camel class sits in net.minecraft.world.entity.animal.
 * camel, extends AbstractHorse, and its createAttributes
 * builds on the base horse chain with MAX_HEALTH fixed at
 * 32.0, MOVEMENT_SPEED 0.09, JUMP_STRENGTH 0.42, and
 * STEP_HEIGHT 1.5. Like the rest of the horse chain it has
 * NO ATTACK_DAMAGE attribute instance, so every damage
 * number in this package is a flat code value dealt through
 * a damage source credited to the camel. The camel has a
 * real vanilla kneel: sitDown() and standUp() drive its
 * sitting pose, and CAMEL_AMBIENT, CAMEL_SIT, CAMEL_STAND,
 * and CAMEL_STEP_SAND are plain SoundEvents.
 */
public final class CamelAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new DesertMirageAbility(),
        new TwoHumpFortressAbility(),
        new SpitVolleyAbility(),
        new OasisBlessingAbility(),
        new SandstormStrideAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
