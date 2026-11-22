package mod.advancedmobs.ability.cat;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.feline.Cat;

/**
 * Alley Cat Swarm (Cat ability 4 of 5, summon).
 *
 * WHAT IT DOES: one yowl and the alley answers. A gang of
 * small, fast cats pours in from the edges of the fight,
 * swarms the original cat's enemy, draws its attacks, and
 * rakes it for chip damage, while the original cat circles
 * at pounce range looking for its opening.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the cat's current target within 16 blocks
 *   (distance squared 256).
 * - The call (start()): the original cat is marked with
 *   the scoreboard tag "advancedmobs_alley_original" (the
 *   design's tag to mark the original; removed in end())
 *   and yowls: its ambient sound at volume 1.5, pitch 1.3.
 *   Four gang cats are summoned at radius 6.0 around the
 *   caster on the four diagonals (45, 135, 225, 315
 *   degrees), each landing height from one bounded surface
 *   scan: the edges they pour in from. Each is a baby cat
 *   (age -24000) spawned with MOB_SUMMONED and
 *   finalizeSpawn, with two of the design's summon
 *   modifiers carried as PERMANENT base values on the new
 *   mob (the Warren Call and Herd Sovereign precedent):
 *   SCALE base 0.8 (small) and MOVEMENT_SPEED base 0.45
 *   (fast; vanilla cat base is 0.3). Each gang cat is
 *   allied with the caster in both directions in MobWarData
 *   and registered in SummonerTracker, is ordered onto the
 *   caster's current target, and is tracked in the state
 *   tag. 6 POOF motes burst at each arrival.
 * - The swarm (tick(), babysit every 5 ticks): each living
 *   gang cat is retargeted onto the caster's live target
 *   and navigation-driven at speed 1.4 while it is more
 *   than 1.5 blocks away. In reach (1.5 blocks, distance
 *   squared 2.25), a gang cat rakes the target for 1.0
 *   damage through a mob attack source credited to that
 *   gang cat, at most once per gang cat per 15 ticks (the
 *   per-cat clock is stored in the state tag by gang list
 *   index). The gang draws fire on its own: nothing forces
 *   the enemy onto them, but four extra attackers at its
 *   feet are targets its AI can pick, which is the design's
 *   draw.
 * - The circling original (tick(), every 10 ticks): while
 *   the target is more than 4 blocks away the original cat
 *   navigation-drives toward it at speed 1.1; inside 4
 *   blocks it stops and holds at pounce range, circling
 *   for the opening the design describes (its own Pounce
 *   Assassin is a separate ability and fires on its own
 *   cooldown after this one ends).
 * - Duration: exactly 300 ticks (15 seconds), ending early
 *   when the whole gang is confirmed dead or gone (the
 *   babysit sets the "gang_gone" flag).
 * - end(): the original's tag is removed; every surviving
 *   gang cat is dismissed in 4 POOF motes and discarded,
 *   and the ally records are removed in both directions.
 *   The gang is temporary backup, not a permanent family:
 *   unlike the Warren Call's rabbits, alley cats answer a
 *   yowl, fight one fight, and scatter. Idempotent.
 *
 * WHAT IT LOOKS LIKE: a yowl, four puffs of dust at the
 * edges of the fight, and four half-sized cats streaking in
 * from four directions at once, all claws on one target,
 * while the cat that called them pads a slow circle just
 * out of reach.
 *
 * PERFORMANCE: start() is 4 spawns, 4 surface scans of at
 * most 12 block reads, and 4 bursts. tick() returns
 * immediately except every 5th tick (babysit: resolves at
 * most 4 UUIDs, retargets, drives, and runs at most 4
 * distance checks and 4 damage calls) and every 10th tick
 * (the circling drive: one distance check). Particle
 * budget per activation: 4 times 6 at start = 24, end at
 * most 4 times 4 = 16, total at most 40 particles.
 *
 * TERRAIN: this ability changes no blocks; the
 * mobGriefing gate does not apply.
 *
 * State tag keys: "gang" (list of compounds, one per gang
 * cat: "id", its UUID), "rake_0" through "rake_3" (the
 * next tick each gang cat may rake again, by gang list
 * index), "gang_gone" (1 once the whole gang is confirmed
 * dead or gone).
 */
public final class AlleyCatSwarmAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int BABYSIT_INTERVAL = 5;
    private static final int CIRCLE_INTERVAL = 10;
    private static final int GANG_COUNT = 4;
    private static final double SPAWN_RADIUS = 6.0;
    private static final double RAKE_RANGE_SQR = 2.25; // 1.5 blocks
    private static final double RAKE_DAMAGE = 1.0;
    private static final int RAKE_COOLDOWN_TICKS = 15;
    private static final String ORIGINAL_TAG = "advancedmobs_alley_original";

    @Override
    public String id() {
        return "alley_cat_swarm";
    }

    @Override
    public String displayName() {
        return "Alley Cat Swarm";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            ctx.state().put("gang", new ListTag());
            return;
        }
        Mob caster = ctx.mob();
        // The design's tag to mark the original.
        caster.addTag(ORIGINAL_TAG);
        CatAbilitySupport.playCatSound(level, caster.getX(), caster.getY(), caster.getZ(),
            CatAbilitySupport.CatSound.AMBIENT, 1.5f, 1.3f);

        LivingEntity target = ctx.target();
        for (int i = 0; i < GANG_COUNT; i++) {
            Cat gangCat = EntityTypes.CAT.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (gangCat == null) {
                continue;
            }
            double angle = Math.toRadians(45.0 + 90.0 * i);
            double spawnX = caster.getX() + Math.cos(angle) * SPAWN_RADIUS;
            double spawnZ = caster.getZ() + Math.sin(angle) * SPAWN_RADIUS;
            double spawnY = CatAbilitySupport.findGroundY(level, spawnX, caster.getY() + 1.0, spawnZ);
            gangCat.snapTo(spawnX, spawnY, spawnZ, ctx.random().nextFloat() * 360.0f, 0.0f);
            gangCat.setAge(-24000); // A baby: half-grown alley muscle.
            gangCat.finalizeSpawn(level, level.getCurrentDifficultyAt(gangCat.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
            if (!level.addFreshEntity(gangCat)) {
                continue;
            }
            // The design's summon modifiers, carried as base
            // values on the new mob (small and fast).
            if (gangCat.getAttribute(Attributes.SCALE) != null) {
                gangCat.getAttribute(Attributes.SCALE).setBaseValue(0.8);
            }
            if (gangCat.getAttribute(Attributes.MOVEMENT_SPEED) != null) {
                gangCat.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.45);
            }
            // Team assignment, both directions, plus the summoner registry.
            ctx.data().addAlly(gangCat.getUUID());
            MobWarData.get(gangCat).addAlly(caster.getUUID());
            SummonerTracker.registerSummoned(gangCat.getUUID(), caster.getUUID());
            if (target != null) {
                gangCat.setTarget(target);
            }
            level.sendParticles(ParticleTypes.POOF, spawnX, spawnY + 0.4, spawnZ,
                6, 0.3, 0.3, 0.3, 0.05);
            CatAbilitySupport.trackEntity(ctx.state(), "gang", gangCat);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks % BABYSIT_INTERVAL == 0) {
            babysit(ctx, ticks);
        }
        if (ticks % CIRCLE_INTERVAL == 0) {
            circle(ctx);
        }
    }

    /** Retargets, drives, and rakes with the gang, every 5 ticks. */
    private void babysit(AbilityContext ctx, int ticks) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        LivingEntity desired = ctx.target();
        ListTag gang = ctx.state().getListOrEmpty("gang");
        boolean anyAlive = false;
        for (int i = 0; i < gang.size(); i++) {
            if (!(CatAbilitySupport.resolveTracked(level, gang.getCompoundOrEmpty(i)) instanceof Cat gangCat)
                || !gangCat.isAlive()) {
                continue;
            }
            anyAlive = true;
            if (desired == null) {
                continue;
            }
            if (gangCat.getTarget() != desired) {
                gangCat.setTarget(desired);
            }
            if (gangCat.distanceToSqr(desired) > RAKE_RANGE_SQR) {
                gangCat.getNavigation().moveTo(desired, 1.4);
                continue;
            }
            // The rake: chip damage at the shared target's feet.
            String rakeKey = "rake_" + i;
            if (ticks >= ctx.state().getIntOr(rakeKey, 0)) {
                ctx.state().putInt(rakeKey, ticks + RAKE_COOLDOWN_TICKS);
                desired.hurtServer(level, level.damageSources().mobAttack(gangCat), (float) RAKE_DAMAGE);
            }
        }
        if (!anyAlive && ticks >= BABYSIT_INTERVAL) {
            ctx.state().putInt("gang_gone", 1);
        }
    }

    /** The original circles at pounce range, every 10 ticks. */
    private void circle(AbilityContext ctx) {
        Mob caster = ctx.mob();
        LivingEntity target = ctx.target();
        if (target == null) {
            return;
        }
        if (caster.distanceToSqr(target) > 16.0) {
            caster.getNavigation().moveTo(target, 1.1);
        } else {
            caster.getNavigation().stop();
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS || ctx.state().getIntOr("gang_gone", 0) == 1;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.removeTag(ORIGINAL_TAG);
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        // The gang scatters: survivors dismissed, ally records cleaned both sides.
        ListTag gang = ctx.state().getListOrEmpty("gang");
        for (int i = 0; i < gang.size(); i++) {
            if (!(CatAbilitySupport.resolveTracked(level, gang.getCompoundOrEmpty(i)) instanceof Cat gangCat)
                || !gangCat.isAlive()) {
                continue;
            }
            ctx.data().removeAlly(gangCat.getUUID());
            MobWarData.get(gangCat).removeAlly(caster.getUUID());
            level.sendParticles(ParticleTypes.POOF, gangCat.getX(), gangCat.getY() + 0.4, gangCat.getZ(),
                4, 0.3, 0.3, 0.3, 0.05);
            gangCat.discard();
        }
    }
}
