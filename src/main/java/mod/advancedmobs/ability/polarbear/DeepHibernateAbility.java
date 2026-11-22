package mod.advancedmobs.ability.polarbear;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * Deep Hibernate (Polar Bear ability 3 of 5, defense).
 *
 * WHAT IT DOES: hurt and pressed, the bear curls up and a
 * mound of snow heaps over it until only its ears stick
 * out. While hibernating it cannot move or attack, but it
 * regenerates fast and shrugs off most damage, and any
 * enemy foolish enough to hit the mound gets a face full
 * of snow and is knocked back.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the bear's current target within 16 blocks (distance
 *   squared 256) AND the bear at or below 50 percent of its
 *   maximum health: hibernation is a defensive answer, not
 *   an opener.
 * - Duration: exactly 240 ticks (12 seconds) from start().
 * - The curl (every tick): navigation is stopped (never
 *   NoAi), the horizontal velocity is zeroed, and the
 *   bear's vanilla target is cleared, so it neither walks
 *   nor swings for the whole duration: the design's "cannot
 *   attack". (The framework's own combat bookkeeping is
 *   unaffected; the ability is already running.)
 * - The healing (start()): REGENERATION II (amplifier 1)
 *   and RESISTANCE II (amplifier 1), each for 260 ticks,
 *   applied once in start() and removed in end(). Removing
 *   them in end() can also strip a same-effect instance the
 *   upgrade tree granted permanently; ScalingSystem
 *   re-applies permanent upgrade effects on its own tick,
 *   the accepted edge case every fixed-duration effect in
 *   this system shares (Guardian Stance precedent).
 * - The mound (start()): one snow block DISPLAY (scale
 *   2.0, centered transformation) is created through the
 *   support factory, added to the level at the bear's
 *   position, and its UUID stored in the state tag
 *   ("display"). Every tick it is snapped to the bear's
 *   position at feet plus 0.3, so the mound's top sits at
 *   about 1.3 blocks: the bear's body (1.4 tall) is buried
 *   to the ears, which poke out of the top. The display is
 *   a pure visual: no hitbox, no block change, so the
 *   mobGriefing gate does not apply to it. end() discards
 *   it. If the display cannot be created the ability still
 *   runs (the effects and the retaliation carry it).
 * - The retaliation (polled every tick, answered at most
 *   once per 20 ticks): the bear's last-hurt-by timestamp
 *   is primed in start() and compared each tick (the Frozen
 *   Carapace pattern). On a fresh hit whose attacker is a
 *   living enemy (PolarBearAbilitySupport rules) within 12
 *   blocks, the mound answers: 12 SNOWFLAKE burst at the
 *   attacker's chest, POLAR_BEAR_WARNING at volume 1.2 at
 *   the bear, and a code knockback on the attacker of 1.1
 *   horizontal away from the bear and 0.45 up, hurtMarked
 *   set. No damage: the design gives the mound snow and
 *   force, not claws. The next answer can fire 20 ticks
 *   later ("answer_cd" in the state tag).
 * - Drift (every 4 ticks): 2 SNOWFLAKE settle over the
 *   mound.
 *
 * WHAT IT LOOKS LIKE: the bear stops dead, a white mound
 * swallows it to the ears, snowflakes drift down over the
 * heap, and every hit on it answers with a burst of snow
 * in the attacker's face.
 *
 * PERFORMANCE: per tick one navigation stop, one velocity
 * write, one display snap, one timestamp compare; the
 * retaliation resolves at most one attacker and runs at
 * most once per 20 ticks. No entity queries anywhere (the
 * attacker comes from the vanilla hurt record). Particle
 * budget per activation: 60 drift emissions times 2 = 120,
 * plus at most 12 answers times 12 = 144, total at most
 * 264 particles.
 *
 * TERRAIN: the mound is a display entity, not a block; no
 * blocks change, so the gate does not apply.
 *
 * State tag keys: "display" (the mound display's UUID),
 * "hit_ts" (the last-hurt-by timestamp already answered),
 * "answer_cd" (the first tick the next answer may fire).
 */
public final class DeepHibernateAbility implements MobAbility {

    static final int DURATION_TICKS = 240;
    private static final int ANSWER_COOLDOWN_TICKS = 20;
    private static final float MOUND_SCALE = 2.0f;

    @Override
    public String id() {
        return "deep_hibernate";
    }

    @Override
    public String displayName() {
        return "Deep Hibernate";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        Mob caster = ctx.mob();
        return target != null && caster.distanceToSqr(target) <= 256.0
            && caster.getHealth() <= caster.getMaxHealth() * 0.5f;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        state.putInt("hit_ts", caster.getLastHurtByMobTimestamp());
        state.putInt("answer_cd", 0);

        caster.addEffect(new MobEffectInstance(MobEffects.REGENERATION, DURATION_TICKS + 20, 1));
        caster.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, DURATION_TICKS + 20, 1));

        // The mound heaps over the bear: a snow block display
        // snapped onto it every tick (see tick()).
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            Entity mound = PolarBearAbilitySupport.createSnowMoundDisplay(level, MOUND_SCALE);
            if (mound != null) {
                mound.snapTo(caster.getX(), caster.getY() + 0.3, caster.getZ(),
                    caster.getYRot(), 0.0f);
                if (level.addFreshEntity(mound)) {
                    PolarBearAbilitySupport.storeEntityId(state, "display", mound);
                }
            }
        }
        caster.setTarget(null);
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        int ticks = ctx.ticksActive();

        // The curl: rooted in place, no target, no attacks.
        caster.getNavigation().stop();
        Vec3 velocity = caster.getDeltaMovement();
        caster.setDeltaMovement(0.0, velocity.y, 0.0);
        caster.setTarget(null);

        if (level == null) {
            return;
        }

        // Keep the mound heaped over the bear.
        Entity mound = PolarBearAbilitySupport.resolveStored(level, ctx.state(), "display");
        if (mound != null) {
            mound.snapTo(caster.getX(), caster.getY() + 0.3, caster.getZ(), caster.getYRot(), 0.0f);
        }

        // Settling snow over the heap.
        if (ticks % 4 == 0) {
            level.sendParticles(ParticleTypes.SNOWFLAKE, caster.getX(), caster.getY() + 1.5, caster.getZ(),
                2, 0.9, 0.3, 0.9, 0.03);
        }

        answerHit(ctx, level, ticks);
    }

    /**
     * The mound's answer to a fresh hit (see the class
     * comment): a snow burst and a knockback on the
     * attacker, at most once per ANSWER_COOLDOWN_TICKS.
     */
    private void answerHit(AbilityContext ctx, ServerLevel level, int ticks) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        int timestamp = caster.getLastHurtByMobTimestamp();
        if (timestamp == state.getIntOr("hit_ts", 0)) {
            return; // No fresh hit since the last answer.
        }
        state.putInt("hit_ts", timestamp);
        if (ticks < state.getIntOr("answer_cd", 0)) {
            return; // Fresh hit noted, but the mound is still settling.
        }
        LivingEntity attacker = caster.getLastHurtByMob();
        if (attacker == null || !PolarBearAbilitySupport.isEnemy(ctx, attacker)
            || caster.distanceToSqr(attacker) > 144.0) {
            return;
        }
        state.putInt("answer_cd", ticks + ANSWER_COOLDOWN_TICKS);
        level.sendParticles(ParticleTypes.SNOWFLAKE,
            attacker.getX(), attacker.getY() + 1.2, attacker.getZ(),
            12, 0.4, 0.5, 0.4, 0.12);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.POLAR_BEAR_WARNING, SoundSource.HOSTILE, 1.2f, 1.0f);
        double kx = attacker.getX() - caster.getX();
        double kz = attacker.getZ() - caster.getZ();
        double kLength = Math.sqrt(kx * kx + kz * kz);
        if (kLength < 0.001) {
            kx = 1.0;
            kz = 0.0;
            kLength = 1.0;
        }
        attacker.setDeltaMovement(kx / kLength * 1.1, 0.45, kz / kLength * 1.1);
        attacker.hurtMarked = true;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.removeEffect(MobEffects.REGENERATION);
        caster.removeEffect(MobEffects.RESISTANCE);
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Entity mound = PolarBearAbilitySupport.resolveStored(level, ctx.state(), "display");
        if (mound != null) {
            mound.discard();
        }
    }
}
