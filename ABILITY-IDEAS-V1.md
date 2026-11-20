# Advanced Mobs: New Ability Ideas

Five fresh ability designs for every one of the 81 mobs, 405 in total. None of these are implemented. They replace the old ability set (hunger attack, horde summon, piercing shot, multishot, ranged potion mastery, shadow step, void grasp, poison mastery, creeper power, creeper potion cloud, void bombardment), which has been stripped out of the code on a local branch.

How to pick: go mob by mob and choose your top 3 of the 5. Reply with the mob name and the three numbers or names you want, in any order, a few mobs at a time or all at once, and those become the build list.

# Advanced Mobs: New Ability Designs, Group A

Design notes only, no implementation. Each mob gets five abilities that differ in kind: a mix of attack, defense, summoning, terrain, transformation, aura, and mobility powers.

## Zombie

1. **Graveborn Horde** (Summon): The zombie slams both fists into the ground and the dirt around it cracks open with brown dust and green soul wisps, and two or three baby zombies claw their way up out of the holes over a couple of seconds. The babies are fast, swarm the zombie's current target, and crumble into dirt particles when killed instead of dropping loot. While the horde is alive the parent zombie gains a small speed boost, as if driven on by its own pack.

2. **Hulking Rot** (Transformation): The zombie swells to about one and a half times its normal size, its arms thicken, and glowing green veins pulse under torn skin while rot particles drip off it. In this form it gains a large health and knockback resistance increase and its hits land much harder, but it moves slower and cannot fit through one block gaps. The form lasts until it takes a set amount of damage, at which point it deflates back to normal size in a burst of spores.

3. **Seismic Slam** (Attack): The zombie leaps into the air with a grunt and comes down with both arms overhead, cracking the ground in a ring around the landing point. A visible shockwave of dirt chunks, gravel particles, and a dust ring rolls outward, dealing damage and heavy knockback to every mob caught in it and briefly stunning smaller mobs. It leaves cracked dirt and coarse dirt patches behind for a while as a scar on the terrain.

4. **Plague Miasma** (Aura): A low green fog rolls off the zombie and pools around its feet in a wide radius, with drifting spores and the occasional bubble pop particle. Enemy mobs standing in the fog slowly lose health, move sluggishly, and have their healing reduced, while undead allies in the fog are unaffected. The fog clings for a few seconds after the zombie leaves an area, so retreating enemies still choke on it.

5. **Undying Flesh** (Defense): The first time the zombie would die, it instead falls apart into a heap of limbs and rot particles, then the pieces twitch, crawl back together, and it stands back up at half health with a burst of green sparks. It can only do this once per fight, and during the few seconds it is in pieces it cannot be targeted. Fire damage taken while it is down prevents the reassembly and finishes it for real.

## Zombie Villager

1. **Rustbound Guardian** (Summon): The zombie villager raises its arms and calls out, and a corroded iron golem with vines, rust patches, and one glowing green eye assembles itself from iron blocks and scrap that fly in from the ground around it. The guardian is slower than a normal golem but hits with huge sweeping knockback and always steps between the zombie villager and whatever is attacking it. When the guardian dies it collapses into a pile of iron ingots and poppies that fade away.

2. **Emerald Barrage** (Attack): It digs into a phantom merchant pouch and hurls handfuls of glowing emeralds that arc through the air trailing green sparkles and chiming softly. Each emerald that hits deals solid damage and knocks a random piece of the target's equipment loose or degrades its weapon, fitting a cursed parody of trading. Missed emeralds stick in the ground briefly as shiny decoys before vanishing.

3. **Bell Toll of the Fallen Village** (Aura): A translucent spectral bell appears above the zombie villager and tolls, sending visible golden-green sound rings pulsing outward. Every toll speeds up and slightly heals nearby undead allies, and enemy mobs caught in a ring get a brief shiver of slowness as the sound washes over them. The bell fades after several tolls, with the last ring being the widest.

4. **Plague Produce** (Attack): It lobs rotten melons, pumpkins, and suspicious stew bowls from an unseen stockpile, each trailing flies and drip particles. On impact the produce splatters into a sticky puddle that poisons and slows anything standing in it, and the puddles linger as terrain hazards for several seconds. The zombie villager prefers to throw from behind cover or its guardian, like a merchant who never stopped working the stall.

5. **Barricade Stall** (Defense / Terrain): The zombie villager slams down a makeshift market stall barricade of oak doors, fences, and a striped awning in front of itself, physically blocking the path. While behind the barricade it rapidly regains health and cannot be hit by melee from the front, though the barricade itself has health and can be smashed through. When the barricade breaks it splinters into wood particles and the zombie villager scrambles away with a short speed burst.

## Husk

1. **Sandstorm Veil** (Aura / Mobility): The husk spins up a personal cyclone of sand, dust, and grit particles that whips around it in a tall column. Inside the storm the husk moves much faster and is hard to target, projectiles fired into the storm get deflected by the wind, and enemies standing in it take scratching damage and get pushed around by gusts. The storm leaves a trail of sand layers on the ground that slowly blow away.

2. **Dune Burial** (Mobility / Attack): The husk dives headfirst into the ground with a spray of sand and travels beneath the surface as a moving mound with dust puffs, completely untargetable. It erupts beneath its target in an explosion of sand blocks and particles, launching the target upward and burying its feet in fresh sand that slows it. On hard ground like stone it cannot burrow and instead just does a short sand blast leap.

3. **Mirage Doubles** (Summon / Defense): Heat shimmer ripples around the husk and it splits visually into three figures: the real husk and two shimmering sand clones with wavy, translucent bodies. The clones copy its movements with a slight delay, deal no damage, and dissolve into sand when struck, but enemies and targeting AI pick between all three. Every time a clone is destroyed the real husk gets a brief speed boost from the released heat.

4. **Desiccation Grasp** (Attack): When the husk lands a melee hit, ghostly dry hands of sand latch onto the target and visibly wring moisture out of it, shown as water droplets being pulled out and evaporating into steam. The target is slowed, weakened, and loses health over several seconds while the husk heals for part of what was drained. Against plant and water themed mobs the drain is stronger, and the husk's hands stay sandy and cracked for a moment after.

5. **Sunscorch Pillar** (Attack / Terrain): The husk raises both arms to the sky and a pillar of blinding light and white-gold fire particles crashes down on a marked spot, preceded by a growing bright circle on the ground as a warning. The impact scorches the earth, turning sand to glass and dirt to coarse dirt in a small radius, sets enemies ablaze, and leaves a patch of glowing hot ground that burns anything standing on it for a few seconds. It works best in open sky and fizzles to a weaker version under a ceiling.

## Drowned

1. **Tidal Surge** (Attack / Terrain): The drowned raises its trident or arms and a rolling wall of water blocks surges forward from behind it, foam particles flying and shells and kelp tumbling in the wave. The wave shoves every enemy it catches backward, deals impact damage when they slam into walls, and leaves a temporary stream of flowing water across the ground that the drowned can swim through at full speed even on land. After the surge passes, the water drains away over a few seconds.

2. **Barnacle Shell** (Defense / Transformation): Coral, barnacles, and shell plates grow over the drowned's body in patches of pink, white, and orange, and its silhouette gets bulkier and rougher. While armored it gains strong damage reduction and anything that hits it in melee takes thorn damage from the sharp shells, shown by shell shard particles. The shell cracks visibly as it absorbs damage and shatters off in chunks when depleted, after which the drowned is briefly faster without the weight.

3. **Trident Tempest** (Attack): The drowned calls down a storm of spectral glowing tridents that swirl in a vortex above the fight, water droplets and lightning sparks crackling between them. Tridents rain down one by one onto marked spots around its target, each sticking in the ground with a splash and a small electric burst before dissolving. The drowned can keep moving and fighting while the tempest plays out, making it dangerous to stand still against.

4. **Abyssal Whirlpool** (Terrain / Control): A swirling vortex of water and bubble particles opens on the ground at a target's feet, with seaweed and debris spiraling inward. Enemies caught in the whirlpool get dragged toward the center, spun around, and slowed, and flying or jumping mobs get pulled down into it. The drowned takes reduced damage from anything trapped in its own whirlpool, and the vortex collapses with a final upward bubble burst that pops victims into the air.

5. **Sunken Crew** (Summon): Bubble columns erupt from the ground or water nearby and two drowned sailors rise out of them wearing tattered, unique looks: one with a captain's hat of kelp, one bulkier with an anchor chain wrapped around its arm. The crew fights beside the summoner, and the chained one occasionally throws its anchor to yank a distant enemy closer. When killed, the crew sinks back down in a spiral of bubbles instead of falling over.

## Skeleton

1. **Bone Spire Eruption** (Attack / Terrain): The skeleton stomps and a line of jagged bone spikes erupts from the ground toward its target, each spike bursting up with bone shard particles and a hollow clatter. Enemies in the line take damage and get impaled briefly, pinned in place for a moment, and the spikes remain as a short-lived bone fence that blocks movement. The skeleton likes to fire arrows over its own spikes while enemies are stuck.

2. **Floating Skull Sentinel** (Summon): The skeleton pulls off a spare skull, which ignites with pale blue soul fire in its eye sockets and floats up to hover at the skeleton's shoulder. The sentinel independently shoots small bolts of soul fire at the skeleton's enemies and bobs and weaves to dodge. If the skeleton takes a hit that would kill it, the sentinel can sacrifice itself in a flash of blue flame to keep the skeleton alive at low health.

3. **Ribcage Snare** (Control): The skeleton rips out its own ribcage, which grows to a huge size mid-air, and hurls it spinning at a target. On impact the ribcage slams down as a cage of curved bone bars around the victim, trapping it for several seconds while the skeleton shoots at it from range. The cage can be broken by attacking the bars, and the skeleton regrows a normal ribcage with a puff of bone dust once the snare ends.

4. **Scatter and Reassemble** (Defense / Mobility): When badly hurt, the skeleton deliberately collapses into a scattered pile of bones that skitter and roll away in different directions, completely untargetable. The bones regroup at a safer spot nearby and stack back together into the skeleton with a snap and a puff of dust, restoring a chunk of health. Enemies that chase the pile find nothing to hit, and the skeleton often reappears behind cover already drawing its bow.

5. **Ossuary Colossus** (Transformation): Loose bones from the ground and from fallen skeletons stream through the air and bolt themselves onto the skeleton, building it up into a three block tall colossus with a horned skull, ribcage armor, and glowing white eyes. The colossus gains big health, knockback immunity, and sweeping melee swipes that hit in an arc, and its arrows become heavy bone lances. When the form ends, the extra bones rain down in a clattering shower.

## Stray

1. **Blizzard Shroud** (Aura): A swirling cloak of snowflakes, ice crystals, and pale mist wraps around the stray and spreads into a wide chilly zone. Enemies inside the shroud steadily build up freeze, moving and attacking slower the longer they stay, and their vision range effectively shrinks in the blowing snow. The stray itself glides through its own blizzard at full speed, trailing sparkling frost.

2. **Ice Tomb** (Control): The stray fires a special arrow that bursts into crawling frost on impact, and ice grows rapidly up and over the target until it is sealed inside a translucent ice block with the victim visible inside. The trapped mob takes cold damage over time and cannot act until the ice is broken by allies, its own struggle over several seconds, or the stray's focus shifts. Fire based mobs melt out faster, and the tomb shatters into glittering shards when it breaks.

3. **Glacial Lance** (Attack / Terrain): The stray draws back an oversized spear of clear blue ice and hurls it in a straight line, leaving a trail of frost particles and a cracking sound. The lance pierces through multiple enemies in a row, and wherever it passes it lays down a strip of packed ice and ice spikes on the ground that make footing treacherous for everyone except the stray. The strip lingers as slippery terrain that the stray can slide along for a speed boost.

4. **Aurora Veil** (Transformation / Mobility): Ribbons of green and violet aurora light pour off the stray's tattered cloak and it becomes semi-transparent, like a figure seen through frosted glass. In this form it gains a large speed increase, makes almost no sound to draw attention, and periodically flickers a few blocks forward in a shower of sparkles, making it very hard to pin down. Its arrows during the veil leave little aurora trails and slow targets more than usual.

5. **Shardling Pack** (Summon): The stray kneels and packs snow and ice into three small golem-like shardlings with coal eyes and stick arms, which pop to life with puffs of snow. Shardlings are fragile but fast, and they throw snowballs that build up freeze and knock enemies off balance. When a shardling dies it bursts into a cloud of chilling powder that slows everything around the spot.

## Bogged

1. **Sporeburst Volley** (Attack): The mushrooms growing on the bogged's body swell, glow faintly, and launch as a volley of puffball spores that drift toward enemies on the air. Each spore that pops against a target releases a cloud of toxic green spores that poison and confuse, and the clouds hang in the air briefly as drifting hazards. The mushrooms regrow over time, so the bogged's silhouette visibly refills between volleys.

2. **Mire Snare Roots** (Terrain / Control): The bogged stamps the ground and the earth around its enemies turns to sucking mud with bubbling brown particles, while mangrove roots burst up and wrap around their legs. Snared mobs are rooted in place and sink slightly, taking damage if they struggle, and the mud patch spreads a little whenever it rains. The bogged wades through its own mire without slowing, arrows ready.

3. **Swamp Gas Ignition** (Attack): Bubbles of swamp gas rise around the bogged and it looses a fire-tipped shot or strikes a spark, igniting the gas in a chain of rolling green-blue fireballs across the area. Each pocket of gas pops in sequence, so enemies get chased by a line of explosions and burning patches that linger on the water and mud. The bogged itself is fireproof in the brief blaze and emerges from the smoke with fresh mushrooms sprouting.

4. **Fungal Bulwark** (Defense): A huge mushroom cap sprouts from the bogged's back and shoulders, unfolding like a living umbrella shield with spotted red and white coloring. While the bulwark is up, damage from the front is heavily reduced and projectiles bounce off the cap with a soft boing and a puff of spores. The cap wilts as it takes damage and folds away when depleted, and standing in rain makes it regrow noticeably faster.

5. **Bog Wisp Lanterns** (Summon): The bogged shakes loose a trio of glowing green wisps that look like little floating lantern mushrooms, which orbit it lazily shedding sparkles. When an enemy comes near, a wisp darts at it and bursts in a flash that poisons, briefly blinds with spore glare, and marks the target so the bogged's arrows home in slightly. Wisps regrow one at a time from the bogged's back after they are spent.

## Wither Skeleton

1. **Wither Brand Detonation** (Attack): Its stone sword ignites with black and gray wither flames, and any enemy it strikes gets a burning wither skull mark branded onto them, visible as a small dark skull icon wreathed in smoke above their head. After a short delay the brand detonates in a burst of wither particles, dealing heavy damage and withering the victim, and brands can chain to nearby marked enemies. The sword stays aflame for the whole rampage, trailing smoke with every swing.

2. **Soul Harvest** (Aura / Sustain): Whenever any mob dies near the wither skeleton, a pale soul wisp streams out of the corpse and orbits the skeleton, up to a visible ring of collected souls. Each stored soul slightly increases its damage and speed, and when the wither skeleton takes a big hit it consumes a soul in a flash of gray light to heal instead of dying down further. The ring of souls makes it visibly more dangerous the longer a war drags on around it.

3. **Obsidian Rampart** (Terrain / Defense): It drives its sword into the ground and a jagged wall of obsidian and blackstone spikes tears up out of the earth in front of it with purple crystal glints. The rampart blocks movement and line of sight, has real health, and anything that climbs or breaks through takes damage from the sharp crystal edges. The wither skeleton uses the wall to break up charges and buy time for its brands to tick down.

4. **Wither Herald** (Transformation): The skeleton grows taller and gaunter, a tattered cloak of wither rose petals and black smoke forms around it, and a third spectral wither skull materializes floating beside its head. In herald form it can loose explosive wither skulls at range like its master, its melee applies stronger wither, and roses sprout and die in black particles wherever it walks. The form burns through its stored souls to sustain itself, ending when they run out.

5. **Ashen Dash** (Mobility / Defense): The wither skeleton dissolves into a streak of black ash and coal dust and surges forward several blocks, passing through enemies and leaving a trail of smoldering ash on the ground. Anything it passes through gets a brief wither singe, and the ash trail burns feet for a few seconds. During the dash it cannot be hit, and it often uses it to escape behind its rampart or to appear behind an archer.


## Creeper

1. **Bloom Blast** (Attack / Terrain): When this creeper explodes, instead of only destruction it detonates into a shockwave of seeds, petals, and green sparks that carpets the area in flowers, tall grass, vines, and moss. Enemies caught in the blast still take damage and knockback, but the terrain is left overgrown rather than cratered, and the plants briefly entangle survivors' feet. A smaller creeper sprout with a flower on its head grows from the center of the bloom a moment later to carry on the fight.

2. **Charged Stormform** (Transformation): Lightning arcs crawl over the creeper's body in a permanent blue-white aura, its face glows, and static particles snap and pop around it for the duration. The stormform creeper moves faster, its fuse burns shorter, and its explosion radius and damage grow dramatically, with electric arcs chaining to nearby metal wearing mobs. It leaves little scorch marks and occasional fires where it walks, and the charge visibly crackles louder as its fuse lights.

3. **Sprout Mines** (Terrain / Summon): As it walks, the creeper sheds small creeper-head buds that burrow into the ground, leaving only a tiny green sprout visible. When an enemy steps near a bud, it pops up on a stalk, hisses for a split second, and bursts in a small explosion of dirt and leaves. The creeper can have several mines planted at once and likes to retreat across its own minefield.

4. **Fuse Rush** (Mobility / Attack): The creeper lights its own fuse early and rocket-propelled by sparks and smoke, it hurtles in a straight line at high speed, wobbling and shedding embers. If it reaches its target it detonates on arrival; if it misses or is stopped, it survives the rush burned and briefly stunned instead of exploding, having vented the blast as propulsion. The rush leaves a scorched dashed line across the ground.

5. **Underbrush Camouflage** (Defense / Ambush): The creeper crouches and its texture shifts into a perfect disguise as a leafy bush with small flowers, becoming still and silent with only occasional drifting leaf particles. While camouflaged it rapidly recovers health and other mobs will walk right past it unless they bump into it. It breaks cover with a point blank hiss, getting a damage bonus on that first explosion for the ambush.

## Spider

1. **Broodmother Ascendant** (Transformation / Summon): The spider swells to double size, its abdomen grows round and pulses with visible egg sacs, and its eyes multiply and glow red. In this form it periodically lays eggs that hatch into spiderlings which swarm its enemies, and its own bites deal heavy damage with big knockback from its sheer mass. The form slowly drains its health to sustain the brood, ending in an exhausted shrink back to normal size.

2. **Silk Snarefield** (Terrain / Control): The spider rapidly weaves a wide carpet of layered webs across the ground and between nearby blocks, finished with a flourish of silk strands fired from its spinnerets. Enemies crossing the snarefield get progressively tangled, slowing more the deeper they push in, while the spider itself skitters across the top at increased speed. Struggling victims shake loose strands that alert the spider with visible web vibrations.

3. **Webline Slingshot** (Mobility): The spider fires a strand of web at a distant block or ceiling and instantly winches itself along the line in a blur, legs tucked, silk trailing. It can slingshot past enemies to land behind them, or anchor to the ground and yank itself down from a wall to pounce. Landing from a slingshot grants its next bite bonus damage, rewarding hit and run play.

4. **Prey Cocoon** (Control / Sustain): The spider tackles a weakened enemy and frantically wraps it in layer after layer of silk until it hangs as a white cocoon, completely immobilized. While the victim is cocooned the spider feeds, steadily healing itself, and the cocoon bulges and weakens as the prey struggles. Allies can cut the cocoon open early, and if the prey breaks free on its own the spider is left briefly exposed and slow.

5. **Venom Fang Pounce** (Attack): The spider crouches, fangs dripping with bright green venom particles, then launches itself in a long arcing pounce onto its target. On landing it bites deep, injecting a fast ticking venom and splashing a small pool of corrosive venom onto the ground that keeps damaging anything standing in it. The pounce can clear gaps and low walls, making high ground no safe answer.

## Cave Spider

1. **Luminous Contagion** (Attack): The cave spider's venom becomes a glowing blue-green fungal toxin, and its whole body takes on a faint bioluminescent shimmer. Bitten enemies glow visibly and shed spore particles, and when a glowing victim stands close to its allies the toxin jumps to them too, spreading through a clustered group. The glow also makes victims easier for all spiders to track in the dark.

2. **Dripstone Drop** (Terrain / Attack): In caves and under ceilings, the cave spider scuttles overhead and shakes loose pointed dripstone from above with targeted kicks, sending stalactites crashing down onto marked spots. Each falling spike deals heavy damage and sticks in the ground as a temporary obstacle. Little dust clouds and pebble particles warn where the next spike will fall, forcing enemies to keep moving.

3. **Silk Zipline Ambush** (Mobility): The cave spider anchors silk lines between walls and ceiling and zips along them upside down at high speed, a faint silk thread glinting behind it. Mid-ride it can drop straight down on a silk thread onto an enemy below, biting before swinging back up. Enemies rarely look up, so the zipline doubles as near invisibility in dark caves until the moment it strikes.

4. **Stonebore Ambush** (Mobility / Attack): The cave spider chews and claws its way directly into stone, disappearing with a spray of gravel and dust while a faint scratching trail shows its path through the rock. It bursts out beneath or beside its target with a shower of rubble and an empowered bite, catching enemies who thought a wall kept them safe. It cannot burrow through obsidian or bedrock, and long tunnels tire it out.

5. **Egg Sac Cluster** (Summon / Terrain): The cave spider lays a cluster of pale, pulsing egg sacs stuck to walls and ceilings around the fight, each faintly glowing. When an enemy wanders close, a sac hatches in a pop of silk and releases two small cave spiderlings that rush the intruder. Sacs can be destroyed safely from range, but meleeing them bursts them point blank in the attacker's face.

## Enderman

1. **Riftwalk Portals** (Mobility): The enderman tears open two tall, swirling purple rift portals with jagged endstone edges, one beside itself and one near its target or escape point, with ender particles streaming between them. It steps through and emerges from the other rift in a shower of sparks, and it can dart back and forth through the pair several times before they collapse, leaving afterimages at each exit. Enemies who blunder into a portal get spat out the other side disoriented and slowed.

2. **Terrain Fists** (Attack): The enderman rips blocks straight out of the ground, stone, dirt, or end stone, and compacts them around its hands into massive blocky fists crackling with purple energy. Each swing lands with boulder-crushing force, huge knockback, and a shockwave of debris, and it can hurl a fist as a projectile that explodes into block shrapnel on impact. The stolen blocks leave real holes in the terrain, and the fists crumble after several hits.

3. **Void Gaze** (Control / Attack): The enderman's eyes flare blinding violet and twin beams of ender light lock onto a target that meets its gaze, lifting the victim helplessly into the air amid swirling particles. After a moment of suspension it slams them into the ground with a burst of ender sparks and cracked blocks. Breaking line of sight, or allies staggering the enderman, ends the gaze early and drops the victim for reduced damage.

4. **Dimension Bleed** (Aura / Terrain): Reality frays around the enderman: patches of ground convert to end stone and obsidian shards, chorus flowers sprout and pop, and gravity weakens in a wide zone so everything drifts and falls slowly. Enemies in the bleed zone float awkwardly, their jumps carry them too far, and projectiles curve, while the enderman moves as if the zone were home. When the bleed ends the terrain snaps back, dropping anything still floating.

5. **Ender Colossus** (Transformation): The enderman unfolds to terrifying height, nearly doubling in size, limbs elongating and cracking open with seams of violet light, a halo of ender shards orbiting its head. The colossus gains massive health and reach, its screech is a physical shockwave that shoves everything back, and its slams leave craters of end stone. Smaller mobs panic at the sight, and when the form collapses the enderman is left briefly shrunken and vulnerable.

## Endermite

1. **Rift Burrow** (Mobility / Defense): The endermite chews a tiny swirling rift into the block beneath it and vanishes, then pops out of a matching micro portal several blocks away with a pop of purple sparks. It can chain burrows to cross a fight untouched, and each entry and exit hole lingers for a moment as a hazard that trips up pursuers with a small gravity tug. While mid-burrow it is completely untargetable.

2. **Mite Swarm Call** (Summon): The endermite lets out an ultrasonic screech, visible as faint rings, and cracks in nearby end stone and obsidian split open as four to six more endermites pour out in a skittering wave. The swarm coordinates, surrounding a single target and nibbling from all sides, and the original mite directs them, retargeting the pack with little chirps. Individually fragile, the swarm overwhelms by numbers and confusion.

3. **Parasitic Hitchhiker** (Control): The endermite leaps onto a much larger mob and latches onto its back or head, half burrowing in with only its rear and legs visible, trailing ender particles. While attached it steadily drains the host's health, and worse, it can steer: the host periodically lurches and turns toward the mite's enemies instead of its own target. The host can shake it off by taking a big hit or rolling against a wall, and the mite pops off with a burst of sparks.

4. **Chorus Infestation** (Terrain / Summon): The endermite infests nearby blocks, which sprout twisted chorus-like growths with purple bulbs over a few seconds. The bulbs pop when enemies pass, releasing a small endermite or a burst of teleporting spores that scatter victims randomly a few blocks in random directions. The infestation spreads slowly from block to block if left alone, turning a patch of battlefield into a minefield.

5. **Void Molt Decoy** (Defense): When struck, the endermite can shed its entire outer shell in a flash of purple light, leaving behind a perfect hollow decoy that keeps twitching and drawing attacks while the real mite slips away invisible for a few seconds. The decoy has a sliver of health and pops into particles when destroyed. The mite's first bite out of invisibility deals bonus damage, making the molt both an escape and a setup.

## Silverfish

1. **Stonebore Eruption** (Mobility / Attack): The silverfish dives into stone with a spray of gravel and races through the rock as a cracking, rumbling trail on the surface, then erupts beneath its target in a geyser of stone chunks and dust. The eruption launches the target upward and leaves a crater of cracked stone and cobblestone. It can re-burrow almost immediately, making it a hit and run nightmare in stone terrain.

2. **Infested Golem Form** (Transformation): The silverfish calls loose stone blocks to itself and they assemble around it into a bulky, rough golem body of cobblestone and stone bricks, with the silverfish's head peeking from the chest as the pilot. The golem form grants heavy armor, big slow punches, and immunity to knockback, but it is slow and cannot burrow. When the form's health runs out the stone shell explodes outward in damaging shrapnel and the silverfish scurries free.

3. **Hive Fracture** (Summon / Terrain): The silverfish slams the ground and a jagged crack races across the stone, splitting blocks open as more silverfish boil out of the fissure in a gray tide. The crack remains as a shallow trench that trips up movement, and silverfish keep trickling out of it for a while until the fissure seals. In stone-heavy areas the fracture travels farther and spawns more of the swarm.

4. **Pebble Barrage** (Attack): The silverfish rears up and spits a rapid stream of gravel and pebble projectiles with little dust puffs, each hit stinging and flinching the target. Sustained fire builds up a pile of gravel at the target's feet that can start to bury and slow them. It is the silverfish's answer to enemies who keep their distance, and it reloads by nibbling a nearby stone block.

5. **Living Fault Line** (Terrain / Control): The silverfish burrows a shallow fault under a stretch of battlefield, shown as a hairline crack with dust leaking out. When enemies cross the fault, the silverfish triggers it: the ground drops a half block along the line with a crunch, staggering and briefly rooting everyone on it and opening the crack wider. Repeated triggers deepen the trench until it becomes a real obstacle that splits the fight in two.

## Slime

1. **Grand Split** (Summon / Defense): Instead of dying down through sizes, this slime deliberately splits into two full medium slimes with a wet pop and a spray of gel droplets, each with its own health. The two halves fight independently, flank targets, and if one survives long enough it can regrow toward full size. If both halves end up adjacent and out of combat, they merge back together and pool their remaining health.

2. **Bouncequake** (Attack / Terrain): The slime inflates, launches itself to great height, and comes down in a massive slam that sends a ring-shaped shockwave of gel and dust rolling across the ground. Enemies in the ring take damage and get launched upward themselves, and the impact dents the ground into a shallow bowl of compressed dirt. The slime rebounds high off its own slam, ready to chain a second, smaller bounce onto a new target.

3. **Adhesive Trail** (Terrain / Mobility): The slime leaves a glistening trail of sticky gel wherever it hops, which lingers on the ground as shiny patches. Enemies crossing the gel get slowed and their jumps are smothered, while the slime itself accelerates along its own trail and can even ooze up walls and across ceilings slowly, dripping gel as it goes. The trail dries and flakes away after a while.

4. **Gelatinous Engulf** (Control): The slime leaps onto a smaller or weakened enemy and swallows it whole: the victim is visible inside the translucent green body, struggling as it slowly takes digestion damage and its attacks are muffled. The slime grows visibly bigger with its meal and moves slower, and if the slime takes enough damage the victim bursts free in a spray of gel. Big mobs cannot be engulfed, but the slime still coats them in slowing gel on the attempt.

5. **King Slime Coronation** (Transformation): Absorbing loose gel from its own trails and fallen slimes, the slime swells into a huge king slime with a crown-shaped crest of hardened gel and a deeper, more saturated color. The king has enormous health and mass, its hops shake the ground and knock enemies back on landing, and it periodically buds off small slime minions from its sides. When the coronation ends it sheds the extra mass in a ring of harmless gel blobs.

## Magma Cube

1. **Meteor Descent** (Attack / Terrain): The magma cube launches itself skyward in a trail of fire and smoke, hangs at the peak wreathed in flames, then plummets as a living meteor onto a marked, glowing target spot. The impact explodes in lava particles and embers, sets the area ablaze, and leaves a small crater lined with magma blocks that keep burning feet. The cube itself bounces out of its own crater unharmed, ready to hop again.

2. **Lava Wake** (Terrain / Aura): Every hop leaves behind a splash of lava that pools briefly before cooling into magma blocks and then cobblestone, so the cube paints the battlefield with fire as it moves. Enemies must weave between the pools, and the cube's own path becomes a wall of hazards behind it. In cold biomes the lava cools faster, while near existing lava the pools last much longer and can reconnect into streams.

3. **Obsidian Shell Slam** (Defense / Transformation): The magma cube rapidly cools its outer layer into a black obsidian shell, going dark and still like a statue with only cracks of orange light showing. While shelled it is nearly immune to damage and knockback, and when it breaks out it does so explosively, hurling obsidian shards in all directions and reigniting in a burst of flame. It uses the shell to survive focus fire and punish everyone standing close when it hatches.

4. **Ember Brood Split** (Summon): The magma cube splits not into plain smaller cubes but into a brood of ember cubes: smaller, faster magma cubes wreathed in open flame that leave tiny fire patches with each hop. Ember cubes burn out over time, shrinking as they spend themselves, but while they last they swarm and ignite everything they touch. If an ember cube survives to cool down it settles into a normal small magma cube instead of dying.

5. **Volcanic Heart** (Aura / Transformation): The cube's core ignites visibly through its cracks, pulsing brighter with each hop like a heartbeat, and waves of heat shimmer off its body. The heat aura periodically ignites nearby enemies, melts ice and snow in a radius into water and then steam, and makes the cube's own damage climb the longer it stays in a fight as the core builds toward eruption. If it is killed while fully heated, it erupts in a final lava burst instead of simply dying.

# Advanced Mobs Ability Design - Group B

Paper design only. Five brand new special abilities per mob, written to replace the old ability set. Each mob gets a mix of kinds: attack, defense, summoning, terrain, transformation, aura, and mobility, so no mob is five damage buttons in a row.

## Blaze

1. **Inferno Crown** - The blaze grows a rotating ring of twelve flame rods around its head like a crown, each rod trailing orange and gold particles, and its eyes flare bright white. While active the crown periodically slams downward in a ring of fire that ignites the ground in a circle, forcing melee mobs to cross burning terrain to reach it, and the blaze gains fire-based knockback resistance so it cannot be pushed out of its own ring.
2. **Magma Bloom** - The blaze sheds its rods one by one into the ground, where they erupt as short-lived magma geysers that launch enemies skyward on pillars of lava particles. Launched mobs take heavy fall damage when they land, and each geyser leaves a temporary magma block that keeps burning anyone standing on it.
3. **Ashen Shroud** - The blaze explodes into a thick cloud of gray ash particles and goes semi-transparent, drifting slowly like smoke. In this form it takes reduced damage and cannot be targeted by ranged attacks, but it moves slower, and when it reforms it does so with a concussive burst of embers that blinds and pushes back everything nearby.
4. **Solar Lance** - The blaze gathers its rods into a single vertical beam of white-hot flame and fires a piercing laser of fire particles across the battlefield, melting a trench of temporary netherrack-colored scorch marks along its path. Anything standing in the beam takes rapid damage and catches fire, and the blaze is rooted in place while firing, trading mobility for raw area denial.
5. **Ember Legion** - The blaze splits three of its rods off into miniature rod-spirit minions that orbit it and shoot small fireballs on their own, each minion trailing sparks and lasting until destroyed. The blaze loses a little maximum health per active minion, balancing the extra firepower, and when a minion dies it pops in a small flare that lights its killer on fire.

## Ghast

1. **Sorrowful Barrage** - The ghast's face twists into a screaming expression with glowing red tear streaks, and it launches a fan of five fireballs at once in a spread, each trailing black smoke and wailing particles. The fireballs arc slightly and explode into lingering fire patches, turning the area below into a minefield that ground mobs must weave through.
2. **Ghastly Gale** - The ghast inhales with a visible swirl of wind particles rushing into its mouth, then exhales a massive horizontal shockwave that shoves every mob in front of it backward and upward, extinguishing fires and deflecting projectiles mid-flight. Enemies slammed into walls or dropped off ledges take extra damage, and the ghast recoils slightly upward from its own blast.
3. **Phantom Drift** - The ghast turns pale and translucent, its tentacles stretching longer, and it phases upward through blocks for a few seconds while shedding slow-falling white mist particles. It cannot attack while phased but repositions freely, and when it solidifies it releases a chilling pulse that slows every enemy beneath it.
4. **Cinder Rain** - The ghast rises high, glows from within like a lantern, and sheds a rain of burning cinders over a wide area below, each cinder a bright orange particle that ignites small fires where it lands. The bombardment lasts several seconds, forcing ground armies to scatter, but the ghast hangs nearly still while channeling and is an easy target for ranged mobs.
5. **Soul Anchor** - The ghast vomits up a chained spectral anchor of soul-fire particles that tethers to the strongest enemy nearby, visibly linking them with a glowing blue chain. The tethered mob is dragged slowly toward the ghast and cannot fly or teleport away, and when the tether snaps it yanks the victim in with a burst of soul flames.

## Wither

1. **Cataclysm Bloom** - The wither swells to one and a half times its size, its three heads sprouting cracked black roses of particle petals, and detonates a massive withering explosion that replaces nearby ground with temporary wither-rose thorn blocks. Everything caught in the blast gains wither effect, and the thorn field keeps poisoning mobs that stand in it while the wither feeds on the damage to slowly regenerate.
2. **Skullstorm** - All three heads open at once and vomit a spiraling storm of dozens of small wither skulls that orbit the boss like a tornado before homing onto scattered targets, each skull trailing dark smoke. The storm shreds groups of weaker mobs, and the wither gains speed during the channel so it can chase fleeing targets while the skulls fly.
3. **Graveyard Shift** - The wither plunges into the ground and tears up a ring of grave mounds, spawning wither skeletons and skeletons from the dirt with bursts of black particles and bone-white flashes. The raised skeletons are briefly empowered with glowing eyes and extra health, and the wither resurfaces at a grave of its choice, using the move to escape being surrounded.
4. **Deathless Husk** - At low health the wither's body cracks open with glowing purple fissures and it sheds its outer ribs as a separate floating shield of bones that intercepts projectiles. While the husk shield lasts the wither cannot fly but gains heavy armor and its skulls charge faster, and when the shield breaks it shatters into bone shards that damage everything around it.
5. **Event Horizon** - The wither's center head becomes a swirling black vortex with purple accretion particles, pulling every mob and loose projectile in the area steadily toward it for several seconds. Pulled mobs are compressed into a tight cluster, helpless against the follow-up skull volley, and the wither itself is immune to knockback while the vortex is open.

## Ender Dragon

1. **Crystal Requiem** - The dragon roars and every nearby end crystal, real or summoned, fires a beam into her at once, overcharging her scales with crackling pink and purple energy particles. While overcharged her breath attacks leave pools of dragon fire that linger twice as long, her wing beats knock mobs away, and she gains a temporary shield of crystal shards orbiting her body.
2. **Void Maw Dive** - The dragon folds her wings, coats herself in a comet of dark purple void particles, and dive-bombs a target area, smashing a crater of cracked end stone and obsidian spikes on impact. The crash throws surviving mobs outward with huge knockback, and she takes off again in a spiral of rising void motes, briefly faster and harder to hit.
3. **Ender Mirage** - The dragon splits into three shimmering translucent copies of herself that circle the battlefield shedding teleport particles, only one of which is real. Enemies waste attacks on the fakes, which pop in bursts of chorus-colored sparks, and when the real dragon is struck the copies collapse and she teleports to a random copy's position with a flash.
4. **Gravity Perch** - The dragon lands on a summoned pillar of bedrock-like void stone in the middle of the fight and becomes a stationary artillery platform, her size looming over the field. From the perch her fireballs gain splash damage and her roar periodically inverts gravity for ground mobs nearby, lifting them helplessly before dropping them, but she cannot dodge while perched.
5. **Chorus Bloom Barrage** - The dragon breathes a stream of glowing chorus spores that sprout instant chorus plants across the battlefield, each plant teleporting any mob that touches it to a random nearby spot in a puff of purple particles. The chaos breaks up formations and separates tanks from their healers, and the dragon's own attacks during the bloom randomly swap her position with struck enemies.

## Guardian

1. **Prism Ray Split** - The guardian's single eye splits its beam into three thinner prismatic rays that each lock onto a different target, refracting through rainbow particles. Each ray deals less damage than the full beam but ramps up the longer it stays connected, and moving behind cover breaks only that ray, letting the guardian pressure a whole squad at once.
2. **Spike Minefield** - The guardian shakes loose a cloud of its own spikes that drift outward and hang in the water or air as floating mines, glinting orange and gray. Any enemy that brushes a mine triggers a spiky explosion of thorns damage and knockback, and the guardian retreats behind its field, daring melee mobs to push through.
3. **Tidal Bulwark** - The guardian inflates with water, growing rounder and larger with bubble particles streaming off it, and gains a heavy shell of prismarine shards that plates over its body. In this form it is slow but nearly immovable, reflects a portion of melee damage as thorns, and its beam charges while it soaks hits for its allies.
4. **Sonar Pulse** - The guardian emits expanding rings of blue sonar particles that sweep the area and mark every enemy with a glowing outline visible through walls and water. Marked mobs take extra damage from all guardian beams and cannot hide or turn invisible, and the pulse also scrambles flying mobs, briefly dropping their altitude.
5. **Riptide Ram** - The guardian coats itself in a spiraling jet of water particles and launches forward in a ramming dash that carries it across the battlefield, smashing through the first enemy hit with a burst of spray and bubbles. The ram deals heavy damage and massive knockback, can be steered slightly mid-dash, and leaves a temporary water current trail that pushes other mobs around.

## Elder Guardian

1. **Monument's Wrath** - The elder guardian rises and projects a colossal ghostly image of an ocean monument around itself, translucent prismarine walls and pillars forming a temporary arena. Enemies trapped inside are afflicted with mining fatigue so their attacks slow down, the walls block escape, and the elder guardian's beams fire faster inside its own monument.
2. **Abyssal Gaze** - Its eye swells and glows deep cyan, locking a single target with a thick beam surrounded by spiraling darkness particles that steadily drains the target's health and transfers it to the elder guardian. The beam holds through movement for a few seconds, and while draining, the elder guardian's spikes extend and bristle, warning melee mobs away.
3. **Sponge Eruption** - The elder guardian absorbs surrounding water with a sucking vortex of bubbles, swelling to double size, then erupts in a ring of sponge blocks and pressurized water jets that blast enemies outward. The sponges left behind slowly soak up and delete nearby water or lava pools, reshaping wet terrain, and dry the elder guardian out for a moment of vulnerability.
4. **Curse of the Deep** - A spectral elder guardian face, huge and translucent, materializes above the battlefield with a haunting screech and fixes its gaze on all enemies at once. Every enemy gains mining fatigue and slowness, their vision edges darken with ink particles, and any mob that dies while cursed bursts into a cloud of guardian spikes that wounds its own allies.
5. **Prismarine Cataclysm** - The elder guardian channels with all fins spread, summoning a rotating ring of giant prismarine shards that orbit it before slamming into the ground one by one as falling meteors of stone and crystal particles. Each impact cracks the ground and staggers nearby mobs, and after the last shard falls the elder guardian is armored in the leftover crystal dust, gaining temporary bonus health.

## Hoglin

1. **Bristleback Charge** - The hoglin's back bristles flare up into long glowing orange quills and it lowers its head, pawing the ground with dust particles before charging in a straight line. Anything in the charge path is gored and launched upward, the hoglin smashes through temporary blocks and weak cover, and it gains speed the longer the charge runs before impact.
2. **Fungal Frenzy** - Warped and crimson fungus particles burst from the hoglin's hide as it shakes violently, entering a frenzy where its eyes glow and its body steams. During the frenzy it attacks much faster and each bite has a chance to knock weapons or shields loose, but it also takes slightly more damage, trading defense for savage offense.
3. **Tusk Quake** - The hoglin rears up and slams its tusks into the ground, sending a visible crack of red netherrack particles racing forward that erupts into a row of jagged stone spikes. Mobs along the line are impaled and rooted briefly, and the spike row remains as rough terrain that slows pursuers while the hoglin retreats or regroups.
4. **Piglin's Bulwark** - The hoglin plants its feet, grows visibly bulkier with muscle and scarred hide texture, and becomes a living wall that taunts nearby enemies into targeting it. While planted it gains heavy damage reduction and reflects knockback onto attackers, protecting squishier allies behind it, but it cannot move until it shakes the stance off.
5. **Stampede Call** - The hoglin bellows with rings of sound particles and summons two smaller juvenile hoglins that charge in alongside it, all three trailing dust and ember motes. The juveniles are fragile but distract enemies and add chip damage, and if the adult survives the fight it eats the battlefield fungus afterward, slowly healing.

## Zoglin

1. **Rotting Surge** - The zoglin's fungal growths pulse green and it surges forward in a shambling sprint, shedding spore particles and leaving a trail of temporary mycelium that slows living mobs. During the surge its bites infect targets with a stacking rot that eats a percentage of their remaining health, making it a tank killer despite its modest size.
2. **Corpse Bloom** - The zoglin stops and convulses as a huge fungal flower bursts from its back, blooming into a canopy of glowing teal spores that rain down over the area. Enemies under the canopy are poisoned and confused, occasionally turning to attack their own allies, while undead mobs nearby are healed by the spores instead.
3. **Bone Plating** - Exposed ribs and skull plates push through the zoglin's rotting hide with cracking sounds and pale bone particles, forming natural armor across its flanks. The plating grants strong resistance to arrows and frontal attacks, and when a plate breaks it sprays bone shards and spores that damage the attacker.
4. **Grave Tremor** - The zoglin stomps repeatedly, each stomp sending green shockwave rings through the ground that knock loose buried bones, which rise as temporary bone spikes. The tremors stagger and slow ground mobs in a wide radius, and the zoglin gains a burst of speed each time an enemy is staggered, letting it run down weakened prey.
5. **Undying Hunger** - When the zoglin would die, it instead collapses into a heap of fungus and bone, then reanimates a few seconds later at half health with a burst of spores and a more skeletal, faster form. The revived zoglin is smaller and quicker with glowing eye sockets, but it can only do this once, and fire damage during its collapse prevents the revival.

## Piglin

1. **Golden Hoard** - The piglin throws down a pile of glowing gold ingots and nuggets that glitter with sparkle particles, and every piglin and piglin brute nearby fixates on the pile, gaining speed and damage as they swarm to defend it. Enemies who step on the hoard are slowed by slipping on loose gold, and the pile can be destroyed to end the buff early.
2. **Crossbow Volley** - The piglin loads a oversized crossbow that crackles with enchantment glints and fires a rapid volley of five bolts in a fanning arc, each bolt trailing gold dust. The volley suppresses an area and pins hit mobs with brief slowness, and the piglin reloads behind cover with a visible golden shimmer shield while vulnerable.
3. **Barter Bomb** - The piglin hurls a suspicious golden bundle that bounces with clinking particles before bursting into a random barter result: a splash of fire resistance potion, a burst of ender pearls that scatter-teleport nearby mobs, or a small explosion of gravel and flint. The randomness makes it unreliable but wildly disruptive, and piglins themselves dive away from the blast.
4. **Gilded Armor** - The piglin coats itself in molten gold that hardens into ornate gilded armor plates with glowing seams, a full model change that gleams in the light. The armor grants strong protection and makes the piglin immune to zombification effects and fire, but it is slower, and the armor visibly cracks and flakes off as it absorbs damage.
5. **Nether Portal Ambush** - The piglin tears open a small unstable nether portal with a screech of purple particles, vanishing into it and reappearing behind the strongest enemy a moment later with weapons raised. The ambush strike deals bonus damage from behind, the portal lingers as a hazard that randomly teleports mobs who touch it, and the piglin emerges with brief speed.

## Piglin Brute

1. **Warlord's Axe** - The brute's axe ignites with soul fire along its edge, leaving blue flame trails with every swing, and its overhead chops send crescent waves of fire rolling along the ground. Each wave ignites terrain briefly, and the brute gains attack speed with every enemy it kills while the axe burns, snowballing in long fights.
2. **Bastion Stomp** - The brute slams its foot down and raises a ring of blackstone rubble and basalt pillars around itself with a deep rumble and dust explosion, forming a personal arena. Inside the ring the brute gains armor and its axe swings cause small shockwaves, while enemies must break through or climb the rubble to reach it.
3. **Intimidating Bellow** - The brute beats its chest and bellows, visible sound rings blasting outward and its body briefly swelling with rage-red particle steam. Weaker mobs caught in the bellow are frightened and flee for a few seconds, stronger mobs are staggered, and the brute's next attack deals greatly increased damage to anything that stood its ground.
4. **Goldfury Execution** - The brute marks the lowest-health enemy with a floating golden skull icon and a trail of gold particles linking them, then charges with unstoppable momentum, immune to knockback and slows. If the charge connects it deals execution damage that scales with the target's missing health, and a kill refunds the brute a chunk of health as it roars over the body.
5. **Unyielding Hide** - The brute's scars glow faintly and its hide hardens into a bark-like texture with cracking earth particles whenever it drops below half health. In this state it takes heavily reduced damage from the front, gains regeneration while standing still, and its sheer mass means charges and knockback effects barely move it, though it attacks more slowly.

## Shulker

1. **Shell Fortress** - The shulker's shell expands into a full rotating fortress of interlocking purpur plates that unfold around it like a blooming stone flower, with extra floating shell shards orbiting as shields. Projectiles bounce off the plates, the shulker can still peek out to fire between rotations, and the fortress slowly grinds forward, pushing mobs in its path.
2. **Levitation Barrage** - The shulker opens wide and launches a swarm of guided bullets in a spiral pattern, far more than normal, each bullet trailing bright white and lavender particles. Hit mobs float helplessly upward in a cluster, and the shulker then slams its shell shut, releasing a shockwave that knocks the floating victims outward to take fall damage.
3. **Endstone Burrow** - The shulker retracts fully and burrows into the ground with a spray of end stone particles, traveling underground as a moving mound of cracking terrain before erupting beneath an enemy. The eruption launches the victim upward and leaves the shulker in a fresh defensive spot, and while burrowed it cannot be targeted, only tracked by the dust trail.
4. **Gravity Well Shell** - The shulker inverts its shell, which becomes a swirling vortex of purple particles that pulls nearby mobs and projectiles toward it and holds them orbiting helplessly for a few seconds. When the well collapses, everything caught is slammed together at the center in a burst of damage, and the shulker teleports a short distance away to safety.
5. **Chorus Shell Split** - The shulker splits its shell into two halves that each grow a smaller shulker head, becoming a two-headed turret that can target two enemies at once with independent bullet streams. The split form has less armor and cannot move its shell, but when one head is destroyed the other enrages, firing faster and teleporting erratically.

## Vex

1. **Phantom Legion** - The vex screeches and multiplies into a swirling flock of five spectral copies that orbit the original in a ring of blue-white particles, all diving at targets in coordinated swoops. The copies are fragile illusions that pop when hit, but they draw attacks away from the real vex, which glows slightly brighter and strikes harder while its legion lasts.
2. **Soulbrand Charge** - The vex's sword ignites with soul flame and it charges in a corkscrew of blue fire particles, phasing through walls and enemies alike. Every mob it passes through is branded with a glowing rune that burns for damage over time, and the vex exits the charge behind enemy lines with brief invisibility.
3. **Haunting Wail** - The vex swells its head, eyes flaring red, and unleashes a wail visualized as expanding rings of distorted air and gray particles that disorient every enemy in range. Afflicted mobs have their controls scrambled, drifting and turning randomly, and their attacks swing wide, while the vex flits between them picking off the most confused.
4. **Ironbound Form** - The vex's body hardens into a metallic form with visible iron plating and rivets replacing its ghostly texture, wings clanking as they beat with puffs of rust particles. In this form it loses the ability to phase through blocks but gains heavy armor, extra health, and a ramming tackle, becoming a tiny armored missile instead of a fragile ghost.
5. **Grave Chill Aura** - A freezing mist of pale blue particles pours off the vex, coating the ground around it in temporary frost that crackles as it spreads. Enemies standing in the frost are steadily slowed and eventually rooted in ice for a moment, and the vex's sword strikes against chilled targets shatter the frost for bonus damage.

## Witch

1. **Cauldron Cataclysm** - The witch summons a huge bubbling cauldron that crashes down from above with a splash of green potion particles, then boils over into an expanding pool of random potion effects. The pool shifts colors every few seconds, cycling between poison, slowness, weakness, and harming, and the witch herself stands immune at its center stirring it, healing slowly while enemies wade through the brew.
2. **Potion Golem** - The witch throws a cluster of bottles that shatter and coalesce into a lurching golem made of glass shards and swirling potion liquid, its body tinted by whatever potion it absorbed last. The golem splashes its current effect onto enemies with every swing, and when destroyed it detonates into a big splash of that same effect, while the witch keeps her distance and lobs support potions at it.
3. **Hexweaver** - The witch's hat elongates and sprouts glowing runes, and she weaves visible threads of purple magic between up to three enemies, linking them with crackling particle strings. Damage dealt to one linked mob is shared among all of them, and if a linked mob dies the thread snaps back and curses the survivors with weakness, letting the witch punish grouped enemies.
4. **Broomstick Strafe** - The witch hops onto a flying broom with a trail of sparkles and straw particles, gaining true flight and swooping mobility for the duration. From the broom she dive-bombs enemies with splash potions at high speed, nearly impossible for melee mobs to catch, but a solid ranged hit knocks her off the broom and grounds her with a stumble.
5. **Transfiguration Curse** - The witch hurls a swirling green and purple curse bolt that, on hit, transforms the target into a harmless frog or bat for several seconds, complete with a puff of smoke and a shower of sparkles. The transformed mob keeps its health pool but cannot attack and hops or flutters helplessly, and bosses and brutes resist the curse, instead shrinking to half size with reduced damage for the duration.

## Evoker

1. **Totem Ascendant** - The evoker raises a giant golden totem that erupts from the ground beneath it, lifting the evoker atop a pillar while the totem radiates rings of warm light particles. While ascendant, the evoker's fang attacks summon twice as many fangs, it gains a regenerating shield, and if it would die the totem shatters to revive it at partial health instead, once.
2. **Fang Labyrinth** - The evoker slams the ground and raises a maze of snapping evoker fangs arranged in walls and corridors across the battlefield, each fang bursting from the floor with dust and bone-white particles. Enemies must navigate the maze while fangs keep snapping at them, and the evoker stands at the maze's heart, repositioning the walls with fresh casts.
3. **Wolloomancy Surge** - The evoker channels a storm of colored wool particles and fires a barrage of dye bolts that repaint enemy mobs in flashing colors, each color applying a different curse: red for burning, blue for freezing slowness, yellow for blindness, green for poison. The chaos of mixed curses breaks enemy coordination, and sheep caught in the surge explode into confetti wool bursts that damage their neighbors.
4. **Vex Halo** - A rotating halo of six bound vexes forms above the evoker's head like a crown of screaming spirits, trailing blue flames. The halo vexes automatically dive-bomb any mob that comes close or attacks the evoker, then reform, and while the halo lasts the evoker moves faster and its own casts cannot be interrupted by knockback.
5. **Mansion's Judgment** - The evoker summons a phantom woodland mansion facade behind itself, towering dark oak walls and glowing windows materializing with creaking sounds and dust, then the whole facade collapses forward onto the battlefield. The collapse deals massive damage in a wide rectangle and leaves burning, broken timber terrain that blocks movement, while the evoker teleports to a rooftop remnant to survey the wreckage.

## Vindicator

1. **Lumberjack's Wrath** - The vindicator's axe grows into a huge double-bladed woodland axe wreathed in falling leaf and splinter particles, and his chops send splinter shockwaves through the ground in a line. Each swing that kills grows his attack speed and size slightly for the rest of the fight, and trees or wooden blocks nearby shatter into damaging debris when he rages.
2. **Doorbreaker Charge** - The vindicator lowers his shoulder behind a raised plank shield torn from a mansion door, wood grain particles streaming, and charges with unstoppable force that smashes through blocks, shields, and the first enemy in line. The charge pins the struck enemy against whatever stops the charge, dealing crushing damage, and leaves the vindicator briefly winded afterward.
3. **Johnny Protocol** - The vindicator's eyes glow red and he enters the infamous Johnny state, name tag flickering, axe raised, with red particle streaks trailing his movements. In this state he attacks everything indiscriminately, friend and foe, with greatly increased speed and damage, leaping between targets, and the state only ends after several seconds or when he kills something, making him as dangerous to his own side as to the enemy.
4. **Axe Cyclone** - The vindicator spins his axe in a widening cyclone of wind and wood-chip particles, becoming a moving whirlwind that deflects projectiles and shreds adjacent enemies with repeated small hits. The cyclone pulls loose items and small mobs toward its edge, and when it ends the vindicator is dizzy for a moment, swaying and vulnerable to a counterattack.
5. **Warband Herald** - The vindicator plants a tattered illager banner in the ground with a horn blast of sound particles, rallying every illager and ravager nearby with glowing banner sigils above their heads. Rallied allies gain speed, damage, and fearlessness, the banner itself acts as a destructible rally point they defend, and the vindicator gains armor for each ally standing under the banner's effect.

## Pillager

1. **Firework Crossbow** - The pillager loads a crackling firework rocket into an oversized crossbow and fires it in a high arc, the rocket trailing colored sparks before bursting over the enemy group in a star-shaped explosion of light particles. The burst damages and briefly blinds everyone under it, and the pillager can follow up with normal bolts while enemies are dazzled.
2. **Raid Banner Bulwark** - The pillager plants its ominous banner and it projects a dome of gray and white illager-patterned energy, shimmering with glyph particles, that shields allies inside from projectiles. The pillager reloads safely under the dome and gains damage for each ally sheltered, but melee mobs can simply walk through the dome, and destroying the banner collapses it.
3. **Beast Bait** - The pillager throws a hunk of glistening bait that splats onto the strongest enemy with a puff of red particles, marking them with a dripping target icon. Any nearby ravagers and neutral beasts aggro onto the marked mob and gain speed charging it, and the pillager uses the distraction to reposition or escape, making it a trickster tool rather than a weapon.
4. **Multishot Mantlet** - The pillager unfolds a portable wooden mantlet shield, a model change that plants a slatted wooden wall in front of it with arrow slits. From behind the mantlet it fires three-bolt spreads in quick succession through the slits, nearly immune to frontal projectiles, but the mantlet roots it in place and its flanks are completely exposed.
5. **Outpost Mortar** - The pillager kneels and assembles a crude mortar from crossbow parts that lobs explosive bolts in high arcs over walls and cover, each shell whistling down with a smoke trail before a fiery burst. The mortar outranges almost everything and reshapes cover with its blasts, but it takes time to set up and pack away, and the pillager is defenseless while operating it.

## Illusioner

1. **Hall of Mirrors** - The illusioner raises a ring of tall mirror shards around the battlefield, crystalline panels shimmering with its reflection, and every reflection steps out as a solid clone armed with a bow. The clones fire real arrows but shatter into glass particles when killed, the real illusioner swaps places with a clone whenever it is struck, and destroying the mirrors ends the trick early.
2. **Prismatic Volley** - The illusioner's bow splits light into a fan of rainbow arrows that each carry a different random effect: blindness, slowness, poison, weakness, or a burst of damage, all trailing colored sparkles. The volley is wildly unpredictable but can cripple a whole front line at once, and the illusioner's eyes glow with shifting colors while it channels the shot.
3. **False Terrain** - The illusioner waves its arms and paints illusory terrain over the battlefield: fake bridges over pits, phantom walls, and mirage pools that shimmer and ripple as mobs approach. Enemies path into the fakes and stumble, fall, or bunch up at phantom obstacles, and the illusioner's allies see the illusions as faint outlines they can walk through freely.
4. **Doppelganger Swap** - The illusioner locks eyes with an enemy, both of them flashing with inverted-color particles, and swaps appearances and positions with the victim for several seconds. The victim's own allies see an enemy and attack it while the illusioner, wearing the victim's shape, slips behind lines, and the swap ends in a blinding flash that reveals the truth.
5. **Blindspot Veil** - The illusioner drapes itself in a veil of bending light, becoming a shimmering distortion that is nearly invisible except for faint particle wisps, and projects a dome where light behaves wrongly. Inside the dome, enemy ranged attacks curve and miss, name tags vanish, and the illusioner's arrows emerge from the distortion at wrong angles, making its true position impossible to judge.

## Ravager

1. **Siege Ram Form** - The ravager's head plating thickens into a massive battering ram crest of dark iron and oak, its body lowering and widening with grinding stone particles, and its charge becomes a siege rush that demolishes blocks and formations alike. The charge deals escalating damage the farther it travels, staggers bosses, and leaves a trench of churned earth, but turning is nearly impossible mid-charge.
2. **Roar of the Raid** - The ravager rears up and unleashes its roar as a physical shockwave of dust and sound rings that flattens grass, cracks the ground in a circle, and throws back every mob around it. Allies caught in the roar are emboldened with speed and damage, enemies are deafened and slowed, and the ravager's next headbutt after roaring deals double damage.
3. **Armored Howdah** - A pillager-style war platform materializes on the ravager's back in a clatter of wood and iron particles, complete with banners and a small crossbow turret that fires on its own at nearby enemies. The howdah grants the ravager extra armor and a ranged threat it normally lacks, and destroying the platform leaves the ravager enraged with a burst of speed.
4. **Earthshaker Stomp** - The ravager rises on its hind legs and crashes down repeatedly, each stomp sending a ring of erupting dirt spikes and stone shards outward that damages and launches ground mobs. After several stomps the ground in the area becomes cracked rough terrain that slows everyone except the ravager, whose wide feet stomp it flat as it moves.
5. **Unbreakable Beast** - The ravager's hide scars over with thick calloused plates and glowing raid-red war paint stripes, a full texture change, as it drops into a berserk last stand at low health. In this state it gains heavy damage reduction, its attacks inflict knockback strong enough to throw mobs into each other for collision damage, and it cannot be slowed, rooted, or frightened until the fight ends or it heals above the threshold.

# Advanced Mobs: New Ability Designs (Group C)

Paper design list only. Five abilities per mob, each a different kind: attack, defense, summoning, terrain, transformation, aura, or mobility. Carter picks the top three per mob before any implementation.

## Warden

1. **Heart of the Deep Dark**: The warden's chest cavity splits open and a glowing sculk heart becomes visible, pulsing with cyan light and shedding a storm of soul particles and sculk spores. It releases a visible seismic ring along the ground that tears up a trail of sculk veins as it travels, and any mob caught in the ring is blasted with a sonic boom that deals heavy damage and massive knockback. For a few seconds after, the warden moves 40 percent faster and its melee hits carry the same booming shockwave in a small arc.

2. **Blind God's Domain**: The warden slams both fists down and a dome of darkness spreads outward, dimming light in a wide radius and growing sculk sensors and shriekers across the floor, walls, and ceiling. Every sound or movement by an enemy inside the dome feeds the warden power: it gains stacking speed and attack strength, and glowing tendrils visibly whip out of the ground to lash the loudest target. The warden's tendrils and back spikes glow brighter the more stacks it holds.

3. **Catacomb Burrow**: The warden sinks into the ground in a burst of dirt and sculk particles, leaving a cracked scar in the floor. It tunnels beneath the battlefield, shown by a moving mound of breaking blocks and trailing spores, then erupts under its target in a geyser of sculk blocks and debris that launches the victim skyward. While burrowed it cannot be hit, and the eruption zone stays covered in slowing sculk sludge.

4. **Ancient Awakening**: At low health the warden transforms: its body swells 30 percent larger, ribs flare open, horns elongate, and its whole frame burns with cyan soul fire and rising wisp particles. It gains a big health refill, double knockback resistance, and a constant aura of dread that makes nearby weaker mobs flee. Every roar in this state emits a real sonic cone that shatters loose blocks and sends enemies tumbling.

5. **Summon the Sculk Choir**: The warden rears back and lets out a layered, echoing shriek, and shrieker style growths burst from the ground around the arena, each one shrieking in sequence. Every shriek fires a focused sonic beam at a different enemy, and if the choir completes all its notes, a wave of awakened sculk tendril entities rises and grapples everything nearby, holding mobs in place for the warden to finish.

## Breeze

1. **Cyclone Form**: The breeze unravels into a living tornado, its rod segments spinning into a wide funnel of wind charges, swirling leaves, and spiral particle trails. In this form it hovers higher, moves far faster, and constantly drags nearby mobs toward its center while flinging debris blocks around the funnel. Touching the funnel launches mobs upward and outward, and the breeze can steer the storm across the arena like a moving hazard.

2. **Wind Charge Barrage Bloom**: The breeze charges up, glowing rods spinning faster and faster, then releases a blooming flower of wind charges in every direction, each trailing white gust particles. Every charge that lands creates a burst of knockback and a small crater of disturbed blocks, and charges fired at the ground bounce the breeze itself into the air to reposition. Enemies hit midair take bonus damage and get juggled by follow up bursts.

3. **Summon the Gust Sprites**: With a sharp spin, the breeze sheds three small wind sprite entities, miniature whirlwinds with glowing cores, that dart around the arena on their own. The sprites harass enemies with gusts that shove them off balance, deflect incoming projectiles in puffs of air, and periodically dive bomb a target in a spiral of particles. When a sprite dies it pops into a wind charge explosion that knocks everything nearby away.

4. **Trial Chamber Gale**: The breeze reshapes the local air into a howling arena wide gale, shown by streaming particle lines, spinning weather vanes of debris, and tuff blocks rattling in place. Inside the gale, all projectiles curve toward the breeze's chosen target, enemy mobs are slowly pushed toward one wall, and the breeze gains a permanent double jump style air dash with a visible ring burst. Loose blocks and items swirl around the edges as visual chaos.

5. **Pressure Slam Vortex**: The breeze rockets straight up on a column of compressed air, then plummets down and detonates a massive outward vortex ring at ground level. The ring throws every mob on the ground to the arena edges, flips gravity briefly for victims caught in the inner ring so they float helplessly, and leaves a spinning low pressure zone that keeps sucking in projectiles and small mobs for several seconds.

## Phantom

1. **Nightmare Descent**: The phantom ignites with purple soul flame, its membrane wings stretching wider with glowing torn edges, and dives in a screaming spiral of nightmare particles. On impact it erupts into a burst of insomnia wisps that latch onto nearby mobs, draining their health over time and making them visibly stagger and slow. The phantom gains a speed surge and its next three swoops leave burning trails of violet fire on the ground.

2. **Sleepless Legion**: The phantom splits the night sky with a shriek and summons a flock of smaller spectral phantom echoes, translucent copies with glowing eyes. The echoes swoop in staggered waves, each bite stacking a sleeplessness curse that reduces the victim's damage output and makes their model flicker and droop. The real phantom turns semi transparent while the flock is active and cannot be targeted until an echo falls.

3. **Membrane of Dusk**: The phantom wraps its wings around itself and becomes a hovering cocoon of leathery membrane ringed by drifting purple motes. While cocooned it rapidly regenerates health, reflects projectiles back in puffs of smoke, and builds a charge shown by growing inner glow. When the cocoon bursts, it releases a shockwave of drowsiness that puts ground mobs into a brief sleep state, standing still with Z particles rising, while the phantom escapes upward at high speed.

4. **Gravity of Insomnia**: The phantom circles high above and its screech bends the air, creating a zone where gravity weakens, shown by floating debris, rising dust, and enemies drifting upward with stretched particle trails. Mobs in the zone float helplessly and take fall style damage when the effect snaps off and they crash down. The phantom itself flies faster and hits harder inside its own warped sky.

5. **Eclipse Form**: The phantom transforms under a false eclipse: it grows larger, its body turns pitch black with a glowing crescent pattern across its wings, and moonbeam particles rain around it. In this form its swoops create rifts of darkness on the ground that damage and blind anything standing in them, and it can teleport between its own rifts in flashes of violet light. The form fades after a set time, leaving the phantom briefly exhausted and slow.

## Giant

1. **Worldbreaker Stomp**: The giant raises one enormous leg, shadow swelling across the ground with cracking particle previews, then stomps down hard enough to reshape the terrain. The impact carves a crater, launches a ring of dirt and stone blocks into the air, and sends a rolling shockwave that throws every mob off its feet and buries small mobs in rubble. Standing in the crater afterward counts as rough ground that slows everyone except the giant.

2. **Colossus Awakening**: The giant's dormant body surges with life: moss and vines burst across its skin, its eyes ignite with green light, and it grows another 50 percent taller with a deeper, heavier footfall that shakes the screen. It gains huge health and knockback immunity, its reach extends dramatically, and each swing of its arms sweeps an arc that can flatten several mobs at once. Grass and flowers spring up in its footsteps as it moves.

3. **Throw the Mountain**: The giant rips a chunk of terrain straight out of the ground, a jagged block of stone and dirt held overhead with crumbling particle trails, and hurls it across the arena. The boulder rolls on landing, crushing mobs in its path, then shatters into a pile of blocks that forms real cover and high ground. The giant can also throw allied smaller mobs this way, launching them into the fight with a damage boosted landing.

4. **Titan's Roarquake**: The giant bellows and the sound becomes physical: concentric distortion rings roll outward, loose blocks pop out of the ground, and stalactite style debris rains from above. Enemies caught in the rings are deafened and disoriented, spinning in place with dizzy particles, and take damage from falling rubble. The giant's roar also cracks a fissure line across the ground that stays as a trench obstacle for the rest of the fight.

5. **Living Siege Armor**: Vines, stone, and earth crawl over the giant's body and harden into living armor plates with glowing seams, turning it into a walking fortress. The armor absorbs damage and visibly chips and regrows, thorn vines lash out at anything that strikes it, and when a plate finally breaks it explodes into a spray of seeds that grow instant entangling vine patches under nearby enemies. The giant moves slower in this state but is nearly unstoppable.

## Sulfur Cube

1. **Sulfur Geyser Slam**: The cube compresses flat with hissing yellow gas leaking from its seams, then launches itself high and slams down, erupting a geyser of sulfur particles, glowing gas, and molten droplets. The landing zone becomes a bubbling pool of liquid sulfur that burns and slows mobs standing in it, and the cube rebounds off the blast in a random direction at high speed. Each bounce for a few seconds leaves a smaller burning puddle behind it.

2. **Toxic Bloom Split**: The sulfur cube swells, cracks glowing across its surface, then splits into three smaller sulfur cubes in a pop of chartreuse spores. The minis bounce erratically, each trailing clouds of choking gas that damage and confuse enemies, and they kamikaze onto targets in little gas explosions. When all three minis die, or after a timer, they merge back into the full cube with a burst that clears nearby enemies away.

3. **Brimstone Aura**: The cube begins venting a constant shimmering haze of sulfur gas, tinting the air yellow green around it with floating ember motes. Enemies breathing the haze take stacking poison style damage and their vision themed particles show them coughing and slowing, while the cube itself gets faster and bouncier the longer the aura runs. The gas is flammable in spirit: any fire or explosion nearby ignites the whole cloud into a flash burn that damages everything inside it.

4. **Elastic Catapult**: The cube anchors itself, stretches like a drawn slingshot with visible strain lines and creaking particles, and launches a huge glob of sticky sulfur across the arena. The glob splats into a trampoline patch of elastic sulfur: mobs that step on it get bounced violently into the air or into walls, taking impact damage. The cube can bounce off its own patch to reach absurd heights and drop into its slam attacks.

5. **Magma Heart Form**: The cube's core ignites and it transforms into a glowing, half molten version of itself, dripping lava particles and leaving scorched, cracked footprints. Its bounces now ignite the ground briefly, its body deals contact burn damage, and it gains bonus health and a bigger, heavier body that knocks mobs aside on landing. Steam and smoke pour off it constantly, and when the form ends it vents a ring of superheated gas that pushes everything back.

## Bee

1. **Royal Sting Barrage**: The bee glows gold, its wings blurring into a halo of light trails, and performs a corkscrew dive that lands a rapid series of stings on multiple targets. Each sting leaves a glowing puncture mark and a dripping venom bead particle, and unlike a normal bee, this one keeps its stinger through sheer royal resilience. After the barrage it gains a burst of speed and a shimmering shield of pollen that blocks the next hit.

2. **Summon the Swarm Guard**: The bee releases a pheromone flare of sparkling amber particles, and a squad of loyal swarm bees spirals in from above to form a rotating defensive ring. The ring bees intercept attackers, sting in coordinated volleys, and body block projectiles, each falling heroically in a puff of petals. While the ring holds, the main bee regenerates and its own attacks poison enemies that the swarm has marked with scent particles.

3. **Pollenstorm Bloom**: The bee shakes out an explosion of golden pollen that blooms into a drifting flower field cloud, with petals, sparkles, and sprouting flowers appearing on the ground beneath it. Enemies in the cloud sneeze and stumble with reduced accuracy and speed, shown by dizzy spirals and flower particles stuck to their bodies. Allies and the bee itself gain regeneration and speed inside the bloom, and the flowers keep emitting pollen pulses for a while.

4. **Honey Bomb Barrage**: The bee produces globs of sticky honey, glowing amber orbs that it drops like bombs while strafing across the arena. Each glob splats into a real honey patch on the ground that traps mobs in place, slows them to a crawl, and coats them in dripping honey particles that reduce their damage. The bee can detonate its own patches with a sting dive, bursting them into a spray that glues wings and legs for a few seconds.

5. **Queen Ascendant**: The bee transforms into a queen form: larger body, crown like antennae, elongated glowing abdomen, and a regal trail of sparkles and rose petals. In this form it commands periodic mini swarms automatically, its sting applies a stronger royal venom, and it periodically lays down a honeycomb barrier block structure that blocks enemy movement. The transformation costs its agility, making it slower but far tougher.

## Wolf

1. **Pack Howl Ascension**: The wolf throws back its head and howls, sending visible sound rings and moonlit particles into the sky, and spectral pack wolves made of mist and starlight materialize around it. The ghost wolves fight alongside it, phasing through blocks, and every kill they score feeds the real wolf health and attack power. The wolf's eyes glow silver for the duration and its fur stands with static like energy.

2. **Blood Moon Frenzy**: A red tint washes over the wolf as it transforms: muscles swell, fur darkens, claws lengthen with glowing tips, and it leaves bloody paw print particles with every stride. In frenzy it gains major speed and attack boosts, each bite heals it for part of the damage dealt, and wounded enemies bleed particle trails that the wolf tracks automatically, always pathing to the weakest target. The frenzy ends with an exhaustion window where it moves slower.

3. **Ambush Pounce Web**: The wolf crouches, body low and tail still, then vanishes into a burst of leaves and dust, reappearing in a devastating pounce from an unexpected angle. The pounce pins the target briefly, shown by the wolf latched onto it with shaking struggle particles, and deals bonus damage from behind. It can chain up to three pounces between different targets, leaving decoy dust wolves behind that distract enemies.

4. **Territorial Frost Mark**: The wolf raises its leg and marks the ground, and a ring of frost and claw scarred earth spreads out as its claimed territory, edged with glowing blue boundary stones. Inside its territory the wolf gains speed, regeneration, and a damage bonus, while intruders get chilled and slowed with frost creeping up their legs. The territory persists and the wolf fights fiercely to stay inside it, gaining rage stacks when an enemy crosses the border.

5. **Alpha's Challenge Roar**: The wolf plants its feet and unleashes a dominating roar with a shockwave of dust and debris that forces every nearby mob into a one on one mindset: they all turn and focus the wolf, shown by red target lines converging on it. For the duration the wolf gains armor and reflects a portion of melee damage as bite back bursts, and each attacker it defeats during the challenge triggers a fear pulse that scatters the rest. It is a taunt, a shield, and a trap in one.

## Polar Bear

1. **Glacier Slam**: The polar bear rears up, frost crystals growing across its shoulders, then slams down and sends a wave of jagged ice spikes erupting across the ground in a line. Mobs are impaled and frozen in place inside translucent ice blocks with frost particle breath, taking cold damage over time. The ice terrain lingers, making the ground slippery for everyone except the bear, which gains speed on its own ice.

2. **Whiteout Aura**: The bear shakes out a blizzard from its fur and a localized whiteout storm swirls around it, dense with snow particles, howling wind lines, and frost mist. Enemies inside the storm are blinded by swirling snow, slowed, and slowly freeze solid if they stay too long, while the bear's white camouflage makes it flicker in and out of visibility. The bear sees perfectly and gains bonus damage against frozen targets, shattering them with extra particles.

3. **Cub Guardian Summon**: The bear bellows and two polar bear cubs, smaller and faster, charge in from the edges of the arena in sprays of snow. The cubs harry enemies with quick bites that slow and distract, and any mob that attacks a cub enrages the mother, shown by red steam breath and a visible size and strength surge. If a cub falls, the bear enters a grief fury with a big temporary damage boost and a shockwave roar.

4. **Avalanche Charge**: The bear drops to all fours and charges, snow building up in front of it into a rolling avalanche ball of packed snow and ice chunks. The charge plows through mobs, scooping them into the snowball, and ends in a burst that entombs victims in snow blocks they must break out of. The bear's charge can smash through soft terrain, carving a trench path with scattered powder behind it.

5. **Ancient Ice Armor**: Plates of ancient blue ice grow over the bear's body with crackling frost sounds and glowing rune like seams, transforming it into an armored glacier beast. The armor grants heavy damage reduction and thorn style ice shard retaliation, and the bear's steps freeze water and ground alike into a spreading ice sheet. When the armor finally shatters it releases a freezing nova that flash freezes everything nearby.

## Llama

1. **Spit Gatling Barrage**: The llama's cheeks bulge comically, then it unleashes a rapid fire stream of glowing spit globs in a wide cone, each trailing green droplets and landing with splat particles. The barrage suppresses enemies, stacking a disgust debuff that lowers their damage and makes them shake particles off constantly. The llama backs up while firing, kiting at increased speed, and the ground ends up coated in slippery spit patches.

2. **Alpaca Wool Fortress**: The llama shakes violently and its wool explodes outward into a huge fluffy mass, transforming it into a round wool fortress with only its head poking out, surrounded by floating wool tufts. In this form it is nearly immune to knockback and heavily armored, and enemies that bite the wool get a mouthful that slows and silences them briefly. It can shed the fortress in a burst that blinds attackers with a wool cloud and restores its mobility.

3. **Summon the Caravan Guard**: The llama lets out a braying call and decorated caravan llamas trot in wearing colorful carpets, forming a defensive circle around it. The caravan spits in coordinated volleys at anything that approaches and body blocks charges, their carpets glowing with woven patterns. While the caravan holds, the main llama gains regeneration and its spit becomes explosive, bursting into smaller globs on impact.

4. **Stampede Kick Spiral**: The llama spins in place, building a dust devil of kicked up dirt, wool, and debris particles, then launches into a whirling kick combo that travels across the arena. Each kick lands with a cartoon shockwave ring and huge knockback, and the spin deflects projectiles in random directions. At the end it strikes a pose, briefly taunting enemies into targeting it while it catches its breath behind raised defenses.

5. **High Altitude Lunger**: The llama inflates with mountain air, body swelling and floating slightly off the ground with mist particles, adapted from its highland roots. In this state it gains slow fall, big jump power, and its spit falls like artillery with splash damage and crater splats. It can also exhale a massive gust that pushes all nearby mobs back in a visible cone of wind and spit droplets.

## Trader Llama

1. **Merchant's Golden Spit**: The trader llama's spit turns to molten gold colored globs that splatter targets with sparkling coin particles and gilded drips. Gilded enemies become high value targets: every other mob in the war gains a damage bonus against them, shown by targeting sparkles, and they drop bonus points when defeated. The llama itself stays at range, retreating at boosted speed while its merchandise does the work.

2. **Caravan Mirage**: The llama and its trader's wares shimmer and split into three mirage copies, each with slightly transparent models and heat haze particles, while the real one hides among them. Enemies waste attacks on the fakes, which pop into puffs of dyed wool and carpet scraps, and each fake destroyed refunds the llama a bit of health. The real llama's next spit after a mirage pops is a guaranteed heavy critical glob.

3. **Summon the Escort Caravan**: With a shake of its decorated harness, bells ringing with note particles, the trader llama calls in an escort of armored caravan llamas and a wandering trader decoy on a lead. The escorts form a moving convoy that circles the arena, spitting at threats and trampling small mobs, while the decoy trader draws enemy attention by waving emeralds. The convoy leaves a trail of scattered goods particles that slow pursuers like caltrops.

4. **Leash Whip Lasso**: The trader llama weaponizes its lead, whipping it out in a long glowing lasso that wraps around a target's legs with rope particles and a yank animation. The lassoed mob is dragged toward the llama's caravan, takes damage from the rough pull across the ground, and stays tethered for several seconds, unable to move far. If the tethered mob dies while leashed, the llama steals a chunk of its points directly.

5. **War Profiteer Aura**: The llama sets up an aura of commerce: floating emerald sparkles, price tag particles, and a soft golden glow in a wide radius. Inside the aura, every mob kill by anyone pays the llama a tax of points, shown by coins streaming through the air to it, and the llama converts gathered points into instant healing and speed. Enemies that linger in the aura get fleeced, losing a trickle of points and attack strength over time.

## Goat

1. **Ram Rocket Charge**: The goat lowers its head, horns glowing and scraping sparks off the ground, then launches forward like a rocket with a dust contrail and speed lines. The headbutt lands with an enormous shockwave, screen shake style particle burst, and sends the victim flying farther than any normal knockback, smashing through soft blocks on the way. After the charge the goat skids to a stop, briefly dazed with circling stars, unless it hit a target, in which case it can immediately chain a second charge.

2. **Mountain King's Terrain**: The goat bleats a challenge and the ground answers: stone pillars and jagged ledges erupt around the arena, reshaping it into a goat favored mountain course of high perches and narrow ramps. The goat gains extreme jump power and fall immunity, raining headbutts from above, while ground bound enemies must navigate the maze. Goat favored perches glow faintly, and standing on one regenerates the goat's stamina for more charges.

3. **Screaming Goat Aura**: The goat unleashes its infamous scream, a visible cone of distorted air, spit flecks, and musical chaos particles that rolls across the battlefield. Mobs caught in the scream are stunned by sheer auditory assault, clutching their heads with dizzy spirals, and take damage over time while it lasts. The goat can sustain the scream while slowly rotating, sweeping the cone like a lighthouse beam of noise.

4. **Herd Stampede Summon**: The goat calls the herd: a stampede of goats charges through the arena in a thundering line, kicking up a massive dust cloud with debris and hoof spark particles. The stampede tramples everything in its path for heavy damage and knockback, smashes obstacles, and then scatters, with a few goats lingering to headbutt stragglers. The main goat rides the front of the stampede, immune to damage while surfing the chaos.

5. **Ironhorn Transformation**: The goat's horns transmute into gleaming iron, then steel, then a glowing enchanted metal as it grows larger and more muscular, with sparks flying at every step. In Ironhorn form its headbutts can crack armor, shown by shield shatter particles and reduced enemy defense, and its body gains heavy knockback resistance and bonus health. The form sheds metal shards as it takes damage, which litter the ground as sharp caltrops.

## Panda

1. **Bamboo Barrage Forest**: The panda slams its paws down and bamboo shoots erupt from the ground all around in a rapid growing forest, with leaf bursts and green sparkle particles. Enemies are speared and tangled by the shoots, taking damage and getting rooted briefly, while the panda moves freely through its own bamboo. The panda can eat the bamboo mid fight, crunching visibly and converting each stalk into healing and a stacking speed boost.

2. **Rolling Thunder Panda**: The panda curls into a ball and becomes a rolling juggernaut, fur crackling with static and dust streaming behind it as it ricochets around the arena. It bounces off walls and mobs alike, each impact landing with a thud shockwave and growing its momentum and damage. Steering is wild but devastating, and when it finally unrolls it releases a dizziness wave that staggers everything nearby, itself included for a shorter time.

3. **Playful Spirit Guardians**: The panda claps its paws and two playful panda spirit cubs, glowing and semi transparent with leaf trails, tumble into the fight. The cubs roll into enemies to trip and distract them, steal their footing with banana peel style slips shown by spiral particles, and periodically flop onto a target to pin it with their combined cuteness and weight. Damage to the cubs is redirected as healing to the main panda while they play.

4. **Lazy Zen Fortress**: The panda sits down, plants itself, and enters a zen state: a serene bubble of calm air, floating bamboo leaves, and soft green light surrounds it. While seated it rapidly regenerates, gains heavy armor, and reflects projectiles lazily with one paw swats that send them back in slow arcs. Enemies that enter the bubble are calmed, losing aggression and attack speed, shown by drooping eyelids and slow drifting Z particles. Rising from the fortress releases a burst of stored energy as a healing shockwave for itself and a knockback for others.

5. **Sneeze Cataclysm**: The panda's nose twitches, a tiny flower petal lands on it, and the buildup begins: swelling body, watering eyes, and rising particle pressure over several dramatic seconds. The sneeze detonates as a cataclysmic cone of wind, mucus globs, and debris that obliterates everything in front of it, flattening bamboo, launching mobs to the arena edge, and leaving the panda briefly tiny, dazed, and adorable before it regrows. Nothing in the cone is left standing, including terrain cover.

## Iron Golem

1. **Village's Vengeance Slam**: The iron golem raises both arms, vines and poppies across its body glowing red with rage, and brings them down in a slam that cracks the ground into a spiderweb fissure. The fissure erupts with dust, rock chunks, and a ring of upheaval that launches every mob into the air, and the golem follows with a swat that sends one victim flying across the arena. The cracked ground remains as difficult terrain that slows enemies crossing it.

2. **Forge Heart Awakening**: The golem's chest plates split open to reveal a molten forge heart, orange light pouring out with ember and smoke particles, and its iron body heats to a glowing temper. In this state its fists deal burn damage with molten drips, it gains major attack and health boosts, and striking it splashes attackers with liquid metal sparks. The heat warps the air around it and leaves scorched footprints.

3. **Summon the Iron Sentinels**: The golem strikes a heroic pose and the ground assembles reinforcements: scrap iron and stone whirl together with clanking particles to form two smaller iron sentinel golems. The sentinels guard the main golem, intercepting attackers with poppy red aggro lines, and when one falls it collapses into an iron block pile that the main golem can absorb to repair itself. Their eyes glow with the same protective light as their maker.

4. **Living Fortress Mode**: The golem plants its feet, roots and stone creep up its legs, and it transforms into a stationary fortress form, growing broader with battlements of cracked stone and a poppy banner. As a fortress it gains huge armor and regeneration, automatically launches rock projectiles at ranged attackers, and provides cover that blocks enemy movement and projectiles. It can uproot itself in an explosive burst that showers the area with stone shrapnel when it needs to move again.

5. **Seismic Poppy Field**: The golem kneels and presses its palms to the earth, and a field of poppies and iron rich soil spreads outward in a wide circle, flowers blooming in waves with petal particles. Enemies standing in the field are rooted by iron vines that erupt periodically, and each root pulses damage. The golem draws strength from its field, gaining healing for every rooted enemy, and the poppies turn black and wither one by one as the effect's power is spent.

## Snow Golem

1. **Blizzard Barrage Cannon**: The snow golem's head spins, its pumpkin face glowing with inner frost light, and it becomes a rotary snowball cannon, firing a sweeping stream of oversized snowballs with spiral frost trails. Each hit builds a frost stack on the victim, shown by creeping ice crystals on their body, and at full stacks the mob freezes solid in a block of ice. The golem overheats its own snow supply over time, needing a brief cooldown where it visibly repacks its body.

2. **Snowman Army Decoys**: The snow golem rapidly builds a squad of decoy snowmen around the arena, each with slightly different crooked pumpkin faces and stick arms, amid puffs of snow and carrot particles. Enemies attack the decoys, which soak hits and burst into blinding snow clouds, while the real golem hides among them and relocates in a swirl of flakes. Every decoy destroyed chills its destroyer, stacking slowness on the whole enemy side.

3. **Avalanche Form**: The snow golem absorbs surrounding snow and transforms into a hulking abominable snowman form, three times its size with ice crystal claws and a roaring carved face. In this form its snowballs become boulders of packed ice that smash and crater the ground, its melee swats create frost shockwaves, and it gains heavy armor and health. It slowly melts under sustained fire damage, shrinking visibly as it takes heat.

4. **Permafrost Domain**: The golem plants its stick arms and freezes the battlefield into a permafrost domain: ice sheet spreads across the ground, frost fog rolls in, and icicles grow from every surface with tinkling particle sparkles. Enemies slide uncontrollably on the ice and slowly freeze, while the golem skates gracefully at high speed and its projectiles home slightly toward chilled targets. The domain keeps snowing the whole time, gradually burying small mobs up to their waists in drifts that slow them further.

5. **Gift of the Melting Heart**: The snow golem sacrifices its own body in a noble melt, collapsing into a magical snowmelt pool that sparkles with healing light and rising steam. The pool rapidly heals the golem's allies standing in it, or itself if solo, over several seconds, while enemies in the pool are soaked, chilled, and slowed. At the end, the golem reforms from the pool in a burst of fresh snow at full fighting shape, wherever the pool's center is.

## Strider

1. **Lava Wake Tsunami**: The strider surges forward on the lava, kicking up a towering wake of molten rock, embers, and steam that rolls outward like a tsunami wave. Mobs caught in the wake are swept along, burned, and deposited in a heap at its edge, and the strider gains huge speed while riding its own wave. The wave cools into a temporary obsidian and basalt path that other mobs must cross carefully.

2. **Magma Bloom Eruption**: The strider stomps and cracks the lava surface, triggering a blooming eruption of lava fountains in a ring around it, each jet throwing glowing rock chunks and ash particles. Enemies are launched by the jets and take heavy fire damage, and the strider basks in the eruption, absorbing heat to grow larger, hotter, and stronger. Cooled rock platforms from the chunks become stepping stones the strider can use to tower over the fight.

3. **Shivering Heat Theft**: The strider, famous for shivering when cold, weaponizes it: it drains the heat from nearby mobs, shown by streams of orange warmth particles flowing from victims into the strider. Drained mobs turn blue tinted, slow, and begin shivering uncontrollably with chattering particles, while the strider ignites with stolen warmth, gaining speed, fire damage on contact, and a glowing mane. If it steals enough heat it briefly becomes a living furnace that scorches the ground it walks on, even off lava.

4. **Summon the Strider Calves**: The strider calls and baby striders, small and wobbly with oversized heads, ride in on little lava ripples to join the fight. The calves bump and nip at enemies, and each one acts as a heat battery: the parent can draw warmth from them to heal, shown by glowing transfer streams. Enemies that attack a calf face the parent's protective rage, a visible steam snort and a charging headbutt with bonus damage.

5. **Basalt Titan Form**: The strider transforms as basalt plates armor its body, its legs lengthen, and it rises into a towering titan that can stride across land and lava alike, dripping magma from its joints. In titan form it gains massive health and size, its steps create lava pools on any surface, and its drooping hair becomes burning whips that lash nearby mobs. The form slowly cools and cracks, shedding armor plates as its timer runs down.

## Dolphin

1. **Sonar Shockwave Pulse**: The dolphin emits a devastating sonar pulse, visible as expanding blue rings with bubble trails and distorted water shimmer, that travels through water and air alike. Enemies caught in the rings are stunned by the sensory blast, shown by spiraling stars and echo ghosts of themselves, and take damage that ignores armor. The pulse also reveals every mob's position to the dolphin with glowing outlines through walls for several seconds.

2. **Grace of the Pod**: The dolphin calls its pod and spectral dolphins of light and bubbles swim in through the air as if it were water, circling in a protective, playful formation. The pod grants the dolphin and its allies the Dolphin's Grace effect made visible: speed streams, bubble auras, and boosted damage, and pod members ram attackers in synchronized arcs. The real dolphin becomes hard to pin down, darting between pod members in flashes of spray.

3. **Tidal Breach Slam**: The dolphin launches itself in a spectacular breach, trailing a ribbon of conjured water, then crashes down and creates a real tidal wave block of water that floods outward across the arena. The flood sweeps enemies off their feet, pushes them to the edges, and leaves a temporary shallow sea where the dolphin swims at full speed while land mobs flounder and slow. The water drains over time through cracks with swirling particle eddies.

4. **Treasure Hunter's Arsenal**: The dolphin dives and returns tossing up sunken treasures that become weapons: it hurls enchanted trident like debris, sprays ink black water from a squid ally's gift, and scatters pufferfish that inflate and spike nearby mobs. Each treasure appears with a golden glint and bubble burst, and the dolphin chains three different finds in one flashy sequence. Enemies hit by the pufferfish take poison and knockback, and the ink blinds in a dark splash cloud.

5. **Typhoon Form**: The dolphin transforms into a storm spirit of the sea: larger, translucent, and wreathed in a personal typhoon of water spirals, rain, and lightning spark particles. In this form it flies through the air on its storm, calls down small lightning strikes on wet enemies with crackling arcs, and its sonar pulses become constant waves that push everything back. The storm rains on the whole arena, keeping all surfaces wet and boosting every other dolphin ability it uses.

# Advanced Mobs: New Ability Designs, Group D

Paper design list only. Five fresh abilities per mob, built for spectacle: particle storms, transformations, summons, terrain reshaping, auras, and size changes. Carter picks the top three per mob before anything is implemented.

## Pig

1. **Mudslide Charge** - The pig drops onto its belly in a spray of brown mud particles and sleds forward on a temporary trail of mud blocks, knocking every mob in the path sideways. Anything it plows through takes damage and heavy knockback, and the pig gains a burst of speed and knockback resistance while sliding. The mud trail melts away after a few seconds.

2. **Truffle Tremor** - The pig roots at the ground, snout digging with dirt particles flying, until the soil erupts in a ring of cracking blocks and dust. Enemies standing in the ring are launched upward and take fall damage when they land. The pig itself stands in a calm center and gains a short armor bonus from being coated in packed earth.

3. **Swine Stampede** - With a squeal and a burst of pink sparkle particles, the pig summons three phantom war pigs that charge in a line at its target before popping into puffs of smoke. Each phantom deals contact damage and shoves the target backward. The real pig gets a small speed boost while the stampede runs so it can follow up.

4. **Golden Mud Armor** - The pig rolls in a shimmering puddle that appears under it, coating its body in a glossy gold tinted mud shell with dripping particle effects. While coated, it gains a large health and armor increase and reflects a chunk of melee damage back at attackers as flying mud globs. The shell cracks visually as it absorbs hits and shatters in a splash when it breaks.

5. **Feast Frenzy** - Carrot and beetroot particles swirl around the pig as it inhales every dropped food item nearby, growing noticeably bigger with each bite. Its size, max health, and stomp damage scale up with how much it ate, and its footsteps shake loose small dust clouds. The growth fades slowly once it stops eating.

## Cow

1. **Stampede Quake** - The cow paws the ground, snorting steam particles, then charges in a straight line leaving a cracked earth trail and a dust shockwave on both sides. Mobs caught in the path are trampled for heavy damage and knocked down with slowness. During the charge the cow gains major knockback resistance and a speed surge.

2. **Milk of Renewal** - A soft white glow and floating milk droplet particles surround the cow as it releases a healing splash in a circle around itself. Allied mobs in the splash recover health and shed negative effects, while the cow regains a portion of its own health too. Enemies get nothing but a face full of harmless foam particles.

3. **Meteor Moo** - The cow bellows and a deep horn sound rolls out as a ring of brown and white shockwave particles expands from it, pushing every nearby mob hard away from the center. Mobs slammed into walls or each other take bonus impact damage. The cow plants its feet and cannot be moved for a few seconds afterward.

4. **Leatherhide Transformation** - The cow's hide darkens and thickens with a leathery sheen and rising heat shimmer particles, and its horns grow longer and sharper on the model. In this form it gains big armor and health bonuses and its headbutts deal piercing damage. The form lasts until it takes a set amount of damage, ending in a burst of hide fragments.

5. **Calf Guard** - The cow summons two small calf companions with glowing collars that circle it like bodyguards, trailing tiny hoof print particles. The calves ram attackers for small damage and intercept projectiles aimed at the cow. While both calves stand, the cow gains a speed bonus from herd energy.

## Mooshroom

1. **Spore Nova** - The mooshroom shakes and releases a huge expanding cloud of red and white spore particles that hangs in the air for several seconds. Enemies inside the cloud take damage over time and get slowed as mushrooms sprout on the ground beneath them. The mooshroom heals slowly while standing in its own spores.

2. **Fungal Colossus** - Giant mushrooms erupt from the mooshroom's back and it grows to triple size, its cap glowing with pulsing red light and drifting spores. Its stomps create small shockwaves, its health pool swells, and its melee hits fling enemies far. When the form ends it shrinks back in a puff of spores and drops a few mushroom blocks.

3. **Mycelium Snare** - Purple mycelium tendrils burst from the ground in a circle, converting the surface blocks to mycelium temporarily and wrapping around enemy legs with root particles. Trapped mobs cannot move and take steady damage from the hungry fungus. The mooshroom gains speed on mycelium ground while the snare lasts.

4. **Stew Surge** - The mooshroom tips its bowl and a fountain of glowing stew particles arcs out, splashing a target area. Allies splashed gain regeneration and strength, and the mooshroom gains a matching attack boost. Enemies splashed get weakness and a face full of mushroom chunks.

5. **Spore Clone** - The mooshroom splits off a smaller spore copy of itself, a translucent mini mooshroom made of drifting particles that mimics its movements. The clone soaks attacks meant for the original and explodes into a blinding spore burst when destroyed. The original gains a brief invisibility shimmer while the clone is active.

## Sheep

1. **Wool Fortress** - The sheep puffs up into a giant ball of wool, its body swelling with soft white particles until it is mostly a rolling sphere. In this form it gains huge knockback resistance and armor, and attackers get tangled, taking slowness when they hit it. It can roll slowly into enemies to shove them away.

2. **Rainbow Ram** - The sheep's wool cycles through bright colors with sparkle particles as it charges, leaving a fading rainbow trail in the air. The headbutt at the end of the charge deals damage based on how far it ran and launches the target upward. Its speed ramps up the longer the charge goes.

3. **Shear Storm** - Wool explodes off the sheep in a blizzard of white fluff particles that fills the area and blocks vision. Enemies in the storm are slowed and take small ticking damage from the choking fluff, while the now shorn sheep becomes much faster and harder to hit. Its wool regrows over the next minute with little sprouting particles.

4. **Thundercloud Wool** - Static electricity crackles through the sheep's wool, turning it dark gray with blue spark particles and a low storm cloud forming above it. Melee attackers get zapped for lightning damage, and periodically a small bolt strikes the nearest enemy. The sheep gains a slight levitation drift, hovering just off the ground while charged.

5. **Flock Mirage** - The sheep bleats and four illusory sheep made of shimmering wool particles appear around it, copying its movements with a slight delay. Enemies waste attacks on the fakes, which pop into fluff when hit. The real sheep gains a damage bonus for each mirage still standing.

## Chicken

1. **Egg Barrage** - The chicken flaps furiously, rising into the air on a swirl of feather particles, and rains down a volley of eggs that crack on impact with yolk splat particles. Each egg deals small damage and has a chance to hatch a furious baby chick that pecks the nearest enemy. While airborne the chicken gains slow falling and a speed boost.

2. **Feather Cyclone** - The chicken spins in place, whipping up a tornado of white feathers that pulls nearby mobs toward it and tosses them around. Enemies caught in the cyclone take repeated small damage and get launched out at the end. The chicken sits safely in the calm eye with a small armor bonus.

3. **Colossal Cluck** - The chicken puffs up to several times its normal size with a deep echoing cluck, its comb glowing red and shockwave rings rolling off it. At full size its pecks deal heavy damage, its health pool multiplies, and its footsteps make dust bursts. It slowly deflates back to normal after the effect ends.

4. **Seed Frenzy** - Golden seed particles scatter across the ground as the chicken enters a pecking frenzy, dashing from seed to seed at blinding speed. Each peck at an enemy between seeds deals rapid damage, and the chicken becomes a blur with motion trail particles. It gains major speed and attack speed for the duration.

5. **Roost Totem** - The chicken hops onto a wooden perch block that rises from the ground, crowing as dawn colored light particles radiate outward. From the perch it cannot be reached by ground melee and it periodically lays explosive eggs that roll toward enemies. Allied mobs in the light gain a small regeneration effect.

## Rabbit

1. **Burrow Ambush** - The rabbit dives into the ground with a spray of dirt, leaving a small burrow hole block, and tunnels invisibly beneath the fight. It erupts under a target in an explosion of soil particles, dealing damage and knocking the target up, then gains a burst of speed. Fresh burrow holes mark each entrance and exit.

2. **Moonlit Leap** - Silver moonlight particles gather around the rabbit as it crouches, then it launches in an enormous arc trailing sparkles, ignoring fall damage completely. Where it lands, a small shockwave knocks enemies back. Its jump height and air control stay boosted for several seconds after landing.

3. **Thumper Quake** - The rabbit stands up and thumps both hind feet, sending visible ripple rings through the ground blocks around it. Each thump deals damage and worsens the cracks shown on the ground until the surface in the ring temporarily collapses into a pit. Enemies in the pit are slowed climbing out, while the rabbit hops freely over it.

4. **Carrot Cache Cannon** - The rabbit digs up a buried carrot cache in a shower of orange particles and begins hurling giant carrots like spears. Each giant carrot pierces through enemies in a line and sticks in the ground as a temporary block. Eating a dropped normal carrot mid fight gives the rabbit a quick heal and speed tick.

5. **Bunny Mirage Dash** - The rabbit dashes so fast it leaves solid looking afterimages behind, white blur particles streaming off its fur. For a few seconds every dash creates a decoy rabbit that draws enemy attention and pops when struck. The rabbit gains major speed and a chance to dodge attacks entirely while dashing.

## Horse

1. **Gallop Shockwave** - The horse rears with whinnying echo particles, then gallops in a chosen direction trailing wind streaks, and every few blocks its hooves slam out a dust shockwave. Mobs near the path take damage and knockback from the shockwaves. The horse gains stacking speed the longer it gallops without turning.

2. **Ironshod Kick** - The horse spins and delivers a double hind leg kick wreathed in gray metallic particles, dealing massive damage and the biggest knockback of any farm animal ability. The kick also shatters temporary blocks in front of it. After the kick the horse gains a brief armor bonus from its braced stance.

3. **Warhorse Form** - Armor plates materialize onto the horse piece by piece with clanking spark particles, and a plume appears on its head as war drums echo. In this form it gains large armor and health bonuses, its charge attacks trample enemies underfoot, and it becomes immune to knockback. The armor fades in a shower of sparks when the form ends.

4. **Mane of Embers** - The horse's mane and tail ignite into flowing fire colored hair with ember particles drifting off, though the fire is spectral and lights nothing. Enemies that touch the horse take burn damage, and its gallop leaves a short lived trail of glowing hoofprints. It gains speed at night and in the dark.

5. **Herd Call** - The horse lets out a piercing neigh and ghostly horse silhouettes made of pale particles gallop in from all sides, converging on its position. Each phantom that passes through an enemy deals damage and slows it. The horse gains a health bonus for every ally standing near it when the call ends.

## Donkey

1. **Stubborn Anchor** - The donkey digs in, its hooves sparking against the ground as stone colored particles lock around its legs, and it becomes completely immovable. While anchored it gains massive armor and reflects knockback onto attackers, sending them flying instead. Enemies that keep hitting it build up a release blast that fires when the anchor ends.

2. **Pack Mule Barrage** - The donkey shrugs off its packs and kicks a spray of supplies into the air: anvils of junk, barrels, and crates rendered as tumbling item particles and falling block shapes. Each falling piece deals area damage where it lands. The donkey becomes much faster with its load gone.

3. **Bray Blast** - The donkey throws its head back and unleashes a deafening bray shown as expanding sound rings and vibrating air particles. Enemies in a cone in front take damage, get knocked back, and suffer a brief confusion that scrambles their movement. The donkey gains a small attack bonus from sheer satisfaction.

4. **Dust Devil Trot** - Kicking up an ever growing cloud of tan dust particles, the donkey trots in a circle and whips the dust into a small traveling whirlwind that follows its target. The whirlwind lifts and spins enemies, dealing damage over time. The donkey gains speed inside its own dust cloud and is hard to target there.

5. **Treasure Sniff** - The donkey's nose glows gold and it paws at a sparkling patch of ground, digging up a buried chest block that bursts open with coin like sparkle particles. The burst heals the donkey and grants it a random strong buff, shown by colored wisps circling its head. Enemies near the chest get shoved away by the eruption of dirt.

## Mule

1. **Hybrid Surge** - Lightning like energy arcs between the mule's ears as horse speed and donkey stubbornness fuse, shown by split blue and brown particle streams along its body. The mule gains both a major speed boost and major knockback resistance at once, a combination no other animal gets. Its strikes during the surge deal bonus damage.

2. **Cargo Catapult** - The mule bucks and launches a strapped cargo crate high into the air with rope snap particles, and the crate crashes down on its target area as a temporary block before breaking apart. The impact deals heavy area damage and leaves splinter particles and a crater of cracked blocks. The mule gains a short speed boost after unloading.

3. **Iron Will Aura** - A steady amber glow radiates from the mule in a visible ring on the ground, with calm floating rune like particles. Allies inside the ring gain armor and knockback resistance, and the mule itself gains double the effect. The ring pulses outward whenever an ally inside takes a big hit, briefly strengthening everyone.

4. **Quicksand Kick** - The mule stamps and kicks loose soil into a patch of sinking sand blocks, tan particles bubbling on the surface. Enemies stepping in sink slowly, taking damage and losing speed the longer they stand there. The mule can walk across the surface of its own quicksand without sinking.

5. **Endless Trail** - The mule leaves a trail of glowing hoofprint decals and drifting map paper particles as it moves, building up momentum over distance. The longer it runs in a straight line, the faster and stronger its next attack becomes, up to a large cap. Turning sharply resets the bonus in a puff of paper scraps.

## Skeleton Horse

1. **Grave Gallop** - The skeleton horse charges trailing ghostly green flames and bone dust particles, passing straight through enemies and leaving them chilled with frost wisps. Each mob it passes through takes damage and slowness. During the gallop it gains huge speed and can run across water on a temporary path of ice thin bone plates.

2. **Rib Cage Prison** - Its ribs spring open with a clatter and a cage of giant bone bars erupts from the ground around its target, bone white particles knitting the bars together. The trapped mob cannot leave and takes damage each time it touches the bars. The skeleton horse gains armor while the prison stands.

3. **Soul Harvest** - Wisps of pale blue soul light drift from every mob that dies nearby and stream into the skeleton horse, its eye sockets glowing brighter with each one. Each soul restores its health and adds a stacking damage bonus. At full stacks it releases a wail that damages all enemies around it in a ring of ghostly particles.

4. **Bone Reconstruction** - When badly hurt, the skeleton horse collapses into a pile of bones with a puff of dust, seemingly dead, then reassembles itself piece by piece as the bones float back together with spectral chains of particles. It returns with a chunk of health restored and a brief speed boost. Enemies that wander near the bone pile get bitten by the skull for small damage.

5. **Death Knight's Mount** - The skeleton horse rears and a ghostly armored rider made of translucent green particles fades in on its back, raising a spectral lance. While mounted, the horse gains major armor and its charges impale the first enemy hit for heavy piercing damage. The rider vanishes in a swirl of souls when the effect ends.

## Camel

1. **Sandstorm Veil** - The camel kicks up a vast swirling wall of sand particles that circles it like a personal storm, blocks appearing to ripple at the edges. Enemies inside the veil are slowed, take ticking damage, and can barely see, while the camel moves at full speed and gains armor. The storm follows the camel as it walks.

2. **Twin Hump Bastion** - The camel kneels and its two humps swell and harden with rocky texture changes and dust particles, turning it into a living fort. While kneeling it gains enormous armor and health regeneration, and allies behind it are shielded from projectiles. It cannot move until it stands, ending with a shaking off burst of sand.

3. **Desert Mirage** - Heat shimmer particles warp the air and two false camels shimmer into being beside the real one, wavering like images over hot sand. Enemies attack the mirages, which dissolve into heat waves when hit. The real camel gains a damage bonus while at least one mirage remains.

4. **Spit Salvo** - The camel fires a rapid series of glowing green spit globs with dripping particle trails that arc toward its enemies. Each hit deals damage and coats the target in slowing goo shown as green drips. After several hits on the same target, the goo hardens briefly and roots the target in place.

5. **Oasis Blessing** - A ring of water sparkle particles and palm green motes blooms around the camel as it stamps, briefly sprouting grass and flower blocks in a circle. Allies in the ring heal steadily and gain speed, while the camel restores a large amount of its own health. The plants wither away when the blessing ends.

## Cat

1. **Nine Lives Gambit** - Golden number shaped sparkles orbit the cat, one for each life, and when the cat would take a fatal hit it instead bursts into light particles and reappears a few blocks away at partial health, spending one sparkle. While lives remain, the cat gains a small stacking speed bonus. Attackers are left swiping at fading afterimages.

2. **Shadow Pounce** - The cat melts into a flat shadow with only glowing eyes visible, sliding silently along the ground trailing dark wisps. It erupts from beneath a target in a burst of claw slash particles, dealing heavy damage from behind. The cat gains brief invisibility if the pounce kills its target.

3. **Yarn Ball Chaos** - The cat bats out a huge ball of red yarn that rolls through the fight trailing string particles and tangling around enemy legs. Mobs hit by the ball are slowed and pulled slightly off course as the yarn wraps them. The cat rides on top of the ball while it rolls, gaining knockback immunity.

4. **Alley Cat Fury** - The cat arches its back, fur standing on end with static spark particles, doubling its apparent size through sheer fluff and attitude. In this state its swipes come twice as fast, deal bonus damage, and each hit has a chance to make enemies flinch and back away. Its movement speed rises sharply.

5. **Curiosity Trap** - The cat places a cardboard box block that sits innocently in the arena with little sparkle particles of pure temptation. Enemies that wander close get distracted staring at the box, and the cat teleports into the box with a pop, then bursts out clawing the nearest distracted enemy for bonus damage. The box breaks after one use.

## Ocelot

1. **Jungle Phantom** - Leaf and green mist particles cloak the ocelot as it fades to near invisibility, leaving only faint paw print glows on the ground. It strikes from stealth for greatly increased damage, then recloaks if it breaks line of sight. Its speed in jungle foliage and tall grass is boosted the whole time.

2. **Vine Lash Ambush** - Vines burst from nearby trees and ground blocks, whipping toward the ocelot's target with leaf burst particles and wrapping it tight. The target is rooted and takes damage as the vines squeeze. The ocelot gains a free high speed approach while its prey is held.

3. **Spotted Blur** - The ocelot's spots stretch into motion lines as it dashes in a zigzag pattern, gold and brown blur particles trailing behind. Each dash segment that passes an enemy deals slashing damage. It gains major speed and evasion during the zigzag, making it very hard to hit.

4. **Territory Roar** - Despite its size, the ocelot lets out a jungle shaking growl with expanding green sound rings and falling leaf particles from above. Enemies in range are frightened, backing away and taking a defense drop. The ocelot gains an attack bonus against frightened enemies.

5. **Canopy Drop** - A temporary platform of leaf blocks forms high above the fight and the ocelot leaps up to it in a swirl of leaves, then dives down claws first onto a target. The dive deals heavy damage and creates a leaf explosion that slows nearby enemies. The ocelot takes no fall damage and gains a speed burst on landing.

## Parrot

1. **Mimic Barrage** - The parrot shrieks out copies of sounds from the fight, shown as colorful musical note particles and echo rings that bounce between enemies. Each bounce deals small damage and briefly confuses the mob it hits. The parrot gains a speed boost for every enemy currently confused.

2. **Rainbow Plume Burst** - The parrot explodes its feathers outward in a dazzling ring of red, blue, green, and yellow feather particles, blinding enemies facing it. Blinded mobs swing wildly and often miss, while the parrot darts away with a major speed burst. Its feathers regrow in a shimmering cascade over a few seconds.

3. **Pirate's Shoulder Guard** - The parrot swoops to perch on the largest allied mob nearby, settling on its shoulder with a happy chirp and heart particles. While perched it cannot be targeted by melee, and it periodically dives off to peck an enemy for damage before returning. The ally it rides gains a small armor bonus from its watchful eyes.

4. **Cracker Bomb** - The parrot drops a glowing cracker that bursts into a shower of seed and crumb particles, dealing area damage and scattering the ground with seed items. Enemies slip on the seeds, losing speed and footing. The parrot swoops down to eat a seed mid fight for a quick heal.

5. **Squawk Shockwave** - The parrot inflates its chest and unleashes a piercing squawk rendered as jagged sonic rings and vibrating feather particles in a cone. Enemies in the cone take damage, are knocked back, and get their ears ringing, shown by spiral particles and wobbly movement. The parrot gains brief hovering flight afterward, fluttering above melee reach.

## Fox

1. **Ember Pounce** - The fox leaps in a high arc trailing orange spark and snow or leaf particles depending on the ground, then dives nose first into a target with a burst of embers. The dive deals heavy damage and ignites the target briefly with harmless looking but damaging magical flame. The fox gains a speed burst on landing.

2. **Berry Illusion** - The fox shakes a shower of sweet berry particles into the air and splits into three foxes, two of them translucent illusions with berry red shimmer. The illusions dart around confusing enemy targeting and pop into berry bursts when hit. The real fox gains bonus damage while illusions remain.

3. **Snow Burrow Strike** - The fox dives headfirst into the ground, vanishing in a spray of dirt and a small hole, then tunnels and bursts out beneath an enemy in an eruption of soil particles. The surprise strike deals damage and knocks the enemy upward. The fox gains brief damage immunity as it shakes off the dirt.

4. **Tail Fan Blaze** - The fox fans its tail and it splits visually into a peacock like spread of three glowing tails with drifting firefly particles. Each tail lash as it spins deals damage in an arc, and the spin builds a small vortex of sparks around it. Its movement speed and turning speed increase while the tails are fanned.

5. **Den Mother's Call** - The fox yips and two pup foxes dig out of the ground beside it with dirt spray particles, joining the fight at its side. The pups nip at enemies for small damage and distract them from the adult. The adult fox gains health regeneration while both pups are alive.

## Frog

1. **Tongue Grapple** - The frog's tongue shoots out as a long pink ribbon with sticky droplet particles, latching onto a distant enemy and yanking it straight to the frog, or pulling the frog to a heavier enemy. The yanked mob takes damage and is briefly stunned from the whiplash. The frog gains a small armor bonus while digesting its momentum.

2. **Swamp Bubble Shield** - The frog inflates its throat sac into a huge translucent bubble with rainbow sheen particles that surrounds its whole body. The bubble absorbs damage and wobbles visibly with each hit, and enemies that strike it get splashed with slowing swamp goo. When it pops it sprays goo in a small ring.

3. **Meteor Croak Leap** - The frog crouches as green energy particles gather in its legs, then launches sky high and comes down like a meteor with a trailing green streak. The landing creates a shockwave of lily pad and water particles that damages and knocks back everything nearby. The frog takes no fall damage and gains a brief speed boost.

4. **Tadpole Swarm** - The frog releases a swirling cloud of tiny tadpole shapes made of dark particles into the air around it, which orbit like a living shield. Enemies entering the swarm get nibbled for steady damage and slowed by the swirling water effect. The frog heals slightly for each enemy currently in the swarm.

5. **Rain Dance** - The frog begins a rhythmic hopping dance as blue rain drop particles gather in a small personal rain cloud above it, which follows it around. While it rains on the frog, it regenerates health, moves faster on wet ground, and its skin glistens with a protective slime shown by droplet sparkles. Enemies under the cloud's edge get slowed by the downpour.

## Tadpole

1. **Schooling Vortex** - The tiny tadpole summons a swirling school of dozens of glowing tadpole silhouettes that spiral around it in a watery vortex of bubble particles, even on land in a magical splash zone. Enemies caught in the spiral take nibbling damage and get dragged slowly toward the center. The tadpole gains armor while the school surrounds it.

2. **Sudden Metamorphosis** - In a burst of green and gold sparkle particles, the tadpole flashes through its whole life cycle and emerges as a full sized frog for a short time, complete with legs and a croak shockwave on arrival. As a frog it gains hugely increased health, jump power, and a tongue slap attack. When the time runs out it reverts in a puff of pond mist.

3. **Puddle Mirage** - The tadpole splashes and creates a spread of shimmering puddle blocks across the ground that reflect light particles. Enemies crossing the puddles slip and slide, losing control of their movement, while the tadpole swims through them at high speed as if they were deep water. It gains major speed in its own puddles.

4. **Bubble Barrage** - The tadpole puffs its cheeks and fires a rapid stream of wobbling bubbles with shiny surface particles that float toward enemies and pop on contact. Each pop deals small damage and knocks the target slightly upward, juggling lighter mobs in the air. The tadpole gains a tiny hop backward with each shot from the recoil.

5. **Ancient Awakening** - Ancient teal runes circle the tadpole as it glows with deep water light, hinting at the enormous frog it might one day become, and a giant ghostly frog silhouette looms behind it for a moment. The apparition's single stomp sends a shockwave through the ground damaging all enemies nearby. The tadpole gains a lasting small size decrease and speed increase from the blessing, making it harder to hit.

## Turtle

1. **Shell Fortress Slam** - The turtle withdraws into its shell, which grows stone like spikes and moss with green particle motes, then spins and slides across the ground like a living boulder. Mobs in the slide path take heavy damage and knockback. While withdrawn the turtle gains massive armor but cannot use other attacks.

2. **Tidal Retreat** - A wave of water particles and temporary water blocks surges out from the turtle in a ring, pushing enemies away and leaving the ground slick. The turtle rides the wave backward at high speed, gaining distance from danger. Allies caught in the wave are pushed harmlessly and gain a brief speed boost.

3. **Ancient Shell Aura** - Old carved patterns on the turtle's shell light up with soft teal glowing lines, and a calm aura ring spreads across the ground with drifting sea grass particles. Allies in the aura regenerate health slowly and gain armor. The turtle itself gains double regeneration while the patterns glow.

4. **Snap Ambush** - The turtle sits perfectly still, shell dusted with sand particles until it looks like a harmless rock, then lunges with shocking speed as its jaws snap out in a burst of sand. The ambush bite deals heavy damage and briefly holds the target in its grip. After the snap the turtle's speed returns to its normal slow pace.

5. **Egg Minefield** - The turtle digs in the sand with spray particles and lays a clutch of glowing egg blocks around itself that pulse gently. Enemies stepping near an egg trigger it to hatch in a burst of shell fragments, and a baby turtle charges out biting the intruder before fading into sea sparkles. The parent gains a small heal for each egg that hatches.

## Armadillo

1. **Rolling Boulder Form** - The armadillo curls into a perfect armored ball with a clank and a ring of dust particles, then rolls at high speed, steering into enemies like a bowling ball. Each mob it strikes takes damage and big knockback, and it can bounce off walls to keep rolling. While balled it gains huge armor and knockback resistance but limited turning.

2. **Scute Shrapnel** - The armadillo shakes violently and sheds a burst of sharp scute plates that fly outward as spinning brown projectiles with dust trails. Each plate deals damage and can ricochet once off a wall or another mob. Its armor dips briefly while the plates regrow with little sprouting particles.

3. **Burrow Network** - The armadillo digs a series of burrow holes with flying dirt particles, vanishing into one and popping out of another across the arena. Each emergence under an enemy deals damage and knocks them up. It gains speed for a short time after every emergence.

4. **Desert Mirage Shell** - Heat waves and sand colored particles make the armadillo's shell blend into the ground as it flattens, becoming nearly invisible from above. Enemies lose track of it and wander, and its next attack from hiding deals double damage. Stepping on it by accident triggers an instant defensive curl with bonus armor.

5. **Ancient Armor Awakening** - Golden cracks of light spread across the armadillo's scutes as it channels the toughness of its giant ancestors, growing noticeably larger with rumbling ground particles at its feet. In this form it gains large health and armor bonuses, and its body slams shake the ground. The glow fades as it shrinks back to normal size.

## Sniffer

1. **Ancient Bloom Burst** - The sniffer digs its huge nose into the ground and pulls up a glowing ancient flower that blooms in an explosion of pollen and petal particles. Enemies in the pollen cloud are slowed and take damage over time from the potent spores, while allies gain regeneration. The sniffer gains a health boost from the fresh scent.

2. **Seismic Sniff** - The sniffer presses its nose to the earth and inhales so hard that visible scent trails and dust streams pull toward it from every enemy, revealing them with glowing outlines. Marked enemies take bonus damage from the sniffer's attacks, and the sniffer gains speed toward marked targets. The ground trembles slightly with each deep sniff.

3. **Torchflower Flare** - The sniffer shakes loose a cloud of torchflower seeds that ignite mid air into drifting orange flare particles, lighting the area and burning enemies they touch. The flares float slowly downward for several seconds, creating a dangerous glowing curtain. The sniffer gains night vision like clarity, shown by its eyes glowing softly.

4. **Gentle Giant Trample** - Despite its calm nature, the sniffer lumbers into a charge, its massive body shaking the ground with each step as leaf and dirt particles fly. Mobs underfoot take trample damage and are knocked aside. It gains knockback resistance and a speed ramp during the charge, and its size feels even more imposing with a slight scale up.

5. **Seed Vault Scatter** - The sniffer's back plates open like a living vault and scatter a ring of ancient seed pods that sprout instantly into temporary plant blocks: pitcher pods that snap at enemies and spore blossoms that puff slowing clouds. Enemies must fight through the garden while the sniffer retreats or regroups. The sniffer gains a small heal for each plant that lands a hit.

# Advanced Mobs: New Ability Designs, Group E

Design list only. Five candidate abilities per mob, built to look spectacular in a mob war: particle storms, transformations, summons, terrain reshaping, size changes, auras, beams, and shockwaves.

## Cod

1. **Tidal School Vortex** - The cod summons a swirling cyclone of water particles and a ring of 6 to 8 ghostly translucent cod that orbit it at high speed, forming a living whirlpool. Enemies caught inside get pulled toward the center, take rapid small hits from the circling fish, and have their movement slowed as if swimming against a current. The cod itself gains speed and knockback resistance while the vortex lasts.

2. **Pressure Slam** - The cod rockets upward out of the water, trailing bubble particles, then dives down and detonates a deep blue shockwave ring on impact that flattens nearby ground blocks into cracked variants for a few seconds. The slam deals heavy damage in a small radius, launches enemies into the air, and leaves a temporary puddle layer that makes the ground slippery for anything chasing the cod.

3. **Silverflash Mirage** - The cod's scales flare with bright white and silver sparkle particles and it splits into three shimmering mirror images that dart around confusingly. The copies are fake but enemies target them randomly, and each time a copy is "hit" it pops in a burst of glittering scales and blinds the attacker for a moment. The real cod becomes slightly transparent and gains a speed burst while at least one copy survives.

4. **Abyssal Maw** - The cod briefly transforms: it swells to three times its size, its body darkens to deep navy, and a glowing lure of light particles dangles in front of a huge translucent jaw outline. In this form it can bite once for massive damage that ignores part of the target's armor, and the bite spawns a swirl of dark ink-like particles. After the bite it shrinks back down and is briefly slower while it recovers.

5. **Roe Burst Barrage** - The cod releases a cloud of glowing orange egg particles that drift and bob like mines in the water or hover low over land. Each egg that an enemy touches hatches in a tiny explosion of bubbles, dealing small damage and applying a brief slowness, and chained eggs can set each other off. The cod lays more eggs the longer the fight lasts, slowly turning its home turf into a minefield.

## Salmon

1. **Upstream Rush** - A roaring column of whitewater particles erupts beneath the salmon and it launches itself in a powerful arcing leap, trailing foam and spray, able to cross gaps and climb waterfalls mid-fight. Landing creates a splashing shockwave that knocks enemies back, and while rushing the salmon ignores knockback entirely and gains a big temporary speed and attack boost.

2. **Leaping Barrage** - The salmon calls its school: a volley of glowing salmon silhouettes rains down in arcs around the target area like living artillery, each one bursting into spray and fish-scale particles on impact. Each impact deals moderate damage and slightly floods the struck block, and the barrage repeats in three waves that walk across the battlefield.

3. **Scarlet Surge** - The salmon's body flushes deep red with ember-like red particles rising off its scales, its fins grow larger and more jagged in silhouette, and a red mist aura spreads around it. In this enraged spawning-run state it gains health regeneration, faster attacks, and bonus damage that grows the lower its health drops, but it cannot retreat: it always charges the nearest enemy.

4. **Riverbend Snare** - The salmon whips up a spiraling ring of current particles that hardens into a visible whirlpool trap on the ground or water surface. Enemies stepping in are spun in place, slowly dragged to the center, and take choking damage while their attacks are interrupted by the spin. The salmon can swim or move freely through its own trap and gets a damage bonus against trapped targets.

5. **Glacier Melt Flood** - With a crackling burst of ice-blue particles, the salmon summons a sudden flood wave that rolls outward in one direction, pushing enemies along with it and converting the ground it crosses into temporary ice and water blocks. Enemies carried by the wave take damage when they slam into walls, and the salmon rides the front of the wave at high speed, gaining a brief shield of swirling ice shards.

## Tropical Fish

1. **Kaleidoscope Dazzle** - The fish erupts in a rotating rainbow of colored light particles and its pattern shifts rapidly through bright neon color schemes, projecting flashing rings of color. Enemies looking at it gain confusion and their attacks swing wildly off target, while allies nearby get a small speed boost from the display. The effect pulses stronger each time the fish takes a hit, like a warning flare.

2. **Reef Bloom** - The fish plants itself and blooms: coral-like particle structures and glowing anemone tendrils sprout from the ground in a circle around it, turning the patch into a miniature reef with swaying colored lights. Standing in the reef heals the fish and its allies steadily, while enemies inside take tiny stinging hits from the anemones and get slowed by the tangling tendrils.

3. **Mimic Morph** - The tropical fish flashes white and reshapes itself into a rough copy of the strongest enemy nearby, matching its size and silhouette with a shimmering tropical color overlay, for several seconds. It gains a portion of that enemy's attack style as simple melee power and bonus health based on the copied mob, and enemies hesitate, briefly unsure which one to target. When the morph ends it bursts into confetti-like scale particles.

4. **Venom Fin Fan** - The fish flares its fins wide, revealing glowing striped patterns, and fans out a cone of shimmering spore-like particles. Enemies in the cone are poisoned with a hallucinogenic venom: they take damage over time and their screen-side behavior in the war is scrambled, making them attack the nearest mob at random, friend or foe. The fish's fins stay visibly larger and brighter while the venom is charged.

5. **Shimmerstep School** - The fish blurs into a streak of rainbow light particles and darts in a zigzag pattern too fast to track, leaving a fading trail of colored afterimages. During the dash it is untargetable, and it can pass through enemy lines to strike the back row, dealing bonus damage to the first enemy it reappears next to. Chained dashes leave a sparkling rainbow trail that allies can follow for a speed boost.

## Pufferfish

1. **Grand Inflation** - The pufferfish swells far beyond its normal puffed size, growing to rival a cow, with spikes that lengthen, glow faintly green at the tips, and rattle with particle sparks. While inflated it gains large bonus health and heavy knockback resistance, and any enemy that melee-hits it takes spike damage and gets knocked back hard. It slowly deflates over time or can hold the form longer by staying still.

2. **Neurotoxin Nova** - The pufferfish detonates a billowing sphere of purple-green toxin particles that expands outward and lingers as a visible poison cloud shaped like a ringed dome. Enemies inside the cloud take stacking poison damage and their vision-side targeting gets sluggish, reducing their attack speed. The cloud drifts slowly with the wind of the fight and the pufferfish is immune, lurking at its center.

3. **Spike Mortar** - The pufferfish inflates partially and fires volleys of glowing spikes that arc through the air trailing green particles and stick into the ground and enemies. Spikes stuck in enemies deal damage over time and reduce their max health slightly while lodged, and ground spikes act as caltrops that hurt anything walking over them. It can fire while moving, kiting bigger mobs from range.

4. **Tetrodotoxin Revenge** - When the pufferfish drops to low health, it enters a dramatic death bloom state: its body flashes purple and white, swells, and releases a massive final pulse of toxin rings and floating spore particles in all directions. The pulse deals heavy damage and a long poison to everything nearby, and if the pufferfish survives the next few seconds it rapidly regenerates a chunk of health as the toxins cycle through its body.

5. **Balloon Drift** - The pufferfish inflates with light, buoyant gases shown as pale shimmering particles, and floats up out of reach, bobbing in the air with greatly reduced gravity. From above it gains a height advantage: it can drop spike clusters and toxin puffs on enemies below, and ground-bound mobs struggle to hit it. Strong hits can pop the balloon early, dropping it with a small shockwave but no fall damage.

## Squid

1. **Inkstorm Blackout** - The squid detonates a huge expanding cloud of black ink particles that swallows the whole fight area in darkness, with faint purple glimmers drifting inside. Enemies inside the cloud are blinded, lose their target lock, and swing at random, while the squid's eyes glow as two visible points in the dark. The squid gains a major speed boost inside its own ink and its attacks from within the cloud deal bonus damage.

2. **Kraken Grasp** - The squid grows: its tentacles stretch to several times their normal length, rendered thicker and lined with glowing sucker rings, and lash out to grab up to three enemies at once. Grabbed enemies are held off the ground, squeezed for damage over time, and cannot attack, while the squid's body rises up on its tentacles gaining height and reach. Struggling enemies can break free early by dealing enough damage to the holding tentacle.

3. **Jetstream Ram** - The squid contracts with a burst of white water particles and launches itself like a torpedo in a straight line, mantle first, trailing a spiraling jet wake. Anything in the path takes heavy ramming damage and is knocked aside, and the squid can chain up to three jets in different directions, bouncing off walls with splashes. After the final jet it is briefly vulnerable and slower while it refills.

4. **Biolume Lure** - The squid's body begins pulsing with hypnotic rings of blue and violet light particles that ripple outward in waves, and small glowing orbs drift from its tentacles. Enemies that see the pulses are drawn toward the squid against their war instincts, walking into range and lowering their guard, taking bonus damage from the squid's next attack. Allies are unaffected and can use the lure to set up ambushes on the drawn-in enemies.

5. **Ink Clone Shoal** - The squid sprays ink that condenses into two solid ink-black squid copies, glossy and dripping particles, which fight alongside it with real melee attacks. The copies have a portion of the squid's health, and when destroyed they burst into blinding ink splashes. The real squid can swap places with a copy once in a flash of ink, leaving enemies hitting the wrong target.

## Glow Squid

1. **Strobe Nova** - The glow squid builds up light until its whole body is a radiant cyan star, then releases a blinding nova flash of sparkles and light rings that whites out the area. Enemies caught by the flash are stunned briefly and take damage that scales with how close they were, and glowing sparkle particles cling to them afterward, making them visible and easier to target through walls and darkness for a while.

2. **Constellation Form** - The glow squid's spots detach as floating points of light that arrange into a slowly rotating constellation around it, connected by faint beams, while its body turns semi-transparent and starry. In this form it gains levitation, drifts over the battlefield, and the constellation points periodically fire small beams of light at nearby enemies. Melee attacks against it mostly pass through the transparent body, giving it strong damage reduction from ground mobs.

3. **Abyssal Lantern Call** - The glow squid pulses a deep rhythmic glow and calls aid from the dark: several small glowing squid silhouettes and shimmering angler-like lights rise from below or fade in around it, forming a ring of lights. The summoned lights dart at enemies, each exploding in a small flash that damages and briefly blinds, and while the ring lasts the glow squid regenerates health in the comforting glow.

4. **Prism Ink** - Instead of black ink, this squid sprays refractive prismatic ink: clouds of rainbow-sparkle particles that bend light and create shimmering distortion zones. Enemies inside a prism zone see their attacks refracted, dealing reduced damage, and their projectiles can be deflected at random angles. The glow squid inside the zone becomes harder to see, flickering like a mirage, and gains movement speed.

5. **Tidal Glow Surge** - The glow squid channels the ocean's glow: waves of cyan light particles roll across the ground or water in expanding rings from its position, each wave healing allies it washes over and damaging enemies with electric-looking sparks. Standing in deep water makes the waves stronger and faster, and after three waves the squid releases a final vertical beam of light that strikes the strongest enemy nearby.

## Villager

1. **Emerald Barrage** - The villager panics productively, hurling handfuls of emeralds that arc through the air trailing green sparkles and clink into enemies with surprising force. Each emerald hit deals damage and has a chance to drop a brief "discount" effect on the target, making it take increased damage from all sources, as if its defenses were haggled down. The villager's own emerald stash visibly shrinks and regrows as it throws and re-earns them.

2. **Summon Iron Guardian** - With a ring of anvil-gray particles and a deep bell chime visual of expanding sound rings, the villager summons an iron golem ally that rises from the ground in a shower of stone dust and poppy petals. The golem fights for the villager, focusing on whatever hurt the villager last, and while it lives the villager gains courage: bonus speed and the ability to throw emeralds faster. If the golem falls, the villager can eventually earn enough points to call another.

3. **Master Trader's Gambit** - The villager opens a spectral trading display: glowing item icons and emerald particles swirl around it in a merchant's halo, and it "trades away" the enemy's strength. The strongest enemy nearby has one of its attributes (damage, speed, or armor) reduced while the villager or its weakest ally gains the same amount, shown by streams of green particles flowing from enemy to ally. The swap lasts until the villager is hit hard enough to break its concentration.

4. **Village Bell Sanctuary** - The villager rings a phantom golden bell that materializes above it, sending out warm golden rings of particles that reshape a small patch of ground into a safe market square with fleeting stall silhouettes. Inside the square the villager and allies regenerate health, gain armor, and enemies feel compelled to keep their distance, getting gently pushed at the border. The bell tolls again each time an ally inside would take a fatal hit, negating it once.

5. **Cartographer's Escape** - The villager unrolls a glowing map with a burst of paper and compass sparkle particles, studies it for a split second, and vanishes in a swirl of map fragments, reappearing a good distance away at a "marked" location shown by a floating X of light. It leaves behind a decoy villager made of paper and sticks that wobbles and draws enemy attacks before collapsing. After escaping, the villager gains a speed boost and brief invisibility while it repositions.

## Wandering Trader

1. **Caravan Ambush Call** - The trader blows a horn visual of expanding note rings and his two leashed llamas multiply: a whole caravan of spectral trader llamas fades in around him in a swirl of carpet-colored particles, each wearing patterned blankets. The llamas form a defensive circle, spit at enemies in volleys, and body-block charges aimed at the trader. The trader directs them with pointed gestures, focusing their spit on his current target.

2. **Exotic Potion Stock** - The trader rummages in his robes and starts throwing his rarest wares: bottles that burst into colorful clouds of random potent effects, shown as swirling multicolored particles and bottle-shard sparkles. Each throw can splash enemies with slowness, weakness, levitation, or glowing, and splash himself or allies with speed, strength, or regeneration. No two throws are alike, making him a chaotic wildcard in any war.

3. **Invisibility Draught Ambush** - The trader drinks one of his own potions in a swirl of pale blue particles and vanishes completely, leaving only faint footstep sparkles and the occasional shimmer of his outline. While invisible he circles the fight, and his first attack from stealth is a devastating ambush that deals multiplied damage and startles the target into dropping its guard, reducing its armor briefly. Getting hit or attacking reveals him in a splash of particles.

4. **Llama Spit Artillery** - The trader's llamas rear back and unleash a coordinated artillery mode: their spits become glowing, arcing globs trailing green-white particles that explode on impact in small splashes. Splash hits deal damage and coat enemies in sticky spit that slows them and reduces their attack speed, stacking with each coat. The trader himself reloads the llamas faster the longer he stays out of melee, rewarding him for keeping his distance like a true merchant of war.

5. **Wares of the World** - The trader throws open a floating display of treasures from every biome: glowing artifacts, a miniature trident, a tiny totem, and sparkling geodes orbit him in a ring of golden particles. Each orbit cycle, one treasure activates at random: the totem grants him a death save, the trident calls a small lightning strike on his attacker, the geodes burst into crystal shards that damage nearby enemies. The display makes him a priority target, but a dangerous one to focus.

## Allay

1. **Duplication Waltz** - The allay twirls with a burst of note particles and splits into a chorus of copies, up to four extra allays that dance in a circle around the original, all trailing musical sparkles. The copies are semi-real: each can deliver small pecking attacks and distract enemies, and damage dealt to the allay is shared across the whole chorus. When a copy fades it pops into a shower of blue notes that heal the remaining chorus slightly.

2. **Symphony of Sorrows** - The allay sings, and visible waves of deep blue and violet note particles roll outward in slow, heavy pulses that reshape the mood of the battlefield. Enemies caught in the waves are slowed, their attack damage drops as if demoralized, and low-health enemies may simply give up and stop attacking for a few seconds. Allies hearing the song gain regeneration, and the melody visibly intensifies with visual staff lines curling through the air.

3. **Gift of the Hoard** - The allay darts around collecting shimmering "treasure" motes that appear as golden sparkles, then delivers them in a grand gesture: it drops a pile of glowing items that burst into a fountain of beneficial particles for its allies. Allies near the fountain gain random strong buffs (speed, strength, resistance) while enemies drawn to the shiny pile find it is a trap: the last item explodes in a flash of light and sound that damages and disorients them.

4. **Note Block Barrage** - The allay summons a ring of floating note blocks that materialize with wooden thock particles and begin playing themselves in a rapid musical sequence, each note firing a visible sound projectile of colored light at nearby enemies. Different note colors deal different effects: red notes burn briefly, blue notes slow, green notes knock back. The barrage follows the rhythm, firing in bursts on the beat while the allay conducts from the center, floating higher and gaining damage reduction.

5. **Fae Phase** - The allay turns translucent and ghostly, its wings leaving trails of pale cyan light as it gains the ability to phase through blocks and enemies for a short time, ignoring gravity and terrain entirely. While phased it cannot attack, but it leaves behind a trail of fairy dust particles that damages and slows enemies who cross it. It uses the phase to escape, reposition behind enemy lines, or lead chasers through walls into ambushes, then rematerializes with a chime burst.

## Axolotl

1. **Play Dead Ambush** - The axolotl flops over dramatically, belly up, with little X-shaped sparkle particles over its eyes and a faint ghostly outline rising from its body, convincing enemies it is dead. Enemies lose interest and turn away, and the moment a strong enemy passes close, the axolotl springs back to life in a burst of pink bubbles with a lunging bite that deals heavy bonus damage to targets that believed the act. Faking death also rapidly regenerates its health while the act holds.

2. **Regeneration Bloom** - The axolotl's gills flare out huge and glowing, and waves of soft pink and green healing particles pulse from its body in a blooming flower pattern, while lost "limbs" of light regrow along its silhouette. It regenerates health at a very high rate for several seconds and sheds all negative effects in little puffs of steam-like particles. Allies very close by share a portion of the healing, drawn to the glow.

3. **Mud Geyser** - The axolotl burrows in a spray of brown particles and the ground beneath its enemies begins to rumble with dust motes, then erupts: geysers of mud and water blast upward in sequence, reshaping the terrain with temporary mud mounds and puddles. Enemies hit by a geyser are launched skyward, take fall-style damage on landing, and are coated in mud that slows them and dulls their attacks. The axolotl surfaces riding the final geyser, unharmed and repositioned.

4. **Predator Snap Reflexes** - The axolotl's eyes flash and time seems to slow around it: afterimages trail its movements, its body gains a sleek streamlined glow, and faint targeting rings appear around nearby enemies. It gains a large speed and attack speed boost, its bites gain a chance to critically strike, and it automatically dodges the first attack aimed at it each few seconds with a sideways blur of bubbles. The effect makes it a duelist that dances around bigger mobs.

5. **Axolotl Swarm Pups** - The axolotl releases a cloud of glowing eggs that hatch in seconds into a swarm of tiny baby axolotls, each trailing pink sparkles, which swarm the nearest enemy in a adorable but vicious cloud. The babies nibble rapidly for small damage each, distract the target so its attacks keep missing toward them, and when they expire they pop into healing bubbles that drift back to the parent. The parent gains a small health shield while any pups remain.

## Bat

1. **Echolocation Pulse** - The bat emits expanding rings of white and pale blue sonar particles that sweep the whole area, bouncing off enemies and outlining them in glowing sonar echoes visible through walls. Revealed enemies take increased damage from the bat's pack and lose any stealth or invisibility, and the bat gains perfect tracking: bonus speed and accuracy against outlined targets. The pulses repeat every few seconds while the ability is active, painting the cave battlefield in light.

2. **Guano Storm Dive** - The bat climbs high with a spiral of dark particles, then folds its wings and dive-bombs in a screeching streak, releasing a choking cloud of brown and gray dust particles on impact that lingers and spreads. Enemies in the cloud cough and stumble: reduced speed, reduced attack accuracy, and damage over time, while the bat ricochets off the ground back into the air with a burst of leathery wing particles, ready to dive again.

3. **Vampire Swoop** - The bat's eyes glow red, its fangs visibly lengthen in a flash of crimson particles, and it swoops in a looping figure-eight pattern too erratic to hit, latching onto an enemy's back. While latched it drains health steadily, healing itself and growing slightly larger and redder with each tick, and the victim thrashes, unable to target the bat. The victim's allies can knock the bat off, ending the drain with a spray of red sparkles.

4. **Cave Darkness Shroud** - The bat shrieks and the light itself seems to die: a dome of deep black and purple particles settles over the area, extinguishing light sources visually and plunging enemies into darkness where their targeting range shrinks drastically. Inside the shroud the bat becomes nearly invisible except for faint wing glimmers, gains a big damage bonus, and can teleport short distances between shadows in puffs of dark smoke. Sunlight-style bright effects from enemies can burn the shroud away faster.

5. **Colony Swarm Call** - The bat's screech echoes and multiplies as a colony of shadowy bats pours in from cracks and darkness in a fluttering storm of wing particles and squeak notes, swirling around enemies in a living cyclone. The swarm confuses and batters enemies with dozens of tiny hits, blocks their view with flapping silhouettes, and carries the main bat aloft at the cyclone's center, granting it height and safety. When the swarm disperses, each enemy it battered is left dizzy and slowed.

