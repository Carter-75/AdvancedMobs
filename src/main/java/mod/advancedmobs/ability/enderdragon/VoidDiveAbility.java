package mod.advancedmobs.ability.enderdragon;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.phys.Vec3;

/**
 * Void Dive (Ender Dragon ability 3 of 5, mobility, attack).
 *
 * WHAT IT DOES: the dragon folds her wings and plunges
 * through a tear of PORTAL and REVERSE_PORTAL particles,
 * vanishing from one end of the island and bursting out of a
 * second tear at full speed behind her enemies. The dive
 * trail lays a lingering river of dragon breath cloud across
 * the sky between the tears, and she exits streaming ENCHANT
 * sparkles.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the dragon's current target within 90 blocks (distance
 *   squared 8100), plus her current phase instance NOT
 *   reporting isSitting(): the dive starts from flight, and
 *   a perched dragon has nowhere to dive from.
 * - The dive (all of it happens in start()): the exit point
 *   is computed from the target's position at the dive: 14
 *   blocks past the target along the dragon's line of
 *   approach (the horizontal direction from her position to
 *   the target, or her look direction if she is somehow on
 *   top of the target), at the target's height plus 7. She
 *   teleports there in one step and is yawed to face along
 *   the approach direction, so she comes out of the tear
 *   already aimed past her enemies.
 * - The breath river: before the teleport, one breath cloud
 *   (radius 2.5, duration 120 ticks, instant damage
 *   amplifier 0, see EnderDragonAbilitySupport) is spawned
 *   at each of up to 12 evenly spaced points along the
 *   straight line from the entry point to the exit point,
 *   one about every 6 blocks, endpoints excluded. The
 *   clouds hang on the dive line in the sky: they threaten
 *   anything flying through the line, and anything tall
 *   enough to reach it. They are entities with a fixed
 *   lifetime, not block changes, so the terrain rule does
 *   not gate them, and they expire on their own.
 * - The exit speed: the design builds this on the
 *   FLYING_SPEED attribute, and verified against the real
 *   26.2 jar the dragon has no FLYING_SPEED attribute
 *   instance (her supplier is Mob.createMobAttributes plus
 *   MAX_HEALTH and CAMERA_DISTANCE, and
 *   LivingEntity.createLivingAttributes does not carry
 *   FLYING_SPEED), so an attribute modifier would be inert
 *   on her: her flight speed is set by her current phase,
 *   not by any attribute. The exit speed is therefore
 *   delivered the way her own controller understands it: on
 *   exit she is put into the vanilla CHARGING_PLAYER phase,
 *   her fastest flight, which screams her past the target
 *   from the exit tear. No fake modifier is applied.
 * - Duration: 80 ticks (4 seconds) of aftermath: the ability
 *   itself is done moving her in start(), and the remaining
 *   ticks are the sparkle window below. The framework
 *   cooldown runs from the end of those 80 ticks, as it does
 *   for every ability.
 *
 * MOVEMENT INTERACTION (the standing warning for this mob):
 * the dragon's movement is phase-driven, and this is the one
 * ability in her set that deliberately reaches into it. The
 * teleport relocates her instantly; her parts and flight
 * history re-seat on the next aiStep, the same way they do
 * after any vanilla teleport. The phase switch is a single
 * setPhase call at the dive moment: after it, the phase
 * manager owns her completely again, and the ability never
 * touches her position, velocity, or phase in tick(). If the
 * fight itself changes her phase during the 80 aftermath
 * ticks (a landing call, a crystal event), nothing here
 * fights it: the dive is already spent.
 *
 * WHAT IT LOOKS AND SOUNDS LIKE: at the entry tear, 40
 * PORTAL and 20 REVERSE_PORTAL particles swallow her with an
 * ENDERMAN_TELEPORT sound and a low ENDER_DRAGON_FLAP
 * (volume 2.0, pitch 0.6). At the exit, 30 REVERSE_PORTAL
 * and 10 PORTAL particles burst outward. For the 80
 * aftermath ticks, every 2 ticks 2 ENCHANT sparkles stream
 * off each wing (wing points 7 blocks to either side of her
 * center at 2.5 above her feet, along her facing), and every
 * 4 ticks 3 REVERSE_PORTAL wisps curl off her body, the
 * tear still leaking around her.
 *
 * PERFORMANCE: everything heavy happens once in start()
 * (one teleport, at most 12 cloud spawns, two particle
 * bursts). tick() is 2 particle pulses on alternating
 * intervals with no entity queries, no allocations beyond
 * the particle calls, and no lookups: her facing is read
 * from the mob directly. Particle budget per activation:
 * entry 60, exit 40, ENCHANT stream 40 pulses times 4 =
 * 160, wisps 20 pulses times 3 = 60, total at most 320
 * particles, not counting the breath clouds' own rendering
 * (vanilla entities).
 *
 * State tag keys: none. The dive is a pure function of the
 * start moment, and the aftermath is a pure function of
 * ctx.ticksActive(), which the framework persists, so a
 * resumed activation after a reload simply finishes its
 * sparkle window.
 */
public final class VoidDiveAbility implements MobAbility {

    static final int DURATION_TICKS = 80;
    private static final double EXIT_BEYOND = 14.0;
    private static final double EXIT_HEIGHT = 7.0;
    private static final int MAX_CLOUDS = 12;
    private static final float CLOUD_RADIUS = 2.5f;
    private static final int CLOUD_DURATION = 120;

    @Override
    public String id() {
        return "void_dive";
    }

    @Override
    public String displayName() {
        return "Void Dive";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        if (!(ctx.mob() instanceof EnderDragon dragon)) {
            return false;
        }
        if (dragon.getPhaseManager().getCurrentPhase().isSitting()) {
            return false;
        }
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 8100.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null || !(ctx.mob() instanceof EnderDragon dragon)) {
            return;
        }
        LivingEntity target = ctx.target();
        Vec3 entry = dragon.position();

        // The approach line and the exit point past the target.
        Vec3 approach;
        Vec3 exit;
        if (target != null) {
            Vec3 flat = new Vec3(target.getX() - entry.x, 0.0, target.getZ() - entry.z);
            approach = flat.lengthSqr() < 1.0e-4
                ? new Vec3(dragon.getLookAngle().x, 0.0, dragon.getLookAngle().z).normalize()
                : flat.normalize();
            exit = new Vec3(target.getX() + approach.x * EXIT_BEYOND,
                            target.getY() + EXIT_HEIGHT,
                            target.getZ() + approach.z * EXIT_BEYOND);
        } else {
            // The target died between the pick and the start:
            // dive along her current heading instead of
            // cancelling, out the far side of where she flies.
            approach = new Vec3(dragon.getLookAngle().x, 0.0, dragon.getLookAngle().z).normalize();
            exit = entry.add(approach.scale(30.0));
        }

        // The entry tear swallows her.
        level.sendParticles(ParticleTypes.PORTAL, entry.x, entry.y + 3.0, entry.z,
            40, 3.0, 2.0, 3.0, 0.5);
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, entry.x, entry.y + 3.0, entry.z,
            20, 2.5, 1.5, 2.5, 0.3);
        level.playSound(null, entry.x, entry.y, entry.z,
            SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 2.0f, 0.7f);
        level.playSound(null, entry.x, entry.y, entry.z,
            SoundEvents.ENDER_DRAGON_FLAP, SoundSource.HOSTILE, 2.0f, 0.6f);

        // The breath river along the dive line.
        double distance = entry.distanceTo(exit);
        int clouds = Math.min(MAX_CLOUDS, (int) Math.floor(distance / 6.0));
        for (int i = 1; i <= clouds; i++) {
            double t = (double) i / (clouds + 1);
            EnderDragonAbilitySupport.spawnBreathCloud(level, dragon,
                entry.x + (exit.x - entry.x) * t,
                entry.y + (exit.y - entry.y) * t,
                entry.z + (exit.z - entry.z) * t,
                CLOUD_RADIUS, CLOUD_DURATION);
        }

        // The dive itself: one teleport, faced along the
        // approach, and handed to her fastest phase.
        dragon.teleportTo(exit.x, exit.y, exit.z);
        dragon.setYRot((float) Math.toDegrees(Math.atan2(-approach.x, approach.z)));
        dragon.getPhaseManager().setPhase(EnderDragonPhase.CHARGING_PLAYER);

        // The exit tear bursts open around her.
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, exit.x, exit.y + 3.0, exit.z,
            30, 3.0, 2.0, 3.0, 0.5);
        level.sendParticles(ParticleTypes.PORTAL, exit.x, exit.y + 3.0, exit.z,
            10, 2.5, 1.5, 2.5, 0.3);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        Mob dragon = ctx.mob();
        if (ticks % 2 == 0) {
            // ENCHANT sparkles streaming off both wings: the
            // wing points sit 7 blocks out along her facing's
            // perpendicular, 2.5 above her feet.
            double yawRad = Math.toRadians(dragon.getYRot());
            double perpX = Math.cos(yawRad);
            double perpZ = Math.sin(yawRad);
            double wingY = dragon.getY() + 2.5;
            level.sendParticles(ParticleTypes.ENCHANT,
                dragon.getX() + perpX * 7.0, wingY, dragon.getZ() + perpZ * 7.0,
                2, 0.8, 0.5, 0.8, 0.3);
            level.sendParticles(ParticleTypes.ENCHANT,
                dragon.getX() - perpX * 7.0, wingY, dragon.getZ() - perpZ * 7.0,
                2, 0.8, 0.5, 0.8, 0.3);
        }
        if (ticks % 4 == 0) {
            level.sendParticles(ParticleTypes.REVERSE_PORTAL,
                dragon.getX(), dragon.getY() + 3.0, dragon.getZ(),
                3, 2.0, 1.5, 2.0, 0.2);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to clean up: no modifiers were applied, the
        // breath clouds expire on their own within 120 ticks,
        // and her phase belongs to the fight again (the dive
        // deliberately leaves her in CHARGING_PLAYER; see the
        // movement note in the class comment).
    }
}
