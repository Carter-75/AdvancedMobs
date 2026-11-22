package mod.advancedmobs.ability.horse;

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
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Sky Gallop (Horse ability 3 of 5, mobility / attack).
 *
 * WHAT IT DOES: the horse crouches, then leaps impossibly
 * high toward its target under lowered gravity. At the apex
 * it hangs for a breath under slow falling, then folds its
 * legs and comes down hooves first: the landing slams out a
 * ring of crumbling ground and heavy area damage.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the horse on the ground and its current target within
 *   14 blocks (distance squared 196).
 * - Leap kit (start(), removed exactly in end()): three
 *   transient modifiers for the whole activation: GRAVITY
 *   -0.5 ADD_MULTIPLIED_BASE (gravity halved, the design's
 *   gravity line), JUMP_STRENGTH +0.55 ADD_VALUE (a plain
 *   horse's randomized 0.4 to 1.0 becomes 0.95 to 1.55, the
 *   design's jump_strength line; the leap itself is a code
 *   velocity write, below, sized to match), and
 *   SAFE_FALL_DISTANCE +30 ADD_VALUE (the design's
 *   safe_fall_distance line: the horse takes no fall damage
 *   from its own landing; the slam's damage to enemies is
 *   code, below).
 * - Phase 0, the crouch (ticks 1 to 6): navigation stopped
 *   every tick (never NoAi), 3 tan DUST at the hooves every
 *   2 ticks.
 * - The launch (tick 6, end of the crouch): the target's
 *   live position is the aim point. Velocity is set once:
 *   horizontal toward the target at distance / 18 clamped
 *   to [0.5, 1.3] blocks per tick, vertical 1.15. With
 *   gravity halved the arc rises about 16 blocks and
 *   carries the horse onto or past the target. HORSE_JUMP
 *   plays (volume 1.3). The launch tick is recorded so the
 *   landing check cannot fire on the crouch's own ground
 *   contact.
 * - Phase 1, the flight: 2 tan DUST trail at the hooves
 *   every 2 ticks. The apex: the first tick the vertical
 *   velocity reads at or below 0.02 after the launch, the
 *   horse gains SLOW_FALLING for 40 ticks (the design's
 *   slow falling at the apex) and the apex tick is
 *   recorded. 24 ticks after the apex the SLOW_FALLING is
 *   removed again, so the horse folds and dives hooves
 *   first for the rest of the fall instead of parachuting
 *   all the way down.
 * - The slam (the first onGround tick at least 3 ticks
 *   after the launch): one bounded radius-4.0 query (2
 *   below, 3 above) at the horse's landing position: every
 *   enemy takes 10.0 damage as a mob attack credited to
 *   the horse (a flat code value: a horse has no
 *   ATTACK_DAMAGE attribute instance in 26.2, verified
 *   against the jar) and is knocked 1.2 horizontal away
 *   from the horse and 0.5 up, hurtMarked set. The ring:
 *   20 BLOCK_CRUMBLE of the ground block's state plus 10
 *   tan DUST, GENERIC_EXPLODE (volume 1.2, pitch 0.8) and
 *   HORSE_LAND (volume 1.2).
 * - Phase 2, the recovery: 10 ticks standing in the dust,
 *   then the ability finishes. Hard cap tick 160 (a launch
 *   that somehow never lands still ends, and end() strips
 *   everything).
 * - end(): the three modifiers are removed and any
 *   SLOW_FALLING still on the horse is removed with them.
 *
 * WHAT IT LOOKS LIKE: a crouch, a dust burst, and a horse
 * sailing in a huge slow arc, hanging a moment at the top,
 * then plummeting hooves first into a crumbling ring of
 * dirt and a shockwave of damage.
 *
 * PERFORMANCE: the crouch is one navigation stop and an
 * emission every 2 ticks; the flight is one velocity read,
 * one onGround read, and an emission every 2 ticks; the
 * slam is one bounded query once. Particle budget per
 * activation: crouch 3 times 3 = 9, flight trail at most
 * 50, slam 30, total at most about 90 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply. BLOCK_CRUMBLE is a particle of the ground
 * block's state, not a placed or broken block.
 *
 * State tag keys: "phase" (0 crouch, 1 flight, 2
 * recovery), "phase_end" (tick the recovery ends),
 * "launch_tick" (tick the launch fired), "apex_tick"
 * (tick the apex float began, 0 while it has not).
 */
public final class SkyGallopAbility implements MobAbility {

    private static final int CROUCH_TICKS = 6;
    private static final int APEX_FLOAT_TICKS = 24;
    private static final int RECOVERY_TICKS = 10;
    private static final int HARD_CAP_TICKS = 160;
    private static final double LAUNCH_VERTICAL = 1.15;
    private static final double SLAM_RADIUS = 4.0;
    private static final double SLAM_DAMAGE = 10.0;
    private static final Identifier GRAVITY_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "sky_gallop_gravity");
    private static final Identifier JUMP_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "sky_gallop_jump");
    private static final Identifier FALL_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "sky_gallop_fall");

    @Override
    public String id() {
        return "sky_gallop";
    }

    @Override
    public String displayName() {
        return "Sky Gallop";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().onGround()
            && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        state.putInt("phase", 0);
        state.putInt("phase_end", 0);
        state.putInt("launch_tick", 0);
        state.putInt("apex_tick", 0);

        // The leap kit.
        HorseAbilitySupport.addModifier(caster, Attributes.GRAVITY,
            new AttributeModifier(GRAVITY_ID, -0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        HorseAbilitySupport.addModifier(caster, Attributes.JUMP_STRENGTH,
            new AttributeModifier(JUMP_ID, 0.55, AttributeModifier.Operation.ADD_VALUE));
        HorseAbilitySupport.addModifier(caster, Attributes.SAFE_FALL_DISTANCE,
            new AttributeModifier(FALL_ID, 30.0, AttributeModifier.Operation.ADD_VALUE));
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        CompoundTag state = ctx.state();
        int ticks = ctx.ticksActive();
        int phase = state.getIntOr("phase", 0);

        if (phase == 0) {
            // The crouch: rooted, dust at the hooves.
            caster.getNavigation().stop();
            if (level != null && ticks % 2 == 0) {
                level.sendParticles(HorseAbilitySupport.TAN_DUST,
                    caster.getX(), caster.getY() + 0.15, caster.getZ(),
                    3, 0.45, 0.1, 0.45, 0.08);
            }
            if (ticks >= CROUCH_TICKS) {
                launch(ctx, ticks);
            }
        } else if (phase == 1) {
            flightTick(ctx, level, ticks);
        }
        // Phase 2 is a standing recovery: nothing to do per tick.
    }

    /** The launch: one velocity write aimed at the target's live position. */
    private void launch(AbilityContext ctx, int ticks) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        state.putInt("phase", 1);
        state.putInt("launch_tick", ticks);

        double dx = 0.0;
        double dz = 0.0;
        double horizontalSpeed = 0.5;
        LivingEntity target = ctx.target();
        if (target != null) {
            dx = target.getX() - caster.getX();
            dz = target.getZ() - caster.getZ();
            double distance = Math.sqrt(dx * dx + dz * dz);
            if (distance > 0.001) {
                horizontalSpeed = Math.max(0.5, Math.min(1.3, distance / 18.0));
                dx = dx / distance * horizontalSpeed;
                dz = dz / distance * horizontalSpeed;
            } else {
                dx = 0.0;
                dz = 0.0;
            }
        }
        caster.setDeltaMovement(dx, LAUNCH_VERTICAL, dz);
        caster.hurtMarked = true;

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.HORSE_JUMP, SoundSource.HOSTILE, 1.3f, 1.0f);
        }
    }

    /** One flight tick: trail, the apex float, the dive, and the landing slam. */
    private void flightTick(AbilityContext ctx, ServerLevel level, int ticks) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        if (level == null) {
            return;
        }
        if (ticks % 2 == 0) {
            level.sendParticles(HorseAbilitySupport.TAN_DUST,
                caster.getX(), caster.getY() + 0.15, caster.getZ(),
                2, 0.4, 0.1, 0.4, 0.05);
        }

        int apexTick = state.getIntOr("apex_tick", 0);
        Vec3 velocity = caster.getDeltaMovement();
        if (apexTick == 0 && velocity.y <= 0.02) {
            // The apex: hang under slow falling for a breath.
            caster.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 40, 0));
            state.putInt("apex_tick", ticks);
        } else if (apexTick > 0 && ticks == apexTick + APEX_FLOAT_TICKS
            && caster.hasEffect(MobEffects.SLOW_FALLING)) {
            // The breath is over: fold and dive hooves first.
            caster.removeEffect(MobEffects.SLOW_FALLING);
        }

        if (caster.onGround() && ticks >= state.getIntOr("launch_tick", 0) + 3) {
            slam(ctx, level, ticks);
        }
    }

    /** The landing slam: the crumble ring and the area damage. */
    private void slam(AbilityContext ctx, ServerLevel level, int ticks) {
        Mob caster = ctx.mob();
        ctx.state().putInt("phase", 2);
        ctx.state().putInt("phase_end", ticks + RECOVERY_TICKS);

        List<LivingEntity> victims = HorseAbilitySupport.enemiesInBox(
            ctx, caster.getX(), caster.getY(), caster.getZ(), SLAM_RADIUS, 2.0, 3.0);
        for (LivingEntity victim : victims) {
            victim.hurtServer(level, level.damageSources().mobAttack(caster), (float) SLAM_DAMAGE);
            double dx = victim.getX() - caster.getX();
            double dz = victim.getZ() - caster.getZ();
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length < 0.001) {
                dx = 1.0;
                dz = 0.0;
                length = 1.0;
            }
            victim.setDeltaMovement(dx / length * 1.2, 0.5, dz / length * 1.2);
            victim.hurtMarked = true;
        }

        level.sendParticles(HorseAbilitySupport.groundCrumble(level, caster.getX(), caster.getY(), caster.getZ()),
            caster.getX(), caster.getY() + 0.3, caster.getZ(),
            20, 2.6, 0.3, 2.6, 0.25);
        level.sendParticles(HorseAbilitySupport.TAN_DUST,
            caster.getX(), caster.getY() + 0.3, caster.getZ(),
            10, 2.6, 0.3, 2.6, 0.2);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1.2f, 0.8f);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.HORSE_LAND, SoundSource.HOSTILE, 1.2f, 1.0f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        if (ctx.ticksActive() >= HARD_CAP_TICKS) {
            return true;
        }
        CompoundTag state = ctx.state();
        return state.getIntOr("phase", 0) == 2
            && ctx.ticksActive() >= state.getIntOr("phase_end", 0);
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        HorseAbilitySupport.removeModifier(caster, Attributes.GRAVITY, GRAVITY_ID);
        HorseAbilitySupport.removeModifier(caster, Attributes.JUMP_STRENGTH, JUMP_ID);
        HorseAbilitySupport.removeModifier(caster, Attributes.SAFE_FALL_DISTANCE, FALL_ID);
        caster.removeEffect(MobEffects.SLOW_FALLING);
    }
}
