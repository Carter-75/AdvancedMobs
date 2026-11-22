package mod.advancedmobs.ability.glowsquid;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
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

import java.util.List;
import java.util.UUID;

/**
 * Biolume Pulse (Glow Squid ability 1 of 5, aura /
 * attack).
 *
 * WHAT IT DOES: the glow squid pulses in slow waves
 * of light. Rings of glow particles and glowing ink
 * radiate out through the dark water, and every
 * enemy the light touches is marked: outlined with
 * the glowing effect, visible through rock and ink
 * to everyone. While the marks last, the squid
 * hunts through its own light show and its strikes
 * land harder on marked targets.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat
 *   gate, plus the squid's current target within 14
 *   blocks (distance squared 196), plus the squid in
 *   water (its light show is an underwater display; a
 *   beached glow squid never pulses).
 * - The pulse (every 20 ticks, starting in start()):
 *   one ring of particles at the pulse's ring radius,
 *   which steps 2.0, 3.5, 5.0, 6.0 across successive
 *   pulses and then repeats: 8 points on the ring,
 *   each sending 1 GLOW and 1 GLOW_SQUID_INK. The
 *   same moment runs one bounded query, radius 6.0
 *   around the caster, vertical reach 3.0 both ways:
 *   every enemy (the package enemy rule) gains
 *   GLOWING refreshed to 60 ticks and is entered in
 *   the state tag's marked list with the current
 *   activation tick. A mark lives 100 ticks from its
 *   last refresh; the list is pruned on every pulse
 *   and capped at 16 entries. GLOW_SQUID_SQUIRT
 *   plays softly at each pulse (volume 0.6, pitch
 *   1.2).
 * - The hunt (every tick): the caster is steered at
 *   its live target at 0.5 blocks per tick (the
 *   support steering helper). Between pulses, 1 GLOW
 *   mote drifts off the caster every 4 ticks.
 * - The strike: when the target is within 2.2
 *   blocks, at most once per 20 ticks (stamp in the
 *   state tag), the caster deals a flat 3.0 points
 *   of mob-attack damage credited to itself, or 5.0
 *   when the target is currently marked (in the
 *   marked list with a live stamp). The design's
 *   "attack_damage modifier against marked targets"
 *   is this code bonus: the glow squid has no
 *   ATTACK_DAMAGE attribute instance to modify (see
 *   the provider comment), so the bonus is computed
 *   in code instead. A strike bursts 6 GLOW at the
 *   victim.
 * - Duration: 400 ticks, hard finish.
 *
 * WHAT IT LOOKS LIKE: expanding rings of pale light
 * rolling out through dark water, every hostile it
 * crosses suddenly outlined in white, and the squid
 * itself swimming the rings and biting hardest at
 * the outlined prey.
 *
 * PERFORMANCE: the pulse is one bounded query every
 * 20 ticks plus 16 particle sends; the hunt is one
 * steering write per tick; the strike check is one
 * distance read per tick against the already
 * resolved target. Particle budget per activation:
 * 20 pulses of 16 = 320, drift at most 100, strikes
 * at most 20 times 6 = 120, total at most 540.
 *
 * TERRAIN: this ability changes no blocks; the
 * light is particles and the mark is an effect, so
 * the mobGriefing gate does not apply.
 *
 * CLEANUP: end() has nothing to strip: the caster
 * takes no modifiers and no self effects. The marks
 * live in the state tag and die with it; the GLOWING
 * on victims rides its own 60-tick clock out (the
 * Bray of Doom precedent).
 *
 * State tag keys: "marked" (list of compounds: "id",
 * "tick", one per marked enemy), "last_strike" (the
 * activation tick of the last strike), "pulses" (the
 * pulse count, drives the ring radius cycle).
 */
public final class BiolumePulseAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int PULSE_INTERVAL = 20;
    private static final double MARK_RADIUS = 6.0;
    private static final int MARK_LIFE_TICKS = 100;
    private static final int MARK_CAP = 16;
    private static final double STRIKE_RANGE = 2.2;
    private static final int STRIKE_INTERVAL = 20;
    private static final float STRIKE_DAMAGE = 3.0f;
    private static final float MARKED_STRIKE_DAMAGE = 5.0f;
    private static final double[] RING_RADII = {2.0, 3.5, 5.0, 6.0};

    @Override
    public String id() {
        return "biolume_pulse";
    }

    @Override
    public String displayName() {
        return "Biolume Pulse";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().isInWater()
            && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        state.put("marked", new ListTag());
        state.putInt("last_strike", -STRIKE_INTERVAL);
        state.putInt("pulses", 0);
        pulse(ctx);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        Mob caster = ctx.mob();

        if (ticks % PULSE_INTERVAL == 0) {
            pulse(ctx);
        }
        if (ticks % 4 == 0) {
            level.sendParticles(ParticleTypes.GLOW, caster.getX(), caster.getY() + 0.3, caster.getZ(),
                1, 0.3, 0.3, 0.3, 0.02);
        }

        LivingEntity target = ctx.target();
        if (target == null) {
            return;
        }
        // The hunt: swim the rings toward the prey.
        GlowSquidAbilitySupport.steerToward(caster, target.getX(), target.getY() + 0.2, target.getZ(), 0.5);

        // The strike: marked prey takes the bonus.
        CompoundTag state = ctx.state();
        if (caster.distanceToSqr(target) <= STRIKE_RANGE * STRIKE_RANGE
            && ticks - state.getIntOr("last_strike", -STRIKE_INTERVAL) >= STRIKE_INTERVAL) {
            state.putInt("last_strike", ticks);
            float damage = isMarked(state, target.getUUID(), ticks) ? MARKED_STRIKE_DAMAGE : STRIKE_DAMAGE;
            target.hurtServer(level, level.damageSources().mobAttack(caster), damage);
            level.sendParticles(ParticleTypes.GLOW, target.getX(), target.getY() + 0.4, target.getZ(),
                6, 0.4, 0.4, 0.4, 0.05);
        }
    }

    /** One light wave: the particle ring plus the marking query. */
    private void pulse(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        int pulses = state.getIntOr("pulses", 0);
        state.putInt("pulses", pulses + 1);
        int now = ctx.ticksActive();

        if (level != null) {
            double radius = RING_RADII[pulses % RING_RADII.length];
            for (int i = 0; i < 8; i++) {
                double angle = i * Math.PI / 4.0 + pulses * 0.3;
                double px = caster.getX() + Math.cos(angle) * radius;
                double pz = caster.getZ() + Math.sin(angle) * radius;
                level.sendParticles(ParticleTypes.GLOW, px, caster.getY() + 0.3, pz,
                    1, 0.05, 0.05, 0.05, 0.0);
                level.sendParticles(ParticleTypes.GLOW_SQUID_INK, px, caster.getY() + 0.3, pz,
                    1, 0.05, 0.05, 0.05, 0.0);
            }
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.GLOW_SQUID_SQUIRT, SoundSource.HOSTILE, 0.6f, 1.2f);
        }

        // The marking: outline and record every enemy the light touches.
        ListTag marked = new ListTag();
        for (LivingEntity enemy : GlowSquidAbilitySupport.enemiesAroundSelf(ctx, MARK_RADIUS, 3.0, 3.0)) {
            enemy.addEffect(new MobEffectInstance(MobEffects.GLOWING, 60, 0));
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, enemy.getUUID());
            entry.putInt("tick", now);
            marked.add(entry);
            if (marked.size() >= MARK_CAP) {
                break;
            }
        }
        // Keep still-live older marks that were out of range this pulse.
        ListTag old = state.getListOrEmpty("marked");
        for (int i = 0; i < old.size() && marked.size() < MARK_CAP; i++) {
            CompoundTag entry = old.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (id == null || now - entry.getIntOr("tick", -MARK_LIFE_TICKS) > MARK_LIFE_TICKS) {
                continue;
            }
            boolean refreshed = false;
            for (int j = 0; j < marked.size(); j++) {
                UUID fresh = marked.getCompoundOrEmpty(j).read("id", UUIDUtil.CODEC).orElse(null);
                if (id.equals(fresh)) {
                    refreshed = true;
                    break;
                }
            }
            if (!refreshed) {
                marked.add(entry);
            }
        }
        state.put("marked", marked);
    }

    /** Whether the given entity currently carries a live mark. */
    private boolean isMarked(CompoundTag state, UUID id, int now) {
        ListTag marked = state.getListOrEmpty("marked");
        for (int i = 0; i < marked.size(); i++) {
            CompoundTag entry = marked.getCompoundOrEmpty(i);
            UUID found = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (id.equals(found)) {
                return now - entry.getIntOr("tick", -MARK_LIFE_TICKS) <= MARK_LIFE_TICKS;
            }
        }
        return false;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to strip: no caster modifiers or self effects.
        // Marks die with the state tag; victim GLOWING expires on its own clock.
    }
}
