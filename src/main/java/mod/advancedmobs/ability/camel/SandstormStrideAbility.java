package mod.advancedmobs.ability.camel;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Sandstorm Stride (Camel ability 5 of 5, mobility and terrain).
 *
 * WHAT IT DOES: the camel strides out on legs that step
 * over anything, each footfall kicking up falling dust and
 * leaving a trail of loose sand behind it. It walks straight
 * over obstacles that stop other mobs, hunting its target
 * down, and any enemy that follows in its sandy trail wades
 * and slows.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the camel's current target within 18 blocks (distance
 *   squared 324), and the camel on the ground.
 * - The stride kit (start(), both removed exactly in
 *   end()): STEP_HEIGHT +2.5 ADD_VALUE (the camel's base
 *   1.5 becomes 4.0: it steps up four-block rises without
 *   jumping, the design's huge step height) and MOVEMENT_
 *   SPEED +0.6 ADD_MULTIPLIED_BASE (base 0.09 becomes about
 *   0.144, the design's speed line).
 * - The hunt (tick(), every 5 ticks): the camel's
 *   navigation is driven onto its live target at speed
 *   multiplier 1.3. If the target is gone, the camel simply
 *   strides in place for the rest of the activation.
 * - The trail (tick(), every tick): when the camel has
 *   moved at least 1.6 blocks horizontally from the last
 *   recorded trail point, a new point is recorded at that
 *   last point: its column's surface height comes from one
 *   bounded scan (CamelAbilitySupport.findGroundY), and the
 *   point (center x/z, surface y, and the activation tick it
 *   was laid) is appended to the state tag's trail list,
 *   capped at 24 points with the oldest dropped. The last
 *   recorded point then updates to the camel's position.
 * - The sand (the terrain rule): at the moment a point is
 *   recorded, if AbilityContext.mobGriefingAllowed() is true
 *   and fewer than 24 sand blocks have been placed this
 *   activation, one real sand block is placed in the surface
 *   cell the point stands on, PERMANENTLY: the standing
 *   terrain rule supersedes the design text's "short lived,
 *   restored by schedule" trail, and there is no restore
 *   machinery. Placement safety: the cell must be
 *   replaceable, must hold no block entity (never overwrite
 *   a container), and the block directly below it must be
 *   solid ground (never float, never fill a fluid surface).
 *   With mobGriefing off, no sand is ever placed and
 *   everything else in the ability still runs.
 * - The slow zone (tick(), every 10 ticks): one bounded
 *   query for enemies within radius 11 of the camel; an
 *   enemy standing within 1.4 blocks horizontally and 2.0
 *   blocks vertically of any live trail point (laid within
 *   the last 200 ticks) gains SLOWNESS I refreshed to 30
 *   ticks. The zone is a code effect over the recorded
 *   points, so it works the same whether or not the sand
 *   blocks themselves were placed, and it ends with the
 *   activation; placed sand, when it exists, is just sand
 *   afterwards.
 * - Dust and sound (tick()): every 3 ticks, 3 FALLING_DUST
 *   motes carrying the sand state kick up at the camel's
 *   feet; every 20 ticks CAMEL_STEP_SAND plays at volume
 *   0.7, the crunch of the stride.
 * - Duration: exactly 300 ticks (15 seconds), hard finish.
 * - end(): both modifiers are removed. Recorded points and
 *   placed sand need no cleanup: the points live in the
 *   discarded state tag, and placed sand is permanent
 *   terrain by the standing rule. Idempotent.
 *
 * WHAT IT LOOKS LIKE: the camel suddenly moves like the
 * desert itself is walking: dust sifting off every long
 * stride, a dotted line of fresh sand unspooling behind it
 * across whatever it crosses, stepping up ledges other mobs
 * have to path around, while pursuers flounder in the loose
 * trail.
 *
 * PERFORMANCE: per tick the ability is one distance check
 * and, on a recording tick, one surface scan of at most 12
 * block reads plus at most one block placement. The hunt
 * drive is every 5 ticks, the slow zone is one bounded
 * radius-11 query every 10 ticks checked against at most
 * 24 stored points, and the dust is 3 particles every 3
 * ticks. No per-tick allocations beyond the pulse lists.
 * Particle budget per activation: 100 emissions times 3 =
 * 300 particles.
 *
 * TERRAIN: the sand trail is this package's one block
 * change; see the class comment's terrain paragraph and
 * CamelAbilitySupport for the gate and the safety checks.
 *
 * State tag keys: "lx"/"lz" (the last recorded trail
 * point's x/z), "placed" (sand blocks placed this
 * activation), "trail" (list of compounds, one per point:
 * "x", "y", "z" center and surface height, "t" the
 * activation tick it was laid).
 */
public final class SandstormStrideAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final double POINT_SPACING = 1.6;
    private static final int MAX_TRAIL_POINTS = 24;
    private static final int MAX_PLACED_SAND = 24;
    private static final int TRAIL_LIFE_TICKS = 200;
    private static final double ZONE_RADIUS = 1.4;
    private static final int SLOWNESS_TICKS = 30;
    private static final BlockParticleOption SAND_DUST =
        new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.SAND.defaultBlockState());
    private static final Identifier STEP_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "sandstorm_stride_step_height");
    private static final Identifier SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "sandstorm_stride_speed");

    @Override
    public String id() {
        return "sandstorm_stride";
    }

    @Override
    public String displayName() {
        return "Sandstorm Stride";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().onGround()
            && ctx.mob().distanceToSqr(target) <= 324.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CamelAbilitySupport.addModifier(caster, Attributes.STEP_HEIGHT,
            new AttributeModifier(STEP_ID, 2.5, AttributeModifier.Operation.ADD_VALUE));
        CamelAbilitySupport.addModifier(caster, Attributes.MOVEMENT_SPEED,
            new AttributeModifier(SPEED_ID, 0.6, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        ctx.state().putDouble("lx", caster.getX());
        ctx.state().putDouble("lz", caster.getZ());
        ctx.state().putInt("placed", 0);
        ctx.state().put("trail", new ListTag());
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        int ticks = ctx.ticksActive();

        // The hunt: navigation drives onto the live target.
        if (ticks % 5 == 0) {
            LivingEntity target = ctx.target();
            if (target != null) {
                caster.getNavigation().moveTo(target, 1.3);
            }
        }

        recordTrailPoint(ctx, level, ticks);

        if (ticks % 10 == 0) {
            slowFollowers(ctx);
        }
        if (ticks % 3 == 0) {
            level.sendParticles(SAND_DUST, caster.getX(), caster.getY() + 0.3, caster.getZ(),
                3, 0.6, 0.2, 0.6, 0.04);
        }
        if (ticks % 20 == 0) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.CAMEL_STEP_SAND,
                SoundSource.HOSTILE, 0.7f, 1.0f);
        }
    }

    /**
     * Records a trail point at the last recorded position
     * once the camel has strode far enough from it, and lays
     * the point's sand block when the terrain gate allows
     * (see the class comment). Runs the spacing check every
     * tick; the scan and placement happen only on a recording
     * tick.
     */
    private void recordTrailPoint(AbilityContext ctx, ServerLevel level, int ticks) {
        Mob caster = ctx.mob();
        double lx = ctx.state().getDoubleOr("lx", caster.getX());
        double lz = ctx.state().getDoubleOr("lz", caster.getZ());
        double dx = caster.getX() - lx;
        double dz = caster.getZ() - lz;
        if (dx * dx + dz * dz < POINT_SPACING * POINT_SPACING) {
            return;
        }

        double groundY = CamelAbilitySupport.findGroundY(level, lx, caster.getY() + 1.0, lz);
        int cellX = (int) Math.floor(lx);
        int cellZ = (int) Math.floor(lz);

        ListTag trail = ctx.state().getListOrEmpty("trail");
        while (trail.size() >= MAX_TRAIL_POINTS) {
            trail.remove(0);
        }
        CompoundTag point = new CompoundTag();
        point.putDouble("x", cellX + 0.5);
        point.putDouble("y", groundY);
        point.putDouble("z", cellZ + 0.5);
        point.putInt("t", ticks);
        trail.add(point);
        ctx.state().put("trail", trail);

        // The sand itself: permanent, gated, and placed only
        // where it can never overwrite or float (class comment).
        int placed = ctx.state().getIntOr("placed", 0);
        if (placed < MAX_PLACED_SAND && ctx.mobGriefingAllowed()) {
            BlockPos cell = new BlockPos(cellX, (int) Math.floor(groundY), cellZ);
            BlockState cellState = level.getBlockState(cell);
            BlockState belowState = level.getBlockState(cell.below());
            if (cellState.canBeReplaced() && level.getBlockEntity(cell) == null
                && !belowState.isAir() && belowState.getFluidState().isEmpty()) {
                level.setBlock(cell, Blocks.SAND.defaultBlockState(), Block.UPDATE_ALL);
                ctx.state().putInt("placed", placed + 1);
            }
        }

        ctx.state().putDouble("lx", caster.getX());
        ctx.state().putDouble("lz", caster.getZ());
    }

    /** One slow-zone pulse: enemies standing on a live trail point are slowed (class comment). */
    private void slowFollowers(AbilityContext ctx) {
        ListTag trail = ctx.state().getListOrEmpty("trail");
        if (trail.isEmpty()) {
            return;
        }
        int ticks = ctx.ticksActive();
        List<LivingEntity> enemies = CamelAbilitySupport.enemiesAroundSelf(ctx, 11.0, 2.0, 3.0);
        for (LivingEntity enemy : enemies) {
            for (int i = 0; i < trail.size(); i++) {
                CompoundTag point = trail.getCompoundOrEmpty(i);
                if (ticks - point.getIntOr("t", 0) > TRAIL_LIFE_TICKS) {
                    continue;
                }
                double dx = enemy.getX() - point.getDoubleOr("x", 0.0);
                double dz = enemy.getZ() - point.getDoubleOr("z", 0.0);
                double dy = enemy.getY() - point.getDoubleOr("y", 0.0);
                if (dx * dx + dz * dz <= ZONE_RADIUS * ZONE_RADIUS && Math.abs(dy) <= 2.0) {
                    enemy.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, SLOWNESS_TICKS, 0));
                    break;
                }
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CamelAbilitySupport.removeModifier(caster, Attributes.STEP_HEIGHT, STEP_ID);
        CamelAbilitySupport.removeModifier(caster, Attributes.MOVEMENT_SPEED, SPEED_ID);
        // Placed sand is permanent terrain (standing terrain
        // rule); the recorded points die with the state tag.
    }
}
