package mod.advancedmobs.client;

import mod.advancedmobs.config.ModConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Universal Mob War settings screen, rebuilt from scratch as a native
 * Screen (the previous version delegated everything to Cloth Config;
 * this one owns its layout so the menu is fully ours).
 *
 * Layout: category rail on the left (General, Targeting, Alliance,
 * Scaling, Abilities, Warlord, Performance, Visuals, Debug), a
 * scrolling list of settings on the right. Booleans are ON/OFF buttons;
 * numbers are an edit box flanked by - / + steppers. Every control is
 * bound to the same ModConfig fields (including the mirror fields other
 * systems read, like rangeMultiplier mirroring rangeMultiplierPercent)
 * and applies live; the config is persisted with ModConfig.save() when
 * the screen closes. Percent fields are edited as whole percents
 * (100 = 1.0x), matching how ModConfig stores them.
 */
public class AdvancedMobsConfigScreen extends Screen {

    private enum Kind { BOOL, INT, DOUBLE }

    private record Setting(String label, String tooltip, Kind kind,
                           Supplier<Object> getter, Consumer<Object> setter,
                           double min, double max) {
        static Setting bool(String label, String tooltip, BooleanSupplier get, Consumer<Boolean> set) {
            return new Setting(label, tooltip, Kind.BOOL, get::getAsBoolean, v -> set.accept((Boolean) v), 0, 0);
        }

        static Setting integer(String label, String tooltip, IntSupplier get, IntConsumer set, int min, int max) {
            return new Setting(label, tooltip, Kind.INT, get::getAsInt, v -> set.accept((Integer) v), min, max);
        }

        static Setting dbl(String label, String tooltip, DoubleSupplier get, DoubleConsumer set, double min, double max) {
            return new Setting(label, tooltip, Kind.DOUBLE, get::getAsDouble, v -> set.accept((Double) v), min, max);
        }
    }

    private record Category(String name, List<Setting> settings) {}

    private static final int SIDEBAR_WIDTH = 116;
    private static final int CONTENT_TOP = 44;
    private static final int ROW_HEIGHT = 26;
    private static final int FOOTER_HEIGHT = 36;

    private final Screen parent;
    private final List<Category> categories;
    private int categoryIndex = 0;
    private int scrollRow = 0;
    private boolean building = false;

    public AdvancedMobsConfigScreen(Screen parent) {
        super(Component.literal("Universal Mob War Settings"));
        this.parent = parent;
        this.categories = buildCategories(ModConfig.getInstance());
    }

    public static Screen create(Screen parent) {
        return new AdvancedMobsConfigScreen(parent);
    }

    // ======================================================================
    //                              SETTINGS
    // ======================================================================

    private static List<Category> buildCategories(ModConfig config) {
        List<Category> categories = new ArrayList<>();

        categories.add(new Category("General", List.of(
            Setting.bool("Enable Mod", "Master switch for every system in Universal Mob War.",
                () -> config.modEnabled, v -> config.modEnabled = v),
            Setting.bool("Targeting System", "Controls mob-vs-mob combat behavior.",
                () -> config.targetingEnabled, v -> config.targetingEnabled = v),
            Setting.bool("Alliance System", "Let mobs form temporary alliances.",
                () -> config.allianceEnabled, v -> { config.allianceEnabled = v; config.allianceSystemEnabled = v; }),
            Setting.bool("Scaling System", "Allow mobs to earn and spend upgrade points.",
                () -> config.scalingEnabled, v -> { config.scalingEnabled = v; config.evolutionSystemEnabled = v; }),
            Setting.bool("Warlord System", "Enable the Mob Warlord raid boss feature.",
                () -> config.warlordEnabled, v -> { config.warlordEnabled = v; config.enableMobWarlord = v; })
        )));

        categories.add(new Category("Targeting", List.of(
            Setting.bool("Target Players", "If disabled, mobs focus entirely on other mobs.",
                () -> config.targetPlayers, v -> config.targetPlayers = v),
            Setting.bool("Ignore Same Species", "Prevents zombies from fighting other zombies, etc.",
                () -> config.ignoreSameSpecies, v -> config.ignoreSameSpecies = v),
            Setting.bool("Neutral Mobs Always Aggressive", "Forces golems, wolves, and other neutrals into the war.",
                () -> config.neutralMobsAlwaysAggressive, v -> config.neutralMobsAlwaysAggressive = v),
            Setting.bool("Disable Natural Spawns", "Completely blocks passive and hostile natural spawns.",
                () -> config.disableNaturalMobSpawns, v -> config.disableNaturalMobSpawns = v),
            Setting.bool("Limit Natural Spawns", "Caps natural spawns per simulation distance.",
                () -> config.limitNaturalMobSpawns, v -> config.limitNaturalMobSpawns = v),
            Setting.integer("Natural Spawn Cap", "Spawn cap = this value times the simulation distance.",
                () -> config.naturalSpawnCapPerSimulationDistance, v -> config.naturalSpawnCapPerSimulationDistance = v, 0, 1000),
            Setting.integer("Detection Range %", "100 = normal detection range, 200 = double.",
                () -> config.rangeMultiplierPercent, v -> { config.rangeMultiplierPercent = v; config.rangeMultiplier = v / 100.0; }, 10, 500),
            Setting.integer("Target Cache (ms)", "How long a mob keeps a picked target before re-checking.",
                () -> config.targetingCacheMs, v -> config.targetingCacheMs = v, 500, 5000),
            Setting.integer("Target Queries / Tick", "Budget for target searches each tick.",
                () -> config.targetingMaxQueriesPerTick, v -> config.targetingMaxQueriesPerTick = v, 10, 200)
        )));

        categories.add(new Category("Alliance", List.of(
            Setting.bool("Alliance System", "Let mobs form temporary alliances.",
                () -> config.allianceEnabled, v -> { config.allianceEnabled = v; config.allianceSystemEnabled = v; }),
            Setting.integer("Weak Alliance Duration (ms)", "How long a cross-species alliance lasts.",
                () -> config.weakAllianceDurationMs, v -> config.weakAllianceDurationMs = v, 1000, 60000),
            Setting.integer("Strong Alliance Duration (ms)", "How long a same-species alliance lasts.",
                () -> config.strongAllianceDurationMs, v -> config.strongAllianceDurationMs = v, 1000, 120000),
            Setting.integer("Weak Break Chance %", "Chance a weak alliance breaks early.",
                () -> config.allianceBreakChancePercent, v -> config.allianceBreakChancePercent = v, 0, 100),
            Setting.integer("Strong Break Chance %", "Chance a strong alliance breaks early.",
                () -> config.strongAllianceBreakChancePercent, v -> config.strongAllianceBreakChancePercent = v, 0, 100)
        )));

        categories.add(new Category("Scaling", List.of(
            Setting.bool("Scaling System", "Allow mobs to earn and spend upgrade points.",
                () -> config.scalingEnabled, v -> { config.scalingEnabled = v; config.evolutionSystemEnabled = v; }),
            Setting.bool("Allow Boss Scaling", "Bosses obey the same progression rules.",
                () -> config.allowBossScaling, v -> config.allowBossScaling = v),
            Setting.integer("Day Scaling %", "Points from world age. 100 = normal rate.",
                () -> config.dayScalingMultiplierPercent, v -> { config.dayScalingMultiplierPercent = v; config.dayScalingMultiplier = v / 100.0; }, 0, 1000),
            Setting.integer("Kill Scaling %", "Points from kills. 100 = normal rate.",
                () -> config.killScalingMultiplierPercent, v -> { config.killScalingMultiplierPercent = v; config.killScalingMultiplier = v / 100.0; }, 0, 1000),
            Setting.integer("Save Chance %", "Chance a mob banks its points instead of spending. Buy chance is the rest.",
                () -> config.saveChancePercent, v -> { config.saveChancePercent = v; config.buyChancePercent = 100 - v; }, 0, 100),
            Setting.integer("Max Upgrades / Cycle", "Hard cap on purchases in one spending cycle.",
                () -> config.maxUpgradeIterations, v -> config.maxUpgradeIterations = v,
                ModConfig.MIN_UPGRADE_ITERATIONS, ModConfig.MAX_UPGRADE_ITERATIONS),
            Setting.integer("Manual Day Override", "Pretend the world has run for this many days. -1 uses the real count.",
                () -> config.manualWorldDayOverride, v -> config.manualWorldDayOverride = v, -1, 100000),
            Setting.integer("Upgrade Interval (ticks)", "How often mobs run a spending cycle. 24000 ticks = one in-game day.",
                () -> config.upgradeIntervalTicks, v -> config.upgradeIntervalTicks = v, 200, 720000),
            Setting.bool("Scale XP Drops With Spent Points", "Mobs that spent more points drop more XP.",
                () -> config.scaleMobXpDropsWithSpentPoints, v -> config.scaleMobXpDropsWithSpentPoints = v),
            Setting.bool("Spend All Points On Spawn", "New mobs immediately spend their starting points.",
                () -> config.forceSpendAllOnSpawn, v -> config.forceSpendAllOnSpawn = v),
            Setting.bool("Allow Modded Enchantments", "Let mob gear roll enchantments from other mods.",
                () -> config.allowModdedEnchantments, v -> config.allowModdedEnchantments = v),
            Setting.bool("Fight Rage", "While a fight lasts, every mob ramps the same temporary damage and speed bonus, then takes growing end waves of its own max health. It fades when the fight stops.",
                () -> config.combatRageEnabled, v -> config.combatRageEnabled = v),
            Setting.dbl("Rage Grace (seconds)", "How long a fight runs before the ramp starts.",
                () -> config.combatRageGraceSeconds, v -> config.combatRageGraceSeconds = v, 0.0, 600.0),
            Setting.dbl("Rage Ramp (seconds)", "How long the ramp takes to reach full damage and speed.",
                () -> config.combatRageRampSeconds, v -> config.combatRageRampSeconds = v, 1.0, 600.0),
            Setting.dbl("Rage Max Damage", "Damage multiplier at full rage. 3 means triple damage.",
                () -> config.combatRageMaxDamageMultiplier, v -> config.combatRageMaxDamageMultiplier = v, 1.0, 20.0),
            Setting.dbl("Rage Max Speed", "Movement speed bonus at full rage. 0.35 is +35%.",
                () -> config.combatRageMaxSpeedBonus, v -> config.combatRageMaxSpeedBonus = v, 0.0, 3.0),
            Setting.dbl("End Wave Start (seconds)", "When the finishing waves begin.",
                () -> config.combatRageWaveStartSeconds, v -> config.combatRageWaveStartSeconds = v, 0.0, 600.0),
            Setting.dbl("End Wave Interval (seconds)", "Time between finishing waves.",
                () -> config.combatRageWaveIntervalSeconds, v -> config.combatRageWaveIntervalSeconds = v, 1.0, 120.0),
            Setting.dbl("End Wave Base", "First wave, as a fraction of that mob's max health.",
                () -> config.combatRageWaveBaseFraction, v -> config.combatRageWaveBaseFraction = v, 0.0, 1.0),
            Setting.dbl("End Wave Growth", "Extra max-health fraction added for each wave after the first.",
                () -> config.combatRageWaveGrowthFraction, v -> config.combatRageWaveGrowthFraction = v, 0.0, 1.0),
            Setting.dbl("End Wave Cap", "Most a single wave can take, as a fraction of max health.",
                () -> config.combatRageWaveFractionCap, v -> config.combatRageWaveFractionCap = v, 0.0, 1.0),
            Setting.dbl("End Wave Randomness", "How far each wave can swing around that fraction. 0.15 is plus or minus 15%.",
                () -> config.combatRageWaveJitter, v -> config.combatRageWaveJitter = v, 0.0, 1.0),
            Setting.dbl("Rage Fade (seconds)", "How long after combat the temporary rage lasts.",
                () -> config.combatRageDecaySeconds, v -> config.combatRageDecaySeconds = v, 0.0, 120.0)
        )));

        categories.add(new Category("Abilities", List.of(
            Setting.bool("Enable Abilities", "Master switch for the special abilities every mob can earn.",
                () -> config.abilitiesEnabled, v -> config.abilitiesEnabled = v),
            Setting.dbl("Full Power Points", "Lifetime points at which a mob counts as full power for ability access. Lower unlocks abilities faster.",
                () -> config.abilityFullPowerPoints, v -> config.abilityFullPowerPoints = v, 1.0, 1000000.0),
            Setting.dbl("Boss Points / Sec: Ender Dragon", "Points the Ender Dragon earns per second while in combat.",
                () -> config.bossCombatPointsPerSecondDragon, v -> config.bossCombatPointsPerSecondDragon = v, 0.0, 1000.0),
            Setting.dbl("Boss Points / Sec: Wither", "Points the Wither earns per second while in combat.",
                () -> config.bossCombatPointsPerSecondWither, v -> config.bossCombatPointsPerSecondWither = v, 0.0, 1000.0),
            Setting.dbl("Boss Points / Sec: Mob Warlord", "Points the Mob Warlord earns per second while in combat.",
                () -> config.bossCombatPointsPerSecondWarlord, v -> config.bossCombatPointsPerSecondWarlord = v, 0.0, 1000.0),
            Setting.dbl("Boss Points / Sec: Warden", "Points the Warden earns per second while in combat.",
                () -> config.bossCombatPointsPerSecondWarden, v -> config.bossCombatPointsPerSecondWarden = v, 0.0, 1000.0),
            Setting.dbl("Boss Points / Sec: Elder Guardian", "Points the Elder Guardian earns per second while in combat.",
                () -> config.bossCombatPointsPerSecondElderGuardian, v -> config.bossCombatPointsPerSecondElderGuardian = v, 0.0, 1000.0)
        )));

        categories.add(new Category("Warlord", List.of(
            Setting.bool("Warlord System", "Enable the Mob Warlord raid boss feature.",
                () -> config.warlordEnabled, v -> { config.warlordEnabled = v; config.enableMobWarlord = v; }),
            Setting.bool("Always Spawn On Final Wave", "Forces every final raid wave to include the Warlord.",
                () -> config.alwaysSpawnWarlordOnFinalWave, v -> config.alwaysSpawnWarlordOnFinalWave = v),
            Setting.integer("Spawn Chance %", "Chance the Warlord joins a qualifying raid wave.",
                () -> config.warlordSpawnChance, v -> config.warlordSpawnChance = v, 0, 100),
            Setting.integer("Min Raid Level", "Lowest raid level that can spawn the Warlord.",
                () -> config.warlordMinRaidLevel, v -> config.warlordMinRaidLevel = v, 1, 20),
            Setting.integer("Minion Count", "How many minions the Warlord brings.",
                () -> config.warlordMinionCount, v -> config.warlordMinionCount = v, 5, 100),
            Setting.integer("Health %", "Warlord health multiplier. 300 = 3x health.",
                () -> config.warlordHealthMultiplierPercent, v -> { config.warlordHealthMultiplierPercent = v; config.warlordHealthMultiplier = v / 100.0; }, 100, 1000),
            Setting.integer("Damage %", "Warlord damage multiplier. 200 = 2x damage.",
                () -> config.warlordDamageMultiplierPercent, v -> { config.warlordDamageMultiplierPercent = v; config.warlordDamageMultiplier = v / 100.0; }, 100, 1000)
        )));

        categories.add(new Category("Performance", List.of(
            Setting.bool("Performance Mode", "Cuts visuals and batching for weaker machines.",
                () -> config.performanceMode, v -> config.performanceMode = v),
            Setting.bool("Enable Batching", "Batch upgrade tasks to reduce tick spikes.",
                () -> config.enableBatching, v -> config.enableBatching = v),
            Setting.bool("Enable Async Tasks", "Move heavy calculations off the main thread.",
                () -> config.enableAsyncTasks, v -> config.enableAsyncTasks = v),
            Setting.integer("Concurrent Upgrades", "How many upgrade jobs may run at once.",
                () -> config.maxConcurrentUpgradeJobs, v -> config.maxConcurrentUpgradeJobs = v, 1, 256),
            Setting.integer("Upgrade Window (ms)", "Time budget for processing upgrades each cycle.",
                () -> config.upgradeProcessingTimeMs, v -> config.upgradeProcessingTimeMs = v, 1000, 30000),
            Setting.bool("Clean Up Stuck Projectiles", "Periodically removes old arrows and tridents stuck in the ground.",
                () -> config.cleanupNonPlayerGroundProjectiles, v -> config.cleanupNonPlayerGroundProjectiles = v),
            Setting.integer("Projectile Cleanup Interval (s)", "How often the projectile cleanup runs.",
                () -> config.cleanupNonPlayerGroundProjectilesIntervalSeconds, v -> config.cleanupNonPlayerGroundProjectilesIntervalSeconds = v, 5, 120)
        )));

        categories.add(new Category("Visuals", List.of(
            Setting.bool("Show Health Plates", "Name, level and HP bar above mobs near you.",
                () -> config.showHealthPlates, v -> config.showHealthPlates = v),
            Setting.integer("Health Plate Range", "Blocks within which health plates are drawn.",
                () -> config.healthPlateRange, v -> config.healthPlateRange = v, 4, 32),
            Setting.bool("Disable Particles", "Removes most particles for better FPS.",
                () -> config.disableParticles, v -> config.disableParticles = v),
            Setting.bool("Show Target Lines", "Draw lines between mobs and their targets.",
                () -> config.showTargetLines, v -> config.showTargetLines = v),
            Setting.bool("Level Up Particles", "Play particles when mobs level up.",
                () -> config.showLevelParticles, v -> config.showLevelParticles = v),
            Setting.integer("Minimum FPS For Visuals", "Visuals pause below this frame rate.",
                () -> config.minFpsForVisuals, v -> config.minFpsForVisuals = v, 0, 144),
            Setting.integer("Particles / Connection", "Particle budget per drawn connection.",
                () -> config.maxParticlesPerConnection, v -> config.maxParticlesPerConnection = v, 1, 50),
            Setting.integer("Minion Connections Drawn", "How many warlord minion links are drawn at once.",
                () -> config.maxDrawnMinionConnections, v -> config.maxDrawnMinionConnections = v, 1, 100)
        )));

        categories.add(new Category("Debug", List.of(
            Setting.bool("Upgrade Chat Log", "Print every upgrade decision in chat while testing.",
                () -> config.debugUpgradeLog, v -> config.debugUpgradeLog = v),
            Setting.bool("Verbose Console Logging", "Spam-level logs for profiling and bug reports. Only toggle when needed.",
                () -> config.debugLogging, v -> config.debugLogging = v)
        )));

        return categories;
    }

    // ======================================================================
    //                              LAYOUT
    // ======================================================================

    @Override
    protected void init() {
        rebuildWidgets();
    }

    private int contentLeft() {
        return SIDEBAR_WIDTH + 16;
    }

    private int visibleRows() {
        return Math.max(1, (this.height - CONTENT_TOP - FOOTER_HEIGHT) / ROW_HEIGHT);
    }

    @Override
    protected void rebuildWidgets() {
        building = true;
        clearWidgets();

        // Category rail.
        for (int i = 0; i < categories.size(); i++) {
            final int index = i;
            Button button = Button.builder(Component.literal(categories.get(i).name()), b -> {
                categoryIndex = index;
                scrollRow = 0;
                rebuildWidgets();
            }).bounds(8, CONTENT_TOP + i * 24, SIDEBAR_WIDTH - 8, 20).build();
            button.active = index != categoryIndex;
            addRenderableWidget(button);
        }

        // Done button.
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
            .bounds(this.width - 108, this.height - 28, 100, 20)
            .build());

        // Settings rows for the current category.
        List<Setting> settings = categories.get(categoryIndex).settings();
        int rows = visibleRows();
        for (int row = 0; row < rows; row++) {
            int settingIndex = scrollRow + row;
            if (settingIndex >= settings.size()) {
                break;
            }
            buildRowWidgets(settings.get(settingIndex), CONTENT_TOP + row * ROW_HEIGHT);
        }
        building = false;
    }

    private void buildRowWidgets(Setting setting, int y) {
        int right = this.width - 12;
        switch (setting.kind()) {
            case BOOL -> {
                boolean current = (Boolean) setting.getter().get();
                Button[] holder = new Button[1];
                Button button = Button.builder(Component.literal(current ? "ON" : "OFF"), b -> {
                    boolean newValue = !(Boolean) setting.getter().get();
                    setting.setter().accept(newValue);
                    holder[0].setMessage(Component.literal(newValue ? "ON" : "OFF"));
                }).bounds(right - 70, y + 3, 70, 20).build();
                holder[0] = button;
                addRenderableWidget(button);
            }
            case INT, DOUBLE -> {
                boolean isInt = setting.kind() == Kind.INT;
                double step = isInt ? Math.max(1, (setting.max() - setting.min()) / 100.0) : 1.0;
                EditBox box = new EditBox(this.font, right - 118, y + 3, 72, 20, Component.literal(setting.label()));
                box.setMaxLength(18);
                box.setValue(formatValue(setting));
                box.setResponder(text -> {
                    if (building) {
                        return;
                    }
                    applyTextValue(setting, text);
                });
                addRenderableWidget(box);
                addRenderableWidget(Button.builder(Component.literal("-"), b -> stepValue(setting, -step, box))
                    .bounds(right - 142, y + 3, 20, 20).build());
                addRenderableWidget(Button.builder(Component.literal("+"), b -> stepValue(setting, step, box))
                    .bounds(right - 42, y + 3, 20, 20).build());
            }
        }
    }

    private String formatValue(Setting setting) {
        Object value = setting.getter().get();
        if (value instanceof Double d) {
            return d == Math.floor(d) ? Long.toString(Math.round(d)) : Double.toString(d);
        }
        return String.valueOf(value);
    }

    private void applyTextValue(Setting setting, String text) {
        try {
            if (setting.kind() == Kind.INT) {
                int parsed = Integer.parseInt(text.trim());
                int clamped = (int) Math.max(setting.min(), Math.min(setting.max(), parsed));
                setting.setter().accept(clamped);
            } else if (setting.kind() == Kind.DOUBLE) {
                double parsed = Double.parseDouble(text.trim());
                if (Double.isFinite(parsed)) {
                    double clamped = Math.max(setting.min(), Math.min(setting.max(), parsed));
                    setting.setter().accept(clamped);
                }
            }
        } catch (NumberFormatException ignored) {
            // Mid-typing text (empty, "-", "1.") is not a value yet.
        }
    }

    private void stepValue(Setting setting, double delta, EditBox box) {
        if (setting.kind() == Kind.INT) {
            int current = (Integer) setting.getter().get();
            int next = (int) Math.max(setting.min(), Math.min(setting.max(), current + Math.round(delta)));
            setting.setter().accept(next);
        } else {
            double current = (Double) setting.getter().get();
            double next = Math.max(setting.min(), Math.min(setting.max(), current + delta));
            setting.setter().accept(next);
        }
        building = true;
        box.setValue(formatValue(setting));
        building = false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        List<Setting> settings = categories.get(categoryIndex).settings();
        int maxScroll = Math.max(0, settings.size() - visibleRows());
        int newScroll = Math.max(0, Math.min(maxScroll, scrollRow - (int) Math.signum(scrollY) * 2));
        if (newScroll != scrollRow) {
            scrollRow = newScroll;
            rebuildWidgets();
        }
        return true;
    }

    @Override
    public void onClose() {
        ModConfig.save();
        this.minecraft.setScreenAndShow(parent);
    }

    @Override
    public void removed() {
        ModConfig.save();
        super.removed();
    }

    // ======================================================================
    //                              RENDER
    // ======================================================================

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Backdrop and chrome.
        graphics.fill(0, 0, this.width, this.height, 0xE0080810);
        graphics.fill(0, 0, SIDEBAR_WIDTH + 4, this.height, 0xFF0D0D16);
        graphics.fill(SIDEBAR_WIDTH + 4, 0, SIDEBAR_WIDTH + 6, this.height, 0xFFB02A30);
        graphics.text(this.font, "UNIVERSAL MOB WAR", 8, 10, 0xFFFF4B4B);
        graphics.text(this.font, "Settings", 8, 22, 0xFF888888);
        graphics.text(this.font, categories.get(categoryIndex).name(), contentLeft(), 12, 0xFFFFFFFF);
        graphics.text(this.font, "Changes apply live and save when this screen closes.",
            contentLeft(), 24, 0xFF888888);

        // Row labels and hover tooltip.
        List<Setting> settings = categories.get(categoryIndex).settings();
        String hoveredTooltip = null;
        int rows = visibleRows();
        for (int row = 0; row < rows; row++) {
            int settingIndex = scrollRow + row;
            if (settingIndex >= settings.size()) {
                break;
            }
            Setting setting = settings.get(settingIndex);
            int y = CONTENT_TOP + row * ROW_HEIGHT;
            if (row % 2 == 0) {
                graphics.fill(contentLeft() - 4, y, this.width - 8, y + ROW_HEIGHT, 0x22FFFFFF);
            }
            String label = this.font.plainSubstrByWidth(setting.label(), this.width - contentLeft() - 160);
            graphics.text(this.font, label, contentLeft(), y + 8, 0xFFD8D8D8);
            if (mouseX >= contentLeft() && mouseX < this.width - 8 && mouseY >= y && mouseY < y + ROW_HEIGHT) {
                hoveredTooltip = setting.tooltip();
            }
        }

        graphics.fill(0, this.height - FOOTER_HEIGHT + 8, this.width, this.height, 0xFF0D0D16);
        if (hoveredTooltip != null) {
            String tip = this.font.plainSubstrByWidth(hoveredTooltip, this.width - 16);
            graphics.text(this.font, tip, 8, this.height - FOOTER_HEIGHT + 14, 0xFFFFC857);
        }
        List<Setting> current = categories.get(categoryIndex).settings();
        int maxScroll = Math.max(0, current.size() - rows);
        if (maxScroll > 0) {
            graphics.text(this.font, "scroll " + scrollRow + "/" + maxScroll, contentLeft(), this.height - FOOTER_HEIGHT + 14, 0xFF666666);
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }
}
