package mod.advancedmobs.ability.endermite;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Flicker Hop (Endermite ability 4 of 5, mobility).
 *
 * WHAT IT DOES: the mite stops running and starts flickering.
 * Every 12 ticks it teleports a short hop toward its target,
 * and every hop leaves a fading afterimage hanging in the air
 * where it just was, a trail of ghost mites that makes its
 * real path unreadable. Between hops it scuttles at SPEED I,
 * so the whole sequence crosses rooms and gaps in a stutter
 * no pursuer can follow.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the mite's current target within 16 blocks (distance
 *   squared 256).
 * - Duration: exactly 168 ticks (8.4 seconds) from start().
 *   SPEED I (amplifier 0) is applied for 180 ticks at start,
 *   covering the whole activation, for the scuttles between
 *   hops.
 * - The hop, every 12 ticks (ticks 12, 24, ..., 168: at most
 *   14 hops): direction is toward the live target when one
 *   exists, else along the mite's horizontal look direction.
 *   Hop length toward a target is the distance to the target
 *   minus 1.0, clamped between 1.5 and 4.0 blocks, so the mite
 *   lands beside its prey instead of inside it; without a
 *   target the hop is a flat 3.0 blocks. The destination is
 *   searched from the full hop length down in 0.5 block steps
 *   to 1.0: the first spot where the mite's bounding box
 *   collides with nothing wins. The search only checks the
 *   destination box, never the path, so hops cross gaps,
 *   walls of enemies, and low obstacles exactly as the design
 *   says; a hop that finds no free spot is skipped entirely
 *   (no teleport, no afterimage, no particles).
 * - The afterimage: at the position a hop departs from, an
 *   item display of the endermite spawn egg (the one item
 *   model carrying the mite's form and violet shell colors),
 *   uniform scale 1.6, transform context "fixed", full
 *   brightness, is left hanging at the departure point. It
 *   lingers exactly 12 ticks, until the next hop, then is
 *   discarded: at most 1 afterimage is ever alive. Departure
 *   and arrival each burst 6 PORTAL particles, and each hop
 *   plays ENDERMITE_STEP at volume 0.6, pitch 1.5, a dry
 *   skitter at the arrival point.
 * - end(): every tracked afterimage still alive is discarded
 *   and SPEED is removed. If the mite owns a permanent speed
 *   upgrade from its config tree, ScalingSystem's
 *   refreshMissingEffects pass re-applies that permanent
 *   instance on its next processing pass, the same pattern
 *   the other form abilities rely on.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026): the mite teleports; it
 * places and breaks nothing. The afterimages are displays.
 * The mobGriefing gate does not apply to this ability.
 *
 * WHAT IT LOOKS LIKE: a mite that is never quite where the
 * eye lands: purple PORTAL motes, a skitter, and it is four
 * blocks closer with a ghost egg-mite hanging where it stood,
 * then that one fades as the next appears.
 *
 * PERFORMANCE: tick() returns immediately except every 12th
 * tick and the afterimage prune, which walks a list that
 * never holds more than 1 live entry. A hop costs at most 7
 * bounding box collision checks (hop lengths 4.0 down to 1.0
 * in 0.5 steps) plus one display creation. No entity scans at
 * all. Particle budget per activation: at most 14 hops times
 * 12 PORTAL = at most 168 particles.
 *
 * State tag keys: "images" (list of compounds, one per live
 * afterimage: "id" the display's UUID, "expiry" the tick at
 * which it is discarded).
 */
public final class FlickerHopAbility implements MobAbility {

    static final int DURATION_TICKS = 168;
    private static final int HOP_INTERVAL = 12;
    private static final int IMAGE_LINGER_TICKS = 12;
    private static final int SPEED_TICKS = 180;
    private static final double MAX_HOP = 4.0;
    private static final double MIN_HOP = 1.5;
    private static final double FREE_HOP = 3.0;
    private static final float IMAGE_SCALE = 1.6f;

    @Override
    public String id() {
        return "flicker_hop";
    }

    @Override
    public String displayName() {
        return "Flicker Hop";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().put("images", new ListTag());
        ctx.mob().addEffect(new MobEffectInstance(MobEffects.SPEED, SPEED_TICKS, 0));
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        pruneImages(ctx, level);
        if (ctx.ticksActive() % HOP_INTERVAL != 0) {
            return; // Cheap early-out: hops land every 12 ticks only.
        }
        hop(ctx, level);
    }

    /** One flicker: afterimage at the departure point, teleport, PORTAL at both ends. */
    private void hop(AbilityContext ctx, ServerLevel level) {
        Mob mob = ctx.mob();
        LivingEntity target = ctx.target();

        Vec3 direction;
        double hopLength;
        if (target != null) {
            Vec3 toTarget = new Vec3(target.getX() - mob.getX(), 0.0, target.getZ() - mob.getZ());
            double distance = toTarget.length();
            if (distance < 0.01) {
                return; // Already on top of the target; nowhere to hop.
            }
            direction = toTarget.scale(1.0 / distance);
            hopLength = Math.max(MIN_HOP, Math.min(MAX_HOP, distance - 1.0));
        } else {
            Vec3 look = mob.getLookAngle();
            direction = new Vec3(look.x, 0.0, look.z);
            if (direction.lengthSqr() < 0.01) {
                return;
            }
            direction = direction.normalize();
            hopLength = FREE_HOP;
        }

        // Destination search: full hop first, stepping down to
        // 1.0 blocks. Only the destination box is checked, so
        // the path itself may cross anything.
        double destX = 0.0;
        double destZ = 0.0;
        boolean found = false;
        for (double step = hopLength; step >= 1.0; step -= 0.5) {
            double candidateX = mob.getX() + direction.x * step;
            double candidateZ = mob.getZ() + direction.z * step;
            if (isFreeSpot(level, mob, candidateX, mob.getY(), candidateZ)) {
                destX = candidateX;
                destZ = candidateZ;
                found = true;
                break;
            }
        }
        if (!found) {
            return; // Boxed in: this hop is skipped entirely.
        }

        double fromX = mob.getX();
        double fromY = mob.getY();
        double fromZ = mob.getZ();

        // The afterimage hangs where the mite just was.
        Entity image = EndermiteAbilitySupport.createItemDisplay(level,
            Items.ENDERMITE_SPAWN_EGG, IMAGE_SCALE, "fixed");
        if (image != null) {
            image.snapTo(fromX, fromY + 0.25, fromZ);
            if (level.addFreshEntity(image)) {
                CompoundTag entry = new CompoundTag();
                entry.store("id", UUIDUtil.CODEC, image.getUUID());
                entry.putInt("expiry", ctx.ticksActive() + IMAGE_LINGER_TICKS);
                ListTag images = ctx.state().getListOrEmpty("images");
                images.add(entry);
                ctx.state().put("images", images);
            }
        }

        level.sendParticles(ParticleTypes.PORTAL, fromX, fromY + 0.3, fromZ,
            6, 0.25, 0.25, 0.25, 0.2);
        mob.teleportTo(destX, mob.getY(), destZ);
        level.sendParticles(ParticleTypes.PORTAL, destX, mob.getY() + 0.3, destZ,
            6, 0.25, 0.25, 0.25, 0.2);
        level.playSound(null, destX, mob.getY(), destZ,
            SoundEvents.ENDERMITE_STEP, SoundSource.HOSTILE, 0.6f, 1.5f);
    }

    /** Discards afterimages whose linger has run out; keeps the rest tracked. */
    private void pruneImages(AbilityContext ctx, ServerLevel level) {
        ListTag images = ctx.state().getListOrEmpty("images");
        if (images.isEmpty()) {
            return;
        }
        int ticks = ctx.ticksActive();
        ListTag remaining = new ListTag();
        for (int i = 0; i < images.size(); i++) {
            CompoundTag entry = images.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            Entity image = id != null ? level.getEntity(id) : null;
            if (ticks >= entry.getIntOr("expiry", 0)) {
                if (image != null) {
                    image.discard();
                }
                continue;
            }
            if (image != null) {
                remaining.add(entry);
            }
        }
        ctx.state().put("images", remaining);
    }

    /** Whether the mob's bounding box at the given feet position collides with nothing. */
    private boolean isFreeSpot(ServerLevel level, Mob mob, double x, double y, double z) {
        double halfWidth = mob.getBbWidth() / 2.0;
        AABB box = new AABB(x - halfWidth, y, z - halfWidth, x + halfWidth, y + mob.getBbHeight(), z + halfWidth);
        return level.noCollision(mob, box);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        ctx.mob().removeEffect(MobEffects.SPEED);
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        // No afterimage outlives the activation.
        ListTag images = ctx.state().getListOrEmpty("images");
        for (int i = 0; i < images.size(); i++) {
            CompoundTag entry = images.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
            Entity image = id != null ? level.getEntity(id) : null;
            if (image != null) {
                image.discard();
            }
        }
    }
}
