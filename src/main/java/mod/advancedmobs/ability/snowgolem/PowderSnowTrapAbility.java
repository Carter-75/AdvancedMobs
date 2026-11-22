package mod.advancedmobs.ability.snowgolem;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Powder Snow Trap (Snow Golem ability 5 of 5, terrain).
 *
 * WHAT IT DOES: the golem gestures at the ground between
 * itself and its target and the patch converts to real
 * powder snow, soft and innocent under a dusting of
 * SNOWFLAKE. Anything that walks in sinks straight down
 * through it, takes freezing damage from the powder snow
 * mechanic itself while it struggles, and whatever finally
 * climbs out does so chilled and slow.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the golem's current target within 14 blocks (distance
 *   squared 196).
 * - The patch (start()): the center is 3.5 blocks ahead of
 *   the golem toward its target (the golem's feet height),
 *   recorded in the state tag. The patch is the Chebyshev
 *   radius 1 square (3 by 3 columns) around that center.
 * - The conversion (start(), mobGriefing mode only): per
 *   column, scanning from 2 above the center height down
 *   to 5 below, the surface is the first diggable block
 *   (the shared safety check: dry, no block entity,
 *   breakable) with air above it. The surface block
 *   converts to POWDER_SNOW, and the block directly below
 *   it converts too when it also passes the safety check,
 *   so the patch is 2 blocks deep and a victim sinks to
 *   its waist or deeper instead of standing on a crust.
 *   Columns whose surface is already powder snow, fluid,
 *   a container, or unbreakable are skipped. At most 9
 *   columns and 18 blocks change. Freezing itself is NOT
 *   code: vanilla powder snow builds an entity's freeze
 *   while it stands in the block and deals freeze damage
 *   at full freeze (CAPABILITIES.md), which is exactly the
 *   design's block mechanic line.
 * - The chill on escape (pulse every 10 ticks, for the
 *   300-tick watch): one bounded enemy query in radius 3.0
 *   of the patch center (2.5 below to 2.5 above) maintains
 *   the inside set in the state tag. An enemy that was in
 *   the set and is no longer in the query results (and is
 *   still alive) has climbed out: it gains SLOWNESS I for
 *   100 ticks, once, and leaves the set. Enemies that die
 *   in the patch leave the set silently. The watch runs
 *   the full 300 ticks whether or not the conversion
 *   happened (see the terrain rule branch below).
 * - Duration: exactly 300 ticks (15 seconds) of watch from
 *   start(). The powder snow itself is permanent terrain
 *   and outlives the watch.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6 and the MobAbility
 * contract): the conversion is PERMANENT and gated by the
 * vanilla mobGriefing game rule, checked through
 * AbilityContext.mobGriefingAllowed() once in start():
 * - mobGriefing ON: the patch converts and STAYS. end()
 *   touches no blocks; the trap is ordinary (if treacherous)
 *   terrain afterwards.
 * - mobGriefing OFF (documented branch difference): no
 *   blocks are changed, so there is no powder snow to sink
 *   into and no freezing; the gesture, the SNOWFLAKE
 *   dusting, and the escape watch still run over the marked
 *   patch, so an enemy crossing the marked ground and
 *   leaving it is still chilled. Only the terrain, and the
 *   sinking and freezing it causes, is missing.
 *
 * WHAT IT LOOKS LIKE: a POWDER_SNOW_PLACE (volume 1.0,
 * pitch 0.8) as the patch sets, and 4 SNOWFLAKE dusting
 * over the patch every 5 ticks for the whole watch, the
 * innocent sparkle on the surface.
 *
 * PERFORMANCE: start() is at most 9 column scans of at
 *   most 8 block reads plus at most 18 block writes, once.
 * tick() is 4 particles every 5 ticks and one bounded
 * entity query every 10 ticks plus a pass over the inside
 * set (at most the enemies one pulse can catch). Particle
 * budget per activation: 60 emissions times 4 = at most
 * 240 particles.
 *
 * State tag keys: "cx"/"cy"/"cz" (the patch center, block
 * coordinates as ints), "inside" (list of compounds, one
 * per enemy currently inside the patch: "id" UUID).
 */
public final class PowderSnowTrapAbility implements MobAbility {

    static final int DURATION_TICKS = 300;
    private static final double PATCH_FORWARD = 3.5;
    private static final int PATCH_RADIUS = 1;
    private static final int WATCH_INTERVAL = 10;
    private static final double WATCH_RADIUS = 3.0;
    private static final int ESCAPE_SLOW_TICKS = 100;

    @Override
    public String id() {
        return "powder_snow_trap";
    }

    @Override
    public String displayName() {
        return "Powder Snow Trap";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        LivingEntity target = ctx.target();
        double dirX = 0.0;
        double dirZ = 1.0;
        if (target != null) {
            dirX = target.getX() - caster.getX();
            dirZ = target.getZ() - caster.getZ();
            double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
            if (len > 0.001) {
                dirX /= len;
                dirZ /= len;
            }
        }
        BlockPos center = BlockPos.containing(
            caster.getX() + dirX * PATCH_FORWARD, caster.getY(), caster.getZ() + dirZ * PATCH_FORWARD);
        state.putInt("cx", center.getX());
        state.putInt("cy", center.getY());
        state.putInt("cz", center.getZ());
        state.put("inside", new ListTag());

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        if (ctx.mobGriefingAllowed()) {
            convertPatch(level, center);
        }
        level.playSound(null, center.getX() + 0.5, center.getY() + 1.0, center.getZ() + 0.5,
            SoundEvents.POWDER_SNOW_PLACE, SoundSource.HOSTILE, 1.0f, 0.8f);
    }

    /** Converts the patch's columns to powder snow, 2 deep where the ground allows. */
    private static void convertPatch(ServerLevel level, BlockPos center) {
        BlockState powder = Blocks.POWDER_SNOW.defaultBlockState();
        for (int dx = -PATCH_RADIUS; dx <= PATCH_RADIUS; dx++) {
            for (int dz = -PATCH_RADIUS; dz <= PATCH_RADIUS; dz++) {
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                for (int y = center.getY() + 2; y >= center.getY() - 5; y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.is(Blocks.POWDER_SNOW)) {
                        break;
                    }
                    if (!SnowGolemAbilitySupport.isDiggable(level, pos)) {
                        continue;
                    }
                    if (!level.getBlockState(pos.above()).isAir()
                        && !level.getBlockState(pos.above()).is(Blocks.POWDER_SNOW)) {
                        continue;
                    }
                    level.setBlock(pos, powder, Block.UPDATE_ALL);
                    BlockPos below = pos.below();
                    if (SnowGolemAbilitySupport.isDiggable(level, below)) {
                        level.setBlock(below, powder, Block.UPDATE_ALL);
                    }
                    break;
                }
            }
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        double cx = state.getIntOr("cx", 0) + 0.5;
        double cy = state.getIntOr("cy", 0);
        double cz = state.getIntOr("cz", 0) + 0.5;

        // The innocent dusting over the patch.
        if (ticks % 5 == 0) {
            level.sendParticles(ParticleTypes.SNOWFLAKE, cx, cy + 1.2, cz,
                4, 1.5, 0.4, 1.5, 0.03);
        }

        if (ticks % WATCH_INTERVAL == 0) {
            escapeWatch(ctx, level, cx, cy, cz);
        }
    }

    /**
     * One watch pulse: refreshes the inside set from the
     * bounded query and chills every living enemy that has
     * left the patch since the last pulse.
     */
    private static void escapeWatch(AbilityContext ctx, ServerLevel level, double cx, double cy, double cz) {
        CompoundTag state = ctx.state();
        List<LivingEntity> present = SnowGolemAbilitySupport.enemiesInBox(ctx, cx, cy, cz, WATCH_RADIUS, 2.5, 2.5);
        Set<UUID> presentIds = new HashSet<>();
        for (LivingEntity enemy : present) {
            presentIds.add(enemy.getUUID());
        }
        ListTag kept = new ListTag();
        ListTag inside = state.getListOrEmpty("inside");
        for (int i = 0; i < inside.size(); i++) {
            CompoundTag entry = inside.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id == null) {
                continue;
            }
            if (presentIds.contains(id)) {
                kept.add(entry);
                continue;
            }
            // Gone from the patch: chill it on the way out if
            // it is still alive; the dead leave silently.
            if (level.getEntity(id) instanceof LivingEntity escapee && escapee.isAlive()) {
                escapee.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, ESCAPE_SLOW_TICKS, 0));
            }
        }
        for (UUID id : presentIds) {
            boolean tracked = false;
            for (int i = 0; i < kept.size(); i++) {
                UUID existing = kept.getCompoundOrEmpty(i).read("id", UUIDUtil.CODEC).orElse(null);
                if (id.equals(existing)) {
                    tracked = true;
                    break;
                }
            }
            if (!tracked) {
                CompoundTag entry = new CompoundTag();
                entry.store("id", UUIDUtil.CODEC, id);
                kept.add(entry);
            }
        }
        state.put("inside", kept);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to undo: the powder snow is permanent
        // terrain (terrain rule, see the class comment) and
        // the escape chill rides its own clock.
    }
}
