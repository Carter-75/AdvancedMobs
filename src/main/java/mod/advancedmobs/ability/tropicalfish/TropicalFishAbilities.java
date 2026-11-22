package mod.advancedmobs.ability.tropicalfish;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * TropicalFishAbilities: the Tropical Fish's provider
 * for the new ability system. Supplies the tropical
 * fish's 5 designed abilities (ABILITY-IDEAS.md,
 * Tropical Fish): Kaleidoscope Flash, Coral Crown
 * Garden, Anemone Guard, Mirage School, Prism Scale
 * Aura. The instances are shared singletons; all
 * per-activation state lives in
 * AbilityContext.state(), never in the ability
 * objects.
 *
 * TROPICAL FISH FACTS (verified against the real
 * 26.2 jar): the TropicalFish class sits in
 * net.minecraft.world.entity.animal.fish, extends
 * AbstractSchoolingFish, and its attribute supplier
 * comes from AbstractFish.createAttributes, which
 * adds only MAX_HEALTH fixed at 3.0 on top of
 * Mob.createMobAttributes (which itself adds only
 * FOLLOW_RANGE 16.0 on top of the living base).
 * There is NO ATTACK_DAMAGE attribute instance
 * anywhere in the tropical fish's chain, so every
 * damage number in this package is a flat code value
 * dealt through a damage source credited to the
 * dealer, the treatment the Cod, Cow, Sheep, and
 * Chicken packages use for their attack-less mobs.
 * The fish's sounds are plain SoundEvent constants
 * in 26.2 (TROPICAL_FISH_AMBIENT, TROPICAL_FISH_HURT,
 * TROPICAL_FISH_FLOP, TROPICAL_FISH_DEATH, and the
 * shared FISH_SWIM). Its visible variant is data
 * component driven in 26.2: the pattern, base color,
 * and pattern color live in the TROPICAL_FISH_PATTERN,
 * TROPICAL_FISH_BASE_COLOR, and
 * TROPICAL_FISH_PATTERN_COLOR components, settable
 * through Entity.setComponent, which is how the
 * Mirage School gives each decoy its own colors.
 * AbstractSchoolingFish.finalizeSpawn with null
 * group data only marks the new fish as the leader
 * of an empty school and spawns nothing extra
 * (verified in the jar bytecode, the Cod package's
 * note), so the package's decoy summons call it
 * exactly like every other summon in this mod. The
 * tropical fish is fully aquatic: it swims and
 * flops on land, so every ability in this package
 * gates on the caster being in water.
 */
public final class TropicalFishAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new KaleidoscopeFlashAbility(),
        new CoralCrownGardenAbility(),
        new AnemoneGuardAbility(),
        new MirageSchoolAbility(),
        new PrismScaleAuraAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
