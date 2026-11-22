package mod.advancedmobs.ability.allay;

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
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * Fetch and Drop (Allay ability 2 of 5, attack).
 *
 * WHAT IT DOES: the allay swoops in straining under a heavy
 * prop, an anvil or a stone block shown as a block display
 * riding smoothly overhead, flies it out over its target,
 * and drops it squarely on the victim's head as a real
 * falling block. The landing bursts in block particles and
 * falling block damage, and the allay is already off
 * fetching the next one: two drops per activation.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the allay's current target within 14 blocks (distance
 *   squared 196).
 * - The fetch (start of each cycle): the prop is chosen by
 *   the allay's random source, ANVIL or STONE, stored in the
 *   state tag as block state NBT. A block display of the
 *   prop at scale 1.0 is created and snapped every tick to
 *   the point above the allay (feet plus bounding box height
 *   plus 0.7), gliding on its 2-tick teleport duration.
 * - The carry (per tick): the allay steers by velocity write
 *   toward a hover point 3.2 blocks above its target's live
 *   position at a capped speed of 0.55 blocks per tick. The
 *   drop fires when the allay is within 1.2 blocks
 *   horizontally of the target after at least 8 carry ticks,
 *   or unconditionally at 40 carry ticks (aimed at wherever
 *   the target then is). With no live target the allay holds
 *   position and the 40-tick drop fires straight down.
 * - The drop: the display is discarded and a real
 *   FallingBlockEntity of the prop state spawns at the held
 *   position (FallingBlockEntity.fall), with dropItem
 *   disabled so a failed landing never scatters an item, and
 *   its own vanilla entity-crush damage zeroed through
 *   setHurtsEntities(0, 0): the impact below is the one
 *   damage number of record, dealt with the falling_block
 *   damage type credited to the allay, which is the design's
 *   "real falling block damage" made predictable. Its
 *   velocity is set to a gentle (0, -0.1, 0) so it drops
 *   squarely. An ALLAY_THROW sound (volume 1.0, pitch 0.9)
 *   marks the release.
 * - The landing (tracked every tick): when the falling block
 *   resolves (the entity is gone because it landed and, in
 *   mobGriefing mode, placed its block by vanilla falling
 *   block rules), or is seen on the ground, the impact runs
 *   at its last known position. With mobGriefing OFF the
 *   prop is intercepted just above the ground (the Icicle
 *   Fall pattern: falling, and the gap to the first
 *   collidable block below within one tick of travel) and
 *   discarded before its own tick can place the block, so
 *   no block is ever placed; the impact still runs in full.
 *   Hard timeout: after 60 flight ticks a still-flying prop
 *   impacts at its last position and is discarded.
 * - The impact: 12 BLOCK particles of the prop state and 6
 *   POOF; an ANVIL_LAND sound (volume 0.9, pitch 1.0) for an
 *   anvil or GENERIC_EXPLODE (volume 0.7, pitch 1.2) for
 *   stone; every enemy (package rules) within radius 2.0
 *   (1.5 below to 2.0 above the impact) takes 6.0 damage
 *   through level.damageSources().fallingBlock(caster), the
 *   falling block damage type credited to the allay.
 * - The second fetch: after the first impact the cycle
 *   repeats once with a freshly rolled prop. After the
 *   second impact the ability finishes 5 ticks later; hard
 *   cap 200 ticks.
 * - end(): a still-held display and a still-flying prop are
 *   discarded with no impact: a terminated drop never lands,
 *   which also guarantees a mobGriefing-off world never
 *   receives the prop's block from a dead allay. A prop that
 *   already landed and placed its block (mobGriefing on) is
 *   permanent terrain and is deliberately not touched.
 *
 * WHAT IT LOOKS LIKE: a tiny allay wobbling under a full
 * anvil, a swoop out over the victim, a whistle-drop, and a
 * bursting crash, twice in a row.
 *
 * PERFORMANCE: the carry is one steering write, one UUID
 * resolve, and one snap per tick; the flight is one UUID
 * resolve and one position read per tick, plus at most 4
 * block reads on the mobGriefing-off landing check; each
 * impact is one bounded entity query. Particle budget per
 * activation: 18 per impact, 2 impacts, total at most 36
 * particles.
 *
 * TERRAIN: the falling prop is the package's one block
 * change and follows the standing rule exactly: permanent
 * placement only when mobGriefing is on (vanilla falling
 * block landing), intercepted and discarded with no
 * placement when it is off, impact effects running either
 * way. See the class comment of AllayAbilitySupport.
 *
 * State tag keys: "phase" (0 carrying, 1 falling, 2 done),
 * "cycle" (0 first prop, 1 second), "carry_ticks" (ticks in
 * the current carry), "prop_state" (the prop's block state
 * NBT), "anvil" (whether the current prop is the anvil),
 * "display" (the held display's UUID), "prop" (the falling
 * block's UUID), "px"/"py"/"pz" (its last known position),
 * "flight_ticks" (ticks in the current fall), "impact_tick"
 * (the tick the last impact ran).
 */
public final class FetchAndDropAbility implements MobAbility {

    private static final int MIN_CARRY_TICKS = 8;
    private static final int MAX_CARRY_TICKS = 40;
    private static final int FLIGHT_TIMEOUT_TICKS = 60;
    private static final int HARD_FINISH_TICKS = 200;
    private static final int CYCLES = 2;
    private static final float IMPACT_DAMAGE = 6.0f;
    private static final double IMPACT_RADIUS = 2.0;
    private static final double CARRY_SPEED = 0.55;
    private static final double HOVER_HEIGHT = 3.2;
    private static final double DROP_RANGE = 1.2;

    @Override
    public String id() {
        return "fetch_and_drop";
    }

    @Override
    public String displayName() {
        return "Fetch and Drop";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        state.putInt("cycle", 0);
        beginCycle(ctx);
    }

    /** Starts one fetch: roll the prop, build its display, reset the carry. */
    private void beginCycle(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        CompoundTag state = ctx.state();
        Mob caster = ctx.mob();
        state.putInt("phase", 0);
        state.putInt("carry_ticks", 0);
        boolean anvil = ctx.random().nextBoolean();
        state.putBoolean("anvil", anvil);
        BlockState propState = (anvil ? Blocks.ANVIL : Blocks.STONE).defaultBlockState();
        state.put("prop_state", NbtUtils.writeBlockState(propState));
        if (level == null) {
            return;
        }
        Entity display = AllayAbilitySupport.createBlockDisplay(level, propState, 1.0f);
        if (display != null) {
            display.snapTo(caster.getX(), overheadY(caster), caster.getZ());
            level.addFreshEntity(display);
            state.store("display", UUIDUtil.CODEC, display.getUUID());
        }
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.ALLAY_ITEM_TAKEN, SoundSource.HOSTILE, 1.0f, 0.8f);
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
            tickCarry(ctx, level);
        } else if (phase == 1) {
            trackProp(ctx, level);
        }
        // Phase 2: both impacts ran; nothing left to do.
    }

    /** The carry: keep the prop overhead and fly it out over the target. */
    private void tickCarry(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        Mob caster = ctx.mob();
        int carryTicks = state.getIntOr("carry_ticks", 0) + 1;
        state.putInt("carry_ticks", carryTicks);

        Entity display = AllayAbilitySupport.resolveStored(level, state, "display");
        if (display != null) {
            display.snapTo(caster.getX(), overheadY(caster), caster.getZ());
        }

        LivingEntity target = ctx.target();
        if (target != null) {
            AllayAbilitySupport.steerToward(ctx,
                target.getX(), target.getY() + HOVER_HEIGHT, target.getZ(), CARRY_SPEED);
            double dx = caster.getX() - target.getX();
            double dz = caster.getZ() - target.getZ();
            boolean overTarget = dx * dx + dz * dz <= DROP_RANGE * DROP_RANGE;
            if ((overTarget && carryTicks >= MIN_CARRY_TICKS) || carryTicks >= MAX_CARRY_TICKS) {
                doDrop(ctx, level);
            }
        } else if (carryTicks >= MAX_CARRY_TICKS) {
            doDrop(ctx, level);
        }
    }

    /** The drop: swap the held display for a real falling block over the target. */
    private void doDrop(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        Mob caster = ctx.mob();
        double dropX = caster.getX();
        double dropY = overheadY(caster);
        double dropZ = caster.getZ();
        Entity display = AllayAbilitySupport.resolveStored(level, state, "display");
        if (display != null) {
            dropX = display.getX();
            dropY = display.getY();
            dropZ = display.getZ();
            display.discard();
        }

        FallingBlockEntity prop = FallingBlockEntity.fall(level,
            BlockPos.containing(dropX, dropY, dropZ), propState(ctx, level));
        if (prop == null) {
            runImpact(ctx, level, dropX, dropY, dropZ);
            return;
        }
        // A failed landing must never scatter a prop item, and
        // the impact below is the one damage number of record.
        prop.dropItem = false;
        prop.disableDrop();
        prop.setHurtsEntities(0.0f, 0);
        prop.setDeltaMovement(0.0, -0.1, 0.0);
        prop.hurtMarked = true;
        state.store("prop", UUIDUtil.CODEC, prop.getUUID());
        state.putDouble("px", prop.getX());
        state.putDouble("py", prop.getY());
        state.putDouble("pz", prop.getZ());
        state.putInt("flight_ticks", 0);
        state.putInt("phase", 1);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.ALLAY_THROW, SoundSource.HOSTILE, 1.0f, 0.9f);
    }

    /**
     * Watches the falling prop: refreshes its last known
     * position, runs the impact when it lands, and, with
     * mobGriefing off, intercepts it just above the ground so
     * no block is ever placed (see the TERRAIN note in the
     * class comment).
     */
    private void trackProp(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        state.putInt("flight_ticks", state.getIntOr("flight_ticks", 0) + 1);
        Entity prop = AllayAbilitySupport.resolveStored(level, state, "prop");
        double x = state.getDoubleOr("px", ctx.mob().getX());
        double y = state.getDoubleOr("py", ctx.mob().getY());
        double z = state.getDoubleOr("pz", ctx.mob().getZ());
        if (prop == null) {
            // Landed and resolved itself (placing its block if
            // mobGriefing allowed it): impact at the last spot.
            runImpact(ctx, level, x, y, z);
            return;
        }
        x = prop.getX();
        y = prop.getY();
        z = prop.getZ();
        state.putDouble("px", x);
        state.putDouble("py", y);
        state.putDouble("pz", z);
        if (prop.onGround() || state.getIntOr("flight_ticks", 0) >= FLIGHT_TIMEOUT_TICKS) {
            runImpact(ctx, level, x, y, z);
            prop.discard();
            return;
        }
        if (!ctx.mobGriefingAllowed() && prop.getDeltaMovement().y < 0.0
            && aboutToLand(level, prop)) {
            // mobGriefing off: impact now and discard the
            // falling block before its own tick can place it.
            runImpact(ctx, level, x, y, z);
            prop.discard();
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

    /** The landing burst: debris, sound, and falling block damage. Runs exactly once per drop. */
    private void runImpact(AbilityContext ctx, ServerLevel level, double x, double y, double z) {
        CompoundTag state = ctx.state();
        BlockState propState = propState(ctx, level);
        boolean anvil = state.getBooleanOr("anvil", false);
        if (anvil) {
            level.playSound(null, x, y, z, SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 0.9f, 1.0f);
        } else {
            level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 0.7f, 1.2f);
        }
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, propState),
            x, y + 0.3, z, 12, 0.8, 0.4, 0.8, 0.15);
        level.sendParticles(ParticleTypes.POOF, x, y + 0.3, z, 6, 0.7, 0.3, 0.7, 0.05);

        Mob caster = ctx.mob();
        List<LivingEntity> victims = AllayAbilitySupport.enemiesInBox(ctx, x, y, z, IMPACT_RADIUS, 1.5, 2.0);
        for (LivingEntity victim : victims) {
            victim.hurtServer(level, level.damageSources().fallingBlock(caster), IMPACT_DAMAGE);
        }

        int cycle = state.getIntOr("cycle", 0);
        if (cycle + 1 < CYCLES) {
            // Off fetching the next one, per the design.
            state.putInt("cycle", cycle + 1);
            beginCycle(ctx);
        } else {
            state.putInt("phase", 2);
            state.putInt("impact_tick", ctx.ticksActive());
        }
    }

    /** The current prop's block state, read back from the state tag (STONE fallback). */
    private BlockState propState(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        if (state.contains("prop_state")) {
            return NbtUtils.readBlockState(
                level.registryAccess().lookupOrThrow(Registries.BLOCK),
                state.getCompoundOrEmpty("prop_state"));
        }
        return Blocks.STONE.defaultBlockState();
    }

    /** The height of the held prop's center: just above the allay's head. */
    private static double overheadY(Mob mob) {
        return mob.getY() + mob.getBbHeight() + 0.7;
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
        // A still-held display and a still-flying prop are
        // discarded with no impact: a terminated drop never
        // lands. A prop that already landed and placed its
        // block (mobGriefing on) is permanent terrain and is
        // deliberately not touched here.
        Entity display = AllayAbilitySupport.resolveStored(level, state, "display");
        if (display != null) {
            display.discard();
        }
        if (state.getIntOr("phase", 0) == 1) {
            Entity prop = AllayAbilitySupport.resolveStored(level, state, "prop");
            if (prop != null) {
                prop.discard();
            }
        }
    }
}
