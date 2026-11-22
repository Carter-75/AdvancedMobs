package mod.advancedmobs.ability.enderdragon;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.hurtingprojectile.DragonFireball;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Breathfire Ring (Ender Dragon ability 5 of 5, attack,
 * terrain-shaped area denial).
 *
 * WHAT IT DOES: instead of a single fireball, the dragon
 * spits a spreading fan of 10 dragon fireballs that land in
 * a great circle around her target and bloom into a
 * connected ring wall of lingering dragon breath cloud.
 * Enemies inside the ring are trapped in with her ground
 * attacks, enemies outside are cut off from helping, and
 * crossing the wall means wading through breath damage.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the dragon's current target within 48 blocks (distance
 *   squared 2304).
 * - The fan (start()): the ring center is the target's
 *   position at the cast. 10 fireballs are launched at once
 *   through the support launcher, one at each of 10 points
 *   spaced evenly on a circle of radius 9 around the center,
 *   aimed at half a block above the target's feet height.
 *   The ring spacing is about 5.65 blocks point to point,
 *   and each vanilla breath cloud has radius 3, so the
 *   clouds overlap into a connected wall with no gaps. An
 *   ENDER_DRAGON_SHOOT sound (volume 2.5) and an
 *   8-particle DRAGON_BREATH puff at her head mark the cast.
 * - The blooms are vanilla: a DragonFireball's own impact
 *   behavior creates its breath cloud (radius 3, duration
 *   600 ticks, instant damage), and this ability never
 *   reimplements it. The ring therefore burns for the
 *   vanilla cloud's full 30 seconds before thinning away,
 *   longer than the design's "several seconds": the clouds
 *   are the vanilla projectile's product, with the vanilla
 *   lifetime, stated here so the difference is on record.
 * - Tracking: each launched fireball's UUID and last known
 *   position are stored in the state tag. Every 2 ticks the
 *   tracked set is resolved: a fireball still flying updates
 *   its stored position; a fireball that is gone has landed,
 *   and its landing gets one 8-particle DRAGON_BREATH burst
 *   at its last known position (on top of the vanilla
 *   cloud's own appearance) and leaves the tracked set.
 * - Duration: the ability finishes when every fireball has
 *   landed, with a hard finish at tick 140 for any fireball
 *   that somehow never resolves (flying out of the loaded
 *   arena, for instance). The ring clouds outlive the
 *   ability on their own vanilla lifetime.
 *
 * TERRAIN: the ring changes no blocks. The clouds are area
 * effect cloud entities, not block placement, so the
 * terrain rule and its mobGriefing gate do not apply, and
 * the fireballs themselves set nothing on fire (vanilla
 * DragonFireball behavior: it does not burn).
 *
 * MOVEMENT INTERACTION: none. The ability never moves,
 * teleports, steers, or re-phases the dragon; she keeps
 * whatever her phase controller is doing while the fan
 * flies.
 *
 * PERFORMANCE: start() is 10 projectile spawns and one
 * particle burst. tick() returns immediately on odd ticks;
 * on even ticks it resolves at most 10 UUIDs and rewrites
 * the small tracked list. No entity queries anywhere.
 * Particle budget per activation: cast puff 8, landing
 * bursts at most 10 times 8 = 80, total at most 88 sent
 * particles, not counting the fireballs' vanilla trails and
 * the clouds' own rendering.
 *
 * State tag keys: "shots" (list of compounds, one per
 * fireball still flying: "id" its UUID, "x", "y", "z" its
 * last known position).
 */
public final class BreathfireRingAbility implements MobAbility {

    static final int HARD_FINISH_TICKS = 140;
    private static final int FIREBALL_COUNT = 10;
    private static final double RING_RADIUS = 9.0;

    @Override
    public String id() {
        return "breathfire_ring";
    }

    @Override
    public String displayName() {
        return "Breathfire Ring";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 2304.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        ctx.state().put("shots", new ListTag());
        if (level == null) {
            return;
        }
        Mob dragon = ctx.mob();
        LivingEntity target = ctx.target();
        if (target == null) {
            return;
        }
        double centerX = target.getX();
        double centerY = target.getY() + 0.5;
        double centerZ = target.getZ();

        // The fan: one fireball per ring point.
        ListTag shots = new ListTag();
        for (int i = 0; i < FIREBALL_COUNT; i++) {
            double angle = (i * 2.0 * Math.PI) / FIREBALL_COUNT;
            Vec3 aim = new Vec3(centerX + Math.cos(angle) * RING_RADIUS, centerY,
                                centerZ + Math.sin(angle) * RING_RADIUS);
            DragonFireball fireball = EnderDragonAbilitySupport.launchDragonFireball(ctx, level, aim);
            if (fireball == null) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, fireball.getUUID());
            entry.putDouble("x", fireball.getX());
            entry.putDouble("y", fireball.getY());
            entry.putDouble("z", fireball.getZ());
            shots.add(entry);
        }
        ctx.state().put("shots", shots);

        level.playSound(null, dragon.getX(), dragon.getY(), dragon.getZ(),
            SoundEvents.ENDER_DRAGON_SHOOT, SoundSource.HOSTILE, 2.5f, 1.0f);
        level.sendParticles(EnderDragonAbilitySupport.BREATH_PARTICLE,
            dragon.getX(), dragon.getY() + dragon.getBbHeight() * 0.55, dragon.getZ(),
            8, 1.5, 1.0, 1.5, 0.1);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null || ctx.ticksActive() % 2 != 0) {
            return;
        }
        // Resolve the tracked fireballs: flying ones update
        // their last known position, landed ones burst and
        // leave the set.
        ListTag tracked = ctx.state().getListOrEmpty("shots");
        if (tracked.isEmpty()) {
            return;
        }
        ListTag survivors = new ListTag();
        for (int i = 0; i < tracked.size(); i++) {
            CompoundTag entry = tracked.getCompoundOrEmpty(i);
            UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
            Entity entity = id == null ? null : level.getEntity(id);
            if (entity instanceof DragonFireball fireball && fireball.isAlive()) {
                entry.putDouble("x", fireball.getX());
                entry.putDouble("y", fireball.getY());
                entry.putDouble("z", fireball.getZ());
                survivors.add(entry);
                continue;
            }
            level.sendParticles(EnderDragonAbilitySupport.BREATH_PARTICLE,
                entry.getDoubleOr("x", 0.0), entry.getDoubleOr("y", 0.0), entry.getDoubleOr("z", 0.0),
                8, 0.8, 0.4, 0.8, 0.05);
        }
        ctx.state().put("shots", survivors);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        if (ctx.ticksActive() >= HARD_FINISH_TICKS) {
            return true;
        }
        // Every fireball has landed once the tracked set is
        // empty; the small tick floor keeps a cast whose
        // launches all failed from ending before its first
        // tick has run.
        return ctx.ticksActive() >= 5 && ctx.state().getListOrEmpty("shots").isEmpty();
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to clean up: no modifiers, and the ring
        // clouds are vanilla fireball products that expire on
        // their own 600-tick lifetime. Any fireball still
        // flying at the hard finish stays a vanilla
        // projectile and lands (or not) on its own.
    }
}
