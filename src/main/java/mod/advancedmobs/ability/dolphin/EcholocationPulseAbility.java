package mod.advancedmobs.ability.dolphin;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.List;

/**
 * Echolocation Pulse (Dolphin ability 2 of 5, aura).
 *
 * WHAT IT DOES: the dolphin clicks, and a visible ring of
 * DOLPHIN particles ripples outward through the water,
 * painting everything it touches. Enemies caught by a pulse
 * are outlined with GLOWING through the murk, and the
 * dolphin and its allies hunt the marked prey under
 * DOLPHINS_GRACE. The pulses repeat for the duration, so
 * nothing in the dolphin's water stays hidden for long.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the dolphin's current target within 20 blocks (distance
 *   squared 400).
 * - Duration: exactly 200 ticks (10 seconds) from start().
 * - The dolphin itself carries a FOLLOW_RANGE add_value
 *   modifier of +16 for the whole activation (modifier id
 *   advancedmobs:echolocation_follow, the design's attribute
 *   line; removed in end()), so it holds onto the marked
 *   targets from farther away.
 * - The pulse (every 40 ticks: ticks 40, 80, 120, 160, 200,
 *   5 pulses): one bounded query of radius 12 around the
 *   dolphin (4 below, 4 above, round distance checked after
 *   the box query). Every enemy in it (package rule) gains
 *   GLOWING for 100 ticks: the 100 tick mark against the 40
 *   tick pulse means a marked enemy stays outlined through
 *   two more pulses even if it slips out of one. The dolphin
 *   and every ally in the same query gain DOLPHINS_GRACE I
 *   for 100 ticks on the same clock. The effects are ordinary
 *   instances on their own clocks; end() does not strip
 *   them, they expire naturally after the last pulse (the
 *   Caravan Call precedent). An enemy that somehow had
 *   GLOWING from another source keeps it only as long as its
 *   own instance runs, the accepted edge case shared with
 *   every fixed-duration effect in this system.
 * - The click: DOLPHIN_AMBIENT_WATER at volume 1.5, pitch
 *   1.3 (a bright click, not a song) at every pulse.
 *
 * WHAT IT LOOKS LIKE: at each pulse, three concentric rings
 * of DOLPHIN particles expand around the dolphin (radii 3,
 * 6, and 9, with 10, 14, and 18 motes), the visible ripple
 * of the click rolling out through the water; enemies light
 * up with the vanilla GLOWING outline as the rings pass.
 *
 * PERFORMANCE: tick() returns immediately except every 40th
 * tick, where the pulse is two bounded queries (enemies and
 * allies over the same box) plus the ring math. No registry
 * or config lookups in the tick path: the modifier id is a
 * pre-built static final. Particle budget per activation:
 * 5 pulses times 42 ring motes = 210 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: none (the pulse schedule runs off
 * ticksActive()).
 */
public final class EcholocationPulseAbility implements MobAbility {

    private static final int DURATION_TICKS = 200;
    private static final int PULSE_INTERVAL = 40;
    private static final int EFFECT_TICKS = 100;
    private static final double RADIUS = 12.0;
    private static final Identifier FOLLOW_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "echolocation_follow");

    @Override
    public String id() {
        return "echolocation_pulse";
    }

    @Override
    public String displayName() {
        return "Echolocation Pulse";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 400.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        // The design's FOLLOW_RANGE line, held for the hunt.
        DolphinAbilitySupport.addModifier(ctx.mob(), Attributes.FOLLOW_RANGE,
            new AttributeModifier(FOLLOW_ID, 16.0, AttributeModifier.Operation.ADD_VALUE));
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % PULSE_INTERVAL != 0) {
            return; // Cheap early-out: pulses run every 40 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();

        // The click.
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.DOLPHIN_AMBIENT_WATER, SoundSource.HOSTILE, 1.5f, 1.3f);

        // The ripple: three concentric DOLPHIN rings.
        double[] radii = {3.0, 6.0, 9.0};
        int[] motes = {10, 14, 18};
        for (int ring = 0; ring < radii.length; ring++) {
            for (int i = 0; i < motes[ring]; i++) {
                double angle = i * (Math.PI * 2.0 / motes[ring]);
                level.sendParticles(ParticleTypes.DOLPHIN,
                    caster.getX() + Math.cos(angle) * radii[ring], caster.getY() + 0.3,
                    caster.getZ() + Math.sin(angle) * radii[ring],
                    1, 0.0, 0.0, 0.0, 0.0);
            }
        }

        // Everything the pulse touches is painted.
        List<LivingEntity> enemies = DolphinAbilitySupport.enemiesAroundSelf(ctx, RADIUS, 4.0, 4.0);
        for (LivingEntity enemy : enemies) {
            if (enemy.distanceToSqr(caster) <= RADIUS * RADIUS) {
                enemy.addEffect(new MobEffectInstance(MobEffects.GLOWING, EFFECT_TICKS, 0));
            }
        }
        caster.addEffect(new MobEffectInstance(MobEffects.DOLPHINS_GRACE, EFFECT_TICKS, 0));
        List<LivingEntity> allies = DolphinAbilitySupport.alliesInBox(ctx,
            caster.getX(), caster.getY(), caster.getZ(), RADIUS, 4.0, 4.0);
        for (LivingEntity ally : allies) {
            if (ally.distanceToSqr(caster) <= RADIUS * RADIUS) {
                ally.addEffect(new MobEffectInstance(MobEffects.DOLPHINS_GRACE, EFFECT_TICKS, 0));
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // The FOLLOW_RANGE boost ends with the hunt. The
        // GLOWING marks and the grace are left to expire on
        // their own 100 tick clocks (see the class comment).
        DolphinAbilitySupport.removeModifier(ctx.mob(), Attributes.FOLLOW_RANGE, FOLLOW_ID);
    }
}
