package mod.advancedmobs.ability.guardian;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Guardian;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Laser Lattice (Guardian ability 2 of 5, attack).
 *
 * WHAT IT DOES: while the lattice lasts, the guardian's laser is
 * more than a single beam. Whenever the vanilla beam locks onto
 * an enemy, thinner beams fork from that main target to up to two
 * more nearby enemies in a crackling lattice of ELECTRIC_SPARK
 * and BUBBLE particles, and the chained enemies burn with a share
 * of the beam's damage for as long as the main beam holds.
 * Clustered enemies burn together; breaking line of sight to the
 * main target collapses the whole lattice at once, because the
 * vanilla beam itself drops and there is nothing left to fork
 * from.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   guardian's current target within 15 blocks (distance squared
 *   225), the vanilla beam's own range: the lattice only matters
 *   inside beam fights.
 * - Duration: exactly 200 ticks (10 seconds) from start(). The
 *   lattice does not fire its own beam; it rides whatever beams
 *   the guardian's vanilla attack goal fires during the window,
 *   so an activation with no beam in it simply does nothing but
 *   wait (documented, intended: the framework picked the ability,
 *   the beam timing is the guardian's own).
 * - The main beam is read live each tick from the guardian
 *   itself: Guardian.hasActiveAttackTarget() and
 *   getActiveAttackTarget() (both verified against the 26.2 jar;
 *   this is the same target the vanilla beam renders and charges
 *   on). The lattice only ever forks from a main target that is
 *   an enemy by the package rule.
 * - Chain selection, on a 20 tick cycle and immediately whenever
 *   the main beam target changes: enemies (package rule) within
 *   a 7 block box of the main target (3 below, 3 above), not the
 *   main target itself, with line of sight from the main target
 *   (LivingEntity.hasLineOfSight), nearest first, at most 2. The
 *   chosen pair is stored by UUID in the state tag and resolved
 *   live afterwards; a chain that dies or leaves is dropped at
 *   the next selection.
 * - Chain damage: on each selection cycle (every 20 ticks), each
 *   chained enemy takes 1.0 damage typed as the guardian's mob
 *   attack (level.damageSources().mobAttack(mob)). The vanilla
 *   beam lands about 6 damage per 80 tick charge on the main
 *   target, an average of 1.5 per 20 ticks; the chains take a
 *   share of that rate, not the whole of it. Damage stops the
 *   tick the main beam drops: no active main target, no chains,
 *   no damage.
 * - The collapse: because chain selection and damage are both
 *   gated on the live vanilla beam target, losing line of sight
 *   to the main target ends the lattice's effect the same tick
 *   the guardian's own beam goal gives up on it. Nothing needs
 *   cleaning up when that happens; the stored chains just stop
 *   resolving to a live beam.
 *
 * WHAT IT LOOKS LIKE: forked beams drawn as particle lines from
 * the main target's center to each chained enemy's center, drawn
 * every 4 ticks: ELECTRIC_SPARK at the third and two-third points
 * of each line and BUBBLE at the chained enemy, crackling and
 * fizzing in the water. The main beam itself is the vanilla
 * guardian laser, unchanged.
 *
 * PERFORMANCE: the per-tick cost is one beam-target read on the
 * guardian (a synched-data getter, no scan). Selection runs one
 * bounded entity query (14 by 6 by 14 box around the main target)
 * at most once per 20 ticks and sorts a handful of results;
 * damage rides the same cycle. Particles run every 4 ticks and
 * resolve at most 2 stored UUIDs. Particle budget per
 * activation: at most 50 drawing pulses times 2 chains times 4
 * particles = 400, plus 6 ELECTRIC_SPARK at start, total at most
 * 406 particles. No attribute modifiers, no effects, no spawned
 * entities: end() has nothing to undo.
 *
 * State tag keys: "main" (UUID of the beam target the chains were
 * picked for), "chains" (list of compounds, one per chained
 * enemy: "id", the enemy's UUID).
 */
public final class LaserLatticeAbility implements MobAbility {

    static final int DURATION_TICKS = 200;
    private static final int SELECT_INTERVAL = 20;
    private static final int DRAW_INTERVAL = 4;
    private static final int MAX_CHAINS = 2;
    private static final float CHAIN_DAMAGE = 1.0f;

    @Override
    public String id() {
        return "laser_lattice";
    }

    @Override
    public String displayName() {
        return "Laser Lattice";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return ctx.target() != null && ctx.mob().distanceToSqr(ctx.target()) <= 225.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        // No beam is read here: start() only marks the state tag
        // empty (no main target seen yet) and plays the opening
        // crackle. The first tick's selection does the real work.
        ctx.state().putBoolean("has_main", false);
        ServerLevel level = ctx.serverLevel();
        Mob mob = ctx.mob();
        if (level != null) {
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, mob.getX(), mob.getY() + 0.7, mob.getZ(),
                6, 0.5, 0.4, 0.5, 0.15);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (!(ctx.mob() instanceof Guardian guardian)) {
            return; // The provider is keyed to the Guardian config; anything else has no beam to ride.
        }
        LivingEntity beamTarget = guardian.hasActiveAttackTarget() ? guardian.getActiveAttackTarget() : null;
        if (beamTarget != null && !GuardianAbilitySupport.isEnemy(ctx, beamTarget)) {
            beamTarget = null; // The lattice only forks onto enemies.
        }

        int ticks = ctx.ticksActive();
        boolean selectTick = ticks % SELECT_INTERVAL == 0;
        boolean mainChanged = beamTarget != null
            && (!ctx.state().getBooleanOr("has_main", false)
                || !beamTarget.getUUID().equals(readMain(ctx)));
        if (selectTick || mainChanged) {
            selectChains(ctx, beamTarget);
        }
        if (beamTarget == null) {
            return; // No beam, no lattice: the collapse rule.
        }
        if (ticks % DRAW_INTERVAL == 0) {
            drawForks(ctx, beamTarget);
        }
    }

    /**
     * Picks (or clears) the chained enemies for the current beam
     * target and applies this cycle's chain damage. Runs on the
     * 20 tick cycle and whenever the beam target changes.
     */
    private void selectChains(AbilityContext ctx, LivingEntity beamTarget) {
        CompoundTag state = ctx.state();
        if (beamTarget == null) {
            state.putBoolean("has_main", false);
            state.remove("chains");
            return;
        }
        state.store("main", UUIDUtil.CODEC, beamTarget.getUUID());
        state.putBoolean("has_main", true);

        // Candidates: enemies near the main target that the main
        // target can see, nearest first, at most MAX_CHAINS.
        List<LivingEntity> candidates = GuardianAbilitySupport.enemiesInBox(ctx,
            beamTarget.getX(), beamTarget.getY(), beamTarget.getZ(), 7.0, 3.0, 3.0);
        candidates.remove(beamTarget);
        candidates.removeIf(candidate -> !beamTarget.hasLineOfSight(candidate));
        candidates.sort((a, b) -> Double.compare(
            a.distanceToSqr(beamTarget), b.distanceToSqr(beamTarget)));

        ServerLevel level = ctx.serverLevel();
        net.minecraft.nbt.ListTag chains = new net.minecraft.nbt.ListTag();
        int picked = 0;
        for (LivingEntity candidate : candidates) {
            if (picked >= MAX_CHAINS) {
                break;
            }
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, candidate.getUUID());
            chains.add(entry);
            picked++;
            // The share of the beam: chained enemies burn while
            // the main beam holds.
            if (level != null) {
                candidate.hurtServer(level, level.damageSources().mobAttack(ctx.mob()), CHAIN_DAMAGE);
            }
        }
        state.put("chains", chains);
    }

    /** Draws the forked beams as particle lines from the main target to each live chain. */
    private void drawForks(AbilityContext ctx, LivingEntity beamTarget) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        double x0 = beamTarget.getX(), y0 = beamTarget.getY() + 0.8, z0 = beamTarget.getZ();
        for (LivingEntity chain : resolveChains(ctx, level)) {
            double x1 = chain.getX(), y1 = chain.getY() + 0.8, z1 = chain.getZ();
            for (double t : new double[]{1.0 / 3.0, 2.0 / 3.0}) {
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                    x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, z0 + (z1 - z0) * t,
                    1, 0.05, 0.05, 0.05, 0.02);
            }
            level.sendParticles(ParticleTypes.BUBBLE, x1, y1, z1, 2, 0.3, 0.3, 0.3, 0.05);
        }
    }

    /** Resolves the stored chain UUIDs to live entities, skipping the dead and the gone. */
    private List<LivingEntity> resolveChains(AbilityContext ctx, ServerLevel level) {
        List<LivingEntity> resolved = new ArrayList<>();
        net.minecraft.nbt.ListTag chains = ctx.state().getListOrEmpty("chains");
        for (int i = 0; i < chains.size(); i++) {
            CompoundTag entry = chains.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (id != null && level.getEntity(id) instanceof LivingEntity living && living.isAlive()) {
                resolved.add(living);
            }
        }
        return resolved;
    }

    /** The stored main beam target UUID, or null when none was stored. */
    private UUID readMain(AbilityContext ctx) {
        return ctx.state().read("main", UUIDUtil.CODEC).orElse(null);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to undo: no modifiers, no effects, no spawns.
        // The forked beams were particles and damage only.
    }
}
