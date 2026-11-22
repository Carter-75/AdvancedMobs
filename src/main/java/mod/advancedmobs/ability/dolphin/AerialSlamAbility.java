package mod.advancedmobs.ability.dolphin;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
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
 * Aerial Slam (Dolphin ability 4 of 5, mobility / attack).
 *
 * WHAT IT DOES: the dolphin launches itself clean out of
 * the water in a sparkling arc, hanging at the peak as its
 * GRAVITY eases, SPLASH particles raining off its body. It
 * crashes back down on top of its target at the surface,
 * and the re-entry burst deals area damage and shoves
 * everything nearby outward in a ring of foam.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the dolphin's current target within 14 blocks (distance
 *   squared 196), plus the dolphin in water (the leap starts
 *   from the water; a beached dolphin cannot launch).
 * - The launch (start()): two transient modifiers for the
 *   flight, both removed in end(): GRAVITY -0.5
 *   add_multiplied_base (modifier id
 *   advancedmobs:aerial_slam_gravity; halved gravity is
 *   what makes the arc hang at the peak, the design's
 *   GRAVITY line) and SAFE_FALL_DISTANCE +30 add_value
 *   (modifier id advancedmobs:aerial_slam_fall; the
 *   design's second line, so the dolphin itself never takes
 *   fall damage from its own leap if it lands on a shore or
 *   a boat instead of water). The launch velocity is set
 *   once: vertical 1.05 blocks per tick, horizontal toward
 *   the target's position at distance / 16 clamped to
 *   [0.5, 1.2] blocks per tick, aimed to come down on the
 *   target. DOLPHIN_JUMP plays at volume 1.2 and 10 SPLASH
 *   particles burst at the launch point. The dolphin's
 *   vanilla goals do the rest of the flying: the ability
 *   only watches for the landing, it never steers the arc.
 * - The landing watch (tick()): a state flag records when
 *   the dolphin has genuinely left the water (not in water
 *   and not on ground). The first later tick it is back in
 *   water or on ground is the landing, and the slam fires
 *   exactly once. If the dolphin never leaves the water by
 *   tick 30 (the launch was smothered by a ceiling or a
 *   wall), the ability fizzles with no slam.
 * - The slam: one bounded query of radius 4.0 around the
 *   dolphin's landing position; every enemy in it (package
 *   rule, round distance checked) takes 6.0 damage as a mob
 *   attack credited to the dolphin (typed damage in code,
 *   the design's line) and is shoved directly outward from
 *   the dolphin at 1.1 blocks per tick horizontal plus 0.4
 *   up, hurtMarked so it syncs. 16 SPLASH particles ring
 *   the landing point with 10 CURRENT_DOWN motes swirling
 *   down through the foam, and DOLPHIN_SPLASH plays at
 *   volume 1.5.
 * - The ability finishes 10 ticks after the slam (the
 *   dolphin recovering its stroke), or at the 100 tick hard
 *   cap if the landing somehow never registers.
 *
 * WHAT IT LOOKS LIKE: a clean breach, a glittering hang at
 * the top of the arc, and a foam-ringed crash back into the
 * surface that bowls everything at the landing point
 * outward.
 *
 * PERFORMANCE: per tick the watch is two state reads and
 * two entity state checks; the slam is one bounded query
 * once. No per-tick particles, no per-tick allocations.
 * Particle budget per activation: 10 at launch plus 26 at
 * the slam, total at most 36 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * CLEANUP: end() removes both flight modifiers, whether
 * the slam landed, fizzled, or was cut short by the
 * caster's death (the framework termination path runs this
 * same end()).
 *
 * State tag keys: "airborne" (1 once the dolphin has left
 * the water), "slammed" (1 once the slam has fired),
 * "slam_tick" (the tick the slam fired on).
 */
public final class AerialSlamAbility implements MobAbility {

    private static final int HARD_CAP_TICKS = 100;
    private static final int FIZZLE_TICK = 30;
    private static final int RECOVER_TICKS = 10;
    private static final double SLAM_RADIUS = 4.0;
    private static final double SLAM_DAMAGE = 6.0;
    private static final Identifier GRAVITY_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "aerial_slam_gravity");
    private static final Identifier FALL_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "aerial_slam_fall");

    @Override
    public String id() {
        return "aerial_slam";
    }

    @Override
    public String displayName() {
        return "Aerial Slam";
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
        ctx.state().putInt("airborne", 0);
        ctx.state().putInt("slammed", 0);
        ctx.state().putInt("slam_tick", -1);

        // The design's two flight lines, held until end().
        DolphinAbilitySupport.addModifier(caster, Attributes.GRAVITY,
            new AttributeModifier(GRAVITY_ID, -0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        DolphinAbilitySupport.addModifier(caster, Attributes.SAFE_FALL_DISTANCE,
            new AttributeModifier(FALL_ID, 30.0, AttributeModifier.Operation.ADD_VALUE));

        // The launch: up and toward the target in one velocity.
        LivingEntity target = ctx.target();
        double dx = target != null ? target.getX() - caster.getX() : 0.0;
        double dz = target != null ? target.getZ() - caster.getZ() : 0.0;
        double dist = Math.sqrt(dx * dx + dz * dz);
        double horizontal = Math.max(0.5, Math.min(1.2, dist / 16.0));
        if (dist < 0.001) {
            dx = 1.0;
            dist = 1.0;
        }
        caster.setDeltaMovement(dx / dist * horizontal, 1.05, dz / dist * horizontal);
        caster.hurtMarked = true;

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.sendParticles(ParticleTypes.SPLASH, caster.getX(), caster.getY() + 0.4, caster.getZ(),
                10, 0.4, 0.2, 0.4, 0.1);
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.DOLPHIN_JUMP, SoundSource.HOSTILE, 1.2f, 1.0f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.state().getIntOr("slammed", 0) != 0) {
            return; // The slam already fired; the recovery ticks just run out.
        }
        Mob caster = ctx.mob();
        if (ctx.state().getIntOr("airborne", 0) == 0) {
            if (!caster.isInWater() && !caster.onGround()) {
                ctx.state().putInt("airborne", 1);
            }
            return;
        }
        if (!caster.isInWater() && !caster.onGround()) {
            return; // Still flying.
        }
        // Back in the water or on the ground: the landing.
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            slam(ctx, level);
        }
        ctx.state().putInt("slammed", 1);
        ctx.state().putInt("slam_tick", ctx.ticksActive());
    }

    /** The re-entry burst: area damage and the outward ring shove. See the class comment. */
    private void slam(AbilityContext ctx, ServerLevel level) {
        Mob caster = ctx.mob();
        List<LivingEntity> victims = DolphinAbilitySupport.enemiesAroundSelf(ctx, SLAM_RADIUS, 3.0, 3.0);
        for (LivingEntity victim : victims) {
            double dx = victim.getX() - caster.getX();
            double dz = victim.getZ() - caster.getZ();
            double distSqr = dx * dx + dz * dz + (victim.getY() - caster.getY()) * (victim.getY() - caster.getY());
            if (distSqr > SLAM_RADIUS * SLAM_RADIUS) {
                continue; // The box query is square; the ring is round.
            }
            victim.hurtServer(level, level.damageSources().mobAttack(caster), (float) SLAM_DAMAGE);
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 0.001) {
                dx = 1.0;
                len = 1.0;
            }
            victim.setDeltaMovement(dx / len * 1.1, 0.4, dz / len * 1.1);
            victim.hurtMarked = true;
        }
        // The foam ring.
        for (int i = 0; i < 16; i++) {
            double angle = i * (Math.PI * 2.0 / 16.0);
            level.sendParticles(ParticleTypes.SPLASH,
                caster.getX() + Math.cos(angle) * 2.5, caster.getY() + 0.4,
                caster.getZ() + Math.sin(angle) * 2.5,
                1, 0.1, 0.2, 0.1, 0.05);
        }
        level.sendParticles(ParticleTypes.CURRENT_DOWN, caster.getX(), caster.getY() + 0.6, caster.getZ(),
            10, 1.5, 0.4, 1.5, 0.05);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.DOLPHIN_SPLASH, SoundSource.HOSTILE, 1.5f, 1.0f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks >= HARD_CAP_TICKS) {
            return true;
        }
        int slamTick = ctx.state().getIntOr("slam_tick", -1);
        if (slamTick >= 0) {
            return ticks >= slamTick + RECOVER_TICKS;
        }
        // Never got airborne: the launch was smothered.
        return ticks >= FIZZLE_TICK && ctx.state().getIntOr("airborne", 0) == 0;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        DolphinAbilitySupport.removeModifier(caster, Attributes.GRAVITY, GRAVITY_ID);
        DolphinAbilitySupport.removeModifier(caster, Attributes.SAFE_FALL_DISTANCE, FALL_ID);
    }
}
