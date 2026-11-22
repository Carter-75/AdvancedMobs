package mod.advancedmobs.ability.squid;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * SquidAbilities: the Squid's provider for the new
 * ability system. Supplies the squid's 5 designed
 * abilities (ABILITY-IDEAS.md, Squid): Ink Nova,
 * Tentacle Slam, Jet Siphon, Ink Clone Decoy, Abyssal
 * Drag. The instances are shared singletons; all
 * per-activation state lives in
 * AbilityContext.state(), never in the ability
 * objects.
 *
 * SQUID FACTS (verified against the real 26.2 jar):
 * the Squid class sits in
 * net.minecraft.world.entity.animal.squid, extends
 * AgeableWaterCreature, and its attribute supplier
 * adds only MAX_HEALTH fixed at 10.0 on top of
 * Mob.createMobAttributes (which itself adds only
 * FOLLOW_RANGE on top of the living base). There is
 * NO ATTACK_DAMAGE attribute instance anywhere in
 * the squid's chain, so every damage number in this
 * package is a flat code value dealt through a mob
 * attack source credited to the dealer, the
 * treatment the Cow, Sheep, Chicken, Cod, and
 * Tadpole packages use for their attack-less mobs;
 * the two design lines that name attack_damage and
 * attack_knockback modifiers (Tentacle Slam) are
 * still applied through the package's null-tolerant
 * helper and documented as inert, the Grand Hex Form
 * precedent. The squid's sounds are plain SoundEvent
 * constants in 26.2 (SQUID_AMBIENT, SQUID_HURT,
 * SQUID_DEATH, SQUID_SQUIRT). A hurt vanilla squid
 * already sprays SQUID_INK particles and squirts;
 * this package's ink effects use that same particle
 * and sound, just weaponized. The squid's vanilla
 * swim is a slow wander with no combat navigation,
 * so every movement in this package is per-tick
 * velocity steering (see the support class). The
 * squid is fully aquatic: it swims and flops on
 * land, so every ability in this package gates on
 * the caster being in water.
 */
public final class SquidAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new InkNovaAbility(),
        new TentacleSlamAbility(),
        new JetSiphonAbility(),
        new InkCloneDecoyAbility(),
        new AbyssalDragAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
