package mod.advancedmobs.ability.salmon;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * SalmonAbilities: the Salmon's provider for the new
 * ability system. Supplies the salmon's 5 designed
 * abilities (ABILITY-IDEAS.md, Salmon): Upstream Leap,
 * Rapids Run, Scale Shed Snare, Red Run, Waterfall
 * Barrage. The instances are shared singletons; all
 * per-activation state lives in AbilityContext.state(),
 * never in the ability objects.
 *
 * SALMON FACTS (verified against the real 26.2 jar):
 * the Salmon class sits in net.minecraft.world.entity.
 * animal.fish, extends AbstractSchoolingFish, and its
 * attribute supplier comes from AbstractFish.
 * createAttributes, which adds only MAX_HEALTH fixed
 * at 3.0 on top of Mob.createMobAttributes (which
 * itself adds only FOLLOW_RANGE 16.0 on top of the
 * living base). There is NO ATTACK_DAMAGE attribute
 * instance anywhere in the salmon's chain, so every
 * damage number in this package is a flat code value
 * dealt through a mob attack source credited to the
 * dealer, the treatment the Cow, Sheep, Chicken,
 * Tadpole, and Cod packages use for their attack-less
 * mobs. The salmon's sounds are plain SoundEvent
 * constants in 26.2 (SALMON_AMBIENT, SALMON_HURT,
 * SALMON_FLOP, SALMON_DEATH, FISH_SWIM). The salmon
 * has body size variants (Salmon.Variant SMALL,
 * MEDIUM, LARGE), but setVariant is private in 26.2
 * and only changes the bounding box class, not the
 * texture; Red Run's comment carries the consequence
 * for its spawning livery. Like the cod, the salmon
 * is fully aquatic: it swims and flops on land, so
 * most abilities in this package gate on the caster
 * being in water; Upstream Leap is the exception by
 * design, since leaving the water is its whole point.
 */
public final class SalmonAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new UpstreamLeapAbility(),
        new RapidsRunAbility(),
        new ScaleShedSnareAbility(),
        new RedRunAbility(),
        new WaterfallBarrageAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
