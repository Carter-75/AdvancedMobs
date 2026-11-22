package mod.advancedmobs.ability;

import mod.advancedmobs.ability.blaze.BlazeAbilities;
import mod.advancedmobs.ability.bogged.BoggedAbilities;
import mod.advancedmobs.ability.cavespider.CaveSpiderAbilities;
import mod.advancedmobs.ability.creeper.CreeperAbilities;
import mod.advancedmobs.ability.drowned.DrownedAbilities;
import mod.advancedmobs.ability.enderman.EndermanAbilities;
import mod.advancedmobs.ability.endermite.EndermiteAbilities;
import mod.advancedmobs.ability.ghast.GhastAbilities;
import mod.advancedmobs.ability.husk.HuskAbilities;
import mod.advancedmobs.ability.magmacube.MagmaCubeAbilities;
import mod.advancedmobs.ability.silverfish.SilverfishAbilities;
import mod.advancedmobs.ability.slime.SlimeAbilities;
import mod.advancedmobs.ability.skeleton.SkeletonAbilities;
import mod.advancedmobs.ability.spider.SpiderAbilities;
import mod.advancedmobs.ability.stray.StrayAbilities;
import mod.advancedmobs.ability.wither.WitherAbilities;
import mod.advancedmobs.ability.witherskeleton.WitherSkeletonAbilities;
import mod.advancedmobs.ability.zombie.ZombieAbilities;
import mod.advancedmobs.ability.zombievillager.ZombieVillagerAbilities;

/**
 * AbilityProviders: THE registration site for the new ability system.
 *
 * Every implemented mob adds exactly one line in registerAll(): its config
 * mob_name (the "mob_name" value of its file in mob_configs, the same
 * identity ScalingSystem resolves the mob's upgrade config by) mapped to
 * its MobAbilityProvider. registerAll() is called once from
 * AdvancedMobsMod.onInitialize(), before any mob ticks; the registry is
 * not modified afterwards.
 *
 * A mob type with no line here is simply not part of the ability system
 * yet: AbilitySystem skips it entirely and never rolls access for it.
 */
public final class AbilityProviders {

    private AbilityProviders() {}

    /** Registers every implemented mob's ability provider. Called once at mod init. */
    public static void registerAll() {
        AbilityRegistry.register("Zombie", new ZombieAbilities());
        AbilityRegistry.register("Zombie_Villager", new ZombieVillagerAbilities());
        AbilityRegistry.register("Husk", new HuskAbilities());
        AbilityRegistry.register("Drowned", new DrownedAbilities());
        AbilityRegistry.register("Skeleton", new SkeletonAbilities());
        AbilityRegistry.register("Stray", new StrayAbilities());
        AbilityRegistry.register("Bogged", new BoggedAbilities());
        AbilityRegistry.register("Wither_Skeleton", new WitherSkeletonAbilities());
        AbilityRegistry.register("Creeper", new CreeperAbilities());
        AbilityRegistry.register("Spider", new SpiderAbilities());
        AbilityRegistry.register("Cave_Spider", new CaveSpiderAbilities());
        AbilityRegistry.register("Enderman", new EndermanAbilities());
        AbilityRegistry.register("Endermite", new EndermiteAbilities());
        AbilityRegistry.register("Silverfish", new SilverfishAbilities());
        AbilityRegistry.register("Slime", new SlimeAbilities());
        AbilityRegistry.register("Magma_Cube", new MagmaCubeAbilities());
        AbilityRegistry.register("Blaze", new BlazeAbilities());
        AbilityRegistry.register("Ghast", new GhastAbilities());
        AbilityRegistry.register("Wither", new WitherAbilities());
    }
}
