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
import net.minecraft.world.phys.Vec3;

/**
 * Upstream Leap (Salmon ability 1 of 5, mobility /
 * attack).
 *
 * WHAT IT DOES: the salmon launches itself clear out
 * of the water in a high arc, wrapped in splash and
 * falling water, and crashes down on its target with
 * its full body weight. It can chain up to three
 * leaps in a row, each one faster than the last,
 * crossing pools and short stretches of land that
 * would stop any other fish.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the salmon's current target within 14 blocks
 *   (distance squared 196), plus the salmon in water
 *   (the leap starts from a swim; leaving the water is
 *   the ability's whole point, so it is the one salmon
 *   ability that does not stay in the water).
 * - The leap kit (start()): the design's four
 *   attribute lines as transient modifiers, all
 *   removed in end(): GRAVITY -0.4 add_multiplied_base
 *   (id advancedmobs:upstream_leap_gravity; the arc
 *   hangs longer), AIR_DRAG_MODIFIER -0.9 add_value
 *   (id advancedmobs:upstream_leap_drag; to 0.1, the
 *   design's low drag mid-leap, so the salmon does not
 *   stall in the air), JUMP_STRENGTH +0.5 add_value
 *   (id advancedmobs:upstream_leap_jump; the design's
 *   line, applied through the null-tolerant helper: a
 *   fish has no vanilla jump path for it to ride, so
 *   the arc itself is code velocity below), and
 *   SAFE_FALL_DISTANCE +100 add_value (id
 *   advancedmobs:upstream_leap_fall; the design's
 *   safe_fall_distance control, so the salmon never
 *   takes fall damage from its own crash landings).
 * - The chain (tick()): the activation runs in two
 *   phases per leap. Phase 0 waits (6 ticks between
 *   leaps, none before the first) while the salmon
 *   steadies, then launches: one velocity write
 *   toward the target's live position, horizontal
 *   speed 0.85 plus 0.15 per leap already completed
 *   (leap 1: 0.85, leap 2: 1.0, leap 3: 1.15 blocks
 *   per tick) and vertical 0.8 plus 0.08 per leap
 *   (0.8, 0.88, 0.96), with SALMON_FLOP at pitch
 *   1.0 plus 0.1 per leap. Phase 1 is airborne: 2
 *   FALLING_WATER plus 1 SPLASH trail the body every
 *   tick. Landing is the first tick the salmon is in
 *   water or on the ground after at least 4 airborne
 *   ticks: the crash lands where it lands, and if the
 *   target is within 2.5 blocks it takes typed
 *   mob-attack damage credited to the salmon, 4.0
 *   plus 1.0 per leap already completed before this
 *   one (4.0, 5.0, 6.0), plus a 0.5 horizontal / 0.3
 *   up knockback, with 10 SPLASH and 6 FALLING_WATER
 *   bursting at the landing point. An airborne salmon
 *   still flying at 60 airborne ticks is forced to
 *   resolve as landed wherever it is, so a weird arc
 *   can never hang the activation forever.
 * - The chain ends after 3 completed leaps, when the
 *   target dies or escapes beyond 20 blocks between
 *   leaps, or at the 160 tick hard cap.
 *
 * WHAT IT LOOKS LIKE: a salmon-shaped comet of spray
 * leaving the water, a white arc of falling water
 * over the pool, and a body-slam crash that throws a
 * ring of splash up around the target.
 *
 * PERFORMANCE: one velocity write and 3 particles per
 * airborne tick, one distance check per landing, no
 * entity queries at all. Particle budget per
 * activation: at most 3 leaps times 60 airborne ticks
 * times 3 = 540 trail, at most 3 crashes times 16 =
 * 48, total at most 588.
 *
 * TERRAIN: this ability changes no blocks; the gate
 * does not apply.
 *
 * CLEANUP: end() removes all four transient
 * modifiers by id. A salmon that dies mid-air keeps
 * no modifier behind: they are transient and end()
 * runs on termination too.
 *
 * State tag keys: "leaps" (completed leaps), "phase"
 * (0 waiting, 1 airborne), "air_ticks" (ticks since
 * launch), "wait" (ticks left before the next launch),
 * "done" (the chain is over).
 */
public final class UpstreamLeapAbility implements MobAbility {

    private static final int MAX_LEAPS = 3;
    private static final int HARD_CAP_TICKS = 160;
    private static final int MAX_AIR_TICKS = 60;
    private static final int BETWEEN_LEAP_WAIT = 6;
    private static final int MIN_AIR_TICKS = 4;
    private static final double CRASH_RADIUS = 2.5;
    private static final double MAX_LEAP_RANGE = 20.0;
    private static final Identifier GRAVITY_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "upstream_leap_gravity");
    private static final Identifier DRAG_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "upstream_leap_drag");
    private static final Identifier JUMP_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "upstream_leap_jump");
    private static final Identifier FALL_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "upstream_leap_fall");

    @Override
    public String id() {
        return "upstream_leap";
    }

    @Override
    public String displayName() {
        return "Upstream Leap";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().isInWater()
            && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        state.putInt("leaps", 0);
        state.putInt("phase", 0);
        state.putInt("air_ticks", 0);
        state.putInt("wait", 0);
        state.putBoolean("done", false);

        // The design's four attribute lines, for the whole chain.
        SalmonAbilitySupport.addModifier(caster, Attributes.GRAVITY,
            new AttributeModifier(GRAVITY_ID, -0.4, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        SalmonAbilitySupport.addModifier(caster, Attributes.AIR_DRAG_MODIFIER,
            new AttributeModifier(DRAG_ID, -0.9, AttributeModifier.Operation.ADD_VALUE));
        SalmonAbilitySupport.addModifier(caster, Attributes.JUMP_STRENGTH,
            new AttributeModifier(JUMP_ID, 0.5, AttributeModifier.Operation.ADD_VALUE));
        SalmonAbilitySupport.addModifier(caster, Attributes.SAFE_FALL_DISTANCE,
            new AttributeModifier(FALL_ID, 100.0, AttributeModifier.Operation.ADD_VALUE));
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        int leaps = state.getIntOr("leaps", 0);
        int phase = state.getIntOr("phase", 0);

        if (phase == 0) {
            int wait = state.getIntOr("wait", 0);
            if (wait > 0) {
                state.putInt("wait", wait - 1);
                return;
            }
            LivingEntity target = ctx.target();
            if (target == null || !target.isAlive()
                || caster.distanceToSqr(target) > MAX_LEAP_RANGE * MAX_LEAP_RANGE) {
                state.putBoolean("done", true);
                return;
            }
            launch(ctx, level, leaps, target);
            return;
        }

        // Phase 1: airborne, trailing spray.
        int airTicks = state.getIntOr("air_ticks", 0) + 1;
        state.putInt("air_ticks", airTicks);
        level.sendParticles(ParticleTypes.FALLING_WATER,
            caster.getX(), caster.getY() + 0.4, caster.getZ(),
            2, 0.25, 0.25, 0.25, 0.02);
        level.sendParticles(ParticleTypes.SPLASH,
            caster.getX(), caster.getY() + 0.2, caster.getZ(),
            1, 0.3, 0.2, 0.3, 0.05);

        boolean landed = airTicks >= MIN_AIR_TICKS && (caster.isInWater() || caster.onGround());
        if (!landed && airTicks < MAX_AIR_TICKS) {
            return;
        }
        resolveLanding(ctx, level, leaps);
    }

    /** One launch: a velocity write toward the target's live position, faster per leap. */
    private void launch(AbilityContext ctx, ServerLevel level, int leaps, LivingEntity target) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        double dx = target.getX() - caster.getX();
        double dz = target.getZ() - caster.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double speed = 0.85 + 0.15 * leaps;
        double vx = horizontal > 0.001 ? dx / horizontal * speed : 0.0;
        double vz = horizontal > 0.001 ? dz / horizontal * speed : 0.0;
        double vy = 0.8 + 0.08 * leaps;
        caster.setDeltaMovement(vx, vy, vz);
        caster.hurtMarked = true;
        state.putInt("phase", 1);
        state.putInt("air_ticks", 0);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.SALMON_FLOP, SoundSource.HOSTILE, 1.0f, 1.0f + 0.1f * leaps);
    }

    /** The crash: damage the target if it is under the landing point, then set up the next leap. */
    private void resolveLanding(AbilityContext ctx, ServerLevel level, int leaps) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        LivingEntity target = ctx.target();
        if (target != null && target.isAlive()
            && caster.distanceToSqr(target) <= CRASH_RADIUS * CRASH_RADIUS) {
            float damage = 4.0f + 1.0f * leaps;
            target.hurtServer(level, level.damageSources().mobAttack(caster), damage);
            Vec3 away = new Vec3(target.getX() - caster.getX(), 0.0, target.getZ() - caster.getZ());
            double length = away.length();
            if (length > 0.001) {
                target.setDeltaMovement(away.x / length * 0.5, 0.3, away.z / length * 0.5);
            } else {
                target.setDeltaMovement(0.0, 0.3, 0.0);
            }
            target.hurtMarked = true;
        }
        level.sendParticles(ParticleTypes.SPLASH,
            caster.getX(), caster.getY() + 0.3, caster.getZ(),
            10, 0.5, 0.3, 0.5, 0.08);
        level.sendParticles(ParticleTypes.FALLING_WATER,
            caster.getX(), caster.getY() + 0.5, caster.getZ(),
            6, 0.5, 0.4, 0.5, 0.02);

        int done = leaps + 1;
        state.putInt("leaps", done);
        if (done >= MAX_LEAPS) {
            state.putBoolean("done", true);
            return;
        }
        state.putInt("phase", 0);
        state.putInt("wait", BETWEEN_LEAP_WAIT);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.state().getBooleanOr("done", false)
            || ctx.ticksActive() >= HARD_CAP_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        SalmonAbilitySupport.removeModifier(caster, Attributes.GRAVITY, GRAVITY_ID);
        SalmonAbilitySupport.removeModifier(caster, Attributes.AIR_DRAG_MODIFIER, DRAG_ID);
        SalmonAbilitySupport.removeModifier(caster, Attributes.JUMP_STRENGTH, JUMP_ID);
        SalmonAbilitySupport.removeModifier(caster, Attributes.SAFE_FALL_DISTANCE, FALL_ID);
    }
}
