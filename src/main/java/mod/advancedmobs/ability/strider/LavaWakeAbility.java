package mod.advancedmobs.ability.strider;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.UUID;

/**
 * Lava Wake (Strider ability 1 of 5, terrain and mobility).
 *
 * WHAT IT DOES: the strider picks up speed across the lava
 * and its passage churns the surface behind it into a crust
 * of real magma blocks, FLAME and LAVA spray kicking up at
 * its feet. The longer the wake runs without a stall, the
 * faster the strider goes, up to a hard stage cap. Enemies
 * who follow across the fresh crust get scorched: their
 * BURNING_TIME doubles and they are lit, so crossing the
 * wake burns long after they leave it.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): a live target within 16 blocks
 *   (distance squared 256). No ground requirement: a strider
 *   in lava is not onGround, and the wake is at its best
 *   there. On dry land the speed stages still build and the
 *   spray still shows; only the crust needs lava underfoot.
 * - Speed stages: every 20 ticks the strider's horizontal
 *   movement since the last check is measured. At least 2.0
 *   blocks of travel raises the stage by 1 (cap 5); less
 *   lowers it by 1 (floor 0), so a stalled strider sheds its
 *   speed instead of banking it forever. The stage drives one
 *   transient MOVEMENT_SPEED modifier (id
 *   advancedmobs:strider_lava_wake_speed), add_multiplied_base
 *   +0.10 per stage: at stage 5 the strider runs at 1.5 times
 *   its base 0.175, about 0.26. The modifier is only rewritten
 *   on the tick the stage changes, never per tick.
 * - The crust (terrain, see the TERRAIN RULE): every 4 ticks,
 *   if the strider moved at least 0.5 blocks since the last
 *   wake point, the lava block at the strider's feet (the
 *   block its bounding box bottom sits in) converts to a
 *   MAGMA_BLOCK. At most 60 conversions per activation. Only
 *   lava blocks convert: no other block is ever read as a
 *   candidate, so no container, unbreakable block, or solid
 *   ground can be touched, and nothing is placed into air or
 *   water. FLAME (2) and LAVA (2) particles spray at the feet
 *   on every wake pulse where the strider is in lava, whether
 *   or not that pulse converted a block.
 * - The scorch: every 10 ticks, one bounded enemy query in
 *   radius 6 around the strider. An enemy standing on a magma
 *   block (the block directly below its feet is MAGMA_BLOCK)
 *   gains the scorch: a BURNING_TIME modifier
 *   (add_multiplied_base +1.0, id
 *   advancedmobs:strider_wake_scorch) held for 100 ticks and
 *   igniteForSeconds(3), which the doubled burning time
 *   stretches to about 6 seconds of burn. Scorched victims
 *   are tracked in the state tag; the same 10-tick pulse
 *   sweeps the modifier off victims whose 100 ticks ran out
 *   or who are gone, and end() strips it from everyone still
 *   tracked. The crust itself also hurts crossers the vanilla
 *   way (magma blocks burn feet); the scorch is the extra
 *   price for following the wake specifically.
 * - Duration: exactly 300 ticks (15 seconds) from start().
 *   The crust does NOT end with the ability: magma blocks are
 *   permanent terrain (see the TERRAIN RULE).
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6): the wake's magma blocks
 * are PERMANENT terrain changes, gated by
 * AbilityContext.mobGriefingAllowed(), which is checked at
 * every wake pulse:
 * - mobGriefing ON: lava underfoot converts to magma block
 *   and STAYS converted. (The design text said the crust is
 *   brief; the standing terrain rule supersedes that, the
 *   same call as Magma Ring Rising and Graveyard Bloom.)
 * - mobGriefing OFF: no block changes at all. The wake still
 *   runs: speed stages build, the spray shows, and the scorch
 *   still applies to enemies standing on NATURAL magma
 *   blocks. Only the fresh crust is missing.
 *
 * WHAT IT LOOKS LIKE: a strider accelerating across a lava
 * lake leaving a dark crust trail in its own footprints,
 * FLAME and LAVA spray at its feet every few ticks, enemies
 * who chase across the crust catching fire and burning twice
 * as long as they should.
 *
 * PERFORMANCE: the wake pulse runs every 4 ticks (one
 * position read, at most one block read and one block write);
 * the stage check every 20 ticks is arithmetic on stored
 * positions; the scorch is one bounded query plus a sweep of
 * a short list every 10 ticks. No per-tick entity queries.
 * Particle budget per activation: wake pulses at most 75
 * times 4 = 300, total at most 300 particles.
 *
 * CLEANUP: end() removes the speed modifier and the scorch
 * modifier from every tracked victim that still resolves.
 * The magma crust is permanent terrain and is deliberately
 * NOT touched.
 *
 * State tag keys: "stage" (current speed stage), "lastX",
 * "lastZ" (position at the last stage check), "wakeX",
 * "wakeZ" (position at the last wake pulse), "placed"
 * (crust conversions so far, against the cap), "burning"
 * (list of compounds, one per scorched victim: "id" the
 * victim's UUID, "until" the activation tick the scorch
 * ends).
 */
public final class LavaWakeAbility implements MobAbility {

    static final int DURATION_TICKS = 300;
    private static final int MAX_STAGE = 5;
    private static final double SPEED_PER_STAGE = 0.10;
    private static final int STAGE_INTERVAL = 20;
    private static final int WAKE_INTERVAL = 4;
    private static final int SCORCH_INTERVAL = 10;
    private static final int SCORCH_TICKS = 100;
    private static final int MAX_PLACED = 60;
    private static final Identifier SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "strider_lava_wake_speed");
    private static final Identifier SCORCH_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "strider_wake_scorch");

    @Override
    public String id() {
        return "lava_wake";
    }

    @Override
    public String displayName() {
        return "Lava Wake";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        state.putInt("stage", 0);
        state.putInt("placed", 0);
        state.put("burning", new ListTag());
        state.putDouble("lastX", mob.getX());
        state.putDouble("lastZ", mob.getZ());
        state.putDouble("wakeX", mob.getX());
        state.putDouble("wakeZ", mob.getZ());
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        int ticks = ctx.ticksActive();

        if (ticks % WAKE_INTERVAL == 0) {
            wakePulse(ctx, level, mob, state);
        }
        if (ticks % STAGE_INTERVAL == 0) {
            stageCheck(ctx, mob, state);
        }
        if (ticks % SCORCH_INTERVAL == 0) {
            scorchPulse(ctx, level, state);
            sweepScorched(ctx, level, false);
        }
    }

    /**
     * One wake pulse: spray at the feet when in lava, and, when
     * the terrain rule allows and the strider has actually
     * moved, crust the lava block underfoot into magma.
     */
    private static void wakePulse(AbilityContext ctx, ServerLevel level, Mob mob, CompoundTag state) {
        double dx = mob.getX() - state.getDoubleOr("wakeX", mob.getX());
        double dz = mob.getZ() - state.getDoubleOr("wakeZ", mob.getZ());
        boolean inLava = mob.isInLava();
        if (inLava) {
            level.sendParticles(ParticleTypes.FLAME, mob.getX(), mob.getY() + 0.2, mob.getZ(),
                2, 0.4, 0.1, 0.4, 0.03);
            level.sendParticles(ParticleTypes.LAVA, mob.getX(), mob.getY() + 0.2, mob.getZ(),
                2, 0.4, 0.1, 0.4, 0.0);
        }
        if (dx * dx + dz * dz < 0.25) {
            return;
        }
        state.putDouble("wakeX", mob.getX());
        state.putDouble("wakeZ", mob.getZ());
        if (!inLava || !ctx.mobGriefingAllowed()) {
            return;
        }
        if (state.getIntOr("placed", 0) >= MAX_PLACED) {
            return;
        }
        BlockPos feet = BlockPos.containing(mob.getX(), mob.getY(), mob.getZ());
        if (level.getBlockState(feet).is(Blocks.LAVA)) {
            level.setBlock(feet, Blocks.MAGMA_BLOCK.defaultBlockState(),
                net.minecraft.world.level.block.Block.UPDATE_ALL);
            state.putInt("placed", state.getIntOr("placed", 0) + 1);
        }
    }

    /** The stage ladder: sustained travel builds speed, a stall sheds it. */
    private static void stageCheck(AbilityContext ctx, Mob mob, CompoundTag state) {
        double dx = mob.getX() - state.getDoubleOr("lastX", mob.getX());
        double dz = mob.getZ() - state.getDoubleOr("lastZ", mob.getZ());
        state.putDouble("lastX", mob.getX());
        state.putDouble("lastZ", mob.getZ());
        int stage = state.getIntOr("stage", 0);
        int next = dx * dx + dz * dz >= 4.0
            ? Math.min(stage + 1, MAX_STAGE)
            : Math.max(stage - 1, 0);
        if (next == stage) {
            return;
        }
        state.putInt("stage", next);
        if (next == 0) {
            StriderAbilitySupport.removeModifier(mob, Attributes.MOVEMENT_SPEED, SPEED_ID);
        } else {
            StriderAbilitySupport.addModifier(mob, Attributes.MOVEMENT_SPEED,
                new AttributeModifier(SPEED_ID, SPEED_PER_STAGE * next,
                    AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
    }

    /**
     * The scorch: enemies standing on magma blocks near the
     * strider burn twice as long and are lit. One bounded query.
     */
    private static void scorchPulse(AbilityContext ctx, ServerLevel level, CompoundTag state) {
        List<LivingEntity> enemies = StriderAbilitySupport.enemiesAroundSelf(ctx, 6.0, 2.0, 2.0);
        if (enemies.isEmpty()) {
            return;
        }
        ListTag burning = state.getListOrEmpty("burning");
        int until = ctx.ticksActive() + SCORCH_TICKS;
        for (LivingEntity victim : enemies) {
            BlockPos below = BlockPos.containing(victim.getX(), victim.getY() - 0.5, victim.getZ());
            if (!level.getBlockState(below).is(Blocks.MAGMA_BLOCK)) {
                continue;
            }
            StriderAbilitySupport.addModifier(victim, Attributes.BURNING_TIME,
                new AttributeModifier(SCORCH_ID, 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
            victim.igniteForSeconds(3.0f);
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, victim.getUUID());
            entry.putInt("until", until);
            burning.add(entry);
        }
        state.put("burning", burning);
    }

    /**
     * Removes the scorch modifier from tracked victims whose
     * time ran out or who no longer resolve. With finishAll
     * set (end()), every tracked victim is stripped and the
     * list is cleared.
     */
    private static void sweepScorched(AbilityContext ctx, ServerLevel level, boolean finishAll) {
        CompoundTag state = ctx.state();
        ListTag burning = state.getListOrEmpty("burning");
        ListTag kept = new ListTag();
        int ticks = ctx.ticksActive();
        for (int i = 0; i < burning.size(); i++) {
            CompoundTag entry = burning.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            Entity entity = id != null ? level.getEntity(id) : null;
            boolean expired = finishAll || ticks >= entry.getIntOr("until", 0);
            if (expired) {
                if (entity instanceof LivingEntity victim) {
                    StriderAbilitySupport.removeModifier(victim, Attributes.BURNING_TIME, SCORCH_ID);
                }
                continue;
            }
            if (entity != null) {
                kept.add(entry);
            }
        }
        state.put("burning", kept);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        StriderAbilitySupport.removeModifier(ctx.mob(), Attributes.MOVEMENT_SPEED, SPEED_ID);
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            sweepScorched(ctx, level, true);
        }
    }
}
