package mod.advancedmobs.ability.spider;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Strangling Web Shot (Spider ability 1 of 5, attack).
 *
 * WHAT IT DOES: the spider rears back, gathers a mass of silk at
 * its fangs, and fires it at its target. The mass slams home and
 * instantly sets into real cobweb blocks wrapped around the
 * victim, and while the strands hold, the victim is dragged
 * bodily toward the spider and pinned under heavy SLOWNESS: a
 * delivery service that brings the meal to the fangs.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists, between 3 and 14 blocks
 *   away (distance squared 9 to 196). Closer than 3 the spider
 *   just bites; farther than 14 the shot cannot reach.
 * - Wind-up: 8 ticks. start() records the target's UUID. On
 *   ticks 2, 4, and 6, 2 ITEM_COBWEB flecks gather at the
 *   spider's mouth (0.9 blocks ahead of its center, 0.6 up) and
 *   the spider hisses (SPIDER_AMBIENT at volume 0.6, pitch 1.3).
 * - The shot, at tick 8: the target is resolved live (the
 *   recorded UUID, falling back to the context target). 20
 *   ITEM_COBWEB flecks burst at the victim and a WOOL_BREAK snap
 *   plays at volume 0.9. In mobGriefing mode the victim is
 *   wrapped: cobwebs are placed in the 3 by 3 square at the
 *   victim's feet level and the same square 1 block up, at most
 *   18 blocks, through the shared safety-checked placement path
 *   (replaceable, no fluid, no block entity, not unbreakable).
 *   The webs are PERMANENT terrain. With mobGriefing off no
 *   block changes; the drag and the pin below are identical.
 * - The pin and drag, ticks 8 to 68: every 5 ticks the victim
 *   (while alive and within 24 blocks of the spider) is pulled
 *   toward the spider: its velocity is blended halfway toward a
 *   pull vector aimed at the spider's position, horizontal speed
 *   0.45, plus a 0.08 lift when the victim is on the ground so
 *   it skids instead of snagging on lips. Each pulse also
 *   refreshes SLOWNESS IV (amplifier 3) for 12 ticks on the
 *   victim, so between pulses the pin never lapses.
 * - The ability finishes at tick 68, or early once the shot has
 *   fired and the victim is dead, gone, or more than 24 blocks
 *   away: the strands have nothing left to hold.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6): the wrap's cobwebs are real
 * block changes, so they are placed only when
 * AbilityContext.mobGriefingAllowed() is true, and they stay
 * placed for good. Nothing in end() touches them.
 *
 * WHAT IT LOOKS LIKE: silk flecks gathering at the spider's
 * fangs over a hiss, then a burst of ITEM_COBWEB at the victim
 * as a cage of fresh cobweb snaps into place around it, and the
 * wrapped victim visibly skidding across the ground toward the
 * spider in 5-tick tugs until it arrives at biting range.
 *
 * PERFORMANCE: the wind-up sends particles on 3 ticks only. The
 * pin phase runs one UUID resolve and one velocity write per
 * pulse (every 5 ticks, at most 12 pulses). The wrap placement
 * is a fixed 18-position pass, once. No entity scans at all.
 * Particle budget per activation: wind-up 3 times 2 = 6, impact
 * 20, total at most 26 particles.
 *
 * CLEANUP: nothing to undo. The cobwebs are permanent terrain
 * (mobGriefing mode), the SLOWNESS is a short timed effect that
 * expires on its own, and the drag only ever wrote velocity.
 * end() is intentionally empty.
 *
 * State tag keys: "tid" (the target's UUID), "fired" (1 once
 * the shot has gone off).
 */
public final class StranglingWebShotAbility implements MobAbility {

    private static final int FIRE_TICK = 8;
    private static final int DURATION_TICKS = 68;
    private static final int PULSE_INTERVAL = 5;
    private static final double MAX_PIN_DISTANCE_SQR = 24.0 * 24.0;

    @Override
    public String id() {
        return "strangling_web_shot";
    }

    @Override
    public String displayName() {
        return "Strangling Web Shot";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null) {
            return false;
        }
        double distanceSqr = ctx.mob().distanceToSqr(target);
        return distanceSqr >= 9.0 && distanceSqr <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target != null) {
            ctx.state().store("tid", UUIDUtil.CODEC, target.getUUID());
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        CompoundTag state = ctx.state();

        if (ticks < FIRE_TICK) {
            // The wind-up: silk gathers at the fangs.
            if (ticks % 2 == 0) {
                Vec3 look = ctx.mob().getLookAngle();
                level.sendParticles(ParticleTypes.ITEM_COBWEB,
                    ctx.mob().getX() + look.x * 0.9, ctx.mob().getY() + 0.6, ctx.mob().getZ() + look.z * 0.9,
                    2, 0.15, 0.15, 0.15, 0.02);
                if (ticks == 2) {
                    level.playSound(null, ctx.mob().getX(), ctx.mob().getY(), ctx.mob().getZ(),
                        SoundEvents.SPIDER_AMBIENT, SoundSource.HOSTILE, 0.6f, 1.3f);
                }
            }
            return;
        }

        LivingEntity victim = resolveVictim(ctx, level);
        if (state.getIntOr("fired", 0) == 0) {
            state.putInt("fired", 1);
            if (victim != null) {
                fireShot(ctx, level, victim);
            }
            return;
        }

        // The pin: a tug toward the spider and a fresh layer of
        // slowness every 5 ticks.
        if (ticks % PULSE_INTERVAL != 0 || victim == null) {
            return;
        }
        if (ctx.mob().distanceToSqr(victim) > MAX_PIN_DISTANCE_SQR) {
            return;
        }
        Vec3 toSpider = new Vec3(ctx.mob().getX() - victim.getX(), 0.0, ctx.mob().getZ() - victim.getZ());
        if (toSpider.lengthSqr() > 0.0001) {
            Vec3 pull = toSpider.normalize().scale(0.45);
            double lift = victim.onGround() ? 0.08 : 0.0;
            Vec3 current = victim.getDeltaMovement();
            victim.setDeltaMovement(
                current.x * 0.5 + pull.x,
                Math.max(current.y, lift),
                current.z * 0.5 + pull.z);
            victim.hurtMarked = true;
        }
        victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 12, 3));
    }

    /** The shot itself: the burst, the snap, and the permanent cobweb wrap (mobGriefing mode). */
    private static void fireShot(AbilityContext ctx, ServerLevel level, LivingEntity victim) {
        level.sendParticles(ParticleTypes.ITEM_COBWEB, victim.getX(), victim.getY() + 0.8, victim.getZ(),
            20, 0.6, 0.7, 0.6, 0.12);
        level.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
            SoundEvents.WOOL_BREAK, SoundSource.HOSTILE, 0.9f, 1.1f);
        victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 12, 3));
        if (!ctx.mobGriefingAllowed()) {
            return; // Terrain rule: no blocks change with mobGriefing off.
        }
        BlockPos feet = victim.blockPosition();
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    SpiderAbilitySupport.placeCobweb(level, feet.offset(dx, dy, dz));
                }
            }
        }
    }

    /** The recorded victim, resolved live; falls back to the context target. */
    private static LivingEntity resolveVictim(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        if (state.contains("tid")) {
            UUID id = state.read("tid", UUIDUtil.CODEC).orElse(null);
            Entity entity = id != null ? level.getEntity(id) : null;
            if (entity instanceof LivingEntity living && living.isAlive()) {
                return living;
            }
        }
        return ctx.target();
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        if (ctx.ticksActive() >= DURATION_TICKS) {
            return true;
        }
        CompoundTag state = ctx.state();
        if (state.getIntOr("fired", 0) == 0) {
            return false;
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return true;
        }
        LivingEntity victim = resolveVictim(ctx, level);
        return victim == null || ctx.mob().distanceToSqr(victim) > MAX_PIN_DISTANCE_SQR;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Intentionally empty: the cobwebs are permanent terrain,
        // the slowness expires on its own, and the drag wrote only
        // velocity. See CLEANUP in the class comment.
    }
}
