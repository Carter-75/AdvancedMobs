package mod.advancedmobs.ability.polarbear;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
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
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Blizzard Aura (Polar Bear ability 2 of 5, aura / terrain).
 *
 * WHAT IT DOES: a local storm gathers around the bear and
 * follows it, SNOWFLAKE thickening into a whiteout while
 * real snow layers pile up on the ground inside the aura.
 * Enemies caught in the storm are chilled and weakened;
 * the bear moves through its own blizzard at full speed.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the bear's current target within 14 blocks (distance
 *   squared 196).
 * - Duration: exactly 400 ticks (20 seconds) from start().
 *   The aura is centered on the bear and moves with it.
 * - The bear's footing (start(), removed in end()): a
 *   MOVEMENT_EFFICIENCY add_value modifier of +1.0
 *   (modifier id advancedmobs:blizzard_aura_efficiency),
 *   the design's line: at the attribute's maximum the
 *   slowdown from difficult ground (snow layers, powder
 *   snow, webs and the like) is fully offset, so the bear
 *   crosses its own piling snow at full speed while
 *   everyone else wades.
 * - Chill pulse every 10 ticks (40 pulses): enemies
 *   (PolarBearAbilitySupport rules) within radius 5.0 of the
 *   bear (2.5 below to 2.5 above) gain SLOWNESS I
 *   (amplifier 0) and WEAKNESS I (amplifier 0), each
 *   refreshed to 30 ticks: inside the storm an enemy is
 *   permanently a step slower and hits softer, and the
 *   effects lapse 30 ticks after it escapes.
 * - Snow pulse every 20 ticks (20 pulses, mobGriefing mode
 *   only): snow piles up in a Chebyshev radius 4 (9 by 9
 *   columns) around the bear's block position. Per column,
 *   scanning from 2 above the bear's feet level down to 3
 *   below, the first workable cell wins: an existing snow
 *   layer below the cap of 4 layers grows by 1 layer, or an
 *   air cell whose block below is face-sturdy upward (or is
 *   itself snow) and where a snow layer can survive gains a
 *   fresh 1-layer snow. At most 12 cells change per pulse,
 *   so the drift builds gradually (at most 240 cells over
 *   the whole aura) instead of snapping in at once. The
 *   cell changed is always air or snow, never a container
 *   or any other block, and nothing is placed over fluids:
 *   a water or lava surface stops the column scan.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6 and the MobAbility
 * contract): the snow is PERMANENT and gated by the vanilla
 * mobGriefing game rule, checked through
 * AbilityContext.mobGriefingAllowed() at every snow pulse:
 * - mobGriefing ON: the layers pile up and STAY. end()
 *   touches no blocks; the drift is ordinary terrain
 *   afterwards, and later weather or players deal with it.
 * - mobGriefing OFF: no blocks are changed at all. The
 *   storm still chills and weakens everything inside it and
 *   the whiteout still shows; only the piling snow is
 *   missing.
 *
 * WHAT IT LOOKS LIKE: POLAR_BEAR_AMBIENT at volume 1.5,
 * pitch 0.8 at the start, 4 SNOWFLAKE every 4 ticks
 * swirling out to radius 4 around the bear for the whole
 * duration (the whiteout), and the ground visibly whitening
 * drift by drift in mobGriefing mode.
 *
 * PERFORMANCE: tick() returns immediately except on the
 * pulse ticks: the chill pulse is one bounded entity query
 * every 10 ticks; the snow pulse is at most 81 column
 * scans of at most 6 block reads every 20 ticks, capped at
 * 12 changes; particles ride the 4-tick interval. Particle
 * budget per activation: 100 emissions times 4 = at most
 * 400 particles.
 *
 * State tag keys: none. The aura needs no per-activation
 * data beyond the framework tick counter: its center is
 * the bear, its pulses are pure functions of the tick, and
 * its one modifier is removed in end() by fixed id.
 */
public final class BlizzardAuraAbility implements MobAbility {

    static final int DURATION_TICKS = 400;
    private static final int CHILL_INTERVAL = 10;
    private static final int SNOW_INTERVAL = 20;
    private static final int SNOW_RADIUS = 4;
    private static final int SNOW_MAX_LAYERS = 4;
    private static final int SNOW_MAX_CHANGES_PER_PULSE = 12;
    private static final double CHILL_RADIUS = 5.0;
    private static final Identifier EFFICIENCY_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "blizzard_aura_efficiency");

    @Override
    public String id() {
        return "blizzard_aura";
    }

    @Override
    public String displayName() {
        return "Blizzard Aura";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        PolarBearAbilitySupport.addModifier(caster, Attributes.MOVEMENT_EFFICIENCY,
            new AttributeModifier(EFFICIENCY_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.POLAR_BEAR_AMBIENT, SoundSource.HOSTILE, 1.5f, 0.8f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();

        // Whiteout drift: 4 SNOWFLAKE every 4 ticks.
        if (ticks % 4 == 0) {
            level.sendParticles(ParticleTypes.SNOWFLAKE, caster.getX(), caster.getY() + 1.0, caster.getZ(),
                4, 3.6, 1.0, 3.6, 0.04);
        }

        // Chill pulse: the storm slows and weakens its enemies.
        if (ticks % CHILL_INTERVAL == 0) {
            List<LivingEntity> victims = PolarBearAbilitySupport.enemiesInBox(
                ctx, caster.getX(), caster.getY(), caster.getZ(), CHILL_RADIUS, 2.5, 2.5);
            for (LivingEntity victim : victims) {
                victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 30, 0));
                victim.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 30, 0));
            }
        }

        // Snow pulse: the drift piles up, permanently, in
        // mobGriefing mode only. See the TERRAIN RULE in the
        // class comment.
        if (ticks % SNOW_INTERVAL == 0 && ctx.mobGriefingAllowed()) {
            pileSnow(level, caster.blockPosition());
        }
    }

    /**
     * One snow pulse around the bear's block position: grows
     * or lays snow layers column by column (see the class
     * comment for the exact rules), at most
     * SNOW_MAX_CHANGES_PER_PULSE changes.
     */
    private static void pileSnow(ServerLevel level, BlockPos center) {
        BlockState freshSnow = Blocks.SNOW.defaultBlockState();
        int changes = 0;
        for (int dx = -SNOW_RADIUS; dx <= SNOW_RADIUS && changes < SNOW_MAX_CHANGES_PER_PULSE; dx++) {
            for (int dz = -SNOW_RADIUS; dz <= SNOW_RADIUS && changes < SNOW_MAX_CHANGES_PER_PULSE; dz++) {
                if (pileColumn(level, center.getX() + dx, center.getY(), center.getZ() + dz, freshSnow)) {
                    changes++;
                }
            }
        }
    }

    /**
     * Works one column: scans from 2 above the bear's feet
     * level down to 3 below and changes the first workable
     * cell (grows a snow layer below the cap, or lays a fresh
     * layer on a survivable air cell). Returns whether a
     * block changed. Fluids and any other block end the scan
     * with no change.
     */
    private static boolean pileColumn(ServerLevel level, int x, int feetY, int z, BlockState freshSnow) {
        for (int y = feetY + 2; y >= feetY - 3; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (state.is(Blocks.SNOW)) {
                int layers = state.getValue(SnowLayerBlock.LAYERS);
                if (layers < SNOW_MAX_LAYERS) {
                    level.setBlock(pos, state.setValue(SnowLayerBlock.LAYERS, layers + 1), Block.UPDATE_ALL);
                    return true;
                }
                return false;
            }
            if (state.isAir()) {
                BlockPos belowPos = pos.below();
                BlockState below = level.getBlockState(belowPos);
                boolean supported = below.is(Blocks.SNOW)
                    || below.isFaceSturdy(level, belowPos, Direction.UP);
                if (supported && freshSnow.canSurvive(level, pos)) {
                    level.setBlock(pos, freshSnow, Block.UPDATE_ALL);
                    return true;
                }
                continue;
            }
            return false;
        }
        return false;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // The bear's footing comes off. The snow, when piled,
        // is permanent terrain and is deliberately NOT
        // touched here (terrain rule, see the class comment).
        PolarBearAbilitySupport.removeModifier(ctx.mob(), Attributes.MOVEMENT_EFFICIENCY, EFFICIENCY_ID);
    }
}
