package mod.advancedmobs.system;

import mod.advancedmobs.config.ModConfig;
import mod.advancedmobs.data.MobWarData;
import net.minecraft.world.entity.Mob;

/**
 * ProgressionTicker: the single per-tick driver for the whole progression
 * stack (equipment monitoring, points and spending, abilities), shared by
 * every mixin hook that ticks a mob.
 *
 * WHY THIS EXISTS (Part A of the Nov 21, 2026 audit): the stack used to be
 * driven only from MobDataMixin's injection into Mob.serverAiStep. That
 * covers almost every mob, but NOT the Ender Dragon: verified against the
 * real 26.2 jar, EnderDragon overrides aiStep() completely and its
 * bytecode contains no call to Mob.aiStep or LivingEntity.aiStep, and
 * LivingEntity.aiStep is the only caller of serverAiStep, so the dragon
 * never ran processMobTick or AbilitySystem.tick at all (no day points,
 * no spending, no ability access, despite its config and its guaranteed
 * access rule). EnderDragonProgressionMixin now hooks the dragon's own
 * aiStep and calls this same driver, so the dragon runs the identical
 * pipeline in the identical order as every other mob.
 *
 * ORDER IS PART OF THE CONTRACT: equipment monitoring first, then
 * ScalingSystem.processMobTick (which refreshes lifetime points), then
 * AbilitySystem.tick (whose power fraction reads those fresh points).
 */
public final class ProgressionTicker {

    private ProgressionTicker() {}

    /** Runs one progression tick for a mob. Server side callers only. */
    public static void tick(Mob mob, MobWarData data) {
        if (mob == null || data == null) {
            return;
        }
        if (!ModConfig.getInstance().modEnabled) {
            return;
        }
        ScalingSystem.monitorEquipmentState(mob, data);
        ScalingSystem.processMobTick(mob, mob.level(), data);
        // Ability system ticks after scaling so the lifetime points
        // behind its power fraction are fresh for this tick.
        AbilitySystem.tick(mob, mob.level(), data);
    }
}
