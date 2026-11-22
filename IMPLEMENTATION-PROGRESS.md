# Advanced Mobs: New Ability Implementation Progress

One entry per mob from src/main/resources/mob_configs (81 mobs). A mob is
checked off when all 5 of its designed abilities from ABILITY-IDEAS.md are
implemented behind a registered MobAbilityProvider, the framework compiles
clean twice, and the step has been pushed (Carter's Nov 21, 2026 process:
one mob at a time, verify, then push). The ability gating framework itself
(AbilitySystem, access rolls, brackets, cooldowns) landed before the first
mob and is not tracked here.

- [ ] Allay
- [ ] Armadillo
- [ ] Axolotl
- [ ] Bat
- [ ] Bee
- [x] Blaze
- [x] Bogged
- [ ] Breeze
- [ ] Camel
- [ ] Cat
- [x] Cave_Spider
- [ ] Chicken
- [ ] Cod
- [ ] Cow
- [x] Creeper
- [ ] Dolphin
- [ ] Donkey
- [x] Drowned
- [x] Elder_Guardian
- [x] Ender_Dragon
- [x] Enderman
- [x] Endermite
- [ ] Evoker
- [ ] Fox
- [ ] Frog
- [x] Ghast
- [ ] Giant
- [ ] Glow_Squid
- [ ] Goat
- [x] Guardian
- [x] Hoglin
- [ ] Horse
- [x] Husk
- [ ] Illusioner
- [ ] Iron_Golem
- [ ] Llama
- [x] Magma_Cube
- [ ] Mooshroom
- [ ] Mule
- [ ] Ocelot
- [ ] Panda
- [ ] Parrot
- [ ] Phantom
- [ ] Pig
- [x] Piglin
- [x] Piglin_Brute
- [ ] Pillager
- [ ] Polar_Bear
- [ ] Pufferfish
- [ ] Rabbit
- [ ] Ravager
- [ ] Salmon
- [ ] Sheep
- [x] Shulker
- [x] Silverfish
- [x] Skeleton
- [ ] Skeleton_Horse
- [x] Slime
- [ ] Sniffer
- [ ] Snow_Golem
- [x] Spider
- [ ] Squid
- [x] Stray
- [ ] Strider
- [ ] Sulfur_Cube
- [ ] Tadpole
- [ ] Trader_Llama
- [ ] Tropical_Fish
- [ ] Turtle
- [x] Vex
- [ ] Villager
- [ ] Vindicator
- [ ] Wandering_Trader
- [ ] Warden
- [x] Witch
- [x] Wither
- [x] Wither_Skeleton
- [ ] Wolf
- [x] Zoglin
- [x] Zombie
- [x] Zombie_Villager
