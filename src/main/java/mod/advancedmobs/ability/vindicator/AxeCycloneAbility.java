package mod.advancedmobs.ability.vindicator;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Axe Cyclone (Vindicator ability 1 of 5, attack).
 *
 * WHAT IT DOES: the vindicator plants its feet, throws its
 * axe wide, and spins like a saw blade: a whirling circle of
 * sweep arcs that chews through everything around it, faster
 * with every rotation, dragging slowly toward the nearest
 * enemy like a storm with a grudge. A second axe, an item
 * display, spins overhead in the opposite direction for pure
 * menace. After five seconds the spin stops dead and the
 * vindicator stands dizzy, axe down, for one vulnerable
 * breath.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the vindicator's current target within 7 blocks (distance
 *   squared 49) and the vindicator on the ground. The cyclone
 *   is a point-blank blender; it never starts across a room.
 * - Duration: 140 ticks total. Ticks 1 to 100 are the spin;
 *   ticks 101 to 140 are the dizzy breath.
 * - SPEED (the design's Uses list): applied at amplifier 0 in
 *   start() and raised at every 20-tick rotation mark,
 *   amplifier = min(3, ticks / 20), each application lasting
 *   30 ticks so the chain never lapses: SPEED I for the first
 *   rotation, up to SPEED IV for the last. The rising speed
 *   feeds the vanilla movement the steering below rides on,
 *   and the reach grows with it (next point).
 * - The chew, every 5 ticks during the spin: one bounded
 *   query at radius R around the vindicator (2.0 below to 2.5
 *   above), where R grows one step per rotation: 2.2 blocks
 *   for ticks 1 to 20, then 2.5, 2.8, 3.1, and 3.4 for the
 *   last rotation. Every enemy (VindicatorAbilitySupport
 *   rules) inside takes 5.0 points of typed mob-attack damage
 *   credited to the vindicator and is pushed radially outward
 *   at 0.55 horizontal and 0.25 up (delta movement set,
 *   hurtMarked set so it syncs; a victim dead center is
 *   pushed along the vindicator's look direction). A victim
 *   standing in the circle for the whole spin takes a pulse
 *   every 5 ticks: this is a blender, not a duel.
 * - The drag: every chew pulse records the nearest enemy hit
 *   (or, failing that, the framework target) as the drag
 *   point. On every spin tick the vindicator's navigation is
 *   stopped and its horizontal velocity is set toward the
 *   drag point at 0.12 blocks per tick when it is more than
 *   1.0 block away, so the cyclone crawls after its prey
 *   instead of spinning in place. Vertical velocity is left
 *   alone. NoAi is never used.
 * - The overhead axe: an item display of an iron axe at scale
 *   1.3, created in start() and tracked in the state tag. On
 *   every spin tick it is snapped to a point circling the
 *   vindicator at radius 0.55, at head height plus 0.55,
 *   its angle stepping BACKWARD by 0.35 radians per tick
 *   (counter-rotation against the spin, which the chew's
 *   outward push implies runs the other way), gliding on the
 *   display's 2-tick teleport duration. The display is a pure
 *   visual: it deals no damage; the chew pulse above is the
 *   real circle. It is discarded the moment the dizzy phase
 *   begins (axe down) and again defensively in end().
 * - The dizzy breath (tick 101): the axe display is
 *   discarded, navigation is stopped, and the vindicator
 *   applies SLOWNESS I and WEAKNESS I to itself for 40 ticks:
 *   the vulnerable window the design promises. No damage, no
 *   steering, no drag for these 40 ticks.
 * - Sounds: a PLAYER_ATTACK_SWEEP whoosh at every rotation
 *   mark (ticks 20, 40, 60, 80), volume 0.9, pitch rising
 *   0.8, 0.9, 1.0, 1.1 with the speed. A soft VINDICATOR_HURT
 *   groan (volume 0.6, pitch 0.7) when the dizziness lands.
 *
 * WHAT IT LOOKS LIKE: the vindicator spinning in place under
 * a counter-spinning axe, a ring of 10 SWEEP_ATTACK arcs
 * drawn on the chew circle at every pulse (radius R at waist
 * height), enemies flung outward in sprays, then a sudden
 * stop: two thin SMOKE wisps over its head every 10 dizzy
 * ticks while it sways, axe gone, guard down.
 *
 * TERRAIN: this ability places and breaks no blocks in
 * either mobGriefing mode; the circle is damage and motion
 * only, so the terrain gate has nothing to block here.
 *
 * PERFORMANCE: the chew is one bounded query every 5 ticks;
 * the drag and the axe snap are one distance computation, one
 * velocity write, and one UUID resolve per tick; effect
 * refreshes happen on the 20-tick marks only. Particle
 * budget per activation: 20 chew pulses times 10 sweep arcs
 * = 200, plus 4 dizzy draws times 2 = 8, total at most 208.
 *
 * CLEANUP: end() discards the overhead axe if it somehow
 * survives, and removes SPEED, SLOWNESS, and WEAKNESS from
 * the vindicator. Those are timed effects the ability itself
 * applied; if the vindicator somehow carried one from its
 * permanent upgrade tree, ScalingSystem's
 * refreshMissingEffects re-applies the upgrade version, the
 * accepted edge case every fixed-duration effect in this
 * system shares (Scarab Brood precedent).
 *
 * State tag keys: "axe" (list holding the overhead axe
 * display's entry), "axe_angle" (its orbit angle, radians),
 * "drag_x", "drag_z" (the drag point), "dizzy" (1 once the
 * dizzy phase has begun).
 */
public final class AxeCycloneAbility implements MobAbility {

    static final int SPIN_TICKS = 100;
    static final int TOTAL_TICKS = 140;
    private static final int PULSE_INTERVAL = 5;
    private static final float PULSE_DAMAGE = 5.0f;
    private static final double PULSE_KNOCKBACK = 0.55;
    private static final double PULSE_KNOCKBACK_UP = 0.25;
    private static final double DRAG_SPEED = 0.12;

    @Override
    public String id() {
        return "axe_cyclone";
    }

    @Override
    public String displayName() {
        return "Axe Cyclone";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().onGround()
            && ctx.mob().distanceToSqr(target) <= 49.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        ctx.state().putDouble("axe_angle", 0.0);
        ctx.state().putInt("dizzy", 0);
        LivingEntity target = ctx.target();
        ctx.state().putDouble("drag_x", target != null ? target.getX() : mob.getX());
        ctx.state().putDouble("drag_z", target != null ? target.getZ() : mob.getZ());

        // Rotation zero: SPEED I. The 20-tick marks raise it.
        mob.addEffect(new MobEffectInstance(MobEffects.SPEED, 30, 0));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            Entity axe = VindicatorAbilitySupport.createItemDisplay(level, new ItemStack(Items.IRON_AXE), 1.3f);
            if (axe != null) {
                axe.snapTo(mob.getX(), mob.getY() + mob.getBbHeight() + 0.55, mob.getZ(), 0.0f, 0.0f);
                if (level.addFreshEntity(axe)) {
                    VindicatorAbilitySupport.trackEntity(ctx.state(), "axe", axe);
                }
            }
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() > SPIN_TICKS) {
            tickDizzy(ctx);
            return;
        }
        Mob mob = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();

        // Rotation marks: the SPEED climbs and the whoosh rises.
        if (ticks % 20 == 0) {
            mob.addEffect(new MobEffectInstance(MobEffects.SPEED, 30, Math.min(3, ticks / 20)));
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP,
                SoundSource.HOSTILE, 0.9f, 0.7f + 0.1f * (ticks / 20));
        }

        if (ticks % PULSE_INTERVAL == 0) {
            chewPulse(ctx, level);
        }

        // The drag: crawl toward the recorded point.
        mob.getNavigation().stop();
        double dx = ctx.state().getDoubleOr("drag_x", mob.getX()) - mob.getX();
        double dz = ctx.state().getDoubleOr("drag_z", mob.getZ()) - mob.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        Vec3 current = mob.getDeltaMovement();
        if (horizontal > 1.0) {
            mob.setDeltaMovement(dx / horizontal * DRAG_SPEED, current.y, dz / horizontal * DRAG_SPEED);
            mob.hurtMarked = true;
        }

        // The overhead axe counter-spins.
        Entity axe = VindicatorAbilitySupport.resolveFirstTracked(level, ctx.state(), "axe");
        if (axe != null) {
            double angle = ctx.state().getDoubleOr("axe_angle", 0.0) - 0.35;
            ctx.state().putDouble("axe_angle", angle);
            axe.snapTo(mob.getX() + Math.cos(angle) * 0.55, mob.getY() + mob.getBbHeight() + 0.55,
                mob.getZ() + Math.sin(angle) * 0.55, 0.0f, 0.0f);
        }
    }

    /** One chew pulse: the damage circle, the sweep ring visual, and the drag point update. */
    private void chewPulse(AbilityContext ctx, ServerLevel level) {
        Mob mob = ctx.mob();
        int ticks = ctx.ticksActive();
        double radius = 2.2 + 0.3 * Math.min(4, ticks / 20);
        List<LivingEntity> victims = VindicatorAbilitySupport.enemiesAroundSelf(ctx, radius, 2.0, 2.5);
        LivingEntity nearest = null;
        double nearestSqr = Double.MAX_VALUE;
        for (LivingEntity victim : victims) {
            victim.hurtServer(level, level.damageSources().mobAttack(mob), PULSE_DAMAGE);
            double ox = victim.getX() - mob.getX();
            double oz = victim.getZ() - mob.getZ();
            double horizontal = Math.sqrt(ox * ox + oz * oz);
            if (horizontal < 1.0e-4) {
                ox = mob.getLookAngle().x;
                oz = mob.getLookAngle().z;
                horizontal = Math.sqrt(ox * ox + oz * oz);
                if (horizontal < 1.0e-4) {
                    ox = 1.0;
                    horizontal = 1.0;
                }
            }
            victim.setDeltaMovement(ox / horizontal * PULSE_KNOCKBACK, PULSE_KNOCKBACK_UP,
                oz / horizontal * PULSE_KNOCKBACK);
            victim.hurtMarked = true;
            double distSqr = victim.distanceToSqr(mob);
            if (distSqr < nearestSqr) {
                nearestSqr = distSqr;
                nearest = victim;
            }
        }
        LivingEntity dragTarget = nearest != null ? nearest : ctx.target();
        if (dragTarget != null) {
            ctx.state().putDouble("drag_x", dragTarget.getX());
            ctx.state().putDouble("drag_z", dragTarget.getZ());
        }
        for (int i = 0; i < 10; i++) {
            double a = i * (Math.PI * 2.0 / 10.0);
            level.sendParticles(ParticleTypes.SWEEP_ATTACK, mob.getX() + Math.cos(a) * radius,
                mob.getY() + 1.0, mob.getZ() + Math.sin(a) * radius, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /** The dizzy breath: axe down, guard down, standing still. Runs ticks 101 to 140. */
    private void tickDizzy(AbilityContext ctx) {
        Mob mob = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (ctx.state().getIntOr("dizzy", 0) == 0) {
            ctx.state().putInt("dizzy", 1);
            mob.getNavigation().stop();
            mob.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, TOTAL_TICKS - SPIN_TICKS, 0));
            mob.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, TOTAL_TICKS - SPIN_TICKS, 0));
            if (level != null) {
                Entity axe = VindicatorAbilitySupport.resolveFirstTracked(level, ctx.state(), "axe");
                if (axe != null) {
                    axe.discard();
                }
                level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.VINDICATOR_HURT,
                    SoundSource.HOSTILE, 0.6f, 0.7f);
            }
            return;
        }
        if (level != null && ctx.ticksActive() % 10 == 0) {
            level.sendParticles(ParticleTypes.SMOKE, mob.getX(), mob.getY() + mob.getBbHeight() + 0.3,
                mob.getZ(), 2, 0.25, 0.1, 0.25, 0.02);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= TOTAL_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        mob.removeEffect(MobEffects.SPEED);
        mob.removeEffect(MobEffects.SLOWNESS);
        mob.removeEffect(MobEffects.WEAKNESS);
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            Entity axe = VindicatorAbilitySupport.resolveFirstTracked(level, ctx.state(), "axe");
            if (axe != null) {
                axe.discard();
            }
        }
    }
}
