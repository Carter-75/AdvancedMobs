package mod.advancedmobs.ability.ghast;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Nether Rift Maw (Ghast ability 4 of 5, terrain control).
 *
 * WHAT IT DOES: beneath its enemies the ghast tears open a
 * swirling maw of nether portal light. Everything standing on
 * the ground inside it is dragged slowly toward its center,
 * too weak a pull to be a prison but strong enough to ruin
 * aim and herd enemies into a clump, and the ghast lobs a
 * fireball into the middle of the clump while it lasts. After
 * eight seconds the rift snaps shut in a bright flash with one
 * final hard inward yank.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists within 20 blocks
 *   (distance squared 400).
 * - The rift (start()): the center is the target's position
 *   at cast time, seated on the ground by the shared surface
 *   scan anchored on the target's block height, and recorded
 *   in the state tag. The rift is a place, not a tracker: it
 *   does not follow the target afterwards. A GHAST_WARN
 *   (volume 1.5, pitch 0.7) announces the tear.
 * - The pull (tick(), every 2 ticks): one bounded enemy query
 *   in radius 6.0 around the center (2 below to 3 above its
 *   surface). Every enemy that is ON THE GROUND (the design
 *   drags what stands in the rift; flyers and jumpers at the
 *   top of their arc are exempt) and more than 0.75 blocks
 *   from the center gets a velocity nudge toward the center:
 *   its current velocity plus 0.09 blocks per tick along the
 *   normalized horizontal direction to the center, with
 *   hurtMarked set so player clients apply it. Applied every
 *   2 ticks, the drag sums to roughly 0.9 blocks per second
 *   against a walking enemy: fightable, exactly as designed,
 *   but it bunches a group standing in the rift.
 * - The focused fireball (tick 80, the halfway mark): when a
 *   live target exists, the ghast launches one ordinary
 *   LargeFireball (explosion power 1, the vanilla value) from
 *   its mouth straight at the rift center, the "one big
 *   blast" the pull has been herding for. Its explosion is
 *   vanilla LargeFireball behavior, which checks the
 *   mobGriefing game rule itself for block damage.
 * - The snap (tick 160): one bounded enemy query in radius
 *   6.0, every enemy in it (grounded or not) has its velocity
 *   SET toward the center at 0.9 blocks per tick horizontal
 *   with a 0.1 lift, hurtMarked set. A FLASH particle
 *   (ColorParticleOption, ARGB 0xFFFFD9A0, a hot white-gold
 *   flash) bursts at the center with 10 PORTAL particles, and
 *   a GHAST_SCREAM (volume 1.2, pitch 1.4) cracks it shut.
 * - Duration: the ability finishes at tick 165, five ticks
 *   after the snap, so the yank is seen in flight before the
 *   cooldown starts.
 *
 * WHAT IT LOOKS LIKE: a turning spiral of PORTAL and
 * REVERSE_PORTAL motes on the ground (every 2 ticks, 4 of
 * each at radius 3.5, the ring turning with the activation
 * clock and slowly contracting: radius 3.5 minus 1.5 times
 * the elapsed fraction), enemies visibly sliding inward
 * against their own movement, a fireball spearing into the
 * middle of the clump, then a white-gold flash and everything
 * jerking to the center at once.
 *
 * PERFORMANCE: the pull and the spiral share one pass every
 * 2 ticks: one bounded enemy query and 8 particle spawns.
 * The fireball is one spawn on one tick; the snap is one
 * bounded query on one tick. No per-tick work at all on odd
 * ticks. Particle budget per activation: spiral 80 pulses
 * times 8 = 640, snap 11, total at most 651 particles.
 *
 * CLEANUP: end() does nothing: the rift is particles and
 * velocity only, and nothing persists past the snap.
 *
 * TERRAIN: the rift itself is a zone effect (particles and
 * velocity), not block placement, so the mobGriefing gate
 * does not apply to it. The focused fireball's explosion
 * block damage is vanilla's own gated behavior.
 *
 * State tag keys: "cx", "cy", "cz" (the rift center, doubles,
 * cy being the surface height).
 */
public final class NetherRiftMawAbility implements MobAbility {

    private static final int FIREBALL_TICK = 80;
    private static final int SNAP_TICK = 160;
    private static final int FINISH_TICKS = 165;
    private static final double RADIUS = 6.0;
    private static final ColorParticleOption SNAP_FLASH =
        ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFD9A0);

    @Override
    public String id() {
        return "nether_rift_maw";
    }

    @Override
    public String displayName() {
        return "Nether Rift Maw";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return ctx.target() != null && ctx.mob().distanceToSqr(ctx.target()) <= 400.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        LivingEntity target = ctx.target();
        Mob mob = ctx.mob();
        double cx = target != null ? target.getX() : mob.getX();
        double cz = target != null ? target.getZ() : mob.getZ();
        int refY = target != null ? target.getBlockY() : mob.getBlockY();
        double cy = refY;
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            cy = GhastAbilitySupport.findSurfaceY(level, (int) Math.floor(cx), (int) Math.floor(cz), refY);
            level.playSound(null, cx, cy, cz, SoundEvents.GHAST_WARN, SoundSource.HOSTILE, 1.5f, 0.7f);
        }
        state.putDouble("cx", cx);
        state.putDouble("cy", cy);
        state.putDouble("cz", cz);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        CompoundTag state = ctx.state();
        double cx = state.getDoubleOr("cx", ctx.mob().getX());
        double cy = state.getDoubleOr("cy", ctx.mob().getY());
        double cz = state.getDoubleOr("cz", ctx.mob().getZ());

        if (ticks == FIREBALL_TICK) {
            launchFocusedFireball(ctx, level, cx, cy, cz);
        }
        if (ticks == SNAP_TICK) {
            snapShut(ctx, level, cx, cy, cz);
            return;
        }
        if (ticks % 2 != 0 || ticks > SNAP_TICK) {
            return; // Cheap early-out: the pull and spiral run every 2 ticks only.
        }

        // The spiral (see the class comment): a turning,
        // slowly contracting ring of portal motes.
        double ringRadius = 3.5 - 1.5 * (ticks / (double) SNAP_TICK);
        double turn = ticks * 0.12;
        for (int i = 0; i < 4; i++) {
            double angle = turn + i * (Math.PI / 2.0);
            level.sendParticles(ParticleTypes.PORTAL,
                cx + Math.cos(angle) * ringRadius, cy + 0.3, cz + Math.sin(angle) * ringRadius,
                1, 0.0, 0.05, 0.0, 0.0);
            level.sendParticles(ParticleTypes.REVERSE_PORTAL,
                cx - Math.cos(angle) * ringRadius, cy + 0.3, cz - Math.sin(angle) * ringRadius,
                1, 0.0, 0.05, 0.0, 0.0);
        }

        // The pull (see the class comment).
        List<LivingEntity> victims = GhastAbilitySupport.enemiesInBox(ctx, cx, cy, cz, RADIUS, 2.0, 3.0);
        for (LivingEntity victim : victims) {
            if (!victim.onGround()) {
                continue;
            }
            Vec3 toCenter = new Vec3(cx - victim.getX(), 0.0, cz - victim.getZ());
            if (toCenter.lengthSqr() < 0.5625) {
                continue; // Already at the center (within 0.75 blocks).
            }
            toCenter = toCenter.normalize();
            Vec3 velocity = victim.getDeltaMovement();
            victim.setDeltaMovement(velocity.x + toCenter.x * 0.09, velocity.y, velocity.z + toCenter.z * 0.09);
            victim.hurtMarked = true;
        }
    }

    /** The halfway fireball into the rift center (see the class comment). */
    private static void launchFocusedFireball(AbilityContext ctx, ServerLevel level,
                                              double cx, double cy, double cz) {
        Mob mob = ctx.mob();
        if (ctx.target() == null) {
            return;
        }
        Vec3 from = new Vec3(mob.getX(), mob.getY() + mob.getBbHeight() * 0.4, mob.getZ());
        Vec3 to = new Vec3(cx, cy + 0.5, cz);
        Vec3 direction = to.subtract(from);
        if (direction.lengthSqr() < 1.0e-4) {
            return;
        }
        direction = direction.normalize();
        LargeFireball fireball = new LargeFireball(level, mob, direction.scale(0.15), 1);
        Vec3 mouth = from.add(direction.scale(2.0)).add(0.0, -0.5, 0.0);
        fireball.snapTo(mouth.x, mouth.y, mouth.z, 0.0f, 0.0f);
        fireball.setDeltaMovement(direction.scale(0.5));
        if (level.addFreshEntity(fireball)) {
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.GHAST_SHOOT,
                SoundSource.HOSTILE, 1.2f, 0.9f);
        }
    }

    /** The snap shut: flash and the final inward yank (see the class comment). */
    private static void snapShut(AbilityContext ctx, ServerLevel level, double cx, double cy, double cz) {
        List<LivingEntity> victims = GhastAbilitySupport.enemiesInBox(ctx, cx, cy, cz, RADIUS, 2.0, 3.0);
        for (LivingEntity victim : victims) {
            Vec3 toCenter = new Vec3(cx - victim.getX(), 0.0, cz - victim.getZ());
            if (toCenter.lengthSqr() < 1.0e-4) {
                toCenter = new Vec3(1.0, 0.0, 0.0);
            }
            toCenter = toCenter.normalize();
            victim.setDeltaMovement(toCenter.x * 0.9, 0.1, toCenter.z * 0.9);
            victim.hurtMarked = true;
        }
        level.sendParticles(SNAP_FLASH, cx, cy + 0.6, cz, 1, 0.0, 0.0, 0.0, 0.0);
        level.sendParticles(ParticleTypes.PORTAL, cx, cy + 0.4, cz, 10, 1.5, 0.3, 1.5, 0.2);
        level.playSound(null, cx, cy, cz, SoundEvents.GHAST_SCREAM, SoundSource.HOSTILE, 1.2f, 1.4f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= FINISH_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing persists past the snap: the rift was
        // particles and velocity only (see the class comment).
    }
}
