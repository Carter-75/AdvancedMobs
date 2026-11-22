package mod.advancedmobs.ability.polarbear;

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
import net.minecraft.world.entity.animal.polarbear.PolarBear;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Ursa Major (Polar Bear ability 5 of 5, transformation).
 *
 * WHAT IT DOES: the bear rises to its full height and
 * keeps growing, fur frosted white at the tips, breath
 * rolling out in CLOUD puffs with every snort. In this
 * form it is a siege engine: far stronger, far tougher,
 * and its swipes land with SWEEP_ATTACK arcs that hit
 * everything in front of it at once.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the bear's current target within 16 blocks (distance
 *   squared 256).
 * - Duration: exactly 400 ticks (20 seconds) from start().
 * - The form (start(), every modifier removed in end()):
 *   four transient modifiers under fixed ids:
 *     SCALE                 add_multiplied_base +0.6
 *                           (modifier id advancedmobs:
 *                           ursa_major_scale; 1.6 times
 *                           size, so about 2.24 blocks tall)
 *     ATTACK_DAMAGE         add_value +6.0 (modifier id
 *                           advancedmobs:ursa_major_damage;
 *                           an unupgraded bear hits for
 *                           12.0 instead of 6.0)
 *     MAX_HEALTH            add_value +30.0 (modifier id
 *                           advancedmobs:ursa_major_health;
 *                           30 to 60, and the bear heals the
 *                           added 30 on entry so it arrives
 *                           full; end() clamps its health
 *                           back to the restored maximum if
 *                           it is above it, the Colossus
 *                           Form pattern)
 *     SWEEPING_DAMAGE_RATIO add_value +1.0 (modifier id
 *                           advancedmobs:ursa_major_sweep;
 *                           the design's ratio line: any
 *                           vanilla sweep the bear's own
 *                           melee triggers carries full
 *                           force, on top of the code arcs
 *                           below)
 * - The rise (start()): when the caster is a real
 *   PolarBear it is set standing (the vanilla rear-up pose)
 *   for the whole form, and set back down in end();
 *   POLAR_BEAR_WARNING plays at volume 1.5, pitch 0.7.
 *   Documented approximation: the frosted white fur tips
 *   have no server-side retexture hook in 26.2, so the look
 *   is carried by the size, the standing pose, and the
 *   breath.
 * - Breath (every 10 ticks): 4 CLOUD puffs at the snout
 *   (the bear's position plus its look direction times 1.2,
 *   at 1.3 height).
 * - Sweep arcs (polled every tick, fired at most once per
 *   10 ticks): the bear's last-hurt-mob timestamp is primed
 *   in start() and compared each tick (the Veil of the
 *   Night pattern). On a fresh landed hit, every OTHER
 *   enemy (PolarBearAbilitySupport rules) inside a box of
 *   radius 2.2 around the bear's front point (its position
 *   plus its look direction times 1.5, 2 below to 2 above),
 *   excluding the primary victim, takes damage equal to
 *   half the bear's live ATTACK_DAMAGE attribute value as
 *   a mob attack credited to the bear, and 1 SWEEP_ATTACK
 *   particle draws the arc at the front point. Vanilla
 *   sweeping may ALSO fire on the bear's own attacks (the
 *   ratio modifier above); the code arc is the guaranteed
 *   one the design promises, and the two stacking on a
 *   lucky swing is accepted and intended: this is the
 *   siege form.
 *
 * WHAT IT LOOKS LIKE: a bear rearing up and swelling to
 * half again its size, breath rolling out in clouds, and
 * every swipe drawn with a white arc that catches the
 * whole front rank.
 *
 * PERFORMANCE: per tick one timestamp compare; breath
 * every 10 ticks; a sweep is one bounded entity query and
 * runs at most once per 10 ticks. Particle budget per
 * activation: 40 breaths times 4 = 160 CLOUD, plus at most
 * 40 sweep arcs = at most 200 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "hit_ts" (the last-hurt-mob timestamp
 * already answered with an arc), "sweep_cd" (the first
 * tick the next arc may fire).
 */
public final class UrsaMajorAbility implements MobAbility {

    static final int DURATION_TICKS = 400;
    private static final int SWEEP_COOLDOWN_TICKS = 10;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "ursa_major_scale");
    private static final Identifier DAMAGE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "ursa_major_damage");
    private static final Identifier HEALTH_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "ursa_major_health");
    private static final Identifier SWEEP_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "ursa_major_sweep");

    @Override
    public String id() {
        return "ursa_major";
    }

    @Override
    public String displayName() {
        return "Ursa Major";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        state.putInt("hit_ts", caster.getLastHurtMobTimestamp());
        state.putInt("sweep_cd", 0);

        PolarBearAbilitySupport.addModifier(caster, Attributes.SCALE,
            new AttributeModifier(SCALE_ID, 0.6, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        PolarBearAbilitySupport.addModifier(caster, Attributes.ATTACK_DAMAGE,
            new AttributeModifier(DAMAGE_ID, 6.0, AttributeModifier.Operation.ADD_VALUE));
        PolarBearAbilitySupport.addModifier(caster, Attributes.MAX_HEALTH,
            new AttributeModifier(HEALTH_ID, 30.0, AttributeModifier.Operation.ADD_VALUE));
        // Arrive full: the added pool comes in healed.
        caster.heal(30.0f);
        PolarBearAbilitySupport.addModifier(caster, Attributes.SWEEPING_DAMAGE_RATIO,
            new AttributeModifier(SWEEP_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));

        if (caster instanceof PolarBear bear) {
            bear.setStanding(true);
        }
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.POLAR_BEAR_WARNING, SoundSource.HOSTILE, 1.5f, 0.7f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        int ticks = ctx.ticksActive();
        if (level == null) {
            return;
        }

        // Breath rolling out with every snort.
        if (ticks % 10 == 0) {
            Vec3 look = caster.getLookAngle();
            level.sendParticles(ParticleTypes.CLOUD,
                caster.getX() + look.x * 1.2, caster.getY() + 1.3, caster.getZ() + look.z * 1.2,
                4, 0.25, 0.2, 0.25, 0.04);
        }

        sweepArc(ctx, level, ticks);
    }

    /**
     * The sweep arc on a fresh landed hit (see the class
     * comment): every other enemy in front of the bear takes
     * half the bear's attack damage, at most once per
     * SWEEP_COOLDOWN_TICKS.
     */
    private void sweepArc(AbilityContext ctx, ServerLevel level, int ticks) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        int timestamp = caster.getLastHurtMobTimestamp();
        if (timestamp == state.getIntOr("hit_ts", 0)) {
            return; // No fresh hit since the last arc.
        }
        state.putInt("hit_ts", timestamp);
        if (ticks < state.getIntOr("sweep_cd", 0)) {
            return; // Fresh hit noted, but the arc is still recovering.
        }
        LivingEntity victim = caster.getLastHurtMob();
        if (victim == null || !victim.isAlive()) {
            return;
        }
        state.putInt("sweep_cd", ticks + SWEEP_COOLDOWN_TICKS);
        Vec3 look = caster.getLookAngle();
        double frontX = caster.getX() + look.x * 1.5;
        double frontZ = caster.getZ() + look.z * 1.5;
        level.sendParticles(ParticleTypes.SWEEP_ATTACK, frontX, caster.getY() + 1.0, frontZ,
            1, 0.0, 0.0, 0.0, 0.0);
        float arcDamage = (float) (caster.getAttributeValue(Attributes.ATTACK_DAMAGE) * 0.5);
        List<LivingEntity> caught = PolarBearAbilitySupport.enemiesInBox(
            ctx, frontX, caster.getY(), frontZ, 2.2, 2.0, 2.0);
        for (LivingEntity enemy : caught) {
            if (enemy == victim) {
                continue; // The primary victim already took the full swipe.
            }
            enemy.hurtServer(level, level.damageSources().mobAttack(caster), arcDamage);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        PolarBearAbilitySupport.removeModifier(caster, Attributes.SCALE, SCALE_ID);
        PolarBearAbilitySupport.removeModifier(caster, Attributes.ATTACK_DAMAGE, DAMAGE_ID);
        PolarBearAbilitySupport.removeModifier(caster, Attributes.MAX_HEALTH, HEALTH_ID);
        PolarBearAbilitySupport.removeModifier(caster, Attributes.SWEEPING_DAMAGE_RATIO, SWEEP_ID);
        // Clamp health back to the restored maximum.
        if (caster.getHealth() > caster.getMaxHealth()) {
            caster.setHealth(caster.getMaxHealth());
        }
        if (caster instanceof PolarBear bear) {
            bear.setStanding(false);
        }
    }
}
