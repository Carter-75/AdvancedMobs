package mod.advancedmobs.ability.zoglin;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.DustColorTransitionOptions;
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
 * Rotten Rampage (Zoglin ability 1 of 5, mobility and attack).
 *
 * WHAT IT DOES: the zoglin lowers its rotting head and charges
 * in a lurching zigzag, filth streaming off it in motes that
 * rot from green to black. Every enemy it clips is gored and
 * left POISONED by the filth caked on its tusks. It does not
 * steer well in this state: the zigzag is fixed strides, not
 * tracking, and when it slams into a wall it stops dead, shakes
 * the impact off in a few ticks, re-aims at its target, and
 * charges on. The zigzag is what makes it hard to dodge: a
 * sidestep that beats a straight charge walks into the zag.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   zoglin's current target within 14 blocks (distance squared
 *   196) and the zoglin on the ground.
 * - Duration: at most 100 ticks (5 seconds) from start(),
 *   ending early once the zoglin has travelled 30 blocks from
 *   the charge start point.
 * - The charge: the base direction is recorded in start()
 *   (toward the target's position at that moment, or along the
 *   look direction if the target is dead center). Every tick
 *   the navigation is stopped and the horizontal velocity is
 *   set to the current direction times 0.55 blocks per tick
 *   (vertical velocity left alone, so gravity and steps behave
 *   normally). Every 12 ticks the direction rotates by 0.65
 *   radians, alternating sign (state keys "zig" and
 *   "next_switch"): the lurching zigzag. The direction is
 *   otherwise fixed between switches; the charge does not home.
 * - Wall slams: when the zoglin registers horizontalCollision
 *   during the charge, it enters a 5 tick stall (state key
 *   "stall"): horizontal velocity zeroed, navigation stopped,
 *   2 rot motes shaken off per tick. When the stall ends, the
 *   direction re-aims at the target's current position (or
 *   keeps its heading when the target is gone), the zigzag
 *   resets (sign +1, next switch 12 ticks out), and the charge
 *   resumes. A wall costs the charge about a quarter second,
 *   never the whole ability: the design's "shaking the impact
 *   off faster than anything living could".
 * - Toughness: for the whole activation the zoglin carries a
 *   KNOCKBACK_RESISTANCE add_value modifier of +0.4 (id
 *   advancedmobs:rotten_rampage_knockback, removed in end()).
 *   The zoglin's base knockback resistance is 0.6 (verified
 *   against the 26.2 jar), so the modifier brings it to the
 *   1.0 cap: nothing knocks the charging beast off its line.
 *   This is the design's KNOCKBACK_RESISTANCE entry.
 * - Clips: every charge tick (never during a stall), enemies
 *   (ZoglinAbilitySupport rules) within radius 1.8 of the
 *   zoglin (1.0 below to 2.2 above its feet) that this charge
 *   has not already clipped take 6.0 points of typed mob-attack
 *   damage credited to the zoglin, gain POISON amplifier 0 for
 *   100 ticks (the tusk filth), and are knocked along the
 *   charge direction (delta movement set to 0.9 horizontal
 *   along the line, 0.35 up, hurtMarked set so it syncs). Each
 *   enemy is clipped at most once per activation (tracked in
 *   the state tag's "hit" compound), so a victim the zigzag
 *   crosses twice is not machine-gunned; the poison is the
 *   lasting part of a clip.
 *
 * WHAT IT LOOKS LIKE: a ZOGLIN_ANGRY bellow at the start, then
 * every charge tick 3 DUST_COLOR_TRANSITION motes stream at
 * the zoglin's feet, rotting from sickly green (0x5FAE3F) to
 * black (0x0D0D0D) at scale 1.2: a visible trail of filth along
 * the zigzag. During a wall stall the same motes shake off at
 * 2 per tick while the beast stands stunned. Victims show the
 * vanilla POISON bubbles from the tusk filth.
 *
 * TERRAIN: this ability changes no blocks. The charge runs
 * over the world as it finds it, so the mobGriefing gate does
 * not apply anywhere in it.
 *
 * PERFORMANCE: the charge tick does one navigation stop, one
 * velocity write, one bounded entity query (3.6 by 3.2 by 3.6
 * box), and 3 particle sends; the stall tick does strictly
 * less. No registry or config lookups after start(). Particle
 * budget per activation: at most 100 charge ticks times 3 =
 * 300 trail motes, plus stall shakes bounded by the same 100
 * tick cap, total at most 350 particles.
 *
 * CLEANUP: end() removes the KNOCKBACK_RESISTANCE modifier.
 * If the zoglin dies mid-charge the framework termination
 * path runs this same end() on a dead mob, where the removal
 * is a harmless no-op.
 *
 * State tag keys: "dir_x" and "dir_z" (current charge
 * direction, unit length), "zig" (current zigzag sign, +1 or
 * -1), "next_switch" (tick count at which the direction next
 * rotates), "stall" (stall ticks remaining), "sx" and "sz"
 * (charge start point, for the travel cap), "hit" (compound:
 * victim UUID string to 1, everyone this charge has clipped),
 * "done" (1 once the travel cap is reached).
 */
public final class RottenRampageAbility implements MobAbility {

    static final int DURATION_TICKS = 100;
    private static final double CHARGE_SPEED = 0.55;
    private static final int ZIGZAG_INTERVAL = 12;
    private static final double ZIGZAG_ANGLE = 0.65;
    private static final int STALL_TICKS = 5;
    private static final double TRAVEL_CAP_SQR = 30.0 * 30.0;
    private static final float CLIP_DAMAGE = 6.0f;
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "rotten_rampage_knockback");
    private static final DustColorTransitionOptions ROT_MOTES =
        new DustColorTransitionOptions(0x5FAE3F, 0x0D0D0D, 1.2f);

    @Override
    public String id() {
        return "rotten_rampage";
    }

    @Override
    public String displayName() {
        return "Rotten Rampage";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().onGround()
            && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();

        // The base line: toward the target right now, or along
        // the look direction when the target is dead center.
        double dx = 0.0;
        double dz = 0.0;
        LivingEntity target = ctx.target();
        if (target != null) {
            dx = target.getX() - mob.getX();
            dz = target.getZ() - mob.getZ();
        }
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0e-4) {
            Vec3 look = mob.getLookAngle();
            dx = look.x;
            dz = look.z;
            length = Math.sqrt(dx * dx + dz * dz);
            if (length < 1.0e-4) {
                dx = 1.0;
                length = 1.0;
            }
        }
        state.putDouble("dir_x", dx / length);
        state.putDouble("dir_z", dz / length);
        state.putInt("zig", 1);
        state.putInt("next_switch", ZIGZAG_INTERVAL);
        state.putInt("stall", 0);
        state.putDouble("sx", mob.getX());
        state.putDouble("sz", mob.getZ());
        state.put("hit", new CompoundTag());
        state.putInt("done", 0);

        // Unstoppable: base 0.6 plus 0.4 is the 1.0 cap.
        ZoglinAbilitySupport.addModifier(mob, Attributes.KNOCKBACK_RESISTANCE,
            new AttributeModifier(KNOCKBACK_ID, 0.4, AttributeModifier.Operation.ADD_VALUE));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.ZOGLIN_ANGRY,
                SoundSource.HOSTILE, 1.0f, 0.9f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        int ticks = ctx.ticksActive();

        // Wall stall: stand, shake the impact off, then re-aim.
        int stall = state.getIntOr("stall", 0);
        if (stall > 0) {
            mob.getNavigation().stop();
            Vec3 current = mob.getDeltaMovement();
            mob.setDeltaMovement(0.0, current.y, 0.0);
            state.putInt("stall", stall - 1);
            if (stall - 1 == 0) {
                reaim(ctx);
            }
            ServerLevel level = ctx.serverLevel();
            if (level != null) {
                level.sendParticles(ROT_MOTES, mob.getX(), mob.getY() + 0.4, mob.getZ(),
                    2, 0.6, 0.4, 0.6, 0.05);
            }
            return;
        }

        // Zigzag: rotate the heading on schedule, alternating.
        if (ticks >= state.getIntOr("next_switch", ZIGZAG_INTERVAL)) {
            int zig = state.getIntOr("zig", 1);
            rotate(state, zig * ZIGZAG_ANGLE);
            state.putInt("zig", -zig);
            state.putInt("next_switch", ticks + ZIGZAG_INTERVAL);
        }

        // Stride: hold the current heading at charge speed.
        double dirX = state.getDoubleOr("dir_x", 1.0);
        double dirZ = state.getDoubleOr("dir_z", 0.0);
        mob.getNavigation().stop();
        Vec3 current = mob.getDeltaMovement();
        mob.setDeltaMovement(dirX * CHARGE_SPEED, current.y, dirZ * CHARGE_SPEED);
        mob.hurtMarked = true;

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            // Clips: everyone in the gore box not yet clipped by
            // this charge is gored, poisoned, and knocked along.
            CompoundTag hit = state.getCompoundOrEmpty("hit");
            List<LivingEntity> victims = ZoglinAbilitySupport.enemiesAroundSelf(ctx, 1.8, 1.0, 2.2);
            for (LivingEntity victim : victims) {
                String key = victim.getUUID().toString();
                if (hit.contains(key)) {
                    continue;
                }
                hit.putInt(key, 1);
                victim.hurtServer(level, level.damageSources().mobAttack(mob), CLIP_DAMAGE);
                victim.addEffect(new MobEffectInstance(MobEffects.POISON, 100, 0));
                victim.setDeltaMovement(dirX * 0.9, 0.35, dirZ * 0.9);
                victim.hurtMarked = true;
            }
            state.put("hit", hit);

            // The rot trail: motes streaming off the charging beast.
            level.sendParticles(ROT_MOTES, mob.getX(), mob.getY() + 0.2, mob.getZ(),
                3, 0.6, 0.3, 0.6, 0.08);
        }

        // Wall slam: the collision registers this tick, the
        // stall runs from the next.
        if (mob.horizontalCollision) {
            state.putInt("stall", STALL_TICKS);
            return;
        }

        // Travel cap: the charge has run its ground.
        double dx = mob.getX() - state.getDoubleOr("sx", mob.getX());
        double dz = mob.getZ() - state.getDoubleOr("sz", mob.getZ());
        if (dx * dx + dz * dz >= TRAVEL_CAP_SQR) {
            state.putInt("done", 1);
        }
    }

    /** Rotates the recorded charge direction by the given angle in radians. */
    private void rotate(CompoundTag state, double angle) {
        double dirX = state.getDoubleOr("dir_x", 1.0);
        double dirZ = state.getDoubleOr("dir_z", 0.0);
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        state.putDouble("dir_x", dirX * cos - dirZ * sin);
        state.putDouble("dir_z", dirX * sin + dirZ * cos);
    }

    /** Re-aims the charge at the target's current position after a wall stall. */
    private void reaim(AbilityContext ctx) {
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        LivingEntity target = ctx.target();
        if (target != null) {
            double dx = target.getX() - mob.getX();
            double dz = target.getZ() - mob.getZ();
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length > 1.0e-4) {
                state.putDouble("dir_x", dx / length);
                state.putDouble("dir_z", dz / length);
            }
        }
        state.putInt("zig", 1);
        state.putInt("next_switch", ctx.ticksActive() + ZIGZAG_INTERVAL);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS || ctx.state().getIntOr("done", 0) != 0;
    }

    @Override
    public void end(AbilityContext ctx) {
        ZoglinAbilitySupport.removeModifier(ctx.mob(), Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_ID);
    }
}
