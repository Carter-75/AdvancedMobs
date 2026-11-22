package mod.advancedmobs.ability.pufferfish;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * PufferfishAbilities: the Pufferfish's provider for
 * the new ability system. Supplies the pufferfish's 5
 * designed abilities (ABILITY-IDEAS.md, Pufferfish):
 * Full Inflation, Tetrodotoxin Mist, Spine Volley,
 * Rolling Mine, Deflation Dash. The instances are
 * shared singletons; all per-activation state lives
 * in AbilityContext.state(), never in the ability
 * objects.
 *
 * PUFFERFISH FACTS (verified against the real 26.2
 * jar): the Pufferfish class sits in
 * net.minecraft.world.entity.animal.fish, extends
 * AbstractFish, and declares no attribute supplier of
 * its own; it inherits AbstractFish.createAttributes,
 * which adds only MAX_HEALTH fixed at 3.0 on top of
 * Mob.createMobAttributes (which itself adds only
 * FOLLOW_RANGE on top of the living base). There is
 * NO ATTACK_DAMAGE attribute instance anywhere in the
 * pufferfish's chain, so every damage number in this
 * package is a flat code value dealt through a typed
 * source credited to the dealer (the vanilla sting
 * source for spike and spine contact, a mob attack
 * source elsewhere), the treatment the Cow, Sheep,
 * Chicken, Cod, and Tadpole packages use for their
 * attack-less mobs. The pufferfish's inflation is a
 * real synced data value: Pufferfish.setPuffState
 * takes STATE_SMALL (0), STATE_MID (1), or STATE_FULL
 * (2), the render and the bounding box follow it, and
 * the vanilla puff goal keeps managing the state
 * outside activations, so abilities that force a
 * state record the state they found in start() and
 * restore it in end() (Rolling Mine deflates to
 * small instead, its design's safe deflation). The
 * pufferfish's sounds are plain SoundEvent constants
 * in 26.2 (PUFFER_FISH_BLOW_UP, PUFFER_FISH_BLOW_OUT,
 * PUFFER_FISH_STING, PUFFER_FISH_FLOP, PUFFER_FISH_
 * HURT, FISH_SWIM). The pufferfish is fully aquatic:
 * it swims and flops on land, so every ability in
 * this package gates on the caster being in water.
 */
public final class PufferfishAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new FullInflationAbility(),
        new TetrodotoxinMistAbility(),
        new SpineVolleyAbility(),
        new RollingMineAbility(),
        new DeflationDashAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
