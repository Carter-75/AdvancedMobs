package mod.advancedmobs.config;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import me.shedaniel.autoconfig.ConfigData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Advanced Mobs Configuration
 * 
 * The mod has 4 independent systems that can be enabled/disabled separately:
 * 1. TARGETING - Mobs fight each other
 * 2. ALLIANCE - Mobs team up against common enemies
 * 3. MOB SCALING - Mobs get stronger over time (progression system)
 * 4. MOB WARLORD - Raid boss that spawns during raids
 * 
 * Each system works independently. Disabling one doesn't affect others.
 */
@Config(name = "advancedmobs")
public class ModConfig implements ConfigData {

    // ==========================================================================
    //                              MASTER CONTROL
    // ==========================================================================
    
    @ConfigEntry.Category("general")
    @ConfigEntry.Gui.Tooltip(count = 2)
    public boolean modEnabled = true;

    // ==========================================================================
    //                         SECTION 1: TARGETING SYSTEM
    // Controls whether mobs fight each other
    // ==========================================================================
    
    @ConfigEntry.Category("targeting")
    @ConfigEntry.Gui.Tooltip(count = 2)
    public boolean targetingEnabled = true;
    
    @ConfigEntry.Gui.Tooltip(count = 2)
    public boolean targetPlayers = true;
    
    @ConfigEntry.Gui.Tooltip(count = 2)
    public boolean ignoreSameSpecies = true;
    
    @ConfigEntry.Gui.Tooltip(count = 2)
    public boolean neutralMobsAlwaysAggressive = false;
    
    @ConfigEntry.Gui.Tooltip(count = 2)
    public boolean disableNaturalMobSpawns = false;

    @ConfigEntry.Gui.Tooltip(count = 3)
    public boolean limitNaturalMobSpawns = true;

    @ConfigEntry.BoundedDiscrete(min = 0, max = 1000)
    @ConfigEntry.Gui.Tooltip(count = 3)
    public int naturalSpawnCapPerSimulationDistance = 5; // cap = multiplier * simulationDistance
    
    @ConfigEntry.BoundedDiscrete(min = 1, max = 5000)
    @ConfigEntry.Gui.Tooltip(count = 2)
    public int rangeMultiplierPercent = 100; // 100 = 1.0x, 200 = 2.0x, etc.

    // ==========================================================================
    //                         SECTION 2: ALLIANCE SYSTEM
    // Controls whether mobs form temporary alliances
    // ==========================================================================
    
    @ConfigEntry.Category("alliance")
    @ConfigEntry.Gui.Tooltip(count = 2)
    public boolean allianceEnabled = true;
    
    @ConfigEntry.BoundedDiscrete(min = 1000, max = 60000)
    @ConfigEntry.Gui.Tooltip(count = 2)
    public int weakAllianceDurationMs = 5000; // Different species
    
    @ConfigEntry.BoundedDiscrete(min = 1000, max = 120000)
    @ConfigEntry.Gui.Tooltip(count = 2)
    public int strongAllianceDurationMs = 20000; // Same species
    
    @ConfigEntry.BoundedDiscrete(min = 0, max = 100)
    @ConfigEntry.Gui.Tooltip(count = 2)
    public int allianceBreakChancePercent = 30; // Weak alliance break chance
    
    @ConfigEntry.BoundedDiscrete(min = 0, max = 100)
    @ConfigEntry.Gui.Tooltip(count = 2)
    public int strongAllianceBreakChancePercent = 5; // Strong alliance break chance

    // ==========================================================================
    //                       SECTION 3: MOB SCALING SYSTEM
    // Controls mob progression (getting stronger over time)
    // ==========================================================================
    
    @ConfigEntry.Category("scaling")
    @ConfigEntry.Gui.Tooltip(count = 2)
    public boolean scalingEnabled = true;
    
    @ConfigEntry.BoundedDiscrete(min = 0, max = 1000)
    @ConfigEntry.Gui.Tooltip(count = 2)
    public int dayScalingMultiplierPercent = 100; // 100 = 1.0x
    
    @ConfigEntry.BoundedDiscrete(min = 0, max = 1000)
    @ConfigEntry.Gui.Tooltip(count = 2)
    public int killScalingMultiplierPercent = 100; // 100 = 1.0x
    
    @ConfigEntry.BoundedDiscrete(min = 0, max = 100)
    @ConfigEntry.Gui.Tooltip(count = 2)
    public int buyChancePercent = 80; // 80% chance to buy upgrade
    
    @ConfigEntry.BoundedDiscrete(min = 0, max = 100)
    @ConfigEntry.Gui.Tooltip(count = 2)
    public int saveChancePercent = 20; // 20% chance to save points

    public static final int MIN_UPGRADE_ITERATIONS = 10;
    public static final int MAX_UPGRADE_ITERATIONS = 1000;

    @ConfigEntry.BoundedDiscrete(min = MIN_UPGRADE_ITERATIONS, max = MAX_UPGRADE_ITERATIONS)
    @ConfigEntry.Gui.Tooltip(count = 2)
    public int maxUpgradeIterations = MAX_UPGRADE_ITERATIONS; // Hard cap per spend cycle
    
    @ConfigEntry.Gui.Tooltip(count = 2)
    public boolean allowBossScaling = true;

    @ConfigEntry.Category("scaling")
    @ConfigEntry.Gui.Tooltip(count = 4)
    public boolean scaleMobXpDropsWithSpentPoints = true;

    @ConfigEntry.Category("scaling")
    @ConfigEntry.BoundedDiscrete(min = 1, max = 100000)
    @ConfigEntry.Gui.Tooltip(count = 4)
    public int spentPointsPerXpBonusStep = 100; // 100 spent points = +bonusPercentPerXpStep%

    @ConfigEntry.Category("scaling")
    @ConfigEntry.BoundedDiscrete(min = 0, max = 10000)
    @ConfigEntry.Gui.Tooltip(count = 4)
    public int xpBonusPercentPerStep = 150; // 150 = +150% XP per step

    @ConfigEntry.BoundedDiscrete(min = -1, max = 100000)
    @ConfigEntry.Gui.Tooltip(count = 3)
    public int manualWorldDayOverride = -1; // -1 = use actual world time

    @ConfigEntry.BoundedDiscrete(min = 20, max = 480000)
    @ConfigEntry.Gui.Tooltip(count = 4)
    public int upgradeIntervalTicks = 24000; // Default: once per in-game day (24000 ticks)

    @ConfigEntry.Gui.Tooltip(count = 4)
    public boolean forceSpendAllOnSpawn = true;

    @ConfigEntry.BoundedDiscrete(min = MIN_UPGRADE_ITERATIONS, max = 20000)
    @ConfigEntry.Gui.Tooltip(count = 4)
    public int maxSpawnUpgradeIterations = 5000;

    @ConfigEntry.Gui.Tooltip(count = 4)
    public boolean forceSyncSpawnUpgrade = true;

    @ConfigEntry.Gui.Tooltip(count = 4)
    public boolean logSpawnPointSummary = true;

    @ConfigEntry.Gui.Tooltip(count = 5)
    public boolean allowModdedEnchantments = true;

    // ==========================================================================
    //                       SECTION 3B: ABILITY SYSTEM
    // Controls the new special abilities (see AbilitySystem for the rules)
    // ==========================================================================

    @ConfigEntry.Category("abilities")
    @ConfigEntry.Gui.Tooltip(count = 2)
    public boolean abilitiesEnabled = true;

    // FULL_POWER reference for the ability system's power fraction:
    // frac = lifetime points earned / this value, clamped to [0, 1].
    // Default 3000 is the summed cost of every level of every upgrade track
    // in the Zombie reference config (potion effects 230 + weapon 439 +
    // shield 352 + helmet 485 + chestplate 464 + leggings 488 + boots 528
    // = 2,986, rounded): a mob that earned this many lifetime points has
    // earned enough to buy out a complete upgrade tree. Lower it to make
    // abilities unlock faster, raise it to make full power rarer.
    @ConfigEntry.Category("abilities")
    @ConfigEntry.Gui.Tooltip(count = 4)
    public double abilityFullPowerPoints = 3000.0;

    // ==========================================================================
    //                 SECTION 3C: BOSS COMBAT PROGRESSION
    // ==========================================================================
    // Boss fights last minutes, not days, so day-based accrual can never
    // power a boss up mid-fight. While a boss is in active combat (living
    // target, or hurt within the last 10 seconds, or, for the dragon, a
    // player inside its arena), it earns these points per second into the
    // same lifetime pool the spending lottery and the ability power
    // fraction read. Non-boss mobs earn nothing here. Rates are tuned
    // against FULL_POWER (3000): ability brackets sit at 900 / 1650 /
    // 2250 / 2700 lifetime points (frac 0.30 / 0.55 / 0.75 / 0.90).
    //   Ender Dragon 25/s: bracket 2 at 36 s, 3 at 66 s, 4 at 90 s,
    //     5 at 108 s, full power at 120 s of continuous combat.
    //   Wither and Mob Warlord 15/s: bracket 2 at 60 s, 5 at 180 s,
    //     full power at 200 s.
    //   Warden and Elder Guardian 10/s: bracket 2 at 90 s, 5 at 270 s,
    //     full power at 300 s.
    // The full design comment lives at the implementation site in
    // ScalingSystem (accrueBossCombatPoints).

    @ConfigEntry.Category("scaling")
    @ConfigEntry.Gui.Tooltip(count = 2)
    public double bossCombatPointsPerSecondDragon = 25.0;

    @ConfigEntry.Category("scaling")
    @ConfigEntry.Gui.Tooltip(count = 2)
    public double bossCombatPointsPerSecondWither = 15.0;

    @ConfigEntry.Category("scaling")
    @ConfigEntry.Gui.Tooltip(count = 2)
    public double bossCombatPointsPerSecondWarlord = 15.0;

    @ConfigEntry.Category("scaling")
    @ConfigEntry.Gui.Tooltip(count = 2)
    public double bossCombatPointsPerSecondWarden = 10.0;

    @ConfigEntry.Category("scaling")
    @ConfigEntry.Gui.Tooltip(count = 2)
    public double bossCombatPointsPerSecondElderGuardian = 10.0;

    // Temporary fight rage. Same formula for every mob, read from config,
    // dropped when the fight ends. The damage multiplier makes a live fight
    // snowball; the end waves are a percent of that mob's own max health so
    // equal fighters bleed out together and a stalemate cannot sit past a
    // few minutes. Nothing here is stored as lifetime points.
    @ConfigEntry.Category("scaling")
    @ConfigEntry.Gui.Tooltip(count = 3)
    public boolean combatRageEnabled = true;

    @ConfigEntry.Category("scaling")
    @ConfigEntry.Gui.Tooltip(count = 2)
    public double combatRageGraceSeconds = 20.0;

    @ConfigEntry.Category("scaling")
    @ConfigEntry.Gui.Tooltip(count = 2)
    public double combatRageRampSeconds = 70.0;

    @ConfigEntry.Category("scaling")
    @ConfigEntry.Gui.Tooltip(count = 2)
    public double combatRageMaxDamageMultiplier = 3.0;

    @ConfigEntry.Category("scaling")
    @ConfigEntry.Gui.Tooltip(count = 2)
    public double combatRageMaxSpeedBonus = 0.35;

    @ConfigEntry.Category("scaling")
    @ConfigEntry.Gui.Tooltip(count = 2)
    public double combatRageWaveStartSeconds = 90.0;

    @ConfigEntry.Category("scaling")
    @ConfigEntry.Gui.Tooltip(count = 2)
    public double combatRageWaveIntervalSeconds = 12.0;

    @ConfigEntry.Category("scaling")
    @ConfigEntry.Gui.Tooltip(count = 2)
    public double combatRageWaveBaseFraction = 0.05;

    @ConfigEntry.Category("scaling")
    @ConfigEntry.Gui.Tooltip(count = 2)
    public double combatRageWaveGrowthFraction = 0.012;

    @ConfigEntry.Category("scaling")
    @ConfigEntry.Gui.Tooltip(count = 2)
    public double combatRageWaveFractionCap = 0.25;

    @ConfigEntry.Category("scaling")
    @ConfigEntry.Gui.Tooltip(count = 2)
    public double combatRageWaveJitter = 0.15;

    @ConfigEntry.Category("scaling")
    @ConfigEntry.Gui.Tooltip(count = 2)
    public double combatRageDecaySeconds = 8.0;

    // ==========================================================================
    //                       SECTION 4: MOB WARLORD SYSTEM
    // Controls the raid boss feature
    // ==========================================================================
    
    @ConfigEntry.Category("warlord")
    @ConfigEntry.Gui.Tooltip(count = 2)
    public boolean warlordEnabled = true;
    
    @ConfigEntry.Gui.Tooltip(count = 3)
    public boolean alwaysSpawnWarlordOnFinalWave = false;
    
    @ConfigEntry.BoundedDiscrete(min = 0, max = 100)
    @ConfigEntry.Gui.Tooltip(count = 2)
    public int warlordSpawnChance = 25;
    
    @ConfigEntry.BoundedDiscrete(min = 1, max = 20)
    @ConfigEntry.Gui.Tooltip(count = 2)
    public int warlordMinRaidLevel = 3;
    
    @ConfigEntry.BoundedDiscrete(min = 5, max = 100)
    @ConfigEntry.Gui.Tooltip(count = 2)
    public int warlordMinionCount = 20;
    
    @ConfigEntry.BoundedDiscrete(min = 100, max = 1000)
    @ConfigEntry.Gui.Tooltip(count = 2)
    public int warlordHealthMultiplierPercent = 300; // 300 = 3.0x
    
    @ConfigEntry.BoundedDiscrete(min = 100, max = 1000)
    @ConfigEntry.Gui.Tooltip(count = 2)
    public int warlordDamageMultiplierPercent = 200; // 200 = 2.0x

    // ==========================================================================
    //                            PERFORMANCE
    // Shared performance settings that affect all systems
    // ==========================================================================
    
    @ConfigEntry.Category("performance")
    @ConfigEntry.Gui.Tooltip(count = 2)
    public boolean performanceMode = false;
    
    @ConfigEntry.Gui.Tooltip(count = 2)
    public boolean enableBatching = true;
    
    @ConfigEntry.Gui.Tooltip(count = 2)
    public boolean enableAsyncTasks = true;

    @ConfigEntry.BoundedDiscrete(min = 1, max = 256)
    @ConfigEntry.Gui.Tooltip(count = 3)
    public int maxConcurrentUpgradeJobs = 32;
    
    @ConfigEntry.BoundedDiscrete(min = 1000, max = 30000)
    @ConfigEntry.Gui.Tooltip(count = 3)
    public int upgradeProcessingTimeMs = 5000; // Default 5 seconds per skilltree.txt
    
    @ConfigEntry.BoundedDiscrete(min = 100, max = 5000)
    @ConfigEntry.Gui.Tooltip(count = 2)
    public int targetingCacheMs = 2000;
    
    @ConfigEntry.BoundedDiscrete(min = 10, max = 200)
    @ConfigEntry.Gui.Tooltip(count = 2)
    public int targetingMaxQueriesPerTick = 30;
    
    @ConfigEntry.BoundedDiscrete(min = 50, max = 1000)
    @ConfigEntry.Gui.Tooltip(count = 2)
    public int mobDataSaveDebounceMs = 200;

    @ConfigEntry.Category("performance")
    @ConfigEntry.Gui.Tooltip(count = 4)
    public boolean cleanupNonPlayerGroundProjectiles = false;

    @ConfigEntry.Category("performance")
    @ConfigEntry.BoundedDiscrete(min = 5, max = 600)
    @ConfigEntry.Gui.Tooltip(count = 4)
    public int cleanupNonPlayerGroundProjectilesIntervalSeconds = 5;

    @ConfigEntry.Category("performance")
    @ConfigEntry.BoundedDiscrete(min = 0, max = 12000)
    @ConfigEntry.Gui.Tooltip(count = 4)
    public int cleanupNonPlayerGroundProjectilesMinAgeTicks = 60;

    @ConfigEntry.Category("performance")
    @ConfigEntry.BoundedDiscrete(min = 0, max = 50000)
    @ConfigEntry.Gui.Tooltip(count = 4)
    public int cleanupNonPlayerGroundProjectilesMaxPerWorldPerRun = 2000;

    // ==========================================================================
    //                              VISUALS
    // ==========================================================================
    
    @ConfigEntry.Category("visuals")
    @ConfigEntry.Gui.Tooltip(count = 1)
    public boolean showTargetLines = true;
    
    @ConfigEntry.Gui.Tooltip(count = 1)
    public boolean showLevelParticles = true;
    
    @ConfigEntry.Gui.Tooltip(count = 1)
    public boolean disableParticles = false;
    
    @ConfigEntry.BoundedDiscrete(min = 0, max = 144)
    @ConfigEntry.Gui.Tooltip(count = 2)
    public int minFpsForVisuals = 30;
    
    @ConfigEntry.BoundedDiscrete(min = 1, max = 50)
    @ConfigEntry.Gui.Tooltip(count = 1)
    public int maxParticlesPerConnection = 8;
    
    @ConfigEntry.BoundedDiscrete(min = 1, max = 100)
    @ConfigEntry.Gui.Tooltip(count = 1)
    public int maxDrawnMinionConnections = 15;

    // Health plates (name + HP bar + level) above nearby mobs, and the
    // right-click inspect panel / spectator info overlay.
    @ConfigEntry.Category("visuals")
    public boolean showHealthPlates = true;

    @ConfigEntry.Category("visuals")
    @ConfigEntry.BoundedDiscrete(min = 4, max = 32)
    public int healthPlateRange = 10;

    // ==========================================================================
    //                               DEBUG
    // ==========================================================================
    
    @ConfigEntry.Category("debug")
    @ConfigEntry.Gui.Tooltip(count = 2)
    public boolean debugUpgradeLog = false;
    
    @ConfigEntry.Gui.Tooltip(count = 2)
    public boolean debugLogging = false;

    // ==========================================================================
    //                             MOB LISTS
    // ==========================================================================
    
    @ConfigEntry.Category("mobs")
    @ConfigEntry.Gui.Tooltip(count = 2)
    public List<String> excludedMobs = new ArrayList<>();
    
    @ConfigEntry.Gui.Excluded
    public List<String> specialMobs = Arrays.asList("witch", "creeper", "cave_spider");

    // ==========================================================================
    //                        EQUIPMENT TIERS (Hidden)
    // ==========================================================================
    
    @ConfigEntry.Gui.Excluded
    public List<String> swordTiers = Arrays.asList(
        "minecraft:wooden_sword", 
        "minecraft:stone_sword", 
        "minecraft:iron_sword", 
        "minecraft:diamond_sword", 
        "minecraft:netherite_sword"
    );
    
    @ConfigEntry.Gui.Excluded
    public List<String> goldSwordTiers = Arrays.asList(
        "minecraft:golden_sword", 
        "minecraft:netherite_sword"
    );
    
    @ConfigEntry.Gui.Excluded
    public List<String> axeTiers = Arrays.asList(
        "minecraft:wooden_axe", 
        "minecraft:stone_axe", 
        "minecraft:iron_axe", 
        "minecraft:diamond_axe", 
        "minecraft:netherite_axe"
    );
    
    @ConfigEntry.Gui.Excluded
    public List<String> goldAxeTiers = Arrays.asList(
        "minecraft:golden_axe", 
        "minecraft:netherite_axe"
    );
    
    @ConfigEntry.Gui.Excluded
    public List<String> helmetTiers = Arrays.asList(
        "minecraft:leather_helmet", 
        "minecraft:chainmail_helmet", 
        "minecraft:iron_helmet", 
        "minecraft:diamond_helmet", 
        "minecraft:netherite_helmet"
    );
    
    @ConfigEntry.Gui.Excluded
    public List<String> chestTiers = Arrays.asList(
        "minecraft:leather_chestplate", 
        "minecraft:chainmail_chestplate", 
        "minecraft:iron_chestplate", 
        "minecraft:diamond_chestplate", 
        "minecraft:netherite_chestplate"
    );
    
    @ConfigEntry.Gui.Excluded
    public List<String> legsTiers = Arrays.asList(
        "minecraft:leather_leggings", 
        "minecraft:chainmail_leggings", 
        "minecraft:iron_leggings", 
        "minecraft:diamond_leggings", 
        "minecraft:netherite_leggings"
    );
    
    @ConfigEntry.Gui.Excluded
    public List<String> bootsTiers = Arrays.asList(
        "minecraft:leather_boots", 
        "minecraft:chainmail_boots", 
        "minecraft:iron_boots", 
        "minecraft:diamond_boots", 
        "minecraft:netherite_boots"
    );
    
    @ConfigEntry.Gui.Excluded
    public List<String> goldHelmetTiers = Arrays.asList(
        "minecraft:golden_helmet", 
        "minecraft:netherite_helmet"
    );
    
    @ConfigEntry.Gui.Excluded
    public List<String> goldChestTiers = Arrays.asList(
        "minecraft:golden_chestplate", 
        "minecraft:netherite_chestplate"
    );
    
    @ConfigEntry.Gui.Excluded
    public List<String> goldLegsTiers = Arrays.asList(
        "minecraft:golden_leggings", 
        "minecraft:netherite_leggings"
    );
    
    @ConfigEntry.Gui.Excluded
    public List<String> goldBootsTiers = Arrays.asList(
        "minecraft:golden_boots", 
        "minecraft:netherite_boots"
    );

    // ==========================================================================
    //                              INTERNAL
    // ==========================================================================
    
    private static ConfigHolder<ModConfig> holder;
    private static ModConfig fallbackInstance;

    public static ModConfig getInstance() {
        if (holder == null) {
            try {
                holder = AutoConfig.getConfigHolder(ModConfig.class);
            } catch (RuntimeException notRegistered) {
                // AutoConfig.getConfigHolder throws when the config has not
                // been registered yet. Serve a defaults instance instead of
                // crashing the caller; the real config takes over as soon as
                // AdvancedMobsMod.onInitialize registers it.
                if (fallbackInstance == null) {
                    fallbackInstance = new ModConfig();
                }
                return fallbackInstance;
            }
        }
        return holder.getConfig();
    }

    /**
     * Force reload config values from disk immediately.
     * Useful for applying settings like manualWorldDayOverride without restarting.
     */
    public static ModConfig reload() {
        try {
            if (holder == null) {
                holder = AutoConfig.getConfigHolder(ModConfig.class);
            }
            holder.load();
        } catch (Exception ignored) {
            // Best-effort: keep current config instance if reload fails.
            return getInstance();
        }
        return holder.getConfig();
    }

    public static void save() {
        if (holder != null) holder.save();
    }

    // ==========================================================================
    //                           HELPER METHODS
    // ==========================================================================
    
    public boolean isMobExcluded(String entityTypeId) {
        return excludedMobs.contains(entityTypeId);
    }

    public void addExcludedMob(String entityTypeId) {
        if (!excludedMobs.contains(entityTypeId)) {
            excludedMobs.add(entityTypeId);
            save();
        }
    }

    public void removeExcludedMob(String entityTypeId) {
        if (excludedMobs.remove(entityTypeId)) {
            save();
        }
    }
    
    // Convenience getters that convert percent to decimal
    public double getRangeMultiplier() {
        return rangeMultiplierPercent / 100.0;
    }
    
    public double getDayScalingMultiplier() {
        return dayScalingMultiplierPercent / 100.0;
    }
    
    public double getKillScalingMultiplier() {
        return killScalingMultiplierPercent / 100.0;
    }
    
    public double getWarlordHealthMultiplier() {
        return warlordHealthMultiplierPercent / 100.0;
    }
    
    public double getWarlordDamageMultiplier() {
        return warlordDamageMultiplierPercent / 100.0;
    }
    
    public double getBuyChance() {
        return buyChancePercent / 100.0;
    }
    
    public double getSaveChance() {
        return saveChancePercent / 100.0;
    }

    public long getUpgradeIntervalTicks() {
        return Math.max(20L, Math.min(480000L, (long) upgradeIntervalTicks));
    }

    public int getMaxSpawnUpgradeIterations() {
        return Math.max(MIN_UPGRADE_ITERATIONS, Math.min(20000, maxSpawnUpgradeIterations));
    }

    public int getMaxUpgradeIterations() {
        return Math.max(MIN_UPGRADE_ITERATIONS, Math.min(MAX_UPGRADE_ITERATIONS, maxUpgradeIterations));
    }

    public int getMaxConcurrentUpgradeJobs() {
        return Math.max(1, Math.min(256, maxConcurrentUpgradeJobs));
    }
    
    // Section enable checks
    public boolean isTargetingActive() {
        return modEnabled && targetingEnabled;
    }
    
    public boolean isAllianceActive() {
        return modEnabled && allianceEnabled;
    }
    
    public boolean isScalingActive() {
        return modEnabled && scalingEnabled;
    }
    
    public boolean isWarlordActive() {
        return modEnabled && warlordEnabled;
    }
    
    // ==========================================================================
    //                         LEGACY COMPATIBILITY
    // These fields maintain compatibility with older code
    // ==========================================================================
    
    @ConfigEntry.Gui.Excluded
    public transient boolean evolutionSystemEnabled = true; // Maps to scalingEnabled
    
    @ConfigEntry.Gui.Excluded  
    public transient boolean allianceSystemEnabled = true; // Maps to allianceEnabled
    
    @ConfigEntry.Gui.Excluded
    public transient boolean enableMobWarlord = true; // Maps to warlordEnabled
    
    @ConfigEntry.Gui.Excluded
    public transient double rangeMultiplier = 1.0; // Maps to getRangeMultiplier()
    
    @ConfigEntry.Gui.Excluded
    public transient double dayScalingMultiplier = 1.0;
    
    @ConfigEntry.Gui.Excluded
    public transient double killScalingMultiplier = 1.0;
    
    @ConfigEntry.Gui.Excluded
    public transient double warlordHealthMultiplier = 3.0;
    
    @ConfigEntry.Gui.Excluded
    public transient double warlordDamageMultiplier = 2.0;
    
    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double sanitizeRate(double value, double fallback) {
        return (Double.isFinite(value) && value >= 0.0) ? value : fallback;
    }

    private static double clampFraction(double value, double fallback) {
        if (!Double.isFinite(value) || value < 0.0) return fallback;
        return Math.min(1.0, value);
    }

    // Sanitize, then sync legacy fields on load
    @Override
    public void validatePostLoad() throws ValidationException {
        // Values come from a JSON file users can hand edit, and several
        // systems trust them raw. A NaN abilityFullPowerPoints makes Gson
        // throw on the next save and poisons the ability math; negative
        // alliance durations expire every alliance instantly; percents
        // outside 0-100 skew the spending lottery. Clamp everything to its
        // documented range here, the one place every load passes through.
        if (!Double.isFinite(abilityFullPowerPoints) || abilityFullPowerPoints < 1.0) {
            abilityFullPowerPoints = 3000.0;
        }
        bossCombatPointsPerSecondDragon = sanitizeRate(bossCombatPointsPerSecondDragon, 25.0);
        bossCombatPointsPerSecondWither = sanitizeRate(bossCombatPointsPerSecondWither, 15.0);
        bossCombatPointsPerSecondWarlord = sanitizeRate(bossCombatPointsPerSecondWarlord, 15.0);
        bossCombatPointsPerSecondWarden = sanitizeRate(bossCombatPointsPerSecondWarden, 10.0);
        bossCombatPointsPerSecondElderGuardian = sanitizeRate(bossCombatPointsPerSecondElderGuardian, 10.0);
        combatRageGraceSeconds = sanitizeRate(combatRageGraceSeconds, 20.0);
        combatRageRampSeconds = sanitizeRate(combatRageRampSeconds, 70.0);
        if (!Double.isFinite(combatRageMaxDamageMultiplier) || combatRageMaxDamageMultiplier < 1.0) {
            combatRageMaxDamageMultiplier = 3.0;
        }
        combatRageMaxSpeedBonus = sanitizeRate(combatRageMaxSpeedBonus, 0.35);
        combatRageWaveStartSeconds = sanitizeRate(combatRageWaveStartSeconds, 90.0);
        if (!Double.isFinite(combatRageWaveIntervalSeconds) || combatRageWaveIntervalSeconds < 1.0) {
            combatRageWaveIntervalSeconds = 12.0;
        }
        combatRageWaveBaseFraction = clampFraction(combatRageWaveBaseFraction, 0.05);
        combatRageWaveGrowthFraction = sanitizeRate(combatRageWaveGrowthFraction, 0.012);
        combatRageWaveFractionCap = clampFraction(combatRageWaveFractionCap, 0.25);
        combatRageWaveJitter = clampFraction(combatRageWaveJitter, 0.15);
        combatRageDecaySeconds = sanitizeRate(combatRageDecaySeconds, 8.0);

        buyChancePercent = clampInt(buyChancePercent, 0, 100);
        saveChancePercent = clampInt(saveChancePercent, 0, 100);
        allianceBreakChancePercent = clampInt(allianceBreakChancePercent, 0, 100);
        strongAllianceBreakChancePercent = clampInt(strongAllianceBreakChancePercent, 0, 100);
        warlordSpawnChance = clampInt(warlordSpawnChance, 0, 100);
        xpBonusPercentPerStep = clampInt(xpBonusPercentPerStep, 0, 1000);
        rangeMultiplierPercent = clampInt(rangeMultiplierPercent, 0, 1000);
        dayScalingMultiplierPercent = clampInt(dayScalingMultiplierPercent, 0, 1000);
        killScalingMultiplierPercent = clampInt(killScalingMultiplierPercent, 0, 1000);
        warlordHealthMultiplierPercent = clampInt(warlordHealthMultiplierPercent, 1, 10000);
        warlordDamageMultiplierPercent = clampInt(warlordDamageMultiplierPercent, 1, 10000);

        weakAllianceDurationMs = Math.max(0, weakAllianceDurationMs);
        strongAllianceDurationMs = Math.max(0, strongAllianceDurationMs);
        spentPointsPerXpBonusStep = Math.max(1, spentPointsPerXpBonusStep);
        upgradeIntervalTicks = clampInt(upgradeIntervalTicks, 20, 480000);
        maxSpawnUpgradeIterations = clampInt(maxSpawnUpgradeIterations, 0, 20000);
        naturalSpawnCapPerSimulationDistance = Math.max(0, naturalSpawnCapPerSimulationDistance);
        warlordMinRaidLevel = clampInt(warlordMinRaidLevel, 1, 10);
        warlordMinionCount = clampInt(warlordMinionCount, 0, 64);
        maxConcurrentUpgradeJobs = clampInt(maxConcurrentUpgradeJobs, 1, 256);
        upgradeProcessingTimeMs = clampInt(upgradeProcessingTimeMs, 100, 60000);
        targetingCacheMs = clampInt(targetingCacheMs, 0, 60000);
        targetingMaxQueriesPerTick = clampInt(targetingMaxQueriesPerTick, 1, 10000);
        mobDataSaveDebounceMs = clampInt(mobDataSaveDebounceMs, 0, 60000);
        minFpsForVisuals = clampInt(minFpsForVisuals, 0, 240);
        healthPlateRange = clampInt(healthPlateRange, 4, 32);
        maxParticlesPerConnection = clampInt(maxParticlesPerConnection, 0, 1000);
        maxDrawnMinionConnections = clampInt(maxDrawnMinionConnections, 0, 256);
        cleanupNonPlayerGroundProjectilesIntervalSeconds = clampInt(cleanupNonPlayerGroundProjectilesIntervalSeconds, 1, 3600);
        cleanupNonPlayerGroundProjectilesMinAgeTicks = clampInt(cleanupNonPlayerGroundProjectilesMinAgeTicks, 0, 72000);
        cleanupNonPlayerGroundProjectilesMaxPerWorldPerRun = clampInt(cleanupNonPlayerGroundProjectilesMaxPerWorldPerRun, 1, 10000);

        // A JSON null in any list field would NPE its consumers (for
        // example isMobExcluded inside the ENTITY_LOAD handler). Normalize.
        if (excludedMobs == null) excludedMobs = new ArrayList<>();
        if (specialMobs == null) specialMobs = new ArrayList<>();
        if (swordTiers == null) swordTiers = new ArrayList<>();
        if (goldSwordTiers == null) goldSwordTiers = new ArrayList<>();
        if (axeTiers == null) axeTiers = new ArrayList<>();
        if (goldAxeTiers == null) goldAxeTiers = new ArrayList<>();
        if (helmetTiers == null) helmetTiers = new ArrayList<>();
        if (chestTiers == null) chestTiers = new ArrayList<>();
        if (legsTiers == null) legsTiers = new ArrayList<>();
        if (bootsTiers == null) bootsTiers = new ArrayList<>();
        if (goldHelmetTiers == null) goldHelmetTiers = new ArrayList<>();
        if (goldChestTiers == null) goldChestTiers = new ArrayList<>();
        if (goldLegsTiers == null) goldLegsTiers = new ArrayList<>();
        if (goldBootsTiers == null) goldBootsTiers = new ArrayList<>();

        evolutionSystemEnabled = scalingEnabled;
        allianceSystemEnabled = allianceEnabled;
        enableMobWarlord = warlordEnabled;
        rangeMultiplier = getRangeMultiplier();
        dayScalingMultiplier = getDayScalingMultiplier();
        killScalingMultiplier = getKillScalingMultiplier();
        warlordHealthMultiplier = getWarlordHealthMultiplier();
        warlordDamageMultiplier = getWarlordDamageMultiplier();
        manualWorldDayOverride = Math.max(-1, Math.min(100000, manualWorldDayOverride));
        maxUpgradeIterations = getMaxUpgradeIterations();
    }
}
