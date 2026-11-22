package mod.advancedmobs.ability;

import mod.advancedmobs.ability.blaze.BlazeAbilities;
import mod.advancedmobs.ability.bogged.BoggedAbilities;
import mod.advancedmobs.ability.cavespider.CaveSpiderAbilities;
import mod.advancedmobs.ability.creeper.CreeperAbilities;
import mod.advancedmobs.ability.drowned.DrownedAbilities;
import mod.advancedmobs.ability.elderguardian.ElderGuardianAbilities;
import mod.advancedmobs.ability.enderdragon.EnderDragonAbilities;
import mod.advancedmobs.ability.enderman.EndermanAbilities;
import mod.advancedmobs.ability.endermite.EndermiteAbilities;
import mod.advancedmobs.ability.evoker.EvokerAbilities;
import mod.advancedmobs.ability.fox.FoxAbilities;
import mod.advancedmobs.ability.ghast.GhastAbilities;
import mod.advancedmobs.ability.giant.GiantAbilities;
import mod.advancedmobs.ability.sulfurcube.SulfurCubeAbilities;
import mod.advancedmobs.ability.bee.BeeAbilities;
import mod.advancedmobs.ability.guardian.GuardianAbilities;
import mod.advancedmobs.ability.hoglin.HoglinAbilities;
import mod.advancedmobs.ability.husk.HuskAbilities;
import mod.advancedmobs.ability.illusioner.IllusionerAbilities;
import mod.advancedmobs.ability.ravager.RavagerAbilities;
import mod.advancedmobs.ability.breeze.BreezeAbilities;
import mod.advancedmobs.ability.phantom.PhantomAbilities;
import mod.advancedmobs.ability.warden.WardenAbilities;
import mod.advancedmobs.ability.magmacube.MagmaCubeAbilities;
import mod.advancedmobs.ability.piglin.PiglinAbilities;
import mod.advancedmobs.ability.piglinbrute.PiglinBruteAbilities;
import mod.advancedmobs.ability.silverfish.SilverfishAbilities;
import mod.advancedmobs.ability.slime.SlimeAbilities;
import mod.advancedmobs.ability.skeleton.SkeletonAbilities;
import mod.advancedmobs.ability.spider.SpiderAbilities;
import mod.advancedmobs.ability.stray.StrayAbilities;
import mod.advancedmobs.ability.wither.WitherAbilities;
import mod.advancedmobs.ability.witherskeleton.WitherSkeletonAbilities;
import mod.advancedmobs.ability.shulker.ShulkerAbilities;
import mod.advancedmobs.ability.vex.VexAbilities;
import mod.advancedmobs.ability.pillager.PillagerAbilities;
import mod.advancedmobs.ability.vindicator.VindicatorAbilities;
import mod.advancedmobs.ability.witch.WitchAbilities;
import mod.advancedmobs.ability.polarbear.PolarBearAbilities;
import mod.advancedmobs.ability.wolf.WolfAbilities;
import mod.advancedmobs.ability.llama.LlamaAbilities;
import mod.advancedmobs.ability.traderllama.TraderLlamaAbilities;
import mod.advancedmobs.ability.goat.GoatAbilities;
import mod.advancedmobs.ability.panda.PandaAbilities;
import mod.advancedmobs.ability.irongolem.IronGolemAbilities;
import mod.advancedmobs.ability.snowgolem.SnowGolemAbilities;
import mod.advancedmobs.ability.strider.StriderAbilities;
import mod.advancedmobs.ability.dolphin.DolphinAbilities;
import mod.advancedmobs.ability.pig.PigAbilities;
import mod.advancedmobs.ability.cow.CowAbilities;
import mod.advancedmobs.ability.mooshroom.MooshroomAbilities;
import mod.advancedmobs.ability.sheep.SheepAbilities;
import mod.advancedmobs.ability.chicken.ChickenAbilities;
import mod.advancedmobs.ability.rabbit.RabbitAbilities;
import mod.advancedmobs.ability.horse.HorseAbilities;
import mod.advancedmobs.ability.donkey.DonkeyAbilities;
import mod.advancedmobs.ability.mule.MuleAbilities;
import mod.advancedmobs.ability.skeletonhorse.SkeletonHorseAbilities;
import mod.advancedmobs.ability.camel.CamelAbilities;
import mod.advancedmobs.ability.cat.CatAbilities;
import mod.advancedmobs.ability.ocelot.OcelotAbilities;
import mod.advancedmobs.ability.parrot.ParrotAbilities;
import mod.advancedmobs.ability.frog.FrogAbilities;
import mod.advancedmobs.ability.tadpole.TadpoleAbilities;
import mod.advancedmobs.ability.turtle.TurtleAbilities;
import mod.advancedmobs.ability.armadillo.ArmadilloAbilities;
import mod.advancedmobs.ability.sniffer.SnifferAbilities;
import mod.advancedmobs.ability.cod.CodAbilities;
import mod.advancedmobs.ability.salmon.SalmonAbilities;
import mod.advancedmobs.ability.tropicalfish.TropicalFishAbilities;
import mod.advancedmobs.ability.pufferfish.PufferfishAbilities;
import mod.advancedmobs.ability.glowsquid.GlowSquidAbilities;
import mod.advancedmobs.ability.villager.VillagerAbilities;
import mod.advancedmobs.ability.wanderingtrader.WanderingTraderAbilities;
import mod.advancedmobs.ability.squid.SquidAbilities;
import mod.advancedmobs.ability.zoglin.ZoglinAbilities;
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
        AbilityRegistry.register("Ender_Dragon", new EnderDragonAbilities());
        AbilityRegistry.register("Guardian", new GuardianAbilities());
        AbilityRegistry.register("Elder_Guardian", new ElderGuardianAbilities());
        AbilityRegistry.register("Hoglin", new HoglinAbilities());
        AbilityRegistry.register("Zoglin", new ZoglinAbilities());
        AbilityRegistry.register("Shulker", new ShulkerAbilities());
        AbilityRegistry.register("Piglin", new PiglinAbilities());
        AbilityRegistry.register("Piglin_Brute", new PiglinBruteAbilities());
        AbilityRegistry.register("Vex", new VexAbilities());
        AbilityRegistry.register("Witch", new WitchAbilities());
        AbilityRegistry.register("Evoker", new EvokerAbilities());
        AbilityRegistry.register("Vindicator", new VindicatorAbilities());
        AbilityRegistry.register("Pillager", new PillagerAbilities());
        AbilityRegistry.register("Illusioner", new IllusionerAbilities());
        AbilityRegistry.register("Ravager", new RavagerAbilities());
        AbilityRegistry.register("Warden", new WardenAbilities());
        AbilityRegistry.register("Breeze", new BreezeAbilities());
        AbilityRegistry.register("Phantom", new PhantomAbilities());
        AbilityRegistry.register("Giant", new GiantAbilities());
        AbilityRegistry.register("Sulfur_Cube", new SulfurCubeAbilities());
        AbilityRegistry.register("Bee", new BeeAbilities());
        AbilityRegistry.register("Wolf", new WolfAbilities());
        AbilityRegistry.register("Polar_Bear", new PolarBearAbilities());
        AbilityRegistry.register("Llama", new LlamaAbilities());
        AbilityRegistry.register("Trader_Llama", new TraderLlamaAbilities());
        AbilityRegistry.register("Goat", new GoatAbilities());
        AbilityRegistry.register("Panda", new PandaAbilities());
        AbilityRegistry.register("Iron_Golem", new IronGolemAbilities());
        AbilityRegistry.register("Snow_Golem", new SnowGolemAbilities());
        AbilityRegistry.register("Strider", new StriderAbilities());
        AbilityRegistry.register("Dolphin", new DolphinAbilities());
        AbilityRegistry.register("Pig", new PigAbilities());
        AbilityRegistry.register("Cow", new CowAbilities());
        AbilityRegistry.register("Mooshroom", new MooshroomAbilities());
        AbilityRegistry.register("Sheep", new SheepAbilities());
        AbilityRegistry.register("Chicken", new ChickenAbilities());
        AbilityRegistry.register("Rabbit", new RabbitAbilities());
        AbilityRegistry.register("Horse", new HorseAbilities());
        AbilityRegistry.register("Donkey", new DonkeyAbilities());
        AbilityRegistry.register("Mule", new MuleAbilities());
        AbilityRegistry.register("Skeleton_Horse", new SkeletonHorseAbilities());
        AbilityRegistry.register("Camel", new CamelAbilities());
        AbilityRegistry.register("Cat", new CatAbilities());
        AbilityRegistry.register("Ocelot", new OcelotAbilities());
        AbilityRegistry.register("Parrot", new ParrotAbilities());
        AbilityRegistry.register("Fox", new FoxAbilities());
        AbilityRegistry.register("Frog", new FrogAbilities());
        AbilityRegistry.register("Tadpole", new TadpoleAbilities());
        AbilityRegistry.register("Turtle", new TurtleAbilities());
        AbilityRegistry.register("Armadillo", new ArmadilloAbilities());
        AbilityRegistry.register("Sniffer", new SnifferAbilities());
        AbilityRegistry.register("Cod", new CodAbilities());
        AbilityRegistry.register("Salmon", new SalmonAbilities());
        AbilityRegistry.register("Tropical_Fish", new TropicalFishAbilities());
        AbilityRegistry.register("Pufferfish", new PufferfishAbilities());
        AbilityRegistry.register("Squid", new SquidAbilities());
        AbilityRegistry.register("Glow_Squid", new GlowSquidAbilities());
        AbilityRegistry.register("Villager", new VillagerAbilities());
        AbilityRegistry.register("Wandering_Trader", new WanderingTraderAbilities());
    }
}
