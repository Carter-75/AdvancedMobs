package mod.advancedmobs.ability.donkey;

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

/**
 * Cargo Catapult (Donkey ability 4 of 5, attack / terrain).
 *
 * WHAT IT DOES: the donkey bucks its cargo loose. A shadow
 * blooms on the ground at its target's feet, marking the
 * fall, and a moment later a piece of cargo, an anvil, a
 * barrel crate, or a plain stone block, drops out of the
 * sky on the mark and crashes down in a burst of block
 * crumble and heavy damage.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the donkey on the ground with its target between 6 and
 *   20 blocks away (distance squared 36 to 400).
 * - The mark (start()): the target's position at the cast
 *   moment is recorded in the state tag and never moves:
 *   the catapult fires at a marked position, not a tracked
 *   enemy, so a fast target can walk out of it. The cargo
 *   is picked by one roll of the donkey's random (0 anvil,
 *   1 barrel, 2 stone) and its block state is stored in
 *   the state tag as NBT. A warning marker display of the
 *   cargo state is created at the mark (scale 1.0, hovering
 *   at the target's feet height plus 0.1) with its
 *   shadow_radius set to 1.4 and shadow_strength to 0.9
 *   (the design's shadow display: the dark blob on the
 *   ground is the warning). DONKEY_ANGRY plays at volume
 *   1.0, pitch 0.9.
 * - The telegraph (ticks 1 to 20): every 2 ticks, 3
 *   DUST_PILLAR particles of the ground state rise at the
 *   mark. The marker display itself does not move.
 * - The drop (tick 20): the marker is discarded and a real
 *   FallingBlockEntity of the cargo state spawns 14 blocks
 *   above the mark with no initial velocity, with dropItem
 *   disabled so a failed landing never scatters an item.
 *   The cargo is tracked by UUID with its last known
 *   position.
 * - The landing (tracked every tick): when the cargo
 *   resolves (the entity is gone because it landed and, in
 *   mobGriefing mode, placed its block by vanilla falling
 *   block rules), or is seen on the ground, the impact runs
 *   at its last known position. With mobGriefing OFF the
 *   cargo is intercepted just above the ground (the Icicle
 *   Fall pattern: falling, and the gap to the first
 *   collidable block below within one tick of travel) and
 *   discarded before its own tick can place the block, so
 *   no block is ever placed; the impact still runs in
 *   full. Hard timeout: at tick 90 a still-falling cargo
 *   impacts at its last position and is discarded.
 * - The impact: GENERIC_EXPLODE at volume 1.2, pitch 0.7;
 *   16 BLOCK_CRUMBLE and 10 BLOCK particles of the cargo
 *   state; every enemy (package rules) within radius 2.5
 *   (2.0 below to 2.5 above the impact) takes 14.0
 *   mob-attack damage credited to the donkey and is
 *   knocked 1.0 horizontally away from the impact and 0.5
 *   up, hurtMarked set.
 * - The ability finishes 5 ticks after the impact, or at
 *   the tick 120 hard cap. end() discards a still-showing
 *   marker and a still-falling cargo with no impact: a
 *   terminated catapult never lands, which also guarantees
 *   a mobGriefing-off world never receives the cargo's
 *   block from a dead donkey's drop. A cargo that already
 *   landed and placed its block (mobGriefing on) is
 *   permanent terrain and is deliberately not touched.
 *
 * WHAT IT LOOKS LIKE: a bray, a dark shadow swelling on
 * the ground at the target's feet, dust puffing at the
 * edges of the mark, then a whistle-drop and a cargo block
 * hammering into the earth in a spray of its own debris,
 * left sitting in the terrain where it fell (mobGriefing
 * on).
 *
 * PERFORMANCE: the telegraph is one particle emission
 * every 2 ticks; the flight is one UUID resolve and one
 * position read per tick, plus at most 4 block reads on
 * the mobGriefing-off landing check; the impact is one
 * bounded entity query. Particle budget per activation:
 * 30 telegraph plus 26 impact = at most 56 particles.
 *
 * TERRAIN: the falling cargo is this package's one real
 * block change. It is PERMANENT and gated exactly per the
 * standing terrain rule: with mobGriefing on, vanilla
 * falling block placement leaves the anvil, barrel, or
 * stone sitting where it landed (vanilla placement only
 * ever fills a replaceable cell and never overwrites a
 * container, another block entity, or an unbreakable
 * block; a cargo that cannot place is discarded silently
 * because its drop is disabled). With mobGriefing off, the
 * interception above guarantees no block is ever placed.
 * The warning marker is a display entity, not a block.
 *
 * State tag keys: "cargo_state" (the cargo block state
 * NBT), "marker" (the warning display's UUID), "cargo"
 * (the falling block's UUID), "phase" (0 telegraph, 1
 * falling, 2 impacted), "tx"/"ty"/"tz" (the marked
 * position), "cx"/"cy"/"cz" (the cargo's last known
 * position), "impact_tick" (the tick the impact ran).
 */
public final class CargoCatapultAbility implements MobAbility {

    private static final int DROP_TICK = 20;
    private static final int FLIGHT_TIMEOUT_TICK = 90;
    private static final int HARD_FINISH_TICKS = 120;
    private static final double DROP_HEIGHT = 14.0;
    private static final float IMPACT_DAMAGE = 14.0f;
    private static final double IMPACT_RADIUS = 2.5;

    @Override
    public String id() {
        return "cargo_catapult";
    }

    @Override
    public String displayName() {
        return "Cargo Catapult";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null || !ctx.mob().onGround()) {
            return false;
        }
        double distanceSqr = ctx.mob().distanceToSqr(target);
        return distanceSqr >= 36.0 && distanceSqr <= 400.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        state.putInt("phase", 0);
        LivingEntity target = ctx.target();
        if (target != null) {
            state.putDouble("tx", target.getX());
            state.putDouble("ty", target.getY());
            state.putDouble("tz", target.getZ());
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null || target == null) {
            return;
        }
        Mob caster = ctx.mob();

        // The cargo: anvil, barrel crate, or stone, by one roll.
        BlockState cargoState = switch (ctx.random().nextInt(3)) {
            case 0 -> Blocks.ANVIL.defaultBlockState();
            case 1 -> Blocks.BARREL.defaultBlockState();
            default -> Blocks.STONE.defaultBlockState();
        };
        state.put("cargo_state", NbtUtils.writeBlockState(cargoState));

        // The mark: a shadow-casting display of the cargo at the target's feet.
        Entity marker = DonkeyAbilitySupport.createShadowMarker(level, cargoState);
        if (marker != null) {
            marker.snapTo(target.getX(), target.getY() + 0.1, target.getZ());
            level.addFreshEntity(marker);
            state.store("marker", UUIDUtil.CODEC, marker.getUUID());
        }
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.DONKEY_ANGRY,
            SoundSource.HOSTILE, 1.0f, 0.9f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        int phase = state.getIntOr("phase", 0);
        int ticks = ctx.ticksActive();
        if (phase == 0) {
            // Telegraph: dust puffs at the mark every 2 ticks.
            if (ticks % 2 == 0) {
                double tx = state.getDoubleOr("tx", ctx.mob().getX());
                double ty = state.getDoubleOr("ty", ctx.mob().getY());
                double tz = state.getDoubleOr("tz", ctx.mob().getZ());
                level.sendParticles(
                    DonkeyAbilitySupport.groundDustPillar(level, tx, ty + 0.5, tz),
                    tx, ty + 0.2, tz, 3, 1.0, 0.2, 1.0, 0.05);
            }
            if (ticks >= DROP_TICK) {
                doDrop(ctx, level);
            }
            return;
        }
        if (phase == 1) {
            trackCargo(ctx, level);
        }
        // Phase 2: the impact already ran; nothing left to do.
    }

    /** The drop: swap the warning marker for a real falling cargo block above the mark. */
    private void doDrop(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        Entity marker = DonkeyAbilitySupport.resolveStored(level, state, "marker");
        if (marker != null) {
            marker.discard();
        }
        double tx = state.getDoubleOr("tx", ctx.mob().getX());
        double ty = state.getDoubleOr("ty", ctx.mob().getY());
        double tz = state.getDoubleOr("tz", ctx.mob().getZ());

        BlockState cargoState = Blocks.STONE.defaultBlockState();
        if (state.contains("cargo_state")) {
            cargoState = NbtUtils.readBlockState(
                level.registryAccess().lookupOrThrow(Registries.BLOCK),
                state.getCompoundOrEmpty("cargo_state"));
        }
        FallingBlockEntity cargo = FallingBlockEntity.fall(level,
            BlockPos.containing(tx, ty + DROP_HEIGHT, tz), cargoState);
        if (cargo == null) {
            state.putInt("phase", 2);
            state.putInt("impact_tick", ctx.ticksActive());
            return;
        }
        // A failed landing must never scatter a cargo item.
        cargo.dropItem = false;
        cargo.disableDrop();
        state.store("cargo", UUIDUtil.CODEC, cargo.getUUID());
        state.putDouble("cx", cargo.getX());
        state.putDouble("cy", cargo.getY());
        state.putDouble("cz", cargo.getZ());
        state.putInt("phase", 1);
    }

    /**
     * Watches the falling cargo: refreshes its last known
     * position, runs the impact when it lands, and, with
     * mobGriefing off, intercepts it just above the ground so
     * no block is ever placed (see the TERRAIN note in the
     * class comment).
     */
    private void trackCargo(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        Entity cargo = DonkeyAbilitySupport.resolveStored(level, state, "cargo");
        double x = state.getDoubleOr("cx", ctx.mob().getX());
        double y = state.getDoubleOr("cy", ctx.mob().getY());
        double z = state.getDoubleOr("cz", ctx.mob().getZ());
        if (cargo == null) {
            // Landed and resolved itself (placing its block if
            // mobGriefing allowed it): impact at the last spot.
            runImpact(ctx, level, x, y, z);
            return;
        }
        x = cargo.getX();
        y = cargo.getY();
        z = cargo.getZ();
        state.putDouble("cx", x);
        state.putDouble("cy", y);
        state.putDouble("cz", z);
        if (cargo.onGround() || ctx.ticksActive() >= FLIGHT_TIMEOUT_TICK) {
            runImpact(ctx, level, x, y, z);
            cargo.discard();
            return;
        }
        if (!ctx.mobGriefingAllowed() && cargo.getDeltaMovement().y < 0.0
            && aboutToLand(level, cargo)) {
            // mobGriefing off: impact now and discard the
            // falling block before its own tick can place it.
            runImpact(ctx, level, x, y, z);
            cargo.discard();
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

        BlockState cargoState = Blocks.STONE.defaultBlockState();
        if (state.contains("cargo_state")) {
            cargoState = NbtUtils.readBlockState(
                level.registryAccess().lookupOrThrow(Registries.BLOCK),
                state.getCompoundOrEmpty("cargo_state"));
        }
        level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1.2f, 0.7f);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, cargoState),
            x, y + 0.3, z, 16, 1.1, 0.5, 1.1, 0.18);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, cargoState),
            x, y + 0.3, z, 10, 1.1, 0.5, 1.1, 0.18);

        Mob mob = ctx.mob();
        List<LivingEntity> victims = DonkeyAbilitySupport.enemiesInBox(ctx, x, y, z, IMPACT_RADIUS, 2.0, 2.5);
        for (LivingEntity victim : victims) {
            victim.hurtServer(level, level.damageSources().mobAttack(mob), IMPACT_DAMAGE);
            Vec3 away = new Vec3(victim.getX() - x, 0.0, victim.getZ() - z);
            away = away.lengthSqr() < 1.0e-4 ? new Vec3(1.0, 0.0, 0.0) : away.normalize();
            victim.setDeltaMovement(away.x * 1.0, 0.5, away.z * 1.0);
            victim.hurtMarked = true;
        }
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
        // A still-showing marker and a still-falling cargo are
        // discarded with no impact: a terminated catapult
        // never lands. A cargo that already landed and placed
        // its block (mobGriefing on) is permanent terrain and
        // is deliberately not touched here.
        Entity marker = DonkeyAbilitySupport.resolveStored(level, state, "marker");
        if (marker != null) {
            marker.discard();
        }
        if (state.getIntOr("phase", 0) == 1) {
            Entity cargo = DonkeyAbilitySupport.resolveStored(level, state, "cargo");
            if (cargo != null) {
                cargo.discard();
            }
        }
    }
}
