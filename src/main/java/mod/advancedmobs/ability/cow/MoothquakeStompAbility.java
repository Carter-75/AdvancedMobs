package mod.advancedmobs.ability.cow;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

/**
 * Moothquake Stomp (Cow ability 3 of 5, terrain / attack).
 *
 * WHAT IT DOES: the cow rears up, hangs for a heartbeat,
 * and slams both front hooves down hard enough to ring the
 * ground like a bell. The shockwave damages and launches
 * every enemy nearby, and the stomp pops whole dirt and
 * grass blocks around the cow up into the air as spinning
 * visual chunks that hang, then rain back down into place.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the cow on the ground and its current target within 8
 *   blocks (distance squared 64).
 * - Wind-up (ticks 1 to 9): navigation stopped every tick
 *   (never NoAi), 2 tan DUST motes puff at the hooves every
 *   2 ticks as the cow shifts its weight back. The cow's
 *   classic variant ambient sound plays at start at volume
 *   1.5, pitch 1.1 (the rising rear-up bellow).
 * - The slam (tick 10, exactly once, flagged in the state
 *   tag): GENERIC_EXPLODE at volume 1.2, pitch 0.8; 3
 *   EXPLOSION particles, 24 BLOCK_CRUMBLE and 12
 *   DUST_PILLAR of the ground block under the cow. Every
 *   enemy within radius 5.0 (1.0 below to 2.5 above) takes
 *   6.0 damage as a mob attack credited to the cow and is
 *   launched: 1.1 straight up plus 0.3 horizontal directly
 *   away from the cow, hurtMarked set.
 * - The popping blocks (also at tick 10): the columns in a
 *   Chebyshev radius of 4 around the cow are scanned (top
 *   down from 2 above the cow's feet to 4 below) for a
 *   poppable surface: a block of DIRT, GRASS_BLOCK,
 *   COARSE_DIRT, ROOTED_DIRT, PODZOL, or MYCELIUM with air
 *   above it. The first 8 found each get one block DISPLAY
 *   (scale 1.0) showing that block's own state, spawned at
 *   the block's center and tracked in the state tag with
 *   its home position. The REAL blocks never move: the
 *   displays are pure visuals riding on top of them, which
 *   is why the terrain rule's mobGriefing gate does not
 *   apply (the Truffle Tremor precedent).
 * - The rain (ticks 11 to 30, display age = ticks - 10):
 *   each display is snapped per tick to its home position
 *   plus a height offset on a fixed arc: rising over ages
 *   1 to 8 up to +2.2 blocks, hovering at ages 9 to 12,
 *   falling back over ages 13 to 20 to exactly home. At
 *   age 20 (tick 30) every display is discarded. The arc
 *   is computed from the tick count alone, so a resumed
 *   activation after a chunk reload continues the rain
 *   from the right height.
 * - Duration: the ability finishes at tick 34, after the
 *   rain has landed. end() discards any display still
 *   tracked (a termination mid-rain), and is idempotent.
 *
 * WHAT IT LOOKS LIKE: a cow rearing with dust at its
 * hooves, a deep boom, a ring of crumbling earth, enemies
 * bouncing into the air, and eight chunks of dirt and
 * grass leaping up around it, hanging, and thumping back
 * down into the ground they came from.
 *
 * PERFORMANCE: the wind-up is one navigation stop plus an
 * emission every 2 ticks. The slam is one bounded
 * radius-5.0 query plus one bounded column scan (at most
 * 81 columns of at most 7 reads, stopping at 8 finds). The
 * rain snaps at most 8 displays per tick and resolves at
 * most 8 UUIDs. Particle budget per activation: wind-up 5
 * times 2 = 10, slam 3 + 24 + 12 = 39, total at most 49
 * particles.
 *
 * TERRAIN: no real block is placed, broken, or moved by
 * this ability; the flying chunks are display entities.
 * The mobGriefing gate does not apply.
 *
 * State tag keys: "slammed" (0 until the slam, 1 after),
 * "blocks" (list of compounds, one per popped display:
 * "id", the display's UUID; "hx", "hy", "hz", its home
 * snap position).
 */
public final class MoothquakeStompAbility implements MobAbility {

    private static final int WINDUP_TICKS = 9;
    private static final int SLAM_TICK = 10;
    private static final int RAIN_END_TICK = 30;
    private static final int FINISH_TICK = 34;
    private static final int MAX_DISPLAYS = 8;
    private static final int SCAN_RADIUS = 4;
    private static final double POP_HEIGHT = 2.2;
    private static final double SLAM_RADIUS = 5.0;
    private static final double SLAM_DAMAGE = 6.0;
    private static final double LAUNCH_UP = 1.1;
    private static final double LAUNCH_OUT = 0.3;
    private static final Set<Block> POPPABLE = Set.of(
        Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.COARSE_DIRT,
        Blocks.ROOTED_DIRT, Blocks.PODZOL, Blocks.MYCELIUM);

    @Override
    public String id() {
        return "moothquake_stomp";
    }

    @Override
    public String displayName() {
        return "Moothquake Stomp";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().onGround()
            && ctx.mob().distanceToSqr(target) <= 64.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().putInt("slammed", 0);
        ctx.state().put("blocks", new ListTag());
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();
        if (level != null && CowAbilitySupport.cowSound() != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                CowAbilitySupport.cowSound(), SoundSource.HOSTILE, 1.5f, 1.1f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();

        if (ticks < SLAM_TICK) {
            // The rear-up: rooted, weight shifting back.
            caster.getNavigation().stop();
            if (ticks % 2 == 0) {
                BlockState ground = groundState(level, caster);
                level.sendParticles(new BlockParticleOption(ParticleTypes.DUST_PILLAR, ground),
                    caster.getX(), caster.getY() + 0.2, caster.getZ(),
                    2, 0.5, 0.1, 0.5, 0.05);
            }
            return;
        }
        if (ctx.state().getIntOr("slammed", 0) == 0) {
            slam(ctx, level);
            ctx.state().putInt("slammed", 1);
            return;
        }
        if (ticks <= RAIN_END_TICK) {
            rainTick(ctx, level, ticks - SLAM_TICK);
        }
    }

    /** The slam itself: sound, shockwave, launch, and the popping block displays. */
    private void slam(AbilityContext ctx, ServerLevel level) {
        Mob caster = ctx.mob();
        BlockState ground = groundState(level, caster);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1.2f, 0.8f);
        level.sendParticles(ParticleTypes.EXPLOSION,
            caster.getX(), caster.getY() + 0.5, caster.getZ(),
            3, 1.2, 0.3, 1.2, 0.0);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, ground),
            caster.getX(), caster.getY() + 0.3, caster.getZ(),
            24, 2.4, 0.3, 2.4, 0.15);
        level.sendParticles(new BlockParticleOption(ParticleTypes.DUST_PILLAR, ground),
            caster.getX(), caster.getY() + 0.3, caster.getZ(),
            12, 2.0, 0.4, 2.0, 0.1);

        List<LivingEntity> victims = CowAbilitySupport.enemiesInBox(
            ctx, caster.getX(), caster.getY(), caster.getZ(), SLAM_RADIUS, 1.0, 2.5);
        for (LivingEntity victim : victims) {
            victim.hurtServer(level, level.damageSources().mobAttack(caster), (float) SLAM_DAMAGE);
            double dx = victim.getX() - caster.getX();
            double dz = victim.getZ() - caster.getZ();
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length < 0.001) {
                Vec3 look = caster.getLookAngle();
                dx = look.x;
                dz = look.z;
                length = Math.sqrt(dx * dx + dz * dz);
                if (length < 0.001) {
                    dx = 0.0;
                    dz = 1.0;
                    length = 1.0;
                }
            }
            victim.setDeltaMovement(dx / length * LAUNCH_OUT, LAUNCH_UP, dz / length * LAUNCH_OUT);
            victim.hurtMarked = true;
        }

        popBlocks(ctx, level);
    }

    /**
     * Spawns the popping block displays: the first 8
     * poppable surface blocks in the scan area (see the
     * class comment), each tracked with its home position.
     */
    private void popBlocks(AbilityContext ctx, ServerLevel level) {
        Mob caster = ctx.mob();
        BlockPos base = caster.blockPosition();
        ListTag blocks = new ListTag();
        outer:
        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                if (blocks.size() >= MAX_DISPLAYS) {
                    break outer;
                }
                if (dx == 0 && dz == 0) {
                    continue; // Never pop the block the cow stands on.
                }
                for (int dy = 2; dy >= -4; dy--) {
                    BlockPos pos = base.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    if (!POPPABLE.contains(state.getBlock())) {
                        continue;
                    }
                    if (!level.getBlockState(pos.above()).isAir()) {
                        break; // Buried dirt is not a surface block.
                    }
                    Entity display = CowAbilitySupport.createBlockDisplay(level, state, 1.0f);
                    if (display == null) {
                        break;
                    }
                    double hx = pos.getX() + 0.5;
                    double hy = pos.getY() + 0.5;
                    double hz = pos.getZ() + 0.5;
                    display.snapTo(hx, hy, hz, 0.0f, 0.0f);
                    if (!level.addFreshEntity(display)) {
                        break;
                    }
                    CompoundTag entry = new CompoundTag();
                    entry.store("id", UUIDUtil.CODEC, display.getUUID());
                    entry.putDouble("hx", hx);
                    entry.putDouble("hy", hy);
                    entry.putDouble("hz", hz);
                    blocks.add(entry);
                    break;
                }
            }
        }
        ctx.state().put("blocks", blocks);
    }

    /** One rain tick: snap every surviving display along the fixed arc for its age. */
    private void rainTick(AbilityContext ctx, ServerLevel level, int age) {
        double offset;
        if (age <= 8) {
            offset = POP_HEIGHT * age / 8.0;
        } else if (age <= 12) {
            offset = POP_HEIGHT;
        } else {
            offset = POP_HEIGHT * Math.max(0, 20 - age) / 8.0;
        }
        ListTag blocks = ctx.state().getListOrEmpty("blocks");
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag entry = blocks.getCompoundOrEmpty(i);
            Entity display = CowAbilitySupport.resolveTracked(level, entry);
            if (display == null) {
                continue;
            }
            if (age >= 20) {
                display.discard();
                continue;
            }
            display.snapTo(entry.getDoubleOr("hx", display.getX()),
                entry.getDoubleOr("hy", display.getY()) + offset,
                entry.getDoubleOr("hz", display.getZ()), 0.0f, 0.0f);
        }
        if (age >= 20) {
            ctx.state().put("blocks", new ListTag());
        }
    }

    /** The block state under the cow's feet, or dirt when it stands on air or fluid. */
    private BlockState groundState(ServerLevel level, Mob caster) {
        BlockState below = level.getBlockState(caster.blockPosition().below());
        return below.isAir() ? Blocks.DIRT.defaultBlockState() : below;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= FINISH_TICK;
    }

    @Override
    public void end(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag blocks = ctx.state().getListOrEmpty("blocks");
        for (int i = 0; i < blocks.size(); i++) {
            Entity display = CowAbilitySupport.resolveTracked(level, blocks.getCompoundOrEmpty(i));
            if (display != null) {
                display.discard();
            }
        }
    }
}
