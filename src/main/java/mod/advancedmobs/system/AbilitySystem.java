package mod.advancedmobs.system;

import com.google.gson.JsonObject;
import mod.advancedmobs.AdvancedMobsMod;
import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.AbilityRegistry;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;
import mod.advancedmobs.config.ModConfig;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.entity.MobWarlordEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AbilitySystem: the gating, granting, firing, and cooldown engine of the
 * new ability system. It owns NO ability content: the abilities themselves
 * live behind MobAbilityProvider registrations (see AbilityRegistry), one
 * provider per mob type, 5 abilities per mob. This class decides WHETHER a
 * mob has abilities, WHICH of its mob's abilities it may use, and WHEN one
 * fires. The rules below are Carter's design (Nov 21, 2026). Where a number
 * is binding it is stated as a rule; Carter's halfway illustration is
 * labeled as an example, not a requirement.
 *
 * ==========================================================================
 * POWER FRACTION
 * ==========================================================================
 * Every mob's power is a fraction frac in [0, 1]:
 *
 *     frac = clamp(lifetimePointsEarned / FULL_POWER, 0, 1)
 *
 * lifetimePointsEarned is MobWarData.skillPoints, which ScalingSystem
 * maintains as the mob's lifetime total of earned points (world-age day
 * points plus kill points plus, for bosses, combat points accrued while
 * in a fight; spending is tracked separately in spentPoints,
 * and the spendable budget is total minus spent). FULL_POWER is the config
 * value ModConfig.abilityFullPowerPoints (default 3000): the summed cost of
 * every level of every upgrade track in the Zombie reference config
 * (potion effects 230 + weapon 439 + shield 352 + helmet 485 + chestplate
 * 464 + leggings 488 + boots 528 = 2,986, rounded to 3,000). A mob that has
 * earned FULL_POWER points has earned enough to buy out a complete upgrade
 * tree, which is what "full power" means here.
 *
 * ==========================================================================
 * ACCESS: WHO HAS ABILITIES, AND HOW MANY
 * ==========================================================================
 * Access chance (rolled once per evaluation, see EVALUATION below):
 *   normal mob: chance = 0.01 + 0.24 * frac   (1 percent at frac 0, scaling
 *                                              to a 25 percent cap at frac 1)
 *   boss:       chance = 0.01 + 0.99 * frac   (1 percent at frac 0, scaling
 *                                              to 100 percent at frac 1)
 * The endpoints are the binding rules: 1 percent at zero power, 25 percent
 * cap for normal mobs and 100 percent for bosses at full power.
 *
 * Bosses are the entity ids minecraft:ender_dragon, minecraft:wither,
 * minecraft:warden, minecraft:elder_guardian (the same set ScalingSystem
 * uses for boss scaling), plus the mod's own MobWarlordEntity.
 *
 * Accessible ability count, by power bracket (both tracks use the same
 * table; the count grows from 1 to all 5 across the power range, which is
 * the binding shape):
 *   frac below 0.30: 1      0.55 to 0.75: 3      0.90 and up: 5
 *   0.30 to 0.55:    2      0.75 to 0.90: 4
 * Carter's illustration of the shape: at halfway power a normal mob sits
 * near a 13 to 14 percent access chance with 2 accessible abilities. That
 * example is illustrative only, not a required checkpoint; the linear
 * chance formula gives 13 percent at frac 0.5 and the bracket table gives
 * 2, so the design happens to land on his example.
 *
 * ENDER DRAGON EXCEPTION: the Ender Dragon never rolls for access. It is
 * guaranteed abilities from the very start (at least 1 accessible ability
 * at frac 0), and its accessible set simply follows the bracket count as
 * its power grows. It is the only mob with this guarantee.
 *
 * GRANTS: when access is granted, the mob's accessible set is a uniformly
 * random subset of its mob's registered abilities, of the bracket count's
 * size. Grants are permanent: once a mob has abilities they are never
 * taken away, and when its bracket count later rises, its set is extended
 * with random picks from its remaining abilities up to the new count.
 *
 * ABILITY SLOTS ARE BOUGHT: the number of abilities a mob may hold is
 * also limited by the ability slots it has purchased with progression
 * points through ScalingSystem (skillData key ScalingSystem
 * .ABILITY_SLOTS_KEY, costs 30/60/120/240/480 for slots 1-5; the option
 * competes with gear in the same spending pass). The effective unlock
 * target is min(bracket count, unlocked slots), where slots a mob
 * already holds abilities in count as unlocked (mobs granted abilities
 * under the earlier free-grant rules keep them and are never charged
 * retroactively). Buying a first slot also guarantees access: a mob that
 * paid for a slot does not additionally have to win the access roll.
 * The access roll and the Ender Dragon exception still govern mobs that
 * have purchased nothing.
 *
 * EVALUATION: a mob is evaluated when its current bracket count is higher
 * than the count stored at its last evaluation (MobWarData
 * am_ability_eval_count, 0 before the first evaluation). Concretely:
 *   - First tick in the war system (with a provider registered for its
 *     mob): evaluate at the current bracket.
 *   - On evaluation, a mob without abilities rolls its access chance once
 *     (dragon excepted; a purchased ability slot also always grants
 *     access). Success grants access; how many abilities actually unlock
 *     is capped by purchased slots (see ABILITY SLOTS ARE BOUGHT above).
 *   - On evaluation, a mob with abilities extends its set toward its
 *     bracket count, up to its unlocked (purchased/held) slot count. A
 *     new purchase also triggers evaluation, so a bought slot unlocks
 *     promptly instead of waiting for the next bracket.
 *   - A failed roll is NOT retried until the mob crosses into a higher
 *     count bracket, which triggers a fresh evaluation and a fresh roll.
 * Mobs whose type has no registered provider are never evaluated: their
 * access state stays untouched until their abilities exist.
 *
 * ==========================================================================
 * FIRING AND COOLDOWN
 * ==========================================================================
 * - Abilities fire only in combat: the mob must have a living target (the
 *   vanilla target, or the war system's tracked target in MobWarData).
 *   Starting is gated on combat; an ability already running is NOT
 *   cancelled when its target dies, it ticks to its normal end.
 * - Only ONE ability per mob is active at a time.
 * - When the mob is idle (no active ability), in combat, and its cooldown
 *   has expired, the framework picks uniformly at random among its
 *   accessible abilities whose canTrigger passes and starts one. If none
 *   can trigger, nothing starts and the pick is retried next tick.
 * - The cooldown starts when an ability ENDS (after end() returns), never
 *   when it starts. Cooldown length in seconds:
 *       seconds = 60 - 50 * shaped,  shaped = pow(random01, 2.5 - 2*frac)
 *   so the cooldown always lands in [10, 60] seconds (the binding range),
 *   weighted by power: at frac 0 the exponent is 2.5 and shaped skews
 *   toward 0, pushing cooldowns toward 60 seconds; at frac 1 the exponent
 *   is 0.5 and shaped skews toward 1, pushing cooldowns toward 10 seconds.
 *   A full-power boss therefore fires about every 10 to 25 seconds plus
 *   the running time of the ability itself. frac is read at the moment
 *   the ability ends.
 * - The cooldown end is stored as an absolute game time in MobWarData
 *   (am_ability_cooldown_end), so it survives reloads.
 *
 * PERSISTENCE: all of this state lives in MobWarData under am_ability_
 * keys (eval count, has-abilities flag, accessible ids, active id, the
 * active ability's state tag, cooldown end). The legacy ability_ keys from
 * the removed ability set are inert and are not used here.
 *
 * ==========================================================================
 * TERMINATION (death and removal)
 * ==========================================================================
 * When a mob dies or is genuinely removed from the world while an ability
 * is active, terminateActiveAbility runs the active ability's end()
 * exactly once and clears the activation, with NO cooldown (the mob is
 * gone; a cooldown would be meaningless). Call sites: MobDeathTrackerMixin
 * (LivingEntity.die HEAD) and the ENTITY_UNLOAD handler in AdvancedMobsMod
 * (only when the entity is actually removed or dead at unload time).
 * A plain CHUNK unload is deliberately NOT a termination: the activation
 * state persists in MobWarData and the ability resumes ticking when the
 * chunk reloads, per the MobAbility contract. Termination is idempotent:
 * the death path clears the activation, so the later unload of the corpse
 * finds nothing to end a second time. Termination intentionally ignores
 * the abilitiesEnabled toggle: an ability that started must always get
 * its cleanup, even if abilities were switched off mid-activation.
 */
public final class AbilitySystem {

    /** Entity ids that use the boss access track. Mirrors ScalingSystem's boss set. */
    private static final java.util.Set<String> BOSS_ENTITY_IDS = java.util.Set.of(
        "minecraft:ender_dragon",
        "minecraft:wither",
        "minecraft:warden",
        "minecraft:elder_guardian"
    );

    private static final String ENDER_DRAGON_ID = "minecraft:ender_dragon";

    private AbilitySystem() {}

    // ======================================================================
    //                              MAIN TICK
    // ======================================================================

    /**
     * Per-tick driver for one mob, called from MobDataMixin right after
     * ScalingSystem.processMobTick (so the lifetime points behind frac are
     * fresh). Server side only; does nothing when abilities are disabled,
     * when the mob's type has no provider yet, or when the mob has no
     * config (same resolution ScalingSystem uses).
     */
    public static void tick(Mob mob, Level level, MobWarData data) {
        if (mob == null || data == null || level == null || level.isClientSide()) {
            return;
        }
        if (!mob.isAlive() || mob.isRemoved()) {
            return;
        }
        ModConfig config = ModConfig.getInstance();
        if (!config.modEnabled || !config.abilitiesEnabled) {
            return;
        }

        MobAbilityProvider provider = resolveProvider(mob);
        if (provider == null) {
            return; // This mob type has no abilities implemented yet.
        }
        List<MobAbility> registered = provider.abilities();
        if (registered == null || registered.isEmpty()) {
            return;
        }

        double frac = powerFraction(data, config);
        evaluateAccess(mob, data, registered, frac);

        long now = level.getGameTime();
        String activeId = data.getAbilityActiveId();
        if (activeId != null && !activeId.isEmpty()) {
            tickActiveAbility(mob, level, data, registered, activeId, frac, now);
            return;
        }

        if (!data.hasAbilities()) {
            return;
        }
        // PERF: getAbilityAccessibleIds() returns a defensive copy, so it
        // is fetched once here and reused for the empty check and the
        // candidate loop below (this idle path previously copied it twice
        // per tick for every mob with abilities).
        List<String> accessibleIds = data.getAbilityAccessibleIds();
        if (accessibleIds.isEmpty()) {
            return;
        }
        if (now < data.getAbilityCooldownEnd()) {
            return;
        }
        AbilityContext ctx = new AbilityContext(mob, level, data, data.getAbilityActiveState());
        if (ctx.target() == null) {
            return; // Firing requires combat.
        }
        List<MobAbility> candidates = new ArrayList<>();
        for (String id : accessibleIds) {
            MobAbility ability = findAbility(registered, id);
            if (ability != null && ability.canTrigger(ctx)) {
                candidates.add(ability);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        MobAbility chosen = candidates.get(mob.getRandom().nextInt(candidates.size()));
        data.setAbilityActiveId(chosen.id());
        data.getAbilityActiveState().putInt(AbilityContext.KEY_TICKS_ACTIVE, 0);
        try {
            chosen.start(new AbilityContext(mob, level, data, data.getAbilityActiveState()));
        } catch (Throwable t) {
            // A broken ability must not wedge the mob: drop the activation
            // and cool down as if it had ended.
            AdvancedMobsMod.LOGGER.error("Ability {} failed to start on {}; dropping activation",
                    chosen.id(), mob.getName().getString(), t);
            clearActivation(data);
            data.setAbilityCooldownEnd(now + rollCooldownTicks(frac, mob.getRandom()));
        }
    }

    // ======================================================================
    //                        PROVIDER RESOLUTION
    // ======================================================================

    /**
     * Provider lookup cache, keyed by entity class. PERF: this tick used
     * to resolve the mob's full scaling config through
     * ScalingSystem.getConfigForMob on every tick for every mob (the
     * third such resolution per tick, after monitorEquipmentState and
     * processMobTick), just to read its mob_name. Config resolution is
     * deterministic per entity class (ScalingSystem keys its own index
     * by class name), and providers are registered once during mod init
     * and never change afterwards (see AbilityRegistry), so the answer
     * is cached per class: after the first tick of a class, this is one
     * ConcurrentHashMap get, and for the 80 mob types with no provider
     * the whole ability tick costs little more than that get.
     */
    private static final Map<Class<?>, Optional<MobAbilityProvider>> PROVIDER_BY_CLASS = new ConcurrentHashMap<>();

    /** The provider for a mob's type, or null when none is registered. */
    private static MobAbilityProvider resolveProvider(Mob mob) {
        Optional<MobAbilityProvider> cached = PROVIDER_BY_CLASS.get(mob.getClass());
        if (cached != null) {
            return cached.orElse(null);
        }
        MobAbilityProvider provider = null;
        JsonObject mobConfig = ScalingSystem.getConfigForMob(mob);
        if (mobConfig != null && mobConfig.has("mob_name")) {
            provider = AbilityRegistry.getProvider(mobConfig.get("mob_name").getAsString());
        }
        PROVIDER_BY_CLASS.put(mob.getClass(), Optional.ofNullable(provider));
        return provider;
    }

    // ======================================================================
    //                             TERMINATION
    // ======================================================================

    /**
     * Ends the mob's active ability because the mob died or was removed
     * from the world: calls the ability's end() exactly once and clears
     * the activation, without starting a cooldown. See the TERMINATION
     * section of the class comment for the full contract (including why a
     * plain chunk unload does NOT come through here). Safe to call when
     * nothing is active; safe to call twice (the second call finds the
     * activation already cleared).
     */
    public static void terminateActiveAbility(Mob mob, Level level, MobWarData data) {
        if (mob == null || data == null || level == null || level.isClientSide()) {
            return;
        }
        String activeId = data.getAbilityActiveId();
        if (activeId == null || activeId.isEmpty()) {
            return;
        }
        MobAbilityProvider provider = resolveProvider(mob);
        MobAbility ability = provider != null ? findAbility(provider.abilities(), activeId) : null;
        try {
            if (ability != null) {
                ability.end(new AbilityContext(mob, level, data, data.getAbilityActiveState()));
            }
        } finally {
            // The activation is cleared even when end() throws or the
            // ability no longer resolves, so a stale active id can never
            // wedge the mob (or be ended twice).
            clearActivation(data);
        }
    }

    // ======================================================================
    //                           ACCESS EVALUATION
    // ======================================================================

    /**
     * Runs the access evaluation when the mob's bracket count has risen
     * past the count stored at its last evaluation. See the class comment
     * for the exact rules. Mutates only MobWarData ability state.
     */
    private static void evaluateAccess(Mob mob, MobWarData data, List<MobAbility> registered, double frac) {
        int bracketCount = accessibleCountFor(frac);
        // Ability slots bought through ScalingSystem cap how many
        // abilities this mob may hold; abilities it already holds count
        // as unlocked slots, so pre-purchase grants are grandfathered.
        int purchasedSlots = data.getSkillData() != null
            ? data.getSkillData().getIntOr(ScalingSystem.ABILITY_SLOTS_KEY, 0)
            : 0;
        int currentCount = data.hasAbilities() ? data.getAbilityAccessibleIds().size() : 0;
        int unlockedCount = Math.max(purchasedSlots, currentCount);
        // The Ender Dragon's guarantee is unconditional: its unlocked
        // count is always its full bracket, purchased or not.
        if (isEnderDragon(mob)) {
            unlockedCount = bracketCount;
        }
        int targetCount = Math.min(bracketCount, unlockedCount);
        boolean bracketRose = bracketCount > data.getAbilityEvalCount();
        if (!bracketRose && targetCount <= currentCount) {
            return;
        }
        RandomSource random = mob.getRandom();

        if (!data.hasAbilities()) {
            boolean granted;
            if (isEnderDragon(mob)) {
                granted = true; // The dragon never rolls for access.
            } else if (purchasedSlots > 0) {
                granted = true; // A paid-for slot always grants access.
            } else {
                double chance = accessChance(frac, isBoss(mob));
                granted = random.nextDouble() < chance;
            }
            if (granted) {
                data.setHasAbilities(true);
            }
        }

        if (data.hasAbilities()) {
            extendAccessibleSet(data, registered, targetCount, random);
        }
        if (bracketRose) {
            data.setAbilityEvalCount(bracketCount);
        }
    }

    /**
     * Extends the mob's accessible set with uniformly random picks from its
     * mob's registered abilities that it does not already have, until the
     * set reaches the target count (or the registered list runs out).
     * Existing picks are never removed.
     */
    private static void extendAccessibleSet(MobWarData data, List<MobAbility> registered, int targetCount, RandomSource random) {
        List<String> accessible = data.getAbilityAccessibleIds();
        if (accessible.size() >= targetCount) {
            return;
        }
        List<MobAbility> remaining = new ArrayList<>();
        for (MobAbility ability : registered) {
            if (!accessible.contains(ability.id())) {
                remaining.add(ability);
            }
        }
        while (accessible.size() < targetCount && !remaining.isEmpty()) {
            int pick = random.nextInt(remaining.size());
            accessible.add(remaining.remove(pick).id());
        }
        data.setAbilityAccessibleIds(accessible);
    }

    // ======================================================================
    //                         ACTIVE ABILITY TICKING
    // ======================================================================

    /**
     * Ticks the mob's active ability once. Increments the activation tick
     * counter, calls tick(), and when isFinished() reports done, calls
     * end(), clears the activation, and starts the cooldown (the cooldown
     * clock starts here, at the end, per the design). If the active id no
     * longer resolves to a registered ability (removed between versions),
     * the activation is dropped and the cooldown starts as if it ended.
     */
    private static void tickActiveAbility(Mob mob, Level level, MobWarData data,
                                          List<MobAbility> registered, String activeId,
                                          double frac, long now) {
        MobAbility ability = findAbility(registered, activeId);
        if (ability == null) {
            clearActivation(data);
            data.setAbilityCooldownEnd(now + rollCooldownTicks(frac, mob.getRandom()));
            return;
        }
        AbilityContext ctx = new AbilityContext(mob, level, data, data.getAbilityActiveState());
        data.getAbilityActiveState().putInt(AbilityContext.KEY_TICKS_ACTIVE, ctx.ticksActive() + 1);
        try {
            ability.tick(ctx);
            if (ability.isFinished(ctx)) {
                ability.end(ctx);
                clearActivation(data);
                data.setAbilityCooldownEnd(now + rollCooldownTicks(frac, mob.getRandom()));
            }
        } catch (Throwable t) {
            // Without this guard a throwing ability re-throws every tick
            // forever: the activation is never cleared, end() never runs
            // (so its effects are never reversed), and the log fills.
            AdvancedMobsMod.LOGGER.error("Ability {} threw while active on {}; forcing cleanup",
                    activeId, mob.getName().getString(), t);
            try {
                ability.end(ctx);
            } catch (Throwable ignored) {
                // end() is best-effort during forced cleanup.
            }
            clearActivation(data);
            data.setAbilityCooldownEnd(now + rollCooldownTicks(frac, mob.getRandom()));
        }
    }

    /** Clears the active ability id and its per-activation state tag. */
    private static void clearActivation(MobWarData data) {
        data.setAbilityActiveId("");
        data.setAbilityActiveState(new net.minecraft.nbt.CompoundTag());
    }

    private static MobAbility findAbility(List<MobAbility> registered, String id) {
        for (MobAbility ability : registered) {
            if (ability.id().equals(id)) {
                return ability;
            }
        }
        return null;
    }

    // ======================================================================
    //                              THE MATH
    // ======================================================================

    /**
     * The mob's power fraction in [0, 1]: lifetime earned points
     * (MobWarData.skillPoints, maintained by ScalingSystem as day points
     * plus kill points) over the FULL_POWER config reference. See the
     * class comment for the FULL_POWER derivation.
     */
    public static double powerFraction(MobWarData data, ModConfig config) {
        double fullPower = Math.max(1.0, config.abilityFullPowerPoints);
        double frac = data.getSkillPoints() / fullPower;
        // Math.max above does not stop NaN: a NaN divisor or points value
        // would otherwise flow into every comparison downstream.
        if (!Double.isFinite(frac)) return 0.0;
        if (frac < 0.0) return 0.0;
        if (frac > 1.0) return 1.0;
        return frac;
    }

    /**
     * Access chance for one evaluation roll. Normal mobs: 0.01 + 0.24*frac
     * (1 percent at zero power, 25 percent cap at full power). Bosses:
     * 0.01 + 0.99*frac (1 percent at zero power, 100 percent at full).
     */
    public static double accessChance(double frac, boolean boss) {
        return boss ? 0.01 + 0.99 * frac : 0.01 + 0.24 * frac;
    }

    /**
     * Accessible ability count for a power fraction, per the bracket
     * table in the class comment: 1 below 0.30, 2 from 0.30, 3 from 0.55,
     * 4 from 0.75, 5 from 0.90.
     */
    public static int accessibleCountFor(double frac) {
        if (frac < 0.30) return 1;
        if (frac < 0.55) return 2;
        if (frac < 0.75) return 3;
        if (frac < 0.90) return 4;
        return 5;
    }

    /**
     * Rolls a cooldown in ticks: seconds = 60 - 50 * pow(random01,
     * 2.5 - 2*frac), always within [10, 60] seconds, weighted toward 60
     * for weak mobs and toward 10 for full-power mobs. Called at the
     * moment an ability ends; the cooldown starts then, never at start.
     */
    public static long rollCooldownTicks(double frac, RandomSource random) {
        double shaped = Math.pow(random.nextDouble(), 2.5 - 2.0 * frac);
        double seconds = 60.0 - 50.0 * shaped;
        return Math.round(seconds * 20.0);
    }

    /**
     * Whether the mob uses the boss access track: the four vanilla boss
     * entity ids (same set ScalingSystem scales as bosses) plus the mod's
     * Mob Warlord entity.
     */
    public static boolean isBoss(Mob mob) {
        if (mob instanceof MobWarlordEntity) {
            return true;
        }
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType());
        return id != null && BOSS_ENTITY_IDS.contains(id.toString());
    }

    /** Whether the mob is the Ender Dragon (the guaranteed-access mob). */
    public static boolean isEnderDragon(Mob mob) {
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType());
        return id != null && ENDER_DRAGON_ID.equals(id.toString());
    }
}
