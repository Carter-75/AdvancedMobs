package mod.advancedmobs.ability.cat;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.feline.Cat;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Creeperbane Aura (Cat ability 2 of 5, aura / control).
 *
 * WHAT IT DOES: the cat sits, tail lashing, radiating the
 * quiet menace cats hold over creepers and phantoms. Those
 * two mobs, and only those two, are weakened and slowed
 * inside its aura, and their flee instincts kick in: the
 * aura steers them away from the cat while it picks its
 * moment.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the cat's current target within 12 blocks
 *   (distance squared 144).
 * - The sit (start()): the cat takes the vanilla sitting
 *   pose (TamableAnimal.setInSittingPose(true), a synced
 *   pose flag, never NoAi: the standing contract forbids
 *   NoAi because abilities tick from the AI step). Its
 *   previous pose is recorded in the state tag and restored
 *   exactly in end(). The hold itself is mechanical as well
 *   as visual: tick() stops the cat's navigation every
 *   tick. The aura radius is resolved once in start() as
 *   half the cat's live FOLLOW_RANGE attribute value,
 *   clamped to [6.0, 12.0] (the design's follow_range based
 *   radius; the vanilla follow range of 16 gives 8.0), and
 *   stored in the state tag so no attribute read happens in
 *   the pulse. A burst of 6 ANGRY_VILLAGER motes opens the
 *   aura.
 * - The pulse (tick(), every 10 ticks): one bounded query
 *   at the stored radius (vertical reach 3 below, 4 above).
 *   Every enemy that is a Creeper or a Phantom (the
 *   design's two targets; all other enemies are untouched)
 *   gains WEAKNESS I and SLOWNESS I, refreshed to 30 ticks,
 *   amplifier 0. Then the flee steering, in code: the
 *   victim's target is cleared if it was hunting the cat;
 *   a horizontal velocity push directly away from the cat
 *   is added (0.45 blocks per tick for a creeper, 0.35 for
 *   a phantom, which also keeps a small 0.05 lift so it
 *   peels away instead of grinding on the ground); and a
 *   creeper (a ground mob with working navigation) is
 *   additionally navigation-driven to a point 6 blocks
 *   directly away from the cat at speed 1.2. A phantom's
 *   flight is not navigation-driven, so for phantoms the
 *   velocity push and the lost target are the steering.
 *   3 ANGRY_VILLAGER motes puff at the cat's body on each
 *   pulse: the menace made visible.
 * - The tail (tick(), every 4 ticks): 1 ANGRY_VILLAGER
 *   mote lashes out behind the cat (half a block behind
 *   its facing, at body height), the tail twitch between
 *   pulses.
 * - Duration: exactly 300 ticks (15 seconds), hard finish.
 * - end(): the sitting pose is restored to the value
 *   recorded in start() (only when this activation changed
 *   it). Victim debuffs ride their own 30 tick clocks and
 *   are not stripped. Idempotent.
 *
 * WHAT IT LOOKS LIKE: the cat sits down in the middle of
 * the fight like it owns the place, tail flicking angry
 * little sparks, and the creepers and phantoms around it
 * slow, sag, and back away from it in a widening circle.
 *
 * PERFORMANCE: the radius and pose are resolved once in
 * start(); tick() is one navigation stop per tick, one
 * bounded query every 10 ticks, and 1 particle every 4
 * ticks. Particle budget per activation: 6 at start, 30
 * pulses times 3 = 90, 75 tail lashes times 1 = 75, total
 * at most 171 particles.
 *
 * TERRAIN: this ability changes no blocks; the
 * mobGriefing gate does not apply.
 *
 * State tag keys: "radius" (the resolved aura radius),
 * "was_sitting" (1 when the cat was already sitting in
 * start(), so end() leaves its pose alone).
 */
public final class CreeperbaneAuraAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int PULSE_INTERVAL = 10;
    private static final int TAIL_INTERVAL = 4;
    private static final int DEBUFF_TICKS = 30;
    private static final double FLEE_PUSH_CREEPER = 0.45;
    private static final double FLEE_PUSH_PHANTOM = 0.35;
    private static final double FLEE_DISTANCE = 6.0;

    @Override
    public String id() {
        return "creeperbane_aura";
    }

    @Override
    public String displayName() {
        return "Creeperbane Aura";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        // The design's follow_range based radius, resolved once.
        double followRange = caster.getAttributeValue(Attributes.FOLLOW_RANGE);
        double radius = Math.max(6.0, Math.min(12.0, followRange * 0.5));
        ctx.state().putDouble("radius", radius);

        // The sit: a synced pose flag, recorded so end() restores it exactly.
        if (caster instanceof Cat cat) {
            ctx.state().putInt("was_sitting", cat.isInSittingPose() ? 1 : 0);
            cat.setInSittingPose(true);
        }
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.sendParticles(ParticleTypes.ANGRY_VILLAGER, caster.getX(), caster.getY() + 0.5, caster.getZ(),
                6, 0.4, 0.3, 0.4, 0.05);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        // The hold: planted where it sat, without NoAi.
        caster.getNavigation().stop();
        int ticks = ctx.ticksActive();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        if (ticks % TAIL_INTERVAL == 0) {
            // The lashing tail, behind the cat's facing.
            Vec3 look = caster.getLookAngle();
            level.sendParticles(ParticleTypes.ANGRY_VILLAGER,
                caster.getX() - look.x * 0.5, caster.getY() + 0.35, caster.getZ() - look.z * 0.5,
                1, 0.1, 0.1, 0.1, 0.02);
        }
        if (ticks % PULSE_INTERVAL != 0) {
            return;
        }
        double radius = ctx.state().getDoubleOr("radius", 8.0);
        level.sendParticles(ParticleTypes.ANGRY_VILLAGER, caster.getX(), caster.getY() + 0.5, caster.getZ(),
            3, 0.5, 0.3, 0.5, 0.05);
        List<LivingEntity> caught = CatAbilitySupport.enemiesAroundSelf(ctx, radius, 3.0, 4.0);
        for (LivingEntity victim : caught) {
            boolean creeper = victim instanceof Creeper;
            if (!creeper && !(victim instanceof Phantom)) {
                continue; // The menace is for creepers and phantoms only.
            }
            victim.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, DEBUFF_TICKS, 0));
            victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, DEBUFF_TICKS, 0));
            if (!(victim instanceof Mob victimMob)) {
                continue;
            }
            // Flee steering: lose the cat, get pushed out, and
            // (for the ground-bound creeper) run the other way.
            if (victimMob.getTarget() == caster) {
                victimMob.setTarget(null);
            }
            Vec3 away = new Vec3(victim.getX() - caster.getX(), 0.0, victim.getZ() - caster.getZ());
            if (away.lengthSqr() < 1.0e-4) {
                away = new Vec3(1.0, 0.0, 0.0);
            }
            away = away.normalize();
            double push = creeper ? FLEE_PUSH_CREEPER : FLEE_PUSH_PHANTOM;
            Vec3 motion = victim.getDeltaMovement();
            victim.setDeltaMovement(motion.x + away.x * push,
                creeper ? motion.y : motion.y + 0.05,
                motion.z + away.z * push);
            victim.hurtMarked = true;
            if (creeper) {
                victimMob.getNavigation().moveTo(
                    victim.getX() + away.x * FLEE_DISTANCE, victim.getY(),
                    victim.getZ() + away.z * FLEE_DISTANCE, 1.2);
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Stands back up, unless it was already sitting before the aura.
        if (ctx.mob() instanceof Cat cat && ctx.state().getIntOr("was_sitting", 0) == 0) {
            cat.setInSittingPose(false);
        }
    }
}
