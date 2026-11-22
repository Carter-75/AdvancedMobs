package mod.advancedmobs.ability.pillager;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Firework Broadside (Pillager ability 2 of 5, attack).
 *
 * WHAT IT DOES: the pillager loads its crossbow with
 * fireworks and lets fly a full broadside: five firework
 * rockets streak out in a fan over the enemy, burst in
 * colored starbursts among the ranks, and leave drifting
 * smoke between the lines. Wildly inaccurate and completely
 * spectacular.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the pillager's current target within 24 blocks (distance
 *   squared 576).
 * - The broadside (start()): 5 real FireworkRocketEntity
 *   rockets, owned by the pillager, launched from his eye
 *   position in the same tick. Aim is the direction to the
 *   target's mid-body, tilted up by +0.25 on Y (renormalized)
 *   so the fan arcs overhead and among the ranks, then
 *   fanned around the vertical axis by -0.36, -0.18, 0,
 *   +0.18, +0.36 radians. Rocket speed 1.5, inaccuracy 0:
 *   the fan is the inaccuracy, by design. Each rocket's
 *   stack carries a Fireworks component with flight duration
 *   1 (vanilla lifetime about 10 to 15 ticks, so bursts land
 *   roughly 15 to 25 blocks out) and exactly one explosion:
 *   the center rocket is a STAR, the others alternate
 *   LARGE_BALL and SMALL_BALL, colors cycle red, orange,
 *   yellow, lime, purple (vanilla dye RGB), twinkle on, no
 *   trail. FIREWORK_ROCKET_LAUNCH and CROSSBOW_SHOOT sound
 *   once at the launch.
 * - Vanilla burst damage (part of the design, verified
 *   against the 26.2 jar): when a rocket bursts, vanilla
 *   itself deals fireworks damage of 5.0 + 2.0 per explosion
 *   (7.0 for these one-explosion rockets) to living entities
 *   within 5 blocks, falling off with distance, credited to
 *   the rocket's owner through the fireworks damage source.
 *   That is the broadside's main area damage and it needs no
 *   code.
 * - Code shrapnel (the design's "area damage in code"): each
 *   rocket is tracked by UUID with its last position recorded
 *   every tick. When a tracked rocket vanishes (burst at the
 *   end of its fuse, or died on impact), the code bursts at
 *   its last recorded position: 10 FIREWORK particles and 5
 *   SMOKE, and every enemy (package enemy rule) within 2.5
 *   blocks takes 2.0 mob-attack damage credited to the
 *   pillager: the hot heart of the starburst, on top of
 *   vanilla's wider falloff. A rocket lost to a chunk unload
 *   mid-flight reads as a burst at its last seen position;
 *   that accepted edge is shared with every tracked
 *   projectile in this mod.
 * - Duration: until all 5 rockets have burst, hard finish at
 *   120 ticks (a rocket that somehow never resolves is
 *   abandoned, not chased).
 *
 * WHAT IT LOOKS LIKE: five colored rockets fanning out in a
 * rising arc, twinkling starbursts popping over the enemy
 * line one after another, gray SMOKE hanging where each
 * burst went off.
 *
 * TERRAIN: fireworks change no blocks (a vanilla firework
 * burst is light, particles, and entity damage only), so the
 * terrain gate does not apply in either mobGriefing mode.
 *
 * PERFORMANCE: start() spawns 5 entities once. tick()
 * resolves at most 5 UUIDs and writes 3 doubles per live
 * rocket; bursts are one bounded query each, at most 5 per
 * activation. Particle budget per activation: 5 bursts times
 * (10 FIREWORK + 5 SMOKE) = 75 sent particles (the rockets'
 * own vanilla burst particles are the entity's, not the
 * ability's). No per-tick allocations beyond the resolved
 * references.
 *
 * CLEANUP: end() does nothing. The rockets are free
 * projectiles: if the pillager dies mid-flight, the framework
 * ends the activation and any rocket still flying finishes
 * as an ordinary vanilla firework (its vanilla burst damage
 * still lands; only the code shrapnel stops, because the
 * tracking stops).
 *
 * State tag keys: "rockets" (list of compounds, one per
 * rocket: "id" the UUID, "lx"/"ly"/"lz" the last recorded
 * position, "done" 1 once its burst has been run), "bursts"
 * (how many rockets have burst).
 */
public final class FireworkBroadsideAbility implements MobAbility {

    private static final int ROCKET_COUNT = 5;
    private static final int HARD_FINISH_TICKS = 120;
    private static final double SHRAPNEL_DAMAGE = 2.0;
    private static final double SHRAPNEL_RADIUS = 2.5;
    private static final double[] FAN_ANGLES = {-0.36, -0.18, 0.0, 0.18, 0.36};
    private static final int[] COLORS = {0xB02E26, 0xF9801D, 0xFED83D, 0x80C71F, 0x8932B8};
    private static final FireworkExplosion.Shape[] SHAPES = {
        FireworkExplosion.Shape.LARGE_BALL, FireworkExplosion.Shape.SMALL_BALL,
        FireworkExplosion.Shape.STAR,
        FireworkExplosion.Shape.SMALL_BALL, FireworkExplosion.Shape.LARGE_BALL
    };

    @Override
    public String id() {
        return "firework_broadside";
    }

    @Override
    public String displayName() {
        return "Firework Broadside";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 576.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            ctx.state().put("rockets", new ListTag());
            ctx.state().putInt("bursts", ROCKET_COUNT);
            return;
        }
        Mob mob = ctx.mob();
        LivingEntity target = ctx.target();

        Vec3 base;
        if (target != null) {
            base = new Vec3(target.getX() - mob.getX(),
                            (target.getY() + target.getBbHeight() * 0.6) - mob.getEyeY(),
                            target.getZ() - mob.getZ());
            base = base.lengthSqr() > 1.0e-6 ? base.normalize() : mob.getLookAngle();
        } else {
            base = mob.getLookAngle();
        }
        // The overhead tilt: the fan arcs up before it comes
        // down among the ranks.
        base = new Vec3(base.x, base.y + 0.25, base.z).normalize();

        Vec3 eye = mob.getEyePosition();
        ListTag rockets = new ListTag();
        for (int i = 0; i < ROCKET_COUNT; i++) {
            double angle = FAN_ANGLES[i];
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            Vec3 dir = new Vec3(base.x * cos + base.z * sin, base.y, -base.x * sin + base.z * cos).normalize();
            FireworkRocketEntity rocket = new FireworkRocketEntity(level, mob, eye.x, eye.y, eye.z,
                buildRocketStack(ctx.random(), i));
            rocket.shoot(dir.x, dir.y, dir.z, 1.5f, 0.0f);
            if (!level.addFreshEntity(rocket)) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, rocket.getUUID());
            entry.putDouble("lx", rocket.getX());
            entry.putDouble("ly", rocket.getY());
            entry.putDouble("lz", rocket.getZ());
            entry.putInt("done", 0);
            rockets.add(entry);
        }
        ctx.state().put("rockets", rockets);
        ctx.state().putInt("bursts", 0);
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.FIREWORK_ROCKET_LAUNCH,
            SoundSource.HOSTILE, 1.0f, 1.0f);
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.CROSSBOW_SHOOT,
            SoundSource.HOSTILE, 1.0f, 0.8f);
    }

    /** One broadside rocket stack: flight 1, a single twinkling explosion in the fan slot's color and shape. */
    private ItemStack buildRocketStack(RandomSource random, int slot) {
        FireworkExplosion explosion = new FireworkExplosion(SHAPES[slot],
            new IntArrayList(new int[]{COLORS[slot]}), new IntArrayList(), false, true);
        ItemStack stack = new ItemStack(Items.FIREWORK_ROCKET);
        stack.set(DataComponents.FIREWORKS, new Fireworks(1, List.of(explosion)));
        return stack;
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag rockets = ctx.state().getListOrEmpty("rockets");
        for (int i = 0; i < rockets.size(); i++) {
            CompoundTag entry = rockets.getCompoundOrEmpty(i);
            if (entry.getIntOr("done", 0) == 1 || !entry.contains("id")) {
                continue;
            }
            UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
            Entity rocket = id != null ? level.getEntity(id) : null;
            if (rocket != null && rocket.isAlive()) {
                entry.putDouble("lx", rocket.getX());
                entry.putDouble("ly", rocket.getY());
                entry.putDouble("lz", rocket.getZ());
                continue;
            }
            // The rocket is gone: it burst. Run the code
            // shrapnel at its last recorded position.
            entry.putInt("done", 1);
            ctx.state().putInt("bursts", ctx.state().getIntOr("bursts", 0) + 1);
            burst(ctx, level, entry.getDoubleOr("lx", ctx.mob().getX()),
                entry.getDoubleOr("ly", ctx.mob().getEyeY()), entry.getDoubleOr("lz", ctx.mob().getZ()));
        }
        ctx.state().put("rockets", rockets);
    }

    /** The code half of one rocket's burst: particles plus the shrapnel core (see the class comment). */
    private void burst(AbilityContext ctx, ServerLevel level, double x, double y, double z) {
        level.sendParticles(ParticleTypes.FIREWORK, x, y, z, 10, 0.5, 0.5, 0.5, 0.1);
        level.sendParticles(ParticleTypes.SMOKE, x, y, z, 5, 0.6, 0.4, 0.6, 0.03);
        List<LivingEntity> victims = PillagerAbilitySupport.enemiesInBox(ctx, x, y, z,
            SHRAPNEL_RADIUS, SHRAPNEL_RADIUS, SHRAPNEL_RADIUS);
        for (LivingEntity victim : victims) {
            victim.hurtServer(level, level.damageSources().mobAttack(ctx.mob()), (float) SHRAPNEL_DAMAGE);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.state().getIntOr("bursts", 0) >= ROCKET_COUNT
            || ctx.ticksActive() >= HARD_FINISH_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to clean up: the rockets are free projectiles
        // and finish as vanilla fireworks on their own (see the
        // class comment).
    }
}
