package mod.advancedmobs.ability.tadpole;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * TadpoleAbilities: the Tadpole's provider for the new
 * ability system. Supplies the tadpole's 5 designed
 * abilities (ABILITY-IDEAS.md, Tadpole): Schooling Surge,
 * Bubble Veil, Metamorphosis, Current Rider, Algae Bloom.
 * The instances are shared singletons; all per-activation
 * state lives in AbilityContext.state(), never in the
 * ability objects.
 *
 * TADPOLE FACTS (verified against the real 26.2 jar): the
 * Tadpole class sits in net.minecraft.world.entity.
 * animal.frog, extends AbstractFish, and its
 * createAttributes builds on the animal base with
 * MOVEMENT_SPEED 1.0 and MAX_HEALTH fixed at 6.0, and
 * nothing else: there is NO ATTACK_DAMAGE attribute
 * instance anywhere in the tadpole's chain (the animal
 * base carries none; see the Horse package's jar
 * verification), so every damage number in this package
 * is a flat code value dealt through a mob attack source
 * credited to the dealer, the treatment the Cow, Sheep,
 * and Chicken packages use for their attack-less mobs.
 * The tadpole's sounds are plain SoundEvent constants in
 * 26.2 (FISH_SWIM, TADPOLE_FLOP, TADPOLE_GROW_UP,
 * TADPOLE_HURT, TADPOLE_DEATH). The tadpole is a Brain
 * mob in vanilla, like the frog; that changes nothing
 * here, because abilities tick from the AI step the same
 * way for every mob (see the MobAbility contract). The
 * tadpole is fully aquatic: it swims, flops on land, and
 * suffocates in air, so the package's movement and
 * terrain abilities assume water and two of them gate on
 * the caster being in water.
 */
public final class TadpoleAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new SchoolingSurgeAbility(),
        new BubbleVeilAbility(),
        new MetamorphosisAbility(),
        new CurrentRiderAbility(),
        new AlgaeBloomAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
