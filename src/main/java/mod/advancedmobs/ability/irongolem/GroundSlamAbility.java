package mod.advancedmobs.ability.irongolem;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Ground Slam (Iron Golem ability 4 of 5, terrain).
 *
 * WHAT IT DOES: the golem raises both arms and brings them
 * down hard enough to crack the earth in front of it. The
 * shockwave bursts outward and knocks enemies off their
 * feet, and the top layer of ground in front of the golem
 * is replaced with cracked stone and rubble. The cracked
 * ground stays: rough terrain that slows anything crossing
 * it long after the slam.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the golem on the ground with its target within 10
 *   blocks (distance squared 100).
 * - Wind-up (ticks 1 to 9): the golem holds position
 *   (navigation stopped and horizontal velocity zeroed
 *   every tick; NoAi is never used). 2 BLOCK_CRUMBLE
 *   particles of the ground block shift at its feet every
 *   3 ticks as the arms come up.
 * - The slam (tick 10), centered on the golem's feet:
 *     Sound: IRON_GOLEM_ATTACK at volume 1.5, pitch 0.7.
 *     Particles: 1 EXPLOSION_EMITTER plus 12 EXPLOSION,
 *       24 BLOCK particles of the ground block underfoot
 *       spread over radius 3.0, 12 DUST_PILLAR of the same
 *       block.
 *     Shockwave: every enemy (package rule) in a radius
 *       5.0 box (1.5 below to 3.0 above the feet) takes 6.0
 *       mob-attack damage credited to the golem and is
 *       knocked 1.2 blocks per tick horizontally away from
 *       the golem and 0.55 up: off their feet.
 *     Terrain (only when mobGriefingAllowed()): every
 *       column within radius 4.5 of the golem whose center
 *       is in the front hemisphere (the horizontal dot
 *       product of the golem's look direction with the
 *       direction to the column is above 0.3) has its
 *       surface block converted to CRACKED_STONE_BRICKS or
 *       GRAVEL, alternating by (x + z) parity: the cracked
 *       stone and rubble of the design. The surface is
 *       found by the shared scan (one above to two below
 *       the golem's feet height) and must pass the shared
 *       crackable check (solid, dry, no block entity,
 *       breakable, not already cracked bricks or gravel).
 *       Hard cap 24 conversions. PERMANENT, per the
 *       standing terrain rule: nothing restores them. The
 *       converted positions are recorded in the state tag.
 *       With mobGriefing off, no block changes and
 *       everything else still runs.
 * - Linger (ticks 11 to 200): every 10 ticks, the rough
 *   ground slows what crosses it. With mobGriefing on,
 *   every enemy whose block below is one of the recorded
 *   cracked positions gains SLOWNESS I refreshed to 30
 *   ticks (one bounded query, radius 5.0, then a position
 *   set lookup per enemy). With mobGriefing off there is no
 *   cracked ground to read, so the linger instead refreshes
 *   SLOWNESS I for 30 ticks on enemies still inside the
 *   radius 4.5 frontal zone around the recorded slam center
 *   (the rubble is imaginary there, but the shockwave's
 *   stagger is not): the documented branch difference.
 * - Hard finish at tick 200. end() changes nothing: the
 *   terrain is permanent and the slow rides its own clock.
 *
 * WHAT IT LOOKS LIKE: a slow, telegraphed arm raise with
 * grit shifting at the golem's feet, then one deep slam:
 * the emitter flash, a rolling burst of real ground debris
 * and dust pillars, enemies hurled backward, and a fan of
 * cracked stone bricks and gravel spread across the ground
 * in front of the golem (mobGriefing on).
 *
 * PERFORMANCE: wind-up ticks are one navigation stop plus
 * a 2-particle pulse every 3 ticks. The slam is one bounded
 * entity query (radius 5.0) and, in mobGriefing mode, at
 * most 81 column scans of at most 4 block reads each with
 * conversions capped at 24. The linger is one bounded query
 * every 10 ticks plus a set lookup per enemy found.
 * Particle budget per activation: at most 6 wind-up, 49 at
 * the slam, total at most 55 particles.
 *
 * State tag keys: "cracked" (list of compounds, one per
 * converted block: "x", "y", "z" ints), "cx" and "cz" (the
 * slam center), "griefing" (whether the slam converted
 * terrain, choosing the linger mode).
 */
public final class GroundSlamAbility implements MobAbility {

    private static final int SLAM_TICK = 10;
    private static final int MAX_TICKS = 200;
    private static final int MAX_CONVERSIONS = 24;
    private static final double SLAM_RADIUS = 4.5;

    @Override
    public String id() {
        return "ground_slam";
    }

    @Override
    public String displayName() {
        return "Ground Slam";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().onGround()
            && ctx.mob().distanceToSqr(target) <= 100.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().put("cracked", new ListTag());
        ctx.mob().getNavigation().stop();
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob mob = ctx.mob();
        int ticks = ctx.ticksActive();
        if (ticks < SLAM_TICK) {
            // Wind-up: hold position while the arms come up.
            mob.getNavigation().stop();
            Vec3 velocity = mob.getDeltaMovement();
            if (velocity.x != 0.0 || velocity.z != 0.0) {
                mob.setDeltaMovement(0.0, velocity.y, 0.0);
            }
            ServerLevel level = ctx.serverLevel();
            if (level != null && ticks % 3 == 0) {
                BlockState ground = level.getBlockState(mob.blockPosition().below());
                if (!ground.isAir()) {
                    level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, ground),
                        mob.getX(), mob.getY() + 0.2, mob.getZ(), 2, 1.0, 0.1, 1.0, 0.03);
                }
            }
            return;
        }
        if (ticks == SLAM_TICK) {
            doSlam(ctx);
            return;
        }
        if (ticks % 10 == 0) {
            lingerSlow(ctx);
        }
    }

    /** The slam itself: boom, shockwave, and the cracked fan. Runs once, at tick 10. */
    private void doSlam(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        double cx = mob.getX(), cy = mob.getY(), cz = mob.getZ();
        BlockPos feet = mob.blockPosition();
        CompoundTag state = ctx.state();
        state.putDouble("cx", cx);
        state.putDouble("cz", cz);

        level.playSound(null, cx, cy, cz, SoundEvents.IRON_GOLEM_ATTACK, SoundSource.HOSTILE, 1.5f, 0.7f);
        BlockState ground = level.getBlockState(feet.below());
        BlockState particleState = ground.isAir() ? Blocks.DIRT.defaultBlockState() : ground;
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, cx, cy + 0.3, cz, 1, 0.0, 0.0, 0.0, 0.0);
        level.sendParticles(ParticleTypes.EXPLOSION, cx, cy + 0.3, cz, 12, 1.5, 0.3, 1.5, 0.1);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, particleState),
            cx, cy + 0.3, cz, 24, 3.0, 0.4, 3.0, 0.25);
        level.sendParticles(new BlockParticleOption(ParticleTypes.DUST_PILLAR, particleState),
            cx, cy + 0.3, cz, 12, 2.5, 0.5, 2.5, 0.08);

        boolean griefing = ctx.mobGriefingAllowed();
        state.putBoolean("griefing", griefing);
        if (griefing) {
            crackFan(ctx, level, mob, feet);
        }

        List<LivingEntity> victims = IronGolemAbilitySupport.enemiesInBox(ctx, cx, cy, cz, 5.0, 1.5, 3.0);
        for (LivingEntity victim : victims) {
            victim.hurtServer(level, level.damageSources().mobAttack(mob), 6.0f);
            Vec3 away = new Vec3(victim.getX() - cx, 0.0, victim.getZ() - cz);
            away = away.lengthSqr() < 1.0e-4 ? new Vec3(1.0, 0.0, 0.0) : away.normalize();
            victim.setDeltaMovement(away.x * 1.2, 0.55, away.z * 1.2);
            victim.hurtMarked = true;
        }
    }

    /** Cracks the surface layer of the frontal fan into cracked stone bricks and gravel. mobGriefing only. */
    private void crackFan(AbilityContext ctx, ServerLevel level, Mob mob, BlockPos feet) {
        Vec3 look = mob.getLookAngle();
        ListTag cracked = new ListTag();
        int conversions = 0;
        int radius = (int) Math.ceil(SLAM_RADIUS);
        for (int dx = -radius; dx <= radius && conversions < MAX_CONVERSIONS; dx++) {
            for (int dz = -radius; dz <= radius && conversions < MAX_CONVERSIONS; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > SLAM_RADIUS || dist < 0.5) {
                    continue;
                }
                // Front hemisphere only: the fan spreads where
                // the arms came down, not behind the golem.
                double dot = (look.x * dx + look.z * dz) / Math.max(dist, 0.001);
                if (dot <= 0.3) {
                    continue;
                }
                int x = feet.getX() + dx;
                int z = feet.getZ() + dz;
                BlockPos groundPos = IronGolemAbilitySupport.findGround(level, x, z, feet.getY());
                if (groundPos == null) {
                    continue;
                }
                BlockState crackedState = ((x + z) & 1) == 0
                    ? Blocks.CRACKED_STONE_BRICKS.defaultBlockState()
                    : Blocks.GRAVEL.defaultBlockState();
                if (level.setBlock(groundPos, crackedState, 3)) {
                    CompoundTag entry = new CompoundTag();
                    entry.putInt("x", groundPos.getX());
                    entry.putInt("y", groundPos.getY());
                    entry.putInt("z", groundPos.getZ());
                    cracked.add(entry);
                    conversions++;
                }
            }
        }
        ctx.state().put("cracked", cracked);
    }

    /** The rough ground slows what crosses it. Runs every 10 ticks after the slam. */
    private void lingerSlow(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        double cx = state.getDoubleOr("cx", ctx.mob().getX());
        double cz = state.getDoubleOr("cz", ctx.mob().getZ());
        List<LivingEntity> enemies = IronGolemAbilitySupport.enemiesInBox(
            ctx, cx, ctx.mob().getY(), cz, 5.0, 2.0, 2.0);
        boolean griefing = state.getBooleanOr("griefing", false);
        ListTag cracked = state.getListOrEmpty("cracked");
        for (LivingEntity enemy : enemies) {
            boolean onRoughGround;
            if (griefing) {
                BlockPos below = enemy.blockPosition().below();
                onRoughGround = false;
                for (int i = 0; i < cracked.size(); i++) {
                    CompoundTag entry = cracked.getCompoundOrEmpty(i);
                    if (entry.getIntOr("x", 0) == below.getX()
                        && entry.getIntOr("y", 0) == below.getY()
                        && entry.getIntOr("z", 0) == below.getZ()) {
                        onRoughGround = true;
                        break;
                    }
                }
            } else {
                double dx = enemy.getX() - cx;
                double dz = enemy.getZ() - cz;
                onRoughGround = dx * dx + dz * dz <= SLAM_RADIUS * SLAM_RADIUS;
            }
            if (onRoughGround) {
                enemy.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 30, 0));
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= MAX_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to undo: the cracked terrain is permanent
        // (standing terrain rule) and the slow rides its own
        // clock.
    }
}
