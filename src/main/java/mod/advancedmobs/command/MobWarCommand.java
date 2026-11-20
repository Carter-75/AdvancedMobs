package mod.advancedmobs.command;
import net.minecraft.core.BlockPos;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import mod.advancedmobs.AdvancedMobsMod;
import mod.advancedmobs.config.ModConfig;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.entity.MobWarlordEntity;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Provides /advancedmobs commands for managing the mod.
 * The old /mobwar root is kept as an alias with the same subcommands.
 */
public class MobWarCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess, Commands.RegistrationEnvironment environment) {
        // Primary command root: /advancedmobs
        dispatcher.register(buildCommandTree("advancedmobs"));
        // Alias kept from the old mod name: /mobwar runs the same commands
        dispatcher.register(buildCommandTree("mobwar"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildCommandTree(String root) {
        return Commands.literal(root)
            .then(Commands.literal("help")
                .executes(MobWarCommand::executeHelp))
            .then(Commands.literal("stats")
                .executes(MobWarCommand::executeStats))
            .then(Commands.literal("reset")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(MobWarCommand::executeReset))
            .then(Commands.literal("reload")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(MobWarCommand::executeReload))
            .then(Commands.literal("summon")
                .requires(source -> source.hasPermissionLevel(2))
                .then(Commands.literal("warlord")
                    .executes(MobWarCommand::executeSummonWarlord)))
            .then(Commands.literal("raid")
                .requires(source -> source.hasPermissionLevel(2))
                .then(Commands.literal("forceboss")
                    .executes(MobWarCommand::executeForceRaidBoss)))
            .executes(MobWarCommand::executeHelp);
    }
    
    private static int executeHelp(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getDirectEntity();
        
        source.sendFeedback(() -> Component.literal(""), false);
        source.sendFeedback(() -> 
            Component.literal("═══════════════════════════════════════════════════")
                .withStyle(style -> style.withColor(ChatFormatting.GOLD).withBold(true)), false);
        source.sendFeedback(() -> 
            Component.literal("    ADVANCED MOBS - Commands")
                .withStyle(style -> style.withColor(ChatFormatting.RED).withBold(true)), false);
        source.sendFeedback(() -> 
            Component.literal("═══════════════════════════════════════════════════")
                .withStyle(style -> style.withColor(ChatFormatting.GOLD).withBold(true)), false);
        
        source.sendFeedback(() -> 
            Component.literal("Commands:").withStyle(style -> style.withColor(ChatFormatting.AQUA).withBold(true)), false);
        
        source.sendFeedback(() -> 
            Component.literal("  • /kit [player]")
                .withStyle(style -> style.withColor(ChatFormatting.GREEN))
                .append(Component.literal(" - Max gear kit (self or OP target)")
                    .withStyle(style -> style.withColor(ChatFormatting.GRAY))), false);
        
        source.sendFeedback(() -> 
            Component.literal("  • /advancedmobs help")
                .withStyle(style -> style.withColor(ChatFormatting.GREEN))
                .append(Component.literal(" - Show this help message")
                    .withStyle(style -> style.withColor(ChatFormatting.GRAY))), false);
        
        source.sendFeedback(() -> 
            Component.literal("  • /advancedmobs stats")
                .withStyle(style -> style.withColor(ChatFormatting.GREEN))
                .append(Component.literal(" - Show nearby mob statistics")
                    .withStyle(style -> style.withColor(ChatFormatting.GRAY))), false);
        
        source.sendFeedback(() -> 
            Component.literal("  • /advancedmobs reset")
                .withStyle(style -> style.withColor(ChatFormatting.GREEN))
                .append(Component.literal(" - Clear all mob targets (OP)")
                    .withStyle(style -> style.withColor(ChatFormatting.GRAY))), false);
        
        source.sendFeedback(() -> 
            Component.literal("  • /advancedmobs reload")
                .withStyle(style -> style.withColor(ChatFormatting.GREEN))
                .append(Component.literal(" - Reload config file (OP)")
                    .withStyle(style -> style.withColor(ChatFormatting.GRAY))), false);

        source.sendFeedback(() ->
            Component.literal("  (/mobwar works as an alias for all of these)")
                .withStyle(style -> style.withColor(ChatFormatting.DARK_GRAY)), false);

        source.sendFeedback(() -> 
            Component.literal("Boss:").withStyle(style -> style.withColor(ChatFormatting.DARK_PURPLE).withBold(true)), false);
        
        source.sendFeedback(() -> 
            Component.literal("  • /advancedmobs summon warlord")
                .withStyle(style -> style.withColor(ChatFormatting.GREEN))
                .append(Component.literal(" - Summon Mob Warlord boss (OP)")
                    .withStyle(style -> style.withColor(ChatFormatting.GRAY))), false);
        
        source.sendFeedback(() -> 
            Component.literal("  • /advancedmobs raid forceboss")
                .withStyle(style -> style.withColor(ChatFormatting.GREEN))
                .append(Component.literal(" - Guarantee boss in next raid (OP)")
                    .withStyle(style -> style.withColor(ChatFormatting.GRAY))), false);
        
        source.sendFeedback(() -> 
            Component.literal("  • /summon advancedmobs:mob_warlord")
                .withStyle(style -> style.withColor(ChatFormatting.LIGHT_PURPLE))
                .append(Component.literal(" - Summon the Mob Warlord boss")
                    .withStyle(style -> style.withColor(ChatFormatting.GRAY))), false);
        
        source.sendFeedback(() -> 
            Component.literal("  • Or use Mob Warlord Spawn Egg")
                .withStyle(style -> style.withColor(ChatFormatting.LIGHT_PURPLE)), false);
        
        source.sendFeedback(() -> Component.literal(""), false);
        source.sendFeedback(() -> 
            Component.literal("Game Rules:").withStyle(style -> style.withColor(ChatFormatting.AQUA).withBold(true)), false);
        
        source.sendFeedback(() -> 
            Component.literal("  • /gamerule advancedMobsEnabled <true|false>")
                .withStyle(style -> style.withColor(ChatFormatting.YELLOW)), false);
        source.sendFeedback(() -> 
            Component.literal("  • /gamerule advancedMobsIgnoreSame <true|false>")
                .withStyle(style -> style.withColor(ChatFormatting.YELLOW)), false);
        source.sendFeedback(() -> 
            Component.literal("  • /gamerule advancedMobsTargetPlayers <true|false>")
                .withStyle(style -> style.withColor(ChatFormatting.YELLOW)), false);
        source.sendFeedback(() -> 
            Component.literal("  • /gamerule advancedMobsNeutralAggressive <true|false>")
                .withStyle(style -> style.withColor(ChatFormatting.YELLOW)), false);
        source.sendFeedback(() -> 
            Component.literal("  • /gamerule advancedMobsAlliances <true|false>")
                .withStyle(style -> style.withColor(ChatFormatting.YELLOW)), false);
        source.sendFeedback(() -> 
            Component.literal("  • /gamerule advancedMobsEvolution <true|false>")
                .withStyle(style -> style.withColor(ChatFormatting.YELLOW)), false);
        source.sendFeedback(() -> 
            Component.literal("  • /gamerule advancedMobsRangeMultiplier <1-10000>")
                .withStyle(style -> style.withColor(ChatFormatting.YELLOW)), false);
        source.sendFeedback(() -> 
            Component.literal("    (1=0.01x, 100=1.0x, 10000=100.0x range)")
                .withStyle(style -> style.withColor(ChatFormatting.DARK_GRAY)), false);
        
        source.sendFeedback(() -> 
            Component.literal("═══════════════════════════════════════════════════")
                .withStyle(style -> style.withColor(ChatFormatting.GOLD).withBold(true)), false);
        
        return 1;
    }
    
    private static int executeStats(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getDirectEntity();
        ServerLevel world = source.level();
        // Removed unused variable: BlockPos pos = BlockPos.ofFloored(source.getPosition());
        
        // Find mobs within 50 blocks
        List<Mob> nearbyMobs = world.getEntitiesOfClass(
            Mob.class,
            source.getPlayer().getBoundingBox().inflate(50),
            mob -> mob.isAlive()
        );
        
        if (nearbyMobs.isEmpty()) {
            source.sendFeedback(() -> 
                Component.literal("No mobs found within 50 blocks.")
                    .withStyle(style -> style.withColor(ChatFormatting.YELLOW)), false);
            return 0;
        }
        
        source.sendFeedback(() -> 
            Component.literal("═══ Nearby Mob Statistics ═══")
                .withStyle(style -> style.withColor(ChatFormatting.GOLD).withBold(true)), false);
        
        int highestLevel = 0;
        Mob topMob = null;
        
        for (Mob mob : nearbyMobs) {
            MobWarData data = MobWarData.get(mob);
            if (data.getLevel() > highestLevel) {
                highestLevel = data.getLevel();
                topMob = mob;
            }
        }
        
        source.sendFeedback(() -> 
            Component.literal("Total Mobs: ")
                .withStyle(style -> style.withColor(ChatFormatting.AQUA))
                .append(Component.literal(String.valueOf(nearbyMobs.size()))
                    .withStyle(style -> style.withColor(ChatFormatting.WHITE))), false);
        
        if (topMob != null) {
            Mob finalTopMob = topMob;
            int finalHighestLevel = highestLevel;
            MobWarData topData = MobWarData.get(topMob);
            
            source.sendFeedback(() -> 
                Component.literal("Strongest Mob: ")
                    .withStyle(style -> style.withColor(ChatFormatting.AQUA))
                    .append(Component.literal(finalTopMob.getType().getName().getString())
                        .withStyle(style -> style.withColor(ChatFormatting.RED).withBold(true)))
                    .append(Component.literal(" (Level " + finalHighestLevel + ", " + topData.getKillCount() + " kills)")
                        .withStyle(style -> style.withColor(ChatFormatting.YELLOW))), false);
        }
        
        // Count mobs with levels
        long veteranMobs = nearbyMobs.stream()
            .filter(mob -> MobWarData.get(mob).getLevel() >= 10)
            .count();
        
        if (veteranMobs > 0) {
            long finalVeteranMobs = veteranMobs;
            source.sendFeedback(() -> 
                Component.literal("Veteran Mobs (Lv10+): ")
                    .withStyle(style -> style.withColor(ChatFormatting.AQUA))
                    .append(Component.literal(String.valueOf(finalVeteranMobs))
                        .withStyle(style -> style.withColor(ChatFormatting.RED))), false);
        }
        
        source.sendFeedback(() -> 
            Component.literal("═══════════════════════════")
                .withStyle(style -> style.withColor(ChatFormatting.GOLD).withBold(true)), false);
        
        return 1;
    }
    
    private static int executeReset(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getDirectEntity();
        ServerLevel world = source.level();
        
        // Get all mob entities in the world
        int count = 0;
        for (net.minecraft.world.entity.Entity entity : world.getAllEntities()) {
            if (entity instanceof Mob mob) {
                mob.setTarget(null);
                count++;
            }
        }
        
        int finalCount = count;
        source.sendFeedback(() -> 
            Component.literal("Reset targets for " + finalCount + " mobs.")
                .withStyle(style -> style.withColor(ChatFormatting.GREEN)), true);
        
        return count;
    }
    
    private static int executeReload(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getDirectEntity();
        ModConfig config = ModConfig.reload();

        source.sendFeedback(() -> Component.literal("Advanced Mobs config reloaded from disk.")
            .withStyle(style -> style.withColor(ChatFormatting.GREEN).withBold(true)), true);

        // Print all config fields (excluding lists for brevity)
        if (config != null) {
            source.sendFeedback(() -> Component.literal("--- Advanced Mobs Config ---")
                .withStyle(style -> style.withColor(ChatFormatting.AQUA)), false);

            source.sendFeedback(() -> Component.literal("modEnabled: ").append(Component.literal(String.valueOf(config.modEnabled)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("targetingEnabled: ").append(Component.literal(String.valueOf(config.targetingEnabled)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("targetPlayers: ").append(Component.literal(String.valueOf(config.targetPlayers)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("ignoreSameSpecies: ").append(Component.literal(String.valueOf(config.ignoreSameSpecies)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("neutralMobsAlwaysAggressive: ").append(Component.literal(String.valueOf(config.neutralMobsAlwaysAggressive)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("disableNaturalMobSpawns: ").append(Component.literal(String.valueOf(config.disableNaturalMobSpawns)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("limitNaturalMobSpawns: ").append(Component.literal(String.valueOf(config.limitNaturalMobSpawns)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("naturalSpawnCapPerSimulationDistance: ").append(Component.literal(String.valueOf(config.naturalSpawnCapPerSimulationDistance)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("rangeMultiplierPercent: ").append(Component.literal(String.valueOf(config.rangeMultiplierPercent)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("allianceEnabled: ").append(Component.literal(String.valueOf(config.allianceEnabled)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("weakAllianceDurationMs: ").append(Component.literal(String.valueOf(config.weakAllianceDurationMs)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("strongAllianceDurationMs: ").append(Component.literal(String.valueOf(config.strongAllianceDurationMs)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("allianceBreakChancePercent: ").append(Component.literal(String.valueOf(config.allianceBreakChancePercent)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("strongAllianceBreakChancePercent: ").append(Component.literal(String.valueOf(config.strongAllianceBreakChancePercent)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("scalingEnabled: ").append(Component.literal(String.valueOf(config.scalingEnabled)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("dayScalingMultiplierPercent: ").append(Component.literal(String.valueOf(config.dayScalingMultiplierPercent)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("killScalingMultiplierPercent: ").append(Component.literal(String.valueOf(config.killScalingMultiplierPercent)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("buyChancePercent: ").append(Component.literal(String.valueOf(config.buyChancePercent)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("saveChancePercent: ").append(Component.literal(String.valueOf(config.saveChancePercent)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("maxUpgradeIterations: ").append(Component.literal(String.valueOf(config.maxUpgradeIterations)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("allowBossScaling: ").append(Component.literal(String.valueOf(config.allowBossScaling)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("scaleMobXpDropsWithSpentPoints: ").append(Component.literal(String.valueOf(config.scaleMobXpDropsWithSpentPoints)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("spentPointsPerXpBonusStep: ").append(Component.literal(String.valueOf(config.spentPointsPerXpBonusStep)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("xpBonusPercentPerStep: ").append(Component.literal(String.valueOf(config.xpBonusPercentPerStep)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("manualWorldDayOverride: ").append(Component.literal(String.valueOf(config.manualWorldDayOverride)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("upgradeIntervalTicks: ").append(Component.literal(String.valueOf(config.upgradeIntervalTicks)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("forceSpendAllOnSpawn: ").append(Component.literal(String.valueOf(config.forceSpendAllOnSpawn)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("maxSpawnUpgradeIterations: ").append(Component.literal(String.valueOf(config.maxSpawnUpgradeIterations)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("forceSyncSpawnUpgrade: ").append(Component.literal(String.valueOf(config.forceSyncSpawnUpgrade)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("logSpawnPointSummary: ").append(Component.literal(String.valueOf(config.logSpawnPointSummary)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("allowModdedEnchantments: ").append(Component.literal(String.valueOf(config.allowModdedEnchantments)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("warlordEnabled: ").append(Component.literal(String.valueOf(config.warlordEnabled)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("alwaysSpawnWarlordOnFinalWave: ").append(Component.literal(String.valueOf(config.alwaysSpawnWarlordOnFinalWave)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("warlordSpawnChance: ").append(Component.literal(String.valueOf(config.warlordSpawnChance)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("warlordMinRaidLevel: ").append(Component.literal(String.valueOf(config.warlordMinRaidLevel)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("warlordMinionCount: ").append(Component.literal(String.valueOf(config.warlordMinionCount)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("warlordHealthMultiplierPercent: ").append(Component.literal(String.valueOf(config.warlordHealthMultiplierPercent)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("warlordDamageMultiplierPercent: ").append(Component.literal(String.valueOf(config.warlordDamageMultiplierPercent)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("performanceMode: ").append(Component.literal(String.valueOf(config.performanceMode)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("enableBatching: ").append(Component.literal(String.valueOf(config.enableBatching)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("enableAsyncTasks: ").append(Component.literal(String.valueOf(config.enableAsyncTasks)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("maxConcurrentUpgradeJobs: ").append(Component.literal(String.valueOf(config.maxConcurrentUpgradeJobs)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("upgradeProcessingTimeMs: ").append(Component.literal(String.valueOf(config.upgradeProcessingTimeMs)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("targetingCacheMs: ").append(Component.literal(String.valueOf(config.targetingCacheMs)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("targetingMaxQueriesPerTick: ").append(Component.literal(String.valueOf(config.targetingMaxQueriesPerTick)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("mobDataSaveDebounceMs: ").append(Component.literal(String.valueOf(config.mobDataSaveDebounceMs)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("cleanupNonPlayerGroundProjectiles: ").append(Component.literal(String.valueOf(config.cleanupNonPlayerGroundProjectiles)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("cleanupNonPlayerGroundProjectilesIntervalSeconds: ").append(Component.literal(String.valueOf(config.cleanupNonPlayerGroundProjectilesIntervalSeconds)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("cleanupNonPlayerGroundProjectilesMinAgeTicks: ").append(Component.literal(String.valueOf(config.cleanupNonPlayerGroundProjectilesMinAgeTicks)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("cleanupNonPlayerGroundProjectilesMaxPerWorldPerRun: ").append(Component.literal(String.valueOf(config.cleanupNonPlayerGroundProjectilesMaxPerWorldPerRun)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("showTargetLines: ").append(Component.literal(String.valueOf(config.showTargetLines)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("showLevelParticles: ").append(Component.literal(String.valueOf(config.showLevelParticles)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("disableParticles: ").append(Component.literal(String.valueOf(config.disableParticles)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("minFpsForVisuals: ").append(Component.literal(String.valueOf(config.minFpsForVisuals)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("maxParticlesPerConnection: ").append(Component.literal(String.valueOf(config.maxParticlesPerConnection)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("maxDrawnMinionConnections: ").append(Component.literal(String.valueOf(config.maxDrawnMinionConnections)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("debugUpgradeLog: ").append(Component.literal(String.valueOf(config.debugUpgradeLog)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
            source.sendFeedback(() -> Component.literal("debugLogging: ").append(Component.literal(String.valueOf(config.debugLogging)).withStyle(s -> s.withColor(ChatFormatting.YELLOW))), false);
        }

        return 1;
    }
    
    private static int executeSummonWarlord(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getDirectEntity();
        ServerLevel world = source.level();
        Vec3 pos = source.getPosition();
        
        try {
            // Create the Mob Warlord
            MobWarlordEntity warlord = new MobWarlordEntity(AdvancedMobsMod.MOB_WARLORD, world);
            warlord.snapTo(pos.x, pos.y, pos.z, 0.0f, 0.0f);
            warlord.finalizeSpawn(world, world.getCurrentDifficultyAt(BlockPos.ofFloored(pos)), EntitySpawnReason.COMMAND, null);
            
            world.addFreshEntity(warlord);
            
            source.sendFeedback(() -> 
                Component.literal("Summoned the Mob Warlord boss at ")
                    .withStyle(style -> style.withColor(ChatFormatting.DARK_PURPLE))
                    .append(Component.literal(String.format("%.1f, %.1f, %.1f", pos.x, pos.y, pos.z))
                        .withStyle(style -> style.withColor(ChatFormatting.GOLD)))
                    .append(Component.literal("!")
                        .withStyle(style -> style.withColor(ChatFormatting.DARK_PURPLE))), 
                true
            );
            
            return 1;
        } catch (Exception e) {
            source.sendError(Component.literal("Failed to summon Mob Warlord: " + e.getMessage()));
            return 0;
        }
    }
    
    private static int executeForceRaidBoss(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getDirectEntity();
        
        // Arm the flag in RaidBossSpawnCommand (the raid mixin consumes it).
        // This previously called shouldForceSpawn(), which READS and clears
        // the flag, so the command reported success but never forced a boss.
        mod.advancedmobs.command.RaidBossSpawnCommand.forceNextRaidBoss();
        
        source.sendFeedback(() -> 
            Component.literal("[SUCCESS] Next raid will GUARANTEE a Mob Warlord spawn on the final wave!")
                .withStyle(style -> style.withColor(ChatFormatting.GREEN).withBold(true)), 
            true
        );
        
        source.sendFeedback(() -> 
            Component.literal("  Start a raid to summon the boss!")
                .withStyle(style -> style.withColor(ChatFormatting.GRAY)), 
            false
        );
        
        return 1;
    }
}

