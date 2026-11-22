package mod.advancedmobs.ability;

import mod.advancedmobs.data.MobWarData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;

import java.util.UUID;

/**
 * AbilityContext: everything an ability activation needs, handed to every
 * MobAbility method by the framework.
 *
 * STATE TAG RULES:
 * - state() is the per-mob scratch CompoundTag for the CURRENT activation.
 *   The framework creates it empty when an ability starts, persists it in
 *   MobWarData under "am_ability_state" for as long as the ability is
 *   active, and discards it when the ability ends. Abilities store ALL of
 *   their activation data here (counters, phases, chosen targets, spawned
 *   entity ids) and never in Java fields, so an activation survives chunk
 *   unloads and restarts.
 * - Keys starting with "fw_" are reserved for the framework (currently
 *   "fw_ticks", the activation tick counter behind ticksActive()).
 *   Ability implementations must not read or write "fw_" keys.
 *
 * TARGET: target() resolves the mob's current combat target live on each
 * call: first the vanilla target (mob.getTarget()), and if that is empty,
 * the war system's tracked target UUID in MobWarData, resolved against the
 * server level. It returns null when the mob has no living target. The
 * framework only STARTS abilities while a target exists, but a target can
 * die or be lost mid-activation, so tick() code must null-check target().
 */
public final class AbilityContext {

    /** Framework-reserved state key: ticks elapsed in the current activation. */
    public static final String KEY_TICKS_ACTIVE = "fw_ticks";

    private final Mob mob;
    private final Level level;
    private final MobWarData data;
    private final CompoundTag state;

    public AbilityContext(Mob mob, Level level, MobWarData data, CompoundTag state) {
        this.mob = mob;
        this.level = level;
        this.data = data;
        this.state = state;
    }

    /** The mob using the ability. Never null. */
    public Mob mob() {
        return mob;
    }

    /** The mob's level. On the server this is a ServerLevel in practice. */
    public Level level() {
        return level;
    }

    /** The mob's level as a ServerLevel, or null if not on a server level. */
    public ServerLevel serverLevel() {
        return level instanceof ServerLevel serverLevel ? serverLevel : null;
    }

    /** The mob's persistent war data (points, kills, allies, ability state). */
    public MobWarData data() {
        return data;
    }

    /** Per-activation scratch tag; see the class comment for the rules. */
    public CompoundTag state() {
        return state;
    }

    /** The mob's own random source; use it for every roll inside an ability. */
    public RandomSource random() {
        return mob.getRandom();
    }

    /**
     * Ticks elapsed in the current activation: 0 during start(), then 1 on
     * the first tick() call, incremented by the framework before each tick().
     */
    public int ticksActive() {
        return state.getIntOr(KEY_TICKS_ACTIVE, 0);
    }

    /**
     * The mob's current combat target, resolved live (see class comment).
     * Null when the mob has no living target right now.
     */
    public LivingEntity target() {
        LivingEntity vanillaTarget = mob.getTarget();
        if (vanillaTarget != null && vanillaTarget.isAlive()) {
            return vanillaTarget;
        }
        UUID tracked = data.getCurrentTarget();
        if (tracked != null && level instanceof ServerLevel serverLevel) {
            Entity entity = serverLevel.getEntity(tracked);
            if (entity instanceof LivingEntity living && living.isAlive()) {
                return living;
            }
        }
        return null;
    }
}
