package mod.advancedmobs.ability.creeper;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.util.TickScheduler;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;

/**
 * Cluster Detonation (Creeper ability 1 of 5, attack).
 *
 * WHAT IT DOES: instead of one blast, the creeper cooks off like a
 * string of charges. Its own primary explosion goes off first, and
 * a heartbeat later four secondary pockets of gunpowder seeded
 * around the blast cook off in sequence, each popping with its own
 * flash. Enemies who dodge the first blast get caught by the
 * rolling follow ups, and the chained pattern tears a wider,
 * uneven crater than any single explosion.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   creeper's current target within 10 blocks (distance squared
 *   100). The creeper has to be close: this is its own detonation.
 * - The fuse: 15 ticks. start() plays the vanilla CREEPER_PRIMED
 *   hiss at the creeper, and every 3 ticks of the fuse the creeper
 *   sheds 3 SMOKE particles as its fuse burns down. The creeper is
 *   NOT frozen during the fuse: it keeps walking at its target
 *   like a vanilla primed creeper, and the blast centers wherever
 *   it stands when the fuse ends.
 * - Detonation (tick 15), in this exact order:
 *   1. The 4 secondary charges are scheduled on the mod's
 *      TickScheduler at 7, 14, 21, and 28 ticks after the primary,
 *      at ring positions 3.0 to 5.0 blocks from the detonation
 *      center (random angle per charge, same Y as the creeper).
 *      They are scheduled FIRST so the chain exists no matter what
 *      the primary blast does to the caster.
 *   2. The primary explosion: power 4.0 (a vanilla creeper is
 *      3.0), centered on the creeper, through Level.explode, which
 *      deals typed explosion damage with vanilla falloff and
 *      emits its own EXPLOSION_EMITTER flash and boom.
 *   3. THE CASTER DIES. This ability is the creeper's own
 *      detonation: if the primary blast did not already kill it,
 *      tick() discards it immediately after exploding, mirroring
 *      vanilla Creeper.explodeCreeper. The framework's termination
 *      path then runs end() exactly once; see CLEANUP.
 * - The secondaries: each is a power 2.0 explosion through
 *   Level.explode with a null source (the caster is gone; nothing
 *   may credit or blame it), at its precomputed position. They are
 *   fire-and-forget world events: once the primary cooks off, the
 *   chain belongs to the world and nothing cancels it. If the
 *   server stops before a charge's tick arrives, TickScheduler
 *   drops it like every other delayed task in this mod.
 * - If the creeper dies or is removed during the 15 tick fuse
 *   (shot down before it cooks off), the termination path runs
 *   end() with the detonated flag still false and nothing was
 *   scheduled: the whole ability fizzles, no blast at all. That is
 *   the counterplay, and it is deliberate.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6): every explosion in this ability
 * picks its block interaction from the shared
 * AbilityContext.mobGriefingAllowed() check, read once at
 * detonation time and captured by the scheduled secondaries:
 * - mobGriefing ON: Level.ExplosionInteraction.MOB, so the blasts
 *   break blocks and the chained craters are PERMANENT terrain.
 * - mobGriefing OFF: Level.ExplosionInteraction.NONE, so no blast
 *   in the chain ever alters a block; the explosion damage,
 *   knockback, flashes, and sounds are identical.
 *
 * WHAT IT LOOKS LIKE: the primed hiss, smoke wisping off the
 * burning fuse, then the big central EXPLOSION_EMITTER flash, and
 * four more flashes popping around it roughly a third of a second
 * apart, walking outward across the ring.
 *
 * PERFORMANCE: the fuse sends 3 SMOKE every 3 ticks (at most 5
 * pulses, 15 particles; the explosions' own particles are
 * vanilla's, not this ability's budget). Detonation is one ring
 * computation (4 positions), 4 scheduler registrations, and one
 * explode call. No entity scans, no per-tick work after the fuse.
 *
 * CLEANUP: end() is deliberately a no-op. Nothing this ability
 * creates outlives it in a form end() could or should undo: the
 * caster's death is the ability working as designed (not a leak),
 * the scheduled secondaries are world events described above, and
 * terrain changes under mobGriefing are permanent by the standing
 * rule. The detonated flag in the state tag guarantees the
 * detonation block runs at most once even if tick() and the
 * termination path race.
 *
 * State tag keys: "detonated" (1 once the detonation block has
 * run; set before the primary explosion fires).
 */
public final class ClusterDetonationAbility implements MobAbility {

    private static final int FUSE_TICKS = 15;
    private static final float PRIMARY_POWER = 4.0f;
    private static final int SECONDARY_COUNT = 4;
    private static final float SECONDARY_POWER = 2.0f;
    private static final int SECONDARY_DELAY_STEP = 7;
    private static final double RING_MIN_RADIUS = 3.0;
    private static final double RING_MAX_RADIUS = 5.0;

    @Override
    public String id() {
        return "cluster_detonation";
    }

    @Override
    public String displayName() {
        return "Cluster Detonation";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 100.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().putInt("detonated", 0);
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            Mob mob = ctx.mob();
            // The fuse is lit: the vanilla primed hiss.
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.CREEPER_PRIMED, SoundSource.HOSTILE, 1.0f, 1.0f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.state().getIntOr("detonated", 0) != 0) {
            return; // Already cooked off; nothing left to do.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        int ticks = ctx.ticksActive();
        if (ticks % 3 == 0) {
            // The burning fuse: smoke wisping off the creeper's head.
            level.sendParticles(ParticleTypes.SMOKE, mob.getX(), mob.getY() + 1.4, mob.getZ(),
                3, 0.25, 0.3, 0.25, 0.03);
        }
        if (ticks >= FUSE_TICKS) {
            detonate(ctx, level);
        }
    }

    /**
     * The detonation block: schedules the secondary chain, fires
     * the primary blast, and kills the caster (see the class
     * comment for the ordering and the terrain rule).
     */
    private static void detonate(AbilityContext ctx, ServerLevel level) {
        Mob caster = ctx.mob();
        double cx = caster.getX();
        double cy = caster.getY();
        double cz = caster.getZ();

        // The one shared terrain gate, read once for the whole chain.
        Level.ExplosionInteraction interaction = ctx.mobGriefingAllowed()
            ? Level.ExplosionInteraction.MOB
            : Level.ExplosionInteraction.NONE;

        // 1. Seed the chain first: 4 secondaries on a ring around
        // the blast, cooking off 7 ticks apart.
        MinecraftServer server = level.getServer();
        for (int i = 0; i < SECONDARY_COUNT; i++) {
            double angle = ctx.random().nextDouble() * Math.PI * 2.0;
            double radius = RING_MIN_RADIUS + ctx.random().nextDouble() * (RING_MAX_RADIUS - RING_MIN_RADIUS);
            final double sx = cx + Math.cos(angle) * radius;
            final double sy = cy;
            final double sz = cz + Math.sin(angle) * radius;
            final int delay = SECONDARY_DELAY_STEP * (i + 1);
            TickScheduler.schedule(server, delay,
                () -> level.explode(null, sx, sy, sz, SECONDARY_POWER, false, interaction));
        }

        // 2. The primary blast, flagged before it fires so nothing
        // can ever run this block twice.
        ctx.state().putInt("detonated", 1);
        level.explode(caster, cx, cy, cz, PRIMARY_POWER, false, interaction);

        // 3. The caster dies in its own detonation, by design. If
        // the blast already killed it this discard is a no-op.
        if (!caster.isRemoved()) {
            caster.discard();
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.state().getIntOr("detonated", 0) != 0;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Deliberately empty: see CLEANUP in the class comment.
        // The caster's death is the design, the scheduled chain is
        // fire-and-forget, and mobGriefing terrain is permanent.
    }
}
