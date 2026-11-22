package mod.advancedmobs.ability.blaze;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Cinder Meteor Volley (Blaze ability 3 of 5, attack).
 *
 * WHAT IT DOES: the blaze rises high above the fight while
 * three spots on the ground around its target light up with
 * glowing red circles, and then a fireball falls out of the
 * sky onto each mark in turn, bursting in a flash of fire
 * that leaves a patch of real fire burning on the ground.
 * Enemies who read the circles can dodge; standing still
 * under one is close to fatal.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists within 20 blocks
 *   (distance squared 400).
 * - The marks (start()): three spots on the ground. Mark 0 is
 *   the target's position at cast time; mark 1 is 2.5 blocks
 *   from it at a random angle; mark 2 is 3.5 blocks from it at
 *   another random angle. Each mark's surface height comes
 *   from the shared surface scan anchored on the target's
 *   block height. The marks are places, not trackers: they do
 *   not follow the target after the cast, which is what makes
 *   the volley dodgeable. A BLAZE_SHOOT (volume 1.0, pitch
 *   0.6) calls the volley in.
 * - The rise (tick(), ticks 1 to 70): the blaze's navigation
 *   is stopped every tick and its velocity is set directly:
 *   horizontal components halved each tick (it brakes to a
 *   hover over the fight), vertical +0.30 per tick until it
 *   is 6 blocks above its cast height, then +0.02 (a hover
 *   hold). After tick 70 the velocity is left alone and the
 *   blaze's own flight AI resumes. No NoAi is used; the
 *   velocity writes are the hold, per the standing contract.
 * - The telegraph (tick(), ticks 1 to 39, every 3 ticks):
 *   each mark is drawn as a circle of 8 red DUST particles
 *   (color 0xFF2A00, scale 1.4) at radius 1.6 around the mark,
 *   a quarter block above its surface, the whole circle
 *   slowly turning with the activation clock.
 * - The meteors (ticks 40, 52, and 64, one per mark in
 *   order): one SmallFireball per mark, spawned 12 blocks
 *   above the mark with the blaze as its owner, velocity
 *   straight down at 1.6 blocks per tick, and its
 *   accelerationPower zeroed so nothing propels it. tick()
 *   re-sets the velocity to straight down every tick (the
 *   vanilla drag would otherwise bleed the fall speed) and
 *   trails 2 SMOKE particles off it. A meteor falls the 12
 *   blocks in about 8 ticks. Vanilla meteor behavior on the
 *   way down is part of the design: a meteor that strikes an
 *   enemy mid-fall deals its vanilla 5.0 fireball damage and
 *   lights it for 5 seconds, and one that strikes a block
 *   with mobGriefing on sets that one block on fire (vanilla
 *   checks the game rule itself).
 * - The burst (when a meteor entity is gone, for any reason,
 *   or after 40 ticks of flight when it is discarded where it
 *   flies): the burst always lands on the meteor's mark,
 *   wherever the meteor itself stopped. One bounded enemy
 *   query in radius 2.5 around the mark (1.5 below to 2.5
 *   above its surface): every enemy takes 6.0 damage credited
 *   to the blaze (mobAttack) and is ignited for 4 seconds.
 *   Combined with a direct vanilla hit on the way down that
 *   is 11.0 damage plus two burns, which is why standing
 *   still is fatal. The burst throws 1 EXPLOSION and 8 FLAME
 *   particles and a GENERIC_EXPLODE (volume 0.7, pitch 1.0),
 *   and, when the terrain rule allows, leaves its fire patch
 *   (see the TERRAIN RULE).
 * - Duration: the ability finishes once all three marks have
 *   burst and tick 75 has passed, with a hard finish at 200
 *   ticks.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6): each burst's fire patch is
 * PERMANENT terrain, gated by
 * AbilityContext.mobGriefingAllowed():
 * - mobGriefing ON: up to 4 fire blocks are placed at the
 *   mark (its surface position, then random offsets inside
 *   radius 1.2 at the same height), each through the shared
 *   placeFire safety check (dry, replaceable, sturdy ground
 *   beneath, never a block entity). The patch burns whatever
 *   stands in it until it burns out on vanilla's own
 *   schedule.
 * - mobGriefing OFF: no fire is placed (and a vanilla meteor
 *   places none either, per its own rule check). The burst
 *   damage and ignition are unchanged.
 *
 * WHAT IT LOOKS LIKE: the blaze climbing as three red rings
 * kindle on the ground and slowly turn, then a smoking
 * fireball spearing down onto each ring in turn, each impact
 * flashing EXPLOSION and FLAME over a fresh patch of fire.
 *
 * PERFORMANCE: the rise is one velocity read and write per
 * tick for 70 ticks. The telegraph runs every 3 ticks. Meteor
 * tracking is at most 3 UUID resolves and velocity writes per
 * tick. The only entity query is one bounded box per burst,
 * 3 per activation. Particle budget per activation:
 * telegraph 13 pulses times 3 marks times 8 = 312 DUST,
 * meteor trails at most 3 meteors times about 10 ticks times
 * 2 = 60 SMOKE, bursts 3 times 9 = 27, total at most 399
 * particles.
 *
 * CLEANUP: end() discards any meteor still in flight, with
 * no burst: the payload is the blaze's attack and dies with
 * the activation. Fire patches already placed (mobGriefing
 * on) are permanent terrain and are deliberately NOT
 * touched.
 *
 * State tag keys: "base_y" (the cast height, a double),
 * "marks" (list of compounds, one per mark: "x", "y", "z"
 * doubles, the surface center; "burst", 1 once the mark's
 * burst ran), "meteors" (list of compounds, one per live
 * meteor: "id" the fireball's UUID, "mark" the mark index,
 * "born" the tick it was spawned).
 */
public final class CinderMeteorVolleyAbility implements MobAbility {

    private static final int HARD_FINISH_TICKS = 200;
    private static final int RISE_TICKS = 70;
    private static final int METEOR_TIMEOUT_TICKS = 40;
    private static final int[] METEOR_TICKS = {40, 52, 64};
    private static final double BURST_RADIUS = 2.5;
    private static final float BURST_DAMAGE = 6.0f;
    private static final int MAX_FIRE_BLOCKS = 4;
    // 26.2 DustParticleOptions takes an RGB int color: a hot
    // ember red, 0xFF2A00.
    private static final DustParticleOptions MARK_DUST = new DustParticleOptions(0xFF2A00, 1.4f);

    @Override
    public String id() {
        return "cinder_meteor_volley";
    }

    @Override
    public String displayName() {
        return "Cinder Meteor Volley";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return ctx.target() != null && ctx.mob().distanceToSqr(ctx.target()) <= 400.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        state.putDouble("base_y", mob.getY());
        state.put("meteors", new ListTag());
        ListTag marks = new ListTag();
        state.put("marks", marks);
        LivingEntity target = ctx.target();
        if (level == null || target == null) {
            return;
        }
        addMark(ctx, level, target.getX(), target.getZ(), target.getBlockY());
        double angle1 = ctx.random().nextDouble() * Math.PI * 2.0;
        addMark(ctx, level, target.getX() + Math.cos(angle1) * 2.5,
            target.getZ() + Math.sin(angle1) * 2.5, target.getBlockY());
        double angle2 = ctx.random().nextDouble() * Math.PI * 2.0;
        addMark(ctx, level, target.getX() + Math.cos(angle2) * 3.5,
            target.getZ() + Math.sin(angle2) * 3.5, target.getBlockY());
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.BLAZE_SHOOT,
            SoundSource.HOSTILE, 1.0f, 0.6f);
    }

    /** Records one ground mark at the given spot (see the class comment). */
    private static void addMark(AbilityContext ctx, ServerLevel level, double x, double z, int refY) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        int surfaceY = BlazeAbilitySupport.findSurfaceY(level, blockX, blockZ, refY);
        CompoundTag mark = new CompoundTag();
        mark.putDouble("x", blockX + 0.5);
        mark.putDouble("y", surfaceY);
        mark.putDouble("z", blockZ + 0.5);
        mark.putInt("burst", 0);
        ListTag marks = ctx.state().getListOrEmpty("marks");
        marks.add(mark);
        ctx.state().put("marks", marks);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        Mob mob = ctx.mob();

        if (ticks <= RISE_TICKS) {
            // The rise and hover (see the class comment): brake
            // horizontally, climb to 6 blocks above the cast
            // height, then hold.
            mob.getNavigation().stop();
            Vec3 velocity = mob.getDeltaMovement();
            double baseY = ctx.state().getDoubleOr("base_y", mob.getY());
            double vy = mob.getY() < baseY + 6.0 ? 0.30 : 0.02;
            mob.setDeltaMovement(velocity.x * 0.5, vy, velocity.z * 0.5);
        }

        if (ticks <= 39 && ticks % 3 == 0) {
            drawMarks(ctx, level);
        }

        for (int i = 0; i < METEOR_TICKS.length; i++) {
            if (ticks == METEOR_TICKS[i]) {
                spawnMeteor(ctx, level, i);
            }
        }

        trackMeteors(ctx, level);
    }

    /** Draws the three telegraph circles (see the class comment). */
    private static void drawMarks(AbilityContext ctx, ServerLevel level) {
        ListTag marks = ctx.state().getListOrEmpty("marks");
        double turn = ctx.ticksActive() * 0.05;
        for (int m = 0; m < marks.size(); m++) {
            CompoundTag mark = marks.getCompoundOrEmpty(m);
            double x = mark.getDoubleOr("x", 0.0);
            double y = mark.getDoubleOr("y", 0.0);
            double z = mark.getDoubleOr("z", 0.0);
            for (int i = 0; i < 8; i++) {
                double angle = turn + i * (Math.PI / 4.0);
                level.sendParticles(MARK_DUST,
                    x + Math.cos(angle) * 1.6, y + 0.25, z + Math.sin(angle) * 1.6,
                    1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }

    /** Calls one meteor down onto the given mark (see the class comment). */
    private static void spawnMeteor(AbilityContext ctx, ServerLevel level, int markIndex) {
        ListTag marks = ctx.state().getListOrEmpty("marks");
        if (markIndex >= marks.size()) {
            return;
        }
        CompoundTag mark = marks.getCompoundOrEmpty(markIndex);
        double x = mark.getDoubleOr("x", ctx.mob().getX());
        double y = mark.getDoubleOr("y", ctx.mob().getY());
        double z = mark.getDoubleOr("z", ctx.mob().getZ());
        SmallFireball meteor = new SmallFireball(level, ctx.mob(), new Vec3(0.0, -1.6, 0.0));
        meteor.snapTo(x, y + 12.0, z, 0.0f, 0.0f);
        // No self-propulsion: the fall is the velocity, held
        // straight down by tick().
        meteor.accelerationPower = 0.0;
        if (!level.addFreshEntity(meteor)) {
            runBurst(ctx, level, markIndex);
            return;
        }
        CompoundTag entry = new CompoundTag();
        entry.store("id", UUIDUtil.CODEC, meteor.getUUID());
        entry.putInt("mark", markIndex);
        entry.putInt("born", ctx.ticksActive());
        ListTag meteors = ctx.state().getListOrEmpty("meteors");
        meteors.add(entry);
        ctx.state().put("meteors", meteors);
        level.playSound(null, x, y + 12.0, z, SoundEvents.FIRECHARGE_USE,
            SoundSource.HOSTILE, 0.8f, 0.5f);
    }

    /**
     * Tracks the live meteors: holds their fall straight down,
     * trails their smoke, and runs the burst for each one that
     * lands, hits, or times out (see the class comment).
     */
    private static void trackMeteors(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        ListTag meteors = state.getListOrEmpty("meteors");
        if (meteors.isEmpty()) {
            return;
        }
        ListTag kept = new ListTag();
        int ticks = ctx.ticksActive();
        for (int i = 0; i < meteors.size(); i++) {
            CompoundTag entry = meteors.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            Entity meteor = id != null ? level.getEntity(id) : null;
            int markIndex = entry.getIntOr("mark", 0);
            if (meteor == null) {
                runBurst(ctx, level, markIndex);
                continue;
            }
            if (ticks - entry.getIntOr("born", ticks) >= METEOR_TIMEOUT_TICKS) {
                meteor.discard();
                runBurst(ctx, level, markIndex);
                continue;
            }
            meteor.setDeltaMovement(0.0, -1.6, 0.0);
            level.sendParticles(ParticleTypes.SMOKE, meteor.getX(), meteor.getY(), meteor.getZ(),
                2, 0.1, 0.1, 0.1, 0.0);
            kept.add(entry);
        }
        state.put("meteors", kept);
    }

    /**
     * The burst on one mark: damage and ignition for every
     * enemy there, the flash, and, when the terrain rule
     * allows, the permanent fire patch (see the TERRAIN RULE
     * in the class comment).
     */
    private static void runBurst(AbilityContext ctx, ServerLevel level, int markIndex) {
        CompoundTag state = ctx.state();
        ListTag marks = state.getListOrEmpty("marks");
        if (markIndex >= marks.size()) {
            return;
        }
        CompoundTag mark = marks.getCompoundOrEmpty(markIndex);
        if (mark.getIntOr("burst", 0) == 1) {
            return;
        }
        mark.putInt("burst", 1);
        marks.set(markIndex, mark);
        state.put("marks", marks);
        double x = mark.getDoubleOr("x", ctx.mob().getX());
        double y = mark.getDoubleOr("y", ctx.mob().getY());
        double z = mark.getDoubleOr("z", ctx.mob().getZ());

        List<LivingEntity> victims = BlazeAbilitySupport.enemiesInBox(ctx, x, y, z, BURST_RADIUS, 1.5, 2.5);
        for (LivingEntity victim : victims) {
            victim.hurtServer(level, level.damageSources().mobAttack(ctx.mob()), BURST_DAMAGE);
            victim.igniteForSeconds(4.0f);
        }

        if (ctx.mobGriefingAllowed()) {
            int placed = 0;
            if (BlazeAbilitySupport.placeFire(level, BlockPos.containing(x, y, z))) {
                placed++;
            }
            for (int attempt = 0; attempt < 8 && placed < MAX_FIRE_BLOCKS; attempt++) {
                double ox = ctx.random().nextDouble() * 2.4 - 1.2;
                double oz = ctx.random().nextDouble() * 2.4 - 1.2;
                if (BlazeAbilitySupport.placeFire(level, BlockPos.containing(x + ox, y, z + oz))) {
                    placed++;
                }
            }
        }

        level.sendParticles(ParticleTypes.EXPLOSION, x, y + 0.5, z, 1, 0.0, 0.0, 0.0, 0.0);
        level.sendParticles(ParticleTypes.FLAME, x, y + 0.5, z, 8, 1.0, 0.4, 1.0, 0.08);
        level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 0.7f, 1.0f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        if (ctx.ticksActive() >= HARD_FINISH_TICKS) {
            return true;
        }
        if (ctx.ticksActive() < 75) {
            return false;
        }
        ListTag marks = ctx.state().getListOrEmpty("marks");
        for (int i = 0; i < marks.size(); i++) {
            if (marks.getCompoundOrEmpty(i).getIntOr("burst", 0) == 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void end(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        // Meteors still in flight are discarded with no burst:
        // the payload dies with the activation (see the class
        // comment). Fire patches already placed are permanent
        // terrain and are deliberately NOT touched.
        ListTag meteors = ctx.state().getListOrEmpty("meteors");
        for (int i = 0; i < meteors.size(); i++) {
            CompoundTag entry = meteors.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            Entity meteor = id != null ? level.getEntity(id) : null;
            if (meteor != null) {
                meteor.discard();
            }
        }
    }
}
