# Advanced Mobs: New Ability Implementation Rules

Set by Carter on Nov 20, 2026, to be followed when he sends his top 3 picks per mob from ABILITY-IDEAS.md.

1. PARALLEL AGENTS: implement with multiple agents working at the same time, split by mob groups, to speed the build up.

2. EVERY ABILITY FULLY SPECIFIED IN CODE COMMENTS: for each of the 3 abilities on each mob (243 total), the code must carry comments that state exactly:
   - what the ability does, in plain terms
   - how it works mechanically: trigger, cooldown, range, numbers, targets, what it spawns or changes
   - what it looks like: particles, model or render changes, sounds
   The point: bugs are expected after the first real run. The comments are the spec of intended behavior, so a bug can be recognized as a bug and fixed against the spec instead of guessed at.

3. Base is the local strip-abilities branch (old abilities already removed, compile-verified). The strip stays unpushed until Carter says otherwise.
