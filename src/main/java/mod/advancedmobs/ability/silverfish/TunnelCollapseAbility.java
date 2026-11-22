package mod.advancedmobs.ability.silverfish;

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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;
import java.util.UUID;

/**
 * Tunnel Collapse (Silverfish ability 4 of 5, terrain).
 *
 * WHAT IT DOES: the silverfish chews through the supports
 * and the ceiling comes down: falling block gravel and stone
 * rain onto its enemies with FALLING_DUST choking the air,
 * while real blocks are eaten out of the ceiling slab above
 * them. Victims take typed falling damage in the collapse and
 * are left SLOWED, digging themselves out of the rubble.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists, between 4 and 16
 *   blocks away (distance squared 16 to 256). The collapse is
 *   dropped on a fight at burrowing range, not point blank
 *   and not across the map.
 * - Telegraph: 15 ticks. start() records the target's
 *   position at that moment (the collapse aims at where the
 *   target stood, so a target that keeps moving can dodge
 *   it). Every 3 ticks of the telegraph, 4 stone FALLING_DUST
 *   particles sift down from 6 blocks above the recorded
 *   position, spread over the 3 block collapse radius, and
 *   start() plays a low GRAVEL_BREAK (volume 0.6, pitch 0.8)
 *   up at the ceiling: the visible and audible warning that
 *   the roof is going.
 * - The volley, at tick 15, in two parts:
 *   1. The eaten supports (mobGriefing ON only, see the
 *      TERRAIN RULE): a one-shot scan of the ceiling slab,
 *      the 7 by 7 area around the recorded position at heights
 *      4 to 7 above it, lowest layer first. Up to 6 diggable
 *      blocks (SilverfishAbilitySupport.isDiggable: real
 *      block, no fluid, no block entity, destroy speed not
 *      negative) are eaten: set to air, permanently, dropping
 *      nothing. These are the supports the fish chewed
 *      through; outdoors or under a thin roof the scan simply
 *      finds fewer, and the debris below still falls.
 *   2. The debris: up to 3 enemies (SilverfishAbilitySupport
 *      rules) inside radius 3.0 of the recorded position are
 *      chosen; over each, 3 falling blocks drop from 5.5, 6.5,
 *      and 7.5 blocks above them (gravel, stone, gravel, with
 *      small horizontal offsets), each a FallingBlockEntity
 *      (FallingBlockEntity.fall, the /summon falling_block
 *      path of the design) with dropItem disabled twice over
 *      (dropItem field and disableDrop), so a failed landing
 *      never scatters an item. When no enemy is in the area,
 *      one volley of 3 drops on the recorded position instead.
 * - Impact: a debris block impacts when it lands (entity gone
 *   after placing itself, or found resting onGround) or, in
 *   the mobGriefing-off case below, just before it would land.
 *   The impact hits every enemy within horizontal radius 1.6
 *   of the impact point, between 1.5 below and 2 above it, for
 *   4.0 FALL damage (level.damageSources().fall(), the typed
 *   fall damage of the design; no attacker is attached, the
 *   same approach Seismic Slam uses) plus SLOWNESS I
 *   (amplifier 0) for 60 ticks (3 seconds): victims digging
 *   themselves out. Each impact bursts 8 BLOCK particles and
 *   5 FALLING_DUST of the debris block's own state, and plays
 *   its break sound (GRAVEL_BREAK or STONE_BREAK) at volume
 *   0.7.
 * - The ability finishes when every debris block has
 *   impacted, with a hard finish at tick 160 in case one falls
 *   into the void or gets stuck on a ledge.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6): this ability changes real
 * blocks two ways, and both are gated by
 * AbilityContext.mobGriefingAllowed():
 * - The eaten supports are a permanent removal, and they only
 *   happen with mobGriefing on. With it off, no block is
 *   eaten; the telegraph, debris damage, debuffs, and
 *   particles all run identically.
 * - A falling block that lands places its block, which is a
 *   permanent terrain change, so it is gated the same way
 *   Icicle Fall gates its icicles: with mobGriefing ON, debris
 *   lands naturally and the gravel and stone blocks it places
 *   STAY, a permanent scatter of rubble on the battlefield.
 *   With mobGriefing OFF, each falling debris block is watched
 *   every tick; when its underside comes within one tick of
 *   falling travel above the first solid block below it, the
 *   impact runs at that spot and the falling block entity is
 *   discarded before it can place anything. The damage,
 *   debuff, particles, and sound are identical; the world is
 *   left untouched.
 *
 * WHAT IT LOOKS LIKE: dust sifting from the ceiling for three
 * quarters of a second and a grinding crunch overhead, then
 * the roof comes down: nine chunks of gravel and stone
 * hammering the ground in bursts of rock shards and choking
 * dust, leaving rubble blocks scattered where they fell.
 *
 * PERFORMANCE: the telegraph sends particles every 3 ticks
 * only. The ceiling scan is one-shot at the volley: at most
 * 196 block reads, once per activation, and only when
 * mobGriefing is on. The tracking phase runs one UUID resolve
 * and one position read per live debris block per tick (at
 * most 9), plus, only when mobGriefing is off, a downward
 * collision scan of at most 4 blocks per block. The single
 * bounded enemy query for volley targets runs once, and one
 * per impact, at most 9 per activation. Particle budget per
 * activation: telegraph 5 bursts times 4 = 20 FALLING_DUST,
 * impacts 9 times (8 BLOCK + 5 FALLING_DUST) = 117, total at
 * most 137 particles.
 *
 * CLEANUP: end() discards every debris block still tracked,
 * so a fish that dies mid-collapse leaves no falling blocks
 * hanging in the air. With mobGriefing on, blocks already
 * placed by landed debris and blocks already eaten are
 * permanent terrain and are deliberately NOT touched.
 *
 * State tag keys: "tx", "ty", "tz" (the recorded target
 * position, doubles), "spawned" (1 once the volley has
 * dropped), "debris" (list of compounds, one per live debris
 * block: "id" the falling block's UUID, "kind" 0 for gravel
 * or 1 for stone, "x", "y", "z" its last known position,
 * refreshed every tick so an impact still lands at the right
 * spot after the entity resolves itself away).
 */
public final class TunnelCollapseAbility implements MobAbility {

    static final int VOLLEY_TICK = 15;
    private static final int HARD_FINISH_TICKS = 160;
    private static final int MAX_EATEN = 6;
    private static final int MAX_VOLLEY_TARGETS = 3;
    private static final double IMPACT_RADIUS = 1.6;
    private static final float FALL_DAMAGE = 4.0f;
    private static final int SLOWNESS_TICKS = 60;
    private static final int KIND_GRAVEL = 0;
    private static final int KIND_STONE = 1;
    private static final BlockParticleOption GRAVEL_BLOCK =
        new BlockParticleOption(ParticleTypes.BLOCK, Blocks.GRAVEL.defaultBlockState());
    private static final BlockParticleOption STONE_BLOCK =
        new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState());
    private static final BlockParticleOption GRAVEL_FALLING_DUST =
        new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.GRAVEL.defaultBlockState());
    private static final BlockParticleOption STONE_FALLING_DUST =
        new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.STONE.defaultBlockState());
    /** Debris per volley center: horizontal offsets and spawn heights, gravel then stone then gravel. */
    private static final double[][] DEBRIS = {
        {0.0, 5.5, 0.0}, {0.7, 6.5, -0.4}, {-0.7, 7.5, 0.4}
    };
    private static final int[] DEBRIS_KINDS = {KIND_GRAVEL, KIND_STONE, KIND_GRAVEL};

    @Override
    public String id() {
        return "tunnel_collapse";
    }

    @Override
    public String displayName() {
        return "Tunnel Collapse";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null) {
            return false;
        }
        double distanceSqr = ctx.mob().distanceToSqr(target);
        return distanceSqr >= 16.0 && distanceSqr <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        CompoundTag state = ctx.state();
        state.put("debris", new ListTag());
        if (target == null) {
            return;
        }
        // The collapse aims at where the target stands NOW; the
        // telegraph is the target's chance to move.
        state.putDouble("tx", target.getX());
        state.putDouble("ty", target.getY());
        state.putDouble("tz", target.getZ());

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            // The roof starts to go: a grinding crunch overhead.
            level.playSound(null, target.getX(), target.getY() + 6.0, target.getZ(),
                SoundEvents.GRAVEL_BREAK, SoundSource.HOSTILE, 0.6f, 0.8f);
        }
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

        if (ticks < VOLLEY_TICK) {
            // Dust sifts from the ceiling over the collapse
            // area every 3 ticks: the warning.
            if (ticks % 3 == 0) {
                level.sendParticles(STONE_FALLING_DUST, tx, ty + 6.0, tz,
                    4, 2.5, 0.4, 2.5, 0.02);
            }
            return;
        }
        if (state.getIntOr("spawned", 0) == 0) {
            dropCollapse(ctx, level, tx, ty, tz);
            state.putInt("spawned", 1);
            return;
        }
        trackDebris(ctx, level);
    }

    /**
     * The volley: eats the ceiling supports (mobGriefing on
     * only) and drops the debris over the enemies in the area
     * (see the class comment for the scan shape, the caps, and
     * the debris pattern).
     */
    private static void dropCollapse(AbilityContext ctx, ServerLevel level, double tx, double ty, double tz) {
        if (ctx.mobGriefingAllowed()) {
            eatSupports(level, tx, ty, tz);
        }

        List<LivingEntity> targets = SilverfishAbilitySupport.enemiesInBox(ctx, tx, ty, tz, 3.0, 2.0, 3.0);
        ListTag debris = new ListTag();
        if (targets.isEmpty()) {
            dropDebrisOver(ctx, level, tx, ty, tz, debris);
        } else {
            int centers = Math.min(targets.size(), MAX_VOLLEY_TARGETS);
            for (int i = 0; i < centers; i++) {
                LivingEntity victim = targets.get(i);
                dropDebrisOver(ctx, level, victim.getX(), victim.getY(), victim.getZ(), debris);
            }
        }
        ctx.state().put("debris", debris);
    }

    /**
     * Eats up to MAX_EATEN diggable blocks out of the ceiling
     * slab above the recorded position (heights 4 to 7, radius
     * 3, lowest layer first): the chewed supports. Permanent,
     * drops nothing, mobGriefing mode only (the caller checks).
     */
    private static void eatSupports(ServerLevel level, double tx, double ty, double tz) {
        int cx = (int) Math.floor(tx);
        int cz = (int) Math.floor(tz);
        int baseY = (int) Math.floor(ty);
        int eaten = 0;
        for (int dy = 4; dy <= 7 && eaten < MAX_EATEN; dy++) {
            for (int dx = -3; dx <= 3 && eaten < MAX_EATEN; dx++) {
                for (int dz = -3; dz <= 3 && eaten < MAX_EATEN; dz++) {
                    BlockPos pos = new BlockPos(cx + dx, baseY + dy, cz + dz);
                    if (SilverfishAbilitySupport.isDiggable(level, pos)) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                        eaten++;
                    }
                }
            }
        }
    }

    /** Drops the 3-debris pattern over one center point, tracked into the given list. */
    private static void dropDebrisOver(AbilityContext ctx, ServerLevel level, double cx, double cy, double cz,
                                       ListTag debris) {
        for (int i = 0; i < DEBRIS.length; i++) {
            int kind = DEBRIS_KINDS[i];
            BlockState blockState = kind == KIND_GRAVEL
                ? Blocks.GRAVEL.defaultBlockState() : Blocks.STONE.defaultBlockState();
            BlockPos spawnPos = BlockPos.containing(cx + DEBRIS[i][0], cy + DEBRIS[i][1], cz + DEBRIS[i][2]);
            FallingBlockEntity falling = FallingBlockEntity.fall(level, spawnPos, blockState);
            if (falling == null) {
                continue;
            }
            // A failed landing must never scatter an item.
            falling.dropItem = false;
            falling.disableDrop();
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, falling.getUUID());
            entry.putInt("kind", kind);
            entry.putDouble("x", falling.getX());
            entry.putDouble("y", falling.getY());
            entry.putDouble("z", falling.getZ());
            debris.add(entry);
        }
    }

    /**
     * Watches the live debris: refreshes last known positions,
     * runs impacts for the blocks that landed, and, with
     * mobGriefing off, intercepts them just above the ground so
     * no block is ever placed (see the TERRAIN RULE in the
     * class comment). Resolved blocks are pruned from the state
     * tag.
     */
    private static void trackDebris(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        ListTag tracked = state.getListOrEmpty("debris");
        ListTag remaining = new ListTag();
        boolean griefing = ctx.mobGriefingAllowed();
        for (int i = 0; i < tracked.size(); i++) {
            CompoundTag entry = tracked.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            Entity entity = id != null ? level.getEntity(id) : null;
            int kind = entry.getIntOr("kind", KIND_GRAVEL);
            double x = entry.getDoubleOr("x", 0.0);
            double y = entry.getDoubleOr("y", 0.0);
            double z = entry.getDoubleOr("z", 0.0);
            if (entity == null) {
                // Landed and resolved itself (placing its block
                // if mobGriefing allowed it): the impact runs at
                // the last known position.
                runImpact(ctx, level, x, y, z, kind);
                continue;
            }
            x = entity.getX();
            y = entity.getY();
            z = entity.getZ();
            entry.putDouble("x", x);
            entry.putDouble("y", y);
            entry.putDouble("z", z);
            if (entity.onGround()) {
                runImpact(ctx, level, x, y, z, kind);
                entity.discard();
                continue;
            }
            if (!griefing && entity.getDeltaMovement().y < 0.0
                && aboutToLand(level, entity)) {
                // mobGriefing off: impact now and discard the
                // falling block before its own tick can place it.
                runImpact(ctx, level, x, y, z, kind);
                entity.discard();
                continue;
            }
            remaining.add(entry);
        }
        state.put("debris", remaining);
    }

    /**
     * Whether the falling block's underside is within one tick
     * of falling travel above the first collidable block below
     * it: scans down at most 4 blocks from the block under the
     * entity. Only used in the mobGriefing-off interception.
     * (Same helper shape as Icicle Fall's.)
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

    /**
     * One debris block's impact: fall damage and SLOWNESS to
     * enemies at the spot, shards and dust of the block's own
     * state, and its break sound.
     */
    private static void runImpact(AbilityContext ctx, ServerLevel level, double x, double y, double z, int kind) {
        List<LivingEntity> victims = SilverfishAbilitySupport.enemiesInBox(ctx, x, y, z, IMPACT_RADIUS, 1.5, 2.0);
        for (LivingEntity victim : victims) {
            victim.hurtServer(level, level.damageSources().fall(), FALL_DAMAGE);
            victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, SLOWNESS_TICKS, 0));
        }
        BlockParticleOption shards = kind == KIND_GRAVEL ? GRAVEL_BLOCK : STONE_BLOCK;
        BlockParticleOption dust = kind == KIND_GRAVEL ? GRAVEL_FALLING_DUST : STONE_FALLING_DUST;
        level.sendParticles(shards, x, y + 0.3, z, 8, 0.7, 0.4, 0.7, 0.15);
        level.sendParticles(dust, x, y + 0.3, z, 5, 0.7, 0.4, 0.7, 0.04);
        level.playSound(null, x, y, z, kind == KIND_GRAVEL ? SoundEvents.GRAVEL_BREAK : SoundEvents.STONE_BREAK,
            SoundSource.HOSTILE, 0.7f, 0.9f + ctx.random().nextFloat() * 0.2f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks >= HARD_FINISH_TICKS) {
            return true;
        }
        return ticks >= VOLLEY_TICK
            && ctx.state().getIntOr("spawned", 0) == 1
            && ctx.state().getListOrEmpty("debris").isEmpty();
    }

    @Override
    public void end(AbilityContext ctx) {
        // No falling block outlives the activation. Blocks
        // already placed by landed debris and supports already
        // eaten (mobGriefing on) are permanent terrain and are
        // deliberately NOT touched here.
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag tracked = ctx.state().getListOrEmpty("debris");
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
