package mod.advancedmobs.ability.ravager;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.UUID;

/**
 * Rider of Ruin (Ravager ability 5 of 5, summon and mount).
 *
 * WHAT IT DOES: a vindicator champion vaults onto the
 * ravager's back and takes the reins, RAID_OMEN streaming
 * from the pair as they become a single unit of cavalry: the
 * beast charges and tramples on its own AI while the rider
 * hacks at anything that gets close from above with its own
 * axe and its own goals. If the rider is killed, the ravager
 * does not slow down, it enrages. If the ravager falls, the
 * rider fights on afoot, which is its own problem.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the ravager's current target within 20 blocks (distance
 *   squared 400) and the ravager carrying no passenger right
 *   now (a ravager already ridden, by a raid or by an
 *   earlier activation, never stacks a second champion).
 * - The summon (start()): one vindicator, created with
 *   EntitySpawnReason.MOB_SUMMONED and finalizeSpawn like
 *   every other summon in this mod, at the first
 *   collision-free spot 6, 4.5, or 3 blocks from the ravager
 *   on the side away from its target (the ravager's own
 *   position as the fallback). If its main hand is empty it
 *   is given an iron axe, with the slot's drop chance set to
 *   0 so the champion's axe is the ability's, not loot. The
 *   rider is allied both directions in MobWarData and
 *   registered in SummonerTracker (which is also what tags
 *   it umw_summoned for the natural spawn blocker), and is
 *   ordered onto the ravager's current target. RAVAGER_
 *   CELEBRATE and VINDICATOR_CELEBRATE answer each other at
 *   the summon. If the summon itself fails, the state tag
 *   carries a "no_rider" flag and the activation self-ends
 *   at tick 20 rather than wedging.
 * - Phase 0, the approach (at most 80 ticks): every 5 ticks
 *   the rider's navigation is driven onto the ravager at
 *   speed 1.4. At 3.2 blocks or closer, or at tick 80
 *   regardless, the rider mounts: startRiding with force.
 *   If the forced mount refuses at range, the rider is
 *   teleported onto the ravager's position and the mount is
 *   retried once. If it still refuses, the ability falls
 *   back to phase 2 (side-by-side: the champion fights afoot
 *   beside the beast, the Hoglin Cavalry fallback pattern).
 * - Phase 1, mounted: combined targeting every 5 ticks sets
 *   the rider's target to the ravager's live combat target,
 *   so the pair always fights one fight; the rider's own
 *   melee goal does its axe work from the saddle (a riding
 *   vindicator attacks from its mount in vanilla, the raid
 *   behavior this ability is built on). 2 RAID_OMEN
 *   particles stream at the pair every 4 ticks, at the
 *   rider's height. If the rider dies, is removed, or stops
 *   being the ravager's passenger, the enrage fires (below).
 * - The enrage: the ravager gains STRENGTH amplifier 0 and
 *   SPEED amplifier 0 for 300 ticks (15 seconds), applied
 *   once in tick() at the moment the loss is detected, with
 *   a RAVAGER_ROAR at volume 1.5, and the activation ends 30
 *   ticks later. The enrage effects are deliberately NOT
 *   removed by end(): they are the aftermath, not the
 *   ability's held state (the Hoglin Cavalry fury precedent).
 *   Losing a rider that never mounted (killed during the
 *   approach) does not enrage: the activation simply ends,
 *   the champion never took the reins.
 * - Phase 2, the afoot fallback: combined targeting every 5
 *   ticks, no omen stream (the pair is not a pair). If the
 *   rider dies in this phase the activation ends with no
 *   enrage, per the rule above.
 * - Duration: hard cap 1200 ticks (60 seconds).
 *
 * WHAT IT LOOKS LIKE: a vindicator sprinting in from the
 * flank, celebrations answering, then a leap into the saddle
 * and RAID_OMEN motes streaming off the pair as they hunt as
 * one unit. The rider's death is a volume 1.5 RAVAGER_ROAR
 * and a visibly faster, harder-hitting beast for 15 seconds.
 *
 * PERFORMANCE: one UUID resolve per tick; navigation and
 * targeting run every 5 ticks; the omen stream is 2
 * particles every 4 ticks. No entity queries at all after
 * the summon's bounded collision checks (at most 3). Particle
 * budget per activation: at most 1200 ticks of omen stream =
 * 600, plus a 10-particle POOF if end() discards the rider:
 * at most 610.
 *
 * CLEANUP (end()): the rider's fate splits on the ravager's,
 * per the design:
 * - Ravager alive (natural finish): the rider is dismounted,
 *   the alliance records are removed on both sides, and the
 *   rider is discarded in a puff of POOF: the champion is
 *   the ability's, and it does not outlive it (the Scarab
 *   Brood pattern). SummonerTracker records are deliberately
 *   not unregistered, matching every other summon in this
 *   mod: that registry cleans up on the rider's own removal.
 * - Ravager dead or removed (the framework termination
 *   path): the rider is left in the world exactly where it
 *   stands, dismounted by the ravager's own death, to fight
 *   on afoot as the design demands. The alliance records are
 *   still removed on both sides (they pointed at a dead
 *   beast; the rider's own war goes on without them).
 * - The enrage effects, when applied, are never touched by
 *   end() (see above).
 * No blocks are placed or broken by this ability, so the
 * terrain rule does not apply (stated here per the standing
 * convention).
 *
 * State tag keys: "rider" (the champion's UUID, codec
 * stored), "phase" (0 approach, 1 mounted, 2 afoot fallback,
 * 3 enrage countdown), "mounted" (1 once the rider has truly
 * taken the saddle), "end_tick" (the tick the enrage
 * countdown finishes), "no_rider" (true when the summon
 * failed at start).
 */
public final class RiderOfRuinAbility implements MobAbility {

    static final int MAX_TICKS = 1200;
    private static final int APPROACH_MAX_TICKS = 80;
    private static final double MOUNT_RANGE = 3.2;
    private static final int ENRAGE_END_DELAY = 30;

    @Override
    public String id() {
        return "rider_of_ruin";
    }

    @Override
    public String displayName() {
        return "Rider of Ruin";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 400.0
            && ctx.mob().getPassengers().isEmpty();
    }

    @Override
    public void start(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        state.putInt("phase", 0);
        state.putInt("mounted", 0);
        state.putInt("end_tick", Integer.MAX_VALUE);
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            state.putBoolean("no_rider", true);
            return;
        }
        LivingEntity target = ctx.target();

        Vindicator rider = EntityTypes.VINDICATOR.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (rider == null) {
            state.putBoolean("no_rider", true);
            return;
        }
        // Spawn on the side away from the target, first
        // collision-free spot wins, caster position as fallback.
        double awayX = 1.0;
        double awayZ = 0.0;
        if (target != null) {
            double dx = caster.getX() - target.getX();
            double dz = caster.getZ() - target.getZ();
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length > 1.0e-4) {
                awayX = dx / length;
                awayZ = dz / length;
            }
        }
        double spawnX = caster.getX();
        double spawnZ = caster.getZ();
        for (double dist : new double[]{6.0, 4.5, 3.0}) {
            double candX = caster.getX() + awayX * dist;
            double candZ = caster.getZ() + awayZ * dist;
            if (isFreeSpot(level, rider, candX, caster.getY(), candZ)) {
                spawnX = candX;
                spawnZ = candZ;
                break;
            }
        }
        rider.snapTo(spawnX, caster.getY(), spawnZ, ctx.random().nextFloat() * 360.0f, 0.0f);
        rider.finalizeSpawn(level, level.getCurrentDifficultyAt(rider.blockPosition()),
            EntitySpawnReason.MOB_SUMMONED, null);
        if (rider.getMainHandItem().isEmpty()) {
            rider.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
            rider.setDropChance(EquipmentSlot.MAINHAND, 0.0f);
        }
        if (!level.addFreshEntity(rider)) {
            state.putBoolean("no_rider", true);
            return;
        }

        // Team assignment, both directions, plus the summoner registry.
        ctx.data().addAlly(rider.getUUID());
        MobWarData.get(rider).addAlly(caster.getUUID());
        SummonerTracker.registerSummoned(rider.getUUID(), caster.getUUID());
        if (target != null) {
            rider.setTarget(target);
        }
        state.store("rider", UUIDUtil.CODEC, rider.getUUID());

        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.RAVAGER_CELEBRATE,
            SoundSource.HOSTILE, 1.0f, 1.1f);
        level.playSound(null, rider.getX(), rider.getY(), rider.getZ(), SoundEvents.VINDICATOR_CELEBRATE,
            SoundSource.HOSTILE, 1.0f, 1.2f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        if (state.getBooleanOr("no_rider", false)) {
            return; // The summon failed at start; isFinished() ends this quickly.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        Vindicator rider = resolveRider(ctx, level);
        int phase = state.getIntOr("phase", 0);
        int ticks = ctx.ticksActive();

        if (phase == 0) {
            // The approach: run the champion to the beast.
            if (rider == null || !rider.isAlive()) {
                state.putInt("phase", 3);
                state.putInt("end_tick", ticks);
                return;
            }
            if (ticks % 5 == 0) {
                rider.getNavigation().moveTo(caster, 1.4);
            }
            if (caster.distanceToSqr(rider) <= MOUNT_RANGE * MOUNT_RANGE || ticks >= APPROACH_MAX_TICKS) {
                boolean mounted = rider.startRiding(caster, true, true);
                if (!mounted) {
                    // One teleport-and-retry, then the afoot fallback.
                    rider.snapTo(caster.getX(), caster.getY(), caster.getZ(), rider.getYRot(), 0.0f);
                    mounted = rider.startRiding(caster, true, true);
                }
                if (mounted) {
                    state.putInt("mounted", 1);
                    state.putInt("phase", 1);
                    if (ctx.target() != null) {
                        rider.setTarget(ctx.target());
                    }
                } else {
                    state.putInt("phase", 2);
                }
            }
            return;
        }

        if (phase == 1) {
            // Mounted: the pair fights one fight.
            if (rider == null || !rider.isAlive() || rider.getVehicle() != caster) {
                enrage(ctx, level);
                state.putInt("phase", 3);
                state.putInt("end_tick", ticks + ENRAGE_END_DELAY);
                return;
            }
            if (ticks % 5 == 0) {
                LivingEntity target = ctx.target();
                if (target != null && rider.getTarget() != target) {
                    rider.setTarget(target);
                }
            }
            if (ticks % 4 == 0) {
                level.sendParticles(ParticleTypes.RAID_OMEN, caster.getX(), caster.getY() + 2.6, caster.getZ(),
                    2, 0.7, 0.4, 0.7, 0.05);
            }
            return;
        }

        if (phase == 2) {
            // The afoot fallback: the champion fights beside
            // the beast, targets synced, no enrage on loss.
            if (rider == null || !rider.isAlive()) {
                state.putInt("phase", 3);
                state.putInt("end_tick", ticks);
                return;
            }
            if (ticks % 5 == 0) {
                LivingEntity target = ctx.target();
                if (target != null && rider.getTarget() != target) {
                    rider.setTarget(target);
                }
            }
        }
        // Phase 3: nothing per tick; isFinished() reads the
        // recorded end tick.
    }

    /** The enrage: strength and speed for the beast, applied once, never removed by end(). */
    private void enrage(AbilityContext ctx, ServerLevel level) {
        Mob caster = ctx.mob();
        caster.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 300, 0));
        caster.addEffect(new MobEffectInstance(MobEffects.SPEED, 300, 0));
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.RAVAGER_ROAR,
            SoundSource.HOSTILE, 1.5f, 0.9f);
    }

    /** Resolves the tracked rider from the state tag, or null when gone. */
    private Vindicator resolveRider(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        if (!state.contains("rider")) {
            return null;
        }
        UUID id = state.read("rider", UUIDUtil.CODEC).orElse(null);
        Entity entity = RavagerAbilitySupport.resolve(level, id);
        return entity instanceof Vindicator rider ? rider : null;
    }

    /** Whether the rider's bounding box at the given feet position collides with nothing. */
    private boolean isFreeSpot(ServerLevel level, Vindicator rider, double x, double y, double z) {
        double halfWidth = rider.getBbWidth() / 2.0;
        AABB box = new AABB(x - halfWidth, y, z - halfWidth, x + halfWidth, y + rider.getBbHeight(), z + halfWidth);
        return level.noCollision(rider, box);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        if (state.getBooleanOr("no_rider", false)) {
            return ctx.ticksActive() >= 20;
        }
        if (state.getIntOr("phase", 0) == 3) {
            return ctx.ticksActive() >= state.getIntOr("end_tick", Integer.MAX_VALUE);
        }
        return ctx.ticksActive() >= MAX_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        if (!state.contains("rider")) {
            return;
        }
        UUID riderId = state.read("rider", UUIDUtil.CODEC).orElse(null);
        if (riderId == null) {
            return;
        }
        Mob caster = ctx.mob();
        // The alliance ends with the ability, whichever side
        // is still here.
        ctx.data().removeAlly(riderId);
        Vindicator rider = resolveRider(ctx, level);
        if (rider == null) {
            return;
        }
        MobWarData.get(rider).removeAlly(caster.getUUID());
        if (!rider.isAlive()) {
            return;
        }
        if (caster.isAlive() && !caster.isRemoved()) {
            // Natural finish: the champion is the ability's,
            // and it does not outlive it.
            rider.stopRiding();
            level.sendParticles(ParticleTypes.POOF, rider.getX(), rider.getY() + 1.0, rider.getZ(),
                10, 0.4, 0.6, 0.4, 0.05);
            rider.discard();
        }
        // Ravager dead or removed (termination): the rider is
        // left standing to fight on afoot, per the design.
        // Nothing else is done to it.
    }
}
