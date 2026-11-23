package mod.advancedmobs.client;

import mod.advancedmobs.config.ModConfig;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;

/**
 * Advanced Mobs settings screen, built on Cloth Config.
 *
 * This replaces the old hand-rolled Screen subclass, whose custom widget,
 * scrolling, and rendering code was the crash risk when opening the settings
 * from Mod Menu. Cloth Config owns the widgets, layout, scrolling, and
 * rendering here; this class only binds entries to the real ModConfig
 * fields, so every control on the screen reads and writes the live config
 * and nothing is decorative.
 *
 * Binding rules:
 * - Every entry's save consumer writes the ModConfig field of the same
 *   meaning, including the transient mirror fields the rest of the mod
 *   reads (for example rangeMultiplier mirrors rangeMultiplierPercent,
 *   allianceSystemEnabled mirrors allianceEnabled).
 * - Percent fields are edited as whole percents (100 = 1.0x), matching how
 *   ModConfig stores them.
 * - The screen's saving runnable is ModConfig.save(), which persists the
 *   AutoConfig holder to disk.
 */
public final class AdvancedMobsConfigScreen {

    private AdvancedMobsConfigScreen() {
    }

    public static Screen create(Screen parent) {
        ModConfig config = ModConfig.getInstance();

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.literal("Advanced Mobs Config"))
                .setSavingRunnable(ModConfig::save);
        ConfigEntryBuilder entries = builder.entryBuilder();

        // ------------------------------------------------------------------
        // General: the master switches for the big systems.
        // ------------------------------------------------------------------
        ConfigCategory general = builder.getOrCreateCategory(Component.literal("General"));
        general.addEntry(entries.startBooleanToggle(Component.literal("Enable Mod"), config.modEnabled)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Master switch for every system in Advanced Mobs."))
                .setSaveConsumer(v -> config.modEnabled = v)
                .build());
        general.addEntry(entries.startBooleanToggle(Component.literal("Targeting System"), config.targetingEnabled)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Controls mob-vs-mob combat behavior."))
                .setSaveConsumer(v -> config.targetingEnabled = v)
                .build());
        general.addEntry(entries.startBooleanToggle(Component.literal("Alliance System"), config.allianceEnabled)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Let mobs form temporary alliances."))
                .setSaveConsumer(v -> {
                    config.allianceEnabled = v;
                    config.allianceSystemEnabled = v;
                })
                .build());
        general.addEntry(entries.startBooleanToggle(Component.literal("Scaling System"), config.scalingEnabled)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Allow mobs to earn and spend upgrade points."))
                .setSaveConsumer(v -> {
                    config.scalingEnabled = v;
                    config.evolutionSystemEnabled = v;
                })
                .build());
        general.addEntry(entries.startBooleanToggle(Component.literal("Warlord System"), config.warlordEnabled)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Enable the Mob Warlord raid boss feature."))
                .setSaveConsumer(v -> {
                    config.warlordEnabled = v;
                    config.enableMobWarlord = v;
                })
                .build());

        // ------------------------------------------------------------------
        // Targeting: how mobs pick fights.
        // ------------------------------------------------------------------
        ConfigCategory targeting = builder.getOrCreateCategory(Component.literal("Targeting"));
        targeting.addEntry(entries.startBooleanToggle(Component.literal("Target Players"), config.targetPlayers)
                .setDefaultValue(true)
                .setTooltip(Component.literal("If disabled, mobs focus entirely on other mobs."))
                .setSaveConsumer(v -> config.targetPlayers = v)
                .build());
        targeting.addEntry(entries.startBooleanToggle(Component.literal("Ignore Same Species"), config.ignoreSameSpecies)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Prevents zombies from fighting other zombies, etc."))
                .setSaveConsumer(v -> config.ignoreSameSpecies = v)
                .build());
        targeting.addEntry(entries.startBooleanToggle(Component.literal("Neutral Mobs Always Aggressive"), config.neutralMobsAlwaysAggressive)
                .setDefaultValue(false)
                .setTooltip(Component.literal("Forces golems, wolves, and other neutrals into the war."))
                .setSaveConsumer(v -> config.neutralMobsAlwaysAggressive = v)
                .build());
        targeting.addEntry(entries.startBooleanToggle(Component.literal("Disable Natural Spawns"), config.disableNaturalMobSpawns)
                .setDefaultValue(false)
                .setTooltip(Component.literal("Completely blocks passive and hostile natural spawns."))
                .setSaveConsumer(v -> config.disableNaturalMobSpawns = v)
                .build());
        targeting.addEntry(entries.startBooleanToggle(Component.literal("Limit Natural Spawns"), config.limitNaturalMobSpawns)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Caps natural spawns per simulation distance."))
                .setSaveConsumer(v -> config.limitNaturalMobSpawns = v)
                .build());
        targeting.addEntry(entries.startIntSlider(Component.literal("Natural Spawn Cap (per sim. distance)"), config.naturalSpawnCapPerSimulationDistance, 0, 1000)
                .setDefaultValue(5)
                .setTooltip(Component.literal("Spawn cap = this value times the simulation distance."))
                .setSaveConsumer(v -> config.naturalSpawnCapPerSimulationDistance = v)
                .build());
        targeting.addEntry(entries.startIntSlider(Component.literal("Detection Range %"), config.rangeMultiplierPercent, 10, 500)
                .setDefaultValue(100)
                .setTooltip(Component.literal("100 = normal detection range, 200 = double."))
                .setSaveConsumer(v -> {
                    config.rangeMultiplierPercent = v;
                    config.rangeMultiplier = v / 100.0;
                })
                .build());
        targeting.addEntry(entries.startIntSlider(Component.literal("Target Cache (ms)"), config.targetingCacheMs, 500, 5000)
                .setDefaultValue(2000)
                .setTooltip(Component.literal("How long a mob keeps a picked target before re-checking."))
                .setSaveConsumer(v -> config.targetingCacheMs = v)
                .build());
        targeting.addEntry(entries.startIntSlider(Component.literal("Target Queries / Tick"), config.targetingMaxQueriesPerTick, 10, 200)
                .setDefaultValue(30)
                .setTooltip(Component.literal("Budget for target searches each tick."))
                .setSaveConsumer(v -> config.targetingMaxQueriesPerTick = v)
                .build());

        // ------------------------------------------------------------------
        // Alliance: temporary truces between mobs.
        // ------------------------------------------------------------------
        ConfigCategory alliance = builder.getOrCreateCategory(Component.literal("Alliance"));
        alliance.addEntry(entries.startBooleanToggle(Component.literal("Alliance System"), config.allianceEnabled)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Let mobs form temporary alliances."))
                .setSaveConsumer(v -> {
                    config.allianceEnabled = v;
                    config.allianceSystemEnabled = v;
                })
                .build());
        alliance.addEntry(entries.startIntSlider(Component.literal("Weak Alliance Duration (ms)"), config.weakAllianceDurationMs, 1000, 60000)
                .setDefaultValue(5000)
                .setTooltip(Component.literal("How long a cross-species alliance lasts."))
                .setSaveConsumer(v -> config.weakAllianceDurationMs = v)
                .build());
        alliance.addEntry(entries.startIntSlider(Component.literal("Strong Alliance Duration (ms)"), config.strongAllianceDurationMs, 1000, 120000)
                .setDefaultValue(20000)
                .setTooltip(Component.literal("How long a same-species alliance lasts."))
                .setSaveConsumer(v -> config.strongAllianceDurationMs = v)
                .build());
        alliance.addEntry(entries.startIntSlider(Component.literal("Weak Break Chance %"), config.allianceBreakChancePercent, 0, 100)
                .setDefaultValue(30)
                .setTooltip(Component.literal("Chance a weak alliance breaks early."))
                .setSaveConsumer(v -> config.allianceBreakChancePercent = v)
                .build());
        alliance.addEntry(entries.startIntSlider(Component.literal("Strong Break Chance %"), config.strongAllianceBreakChancePercent, 0, 100)
                .setDefaultValue(5)
                .setTooltip(Component.literal("Chance a strong alliance breaks early."))
                .setSaveConsumer(v -> config.strongAllianceBreakChancePercent = v)
                .build());

        // ------------------------------------------------------------------
        // Scaling: points, upgrades, and world-age growth.
        // ------------------------------------------------------------------
        ConfigCategory scaling = builder.getOrCreateCategory(Component.literal("Scaling"));
        scaling.addEntry(entries.startBooleanToggle(Component.literal("Scaling System"), config.scalingEnabled)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Allow mobs to earn and spend upgrade points."))
                .setSaveConsumer(v -> {
                    config.scalingEnabled = v;
                    config.evolutionSystemEnabled = v;
                })
                .build());
        scaling.addEntry(entries.startBooleanToggle(Component.literal("Allow Boss Scaling"), config.allowBossScaling)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Bosses obey the same progression rules."))
                .setSaveConsumer(v -> config.allowBossScaling = v)
                .build());
        scaling.addEntry(entries.startIntSlider(Component.literal("Day Scaling %"), config.dayScalingMultiplierPercent, 0, 1000)
                .setDefaultValue(100)
                .setTooltip(Component.literal("Points from world age. 100 = normal rate."))
                .setSaveConsumer(v -> {
                    config.dayScalingMultiplierPercent = v;
                    config.dayScalingMultiplier = v / 100.0;
                })
                .build());
        scaling.addEntry(entries.startIntSlider(Component.literal("Kill Scaling %"), config.killScalingMultiplierPercent, 0, 1000)
                .setDefaultValue(100)
                .setTooltip(Component.literal("Points from kills. 100 = normal rate."))
                .setSaveConsumer(v -> {
                    config.killScalingMultiplierPercent = v;
                    config.killScalingMultiplier = v / 100.0;
                })
                .build());
        scaling.addEntry(entries.startIntSlider(Component.literal("Save Chance %"), config.saveChancePercent, 0, 100)
                .setDefaultValue(20)
                .setTooltip(Component.literal("Chance a mob banks its points instead of spending. Buy chance is the rest."))
                .setSaveConsumer(v -> {
                    config.saveChancePercent = v;
                    config.buyChancePercent = 100 - v;
                })
                .build());
        scaling.addEntry(entries.startIntSlider(Component.literal("Max Upgrades / Cycle"), config.maxUpgradeIterations, ModConfig.MIN_UPGRADE_ITERATIONS, ModConfig.MAX_UPGRADE_ITERATIONS)
                .setDefaultValue(ModConfig.MAX_UPGRADE_ITERATIONS)
                .setTooltip(Component.literal("Hard cap on purchases in one spending cycle."))
                .setSaveConsumer(v -> config.maxUpgradeIterations = v)
                .build());
        scaling.addEntry(entries.startIntField(Component.literal("Manual Day Override"), config.manualWorldDayOverride)
                .setDefaultValue(-1)
                .setMin(-1)
                .setMax(100000)
                .setTooltip(Component.literal("Pretend the world has run for this many days. -1 uses the real count."))
                .setSaveConsumer(v -> config.manualWorldDayOverride = v)
                .build());
        scaling.addEntry(entries.startIntField(Component.literal("Upgrade Interval (ticks)"), config.upgradeIntervalTicks)
                .setDefaultValue(24000)
                .setMin(200)
                .setMax(720000)
                .setTooltip(Component.literal("How often mobs run a spending cycle. 24000 ticks = one in-game day."))
                .setSaveConsumer(v -> config.upgradeIntervalTicks = v)
                .build());
        scaling.addEntry(entries.startBooleanToggle(Component.literal("Scale XP Drops With Spent Points"), config.scaleMobXpDropsWithSpentPoints)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Mobs that spent more points drop more XP."))
                .setSaveConsumer(v -> config.scaleMobXpDropsWithSpentPoints = v)
                .build());
        scaling.addEntry(entries.startBooleanToggle(Component.literal("Spend All Points On Spawn"), config.forceSpendAllOnSpawn)
                .setDefaultValue(true)
                .setTooltip(Component.literal("New mobs immediately spend their starting points."))
                .setSaveConsumer(v -> config.forceSpendAllOnSpawn = v)
                .build());
        scaling.addEntry(entries.startBooleanToggle(Component.literal("Allow Modded Enchantments"), config.allowModdedEnchantments)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Let mob gear roll enchantments from other mods."))
                .setSaveConsumer(v -> config.allowModdedEnchantments = v)
                .build());

        // ------------------------------------------------------------------
        // Abilities: the per-mob special ability system.
        // ------------------------------------------------------------------
        ConfigCategory abilities = builder.getOrCreateCategory(Component.literal("Abilities"));
        abilities.addEntry(entries.startBooleanToggle(Component.literal("Enable Abilities"), config.abilitiesEnabled)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Master switch for the special abilities every mob can earn."))
                .setSaveConsumer(v -> config.abilitiesEnabled = v)
                .build());
        abilities.addEntry(entries.startDoubleField(Component.literal("Full Power Points"), config.abilityFullPowerPoints)
                .setDefaultValue(3000.0)
                .setMin(1.0)
                .setMax(1000000.0)
                .setTooltip(Component.literal("Lifetime points at which a mob counts as full power for ability access. Lower unlocks abilities faster."))
                .setSaveConsumer(v -> config.abilityFullPowerPoints = v)
                .build());
        abilities.addEntry(entries.startDoubleField(Component.literal("Boss Points / Second: Ender Dragon"), config.bossCombatPointsPerSecondDragon)
                .setDefaultValue(25.0)
                .setMin(0.0)
                .setMax(1000.0)
                .setTooltip(Component.literal("Points the Ender Dragon earns per second while in combat."))
                .setSaveConsumer(v -> config.bossCombatPointsPerSecondDragon = v)
                .build());
        abilities.addEntry(entries.startDoubleField(Component.literal("Boss Points / Second: Wither"), config.bossCombatPointsPerSecondWither)
                .setDefaultValue(15.0)
                .setMin(0.0)
                .setMax(1000.0)
                .setTooltip(Component.literal("Points the Wither earns per second while in combat."))
                .setSaveConsumer(v -> config.bossCombatPointsPerSecondWither = v)
                .build());
        abilities.addEntry(entries.startDoubleField(Component.literal("Boss Points / Second: Mob Warlord"), config.bossCombatPointsPerSecondWarlord)
                .setDefaultValue(15.0)
                .setMin(0.0)
                .setMax(1000.0)
                .setTooltip(Component.literal("Points the Mob Warlord earns per second while in combat."))
                .setSaveConsumer(v -> config.bossCombatPointsPerSecondWarlord = v)
                .build());
        abilities.addEntry(entries.startDoubleField(Component.literal("Boss Points / Second: Warden"), config.bossCombatPointsPerSecondWarden)
                .setDefaultValue(10.0)
                .setMin(0.0)
                .setMax(1000.0)
                .setTooltip(Component.literal("Points the Warden earns per second while in combat."))
                .setSaveConsumer(v -> config.bossCombatPointsPerSecondWarden = v)
                .build());
        abilities.addEntry(entries.startDoubleField(Component.literal("Boss Points / Second: Elder Guardian"), config.bossCombatPointsPerSecondElderGuardian)
                .setDefaultValue(10.0)
                .setMin(0.0)
                .setMax(1000.0)
                .setTooltip(Component.literal("Points the Elder Guardian earns per second while in combat."))
                .setSaveConsumer(v -> config.bossCombatPointsPerSecondElderGuardian = v)
                .build());

        // ------------------------------------------------------------------
        // Warlord: the raid boss.
        // ------------------------------------------------------------------
        ConfigCategory warlord = builder.getOrCreateCategory(Component.literal("Warlord"));
        warlord.addEntry(entries.startBooleanToggle(Component.literal("Warlord System"), config.warlordEnabled)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Enable the Mob Warlord raid boss feature."))
                .setSaveConsumer(v -> {
                    config.warlordEnabled = v;
                    config.enableMobWarlord = v;
                })
                .build());
        warlord.addEntry(entries.startBooleanToggle(Component.literal("Always Spawn On Final Wave"), config.alwaysSpawnWarlordOnFinalWave)
                .setDefaultValue(false)
                .setTooltip(Component.literal("Forces every final raid wave to include the Warlord."))
                .setSaveConsumer(v -> config.alwaysSpawnWarlordOnFinalWave = v)
                .build());
        warlord.addEntry(entries.startIntSlider(Component.literal("Spawn Chance %"), config.warlordSpawnChance, 0, 100)
                .setDefaultValue(25)
                .setTooltip(Component.literal("Chance the Warlord joins a qualifying raid wave."))
                .setSaveConsumer(v -> config.warlordSpawnChance = v)
                .build());
        warlord.addEntry(entries.startIntSlider(Component.literal("Min Raid Level"), config.warlordMinRaidLevel, 1, 20)
                .setDefaultValue(3)
                .setTooltip(Component.literal("Lowest raid level that can spawn the Warlord."))
                .setSaveConsumer(v -> config.warlordMinRaidLevel = v)
                .build());
        warlord.addEntry(entries.startIntSlider(Component.literal("Minion Count"), config.warlordMinionCount, 5, 100)
                .setDefaultValue(20)
                .setTooltip(Component.literal("How many minions the Warlord brings."))
                .setSaveConsumer(v -> config.warlordMinionCount = v)
                .build());
        warlord.addEntry(entries.startIntSlider(Component.literal("Health %"), config.warlordHealthMultiplierPercent, 100, 1000)
                .setDefaultValue(300)
                .setTooltip(Component.literal("Warlord health multiplier. 300 = 3x health."))
                .setSaveConsumer(v -> {
                    config.warlordHealthMultiplierPercent = v;
                    config.warlordHealthMultiplier = v / 100.0;
                })
                .build());
        warlord.addEntry(entries.startIntSlider(Component.literal("Damage %"), config.warlordDamageMultiplierPercent, 100, 1000)
                .setDefaultValue(200)
                .setTooltip(Component.literal("Warlord damage multiplier. 200 = 2x damage."))
                .setSaveConsumer(v -> {
                    config.warlordDamageMultiplierPercent = v;
                    config.warlordDamageMultiplier = v / 100.0;
                })
                .build());

        // ------------------------------------------------------------------
        // Performance: budgets and cleanup.
        // ------------------------------------------------------------------
        ConfigCategory performance = builder.getOrCreateCategory(Component.literal("Performance"));
        performance.addEntry(entries.startBooleanToggle(Component.literal("Performance Mode"), config.performanceMode)
                .setDefaultValue(false)
                .setTooltip(Component.literal("Cuts visuals and batching for weaker machines."))
                .setSaveConsumer(v -> config.performanceMode = v)
                .build());
        performance.addEntry(entries.startBooleanToggle(Component.literal("Enable Batching"), config.enableBatching)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Batch upgrade tasks to reduce tick spikes."))
                .setSaveConsumer(v -> config.enableBatching = v)
                .build());
        performance.addEntry(entries.startBooleanToggle(Component.literal("Enable Async Tasks"), config.enableAsyncTasks)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Move heavy calculations off the main thread."))
                .setSaveConsumer(v -> config.enableAsyncTasks = v)
                .build());
        performance.addEntry(entries.startIntSlider(Component.literal("Concurrent Upgrades"), config.maxConcurrentUpgradeJobs, 1, 256)
                .setDefaultValue(32)
                .setTooltip(Component.literal("How many upgrade jobs may run at once."))
                .setSaveConsumer(v -> config.maxConcurrentUpgradeJobs = v)
                .build());
        performance.addEntry(entries.startIntSlider(Component.literal("Upgrade Window (ms)"), config.upgradeProcessingTimeMs, 1000, 30000)
                .setDefaultValue(5000)
                .setTooltip(Component.literal("Time budget for processing upgrades each cycle."))
                .setSaveConsumer(v -> config.upgradeProcessingTimeMs = v)
                .build());
        // Note: there is deliberately no Mob Save Debounce entry. The
        // config field exists but nothing consumes it (mob data persists
        // through vanilla entity NBT saving, which has no debounce), and
        // the screen must not promise a setting that does nothing.
        performance.addEntry(entries.startBooleanToggle(Component.literal("Clean Up Stuck Projectiles"), config.cleanupNonPlayerGroundProjectiles)
                .setDefaultValue(false)
                .setTooltip(Component.literal("Periodically removes old arrows and tridents stuck in the ground."))
                .setSaveConsumer(v -> config.cleanupNonPlayerGroundProjectiles = v)
                .build());
        performance.addEntry(entries.startIntSlider(Component.literal("Projectile Cleanup Interval (s)"), config.cleanupNonPlayerGroundProjectilesIntervalSeconds, 5, 120)
                .setDefaultValue(5)
                .setTooltip(Component.literal("How often the projectile cleanup runs."))
                .setSaveConsumer(v -> config.cleanupNonPlayerGroundProjectilesIntervalSeconds = v)
                .build());

        // ------------------------------------------------------------------
        // Visuals: client overlays.
        // ------------------------------------------------------------------
        ConfigCategory visuals = builder.getOrCreateCategory(Component.literal("Visuals"));
        visuals.addEntry(entries.startBooleanToggle(Component.literal("Disable Particles"), config.disableParticles)
                .setDefaultValue(false)
                .setTooltip(Component.literal("Removes most particles for better FPS."))
                .setSaveConsumer(v -> config.disableParticles = v)
                .build());
        visuals.addEntry(entries.startBooleanToggle(Component.literal("Show Target Lines"), config.showTargetLines)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Draw lines between mobs and their targets."))
                .setSaveConsumer(v -> config.showTargetLines = v)
                .build());
        visuals.addEntry(entries.startBooleanToggle(Component.literal("Level Up Particles"), config.showLevelParticles)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Play particles when mobs level up."))
                .setSaveConsumer(v -> config.showLevelParticles = v)
                .build());
        visuals.addEntry(entries.startIntSlider(Component.literal("Minimum FPS For Visuals"), config.minFpsForVisuals, 0, 144)
                .setDefaultValue(30)
                .setTooltip(Component.literal("Visuals pause below this frame rate."))
                .setSaveConsumer(v -> config.minFpsForVisuals = v)
                .build());
        visuals.addEntry(entries.startIntSlider(Component.literal("Particles / Connection"), config.maxParticlesPerConnection, 1, 50)
                .setDefaultValue(8)
                .setTooltip(Component.literal("Particle budget per drawn connection."))
                .setSaveConsumer(v -> config.maxParticlesPerConnection = v)
                .build());
        visuals.addEntry(entries.startIntSlider(Component.literal("Minion Connections Drawn"), config.maxDrawnMinionConnections, 1, 100)
                .setDefaultValue(15)
                .setTooltip(Component.literal("How many warlord minion links are drawn at once."))
                .setSaveConsumer(v -> config.maxDrawnMinionConnections = v)
                .build());

        // ------------------------------------------------------------------
        // Debug: logging for testing.
        // ------------------------------------------------------------------
        ConfigCategory debug = builder.getOrCreateCategory(Component.literal("Debug"));
        debug.addEntry(entries.startBooleanToggle(Component.literal("Upgrade Chat Log"), config.debugUpgradeLog)
                .setDefaultValue(false)
                .setTooltip(Component.literal("Print every upgrade decision in chat while testing."))
                .setSaveConsumer(v -> config.debugUpgradeLog = v)
                .build());
        debug.addEntry(entries.startBooleanToggle(Component.literal("Verbose Console Logging"), config.debugLogging)
                .setDefaultValue(false)
                .setTooltip(Component.literal("Spam-level logs for profiling and bug reports. Only toggle when needed."))
                .setSaveConsumer(v -> config.debugLogging = v)
                .build());

        return builder.build();
    }
}
