package mod.advancedmobs.ability.hoglin;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Territorial Slam (Hoglin ability 4 of 5, attack).
 *
 * WHAT IT DOES: the hoglin rears up, plants itself, and after
 * a slow, impossible-to-miss wind-up crashes down with its
 * full weight. The impact sends a shockwave ring of dust and
 * flying ground debris across the ground in every direction:
 * enemies at point blank take the full crushing damage, those
 * at the edge only stumble, and everything in the ring is
 * knocked off its feet.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   hoglin's current target within 6 blocks (distance squared
 *   36) and the hoglin on the ground. This is a point blank
 *   slam; it never fires at range.
 * - Duration: exactly 30 ticks. Ticks 1 to 19 are the wind-up,
 *   the slam lands on tick 20, ticks 21 to 30 are the hoglin
 *   recovering in the dust.
 * - The wind-up: the hoglin is rooted for the whole activation:
 *   every tick the navigation is stopped and its horizontal
 *   velocity is zeroed (vertical velocity is left alone). It
 *   carries an EXPLOSION_KNOCKBACK_RESISTANCE add_value
 *   modifier of +1.0 from start() to end() (id
 *   advancedmobs:territorial_slam_brace), the design's brace
 *   against being blasted out of its own slam: explosions
 *   cannot move it while it rears and crashes. Ordinary melee
 *   knockback still applies; only the explosion component is
 *   fully resisted. A HOGLIN_ANGRY roar opens the wind-up and
 *   2 FALLING_DUST particles of the ground block sift at its
 *   feet every 5 ticks as the earth trembles.
 * - The slam (tick 20, one-shot): one bounded query, enemies
 *   (HoglinAbilitySupport rules) within radius 5.0 of the
 *   hoglin (2.0 below to 3.0 above its feet). Damage and
 *   knockback fall off by the victim's horizontal distance:
 *     Point blank (at most 2.0 blocks): 10.0 points of typed
 *       mob-attack damage credited to the hoglin, knockback
 *       1.6 horizontal and 0.45 up.
 *     Edge (over 2.0 and up to 5.0 blocks): 4.0 damage,
 *       knockback 1.1 horizontal and 0.45 up: a stumble, not
 *       a crushing.
 *   Knockback is applied as a delta movement set away from
 *   the hoglin with hurtMarked set so it syncs; a victim
 *   standing dead center is pushed along the hoglin's look
 *   direction instead.
 * - The crack in the earth is particle theater, NOT terrain:
 *   24 FALLING_DUST and 16 BLOCK particles of the ground block
 *   under the hoglin (dirt when there is no solid ground to
 *   read) burst across a 2.5 block radius, plus 8 DUST_PILLAR
 *   of the same block at the impact point. No block is placed,
 *   broken, or converted by this ability in either mobGriefing
 *   mode, so the terrain gate has nothing to block here; the
 *   design's own Uses list is particles and radial code only.
 *   A GENERIC_EXPLODE at volume 0.5 and pitch 1.1 and a
 *   HOGLIN_ATTACK bellow land with the impact.
 *
 * PERFORMANCE: the wind-up ticks do one navigation stop, one
 * velocity write, and a 2-particle send every 5th tick. The
 * slam is one bounded entity query (10 by 5 by 10 box) plus
 * one block read, once. No state is read or written at all:
 * the whole activation is a function of ticksActive() and the
 * start() modifier. Particle budget per activation: wind-up
 * at most 4 sends times 2 = 8, slam 48, total at most 56
 * particles.
 *
 * CLEANUP: end() removes the explosion knockback modifier.
 * Nothing else was created or changed.
 *
 * State tag keys: none.
 */
public final class TerritorialSlamAbility implements MobAbility {

    static final int DURATION_TICKS = 30;
    private static final int SLAM_TICK = 20;
    private static final double SLAM_RADIUS = 5.0;
    private static final double POINT_BLANK_RADIUS = 2.0;
    private static final float POINT_BLANK_DAMAGE = 10.0f;
    private static final float EDGE_DAMAGE = 4.0f;
    private static final double POINT_BLANK_KNOCKBACK = 1.6;
    private static final double EDGE_KNOCKBACK = 1.1;
    private static final double KNOCKBACK_UP = 0.45;
    private static final Identifier BRACE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "territorial_slam_brace");

    @Override
    public String id() {
        return "territorial_slam";
    }

    @Override
    public String displayName() {
        return "Territorial Slam";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().onGround()
            && ctx.mob().distanceToSqr(target) <= 36.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        // Braced against explosions for the whole slam.
        HoglinAbilitySupport.addModifier(mob, Attributes.EXPLOSION_KNOCKBACK_RESISTANCE,
            new AttributeModifier(BRACE_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.HOGLIN_ANGRY,
                SoundSource.HOSTILE, 1.0f, 0.8f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob mob = ctx.mob();
        // Rooted: the hoglin rears and crashes in place. Vertical
        // velocity is left alone; only the horizontal is held.
        mob.getNavigation().stop();
        net.minecraft.world.phys.Vec3 current = mob.getDeltaMovement();
        mob.setDeltaMovement(0.0, current.y, 0.0);

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        if (ticks == SLAM_TICK) {
            runSlam(ctx, level);
        } else if (ticks < SLAM_TICK && ticks % 5 == 0) {
            // The earth trembles under the rearing hoglin.
            BlockState ground = groundState(level, mob);
            level.sendParticles(new BlockParticleOption(ParticleTypes.FALLING_DUST, ground),
                mob.getX(), mob.getY() + 0.2, mob.getZ(), 2, 0.8, 0.1, 0.8, 0.03);
        }
    }

    /** The impact: radial damage and knockback, and the crack of dust and debris. */
    private void runSlam(AbilityContext ctx, ServerLevel level) {
        Mob mob = ctx.mob();
        List<LivingEntity> victims = HoglinAbilitySupport.enemiesAroundSelf(ctx, SLAM_RADIUS, 2.0, 3.0);
        for (LivingEntity victim : victims) {
            double ox = victim.getX() - mob.getX();
            double oz = victim.getZ() - mob.getZ();
            double horizontal = Math.sqrt(ox * ox + oz * oz);
            boolean pointBlank = horizontal <= POINT_BLANK_RADIUS;
            victim.hurtServer(level, level.damageSources().mobAttack(mob),
                pointBlank ? POINT_BLANK_DAMAGE : EDGE_DAMAGE);
            if (horizontal < 1.0e-4) {
                // Dead center: push along the hoglin's facing instead.
                ox = mob.getLookAngle().x;
                oz = mob.getLookAngle().z;
                horizontal = Math.sqrt(ox * ox + oz * oz);
                if (horizontal < 1.0e-4) {
                    ox = 1.0;
                    horizontal = 1.0;
                }
            }
            double strength = pointBlank ? POINT_BLANK_KNOCKBACK : EDGE_KNOCKBACK;
            victim.setDeltaMovement(ox / horizontal * strength, KNOCKBACK_UP, oz / horizontal * strength);
            victim.hurtMarked = true;
        }

        // The crack: a ring of dust and flying ground debris.
        BlockState ground = groundState(level, mob);
        double x = mob.getX(), y = mob.getY() + 0.2, z = mob.getZ();
        level.sendParticles(new BlockParticleOption(ParticleTypes.FALLING_DUST, ground),
            x, y, z, 24, 2.5, 0.3, 2.5, 0.12);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground),
            x, y, z, 16, 2.2, 0.5, 2.2, 0.25);
        level.sendParticles(new BlockParticleOption(ParticleTypes.DUST_PILLAR, ground),
            x, y, z, 8, 1.2, 0.4, 1.2, 0.10);
        level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 0.5f, 1.1f);
        level.playSound(null, x, y, z, SoundEvents.HOGLIN_ATTACK, SoundSource.HOSTILE, 1.0f, 0.9f);
    }

    /** The block state of the ground under the mob, or dirt when there is no solid ground to read. */
    private static BlockState groundState(ServerLevel level, Mob mob) {
        BlockPos below = mob.blockPosition().below();
        BlockState state = level.getBlockState(below);
        return state.isAir() ? Blocks.DIRT.defaultBlockState() : state;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        HoglinAbilitySupport.removeModifier(ctx.mob(), Attributes.EXPLOSION_KNOCKBACK_RESISTANCE, BRACE_ID);
    }
}
