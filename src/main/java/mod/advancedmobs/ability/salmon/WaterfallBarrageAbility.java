package mod.advancedmobs.ability.salmon;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.List;

/**
 * Waterfall Barrage (Salmon ability 5 of 5, attack).
 *
 * WHAT IT DOES: the salmon hurls itself at the enemy
 * again and again from alternating sides, each pass a
 * streak of bubble and crit particles, too fast to
 * track. Every third pass ends in a tail slap that
 * deals sweeping damage to everything clustered
 * around the target.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the salmon's current target within 16 blocks
 *   (distance squared 256), plus the salmon in water.
 * - The pass kit (start()): the design's two attribute
 *   lines as transient modifiers, removed in end():
 *   SWEEPING_DAMAGE_RATIO +1.0 add_value (id
 *   advancedmobs:waterfall_barrage_sweep; a salmon
 *   has no vanilla melee swing for the ratio to ride,
 *   so it is applied for the design's line through the
 *   null-tolerant helper and the tail slap's real
 *   sweep below is code) and ATTACK_SPEED +0.5
 *   add_multiplied_base (id advancedmobs:waterfall_
 *   barrage_haste; likewise no vanilla attack cycle
 *   exists on a fish for it to speed up, documented
 *   inert the Grand Hex Form way: the pass timing
 *   below is code).
 * - The passes (tick()): 5 passes. Each pass runs in
 *   two beats. Beat 1 (attack): the salmon is steered
 *   at 1.1 blocks per tick straight at the target's
 *   live position (velocity write via the package
 *   steering helper), trailing 2 BUBBLE particles per
 *   tick. The strike lands the first tick the salmon
 *   is within 1.8 blocks of the target:
 *   - Normal pass: the target takes 3.0 flat typed
 *     mob-attack damage credited to the salmon, with
 *     6 CRIT and 6 BUBBLE at the target and
 *     SALMON_HURT at volume 1.0, pitch 1.3.
 *   - Every third pass (passes 3 and, if reached, the
 *     count continues so pass 6 would be one too, but
 *     the barrage stops at 5, so in practice pass 3):
 *     the tail slap. One bounded query, radius 3.0
 *     around the target's position, vertical reach
 *     2.0 both ways: every enemy (the package enemy
 *     rule) takes 4.0 flat damage credited to the
 *     salmon plus a knockback of 0.8 horizontally away
 *     from the target and 0.3 up, hurtMarked, with 8
 *     SWEEP_ATTACK and 8 BUBBLE bursting at the
 *     target: the design's sweeping hit, in code.
 *   Beat 2 (regroup): for 14 ticks after a strike the
 *   salmon steers at 0.9 blocks per tick to a staging
 *   point beside the target: 5 blocks out along the
 *   perpendicular of the salmon-to-target axis, 1.5
 *   above the target, the side alternating per pass
 *   (even passes stage left, odd passes right: the
 *   design's alternating sides), still trailing 2
 *   BUBBLE per tick. Then the next pass begins.
 * - The barrage ends when the 5th pass's regroup has
 *   run its 14 ticks, when the target dies or is lost
 *   (checked each tick; a lost target ends the
 *   barrage at once), or at the 240 tick hard cap.
 *
 * WHAT IT LOOKS LIKE: a bubble-streaked silver dart
 * slamming into the target from one side, wheeling
 * out wide, and slamming in from the other side,
 * again and again, with the middle pass detonating
 * into a sweeping tail slap that throws the whole
 * cluster back.
 *
 * PERFORMANCE: per tick one steering write, one
 * distance check, and 2 particles; the tail slap is
 * one bounded query, once per third pass. Particle
 * budget per activation: at most 240 ticks times 2 =
 * 480 trail, strikes at most 5 times 12 = 60, tail
 * slaps at most 1 times 16 = 16, total at most 556.
 *
 * TERRAIN: this ability changes no blocks; the gate
 * does not apply.
 *
 * CLEANUP: end() removes both transient modifiers by
 * id. Nothing else persists.
 *
 * State tag keys: "passes" (strikes landed), "struck"
 * (the current pass has struck and is regrouping),
 * "strike_tick" (the tick of the current pass's
 * strike), "done" (the target was lost).
 */
public final class WaterfallBarrageAbility implements MobAbility {

    private static final int MAX_PASSES = 5;
    private static final int HARD_CAP_TICKS = 240;
    private static final int REGROUP_TICKS = 14;
    private static final double PASS_SPEED = 1.1;
    private static final double REGROUP_SPEED = 0.9;
    private static final double STRIKE_RANGE = 1.8;
    private static final float PASS_DAMAGE = 3.0f;
    private static final float SLAP_DAMAGE = 4.0f;
    private static final double SLAP_RADIUS = 3.0;
    private static final double STAGE_DISTANCE = 5.0;
    private static final Identifier SWEEP_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "waterfall_barrage_sweep");
    private static final Identifier HASTE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "waterfall_barrage_haste");

    @Override
    public String id() {
        return "waterfall_barrage";
    }

    @Override
    public String displayName() {
        return "Waterfall Barrage";
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
        CompoundTag state = ctx.state();
        state.putInt("passes", 0);
        state.putBoolean("struck", false);
        state.putInt("strike_tick", -100);
        state.putBoolean("done", false);

        // The design's two attribute lines, for the barrage.
        SalmonAbilitySupport.addModifier(caster, Attributes.SWEEPING_DAMAGE_RATIO,
            new AttributeModifier(SWEEP_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        SalmonAbilitySupport.addModifier(caster, Attributes.ATTACK_SPEED,
            new AttributeModifier(HASTE_ID, 0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        int ticks = ctx.ticksActive();
        LivingEntity target = ctx.target();
        if (target == null || !target.isAlive()) {
            state.putBoolean("done", true);
            return;
        }

        int passes = state.getIntOr("passes", 0);
        boolean struck = state.getBooleanOr("struck", false);

        // The streak: bubble trail on every moving tick.
        level.sendParticles(ParticleTypes.BUBBLE,
            caster.getX(), caster.getY() + 0.3, caster.getZ(),
            2, 0.25, 0.25, 0.25, 0.02);

        if (!struck) {
            SalmonAbilitySupport.steerToward(caster, target.getX(), target.getY() + 0.3, target.getZ(), PASS_SPEED);
            if (caster.distanceToSqr(target) <= STRIKE_RANGE * STRIKE_RANGE) {
                strike(ctx, level, passes + 1, target);
            }
            return;
        }

        // Regroup: wheel out to the staging point on the alternating side.
        if (ticks - state.getIntOr("strike_tick", -100) >= REGROUP_TICKS) {
            state.putBoolean("struck", false);
            return;
        }
        double axisX = target.getX() - caster.getX();
        double axisZ = target.getZ() - caster.getZ();
        double axisLength = Math.sqrt(axisX * axisX + axisZ * axisZ);
        double perpX = axisLength > 0.001 ? -axisZ / axisLength : 1.0;
        double perpZ = axisLength > 0.001 ? axisX / axisLength : 0.0;
        double side = passes % 2 == 0 ? 1.0 : -1.0;
        SalmonAbilitySupport.steerToward(caster,
            target.getX() + perpX * STAGE_DISTANCE * side,
            target.getY() + 1.5,
            target.getZ() + perpZ * STAGE_DISTANCE * side,
            REGROUP_SPEED);
    }

    /** One strike: a normal pass hit, or the tail slap on every third pass. */
    private void strike(AbilityContext ctx, ServerLevel level, int passNumber, LivingEntity target) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        state.putInt("passes", passNumber);
        state.putBoolean("struck", true);
        state.putInt("strike_tick", ctx.ticksActive());

        if (passNumber % 3 != 0) {
            target.hurtServer(level, level.damageSources().mobAttack(caster), PASS_DAMAGE);
            level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY() + 0.4, target.getZ(),
                6, 0.3, 0.3, 0.3, 0.05);
            level.sendParticles(ParticleTypes.BUBBLE, target.getX(), target.getY() + 0.4, target.getZ(),
                6, 0.3, 0.3, 0.3, 0.05);
            level.playSound(null, target.getX(), target.getY(), target.getZ(),
                SoundEvents.SALMON_HURT, SoundSource.HOSTILE, 1.0f, 1.3f);
            return;
        }

        // The tail slap: everything clustered around the target takes the sweep.
        List<LivingEntity> victims = SalmonAbilitySupport.enemiesInBox(ctx,
            target.getX(), target.getY(), target.getZ(), SLAP_RADIUS, 2.0, 2.0);
        for (LivingEntity victim : victims) {
            victim.hurtServer(level, level.damageSources().mobAttack(caster), SLAP_DAMAGE);
            double awayX = victim.getX() - target.getX();
            double awayZ = victim.getZ() - target.getZ();
            double length = Math.sqrt(awayX * awayX + awayZ * awayZ);
            if (length > 0.001) {
                victim.setDeltaMovement(awayX / length * 0.8, 0.3, awayZ / length * 0.8);
            } else {
                victim.setDeltaMovement(0.0, 0.3, 0.0);
            }
            victim.hurtMarked = true;
        }
        level.sendParticles(ParticleTypes.SWEEP_ATTACK, target.getX(), target.getY() + 0.4, target.getZ(),
            8, 0.5, 0.3, 0.5, 0.05);
        level.sendParticles(ParticleTypes.BUBBLE, target.getX(), target.getY() + 0.4, target.getZ(),
            8, 0.4, 0.3, 0.4, 0.05);
        level.playSound(null, target.getX(), target.getY(), target.getZ(),
            SoundEvents.SALMON_HURT, SoundSource.HOSTILE, 1.2f, 1.0f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        if (state.getBooleanOr("done", false) || ctx.ticksActive() >= HARD_CAP_TICKS) {
            return true;
        }
        return state.getIntOr("passes", 0) >= MAX_PASSES
            && state.getBooleanOr("struck", false)
            && ctx.ticksActive() - state.getIntOr("strike_tick", -100) >= REGROUP_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        SalmonAbilitySupport.removeModifier(caster, Attributes.SWEEPING_DAMAGE_RATIO, SWEEP_ID);
        SalmonAbilitySupport.removeModifier(caster, Attributes.ATTACK_SPEED, HASTE_ID);
    }
}
