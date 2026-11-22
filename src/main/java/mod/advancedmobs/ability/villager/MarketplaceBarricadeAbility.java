package mod.advancedmobs.ability.villager;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Marketplace Barricade (Villager ability 4 of 5, terrain).
 *
 * WHAT IT DOES: market stalls slam down in a defensive row
 * between the villager and its enemy, striped wool canopies over
 * plank counters with a crate stacked beside them, backed by
 * real placed planks so the row is a genuine obstacle an enemy
 * must climb or detour around. The villager trades insults from
 * behind the counter at full safety and restocks its nerve,
 * regenerating for as long as it stays sheltered behind the
 * barricade.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   villager's current target within 14 blocks (distance squared
 *   196) and the villager on the ground.
 * - Duration: exactly 400 ticks (20 seconds). The displays come
 *   down in end(); the real backing blocks do NOT (see TERRAIN).
 * - The row (built once, in start()): the facing direction is
 *   the horizontal unit vector from the caster toward its target
 *   at cast time, stored in the state tag with the row center
 *   (the caster's position plus 2.0 blocks along the facing).
 *   Three stalls stand at perpendicular offsets -1.6, 0, and
 *   +1.6 from the center, each on its own surface-scanned ground
 *   height (at most 8 block reads per stall). Each stall is two
 *   block displays: a counter of oak planks (scale 1.5 wide, 1.0
 *   tall, 0.8 deep) standing on the ground, and a canopy (scale
 *   1.7 wide, 0.25 tall, 1.1 deep) floating at ground + 1.55,
 *   striped by stall: the outer stalls wear white wool, the
 *   center stall red wool (Blocks.WOOL is a color collection in
 *   26.2; the states are WOOL.white() and WOOL.red()). One
 *   barrel crate display (scale 0.9) sits beside the center
 *   stall at perpendicular offset +1.2. Seven displays total,
 *   all tracked in the state tag and discarded in end(). The
 *   displays are pure visuals with no collision; the obstacle
 *   is the backing, below.
 * - The backing (the terrain, built once in start() right after
 *   the displays): behind each stall's counter, 0.8 blocks
 *   further from the target, a two-high column of real oak
 *   planks at the stall's ground height and one above it, at
 *   most 6 blocks total. PERMANENT and gated: a cell is only
 *   filled when AbilityContext.mobGriefingAllowed() is true at
 *   build time, the cell canBeReplaced(), the cell holds no
 *   block entity, and the cell below is solid ground (for the
 *   upper cell, the lower backing block just placed counts).
 *   Nothing is ever overwritten that is a container, a block
 *   entity, or unbreakable, and nothing floats. With mobGriefing
 *   off, no blocks are placed at all and the stalls still stand
 *   as displays; enemies can walk through a display, which is
 *   the documented cost of the rule being off.
 * - Holding the counter (every tick): the caster's navigation
 *   is stopped (never NoAi), so it stays planted behind its
 *   stalls trading insults: every 40 ticks, VILLAGER_AMBIENT at
 *   volume 1.0, pitch 0.9, plus 2 ANGRY_VILLAGER particles over
 *   its head.
 * - Restocking nerve (every 20 ticks): if the caster is still
 *   within 4.0 blocks of the recorded row center, it gains
 *   REGENERATION I refreshed to 40 ticks. A villager knocked
 *   away from its barricade stops regenerating until it is
 *   back behind it (the pulse simply finds it out of range);
 *   the effect lapses on its own 40-tick clock either way, so
 *   end() strips nothing.
 *
 * WHAT IT LOOKS LIKE: three market stalls materializing in a
 * row with a clatter, red and white canopies snapping open over
 * plank counters, a crate thumping down beside the middle
 * stall, real planks thunking into place behind them, and a
 * villager peeking over the counter hurling abuse while its
 * health quietly ticks back up.
 *
 * PERFORMANCE: start() does at most 24 ground-scan reads, 7
 * display spawns, and at most 6 gated placement checks. tick()
 * is one navigation stop per tick, one distance check and one
 * effect refresh every 20 ticks, and one sound plus 2 particles
 * every 40 ticks. No entity queries anywhere. Particle budget
 * per activation: insults at most 10 pulses times 2 = 20
 * particles (the build itself is displays and blocks, no
 * particles).
 *
 * TERRAIN: the backing planks are this package's one block
 * change and follow the standing rule exactly (see the class
 * comment's backing paragraph): permanent, mobGriefing-gated,
 * never removed in end(). The stalls, canopies, and crate are
 * display entities, explicitly not block changes.
 *
 * CLEANUP: end() discards all seven displays. The backing
 * blocks stay in the world permanently, per the terrain rule.
 * If the caster dies mid-activation, the framework termination
 * path runs this same end(), so the displays never outlive the
 * ability while the placed planks remain, as terrain does.
 *
 * State tag keys: "cx"/"cy"/"cz" (row center), "displays"
 * (list of compounds, one per display: "id").
 */
public final class MarketplaceBarricadeAbility implements MobAbility {

    static final int DURATION_TICKS = 400;
    private static final double ROW_DISTANCE = 2.0;
    private static final double[] STALL_OFFSETS = {-1.6, 0.0, 1.6};
    private static final double BACKING_OFFSET = 0.8;
    private static final int REGEN_INTERVAL = 20;
    private static final int INSULT_INTERVAL = 40;
    private static final double SHELTER_RADIUS = 4.0;

    @Override
    public String id() {
        return "marketplace_barricade";
    }

    @Override
    public String displayName() {
        return "Marketplace Barricade";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().onGround()
            && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        LivingEntity target = ctx.target();

        // Facing: from the caster toward its target, horizontal.
        double fx = 1.0, fz = 0.0;
        if (target != null) {
            double dx = target.getX() - caster.getX();
            double dz = target.getZ() - caster.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 1.0e-4) {
                fx = dx / len;
                fz = dz / len;
            }
        }
        double perpX = -fz, perpZ = fx;
        double centerX = caster.getX() + fx * ROW_DISTANCE;
        double centerZ = caster.getZ() + fz * ROW_DISTANCE;
        state.putDouble("cx", centerX);
        state.putDouble("cy", caster.getY());
        state.putDouble("cz", centerZ);
        state.put("displays", new ListTag());

        boolean griefing = ctx.mobGriefingAllowed();
        BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();

        for (int i = 0; i < STALL_OFFSETS.length; i++) {
            double stallX = centerX + perpX * STALL_OFFSETS[i];
            double stallZ = centerZ + perpZ * STALL_OFFSETS[i];
            int groundY = VillagerAbilitySupport.findGroundY(level, stallX, caster.getY(), stallZ);

            // The stall: a plank counter and a striped canopy.
            addDisplay(ctx, level, Blocks.OAK_PLANKS.defaultBlockState(), 1.5f, 1.0f, 0.8f, stallX, groundY, stallZ);
            BlockState canopy = (i == 1 ? Blocks.WOOL.red() : Blocks.WOOL.white()).defaultBlockState();
            addDisplay(ctx, level, canopy, 1.7f, 0.25f, 1.1f, stallX, groundY + 1.55, stallZ);

            // The backing: a real two-high plank column behind the counter.
            if (griefing) {
                int backX = (int) Math.floor(stallX - fx * BACKING_OFFSET);
                int backZ = (int) Math.floor(stallZ - fz * BACKING_OFFSET);
                placeBacking(level, backX, groundY, backZ, planks);
            }
        }

        // The crate beside the center stall.
        double crateX = centerX + perpX * 1.2;
        double crateZ = centerZ + perpZ * 1.2;
        int crateY = VillagerAbilitySupport.findGroundY(level, crateX, caster.getY(), crateZ);
        addDisplay(ctx, level, Blocks.BARREL.defaultBlockState(), 0.9f, 0.9f, 0.9f, crateX, crateY, crateZ);

        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.VILLAGER_AMBIENT,
            SoundSource.HOSTILE, 1.0f, 0.8f);
    }

    /** Places one backing column (two planks) if every safety check passes (see the class comment). */
    private void placeBacking(ServerLevel level, int x, int groundY, int z, BlockState planks) {
        BlockPos lower = new BlockPos(x, groundY, z);
        BlockState lowerCell = level.getBlockState(lower);
        BlockState below = level.getBlockState(lower.below());
        if (!lowerCell.canBeReplaced() || level.getBlockEntity(lower) != null || below.isAir()) {
            return;
        }
        level.setBlock(lower, planks, Block.UPDATE_ALL);
        BlockPos upper = lower.above();
        BlockState upperCell = level.getBlockState(upper);
        if (upperCell.canBeReplaced() && level.getBlockEntity(upper) == null) {
            level.setBlock(upper, planks, Block.UPDATE_ALL);
        }
    }

    /** Creates, places, and tracks one stall display. */
    private void addDisplay(AbilityContext ctx, ServerLevel level, BlockState blockState,
                            float scaleX, float scaleY, float scaleZ, double x, double y, double z) {
        Entity display = VillagerAbilitySupport.createBlockDisplay(level, blockState, scaleX, scaleY, scaleZ);
        if (display == null) {
            return;
        }
        display.snapTo(x, y, z);
        if (level.addFreshEntity(display)) {
            VillagerAbilitySupport.trackEntity(ctx.state(), "displays", display);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        int ticks = ctx.ticksActive();

        // Planted behind the counter, never NoAi.
        caster.getNavigation().stop();

        if (ticks % REGEN_INTERVAL == 0) {
            CompoundTag state = ctx.state();
            double dx = caster.getX() - state.getDoubleOr("cx", caster.getX());
            double dz = caster.getZ() - state.getDoubleOr("cz", caster.getZ());
            if (dx * dx + dz * dz <= SHELTER_RADIUS * SHELTER_RADIUS) {
                caster.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 40, 0));
            }
        }
        if (ticks % INSULT_INTERVAL == 0) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.VILLAGER_AMBIENT,
                SoundSource.HOSTILE, 1.0f, 0.9f);
            level.sendParticles(ParticleTypes.ANGRY_VILLAGER, caster.getX(), caster.getY() + 1.8, caster.getZ(),
                2, 0.25, 0.2, 0.25, 0.02);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        // The stalls come down; the placed planks stay (terrain rule).
        ListTag displays = ctx.state().getListOrEmpty("displays");
        for (int i = 0; i < displays.size(); i++) {
            Entity display = VillagerAbilitySupport.resolveTracked(level, displays.getCompoundOrEmpty(i));
            if (display != null) {
                display.discard();
            }
        }
    }
}
