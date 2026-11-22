package mod.advancedmobs.ability.ocelot;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.BlockParticleOption;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Treefall Ambush (Ocelot ability 3 of 5, mobility /
 * attack).
 *
 * WHAT IT DOES: the ocelot scrambles straight up as if up
 * a tree trunk, hangs in the canopy for a heartbeat
 * directly over its target, then drops on it from above.
 * The fall cannot hurt the ocelot at all, and the impact
 * deals heavy damage that scales with how far it fell,
 * bursting leaves and crumbling earth where it lands.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the ocelot's current target within 12 blocks
 *   (distance squared 144), plus the ocelot on the ground
 *   (a treefall starts from the ground, not mid-air).
 * - The fall immunity (start() through end()): two
 *   transient attribute modifiers, the design's named
 *   attributes, removed exactly in end(): SAFE_FALL_DISTANCE
 *   +100 add_value (id advancedmobs:treefall_safe_fall) and
 *   FALL_DAMAGE_MULTIPLIER -1.0 add_multiplied_total (id
 *   advancedmobs:treefall_fall_multiplier), which zeroes
 *   the fall damage multiplier outright. Between the two,
 *   vanilla fall damage can never touch the ocelot during
 *   this ability, however far it drops: it ignores the
 *   fall entirely. start() also records the launch height.
 * - Phase 0, the scramble (start() to the rise check): the
 *   ocelot gains LEVITATION II (amplifier 1) for 40 ticks
 *   and rises straight up, navigation stopped, shedding 2
 *   TINTED_LEAVES every 2 ticks. The phase ends when the
 *   ocelot has gained at least 6.0 blocks over its launch
 *   height, or at tick 30 if a ceiling stops it lower (the
 *   ambush still runs from whatever height it reached).
 *   The levitation is then removed early.
 * - Phase 1, the perch (10 ticks): the ocelot hangs over
 *   the fight. Each tick its velocity is written directly:
 *   vertical exactly 0 (it holds its height), horizontal
 *   0.3 blocks per tick toward its live target's position,
 *   so the perch drifts until it hangs over the target.
 *   If the target is gone, it holds its position. 2
 *   TINTED_LEAVES drift down every 2 ticks.
 * - Phase 2, the drop (from the perch's end): the ocelot's
 *   height at the drop is recorded ("drop_y") and one
 *   downward impulse of -0.5 starts the fall; gravity does
 *   the rest. The ocelot does not steer during the drop.
 * - The impact (the first phase 2 tick where the ocelot is
 *   back on the ground): the drop height is the recorded
 *   drop_y minus the landing height. Impact damage is 4.0
 *   plus 1.0 for every block dropped beyond the first 3,
 *   capped at 14.0 (a full 6 block perch plus the drift
 *   typically lands at 7.0 to 9.0; a ceiling perch at 3
 *   blocks lands at the 4.0 floor). One bounded radius-3.0
 *   query around the landing point: every enemy in it
 *   takes the impact damage through a mob attack source
 *   credited to the ocelot (the design's /damage scaled by
 *   drop height in code). The look: 12 BLOCK_CRUMBLE motes
 *   of the ground block state under the landing point, 10
 *   TINTED_LEAVES, and OCELOT_HURT at pitch 0.9. The
 *   activation ends 5 ticks after the impact.
 * - Duration: hard cap 120 ticks (a drop that never lands,
 *   off a cliff edge, still ends, and the fall immunity
 *   modifiers are still removed by end()).
 * - end(): both fall modifiers are removed and any
 *   leftover levitation is stripped. Idempotent.
 *
 * WHAT IT LOOKS LIKE: the ocelot shoots upward in a spiral
 * of green leaves, hangs impossibly still over its target
 * for a heartbeat while leaves drift down around it, then
 * plummets. Where it lands, earth crumbles and leaves burst
 * outward, everything near the point of impact is hammered,
 * and the ocelot walks out of its own crater unhurt.
 *
 * PERFORMANCE: canTrigger is one distance check and one
 * onGround check. The scramble is one height read and at
 * most 2 particles every 2 ticks; the perch is one velocity
 * write per tick; the drop is one onGround check per tick;
 * the impact is one bounded radius-3.0 query, once. The
 * leaf option is a pre-built static final in the support
 * class; the crumble option is built once, at the impact.
 * Particle budget per activation: scramble at most 15
 * times 2 = 30, perch 5 times 2 = 10, impact 22, total at
 * most 62 particles.
 *
 * TERRAIN: this ability changes no blocks; the
 * BLOCK_CRUMBLE burst is a particle carrying the ground
 * state, not a block change, so the mobGriefing gate does
 * not apply.
 *
 * State tag keys: "phase" (0 scrambling, 1 perched, 2
 * dropping, 3 impacted), "launch_y" (the ocelot's height
 * when the scramble began), "perch_tick" (the tick the
 * perch began), "drop_y" (the ocelot's height when the
 * drop began), "land_tick" (the tick the impact landed).
 */
public final class TreefallAmbushAbility implements MobAbility {

    private static final int RISE_TICKS_MAX = 30;
    private static final double RISE_HEIGHT = 6.0;
    private static final int PERCH_TICKS = 10;
    private static final int IMPACT_LINGER_TICKS = 5;
    private static final int HARD_CAP_TICKS = 120;
    private static final double BASE_IMPACT_DAMAGE = 4.0;
    private static final double FREE_DROP_BLOCKS = 3.0;
    private static final double MAX_IMPACT_DAMAGE = 14.0;
    private static final Identifier SAFE_FALL_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "treefall_safe_fall");
    private static final Identifier FALL_MULT_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "treefall_fall_multiplier");

    @Override
    public String id() {
        return "treefall_ambush";
    }

    @Override
    public String displayName() {
        return "Treefall Ambush";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0 && ctx.mob().onGround();
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ctx.state().putInt("phase", 0);
        ctx.state().putDouble("launch_y", caster.getY());
        // The fall immunity: the design's two attributes,
        // held for the whole activation, removed in end().
        OcelotAbilitySupport.addModifier(caster, Attributes.SAFE_FALL_DISTANCE,
            new AttributeModifier(SAFE_FALL_ID, 100.0, AttributeModifier.Operation.ADD_VALUE));
        OcelotAbilitySupport.addModifier(caster, Attributes.FALL_DAMAGE_MULTIPLIER,
            new AttributeModifier(FALL_MULT_ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        // The scramble: a burst of levitation straight up.
        caster.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 40, 1));
        caster.getNavigation().stop();
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        int ticks = ctx.ticksActive();
        int phase = ctx.state().getIntOr("phase", 0);
        ServerLevel level = ctx.serverLevel();
        switch (phase) {
            case 0 -> {
                // The scramble: rise until the height or the clock says perch.
                caster.getNavigation().stop();
                if (level != null && ticks % 2 == 0) {
                    level.sendParticles(OcelotAbilitySupport.LEAF, caster.getX(), caster.getY() + 0.3, caster.getZ(),
                        2, 0.4, 0.3, 0.4, 0.1);
                }
                double gained = caster.getY() - ctx.state().getDoubleOr("launch_y", caster.getY());
                if (gained >= RISE_HEIGHT || ticks >= RISE_TICKS_MAX) {
                    caster.removeEffect(MobEffects.LEVITATION);
                    ctx.state().putInt("perch_tick", ticks);
                    ctx.state().putInt("phase", 1);
                }
            }
            case 1 -> {
                // The perch: hold height, drift until over the target.
                LivingEntity target = ctx.target();
                double driftX = 0.0;
                double driftZ = 0.0;
                if (target != null) {
                    double dx = target.getX() - caster.getX();
                    double dz = target.getZ() - caster.getZ();
                    double length = Math.sqrt(dx * dx + dz * dz);
                    if (length > 0.3) {
                        driftX = dx / length * 0.3;
                        driftZ = dz / length * 0.3;
                    }
                }
                caster.setDeltaMovement(driftX, 0.0, driftZ);
                caster.hurtMarked = true;
                if (level != null && ticks % 2 == 0) {
                    level.sendParticles(OcelotAbilitySupport.LEAF, caster.getX(), caster.getY() + 0.2, caster.getZ(),
                        2, 0.4, 0.2, 0.4, 0.1);
                }
                if (ticks - ctx.state().getIntOr("perch_tick", 0) >= PERCH_TICKS) {
                    ctx.state().putDouble("drop_y", caster.getY());
                    caster.setDeltaMovement(0.0, -0.5, 0.0);
                    caster.hurtMarked = true;
                    ctx.state().putInt("phase", 2);
                }
            }
            case 2 -> {
                // The drop: gravity does the work until the ground arrives.
                if (caster.onGround()) {
                    impact(ctx, ticks);
                }
            }
            default -> {
                // Phase 3: the impact has landed; the linger runs out the clock.
            }
        }
    }

    /** The impact: height-scaled area damage and the leaf and crumble burst. */
    private void impact(AbilityContext ctx, int ticks) {
        Mob caster = ctx.mob();
        ctx.state().putInt("land_tick", ticks);
        ctx.state().putInt("phase", 3);
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        double dropped = ctx.state().getDoubleOr("drop_y", caster.getY()) - caster.getY();
        double damage = Math.min(MAX_IMPACT_DAMAGE,
            BASE_IMPACT_DAMAGE + Math.max(0.0, dropped - FREE_DROP_BLOCKS));
        // One bounded query: everything at the landing point takes the fall.
        for (LivingEntity enemy : OcelotAbilitySupport.enemiesAroundSelf(ctx, 3.0, 2.0, 2.0)) {
            enemy.hurtServer(level, level.damageSources().mobAttack(caster), (float) damage);
        }
        BlockState ground = level.getBlockState(caster.blockPosition().below());
        BlockState crumbleState = ground.isAir() ? Blocks.DIRT.defaultBlockState() : ground;
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, crumbleState),
            caster.getX(), caster.getY() + 0.2, caster.getZ(), 12, 0.6, 0.2, 0.6, 0.1);
        level.sendParticles(OcelotAbilitySupport.LEAF, caster.getX(), caster.getY() + 0.5, caster.getZ(),
            10, 0.6, 0.4, 0.6, 0.2);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.OCELOT_HURT, SoundSource.HOSTILE, 1.0f, 0.9f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks >= HARD_CAP_TICKS) {
            return true;
        }
        return ctx.state().getIntOr("phase", 0) == 3
            && ticks - ctx.state().getIntOr("land_tick", 0) >= IMPACT_LINGER_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        OcelotAbilitySupport.removeModifier(caster, Attributes.SAFE_FALL_DISTANCE, SAFE_FALL_ID);
        OcelotAbilitySupport.removeModifier(caster, Attributes.FALL_DAMAGE_MULTIPLIER, FALL_MULT_ID);
        caster.removeEffect(MobEffects.LEVITATION);
    }
}
