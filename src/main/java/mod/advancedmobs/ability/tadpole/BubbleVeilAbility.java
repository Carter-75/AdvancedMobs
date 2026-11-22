package mod.advancedmobs.ability.tadpole;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * Bubble Veil (Tadpole ability 2 of 5, defense).
 *
 * WHAT IT DOES: the tadpole wraps itself in a rising
 * column of bubbles and swims inside its own foam armor.
 * It breathes easy and shrugs off hits while the veil
 * lasts, and anything that does bite through pops a
 * mouthful of bubbles instead of flesh: the burst shoves
 * the attacker back and leaves it dragging.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the tadpole's current target within 10 blocks
 *   (distance squared 100).
 * - Duration: 300 ticks (15 seconds).
 * - The veil buffs (start(), the design's three effect
 *   lines): RESISTANCE I, BREATH_OF_THE_NAUTILUS (the
 *   design's easy breathing: the oxygen bar freezes),
 *   and ABSORPTION I (4 absorption points), each for 320
 *   ticks on the caster, all three removed exactly in
 *   end(). RESISTANCE is the "hits land soft" line: a
 *   fifth of every hit is foam, not flesh.
 * - The column (tick()): 1 BUBBLE drifts up around the
 *   caster every 2 ticks, and 1 BUBBLE_COLUMN_UP mote
 *   rises through it every 6 ticks (the design's
 *   bubble_column_up and bubble lines).
 * - The pop (tick(), health poll every 2 ticks): the
 *   caster's health is compared against the last polled
 *   value, kept in the state tag and initialized in
 *   start(). A fresh drop means a hit landed. At most
 *   once per 20 ticks a landed hit pops the veil: 8
 *   BUBBLE_POP particles burst at the caster, and the
 *   attacker (the caster's last hurt-by mob, when it is
 *   alive, counts as an enemy under the package rule,
 *   and is within 8 blocks) is shoved directly away at
 *   0.5 blocks per tick horizontal and 0.2 up, with
 *   hurtMarked set, and gains SLOWNESS I for 40 ticks
 *   (the mouthful of foam). The burst fires even when no
 *   attacker can be resolved (a drowning tick pops foam
 *   too); only the shove needs the attacker. The poll
 *   updates the stored health every time it runs, pop or
 *   not, so slow damage over time cannot queue up a
 *   burst.
 * - The tadpole is NOT planted: the veil moves with it
 *   and its vanilla swimming is untouched.
 * - end(): the three buffs are removed from the caster.
 *   Idempotent; the pop needs no cleanup because it is
 *   particles, one velocity write, and an effect on its
 *   own clock.
 *
 * WHAT IT LOOKS LIKE: a tadpole trailing a wobbling
 * column of rising bubbles, glittering with absorption
 * hearts under the foam, and every solid hit answers
 * with a spray of popped bubbles and the attacker
 * recoiling through the froth.
 *
 * PERFORMANCE: per tick the work is one particle send
 * every 2 ticks, one column mote every 6 ticks, and one
 * health compare every 2 ticks; the pop is one velocity
 * write and one effect apply, at most once per 20 ticks.
 * No entity queries anywhere (the attacker comes from
 * the vanilla last-hurt-by record). Particle budget per
 * activation: at most 150 drift bubbles, 60 column motes
 * (10 in the start burst plus 1 every 6 ticks), at most
 * 15 pops times 8 = 120, total at most 330.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "last_hp" (the last polled health,
 * stored as a float), "last_pop" (the activation tick of
 * the last veil pop).
 */
public final class BubbleVeilAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int BUFF_TICKS = 320;
    private static final int POP_INTERVAL = 20;
    private static final double SHOVE_HORIZONTAL = 0.5;
    private static final double SHOVE_UP = 0.2;

    @Override
    public String id() {
        return "bubble_veil";
    }

    @Override
    public String displayName() {
        return "Bubble Veil";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 100.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, BUFF_TICKS, 0));
        caster.addEffect(new MobEffectInstance(MobEffects.BREATH_OF_THE_NAUTILUS, BUFF_TICKS, 0));
        caster.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, BUFF_TICKS, 0));
        ctx.state().putFloat("last_hp", caster.getHealth());
        ctx.state().putInt("last_pop", -POP_INTERVAL);
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.FISH_SWIM, SoundSource.HOSTILE, 1.0f, 0.8f);
            level.sendParticles(ParticleTypes.BUBBLE_COLUMN_UP,
                caster.getX(), caster.getY(), caster.getZ(),
                10, 0.4, 0.3, 0.4, 0.05);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        if (ticks % 2 == 0) {
            // One foam mote drifting up the column.
            level.sendParticles(ParticleTypes.BUBBLE, caster.getX(), caster.getY() + 0.2, caster.getZ(),
                1, 0.25, 0.2, 0.25, 0.02);
            pollHit(ctx, level, ticks);
        }
        if (ticks % 6 == 0) {
            level.sendParticles(ParticleTypes.BUBBLE_COLUMN_UP,
                caster.getX(), caster.getY(), caster.getZ(),
                1, 0.2, 0.2, 0.2, 0.02);
        }
    }

    /** The health poll and the veil pop. Every 2 ticks; see the class comment. */
    private void pollHit(AbilityContext ctx, ServerLevel level, int ticks) {
        Mob caster = ctx.mob();
        float now = caster.getHealth();
        float last = ctx.state().getFloatOr("last_hp", now);
        ctx.state().putFloat("last_hp", now);
        if (now >= last - 0.01f) {
            return;
        }
        if (ticks - ctx.state().getIntOr("last_pop", -POP_INTERVAL) < POP_INTERVAL) {
            return;
        }
        ctx.state().putInt("last_pop", ticks);
        level.sendParticles(ParticleTypes.BUBBLE_POP, caster.getX(), caster.getY() + 0.3, caster.getZ(),
            8, 0.4, 0.3, 0.4, 0.08);
        LivingEntity attacker = caster.getLastHurtByMob();
        if (attacker == null || !attacker.isAlive()
            || !TadpoleAbilitySupport.isEnemy(ctx, attacker)
            || caster.distanceToSqr(attacker) > 64.0) {
            return;
        }
        double dx = attacker.getX() - caster.getX();
        double dz = attacker.getZ() - caster.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001) {
            dx = 1.0;
            len = 1.0;
        }
        attacker.setDeltaMovement(dx / len * SHOVE_HORIZONTAL, SHOVE_UP, dz / len * SHOVE_HORIZONTAL);
        attacker.hurtMarked = true;
        attacker.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 0));
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.removeEffect(MobEffects.RESISTANCE);
        caster.removeEffect(MobEffects.BREATH_OF_THE_NAUTILUS);
        caster.removeEffect(MobEffects.ABSORPTION);
    }
}
