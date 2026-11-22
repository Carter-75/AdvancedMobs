package mod.advancedmobs.ability.chicken;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Items;

/**
 * Sky Tyrant Dive (Chicken ability 4 of 5, mobility /
 * attack).
 *
 * WHAT IT DOES: the hen flaps up in a flutter of
 * feathers, hangs for a heartbeat at the top of her
 * climb with her wings spread, then folds them and
 * plummets beak-first onto her victim. The longer the
 * fall, the harder the little tyrant hits, and she walks
 * away from her own crater without a scratch.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the chicken's current target within 20 blocks
 *   (distance squared 400).
 * - The kit (start(), removed exactly in end()): a
 *   transient GRAVITY modifier of -0.5
 *   ADD_MULTIPLIED_BASE (halved gravity, the design's
 *   gravity line, so the climb and the hang float) and a
 *   transient SAFE_FALL_DISTANCE modifier of +30
 *   ADD_VALUE (the design's safe fall line: the chicken
 *   takes no fall damage from its own dive, on top of
 *   the fall immunity chickens already enjoy). LEVITATION
 *   I is applied for 14 ticks as the flutter assist (the
 *   design's levitation line). The classic variant
 *   ambient cluck plays (volume 1.2, pitch 1.2).
 * - Phase 0, the rise (12 ticks): every tick the velocity
 *   is set toward a point 8 blocks above the target's
 *   live position at 0.85 blocks per tick (the step
 *   capped at the remaining distance; the Phantom
 *   steering pattern, since a chicken's movement answers
 *   a per-tick velocity write while an ability drives
 *   it). If the target dies during the rise, the climb
 *   point is above the chicken itself. 1 feather ITEM
 *   particle sheds every 2 ticks (the flutter of the
 *   design).
 * - Phase 1, the hang (8 ticks): at entry the chicken
 *   gains SLOW_FALLING for 12 ticks (the design's slow
 *   falling line), the hover point (its position at
 *   entry) and the dive height are recorded: dive height
 *   is the chicken's height above the target's feet at
 *   hang entry (above its own feet if the target is
 *   gone). Every hang tick the velocity steers gently
 *   toward the hover point at 0.3 blocks per tick,
 *   holding the hen at the top of her climb.
 * - Phase 2, the dive: at entry SLOW_FALLING and any
 *   remaining LEVITATION are removed (the wings fold).
 *   Every tick the velocity is set straight at the
 *   target's live chest (feet plus half its height) at
 *   1.8 blocks per tick. Impact is the first tick the
 *   chicken is within 1.4 blocks of the target: the
 *   strike lands (below).
 * - The strike (exactly once, on impact): the target
 *   alone takes mob-attack damage credited to the
 *   chicken equal to 3.0 plus 1.0 per block of dive
 *   height above 2, capped at 12.0 total (the design's
 *   falloff by dive height in code: a full 8 block climb
 *   strikes for 9.0, a point-blank hop for 3.0; flat code
 *   value, since a chicken has no ATTACK_DAMAGE
 *   instance, see the support class comment). 10 CRIT
 *   plus 6 DAMAGE_INDICATOR particles burst off the
 *   victim's chest (the design's crit particles) and the
 *   classic variant hurt cry plays at the victim (volume
 *   1.3, pitch 0.9). Then the recover phase.
 * - The miss: if the dive runs 40 ticks without impact,
 *   or the chicken falls 2 blocks below the target's
 *   feet, or the target is gone at dive entry, the dive
 *   has missed: no strike, straight to the recover
 *   phase. The price of a miss is the wasted dive
 *   itself.
 * - Phase 3, the recover: 10 ticks of damped movement
 *   (velocity times 0.6 per tick) while the hen shakes
 *   herself off, then the ability ends and the vanilla
 *   AI takes back over.
 * - Duration: the phases above; hard finish at 100
 *   ticks.
 * - end(): the GRAVITY and SAFE_FALL_DISTANCE modifiers
 *   are removed and any LEVITATION or SLOW_FALLING the
 *   ability applied is stripped, so a terminated dive
 *   never leaves the hen floating. The victim's damage
 *   is instant and needs no cleanup.
 *
 * WHAT IT LOOKS LIKE: a cluck, a burst of feathers, and
 * a hen hauling herself skyward in a wobbling climb; a
 * heartbeat hanging at the top with wings spread wide;
 * then the wings fold and a white streak knifes down
 * into one victim in a burst of crit stars, and the hen
 * struts out of the impact without a scratch.
 *
 * PERFORMANCE: per tick one distance computation, one
 * velocity write, and at most 1 particle every 2 ticks;
 * the strike is a single damage call on the one impact
 * tick; no entity scans anywhere. Particle budget per
 * activation: rise and hang at most 10 feathers, strike
 * burst 16, total at most 26 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "phase" (0 rise, 1 hang, 2 dive, 3
 * recover), "phase_ticks", "hx", "hy", "hz" (the hover
 * point), "dive_h" (the recorded dive height), "done"
 * (1 when the recover completes).
 */
public final class SkyTyrantDiveAbility implements MobAbility {

    private static final int RISE_TICKS = 12;
    private static final int HANG_TICKS = 8;
    private static final int DIVE_MAX_TICKS = 40;
    private static final int RECOVER_TICKS = 10;
    private static final int HARD_FINISH_TICKS = 100;
    private static final double RISE_SPEED = 0.85;
    private static final double HANG_SPEED = 0.3;
    private static final double DIVE_SPEED = 1.8;
    private static final double CLIMB_HEIGHT = 8.0;
    private static final double IMPACT_DISTANCE = 1.4;
    private static final double STRIKE_BASE = 3.0;
    private static final double STRIKE_PER_BLOCK = 1.0;
    private static final double STRIKE_CAP = 12.0;
    private static final ItemParticleOption FEATHER =
        new ItemParticleOption(ParticleTypes.ITEM, Items.FEATHER);
    private static final Identifier LIGHT_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "sky_tyrant_light");
    private static final Identifier SOFT_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "sky_tyrant_soft");

    @Override
    public String id() {
        return "sky_tyrant_dive";
    }

    @Override
    public String displayName() {
        return "Sky Tyrant Dive";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 400.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ctx.state().putInt("phase", 0);
        ctx.state().putInt("phase_ticks", 0);
        ctx.state().putInt("done", 0);
        // The kit (see the class comment): halved gravity
        // and the safe fall pad, both removed in end().
        ChickenAbilitySupport.addModifier(caster, Attributes.GRAVITY,
            new AttributeModifier(LIGHT_ID, -0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        ChickenAbilitySupport.addModifier(caster, Attributes.SAFE_FALL_DISTANCE,
            new AttributeModifier(SOFT_ID, 30.0, AttributeModifier.Operation.ADD_VALUE));
        caster.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 14, 0));
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            Holder<SoundEvent> cluck = ChickenAbilitySupport.chickenSound(false);
            if (cluck != null) {
                level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), cluck,
                    SoundSource.HOSTILE, 1.2f, 1.2f);
            }
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        int phase = ctx.state().getIntOr("phase", 0);
        int phaseTicks = ctx.state().getIntOr("phase_ticks", 0) + 1;
        ctx.state().putInt("phase_ticks", phaseTicks);
        LivingEntity target = ctx.target();

        if (phase == 0) {
            double climbX = target != null ? target.getX() : caster.getX();
            double climbY = (target != null ? target.getY() : caster.getY()) + CLIMB_HEIGHT;
            double climbZ = target != null ? target.getZ() : caster.getZ();
            ChickenAbilitySupport.steerToward(ctx, climbX, climbY, climbZ, RISE_SPEED);
            if (level != null && phaseTicks % 2 == 0) {
                level.sendParticles(FEATHER, caster.getX(), caster.getY() + 0.3, caster.getZ(),
                    1, 0.25, 0.2, 0.25, 0.05);
            }
            if (phaseTicks >= RISE_TICKS) {
                // The hang: spread the wings, record the
                // hover point and the dive height.
                caster.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 12, 0));
                ctx.state().putDouble("hx", caster.getX());
                ctx.state().putDouble("hy", caster.getY());
                ctx.state().putDouble("hz", caster.getZ());
                double baseY = target != null ? target.getY() : caster.getY() - CLIMB_HEIGHT;
                ctx.state().putDouble("dive_h", Math.max(0.0, caster.getY() - baseY));
                setPhase(ctx, 1);
            }
        } else if (phase == 1) {
            ChickenAbilitySupport.steerToward(ctx,
                ctx.state().getDoubleOr("hx", caster.getX()),
                ctx.state().getDoubleOr("hy", caster.getY()),
                ctx.state().getDoubleOr("hz", caster.getZ()), HANG_SPEED);
            if (phaseTicks >= HANG_TICKS) {
                // The wings fold (see the class comment).
                caster.removeEffect(MobEffects.SLOW_FALLING);
                caster.removeEffect(MobEffects.LEVITATION);
                setPhase(ctx, 2);
            }
        } else if (phase == 2) {
            if (target == null) {
                setPhase(ctx, 3);
                return;
            }
            double dist = ChickenAbilitySupport.steerToward(ctx,
                target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(), DIVE_SPEED);
            if (dist <= IMPACT_DISTANCE) {
                strike(ctx, level, target);
                setPhase(ctx, 3);
            } else if (phaseTicks >= DIVE_MAX_TICKS || caster.getY() < target.getY() - 2.0) {
                setPhase(ctx, 3); // The miss: no strike, straight to the shake-off.
            }
        } else {
            caster.setDeltaMovement(caster.getDeltaMovement().scale(0.6));
            if (phaseTicks >= RECOVER_TICKS) {
                ctx.state().putInt("done", 1);
            }
        }
    }

    /** The one beak-first strike at the bottom of the dive (see the class comment). Runs exactly once. */
    private static void strike(AbilityContext ctx, ServerLevel level, LivingEntity target) {
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        double diveHeight = ctx.state().getDoubleOr("dive_h", 0.0);
        float damage = (float) Math.min(STRIKE_CAP,
            STRIKE_BASE + STRIKE_PER_BLOCK * Math.max(0.0, diveHeight - 2.0));
        target.hurtServer(level, level.damageSources().mobAttack(caster), damage);
        double cx = target.getX();
        double cy = target.getY() + target.getBbHeight() * 0.6;
        double cz = target.getZ();
        level.sendParticles(ParticleTypes.CRIT, cx, cy, cz, 10, 0.3, 0.4, 0.3, 0.15);
        level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, cx, cy, cz, 6, 0.3, 0.4, 0.3, 0.1);
        Holder<SoundEvent> cry = ChickenAbilitySupport.chickenSound(true);
        if (cry != null) {
            level.playSound(null, cx, cy, cz, cry, SoundSource.HOSTILE, 1.3f, 0.9f);
        }
    }

    private static void setPhase(AbilityContext ctx, int phase) {
        ctx.state().putInt("phase", phase);
        ctx.state().putInt("phase_ticks", 0);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.state().getIntOr("done", 0) == 1 || ctx.ticksActive() >= HARD_FINISH_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ChickenAbilitySupport.removeModifier(caster, Attributes.GRAVITY, LIGHT_ID);
        ChickenAbilitySupport.removeModifier(caster, Attributes.SAFE_FALL_DISTANCE, SOFT_ID);
        caster.removeEffect(MobEffects.LEVITATION);
        caster.removeEffect(MobEffects.SLOW_FALLING);
    }
}
