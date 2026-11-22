package mod.advancedmobs.ability.husk;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.UUID;

/**
 * Scarab Brood (Husk ability 5 of 5, summon).
 *
 * WHAT IT DOES: the husk shakes itself apart at the seams and a
 * skittering brood of 5 silverfish bursts out of it in a spray of
 * sand, desert scarabs pouring onto the field on the husk's side.
 * The brood swarms the husk's current target from every side, and
 * for the duration the husk itself carries INFESTED, so every hit it
 * takes risks shaking loose even more scarabs.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   husk's current target within 12 blocks (distance squared 144).
 * - Duration: exactly 200 ticks (10 seconds) from start().
 * - The brood (start()): 5 silverfish, spawned one per offset around
 *   the husk (1.2 blocks east, west, south, north, and a diagonal),
 *   each at the first collision-free spot in that offset then the
 *   husk's own position as fallback, created with
 *   EntitySpawnReason.MOB_SUMMONED and finalizeSpawn like every
 *   other summon in this mod. Each scarab is 1 vanilla silverfish:
 *   8 health, 1 damage per bite; the threat is the swarm arriving
 *   from five directions at once, not any single scarab.
 * - Team assignment, per scarab, both directions plus the registry:
 *   the caster's data gains the scarab as an ally, the scarab's data
 *   gains the caster, and the pair is registered in SummonerTracker
 *   (which is also what tags the scarab umw_summoned for the natural
 *   spawn blocker, the same flow Bell of the Fallen Village uses).
 *   Each scarab is ordered onto the caster's current target at spawn.
 * - Babysitting every 5 ticks: each living tracked scarab whose
 *   target is empty or is the caster is re-ordered onto the caster's
 *   live target. Silverfish AI only hunts players on its own, so
 *   without this the brood would ignore the mob the husk is fighting;
 *   with it, the swarm stays on the husk's fight. Nothing else is
 *   steered: damage, movement, and burrowing into stone are vanilla
 *   silverfish behavior. (A scarab that burrows into a stone block
 *   mid-ability is inside the block as far as the level is concerned
 *   and resolves as gone; end() then just finds nothing to discard,
 *   and the block stays infested as ordinary terrain.)
 * - INFESTED on the husk: amplifier 0 for 200 ticks, applied in
 *   start() and removed in end() (the brood's infestation is the
 *   ability's own; a husk that somehow had INFESTED from another
 *   source would lose it at end(), an accepted edge case shared with
 *   every fixed-duration effect in this system). While it lasts,
 *   vanilla INFESTED behavior applies: every time the husk is hurt
 *   there is a 10 percent chance to spawn 1 to 3 silverfish at its
 *   position. Those extra scarabs are vanilla spawns: hostile to
 *   whatever hurt the husk, not tracked, not allied, and not cleaned
 *   up by this ability. Only the 5 summoned brood scarabs are the
 *   ability's own.
 * - end(): the INFESTED is removed from the husk, the alliance
 *   records are removed on both sides for every tracked scarab, and
 *   each scarab still alive is discarded in a puff of POOF and sand
 *   BLOCK particles: the brood is the ability's, and it does not
 *   outlive it. If a scarab died in the fight, end() simply finds
 *   nothing to discard for it. If the caster dies first, the
 *   framework termination path runs this same end(), so the brood
 *   never outlives its summoner either. SummonerTracker records are
 *   deliberately not unregistered here, matching Bell of the Fallen
 *   Village: that registry cleans up on the scarab's own removal.
 *
 * WHAT IT LOOKS LIKE: at start, 24 sand BLOCK particles spray out of
 * the husk's body with 6 POOF puffs as the seams split. The brood is
 * plain silverfish (they read as scarabs by context: pouring out of
 * a husk in the desert), skittering at the target from five sides.
 * While INFESTED lasts, the husk shows the vanilla INFESTED effect
 * sparkles.
 *
 * PERFORMANCE: start() does 5 bounded collision checks and 5 spawns,
 * once. tick() returns immediately except every 5th tick, where it
 * resolves at most 5 UUIDs and reads or writes targets only: no
 * entity scans, no allocations beyond the resolved references.
 * Particle budget per activation: 30 at start plus at most 5
 * scarabs times 10 at end() = 50, total at most 80 particles.
 *
 * State tag keys: "brood" (list of compounds, one per scarab: "id",
 * the scarab's UUID).
 */
public final class ScarabBroodAbility implements MobAbility {

    static final int DURATION_TICKS = 200;
    private static final int BROOD_COUNT = 5;
    private static final int BABYSIT_INTERVAL = 5;
    private static final BlockParticleOption SAND_BLOCK =
        new BlockParticleOption(ParticleTypes.BLOCK, Blocks.SAND.defaultBlockState());
    private static final double[][] OFFSETS = {
        {1.2, 0.0}, {-1.2, 0.0}, {0.0, 1.2}, {0.0, -1.2}, {0.85, 0.85}
    };

    @Override
    public String id() {
        return "scarab_brood";
    }

    @Override
    public String displayName() {
        return "Scarab Brood";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            ctx.state().put("brood", new ListTag());
            return;
        }
        Mob caster = ctx.mob();

        // The husk carries the brood's infestation for the duration.
        caster.addEffect(new MobEffectInstance(MobEffects.INFESTED, DURATION_TICKS, 0));

        ListTag brood = new ListTag();
        LivingEntity target = ctx.target();
        for (int i = 0; i < BROOD_COUNT; i++) {
            Silverfish scarab = EntityTypes.SILVERFISH.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (scarab == null) {
                continue;
            }
            double[] offset = OFFSETS[i];
            double spawnX = caster.getX() + offset[0];
            double spawnZ = caster.getZ() + offset[1];
            if (!isFreeSpot(level, scarab, spawnX, caster.getY(), spawnZ)) {
                spawnX = caster.getX();
                spawnZ = caster.getZ();
            }
            scarab.snapTo(spawnX, caster.getY(), spawnZ, ctx.random().nextFloat() * 360.0f, 0.0f);
            scarab.finalizeSpawn(level, level.getCurrentDifficultyAt(scarab.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
            if (!level.addFreshEntity(scarab)) {
                continue;
            }

            // Team assignment, both directions, plus the summoner registry.
            ctx.data().addAlly(scarab.getUUID());
            MobWarData.get(scarab).addAlly(caster.getUUID());
            SummonerTracker.registerSummoned(scarab.getUUID(), caster.getUUID());
            if (target != null) {
                scarab.setTarget(target);
            }
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, scarab.getUUID());
            brood.add(entry);
        }
        ctx.state().put("brood", brood);

        // The seams split: a spray of sand and dust out of the husk's body.
        level.sendParticles(SAND_BLOCK, caster.getX(), caster.getY() + 0.9, caster.getZ(),
            24, 0.5, 0.6, 0.5, 0.15);
        level.sendParticles(ParticleTypes.POOF, caster.getX(), caster.getY() + 0.9, caster.getZ(),
            6, 0.4, 0.5, 0.4, 0.05);
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
        ListTag brood = ctx.state().getListOrEmpty("brood");
        for (int i = 0; i < brood.size(); i++) {
            Silverfish scarab = resolveScarab(level, brood.getCompoundOrEmpty(i));
            if (scarab == null || !scarab.isAlive()) {
                continue;
            }
            LivingEntity scarabTarget = scarab.getTarget();
            if ((scarabTarget == null || scarabTarget == ctx.mob())
                && desired != null && desired != scarab) {
                scarab.setTarget(desired);
            }
        }
    }

    /** Whether the scarab's bounding box at the given feet position collides with nothing. */
    private boolean isFreeSpot(ServerLevel level, Silverfish scarab, double x, double y, double z) {
        double halfWidth = scarab.getBbWidth() / 2.0;
        AABB box = new AABB(x - halfWidth, y, z - halfWidth, x + halfWidth, y + scarab.getBbHeight(), z + halfWidth);
        return level.noCollision(scarab, box);
    }

    /** Resolves a tracked scarab from its brood entry, or null when gone. */
    private Silverfish resolveScarab(ServerLevel level, CompoundTag entry) {
        if (!entry.contains("id")) {
            return null;
        }
        UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
        if (id != null && level.getEntity(id) instanceof Silverfish scarab) {
            return scarab;
        }
        return null;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        // The infestation ends with the brood.
        caster.removeEffect(MobEffects.INFESTED);

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag brood = ctx.state().getListOrEmpty("brood");
        for (int i = 0; i < brood.size(); i++) {
            CompoundTag entry = brood.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID scarabId = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (scarabId == null) {
                continue;
            }
            // The alliance ends with the ability, whichever side is
            // still here.
            ctx.data().removeAlly(scarabId);
            Silverfish scarab = resolveScarab(level, entry);
            if (scarab == null) {
                continue;
            }
            MobWarData.get(scarab).removeAlly(caster.getUUID());
            if (scarab.isAlive()) {
                // The brood crumbles back into the sand it burst from.
                level.sendParticles(ParticleTypes.POOF, scarab.getX(), scarab.getY() + 0.2, scarab.getZ(),
                    6, 0.25, 0.2, 0.25, 0.05);
                level.sendParticles(SAND_BLOCK, scarab.getX(), scarab.getY() + 0.2, scarab.getZ(),
                    4, 0.25, 0.2, 0.25, 0.1);
                scarab.discard();
            }
        }
    }
}
