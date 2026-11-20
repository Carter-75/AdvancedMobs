# Advanced Mobs Ability Design: Verified Capability Reference

Everything in this reference is verified to exist and work in BOTH Minecraft 26.2 and 26.3, unless a section explicitly flags it as version-specific or UNVERIFIED. Part 1 comes from the real 26.2 client jar. Part 2 comes from the Minecraft Wiki and official changelogs.

# PART 1: REGISTRIES FROM THE 26.2 JAR

# Capability registries extracted from the real Minecraft 26.2 client jar

## net.minecraft.core.particles.ParticleTypes
ANGRY_VILLAGER ASH BLOCK BLOCK_CRUMBLE BLOCK_MARKER BUBBLE BUBBLE_COLUMN_UP BUBBLE_POP CAMPFIRE_COSY_SMOKE CAMPFIRE_SIGNAL_SMOKE CHERRY_LEAVES CLOUD CODEC COMPOSTER COPPER_FIRE_FLAME CRIMSON_SPORE CRIT CURRENT_DOWN DAMAGE_INDICATOR DOLPHIN DRAGON_BREATH DRIPPING_DRIPSTONE_LAVA DRIPPING_DRIPSTONE_WATER DRIPPING_HONEY DRIPPING_LAVA DRIPPING_OBSIDIAN_TEAR DRIPPING_WATER DUST DUST_COLOR_TRANSITION DUST_PILLAR DUST_PLUME EFFECT EGG_CRACK ELDER_GUARDIAN ELECTRIC_SPARK ENCHANT ENCHANTED_HIT END_ROD ENTITY_EFFECT EXPLOSION EXPLOSION_EMITTER FALLING_DRIPSTONE_LAVA FALLING_DRIPSTONE_WATER FALLING_DUST FALLING_HONEY FALLING_LAVA FALLING_NECTAR FALLING_OBSIDIAN_TEAR FALLING_SPORE_BLOSSOM FALLING_WATER FIREFLY FIREWORK FISHING FLAME FLASH GEYSER GEYSER_BASE GEYSER_PLUME GEYSER_POOF GLOW GLOW_SQUID_INK GUST GUST_EMITTER_LARGE GUST_EMITTER_SMALL HAPPY_VILLAGER HEART INFESTED INSTANT_EFFECT ITEM ITEM_COBWEB ITEM_SLIME ITEM_SNOWBALL LANDING_HONEY LANDING_LAVA LANDING_OBSIDIAN_TEAR LARGE_SMOKE LAVA MYCELIUM NAUTILUS NOTE NOXIOUS_GAS NOXIOUS_GAS_CLOUD OMINOUS_SPAWNING PALE_OAK_LEAVES PAUSE_MOB_GROWTH POOF PORTAL RAID_OMEN RAIN RESET_MOB_GROWTH REVERSE_PORTAL SCRAPE SCULK_CHARGE SCULK_CHARGE_POP SCULK_SOUL SHRIEK SMALL_FLAME SMALL_GUST SMOKE SNEEZE SNOWFLAKE SONIC_BOOM SOUL SOUL_FIRE_FLAME SPIT SPLASH SPORE_BLOSSOM_AIR SQUID_INK SULFUR_BUBBLES SULFUR_CUBE_GOO SWEEP_ATTACK TINTED_LEAVES TOTEM_OF_UNDYING TRAIL TRIAL_OMEN TRIAL_SPAWNER_DETECTED_PLAYER TRIAL_SPAWNER_DETECTED_PLAYER_OMINOUS UNDERWATER VAULT_CONNECTION VIBRATION WARPED_SPORE WAX_OFF WAX_ON WHITE_ASH WHITE_SMOKE WITCH 

## net.minecraft.world.entity.ai.attributes.Attributes
AIR_DRAG_MODIFIER ARMOR ARMOR_TOUGHNESS ATTACK_DAMAGE ATTACK_KNOCKBACK ATTACK_SPEED BELOW_NAME_DISTANCE BLOCK_BREAK_SPEED BLOCK_INTERACTION_RANGE BOUNCINESS BURNING_TIME CAMERA_DISTANCE DEFAULT_ATTACK_SPEED ENTITY_INTERACTION_RANGE EXPLOSION_KNOCKBACK_RESISTANCE FALL_DAMAGE_MULTIPLIER FLYING_SPEED FOLLOW_RANGE FRICTION_MODIFIER GRAVITY JUMP_STRENGTH KNOCKBACK_RESISTANCE LUCK MAX_ABSORPTION MAX_HEALTH MINING_EFFICIENCY MOVEMENT_EFFICIENCY MOVEMENT_SPEED NAME_TAG_DISTANCE OXYGEN_BONUS SAFE_FALL_DISTANCE SCALE SNEAKING_SPEED SPAWN_REINFORCEMENTS_CHANCE STEP_HEIGHT SUBMERGED_MINING_SPEED SWEEPING_DAMAGE_RATIO TEMPT_RANGE WATER_MOVEMENT_EFFICIENCY WAYPOINT_RECEIVE_RANGE WAYPOINT_TRANSMIT_RANGE 

## net.minecraft.world.effect.MobEffects
ABSORPTION BAD_OMEN BLINDNESS BREATH_OF_THE_NAUTILUS CONDUIT_POWER DARKNESS DOLPHINS_GRACE FIRE_RESISTANCE GLOWING HASTE HEALTH_BOOST HERO_OF_THE_VILLAGE HUNGER INFESTED INSTANT_DAMAGE INSTANT_HEALTH INVISIBILITY JUMP_BOOST LEVITATION LUCK MINING_FATIGUE NAUSEA NIGHT_VISION OOZING POISON RAID_OMEN REGENERATION RESISTANCE SATURATION SLOWNESS SLOW_FALLING SPEED STRENGTH TRIAL_OMEN UNLUCK WATER_BREATHING WEAKNESS WEAVING WIND_CHARGED WITHER 

## net.minecraft.world.entity.EntityType
CODEC 


# PART 2: WIKI AND CHANGELOG RESEARCH

# Minecraft 26.2 and 26.3 Capability Reference for Ability Design

Scope: Java Edition 26.2 (Chaos Cubed, released June 16, 2026) and 26.3 (Wilderness Bound, released September 15, 2026). Everything listed in sections 1 to 4 exists in 26.2 unless flagged. Section 5 lists what changed between the two and ends with the safe and avoid rule lists.

Sources: minecraft.wiki pages for Java Edition 26.2, Java Edition 26.3, Commands, Particles, Java Edition protocol Particles, Attribute, Display, Entity format (display entity), Effect, and Mob AI; the official minecraft.net 26.3 article; the feedback.minecraft.net 26.2 changelog. Items that could not be confirmed from those sources are marked UNVERIFIED.

Version facts: 26.2 is protocol 776, data version 4903, resource pack format 88.0, data pack format 107.1. 26.3 is protocol 777, data version 5023, resource pack format 97.1, data pack format 121.0. Both need Java 25 minimum. The 26.x game jar is unobfuscated and uses official names.

## 1. Commands

A Fabric mod does in code what these commands do from chat, so this list is the menu of vanilla-backed effects an ability can reproduce. All commands below exist in both 26.2 and 26.3 unless a change is noted.

Core ability commands:

- `/particle <type> [pos] [delta] [speed] [count] [mode] [viewers]`: spawns particles at a point or around an entity (via `/execute at`). The main visual tool. Mode is `normal` or `force` (force ignores the client particle distance limit).
- `/summon <entity> [pos] [nbt]`: spawns any entity with full data: mobs with equipment and attributes, projectiles, TNT, falling blocks, lightning is a separate entity type, and the three display entities. The summon tool for minions, hazards, and visual props.
- `/effect give|clear <targets> <effect> [seconds] [amplifier] [hideParticles]`: applies or removes any status effect, including `infinite` duration. See section 4 for the effect list.
- `/attribute <target> <attribute> get|base set|base get|modifier add|modifier remove|modifier value get`: reads and writes every attribute in section 4, with named modifiers using the operations `add_value`, `add_multiplied_base`, and `add_multiplied_total`. Size, speed, gravity, damage, and health changes all go through here.
- `/damage <target> <amount> [damage type] [at <pos>] [by <entity>] [from <entity>]`: deals typed damage with an optional attacker, so kills credit the right mob and damage resistances and immunities apply per damage type.
- `/data get|merge|modify|remove entity|block|storage`: reads and rewrites entity data directly: `Motion` (velocity, non-player entities only), `Health`, `Passengers`, `CustomName`, brain memories on brain mobs, display entity fields, and any custom storage an ability needs.
- `/execute`: the combiner. Useful subcommands for abilities: `as`, `at`, `positioned`, `rotated`, `facing`, `align`, `anchored`, `in`, `summon` (spawn and run as the new entity), `if` and `unless` (block, entity, score, data, predicate), `store result` (into score, data, or bossbar), `run`, and the entity relation selectors `on attacker`, `on target`, `on passengers`, `on vehicle`, `on owner` (26.2 change: vexes now count their evoker as owner), `on last_damage_player`, `on origin`, `on summoner`, `on controller`, `on leasher`, `on raider`, `on wielder`.
- `/fill <from> <to> <block> [destroy|hollow|keep|outline|replace|strict]`: bulk block placement up to 32,768 blocks. Terrain reshaping, cages, floors, and arenas.
- `/setblock <pos> <block> [destroy|keep|replace]`: single block placement or removal.
- `/clone <from> <to> <dest> [filtered|masked|replace] [force|move|normal]`: copies a block region, for duplicating structures or stamping a template.
- `/tp` and `/teleport <target> <dest>|<pos> [rotation]`: instant repositioning of mobs and players, to coordinates or onto another entity.
- `/rotate <target> <rotation>`: sets an entity facing without moving it (added 1.21.2, present in both).
- `/ride <target> mount <vehicle>` and `/ride <target> dismount`: forces passenger stacks. Mount a mob on another mob, stack mobs into towers, or seat a rider for a charge attack.
- Motion: there is no `/motion` or `/velocity` command in Java Edition. Motion changes are done in code (set velocity, apply knockback), or from commands by writing the `Motion` list with `/data modify entity` (works on mobs and other non-player entities; player data cannot be modified by commands). Explosions, wind bursts, and geyser eruptions are the vanilla impulse sources.
- `/gamerule <rule> [value]`: reads or sets world game rules. In 26.x, rule names are namespaced lowercase snake_case IDs (renamed back in 1.21.11), for example `minecraft:keep_inventory`, `advance_time` (was doDaylightCycle), and `spawn_mobs` (was doMobSpawning). Ability-relevant rules include fall damage, fire spread, mob griefing, and drowning damage toggles.
- `/schedule function <id> <time> [append|replace]` and `/schedule clear`: delayed function execution in ticks, seconds, or days. The command-side way to do delayed ability phases.
- `/function <id> [arguments]`: runs a data pack function, with macro arguments. Abilities built as functions chain other commands through this.
- `/loot give|insert|spawn|replace|kill`: rolls a loot table and gives, inserts, drops, or equips the result. Random gear, random drops, and random summon rewards.
- `/item replace <target> <slot> with <item> [count]` and `/item modify`: writes equipment and inventory slots directly, including display entity `contents`. Instant gear swaps and visual prop changes.
- `/enchant <target> <enchantment> [level]`: enchants the target held item, respecting vanilla survival limits unless the level is forced within command rules.
- `/xp add|set|query`: experience levels and points, for level-scaled effects and for feeding XP-based triggers.
- `/weather clear|rain|thunder [duration]`: sets weather. Thunder enables lightning strikes and channeling style effects.
- `/time set|add|query`: sets or advances world time, for day and night gated abilities.
- `/worldborder`: center, size, damage amount, damage buffer, warning distance and time. A shrinking arena is a worldborder with damage turned on.
- `/forceload add|remove|query`: keeps chunks loaded so off-screen ability machinery, summons, and timers keep ticking.
- `/tag <target> add|remove|list`: string tags on entities. The standard way to mark ability states (marked, cursed, phased) and to select them later. 26.2 added an `entity_tags` entity sub-predicate that matches these tags in predicates.
- `/team add|modify|join|leave|empty|remove|list`: teams control friendly fire, nameplate color, glowing outline color, collision, and death messages. The alliance system primitive.
- `/bossbar add|set|get|remove|list`: custom boss bars with name, value, max, color, style, and visibility per player. Boss ability timers and phase meters.
- `/title` and `/subtitle`, `/actionbar` (via `/title ... actionbar`): on-screen text for players. Ability announcements and warnings.
- `/playsound <sound> <source> <targets> [pos] [volume] [pitch] [minVolume]`: plays any sound event, vanilla or modded, positioned or attached to the player.
- `/stopsound <targets> [source] [sound]`: stops playing sounds, for ending loops an ability started.
- `/spreadplayers <center> <spreadDistance> <maxRange> <respectTeams> <targets>`: scatters entities to random safe surface positions. Changed in 26.3: whether a block counts as a safe landing is now controlled by the `#entities_can_teleport_to` block tag.
- `/swing [targets] [hand]`: plays the arm swing animation without a hit. Added in 26.1, so present in both, but CHANGED in 26.3: it gained an optional animation argument (`whack` or `stab`, default `whack`) and an optional duration argument (default 6 ticks), swinging a player no longer resets that player attack strength, and the `minecraft:swing_animation` item component that 26.2 used was removed in 26.3 because the command now carries the animation. In code, swinging a hand is stable across both; do not copy 26.2 command syntax into 26.3 functions.
- `/damage` note: pairs with custom damage types; a mod can register its own damage type and both `/damage` and code use it.

Other commands worth knowing for abilities: `/kill` (instant removal, respects nothing, use for cleanup), `/gamemode` and `/spectate` (player only), `/clear` (strip inventories), `/give` (player only), `/setworldspawn` and `/spawnpoint`, `/difficulty`, `/advancement` (grant and revoke triggers), `/recipe`, `/scoreboard` (objectives as ability counters and storage), `/random value|roll` (command-side RNG that can store into scores), `/return` (function return values, pairs with `/execute store`), `/tick query|rate|freeze|step|sprint` (global tick control, debug and cutscene tool, not per-ability), `/stopwatch` (real-time timers that ignore tick rate), `/waypoint list|modify` (Locator Bar waypoint color and style, player facing markers), `/place` (places features, structures, and jigsaws), `/fillbiome` (rewrites biome data in a region), `/datapack` and `/reload` (pack management), `/seed`, `/locate` (find structures and biomes), `/msg`, `/tellraw`, `/say` (chat output), `/trigger` (scoreboard triggers players can fire), `/publish` (CHANGED in 26.3: the `gamemode` argument was removed).

Commands that are 26.3 ONLY, do not design cross-version abilities on them:

- `/compute <target> integer|float <provider> [scale]`: evaluates a context number provider (default, block, or entity context) and returns the value. New in 26.3.
- `/posteffect add|remove|clear|list`: applies resource pack post-processing shaders to a player screen (blur and similar). New in 26.3. The server cannot verify the client actually applied it.

Command behavior changes in 26.3 that matter when porting command-driven logic: `/team join` and `/team leave` results now count only entities whose team actually changed; `/tick step` and `/tick sprint stop` now signal errors instead of returning 0 with a message; `/data modify` gained a `compute` value source tied to `/compute`; sign text click events no longer run by default (new `allow_op_features` field, default false).

## 2. Particles

How it works for a mod: particles are client-side visuals spawned from the server. In code, the server sends particle data to nearby clients (the server level particle send call, with count, spread offsets, speed, and a force flag that bypasses client distance culling), and each client renders them with its own particle settings. The `/particle` command exposes the same system. Particle types live in the `minecraft:particle_type` registry, so a Fabric mod can also register fully custom particle types with its own texture and physics, but the vanilla set below is large enough for most designs.

Data-carrying particle types (the type takes parameters, verified from the wiki protocol particle list, accurate as of 26.2):

- `block` and `block_marker`: take a block state. Any block texture as flying debris or as a marker.
- `item`: takes an item stack. Any item sprite as debris.
- `dust`: takes a color (0xRRGGBB) and a scale clamped between 0.01 and 4. The generic colored mote.
- `dust_color_transition`: start color, end color, scale. Motes that shift color as they age.
- `effect`, `instant_effect`, and `entity_effect`: take a color; effect types also take a power value. Potion-style sparkles in any color.
- `dragon_breath`: takes a power value. The purple lingering breath cloud.
- `sculk_charge`: takes a roll angle. The rising sculk charge streak.
- `geyser`, `geyser_base`, `geyser_poof`, `geyser_plume`: take `water_blocks` (positive int, scales size and burst impulse); base and poof also take `burst_impulse_base`. Added in 26.2 for potent sulfur geysers.
- `flash`: takes an ARGB color. A single bright flash sprite.
- `trail`: a particle that flies from its spawn point to a target position over a set duration. UNVERIFIED: exact field layout in 26.2 and 26.3 was not re-read for this document.
- `vibration`: travels from a source to a destination over a duration, the sculk sensor signal visual. UNVERIFIED: not re-read for this document.

Main categories in the vanilla set: flames and fire (`flame`, `soul_fire_flame`, `copper_fire_flame`, `lava`), smoke (`smoke`, `large_smoke`, `campfire_cosy_smoke`, `campfire_signal_smoke`, `white_ash`, `ash`), water (`bubble`, `bubble_column_up`, `bubble_pop`, `current_down`, `splash`, `fishing`, `underwater`, `dripping_water`, `falling_water`, `rain`), combat feedback (`crit`, `enchanted_hit`, `damage_indicator`, `sweep_attack`, `sonic_boom`), magic (`enchant`, `portal`, `reverse_portal`, `witch`, `end_rod`, `firework`, `note`, `heart`, `angry_villager`, `happy_villager`, `composter`, `squid_ink`), nature (`cherry_leaves`, `pale_oak_leaves`, `tinted_leaves`, spore types `crimson_spore`, `warped_spore`, dripping types), and explosion (`explosion`, `explosion_emitter`, `poof`, `cloud`).

Visually distinctive types a designer should know by name:

- `sonic_boom`: the warden shockwave ring. One-shot, huge, unmistakable.
- `sculk_soul`: a small rising soul face wisp, from sculk catalysts.
- `sculk_charge` and `sculk_charge_pop`: teal charge streaks that travel along surfaces.
- `soul` and `soul_fire_flame`: soul sand valley soul wisps and blue flames.
- `dragon_breath`: purple area-denial cloud with real gameplay weight behind it.
- `elder_guardian`: a full ghost guardian apparition that flashes on screen, used by the elder guardian curse.
- `cherry_leaves`: pink petals drifting down and sideways.
- `pale_oak_leaves`: gray-white leaves that swirl downward in a narrowing column.
- `tinted_leaves`: falling leaves tinted by biome foliage color. In 26.3, spruce leaves no longer produce falling leaf particles, so this type no longer comes from spruce.
- `copper_fire_flame`: green flame, from copper torches.
- `geyser` family plus `sulfur_bubbles`, `noxious_gas`, and `noxious_gas_cloud`: the 26.2 sulfur set. Water plume eruptions, sulfur bubbles in pools, and the greenish nausea gas cloud that potent sulfur spreads over water surfaces.
- `sulfur_cube_goo`: the goo flecks a sulfur cube sheds while hopping. Added in 26.2.
- `gust` and `gust_emitter_small` and `gust_emitter_large`: the breeze wind burst visuals. UNVERIFIED: names not re-read for this document.
- `trial_spawner` detection and ominous variants, `vault_connection`, and other spawner ambience types exist for set dressing. UNVERIFIED: exact IDs not re-read for this document.
- `firefly`: glowing firefly motes at night near water in warm biomes. UNVERIFIED: not re-read for this document.

Performance rule from 26.2: when the client is over its particle limit, the game now randomly selects which particles render instead of deleting in-flight particles, so dense ability effects degrade gracefully instead of vanishing oldest-first.

Particle changes in 26.3: added `red_poplar_leaves`, `orange_poplar_leaves`, and `yellow_poplar_leaves` (no data fields, fall from the new poplar leaf blocks). Removed falling leaf particles from spruce trees. No particle types were removed from the registry in 26.3. The three poplar leaf particles are 26.3 only; every other particle named in this section exists in 26.2.

Custom particles in a mod: a Fabric mod can register its own particle type, texture, and client factory, and spawn it with the same server call as vanilla types. That is the route for signature visuals (custom shapes, glyphs, embers) that the vanilla set cannot fake.

## 3. Display Entities and Models

Display entities are the vanilla "make a model appear" tool. There are three: `block_display`, `item_display`, and `text_display`. They can only be created by `/summon`, `/execute summon`, or code. They have no hitbox, no health, no AI, no gravity, and no collision. They are invisible for one tick when loaded, and data changes made right after summoning can arrive at clients a tick later, so spawn them a beat before they must look right.

Shared display fields (all settable through entity data, all present in both versions):

- `transformation`: translation (offset), `left_rotation` and `right_rotation` (quaternion or axis-angle), and `scale` (per-axis), or a raw 16-number matrix. This is full 3D placement: any size, any tilt, any spin.
- `interpolation_duration` and `start_interpolation`: changes to transformation, brightness, and several other fields glide over a set number of ticks instead of snapping. Chained interpolations are the standard way to animate displays (grow, rotate, pulse) with zero per-tick code.
- `teleport_duration` (0 to 59 ticks): smooths position and rotation updates from teleports, so a display can chase or orbit a mob smoothly.
- `billboard`: `fixed` (never turns), `vertical` (yaws to face the viewer), `horizontal` (pitches to face the viewer), `center` (fully faces the viewer). Center billboarding makes flat sprites that always read on screen.
- `brightness`: overrides block light and sky light (each 0 to 15), so a display can glow at full brightness in a cave.
- `glow_color_override`: color of the glowing outline; -1 means use the entity team color. Glowing does not work on text displays.
- `shadow_radius` and `shadow_strength`: a fake blob shadow under the display, both interpolated.
- `view_range`: render distance multiplier for the display.
- `width` and `height`: the render culling box. Oversized displays need this raised or they pop out at screen edges.

Per type:

- Block display: shows any block state model, floating free of the block grid. It does not render block entities, so a chest or sign shows without its special renderer content. Rotation pivot is the bottom-north-west corner of the block model. Use for: giant floating blocks, fake terrain pieces, rotating runes of stone, a hovering sulfur spike.
- Item display: shows one item stack model, including any model supplied by the `item_model` data component, so a resource pack or mod model of any shape can be shown. The stack sits in a real slot (`contents`, also addressed as `inventory.0`), so `/item` and `/loot` can swap what it shows mid-ability. The `item_display` field picks the model transform context: `none`, `thirdperson_lefthand`, `thirdperson_righthand`, `firstperson_lefthand`, `firstperson_righthand`, `head`, `gui`, `ground`, or `fixed`. Pivot is the model center. Use for: orbiting weapons, floating icons, giant swords, warning markers.
- Text display: shows a formatted text component. Fields include `text`, `line_width` (default 200, wraps text), `text_opacity` (interpolated, for fades), and `background` color. Text is one-sided by default (readable from the front); billboard modes fix that. Pivot is the bottom center of the text block, and each text pixel is 1/40 of a block wide before scaling. Use for: floating labels, countdowns, damage-style callouts, boss nameplates with live values.

Changing a real mob model, what a mod can do:

- Scale attribute: `minecraft:scale` resizes the whole entity, model and bounding box together (0.0625 to 16). This is the one data-driven model change that needs no renderer code, and abilities in the current mod design space use it heavily.
- Render states (26.x rendering): entity renderers extract a render state each frame, and a mod can add its own render state fields, swap textures per state, tint, add or remove model parts through custom model layers, and register entirely custom renderers for its own entities. For vanilla mobs, changing the model itself (extra limbs, new geometry) requires renderer or mixin code; there is no data-only model swap for mobs.
- Equipment and held items: armor, weapons, shields, and carved pumpkins in equipment slots render on the mob automatically, and `/item replace` or code can change them live. A held item display context applies, so oversized weapons read correctly.
- Nameplates: setting a custom name shows a nameplate; 26.2 added the `name_tag_distance` attribute (0 to 512, default 64) for how far the nameplate renders, and `below_name_distance` (0 to 512, default 10) for the below-name scoreboard line. Sneaking hides player nameplates at range; a nameplate is never visible through the entity being invisible.
- Glowing outline: the `glowing` status effect or the entity glowing flag draws an outline visible through blocks, colored by team. Works on mobs and on block and item displays (not text displays).
- Invisibility: hides the mob model but not its armor, held items, or nameplate logic; see section 4.
- Passengers as props: displays and armor stands can ride mobs (and mobs can ride displays only if the display is a vehicle, which it cannot be in practice; use an armor stand or a real mob as the carrier). The standard floating-prop stack is mob, then an invisible armor stand or display as passenger with an offset transformation. UNVERIFIED: display entities as passengers was not re-read for this document; armor stands as passengers are long-established.

Limits to design around: displays cannot collide, be damaged, pathfind, or be targeted by mob AI as enemies; text displays cannot glow; block displays cannot show block entity detail (no chest lids, no sign text, no banner patterns beyond the block model); interpolation is client-side and starts from the previous state, so teleporting a display resets its glide unless `teleport_duration` is set; and every display is a real entity that counts toward entity load, so hundreds of per-ability displays per fight will cost performance.

## 4. Mob Mechanics

### Attributes

Attributes are the numeric dials on every living entity. A mod sets base values or adds named modifiers (operations `add_value`, `add_multiplied_base`, `add_multiplied_total`), and `/attribute` exposes the same system. Full list relevant to abilities, with ranges from the wiki Attribute page and the 26.3 attribute tables. Attributes marked 26.2 were added in 26.2 and are present in both versions.

- `max_health`: 1 to 1024, default 20. Total health pool.
- `max_absorption`: cap on absorption hearts an entity can hold.
- `armor`: 0 to 30. Physical damage reduction points.
- `armor_toughness`: 0 to 20. Reduction against high damage hits.
- `attack_damage`: 0 to 2048, default 2. Melee hit damage.
- `attack_knockback`: 0 to 5. Extra knockback on the entity own attacks (ravager and warden sit at 1.5).
- `attack_speed`: 0 to 1024, default 4. Attack cooldown speed; mostly player-facing but present on mobs.
- `movement_speed`: 0 to 1024. Ground speed base (zombie 0.23, player 0.1 for scale).
- `flying_speed`: flight speed for flying mobs.
- `follow_range`: 0 to 2048, default 32 (zombies use 40). How far the mob notices and tracks targets. The aggro radius dial.
- `knockback_resistance`: -2 to 1, default 0. Fraction of horizontal knockback ignored; 1 is immune. The minimum was lowered to -2.0 in 26.2, so negative values now amplify knockback taken.
- `explosion_knockback_resistance`: 0 to 1. Same idea, explosions only.
- `scale`: 0.0625 to 16, default 1. Model and hitbox size multiplier.
- `step_height`: default 0.6. How high the entity steps without jumping. Raise it and a mob walks up cliffs; the sulfur cube and horses use elevated values.
- `gravity`: default 0.08 blocks per tick squared. Lower values float, higher values slam down.
- `jump_strength`: jump launch power (horses randomize theirs). Pairs with gravity for moon jumps and super leaps.
- `safe_fall_distance`: -1024 to 1024, default 3 (6 for horses and camels, 5 for foxes). Fall height ignored before damage starts.
- `fall_damage_multiplier`: scales fall damage after the safe distance.
- `water_movement_efficiency`: 0 to 1. How much being underwater slows movement; 1 swims at full land speed.
- `movement_efficiency`: 0 to 1. How much slow blocks (soul sand, honey) are ignored.
- `burning_time`: scales how long the entity burns once lit.
- `oxygen_bonus`: extra underwater breathing luck factor.
- `sneaking_speed`: sneak movement multiplier (player-facing).
- `tempt_range`: how far food-holding players tempt the mob (animal mobs).
- `spawn_reinforcements`: zombie family only, chance to call reinforcements when hurt.
- `block_break_speed`, `block_interaction_range` (0 to 64, default 4.5), `entity_interaction_range` (0 to 64, default 3), `mining_efficiency`, `submerged_mining_speed`, `sweeping_damage_ratio`: reach and work-speed dials, mostly player-facing but valid targets for mob abilities that break blocks or sweep.
- `luck` and `unluck` via effects rather than this list; the `luck` attribute is player-only.
- `camera_distance`, `waypoint_transmit_range`, `waypoint_receive_range`: player-only, not mob ability material.
- `air_drag_modifier` (26.2): 0 to 2048, default 1. Multiplies air drag. 0 is frictionless flight paths; high values stop projectiles and leaping mobs mid-air. Core dial for the sulfur cube archetypes.
- `friction_modifier` (26.2): 0 to 2048, default 1. Multiplies ground friction from the block below. 0 is ice physics everywhere; high values are instant stops.
- `bounciness` (26.2): 0 to 1, default 0. Fraction of velocity returned on collision with blocks and collidable entities; when landing on a bouncy block such as a bed or slime block, the higher of block or entity bounciness applies. Collisions with non-zero bounciness emit the new `bounce` game event (vibration frequency 2, sculk sensors can hear it).
- `name_tag_distance` (26.2): 0 to 512, default 64. Nameplate render range.
- `below_name_distance` (26.2): 0 to 512, default 10. Below-name scoreboard render range.

### Status effects

Applied with `/effect` or in code, each with amplifier (level) and duration, optional hidden particles. Java list highlights from the wiki Effect page; all of these exist in both 26.2 and 26.3:

- Combat core: `speed`, `slowness`, `strength`, `weakness`, `haste`, `mining_fatigue`, `resistance`, `absorption`, `health_boost`, `regeneration`, `instant_health`, `instant_damage`, `poison` (damage over time, cannot kill), `wither` (damage over time, can kill), `saturation`.
- Movement and physics: `jump_boost` (higher jumps, less fall damage), `levitation` (floats the entity upward, amplifier sets rise speed), `slow_falling` (caps fall speed, negates fall damage), `dolphins_grace` (Java only, swim speed).
- Senses and visibility: `invisibility` (model hidden, armor and held items still show, and other mobs detection range against the entity shrinks), `glowing` (Java only, outline through blocks, team colored), `blindness` (player vision fog, blocks sprinting and crits), `night_vision`, `darkness` (pulsing black fog and screen darkening, the warden effect), `nausea` (screen warp; also what potent sulfur noxious gas inflicts).
- Survival: `fire_resistance`, `water_breathing`, `conduit_power` (underwater vision, mining speed, no drowning), `breath_of_the_nautilus` (freezes the oxygen bar), `hunger`.
- Death and event triggers: `wind_charged` (entity emits a wind burst on death), `oozing` (spawns 2 slimes on death), `infested` (10 percent chance to spawn 1 to 3 silverfish when hurt), `weaving` (cobwebs slow the entity less, and it spreads cobwebs on death), `bad_omen`, `raid_omen`, `trial_omen`, `hero_of_the_village`, `luck`, `unluck`.
- Effect mechanics that matter for design: the same effect cannot stack with itself; a stronger application hides the weaker one and the weaker returns if it has longer duration left (Java behavior). Effect damage is magic damage and bypasses armor. Milk, death, and a totem of undying clear all effects on players. Amplifier goes 0 to 255 in commands and data.
- Not available: a `freezing` status effect is listed on the wiki as upcoming for a later 2026 drop. It is in neither 26.2 nor 26.3. Do not design on it. Powder snow freezing is a separate block mechanic: entities standing in powder snow build up freeze and take freeze damage, leather armor slows it, and a fully frozen entity takes ongoing damage. UNVERIFIED: exact freeze timings were not re-read for this document.

### AI, targeting, and hooks

- Two AI systems exist in Java. Most mobs use goal selectors: a behavior goal list (priorities, with control flags for move, look, jump, and target) and a separate target goal list. Brain mobs (villager, piglin, warden, frog, goat, axolotl, allay, breeze, and others) use memories and sensors instead; sensors rescan about once a second and size their search by the mob `follow_range`.
- Goal categories a mod adds or edits in code, at a high level: melee attack, ranged attack (bow, crossbow, spit, wind charge style), leap at target, charge and ram, flee and panic, follow parent or owner or tempt item, wander and look around, swim and door breaking, and mob-specific specials (creeper swelling, spider climbing, enderman teleport). Target selector categories: nearest attackable target with type and visibility filters, hurt-by-target (with call-for-help to nearby same-type mobs), defend village or trusted target, owner attacker and owner target for tames, and universal anger targets on neutral mobs.
- In code, a Fabric mod injects goals into a mob goal selector at a chosen priority, or writes brain memories to steer brain mobs. There is no data-only way to give a vanilla mob a new goal.
- Riding and passengers: entities form stacks; the bottom entity (vehicle) controls movement, passengers follow. `/ride` and the `Passengers` data list build stacks, and code can start or stop riding at any time. A riding entity cannot be teleported away cleanly in Java (it snaps back to its vehicle), so dismount first, then teleport. Sulfur cubes are a special case: they cannot enter boats but can ride minecarts, and with a block absorbed they become pushable physics objects.
- Fire: entities have fire ticks; setting a mob on fire deals periodic fire damage unless it has fire resistance or is in the fire-immune set. `burning_time` scales duration. Fire aspect style ignition on hit is a normal ability building block.
- Glowing and invisibility: covered above; note glowing outline color follows the entity team, which pairs with `/team` for alliance coloring.
- Levitation and slow falling: covered above; levitation into slow falling is the standard safe-drop combo, and levitation cancels most ground AI because the mob leaves the floor.
- Damage types: damage is typed (mob attack, projectile, explosion, magic, freeze, fall, and mod-registered types). Types control armor bypass, invulnerability categories, death messages, and which mobs retaliate. 26.2 added `minecraft:sulfur_cube_hot` for hot archetype contact damage.
- Game events and vibrations: actions emit game events that sculk sensors and wardens detect. 26.2 added the `bounce` event. Abilities that should be hearable by a warden emit events; abilities that should be stealthy avoid them.
- Sulfur system (26.2, in both): potent sulfur under 1 to 4 water blocks makes noxious gas (nausea) on the surface; over magma or lava it makes a geyser that erupts about every 50 seconds (continuous over lava), launching entities above it with an upward impulse scaled by water depth, roughly 7 blocks of height per water block, with no eruption damage. The sulfur cube archetype registry (`minecraft:sulfur_cube_archetype`) is data-driven: an archetype defines attribute modifiers, bounciness and friction behavior through those attributes, optional contact damage, optional explosion (fuse, power, causes fire), and knockback modifiers, selected by which block item the cube has absorbed. A mod or data pack can add archetypes.
- Weather and environment hooks: lightning strikes during thunder transform certain mobs (pig to zombified piglin, villager to witch, skeleton horse traps trigger in rain and thunder), creepers become charged. Time, weather, and biome are all readable in code for gated abilities.

## 5. Version Differences: 26.2 vs 26.3

What 26.2 (Chaos Cubed) established, present in both versions:

- Sulfur caves biome, sulfur and cinnabar block sets, sulfur spikes, potent sulfur, geysers, and the sulfur cube mob. The sulfur cube is the only mob added across 26.2 and 26.3 combined.
- New particles: `geyser`, `geyser_base`, `geyser_poof`, `geyser_plume`, `sulfur_cube_goo`, `sulfur_bubbles`, `noxious_gas`, `noxious_gas_cloud`.
- New attributes: `air_drag_modifier`, `friction_modifier`, `bounciness`, `name_tag_distance`, `below_name_distance`. Plus `knockback_resistance` minimum lowered to -2.0.
- New damage type `sulfur_cube_hot`, new game event `bounce`, new item component `sulfur_cube_content`, new registry `sulfur_cube_archetype`, new entity sub-predicate `entity_tags`.
- Entity predicate format was restructured into a component-map-like structure.
- Experimental Vulkan renderer option (OpenGL fallback). Client rendering only; abilities do not depend on it.

What 26.3 (Wilderness Bound) added or changed:

- No new mobs. Confirmed: the 26.3 changelog adds no mob; the new Dappled Forest biome spawns only existing mobs (rabbits, foxes, and the cold variants of farm animals, on top of the usual forest animals). The newest mob in either version is still the sulfur cube from 26.2.
- Dappled Forest content: poplar trees with red, orange, and yellow leaf blocks, fallen poplar trees, the full poplar wood set, red shrubs, and shelf mushrooms (a fungus block entities bounce on, similar to a bed). Abandoned camp structure with a variant per biome (18 variants) and new explorer map items. All of this is world content an ability can reference (blocks, biome, structure), but none of it exists in 26.2 worlds or code.
- New blocks and items: wool stairs and slabs in all 16 colors, concrete stairs and slabs in all 16 colors, straw beds (sleep through the night without setting spawn, break after one use), cushions in 16 colors. The cushion is also a new entity type (a sittable block-like entity) that exists only in 26.3.
- New particles: `red_poplar_leaves`, `orange_poplar_leaves`, `yellow_poplar_leaves`. Change: spruce leaves no longer emit falling leaf particles.
- New commands: `/compute` and `/posteffect` (26.3 only).
- Command changes: `/swing` gained animation and duration arguments and no longer resets player attack strength; `/publish` lost its `gamemode` argument; `/spreadplayers` landing safety now uses the `#entities_can_teleport_to` block tag; `/team join` and `/team leave` result counts only include entities that changed team; `/tick step` and `/tick sprint stop` signal errors; `/data modify` gained a `compute` source.
- Data component changes: the `minecraft:number_provider` type was split into two registries, `minecraft:context_int_provider` and `minecraft:context_float_provider`. Any 26.2 data using `number_provider` must be rewritten for 26.3. New components: `attack_animation` (swing type `whack` or `stab` plus duration), `interact_animation`, `block_transformer` (items that transform blocks on use, now drives axes, hoes, and shovels), `brewing_fuel`, `compostable`, `cooking_fuel`, `mob_visibility` (equippable items change the range at which mobs detect the wearer, 0.0 to 10.0 multiplier), `villager_food`, `waxed`, `sign_text_front`, `sign_text_back`, `cushion/color`, `provides_pottery_pattern`. Removed: `minecraft:swing_animation` (replaced by the `/swing` arguments and the animation components).
- Predicate and data format changes: the predicate field `condition` was renamed to `type` and is now mandatory; a list of predicates is no longer shorthand for `all_of` in advancement triggers; several advancement trigger fields were renamed (`block` to `blocks`, `recipe_id` to `recipes`, `loot_table` to `loot_tables` in the container loot trigger); new predicate types `int_value_check`, `float_value_check`, and the block predicate `height_range`; new entity data field `invulnerable_time` (temporary invulnerability in ticks); cooking recipe `cookingtime` is no longer optional. Data pack format moved 107.1 to 121.0 and resource pack format 88.0 to 97.1, so pack-format-locked JSON needs a version bump and the renamed fields above.
- Game rule storage: game rules are registry entries addressed by namespaced lowercase IDs in both versions (the camelCase names were retired in 1.21.11), and a mod registers new rules by ID. Values saved under the old camelCase names do not carry into the 26.x format, so an ability or mod that reads a rule must use the 26.x ID in both versions.
- Behavior changes with ability impact: persistent mobs random wander and swim now deactivate when no player is nearby, matching non-persistent mobs; spectator players can use portals; new villager trades unlock live while a trade screen is open.
- No attribute, effect, or mob AI changes were found in the 26.3 changelog. The `freezing` effect remains upcoming and absent from both versions.

### Safe to use in both 26.2 and 26.3

- Every command in section 1 except `/compute` and `/posteffect`, using the 26.2 syntax for `/swing` (targets and hand only) if the same function text must run on both.
- All vanilla particles except the three poplar leaf particles (`red_poplar_leaves`, `orange_poplar_leaves`, `yellow_poplar_leaves`).
- The full attribute list in section 4, including the five 26.2 additions, and attribute modifiers with the three standard operations.
- The full status effect list in section 4.
- Block, item, and text display entities with transformation, interpolation, billboard, brightness, glow color override, and shadow fields.
- Scale-based resizing, equipment swaps, custom names and nameplates, glowing, invisibility, teams, tags, boss bars, and scoreboard storage.
- Riding and passenger stacks, `/ride`, typed damage including mod-registered damage types, game events and vibrations.
- All sulfur content: sulfur cube and its data-driven archetypes, potent sulfur gas and geysers, sulfur and cinnabar blocks, sulfur spikes.
- Goal selector and brain edits in code, effect and attribute application in code, custom particle types registered by the mod, custom render states and model layers in code.

### Avoid (26.3 only, or changed between versions)

- `/compute` and `/posteffect`: 26.3 only, they do not exist in 26.2.
- Poplar leaf particles, poplar blocks, red shrubs, shelf mushrooms, Dappled Forest biome checks, abandoned camps, cushions (block, item, and entity), straw beds, wool and concrete stairs and slabs: 26.3 only content.
- `/swing` animation and duration arguments, and the 26.2 `minecraft:swing_animation` component: the mechanism changed sides in 26.3. In code, swing the hand directly.
- `minecraft:number_provider` in data: split into `context_int_provider` and `context_float_provider` in 26.3. Write per-version data or avoid number providers in shared data.
- Predicate JSON using the `condition` field: renamed to `type` in 26.3. Shared predicates must be written per version or produced by code.
- Advancement trigger field names that changed in 26.3 (`block`, `recipe_id`, singular `loot_table`): use the 26.3 names only in 26.3 data.
- Pack format assumptions: data pack 107.1 and resource pack 88.0 metadata will not carry to 26.3 (121.0 and 97.1). Keep version-specific pack metadata out of shared ability logic.
- The `freezing` status effect: not in either version.
- CamelCase game rule names (`keepInventory`, `doMobSpawning`): dead in both versions. Use the snake_case 26.x IDs.
- Spruce falling leaf ambience: present in 26.2, removed in 26.3. Do not build a visual identity on it.
