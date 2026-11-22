package mod.advancedmobs.ability.wanderingtrader;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * WanderingTraderAbilities: the Wandering Trader's provider for
 * the new ability system. Supplies the wandering trader's 5
 * designed abilities (ABILITY-IDEAS.md, Wandering Trader): Llama
 * Caravan Guard, Invisibility Getaway, Exotic Goods Roulette, The
 * Wandering Road, Bargain Bin Blast. The instances are shared
 * singletons; all per-activation state lives in
 * AbilityContext.state(), never in the ability objects.
 *
 * WANDERING TRADER FACTS (verified against the real 26.2 jar):
 * the WanderingTrader class sits in
 * net.minecraft.world.entity.npc.wanderingtrader, extends
 * AbstractVillager, and its attribute supplier registered in
 * DefaultAttributes is plain Mob.createMobAttributes()
 * (FOLLOW_RANGE 16.0 over the living base, MAX_HEALTH 20.0).
 * There is NO ATTACK_DAMAGE attribute instance anywhere in its
 * chain (a wandering trader has no melee path at all in
 * vanilla), so every damage number in this package is a flat
 * code value dealt through a typed source credited to the
 * trader, the treatment the Cow, Sheep, Chicken, Cod, and
 * Villager packages use for their attack-less mobs. The
 * trader's sounds are plain SoundEvent constants in 26.2, and
 * the package uses its own set where one fits:
 * WANDERING_TRADER_AMBIENT, WANDERING_TRADER_YES,
 * WANDERING_TRADER_HURT, WANDERING_TRADER_DRINK_POTION,
 * WANDERING_TRADER_DISAPPEARED, and WANDERING_TRADER_REAPPEARED
 * (verified by javap; the drink and vanish sounds are the
 * trader's real vanilla getaway sounds).
 */
public final class WanderingTraderAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new LlamaCaravanGuardAbility(),
        new InvisibilityGetawayAbility(),
        new ExoticGoodsRouletteAbility(),
        new TheWanderingRoadAbility(),
        new BargainBinBlastAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
