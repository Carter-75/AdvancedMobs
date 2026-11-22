package mod.advancedmobs.ability.giant;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;
import java.util.UUID;

/**
 * Boulder Throw (Giant ability 3 of 5, attack / terrain).
 *
 * WHAT IT DOES: the giant rips a chunk out of the ground at
 * its feet, holds the boulder overhead as a block display for
 * a beat while it takes aim, then hurls it in a high arc at
 * its target. The boulder flies as a real falling block,
 * bursts into BLOCK particles and area damage where it lands,
 * and, when mobGriefing is on, the chunk stays sitting in the
 * terrain where it fell.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the giant on the ground with its target between 6 and 24
 *   blocks away (distance squared 36 to 576).
 * - The rip (start()): the boulder's block state is the block
 *   directly under the giant's feet when that block is usable
 *   (solid collision shape, no block entity, breakable);
 *   otherwise the boulder is plain STONE. When mobGriefing is
 *   on and the ground block was usable, the block is removed
 *   (set to air, no drops): the rip leaves a real hole,
 *   PERMANENT per the standing terrain rule. With mobGriefing
 *   off nothing is removed; the boulder is a copy. The chosen
 *   state is stored in the state tag as NBT (writeBlockState)
 *   and read back at the throw.
 * - The hold (ticks 1 to 14): a block display of the boulder
 *   state at scale 2.2 is snapped every tick to the point
 *   above the giant's head (feet height + bounding box height
 *   + 1.2), gliding on its 2-tick teleport duration. Its UUID
 *   is stored in the state tag.
 * - The throw (tick 15): the display is discarded and a real
 *   FallingBlockEntity of the boulder state spawns at the
 *   held position (FallingBlockEntity.fall, the same summon
 *   the Stray's icicles use), with dropItem disabled so a
 *   failed landing never scatters an item. Its velocity is
 *   set toward the target's position at the throw moment
 *   (the position recorded in start() if the target is gone):
 *   horizontal speed = distance times 0.08 clamped to
 *   [0.5, 1.15], vertical 0.78, a high lobbed arc. The
 *   boulder is tracked by UUID with its last known position.
 * - The landing (tracked every tick): when the boulder
 *   resolves (the entity is gone because it landed and, in
 *   mobGriefing mode, placed its block by vanilla falling
 *   block rules), or is seen on the ground, the impact runs
 *   at its last known position. With mobGriefing OFF the
 *   boulder is intercepted just above the ground (the Icicle
 *   Fall pattern: falling, and the gap to the first collidable
 *   block below within one tick of travel) and discarded
 *   before its own tick can place the block, so no block is
 *   ever placed; the impact still runs in full. Hard timeout:
 *   at tick 100 a still-flying boulder impacts at its last
 *   position and is discarded.
 * - The impact: GENERIC_EXPLODE at volume 1.0, pitch 0.8; 20
 *   BLOCK particles of the boulder state and 8 DUST_PILLAR;
 *   every enemy (package rules) within radius 3.0 (2.0 below
 *   to 2.5 above the impact) takes 10.0 mob-attack damage
 *   credited to the giant and is knocked 0.8 horizontally away
 *   from the impact and 0.4 up.
 * - The ability finishes 5 ticks after the impact, or at the
 *   tick 120 hard cap. end() discards a still-held display
 *   and a still-flying boulder with no impact: a terminated
 *   throw never lands, which also guarantees a
 *   mobGriefing-off world never receives the boulder's block
 *   from a dead giant's throw. A boulder that already landed
 *   and placed its block (mobGriefing on) is permanent
 *   terrain and is deliberately not touched.
 *
 * WHAT IT LOOKS LIKE: a two-block-wide chunk of the actual
 * ground heaved overhead, a beat of aim, then a lobbed arc
 * and a bursting landing that leaves the chunk sitting in
 * the dirt (mobGriefing on).
 *
 * PERFORMANCE: the hold is one UUID resolve and one snap per
 * tick; the flight is one UUID resolve and one position read
 * per tick, plus at most 4 block reads on the mobGriefing-off
 * landing check; the impact is one bounded entity query.
 * Particle budget per activation: 28 at the impact, total at
 * most 28 particles (the rip, hold, and flight send none).
 *
 * State tag keys: "boulder_state" (the block state NBT),
 * "display" (the held display's UUID), "phase" (0 holding,
 * 1 flying, 2 impacted), "boulder" (the falling block's
 * UUID), "bx"/"by"/"bz" (its last known position),
 * "tx"/"ty"/"tz" (the target position recorded in start()),
 * "impact_tick" (the tick the impact ran).
 */
public final class BoulderThrowAbility implements MobAbility {

    private static final int THROW_TICK = 15;
    private static final int FLIGHT_TIMEOUT_TICK = 100;
    private static final int HARD_FINISH_TICKS = 120;
    private static final float IMPACT_DAMAGE = 10.0f;
    private static final double IMPACT_RADIUS = 3.0;
    private static final float DISPLAY_SCALE = 2.2f;

    @Override
    public String id() {
        return "boulder_throw";
    }

    @Override
    public String displayName() {
        return "Boulder Throw";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null || !ctx.mob().onGround()) {
            return false;
        }
        double distanceSqr = ctx.mob().distanceToSqr(target);
        return distanceSqr >= 36.0 && distanceSqr <= 576.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        state.putInt("phase", 0);
        LivingEntity target = ctx.target();
        if (target != null) {
            state.putDouble("tx", target.getX());
            state.putDouble("ty", target.getY());
            state.putDouble("tz", target.getZ());
        }
        if (level == null) {
            return;
        }

        // The rip: the block under the giant's feet, when usable.
        BlockPos below = mob.blockPosition().below();
        BlockState ground = level.getBlockState(below);
        boolean usable = !ground.isAir()
            && !ground.getCollisionShape(level, below).isEmpty()
            && level.getBlockEntity(below) == null
            && ground.getDestroySpeed(level, below) >= 0.0f;
        BlockState boulderState = usable ? ground : Blocks.STONE.defaultBlockState();
        if (usable && ctx.mobGriefingAllowed()) {
            // The chunk comes out of the ground: a real hole,
            // permanent per the standing terrain rule.
            level.setBlock(below, Blocks.AIR.defaultBlockState(), 3);
        }
        state.put("boulder_state", NbtUtils.writeBlockState(boulderState));

        // The held boulder: a block display overhead.
        Entity display = GiantAbilitySupport.createBlockDisplay(level, boulderState, DISPLAY_SCALE);
        if (display != null) {
            display.snapTo(mob.getX(), overheadY(mob), mob.getZ());
            level.addFreshEntity(display);
            state.store("display", UUIDUtil.CODEC, display.getUUID());
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        int phase = state.getIntOr("phase", 0);
        if (phase == 0) {
            // Hold: keep the boulder over the giant's head.
            Entity display = resolveStored(level, state, "display");
            if (display != null) {
                Mob mob = ctx.mob();
                display.snapTo(mob.getX(), overheadY(mob), mob.getZ());
            }
            if (ctx.ticksActive() >= THROW_TICK) {
                doThrow(ctx, level);
            }
            return;
        }
        if (phase == 1) {
            trackBoulder(ctx, level);
        }
        // Phase 2: the impact already ran; nothing left to do.
    }

    /** The throw: swap the held display for a real falling block, lobbed at the target. */
    private void doThrow(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        Mob mob = ctx.mob();
        double launchX = mob.getX();
        double launchY = overheadY(mob);
        double launchZ = mob.getZ();
        Entity display = resolveStored(level, state, "display");
        if (display != null) {
            launchX = display.getX();
            launchY = display.getY();
            launchZ = display.getZ();
            display.discard();
        }

        BlockState boulderState = Blocks.STONE.defaultBlockState();
        if (state.contains("boulder_state")) {
            boulderState = NbtUtils.readBlockState(
                level.registryAccess().lookupOrThrow(Registries.BLOCK),
                state.getCompoundOrEmpty("boulder_state"));
        }

        // Aim at the target's live position, or the recorded one.
        double aimX = state.getDoubleOr("tx", mob.getX());
        double aimZ = state.getDoubleOr("tz", mob.getZ());
        LivingEntity target = ctx.target();
        if (target != null) {
            aimX = target.getX();
            aimZ = target.getZ();
        }

        FallingBlockEntity boulder = FallingBlockEntity.fall(level,
            BlockPos.containing(launchX, launchY, launchZ), boulderState);
        if (boulder == null) {
            state.putInt("phase", 2);
            state.putInt("impact_tick", ctx.ticksActive());
            return;
        }
        // A failed landing must never scatter a boulder item.
        boulder.dropItem = false;
        boulder.disableDrop();
        double dx = aimX - launchX;
        double dz = aimZ - launchZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        double speed = Math.max(0.5, Math.min(1.15, dist * 0.08));
        if (dist < 1.0e-4) {
            dx = 1.0;
            dz = 0.0;
            dist = 1.0;
        }
        boulder.setDeltaMovement(dx / dist * speed, 0.78, dz / dist * speed);
        boulder.hurtMarked = true;
        state.store("boulder", UUIDUtil.CODEC, boulder.getUUID());
        state.putDouble("bx", boulder.getX());
        state.putDouble("by", boulder.getY());
        state.putDouble("bz", boulder.getZ());
        state.putInt("phase", 1);
    }

    /**
     * Watches the flying boulder: refreshes its last known
     * position, runs the impact when it lands, and, with
     * mobGriefing off, intercepts it just above the ground so
     * no block is ever placed (see the TERRAIN note in the
     * class comment).
     */
    private void trackBoulder(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        Entity boulder = resolveStored(level, state, "boulder");
        double x = state.getDoubleOr("bx", ctx.mob().getX());
        double y = state.getDoubleOr("by", ctx.mob().getY());
        double z = state.getDoubleOr("bz", ctx.mob().getZ());
        if (boulder == null) {
            // Landed and resolved itself (placing its block if
            // mobGriefing allowed it): impact at the last spot.
            runImpact(ctx, level, x, y, z);
            return;
        }
        x = boulder.getX();
        y = boulder.getY();
        z = boulder.getZ();
        state.putDouble("bx", x);
        state.putDouble("by", y);
        state.putDouble("bz", z);
        if (boulder.onGround() || ctx.ticksActive() >= FLIGHT_TIMEOUT_TICK) {
            runImpact(ctx, level, x, y, z);
            boulder.discard();
            return;
        }
        if (!ctx.mobGriefingAllowed() && boulder.getDeltaMovement().y < 0.0
            && aboutToLand(level, boulder)) {
            // mobGriefing off: impact now and discard the
            // falling block before its own tick can place it.
            runImpact(ctx, level, x, y, z);
            boulder.discard();
        }
    }

    /**
     * Whether the falling block's underside is within one tick
     * of falling travel above the first collidable block below
     * it: scans down at most 4 blocks from the block under the
     * entity. Only used in the mobGriefing-off interception.
     * (The Icicle Fall pattern.)
     */
    private static boolean aboutToLand(ServerLevel level, Entity entity) {
        double bottom = entity.getBoundingBox().minY;
        double fallPerTick = -entity.getDeltaMovement().y;
        int bx = (int) Math.floor(entity.getX());
        int bz = (int) Math.floor(entity.getZ());
        int startY = (int) Math.floor(bottom) - 1;
        for (int y = startY; y >= startY - 3; y--) {
            BlockPos pos = new BlockPos(bx, y, bz);
            BlockState blockState = level.getBlockState(pos);
            VoxelShape shape = blockState.getCollisionShape(level, pos);
            if (!shape.isEmpty()) {
                double groundTop = y + shape.max(Direction.Axis.Y);
                return bottom - groundTop <= Math.max(0.7, fallPerTick * 1.05);
            }
        }
        return false;
    }

    /** The landing burst: debris, sound, area damage, and knockback. Runs exactly once. */
    private void runImpact(AbilityContext ctx, ServerLevel level, double x, double y, double z) {
        CompoundTag state = ctx.state();
        state.putInt("phase", 2);
        state.putInt("impact_tick", ctx.ticksActive());

        BlockState boulderState = Blocks.STONE.defaultBlockState();
        if (state.contains("boulder_state")) {
            boulderState = NbtUtils.readBlockState(
                level.registryAccess().lookupOrThrow(Registries.BLOCK),
                state.getCompoundOrEmpty("boulder_state"));
        }
        level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1.0f, 0.8f);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, boulderState),
            x, y + 0.3, z, 20, 1.2, 0.5, 1.2, 0.18);
        level.sendParticles(new BlockParticleOption(ParticleTypes.DUST_PILLAR, boulderState),
            x, y + 0.3, z, 8, 1.4, 0.4, 1.4, 0.06);

        Mob mob = ctx.mob();
        List<LivingEntity> victims = GiantAbilitySupport.enemiesInBox(ctx, x, y, z, IMPACT_RADIUS, 2.0, 2.5);
        for (LivingEntity victim : victims) {
            victim.hurtServer(level, level.damageSources().mobAttack(mob), IMPACT_DAMAGE);
            Vec3 away = new Vec3(victim.getX() - x, 0.0, victim.getZ() - z);
            away = away.lengthSqr() < 1.0e-4 ? new Vec3(1.0, 0.0, 0.0) : away.normalize();
            victim.setDeltaMovement(away.x * 0.8, 0.4, away.z * 0.8);
            victim.hurtMarked = true;
        }
    }

    /** The height of the held boulder's center: just above the giant's head. */
    private static double overheadY(Mob mob) {
        return mob.getY() + mob.getBbHeight() + 1.2;
    }

    /** Resolves a stored entity UUID ("display" or "boulder") against the level, or null. */
    private static Entity resolveStored(ServerLevel level, CompoundTag state, String key) {
        if (!state.contains(key)) {
            return null;
        }
        UUID id = state.read(key, UUIDUtil.CODEC).orElse(null);
        return id != null ? level.getEntity(id) : null;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        if (state.getIntOr("phase", 0) == 2) {
            return ctx.ticksActive() >= state.getIntOr("impact_tick", 0) + 5;
        }
        return ctx.ticksActive() >= HARD_FINISH_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        // A still-held display and a still-flying boulder are
        // discarded with no impact: a terminated throw never
        // lands. A boulder that already landed and placed its
        // block (mobGriefing on) is permanent terrain and is
        // deliberately not touched here.
        Entity display = resolveStored(level, state, "display");
        if (display != null) {
            display.discard();
        }
        if (state.getIntOr("phase", 0) == 1) {
            Entity boulder = resolveStored(level, state, "boulder");
            if (boulder != null) {
                boulder.discard();
            }
        }
    }
}
