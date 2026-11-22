# Advanced Mobs: New Ability Implementation Rules

Original rules set by Carter on Nov 20, 2026; scope and process updated by
Carter's orders of Nov 21, 2026, which supersede the Nov 20 versions where
they differ.

1. ALL FIVE ABILITIES PER MOB (Nov 21, 2026, supersedes the Nov 20 top-3
   picks plan): every one of the 5 designed abilities in ABILITY-IDEAS.md
   is implemented for every mob, 81 mobs, 405 abilities. There are no picks
   and no parallel-agent split for this work.

2. ONE MOB AT A TIME, VERIFY THEN PUSH (Nov 21, 2026): implement all 5
   abilities for one mob, recheck the code, verify the compile (javac
   harness against the real 26.2 jar, zero errors, twice), then push.
   Then the next mob. Track progress in IMPLEMENTATION-PROGRESS.md.
   (Gradle cannot run in the build VM; Carter knows and builds on his PC.)

3. EVERY ABILITY FULLY SPECIFIED IN CODE COMMENTS (Nov 20, 2026, in full
   force): for each ability, the code must carry comments that state
   exactly:
   - what the ability does, in plain terms
   - how it works mechanically: trigger, cooldown behavior, range, numbers,
     targets, what it spawns or changes
   - what it looks like: particles, model or render changes, sounds
   The point: bugs are expected after the first real run. The comments are
   the spec of intended behavior, so a bug can be recognized as a bug and
   fixed against the spec instead of guessed at. The framework classes
   (AbilitySystem, MobAbility) carry the system-level spec the same way.

4. THE GATING SYSTEM (Carter, Nov 21, 2026; implemented in AbilitySystem
   before the first mob):
   - Power fraction: frac = lifetime points earned / FULL_POWER, clamped
     to [0, 1]. FULL_POWER is ModConfig.abilityFullPowerPoints, default
     3000 (the summed cost of maxing the Zombie reference config's upgrade
     tracks, 2,986 rounded).
   - Access chance: normal mobs 1 percent at zero power scaling to a 25
     percent cap at full power; bosses (Ender Dragon, Wither, Warden,
     Elder Guardian, Mob Warlord) 1 percent scaling to 100 percent.
   - Accessible count grows from 1 to all 5 across the power range
     (brackets: 1 below 0.30, 2 from 0.30, 3 from 0.55, 4 from 0.75, 5 from
     0.90). Grants are random subsets, permanent, and extend on bracket
     rises; a failed roll retries only at the next bracket.
   - Ender Dragon exception: guaranteed at least 1 accessible ability from
     the very start, no access roll.
   - Firing: in combat only, one ability active at a time, random pick
     among the accessible abilities whose trigger conditions pass.
   - Cooldown: 10 to 60 seconds, weighted by power (weak mobs skew long,
     full-power mobs skew short), rolled as 60 - 50 * pow(random,
     2.5 - 2 * frac) seconds, and the clock starts when an ability ENDS,
     never when it starts.
   - Carter's halfway description (about 14 percent chance and 2 abilities
     at halfway power) was an EXAMPLE of the shape, not a required
     checkpoint. The binding rules are the endpoints (1 percent at zero
     power; 25 percent normal and 100 percent boss caps at full power),
     the 1-to-5 count growth, the 10 to 60 second cooldown range, and the
     dragon guarantee. Mid-curve values may be tuned without breaking the
     design as long as the endpoints and shape hold.

5. Base is the new-abilities branch (the stripped mod: old abilities
   removed, compile-verified). Ability state persists in MobWarData under
   am_ability_ keys only; the legacy ability_ keys stay inert.

6. TERRAIN CHANGES ARE PERMANENT AND GATED BY mobGriefing (Carter,
   Nov 21, 2026): abilities that change blocks change them permanently.
   There is no temporary placement and no restore machinery. Block
   changes only happen when the level's vanilla mobGriefing game rule
   is on (26.2 key "mob_griefing", checked through the one shared
   helper AbilityContext.mobGriefingAllowed()). When mobGriefing is
   off, an ability must not alter blocks at all; its non-terrain
   effects (damage, debuffs, particles, movement) still run normally.
   Placement safety checks (never overwrite containers or other block
   entities, never touch unbreakable blocks, never float) still apply
   in mobGriefing mode.

7. BOSS COMBAT PROGRESSION (Carter, Nov 21, 2026; implemented in
   ScalingSystem, see the Part B design comment there): while a boss
   (Ender Dragon, Wither, Warden, Elder Guardian, Mob Warlord) is in
   active combat, it earns bonus points per second into the same
   lifetime pool the spending lottery and the ability power fraction
   read, because day-based accrual can never power a boss up inside
   its own fight. Rates live in ModConfig (dragon 25/s, wither and
   warlord 15/s, warden and elder guardian 10/s by default). Non-boss
   mobs earn no combat bonus. The whole progression path must actually
   run for every mob: the Ender Dragon is hooked through its own
   aiStep (EnderDragonProgressionMixin) because its aiStep never
   reaches Mob.serverAiStep.

8. TERMINATION AND TICK SOURCE (Nov 21, 2026 audit): when a caster
   dies or is genuinely removed mid-activation, the framework runs the
   active ability's end() exactly once (AbilitySystem.
   terminateActiveAbility); a plain chunk unload is not a termination
   and the activation resumes on reload. Abilities do not need their
   own death backups for cleanup end() performs. Abilities tick from
   the mob's AI step, so setNoAi freezes them along with the rest of
   the mob's AI; that is the standing contract, and abilities must
   never use NoAi to hold themselves still.
