package mod.advancedmobs.ability.breeze;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Gust Step (Breeze ability 4 of 5, mobility).
 *
 * WHAT IT DOES: the breeze collapses into a streak of wind and
 * reappears up to fifteen blocks away, on the far side of its
 * target's fight. Between the two points a short lived wind
 * tunnel hangs in the air: anything that touches it, friend or
 * foe, is flung along the tunnel's direction, so one step can
 * reposition the breeze and scatter a whole fight line at once.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   breeze's current target within 20 blocks (distance squared
 *   400).
 * - The step (all in start()): the destination is searched on
 *   the side of the breeze away from its target. Distances 15,
 *   12, 9, and 6 blocks are tried in that order; at each
 *   distance, five headings are tried (straight away, then plus
 *   or minus 0.6 radians, then plus or minus 1.2 radians); at
 *   each heading, five heights are tried (the breeze's own
 *   height, then plus 1, minus 1, plus 2, minus 2). The first
 *   spot where the breeze's bounding box collides with nothing
 *   wins. If no spot fits, the breeze stays where it is and the
 *   tunnel is a zero length point at its feet (the rest of the
 *   ability still runs). The breeze teleports to the winning
 *   spot; its fall distance is reset.
 * - The dash drag: AIR_DRAG_MODIFIER +2.0 ADD_MULTIPLIED_BASE
 *   (triple air drag) on the breeze itself for the whole
 *   activation, removed in end(). This is the design's drag on
 *   self during the dash: while the tunnel lives the breeze's
 *   own leaps and slides bleed speed fast, pinning it near its
 *   landing spot instead of letting it chain hops.
 * - The tunnel: the segment from the departure point to the
 *   destination, alive for the activation's 100 ticks (5
 *   seconds). Every 5 ticks, one bounded query around the
 *   segment: every living entity within 1.6 blocks of the
 *   segment that has not been flung yet by this activation and
 *   is the caster's target, an enemy of the caster, or an ally
 *   of the caster (the package rules in BreezeAbilitySupport)
 *   is flung: velocity set to the tunnel direction at 1.7
 *   blocks per tick plus 0.25 up (hurtMarked set), and its UUID
 *   is recorded so it can only be flung once per activation.
 *   The caster itself is never flung.
 * - end(): the self drag modifier is removed by id. The tunnel
 *   is pure state and particles, so nothing else needs cleanup.
 *
 * WHAT IT LOOKS AND SOUNDS LIKE: at the step, 12 GUST and 8
 * CLOUD particles burst at both the departure and the arrival
 * point, with BREEZE_JUMP at the departure and BREEZE_LAND at
 * the arrival. While the tunnel lives, every 4 ticks one
 * particle is drawn at each point 2 blocks apart along the
 * segment, alternating GUST and CLOUD, so the tunnel reads as a
 * dotted streak of wind.
 *
 * PERFORMANCE: the destination search in start() is at most
 * 100 collision checks, once. tick() draws the tunnel every 4
 * ticks (at most 8 points) and runs one bounded query every 5
 * ticks around the segment's midpoint; the flung list only
 * grows. Particle budget per activation: the step bursts 40,
 * the tunnel draws 25 times at most 8 = 200, total at most 240.
 *
 * State tag keys: "fx", "fy", "fz" (departure point), "tx",
 * "ty", "tz" (destination), "dx", "dy", "dz" (the tunnel's
 * unit direction), "flung" (list of compounds, one per entity
 * already flung: "id", its UUID).
 */
public final class GustStepAbility implements MobAbility {

    static final int DURATION_TICKS = 100;
    private static final double TOUCH_DISTANCE = 1.6;
    private static final double FLING_SPEED = 1.7;
    private static final Identifier DRAG_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "gust_step_drag");

    @Override
    public String id() {
        return "gust_step";
    }

    @Override
    public String displayName() {
        return "Gust Step";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 400.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        Mob mob = ctx.mob();
        ServerLevel level = ctx.serverLevel();

        double fx = mob.getX();
        double fy = mob.getY();
        double fz = mob.getZ();
        state.putDouble("fx", fx);
        state.putDouble("fy", fy);
        state.putDouble("fz", fz);
        state.put("flung", new ListTag());

        // The away heading: directly away from the target,
        // horizontal. With no live target (should not happen,
        // the framework gates on combat), fall back to the
        // breeze's own facing.
        LivingEntity target = ctx.target();
        double awayX;
        double awayZ;
        if (target != null) {
            awayX = fx - target.getX();
            awayZ = fz - target.getZ();
        } else {
            Vec3 look = mob.getLookAngle();
            awayX = look.x;
            awayZ = look.z;
        }
        double awayLength = Math.sqrt(awayX * awayX + awayZ * awayZ);
        if (awayLength < 1.0e-4) {
            awayX = 1.0;
            awayZ = 0.0;
            awayLength = 1.0;
        }
        awayX /= awayLength;
        awayZ /= awayLength;

        double destX = fx;
        double destY = fy;
        double destZ = fz;
        if (level != null) {
            double[] found = findDestination(ctx, level, fx, fy, fz, awayX, awayZ);
            if (found != null) {
                destX = found[0];
                destY = found[1];
                destZ = found[2];
            }
        }
        state.putDouble("tx", destX);
        state.putDouble("ty", destY);
        state.putDouble("tz", destZ);

        Vec3 from = new Vec3(fx, fy, fz);
        Vec3 to = new Vec3(destX, destY, destZ);
        Vec3 direction = to.subtract(from);
        double length = direction.length();
        Vec3 unit = length > 1.0e-4 ? direction.scale(1.0 / length) : new Vec3(awayX, 0.0, awayZ);
        state.putDouble("dx", unit.x);
        state.putDouble("dy", unit.y);
        state.putDouble("dz", unit.z);

        if (level != null) {
            level.sendParticles(ParticleTypes.GUST, fx, fy + 0.8, fz, 12, 0.4, 0.5, 0.4, 0.1);
            level.sendParticles(ParticleTypes.CLOUD, fx, fy + 0.8, fz, 8, 0.4, 0.5, 0.4, 0.05);
            level.playSound(null, fx, fy, fz, SoundEvents.BREEZE_JUMP, SoundSource.HOSTILE, 1.0f, 1.0f);
        }
        mob.teleportTo(destX, destY, destZ);
        mob.resetFallDistance();
        if (level != null) {
            level.sendParticles(ParticleTypes.GUST, destX, destY + 0.8, destZ, 12, 0.4, 0.5, 0.4, 0.1);
            level.sendParticles(ParticleTypes.CLOUD, destX, destY + 0.8, destZ, 8, 0.4, 0.5, 0.4, 0.05);
            level.playSound(null, destX, destY, destZ, SoundEvents.BREEZE_LAND, SoundSource.HOSTILE, 1.0f, 1.0f);
        }

        BreezeAbilitySupport.addModifier(mob, Attributes.AIR_DRAG_MODIFIER,
            new AttributeModifier(DRAG_ID, 2.0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
    }

    /**
     * The destination search described in the class comment.
     * Returns {x, y, z} of the first collision-free spot, or
     * null when nothing fits.
     */
    private double[] findDestination(AbilityContext ctx, ServerLevel level,
                                     double fx, double fy, double fz, double awayX, double awayZ) {
        Mob mob = ctx.mob();
        double[] distances = {15.0, 12.0, 9.0, 6.0};
        double[] angleOffsets = {0.0, 0.6, -0.6, 1.2, -1.2};
        double[] heightOffsets = {0.0, 1.0, -1.0, 2.0, -2.0};
        double halfWidth = mob.getBbWidth() / 2.0;
        double height = mob.getBbHeight();
        for (double distance : distances) {
            for (double offset : angleOffsets) {
                double cos = Math.cos(offset);
                double sin = Math.sin(offset);
                double dirX = awayX * cos - awayZ * sin;
                double dirZ = awayX * sin + awayZ * cos;
                double cx = fx + dirX * distance;
                double cz = fz + dirZ * distance;
                for (double heightOffset : heightOffsets) {
                    double cy = fy + heightOffset;
                    AABB box = new AABB(cx - halfWidth, cy, cz - halfWidth,
                                        cx + halfWidth, cy + height, cz + halfWidth);
                    if (level.noCollision(mob, box)) {
                        return new double[] {cx, cy, cz};
                    }
                }
            }
        }
        return null;
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        int ticks = ctx.ticksActive();
        Vec3 from = new Vec3(state.getDoubleOr("fx", 0.0), state.getDoubleOr("fy", 0.0),
                             state.getDoubleOr("fz", 0.0));
        Vec3 to = new Vec3(state.getDoubleOr("tx", 0.0), state.getDoubleOr("ty", 0.0),
                           state.getDoubleOr("tz", 0.0));

        // The tunnel streak.
        if (ticks % 4 == 0) {
            double length = from.distanceTo(to);
            int points = Math.max(1, (int) (length / 2.0));
            for (int i = 0; i <= points; i++) {
                double t = points == 0 ? 0.0 : (double) i / points;
                Vec3 point = from.lerp(to, t);
                level.sendParticles(i % 2 == 0 ? ParticleTypes.GUST : ParticleTypes.CLOUD,
                    point.x, point.y + 0.8, point.z, 1, 0.15, 0.3, 0.15, 0.01);
            }
        }

        // The fling check.
        if (ticks % 5 == 0) {
            flingTouched(ctx, level, state, from, to);
        }
    }

    /** One tunnel fling pass (see the class comment). */
    private void flingTouched(AbilityContext ctx, ServerLevel level, CompoundTag state, Vec3 from, Vec3 to) {
        Mob mob = ctx.mob();
        Vec3 mid = from.lerp(to, 0.5);
        double radius = from.distanceTo(to) / 2.0 + 2.0;
        AABB box = new AABB(mid.x - radius, mid.y - 3.0, mid.z - radius,
                            mid.x + radius, mid.y + 3.0, mid.z + radius);
        List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class, box,
            e -> e != mob && e.isAlive()
                && (BreezeAbilitySupport.isEnemy(ctx, e) || BreezeAbilitySupport.isAlly(ctx, e)));
        ListTag flung = state.getListOrEmpty("flung");
        Vec3 direction = new Vec3(state.getDoubleOr("dx", 1.0), state.getDoubleOr("dy", 0.0),
                                  state.getDoubleOr("dz", 0.0));
        for (LivingEntity candidate : candidates) {
            if (alreadyFlung(flung, candidate)) {
                continue;
            }
            Vec3 center = candidate.position().add(0.0, candidate.getBbHeight() / 2.0, 0.0);
            if (BreezeAbilitySupport.distanceToSegment(center, from, to) > TOUCH_DISTANCE) {
                continue;
            }
            candidate.setDeltaMovement(direction.x * FLING_SPEED, 0.25, direction.z * FLING_SPEED);
            candidate.hurtMarked = true;
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, candidate.getUUID());
            flung.add(entry);
        }
        state.put("flung", flung);
    }

    private boolean alreadyFlung(ListTag flung, LivingEntity candidate) {
        for (int i = 0; i < flung.size(); i++) {
            CompoundTag entry = flung.getCompoundOrEmpty(i);
            if (entry.contains("id")
                && candidate.getUUID().equals(entry.read("id", UUIDUtil.CODEC).orElse(null))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        BreezeAbilitySupport.removeModifier(ctx.mob(), Attributes.AIR_DRAG_MODIFIER, DRAG_ID);
    }
}
