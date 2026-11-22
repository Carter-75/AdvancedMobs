package mod.advancedmobs.ability.stray;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;
import java.util.UUID;

/**
 * Icicle Fall (Stray ability 1 of 5, attack).
 *
 * WHAT IT DOES: the stray raises its bow to the sky and icicles
 * form overhead, SNOWFLAKE particles thickening into a glittering
 * ceiling above its target. Then falling block icicles of packed
 * ice rain onto the target area. Each icicle deals typed freeze
 * damage where it lands and shatters into BLOCK ice shards and
 * WHITE_SMOKE.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists, between 3 and 24 blocks
 *   away (distance squared 9 to 576). The stray snipes from range;
 *   point blank targets are for its bow, not the sky.
 * - Telegraph: 20 ticks. start() records the target's position at
 *   that moment (the icicles aim at where the target stood, so a
 *   target that keeps moving can dodge the volley). Every 2 ticks
 *   of the telegraph, 4 SNOWFLAKE particles drift at the forming
 *   ceiling, 9.5 blocks above the recorded position, spread over
 *   the 2.5 block impact radius: the visible warning.
 * - The volley, at tick 20: 7 icicles, each a FallingBlockEntity
 *   of packed ice (FallingBlockEntity.fall, the /summon
 *   falling_block path) spawning 9 blocks above the recorded
 *   position: one at the center and 6 at random offsets inside
 *   radius 2.5. Each icicle has dropItem disabled twice over
 *   (dropItem field and disableDrop), so a failed landing never
 *   scatters a packed ice item.
 * - Impact: an icicle impacts when it lands (entity gone after
 *   placing itself, or found resting onGround) or, in the
 *   mobGriefing-off case below, just before it would land. The
 *   impact hits every enemy (StrayAbilitySupport rules) within
 *   horizontal radius 1.7 of the impact point, between 1.5 below
 *   and 2 above it, for 5.0 FREEZE damage
 *   (level.damageSources().freeze(), the typed freeze damage the
 *   design calls for; no attacker is attached, the same approach
 *   Seismic Slam uses for its fall damage), plus 8 BLOCK particles
 *   of packed ice and 4 WHITE_SMOKE, and a GLASS_BREAK sound at
 *   volume 0.7.
 * - The ability finishes when every icicle has impacted, with a
 *   hard finish at tick 160 in case one falls into the void or
 *   gets stuck on a ledge.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6): a falling block that lands
 * places its block, which is a permanent terrain change, so it is
 * gated by AbilityContext.mobGriefingAllowed():
 * - mobGriefing ON: icicles land naturally and the packed ice
 *   blocks they place STAY, a permanent scatter of ice spikes on
 *   the battlefield. The impact damage and shatter particles run
 *   as the block settles.
 * - mobGriefing OFF: no block may be placed. Each falling icicle
 *   is watched every tick; when its underside comes within one
 *   tick of falling travel above the first solid block below it,
 *   the impact runs at that spot and the falling block entity is
 *   discarded before it can place anything. The damage, particles,
 *   and sound are identical; the world is left untouched.
 *
 * WHAT IT LOOKS LIKE: a glittering SNOWFLAKE ceiling gathering
 * over the target for a second, then seven packed ice spikes
 * dropping out of it, each bursting into ice shards and white
 * smoke with a glassy crack where it lands.
 *
 * PERFORMANCE: the telegraph sends particles every 2 ticks only.
 * The tracking phase runs one UUID resolve and one position read
 * per live icicle per tick (at most 7), plus, only when
 * mobGriefing is off, a downward collision scan of at most 4
 * blocks per icicle. The single bounded enemy query runs once per
 * impact, at most 7 times per activation. Particle budget per
 * activation: telegraph 10 bursts times 4 = 40 SNOWFLAKE, impacts
 * 7 times (8 BLOCK + 4 WHITE_SMOKE) = 84, total at most 124
 * particles.
 *
 * CLEANUP: end() discards every icicle still tracked, so a stray
 * that dies mid-volley leaves no falling blocks hanging in the
 * air. With mobGriefing on, blocks already placed by landed
 * icicles are permanent terrain and are deliberately NOT touched.
 *
 * State tag keys: "tx", "ty", "tz" (the recorded target position,
 * doubles), "spawned" (1 once the volley has dropped), "icicles"
 * (list of compounds, one per live icicle: "id" the falling
 * block's UUID, "x", "y", "z" its last known position, refreshed
 * every tick so an impact still lands at the right spot after the
 * entity resolves itself away).
 */
public final class IcicleFallAbility implements MobAbility {

    static final int TELEGRAPH_TICKS = 20;
    private static final int HARD_FINISH_TICKS = 160;
    private static final int ICICLE_COUNT = 7;
    private static final double SPAWN_HEIGHT = 9.0;
    private static final double IMPACT_RADIUS = 1.7;
    private static final float FREEZE_DAMAGE = 5.0f;
    private static final BlockParticleOption ICE_SHARDS =
        new BlockParticleOption(ParticleTypes.BLOCK, Blocks.PACKED_ICE.defaultBlockState());

    @Override
    public String id() {
        return "icicle_fall";
    }

    @Override
    public String displayName() {
        return "Icicle Fall";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null) {
            return false;
        }
        double distanceSqr = ctx.mob().distanceToSqr(target);
        return distanceSqr >= 9.0 && distanceSqr <= 576.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        CompoundTag state = ctx.state();
        state.put("icicles", new ListTag());
        if (target == null) {
            return;
        }
        // The volley aims at where the target stands NOW; the
        // telegraph is the target's chance to move.
        state.putDouble("tx", target.getX());
        state.putDouble("ty", target.getY());
        state.putDouble("tz", target.getZ());
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        CompoundTag state = ctx.state();
        double tx = state.getDoubleOr("tx", ctx.mob().getX());
        double ty = state.getDoubleOr("ty", ctx.mob().getY());
        double tz = state.getDoubleOr("tz", ctx.mob().getZ());

        if (ticks < TELEGRAPH_TICKS) {
            // The ceiling gathers: SNOWFLAKE drifting thick over
            // the impact area every 2 ticks.
            if (ticks % 2 == 0) {
                level.sendParticles(ParticleTypes.SNOWFLAKE, tx, ty + SPAWN_HEIGHT + 0.5, tz,
                    4, 2.5, 0.5, 2.5, 0.02);
            }
            return;
        }
        if (state.getIntOr("spawned", 0) == 0) {
            spawnVolley(ctx, level, tx, ty, tz);
            state.putInt("spawned", 1);
            return;
        }
        trackIcicles(ctx, level);
    }

    /** Drops the 7 packed ice icicles over the recorded position. */
    private static void spawnVolley(AbilityContext ctx, ServerLevel level, double tx, double ty, double tz) {
        ListTag icicles = new ListTag();
        for (int i = 0; i < ICICLE_COUNT; i++) {
            double ox = i == 0 ? 0.0 : (ctx.random().nextDouble() * 5.0 - 2.5);
            double oz = i == 0 ? 0.0 : (ctx.random().nextDouble() * 5.0 - 2.5);
            BlockPos spawnPos = BlockPos.containing(tx + ox, ty + SPAWN_HEIGHT, tz + oz);
            FallingBlockEntity icicle = FallingBlockEntity.fall(level, spawnPos,
                Blocks.PACKED_ICE.defaultBlockState());
            if (icicle == null) {
                continue;
            }
            // A failed landing must never scatter a packed ice item.
            icicle.dropItem = false;
            icicle.disableDrop();
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, icicle.getUUID());
            entry.putDouble("x", icicle.getX());
            entry.putDouble("y", icicle.getY());
            entry.putDouble("z", icicle.getZ());
            icicles.add(entry);
        }
        ctx.state().put("icicles", icicles);
    }

    /**
     * Watches the live icicles: refreshes their last known
     * positions, runs impacts for the ones that landed, and, with
     * mobGriefing off, intercepts them just above the ground so no
     * block is ever placed (see the TERRAIN RULE in the class
     * comment). Resolved icicles are pruned from the state tag.
     */
    private static void trackIcicles(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        ListTag tracked = state.getListOrEmpty("icicles");
        ListTag remaining = new ListTag();
        boolean griefing = ctx.mobGriefingAllowed();
        for (int i = 0; i < tracked.size(); i++) {
            CompoundTag entry = tracked.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            Entity entity = id != null ? level.getEntity(id) : null;
            double x = entry.getDoubleOr("x", 0.0);
            double y = entry.getDoubleOr("y", 0.0);
            double z = entry.getDoubleOr("z", 0.0);
            if (entity == null) {
                // Landed and resolved itself (placing its block if
                // mobGriefing allowed it): the impact runs at the
                // last known position.
                runImpact(ctx, level, x, y, z);
                continue;
            }
            x = entity.getX();
            y = entity.getY();
            z = entity.getZ();
            entry.putDouble("x", x);
            entry.putDouble("y", y);
            entry.putDouble("z", z);
            if (entity.onGround()) {
                runImpact(ctx, level, x, y, z);
                entity.discard();
                continue;
            }
            if (!griefing && entity.getDeltaMovement().y < 0.0
                && aboutToLand(level, entity)) {
                // mobGriefing off: impact now and discard the
                // falling block before its own tick can place it.
                runImpact(ctx, level, x, y, z);
                entity.discard();
                continue;
            }
            remaining.add(entry);
        }
        state.put("icicles", remaining);
    }

    /**
     * Whether the falling block's underside is within one tick of
     * falling travel above the first collidable block below it:
     * scans down at most 4 blocks from the block under the entity.
     * Only used in the mobGriefing-off interception.
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

    /** One icicle's impact: freeze damage to enemies at the spot, shards, smoke, and the crack. */
    private static void runImpact(AbilityContext ctx, ServerLevel level, double x, double y, double z) {
        List<LivingEntity> victims = StrayAbilitySupport.enemiesInBox(ctx, x, y, z, IMPACT_RADIUS, 1.5, 2.0);
        for (LivingEntity victim : victims) {
            victim.hurtServer(level, level.damageSources().freeze(), FREEZE_DAMAGE);
        }
        level.sendParticles(ICE_SHARDS, x, y + 0.3, z, 8, 0.7, 0.4, 0.7, 0.15);
        level.sendParticles(ParticleTypes.WHITE_SMOKE, x, y + 0.3, z, 4, 0.6, 0.3, 0.6, 0.04);
        level.playSound(null, x, y, z, SoundEvents.GLASS_BREAK, SoundSource.HOSTILE,
            0.7f, 0.9f + ctx.random().nextFloat() * 0.2f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks >= HARD_FINISH_TICKS) {
            return true;
        }
        return ticks >= TELEGRAPH_TICKS
            && ctx.state().getIntOr("spawned", 0) == 1
            && ctx.state().getListOrEmpty("icicles").isEmpty();
    }

    @Override
    public void end(AbilityContext ctx) {
        // No falling block outlives the activation. Blocks already
        // placed by landed icicles (mobGriefing on) are permanent
        // terrain and are deliberately NOT touched here.
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag tracked = ctx.state().getListOrEmpty("icicles");
        for (int i = 0; i < tracked.size(); i++) {
            CompoundTag entry = tracked.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
            Entity entity = id != null ? level.getEntity(id) : null;
            if (entity != null) {
                entity.discard();
            }
        }
    }
}
