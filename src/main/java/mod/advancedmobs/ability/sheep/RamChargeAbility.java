package mod.advancedmobs.ability.sheep;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.DustParticleOptions;
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
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Ram Charge (Sheep ability 2 of 5, attack, mobility).
 *
 * WHAT IT DOES: the sheep lowers its head and paws the
 * ground, dust puffing, then charges horn first in a
 * speed burst. The impact lands with sweep attack
 * particles and huge knockback, and while the ability
 * lasts the sheep turns and charges again and again: a
 * farm animal running a one-sheep demolition loop.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the sheep on the ground and its current target
 *   within 16 blocks (distance squared 256).
 * - Duration: up to 300 ticks (15 seconds) of repeated
 *   charges; it ends early when a recover phase finishes
 *   with no living target within 20 blocks (distance
 *   squared 400) to aim the next charge at.
 * - Charge kit (start(), removed exactly in end()):
 *   transient ATTACK_KNOCKBACK add_value +3.0 (id
 *   advancedmobs:ram_charge_knockback; the design's
 *   ATTACK_KNOCKBACK line, so any vanilla contact the
 *   sheep makes mid-activation hits at the boosted
 *   value), transient MOVEMENT_SPEED add_multiplied_base
 *   +0.5 (id advancedmobs:ram_charge_speed; the design's
 *   MOVEMENT_SPEED line, felt in the recover turns
 *   between charges), and SPEED I as an effect for 320
 *   ticks. The charge itself is code velocity and does
 *   not depend on any of these.
 * - Phase 0, the pawing (10 ticks per cycle): navigation
 *   stopped every tick (never NoAi), 3 tan DUST motes at
 *   the sheep's front every 2 ticks, SHEEP_AMBIENT at
 *   volume 1.0, pitch 0.9 at the first pawing only. At
 *   the end of the pawing the charge line locks: the
 *   horizontal direction to the target's live position
 *   ("dx", "dz", normalized; the sheep's look direction
 *   when the target is exactly overhead), the start
 *   position is recorded ("sx", "sy", "sz"), and the
 *   sheep is yawed onto the line.
 * - Phase 1, the charge (at most 25 ticks): navigation
 *   stopped, horizontal velocity written along the
 *   locked direction at 0.9 blocks per tick, vertical
 *   left to gravity so the charge follows the ground, 2
 *   tan DUST motes at the hooves per tick.
 * - The impact (the first enemy found by the per-tick
 *   radius-2.0 hit check, 1.0 below to 2.0 above): 5.0
 *   flat damage as a mob attack credited to the sheep
 *   (the sheep has no ATTACK_DAMAGE attribute instance;
 *   see SheepAbilitySupport), plus the launch: 2.0
 *   horizontal along the charge direction and 0.55 up,
 *   hurtMarked set, the huge knockback of the design.
 *   6 SWEEP_ATTACK particles fan at the victim and
 *   PLAYER_ATTACK_SWEEP plays at volume 1.0. One victim
 *   per charge: the impact ends the charge.
 * - Charge stop conditions (first one wins): the impact
 *   connects; horizontalCollision (a wall); 20 blocks
 *   travelled from the charge start; the sheep falls 5
 *   or more blocks below the charge start height; 25
 *   charge ticks run out.
 * - Phase 2, the recover (20 ticks): horizontal velocity
 *   is damped to a fifth once at phase entry; the first
 *   10 ticks hold position (navigation stopped), then
 *   the sheep is free to move (its own AI, sped by the
 *   charge kit) while it turns for the next run. When
 *   the recover ends: if a living target is within 20
 *   blocks, a fresh pawing phase begins; otherwise the
 *   ability sets its "done" flag and finishes.
 * - end(): the two modifiers are removed and the SPEED
 *   effect is removed.
 *
 * WHAT IT LOOKS LIKE: a sheep scraping dust with one
 * hoof, head down, then a woolly blur on a straight line,
 * a crack of swept air as the victim sails, a skidding
 * turn, and the whole thing again from a new angle.
 *
 * PERFORMANCE: the pawing and recover phases do one
 * navigation stop and at most one particle emission
 * every 2 ticks. The charge does one velocity write and
 * one bounded radius-2.0 entity query per tick. Particle
 * budget per activation: at most 5 charge cycles fit in
 * 300 ticks; per cycle pawing 15, charge at most 50,
 * impact 6, so at most about 355 particles total.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply. The dust is a particle, not a placed block.
 *
 * State tag keys: "phase" (0 pawing, 1 charging, 2
 * recover), "phase_start" (tick the current phase
 * began), "charge_start" (tick the current charge
 * began), "dx", "dz" (locked charge direction), "sx",
 * "sy", "sz" (charge start position), "pawed" (1 once
 * the opening baa has played), "done" (1 when the loop
 * has run out of targets).
 */
public final class RamChargeAbility implements MobAbility {

    private static final int HARD_CAP_TICKS = 300;
    private static final int PAW_TICKS = 10;
    private static final int CHARGE_MAX_TICKS = 25;
    private static final int RECOVER_TICKS = 20;
    private static final double CHARGE_SPEED = 0.9;
    private static final double MAX_TRAVEL_SQR = 400.0; // 20 blocks
    private static final double FALL_STOP_DROP = 5.0;
    private static final double HIT_RADIUS = 2.0;
    private static final double IMPACT_DAMAGE = 5.0;
    private static final double LAUNCH_HORIZONTAL = 2.0;
    private static final double LAUNCH_VERTICAL = 0.55;
    private static final double NEXT_CHARGE_RANGE_SQR = 400.0; // 20 blocks
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "ram_charge_knockback");
    private static final Identifier SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "ram_charge_speed");
    private static final DustParticleOptions PAW_DUST =
        new DustParticleOptions(0xC2B280, 1.0f);

    @Override
    public String id() {
        return "ram_charge";
    }

    @Override
    public String displayName() {
        return "Ram Charge";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().onGround()
            && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        state.putInt("phase", 0);
        state.putInt("phase_start", 0);
        state.putInt("charge_start", 0);
        state.putInt("pawed", 0);
        state.putInt("done", 0);
        state.putDouble("dx", 0.0);
        state.putDouble("dz", 1.0);
        state.putDouble("sx", caster.getX());
        state.putDouble("sy", caster.getY());
        state.putDouble("sz", caster.getZ());

        SheepAbilitySupport.addModifier(caster, Attributes.ATTACK_KNOCKBACK,
            new AttributeModifier(KNOCKBACK_ID, 3.0, AttributeModifier.Operation.ADD_VALUE));
        SheepAbilitySupport.addModifier(caster, Attributes.MOVEMENT_SPEED,
            new AttributeModifier(SPEED_ID, 0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        caster.addEffect(new MobEffectInstance(MobEffects.SPEED, 320, 0));
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        CompoundTag state = ctx.state();
        int ticks = ctx.ticksActive();
        int phase = state.getIntOr("phase", 0);
        int phaseStart = state.getIntOr("phase_start", 0);

        if (phase == 0) {
            // The pawing: rooted, dust puffing at the front.
            caster.getNavigation().stop();
            if (state.getIntOr("pawed", 0) == 0) {
                state.putInt("pawed", 1);
                if (level != null) {
                    level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                        SoundEvents.SHEEP_AMBIENT, SoundSource.HOSTILE, 1.0f, 0.9f);
                }
            }
            if (level != null && ticks % 2 == 0) {
                Vec3 look = caster.getLookAngle();
                level.sendParticles(PAW_DUST,
                    caster.getX() + look.x * 0.7, caster.getY() + 0.15, caster.getZ() + look.z * 0.7,
                    3, 0.25, 0.1, 0.25, 0.05);
            }
            if (ticks - phaseStart >= PAW_TICKS) {
                lockChargeLine(ctx);
                state.putInt("phase", 1);
                state.putInt("charge_start", ticks);
            }
        } else if (phase == 1) {
            chargeTick(ctx, level, ticks);
        } else {
            // The recover: skid, hold, then turn free.
            if (ticks - phaseStart < 10) {
                caster.getNavigation().stop();
            }
            if (ticks - phaseStart >= RECOVER_TICKS) {
                LivingEntity target = ctx.target();
                if (target != null && caster.distanceToSqr(target) <= NEXT_CHARGE_RANGE_SQR) {
                    state.putInt("phase", 0);
                    state.putInt("phase_start", ticks);
                } else {
                    state.putInt("done", 1);
                }
            }
        }
    }

    /** Locks the charge line at the target's live position (see the class comment). */
    private void lockChargeLine(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        state.putDouble("sx", caster.getX());
        state.putDouble("sy", caster.getY());
        state.putDouble("sz", caster.getZ());
        double dx = 0.0;
        double dz = 1.0;
        LivingEntity target = ctx.target();
        if (target != null) {
            dx = target.getX() - caster.getX();
            dz = target.getZ() - caster.getZ();
        }
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.001) {
            Vec3 look = caster.getLookAngle();
            dx = look.x;
            dz = look.z;
            length = Math.sqrt(dx * dx + dz * dz);
            if (length < 0.001) {
                dx = 0.0;
                dz = 1.0;
                length = 1.0;
            }
        }
        state.putDouble("dx", dx / length);
        state.putDouble("dz", dz / length);
        caster.setYRot((float) Math.toDegrees(Math.atan2(-(dx / length), dz / length)));
    }

    /** One charge tick: drive, spray, hit check, stop conditions. */
    private void chargeTick(AbilityContext ctx, ServerLevel level, int ticks) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        double dx = state.getDoubleOr("dx", 0.0);
        double dz = state.getDoubleOr("dz", 1.0);

        // Stop conditions that end the charge with no impact.
        double travelledX = caster.getX() - state.getDoubleOr("sx", caster.getX());
        double travelledZ = caster.getZ() - state.getDoubleOr("sz", caster.getZ());
        if (caster.horizontalCollision
            || travelledX * travelledX + travelledZ * travelledZ >= MAX_TRAVEL_SQR
            || caster.getY() < state.getDoubleOr("sy", caster.getY()) - FALL_STOP_DROP
            || ticks - state.getIntOr("charge_start", 0) >= CHARGE_MAX_TICKS) {
            enterRecover(ctx, ticks);
            return;
        }

        // The drive: code velocity along the locked line.
        caster.getNavigation().stop();
        Vec3 velocity = caster.getDeltaMovement();
        caster.setDeltaMovement(dx * CHARGE_SPEED, velocity.y, dz * CHARGE_SPEED);
        caster.hurtMarked = true;

        if (level != null) {
            level.sendParticles(PAW_DUST, caster.getX(), caster.getY() + 0.2, caster.getZ(),
                2, 0.45, 0.1, 0.45, 0.08);
            // The impact: the first enemy in the plow box takes it.
            List<LivingEntity> victims = SheepAbilitySupport.enemiesInBox(
                ctx, caster.getX(), caster.getY(), caster.getZ(), HIT_RADIUS, 1.0, 2.0);
            if (!victims.isEmpty()) {
                impact(ctx, level, victims.get(0), dx, dz);
                enterRecover(ctx, ticks);
            }
        }
    }

    /** The impact itself: flat damage and the huge launch. */
    private void impact(AbilityContext ctx, ServerLevel level, LivingEntity victim, double dx, double dz) {
        Mob caster = ctx.mob();
        victim.hurtServer(level, level.damageSources().mobAttack(caster), (float) IMPACT_DAMAGE);
        victim.setDeltaMovement(dx * LAUNCH_HORIZONTAL, LAUNCH_VERTICAL, dz * LAUNCH_HORIZONTAL);
        victim.hurtMarked = true;
        level.sendParticles(ParticleTypes.SWEEP_ATTACK, victim.getX(), victim.getY() + 0.8, victim.getZ(),
            6, 0.4, 0.4, 0.4, 0.05);
        level.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
            SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 1.0f, 1.0f);
    }

    /** Ends the charge: damp the velocity and start the recover clock. */
    private void enterRecover(AbilityContext ctx, int ticks) {
        Mob caster = ctx.mob();
        ctx.state().putInt("phase", 2);
        ctx.state().putInt("phase_start", ticks);
        Vec3 velocity = caster.getDeltaMovement();
        caster.setDeltaMovement(velocity.x * 0.2, velocity.y, velocity.z * 0.2);
        caster.hurtMarked = true;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= HARD_CAP_TICKS
            || ctx.state().getIntOr("done", 0) == 1;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        SheepAbilitySupport.removeModifier(caster, Attributes.ATTACK_KNOCKBACK, KNOCKBACK_ID);
        SheepAbilitySupport.removeModifier(caster, Attributes.MOVEMENT_SPEED, SPEED_ID);
        caster.removeEffect(MobEffects.SPEED);
    }
}
