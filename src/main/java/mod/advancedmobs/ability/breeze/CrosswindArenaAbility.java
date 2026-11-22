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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Crosswind Arena (Breeze ability 3 of 5, aura / terrain).
 *
 * WHAT IT DOES: the breeze marks a wide circle on the fight: a
 * ring of white stained glass panes (block displays) orbits
 * slowly at the circle's edge, and inside the circle a constant
 * crosswind blows sideways. Enemies in the arena are steadily
 * pushed around the circle, their shots drift off course, the
 * air itself drags on them, and every five seconds a gust burst
 * shoves everyone inside toward the rim.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   breeze's current target within 18 blocks (distance squared
 *   324). The breeze does not need to stay inside its own arena;
 *   the arena is anchored where it was cast.
 * - Duration: exactly 400 ticks (20 seconds). The center is the
 *   breeze's position at cast, recorded in the state tag; the
 *   radius is 9 blocks.
 * - The ring: 12 block displays of white stained glass pane
 *   (vertical scale 1.6) created in start() at radius 9 around
 *   the center, at center height plus 1.2. Every 2 ticks the
 *   whole ring's angle advances 0.02 radians and each display
 *   is snapped to its new slot (teleport duration 2, so they
 *   glide). Displays are pure visuals: no hitbox, no block
 *   change, and they are discarded in end().
 * - The crosswind pulse, every 5 ticks: one bounded query over
 *   the arena. Each enemy within 9 blocks horizontal of the
 *   center and within 4 blocks vertical of it gains a sideways
 *   velocity push of 0.38 blocks per tick along the
 *   counterclockwise tangent (perpendicular to its radius), and
 *   carries the drag modifier below while it stays inside.
 *   Enemy or ownerless projectiles in the same box gain the
 *   same tangential drift at 0.30, read at the projectile's own
 *   position, so shots fired across the arena bend.
 * - The drag: AIR_DRAG_MODIFIER +1.0 ADD_MULTIPLIED_BASE
 *   (double air drag) as a transient modifier on every enemy
 *   inside, tracked by UUID in the state tag. At each pulse, an
 *   enemy no longer inside (or dead or gone) has the modifier
 *   removed and drops off the list; end() removes it from
 *   everyone still listed. A victim that unloads mid-activation
 *   loses the transient modifier on its own, so nothing can
 *   leak permanently.
 * - The rim shove, every 100 ticks (ticks 100, 200, 300, 400):
 *   every enemy inside takes no damage and is pushed radially
 *   outward at 0.95 blocks per tick horizontal and 0.25 up
 *   (hurtMarked set), with 16 SMALL_GUST particles on a ring at
 *   radius 4 around the center and a BREEZE_WHIRL.
 * - Terrain: the ring is display entities, not blocks, so no
 *   block changes happen and the mobGriefing gate does not
 *   apply (the design lists this ability as terrain for its
 *   arena feel; the standing rule gates real block changes,
 *   and there are none).
 *
 * WHAT IT LOOKS AND SOUNDS LIKE: the orbiting pane ring at the
 * rim, SMALL_GUST bursts at each rim shove, BREEZE_WHIRL at the
 * start and at each shove.
 *
 * PERFORMANCE: the orbit pass every 2 ticks resolves at most 12
 * UUIDs and writes 12 positions; the pulse every 5 ticks is one
 * bounded LivingEntity query plus one bounded Projectile query
 * over the arena box; the dragged list is pruned in the same
 * pass. Nothing runs per tick beyond a modulo check. Particle
 * budget per activation: 4 shoves times 16 = 64, total at most
 * 64 particles sent by this ability.
 *
 * State tag keys: "cx", "cy", "cz" (arena center), "angle"
 * (the ring's current orbit angle in radians), "panes" (list
 * of compounds, one per display: "id", its UUID), "dragged"
 * (list of compounds, one per enemy currently carrying the
 * drag modifier: "id", its UUID).
 */
public final class CrosswindArenaAbility implements MobAbility {

    static final int DURATION_TICKS = 400;
    private static final double RADIUS = 9.0;
    private static final int PANE_COUNT = 12;
    private static final int PULSE_INTERVAL = 5;
    private static final int SHOVE_INTERVAL = 100;
    private static final double TANGENT_PUSH = 0.38;
    private static final double PROJECTILE_DRIFT = 0.30;
    private static final Identifier DRAG_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "crosswind_arena_drag");
    private static final AttributeModifier DRAG_MODIFIER =
        new AttributeModifier(DRAG_ID, 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);

    @Override
    public String id() {
        return "crosswind_arena";
    }

    @Override
    public String displayName() {
        return "Crosswind Arena";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 324.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        var mob = ctx.mob();
        state.putDouble("cx", mob.getX());
        state.putDouble("cy", mob.getY());
        state.putDouble("cz", mob.getZ());
        state.putDouble("angle", 0.0);
        state.put("dragged", new ListTag());

        ListTag panes = new ListTag();
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            for (int i = 0; i < PANE_COUNT; i++) {
                Entity pane = BreezeAbilitySupport.createPaneDisplay(level, 1.6f);
                if (pane == null) {
                    continue;
                }
                double[] slot = slot(state, i, 0.0);
                pane.snapTo(slot[0], slot[1], slot[2], 0.0f, 0.0f);
                if (!level.addFreshEntity(pane)) {
                    continue;
                }
                CompoundTag entry = new CompoundTag();
                entry.store("id", UUIDUtil.CODEC, pane.getUUID());
                panes.add(entry);
            }
        }
        state.put("panes", panes);
        ctx.level().playSound(null, mob.getX(), mob.getY(), mob.getZ(),
            SoundEvents.BREEZE_WHIRL, SoundSource.HOSTILE, 1.2f, 0.8f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        int ticks = ctx.ticksActive();

        // The orbiting ring.
        if (ticks % 2 == 0) {
            double angle = state.getDoubleOr("angle", 0.0) + 0.02;
            state.putDouble("angle", angle);
            ListTag panes = state.getListOrEmpty("panes");
            for (int i = 0; i < panes.size(); i++) {
                CompoundTag entry = panes.getCompoundOrEmpty(i);
                if (!entry.contains("id")) {
                    continue;
                }
                UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
                Entity pane = BreezeAbilitySupport.resolve(level, id);
                if (pane == null) {
                    continue;
                }
                double[] slot = slot(state, i, angle);
                pane.snapTo(slot[0], slot[1], slot[2], 0.0f, 0.0f);
            }
        }

        if (ticks % PULSE_INTERVAL == 0) {
            crosswindPulse(ctx, level, state);
        }

        if (ticks % SHOVE_INTERVAL == 0) {
            rimShove(ctx, level, state);
        }
    }

    /** The ring slot position for pane index at the given orbit angle. */
    private double[] slot(CompoundTag state, int index, double angle) {
        double theta = angle + index * (Math.PI * 2.0 / PANE_COUNT);
        return new double[] {
            state.getDoubleOr("cx", 0.0) + Math.cos(theta) * RADIUS,
            state.getDoubleOr("cy", 0.0) + 1.2,
            state.getDoubleOr("cz", 0.0) + Math.sin(theta) * RADIUS
        };
    }

    /** Whether a point is inside the arena cylinder. */
    private boolean inside(CompoundTag state, double x, double y, double z) {
        double dx = x - state.getDoubleOr("cx", 0.0);
        double dz = z - state.getDoubleOr("cz", 0.0);
        double dy = y - state.getDoubleOr("cy", 0.0);
        return dx * dx + dz * dz <= RADIUS * RADIUS && dy >= -4.0 && dy <= 4.0;
    }

    /** The counterclockwise tangent unit vector at a point, as {tx, tz}. */
    private double[] tangent(CompoundTag state, double x, double z) {
        double rx = x - state.getDoubleOr("cx", 0.0);
        double rz = z - state.getDoubleOr("cz", 0.0);
        double length = Math.sqrt(rx * rx + rz * rz);
        if (length < 1.0e-4) {
            return new double[] {1.0, 0.0};
        }
        return new double[] {-rz / length, rx / length};
    }

    /** One crosswind pulse: the sideways push, the projectile drift, the drag upkeep. */
    private void crosswindPulse(AbilityContext ctx, ServerLevel level, CompoundTag state) {
        double cx = state.getDoubleOr("cx", 0.0);
        double cy = state.getDoubleOr("cy", 0.0);
        double cz = state.getDoubleOr("cz", 0.0);

        List<LivingEntity> enemies = BreezeAbilitySupport.enemiesInBox(ctx, cx, cy, cz, RADIUS, 4.0, 4.0);
        List<UUID> stillInside = new ArrayList<>();
        for (LivingEntity enemy : enemies) {
            if (!inside(state, enemy.getX(), enemy.getY(), enemy.getZ())) {
                continue;
            }
            double[] t = tangent(state, enemy.getX(), enemy.getZ());
            Vec3 velocity = enemy.getDeltaMovement();
            enemy.setDeltaMovement(velocity.x + t[0] * TANGENT_PUSH, velocity.y,
                velocity.z + t[1] * TANGENT_PUSH);
            enemy.hurtMarked = true;
            BreezeAbilitySupport.addModifier(enemy, Attributes.AIR_DRAG_MODIFIER, DRAG_MODIFIER);
            stillInside.add(enemy.getUUID());
        }

        // Drag upkeep: anyone recorded who is no longer inside
        // loses the modifier and drops off the list.
        ListTag dragged = state.getListOrEmpty("dragged");
        ListTag kept = new ListTag();
        for (int i = 0; i < dragged.size(); i++) {
            CompoundTag entry = dragged.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (id == null) {
                continue;
            }
            if (stillInside.contains(id)) {
                CompoundTag keptEntry = new CompoundTag();
                keptEntry.store("id", UUIDUtil.CODEC, id);
                kept.add(keptEntry);
                continue;
            }
            Entity gone = BreezeAbilitySupport.resolve(level, id);
            if (gone instanceof LivingEntity living) {
                BreezeAbilitySupport.removeModifier(living, Attributes.AIR_DRAG_MODIFIER, DRAG_ID);
            }
        }
        for (UUID id : stillInside) {
            boolean recorded = false;
            for (int i = 0; i < kept.size(); i++) {
                if (id.equals(kept.getCompoundOrEmpty(i).read("id", UUIDUtil.CODEC).orElse(null))) {
                    recorded = true;
                    break;
                }
            }
            if (!recorded) {
                CompoundTag entry = new CompoundTag();
                entry.store("id", UUIDUtil.CODEC, id);
                kept.add(entry);
            }
        }
        state.put("dragged", kept);

        // Projectile drift: shots crossing the arena bend sideways.
        AABB box = new AABB(cx - RADIUS, cy - 4.0, cz - RADIUS, cx + RADIUS, cy + 4.0, cz + RADIUS);
        List<Projectile> projectiles = level.getEntitiesOfClass(Projectile.class, box);
        for (Projectile projectile : projectiles) {
            if (!BreezeAbilitySupport.isHostileProjectile(ctx, projectile)) {
                continue;
            }
            if (!inside(state, projectile.getX(), projectile.getY(), projectile.getZ())) {
                continue;
            }
            double[] t = tangent(state, projectile.getX(), projectile.getZ());
            Vec3 velocity = projectile.getDeltaMovement();
            projectile.setDeltaMovement(velocity.x + t[0] * PROJECTILE_DRIFT, velocity.y,
                velocity.z + t[1] * PROJECTILE_DRIFT);
        }
    }

    /** One rim shove: everyone inside is pushed toward the rim. */
    private void rimShove(AbilityContext ctx, ServerLevel level, CompoundTag state) {
        double cx = state.getDoubleOr("cx", 0.0);
        double cy = state.getDoubleOr("cy", 0.0);
        double cz = state.getDoubleOr("cz", 0.0);
        List<LivingEntity> enemies = BreezeAbilitySupport.enemiesInBox(ctx, cx, cy, cz, RADIUS, 4.0, 4.0);
        for (LivingEntity enemy : enemies) {
            if (!inside(state, enemy.getX(), enemy.getY(), enemy.getZ())) {
                continue;
            }
            double dx = enemy.getX() - cx;
            double dz = enemy.getZ() - cz;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            double scale = horizontal > 1.0e-4 ? 0.95 / horizontal : 0.0;
            Vec3 velocity = enemy.getDeltaMovement();
            enemy.setDeltaMovement(velocity.x + dx * scale, 0.25, velocity.z + dz * scale);
            enemy.hurtMarked = true;
        }
        for (int i = 0; i < 16; i++) {
            double theta = i * (Math.PI * 2.0 / 16.0);
            level.sendParticles(ParticleTypes.SMALL_GUST,
                cx + Math.cos(theta) * 4.0, cy + 1.0, cz + Math.sin(theta) * 4.0,
                1, 0.1, 0.2, 0.1, 0.02);
        }
        level.playSound(null, cx, cy, cz, SoundEvents.BREEZE_WHIRL, SoundSource.HOSTILE, 1.0f, 0.9f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        ListTag dragged = state.getListOrEmpty("dragged");
        for (int i = 0; i < dragged.size(); i++) {
            CompoundTag entry = dragged.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
            Entity entity = BreezeAbilitySupport.resolve(level, id);
            if (entity instanceof LivingEntity living) {
                BreezeAbilitySupport.removeModifier(living, Attributes.AIR_DRAG_MODIFIER, DRAG_ID);
            }
        }
        ListTag panes = state.getListOrEmpty("panes");
        for (int i = 0; i < panes.size(); i++) {
            CompoundTag entry = panes.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
            Entity pane = BreezeAbilitySupport.resolve(level, id);
            if (pane != null) {
                pane.discard();
            }
        }
    }
}
