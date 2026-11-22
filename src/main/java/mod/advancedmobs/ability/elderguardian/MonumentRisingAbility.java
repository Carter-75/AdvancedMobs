package mod.advancedmobs.ability.elderguardian;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

/**
 * Monument Rising (Elder Guardian ability 3 of 5, terrain and
 * transformation).
 *
 * WHAT IT DOES: the elder guardian calls its monument up out of
 * the seafloor: a stepped pyramid of prismarine bricks and sea
 * lanterns rises beneath it in three stages at the spot where
 * the call began, and the elder takes its throne at the peak,
 * larger, warded by RESISTANCE and REGENERATION while it holds
 * the high ground, its FOLLOW_RANGE stretched so its beams reach
 * farther from the throne. Knocking it off the pyramid or
 * outlasting the reign are the only ways to end it.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6 and the MobAbility contract):
 * terrain changes from abilities are PERMANENT and gated by the
 * vanilla mobGriefing game rule, checked through
 * AbilityContext.mobGriefingAllowed(). This ability has two
 * modes:
 * - mobGriefing ON: the pyramid is placed and STAYS. There is
 *   no sinking it back down when the reign ends: end() touches
 *   no blocks, and the stepped pyramid remains as permanent
 *   terrain, the same way Tidal Bulwark Ring's wall and Magma
 *   Ring Rising's ring remain.
 * - mobGriefing OFF: no blocks are changed at all and there is
 *   no throne to take, so no teleport happens. The reign still
 *   runs for its full duration as a pure transformation: at the
 *   same tick the pyramid would have finished, the elder gains
 *   the same SCALE, FOLLOW_RANGE, RESISTANCE and REGENERATION,
 *   and the dethrone check simply never applies.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   elder guardian's current target within 20 blocks (distance
 *   squared 400).
 * - Duration: at most 900 ticks (45 seconds) from start(): a 60
 *   tick rise and up to 840 ticks of reign. In terrain mode the
 *   reign also ends early the moment the dethrone check fires
 *   (below). The framework cooldown runs from end() as usual.
 * - The rise (terrain mode only): the pyramid's center is the
 *   elder's block position recorded in start(); the monument
 *   rises there even if the elder swims off during the rise, and
 *   the enthronement teleport (below) is what brings it back to
 *   its throne. Stages land at ticks 20, 40 and 60:
 *   stage 1 is a 7 by 7 layer at the recorded feet level, stage
 *   2 a 5 by 5 layer one block above it, stage 3 a 3 by 3 layer
 *   two blocks above it plus one peak block three blocks above
 *   it at the center. Material is PRISMARINE_BRICKS, except sea
 *   lanterns at each layer's center and four corners: the
 *   glowing inlay. A position is filled only when it can be
 *   replaced (air, plants, and other replaceable blocks) or
 *   holds water, has no block entity, and is not already the
 *   block being placed; containers, unbreakable blocks, and
 *   other fluids are never touched, and nothing replaced drops
 *   as an item. At most 84 blocks change (49 + 25 + 9 + 1).
 * - The enthronement (tick 60, both modes): in terrain mode the
 *   elder is teleported to the peak (center plus 0.5, feet at
 *   peak level plus 4, on top of the peak block), its velocity
 *   zeroed and its navigation stopped; this is a one-shot
 *   teleport in tick(), not movement control, and the elder is
 *   free to swim off afterwards (which is what the dethrone
 *   check watches for). In both modes the elder gains:
 *   a SCALE add_multiplied_base modifier of +0.5 (1.5 times
 *   size, modifier id advancedmobs:monument_rising_scale), a
 *   FOLLOW_RANGE add_value modifier of +20 (modifier id
 *   advancedmobs:monument_rising_follow_range; this is the
 *   design's "beams reach farther" line, since the guardian
 *   beam's targeting runs off the mob's follow range), and
 *   RESISTANCE I and REGENERATION I, applied for 840 ticks and
 *   refreshed to 120 ticks every 100 ticks of the reign in case
 *   something cleanses them early. All modifiers are removed in
 *   end(), and end() also removes the two effects: the ward
 *   lasts exactly while the reign does. (An elder that somehow
 *   had RESISTANCE or REGENERATION from another source loses it
 *   at end(), an accepted edge case shared with every
 *   fixed-duration effect in this system; its permanent upgrade
 *   tree regeneration, if it owns one, is re-applied by
 *   ScalingSystem as usual.)
 * - The dethrone check (terrain mode only, once enthroned):
 *   every 20 ticks, if the elder is more than 7.5 blocks
 *   horizontally from the pyramid center or more than 1.5 blocks
 *   below the peak feet level, the reign is over: a flag is set
 *   and isFinished() ends the activation, so end() strips the
 *   throne's gifts. Being knocked off and swimming off both
 *   count; nothing else does.
 * - The design's "curse falls faster" line: curse cadence is
 *   the framework's ability cooldown, which no ability may
 *   rewrite from inside an activation. The reign's curse-side
 *   pressure is carried by the FOLLOW_RANGE above (a wider
 *   court notices and engages the elder sooner), and this
 *   comment is the honest record of that substitution.
 *
 * WHAT IT LOOKS AND SOUNDS LIKE: BUBBLE streams boil up around
 * the rising layers every 10 ticks of the rise (4 per pulse at
 * random spots over the current footprint), with a deep
 * GUARDIAN_AMBIENT (volume 1.5, pitch 0.5) as the call goes out.
 * The sea lantern inlay glows from the pyramid itself. During
 * the reign, 4 BUBBLE_COLUMN_UP particles stream around the peak
 * every 20 ticks. The enthroned elder is visibly half again as
 * large (the SCALE modifier) with RESISTANCE and REGENERATION
 * effect sparkles.
 *
 * PERFORMANCE: block work happens on exactly 3 ticks (the stage
 * ticks), each a bounded loop over at most 49 positions with one
 * block read each. tick() otherwise returns immediately except
 * on the 10 and 20 tick marks (rise bubbles, dethrone check,
 * reign streams: pure math and position reads) and the 100 tick
 * marks (two effect writes). Particle budget per activation: 12
 * at start plus at most 6 rise pulses times 4 = 24, plus 8 at
 * the enthronement, plus at most 42 reign pulses times 4 = 168,
 * total at most 212 particles.
 * No entity queries anywhere in this ability.
 *
 * State tag keys: "cx", "cy", "cz" (pyramid center block pos,
 * the elder's feet level at start), "terrain" (whether
 * mobGriefing allowed the pyramid), "enthroned" (whether tick
 * 60 has run), "dethroned" (whether the dethrone check ended
 * the reign). No block list is stored: placed pyramid blocks are
 * permanent and never revisited.
 */
public final class MonumentRisingAbility implements MobAbility {

    static final int DURATION_TICKS = 900;
    private static final int RISE_TICKS = 60;
    private static final int MAX_PLACEMENTS = 84;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "monument_rising_scale");
    private static final Identifier FOLLOW_RANGE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "monument_rising_follow_range");

    @Override
    public String id() {
        return "monument_rising";
    }

    @Override
    public String displayName() {
        return "Monument Rising";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 400.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        state.putInt("cx", mob.blockPosition().getX());
        state.putInt("cy", mob.blockPosition().getY());
        state.putInt("cz", mob.blockPosition().getZ());
        state.putBoolean("terrain", ctx.mobGriefingAllowed());
        state.putBoolean("enthroned", false);
        state.putBoolean("dethroned", false);

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            // The call goes out: the water boils over the
            // footprint.
            level.sendParticles(ParticleTypes.BUBBLE, mob.getX(), mob.getY() + 0.5, mob.getZ(),
                12, 3.0, 0.8, 3.0, 0.15);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.GUARDIAN_AMBIENT, SoundSource.HOSTILE, 1.5f, 0.5f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        CompoundTag state = ctx.state();
        boolean terrain = state.getBooleanOr("terrain", false);
        boolean enthroned = state.getBooleanOr("enthroned", false);

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }

        // The rise: one stage per 20 ticks, terrain mode only.
        if (terrain && !enthroned) {
            if (ticks == 20) {
                buildLayer(ctx, level, 3, 0);
            } else if (ticks == 40) {
                buildLayer(ctx, level, 2, 1);
            } else if (ticks == RISE_TICKS) {
                buildLayer(ctx, level, 1, 2);
                placePeak(ctx, level);
            }
        }
        if (ticks == RISE_TICKS && !enthroned) {
            enthrone(ctx, level, terrain);
            enthroned = true;
        }

        if (!enthroned) {
            // Rise bubbles on the 10 tick marks while the
            // monument climbs.
            if (ticks % 10 == 0) {
                double cx = state.getIntOr("cx", 0) + 0.5;
                double cz = state.getIntOr("cz", 0) + 0.5;
                double cy = state.getIntOr("cy", 0);
                for (int i = 0; i < 4; i++) {
                    double angle = ctx.random().nextDouble() * Math.PI * 2.0;
                    double distance = ctx.random().nextDouble() * 3.5;
                    level.sendParticles(ParticleTypes.BUBBLE,
                        cx + Math.cos(angle) * distance, cy + 0.5, cz + Math.sin(angle) * distance,
                        1, 0.0, 0.0, 0.0, 0.05);
                }
            }
            return;
        }

        // The reign: effect refresh on the 100 tick marks, peak
        // streams and the dethrone check on the 20 tick marks.
        if (ticks % 100 == 0) {
            Mob mob = ctx.mob();
            mob.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 120, 0));
            mob.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 120, 0));
        }
        if (ticks % 20 == 0) {
            double cx = state.getIntOr("cx", 0) + 0.5;
            double cz = state.getIntOr("cz", 0) + 0.5;
            double peakY = state.getIntOr("cy", 0) + 4;
            level.sendParticles(ParticleTypes.BUBBLE_COLUMN_UP, cx, peakY, cz,
                4, 1.5, 0.5, 1.5, 0.1);
            if (terrain) {
                Mob mob = ctx.mob();
                double dx = mob.getX() - cx;
                double dz = mob.getZ() - cz;
                if (dx * dx + dz * dz > 56.25 || mob.getY() < peakY - 1.5) {
                    // Knocked off the pyramid, or swam away from
                    // the throne: the reign is over.
                    state.putBoolean("dethroned", true);
                }
            }
        }
    }

    /**
     * Places one pyramid layer: a square of half-size "half" (3,
     * 2, or 1) at the recorded center, "height" blocks above the
     * recorded feet level. Sea lanterns at the center and the
     * four corners, prismarine bricks elsewhere. Permanent
     * terrain; see the class comment for the safety checks.
     */
    private void buildLayer(AbilityContext ctx, ServerLevel level, int half, int height) {
        CompoundTag state = ctx.state();
        int cx = state.getIntOr("cx", 0);
        int cy = state.getIntOr("cy", 0) + height;
        int cz = state.getIntOr("cz", 0);
        int placed = 0;
        for (int dx = -half; dx <= half && placed < MAX_PLACEMENTS; dx++) {
            for (int dz = -half; dz <= half && placed < MAX_PLACEMENTS; dz++) {
                boolean inlay = (dx == 0 && dz == 0)
                    || (Math.abs(dx) == half && Math.abs(dz) == half);
                Block block = inlay ? Blocks.SEA_LANTERN : Blocks.PRISMARINE_BRICKS;
                BlockPos pos = new BlockPos(cx + dx, cy, cz + dz);
                if (placeBlock(level, pos, block)) {
                    placed++;
                }
            }
        }
    }

    /** Places the single peak block the throne stands on. */
    private void placePeak(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        BlockPos peak = new BlockPos(state.getIntOr("cx", 0),
            state.getIntOr("cy", 0) + 3, state.getIntOr("cz", 0));
        placeBlock(level, peak, Blocks.PRISMARINE_BRICKS);
    }

    /**
     * The shared placement safety check: the position must be
     * replaceable or water, hold no block entity, and not
     * already be the block being placed. Nothing replaced drops
     * as an item.
     */
    private boolean placeBlock(ServerLevel level, BlockPos pos, Block block) {
        BlockState existing = level.getBlockState(pos);
        boolean wetOrEmpty = existing.canBeReplaced()
            || existing.getFluidState().getType() == Fluids.WATER;
        if (!wetOrEmpty || level.getBlockEntity(pos) != null || existing.is(block)) {
            return false;
        }
        level.setBlock(pos, block.defaultBlockState(), Block.UPDATE_ALL);
        return true;
    }

    /**
     * Tick 60: the elder takes its throne. Terrain mode teleports
     * it onto the peak; both modes apply the throne's modifiers
     * and effects. See the class comment.
     */
    private void enthrone(AbilityContext ctx, ServerLevel level, boolean terrain) {
        CompoundTag state = ctx.state();
        Mob mob = ctx.mob();
        if (terrain) {
            double cx = state.getIntOr("cx", 0) + 0.5;
            double cz = state.getIntOr("cz", 0) + 0.5;
            double peakY = state.getIntOr("cy", 0) + 4;
            mob.snapTo(cx, peakY, cz, mob.getYRot(), 0.0f);
            mob.setDeltaMovement(0.0, 0.0, 0.0);
            mob.getNavigation().stop();
        }
        AttributeInstance scale = mob.getAttribute(Attributes.SCALE);
        if (scale != null) {
            scale.addOrUpdateTransientModifier(new AttributeModifier(
                SCALE_ID, 0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
        AttributeInstance followRange = mob.getAttribute(Attributes.FOLLOW_RANGE);
        if (followRange != null) {
            followRange.addOrUpdateTransientModifier(new AttributeModifier(
                FOLLOW_RANGE_ID, 20.0, AttributeModifier.Operation.ADD_VALUE));
        }
        mob.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, DURATION_TICKS - RISE_TICKS, 0));
        mob.addEffect(new MobEffectInstance(MobEffects.REGENERATION, DURATION_TICKS - RISE_TICKS, 0));
        state.putBoolean("enthroned", true);

        // The court acknowledges the throne.
        level.sendParticles(ParticleTypes.BUBBLE_COLUMN_UP, mob.getX(), mob.getY() + 1.0, mob.getZ(),
            8, 1.5, 1.0, 1.5, 0.15);
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
            SoundEvents.ELDER_GUARDIAN_AMBIENT, SoundSource.HOSTILE, 1.2f, 0.7f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.state().getBooleanOr("dethroned", false)
            || ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // The throne's gifts end with the reign. The pyramid,
        // when placed, is permanent terrain and is deliberately
        // NOT touched here (terrain rule, see the class comment).
        Mob mob = ctx.mob();
        AttributeInstance scale = mob.getAttribute(Attributes.SCALE);
        if (scale != null) {
            scale.removeModifier(SCALE_ID);
        }
        AttributeInstance followRange = mob.getAttribute(Attributes.FOLLOW_RANGE);
        if (followRange != null) {
            followRange.removeModifier(FOLLOW_RANGE_ID);
        }
        if (ctx.state().getBooleanOr("enthroned", false)) {
            mob.removeEffect(MobEffects.RESISTANCE);
            mob.removeEffect(MobEffects.REGENERATION);
        }
    }
}
