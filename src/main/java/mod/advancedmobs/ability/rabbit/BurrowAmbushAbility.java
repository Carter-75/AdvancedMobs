package mod.advancedmobs.ability.rabbit;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Burrow Ambush (Rabbit ability 2 of 5, mobility / attack).
 *
 * WHAT IT DOES: the rabbit scrabbles straight down in a
 * spray of dirt and is gone. A low ridge of kicked earth
 * crawls unseen across the ground toward its target, and
 * a moment later the rabbit bursts out of the ground
 * directly behind the enemy and lands one guaranteed
 * heavy crit before the enemy can turn around.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the rabbit's current target within 16 blocks
 *   (distance squared 256) and the rabbit on the ground.
 *   The dive point (the rabbit's feet) and the target's
 *   position are recorded in start().
 * - The dive (ticks 1 to 8): the navigation is stopped
 *   each tick (never NoAi). Every 2 ticks, 5 BLOCK
 *   particles of the ground block under the rabbit spray
 *   at its feet. RABBIT_JUMP plays at start() (volume
 *   1.2, pitch 0.8), the scrabbling scramble. At tick 8
 *   the rabbit gains INVISIBILITY for 40 ticks with
 *   particles and icon hidden.
 * - The travel (ticks 9 to 30): the body stays rooted at
 *   the dive point by navigation stops while the ridge
 *   shows the path. This is the Stoneborer Ambush
 *   pattern (the Warden's Burrow Ambush uses the same):
 *   teleporting the body under the floor mid-travel
 *   would suffocate it in solid ground. The ridge: every
 *   2 ticks, at the point lerped from the dive point to
 *   the recorded target position by travel progress, 2
 *   BLOCK particles of the dive point's ground block and
 *   1 brown DUST mote. The recorded target position
 *   refreshes every 5 ticks while the target lives, so
 *   the ridge tracks a moving target.
 * - The eruption (tick 30): the rabbit teleports BEHIND
 *   its target: the spot 1.3 blocks behind the target's
 *   facing (the target's position minus its horizontal
 *   look direction times 1.3, at the target's feet
 *   height). Fallbacks, in order: the target's own feet,
 *   the first fitting spot at radius 1.5 at angles 0,
 *   90, 180, 270 degrees, and finally the dive point.
 *   The rabbit is turned to face the target.
 *   INVISIBILITY is removed. The burst: 8 BLOCK
 *   particles of the landing ground and the
 *   RABBIT_AMBIENT cry (volume 1.3, pitch 1.3).
 * - The crit (the eruption, same tick): if the target
 *   is alive and within 3.5 blocks of the landing point,
 *   it takes 8.0 points of mob-attack damage credited
 *   to the rabbit: the guaranteed heavy crit of the
 *   design, a flat number well above the rabbit's 3.0
 *   base bite because it always lands from behind on an
 *   enemy that has not turned yet. 12 CRIT particles
 *   burst at the victim and RABBIT_ATTACK plays (volume
 *   1.2, pitch 1.2). A target that died or escaped the
 *   radius during the travel simply gets no crit; the
 *   rabbit still erupts at the recorded position.
 * - Duration: 90 ticks total; the last 60 ticks are the
 *   rabbit fighting on normally after the ambush. If
 *   the rabbit is terminated mid-travel, end() removes
 *   the invisibility and no eruption ever happens.
 *
 * TERRAIN: this ability changes no blocks. The dive and
 * the eruption are particle theater over a teleport, so
 * the mobGriefing gate does not apply anywhere, and this
 * comment is the statement of that.
 *
 * WHAT IT LOOKS LIKE: a rabbit vanishing into a spray of
 * its own floor, a low brown ridge crawling across the
 * ground, then dirt bursting open behind the target and
 * a rabbit already biting, in a flash of crit stars.
 *
 * PERFORMANCE: the dive and travel are one navigation
 * stop per tick plus particles every 2 ticks and one
 * target position read every 5 ticks; the eruption is at
 * most 6 collision checks and one distance check. No
 * entity scans. Particle budget per activation: dive 4
 * pulses of 5 = 20, ridge 11 pulses of 3 = 33, eruption
 * 8 plus crit 12, total at most 73 particles. The dirt
 * BLOCK option is rebuilt per pulse from the live ground
 * state (a handful of times per activation, never per
 * tick beyond the pulse interval).
 *
 * CLEANUP: end() removes INVISIBILITY from the rabbit, a
 * safety net for termination mid-travel; on a natural
 * finish the effect was already removed at the eruption.
 *
 * State tag keys: "dx", "dy", "dz" (the dive point),
 * "tx", "ty", "tz" (the tracked target position),
 * "erupted" (whether the eruption has fired).
 */
public final class BurrowAmbushAbility implements MobAbility {

    private static final int DURATION_TICKS = 90;
    private static final int DIVE_TICKS = 8;
    private static final int ERUPT_TICK = 30;
    private static final float CRIT_DAMAGE = 8.0f;
    private static final double CRIT_RADIUS_SQR = 12.25;
    private static final double BEHIND_DISTANCE = 1.3;
    private static final DustParticleOptions RIDGE_DUST = new DustParticleOptions(0x8A6B48, 1.0f);

    @Override
    public String id() {
        return "burrow_ambush";
    }

    @Override
    public String displayName() {
        return "Burrow Ambush";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().onGround()
            && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        state.putDouble("dx", mob.getX());
        state.putDouble("dy", mob.getY());
        state.putDouble("dz", mob.getZ());
        LivingEntity target = ctx.target();
        if (target != null) {
            state.putDouble("tx", target.getX());
            state.putDouble("ty", target.getY());
            state.putDouble("tz", target.getZ());
        } else {
            state.putDouble("tx", mob.getX());
            state.putDouble("ty", mob.getY());
            state.putDouble("tz", mob.getZ());
        }
        state.putBoolean("erupted", false);

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.RABBIT_JUMP,
                SoundSource.HOSTILE, 1.2f, 0.8f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        Mob mob = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();

        if (ticks <= DIVE_TICKS) {
            mob.getNavigation().stop();
            if (ticks % 2 == 0) {
                sprayGround(level, mob.getX(), mob.getY(), mob.getZ(), 5);
            }
            if (ticks == DIVE_TICKS) {
                // Under the floor: hidden for the whole travel.
                mob.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 40, 0, false, false, false));
            }
            return;
        }

        if (ticks < ERUPT_TICK) {
            mob.getNavigation().stop();
            // The ridge tracks a living target.
            if (ticks % 5 == 0) {
                LivingEntity target = ctx.target();
                if (target != null) {
                    state.putDouble("tx", target.getX());
                    state.putDouble("ty", target.getY());
                    state.putDouble("tz", target.getZ());
                }
            }
            if (ticks % 2 == 0) {
                double progress = (ticks - DIVE_TICKS) / (double) (ERUPT_TICK - DIVE_TICKS);
                double ridgeX = state.getDoubleOr("dx", mob.getX())
                    + (state.getDoubleOr("tx", mob.getX()) - state.getDoubleOr("dx", mob.getX())) * progress;
                double ridgeZ = state.getDoubleOr("dz", mob.getZ())
                    + (state.getDoubleOr("tz", mob.getZ()) - state.getDoubleOr("dz", mob.getZ())) * progress;
                double ridgeY = state.getDoubleOr("dy", mob.getY());
                sprayGround(level, ridgeX, ridgeY, ridgeZ, 2);
                level.sendParticles(RIDGE_DUST, ridgeX, ridgeY + 0.25, ridgeZ,
                    1, 0.3, 0.1, 0.3, 0.03);
            }
            return;
        }

        if (ticks == ERUPT_TICK && !state.getBooleanOr("erupted", false)) {
            erupt(ctx);
        }
    }

    /** Sprays BLOCK particles of the ground block under the given feet position. */
    private static void sprayGround(ServerLevel level, double x, double y, double z, int count) {
        BlockPos below = BlockPos.containing(x, y - 0.5, z);
        BlockState ground = level.getBlockState(below);
        if (ground.isAir()) {
            return;
        }
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground),
            x, y + 0.2, z, count, 0.4, 0.25, 0.4, 0.1);
    }

    /** The eruption: teleport behind the target, burst out, land the crit. */
    private void erupt(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        state.putBoolean("erupted", true);

        double tx = state.getDoubleOr("tx", mob.getX());
        double ty = state.getDoubleOr("ty", mob.getY());
        double tz = state.getDoubleOr("tz", mob.getZ());

        // The spot behind the target: its position minus its
        // horizontal look direction, so the rabbit comes up
        // facing the target's back.
        LivingEntity target = ctx.target();
        double behindX = tx;
        double behindZ = tz;
        if (target != null) {
            Vec3 look = target.getLookAngle();
            double lookLength = Math.sqrt(look.x * look.x + look.z * look.z);
            if (lookLength > 1.0e-4) {
                behindX = tx - look.x / lookLength * BEHIND_DISTANCE;
                behindZ = tz - look.z / lookLength * BEHIND_DISTANCE;
            }
        }

        // Landing spot: behind the target, then its feet,
        // then the ring around it, then the dive point.
        double landX = state.getDoubleOr("dx", mob.getX());
        double landY = state.getDoubleOr("dy", mob.getY());
        double landZ = state.getDoubleOr("dz", mob.getZ());
        if (RabbitAbilitySupport.isFreeSpot(level, mob, behindX, ty, behindZ)) {
            landX = behindX;
            landY = ty;
            landZ = behindZ;
        } else if (RabbitAbilitySupport.isFreeSpot(level, mob, tx, ty, tz)) {
            landX = tx;
            landY = ty;
            landZ = tz;
        } else {
            for (int i = 0; i < 4; i++) {
                double angle = Math.toRadians(i * 90.0);
                double candidateX = tx + Math.cos(angle) * 1.5;
                double candidateZ = tz + Math.sin(angle) * 1.5;
                if (RabbitAbilitySupport.isFreeSpot(level, mob, candidateX, ty, candidateZ)) {
                    landX = candidateX;
                    landY = ty;
                    landZ = candidateZ;
                    break;
                }
            }
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-(tx - landX), tz - landZ));
        mob.snapTo(landX, landY, landZ, yaw, 0.0f);
        mob.removeEffect(MobEffects.INVISIBILITY);

        level.playSound(null, landX, landY, landZ, SoundEvents.RABBIT_AMBIENT,
            SoundSource.HOSTILE, 1.3f, 1.3f);
        sprayGround(level, landX, landY, landZ, 8);

        // The guaranteed heavy crit, landed before the
        // enemy can turn.
        if (target != null && target.isAlive()
            && target.distanceToSqr(landX, landY, landZ) <= CRIT_RADIUS_SQR) {
            target.hurtServer(level, level.damageSources().mobAttack(mob), CRIT_DAMAGE);
            level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY() + 0.5, target.getZ(),
                12, 0.3, 0.3, 0.3, 0.08);
            level.playSound(null, landX, landY, landZ, SoundEvents.RABBIT_ATTACK,
                SoundSource.HOSTILE, 1.2f, 1.2f);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Safety net for termination mid-travel.
        ctx.mob().removeEffect(MobEffects.INVISIBILITY);
    }
}
