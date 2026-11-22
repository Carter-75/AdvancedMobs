package mod.advancedmobs.ability.drowned;

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
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.phys.AABB;

import java.util.UUID;

/**
 * Call of the Deep (Drowned ability 3 of 5, summon).
 *
 * WHAT IT DOES: the drowned sends out a low pressure wave through
 * the water, and two guardians answer the call, rising up beside it
 * into the fight with their spikes out. The guardians fight on the
 * drowned's side and hunt the drowned's target for as long as the
 * call lasts.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   drowned's current target within 14 blocks (distance squared
 *   196).
 * - Duration: exactly 240 ticks (12 seconds) from start().
 * - The answer (start()): 2 guardians, spawned 1.6 blocks to either
 *   side of the drowned (east and west), each at the first
 *   collision-free spot of the two (its offset, then the drowned's
 *   own position), created with EntitySpawnReason.MOB_SUMMONED and
 *   finalizeSpawn like every other summon in this mod. Each is 1
 *   vanilla guardian: 30 health, a laser that charges for 2 seconds
 *   and deals 6 damage, and a 2 damage spike retaliation. Each gets
 *   an upward velocity of 0.35 on spawn, the visible rise into the
 *   fight. Guardians are aquatic: summoned on dry land they flop
 *   and suffocate by ordinary vanilla rules, which is the accepted
 *   price of calling them out of their element.
 * - Team assignment, per guardian, both directions plus the
 *   registry: the caster's data gains the guardian as an ally, the
 *   guardian's data gains the caster, and the pair is registered in
 *   SummonerTracker (which is also what tags the guardian
 *   umw_summoned for the natural spawn blocker, the same flow
 *   Scarab Brood and Bell of the Fallen Village use). Each guardian
 *   is ordered onto the caster's current target at spawn.
 * - Babysitting every 5 ticks: each living tracked guardian whose
 *   target is empty or is the caster is re-ordered onto the
 *   caster's live target. Vanilla guardian AI only hunts players,
 *   squid, and axolotls on its own, so without this the pair would
 *   ignore the mob the drowned is fighting; with it, they stay on
 *   the drowned's fight. Nothing else is steered: the laser,
 *   movement, and spike defense are vanilla guardian behavior.
 * - end(): the alliance records are removed on both sides for every
 *   tracked guardian, and each guardian still alive is discarded in
 *   a burst of SPLASH and POOF: the answer is the ability's, and it
 *   does not outlive it. If a guardian died in the fight, end()
 *   simply finds nothing to discard for it. If the caster dies
 *   first, the framework termination path runs this same end(), so
 *   the guardians never outlive their caller either.
 *   SummonerTracker records are deliberately not unregistered here,
 *   matching Scarab Brood: that registry cleans up on the
 *   guardian's own removal.
 *
 * WHAT IT LOOKS LIKE: at start, a pressure wave of 20 UNDERWATER
 * particles rolls out from the drowned with 16 BUBBLE particles
 * churning around it, and a ring of 12 SPLASH particles breaks
 * across the surface at the drowned's height as the guardians rise.
 * The guardians themselves are plain guardians, lasers and spikes
 * doing the talking from there.
 *
 * PERFORMANCE: start() does 2 bounded collision checks and 2
 * spawns, once. tick() returns immediately except every 5th tick,
 * where it resolves at most 2 UUIDs and reads or writes targets
 * only: no entity scans, no allocations beyond the resolved
 * references. Particle budget per activation: 48 at start plus at
 * most 2 guardians times 12 at end() = 24, total at most 72
 * particles.
 *
 * State tag keys: "guardians" (list of compounds, one per guardian:
 * "id", the guardian's UUID).
 */
public final class CallOfTheDeepAbility implements MobAbility {

    static final int DURATION_TICKS = 240;
    private static final int GUARDIAN_COUNT = 2;
    private static final int BABYSIT_INTERVAL = 5;
    private static final double[][] OFFSETS = {
        {1.6, 0.0}, {-1.6, 0.0}
    };

    @Override
    public String id() {
        return "call_of_the_deep";
    }

    @Override
    public String displayName() {
        return "Call of the Deep";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            ctx.state().put("guardians", new ListTag());
            return;
        }
        Mob caster = ctx.mob();

        ListTag guardians = new ListTag();
        LivingEntity target = ctx.target();
        for (int i = 0; i < GUARDIAN_COUNT; i++) {
            Guardian guardian = EntityTypes.GUARDIAN.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (guardian == null) {
                continue;
            }
            double[] offset = OFFSETS[i];
            double spawnX = caster.getX() + offset[0];
            double spawnZ = caster.getZ() + offset[1];
            if (!isFreeSpot(level, guardian, spawnX, caster.getY(), spawnZ)) {
                spawnX = caster.getX();
                spawnZ = caster.getZ();
            }
            guardian.snapTo(spawnX, caster.getY(), spawnZ, ctx.random().nextFloat() * 360.0f, 0.0f);
            guardian.finalizeSpawn(level, level.getCurrentDifficultyAt(guardian.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
            if (!level.addFreshEntity(guardian)) {
                continue;
            }
            // The rise into the fight.
            guardian.setDeltaMovement(guardian.getDeltaMovement().x, 0.35, guardian.getDeltaMovement().z);
            guardian.hurtMarked = true;

            // Team assignment, both directions, plus the summoner registry.
            ctx.data().addAlly(guardian.getUUID());
            MobWarData.get(guardian).addAlly(caster.getUUID());
            SummonerTracker.registerSummoned(guardian.getUUID(), caster.getUUID());
            if (target != null) {
                guardian.setTarget(target);
            }
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, guardian.getUUID());
            guardians.add(entry);
        }
        ctx.state().put("guardians", guardians);

        // The pressure wave rolls out, and the surface breaks over the call.
        level.sendParticles(ParticleTypes.UNDERWATER, caster.getX(), caster.getY() + 0.8, caster.getZ(),
            20, 1.6, 0.7, 1.6, 0.12);
        level.sendParticles(ParticleTypes.BUBBLE, caster.getX(), caster.getY() + 0.6, caster.getZ(),
            16, 1.2, 0.7, 1.2, 0.1);
        level.sendParticles(ParticleTypes.SPLASH, caster.getX(), caster.getY() + 1.2, caster.getZ(),
            12, 1.8, 0.3, 1.8, 0.2);
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % BABYSIT_INTERVAL != 0) {
            return; // Cheap early-out: babysitting runs every 5 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        LivingEntity desired = ctx.target();
        ListTag guardians = ctx.state().getListOrEmpty("guardians");
        for (int i = 0; i < guardians.size(); i++) {
            Guardian guardian = resolveGuardian(level, guardians.getCompoundOrEmpty(i));
            if (guardian == null || !guardian.isAlive()) {
                continue;
            }
            LivingEntity guardianTarget = guardian.getTarget();
            if ((guardianTarget == null || guardianTarget == ctx.mob())
                && desired != null && desired != guardian) {
                guardian.setTarget(desired);
            }
        }
    }

    /** Whether the guardian's bounding box at the given feet position collides with nothing. */
    private boolean isFreeSpot(ServerLevel level, Guardian guardian, double x, double y, double z) {
        double halfWidth = guardian.getBbWidth() / 2.0;
        AABB box = new AABB(x - halfWidth, y, z - halfWidth, x + halfWidth, y + guardian.getBbHeight(), z + halfWidth);
        return level.noCollision(guardian, box);
    }

    /** Resolves a tracked guardian from its list entry, or null when gone. */
    private Guardian resolveGuardian(ServerLevel level, CompoundTag entry) {
        if (!entry.contains("id")) {
            return null;
        }
        UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
        if (id != null && level.getEntity(id) instanceof Guardian guardian) {
            return guardian;
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
        ListTag guardians = ctx.state().getListOrEmpty("guardians");
        for (int i = 0; i < guardians.size(); i++) {
            CompoundTag entry = guardians.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID guardianId = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (guardianId == null) {
                continue;
            }
            // The alliance ends with the ability, whichever side is
            // still here.
            ctx.data().removeAlly(guardianId);
            Guardian guardian = resolveGuardian(level, entry);
            if (guardian == null) {
                continue;
            }
            MobWarData.get(guardian).removeAlly(caster.getUUID());
            if (guardian.isAlive()) {
                // The answer slips back into the deep.
                level.sendParticles(ParticleTypes.SPLASH, guardian.getX(), guardian.getY() + 0.5, guardian.getZ(),
                    8, 0.5, 0.4, 0.5, 0.15);
                level.sendParticles(ParticleTypes.POOF, guardian.getX(), guardian.getY() + 0.5, guardian.getZ(),
                    4, 0.4, 0.4, 0.4, 0.05);
                guardian.discard();
            }
        }
    }
}
