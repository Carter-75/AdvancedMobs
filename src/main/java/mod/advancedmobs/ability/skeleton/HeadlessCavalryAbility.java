package mod.advancedmobs.ability.skeleton;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.equine.SkeletonHorse;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.UUID;

/**
 * Headless Cavalry (Skeleton ability 2 of 5, mobility).
 *
 * WHAT IT DOES: with a crack of bone the skeleton summons a skeleton
 * horse and swings up into the saddle in one motion, ENCHANT motes
 * swirling around the pair. Mounted, it becomes cavalry: fast,
 * tall-stepping, circling its enemy at bow range and riding straight
 * over fences and low walls that would stop it on foot.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists, between 5 and 24 blocks
 *   away (distance squared 25 to 576), the skeleton is on the ground
 *   and not already riding something.
 * - Duration: exactly 300 ticks (15 seconds) from start(), or until
 *   the horse dies (see the babysitting below).
 * - The mount (start()): 1 skeleton horse, spawned at the first
 *   collision-free spot of the skeleton's own position and a
 *   1.6-block side offset, created with EntitySpawnReason
 *   .MOB_SUMMONED and finalizeSpawn like every other summon in this
 *   mod. It is explicitly NOT a trap horse (setTrap(false)) and is
 *   tamed (setTamed(true)), so it accepts the rider and steering.
 *   Team assignment matches Call of the Deep and Scarab Brood: the
 *   caster's data gains the horse as an ally, the horse's data
 *   gains the caster, and the pair is registered in
 *   SummonerTracker (which also tags the horse umw_summoned for the
 *   natural spawn blocker). The skeleton then mounts with
 *   startRiding; if mounting somehow fails, the horse is discarded
 *   at once and the ability finishes immediately.
 * - Cavalry stats on the horse, for the duration: SPEED II
 *   (amplifier 1, 320 ticks, one application at start) and a
 *   STEP_HEIGHT add_value modifier of +1.5 (modifier id
 *   advancedmobs:headless_cavalry_step), which on top of the horse's
 *   native 1-block step lets it walk up and over fences (1.5
 *   collision height) and low walls without jumping. Both are
 *   removed in end().
 * - Steering, every 5 ticks (the babysit): the horse is the
 *   vehicle, so the pair moves by the HORSE's navigation, not the
 *   rider's. The babysit keeps the horse's vanilla target set to
 *   the skeleton's live target and steers the navigation to hold a
 *   cavalry band 7 to 11 blocks from that target: farther than 11,
 *   ride straight at the target; closer than 7, ride away to a
 *   point 10 blocks out on the far side; inside the band, strafe to
 *   a point 9 blocks out at a right angle, the side flipping every
 *   60 ticks, which reads as circling. Navigation speed modifier
 *   1.35. The skeleton's own bow goal keeps shooting from the
 *   saddle (vanilla skeleton horse traps prove mounted skeletons
 *   shoot), and its goals cannot walk it off: it is a passenger.
 * - Babysitting also covers the pair breaking up: if the skeleton
 *   is somehow dismounted while the horse lives, it remounts on the
 *   next babysit tick; if the horse is dead or gone, the ability
 *   finishes at once ("mount_gone" in the state tag).
 * - end(): the skeleton dismounts (if it is still on this horse),
 *   the horse's step modifier and SPEED are removed, the alliance
 *   records are removed on both sides, and the horse is discarded
 *   in a burst of POOF and bone BLOCK particles: the mount is the
 *   ability's and does not outlive it. If the horse died in the
 *   fight, end() simply finds nothing to discard. If the caster
 *   dies first, the framework termination path runs this same
 *   end(), so the horse never outlives its rider either.
 *   SummonerTracker records are deliberately not unregistered
 *   here, matching the other summons: that registry cleans up on
 *   the horse's own removal.
 *
 * WHAT IT LOOKS LIKE: at the summon, 16 BLOCK particles of bone
 * block crack outward at the skeleton's feet and 10 ENCHANT motes
 * swirl around the pair; while mounted, 2 ENCHANT motes drift off
 * the pair every 20 ticks; at the end, 8 POOF and 6 bone BLOCK
 * particles as the horse comes apart.
 *
 * PERFORMANCE: start() does 2 bounded collision checks and 1
 * spawn, once. tick() returns immediately except every 5th tick
 * (babysit: 1 UUID resolve, target reads, at most 1 navigation
 * order) and every 20th tick (2 particles). No entity scans.
 * Particle budget per activation: 26 at start plus at most 15
 * mounted pulses times 2 = 30, plus 14 at end(), total at most 70.
 *
 * State tag keys: "horse" (the mount's UUID), "mount_gone"
 * (whether the mount died or vanished mid-activation).
 */
public final class HeadlessCavalryAbility implements MobAbility {

    static final int DURATION_TICKS = 300;
    private static final int BABYSIT_INTERVAL = 5;
    private static final double BAND_MIN = 7.0;
    private static final double BAND_MAX = 11.0;
    private static final double NAV_SPEED = 1.35;
    private static final Identifier STEP_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "headless_cavalry_step");
    private static final BlockParticleOption BONE_BLOCK_PARTICLES =
        new BlockParticleOption(ParticleTypes.BLOCK, Blocks.BONE_BLOCK.defaultBlockState());

    @Override
    public String id() {
        return "headless_cavalry";
    }

    @Override
    public String displayName() {
        return "Headless Cavalry";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        Mob mob = ctx.mob();
        LivingEntity target = ctx.target();
        if (target == null || mob.isPassenger() || !mob.onGround()) {
            return false;
        }
        double distanceSqr = mob.distanceToSqr(target);
        return distanceSqr >= 25.0 && distanceSqr <= 576.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        CompoundTag state = ctx.state();
        state.putBoolean("mount_gone", false);
        if (level == null) {
            state.putBoolean("mount_gone", true);
            return;
        }
        Mob caster = ctx.mob();

        SkeletonHorse horse = EntityTypes.SKELETON_HORSE.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (horse == null) {
            state.putBoolean("mount_gone", true);
            return;
        }
        double spawnX = caster.getX();
        double spawnZ = caster.getZ();
        if (!isFreeSpot(level, horse, spawnX, caster.getY(), spawnZ)) {
            spawnX += 1.6;
            if (!isFreeSpot(level, horse, spawnX, caster.getY(), spawnZ)) {
                state.putBoolean("mount_gone", true);
                return;
            }
        }
        horse.snapTo(spawnX, caster.getY(), spawnZ, caster.getYRot(), 0.0f);
        horse.setTrap(false);
        horse.setTamed(true);
        horse.finalizeSpawn(level, level.getCurrentDifficultyAt(horse.blockPosition()),
            EntitySpawnReason.MOB_SUMMONED, null);
        if (!level.addFreshEntity(horse)) {
            state.putBoolean("mount_gone", true);
            return;
        }
        state.store("horse", UUIDUtil.CODEC, horse.getUUID());

        // Team assignment, both directions, plus the summoner registry.
        ctx.data().addAlly(horse.getUUID());
        MobWarData.get(horse).addAlly(caster.getUUID());
        SummonerTracker.registerSummoned(horse.getUUID(), caster.getUUID());

        // Cavalry stats for the duration.
        horse.addEffect(new MobEffectInstance(MobEffects.SPEED, DURATION_TICKS + 20, 1));
        AttributeInstance step = horse.getAttribute(Attributes.STEP_HEIGHT);
        if (step != null) {
            step.addOrUpdateTransientModifier(
                new AttributeModifier(STEP_ID, 1.5, AttributeModifier.Operation.ADD_VALUE));
        }

        // Up into the saddle in one motion. If the mount fails, the
        // ability is nothing: clean the horse up right away.
        if (!caster.startRiding(horse)) {
            cleanupMount(ctx, horse, false);
            state.putBoolean("mount_gone", true);
            return;
        }
        LivingEntity target = ctx.target();
        if (target != null) {
            horse.setTarget(target);
        }

        // The crack of bone, and the swirl of the mounting.
        level.sendParticles(BONE_BLOCK_PARTICLES, caster.getX(), caster.getY() + 0.4, caster.getZ(),
            16, 0.7, 0.4, 0.7, 0.25);
        level.sendParticles(ParticleTypes.ENCHANT, caster.getX(), caster.getY() + 1.2, caster.getZ(),
            10, 0.8, 0.8, 0.8, 0.4);
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks % BABYSIT_INTERVAL != 0) {
            return; // Cheap early-out: babysitting runs every 5 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        SkeletonHorse horse = resolveHorse(ctx);
        if (horse == null || !horse.isAlive()) {
            state.putBoolean("mount_gone", true);
            return;
        }
        Mob caster = ctx.mob();

        // The pair must stay a pair.
        if (caster.getVehicle() != horse && caster.isAlive()) {
            caster.startRiding(horse);
        }

        // Steering: hold the cavalry band around the live target.
        LivingEntity target = ctx.target();
        if (target != null) {
            horse.setTarget(target);
            double dx = horse.getX() - target.getX();
            double dz = horse.getZ() - target.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            double destX, destZ;
            if (dist > BAND_MAX || dist < 1.0e-4) {
                // Too far (or stacked on the target): ride at it.
                destX = target.getX();
                destZ = target.getZ();
            } else if (dist < BAND_MIN) {
                // Too close: ride out to 10 blocks on the far side.
                destX = target.getX() + dx / dist * 10.0;
                destZ = target.getZ() + dz / dist * 10.0;
            } else {
                // In the band: strafe sideways, side flips every 60
                // ticks, which reads as circling the enemy.
                double side = ((ticks / 60) % 2 == 0) ? 1.0 : -1.0;
                destX = target.getX() + (-dz / dist) * 9.0 * side;
                destZ = target.getZ() + (dx / dist) * 9.0 * side;
            }
            horse.getNavigation().moveTo(destX, target.getY(), destZ, NAV_SPEED);
        }

        if (ticks % 20 == 0) {
            level.sendParticles(ParticleTypes.ENCHANT, horse.getX(), horse.getY() + 1.4, horse.getZ(),
                2, 0.7, 0.6, 0.7, 0.3);
        }
    }

    /** Whether the horse's bounding box at the given feet position collides with nothing. */
    private boolean isFreeSpot(ServerLevel level, SkeletonHorse horse, double x, double y, double z) {
        double halfWidth = horse.getBbWidth() / 2.0;
        AABB box = new AABB(x - halfWidth, y, z - halfWidth, x + halfWidth, y + horse.getBbHeight(), z + halfWidth);
        return level.noCollision(horse, box);
    }

    /** Resolves the tracked mount from the state tag, or null when gone. */
    private SkeletonHorse resolveHorse(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null || !ctx.state().contains("horse")) {
            return null;
        }
        UUID id = ctx.state().read("horse", UUIDUtil.CODEC).orElse(null);
        if (id != null && level.getEntity(id) instanceof SkeletonHorse horse) {
            return horse;
        }
        return null;
    }

    /**
     * Ends the mount's part in the ability: dismounts the caster when
     * asked and it is still on this horse, strips the cavalry stats,
     * removes the alliance records on both sides, and discards the
     * horse when it is still alive. Idempotent: safe on a dead or
     * missing horse.
     */
    private void cleanupMount(AbilityContext ctx, SkeletonHorse horse, boolean dismount) {
        Mob caster = ctx.mob();
        if (dismount && caster.getVehicle() == horse) {
            caster.stopRiding();
        }
        ctx.data().removeAlly(horse.getUUID());
        MobWarData.get(horse).removeAlly(caster.getUUID());
        AttributeInstance step = horse.getAttribute(Attributes.STEP_HEIGHT);
        if (step != null) {
            step.removeModifier(STEP_ID);
        }
        horse.removeEffect(MobEffects.SPEED);
        if (horse.isAlive()) {
            ServerLevel level = ctx.serverLevel();
            if (level != null) {
                level.sendParticles(ParticleTypes.POOF, horse.getX(), horse.getY() + 0.8, horse.getZ(),
                    8, 0.5, 0.5, 0.5, 0.1);
                level.sendParticles(BONE_BLOCK_PARTICLES, horse.getX(), horse.getY() + 0.5, horse.getZ(),
                    6, 0.6, 0.4, 0.6, 0.2);
            }
            horse.discard();
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS || ctx.state().getBooleanOr("mount_gone", false);
    }

    @Override
    public void end(AbilityContext ctx) {
        SkeletonHorse horse = resolveHorse(ctx);
        if (horse == null) {
            // The mount is already gone; still drop the caster's side
            // of the alliance record if the UUID is known.
            if (ctx.state().contains("horse")) {
                UUID id = ctx.state().read("horse", UUIDUtil.CODEC).orElse(null);
                if (id != null) {
                    ctx.data().removeAlly(id);
                }
            }
            return;
        }
        cleanupMount(ctx, horse, true);
    }
}
