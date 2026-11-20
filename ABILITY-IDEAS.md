# Advanced Mobs: New Ability Ideas (Version 2, capability verified)

This list replaces version 1. Every ability here was redesigned against a verified reference of what Minecraft 26.2 and 26.3 can actually do (CAPABILITIES.md: every particle, attribute, status effect, and entity type from the real 26.2 jar, plus the wiki command list, display entity and model techniques, and the 26.2 to 26.3 differences). Nothing in this list uses anything that exists in only one of the two versions, and nothing uses a mechanic the research marked unverified.

Each ability ends with a Uses line naming the exact game systems it is built from, so you can see it is buildable before you pick it. None of these are implemented yet.

How to pick: go mob by mob and choose your top 3 of the 5, by number or name, as many mobs per reply as you want. Those picks become the build list, implemented by parallel agents with every ability fully specified in its code comments.

# Advanced Mobs Ability Designs v2: Group A

Grounded only in systems verified for both Minecraft 26.2 and 26.3. Every ability names its building blocks on its Uses line.

## Zombie

1. **Grave Colossus** (Transformation): The zombie swells into a hulking colossus wrapped in rising SOUL wisps and falling dirt, its silhouette doubling in height while gravestone-gray DUST motes drift off its shoulders. Its health pool and melee damage climb with its size, it steps clean over one and two block ledges, and its footsteps shake loose BLOCK_CRUMBLE debris from the ground.
Uses: SCALE attribute, MAX_HEALTH attribute, ATTACK_DAMAGE attribute, STEP_HEIGHT attribute, SOUL particle, DUST particle, BLOCK_CRUMBLE particle

2. **Gravemud Mire** (Terrain): The zombie slams the ground and a ring of churned earth erupts around its target, real dirt and gravel blocks piling into a rough pit wall while FALLING_DUST and mud-brown DUST hang in the air. Enemies inside the mire are dragged down by SLOWNESS and WEAKNESS as the soft ground steals their footing, and the zombie wades through its own mire at full pace.
Uses: /fill and /setblock block placement (dirt, gravel), FALLING_DUST particle, DUST particle, SLOWNESS effect, WEAKNESS effect, MOVEMENT_EFFICIENCY attribute

3. **Seismic Slam** (Attack): The zombie launches itself skyward trailing DUST_PILLAR streaks, then crashes down in a ring-shaped shockwave of EXPLOSION_EMITTER flashes and rolling dust that hurls nearby enemies outward. Anything caught in the ring takes typed fall-style impact damage, and victims briefly have their knockback resistance stripped so the launch sends them fully flying.
Uses: JUMP_STRENGTH attribute, GRAVITY attribute, DUST_PILLAR particle, EXPLOSION_EMITTER particle, /damage typed damage, KNOCKBACK_RESISTANCE attribute modifier on targets, SAFE_FALL_DISTANCE attribute

4. **Rot Aura** (Aura): A permanent miasma of MYCELIUM spores, ASH, and sickly green ENTITY_EFFECT sparkles boils off the zombie and clings to every enemy that stays close. Lingering in the cloud stacks WEAKNESS and HUNGER on victims, so enemy mobs hit softer and softer the longer they brawl with it, while the zombie itself feeds on the rot with slow REGENERATION.
Uses: MYCELIUM particle, ASH particle, ENTITY_EFFECT particle, WEAKNESS effect, HUNGER effect, REGENERATION effect

5. **Grave Eruption** (Mobility): The zombie sinks into a fresh dirt mound, a block display of heaped earth with FALLING_DUST streaming off it, and vanishes from sight while the mound sits still and quiet. Seconds later it bursts out of the ground beside its target in a spray of dirt BLOCK particles and dust, getting one free heavy swing before the enemy reacts.
Uses: block_display entity, FALLING_DUST particle, BLOCK particle, INVISIBILITY effect, /teleport repositioning, STRENGTH effect

## Zombie Villager

1. **Emerald Shrapnel** (Attack): The zombie villager hurls a fan of spinning emeralds, shown as orbiting item displays that streak out trailing HAPPY_VILLAGER sparkles and green CRIT flashes. Each emerald that connects deals typed projectile damage, and enemies hit are marked GLOWING for a few seconds as the shards stick in and glitter.
Uses: item_display entity with interpolation, HAPPY_VILLAGER particle, CRIT particle, /damage typed damage, GLOWING effect

2. **Golden Apple Ward** (Defense): Remembering its own cure, the zombie villager raises a shimmering ward of ENCHANT glyphs and TOTEM_OF_UNDYING motes that swirl into a dome around it. While the ward holds it gains a thick layer of absorption hearts and steady REGENERATION, and the dome sheds gold DUST every time it soaks a hit.
Uses: ENCHANT particle, TOTEM_OF_UNDYING particle, DUST particle, ABSORPTION effect, MAX_ABSORPTION attribute, REGENERATION effect

3. **Bell of the Fallen Village** (Summon): The zombie villager throws its head back and a phantom bell tolls, NOTE particles ringing outward in expanding circles over the battlefield. An iron golem ally lumbers in on the zombie villager team, rust streaked and mossy, and wades straight for whatever the zombie villager is fighting.
Uses: /summon iron_golem, NOTE particle, /team team assignment, FOLLOW_RANGE attribute

4. **Cursed Market** (Terrain): Broken stall frames of planks and a lectern rise from the ground as block displays, forming a crooked little market square dripping with ANGRY_VILLAGER particles. Enemies who wander into the stall zone are fleeced by the curse, gaining MINING_FATIGUE and WEAKNESS, and their attacks slow to a crawl until they escape the market.
Uses: block_display entity, ANGRY_VILLAGER particle, MINING_FATIGUE effect, WEAKNESS effect, ATTACK_SPEED attribute modifier on targets

5. **Frenzied Merchant** (Transformation): The zombie villager snaps into a frenzy, body hunched and twitching under a boil of purple ENTITY_EFFECT and WITCH sparkles, arms flailing at double pace. It gains SPEED and HASTE, its swings come out in a rapid blur, and it keeps a merchant stubbornness that halves the knockback it takes.
Uses: ENTITY_EFFECT particle, WITCH particle, SPEED effect, HASTE effect, KNOCKBACK_RESISTANCE attribute, SCALE attribute

## Husk

1. **Sandblast Barrage** (Attack): The husk exhales a horizontal storm of grit, DUST_PILLAR columns and tan DUST blasting forward in a wide cone with FALLING_DUST sheeting off the top. Enemies caught in the blast take typed damage and are blinded by grit in their eyes, stumbling while the husk closes in.
Uses: DUST_PILLAR particle, DUST particle, FALLING_DUST particle, /damage typed damage, BLINDNESS effect

2. **Quicksand Pit** (Terrain): The ground under the husk target gives way into a real pit of layered sand and gravel, blocks crumbling inward with BLOCK_CRUMBLE and dust as the floor drops out. Victims sink into the pit under heavy SLOWNESS, their jump strength sapped, while the husk skims across the surface on raised MOVEMENT_EFFICIENCY.
Uses: /fill and /setblock block placement (sand, gravel), BLOCK_CRUMBLE particle, SLOWNESS effect, JUMP_STRENGTH attribute modifier on targets, MOVEMENT_EFFICIENCY attribute

3. **Sandstorm Revenant** (Transformation): The husk dissolves into a walking sandstorm, its form wrapped in a permanent cyclone of DUST_PILLAR, WHITE_ASH, and FALLING_DUST that roars around it in a column. It moves at SPEED through its own storm, takes reduced knockback, and enemies who step into the column are battered by typed damage and pushed toward the edges.
Uses: DUST_PILLAR particle, WHITE_ASH particle, FALLING_DUST particle, SPEED effect, KNOCKBACK_RESISTANCE attribute, /damage typed damage

4. **Arid Aura** (Aura): Heat shimmers off the husk in waves of ASH and pale ENTITY_EFFECT motes, and the air inside its aura turns desert dry. Enemies standing in the aura steadily gain HUNGER and SLOWNESS as the dryness saps them, and any water based defense they rely on does nothing to stop it.
Uses: ASH particle, ENTITY_EFFECT particle, HUNGER effect, SLOWNESS effect

5. **Scarab Brood** (Summon): The husk shakes itself apart at the seams and a skittering brood of silverfish bursts out in a spray of sand BLOCK particles, desert scarabs pouring onto the field on the husk team. The brood swarms the husk current target from every side, and the husk itself carries INFESTED so every hit it takes risks shaking loose even more scarabs.
Uses: /summon silverfish, BLOCK particle, /team team assignment, INFESTED effect

## Drowned

1. **Trident Cyclone** (Attack): The drowned spins up a waterspout of BUBBLE, SPLASH, and CURRENT_DOWN particles and hurls a whirling trident through the middle of it. Enemies near the spout are dragged toward its center by the current, and the trident strike deals typed damage with heavy knockback that knocks victims out of the water column.
Uses: BUBBLE particle, SPLASH particle, CURRENT_DOWN particle, /summon trident projectile, /damage typed damage, ATTACK_KNOCKBACK attribute

2. **Rising Tide** (Terrain): Water wells up from the ground in a spreading sheet, real water blocks filling a shallow arena bowl while BUBBLE_COLUMN_UP streams rise through it. In its own tide the drowned swims at full land speed with DOLPHINS_GRACE and WATER_BREATHING, while land mobs flounder at reduced movement in the flood.
Uses: /fill block placement (water), BUBBLE_COLUMN_UP particle, DOLPHINS_GRACE effect, WATER_BREATHING effect, WATER_MOVEMENT_EFFICIENCY attribute, SLOWNESS effect on targets

3. **Call of the Deep** (Summon): A low pressure wave of UNDERWATER and BUBBLE particles rolls out from the drowned, and two guardians answer the call, rising into the fight on the drowned team with their spikes out. The guardians focus the drowned target, and their arrival sends a ring of SPLASH across the surface.
Uses: /summon guardian, UNDERWATER particle, BUBBLE particle, SPLASH particle, /team team assignment

4. **Kelp Snare** (Defense): When pressed, the drowned lashes out with living kelp, real cobweb blocks snapping into place around its attacker feet with ITEM_COBWEB flecks and dripping water. The snared enemy is rooted by strong SLOWNESS and takes WEAKNESS while tangled, giving the drowned room to back off and line up its trident.
Uses: /setblock block placement (cobweb), ITEM_COBWEB particle, DRIPPING_WATER particle, SLOWNESS effect, WEAKNESS effect

5. **Leviathan Form** (Transformation): The drowned swells into a deep sea brute, prismarine shard item displays orbiting its bulk at full brightness while ELECTRIC_SPARK snaps between them. Its scale and strength surge, it gains CONDUIT_POWER so the water itself feeds it, and its eyes burn with a GLOWING outline visible through the murk.
Uses: SCALE attribute, item_display entity with brightness override, ELECTRIC_SPARK particle, STRENGTH effect, CONDUIT_POWER effect, GLOWING effect

## Skeleton

1. **Executioner Arrow** (Attack): The skeleton draws one enormous arrow, an item display warhead that grows and glows with ENCHANT glyphs and CRIT sparkles as the draw stretches longer and longer. When it looses, the single shot hits with massive typed damage and enough ATTACK_KNOCKBACK to throw the victim backward off its feet.
Uses: item_display entity with interpolation, ENCHANT particle, CRIT particle, /summon arrow projectile, /damage typed damage, ATTACK_KNOCKBACK attribute

2. **Headless Cavalry** (Mobility): With a crack of bone BLOCK particles the skeleton summons a skeleton horse and swings up into the saddle in one motion, ENCHANT motes swirling around the pair. Mounted, it gains cavalry SPEED and STEP_HEIGHT, circling enemies at range and riding straight over fences and low walls that would stop it on foot.
Uses: /summon skeleton_horse, /ride passenger stacking, BLOCK particle, ENCHANT particle, SPEED effect, STEP_HEIGHT attribute

3. **Rib Shield** (Defense): A cage of bone blocks assembles around the skeleton as orbiting block displays, ribs rotating slowly with interpolation while bone-white DUST drifts between them. While the shield turns, the skeleton gains RESISTANCE and bonus ARMOR, and every blocked hit knocks a BLOCK_CRUMBLE chip off the ribs instead of off the skeleton.
Uses: block_display entity with interpolation, DUST particle, BLOCK_CRUMBLE particle, RESISTANCE effect, ARMOR attribute

4. **Grave Soil** (Terrain): The skeleton stamps and the battlefield floor sours into soul soil and gravel, real blocks spreading in a patchwork under SOUL wisps and FALLING_DUST. Enemies crossing the grave soil are slowed and have their follow range cut by the clinging dead, losing track of targets beyond the patch.
Uses: /fill block placement (soul soil, gravel), SOUL particle, FALLING_DUST particle, SLOWNESS effect, FOLLOW_RANGE attribute modifier on targets

5. **Ancient Deadeye** (Transformation): The skeleton eyes ignite with a cold GLOWING light and ENCHANT runes crawl across its bow as it swaps in a gleaming enchanted weapon by direct equipment change. Its follow range stretches to extreme distance, every enemy it can see is marked GLOWING through walls, and it picks targets long before they know it is there.
Uses: GLOWING effect, ENCHANT particle, /item replace equipment swap, FOLLOW_RANGE attribute, NIGHT_VISION effect

## Stray

1. **Icicle Fall** (Attack): The stray raises its bow to the sky and icicles form overhead, SNOWFLAKE particles thickening into a glittering ceiling before falling block icicles of packed ice rain onto its target area. Each icicle deals typed freeze damage on impact and shatters into BLOCK ice shards and WHITE_SMOKE.
Uses: /summon falling_block (packed ice), SNOWFLAKE particle, BLOCK particle, WHITE_SMOKE particle, /damage freeze damage type

2. **Perpetual Frost** (Terrain): A sheet of packed ice spreads under the stray as real blocks, SNOWFLAKE drifting down across a rink of its own making. The stray sets its own ground friction to nearly nothing and glides like a skater, while enemies given the same slippery footing slide past their turns and struggle to line up hits.
Uses: /fill block placement (packed ice), SNOWFLAKE particle, FRICTION_MODIFIER attribute, MOVEMENT_SPEED attribute

3. **Frozen Carapace** (Defense): Plates of blue ice grow over the stray body as block displays with full brightness, locking together with SCRAPE flashes and curls of WHITE_SMOKE. The carapace grants heavy ARMOR and explosion knockback resistance, and melee attackers who strike it are chilled with SLOWNESS by the cold bleeding off the plates.
Uses: block_display entity with brightness override, SCRAPE particle, WHITE_SMOKE particle, ARMOR attribute, EXPLOSION_KNOCKBACK_RESISTANCE attribute, SLOWNESS effect on attackers

4. **Howling Blizzard** (Aura): A personal blizzard orbits the stray, SNOWFLAKE and ASH whipping in a horizontal ring that pulses outward in waves. Each pulse washes over nearby enemies with SLOWNESS and a flash of BLINDNESS, so anything trying to brawl inside the storm swings at shapes in the whiteout.
Uses: SNOWFLAKE particle, ASH particle, SLOWNESS effect, BLINDNESS effect

5. **Wind Rider** (Mobility): The stray catches a rising gust, SMALL_GUST and GUST spirals coiling under it as it launches into the air and rides the wind across the field. It lands feather soft under SLOW_FALLING, having crossed ground, gaps, and enemy lines that no walker could pass.
Uses: SMALL_GUST particle, GUST particle, LEVITATION effect, SLOW_FALLING effect, AIR_DRAG_MODIFIER attribute

## Bogged

1. **Sporeburst Arrow** (Attack): The bogged fires a dripping arrow that bursts on impact into a choking cloud of CRIMSON_SPORE and WARPED_SPORE, green and teal spores rolling across the ground. Enemies caught in the cloud take POISON and NAUSEA as the spores get into them, and the cloud lingers as a hazard patch for several seconds.
Uses: /summon arrow projectile, CRIMSON_SPORE particle, WARPED_SPORE particle, POISON effect, NAUSEA effect

2. **Fungal Spawn** (Summon): Lumps on the bogged back split open with MYCELIUM puffs and two small slimes ooze out, fungal minions already bouncing toward the bogged target on its team. The parent bogged sheds FALLING_SPORE_BLOSSOM motes the whole time it is spawning, marking the birth patch as claimed ground.
Uses: /summon slime, MYCELIUM particle, FALLING_SPORE_BLOSSOM particle, /team team assignment

3. **Bog Mire** (Terrain): The ground softens into real mud and shallow water patches under BUBBLE and brown DUST, the bogged home turf spreading in a wet stain. Enemies in the mire sink into SLOWNESS and lose the benefit of their movement efficiency, while the bogged own steps stay light and quick across the muck.
Uses: /fill and /setblock block placement (mud, water), BUBBLE particle, DUST particle, SLOWNESS effect, MOVEMENT_EFFICIENCY attribute modifier on targets

4. **Shelf Fungus Armor** (Defense): Broad mushroom shelves sprout across the bogged shoulders and skull as block displays, SPORE_BLOSSOM_AIR drifting lazily off the caps. The fungal plating adds ARMOR_TOUGHNESS so heavy hits lose their bite, and every blow that lands shakes loose a puff of spores that applies weak POISON to the attacker.
Uses: block_display entity, SPORE_BLOSSOM_AIR particle, ARMOR_TOUGHNESS attribute, POISON effect on attackers

5. **Ancient Bog Thing** (Transformation): The bogged rises into a towering swamp horror, scale swelling as mossy FALLING_SPORE_BLOSSOM and MYCELIUM cascade off its frame and its bow creaks with new STRENGTH. Standing in water or mire it knits itself back together with REGENERATION, and its silhouette glows faintly GLOWING through the swamp murk.
Uses: SCALE attribute, FALLING_SPORE_BLOSSOM particle, MYCELIUM particle, STRENGTH effect, REGENERATION effect, GLOWING effect

## Wither Skeleton

1. **Wither Brand** (Attack): The wither skeleton strikes a brand onto its victim, a searing black mark of DUST_COLOR_TRANSITION motes fading from gray to black with a coal item display burning above the target head. While the brand lasts the victim suffers WITHER, and when the brand burns out it detonates in a small burst of INSTANT_DAMAGE and SOUL wisps.
Uses: DUST_COLOR_TRANSITION particle, item_display entity, WITHER effect, INSTANT_DAMAGE effect, SOUL particle, /tag state marking

2. **Obsidian Bones** (Defense): The wither skeleton bones blacken to obsidian density, BLOCK obsidian chips flaking off with every hit it takes and ASH curling off its frame. Its ARMOR and ARMOR_TOUGHNESS climb sharply and it gains RESISTANCE, turning it into a wall that trades blows and wins on attrition.
Uses: BLOCK particle, ASH particle, ARMOR attribute, ARMOR_TOUGHNESS attribute, RESISTANCE effect

3. **Fallen Captain** (Summon): The wither skeleton plants its sword and calls up one hulking lieutenant, a wither skeleton summoned at enlarged scale with SOUL_FIRE_FLAME wreathing its blade, arriving on the same team in a burst of SOUL wisps. The captain wades straight into the thickest fighting and soaks attention the summoner does not want.
Uses: /summon wither_skeleton, SCALE attribute, SOUL particle, SOUL_FIRE_FLAME particle, /team team assignment

4. **Ashen Advance** (Terrain): Every step the wither skeleton takes in this state scorches the earth, real fire blocks blooming along its path over a trail of blackstone set into the ground, ASH and SOUL_FIRE_FLAME streaming behind it. Enemies who cross the burning trail catch fire with extended BURNING_TIME, and the lane of flame cuts the battlefield in two.
Uses: /setblock block placement (fire, blackstone), ASH particle, SOUL_FIRE_FLAME particle, BURNING_TIME attribute modifier on targets

5. **Wither Herald** (Transformation): The wither skeleton becomes a herald of decay, DARKNESS pulsing out from it in waves with SHRIEK particles streaking through the gloom and black DUST rolling off its enlarged frame. Enemies near it are intermittently blinded by the darkness pulses, it gains STRENGTH, and its own outline burns GLOWING white against the dark it makes.
Uses: DARKNESS effect, SHRIEK particle, DUST particle, SCALE attribute, STRENGTH effect, GLOWING effect

## Creeper

1. **Cluster Detonation** (Attack): Instead of one blast, the creeper primary explosion seeds a chain of secondary charges across the area, EXPLOSION and EXPLOSION_EMITTER flashes popping in sequence as each pocket of gunpowder cooks off a heartbeat after the last. Enemies who dodge the first blast get caught by the rolling follow ups, and the chained pattern tears a wider, uneven crater than any single explosion.
Uses: EXPLOSION particle, EXPLOSION_EMITTER particle, /summon tnt, /damage explosion damage type, /schedule delayed phases

2. **Fuse Rocket** (Mobility): The creeper lights its own fuse as a rocket motor, EXPLOSION_EMITTER blasting from its feet in a cone of SMOKE and FLAME as it launches in a screaming arc across the field. It crosses gaps and enemy lines in the air, then comes down under SLOW_FALLING control and detonates on landing right in the middle of whatever it aimed at.
Uses: EXPLOSION_EMITTER particle, SMOKE particle, FLAME particle, motion via velocity in code, SLOW_FALLING effect, GRAVITY attribute

3. **Camouflage Bloom** (Defense): The creeper freezes and vanishes into the foliage, its body hidden while TINTED_LEAVES and CHERRY_LEAVES drift down around the spot where it stood and a leafy block display rustles in its place. While bloomed it quietly builds ABSORPTION, and enemies wander past a bush that is watching them.
Uses: INVISIBILITY effect, TINTED_LEAVES particle, CHERRY_LEAVES particle, block_display entity, ABSORPTION effect

4. **Gunpowder Sprites** (Summon): The creeper sheds two small green slimes, living gunpowder blobs that hop after its enemies trailing SMOKE and green DUST, swelling as they go. When a sprite reaches its target or is killed, it pops in a compact explosion, and the parent creeper stays at a safe distance watching its work.
Uses: /summon slime, SMOKE particle, DUST particle, SCALE attribute, /team team assignment, explosion on death via code

5. **Charged Stormform** (Transformation): A bolt from the sky remakes the creeper, lightning striking it in a FLASH of white as the vanilla charged state takes hold and a crackling blue aura sheathes its body. In stormform it moves at SPEED, ELECTRIC_SPARK arcs snap off it constantly, and its outline GLOWING electric blue can be seen through walls and weather.
Uses: /summon lightning_bolt, FLASH particle, charged creeper state, SPEED effect, ELECTRIC_SPARK particle, GLOWING effect

## Spider

1. **Strangling Web Shot** (Attack): The spider fires a mass of silk that slams into its target and instantly sets into real cobweb blocks around the victim, ITEM_COBWEB flecks bursting on impact. The wrapped enemy is dragged toward the spider and pinned under heavy SLOWNESS, set up for the spider to close and bite at its leisure.
Uses: /setblock block placement (cobweb), ITEM_COBWEB particle, SLOWNESS effect, motion via velocity in code

2. **Brood Eggs** (Summon): The spider backs onto a wall or corner and lays a cluster of pale egg block displays, glistening sacs that pulse slowly with interpolation as something moves inside them. After a short incubation the sacs hatch into baby spiders at half scale on the mother team, and the brood pours straight toward the nearest enemy.
Uses: block_display entity with interpolation, /schedule delayed phases, /summon spider, SCALE attribute, /team team assignment

3. **Web Fortress** (Terrain): The spider weaves a fortress in seconds, real cobweb blocks stringing across gaps and doorways in a layered lattice while ITEM_COBWEB motes drift off the fresh silk. The spider itself carries WEAVING and skims across its own webs nearly unhindered, while enemies pushing through are slowed to a crawl and announced by their struggling.
Uses: /fill and /setblock block placement (cobweb), ITEM_COBWEB particle, WEAVING effect, SLOWNESS effect on targets

4. **Egg Sac Shield** (Defense): A grossly swollen egg sac grows over the spider back as layered block displays, white and veined, absorbing attention and blows alike. The sac grants a deep pool of ABSORPTION, and when it finally ruptures it sprays ITEM_COBWEB and silk DUST across attackers, slowing them in the mess.
Uses: block_display entity, ABSORPTION effect, MAX_ABSORPTION attribute, ITEM_COBWEB particle, DUST particle, SLOWNESS effect on attackers

5. **Web Slinger** (Mobility): The spider fires a strand at a distant point, END_ROD motes tracing the silk line through the air, and reels itself along it in a swinging arc that carries it over walls, pits, and enemy heads. It arrives with JUMP_BOOST legs already coiled, ready to drop straight onto whatever waits at the anchor point.
Uses: END_ROD particle, motion via velocity in code, JUMP_BOOST effect, STEP_HEIGHT attribute, /teleport anchor repositioning

## Cave Spider

1. **Neurotoxin Webbing** (Attack): The cave spider bites and weaves in the same motion, wrapping its victim in real cobweb while a venom tailored far beyond the old poison takes hold, WITCH sparkles of violet and green crawling over the wrapped target. The victim suffers POISON alongside MINING_FATIGUE and WEAVING, so it sickens, slows, and cannot even dig itself free at speed.
Uses: /setblock block placement (cobweb), WITCH particle, POISON effect, MINING_FATIGUE effect, WEAVING effect

2. **Silk Minefield** (Terrain): The cave spider strings its hunting ground with near invisible tripwire, real string and cobweb patches laid low across the floor with only the odd DUST mote giving them away. An enemy that crosses a wire triggers a NOTE chime only the spider side hears, gets a burst of SLOWNESS, and reveals itself GLOWING through the dark.
Uses: /setblock block placement (string, cobweb), DUST particle, NOTE particle, SLOWNESS effect, GLOWING effect, /tag state marking

3. **Chitin Plating** (Defense): The cave spider hunkers and its exoskeleton thickens into dark plating, SCRAPE sparks flying as it grinds its legs against the stone to settle the armor in. It shrinks slightly, presenting a smaller target, while ARMOR_TOUGHNESS climbs and blows that used to crush it start glancing off.
Uses: SCRAPE particle, ARMOR_TOUGHNESS attribute, SCALE attribute, RESISTANCE effect

4. **Cave Skitter** (Mobility): In short terrifying bursts the cave spider becomes a blur, SPEED and JUMP_BOOST firing together as its scale drops and it pours through one block gaps and over rubble that stops bigger mobs. Each burst kicks up a small DUST_PILLAR and ends with the spider already on the far side of the obstacle, legs still moving.
Uses: SPEED effect, JUMP_BOOST effect, SCALE attribute, DUST_PILLAR particle, STEP_HEIGHT attribute

5. **Venom Mist** (Aura): The cave spider breathes out a low hanging mist, ENTITY_EFFECT and WITCH motes in venom green pooling around it and spilling downhill across the cave floor. Enemies standing in the mist gain NAUSEA as the fumes turn their stomachs, and the longer they stay the more the mist eats at them with a weak POISON that keeps refreshing.
Uses: ENTITY_EFFECT particle, WITCH particle, NAUSEA effect, POISON effect

## Enderman

1. **Ender Spike Field** (Attack): The enderman tears the ground open along a line toward its target, obsidian block displays spearing up out of the earth one after another with interpolation, PORTAL particles screaming off the rising spikes. Enemies standing on the line take typed piercing damage as the spikes punch through, and the field stays up briefly as a jagged wall that blocks movement.
Uses: block_display entity with interpolation, PORTAL particle, /damage typed damage, REVERSE_PORTAL particle

2. **Obsidian Carapace** (Defense): Shards of obsidian fold out of the void and lock into a rotating shell around the enderman, block displays orbiting at full brightness with REVERSE_PORTAL wisps leaking between the plates. The shell grants RESISTANCE and strong EXPLOSION_KNOCKBACK_RESISTANCE, so blasts and heavy hits wash over the plates instead of moving the enderman at all.
Uses: block_display entity with brightness override, REVERSE_PORTAL particle, RESISTANCE effect, EXPLOSION_KNOCKBACK_RESISTANCE attribute

3. **Mite Broodlord** (Summon): The enderman connection to its parasites turns deliberate, PORTAL bursts along its body shedding endermites that pour onto the field on its team whenever it is pressed in a fight. The mites swarm ankles and draw blades, and the enderman fights from behind its own crawling screen.
Uses: /summon endermite, PORTAL particle, /team team assignment, INFESTED style on-hit spawning via code

4. **Void Titan** (Transformation): The enderman unfolds to a terrible height, scale doubling as DRAGON_BREATH spills around its feet and chorus fruit item displays orbit its head like a crown of the End. It gains STRENGTH and a follow range that spans the arena, and its eyes blaze GLOWING violet through any darkness or wall.
Uses: SCALE attribute, DRAGON_BREATH particle, item_display entity, STRENGTH effect, FOLLOW_RANGE attribute, GLOWING effect

5. **Dimensional Anchor** (Mobility): The enderman plants an anchor in the world, an end stone block display crowned with an END_ROD beacon and a column of PORTAL motes, marking a point it owns. At will it can snap back to the anchor in a burst of REVERSE_PORTAL, crossing the whole battlefield instantly, then stride back into the fight from its chosen ground.
Uses: block_display entity, END_ROD particle, PORTAL particle, REVERSE_PORTAL particle, /teleport repositioning, /tag state marking

## Endermite

1. **Infestation Bite** (Attack): The endermite bite carries the void taint, purple ENTITY_EFFECT boiling off the wound as the victim is tagged with INFESTED. Every hit the victim takes afterward risks silverfish bursting out of its own body, turning the enemy own injuries into a swarm problem while the mite scuttles clear.
Uses: ENTITY_EFFECT particle, INFESTED effect, /tag state marking

2. **Call the Cracks** (Summon): The endermite chitters and the stone itself answers, three silverfish bursting out of the ground in sprays of stone BLOCK particles and BLOCK_CRUMBLE, already angry and already on the mite team. The cracks seal behind them, leaving a fresh ambush squad where bare rock stood a moment before.
Uses: /summon silverfish, BLOCK particle, BLOCK_CRUMBLE particle, /team team assignment

3. **Living Stone Shell** (Defense): The endermite vanishes into a stone block display that settles onto the ground with a small puff of dust, indistinguishable from rubble while it sits still. Inside the shell it holds ARMOR and slow REGENERATION, and enemies walk right past a rock that is quietly healing and waiting.
Uses: block_display entity, DUST particle, INVISIBILITY effect, ARMOR attribute, REGENERATION effect

4. **Flicker Hop** (Mobility): The endermite moves in stuttering hops, each short teleport leaving a fading endermite silhouette item display hanging in the air behind it amid PORTAL motes. The trail of afterimages makes its path unreadable, and it crosses rooms, walls of enemies, and gaps in a blink of hops no pursuer can follow.
Uses: /teleport repositioning, item_display entity, PORTAL particle, SPEED effect

5. **Gorged Mite** (Transformation): Every bite the endermite lands makes it swell, scale creeping upward in visible steps with ENTITY_EFFECT steam hissing off its growing shell, until the ankle biter stands at twice its birth size. Its ATTACK_DAMAGE grows with its body, and at full gorge it gains KNOCKBACK_RESISTANCE, a tiny titan that refuses to be swatted away.
Uses: SCALE attribute, ENTITY_EFFECT particle, ATTACK_DAMAGE attribute, KNOCKBACK_RESISTANCE attribute

## Silverfish

1. **Stoneborer Ambush** (Attack): The silverfish vanishes into the floor and erupts directly beneath its target, stone BLOCK_CRUMBLE and BLOCK shards blasting upward as it strikes from below. The victim takes typed damage and gains MINING_FATIGUE from the shock of the burst, suddenly unable to dig or fight its way down to where the fish went.
Uses: BLOCK_CRUMBLE particle, BLOCK particle, /damage typed damage, MINING_FATIGUE effect, INVISIBILITY effect

2. **Infested Network** (Summon): The silverfish seeds itself with INFESTED, its stone gray body crawling with ENTITY_EFFECT motes, so that every wound it takes shakes loose one to three more silverfish onto its team. Hurting it becomes a losing trade, since each hit grows the swarm that is eating the attacker.
Uses: INFESTED effect, ENTITY_EFFECT particle, /summon silverfish via effect trigger, /team team assignment

3. **Rubble Form** (Defense): The silverfish flattens into a crack in a stone block display that rides the ground where it hides, perfectly still and perfectly ordinary to look at. While in rubble form it holds strong ARMOR and RESISTANCE, and it can hold the disguise until an enemy steps within striking range.
Uses: block_display entity, INVISIBILITY effect, ARMOR attribute, RESISTANCE effect

4. **Tunnel Collapse** (Terrain): The silverfish chews through the supports and the ceiling comes down, falling block gravel and stone raining onto its enemies with FALLING_DUST choking the air as real blocks are eaten out from under the floor above. Victims take typed falling damage in the collapse and are left SLOWED, digging themselves out of the rubble.
Uses: /summon falling_block (gravel, stone), /setblock block removal, FALLING_DUST particle, /damage fall damage type, SLOWNESS effect

5. **Hive Mind Link** (Aura): The silverfish becomes a relay for the swarm, ENCHANT glyphs streaming between it and every allied silverfish and endermite in range in a visible web of light. Linked allies gain SPEED and STRENGTH while they stay near the relay, turning a scattered swarm into a coordinated pack with one small fish at its heart.
Uses: ENCHANT particle, SPEED effect, STRENGTH effect, /team team assignment

## Slime

1. **Meteor Slam** (Attack): The slime launches itself into the sky, ITEM_SLIME flecks shedding off its body as it climbs, hangs at the peak, then slams down with multiplied gravity in a DUST_PILLAR ring and an EXPLOSION_EMITTER flash. The landing deals typed impact damage in a radius, hurls survivors outward, and costs the slime nothing thanks to its negated fall damage.
Uses: ITEM_SLIME particle, JUMP_STRENGTH attribute, GRAVITY attribute, DUST_PILLAR particle, EXPLOSION_EMITTER particle, /damage typed damage, FALL_DAMAGE_MULTIPLIER attribute

2. **Royal Jelly** (Transformation): The slime swells into a royal mass, scale climbing toward triple size as a golden crown item display settles above its head at full brightness and ITEM_SLIME motes drip from its bulk. Its MAX_HEALTH balloons with its body, its bounces carry real ATTACK_KNOCKBACK, and every landing is a minor earthquake.
Uses: SCALE attribute, item_display entity with brightness override, ITEM_SLIME particle, MAX_HEALTH attribute, ATTACK_KNOCKBACK attribute

3. **Gelatinous Split** (Defense): When a heavy blow lands, the slime does not die, it divides, bursting into ITEM_SLIME spray as two healthy medium slimes shear off its body and bounce straight back into the fight on its team. It also carries OOZING, so even a killing blow leaves two more slimes standing in the puddle where it fell.
Uses: /summon slime, ITEM_SLIME particle, OOZING effect, /team team assignment

4. **Superball Mode** (Mobility): The slime physics go feral, its bounciness maxed and air drag slashed so it ricochets off walls, floors, and enemies in a pinball streak of ITEM_SLIME. Every bounce off an enemy deals contact knockback, and the slime crosses arenas in seconds along paths no one can predict or intercept.
Uses: BOUNCINESS attribute, AIR_DRAG_MODIFIER attribute, ITEM_SLIME particle, ATTACK_KNOCKBACK attribute, bounce game event

5. **Slime Slick** (Terrain): The slime leaves a trail of real slime blocks behind it, glistening patches set into the ground wherever it bounces, ITEM_SLIME glistening on the fresh deposits. Enemies who step on the slick are given uncontrolled bounciness of their own, bouncing helplessly with every step and losing all formation while the slime watches from solid ground.
Uses: /setblock block placement (slime block), ITEM_SLIME particle, BOUNCINESS attribute modifier on targets, FRICTION_MODIFIER attribute

## Magma Cube

1. **Magma Mortar** (Attack): The magma cube lobs arcing fireballs that burst on impact in FALLING_LAVA and FLAME, splashing real fire blocks across the impact point. Targets caught in the splash catch fire with extended BURNING_TIME, and the burning patch keeps hurting anything that stands in it.
Uses: /summon fireball, FALLING_LAVA particle, FLAME particle, /setblock block placement (fire), BURNING_TIME attribute modifier on targets

2. **Lava Bloom** (Terrain): The ground cracks open around the magma cube and real lava wells up in a spreading bloom, DRIPPING_LAVA and FALLING_LAVA sheeting down the edges as pools form and link together. The cube bathes in its own bloom untouched, gaining SPEED across the magma crust at the pool edges, while enemies must flee the growing lake or burn.
Uses: /fill and /setblock block placement (lava, magma block), DRIPPING_LAVA particle, FALLING_LAVA particle, FIRE_RESISTANCE effect, SPEED effect

3. **Obsidian Crust** (Defense): With every hit the magma cube crust thickens, magma block displays orbiting its body and locking together as LAVA drips between the plates. Its ARMOR climbs the longer the fight runs and its EXPLOSION_KNOCKBACK_RESISTANCE rises to match, so sustained focus fire plates it faster than it wounds it.
Uses: block_display entity, LAVA particle, ARMOR attribute, EXPLOSION_KNOCKBACK_RESISTANCE attribute

4. **Eruption Leap** (Mobility): The magma cube detonates the ground beneath itself, EXPLOSION and FLAME blasting it skyward in a volcanic launch that carries it clear across the arena. It comes down in a ring of fire blocks and LAVA spray, landing already fighting in the middle of ground the enemy thought was safe.
Uses: EXPLOSION particle, FLAME particle, LAVA particle, motion via velocity in code, /setblock block placement (fire), FALL_DAMAGE_MULTIPLIER attribute

5. **Volcano Core** (Transformation): The magma cube cracks open along glowing seams, SMALL_FLAME and LAVA boiling out of its core as its scale climbs and its outline burns GLOWING orange through smoke and walls. Enemies who stay near the core are set alight by the radiant heat, and the cube own STRENGTH rises with its temperature until it hits like a landslide.
Uses: SMALL_FLAME particle, LAVA particle, SCALE attribute, GLOWING effect, STRENGTH effect, ignition via fire ticks in code

# Advanced Mobs: New Ability Ideas v2 (Group B, grounded in verified 26.2 and 26.3 capabilities)

## Blaze

1. **Solar Crucible** (Transformation): The blaze swells to one and a half times its size, its rods glowing white hot under a constant storm of FLAME and SMALL_FLAME particles while a ring of fire orbits its body. In this form its fireballs hit harder, it shrugs off knockback, and every few seconds it sheds a burst of embers that ignites nearby enemies. The transformation lasts until the blaze has fired ten volleys, then it vents a pillar of LARGE_SMOKE and shrinks back down.
Uses: SCALE attribute, ATTACK_DAMAGE attribute, KNOCKBACK_RESISTANCE attribute, FLAME particle, SMALL_FLAME particle, LARGE_SMOKE particle, ignition via fire ticks and BURNING_TIME attribute, custom render state for the glowing rods

2. **Magma Ring Rising** (Terrain): The blaze slams the ground and a ring of netherrack and magma blocks erupts in a circle around the fight, dripping with DRIPPING_LAVA particles and sealing enemies inside with it. Anything that climbs over or lingers on the magma ring burns, and the blaze inside gains clear lines of fire across the arena. The ring crumbles back to debris after half a minute.
Uses: block placement in code (netherrack, magma block), DRIPPING_LAVA particle, BLOCK_CRUMBLE particle with magma block state, fire damage from magma blocks

3. **Cinder Meteor Volley** (Attack): The blaze rises high, marks three spots on the ground with glowing red DUST circles, and calls down a volley of fireballs from directly above each mark. Each meteor trails SMOKE and bursts on impact with an EXPLOSION flash, leaving a patch of real fire behind. Enemies who read the circles can dodge, but standing still is fatal.
Uses: summoned fireball projectiles, DUST particle with color data, SMOKE particle, EXPLOSION particle, fire block placement

4. **Ashen Veil** (Defense): The blaze detonates a choking cloud of ASH and LARGE_SMOKE and vanishes inside it, invisible except for the faint glow of its rods through the murk. While veiled it gains RESISTANCE and drifts silently to a new position, and enemies inside the cloud cough up ASH particles and lose track of it completely. The veil thins over a few seconds, revealing the blaze already winding up its next volley.
Uses: ASH particle, LARGE_SMOKE particle, INVISIBILITY effect, RESISTANCE effect, repositioning in code

5. **Rod Aegis** (Summon, construct): Six blazing rods detach and hang in the air as item displays, orbiting the blaze in a slow burning halo that intercepts incoming projectiles and burns any melee attacker who connects. Each rod display flares with FLAME particles when it blocks a hit and shatters after absorbing two, raining ITEM debris of blaze rod fragments. The blaze can rebuild the aegis only after a long cooldown, so bursting the rods down opens it up.
Uses: item_display entities with blaze rod contents, transformation interpolation for the orbit, FLAME particle, ITEM particle with blaze rod stack, projectile interception in code

## Ghast

1. **Wailing Shockwave** (Attack, aura): The ghast inhales until its body swells, then unleashes a scream that rolls outward as visible rings of DUST_PLUME and CLOUD, shoving every enemy back with heavy knockback and deflecting projectiles mid-flight. The scream leaves a brief silence, then a second smaller ring follows and knocks enemies off ledges they were pushed toward. Its face stays locked in the scream pose for the whole cast, trailing DRIPPING_WATER tears.
Uses: DUST_PLUME particle, CLOUD particle, knockback applied in code, DRIPPING_WATER particle, custom render state for the scream pose

2. **Colossal Grief** (Transformation): The ghast balloons to two and a half times its normal size, a true sky-whale blotting out the light, with MAX_HEALTH to match and tears streaming constantly from its eyes. Its fireballs become siege meteors with doubled blast radius, but it flies slower and turns like a ship, and the whole arena can hear its crying from anywhere. When the form ends it deflates in a rush of CLOUD particles and is briefly vulnerable.
Uses: SCALE attribute, MAX_HEALTH attribute, FLYING_SPEED attribute, DRIPPING_WATER particle, CLOUD particle, summoned fireball projectiles with larger explosion power

3. **Mirror Ghasts** (Summon, decoy): The ghast splits the sky into three: two phantom ghasts appear beside it, identical at a glance, while the real one turns invisible for a few seconds. The phantoms are real summoned ghasts with a single point of health, no fireballs of their own, and a GLOWING shimmer only visible up close, and they drift and wail exactly like the original. Enemies waste their biggest hits on the fakes, and each phantom pops into POOF and a spray of ghast tear ITEM particles when destroyed.
Uses: /summon ghast with MAX_HEALTH base set low and ATTACK_DAMAGE base set to zero, INVISIBILITY effect on the original, GLOWING effect, POOF particle, ITEM particle with ghast tear stack

4. **Nether Rift Maw** (Terrain, control): The ghast opens a swirling vortex of PORTAL and REVERSE_PORTAL particles beneath its enemies that drags everything on the ground slowly toward its center, where the ghast focuses its fireballs. The pull is weak enough to fight against but strong enough to ruin aim and bunch enemies together for one big blast. After several seconds the rift snaps shut with a FLASH and a final inward yank.
Uses: PORTAL particle, REVERSE_PORTAL particle, FLASH particle with ARGB color data, velocity applied in code, fireball projectiles

5. **Ember Wake Descent** (Mobility, attack): The ghast dives low across the arena trailing a wake of FLAME, SMOKE, and falling fire charges, carpet-bombing a burning line across the ground as it passes. The burning line is real fire blocks that linger and cut the arena in two, forcing enemies to cross flames or bunch on one side. It climbs back to altitude with a burst of speed once the pass completes.
Uses: FLAME particle, SMOKE particle, fire block placement, summoned fire charge projectiles, FLYING_SPEED attribute

## Wither

1. **Cataclysm Skullstorm** (Attack): The wither rears all three heads and unleashes a rolling storm of wither skulls, the blue charged ones mixed among the black, each trailing SOUL wisps as the barrage walks across the arena. Impacts burst with EXPLOSION flashes and leave enemies withering, and the storm is dense enough that cover and distance are the only real answers. The heads keep firing in sequence so the volley comes in waves rather than one burst.
Uses: summoned wither skull projectiles, SOUL particle, EXPLOSION particle, WITHER effect, typed damage in code

2. **Soul Harvest Bulwark** (Transformation, defense): At half health the wither enters its armored phase as a ring of wither skeleton skull block displays rises to orbit it, trailing SCULK_SOUL wisps and intercepting projectiles. Its ARMOR and explosion resistance max out, it grows larger, and every enemy it kills feeds a soul counter shown on its boss bar, each soul buying it a sliver of health back. The orbiting skulls shatter one by one under focused fire, so the shield is strong but finite.
Uses: block_display entities with wither skeleton skull block state, transformation interpolation, SCULK_SOUL particle, ARMOR attribute, EXPLOSION_KNOCKBACK_RESISTANCE attribute, SCALE attribute, boss bar for the soul counter

3. **Graveyard Bloom** (Terrain): The wither poisons the earth itself: the ground in a wide radius converts to soul sand and soul soil dotted with wither roses, while pillars of SOUL_FIRE_FLAME gutter up between the graves. Enemies crossing the graveyard are slowed by the soul sand and withered by the field, and the wither fights noticeably faster and meaner on its own cursed ground. The bloom fades from the edges inward once the wither leaves or dies.
Uses: block placement in code (soul sand, soul soil, wither rose), SOUL_FIRE_FLAME particle, SLOWNESS effect, WITHER effect

4. **Death Nova** (Attack, aura): The wither charges with a rising whine of SCULK_CHARGE_POP sparks and DUST_COLOR_TRANSITION motes shifting from black to violet, then detonates a nova that flattens weak blocks, hurls enemies outward, and leaves a crater of cracked ground. The nova telegraphs long enough to run from, but it also destroys the cover enemies were hiding behind. Each nova makes the next one charge a little faster.
Uses: SCULK_CHARGE_POP particle, DUST_COLOR_TRANSITION particle with color data, EXPLOSION_EMITTER particle, block destruction in code, knockback in code

5. **Revenant Champions** (Summon): Three wither skeletons claw up out of the graveyard soil, named, glowing with the wither's team color, and geared as a proper honor guard: one with a stone sword, one with a bow, one with both and a shield. They fight with STRENGTH and HEALTH_BOOST, and while any champion lives the wither takes reduced damage, linking its survival to theirs in a beam of SOUL particles. When the last champion falls the wither screams and fires one desperate skull volley.
Uses: /summon wither_skeleton with equipment and custom names, GLOWING effect with team color, STRENGTH effect, HEALTH_BOOST effect, SOUL particle, damage reduction in code

## Ender Dragon

1. **Crystal Resonance Cascade** (Attack, aura): The dragon roars and visible beams of END_ROD light lance out to every surviving end crystal, which begin to pulse in time with her heartbeat. Each pulse fires a volley of DRAGON_BREATH orbs down at the enemies below, turning her healing network into an artillery grid. Destroying a crystal breaks its beam in an EXPLOSION and staggers the cascade, so the crystals become targets under fire.
Uses: END_ROD particle, DRAGON_BREATH particle with power data, EXPLOSION particle, existing end crystal entities, beam rendering in code

2. **Fountain Spike Slam** (Terrain, attack): When the dragon perches, she slams the fountain with both claws and a ring of jagged obsidian spikes erupts from the end stone in a widening circle, hurling enemies skyward and shredding anyone standing close. The spikes are real obsidian blocks that linger as temporary cover before crumbling away in BLOCK debris. A DUST_PILLAR marks each spike line a split second before it erupts, giving sharp-eyed enemies a chance to move.
Uses: block placement in code (obsidian), DUST_PILLAR particle, BLOCK_CRUMBLE particle with obsidian state, knockback in code

3. **Void Dive** (Mobility, attack): The dragon folds her wings and plunges through a tear of PORTAL and REVERSE_PORTAL particles, vanishing from one end of the island and bursting out of a second tear at full speed behind her enemies. Her dive trail lays a lingering river of DRAGON_BREATH cloud across the sky that rains purple damage on anything flying or standing tall beneath it. She exits the dive faster than her normal flight, wings streaming ENCHANT sparkles.
Uses: teleport in code, PORTAL particle, REVERSE_PORTAL particle, DRAGON_BREATH particle with power data, FLYING_SPEED attribute, ENCHANT particle

4. **Scale of the End** (Transformation): Below a third of her health the dragon enrages: she grows half again as large, her eyes blaze through a custom render state, and ENCHANT runes stream continuously off her wings. Her melee damage and knockback climb sharply and her breath attacks come twice as often, but her bigger body is a bigger target and her perch slams shake the whole island. This is the final stand form and it does not end until she does.
Uses: SCALE attribute, ATTACK_DAMAGE attribute, ATTACK_KNOCKBACK attribute, ENCHANT particle, custom render state for the eyes and wing runes

5. **Breathfire Ring** (Attack, terrain): Instead of a single fireball, the dragon spits a spreading fan of dragon fireballs that land in a great circle and bloom into a connected ring wall of lingering DRAGON_BREATH cloud. Enemies inside the ring are trapped with her ground attacks, enemies outside are cut off from helping, and crossing the wall means wading through breath damage. The ring burns for several seconds before thinning into harmless purple mist.
Uses: summoned dragon fireball projectiles, lingering breath clouds via area effect cloud entities, DRAGON_BREATH particle with power data

## Guardian

1. **Prismarine Spike Mantle** (Defense, transformation): The guardian flares its spikes to full extension and locks them there, its body hardening under a sheath of BLOCK_CRUMBLE prismarine flecks as its ARMOR climbs. Anything that strikes it in melee eats reflected spike damage and a burst of CRIT particles, and projectiles glance off with ELECTRIC_SPARK snaps. The mantle sheds after several solid hits, the spikes visibly retracting as the defense runs out.
Uses: ARMOR attribute, BLOCK_CRUMBLE particle with prismarine state, CRIT particle, ELECTRIC_SPARK particle, reflected damage in code, custom render state for the extended spikes

2. **Laser Lattice** (Attack): The guardian's beam locks onto one enemy as normal, then forks: thinner beams arc from the first target to two more nearby enemies in a crackling lattice of ELECTRIC_SPARK and BUBBLE particles. The chained targets take a share of the beam damage for as long as the main beam holds, so clustered enemies burn together. Breaking line of sight to the main target collapses the whole lattice at once.
Uses: guardian beam in code, ELECTRIC_SPARK particle, BUBBLE particle, chained damage in code

3. **Tidal Bulwark Ring** (Terrain): With a sweep of its tail the guardian raises a circular wall of prismarine and sea lantern blocks out of the ground or water, trapping itself and its prey inside a glowing arena lit from the walls. Currents of CURRENT_DOWN and SPLASH particles swirl inside the ring, dragging swimmers downward while the guardian, immune to its own current, circles freely. The wall sinks back down after the fight inside resolves or enough time passes.
Uses: block placement in code (prismarine, sea lantern), CURRENT_DOWN particle, SPLASH particle, WATER_MOVEMENT_EFFICIENCY attribute on the guardian

4. **Shard Mine Bloom** (Summon, construct): The guardian sheds a bloom of prismarine shard block displays that drift slowly outward on interpolation paths, hanging in the water like a minefield of glittering crystals. When an enemy swims too close to a shard it detonates into BLOCK_CRUMBLE debris and a spike burst, and chain reactions can clear a whole bloom in one sparkling cascade. The guardian keeps its distance and lets the field do the work.
Uses: block_display entities with prismarine shard block states, transformation interpolation for drift, BLOCK_CRUMBLE particle, proximity detonation in code

5. **Crushing Depth Aura** (Aura): The water around the guardian darkens and thickens as BUBBLE_COLUMN_UP streams spiral upward and the pressure itself becomes a weapon: enemies in range suffer SLOWNESS and MINING_FATIGUE, their swim speed gutted by attribute pressure, while the guardian moves at full land speed through the water. The aura visibly pulses with each heartbeat of the guardian, and leaving the radius lets the pressure bleed off over a few seconds.
Uses: BUBBLE_COLUMN_UP particle, SLOWNESS effect, MINING_FATIGUE effect, WATER_MOVEMENT_EFFICIENCY attribute modifiers on enemies and the guardian

## Elder Guardian

1. **Curse of the Sunken Court** (Aura): The elder guardian's ghost apparition flashes across every enemy in the arena exactly as its vanilla curse does, but the curse now lands in full: MINING_FATIGUE, WEAKNESS, and a crushing SLOWNESS settle on the whole enemy force at once. ELDER_GUARDIAN particles sweep the room and the water goes briefly silent before the fight resumes at the elder's pace. It recasts the curse whenever an enemy shakes it off early.
Uses: ELDER_GUARDIAN particle, MINING_FATIGUE effect, WEAKNESS effect, SLOWNESS effect, curse mechanic in code

2. **Tempest Beam Array** (Attack): Three beam emitters of crackling ELECTRIC_SPARK light anchor around the elder guardian and sweep the arena in slow rotating arcs, trails of BUBBLE particles boiling along each beam line. Touching a beam means heavy damage and a jolt of knockback, so enemies must keep moving in the gaps between the sweeps. The array spins faster the longer the fight goes, turning the arena into a deadly carousel.
Uses: beam rendering in code, ELECTRIC_SPARK particle, BUBBLE particle, typed damage in code

3. **Monument Rising** (Terrain, transformation): The elder guardian calls its monument up out of the seafloor: a stepped pyramid of prismarine bricks and sea lanterns rises beneath it in stages, and the elder takes its throne at the peak, larger, warded by RESISTANCE and REGENERATION while it holds the high ground. From the throne its beams reach farther and its curse falls faster. Knocking it off the pyramid or outlasting the rise are the only ways to end the reign.
Uses: block placement in code (prismarine bricks, sea lantern), SCALE attribute, RESISTANCE effect, REGENERATION effect, FOLLOW_RANGE attribute

4. **Sponge Pulse** (Defense): The elder guardian inhales the whole fight for a moment: every projectile within a wide radius pops into POOF particles and vanishes, and a ring of SPLASH and BUBBLE bursts outward as it exhales. While submerged it drinks in the water and regenerates steadily, so the pulse doubles as its sustain tool in a long fight. The inhale is visible and audible a moment before it fires, giving quick enemies one chance to hold their shots.
Uses: POOF particle, SPLASH particle, BUBBLE particle, projectile removal in code, REGENERATION effect

5. **Turret Brood** (Summon): Two younger guardians rise from the monument steps and anchor themselves in place, movement locked to zero, acting as living turrets that pour beam fire into anything the elder has cursed. Beams of BUBBLE and light link each turret back to the elder, and damage dealt by the turrets feeds a trickle of healing to their parent. The turrets are frail once focused, but ignoring them means fighting three beams at once.
Uses: /summon guardian with MOVEMENT_SPEED base set to zero, beam rendering in code, BUBBLE particle, healing link in code

## Hoglin

1. **Stampede Trench** (Mobility, attack): The hoglin lowers its head and charges in a straight thundering line, DUST_PILLAR and dirt BLOCK debris exploding from its hooves as it plows a visible trench of coarse dirt into the ground behind it. Anything caught in the charge takes a heavy hit and massive knockback, and the trench remains as rough terrain that slows enemies who cross it. The hoglin needs a few strides to reach full speed, so tight arenas blunt the charge.
Uses: ATTACK_KNOCKBACK attribute, DUST_PILLAR particle, BLOCK particle with dirt state, block placement in code (coarse dirt), velocity in code

2. **Crimson Maw** (Transformation): The hoglin's tusks lengthen into crimson-stained spears through a custom model layer while its body swells and CRIMSON_SPORE puffs roll from its snout with every breath. Its bite damage climbs sharply and its goring hits now toss enemies into the air, and the whole beast fights in a visible frenzy until the form burns out. When it ends the tusks crack back to normal with a spray of spore particles.
Uses: SCALE attribute, ATTACK_DAMAGE attribute, CRIMSON_SPORE particle, knockback in code, custom model layer for the tusks

3. **Fungal Frenzy Cloud** (Aura): The hoglin shakes its mane violently and sheds a rolling cloud of CRIMSON_SPORE and MYCELIUM particles that hangs over the battlefield. Enemies breathing the cloud are hit with NAUSEA as the fungus gets into them, while hoglin and zoglin allies inside it gain SPEED and fight in a frenzy. The cloud drifts with the hoglin, so it becomes a moving zone of sickness and strength.
Uses: CRIMSON_SPORE particle, MYCELIUM particle, NAUSEA effect, SPEED effect for allies via team targeting

4. **Territorial Slam** (Attack): The hoglin rears up and crashes down with its full weight, sending a shockwave ring of FALLING_DUST and flying block debris across the ground that knocks enemies off their feet in every direction. The slam cracks the earth where it lands, and enemies at point blank take the full crushing damage while those at the edge only stumble. It is slow to wind up and impossible to miss seeing.
Uses: FALLING_DUST particle, BLOCK particle with ground block states, EXPLOSION_KNOCKBACK_RESISTANCE attribute on the hoglin during the slam, radial knockback and damage in code

5. **Stubborn Hide Brace** (Defense): The hoglin plants all four legs, drops its head, and braces: it becomes completely immune to knockback, its ARMOR spikes, and it simply refuses to be moved while ANGRY_VILLAGER particles steam off its back. While braced it cannot move, but melee attackers wear themselves out on the hide and take chip damage back. The brace breaks if the hoglin takes one very heavy hit, staggering it.
Uses: KNOCKBACK_RESISTANCE attribute, ARMOR attribute, MOVEMENT_SPEED attribute set to zero while braced, ANGRY_VILLAGER particle, reflected chip damage in code

## Zoglin

1. **Rotten Rampage** (Mobility, attack): The zoglin charges in a lurching zigzag, trailing DUST_COLOR_TRANSITION motes that rot from green to black, and every enemy it clips is left POISONED by the filth caked on its tusks. It does not steer well in this state and will happily slam into walls, shaking the impact off faster than anything living could. The zigzag makes it almost impossible to dodge by sidestepping alone.
Uses: DUST_COLOR_TRANSITION particle with color data, POISON effect, velocity in code, KNOCKBACK_RESISTANCE attribute

2. **Undying Bulk** (Defense, transformation): The first time the zoglin would die, it simply does not: it erupts in TOTEM_OF_UNDYING particles and a burst of SOUL wisps, stands back up at half health, and comes back bigger and angrier with its MAX_HEALTH permanently raised for the rest of the fight. The revival is loud and obvious, and enemies who spent their best attacks earning the kill have to earn it again. It only works once, and fire finishes the job properly if it lands during the revival moment.
Uses: TOTEM_OF_UNDYING particle, SOUL particle, death-save mechanic in code, MAX_HEALTH attribute, SCALE attribute

3. **Plague Breath** (Attack): The zoglin opens its rotting jaws and exhales a rolling cone of NOXIOUS_GAS and NOXIOUS_GAS_CLOUD, the greenish sulfur-born miasma that hangs low and spreads across the ground in front of it. Enemies caught in the breath are wracked with NAUSEA and POISON and stumble out of formation, easy prey for the gore that follows. The cloud lingers for a few seconds as a hazard zone before dispersing.
Uses: NOXIOUS_GAS particle, NOXIOUS_GAS_CLOUD particle, NAUSEA effect, POISON effect, area effect in code

4. **Frenzy Gore** (Attack): The zoglin throws itself into a frenzy of rapid goring strikes, SWEEP_ATTACK arcs flashing with every toss of its head as it hits far faster than its bulk suggests. Each successful gore stacks WEAKNESS on the victim, so a zoglin left alone on one target will grind that target down to nothing. The frenzy leaves it winded and slow for a few seconds afterward.
Uses: ATTACK_SPEED attribute, SWEEP_ATTACK particle, WEAKNESS effect, SLOWNESS effect on the zoglin after the frenzy

5. **Carrion Call** (Aura): The zoglin throws back its head and bellows, and every undead ally in earshot answers: zombies, skeletons, and their kin surge with STRENGTH and SPEED while ANGRY_VILLAGER particles boil off the zoglin's flanks. The call marks the zoglin as the heart of the undead push, and killing it drops the buff from everything it rallied. Living enemies near the call are shoved back by the sheer force of the bellow.
Uses: STRENGTH effect, SPEED effect, ANGRY_VILLAGER particle, team and type targeting in code, knockback in code

## Piglin

1. **Gold Rush Frenzy** (Transformation, aura): The moment gold hits the ground nearby, this piglin loses its mind for it: gold DUST sparkles ignite around its body, and while any gold item lies within reach it fights at SPEED and STRENGTH levels no calm piglin matches. It will literally change targets mid-fight to chase a thrown gold ingot, which clever enemies can use, but woe to anyone standing between the piglin and the prize. The frenzy fades a few seconds after the last gold is claimed.
Uses: DUST particle with gold color data, SPEED effect, STRENGTH effect, target switching in code, item detection in code

2. **Barter Barrage** (Attack): The piglin upends its whole trading stock as ammunition, hurling whatever the barter tables gave it: fire charges, splash potions, snowballs, and worse, each throw rolled fresh so no two barrages match. ITEM particles of the thrown goods streak across the fight, and a lucky roll can produce an ender pearl that teleports the piglin itself mid-barrage for a point-blank follow-up. Enemies never know whether the next throw is a joke or a death sentence.
Uses: loot table rolls in code, ITEM particle with item stack data, summoned projectiles (fire charge, splash potion, snowball), teleport in code on an ender pearl roll

3. **Volley Mark** (Attack): The piglin raises its crossbow and paints a target circle on the ground in red DUST, then a heartbeat later a volley of arrows rains straight down into the marked circle from high above. The mark is fair warning, but the circle is wide and the arrows keep falling for several seconds, denying the whole patch of ground. Other piglins nearby add their own arrows to any volley they see called.
Uses: DUST particle with color data, summoned arrow projectiles, area denial in code

4. **Gilded Plate Swap** (Defense): Mid-fight, the piglin slaps on a full set of gleaming golden armor in a flash of ENCHANT particles, its ARMOR jumping as the gold closes over every gap. The armor is real equipment that can be broken piece by piece, and each broken piece bursts into gold nugget ITEM particles and a sad little squeal. While the gold lasts, other piglins treat it as the richest mob in the room and cluster near it for protection.
Uses: equipment swap in code, ENCHANT particle, ITEM particle with gold nugget stack, ARMOR attribute from equipment, ally positioning in code

5. **Hoglin Cavalry** (Summon, mount): The piglin whistles and a hoglin thunders in from the edge of the fight; the piglin vaults into a riding position and the two become a single cavalry unit, the hoglin charging and goring while the piglin shoots from its back. The mount gains SPEED and the pair share the hoglin's knockback resistance, making them hard to stop and harder to unhorse. Kill the hoglin and the piglin fights on alone, furious.
Uses: riding and passenger stacks in code, SPEED effect on the mount, KNOCKBACK_RESISTANCE attribute, combined targeting in code

## Piglin Brute

1. **Bastion Leap Slam** (Mobility, attack): The brute coils and launches itself through the air in a huge arc, golden axe raised, and lands with a slam that cracks blackstone BLOCK_CRUMBLE debris in every direction and hurls nearby enemies outward. It takes no fall damage from the leap no matter the height, and the landing zone stays marked by a ring of dust for a moment after. The leap covers ground no charge can match, making distance a lie against this brute.
Uses: JUMP_STRENGTH attribute, SAFE_FALL_DISTANCE attribute, BLOCK_CRUMBLE particle with blackstone state, ATTACK_KNOCKBACK attribute, radial knockback in code

2. **Blackstone Bulwark Form** (Transformation): The brute grows into a fortress: half again as tall, with block display pauldrons of polished blackstone riding on its shoulders as passengers, offset and gleaming at full brightness. Its ARMOR_TOUGHNESS and knockback resistance climb to siege levels and its axe swings sweep wider with every blow. The pauldrons are pure intimidation and shatter into debris when the form ends.
Uses: SCALE attribute, ARMOR_TOUGHNESS attribute, KNOCKBACK_RESISTANCE attribute, block_display passengers with transformation offsets and brightness override, SWEEPING_DAMAGE_RATIO attribute

3. **War Stomp Fissure** (Terrain): The brute stomps a line of blackstone spikes out of the ground in front of it, each spike erupting with DUST_PILLAR force in sequence like a row of falling dominoes of stone. Enemies standing on the line are launched upward as the spikes rise, and the spikes remain for a few seconds as a wall of cover before crumbling. In the Nether the line ignites patches of fire along its length.
Uses: block placement in code (blackstone), DUST_PILLAR particle, upward velocity in code, fire block placement in the Nether

4. **Executioner's Mark** (Attack): The brute studies the fight, picks the most wounded enemy, and marks it: DAMAGE_INDICATOR particles bleed above the victim's head and the brute gains a burst of SPEED and STRENGTH aimed at that one target. Against a marked enemy below a quarter health, the brute's axe hits land as executions with greatly increased damage. If the marked enemy dies to anyone else, the brute roars and picks a new mark.
Uses: DAMAGE_INDICATOR particle, SPEED effect, STRENGTH effect, conditional bonus damage in code, target selection in code

5. **Bastion Standard Aura** (Aura, defense): The brute plants its presence like a banner: piglin and brute allies within range gain RESISTANCE and a pool of ABSORPTION while gold DUST motes drift in a slow ring around the brute's position. The aura makes a piglin line genuinely hard to break, and the brute at its center becomes the obvious and worst target. The ring of motes shows enemies exactly how far the protection reaches.
Uses: RESISTANCE effect, ABSORPTION effect, DUST particle with gold color data, team targeting in code

## Shulker

1. **Petal Storm Spiral** (Attack): The shulker throws its shell wide and fires its homing bullets in a rising spiral, a dozen of them corkscrewing outward before curving in on their targets from every angle at once. Each bullet still carries the LEVITATION payload, so a mob caught by the spiral is lifted, turned, and dropped from a height in a shower of END_ROD sparks. The spiral is slow to start and impossible to fully dodge in the open.
Uses: summoned shulker bullet projectiles, LEVITATION effect, END_ROD particle, spiral firing pattern in code

2. **Endstone Bunker** (Defense, terrain): The shulker vanishes into a shell of real blocks: end stone and purpur rise around it in a closed bunker with no door, and inside it sits at full RESISTANCE, peeking out only to fire through gaps it opens for a single shot at a time. Enemies must mine through or wait it out, and mining toward a shulker is exactly as dangerous as it sounds. The bunker crumbles to rubble when the shulker teleports away or the timer runs out.
Uses: block placement in code (end stone, purpur block), RESISTANCE effect, teleport in code, BLOCK_CRUMBLE particle

3. **Gravity Well Oscillation** (Aura, control): The shulker becomes the center of a pulsing gravity anomaly wrapped in PORTAL particles: for two seconds every enemy nearby is hit with LEVITATION and floats helplessly upward, then the shulker slams their GRAVITY attribute to several times normal and they crash down for fall damage. The oscillation repeats while the shulker holds position, turning the area around it into a deadly elevator. Leaving the radius breaks the cycle cleanly.
Uses: LEVITATION effect, GRAVITY attribute modifiers on enemies, PORTAL particle, fall damage via FALL_DAMAGE_MULTIPLIER attribute on victims

4. **Chorus Split** (Summon, decoy): Struck hard enough, the shulker splits its defense: it teleports away in a burst of REVERSE_PORTAL particles and leaves a twin behind, a summoned shulker with a sliver of health that keeps firing at full enthusiasm. Enemies who chase the wrong shell waste their burst on a decoy that pops into END_ROD sparks, while the real shulker reopens somewhere safe and starts again. The split can only happen once per fight.
Uses: teleport in code, REVERSE_PORTAL particle, /summon shulker with MAX_HEALTH base set low, END_ROD particle

5. **Skybox Artillery Perch** (Mobility, terrain): The shulker teleports onto a small floating platform of purpur slabs that materializes high above the fight, far beyond melee reach, and from that skybox its FOLLOW_RANGE stretches across the whole arena. It rains homing bullets down at leisure until the platform, always temporary, crumbles away and drops it back into the fight with SLOW_FALLING to cushion the landing. Shooting it down early ends the barrage and the perch together.
Uses: teleport in code, block placement in code (purpur slab), FOLLOW_RANGE attribute, SLOW_FALLING effect, block removal in code

## Vex

1. **Mirage Split** (Summon, defense): The vex flickers and there are suddenly three of them, two being summoned mirror vexes with a single point of health and harmless swords, all three weaving the same flight pattern so the eye cannot hold the real one. The real vex slips INVISIBLE for the first moment of the split, and the mirrors pop into puffs of SMOKE when struck. Every mirror destroyed narrows the guessing game, so the split buys seconds, not safety.
Uses: /summon vex with MAX_HEALTH base set low and ATTACK_DAMAGE base set to zero, INVISIBILITY effect, SMOKE particle, flight pattern mirroring in code

2. **Wallphase Ambush** (Mobility, attack): The vex dives straight through solid blocks in its natural phasing flight, trailing PORTAL wisps, and erupts out of the wall or floor directly behind its target with its sword already swinging and a burst of STRENGTH behind the blow. There is no safe wall to put your back to while a vex hunts, and the ambush works through the ceiling just as well. After the strike it darts back through cover before enemies can answer.
Uses: vex phasing flight (vanilla behavior steered in code), PORTAL particle, STRENGTH effect, positional targeting in code

3. **Ironbound Duelist** (Transformation): The little vex becomes a duelist: it grows by half, its iron sword is swapped for a gleaming enchanted blade wrapped in ENCHANT particles, and its wings beat with visible menace as its damage climbs. In this form it fights like a fencer, darting in for one committed strike and out again rather than swarming. The form fades after a handful of strikes, leaving it small and ordinary again.
Uses: SCALE attribute, equipment swap in code to an enchanted iron sword, ENCHANT particle, ATTACK_DAMAGE attribute

4. **Soul Tether** (Aura, control): The vex latches an spectral chain onto its target: a visible line of SOUL particles links the two, and while the tether holds the victim is SLOWED and every point of damage the vex deals to it comes back as healing. Closing to melee range or breaking line of sight for several seconds snaps the chain in a puff of SOUL wisps. The tether makes the vex a parasite that must be peeled off, not ignored.
Uses: SOUL particle, SLOWNESS effect, lifesteal in code, line of sight checks in code

5. **Screech Divebomb** (Attack): The vex climbs, lets out a SHRIEK that rolls across the arena as a visible particle ring, and folds its wings into a screaming dive directly at its target. On impact it detonates a small blast of EXPLOSION particles and knockback, surviving its own crash thanks to a moment of RESISTANCE timed to the dive. A missed divebomb leaves it briefly grounded and vulnerable, flapping in the dirt.
Uses: SHRIEK particle, EXPLOSION particle, RESISTANCE effect, dive velocity and impact in code

## Witch

1. **Cauldron Cataclysm** (Attack): The witch summons a colossal cauldron as a block display hanging over the arena, tips it with a cackle, and rains a deluge of splash potions down across the whole fight in a glittering cascade of EFFECT sparkles and BUBBLE foam. Every bottle breaks with a different payload rolled fresh, poison here, harming there, slowness pooling on the ground as lingering clouds. Standing under the cauldron when it tips is the worst place in the world.
Uses: block_display entity with cauldron block state and transformation tilt, summoned splash potion projectiles, EFFECT particle with color data, BUBBLE particle, lingering potion clouds via area effect cloud entities

2. **Potion Roulette** (Summon, construct): Three potion bottles take up orbit around the witch as item displays, spinning slowly and glowing with their contents: one red, one green, one black. Every few seconds the wheel stops, one bottle launches itself at the nearest enemy as a real splash potion with its rolled effect, and a fresh bottle fades in to replace it. Enemies can shoot the orbiting bottles down, and the witch hates that, retreating to rebuild her wheel.
Uses: item_display entities with potion contents and transformation interpolation for the orbit, summoned splash potion projectiles, random effect selection in code (POISON, INSTANT_DAMAGE, SLOWNESS, WEAKNESS)

3. **Grand Hex Form** (Transformation): The witch drinks something she should not have: she grows tall and terrible, GLOWING with a violet outline, WITCH particles pouring off her hat and sleeves as her throwing arm becomes a blur. Her potion cooldowns collapse, her splash radius grows, and she gains the confidence to wade into mid range instead of kiting. The form burns out in a coughing fit of purple smoke when it ends.
Uses: SCALE attribute, GLOWING effect, WITCH particle, ATTACK_SPEED attribute, splash radius in code

4. **Swamp Hut Ward** (Terrain, defense): The witch stamps her foot and her hut answers: a ring of spruce planks and a bubbling cauldron block rise around her as a warded campsite, and while she stands inside it she regenerates steadily behind RESISTANCE. The cauldron boils over with BUBBLE particles and any enemy who steps into the ward is splashed with POISON from the brew. The ward is small, so area attacks still reach her, but crossing the threshold costs blood.
Uses: block placement in code (spruce planks, cauldron), REGENERATION effect, RESISTANCE effect, POISON effect, BUBBLE particle

5. **Bottled Tempest** (Attack): The witch's masterpiece brew is a bottled storm: thrown like any potion, but where it breaks a bolt of lightning crashes down in a burst of ELECTRIC_SPARK particles, and the broken glass leaves a fizzing puddle that keeps snapping sparks at anything that steps in it. In rain the bolt is bigger and the puddle spreads. It is the only bottle in her bag she handles with genuine respect.
Uses: summoned splash potion projectile, summoned lightning bolt entity, ELECTRIC_SPARK particle, lingering spark zone in code

## Evoker

1. **Fang Colosseum** (Terrain, attack): The evoker raises both arms and the ground itself becomes the arena: concentric rings of snapping evoker fangs erupt in sequence from the outside inward, each ring telegraphed by a circle of DUST a heartbeat before the jaws close. Enemies must keep moving toward the center or away from it in rhythm, and the final ring closes directly around the evoker's own position where he stands untouched at the eye of the trap. Getting caught by two rings in a row is usually the end of the story.
Uses: summoned evoker fang entities, DUST particle with color data, ring sequencing in code

2. **Totem Tyrant** (Transformation): The evoker produces a totem of undying and rules through it: he gains HEALTH_BOOST and a golden GLOWING outline, and the first blow that would kill him instead triggers the totem in a full burst of TOTEM_OF_UNDYING particles, restoring him and blasting nearby enemies back. While the tyrant form lasts, every vex he summons fights with STRENGTH, making his whole court more dangerous. Once the totem is spent he is just an evoker again, and visibly furious about it.
Uses: HEALTH_BOOST effect, GLOWING effect, TOTEM_OF_UNDYING particle, death-save mechanic in code, STRENGTH effect for summoned vexes

3. **Delayed Hex Fang** (Attack, control): The evoker points at one enemy and speaks a word: OMINOUS_SPAWNING particles coil around the victim and a three second countdown begins that only the victim can see coming. When it expires, a full-sized evoker fang erupts directly beneath the victim wherever they now stand, dealing heavy damage and a dose of INSTANT_DAMAGE magic through armor. Running does not help, only killing the evoker or breaking his concentration cancels the hex.
Uses: OMINOUS_SPAWNING particle, summoned evoker fang entity, INSTANT_DAMAGE effect, delayed execution in code

4. **Crowned Vex Vanguard** (Summon): The evoker summons his honor guard properly this time: vexes wearing tiny golden helmets, named, glowing with his team color, that take up an orbiting guard ring around him instead of charging off. They hold the ring and savage anything that crosses it, and only pursue a wounded enemy when it tries to flee the circle. The guard makes approaching the evoker a fight in itself.
Uses: /summon vex with equipment (golden helmet) and custom names, GLOWING effect with team color, orbit and guard behavior in code

5. **Mansion Mirage Decoys** (Defense, summon): The evoker claps and two more evokers step out of him, summoned duplicates with a single point of health and no spells, posed mid-cast at either side while the real one slips away to a new position. The decoys hold their dramatic poses and soak the first hits aimed at the caster, popping into POOF and a swirl of ENCHANT particles when struck. The trick fails against enemies who watch which evoker actually casts.
Uses: /summon evoker with data set to no AI and MAX_HEALTH base set low, POOF particle, ENCHANT particle, repositioning in code

## Vindicator

1. **Axe Cyclone** (Attack): The vindicator plants its feet and spins, axe extended, becoming a whirling cyclone of SWEEP_ATTACK arcs that chews through everything in a growing circle as it gains SPEED with every rotation. An item display of a second axe spins overhead in the opposite direction for pure menace, and the cyclone drags slowly toward the nearest enemy like a storm with a grudge. It ends dizzy, axe down, for one vulnerable breath.
Uses: SWEEP_ATTACK particle, SPEED effect, item_display entity with iron axe contents and interpolation rotation, area damage in code

2. **Johnny Rampage** (Transformation): The nameplate changes to Johnny, the name tag range stretches so the whole arena can read it, and the famous rule takes hold: everything that moves is a target, starting now. The vindicator grows, gains STRENGTH, and its hits spark with ENCHANTED_HIT particles as it hacks indiscriminately at the nearest living thing, friend or foe. The rampage only ends when Johnny or everything nearby is dead.
Uses: custom name and nameplate via NAME_TAG_DISTANCE attribute, SCALE attribute, STRENGTH effect, ENCHANTED_HIT particle, indiscriminate targeting in code

3. **Doorbreaker Siege** (Terrain, attack): Locked doors and walls stop meaning anything: the vindicator's BLOCK_BREAK_SPEED becomes monstrous and it simply mines through whatever stands between it and its target, BLOCK debris and BLOCK_CRUMBLE dust flying as it carves a vindicator-shaped tunnel in a straight line. It emerges from the far side of fortifications already swinging, and the tunnel stays as a breach the whole raid can use. Wood, stone, it makes no difference to the axe.
Uses: BLOCK_BREAK_SPEED attribute, BLOCK particle with broken block states, BLOCK_CRUMBLE particle, block breaking in code

4. **Patrol Captain's Standard** (Aura): The vindicator plants an ominous banner as an item display in the ground and takes command: every illager ally in range gains ABSORPTION and SPEED, and RAID_OMEN particles drift from the standard like smoke from a signal fire. The standard is a physical thing that can be destroyed, and illagers near it fight in formation instead of scattering. Breaking the standard breaks the buff and enrages the captain.
Uses: item_display entity with ominous banner contents, ABSORPTION effect, SPEED effect, RAID_OMEN particle, team targeting in code

5. **Executioner's Leap** (Mobility, attack): The vindicator springs into the air with a JUMP_STRENGTH surge, axe high, hangs at the peak for one terrible instant, and crashes down in a ring of DUST_PILLAR and splintering ground that damages and staggers everything around the landing point. It falls any distance without harm thanks to its SAFE_FALL_DISTANCE, so it happily leaps off towers and cliffsides onto targets below. The landing is loud, telegraphed by its shadow, and worth dodging.
Uses: JUMP_STRENGTH attribute, SAFE_FALL_DISTANCE attribute, DUST_PILLAR particle, landing shockwave in code

## Pillager

1. **Turret Stance** (Defense, transformation): The pillager digs in behind a block display shield wall of dark oak planks that rises from the ground in front of it, roots itself with MOVEMENT_SPEED cut to nothing and knockback resistance raised, and works its crossbow like a mounted gun. From the stance it fires tight three-bolt bursts over the wall, and the wall itself soaks projectiles until it splinters apart. Uprooting takes a moment, so the stance trades all mobility for a firing position.
Uses: block_display entity with dark oak planks state, MOVEMENT_SPEED attribute, KNOCKBACK_RESISTANCE attribute, summoned crossbow bolt projectiles in bursts

2. **Firework Broadside** (Attack): The pillager loads its crossbow with fireworks and lets fly a full broadside: five firework rockets streak out in a fan and burst overhead and among the enemy ranks in colored FIREWORK starbursts, each dealing real area damage. The broadside lights up the whole fight and leaves drifting SMOKE between the lines. It is wildly inaccurate and completely spectacular, which the pillager considers a fair trade.
Uses: summoned firework rocket projectiles, FIREWORK particle, SMOKE particle, area damage in code

3. **Raid Captain's Call** (Aura): The pillager raises its banner and sounds the call: RAID_OMEN particles pour off it as every illager ally in range surges with SPEED and STRENGTH, and the pillager itself gains the steady aim of a captain with FOLLOW_RANGE stretched to survey the whole field. The call turns a skirmish into a coordinated push for as long as it lasts. Killing the captain mid-call cuts the buff short.
Uses: RAID_OMEN particle, SPEED effect, STRENGTH effect, FOLLOW_RANGE attribute, team targeting in code

4. **Outpost Sniper Perch** (Terrain, mobility): Dark oak logs and planks assemble beneath the pillager in moments, lifting it onto a four-block sniper perch in the middle of the fight where its crossbow looks down on everything. From elevation its bolts gain bonus damage in code, and the perch's narrow top makes it nearly immune to melee. The tower is real wood and burns and breaks like it, so the perch is only as safe as its legs.
Uses: block placement in code (dark oak log, dark oak planks), height-based bonus damage in code, FOLLOW_RANGE attribute

5. **Powder Keg Toss** (Attack): The pillager heaves a primed keg of trouble: a summoned TNT with a short fuse that arcs through the air trailing SMOKE, bounces once, and detonates in the middle of the enemy line with a full EXPLOSION. The crater it leaves is real, and so is the ringing in everyone's ears. A pillager down to its last trick will light the keg and hold it a heartbeat longer for a bigger, closer blast.
Uses: /summon tnt with a short fuse, SMOKE particle, EXPLOSION particle, explosion in code

## Illusioner

1. **Hall of Mirrors** (Summon, defense): The illusioner throws wide its arms and the hall fills with itself: four summoned duplicates take positions in a circle, each with a single point of health and harmless arrows, all bowing and weaving in perfect sync. Every few seconds the whole set shuffles positions in swirls of PORTAL particles, so even a marked original is lost again, and the real illusioner keeps casting from inside the pattern. Enemies must burn hits on mirrors to find the caster, and every mirror broken is information bought with time.
Uses: /summon illusioner with MAX_HEALTH base set low and ATTACK_DAMAGE base set to zero, PORTAL particle, synchronized movement in code

2. **Blindspot Barrage** (Attack): The illusioner snaps its fingers and BLINDNESS rolls over its enemies, and in the dark the sound of five bowstrings comes from five directions at once: the real arrows converge from the illusioner's position while phantom shots whistle from each mirror. Enemies who cannot see cannot dodge, and the barrage is timed to land entirely inside the blindness window. When sight returns, the mirrors are already somewhere else.
Uses: BLINDNESS effect, summoned arrow projectiles, ENCHANT particle, volley timing in code

3. **Kaleidoscope Shift** (Transformation): The illusioner's form dissolves into color: DUST_COLOR_TRANSITION motes cycle through the spectrum across its body, its outline smears with ENTITY_EFFECT tint, and it gains SPEED and a dancer's footwork. Every time it is struck in this form it blinks behind its attacker in a swirl of REVERSE_PORTAL particles and looses one instant arrow in reply. The form is exhausting and ends in a slump of gray dust, but while it lasts the illusioner is nearly untouchable.
Uses: DUST_COLOR_TRANSITION particle with color data, ENTITY_EFFECT particle with color data, SPEED effect, REVERSE_PORTAL particle, reactive teleport and counter shot in code

4. **Phantom Archer Ring** (Summon, construct): A ring of bows materializes in the air as item displays, floating at head height in a slow orbit, each bow drawing and loosing real arrows at enemies on its own rhythm while the illusioner turns INVISIBLE and watches its own artillery work. The bows are fragile constructs and shatter when struck, and without them the ring is just a hiding illusioner. It is a siege engine built from a magic trick.
Uses: item_display entities with bow contents and transformation interpolation for the orbit, summoned arrow projectiles, INVISIBILITY effect, construct targeting in code

5. **Shattered Image Nova** (Attack): The mirrors are weapons even in death: when any duplicate is destroyed it does not fade, it shatters, bursting into BLOCK_CRUMBLE shards of pale glass and a FLASH of light that damages and slows every enemy nearby. A clever enemy learns to kill mirrors from range, but a surrounded illusioner will happily walk its mirrors into the enemy line and let the novas chain. The real illusioner takes no harm from its own shattering images.
Uses: BLOCK_CRUMBLE particle with light blue stained glass state, FLASH particle with ARGB color data, SLOWNESS effect, on-death detonation in code

## Ravager

1. **Siege Ram Charge** (Mobility, attack): The ravager lowers its horned head and charges, and this time nothing stops it: blocks in its path crack and break as it plows through, DUST_PILLAR and BLOCK debris fountaining from the breach while its ATTACK_KNOCKBACK reaches absurd levels. The charge leaves a ravager-shaped hole through fortifications and a clear lane for the raid behind it. It only stops at bedrock, exhaustion, or a very long fall.
Uses: block breaking in code, DUST_PILLAR particle, BLOCK particle with broken block states, ATTACK_KNOCKBACK attribute, charge velocity in code

2. **Armored Juggernaut** (Transformation): Iron plate closes over the beast: the ravager grows by a third, block display armor plates of iron ride as passengers across its back and flanks at full brightness, and its ARMOR and ARMOR_TOUGHNESS climb until ordinary hits ring off it like rain. Its knockback resistance becomes nearly total, so the juggernaut does not stop, does not flinch, and does not turn quickly. The plates crack and fall away one by one under sustained fire, showing exactly how much protection is left.
Uses: SCALE attribute, ARMOR attribute, ARMOR_TOUGHNESS attribute, KNOCKBACK_RESISTANCE attribute, block_display passengers with transformation offsets and brightness override

3. **Roar of the Raid** (Aura): The ravager throws back its head and roars, and the roar is a physical thing: a SONIC_BOOM ring rolls across the field, enemies caught by it are weakened and slowed to a crawl, and every illager ally who hears it surges with STRENGTH. The roar shakes dust from ceilings and courage from lines, and it marks the moment the raid stops skirmishing and starts winning. Even the ravager needs a breath after a roar that size.
Uses: SONIC_BOOM particle, WEAKNESS effect, SLOWNESS effect, STRENGTH effect for allies, team targeting in code

4. **Ground Shatter Fissure** (Terrain): The ravager rears and brings both front legs down, and the earth answers: a fissure of coarse dirt and gravel tears across the ground in a line from its hooves, FALLING_DUST boiling up from the crack as it runs. Enemies standing on the fissure line as it passes are launched into the air by the heaving ground, and the torn earth remains as difficult terrain for the rest of the fight. Repeated shatters can remodel the whole arena into ravager country.
Uses: block placement in code (coarse dirt, gravel), FALLING_DUST particle, upward velocity in code, terrain persistence in code

5. **Rider of Ruin** (Summon, mount): A vindicator champion vaults onto the ravager's back and takes the reins, RAID_OMEN particles streaming from the pair as they become a single unit of cavalry: the beast charges and tramples while the rider hacks at anything that gets close from above. If the rider is killed, the ravager does not slow down, it enrages, gaining STRENGTH and SPEED and losing what little restraint it had. If the ravager falls, the rider fights on afoot, which is its own problem.
Uses: riding and passenger stacks in code, RAID_OMEN particle, STRENGTH effect, SPEED effect, combined behavior in code

# Advanced Mobs: New Ability Ideas (v2, capability grounded)

Five fresh ability designs per mob, built only from systems verified to exist and work in both Minecraft 26.2 and 26.3: the vanilla particle registry, the attribute list, the status effect list, display entities, summons, typed damage, and block placement. Nothing here uses 26.3 only content or anything on the reference Avoid list.

## Warden

1. **Cataclysm Boom** (Attack): The warden rears back as SCULK_CHARGE streaks race up its arms, then fires three SONIC_BOOM rings in a spread instead of one, each ring dealing heavy typed damage and throwing enemies backward. Anything caught by a ring also gets DARKNESS for several seconds, so survivors are blinded while the warden closes in on sound alone.
Uses: SONIC_BOOM and SCULK_CHARGE particles, DARKNESS effect, ATTACK_KNOCKBACK attribute, typed mob attack damage

2. **Sculk Bloom** (Terrain): The warden slams both fists down and a carpet of real sculk blocks, sculk veins, and a sculk catalyst blooms across the ground around it, trailing SCULK_SOUL wisps and SCULK_CHARGE_POP sparks. Enemies standing on the bloom are slowed and the warden regains health while it fights on its own sculk, turning the patch into home ground.
Uses: block placement (fill and setblock with sculk blocks), SCULK_SOUL and SCULK_CHARGE_POP particles, SLOWNESS effect on enemies, REGENERATION effect on self

3. **Heart of the Deep** (Transformation): Its chest splits open with teal light, the warden grows larger and heavier, and sculk tendrils rendered as block displays of sculk vein rise from its shoulders and slowly wave with interpolation. In this form it hits harder, shrugs off knockback, and pulses DARKNESS onto nearby enemies every few seconds.
Uses: SCALE, MAX_HEALTH, ATTACK_DAMAGE, and KNOCKBACK_RESISTANCE attributes, block_display passengers with interpolation, DARKNESS effect

4. **Shrieking Brood** (Summon): The warden shrieks and the ground erupts with SCULK_CHARGE streaks as three silverfish burst out, dark tinted, glowing faintly teal, and immediately hunt the warden's target. The brood is fast and distracting, and each one that dies pops in a small SCULK_CHARGE_POP burst that briefly slows what killed it.
Uses: summon silverfish, GLOWING effect, SCULK_CHARGE and SCULK_CHARGE_POP particles, SLOWNESS effect

5. **Burrow Ambush** (Mobility): The warden dives into the floor in a spray of BLOCK particles from the ground block and travels underground, marked only by a moving ridge of sculk dust and a muffled heartbeat sound. It erupts beneath its target seconds later in an EXPLOSION_EMITTER burst that launches nearby enemies upward and leaves them with heavy fall damage on the way down.
Uses: teleport in code after a delayed phase (schedule), BLOCK and EXPLOSION_EMITTER particles, FALL_DAMAGE_MULTIPLIER attribute on victims, upward velocity in code

## Breeze

1. **Wind Lance Volley** (Attack): The breeze spins up a tight GUST spiral and fires a rapid line of wind charge projectiles, each bursting in a SMALL_GUST pop that damages and scatters enemies in different directions. The last charge in the volley is oversized, with a GUST_EMITTER_LARGE burst that flings everything near the impact point.
Uses: wind charge projectiles (summon), GUST, SMALL_GUST, and GUST_EMITTER_LARGE particles, knockback in code

2. **Cyclone Form** (Transformation): The breeze pulls its rods into a tall spinning cyclone, CLOUD and GUST particles streaming off it in a constant spiral, and drifts above the ground. In this form it moves and shoots faster, takes reduced fall damage, and periodically sheds a gust ring that pushes melee attackers back before they can connect.
Uses: LEVITATION pulses and SLOW_FALLING effect on self, FLYING_SPEED and MOVEMENT_SPEED attributes, GUST and CLOUD particles, GUST_EMITTER_SMALL particles

3. **Crosswind Arena** (Aura / Terrain): The breeze marks a wide circle with orbiting block displays of white stained glass panes spinning slowly at its edge, and inside the circle constant crosswinds blow. Enemies in the arena are steadily pushed sideways, their projectiles drift off course, and every few seconds a SMALL_GUST burst shoves everyone toward the rim.
Uses: block_display ring with interpolation, SMALL_GUST particles, AIR_DRAG_MODIFIER attribute on enemies, velocity pushes in code

4. **Gust Step** (Mobility): The breeze collapses into a streak of GUST and CLOUD particles and reappears up to fifteen blocks away, leaving a short lived wind tunnel between the two points. Allies and enemies who touch the tunnel are flung along it, so the breeze can reposition itself or scatter a whole fight line in one move.
Uses: teleport in code, GUST and CLOUD particles, AIR_DRAG_MODIFIER attribute on self during the dash, velocity in code

5. **Wind Wall** (Defense): A curved wall of spinning GUST_EMITTER_SMALL bursts rises in front of the breeze for several seconds, visibly bending the air. Projectiles that enter the wall are deflected or destroyed, and melee attackers who push through are slowed and knocked back at the wall line.
Uses: GUST_EMITTER_SMALL particles, RESISTANCE effect on self, SLOWNESS effect on attackers, projectile deflection in code

## Phantom

1. **Nightmare Dive** (Attack): The phantom climbs high, folds its wings with a shriek, and dives in a trail of PORTAL particles and tearing wind. The dive hits one target with a heavy crit style strike, DAMAGE_INDICATOR particles bursting off it, and stamps the victim with DARKNESS and WEAKNESS so the rest of the flock can finish it.
Uses: velocity dive in code, PORTAL and DAMAGE_INDICATOR particles, CRIT particles, DARKNESS and WEAKNESS effects

2. **Insomnia Aura** (Aura): A slow ring of purple ENTITY_EFFECT sparkles drifts around the phantom and every enemy beneath it feels the sleepless dread build. Enemies in the aura steadily gain NAUSEA and lose attack strength, and the longer they stay under it the stronger the phantom's own next dive becomes.
Uses: ENTITY_EFFECT particles with a purple color value, NAUSEA and WEAKNESS effects on enemies, STRENGTH effect on self scaling with aura time

3. **Moonlit Mend** (Defense): When hurt, the phantom breaks away and climbs into a shaft of moonlight rendered with END_ROD sparkles and pale DUST motes. While it circles in the light it rapidly regenerates, and it shrinks slightly smaller and harder to hit until it dives again.
Uses: REGENERATION effect, END_ROD and DUST particles, SCALE attribute lowered on self, FLYING_SPEED attribute

4. **Split Image** (Summon): The phantom screeches and tears into three phantoms in a burst of REVERSE_PORTAL particles: itself at full size and two smaller, faster copies at half scale. The copies deal light damage but constantly harass and body block, and killing a copy just makes it dissolve into portal sparks.
Uses: summon phantom with SCALE attribute at 0.5, REVERSE_PORTAL particles, MOVEMENT_SPEED attribute on the copies

5. **Veil of the Night** (Transformation): The phantom's body goes translucent and then fully invisible, leaving only two glowing eyes and a faint PORTAL particle trail where it flies. It keeps this veil until it attacks, and the attack that breaks it deals bonus damage from above with a SWEEP_ATTACK slash visual.
Uses: INVISIBILITY and GLOWING effects, PORTAL particles, SWEEP_ATTACK particles, ATTACK_DAMAGE attribute bonus on the breaking hit

## Giant

1. **Seismic Stomp** (Attack / Terrain): The giant raises one foot and brings it down in an EXPLOSION_EMITTER shockwave with BLOCK debris from the ground flying outward, cracking the top layer of earth into gravel and coarse dirt in a wide ring. Enemies in the ring are launched into the air and take multiplied fall damage when they land.
Uses: block placement (fill replacing the surface layer), EXPLOSION_EMITTER and BLOCK particles, FALL_DAMAGE_MULTIPLIER attribute on victims, upward velocity in code

2. **Colossus Form** (Transformation): The giant grows even larger, its silhouette looming with DUST_PILLAR dust shedding off its shoulders as it moves, and it simply walks up hills and over walls without jumping. In this form its health pool swells, its reach extends, and ordinary mobs barely reach its knees.
Uses: SCALE, MAX_HEALTH, STEP_HEIGHT, and ENTITY_INTERACTION_RANGE attributes, DUST_PILLAR particles

3. **Boulder Throw** (Attack): The giant rips a chunk from the ground, a block display of stone and dirt that it holds overhead for a beat, then hurls it in a high arc. The boulder lands as a real falling block, bursting into BLOCK particles and area damage, and leaves the chunk sitting in the terrain where it fell.
Uses: block_display held prop, falling block entity (summon), BLOCK particles, typed damage

4. **Living Mountain** (Defense): The giant plants its feet, crosses its arms, and its skin takes on a stony render tint while FALLING_DUST trickles off it. While rooted it is nearly immovable and heavily armored, slowly regenerating, and any enemy that hits it takes thorns style damage from the jagged stone hide.
Uses: KNOCKBACK_RESISTANCE and ARMOR attributes, RESISTANCE and REGENERATION effects, FALLING_DUST particles, reflected typed damage in code

5. **Stomp Call** (Summon): The giant stomps twice in a rhythm and two husks claw their way out of the cracked ground at its feet, dust and BLOCK particles spraying as they rise. The husks are oversized for their kind, fight at the giant's side, and rally to whatever the giant is currently stomping toward.
Uses: summon husk with SCALE attribute, BLOCK and DUST_PILLAR particles, FOLLOW_RANGE attribute on the summons

## Sulfur Cube

1. **Geyser Core** (Attack): The cube swells with SULFUR_BUBBLES and steam, then vents a full GEYSER_PLUME eruption from beneath itself, using the geyser particle family's water depth scaling for a towering plume. The eruption launches the cube and every enemy standing close high into the air with no eruption damage, and the fight continues wherever everyone lands.
Uses: GEYSER, GEYSER_PLUME, and GEYSER_POOF particles with water_blocks scaling, SULFUR_BUBBLES particles, upward velocity in code

2. **Hot Archetype** (Transformation): The cube absorbs a lava flavored archetype, its goo turning bright orange with FLAME and LAVA particles dripping off it and a constant shimmer of heat. In this form simple contact with the cube burns enemies with the sulfur cube hot damage type, and it leaves a short trail of fire flecks as it hops.
Uses: sulfur cube archetype registry (contact damage archetype), minecraft:sulfur_cube_hot damage type, FLAME and LAVA particles, BURNING_TIME attribute on victims

3. **Frictionless Slide** (Mobility): The cube flattens slightly, slicks the ground with SULFUR_CUBE_GOO, and takes off in a long frictionless slide that ricochets off walls without losing speed. It bowls through enemies during the slide, knocking them aside, and only stops when it hits a wall hard enough to splat goo in every direction.
Uses: FRICTION_MODIFIER attribute at 0 on self, SULFUR_CUBE_GOO particles, MOVEMENT_SPEED attribute, knockback in code

4. **Super Bounce Slam** (Attack / Mobility): The cube compresses, flashes with GEYSER_POOF puffs, and launches itself skyward, then comes down with maximum bounciness physics in a huge slam. The landing emits the bounce game event, blasts nearby enemies outward, and the cube rebounds off the ground two more times at shrinking height, each bounce a smaller shockwave.
Uses: BOUNCINESS attribute at 1 on self, bounce game event, SAFE_FALL_DISTANCE attribute, GEYSER_POOF particles, velocity in code

5. **Noxious Split** (Summon / Defense): When badly hurt, the cube bursts into two smaller sulfur cubes at half scale inside a spreading NOXIOUS_GAS_CLOUD, the greenish gas hanging over the area. Enemies caught in the cloud are hit with NAUSEA, and the two mini cubes bounce away in opposite directions, buying time to regroup.
Uses: summon sulfur_cube with SCALE attribute at 0.5, NOXIOUS_GAS and NOXIOUS_GAS_CLOUD particles, NAUSEA effect on enemies

## Bee

1. **Sting Barrage** (Attack): The bee blurs forward in a SPEED fueled dash, DUST motes streaking behind it, and stings its target three times in a blink, each sting landing with a CRIT spark. Unlike a normal bee it keeps its stinger and its health, but each sting stacks a short POISON that makes the barrage deadlier the longer it stays on one victim.
Uses: SPEED effect on self, velocity dash in code, CRIT particles, POISON effect on target

2. **Swarm Call** (Summon): The bee releases a burst of ANGRY_VILLAGER particles and a loud buzzing note, and three more bees dive in from above to join the fight. The swarm coordinates on the caller's target, and while at least two swarm mates live, every bee in the group gains a small STRENGTH bonus.
Uses: summon bee, ANGRY_VILLAGER and NOTE particles, STRENGTH effect on the group, FOLLOW_RANGE attribute

3. **Pollen Cloud** (Aura): The bee shakes out a drifting cloud of FALLING_NECTAR and HAPPY_VILLAGER sparkles that hangs in the air where it fights. Allies inside the cloud slowly regenerate, while enemies wading through it are weighed down with SLOWNESS as pollen cakes their joints.
Uses: FALLING_NECTAR and HAPPY_VILLAGER particles, REGENERATION effect on allies, SLOWNESS effect on enemies

4. **Honey Coat** (Defense): The bee coats itself in a glossy layer of honey, shown as a honey block display riding with it and LANDING_HONEY drips falling as it flies. The coat absorbs the next few hits, makes the bee resistant to knockback, and attackers who strike it get briefly stuck with SLOWNESS from the sticky mess.
Uses: block_display passenger (honey block), LANDING_HONEY and DRIPPING_HONEY particles, ABSORPTION and KNOCKBACK_RESISTANCE on self, SLOWNESS effect on attackers

5. **Queen Ascendant** (Transformation): The bee grows to twice its size, its stripes deepening to gold and black with HEART and ENCHANT particles swirling around it, and it gains a commanding hum. As a queen it hits harder, flies faster, and every bee ally near it fights with STRENGTH and SPEED.
Uses: SCALE, ATTACK_DAMAGE, and FLYING_SPEED attributes, HEART and ENCHANT particles, STRENGTH and SPEED effects on nearby bees

## Wolf

1. **Pack Howl** (Summon / Aura): The wolf throws its head back with NOTE particles rising in rings and lets out a long howl that carries across the area. Two wolves answer the call and lope in from the edges to fight beside it, and the whole pack gains SPEED and STRENGTH for the duration of the hunt.
Uses: summon wolf, NOTE particles, SPEED and STRENGTH effects on the pack, FOLLOW_RANGE attribute

2. **Hamstring Bite** (Attack): The wolf lunges low and tears at its target's legs in a spray of DAMAGE_INDICATOR particles and red DUST. The bite itself is modest, but it cripples: the victim is slowed heavily and its jump strength drops, making it easy prey for the rest of the pack.
Uses: velocity lunge in code, DAMAGE_INDICATOR and DUST particles, SLOWNESS effect and JUMP_STRENGTH attribute reduction on target

3. **Alpha Form** (Transformation): The wolf swells larger, its fur darkening and standing on end while its eyes glow with a red ENTITY_EFFECT tint and its breath puffs as white DUST in short bursts. As an alpha it has a bigger health pool, bites far harder, and nearby allied wolves fight harder simply for being near it.
Uses: SCALE, MAX_HEALTH, and ATTACK_DAMAGE attributes, ENTITY_EFFECT particles with a red color value, STRENGTH effect on nearby wolves

4. **Pounce** (Mobility): The wolf crouches, CLOUD dust kicking up at its paws, then launches in a long flat leap that carries it clear across a fight to land on a distant target. It takes no fall damage from the leap, and landing on an enemy pins it briefly with SLOWNESS while the wolf mauls it.
Uses: JUMP_STRENGTH and GRAVITY attributes during the leap, SAFE_FALL_DISTANCE attribute, CLOUD particles, SLOWNESS effect on the landed target

5. **Guardian Stance** (Defense): The wolf plants itself between its allies and the enemy, hackles raised, and lets out a low growl that draws enemy attention onto itself. While in the stance it gains RESISTANCE and a pool of absorption hearts, and enemies find their attacks pulled toward the wolf instead of its packmates.
Uses: RESISTANCE and ABSORPTION effects, target selection changes in code (hurt by target and defend style goals), ANGRY_VILLAGER particles

## Polar Bear

1. **Avalanche Charge** (Attack / Mobility): The bear drops to all fours and charges in a roaring wave of SNOWFLAKE and ITEM snowball particles, snow spraying from its paws with every stride. Anything it hits is smashed aside with huge knockback, and the charge only stops at a wall or after it has plowed through several enemies.
Uses: velocity charge in code, SNOWFLAKE and ITEM (snowball) particles, ATTACK_KNOCKBACK attribute, MOVEMENT_SPEED attribute during the charge

2. **Blizzard Aura** (Aura / Terrain): A local storm gathers around the bear, SNOWFLAKE particles thickening into a whiteout while real snow layers pile up on the ground inside the aura. Enemies in the storm are chilled with SLOWNESS and their attacks weaken, while the bear moves through its own blizzard at full speed.
Uses: block placement (setblock snow layers), SNOWFLAKE particles, SLOWNESS and WEAKNESS effects on enemies, MOVEMENT_EFFICIENCY attribute on self

3. **Deep Hibernate** (Defense): The bear curls into a ball and a mound of snow, shown with a snow block display, heaps over it until only its ears stick out. While hibernating it cannot attack but rapidly regenerates and is heavily resistant, and any enemy foolish enough to hit the mound gets a face full of SNOWFLAKE burst and knockback.
Uses: block_display (snow block) with interpolation, REGENERATION and RESISTANCE effects, SNOWFLAKE particles, knockback in code

4. **Icebreaker** (Terrain): The bear rears up and smashes the ground, and the water or ice around it cracks: open water freezes into a sheet of real ice blocks the bear and its allies can cross, while enemies standing on existing ice have it shattered out from under them into the water below.
Uses: block placement (fill with ice, and fill with water to break), BLOCK particles from ice, SPLASH particles, SLOWNESS effect on dunked enemies

5. **Ursa Major** (Transformation): The bear rises to its full height and keeps growing, fur frosted white at the tips, breath rolling out in CLOUD puffs with every snort. In this form it is a siege engine: far stronger, far tougher, and its swipes land with SWEEP_ATTACK arcs that hit everything in front of it at once.
Uses: SCALE, ATTACK_DAMAGE, and MAX_HEALTH attributes, CLOUD particles, SWEEP_ATTACK particles, SWEEPING_DAMAGE_RATIO attribute

## Llama

1. **Spit Volley** (Attack): The llama works its jaw, DUST motes puffing from its nostrils, then unloads a fanning volley of real llama spit projectiles that arc across the fight. Each glob that lands splashes with SPIT particles and leaves the target weakened and briefly seeing spots, and the volley can clip several enemies standing in a line.
Uses: llama spit projectiles (summon), SPIT particles, WEAKNESS and NAUSEA effects on targets

2. **War Carpet** (Defense): The llama's decorative carpet thickens into real armor, shown with an updated carpet equipment piece and a faint ENCHANT shimmer across the weave. In its war carpet the llama shrugs off a large share of physical damage and cannot be knocked back by ordinary hits, standing like a furry wall.
Uses: equipment swap in code (carpet slot), ARMOR and KNOCKBACK_RESISTANCE attributes, ENCHANT particles

3. **Caravan Call** (Summon): The llama brays and two trader llamas trot in from opposite sides, bells implied by NOTE particles, already leashed into formation with it. The three llamas fight as a unit, spitting at the same target, and while the caravan holds together all three gain RESISTANCE from watching each other's backs.
Uses: summon trader llama, NOTE and HEART particles, RESISTANCE effect on the group, FOLLOW_RANGE attribute

4. **Mule Kick** (Attack): Any enemy that sneaks up behind the llama gets both hind hooves in a single explosive kick, DUST_PILLAR erupting from the impact. The kick deals heavy damage and launches the victim backward and upward so hard that the landing does half the work.
Uses: ATTACK_KNOCKBACK attribute, DUST_PILLAR and DAMAGE_INDICATOR particles, FALL_DAMAGE_MULTIPLIER attribute on the victim

5. **Mountain Stride** (Transformation / Mobility): The llama stretches taller, its legs lengthening as its SCALE grows and its step turns springy with CLOUD dust at every footfall. It strides straight up steep slopes without jumping, moves noticeably faster, and can leap gaps that would stop other mobs cold.
Uses: SCALE, STEP_HEIGHT, MOVEMENT_SPEED, and JUMP_STRENGTH attributes, CLOUD particles

## Trader Llama

1. **Caravan Guard** (Summon): The trader llama stamps and calls, and two plain llamas hurry in to flank it, all three gaining a shimmer of ABSORPTION hearts as the formation sets. The guards intercept attackers heading for the trader llama, and the trader llama repays them by spitting at whatever they are fighting.
Uses: summon llama, ABSORPTION effect on the group, HEART particles, target selection changes in code (defend style goals)

2. **Emerald Spit** (Attack): The trader llama hocks up a spit glob studded with emerald shards, rendered with ITEM particles of emerald bursting on impact. The glob hits harder than ordinary spit and scatters a few emerald chips that any allied villager type mob nearby will scramble to pick up, healing slightly as they do.
Uses: llama spit projectile (summon), SPIT and ITEM (emerald) particles, typed damage, INSTANT_HEALTH effect on allied collectors in code

3. **Wanderer's Retreat** (Mobility): Sensing danger, the trader llama and its trader vanish together in a swirl of PORTAL particles and reappear a long way off in the direction they were already traveling. Both arrive with SLOW_FALLING so even a cliff edge retreat is safe, and the llama leaves a false trail of its own footprints in DUST behind it.
Uses: teleport in code, PORTAL and DUST particles, SLOW_FALLING effect

4. **Pack Mule Form** (Transformation): The trader llama bulks up, a chest block display settling onto its back as a passenger prop while its carpet gains saddlebags in the render. In this form it is far harder to move or kill, and it can carry a second mob as a rider, turning itself into a living mount for a smaller ally.
Uses: SCALE and KNOCKBACK_RESISTANCE attributes, block_display passenger (chest), MAX_HEALTH attribute, ride and passenger stacks in code

5. **Hard Bargain Aura** (Aura): A smug golden shimmer of HAPPY_VILLAGER and ENTITY_EFFECT sparkles hangs around the trader llama, the smell of a good deal. Allies in the aura fight with LUCK flavored crits and small STRENGTH, while enemies in it feel fleeced, losing attack strength as if every trade went against them.
Uses: HAPPY_VILLAGER and ENTITY_EFFECT particles, STRENGTH effect on allies, WEAKNESS effect on enemies, LUCK effect

## Goat

1. **Ram Catapult** (Attack): The goat paws the ground, DUST_PILLAR kicking up, then charges horn first with terrifying speed. The headbutt connects with the biggest knockback in the mod: the victim is launched in a high arc and takes multiplied fall damage wherever it comes down, while the goat skids to a stop, shaking its head.
Uses: velocity charge in code, ATTACK_KNOCKBACK attribute, DUST_PILLAR particles, FALL_DAMAGE_MULTIPLIER attribute on the victim

2. **Screaming Aura** (Aura): The goat opens its mouth and screams, and keeps screaming, NOTE particles and sound waves rolling off it in pulses. Enemies near the screaming goat are rattled with NAUSEA and WEAKNESS, and their attacks keep getting interrupted by the sheer noise, while the goat itself is too stubborn to care.
Uses: NOTE and ANGRY_VILLAGER particles, NAUSEA and WEAKNESS effects on enemies, playsound in code

3. **Cliff Master** (Mobility / Transformation): The goat's hooves spark with SCRAPE particles as it scales a near vertical cliff face in a series of impossible leaps, each landing precise on a ledge a pixel wide. In this state it jumps higher than any mob its size, ignores almost all fall damage, and can drop from the heights straight onto an enemy for a plunging headbutt.
Uses: JUMP_STRENGTH, SAFE_FALL_DISTANCE, and STEP_HEIGHT attributes, SCRAPE and CLOUD particles, velocity in code

4. **Horn Shatter** (Terrain): The goat lowers its head and rams the terrain itself, and stone, logs, and walls crack apart in a spray of BLOCK particles as real blocks break in front of it. It can batter a hole through a defensive wall in a few hits, and enemies standing on the other side of a shattered block are staggered by the debris.
Uses: block breaking in code (setblock to air with drops suppressed), BLOCK and BLOCK_CRUMBLE particles, BLOCK_BREAK_SPEED attribute, SLOWNESS effect on staggered enemies

5. **Stubborn Stance** (Defense): The goat locks its legs, lowers its head, and simply refuses to move, DUST settling around its planted hooves. While in the stance it cannot be knocked back at all and takes reduced damage, and attackers who charge it bounce off its horns and take a little damage for their trouble.
Uses: KNOCKBACK_RESISTANCE attribute at 1, RESISTANCE effect, DUST particles, reflected typed damage in code

## Panda

1. **Rolling Boulder** (Attack / Mobility): The panda tucks into a ball and rolls, gathering speed and a trail of DUST_PILLAR and TINTED_LEAVES, its body visibly tumbling as it goes. Everything in the roll path is bowled over with knockback and damage, and the panda only unrolls when it hits a wall or runs out of slope, slightly dizzy but unhurt.
Uses: velocity roll in code, DUST_PILLAR and TINTED_LEAVES particles, ATTACK_KNOCKBACK attribute, SAFE_FALL_DISTANCE attribute

2. **Bamboo Feast** (Defense / Heal): The panda sits down mid fight, pulls out a bamboo stalk shown as an item display in its paws, and munches it with HAPPY_VILLAGER sparkles and green CRIT style crumbs. While it eats it rapidly regenerates and gains SATURATION, and enemies tend to leave it alone for the first bite before realizing their mistake.
Uses: item_display passenger (bamboo item), HAPPY_VILLAGER particles, REGENERATION and SATURATION effects

3. **Sneeze Bomb** (Attack / Summon): The panda's nose starts twitching, a baby panda at its side copying it, until both let out an enormous sneeze of SNEEZE particles that blasts everything in front of them backward. The sneeze also summons the baby panda into the fight properly if it was not already there, small, fast, and surprisingly vicious.
Uses: SNEEZE particles, summon panda with SCALE attribute at 0.5, knockback in code, SPEED effect on the baby

4. **Drowsy Aura** (Aura): A lazy calm radiates from the panda, TINTED_LEAVES drifting down around it and the light seeming softer inside its bubble. Enemies in the aura grow sluggish with SLOWNESS and MINING_FATIGUE, their attacks coming slower and weaker, while the panda naps its way to small steady regeneration.
Uses: TINTED_LEAVES particles, SLOWNESS and MINING_FATIGUE effects on enemies, WEAKNESS effect on enemies, REGENERATION effect on self

5. **Kung Fu Form** (Transformation): The panda rises onto its hind legs, cracks its knuckles with a ring of CRIT sparkles, and takes a proper martial stance with a render shift to standing posture. In this form it attacks in fast sweeping combos, each strike throwing a SWEEP_ATTACK arc that hits groups, and it sidesteps incoming blows with bursts of SPEED.
Uses: SCALE and ATTACK_SPEED attributes, STRENGTH effect, SWEEP_ATTACK and CRIT particles, SPEED effect

## Iron Golem

1. **Catapult Arms** (Attack): The golem scoops its target up and hurls it straight into the sky, vines cracking with BLOCK_CRUMBLE particles as it throws. The victim hangs at the top of the arc for a heartbeat, then plummets, taking full fall damage on landing while the golem is already walking toward the next enemy.
Uses: upward velocity and LEVITATION pulse in code, BLOCK_CRUMBLE particles, FALL_DAMAGE_MULTIPLIER attribute on the victim

2. **Poppy's Revenge** (Aura): When a villager type ally or a smaller ally near the golem takes damage, the golem's eyes flare and a poppy red ENTITY_EFFECT glow washes over its frame. It gains SPEED and STRENGTH and beelines for whatever did the damage, and it does not calm down until that attacker is dead or far away.
Uses: ENTITY_EFFECT particles with a red color value, SPEED and STRENGTH effects, target selection changes in code (defend style goals), FOLLOW_RANGE attribute

3. **Fortress Form** (Transformation): The golem grows broader and heavier, crack lines spreading across a stony render tint, moss and vines thickening over its shoulders as FALLING_DUST trickles off it. In fortress form it is nearly immovable, hugely armored, and its arms sweep wide enough to hit several enemies per swing.
Uses: SCALE, ARMOR, and KNOCKBACK_RESISTANCE attributes, RESISTANCE effect, FALLING_DUST particles, SWEEPING_DAMAGE_RATIO attribute

4. **Ground Slam** (Terrain): The golem raises both arms and brings them down hard enough to crack the earth, EXPLOSION particles bursting as the top layer of ground in front of it is replaced with cracked stone and rubble. The shockwave knocks enemies off their feet, and the cracked ground remains as rough terrain that slows anything crossing it.
Uses: block placement (fill replacing the surface layer), EXPLOSION and BLOCK particles, knockback in code, SLOWNESS effect on enemies crossing in code

5. **Vine Snare** (Defense / Control): Thick vines burst from the golem's arms and the ground, real cobweb blocks webbing across the area around it while WEAVING energy runs through the snare. Enemies caught in the webs are slowed to a crawl and take damage over time from the tightening vines, and the golem uses the pause to reposition its bulk between them and its allies.
Uses: block placement (setblock cobweb), WEAVING effect on victims, POISON effect (vine damage over time), BLOCK_CRUMBLE particles

## Snow Golem

1. **Blizzard Barrage** (Attack): The snow golem's head spins and it machine guns snowballs in a wide cone, SNOWFLAKE particles filling the air until the fight looks like a snow globe. Each snowball chips damage and stacks SLOWNESS, and a target hit enough times ends up nearly frozen in place for the golem's allies to shatter.
Uses: snowball projectiles (summon), SNOWFLAKE particles, SLOWNESS effect stacking in code

2. **Snowdrift Wake** (Terrain / Mobility): As the golem moves it leaves a wake of real snow layers piling deeper behind it, and it surfs its own drift with a burst of speed. Enemies chasing through the fresh snow sink and slow, and the golem can double back over its own trail at full pace while pursuers flounder.
Uses: block placement (setblock snow layers), MOVEMENT_SPEED attribute on self, FRICTION_MODIFIER attribute raised on enemies in code, SNOWFLAKE particles

3. **Abominable Form** (Transformation): The little snowman swells into a hulking yeti of packed snow, SCALE tripled, stick arms replaced in the render by thick snowy limbs, its carrot nose now a jutting icicle. In this form it trades snowballs for brutal melee swipes that throw SWEEP_ATTACK arcs of snow and hit hard enough to matter.
Uses: SCALE, ATTACK_DAMAGE, and MAX_HEALTH attributes, SWEEP_ATTACK and ITEM (snowball) particles, STRENGTH effect

4. **Orbiting Snow Shield** (Defense): Three snow blocks lift off the ground as block displays and begin slowly orbiting the golem on interpolation paths, trailing SNOWFLAKE sparkles. Each orbiting block intercepts one incoming hit and shatters into ITEM snowball particles, and while any block remains the golem takes reduced damage.
Uses: block_display passengers with interpolation and teleport duration, ITEM (snowball) and SNOWFLAKE particles, RESISTANCE effect

5. **Powder Snow Trap** (Terrain): The golem gestures and a patch of ground ahead of it converts to real powder snow blocks, soft and innocent looking under a dusting of SNOWFLAKE. Enemies that walk in sink straight down, take freezing damage from the powder snow mechanic while they struggle, and climb out slowly and chilled.
Uses: block placement (fill with powder snow), powder snow freezing block mechanic, SNOWFLAKE particles, SLOWNESS effect on escapees

## Strider

1. **Lava Wake** (Terrain / Mobility): The strider picks up speed across the lava and its wake churns, real magma blocks briefly crusting the surface behind it amid FLAME and LAVA particle spray. Enemies following across the wake get burned by the hot crust, and the strider itself only gets faster the longer its wake runs.
Uses: block placement (setblock magma block, temporary), FLAME, LAVA, and FALLING_LAVA particles, MOVEMENT_SPEED attribute, BURNING_TIME attribute on victims

2. **Warped Frenzy** (Transformation): The strider sniffs out warped fungus and loses its mind a little, WARPED_SPORE particles puffing from its head as it shivers with excitement. In the frenzy it moves at absurd speed on any surface, lava or land, and rams enemies at full tilt for real damage instead of stumbling into them.
Uses: MOVEMENT_SPEED attribute heavily raised, WARPED_SPORE particles, SPEED effect, ATTACK_KNOCKBACK attribute

3. **Magma Spit** (Attack): The strider hawks up a glob of molten rock, DRIPPING_LAVA running from its mouth, and spits a small fireball that arcs into the fight. The glob bursts on landing with LAVA particles, sets the target burning, and leaves a short lived patch of real fire blocks on the ground.
Uses: small fireball projectile (summon), DRIPPING_LAVA and LAVA particles, block placement (setblock fire), burning via fire ticks in code

4. **Obsidian Skin** (Defense): The strider's hide cools and darkens into an obsidian shell, FALLING_OBSIDIAN_TEAR flecks shedding as it moves and its render tint going deep purple black. In this shell it takes heavily reduced damage and is immune to fire entirely, though it moves a little slower until the shell cracks away.
Uses: ARMOR attribute, RESISTANCE and FIRE_RESISTANCE effects, FALLING_OBSIDIAN_TEAR and DRIPPING_OBSIDIAN_TEAR particles, MOVEMENT_SPEED attribute lowered

5. **Strider Stack** (Summon / Mobility): A baby strider drops in from above and lands squarely on the adult's head, settling in as a passenger in a proper strider stack with HEART particles from both. The stack moves faster than either strider alone, the baby occasionally spits small lava globs at enemies, and killing the adult first leaves a very angry baby behind.
Uses: summon strider with SCALE attribute at 0.5, ride and passenger stacks in code, HEART particles, MOVEMENT_SPEED attribute on the stack

## Dolphin

1. **Torpedo Ram** (Attack / Mobility): The dolphin lines up, BUBBLE trail streaming behind it, and torpedoes through the water at ramming speed. It slams into its target with a burst of SPLASH and DAMAGE_INDICATOR particles, and the impact knocks the victim spinning sideways in the current.
Uses: WATER_MOVEMENT_EFFICIENCY attribute at 1, DOLPHINS_GRACE effect on self, BUBBLE and SPLASH particles, velocity in code

2. **Echolocation Pulse** (Aura): The dolphin clicks, and a visible DOLPHIN particle pulse ripples outward through the water, painting everything it touches. Enemies caught by the pulse are outlined with GLOWING through the murk for several seconds, and the dolphin and its allies gain DOLPHINS_GRACE while they hunt the marked targets.
Uses: DOLPHIN particles, GLOWING effect on enemies, DOLPHINS_GRACE effect on allies, FOLLOW_RANGE attribute

3. **Rescue Current** (Defense / Support): The dolphin circles an ally in trouble and kicks up a BUBBLE_COLUMN_UP stream that lifts the ally toward the surface while bubbles swarm around them. The rescued ally gains WATER_BREATHING and a burst of REGENERATION, and the current pushes enemies away from the rescue zone.
Uses: BUBBLE_COLUMN_UP and BUBBLE particles, WATER_BREATHING and REGENERATION effects on allies, knockback in code

4. **Aerial Slam** (Mobility / Attack): The dolphin launches itself clean out of the water in a sparkling arc, GRAVITY eased so it hangs at the peak, SPLASH particles raining off it. It crashes back down on top of a target at the surface, the re-entry burst dealing area damage and shoving everything nearby outward in a ring of foam.
Uses: GRAVITY attribute lowered during the leap, SAFE_FALL_DISTANCE attribute, SPLASH and CURRENT_DOWN particles, area typed damage in code

5. **Pod Call** (Summon): The dolphin whistles in a spray of BUBBLE_POP particles and two more dolphins porpoise in from the deep to join it. The pod swims in formation, all three gaining SPEED in the water, and they take turns ramming the same target in a rolling cycle that keeps it permanently off balance.
Uses: summon dolphin, BUBBLE_POP and SPLASH particles, DOLPHINS_GRACE and SPEED effects on the pod, FOLLOW_RANGE attribute

# Advanced Mobs: New Ability Ideas (Group D, v2, capability grounded)

## Pig

1. **Mudslide Charge** (Mobility / Attack): The pig drops into a belly slide, shedding brown dust motes and chunks of dirt as ground friction vanishes under it. It hurtles in a straight line at high speed and slams into the first enemy with heavy knockback, skidding to a stop in a spray of mud.
Uses: friction_modifier and movement_speed attributes, attack_knockback attribute, dust particle (brown), block particle (dirt block state).

2. **Truffle Tremor** (Terrain / Attack): The pig roots and stomps, and a ring of dirt block displays rises out of the ground around it on smooth interpolation before crashing back down. Enemies standing in the ring take typed ground damage and are thrown outward, and the pig often unearths a burst of composter sparkles as if it found something tasty.
Uses: block_display entities with transformation interpolation, block_crumble particle, /damage with a typed damage source, composter particle.

3. **Stormstruck Revenant** (Transformation): When a pig is caught by lightning or drops to low health during a thunderstorm, a lightning bolt strikes it and it rises again as a hulking zombified piglin warrior, bigger, armed, and furious. This leans on the real vanilla rule that lightning transforms pigs into zombified piglins, turned into a second phase for the fight.
Uses: /summon lightning_bolt, /summon zombified_piglin with equipment and attribute modifiers, weather hook (thunder), scale attribute.

4. **Mud Bath Bulwark** (Defense): The pig rolls in place and cakes itself in wet mud, shown by dripping mud block flecks and a dull brown coat. Its armor and toughness jump and it gains absorption hearts, shrugging off hits until the mud layer is beaten off.
Uses: armor and armor_toughness attributes, absorption effect, block particle (mud block state), dripping_water particle tinted by block flecks.

5. **Snort Shockwave** (Aura / Control): The pig lets out a snort so violent it fires a visible gust ring and a puff of ash gray breath. Enemies in front of it are shoved back and weakened, and smaller mobs are knocked clean off their feet.
Uses: gust particle, weakness effect, attack_knockback attribute, code knockback impulse, ash particle.

## Cow

1. **Stampede Engine** (Mobility / Attack): The cow lowers its head and charges with dust pouring off its hooves, gaining a big speed boost for a few seconds. Anything it runs through takes trample damage and knockback, and it can chain straight through a crowd.
Uses: movement_speed attribute, speed effect, dust particle, attack_knockback attribute, /damage typed as mob attack.

2. **Horned Bulwark** (Defense / Transformation): The cow grows to a larger scale, plants its feet, and becomes nearly immovable, with resistance and heavy knockback resistance. Enemies that charge it bounce off its horns and take a counter hit.
Uses: scale attribute, knockback_resistance attribute, resistance effect, armor attribute, sweep_attack particle on the counter hit.

3. **Moothquake Stomp** (Terrain / Attack): The cow rears up and slams both front hooves down, sending a crack of block crumble particles across the ground and popping nearby dirt and grass blocks into the air as displays that rain back down. Enemies nearby take damage and are launched upward.
Uses: block_crumble particle, block_display entities with interpolation, explosion particle (small), /damage, code upward impulse via Motion data.

4. **Cud Cannon** (Attack): The cow chews, glows faintly green with composter motes, and spits a wad of cud that arcs across the arena. On impact it bursts into splash particles and applies slowness and weakness, leaving the target sticky and vulnerable.
Uses: spit particle, /summon projectile (llama_spit style entity), splash particle, slowness and weakness effects.

5. **Pasture Sanctuary** (Aura / Support): The cow gives off a calm aura of heart particles and soft happy villager sparkles. Itself and any allied mobs nearby slowly regenerate, and the cow gains absorption whenever an ally inside the aura takes a hit.
Uses: heart particle, happy_villager particle, regeneration effect, absorption effect, team based ally targeting.

## Mooshroom

1. **Spore Burst** (Attack): The mooshroom shakes its cap and erupts in a cloud of mycelium motes and drifting spores. Enemies caught in the cloud are poisoned and slowed as the spores take hold, while the mooshroom stays immune inside its own haze.
Uses: mycelium particle, spore_blossom_air particle, poison and slowness effects, /particle with force mode.

2. **Fungal Plate Armor** (Defense): Red mushroom block displays sprout across the mooshroom back and sides, growing in with interpolation until it looks like a walking mushroom fortress. While plated its armor climbs sharply, and each hit it takes knocks a chunk of fungus loose as block particles.
Uses: block_display entities with transformation interpolation riding as passengers, armor attribute, block particle (red mushroom block state).

3. **Stew of Second Chances** (Support): The mooshroom offers a steaming bowl, shown by campfire smoke curling off its back and a ring of warm instant effect sparkles. Allied mobs in the ring, including itself, are healed and fed with saturation.
Uses: campfire_cosy_smoke particle, effect particle (warm color), instant_health and saturation effects, team based ally targeting.

4. **Mycelium Creep** (Terrain): Wherever the mooshroom fights, the ground itself converts to mycelium in a spreading patch, veined with purple mycelium particles. Enemies standing on the creep are slowed, and the mooshroom moves faster and regenerates on its own fungus.
Uses: /fill and /setblock with mycelium, mycelium particle, slowness effect on enemies, movement_speed and regeneration for the mooshroom, block based area checks in code.

5. **Sporeling Swarm** (Summon): The mooshroom sheds a clutch of tiny sporelings, baby mooshrooms at small scale that waddle straight at the enemy. They are fragile, but each one that dies pops in a puff of spores that poisons whatever killed it.
Uses: /summon mooshroom (juvenile, scale and max_health modifiers), poof particle, poison effect on death trigger, oozing style death hook in code.

## Sheep

1. **Woolquake** (Defense): The sheep puffs up into an enormous ball of wool, white item flecks and cloud particles bursting outward. In this state it gains absorption and high knockback resistance, and attackers get tangled, taking slowness when they strike it.
Uses: item particle (white wool item), cloud particle, absorption effect, knockback_resistance attribute, slowness effect on attackers.

2. **Ram Charge** (Attack / Mobility): The sheep lowers its head, pawing dust from the ground, then charges horn first with a speed burst. The impact lands with sweep attack particles and huge knockback, and the sheep can turn and charge again while the ability lasts.
Uses: movement_speed attribute, speed effect, attack_knockback attribute, sweep_attack particle, dust particle.

3. **Static Wool** (Aura): The sheep wool crackles with electric sparks as it shuffles about, building a static charge. Nearby enemies take small zaps of typed damage over time, and any enemy that hits the sheep gets a bigger jolt and a moment of weakness.
Uses: electric_spark particle, /damage with a typed damage source, weakness effect, enchant particle while charging.

4. **Rainbow Mirage Flock** (Control / Summon): The sheep wool cycles through colors in a wash of color transitioning dust while it splits the fight with decoys, summoning two plain sheep that look identical at a glance. Enemies waste their attacks on the decoys while the real sheep repositions under invisibility.
Uses: dust_color_transition particle, /summon sheep decoys, invisibility effect on the real sheep, tag to mark the original.

5. **Shear Terror** (Transformation): The sheep explodes its wool off in a blizzard of white item particles and emerges lean, fast, and mean. Stripped of its coat it gains speed and strength and attacks in quick flurries, until the wool slowly grows back and the ability ends.
Uses: item particle (white wool item), cloud particle, speed and strength effects, attack_speed attribute, scale attribute (slightly smaller).

## Chicken

1. **Egg Barrage** (Attack): The chicken takes to a perch position and machine guns eggs across the arena, each one bursting with egg crack particles. The eggs deal small damage but arrive so fast they stagger and peck apart unarmored mobs.
Uses: /summon egg projectiles, egg_crack particle, /damage typed as projectile, attack_speed style cooldown in code.

2. **Chick Swarm** (Summon): The chicken calls down the coop, summoning a flock of baby chickens at tiny scale that swarm the enemy legs. Individually harmless, together they body block, distract, and chip away while the mother hen strikes from behind.
Uses: /summon chicken (juvenile, scale modifier), poof particle, happy_villager particle, follow and target goals in code.

3. **Feather Storm** (Control / Aura): The chicken beats its wings and fills the air with a blizzard of feather item particles and cloud puffs. Enemies inside the storm are blinded briefly and slowed, flailing at feathers while the chicken slips between their legs.
Uses: item particle (feather item), cloud particle, blindness and slowness effects, invisibility effect (brief) on the chicken.

4. **Sky Tyrant Dive** (Mobility / Attack): The chicken flaps up in a flutter of feathers with a touch of levitation, hangs in the air with slow falling, then folds its wings and dive bombs a target. The dive lands with crit particles and bonus damage, and the chicken takes no fall damage from it.
Uses: levitation and slow_falling effects, gravity and safe_fall_distance attributes, crit particle, /damage with falloff by dive height in code.

5. **Golden Egg** (Defense / Summon): The chicken lays a single glowing egg, an item display shining at full brightness with enchant sparkles orbiting it. After a short delay it hatches in a firework burst into a golden chick, a small fast chicken with boosted attack that fights at her side.
Uses: item_display entity (egg item) with brightness and glow_color_override, enchant particle, firework particle, /summon chicken with attribute modifiers, /schedule style delayed trigger in code.

## Rabbit

1. **Killer Bunny Form** (Transformation): The rabbit eyes flash red, it grows a step larger, and its hops turn into lunges. In this form it gains strength and jump power and tears into enemies with rapid bites, a nod to the legendary killer bunny.
Uses: scale attribute, strength effect, jump_strength attribute, glowing effect (red team color outline), angry_villager particle.

2. **Burrow Ambush** (Mobility / Attack): The rabbit digs straight down in a spray of dirt block particles and vanishes, tunneling unseen for a moment. It erupts out of the ground behind its target and lands a guaranteed heavy crit before the enemy can turn.
Uses: block particle (dirt block state), invisibility effect, /teleport behind the target, crit particle, /damage typed as mob attack.

3. **Carrot Rain** (Attack / Terrain): The rabbit kicks up its hidden stash and a volley of carrot item displays arcs into the sky and spears down across an area. Each carrot that lands deals damage and sticks in the ground briefly, and enemies weaving between them are slowed.
Uses: item_display entities (carrot item) with interpolation arcs, /damage in the landing zone, slowness effect, poof particle on impact.

4. **Lucky Foot** (Aura / Defense): The rabbit foot glows with enchant sparkles and it becomes preposterously hard to pin down, gaining luck, speed, and jump boost. Enemies nearby are cursed with unluck and keep fumbling their attacks for reduced damage.
Uses: enchant particle, luck and unluck effects, speed and jump_boost effects, weakness effect on nearby enemies.

5. **Warren Call** (Summon): The rabbit thumps the ground and the warren answers, summoning several adult rabbits that scatter in every direction at high speed. The swarm draws enemy attention and gnaws at ankles, buying the caller room to line up a burrow ambush.
Uses: /summon rabbit with movement_speed modifiers, dust particle, team based targeting in code, tag to mark the caller.

## Horse

1. **Warhorse Charge** (Attack / Mobility): The horse paws the ground, dust kicking up, then launches into a full gallop charge with a long dust trail. It tramples straight through enemies with heavy damage and knockback, and its raised step height lets it thunder up and over blocks in its path.
Uses: movement_speed attribute, step_height attribute, attack_knockback attribute, dust particle, /damage typed as mob attack.

2. **Ironshod Bulwark** (Defense): The horse gear flashes and it is suddenly barded in diamond horse armor, fitted live through its equipment slot. Its armor value jumps, blocked hits ring out with electric spark particles, and it holds its ground against charges.
Uses: /item replace (horse armor slot, diamond horse armor), armor attribute, electric_spark particle, knockback_resistance attribute.

3. **Sky Gallop** (Mobility / Attack): The horse leaps impossibly high with lowered gravity, slow falling at the apex, then comes down hooves first on top of its target. The landing slams out a ring of block crumble particles and heavy area damage.
Uses: jump_strength and gravity attributes, slow_falling effect, block_crumble particle, /damage in the landing radius, safe_fall_distance attribute.

4. **Herd Sovereign** (Summon / Aura): The horse whinnies and two allied horses gallop in from the edges of the fight amid heart particles. While its herd lives, the sovereign and its allies share a strength aura and coordinate on the same target.
Uses: /summon horse with attribute modifiers, heart particle, strength effect, team based ally targeting and shared target goals in code.

5. **Nightmare Steed** (Transformation): The horse coat darkens through a custom render tint, soul fire flames lick along its mane, and its eyes glow. In this form it is fireproof, leaves a trail of blue flame particles, and its hooves ignite enemies it strikes.
Uses: custom render state tint in code, soul_fire_flame particle, fire_resistance effect, ignition on hit (fire ticks), movement_speed attribute.

## Donkey

1. **Mule Kick Barrage** (Attack): The donkey spins and unleashes a flurry of rear kicks, each landing with sweep attack particles and crit flashes. A clean kick launches the target with extreme knockback, and the donkey can pivot to kick the next enemy without pausing.
Uses: attack_knockback attribute, sweep_attack and crit particles, attack_speed attribute, /rotate to face each target.

2. **Pack Fortress** (Defense): The donkey braces, chest block displays riding on its back like a loaded caravan wall, and grows a size step larger. Its armor and knockback resistance spike, and it becomes a living barricade that enemies cannot shove aside.
Uses: block_display entities (chest block state) as passengers, scale attribute, armor and knockback_resistance attributes, resistance effect.

3. **Stubborn Stand** (Control / Defense): The donkey plants all four hooves and simply refuses to move, ground friction under it maxed and its body locked in place. Enemies that strike it during the stand hurt themselves on the counter, typed damage reflected back at the attacker.
Uses: friction_modifier attribute, knockback_resistance attribute, /damage reflected to the attacker (on attacker relation), poof particle, tag to mark the stand state.

4. **Cargo Catapult** (Attack): The donkey bucks its cargo loose and an anvil, barrel style crate, or stone block arcs high and crashes down on a marked enemy position. The falling block lands with block crumble and heavy damage, and a warning shadow display shows where it will fall.
Uses: /summon falling_block (anvil and stone block states), block_display shadow marker with shadow_radius, block_crumble particle, /damage on landing.

5. **Bray of Doom** (Aura / Control): The donkey throws its head back and brays so loudly a sonic boom ring rolls off it with note particles scattering. Enemies caught in the bray are nauseated and weakened, reeling while the donkey lines up a kick.
Uses: sonic_boom particle, note particle, nausea and weakness effects, code radial impulse.

## Mule

1. **Hybrid Vigor** (Transformation): The mule surges with the best of both parents, enchant sparkles running down its flanks. It gains strength, speed, and jump power all at once and fights like a horse with a donkey temper for the duration.
Uses: enchant particle, strength and speed effects, jump_strength attribute, attack_damage attribute.

2. **Surefoot Slam** (Terrain / Attack): The mule picks its way up any slope with raised step height, then rears and slams down, popping a fan of stone slab block displays out of the ground in front of it. Enemies in the fan take damage and are shoved back over the rubble.
Uses: step_height attribute, block_display entities (stone slab block state) with interpolation, block_crumble particle, /damage, code knockback impulse.

3. **Satchel Bombardier** (Attack): The mule shrugs a lit satchel charge off its pack and it sails in a high arc toward the enemy, fuse sparking. It lands as primed TNT and detonates in a real explosion that the mule watches from a safe, smug distance.
Uses: /summon tnt (primed, short fuse), smoke and flame particles on the arc, explosion_knockback_resistance attribute for the mule, tag to credit the kill.

4. **Iron Mule Endurance** (Defense): The mule just does not stop. Its health pool grows, regeneration kicks in, and it gains resistance, shrugging off damage that would drop a horse while heart particles occasionally drift off its hide.
Uses: max_health attribute, regeneration and resistance effects, heart particle, absorption effect.

5. **Dust Devil Dash** (Mobility): The mule kicks up a spinning column of dust pillar particles and launches into a frictionless dash, air drag cut to zero so it slides the full length of the arena. Enemies clipped during the dash are spun aside with knockback and a brief slow.
Uses: dust_pillar particle, air_drag_modifier and friction_modifier attributes, movement_speed attribute, attack_knockback attribute, slowness effect on clipped enemies.

## Skeleton Horse

1. **Gravecaller Host** (Summon): The skeleton horse rears in a storm of soul particles and calls its old cavalry, summoning two more skeleton horses each ridden by a bow armed skeleton, exactly like the classic skeleton trap. The host forms up and charges as one undead unit.
Uses: /summon skeleton_horse with /summon skeleton passengers (Passengers data), soul particle, team based shared targeting in code.

2. **Soulfire Hooves** (Attack / Aura): Blue soul fire flames trail from its hooves and its ribs glow with sculk soul wisps. Enemies it tramples are withered, and the ground it crosses keeps burning with soul flame particles for a few seconds.
Uses: soul_fire_flame and sculk_soul particles, wither effect on trample, /damage typed as mob attack, fire ticks on the path in code.

3. **Deathless Frame** (Defense): There is nothing left to kill. When the skeleton horse drops to low health it rattles, sheds a burst of poof and soul particles, and pulls itself back together with a surge of healing and absorption, once per fight phase.
Uses: poof and soul particles, instant_health and absorption effects, resistance effect, health threshold trigger in code.

4. **Thunder Herald** (Transformation): During thunderstorms the skeleton horse becomes a herald of the storm, lightning striking around it as it grows larger and gains a glowing outline. Its speed and strength surge while thunder lasts, and enemies near each lightning strike are rattled and slowed.
Uses: /summon lightning_bolt, scale attribute, glowing effect, strength and speed effects, slowness effect near strikes, weather hook (thunder).

5. **Bonechill Aura** (Aura / Control): A cold dread rolls off the skeleton horse in waves of pale dust and darkness closes in on nearby enemies. Foes inside the aura are slowed and weakened, and their vision pulses with the warden style darkness effect.
Uses: dust particle (pale gray), darkness, slowness, and weakness effects, sculk_charge_pop particle accents, follow_range based aura radius in code.

## Camel

1. **Desert Mirage** (Control): The camel kicks up a veil of dust and large smoke, and its outline wavers into invisibility inside the haze. Enemies caught in the veil are blinded briefly and swing at sand while the camel repositions.
Uses: dust and large_smoke particles, invisibility effect on the camel, blindness effect on enemies, cloud particle.

2. **Two Hump Fortress** (Defense): The camel kneels into a living dune, sandstone block displays cresting between its humps, and its scale and armor rise. It becomes a wall of a beast that ranged mobs break against, with heavy knockback resistance.
Uses: block_display entities (sandstone block state) as passengers, scale attribute, armor and knockback_resistance attributes, falling_dust particle (sand).

3. **Spit Volley** (Attack): The camel works its jaw and fires a rapid volley of spit globs in high arcs, each bursting into splash particles on impact. Targets are damaged and slowed by the sticky mess, and the volley can arc over cover.
Uses: spit particle, /summon projectile (llama_spit style entity), splash particle, slowness effect, /damage typed as projectile.

4. **Oasis Blessing** (Support): The camel kneels and a spring seems to bubble up around it, splash and falling water particles raining gently in a ring. Allies inside the ring regenerate and gain saturation, and burning allies are doused.
Uses: splash and falling_water particles, regeneration and saturation effects, fire tick clearing in code, team based ally targeting.

5. **Sandstorm Stride** (Mobility / Terrain): The camel strides out with huge step height, each footfall kicking up falling dust and laying a short lived trail of sand blocks behind it. It walks straight over obstacles that stop other mobs, and enemies following its trail are slowed in the loose sand.
Uses: step_height attribute, falling_dust particle, /setblock sand (temporary, restored by schedule in code), movement_speed attribute, slowness effect on the trail.

## Cat

1. **Nine Lives** (Defense): The cat refuses to die. When a hit would kill it, totem of undying particles burst around it, it is restored with a surge of health, and it vanishes into invisibility with a speed burst to escape, one life spent per trigger.
Uses: totem_of_undying particle, instant_health effect, invisibility and speed effects, lethal damage intercept in code with a per cat counter.

2. **Creeperbane Aura** (Aura / Control): The cat sits, tail lashing, radiating the quiet menace cats hold over creepers and phantoms. Those mobs inside its aura are weakened and slowed and their flee instincts kick in, steering them away from the cat while it picks its moment.
Uses: weakness and slowness effects (creeper and phantom targets), flee goal steering in code, angry_villager particle, follow_range based aura radius.

3. **Pounce Assassin** (Mobility / Attack): The cat crouches, tail twitching, then launches in a high arcing pounce with boosted jump strength, landing claws first on its target. The landing hit lands as a crit with bonus damage, and the cat springs away before retaliation.
Uses: jump_strength attribute, crit particle, /damage typed as mob attack, speed effect on landing, code leap impulse via Motion data.

4. **Alley Cat Swarm** (Summon): One yowl and the alley answers, summoning a gang of small fast cats that pour in from the edges. They swarm the enemy, drawing its attacks and raking for chip damage while the original cat circles for a pounce.
Uses: /summon cat (scale and movement_speed modifiers), poof particle, team based targeting in code, tag to mark the original.

5. **Night Prowl** (Transformation): When night falls the cat melts into the dark, invisible and silent with night vision bright eyes, moving faster and hitting harder. It strikes from the shadows and fades again between attacks until dawn or the fight ends.
Uses: invisibility, night_vision, speed, and strength effects, /time query gating in code, enchant particle on each re entry strike.

## Ocelot

1. **Jungle Phantom** (Mobility / Control): The ocelot melts into a swirl of tinted leaves and vanishes, reappearing in bursts of speed as it weaves through the fight. While phantom it is invisible and fast, and enemies lose track of it between strikes.
Uses: tinted_leaves particle, invisibility and speed effects, poof particle on each fade, tag to track the phantom state.

2. **Raking Fury** (Attack): The ocelot becomes a buzzsaw, attack speed cranked up as it rakes with both front paws in alternating sweep attack arcs. The flurry shreds unarmored mobs and keeps armored ones staggered and unable to line up a counter.
Uses: attack_speed attribute, sweep_attack and crit particles, /damage typed as mob attack, attack_damage attribute.

3. **Treefall Ambush** (Mobility / Attack): The ocelot scrambles straight up with a burst of levitation, perches for a heartbeat, then drops on its target from above. It ignores the fall entirely and the impact deals heavy damage with a burst of leaf and crumble particles.
Uses: levitation effect, safe_fall_distance and fall_damage_multiplier attributes, tinted_leaves and block_crumble particles, /damage scaled by drop height in code.

4. **Cub Guard** (Summon / Defense): The ocelot calls its cubs, summoning two small ocelots that stay glued to its flanks. While any cub lives the mother gains resistance and fights harder, and the cubs dart in to nip at whatever she attacks.
Uses: /summon ocelot (juvenile, scale modifier), resistance and strength effects, team based shared targeting in code, poof particle.

5. **Spotted Mark** (Control / Aura): The ocelot fixes its prey with a stare and the target lights up with a glowing outline, marked with a tag for the whole fight. Marked enemies are slowed, and the ocelot deals bonus damage to anything carrying its mark.
Uses: glowing effect, tag (marked state), slowness effect, /damage bonus against tagged targets in code, enchant particle on marking.

## Parrot

1. **Mimic Shriek** (Control): The parrot throws back its head and shrieks a perfect, deafening copy of its enemy war cry, note particles bursting from its beak in a ring. Enemies hit by the shriek are nauseated and weakened, reeling from hearing themselves screamed back at them.
Uses: note particle, sonic_boom particle (small ring), nausea and weakness effects, /playsound mimicry of the target mob sound.

2. **Feather Cyclone** (Aura / Attack): The parrot takes flight and orbits its target, feather item displays circling with it as passengers while loose feathers fill the air. Anything under the cyclone takes steady pecking damage from the spinning plumage and cannot shake the bird off.
Uses: item_display entities (feather item) as passengers with interpolation, item particle (feather item), /damage over time in code, flying_speed attribute.

3. **Flock Raid** (Summon): The parrot squawks a rally call and three more parrots dive in from the canopy in a rainbow of dust color transition trails. The flock divebombs in rotation, each bird striking once and wheeling away, impossible to swat them all.
Uses: /summon parrot with attribute modifiers, dust_color_transition particle, team based staggered attack goals in code, poof particle.

4. **Rainbow Plumage** (Transformation): The parrot plumage ignites into cycling rainbow color, dust shifting hue across its body as it gains a glowing outline and bursts of speed and jump boost. The dazzling display pulses blindness onto enemies that face it.
Uses: dust_color_transition particle, glowing, speed, and jump_boost effects, blindness effect pulses on facing enemies, billboard center item_display accent (gem item).

5. **Death Squawk Revenge** (Defense / Attack): The parrot carries a permanent wind charge in its feathers, shown by occasional gust puffs. When it dies the charge detonates in a wind burst that hurls enemies away in an explosion of feather item particles, and two angry parrots burst from the blast to avenge it.
Uses: wind_charged effect, gust particle, item particle (feather item), code death burst impulse, /summon parrot (two, hostile targeting).

## Fox

1. **Berry Bombardier** (Attack): The fox shakes a berry bush it has been hoarding and lobs clusters of sweet berries in high arcs. Each cluster bursts into red item particles on impact, dealing damage and leaving targets sticky and slowed in the juice.
Uses: item particle (sweet berries item), /summon projectile in code with berry visuals, splash particle, slowness effect, /damage typed as projectile.

2. **Snow Pounce Nova** (Mobility / Attack): The fox springs into its famous headfirst pounce, jump strength cranked, hanging at the apex before spearing down. It lands muzzle first in a burst of snow block crumble and the impact stuns and damages everything at the landing point.
Uses: jump_strength attribute, block_crumble particle (snow block state), /damage in the landing radius, slowness effect (high amplifier, brief) as the stun, safe_fall_distance attribute.

3. **Trickster Decoy** (Control): The fox slips sideways into invisibility and leaves a perfect decoy of itself standing where it was, a summoned fox with a taunting tag. Enemies commit their big attacks to the decoy while the real fox circles behind them.
Uses: /summon fox decoy (low max_health), invisibility effect on the real fox, tag to mark decoy and original, poof and tinted_leaves particles.

4. **Ember Thief** (Transformation): The fox snatches a coal from a campfire and its whole coat takes on a smoldering edge, flame particles curling off its tail. In this form it is fire resistant, faster, and its bites set enemies burning.
Uses: flame particle, fire_resistance and speed effects, ignition on hit (fire ticks), burning_time attribute on victims via code, campfire_signal_smoke accents.

5. **Den Call** (Summon): The fox yips and its litter answers, summoning baby foxes each already holding a weapon in its mouth, little swords and axes fitted through their equipment. The pups swarm in a pack and the mother fights bolder while they live.
Uses: /summon fox (juvenile) with /item replace mouth slot equipment, heart particle, strength effect on the mother, team based pack targeting in code.

## Frog

1. **Tongue Lash** (Attack / Control): The frog tongue fires out in a red dust streak faster than the eye can follow and sticks to its target. Instead of pulling prey in, the frog reels the enemy across the arena to itself, dealing damage on arrival and leaving it in biting range.
Uses: dust particle (red, as the tongue streak), /data modify Motion to drag the target, /damage typed as mob attack, crit particle on arrival.

2. **Marsh Gas Croak** (Aura): The frog throat swells and it croaks out a rolling cloud of greenish noxious gas that hangs low over the swampy ground. Enemies standing in the cloud are nauseated and poisoned, and the frog, bred for bad air, is completely at home in it.
Uses: noxious_gas_cloud particle, nausea and poison effects, area check in code, conduit_power style self immunity in code.

3. **Leapfrog Slam** (Mobility / Attack): The frog coils and launches in an enormous arc, jump strength and hang time boosted, crossing the whole arena in one bound. It lands belly first with a burst of poof and block crumble, damaging and knocking back everything at the splashdown.
Uses: jump_strength and gravity attributes, poof and block_crumble particles, /damage in the landing radius, attack_knockback attribute, safe_fall_distance attribute.

4. **Frogspawn Shield** (Defense / Terrain): The frog lays a glistening mat of frogspawn blocks in a ring around itself and settles into the middle with fresh absorption. Enemies pushing through the spawn mat are slowed by the jelly, and the frog regenerates while it sits in its nursery.
Uses: /setblock frogspawn ring, absorption and regeneration effects, slowness effect on enemies crossing, bubble particle accents.

5. **Golden Hour Form** (Transformation): The frog basks and turns golden, glowing dust motes orbiting it and pearlescent froglight item displays circling as passengers. In this radiant form it gains strength and speed, and the orbiting lights pulse blindness onto nearby enemies.
Uses: dust particle (gold), item_display entities (pearlescent froglight item) as passengers with brightness, strength and speed effects, blindness effect pulses.

## Tadpole

1. **Schooling Surge** (Summon): One tadpole becomes a school, summoning a cloud of tiny tadpoles in a swirl of bubbles. The school moves as a shimmering bait ball that surrounds the enemy, each member nipping for tiny damage that adds up fast.
Uses: /summon tadpole (scale modifier, many), bubble and bubble_pop particles, team based swarm targeting in code.

2. **Bubble Veil** (Defense): The tadpole surrounds itself in a rising column of bubbles and gains resistance and easy breathing, effectively armored in foam. Attackers striking the veil pop bubbles instead of flesh and their hits land soft.
Uses: bubble_column_up and bubble particles, resistance effect, breath_of_the_nautilus effect, absorption effect.

3. **Metamorphosis** (Transformation): Mid fight the tadpole tail shrinks, legs sprout, and in a burst of happy villager sparkles it becomes a frog, bigger, stronger, and able to fight on land and water alike. The new frog keeps the tadpole upgrades and gains a fresh health surge.
Uses: /summon frog replacing the tadpole (passengers and data carried in code), happy_villager particle, poof particle, strength effect, max_health attribute on the new form.

4. **Current Rider** (Mobility): The tadpole reads the water and becomes one with it, moving at full speed underwater with almost no drag. It darts in spirals of underwater particles, striking and vanishing into the current before enemies can track it.
Uses: water_movement_efficiency attribute (full), dolphins_grace effect, underwater and current_down particles, air_drag_modifier attribute.

5. **Algae Bloom** (Terrain / Support): The tadpole stirs the pond into bloom, seagrass and kelp blocks sprouting through the water around it in moments. Enemies tangled in the growth are slowed, and the tadpole regenerates while it hides in its garden.
Uses: /setblock seagrass and kelp in water, slowness effect on enemies, regeneration effect on the tadpole, underwater particle, block based area checks in code.

## Turtle

1. **Shell Fortress** (Defense): The turtle pulls in head and flippers and becomes a dome of pure shell, armor and toughness maxed and knockback simply not applying. It cannot move in this state, but almost nothing can hurt it either, and it waits out enemy cooldowns in perfect safety.
Uses: armor and armor_toughness attributes, knockback_resistance attribute, resistance effect, movement_speed attribute (near zero while active), poof particle on entry and exit.

2. **Tide Ram** (Attack / Mobility): In water the turtle becomes a torpedo, dolphins grace and swim speed surging as it lines up a ram. It slams shell first into its target with splash particles bursting and heavy knockback that sends the enemy tumbling through the water.
Uses: dolphins_grace effect, water_movement_efficiency attribute, splash particle, attack_knockback attribute, /damage typed as mob attack.

3. **Egg Bastion** (Summon / Terrain): The turtle hauls out and lays a clutch of turtle eggs in a defended ring around itself, settling over them with fresh absorption. On a delay the eggs hatch in puffs of egg crack particles into baby turtles that immediately join the fight at her side.
Uses: /setblock turtle_egg clutch, absorption effect, /schedule style delayed /summon turtle (juvenile) in code, egg_crack particle.

4. **Ancient Wisdom** (Aura / Support): This turtle has seen a century of fights. It radiates a slow, calm aura of heart particles, regenerating itself and allies nearby, and in water it grants the whole group conduit power so nobody drowns mid battle.
Uses: heart particle, regeneration effect, conduit_power effect (in water), absorption effect, team based ally targeting.

5. **Shellshock Slam** (Attack / Control): The turtle snaps and flips its whole weight into a rising slam that catches enemies and tosses them skyward with a brief touch of levitation. They hang helpless for a moment, then fall hard, taking fall damage the turtle smugly watches from below.
Uses: levitation effect (brief, on enemies), /data modify Motion upward impulse, fall_damage_multiplier attribute on victims via code, block_crumble particle, /damage typed as fall on landing.

## Armadillo

1. **Rollout** (Mobility / Attack): The armadillo tucks into a perfect ball, shown by a custom ball form render state, and physics takes the wheel: bounciness up, friction down, speed surging. It ricochets around the arena bouncing off walls and enemies, and every bounce off an enemy deals damage with a scute fleck burst.
Uses: bounciness and friction_modifier attributes (26.2 physics attributes), movement_speed attribute, custom render state ball form in code, item particle (armadillo scute item), /damage on bounce collisions (bounce game event hook).

2. **Scute Shield** (Defense): The armadillo hunkers and its scutes lock together like plate armor, item flecks of scute shedding off each blocked hit. Its armor climbs, it gains resistance, and small attackers glance off harmlessly.
Uses: item particle (armadillo scute item), armor attribute, resistance effect, knockback_resistance attribute, poof particle.

3. **Burrow Blitz** (Mobility / Attack): The armadillo digs down in a spray of dirt block particles and tunnels unseen beneath the fight. It bursts out directly under its target, launching the enemy upward with the eruption and landing the first hit before they come down.
Uses: block particle (dirt block state), invisibility effect while burrowed, /teleport under the target, /data modify Motion upward impulse on the victim, crit particle.

4. **Bug Buffet** (Support): The armadillo snuffles through the dirt mid fight and snacks on grubs, composter particles and little hearts drifting up as it feeds. Each snack restores health and stacks saturation, and a well fed armadillo gains a strength bonus from the protein.
Uses: composter and heart particles, instant_health and saturation effects, strength effect, block based ground check in code.

5. **Armored Convoy** (Summon): The armadillo calls its pups and they arrive already rolling, a convoy of small bouncy armadillos at reduced scale. Each pup is a little bowling ball with the same bounce physics, scattering enemies as they carom through the fight.
Uses: /summon armadillo (juvenile, scale modifier) with bounciness attribute modifiers, dust particle, team based swarm targeting in code.

## Sniffer

1. **Ancient Seed Mortar** (Attack): The sniffer digs its snout in, unearths something ancient, and launches pitcher pod item displays in high arcs over the fight. Each pod bursts on landing in a spray of spores and explosion particles, dealing area damage where it falls.
Uses: item_display entities (pitcher plant item) with interpolation arcs, spore_blossom_air particle, explosion particle (small), /damage in the burst radius, dig delay in code.

2. **Sniff Out Weakness** (Control / Aura): The great snout works the air, enchant sparkles streaming into its nostrils, and it reads the fight perfectly. Every enemy it has sniffed is marked with a glowing outline and a tag, weakened, and the sniffer hits marked targets harder.
Uses: enchant particle, glowing effect, tag (marked state), weakness effect, /damage bonus against tagged targets in code.

3. **Rooted Colossus** (Transformation / Defense): The sniffer plants its feet and grows, scale climbing until it towers, friction maxed so nothing can shove it and knockback resistance total. While rooted on grass or dirt it regenerates steadily, an ancient hill with a nose.
Uses: scale attribute, friction_modifier and knockback_resistance attributes, regeneration effect (ground block check in code), armor attribute, dust particle.

4. **Pollen Haze** (Aura): The sniffer shakes out a drifting haze of spore blossom air and cherry leaf particles that hangs over the whole fight. Enemies breathing it are nauseated and slowed, while allies in the haze regenerate, soothed by the ancient pollen.
Uses: spore_blossom_air and cherry_leaves particles, nausea and slowness effects on enemies, regeneration effect on allies, team based targeting.

5. **Seed Vault** (Summon / Terrain): The sniffer plants a living garden mid fight, torchflower and pitcher plant blocks sprouting in a ring around it through setblock. The flowers periodically puff happy villager sparkles that heal allies standing in the garden, and the pitcher plants spit poison spores at enemies who step in.
Uses: /setblock torchflower and pitcher plant ring, happy_villager particle, instant_health effect pulses for allies, poison effect for enemies, /schedule style periodic trigger in code.

## Cod

1. **Bait Ball Barrage** (Summon / Attack): The cod darts in a tight circle, trailing bubble and splash particles, and calls in four temporary cod that form a spinning bait ball around its target. The school batters the enemy from every side for a few seconds, each hit a small silver flash of crit particles, then the extra cod scatter in a burst of bubble_pop particles and vanish.
Uses: /summon cod (temporary allies), bubble, splash, bubble_pop, crit particles, typed damage via /damage.

2. **Slipstream Snatch** (Mobility / Attack): A sleeve of bubble_column_up particles streams behind the cod as it rockets forward in a straight line, fast enough to leave a visible current in the water. Anything it passes through takes a ramming hit with heavy knockback, and the cod keeps most of its speed after the impact instead of stopping.
Uses: bubble_column_up, bubble particles, movement_speed and water_movement_efficiency attribute modifiers, attack_knockback attribute, Motion changes in code.

3. **Downcurrent Net** (Terrain / Control): The cod plants a ring of sinking current columns around the fight, shown by current_down and underwater particles spiraling toward the floor. Enemies swimming inside the ring are dragged downward and slowed, pinned near the bottom where the cod and its allies can strike them, while the cod itself swims through the currents freely.
Uses: current_down, underwater particles, slowness effect on enemies, water_movement_efficiency modifier on self, area control in code.

4. **Silver Flash School** (Defense): The whole school turns sideways at once in a flash particle burst tinted bright silver, and the real cod slips away under invisibility while its schoolmates scatter. Enemies in front of the flash are blinded for a couple of seconds and lose track of which fish is the fighter.
Uses: flash particle (ARGB color), blindness effect on enemies, invisibility effect on self, poof particles.

5. **Frenzy Current Aura** (Aura): The cod churns the water around it into a permanent swirl of bubble and fishing particles that marks its hunting ground. Allied fish and squid inside the swirl gain speed and dolphins_grace, swimming rings around slower enemies, and the aura tightens into a faster spin whenever an ally lands a kill nearby.
Uses: bubble, fishing particles, speed and dolphins_grace effects on allies, aura tracking in code.

## Salmon

1. **Upstream Leap** (Mobility / Attack): The salmon launches itself clear out of the water in a high arc, wrapped in splash and falling_water particles, and crashes down on its target with its full body weight. It can chain up to three leaps in a row, each one faster, crossing pools and short stretches of land that would stop any other fish.
Uses: splash, falling_water particles, jump_strength and gravity attribute modifiers, air_drag_modifier set low mid-leap, fall damage via safe_fall_distance control, typed damage on landing.

2. **Rapids Run** (Terrain): The salmon stamps out a lane of rushing water marked by current_down and bubble particles that flows against its enemies. Foes caught in the lane are shoved backward and tumbled, while the salmon swimming upstream through its own rapids gains movement speed and attack damage, fighting like the current itself is pushing it forward.
Uses: current_down, bubble particles, Motion changes in code on enemies, movement_speed and attack_damage modifiers on self.

3. **Scale Shed Snare** (Defense / Terrain): When hit hard, the salmon bursts out of a cloud of its own loose scales, shown with item particles using a salmon scale colored item sprite and block particles, and leaves the slick patch behind on the ground or in the water. Enemies crossing the patch slide with almost no grip, overshooting their turns, while the salmon escapes with a short speed burst.
Uses: item particle (item stack debris), block particle (block state debris), friction_modifier lowered on enemies in the patch, speed effect on self, poof particles.

4. **Red Run** (Transformation): The salmon flushes deep red along its flanks and its jaw hooks visibly, a render state texture swap done in code, with angry_villager particles puffing from its gills. In this spawning run form it gains strength and armor, ignores knockback, and its hits make enemies bleed wither for a short time, but it slowly tires and the form fades after the fight peaks.
Uses: custom render state texture swap in code, angry_villager particles, strength and armor attribute modifiers, knockback_resistance modifier, wither effect on hit.

5. **Waterfall Barrage** (Attack): The salmon hurls itself at the enemy again and again from alternating sides, each pass a streak of bubble and crit particles, too fast to track. Every third pass ends in a tail slap that deals sweeping damage to everything clustered around the target.
Uses: bubble, crit, sweep_attack particles, Motion changes in code, sweeping_damage_ratio attribute modifier, attack_speed modifier.

## Tropical Fish

1. **Kaleidoscope Flash** (Attack / Control): The fish fans its fins wide and strobes through the whole spectrum, dust_color_transition particles cycling red to blue to gold washing over everything nearby. Enemies caught in the light show are nauseated and briefly blinded, their aim swimming, while the little fish darts between them untouched.
Uses: dust_color_transition particle (start color, end color, scale), flash particle, nausea and blindness effects on enemies.

2. **Coral Crown Garden** (Terrain): Bright coral fans and tubes sprout in a defensive ring around the fish, real coral blocks set into the seafloor with block_display coral pieces tilted at jaunty angles above them. The ring blocks enemy movement and line of sight, and anything that pushes through the coral takes small piercing damage from the sharp branches.
Uses: block_display (coral block states), /setblock and /fill in code for coral blocks, block particle (coral debris), typed damage in code.

3. **Anemone Guard** (Defense): The tropical fish backs into a summoned anemone prop, a cluster of waving block_display and item_display pieces in neon greens and pinks that glows at full brightness. While it shelters inside, attackers that strike it are poisoned by the anemone sting, and the fish recovers health as if being groomed.
Uses: block_display and item_display with brightness override and interpolation, poison effect on attackers, regeneration effect on self.

4. **Mirage School** (Summon / Defense): Three shimmering copies of the fish appear, summoned tropical fish with the glowing effect and no appetite for fighting, each trailing a different color of dust particles. Enemies waste their attacks on the mirages, which pop into effect particles when struck, while the real fish, unglowing and dull by comparison, repositions for a strike.
Uses: /summon tropical_fish decoys, glowing effect on decoys, effect particle (colored), dust particle (color and scale), team assignment via /team to prevent friendly fire confusion.

5. **Prism Scale Aura** (Aura / Transformation): The fish swells slightly and its scales catch impossible light, a slow pulse of dust particles in shifting rainbow bands rolling off its body in rings. Allies inside the rings gain regeneration and a small scale increase of their own, looking grander and hitting a little harder, and the aura bursts in one final bright flash when it ends.
Uses: scale attribute modifier on self and allies, dust particle (color and scale), regeneration effect on allies, flash particle on end.

## Pufferfish

1. **Full Inflation** (Transformation / Defense): The pufferfish gulps water and balloons to triple size in a spray of bubble and poof particles, spikes bristling in every direction. Inflated, it is a living obstacle: high armor and knockback resistance, a much bigger hitbox that body blocks doorways and currents, and enemies that bump into it take spike damage.
Uses: scale attribute modifier (up to 3x), armor and knockback_resistance modifiers, bubble and poof particles, typed contact damage in code.

2. **Tetrodotoxin Mist** (Attack): The pufferfish vents a billowing green cloud, noxious_gas_cloud particles rolling outward at surface level, thick enough to hide in. Enemies breathing the mist are poisoned and nauseated, and the cloud lingers as area denial that the pufferfish happily inflates inside of.
Uses: noxious_gas_cloud, noxious_gas particles, poison and nausea effects on enemies, area denial in code.

3. **Spine Volley** (Attack): Still inflated, the pufferfish shudders and fires a ring of its own spines in every direction, crit and item particles streaking outward. Each spine deals piercing damage and carries a short poison, turning a surrounded pufferfish from cornered prey into the most dangerous thing in the pool.
Uses: crit and item particles, summoned projectile entities in code, poison effect on hit, /damage with projectile damage type.

4. **Rolling Mine** (Mobility / Attack): The inflated pufferfish tucks in and becomes a bouncing hazard, ricocheting off walls, floors, and enemies with heavy contact damage and a poof of bubbles at every bounce. It steers only loosely at this speed, trading aim for chaos, and deflates safely at the end instead of bursting.
Uses: bounciness attribute modifier (near 1), explosion_knockback_resistance modifier, Motion changes in code, bubble and poof particles, typed contact damage.

5. **Deflation Dash** (Mobility / Defense): The pufferfish blows out all its water at once in a bubble and splash jet and rockets backward, small again and suddenly very fast. The jet itself shoves enemies back, and the fish uses the half second of confusion to vanish around cover at full swim speed.
Uses: bubble, splash particles, scale attribute modifier reset, Motion impulse in code, attack_knockback style shove via /damage knockback, speed effect on self.

## Squid

1. **Ink Nova** (Defense / Control): The squid detonates a sphere of ink, squid_ink particles flooding the water in a perfect dark globe, and vanishes into the middle of it. Enemies inside the globe are blinded and wrapped in darkness, swinging at nothing, while the squid jets out the far side at burst speed.
Uses: squid_ink particles, blindness and darkness effects on enemies, speed effect and Motion impulse in code on self.

2. **Tentacle Slam** (Attack): The squid rears up and brings all its arms down at once, sweep_attack and crit particles cracking through the water with a ring of bubbles. The slam deals heavy damage in a short cone and drags the target toward the squid, setting up a grapple of rapid small hits.
Uses: sweep_attack, crit, bubble particles, attack_damage and attack_knockback attribute modifiers, Motion pull in code.

3. **Jet Siphon** (Mobility): A focused burst of bubble_pop and bubble particles fires from its siphon and the squid torpedoes backward faster than most fish swim forward, weaving between obstacles. During the jet it takes reduced damage, riding its own pressure wave, and it can fire the jet twice more before resting.
Uses: bubble, bubble_pop particles, air_drag_modifier lowered in code, resistance effect during the jet, Motion impulse in code.

4. **Ink Clone Decoy** (Summon / Defense): The squid leaves behind a perfect inky double, a summoned squid held nearly still and outlined with the glowing effect, sitting in a slowly expanding ink cloud. Enemies commit to the decoy, and when it is destroyed it bursts into one last squid_ink cloud that blinds its killer.
Uses: /summon squid decoy, glowing effect on decoy, squid_ink particles, blindness effect on the decoy killer, /team assignment in code.

5. **Abyssal Drag** (Terrain / Control): The squid anchors itself and churns up a downcurrent, current_down and underwater particles spiraling to the seafloor in a wide column. Enemies in the column are pulled steadily down and slowed, their oxygen draining as the squid picks at them from the edge of the dark water.
Uses: current_down, underwater particles, slowness effect on enemies, Motion changes in code, oxygen pressure via drowning rules in code (no custom effect).

## Glow Squid

1. **Biolume Pulse** (Aura / Attack): The glow squid pulses in waves of light, glow particles and glow_squid_ink motes radiating out through the dark water. Every enemy the light touches is marked with the glowing effect, outlined through rock and ink for all to see, and the squid own attacks hit marked targets harder.
Uses: glow, glow_squid_ink particles, glowing effect on enemies, attack_damage modifier against marked targets in code.

2. **Hypno Flicker** (Control): Its spots strobe in a slow, rolling pattern, flash particles pulsing at a rhythm that is hard to look away from, dust motes drifting like marine snow. Enemies watching the display are nauseated and slowed, drifting toward the light instead of fighting, until a hard hit snaps them out of it.
Uses: flash particle (ARGB color), dust particle (color and scale), nausea and slowness effects on enemies.

3. **Starfall Ink** (Attack): The glow squid rises and rains glowing ink from above, fat droplets of glow_squid_ink particles arcing down in a sparkling curtain. Each droplet that lands on an enemy deals damage and leaves it glowing, turning a whole enemy school into a constellation of targets.
Uses: glow_squid_ink particles, /damage with magic damage type, glowing effect on hit, area targeting in code.

4. **Deep Dark Cloak** (Defense): The glow squid kills its lights and pulls the dark in with it, a reverse pulse of squid_ink particles and true darkness settling over the area. It gains invisibility while enemies are wrapped in the darkness effect, their detection range shrinking to almost nothing in the black.
Uses: squid_ink particles, darkness effect on enemies, invisibility effect on self, follow_range interaction via invisibility detection rules in code.

5. **Lure Light** (Summon / Control): The squid leaves a single perfect light behind, a summoned glow squid decoy burning at maximum brightness with a halo of glow particles, hanging motionless in the water column. Enemy fish and predators are drawn to investigate the lure and cluster around it, packed tight and lit up, exactly where the real glow squid wants them for a Starfall Ink.
Uses: /summon glow_squid decoy, glowing effect on decoy, glow particles, target redirection via goal and brain edits in code, brightness style emphasis via glow effect.

## Villager

1. **Emerald Barrage** (Attack): The villager panics, reaches into its robes, and starts throwing its savings, a stream of emeralds shown by item particles and flying emerald projectiles. Each emerald hits like a thrown brick, and the barrage gets faster and wilder the longer the villager feels cornered.
Uses: item particle (emerald item stack), summoned projectile entities in code, /damage with projectile damage type, attack_speed style cooldown control in code.

2. **Iron Guardian Call** (Summon): The villager rings for help, happy_villager particles bursting overhead, and an iron golem hauls itself out of the ground nearby in a shower of block particles and dirt. The golem fights at the villager side, defending it fiercely, and the villager wisely keeps the fight between the golem and everyone else.
Uses: /summon iron_golem ally, happy_villager particles, block particle (dirt block state), /team assignment for alliance, defend target goals in code.

3. **Cursed Deal** (Control): The villager offers its enemy a trade too good to refuse, angry_villager particles huffing as it scribbles the contract. The enemy that accepts, or is hit by the flying paperwork of item particles, is saddled with weakness and unluck, while the villager pockets absorption hearts and walks away whistling.
Uses: angry_villager, item particles, weakness and unluck effects on enemy, absorption effect on self, /playsound villager trade sounds in code.

4. **Marketplace Barricade** (Terrain): Stalls slam down in a defensive row, block_display market stalls with striped wool canopies and crate props, backed by real placed blocks so enemies must climb or detour. The villager trades insults from behind the counter at full safety and restocks its nerve, regenerating while the barricade stands.
Uses: block_display (planks, wool, and crate block states) with transformation offsets, /setblock and /fill in code for backing blocks, regeneration effect on self while sheltered.

5. **Hero of the Village Rally** (Aura): The villager climbs its own barricade, or just stands tall, and leads the charge it would rather watch, happy_villager and composter particles drifting like confetti and a text_display banner overhead reading SALE in bold letters. Allies nearby gain strength and speed, inspired by the discounts, and the villager itself glows with hero_of_the_village pride.
Uses: text_display with billboard center and background color, happy_villager and composter particles, hero_of_the_village, strength, and speed effects, glow via glowing effect on self.

## Wandering Trader

1. **Llama Caravan Guard** (Summon): With a whistle and a swirl of happy_villager particles, the trader calls in two trader llamas, already carpeted in their blue and gold gear, which take up flanking positions. The llamas spit at the trader enemies on their own, streams of spit particles and real llama spit projectiles, and body block charges aimed at their owner.
Uses: /summon trader_llama allies with equipment via /item replace in code, spit particles, llama spit projectile entities, /team assignment for alliance.

2. **Invisibility Getaway** (Defense / Mobility): Cornered, the trader does what its kind has always done: chugs a potion in a swirl of effect particles and vanishes, only its lead and a faint trail of poof particles showing where it went. It reappears a safe distance away at a jog, speed boosted, ready to pretend the whole fight is beneath its notice.
Uses: invisibility and speed effects on self, effect particle (potion color), poof particles, /teleport in code for repositioning after dismount free movement.

3. **Exotic Goods Roulette** (Attack): The trader upends its whole pack at the enemy, a fountain of random merchandise, item particles of dyes, saplings, buckets, and stranger things, each thrown item carrying a random small effect. One hit might poison, the next might slow, the next might do nothing but bonk, and neither side knows which until it lands.
Uses: item particle (varied item stacks), /loot driven random item selection in code, poison, slowness, or weakness effects chosen at random on hit, summoned projectile entities in code.

4. **The Wandering Road** (Mobility / Terrain): The trader snaps its fingers, portal particles spiral, and it simply is somewhere else, reappearing at the far side of the fight next to a freshly placed campfire with campfire_cosy_smoke curling up. The little camp marks its fallback point, and the trader fights at range from it, retreating there whenever the road calls again.
Uses: portal, reverse_portal particles, /teleport in code, /setblock campfire in code, campfire_cosy_smoke particles, position memory in code.

5. **Bargain Bin Blast** (Attack): The trader kicks over its bargain bin and everything in it goes off at once, an explosion particle at the center of a spray of junk item particles and a heavy knockback wave. The blast itself is more shove than harm, scattering a whole enemy group and buying the trader room to run its getaway.
Uses: explosion, poof, item particles, explosion_knockback_resistance check via /damage explosion type in code, attack_knockback style impulse via Motion changes in code.

## Allay

1. **Note Block Harmony** (Aura): The allay hums a circling tune, note particles in shifting colors streaming off it in a spiral as it orbits the fight. Allies inside the spiral gain speed and regeneration, moving like they are dancing, and every note the allay sings makes the spiral wider and brighter.
Uses: note particles, speed and regeneration effects on allies, aura tracking in code, /playsound note block sounds in code.

2. **Fetch and Drop** (Attack): The allay swoops up whatever heavy thing is lying around, shown straining under an item_display anvil or stone block that follows it smoothly overhead, and drops it squarely on its target. The falling prop deals real falling block damage where it lands, in a poof of block particles, and the allay is already off fetching the next one.
Uses: item_display and block_display with teleport_duration smoothing to follow, /summon falling_block for the drop, block particles, /damage with fall damage type in code.

3. **Duet** (Summon): The allay splits its song into harmony and a second allay fades in beside it in a swirl of enchant particles, carrying a matching item_display prop. The pair mirror each other movements, doubling every fetch, drop, and delivery, and enemies splitting their attention between the twins get hit from twice as many angles.
Uses: /summon allay ally, enchant particles, item_display props, mirrored goal behavior in code, /team assignment for alliance.

4. **Sonic Chime** (Attack): The allay rings itself like a bell, a single clear tone drawn as a compact sonic_boom ring bursting outward through the air. Enemies caught by the ring take damage and are knocked spinning away, and the chime leaves their ears ringing with a short weakness.
Uses: sonic_boom particle, /damage with sonic boom style typed damage in code, weakness effect on enemies, Motion impulse in code.

5. **Keepsake Cyclone** (Terrain / Control): The allay spins up a gentle cyclone of loose treasures, item_display trinkets and item particles orbiting in a rising helix threaded with enchant particles. The cyclone tugs enemies off balance toward its center and shreds their aim, while the allay rides the top of its own storm, untouchable and delighted.
Uses: item_display with teleport_duration and interpolation for orbiting props, item and enchant particles, Motion changes in code on enemies, levitation micro doses in code to ride the cyclone top.

## Axolotl

1. **Playing Dead** (Defense): Shot or cornered, the axolotl flops over and plays dead exactly like its wild instincts demand, belly up and still, a few heart particles drifting up. Enemies drop it as a target and turn away, while underneath the act it is regenerating fast and stacking resistance, and it springs back to life the moment their backs are turned.
Uses: regeneration and resistance effects on self, target clearing via goal and brain edits in code, heart particles, custom render state pose in code.

2. **Gills of the Deep** (Aura): The axolotl fans its feathery gills wide and breathes for the whole team, bubble particles streaming from its gills in a slow, calm current. Allies in the current gain conduit_power and breath_of_the_nautilus, fighting underwater at full strength with their air frozen, while the axolotl itself never wants for breath.
Uses: bubble particles, conduit_power and breath_of_the_nautilus effects on allies and self, aura tracking in code.

3. **Predator Snap** (Attack): The lazy swimmer explodes into a lunge, a blur of splash and crit particles, jaws snapping shut on fish sized enemies and anything careless enough to drift close. The snap deals heavy damage and briefly pins the target with slowness, the way real axolotls vacuum up prey before it can react.
Uses: splash, crit particles, Motion lunge in code, attack_damage attribute modifier during the snap, slowness effect on target.

4. **Mud Camouflage** (Defense / Transformation): The axolotl burrows half into the mud and goes still, block particles of mud and clay puffing over its back as its color dulls to match the bottom in a render state texture swap. Half buried and nearly invisible in murky water, it waits out danger and ambushes whatever wanders over its hiding spot with a free first strike.
Uses: block particle (mud block state), custom render state texture swap in code, invisibility effect in water in code, attack_damage modifier on the ambush hit.

5. **Regeneration Bloom** (Transformation): Wounded past a threshold, the axolotl does what its species is famous for: it simply regrows, heart and happy_villager particles blooming as it rapidly regenerates and gains health_boost, even regrowing a little larger and frillier than before. The bloom can only happen once in a while, but while it runs the axolotl is effectively a brand new fighter.
Uses: heart, happy_villager particles, regeneration and health_boost effects, scale attribute modifier small increase, cooldown tracking in code.

## Bat

1. **Sonar Pulse** (Control): The bat hangs, listens, and fires a pulse of sound drawn as expanding rings of end_rod and sculk_charge particles sweeping through the cave. Every enemy the rings pass over is outlined with the glowing effect, revealed through walls and darkness, and the bat fights its revealed targets with bonus damage.
Uses: end_rod, sculk_charge particles (roll angle), glowing effect on enemies, attack_damage modifier against revealed targets in code.

2. **Dive Bomb** (Attack): The bat folds its wings and drops like a stone, gravity cranked up and a streak of smoke particles behind it, pulling out at the last instant to rake its target. The dive hits hard for something so small, and the bat converts the fall straight back into climb speed, gone before the enemy finishes turning around.
Uses: gravity attribute modifier during the dive, smoke particles, crit particles on hit, Motion changes in code, safe_fall_distance modifier on self.

3. **Darkness Shroud** (Defense / Aura): The bat beats its wings and the cave goes darker around it, darkness effect pulsing over nearby enemies while ash and smoke particles thicken the air. Inside its shroud the bat is nearly impossible to track, flitting between enemies that can barely see a block ahead of them.
Uses: darkness effect on enemies, ash and smoke particles, invisibility detection range reduction via code, follow_range attribute interaction in code.

4. **Rabid Swarm** (Summon): A screech, and the cave ceiling gives up its secrets: three more bats pour out of the dark in a flutter of poof particles and join the attack. The swarm is fragile but maddening, each bat bite carrying a chance of weakness, and enemies waste their big swings on empty air.
Uses: /summon bat allies, poof particles, weakness effect on bite in code, /team assignment for alliance.

5. **Stalactite Drop** (Terrain / Attack): The bat darts to the ceiling above its enemy and worries at the stone until a pointed dripstone spike gives way, a summoned falling_block of pointed dripstone dropping with a whistle of falling_dust particles. The spike deals heavy piercing damage where it lands and pins the spot as a hazard shard, and the bat is already choosing the next stalactite.
Uses: /summon falling_block (pointed dripstone block state), falling_dust and block particles, /damage with fall damage type in code, ceiling position checks in code.

