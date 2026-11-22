package mod.advancedmobs.ability.villager;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * VillagerAbilities: the Villager's provider for the new ability
 * system. Supplies the villager's 5 designed abilities
 * (ABILITY-IDEAS.md, Villager): Emerald Barrage, Iron Guardian Call,
 * Cursed Deal, Marketplace Barricade, Hero of the Village Rally.
 * The instances are shared singletons; all per-activation state
 * lives in AbilityContext.state(), never in the ability objects.
 *
 * VILLAGER FACTS (verified against the real 26.2 jar): the Villager
 * class sits in net.minecraft.world.entity.npc.villager, extends
 * AbstractVillager, and its attribute supplier is
 * Mob.createMobAttributes (FOLLOW_RANGE 16.0) plus MOVEMENT_SPEED
 * 0.5. MAX_HEALTH is the living base of 20.0. There is NO
 * ATTACK_DAMAGE attribute instance anywhere in the villager's
 * chain (a villager has no melee path at all in vanilla), so every
 * damage number in this package is a flat code value dealt through
 * a typed source credited to the villager, the treatment the Cow,
 * Sheep, Chicken, and Cod packages use for their attack-less mobs.
 * The villager's sounds are plain SoundEvent constants in 26.2
 * (VILLAGER_AMBIENT, VILLAGER_TRADE, VILLAGER_YES, VILLAGER_NO,
 * VILLAGER_HURT, VILLAGER_CELEBRATE, VILLAGER_DEATH).
 */
public final class VillagerAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new EmeraldBarrageAbility(),
        new IronGuardianCallAbility(),
        new CursedDealAbility(),
        new MarketplaceBarricadeAbility(),
        new HeroOfTheVillageRallyAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
