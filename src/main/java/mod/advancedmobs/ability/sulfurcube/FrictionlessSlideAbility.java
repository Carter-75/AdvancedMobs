package mod.advancedmobs.ability.sulfurcube;

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

import java.util.UUID;

/**
 * Frictionless Slide (Sulfur Cube ability 3 of 5, mobility).
 *
 * WHAT IT DOES: the cube flattens, slicks the ground with its
 * own goo, and takes off in a long frictionless slide straight
 * at its target. It ricochets off walls without losing speed,
 * bowling through every enemy in its path, and only stops when
 * it has slammed walls enough times to splat goo in every
 * direction.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the cube's current target within 14 blocks (distance
 *   squared 196) and the cube standing on the ground (a slide
 *   starts from the floor, not mid-hop).
 * - Duration: at most 240 ticks (12 seconds) from start(); a
 *   splat ends it 10 ticks after the splat (see below).
 * - The physics (all transient modifiers added in start(),
 *   removed in end(), ids under advancedmobs:frictionless_*):
 *     FRICTION_MODIFIER  add_value -1.0  (1.0 to 0.0, the
 *                                         design's friction at
 *                                         0: ground contact
 *                                         applies no friction
 *                                         at all to the cube)
 *     MOVEMENT_SPEED     add_value +0.10 (the design's speed
 *                                         line; the slide itself
 *                                         is code-driven, this
 *                                         keeps the cube's own
 *                                         locomotion brisk if
 *                                         the slide is cut
 *                                         short)
 *     SCALE              add_multiplied_base -0.15 (0.85 times:
 *                                         the design's
 *                                         "flattens slightly";
 *                                         SCALE is uniform, so
 *                                         the squash reads as
 *                                         flattening on a cube
 *                                         already hugging the
 *                                         floor at full speed)
 * - The slide itself is code-driven velocity, written every
 *   tick: horizontal velocity is the stored slide direction
 *   times 0.55 blocks per tick (11 blocks per second), and the
 *   vertical component is left alone (0 while grounded, the
 *   live fall velocity while airborne over a ledge), flagged
 *   hurtMarked so it syncs. The direction starts aimed at the
 *   target's position in start() and only changes at walls.
 *   Navigation is stopped in start() so the cube's own goals
 *   do not steer against the slide.
 * - Ricochets: every tick the cube's bounding box is probed
 *   0.45 blocks ahead along the slide direction (one collision
 *   check; two more axis-only probes on a hit). When the way
 *   ahead is blocked, the direction reflects off the wall: the
 *   component whose axis-only probe is blocked flips, and a
 *   corner blocks both and reverses the slide. A ricochet
 *   plays SULFUR_CUBE_BOUNCE at pitch 1.3 and sheds 6
 *   SULFUR_CUBE_GOO. Speed never changes: the reflected
 *   direction is renormalized to the same 0.55.
 * - The splat: wall hits at least 8 ticks apart are counted.
 *   The first two ricochet; the THIRD counted wall hit is the
 *   "hard enough" hit of the design: the slide splats. The
 *   splat bursts 20 SULFUR_CUBE_GOO in every direction, shoves
 *   every enemy within radius 2.5 outward at 1.0 horizontal
 *   and 0.3 up (no damage; the splat is the stop, not a hit),
 *   plays SULFUR_CUBE_BOUNCE at pitch 0.6 and volume 1.2, and
 *   the ability ends 10 ticks later while goo drips off the
 *   cube (2 every 4 ticks).
 * - Bowling: every 2 ticks, enemies inside radius (cube half
 *   width + 1.0) of the cube take 4.0 mob attack damage
 *   credited to the cube and are knocked aside: 0.9 horizontal
 *   away from the cube's center and 0.35 up, hurtMarked. An
 *   enemy can be bowled at most once per 20 ticks (tracked in
 *   the state tag, pruned on each pulse), so standing in the
 *   slide's path is dangerous but not a damage-per-tick shred.
 * - The goo trail: 1 SULFUR_CUBE_GOO at the cube's feet every
 *   tick of the slide.
 * - No blocks are placed or broken anywhere in this ability
 *   (the goo is particles, not blocks), so the mobGriefing
 *   terrain gate does not apply.
 *
 * WHAT IT LOOKS LIKE: a flattened sulfur cube streaking across
 * the arena at full tilt on a trail of green goo flecks,
 * pinging off walls with a rubbery bounce sound and a spray of
 * goo, enemies scattering as it plows through them, until one
 * wall too many stops it in a starburst of goo.
 *
 * PERFORMANCE: per tick the cost is at most 3 bounding box
 * collision probes (usually 1), one velocity write, and 1
 * trail particle; the bowling query runs every 2 ticks on a
 * tight box. Modifiers are resolved at start() and removed at
 * end(). Particle budget per activation, hard caps by
 * construction: trail at most 240, ricochets at most 2 times 6
 * = 12, splat 20 plus drips at most 6, start burst 6, total at
 * most 284 particles.
 *
 * CLEANUP: end() removes all three modifiers by id. The slide
 * direction and bowling records live in the state tag and die
 * with the activation.
 *
 * State tag keys: "dx", "dz" (slide direction, normalized),
 * "wall_hits" (counted wall hits), "last_hit" (tick of the
 * last counted wall hit), "splat_tick" (tick of the splat, 0
 * while unsplatted), "bowled" (list of compounds, one per
 * recently bowled enemy: "id" the UUID, "tick" the tick).
 */
public final class FrictionlessSlideAbility implements MobAbility {

    static final int MAX_TICKS = 240;
    private static final double SLIDE_SPEED = 0.55;
    private static final int SPLAT_WALL_HITS = 3;
    private static final int WALL_HIT_COUNT_GAP = 8;
    private static final int BOWL_DAMAGE = 4;
    private static final int BOWL_IMMUNITY_TICKS = 20;
    private static final Identifier FRICTION_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "frictionless_slide_friction");
    private static final Identifier SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "frictionless_slide_speed");
    private static final Identifier FLATTEN_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "frictionless_slide_flatten");

    @Override
    public String id() {
        return "frictionless_slide";
    }

    @Override
    public String displayName() {
        return "Frictionless Slide";
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
        SulfurCubeAbilitySupport.addModifier(mob, Attributes.FRICTION_MODIFIER,
            new AttributeModifier(FRICTION_ID, -1.0, AttributeModifier.Operation.ADD_VALUE));
        SulfurCubeAbilitySupport.addModifier(mob, Attributes.MOVEMENT_SPEED,
            new AttributeModifier(SPEED_ID, 0.10, AttributeModifier.Operation.ADD_VALUE));
        SulfurCubeAbilitySupport.addModifier(mob, Attributes.SCALE,
            new AttributeModifier(FLATTEN_ID, -0.15, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        mob.getNavigation().stop();

        LivingEntity target = ctx.target();
        double dx = target != null ? target.getX() - mob.getX() : mob.getLookAngle().x;
        double dz = target != null ? target.getZ() - mob.getZ() : mob.getLookAngle().z;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.001) {
            dx = 1.0;
            dz = 0.0;
            length = 1.0;
        }
        CompoundTag state = ctx.state();
        state.putDouble("dx", dx / length);
        state.putDouble("dz", dz / length);
        state.putInt("wall_hits", 0);
        state.putInt("last_hit", -WALL_HIT_COUNT_GAP);
        state.putInt("splat_tick", 0);
        state.put("bowled", new ListTag());

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.SULFUR_CUBE_BOUNCE,
                SoundSource.HOSTILE, 0.8f, 1.2f);
            level.sendParticles(ParticleTypes.SULFUR_CUBE_GOO, mob.getX(), mob.getY() + 0.2, mob.getZ(),
                6, mob.getBbWidth() * 0.5, 0.2, mob.getBbWidth() * 0.5, 0.1);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        int ticks = ctx.ticksActive();

        if (state.getIntOr("splat_tick", 0) > 0) {
            // Splat aftermath: the cube sits and drips.
            if (ticks % 4 == 0) {
                level.sendParticles(ParticleTypes.SULFUR_CUBE_GOO, mob.getX(), mob.getY() + 0.3, mob.getZ(),
                    2, mob.getBbWidth() * 0.5, 0.3, mob.getBbWidth() * 0.5, 0.05);
            }
            return;
        }

        double dx = state.getDoubleOr("dx", 1.0);
        double dz = state.getDoubleOr("dz", 0.0);

        // Wall work: probe ahead, reflect or splat.
        AABB ahead = mob.getBoundingBox().move(dx * 0.45, 0.0, dz * 0.45);
        if (!level.noCollision(mob, ahead)) {
            boolean xBlocked = !level.noCollision(mob, mob.getBoundingBox().move(dx * 0.45, 0.0, 0.0));
            boolean zBlocked = !level.noCollision(mob, mob.getBoundingBox().move(0.0, 0.0, dz * 0.45));
            if (xBlocked) {
                dx = -dx;
            }
            if (zBlocked) {
                dz = -dz;
            }
            if (!xBlocked && !zBlocked) {
                dx = -dx;
                dz = -dz;
            }
            double length = Math.sqrt(dx * dx + dz * dz);
            dx /= length;
            dz /= length;
            state.putDouble("dx", dx);
            state.putDouble("dz", dz);
            if (ticks - state.getIntOr("last_hit", -WALL_HIT_COUNT_GAP) >= WALL_HIT_COUNT_GAP) {
                state.putInt("last_hit", ticks);
                int hits = state.getIntOr("wall_hits", 0) + 1;
                state.putInt("wall_hits", hits);
                if (hits >= SPLAT_WALL_HITS) {
                    splat(ctx, level);
                    return;
                }
            }
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.SULFUR_CUBE_BOUNCE,
                SoundSource.HOSTILE, 0.7f, 1.3f);
            level.sendParticles(ParticleTypes.SULFUR_CUBE_GOO, mob.getX(), mob.getY() + 0.3, mob.getZ(),
                6, mob.getBbWidth() * 0.5, 0.3, mob.getBbWidth() * 0.5, 0.12);
        }

        // The slide: velocity written straight along the stored
        // direction, vertical motion left to gravity.
        double vy = mob.onGround() ? 0.0 : mob.getDeltaMovement().y;
        mob.setDeltaMovement(dx * SLIDE_SPEED, vy, dz * SLIDE_SPEED);
        mob.hurtMarked = true;
        level.sendParticles(ParticleTypes.SULFUR_CUBE_GOO, mob.getX(), mob.getY() + 0.1, mob.getZ(),
            1, mob.getBbWidth() * 0.4, 0.05, mob.getBbWidth() * 0.4, 0.03);

        if (ticks % 2 == 0) {
            bowl(ctx, level);
        }
    }

    /** The bowling pulse: enemies in the slide's path are hit and knocked aside, once per 20 ticks each. */
    private void bowl(AbilityContext ctx, ServerLevel level) {
        Mob mob = ctx.mob();
        int ticks = ctx.ticksActive();
        ListTag bowled = ctx.state().getListOrEmpty("bowled");
        ListTag kept = new ListTag();
        for (int i = 0; i < bowled.size(); i++) {
            CompoundTag entry = bowled.getCompoundOrEmpty(i);
            if (ticks - entry.getIntOr("tick", 0) < BOWL_IMMUNITY_TICKS) {
                kept.add(entry);
            }
        }
        double radius = mob.getBbWidth() / 2.0 + 1.0;
        for (LivingEntity enemy : SulfurCubeAbilitySupport.enemiesInBox(ctx,
                mob.getX(), mob.getY(), mob.getZ(), radius, 1.0, 2.0)) {
            UUID id = enemy.getUUID();
            boolean recent = false;
            for (int i = 0; i < kept.size(); i++) {
                CompoundTag entry = kept.getCompoundOrEmpty(i);
                UUID entryId = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
                if (id.equals(entryId)) {
                    recent = true;
                    break;
                }
            }
            if (recent) {
                continue;
            }
            enemy.hurtServer(level, level.damageSources().mobAttack(mob), BOWL_DAMAGE);
            double awayX = enemy.getX() - mob.getX();
            double awayZ = enemy.getZ() - mob.getZ();
            double length = Math.sqrt(awayX * awayX + awayZ * awayZ);
            double pushX = length > 0.001 ? awayX / length * 0.9 : 0.0;
            double pushZ = length > 0.001 ? awayZ / length * 0.9 : 0.0;
            Vec3 current = enemy.getDeltaMovement();
            enemy.setDeltaMovement(pushX, Math.max(current.y, 0.35), pushZ);
            enemy.hurtMarked = true;
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, id);
            entry.putInt("tick", ticks);
            kept.add(entry);
        }
        ctx.state().put("bowled", kept);
    }

    /** The third counted wall hit: the goo starburst that stops the slide. */
    private void splat(AbilityContext ctx, ServerLevel level) {
        Mob mob = ctx.mob();
        ctx.state().putInt("splat_tick", ctx.ticksActive());
        mob.setDeltaMovement(0.0, mob.getDeltaMovement().y, 0.0);
        mob.hurtMarked = true;
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.SULFUR_CUBE_BOUNCE,
            SoundSource.HOSTILE, 1.2f, 0.6f);
        level.sendParticles(ParticleTypes.SULFUR_CUBE_GOO, mob.getX(), mob.getY() + 0.4, mob.getZ(),
            20, 0.8, 0.5, 0.8, 0.25);
        for (LivingEntity enemy : SulfurCubeAbilitySupport.enemiesInBox(ctx,
                mob.getX(), mob.getY(), mob.getZ(), 2.5, 1.0, 2.0)) {
            double awayX = enemy.getX() - mob.getX();
            double awayZ = enemy.getZ() - mob.getZ();
            double length = Math.sqrt(awayX * awayX + awayZ * awayZ);
            double pushX = length > 0.001 ? awayX / length * 1.0 : 0.0;
            double pushZ = length > 0.001 ? awayZ / length * 1.0 : 0.0;
            Vec3 current = enemy.getDeltaMovement();
            enemy.setDeltaMovement(pushX, Math.max(current.y, 0.3), pushZ);
            enemy.hurtMarked = true;
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        int splatTick = ctx.state().getIntOr("splat_tick", 0);
        return ctx.ticksActive() >= MAX_TICKS || (splatTick > 0 && ctx.ticksActive() >= splatTick + 10);
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        SulfurCubeAbilitySupport.removeModifier(mob, Attributes.FRICTION_MODIFIER, FRICTION_ID);
        SulfurCubeAbilitySupport.removeModifier(mob, Attributes.MOVEMENT_SPEED, SPEED_ID);
        SulfurCubeAbilitySupport.removeModifier(mob, Attributes.SCALE, FLATTEN_ID);
    }
}
