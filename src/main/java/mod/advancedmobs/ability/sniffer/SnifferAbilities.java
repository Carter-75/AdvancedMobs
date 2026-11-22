package mod.advancedmobs.ability.sniffer;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * SnifferAbilities: the Sniffer's provider for the new
 * ability system. Supplies the sniffer's 5 designed
 * abilities (ABILITY-IDEAS.md, Sniffer): Ancient Seed
 * Mortar, Sniff Out Weakness, Rooted Colossus, Pollen
 * Haze, Seed Vault. The instances are shared singletons;
 * all per-activation state lives in
 * AbilityContext.state(), never in the ability objects.
 *
 * SNIFFER FACTS (verified against the real 26.2 jar): the
 * Sniffer class sits in net.minecraft.world.entity.animal
 * .sniffer, extends Animal, and its createAttributes
 * builds on the Animal chain with MAX_HEALTH fixed at
 * 14.0 and MOVEMENT_SPEED fixed at 0.1. The chain carries
 * NO ATTACK_DAMAGE attribute instance (the base living set
 * lacks it and Mob.createMobAttributes adds only
 * FOLLOW_RANGE; the Sniffer's own builder adds only health
 * and speed), so every damage number in this package is a
 * flat code value dealt through a damage source credited
 * to the sniffer. The sniffer's sounds are plain
 * SoundEvents: SNIFFER_IDLE, SNIFFER_HURT, SNIFFER_EAT,
 * SNIFFER_SNIFFING, SNIFFER_SCENTING, SNIFFER_SEARCHING,
 * SNIFFER_DIGGING, SNIFFER_DROP_SEED, and SNIFFER_HAPPY.
 * The ancient plants are Blocks.TORCHFLOWER (a
 * FlowerBlock) and Blocks.PITCHER_PLANT (a
 * DoublePlantBlock whose default state is the lower half),
 * and the mortar's pod item is Items.PITCHER_POD.
 */
public final class SnifferAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new AncientSeedMortarAbility(),
        new SniffOutWeaknessAbility(),
        new RootedColossusAbility(),
        new PollenHazeAbility(),
        new SeedVaultAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
