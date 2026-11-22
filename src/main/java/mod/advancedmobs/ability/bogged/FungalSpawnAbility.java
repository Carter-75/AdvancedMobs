package mod.advancedmobs.ability.bogged;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.phys.AABB;

import java.util.UUID;

/**
 * Fungal Spawn (Bogged ability 2 of 5, summon).
 *
 * WHAT IT DOES: the lumps on the bogged's back swell and split
 * open, and two small slimes ooze out of it, fungal minions that
 * bounce straight into the bogged's fight on the bogged's side.
 * While the spawning runs, the parent sheds FALLING_SPORE_BLOSSOM
 * motes over the ground around it, marking the birth patch as
 * claimed ground.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   bogged's current target within 16 blocks (distance squared
 *   256).
 * - Duration: exactly 200 ticks (10 seconds) from start().
 * - The spawning (ticks 1 to 40): the first slime oozes out at
 *   tick 10 on the bogged's left, the second at tick 25 on its
 *   right (offsets 1.1 blocks to either side, or the bogged's own
 *   position when that spot is not collision-free), each announced
 *   by a 10 particle MYCELIUM burst at the birth spot. Each slime
 *   is a vanilla small slime, size 2 (setSize(2, true)): 4 health,
 *   2 damage per bounce-hit, created with
 *   EntitySpawnReason.MOB_SUMMONED and finalizeSpawn like every
 *   other summon in this mod. The threat is two extra bodies
 *   body-blocking and nibbling the target, not any single slime.
 * - Team assignment, per slime, both directions plus the
 *   registry: the caster's data gains the slime as an ally, the
 *   slime's data gains the caster, and the pair is registered in
 *   SummonerTracker (which is also what tags the slime
 *   umw_summoned for the natural spawn blocker, the same flow
 *   Scarab Brood and Bell of the Fallen Village use). Each slime
 *   is ordered onto the caster's current target at spawn.
 * - Babysitting every 5 ticks: each living tracked slime whose
 *   target is empty or is the caster is re-ordered onto the
 *   caster's live target, so the pair stays on the bogged's fight
 *   instead of wandering off after whatever a slime's own AI
 *   fancies. Nothing else is steered: bouncing, splitting on
 *   death, and damage are vanilla slime behavior. (A size 2 slime
 *   that dies splits into size 1 minis by vanilla rules; those
 *   minis are vanilla spawns, not tracked, not allied, and not
 *   cleaned up by this ability.)
 * - end(): the alliance records are removed on both sides for
 *   every tracked slime, and each slime still alive is discarded
 *   in a puff of 6 MYCELIUM and 4 POOF: the minions are the
 *   ability's, and they do not outlive it. If a slime died in the
 *   fight, end() simply finds nothing to discard for it. If the
 *   caster dies first, the framework termination path runs this
 *   same end(), so the minions never outlive their summoner
 *   either. SummonerTracker records are deliberately not
 *   unregistered here, matching Scarab Brood: that registry
 *   cleans up on the slime's own removal.
 *
 * WHAT IT LOOKS LIKE: during the 40 tick spawning the bogged
 * sheds 2 FALLING_SPORE_BLOSSOM motes every 4 ticks, drifting
 * down over the birth patch, and each birth lands with a damp
 * MYCELIUM puff. The minions are plain green slimes; they read
 * as fungal by context, oozing out of a mushroom-covered skeleton
 * in a swamp.
 *
 * PERFORMANCE: the spawn phase is 2 bounded collision checks and
 * 2 spawns, once each. tick() returns immediately except every
 * 4th tick during the spawning (2 particles) and every 5th tick
 * (babysitting: at most 2 UUID resolves and target reads). No
 * entity scans, no allocations beyond the resolved references.
 * Particle budget per activation: motes at most 10 pulses times
 * 2 = 20, births 2 times 10 = 20, end() at most 2 times 10 = 20,
 * total at most 60 particles.
 *
 * State tag keys: "minions" (list of compounds, one per slime:
 * "id", the slime's UUID), "spawned" (how many slimes have
 * oozed out so far, 0 to 2).
 */
public final class FungalSpawnAbility implements MobAbility {

    static final int DURATION_TICKS = 200;
    private static final int FIRST_SPAWN_TICK = 10;
    private static final int SECOND_SPAWN_TICK = 25;
    private static final int SPAWN_PHASE_TICKS = 40;
    private static final int BABYSIT_INTERVAL = 5;
    private static final int SLIME_SIZE = 2;
    private static final double[][] OFFSETS = {
        {-1.1, 0.3}, {1.1, 0.3}
    };

    @Override
    public String id() {
        return "fungal_spawn";
    }

    @Override
    public String displayName() {
        return "Fungal Spawn";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().put("minions", new ListTag());
        ctx.state().putInt("spawned", 0);
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();

        // The births, and the spore motes the parent sheds while
        // the spawning runs.
        int spawned = state.getIntOr("spawned", 0);
        if (ticks == FIRST_SPAWN_TICK && spawned == 0) {
            spawnSlime(ctx, level, 0);
        } else if (ticks == SECOND_SPAWN_TICK && spawned == 1) {
            spawnSlime(ctx, level, 1);
        }
        if (ticks <= SPAWN_PHASE_TICKS && ticks % 4 == 0) {
            Mob caster = ctx.mob();
            level.sendParticles(ParticleTypes.FALLING_SPORE_BLOSSOM,
                caster.getX(), caster.getY() + 1.6, caster.getZ(),
                2, 0.7, 0.5, 0.7, 0.02);
        }

        if (ticks % BABYSIT_INTERVAL != 0) {
            return; // Cheap early-out: babysitting runs every 5 ticks only.
        }
        LivingEntity desired = ctx.target();
        ListTag minions = state.getListOrEmpty("minions");
        for (int i = 0; i < minions.size(); i++) {
            Slime slime = resolveSlime(level, minions.getCompoundOrEmpty(i));
            if (slime == null || !slime.isAlive()) {
                continue;
            }
            LivingEntity slimeTarget = slime.getTarget();
            if ((slimeTarget == null || slimeTarget == ctx.mob())
                && desired != null && desired != slime) {
                slime.setTarget(desired);
            }
        }
    }

    /** Oozes one fungal slime out of the bogged at the given offset slot and teams it up. */
    private void spawnSlime(AbilityContext ctx, ServerLevel level, int slot) {
        Mob caster = ctx.mob();
        Slime slime = EntityTypes.SLIME.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (slime == null) {
            ctx.state().putInt("spawned", slot + 1);
            return;
        }
        slime.setSize(SLIME_SIZE, true);
        double[] offset = OFFSETS[slot];
        double spawnX = caster.getX() + offset[0];
        double spawnZ = caster.getZ() + offset[1];
        if (!isFreeSpot(level, slime, spawnX, caster.getY(), spawnZ)) {
            spawnX = caster.getX();
            spawnZ = caster.getZ();
        }
        slime.snapTo(spawnX, caster.getY(), spawnZ, ctx.random().nextFloat() * 360.0f, 0.0f);
        slime.finalizeSpawn(level, level.getCurrentDifficultyAt(slime.blockPosition()),
            EntitySpawnReason.MOB_SUMMONED, null);
        if (!level.addFreshEntity(slime)) {
            ctx.state().putInt("spawned", slot + 1);
            return;
        }

        // Team assignment, both directions, plus the summoner registry.
        ctx.data().addAlly(slime.getUUID());
        MobWarData.get(slime).addAlly(caster.getUUID());
        SummonerTracker.registerSummoned(slime.getUUID(), caster.getUUID());
        LivingEntity target = ctx.target();
        if (target != null) {
            slime.setTarget(target);
        }
        CompoundTag entry = new CompoundTag();
        entry.store("id", UUIDUtil.CODEC, slime.getUUID());
        ListTag minions = ctx.state().getListOrEmpty("minions");
        minions.add(entry);
        ctx.state().put("minions", minions);
        ctx.state().putInt("spawned", slot + 1);

        // The lump splits: a damp mycelium puff at the birth spot.
        level.sendParticles(ParticleTypes.MYCELIUM, spawnX, caster.getY() + 0.6, spawnZ,
            10, 0.4, 0.4, 0.4, 0.05);
    }

    /** Whether the slime's bounding box at the given feet position collides with nothing. */
    private boolean isFreeSpot(ServerLevel level, Slime slime, double x, double y, double z) {
        double halfWidth = slime.getBbWidth() / 2.0;
        AABB box = new AABB(x - halfWidth, y, z - halfWidth, x + halfWidth, y + slime.getBbHeight(), z + halfWidth);
        return level.noCollision(slime, box);
    }

    /** Resolves a tracked slime from its minion entry, or null when gone. */
    private Slime resolveSlime(ServerLevel level, CompoundTag entry) {
        if (!entry.contains("id")) {
            return null;
        }
        UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
        if (id != null && level.getEntity(id) instanceof Slime slime) {
            return slime;
        }
        return null;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        ListTag minions = ctx.state().getListOrEmpty("minions");
        for (int i = 0; i < minions.size(); i++) {
            CompoundTag entry = minions.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID slimeId = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (slimeId == null) {
                continue;
            }
            // The alliance ends with the ability, whichever side is
            // still here.
            ctx.data().removeAlly(slimeId);
            Slime slime = resolveSlime(level, entry);
            if (slime == null) {
                continue;
            }
            MobWarData.get(slime).removeAlly(caster.getUUID());
            if (slime.isAlive()) {
                // The minion melts back into the bog.
                level.sendParticles(ParticleTypes.MYCELIUM, slime.getX(), slime.getY() + 0.3, slime.getZ(),
                    6, 0.3, 0.25, 0.3, 0.05);
                level.sendParticles(ParticleTypes.POOF, slime.getX(), slime.getY() + 0.3, slime.getZ(),
                    4, 0.25, 0.2, 0.25, 0.04);
                slime.discard();
            }
        }
    }
}
