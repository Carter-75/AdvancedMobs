package mod.advancedmobs.ability.skeletonhorse;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.equine.SkeletonHorse;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Gravecaller Host (Skeleton Horse ability 1 of 5, summon).
 *
 * WHAT IT DOES: the skeleton horse rears in a storm of soul
 * particles and calls its old cavalry: two more skeleton
 * horses ride in, each carrying a bow-armed, helmeted
 * skeleton, exactly like the classic skeleton trap. Host,
 * mounts, and riders form up on the host's target and charge
 * as one undead unit.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the horse's current target within 16 blocks (distance
 *   squared 256).
 * - The call (start()): SKELETON_HORSE_AMBIENT at volume
 *   1.6, pitch 0.7 (the gravecall), and 16 SOUL particles
 *   swirling over the host. Two skeleton horses are
 *   summoned 2.5 blocks to the host's left and right (the
 *   side axis from its look direction), falling back to the
 *   host's own position at the first collision-free spot.
 *   Each is created with EntitySpawnReason.MOB_SUMMONED,
 *   forced adult (setAge(0)), explicitly NOT a trap horse
 *   (setTrap(false), so it never runs the vanilla trap
 *   script), tamed (setTamed(true), the Headless Cavalry
 *   precedent, so it accepts its rider and steering), and
 *   finalizeSpawn like every other summon in this mod. On
 *   each mount, one skeleton rider: created the same way,
 *   given a BOW in its main hand and an IRON_HELMET on its
 *   head (the classic trap kit; the helmet also keeps the
 *   rider from burning in daylight during the activation),
 *   added to the level, then mounted with startRiding. The
 *   rider's vanilla bow AI shoots from the saddle (vanilla
 *   skeleton traps and Headless Cavalry both prove mounted
 *   skeletons shoot).
 * - Team assignment, per summoned entity (both horses and
 *   both riders), both directions plus the registry: the
 *   caster's data gains the summon as an ally, the summon's
 *   data gains the caster, and the pair is registered in
 *   SummonerTracker (which also tags it umw_summoned for
 *   the natural spawn blocker). Mounts and riders are
 *   ordered onto the caster's current target at spawn.
 * - Babysitting every 5 ticks: each living mount whose
 *   target is not the caster's live target is re-ordered
 *   onto it and navigation-driven at speed 1.3 toward it
 *   whenever it is more than 3 blocks away (a passive
 *   animal's AI never chases a set target on its own, so
 *   the drive is what moves the cavalry: the Herd Sovereign
 *   precedent). Each living rider is likewise re-ordered
 *   onto the shared target (it is a passenger; its own
 *   navigation is irrelevant, its bow is not). The host
 *   itself is driven at speed 1.25 whenever it is more than
 *   3 blocks from the target, so the three horses charge as
 *   one unit instead of the host lagging behind its call.
 * - Soul wisps (every 20 ticks): 3 SOUL particles drift
 *   over the host while the host rides.
 * - The host watch (on the babysit pass, every 20 ticks):
 *   if both summoned mounts are dead or gone after tick 20,
 *   the state tag's "host_gone" flag is set and the ability
 *   ends early (the cavalry is the ability; with no mounts
 *   there is nothing to sustain).
 * - Duration: at most 400 ticks (20 seconds), ending early
 *   on the host_gone flag.
 * - end(): the alliance records are removed on both sides
 *   for every tracked summon, and each one still alive is
 *   discarded in a puff of 6 SOUL particles, riders first
 *   (so no rider is left standing where its mount vanished
 *   under it): the cavalry is the ability's, and it does
 *   not outlive it. If the caster dies first, the framework
 *   termination path runs this same end(), so the cavalry
 *   never outlives its host either. SummonerTracker records
 *   are deliberately not unregistered here, matching the
 *   other summon packages: that registry cleans up on the
 *   summoned mob's own removal.
 *
 * WHAT IT LOOKS LIKE: a deep bony neigh, a swirl of soul
 * wisps, and two skeleton horses thundering in from either
 * side with bow-armed skeletons in their saddles, the whole
 * unit wheeling onto the host's target as one.
 *
 * PERFORMANCE: start() does 2 bounded collision checks and
 * 4 spawns, once. tick() returns immediately except every
 * 5th tick (babysit: resolves at most 4 UUIDs, retargets,
 * drives navigation) and every 20th tick (3 wisps plus the
 * host watch folded into the babysit pass). Particle budget
 * per activation: 16 at start, wisps 20 times 3 = 60, end
 * at most 4 times 6 = 24, total at most 100 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "mounts" (list of compounds, one per
 * summoned horse: "id", its UUID), "riders" (list of
 * compounds, one per summoned skeleton: "id", its UUID,
 * same index order as "mounts"), "host_gone" (1 once both
 * mounts are confirmed dead or gone).
 */
public final class GravecallerHostAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int BABYSIT_INTERVAL = 5;
    private static final int WISP_INTERVAL = 20;
    private static final int HOST_COUNT = 2;

    @Override
    public String id() {
        return "gravecaller_host";
    }

    @Override
    public String displayName() {
        return "Gravecaller Host";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            ctx.state().put("mounts", new ListTag());
            ctx.state().put("riders", new ListTag());
            return;
        }
        Mob caster = ctx.mob();
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.SKELETON_HORSE_AMBIENT, SoundSource.HOSTILE, 1.6f, 0.7f);
        level.sendParticles(ParticleTypes.SOUL, caster.getX(), caster.getY() + 1.2, caster.getZ(),
            16, 0.7, 0.6, 0.7, 0.08);

        // The side axis: the cavalry rides in from the edges of the fight.
        Vec3 look = caster.getLookAngle();
        double sideX = -look.z;
        double sideZ = look.x;
        double sideLength = Math.sqrt(sideX * sideX + sideZ * sideZ);
        if (sideLength < 0.001) {
            sideX = 1.0;
            sideZ = 0.0;
            sideLength = 1.0;
        }
        sideX /= sideLength;
        sideZ /= sideLength;

        LivingEntity target = ctx.target();
        for (int i = 0; i < HOST_COUNT; i++) {
            SkeletonHorse mount = EntityTypes.SKELETON_HORSE.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (mount == null) {
                continue;
            }
            double sign = i == 0 ? 1.0 : -1.0;
            double spawnX = caster.getX();
            double spawnZ = caster.getZ();
            for (double distance : new double[]{2.5, 0.0}) {
                double candidateX = caster.getX() + sideX * sign * distance;
                double candidateZ = caster.getZ() + sideZ * sign * distance;
                if (SkeletonHorseAbilitySupport.isFreeSpot(level, mount, candidateX, caster.getY(), candidateZ)) {
                    spawnX = candidateX;
                    spawnZ = candidateZ;
                    break;
                }
            }
            mount.snapTo(spawnX, caster.getY(), spawnZ, ctx.random().nextFloat() * 360.0f, 0.0f);
            mount.setAge(0);
            mount.setTrap(false);
            mount.setTamed(true);
            mount.finalizeSpawn(level, level.getCurrentDifficultyAt(mount.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
            if (!level.addFreshEntity(mount)) {
                continue;
            }
            teamUp(ctx, mount, target);
            SkeletonHorseAbilitySupport.trackEntity(ctx.state(), "mounts", mount);

            // The rider: the classic trap kit, bow and helmet.
            Skeleton rider = EntityTypes.SKELETON.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (rider == null) {
                continue;
            }
            rider.snapTo(spawnX, caster.getY(), spawnZ, mount.getYRot(), 0.0f);
            rider.finalizeSpawn(level, level.getCurrentDifficultyAt(rider.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
            rider.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
            rider.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            if (!level.addFreshEntity(rider)) {
                continue;
            }
            teamUp(ctx, rider, target);
            rider.startRiding(mount);
            SkeletonHorseAbilitySupport.trackEntity(ctx.state(), "riders", rider);
        }
    }

    /** Team assignment for one summoned entity, both directions plus the registry. */
    private void teamUp(AbilityContext ctx, Mob summon, LivingEntity target) {
        Mob caster = ctx.mob();
        ctx.data().addAlly(summon.getUUID());
        MobWarData.get(summon).addAlly(caster.getUUID());
        SummonerTracker.registerSummoned(summon.getUUID(), caster.getUUID());
        if (target != null) {
            summon.setTarget(target);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks % BABYSIT_INTERVAL == 0) {
            babysit(ctx, ticks);
        }
        if (ticks % WISP_INTERVAL == 0) {
            ServerLevel level = ctx.serverLevel();
            if (level != null) {
                Mob caster = ctx.mob();
                level.sendParticles(ParticleTypes.SOUL, caster.getX(), caster.getY() + 1.2, caster.getZ(),
                    3, 0.6, 0.5, 0.6, 0.05);
            }
        }
    }

    /** Retargets and drives the cavalry, every 5 ticks; also watches for the host's end. */
    private void babysit(AbilityContext ctx, int ticks) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        LivingEntity desired = ctx.target();
        Mob caster = ctx.mob();

        // The host charges with its cavalry, not behind it.
        if (desired != null && caster.distanceToSqr(desired) > 9.0) {
            caster.getNavigation().moveTo(desired, 1.25);
        }

        int livingMounts = 0;
        ListTag mounts = ctx.state().getListOrEmpty("mounts");
        for (int i = 0; i < mounts.size(); i++) {
            if (!(SkeletonHorseAbilitySupport.resolveTracked(level, mounts.getCompoundOrEmpty(i)) instanceof SkeletonHorse mount)
                || !mount.isAlive()) {
                continue;
            }
            livingMounts++;
            if (desired == null) {
                continue;
            }
            if (mount.getTarget() != desired) {
                mount.setTarget(desired);
            }
            if (mount.distanceToSqr(desired) > 9.0) {
                mount.getNavigation().moveTo(desired, 1.3);
            }
        }
        ListTag riders = ctx.state().getListOrEmpty("riders");
        for (int i = 0; i < riders.size(); i++) {
            if (SkeletonHorseAbilitySupport.resolveTracked(level, riders.getCompoundOrEmpty(i)) instanceof Skeleton rider
                && rider.isAlive() && desired != null && rider.getTarget() != desired) {
                rider.setTarget(desired);
            }
        }
        if (livingMounts == 0 && ticks > WISP_INTERVAL) {
            ctx.state().putInt("host_gone", 1);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS
            || ctx.state().getIntOr("host_gone", 0) == 1;
    }

    @Override
    public void end(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        // Riders first, so no rider is left standing where its mount vanished.
        dismiss(ctx, level, "riders");
        dismiss(ctx, level, "mounts");
    }

    /** Removes the alliance and discards every living summon on one tracked list. */
    private void dismiss(AbilityContext ctx, ServerLevel level, String key) {
        Mob caster = ctx.mob();
        ListTag list = ctx.state().getListOrEmpty(key);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID summonId = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (summonId == null) {
                continue;
            }
            // The alliance ends with the ability, whichever side is still here.
            ctx.data().removeAlly(summonId);
            if (SkeletonHorseAbilitySupport.resolveTracked(level, entry) instanceof Mob summon) {
                MobWarData.get(summon).removeAlly(caster.getUUID());
                if (summon.isAlive()) {
                    // The cavalry dissolves back into soul wisps.
                    level.sendParticles(ParticleTypes.SOUL, summon.getX(), summon.getY() + 1.0, summon.getZ(),
                        6, 0.5, 0.5, 0.5, 0.05);
                    summon.discard();
                }
            }
        }
    }
}
