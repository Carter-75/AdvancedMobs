package mod.advancedmobs.ability.magmacube;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
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
 * Eruption Leap (Magma Cube ability 4 of 5, mobility).
 *
 * WHAT IT DOES: the magma cube detonates the ground beneath
 * itself, an EXPLOSION flash and a sheet of FLAME blasting it
 * skyward in a volcanic launch that carries it clear across
 * the arena toward its target. It comes down in a ring of real
 * fire blocks and a spray of LAVA, landing already fighting in
 * the middle of ground the enemy thought was safe. The blast
 * is a visual: no explosion block damage is dealt by this
 * ability at all, so there is no explosion interaction mode to
 * gate; the only terrain it ever makes is the landing's fire
 * ring, gated below.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists, between 6 and 22
 *   blocks away (distance squared 36 to 484), and the cube is
 *   on the ground. It is a gap closer: too close and the cube
 *   just hops, too far and even a volcano cannot throw it.
 * - The launch (start()): a FALL_DAMAGE_MULTIPLIER modifier
 *   (add_multiplied_total -1.0, id
 *   advancedmobs:eruption_leap_fall) zeroes the cube's own fall
 *   damage for the whole activation. The velocity is set once:
 *   vertical 1.15 straight up, and horizontally toward the
 *   target at a speed of the distance times 0.09, clamped
 *   between 0.55 and 1.35 blocks per tick. The arc is
 *   approximate by design (the cube's own air physics ride
 *   along); the landing ring's 2 block radius is what makes
 *   the arrival matter, not a pinpoint touchdown. Launch FX at
 *   the cube's feet: 1 EXPLOSION particle, 12 FLAME, and a
 *   GENERIC_EXPLODE (volume 0.6, pitch 1.0).
 * - Flight (tick()): 2 FLAME particles trail the cube every 2
 *   ticks. The cube's own AI cannot hop mid-air, so the arc
 *   flies clean.
 * - The landing: the first tick the cube is on the ground at
 *   or after tick 6 (the launch ticks themselves never read as
 *   a landing). FX at the touchdown: 1 EXPLOSION particle, 14
 *   LAVA sprayed in a ring, 8 FLAME, and a GENERIC_EXPLODE
 *   (volume 0.7, pitch 0.9). The fire ring (terrain, gated
 *   below): fire is attempted at the touchdown block and at 8
 *   compass points on a ring of radius 2.0 around it, each
 *   through the shared placeFire safety check, so up to 9
 *   permanent fire blocks ring the landing. The ability then
 *   lingers 10 ticks (the cube fights on inside its ring) and
 *   finishes. Hard finish at tick 100 in case the cube somehow
 *   never lands.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6): the landing's fire blocks
 * are PERMANENT terrain and are gated by
 * AbilityContext.mobGriefingAllowed():
 * - mobGriefing ON: the ring is placed as above and stays,
 *   burning whatever stands in it on vanilla's schedule.
 * - mobGriefing OFF: no fire is placed. The launch, the
 *   flight, the landing particles and sounds, and the cube's
 *   new position in the enemy's face all happen exactly the
 *   same; only the ring is missing.
 *
 * WHAT IT LOOKS LIKE: the ground bursting under the cube in
 * explosion flash and flame, a fat magma cube sailing the
 * length of the arena trailing fire, and a second burst where
 * it slams down inside a ring of real fire.
 *
 * PERFORMANCE: tick() reads the ground flag and, on the 2
 * tick trail pulse, sends 2 particles; the landing runs once.
 * No entity queries at all (nothing about the landing targets
 * entities; the fire ring is the hazard). Particle budget per
 * activation: launch 13, trail at most 50 ticks times 2 = 100
 * (a normal arc lands in about 25 ticks, about 24 trail
 * particles), landing 23, total at most 136 particles.
 *
 * CLEANUP: end() removes the fall damage modifier. Nothing
 * else was created; fire blocks already placed (mobGriefing
 * on) are permanent terrain and are deliberately NOT touched.
 *
 * State tag keys: "land_tick" (the tick the landing fired, or
 * -1 while still flying).
 */
public final class EruptionLeapAbility implements MobAbility {

    private static final int HARD_FINISH_TICKS = 100;
    private static final int LINGER_TICKS = 10;
    private static final double LAUNCH_UP = 1.15;
    private static final double FIRE_RING_RADIUS = 2.0;
    private static final Identifier FALL_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "eruption_leap_fall");

    @Override
    public String id() {
        return "eruption_leap";
    }

    @Override
    public String displayName() {
        return "Eruption Leap";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null || !ctx.mob().onGround()) {
            return false;
        }
        double distanceSqr = ctx.mob().distanceToSqr(target);
        return distanceSqr >= 36.0 && distanceSqr <= 484.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob mob = ctx.mob();
        ctx.state().putInt("land_tick", -1);
        // The eruption costs the cube no fall damage.
        MagmaCubeAbilitySupport.addModifier(mob, Attributes.FALL_DAMAGE_MULTIPLIER,
            new AttributeModifier(FALL_ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        LivingEntity target = ctx.target();
        double speed = 0.55;
        double towardX = 0.0;
        double towardZ = 0.0;
        if (target != null) {
            double dx = target.getX() - mob.getX();
            double dz = target.getZ() - mob.getZ();
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length > 0.001) {
                speed = Math.max(0.55, Math.min(1.35, length * 0.09));
                towardX = dx / length * speed;
                towardZ = dz / length * speed;
            }
        }
        mob.setDeltaMovement(towardX, LAUNCH_UP, towardZ);
        mob.hurtMarked = true;
        if (level != null) {
            level.sendParticles(ParticleTypes.EXPLOSION, mob.getX(), mob.getY() + 0.2, mob.getZ(),
                1, 0.0, 0.0, 0.0, 0.0);
            level.sendParticles(ParticleTypes.FLAME, mob.getX(), mob.getY() + 0.2, mob.getZ(),
                12, 0.6, 0.2, 0.6, 0.12);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.GENERIC_EXPLODE,
                SoundSource.HOSTILE, 0.6f, 1.0f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        int ticks = ctx.ticksActive();
        if (ctx.state().getIntOr("land_tick", -1) >= 0) {
            // Landed: the linger phase does nothing but count.
            return;
        }
        if (ticks % 2 == 0) {
            Vec3 pos = mob.position();
            level.sendParticles(ParticleTypes.FLAME, pos.x, pos.y + 0.4, pos.z,
                2, mob.getBbWidth() * 0.35, 0.3, mob.getBbWidth() * 0.35, 0.04);
        }
        if (ticks >= 6 && mob.onGround()) {
            runLanding(ctx, level);
        }
    }

    /** The touchdown: burst FX and the permanent fire ring (see the TERRAIN RULE above). */
    private static void runLanding(AbilityContext ctx, ServerLevel level) {
        Mob mob = ctx.mob();
        ctx.state().putInt("land_tick", ctx.ticksActive());
        double cx = mob.getX();
        double cy = mob.getY();
        double cz = mob.getZ();
        level.sendParticles(ParticleTypes.EXPLOSION, cx, cy + 0.2, cz, 1, 0.0, 0.0, 0.0, 0.0);
        level.sendParticles(ParticleTypes.LAVA, cx, cy + 0.2, cz, 14, 1.4, 0.3, 1.4, 0.15);
        level.sendParticles(ParticleTypes.FLAME, cx, cy + 0.2, cz, 8, 1.0, 0.3, 1.0, 0.1);
        level.playSound(null, cx, cy, cz, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 0.7f, 0.9f);
        if (ctx.mobGriefingAllowed()) {
            MagmaCubeAbilitySupport.placeFire(level, mob.blockPosition());
            for (int i = 0; i < 8; i++) {
                double angle = Math.PI * 2.0 * i / 8.0;
                BlockPos spot = BlockPos.containing(
                    cx + Math.cos(angle) * FIRE_RING_RADIUS, cy, cz + Math.sin(angle) * FIRE_RING_RADIUS);
                MagmaCubeAbilitySupport.placeFire(level, spot);
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks >= HARD_FINISH_TICKS) {
            return true;
        }
        int landTick = ctx.state().getIntOr("land_tick", -1);
        return landTick >= 0 && ticks >= landTick + LINGER_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        MagmaCubeAbilitySupport.removeModifier(ctx.mob(), Attributes.FALL_DAMAGE_MULTIPLIER, FALL_ID);
    }
}
