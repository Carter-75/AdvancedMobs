package mod.advancedmobs.ability.snowgolem;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Snowdrift Wake (Snow Golem ability 2 of 5, terrain /
 * mobility).
 *
 * WHAT IT DOES: the golem surfs away on its own weather:
 * for 20 seconds it moves half again as fast and every
 * stride piles real snow layers into a drift behind it.
 * Enemies chasing through the fresh snow flounder: their
 * ground friction doubles while they stand in the drift,
 * so every step bleeds momentum, while the golem doubles
 * back over its own trail at full boosted pace.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the golem's current target within 14 blocks (distance
 *   squared 196).
 * - Duration: exactly 400 ticks (20 seconds) from start().
 * - The surf (start(), removed in end()): a MOVEMENT_SPEED
 *   add_multiplied_base modifier of +0.50 on the golem
 *   (modifier id advancedmobs:snowdrift_wake_speed): its
 *   0.2 base speed becomes 0.3 for the duration. Snow
 *   layers do not slow vanilla movement, so the boost alone
 *   is the full pace advantage; no efficiency modifier is
 *   needed on the golem.
 * - The wake (every 4 ticks, mobGriefing mode only): when
 *   the golem has moved at least 0.4 blocks horizontally
 *   since the last laying position, snow piles in the
 *   Chebyshev radius 1 (3 by 3 columns) around its current
 *   block position, through the shared growSnowColumn
 *   check (an existing layer below the cap of 4 layers
 *   grows by 1, or a fresh 1-layer snow is laid where one
 *   can survive; never over fluids, never any other block),
 *   at most 4 changes per laying pulse, and the laying
 *   position updates to the golem's feet. A golem standing
 *   still lays nothing: the drift only grows where it
 *   actually travels.
 * - The flounder (pulse every 10 ticks): one bounded enemy
 *   query in radius 5.0 (2.5 below to 2.5 above). An enemy
 *   standing in the drift (the block at its feet or the
 *   block directly below it is a snow layer block) gains a
 *   FRICTION_MODIFIER add_value modifier of +1.0 (modifier
 *   id advancedmobs:snowdrift_wake_friction): its ground
 *   friction doubles, so it decelerates twice as hard every
 *   step and cannot build or hold speed crossing the snow.
 *   Victims are tracked in the state tag; a victim the
 *   latest pulse did not see in the drift has the modifier
 *   removed in the same pulse and leaves the list, and
 *   end() removes it from everyone still tracked.
 *   Transient modifiers are not persisted, so a victim that
 *   unloads mid-ability loses the modifier on its own; the
 *   tracked removal is then a no-op.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6 and the MobAbility
 * contract): the wake's snow is PERMANENT and gated by the
 * vanilla mobGriefing game rule, checked through
 * AbilityContext.mobGriefingAllowed() at every laying
 * pulse:
 * - mobGriefing ON: the drift piles up and STAYS. end()
 *   touches no blocks; the trail is ordinary terrain
 *   afterwards.
 * - mobGriefing OFF: no blocks are changed at all. The
 *   speed boost still runs, and the flounder pulse still
 *   doubles the friction of enemies standing on any
 *   natural snow layers in range; only the golem's own
 *   fresh drift is missing.
 *
 * WHAT IT LOOKS LIKE: a SNOW_GOLEM_AMBIENT at volume 1.0,
 * pitch 1.2 at the start, 2 SNOWFLAKE kicked up at the
 * golem's feet every 2 ticks while it is actually moving,
 * and (in mobGriefing mode) a white trail visibly deepening
 * behind it, drift by drift.
 *
 * PERFORMANCE: tick() returns immediately except on the
 * pulse ticks: the laying pulse is at most 9 column scans
 * of at most 6 block reads every 4 ticks, capped at 4
 * changes, and only after a movement check; the flounder
 * pulse is one bounded entity query every 10 ticks plus a
 * pass over the tracked victims (at most the enemies one
 * pulse can catch). The speed modifier is built once in
 * start() and removed once in end(). Particle budget per
 * activation: at most 200 emissions times 2 = 400
 * particles, and only while the golem moves.
 *
 * State tag keys: "lx"/"lz" (the last laying position),
 * "victims" (list of compounds, one per friction victim:
 * "id" UUID, "last" the tick of the last pulse that saw
 * it in the drift).
 */
public final class SnowdriftWakeAbility implements MobAbility {

    static final int DURATION_TICKS = 400;
    private static final int LAY_INTERVAL = 4;
    private static final int LAY_RADIUS = 1;
    private static final int LAY_MAX_LAYERS = 4;
    private static final int LAY_MAX_CHANGES = 4;
    private static final double LAY_MIN_MOVE = 0.4;
    private static final int FRICTION_INTERVAL = 10;
    private static final double FRICTION_RADIUS = 5.0;
    private static final Identifier SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "snowdrift_wake_speed");
    private static final Identifier FRICTION_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "snowdrift_wake_friction");

    @Override
    public String id() {
        return "snowdrift_wake";
    }

    @Override
    public String displayName() {
        return "Snowdrift Wake";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        SnowGolemAbilitySupport.addModifier(caster, Attributes.MOVEMENT_SPEED,
            new AttributeModifier(SPEED_ID, 0.50, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        CompoundTag state = ctx.state();
        state.putDouble("lx", caster.getX());
        state.putDouble("lz", caster.getZ());
        state.put("victims", new ListTag());
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.SNOW_GOLEM_AMBIENT, SoundSource.HOSTILE, 1.0f, 1.2f);
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

        // Kicked-up snow at the feet, only while moving.
        if (ticks % 2 == 0) {
            Vec3 motion = caster.getDeltaMovement();
            if (motion.x * motion.x + motion.z * motion.z > 0.0025) {
                level.sendParticles(ParticleTypes.SNOWFLAKE, caster.getX(), caster.getY() + 0.2, caster.getZ(),
                    2, 0.4, 0.15, 0.4, 0.03);
            }
        }

        if (ticks % LAY_INTERVAL == 0) {
            layWake(ctx, level);
        }
        if (ticks % FRICTION_INTERVAL == 0) {
            frictionPulse(ctx, level, ticks);
        }
    }

    /** One laying pulse: piles the wake's snow around the golem when it has travelled. */
    private static void layWake(AbilityContext ctx, ServerLevel level) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        double lx = state.getDoubleOr("lx", caster.getX());
        double lz = state.getDoubleOr("lz", caster.getZ());
        double dx = caster.getX() - lx;
        double dz = caster.getZ() - lz;
        if (dx * dx + dz * dz < LAY_MIN_MOVE * LAY_MIN_MOVE) {
            return;
        }
        state.putDouble("lx", caster.getX());
        state.putDouble("lz", caster.getZ());
        if (!ctx.mobGriefingAllowed()) {
            return;
        }
        BlockPos center = caster.blockPosition();
        int changes = 0;
        for (int ox = -LAY_RADIUS; ox <= LAY_RADIUS && changes < LAY_MAX_CHANGES; ox++) {
            for (int oz = -LAY_RADIUS; oz <= LAY_RADIUS && changes < LAY_MAX_CHANGES; oz++) {
                if (SnowGolemAbilitySupport.growSnowColumn(level,
                        center.getX() + ox, center.getY(), center.getZ() + oz, LAY_MAX_LAYERS)) {
                    changes++;
                }
            }
        }
    }

    /**
     * One flounder pulse: enemies standing in snow gain the
     * friction modifier and are marked seen; tracked victims
     * the pulse did not see lose it and leave the list.
     */
    private static void frictionPulse(AbilityContext ctx, ServerLevel level, int ticks) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        List<LivingEntity> enemies = SnowGolemAbilitySupport.enemiesInBox(
            ctx, caster.getX(), caster.getY(), caster.getZ(), FRICTION_RADIUS, 2.5, 2.5);
        ListTag victims = state.getListOrEmpty("victims");
        for (LivingEntity enemy : enemies) {
            if (!standingInSnow(level, enemy)) {
                continue;
            }
            SnowGolemAbilitySupport.addModifier(enemy, Attributes.FRICTION_MODIFIER,
                new AttributeModifier(FRICTION_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
            markSeen(victims, enemy.getUUID(), ticks);
        }
        // Sweep: anyone not refreshed by this pulse is out
        // of the drift, so their friction returns to normal.
        ListTag kept = new ListTag();
        for (int i = 0; i < victims.size(); i++) {
            CompoundTag entry = victims.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id == null) {
                continue;
            }
            if (entry.getIntOr("last", 0) >= ticks) {
                kept.add(entry);
                continue;
            }
            if (level.getEntity(id) instanceof LivingEntity victim) {
                SnowGolemAbilitySupport.removeModifier(victim, Attributes.FRICTION_MODIFIER, FRICTION_ID);
            }
        }
        state.put("victims", kept);
    }

    /** Whether the entity is standing in or on a snow layer block. */
    private static boolean standingInSnow(ServerLevel level, LivingEntity entity) {
        BlockPos feet = entity.blockPosition();
        return level.getBlockState(feet).is(Blocks.SNOW)
            || level.getBlockState(feet.below()).is(Blocks.SNOW);
    }

    /** Records the victim in the tracked list with the current tick, or refreshes its entry. */
    private static void markSeen(ListTag victims, UUID id, int ticks) {
        for (int i = 0; i < victims.size(); i++) {
            CompoundTag entry = victims.getCompoundOrEmpty(i);
            UUID existing = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id.equals(existing)) {
                entry.putInt("last", ticks);
                return;
            }
        }
        CompoundTag entry = new CompoundTag();
        entry.store("id", UUIDUtil.CODEC, id);
        entry.putInt("last", ticks);
        victims.add(entry);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        SnowGolemAbilitySupport.removeModifier(caster, Attributes.MOVEMENT_SPEED, SPEED_ID);
        // Every friction victim still tracked gets its normal
        // footing back. The wake's snow is permanent terrain
        // and is deliberately NOT touched here (terrain rule,
        // see the class comment).
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag victims = ctx.state().getListOrEmpty("victims");
        for (int i = 0; i < victims.size(); i++) {
            CompoundTag entry = victims.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id != null && level.getEntity(id) instanceof LivingEntity victim) {
                SnowGolemAbilitySupport.removeModifier(victim, Attributes.FRICTION_MODIFIER, FRICTION_ID);
            }
        }
    }
}
