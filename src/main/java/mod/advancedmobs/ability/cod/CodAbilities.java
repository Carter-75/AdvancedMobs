package mod.advancedmobs.ability.cod;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * CodAbilities: the Cod's provider for the new ability
 * system. Supplies the cod's 5 designed abilities
 * (ABILITY-IDEAS.md, Cod): Bait Ball Barrage, Slipstream
 * Snatch, Downcurrent Net, Silver Flash School, Frenzy
 * Current Aura. The instances are shared singletons; all
 * per-activation state lives in AbilityContext.state(),
 * never in the ability objects.
 *
 * COD FACTS (verified against the real 26.2 jar): the
 * Cod class sits in net.minecraft.world.entity.animal.
 * fish, extends AbstractSchoolingFish, and its attribute
 * supplier comes from AbstractFish.createAttributes,
 * which adds only MAX_HEALTH fixed at 3.0 on top of
 * Mob.createMobAttributes (which itself adds only
 * FOLLOW_RANGE 16.0 on top of the living base). There
 * is NO ATTACK_DAMAGE attribute instance anywhere in
 * the cod's chain, so every damage number in this
 * package is a flat code value dealt through a mob
 * attack source credited to the dealer, the treatment
 * the Cow, Sheep, Chicken, and Tadpole packages use for
 * their attack-less mobs. The cod's sounds are plain
 * SoundEvent constants in 26.2 (COD_AMBIENT, COD_HURT,
 * COD_FLOP, COD_DEATH, FISH_SWIM). One summon note:
 * AbstractSchoolingFish.finalizeSpawn with null group
 * data only marks the new fish as the leader of an
 * empty school and spawns nothing extra (verified in
 * the jar bytecode), so the package's temporary cod
 * summons call it exactly like every other summon in
 * this mod and get exactly the fish they created. The
 * cod is fully aquatic: it swims and flops on land, so
 * every ability in this package gates on the caster
 * being in water.
 */
public final class CodAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new BaitBallBarrageAbility(),
        new SlipstreamSnatchAbility(),
        new DowncurrentNetAbility(),
        new SilverFlashSchoolAbility(),
        new FrenzyCurrentAuraAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
