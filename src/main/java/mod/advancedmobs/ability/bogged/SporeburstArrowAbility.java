package mod.advancedmobs.ability.bogged;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Sporeburst Arrow (Bogged ability 1 of 5, attack).
 *
 * WHAT IT DOES: the bogged looses a single dripping arrow with a
 * swollen spore pod for a head. Wherever the arrow lands (or
 * whatever it hits), the pod bursts into a choking cloud of
 * CRIMSON_SPORE and WARPED_SPORE that rolls low over the ground
 * for several seconds. Enemies standing in the cloud breathe the
 * spores in: POISON and NAUSEA, refreshed for as long as they
 * stay inside.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists, between 4 and 24 blocks
 *   away (distance squared 16 to 576). The bogged is a ranged
 *   fighter; point blank targets are for its ordinary bow.
 * - The shot (start()): one vanilla Arrow owned by the bogged,
 *   pickup disallowed, base damage 3.0 (an ordinary bogged arrow
 *   is 2.0; vanilla scales arrow damage by impact velocity, so a
 *   clean hit at the shot's speed of 2.4 lands around 7 to 9
 *   before armor, plus whatever the cloud then does). It leaves
 *   from the bogged's eye position toward the live target's
 *   mid-body (60 percent of its height) with zero inaccuracy.
 *   The arrow itself is a normal projectile: if it hits an enemy
 *   directly, that enemy takes the arrow hit as usual, and the
 *   pod still bursts at the hit point.
 * - Impact detection: the arrow's UUID and last known position
 *   are stored in the state tag and refreshed every tick. The
 *   pod bursts when (a) the arrow entity no longer resolves (it
 *   hit an entity, burned, or fell out of the world), at the
 *   last known position, or (b) the arrow has moved less than
 *   0.01 blocks on 2 consecutive ticks after at least 3 ticks of
 *   flight, which means it is stuck in a block (an arrow at the
 *   top of a steep arc stalls for a single tick at most, so the
 *   2-tick rule never fires mid-flight); the stuck arrow is then
 *   discarded so it does not sit in the ground as a plain arrow.
 * - The cloud: centered on the impact point, radius 2.5, lasting
 *   exactly 100 ticks (5 seconds) from the impact tick. Every 10
 *   ticks it pulses: enemies (BoggedAbilitySupport rules) inside
 *   radius 2.5, between 1 below and 2.5 above the center, get
 *   POISON I (amplifier 0) for 80 ticks and NAUSEA I (amplifier
 *   0) for 80 ticks, so the effects outlast one pulse and stack
 *   up exposure for anyone who lingers, exactly the lingering
 *   hazard patch the design calls for.
 * - The ability finishes 100 ticks after the impact, with a hard
 *   finish at tick 260 in case the arrow somehow never resolves
 *   (a shot straight up comes back down well inside that).
 *
 * WHAT IT LOOKS AND SOUNDS LIKE: while the arrow flies, 1
 * CRIMSON_SPORE and 1 WARPED_SPORE drip off it every 2 ticks, a
 * green and teal contrail. The loose is an ordinary ARROW_SHOOT
 * at pitch 0.8. The burst is 10 CRIMSON_SPORE and 10 WARPED_SPORE
 * in a low ball with a wet MUD_BREAK splat (volume 0.9, pitch
 * 0.8). While the cloud lives, every 5 ticks 3 CRIMSON_SPORE and
 * 3 WARPED_SPORE roll across its radius at ground level: red
 * spores from the crimson fungi, teal from the warped, the two
 * families the design names.
 *
 * PERFORMANCE: the flight phase is one UUID resolve and one
 * position read per tick, plus 2 trail particles every 2 ticks.
 * The cloud phase returns immediately except every 5th tick
 * (particles) and every 10th tick (one bounded enemy query over
 * a 5 by 3.5 by 5 box). No registry or config lookups in the
 * tick path. Particle budget per activation: trail at most 130
 * flight ticks times 2 = 260 in the absolute worst case (a
 * typical 15 block shot flies about 10 ticks, so about 20),
 * burst 20, cloud 20 pulses times 6 = 120, total at most 400
 * worst case and about 160 for a typical shot.
 *
 * CLEANUP: end() has nothing to undo. The cloud is pure state
 * tag and particles, no world objects; a still-flying arrow is a
 * normal projectile that simply never bursts if the activation
 * ended first (hard finish or caster death), and a stuck arrow
 * was already discarded at its impact.
 *
 * State tag keys: "arrow" (the arrow's UUID), "lx", "ly", "lz"
 * (last known arrow position, doubles), "stall" (consecutive
 * near-zero-movement ticks), "cloud" (1 once the pod has burst),
 * "cx", "cy", "cz" (cloud center, doubles), "impact_tick" (the
 * activation tick the cloud formed on).
 */
public final class SporeburstArrowAbility implements MobAbility {

    private static final int HARD_FINISH_TICKS = 260;
    private static final int CLOUD_DURATION_TICKS = 100;
    private static final int CLOUD_PULSE_INTERVAL = 10;
    private static final double CLOUD_RADIUS = 2.5;
    private static final double ARROW_BASE_DAMAGE = 3.0;
    private static final float ARROW_SPEED = 2.4f;
    private static final double STUCK_MOVE_SQR = 0.0001;

    @Override
    public String id() {
        return "sporeburst_arrow";
    }

    @Override
    public String displayName() {
        return "Sporeburst Arrow";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null) {
            return false;
        }
        double distanceSqr = ctx.mob().distanceToSqr(target);
        return distanceSqr >= 16.0 && distanceSqr <= 576.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        CompoundTag state = ctx.state();
        state.putInt("cloud", 0);
        state.putInt("stall", 0);
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        LivingEntity target = ctx.target();

        // Aim from the eyes at the target's mid-body, or straight
        // along the look direction if the target is already gone.
        Vec3 from = mob.getEyePosition();
        Vec3 dir;
        if (target != null) {
            Vec3 to = new Vec3(target.getX() - mob.getX(),
                               (target.getY() + target.getBbHeight() * 0.6) - mob.getEyeY(),
                               target.getZ() - mob.getZ());
            dir = to.lengthSqr() > 1.0e-6 ? to.normalize() : mob.getLookAngle();
        } else {
            dir = mob.getLookAngle();
        }

        Arrow arrow = new Arrow(level, mob, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        arrow.setBaseDamage(ARROW_BASE_DAMAGE);
        arrow.snapTo(from.x, from.y, from.z, (float) Math.toDegrees(Math.atan2(-dir.x, dir.z)), 0.0f);
        arrow.shoot(dir.x, dir.y, dir.z, ARROW_SPEED, 0.0f);
        level.addFreshEntity(arrow);

        state.store("arrow", UUIDUtil.CODEC, arrow.getUUID());
        state.putDouble("lx", arrow.getX());
        state.putDouble("ly", arrow.getY());
        state.putDouble("lz", arrow.getZ());
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
            SoundEvents.ARROW_SHOOT, SoundSource.HOSTILE, 1.0f, 0.8f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        int ticks = ctx.ticksActive();

        if (state.getIntOr("cloud", 0) == 0) {
            tickFlight(ctx, level, ticks);
            return;
        }

        // The cloud phase: spore drift every 5 ticks, the
        // poisoning pulse every 10.
        int cloudAge = ticks - state.getIntOr("impact_tick", ticks);
        double cx = state.getDoubleOr("cx", ctx.mob().getX());
        double cy = state.getDoubleOr("cy", ctx.mob().getY());
        double cz = state.getDoubleOr("cz", ctx.mob().getZ());
        if (cloudAge % 5 == 0) {
            level.sendParticles(ParticleTypes.CRIMSON_SPORE, cx, cy + 0.4, cz,
                3, CLOUD_RADIUS * 0.9, 0.5, CLOUD_RADIUS * 0.9, 0.02);
            level.sendParticles(ParticleTypes.WARPED_SPORE, cx, cy + 0.4, cz,
                3, CLOUD_RADIUS * 0.9, 0.5, CLOUD_RADIUS * 0.9, 0.02);
        }
        if (cloudAge % CLOUD_PULSE_INTERVAL == 0) {
            List<LivingEntity> victims = BoggedAbilitySupport.enemiesInBox(ctx, cx, cy, cz,
                CLOUD_RADIUS, 1.0, 2.5);
            for (LivingEntity victim : victims) {
                victim.addEffect(new MobEffectInstance(MobEffects.POISON, 80, 0));
                victim.addEffect(new MobEffectInstance(MobEffects.NAUSEA, 80, 0));
            }
        }
    }

    /** Watches the flying arrow and bursts the pod when it lands, hits, or sticks. */
    private void tickFlight(AbilityContext ctx, ServerLevel level, int ticks) {
        CompoundTag state = ctx.state();
        Entity arrow = null;
        if (state.contains("arrow")) {
            UUID id = state.read("arrow", UUIDUtil.CODEC).orElse(null);
            arrow = id != null ? level.getEntity(id) : null;
        }
        double lx = state.getDoubleOr("lx", ctx.mob().getX());
        double ly = state.getDoubleOr("ly", ctx.mob().getY());
        double lz = state.getDoubleOr("lz", ctx.mob().getZ());

        if (arrow == null) {
            // Hit something, burned, or left the world: the pod
            // bursts where the arrow was last seen.
            burst(ctx, level, lx, ly, lz, ticks);
            return;
        }

        double mdx = arrow.getX() - lx;
        double mdy = arrow.getY() - ly;
        double mdz = arrow.getZ() - lz;
        double movedSqr = mdx * mdx + mdy * mdy + mdz * mdz;
        state.putDouble("lx", arrow.getX());
        state.putDouble("ly", arrow.getY());
        state.putDouble("lz", arrow.getZ());
        if (ticks >= 3 && movedSqr < STUCK_MOVE_SQR) {
            int stall = state.getIntOr("stall", 0) + 1;
            state.putInt("stall", stall);
            if (stall >= 2) {
                // Stuck in a block: burst here and take the arrow
                // with the pod.
                burst(ctx, level, arrow.getX(), arrow.getY(), arrow.getZ(), ticks);
                arrow.discard();
                return;
            }
        } else {
            state.putInt("stall", 0);
        }

        // The dripping contrail.
        if (ticks % 2 == 0) {
            level.sendParticles(ParticleTypes.CRIMSON_SPORE, arrow.getX(), arrow.getY(), arrow.getZ(),
                1, 0.05, 0.05, 0.05, 0.0);
            level.sendParticles(ParticleTypes.WARPED_SPORE, arrow.getX(), arrow.getY(), arrow.getZ(),
                1, 0.05, 0.05, 0.05, 0.0);
        }
    }

    /** Forms the cloud at the impact point: state, burst particles, and the splat. */
    private void burst(AbilityContext ctx, ServerLevel level, double x, double y, double z, int ticks) {
        CompoundTag state = ctx.state();
        state.putInt("cloud", 1);
        state.putDouble("cx", x);
        state.putDouble("cy", y);
        state.putDouble("cz", z);
        state.putInt("impact_tick", ticks);
        level.sendParticles(ParticleTypes.CRIMSON_SPORE, x, y + 0.3, z, 10, 1.1, 0.5, 1.1, 0.08);
        level.sendParticles(ParticleTypes.WARPED_SPORE, x, y + 0.3, z, 10, 1.1, 0.5, 1.1, 0.08);
        level.playSound(null, x, y, z, SoundEvents.MUD_BREAK, SoundSource.HOSTILE, 0.9f, 0.8f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks >= HARD_FINISH_TICKS) {
            return true;
        }
        CompoundTag state = ctx.state();
        return state.getIntOr("cloud", 0) == 1
            && ticks - state.getIntOr("impact_tick", ticks) >= CLOUD_DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to undo: the cloud is state and particles only,
        // and any arrow still flying is a normal projectile (see
        // the CLEANUP note in the class comment).
    }
}
