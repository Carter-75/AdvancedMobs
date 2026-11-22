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
import net.minecraft.world.phys.Vec3;

/**
 * Torpedo Ram (Dolphin ability 1 of 5, attack / mobility).
 *
 * WHAT IT DOES: the dolphin lines up on its target, a BUBBLE
 * trail streaming behind it, and torpedoes through the water
 * at ramming speed under DOLPHINS_GRACE. The impact lands
 * with a burst of SPLASH and DAMAGE_INDICATOR particles, and
 * instead of a straight shove the victim is knocked SPINNING
 * sideways, perpendicular to the dolphin's line of attack, so
 * it tumbles off balance in the current while the dolphin
 * swims through and away.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the dolphin's current target within 16 blocks (distance
 *   squared 256), plus the dolphin in water (a torpedo needs
 *   water to run in).
 * - The run-up (start()): the target's UUID and chest
 *   position are recorded, and for the activation the
 *   dolphin gains DOLPHINS_GRACE I (the design's line; the
 *   effect is removed in end()) and a
 *   WATER_MOVEMENT_EFFICIENCY add_value modifier of +1.0
 *   (modifier id advancedmobs:torpedo_ram_efficiency, the
 *   design's attribute line at its maximum: the dolphin
 *   loses none of its speed to the water while it rams;
 *   removed in end()).
 * - The charge (tick()): the dolphin is steered straight at
 *   the target's live chest at 1.35 blocks per tick (code
 *   velocity, the package steering helper), and its last
 *   known position is refreshed every tick so a target that
 *   dies mid-charge is still charged at its last spot. 3
 *   BUBBLE particles trail at the dolphin's tail each tick.
 *   If the dolphin leaves the water mid-charge the run is
 *   over (the strike simply never lands).
 * - The strike: the first tick the dolphin comes within 1.8
 *   blocks of the target, the ram lands exactly once: damage
 *   equal to the dolphin's live ATTACK_DAMAGE + 4.0 (7.0 for
 *   an unupgraded dolphin, base 3.0 verified against the
 *   jar) as a mob attack credited to the dolphin, then the
 *   spin: the victim's velocity is set to 1.3 blocks per
 *   tick along the horizontal perpendicular of the dolphin's
 *   approach direction (the side chosen by the sign of the
 *   cross product at impact, so it always spins AWAY from
 *   the line it was hit on) plus 0.35 up, hurtMarked so the
 *   knockback syncs. 12 SPLASH and 8 DAMAGE_INDICATOR
 *   particles burst at the victim and DOLPHIN_ATTACK plays
 *   at volume 1.2.
 * - After the strike the dolphin swims on for 10 more ticks
 *   (steered past the victim on its last heading) and the
 *   ability ends. Without a strike, reaching within 1.0
 *   blocks of the recorded last position also ends it (the
 *   charge ran through empty water), and the hard cap is 80
 *   ticks either way.
 *
 * WHAT IT LOOKS LIKE: a bubble-streaming torpedo run, one
 * hard splash burst at the victim, and the victim cartwheeling
 * sideways through the water while the dolphin carries on
 * past it.
 *
 * PERFORMANCE: per tick the charge is one steering write,
 * one distance computation, one position record, and 3
 * particles; the strike is one damage call and one velocity
 * write. No entity queries anywhere. Particle budget per
 * activation: at most 80 ticks times 3 = 240 trail plus 20
 * at the strike, total at most 260 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * CLEANUP: end() removes the efficiency modifier and the
 * DOLPHINS_GRACE effect (the grace is this ability's own;
 * a dolphin that somehow had it from another source would
 * lose it at end(), the accepted edge case shared with
 * every fixed-duration self effect in this system).
 *
 * State tag keys: "tx", "ty", "tz" (the target's last known
 * chest position), "struck" (1 once the ram has landed),
 * "strike_tick" (the tick the ram landed on).
 */
public final class TorpedoRamAbility implements MobAbility {

    private static final int HARD_CAP_TICKS = 80;
    private static final int SWIM_THROUGH_TICKS = 10;
    private static final double CHARGE_SPEED = 1.35;
    private static final double STRIKE_RANGE = 1.8;
    private static final double STRIKE_BONUS = 4.0;
    private static final Identifier EFFICIENCY_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "torpedo_ram_efficiency");

    @Override
    public String id() {
        return "torpedo_ram";
    }

    @Override
    public String displayName() {
        return "Torpedo Ram";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().isInWater()
            && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        LivingEntity target = ctx.target();
        if (target != null) {
            recordTarget(ctx, target);
        } else {
            ctx.state().putDouble("tx", caster.getX());
            ctx.state().putDouble("ty", caster.getY());
            ctx.state().putDouble("tz", caster.getZ());
        }
        ctx.state().putInt("struck", 0);
        ctx.state().putInt("strike_tick", -1);

        // The design's two self boosts for the run.
        caster.addEffect(new MobEffectInstance(MobEffects.DOLPHINS_GRACE, HARD_CAP_TICKS + 20, 0));
        DolphinAbilitySupport.addModifier(caster, Attributes.WATER_MOVEMENT_EFFICIENCY,
            new AttributeModifier(EFFICIENCY_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        if (!caster.isInWater()) {
            return; // Out of water the run is dead; isFinished ends it.
        }
        LivingEntity target = ctx.target();
        if (target != null) {
            recordTarget(ctx, target);
        }
        double tx = ctx.state().getDoubleOr("tx", caster.getX());
        double ty = ctx.state().getDoubleOr("ty", caster.getY());
        double tz = ctx.state().getDoubleOr("tz", caster.getZ());

        double dist = DolphinAbilitySupport.steerToward(caster, tx, ty, tz, CHARGE_SPEED);

        // The bubble trail streams off the tail.
        Vec3 motion = caster.getDeltaMovement();
        level.sendParticles(ParticleTypes.BUBBLE,
            caster.getX() - motion.x * 0.8, caster.getY() + 0.2, caster.getZ() - motion.z * 0.8,
            3, 0.15, 0.15, 0.15, 0.02);

        if (ctx.state().getIntOr("struck", 0) == 0 && target != null && dist <= STRIKE_RANGE) {
            strike(ctx, level, target, motion);
        }
    }

    /** Records the target's chest position as the charge point. */
    private void recordTarget(AbilityContext ctx, LivingEntity target) {
        ctx.state().putDouble("tx", target.getX());
        ctx.state().putDouble("ty", target.getY() + target.getBbHeight() * 0.5);
        ctx.state().putDouble("tz", target.getZ());
    }

    /** The ram lands: damage, the sideways spin, and the splash burst. See the class comment. */
    private void strike(AbilityContext ctx, ServerLevel level, LivingEntity target, Vec3 approach) {
        Mob caster = ctx.mob();
        ctx.state().putInt("struck", 1);
        ctx.state().putInt("strike_tick", ctx.ticksActive());

        double damage = caster.getAttributeValue(Attributes.ATTACK_DAMAGE) + STRIKE_BONUS;
        target.hurtServer(level, level.damageSources().mobAttack(caster), (float) damage);

        // The spin: perpendicular to the approach, on the side
        // the cross product picks, so the victim tumbles
        // sideways off the line of the hit.
        double px = -approach.z;
        double pz = approach.x;
        double plen = Math.sqrt(px * px + pz * pz);
        if (plen < 0.001) {
            px = 1.0;
            pz = 0.0;
            plen = 1.0;
        }
        double side = (target.getX() - caster.getX()) * pz - (target.getZ() - caster.getZ()) * px >= 0 ? 1.0 : -1.0;
        target.setDeltaMovement(px / plen * 1.3 * side, 0.35, pz / plen * 1.3 * side);
        target.hurtMarked = true;

        level.sendParticles(ParticleTypes.SPLASH, target.getX(), target.getY() + 0.5, target.getZ(),
            12, 0.4, 0.4, 0.4, 0.1);
        level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, target.getX(), target.getY() + 0.6, target.getZ(),
            8, 0.3, 0.3, 0.3, 0.1);
        level.playSound(null, target.getX(), target.getY(), target.getZ(),
            SoundEvents.DOLPHIN_ATTACK, SoundSource.HOSTILE, 1.2f, 1.0f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks >= HARD_CAP_TICKS) {
            return true;
        }
        if (!ctx.mob().isInWater()) {
            return true;
        }
        int strikeTick = ctx.state().getIntOr("strike_tick", -1);
        if (strikeTick >= 0) {
            return ticks >= strikeTick + SWIM_THROUGH_TICKS;
        }
        // No strike yet: running through the recorded position
        // ends the charge.
        Mob caster = ctx.mob();
        double dx = ctx.state().getDoubleOr("tx", caster.getX()) - caster.getX();
        double dy = ctx.state().getDoubleOr("ty", caster.getY()) - caster.getY();
        double dz = ctx.state().getDoubleOr("tz", caster.getZ()) - caster.getZ();
        return ticks > 5 && dx * dx + dy * dy + dz * dz <= 1.0;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        DolphinAbilitySupport.removeModifier(caster, Attributes.WATER_MOVEMENT_EFFICIENCY, EFFICIENCY_ID);
        caster.removeEffect(MobEffects.DOLPHINS_GRACE);
    }
}
