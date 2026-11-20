# Advanced Mobs v1.0

![Version](https://img.shields.io/badge/version-1.0.0-blue) ![Minecraft](https://img.shields.io/badge/minecraft-1.21.x-green) ![Loader](https://img.shields.io/badge/loader-Fabric-yellow)

**Transform Minecraft 1.21.x into an epic battlefield where mobs evolve, fight each other, form alliances, and challenge legendary bosses!**

---

## 🎯 What is Advanced Mobs?

Advanced Mobs completely overhauls mob behavior, creating a dynamic battlefield where mobs evolve based on world age and combat, fight each other in massive wars, form temporary alliances, and can spawn as legendary bosses.

> ✅ **Targeted compatibility:** Built for Minecraft 1.21.x (tested on 1.21.1). Earlier versions and future 1.22+ updates need a dedicated port.

**Key Features:**
- 🔥 **Progressive Evolution**: Mobs earn points and spend them on upgrades using an 80%/20% buy/save system
- ⚔️ **Inter-Mob Combat**: All mobs fight each other based on species, type, and alliances
- 🤝 **Dynamic Alliances**: Mobs team up against common enemies (same species = strong, different = weak)
- 👹 **Mob Warlord Boss**: Legendary raid boss with minion armies
- 📊 **80 Individual Mob Configs**: Each mob has its own JSON with complete upgrade paths
- 🌍 **Config-Driven Compatibility**: Ships with configs for every vanilla 1.21.1 mob; everything else auto-classifies for baseline potion scaling until you drop in a JSON.

---

## 💰 Point Economy & Budget System

### How Mobs Earn Points

Every mob accumulates points from TWO sources:

1. **World Age (Daily Scaling)**
   - Day 0-10: 0.1 points/day (very slow start)
   - Day 11-15: 0.5 points/day
   - Day 16-20: 1.0 points/day
   - Day 21-25: 1.5 points/day
   - Day 26-30: 3.0 points/day
   - Day 31+: **5.0 points/day** (late game acceleration)

2. **Player Kills**
   - 1 point per player kill (of that mob type)
   - Tracks global kills across all mobs of that type
   - Multiplied by `killScalingMultiplier` config setting

**Formula**: `TotalPoints = (DailyPoints × dayScalingMultiplier) + (KillCount × killScalingMultiplier)`

### How Mobs Spend Points (80%/20% System)

When a mob spawns or reaches a spending trigger (1+ days since last spawn):

1. **Calculate Budget**: Total available points from world age + kills
2. **Enter Spending Loop**:
   ```
   WHILE (points > 0):
     - List ALL affordable upgrades (equal weight)
     - Pick ONE random upgrade from the list
     - Roll dice:
       → 80% chance: BUY IT (subtract cost, apply upgrade)
       → 20% chance: SAVE IT (stop spending, keep remaining points)
   ```
3. **Loop Ends When**:
   - 20% save roll succeeds (mob saves remaining points for later)
   - No affordable upgrades remain (spent everything)
4. **Apply Equipment**: After spending loop completes, apply all purchased gear in ONE batch

**Result**: Mobs make progressive, random purchases with a tendency to save for bigger upgrades.

### Example: Zombie on Day 50 with 30 Player Kills

```
Daily Points: 5.0 points/day × 50 days = 250 points
Kill Points: 30 kills × 1 point/kill = 30 points
Total Budget: 280 points

Spending Loop (simplified):
1. Buy Health_Boost_1 (cost 1) → 279 points left
2. Buy Strength_1 (cost 1) → 278 points left
3. Buy Weapon_Tier_1 (cost 5) → 273 points left
4. Save roll (20%) → STOP, keep 273 points for next spawn
... OR continue buying until save roll or broke

Final Result:
- Diamond Sword (Sharpness V, Fire Aspect II, Looting II)
- Full Diamond Armor (Protection III, Thorns II)
- Health: 34 HP, Strength III, Speed II, Regeneration II
- Special: Horde Summon 30% chance, Hunger Attack II
```

---

## 🛡️ Equipment & Upgrade System

### Weapon Progression (5 Tiers)

Mobs purchase `weapon_tier` upgrades from their individual JSON:

**Regular Swords** (Zombie, Husk, etc.):
- Tier 1: Wooden Sword (cost varies per mob)
- Tier 2: Stone Sword
- Tier 3: Iron Sword
- Tier 4: Diamond Sword
- Tier 5: Netherite Sword

**Gold Swords** (Piglins):
- Tier 1: Golden Sword
- Tier 2: Netherite Sword

**Axes** (Vindicators, Piglin Brutes):
- Tier 1: Iron/Gold Axe
- Tier 2: Diamond Axe
- Tier 3: Netherite Axe

**Ranged** (Skeleton, Stray, Pillager):
- Bow, Crossbow, Trident (no tier progression, upgrade via enchants)

### Armor Progression (5 Tiers Each Piece)

Mobs buy `helmet_tier`, `chestplate_tier`, `leggings_tier`, `boots_tier` separately:

**Regular Armor**:
1. Leather (cheapest)
2. Chainmail
3. Iron
4. Diamond
5. Netherite (most expensive)

**Gold Armor** (Piglins/Hoglins):
1. Golden
2. Netherite

### Enchantments (Progressive Levels)

Each enchant has 4-8 levels with increasing costs:

**Weapon Enchants**:
- Sharpness I-VIII (1 → 8 points)
- Fire Aspect I-III (3 → 7 points)
- Knockback I-III (2 → 6 points)
- Looting I-IV (5 → 14 points)

**Armor Enchants**:
- Protection I-VIII (1 → 8 points)
- Thorns I-IV (4 → 10 points)
- Feather Falling I-V (2 → 10 points)
- Blast Protection I-V (2 → 10 points)

**Universal Masteries** (Apply to ALL items):
- Drop Mastery 1-10: Reduces item drop chance from 100% → 1%
- Durability Mastery 1-10: Increases item durability from 1% → 100%

### Equipment Downgrade on Break

When an item reaches 0 durability:
1. Item downgrades ONE tier (Diamond Sword → Iron Sword)
2. ALL enchants wiped
3. Drop Mastery and Durability Mastery reset to 0 for that item
4. If already lowest tier (Wooden), item is removed

**Unlock Next Tier Requirement**:
- Drop Mastery Level 10
- Durability Mastery Level 10
- ALL enchants at maximum level on current item

---

## 🌳 Skill Trees (Mob-Specific)

### Universal Tree (ALL Mobs)

**Offensive**:
- `health_boost` (10 levels): +2 HP per level (max +20 HP)
- `strength` (4 levels): +20% → +80% damage
- `speed` (3 levels): +20% → +60% movement speed

**Defensive**:
- `healing` (5 levels): Permanent Regen I-II, burst healing on damage
- `resistance` (3 levels): Damage reduction + Fire Resistance
- `invis_mastery` (5 levels): 5-80% chance for Invisibility on hit

**Equipment**:
- `shield_tier` (5 levels): 20-100% chance to equip shield
- `weapon_tier` (1-5): Weapon material progression
- `helmet_tier`, `chestplate_tier`, `leggings_tier`, `boots_tier` (1-5 each)

### Zombie Tree (Zombie, Husk, Drowned, Zoglin, Giant)

- `horde_summon` (5 levels): 10% → 50% chance to summon 2-4 reinforcements (costs 10-30)
- `hunger_attack` (3 levels): Inflict Hunger I-III for 10-20s (costs 6-14)

### Ranged Tree (Skeleton, Stray, Bogged, Blaze, Breeze, Drowned, Illusioner, Llama, Pillager, Shulker, Snow Golem, Trader Llama, Witch, Wither)

- `piercing_shot` (4 levels): Arrows pierce 1-4 targets (costs 8-20)
- `bow_potion_mastery` (5 levels): 20% → 100% chance for potion arrows (Slowness → Poison II/Wither) (costs 10-30)
- `multishot` (3 levels): Fire +1 to +3 extra arrows (costs 15-35)

### Creeper Tree

- `creeper_power` (5 levels): Explosion radius 4.25 → 10.0 blocks (costs 10-30)
- `creeper_potion_cloud` (4 levels): Lingering effects - Slowness/Weakness/Poison/Wither (costs 12-30)

### Witch Tree

- `potion_throw_speed` (5 levels): Throw potions 1.15× → 1.75× faster with better accuracy (costs 10-30)
- `extra_potion_bag` (3 levels): Add Instant Damage I → II + Wither I to potion throws (costs 12-24)

### Cave Spider Tree

- `poison_mastery` (6 levels): Poison I (7s) → Poison II (20s) + Wither I + Slowness II (costs 0-24)

---

## 🤝 Alliance System

Mobs attacking the SAME target automatically form alliances:

### Strong Alliances (Same Species)
- Duration: 20 seconds
- Cooperation: 80% chance to remain allied
- Example: Zombie + Zombie attacking a Skeleton

### Weak Alliances (Different Species)
- Duration: 5 seconds
- Cooperation: 30% chance to remain allied
- Example: Zombie + Creeper attacking a Skeleton

**Visual Indicator**: Purple particle lines connect allied mobs

**Alliance Breaks**:
- Timer expires (5s or 20s)
- Common target dies
- Cooperation roll fails (20% or 70% chance)
- One mob attacks the other directly

---

## 👹 Mob Warlord Boss

A legendary boss that spawns during raids:

### Spawn Conditions
- 25% chance on final raid wave (configurable)
- Requires raid level ≥3 (configurable)
- OR use `/mobwar summon warlord` command
- OR use `/mobwar raid forceboss` to guarantee next raid

### Boss Abilities
- Summons 20 minions on spawn (configurable)
- Protects minions from player damage (90% reduction)
- 3× health multiplier (configurable)
- 2× damage multiplier (configurable)
- Enhanced equipment and special skills
- Commands minion army to focus fire

### Boss Loot
- Guaranteed rare drops
- Enhanced enchanted gear
- Unique boss-specific items

---

## 🎮 Commands

```bash
/mobwar help              # Show all commands
/mobwar stats             # View nearby mob stats and levels
/mobwar summon warlord    # Spawn Mob Warlord boss
/mobwar raid forceboss    # Force Warlord in next raid
/mobwar reload            # Reload configuration files
```

---

## ⚙️ Configuration (Mod Menu)

**Access**: Click gear icon next to "Advanced Mobs" in Mod Menu

**Scroll-Friendly Layout**: The in-game screen is now scrollable, so every option stays readable even on 720p installs. Widgets are spaced with consistent padding so you can breeze through the categories listed below.

### General
- `modEnabled` – Global kill switch for the entire mod.
- `targetingEnabled`, `allianceEnabled`, `scalingEnabled`, `warlordEnabled` – Independent toggles for each core system. They also keep the legacy flags (`evolutionSystemEnabled`, etc.) in sync.
- `warlordSpawnChance` – 0–100% slider that controls the chance the Warlord hijacks the final raid wave (default 25%).

### Targeting
- `targetPlayers` – If off, mobs fully ignore players and focus on PvPVE wars.
- `ignoreSameSpecies` – When disabled, zombies can fight zombies, skeletons can fight skeletons, etc.
- `neutralMobsAlwaysAggressive` – Forces wolves, golems, and other neutrals into the conflict.
- `disableNaturalMobSpawns` – Blocks every natural spawn so only scripted events and commands create mobs.
- `rangeMultiplier` – 0.1×–5.0× slider that expands or shrinks the targeting radius.
- `targetingCacheMs` – 500–5000 ms cache window for target lookups.
- `targetingMaxQueriesPerTick` – 10–200 scans per tick so you can tune CPU usage.

### Scaling
- `allowBossScaling` – Boss mobs (including the Warlord) obey the same JSON-driven scaling rules.
- `dayScalingMultiplier` – 0×–10× multiplier for world-age point income.
- `killScalingMultiplier` – 0×–10× multiplier per recorded player kill.
- `saveChancePercent` – 0–100% slider for the 80%/20% buy/save loop (buy chance auto-updates to `100 - save`).

### Performance
- `upgradeProcessingTimeMs` – 1–30 second window (slider shows seconds) used by the batch processor to stay within your frame budget.

### Visuals
- `disableParticles` – Quick FPS-friendly kill switch for most particles this mod spawns.
- `showTargetLines` – Toggle the purple target beams between combatants.
- `showLevelParticles` – Enables/disables the celebratory upgrade bursts.
- `minFpsForVisuals` – Visual effects auto-disable if the client dips below this FPS threshold.

### Debug
- `performanceMode` – Cuts most visual flourishes and applies safer defaults when you need guaranteed FPS.
- `enableBatching` / `enableAsyncTasks` – Control how scaling work is grouped and whether it can hop to background threads.
- `mobDataSaveDebounceMs` – Tune how often mob progress is written to disk (50–1000 ms window).
- `weakAllianceDurationMs`, `strongAllianceDurationMs`, `allianceBreakChancePercent`, `strongAllianceBreakChancePercent` – All alliance timing/probability sliders live here now.
- `alwaysSpawnWarlordOnFinalWave`, `warlordMinRaidLevel`, `warlordMinionCount`, `warlordHealthMultiplierPercent`, `warlordDamageMultiplierPercent` – Complete Warlord tuning without touching JSON.
- `maxParticlesPerConnection`, `maxDrawnMinionConnections` – Cap how many connection effects render per mob/minion.
- `debugUpgradeLog`, `debugLogging` – Toggle chat spam for upgrade decisions or ultra-verbose console logging.
- `excludedMobs` list – Still lives in `config/advancedmobs.json`; edit it manually (plus `/mobwar reload`) if you want this system to ignore specific entities.

**Config File**: `config/advancedmobs.json` (auto-generates and syncs with Mod Menu). Only touch it for `excludedMobs` (plus `/mobwar reload`) or other future experimental flags.

---

## Technical Architecture

### 4 Independent Systems (system/ folder)

All 4 core systems now have their main files in `src/main/java/mod/advancedmobs/system/`:

```
system/
├── TargetingSystem.java   - Mobs fight each other intelligently
├── AllianceSystem.java    - Mobs team up against common enemies
├── ScalingSystem.java     - Mobs get stronger over time (JSON-driven)
└── WarlordSystem.java     - Raid boss with minion army
```

**Each system is independent** and can be enabled/disabled via config:
- `targetingEnabled` - Toggle mob-vs-mob combat
- `allianceEnabled` - Toggle alliance formation
- `scalingEnabled` - Toggle mob progression/upgrades
- `warlordEnabled` - Toggle raid boss spawning

**Supporting files**:
- `goal/UniversalTargetGoal.java` - AI goal using TargetingSystem
- `entity/MobWarlordEntity.java` - Boss entity using WarlordSystem
- `mob_configs/*.json` - 80 mob configs for ScalingSystem

### Individual Mob JSON Configs
Each mob has a complete configuration file in `src/main/resources/mob_configs/`:

```
mob_configs/
├── allay.json           (Passive mob - regeneration, resistance, health boost)
├── blaze.json           (Hostile + ranged tree - potion effects + ranged abilities)
├── bogged.json          (Hostile + equipment + zombie/ranged trees - full progression)
├── ... (80 total files when complete)
```

**Each JSON contains**:
- `mob_name`, `entity_class`, `mob_type` - Basic mob info
- `tree_name` - Descriptive skill tree name
- `point_system` - Daily scaling table, kill scaling, buy/save chances
- `tree` - All applicable upgrades with costs:
  - Potion effects (passive OR hostile/neutral based on mob type)
  - Weapon progression (if applicable) with tiers, enchants, masteries
  - Shield (if applicable)
  - Armor progression (helmet, chestplate, leggings, boots)
  - Special abilities from mob's skill trees

> ℹ️ **No matching JSON?** The scaling system auto-detects passive/neutral/hostile state, applies the matching fallback potion suite, and still respects targeting rules. Drop a JSON alongside it when you want full equipment trees and custom abilities.

### Mob Mixin Architecture

Each mob has a dedicated mixin file with **fully embedded progression logic**:

```
mixin/mob/
├── AllayMixin.java       (Passive mob template)
├── BeeMixin.java         (Neutral mob template)  
├── BoggedMixin.java      (Hostile + full equipment template)
├── BlazeMixin.java       (Hostile + ranged tree template)
└── ... (one per mob)
```

**Each Mixin contains**:
- Complete point calculation using `world.getTime()`
- All upgrade costs and effect application
- NBT persistence for all progression data
- Tick handler with `lastUpdateTick` for 1-day inactivity trigger
- 80%/20% buy/save spending logic
- No external dependencies (fully standalone)

### Core System Mixins

- `MobDataMixin`: Attach MobWarData to mobs (saves to NBT)
- `MobDeathTrackerMixin`: Track player kills per mob type
- `MobRevengeBlockerMixin`: Prevent revenge targeting issues
- `NaturalMobSpawnBlockerMixin`: Control natural spawning
- `NeutralMobBehaviorMixin`: Neutral mob aggression handling
- `RaidSpawningMixin`: Raid and Warlord boss integration
- `UniversalSummonerTrackingMixin`: Summoned mob tracking
- `WarlordMinionProtectionMixin`: Boss minion protection

### Data Persistence

- Mob levels saved to entity NBT
- Survives chunk unload/reload
- Survives server restart
- Works in singleplayer and multiplayer
- Kill counts saved to world data

---

## 📦 Installation

1. Install [Fabric Loader](https://fabricmc.net/) for Minecraft 1.21.x (tested on 1.21.1)
2. Install [Fabric API](https://modrinth.com/mod/fabric-api) v0.102.0+
3. Download `advanced-mobs-1.0.0.jar`
4. Place in `mods` folder
5. Launch and enjoy!

**Optional**:
- [Mod Menu](https://modrinth.com/mod/modmenu) (for config GUI)
- [Cloth Config](https://modrinth.com/mod/cloth-config) (for advanced settings)

---

## 🔨 Building from Source

**Requirements**:
- Python 3.x
- Java 21+
- Gradle (via wrapper)

```bash
# Clone repository
git clone https://github.com/Carter-75/AdvancedMobs.git
cd AdvancedMobs

# Validate all 80 mob JSONs + code
python universal_build.py --check

# Build the mod JAR
python universal_build.py --build

# Build + push to GitHub
python universal_build.py --deploy

# Output: build/libs/advanced-mobs-1.0.0.jar
```

**Build Script Features**:
- Validates mob JSON configurations (supports partial completion)
- Checks Java code for 1.21.1 API compatibility  
- Verifies core mixins and mob mixins are present
- Runs full Gradle build
- Shows progress: `X/80 mobs implemented`
- Single log file: `universal_build.log`

---

## 🎮 Gameplay Examples

### Early Game (Days 1-10)
```
Points: ~1 point total
Upgrades: Health +2, maybe Strength I
Equipment: None or wooden sword
Result: Slightly tougher mobs, basic combat
```

### Mid Game (Days 15-25)
```
Points: ~20 points total
Upgrades: Health +6, Strength II, Speed I, weapon tier 2-3
Equipment: Stone/Iron sword, leather/iron armor
Result: Coordinated battles, alliances forming
```

### Late Game (Days 30+)
```
Points: ~178 points total
Upgrades: Health +20, Strength IV, Speed III, all trees progressing
Equipment: Diamond/Netherite gear with enchants
Result: Epic wars, Warlord bosses, village sieges
```

### Maximum Chaos Mode
```
Config:
- ignoreSameSpecies = false (Zombies attack Zombies!)
- rangeMultiplier = 5.0 (5× detection range)
- targetPlayers = false (you're spectator)

Result: EVERY mob attacks EVERY other mob at massive range!
```

---

## 📄 License

**Copyright © 2024 Carter. All rights reserved.**

✅ **Allowed**:
- Use in modpacks (with credit)
- Public/private servers
- Videos and content creation
- Share official download links

❌ **Not Allowed**:
- Redistributing the JAR file
- Modifying or decompiling
- Claiming as your own

**Attribution**: "Advanced Mobs by Carter"

---

## 🐛 Bug Reports

Report bugs on GitHub with:
- Minecraft 1.21.1
- Fabric Loader version
- Other mods installed
- Steps to reproduce
- Screenshots/logs (`latest.log` and `universal_build.log`)

---

## 🎉 Summary

Advanced Mobs transforms Minecraft into a dynamic battlefield:

- ✅ **80 individual mob configs** with complete upgrade paths (in progress)
- ✅ **Progressive point economy** with 80%/20% buy/save system
- ✅ **5-tier equipment progression** (Wood → Netherite)
- ✅ **27 enchantments** with multi-level costs
- ✅ **6 mob-specific skill trees** with unique abilities
- ✅ **Dynamic alliance system** (strong/weak, 5s-20s duration)
- ✅ **Legendary Mob Warlord boss** with minion armies
- ✅ **Config coverage for vanilla 1.21.x mobs**; others auto-scale with fallback potions until you author a JSON
- ✅ **Performance optimized** with multithreading and batching
- ✅ **Fully data-driven** - all costs/skills in JSON files

**Watch the world evolve into an epic war!** 🔥

---

**Made with ❤️ for Minecraft 1.21.1 | Powered by Fabric**
