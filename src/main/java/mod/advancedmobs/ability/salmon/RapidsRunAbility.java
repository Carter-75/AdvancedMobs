package mod.advancedmobs.ability.salmon;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
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
 * Rapids Run (Salmon ability 2 of 5, terrain).
 *
 * WHAT IT DOES: the salmon stamps out a lane of
 * rushing water marked by current_down and bubble
 * particles that flows against its enemies. Foes
 * caught in the lane are shoved backward and tumbled,
 * while the salmon swimming upstream through its own
 * rapids gains movement speed and attack damage,
 * fighting like the current itself is pushing it
 * forward.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the salmon's current target within 16 blocks
 *   (distance squared 256), plus the salmon in water.
 * - The lane (start()): a fixed corridor 12 blocks
 *   long and 3.6 wide (half width 1.8), anchored at the
 *   salmon's position and aimed horizontally at the
 *   target's position right now (the salmon's facing
 *   if there is somehow no target). Origin and unit
 *   direction live in the state tag and never move:
 *   the lane is a stamped channel, not a tracker. For
 *   the activation the salmon gains the design's two
 *   attribute lines as transient modifiers, removed
 *   in end(): MOVEMENT_SPEED +0.5 add_multiplied_base
 *   (id advancedmobs:rapids_run_speed) and
 *   ATTACK_DAMAGE +3.0 add_value (id advancedmobs:
 *   rapids_run_attack; the salmon has NO ATTACK_DAMAGE
 *   instance, so the modifier has nothing to ride,
 *   and the lane's real bite bonus below is code; the
 *   modifier is applied for the design's line through
 *   the null-tolerant helper, the Slipstream Snatch
 *   treatment). FISH_SWIM plays at volume 1.0, pitch
 *   1.1.
 * - Duration: 300 ticks (15 seconds), hard finish.
 * - The hunt (every tick): the salmon is steered at
 *   0.7 blocks per tick straight at the target's live
 *   position (velocity write via the package steering
 *   helper; vanilla fish navigation has no combat
 *   steering). The bite: within 2.0 blocks, at most
 *   once per 20 ticks, typed mob-attack damage
 *   credited to the salmon: 5.0 while the salmon
 *   itself stands inside its lane corridor (the
 *   rapids bonus, the current pushing it forward) or
 *   3.0 outside it, with 4 CRIT particles at the
 *   victim.
 * - The lane pulse (every 5 ticks): the channel is
 *   drawn at 6 points along the lane (2, 4, 6, 8, 10,
 *   and 12 blocks from the origin), 1 particle each,
 *   CURRENT_DOWN on even points and BUBBLE on odd
 *   ones, at the lane's height. One bounded query
 *   (radius 6.5 around the lane's midpoint, vertical
 *   reach 2.5 both ways) finds enemies, and each one
 *   whose projection onto the lane falls inside the
 *   corridor (along-lane distance 0 to 12, cross-lane
 *   distance at most 1.8) is shoved back: horizontal
 *   velocity set to the lane direction times 0.5 (the
 *   current carries it away from the salmon's end of
 *   the lane) with its vertical motion kept, SLOWNESS
 *   I refreshed to 30 ticks (the tumbling), hurtMarked
 *   so the shove syncs. Enemies outside the corridor
 *   are untouched, even inside the query box.
 *
 * WHAT IT LOOKS LIKE: a visible channel of sinking
 * currents and bubbles streaming across the pool,
 * everything hostile in it wallowing and sliding
 * backward, and the salmon boring upstream through
 * the middle of it at its prey.
 *
 * PERFORMANCE: per tick one steering write and at
 * most one distance check; the pulse every 5 ticks is
 * one bounded query and 6 particle sends. Particle
 * budget per activation: 60 pulses times 6 = 360
 * lane markers, at most 15 bites times 4 = 60 crits,
 * total at most 420.
 *
 * TERRAIN: this ability changes no blocks; the
 * rapids are a zone of code applied motion and
 * particles, not placed water or bubble column
 * blocks, so the mobGriefing gate does not apply
 * (see the terrain note in the package support
 * class).
 *
 * CLEANUP: end() removes both transient modifiers by
 * id. Enemy slowness rides its own 30-tick clock out;
 * the shove is a per-pulse velocity write and leaves
 * nothing behind.
 *
 * State tag keys: "ox", "oy", "oz" (the lane origin),
 * "dx", "dz" (the lane's unit direction, horizontal),
 * "last_bite" (the tick of the last bite).
 */
public final class RapidsRunAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int PULSE_INTERVAL = 5;
    private static final double LANE_LENGTH = 12.0;
    private static final double LANE_HALF_WIDTH = 1.8;
    private static final double SWIM_SPEED = 0.7;
    private static final double BITE_RANGE = 2.0;
    private static final int BITE_COOLDOWN = 20;
    private static final float BITE_DAMAGE_IN_LANE = 5.0f;
    private static final float BITE_DAMAGE_OUTSIDE = 3.0f;
    private static final double SHOVE_SPEED = 0.5;
    private static final Identifier SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "rapids_run_speed");
    private static final Identifier ATTACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "rapids_run_attack");

    @Override
    public String id() {
        return "rapids_run";
    }

    @Override
    public String displayName() {
        return "Rapids Run";
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
        LivingEntity target = ctx.target();

        // The stamped channel: origin at the salmon, aimed at the target now.
        double dx = 1.0;
        double dz = 0.0;
        if (target != null) {
            double aimX = target.getX() - caster.getX();
            double aimZ = target.getZ() - caster.getZ();
            double length = Math.sqrt(aimX * aimX + aimZ * aimZ);
            if (length > 0.001) {
                dx = aimX / length;
                dz = aimZ / length;
            }
        } else {
            dx = -Math.sin(Math.toRadians(caster.getYRot()));
            dz = Math.cos(Math.toRadians(caster.getYRot()));
        }
        state.putDouble("ox", caster.getX());
        state.putDouble("oy", caster.getY());
        state.putDouble("oz", caster.getZ());
        state.putDouble("dx", dx);
        state.putDouble("dz", dz);
        state.putInt("last_bite", -100);

        // The design's two self lines, for the activation.
        SalmonAbilitySupport.addModifier(caster, Attributes.MOVEMENT_SPEED,
            new AttributeModifier(SPEED_ID, 0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        SalmonAbilitySupport.addModifier(caster, Attributes.ATTACK_DAMAGE,
            new AttributeModifier(ATTACK_ID, 3.0, AttributeModifier.Operation.ADD_VALUE));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.FISH_SWIM, SoundSource.HOSTILE, 1.0f, 1.1f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        int ticks = ctx.ticksActive();
        LivingEntity target = ctx.target();

        // The upstream hunt: steer straight at the target every tick.
        if (target != null && target.isAlive()) {
            SalmonAbilitySupport.steerToward(caster, target.getX(), target.getY() + 0.3, target.getZ(), SWIM_SPEED);
            if (caster.distanceToSqr(target) <= BITE_RANGE * BITE_RANGE
                && ticks - ctx.state().getIntOr("last_bite", -100) >= BITE_COOLDOWN) {
                ctx.state().putInt("last_bite", ticks);
                float damage = insideLane(ctx, caster.getX(), caster.getZ())
                    ? BITE_DAMAGE_IN_LANE : BITE_DAMAGE_OUTSIDE;
                target.hurtServer(level, level.damageSources().mobAttack(caster), damage);
                level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY() + 0.4, target.getZ(),
                    4, 0.3, 0.3, 0.3, 0.05);
            }
        }

        if (ticks % PULSE_INTERVAL == 0) {
            runLanePulse(ctx, level);
        }
    }

    /** One lane pulse: draw the channel and shove every enemy standing in it. */
    private void runLanePulse(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        double ox = state.getDoubleOr("ox", 0.0);
        double oy = state.getDoubleOr("oy", 0.0);
        double oz = state.getDoubleOr("oz", 0.0);
        double dx = state.getDoubleOr("dx", 1.0);
        double dz = state.getDoubleOr("dz", 0.0);

        for (int i = 1; i <= 6; i++) {
            double along = i * 2.0;
            level.sendParticles(i % 2 == 0 ? ParticleTypes.CURRENT_DOWN : ParticleTypes.BUBBLE,
                ox + dx * along, oy + 0.3, oz + dz * along,
                1, 0.4, 0.3, 0.4, 0.02);
        }

        double midX = ox + dx * (LANE_LENGTH / 2.0);
        double midZ = oz + dz * (LANE_LENGTH / 2.0);
        List<LivingEntity> found = SalmonAbilitySupport.enemiesInBox(ctx, midX, oy, midZ,
            LANE_LENGTH / 2.0 + 0.5, 2.5, 2.5);
        for (LivingEntity enemy : found) {
            if (!insideLane(ctx, enemy.getX(), enemy.getZ())) {
                continue;
            }
            enemy.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 30, 0, false, false, true));
            enemy.setDeltaMovement(dx * SHOVE_SPEED, enemy.getDeltaMovement().y, dz * SHOVE_SPEED);
            enemy.hurtMarked = true;
        }
    }

    /** Whether the given horizontal point lies inside the stamped lane corridor. */
    private boolean insideLane(AbilityContext ctx, double x, double z) {
        CompoundTag state = ctx.state();
        double relX = x - state.getDoubleOr("ox", 0.0);
        double relZ = z - state.getDoubleOr("oz", 0.0);
        double dx = state.getDoubleOr("dx", 1.0);
        double dz = state.getDoubleOr("dz", 0.0);
        double along = relX * dx + relZ * dz;
        double across = Math.abs(relX * dz - relZ * dx);
        return along >= 0.0 && along <= LANE_LENGTH && across <= LANE_HALF_WIDTH;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        SalmonAbilitySupport.removeModifier(caster, Attributes.MOVEMENT_SPEED, SPEED_ID);
        SalmonAbilitySupport.removeModifier(caster, Attributes.ATTACK_DAMAGE, ATTACK_ID);
    }
}
