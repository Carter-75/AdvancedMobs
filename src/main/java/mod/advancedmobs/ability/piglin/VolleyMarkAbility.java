package mod.advancedmobs.ability.piglin;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Comparator;
import java.util.List;

/**
 * Volley Mark (Piglin ability 3 of 5, attack).
 *
 * WHAT IT DOES: the piglin raises its crossbow and paints a
 * wide red target circle on the ground where its target
 * stands. A heartbeat later, arrows begin raining straight
 * down into the marked circle from high above and keep
 * falling for five seconds, denying the whole patch of
 * ground. Other piglins of the caster's crowd who see the
 * volley called add their own arrows to it. The mark is fair
 * warning: the circle is drawn for a full second before the
 * first arrow falls, but it is 7 blocks across and the rain
 * does not stop early.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the piglin's current target within 24 blocks (distance
 *   squared 576).
 * - The mark: start() records the target's position at that
 *   moment (feet height) as the circle center. The mark does
 *   NOT track the target afterwards: it is painted ground,
 *   and walking out of it is the intended counterplay.
 * - Duration: exactly 120 ticks (6 seconds). Ticks 1 to 20
 *   are the mark phase (the heartbeat of warning); the rain
 *   runs ticks 21 to 120 (5 seconds).
 * - The circle: every 4 ticks for the whole activation, 12
 *   red DUST motes (0xFF2222, scale 1.5) are drawn on the
 *   circle's rim (radius 3.5) at the mark height plus 0.15,
 *   plus 1 mote at the center: 13 particles per draw, 30
 *   draws, at most 390 particles.
 * - The rain: every 2 ticks during the rain phase, 3 arrows
 *   spawn at random points inside the circle (uniform disc:
 *   radius 3.5 times the square root of a random roll), 16
 *   blocks above the mark height. Each is a real Arrow owned
 *   by the caster, base damage 3.0, pickup disallowed, fired
 *   straight down at speed 3.0 with no spread, so it lands
 *   about 6 ticks later inside the circle unless something
 *   is in the way. 50 rain pulses times 3 arrows = at most
 *   150 caster arrows. Known behavior, stated plainly: the
 *   volley is an open-sky weapon; under a low ceiling the
 *   arrows spawn inside the roof and stick there instead of
 *   falling.
 * - Contributing piglins: every 10 ticks during the rain
 *   phase (ticks 30, 40, ..., 120: 10 pulses), one bounded
 *   query finds piglins within 20 blocks of the mark center;
 *   cluster allies (the support class's CLUSTER ALLY RULE:
 *   an alliance record in either direction, or fighting the
 *   same target as the caster), nearest first, at most 6 of
 *   them each add 1 arrow of their own to that pulse, owned
 *   by the contributing piglin, same spawn pattern and base
 *   damage. At most 60 contributed arrows per activation.
 * - An ARROW_SHOOT sound (volume 0.6) plays at the mark every
 *   20 ticks during the rain, the distant snap of bowstrings.
 * - end() cleans up nothing: the arrows belong to the world
 *   once loosed, and stuck arrows despawn on vanilla timers.
 *
 * WHAT IT LOOKS LIKE: a flat red DUST ring burning on the
 * ground for a heartbeat of silence, then a hissing rain of
 * arrows stitching the whole disc, thicker whenever the
 * caster's crowd is near, while the red ring keeps burning
 * underneath until the last arrow falls.
 *
 * PERFORMANCE: tick() does one modulo check per phase gate:
 * the circle draw runs every 4 ticks (13 fixed particle
 * sends, positions computed from constants), the rain every
 * 2 ticks during the rain phase (3 arrow constructions), the
 * contributor query every 10 ticks during the rain phase (one
 * bounded Piglin query plus a sort of a small list). No
 * whole-level scans, no per-tick allocations beyond the arrow
 * and particle objects on their gated ticks. Particle budget
 * per activation: at most 390 DUST motes, stated above.
 *
 * State tag keys: "mx", "my", "mz" (the mark center, the
 * target's feet position at cast time).
 */
public final class VolleyMarkAbility implements MobAbility {

    static final int DURATION_TICKS = 120;
    private static final int MARK_TICKS = 20;
    private static final double RADIUS = 3.5;
    private static final double SPAWN_HEIGHT = 16.0;
    private static final int ARROWS_PER_PULSE = 3;
    private static final int MAX_CONTRIBUTORS = 6;
    // 26.2 DustParticleOptions takes an RGB int color: pure warning red 0xFF2222.
    private static final DustParticleOptions MARK_DUST = new DustParticleOptions(0xFF2222, 1.5f);

    @Override
    public String id() {
        return "volley_mark";
    }

    @Override
    public String displayName() {
        return "Volley Mark";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 576.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target != null) {
            ctx.state().putDouble("mx", target.getX());
            ctx.state().putDouble("my", target.getY());
            ctx.state().putDouble("mz", target.getZ());
        }
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            Mob mob = ctx.mob();
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.PIGLIN_ANGRY,
                SoundSource.HOSTILE, 1.0f, 1.2f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        double mx = ctx.state().getDoubleOr("mx", mob.getX());
        double my = ctx.state().getDoubleOr("my", mob.getY());
        double mz = ctx.state().getDoubleOr("mz", mob.getZ());
        int ticks = ctx.ticksActive();

        if (ticks % 4 == 0) {
            drawCircle(level, mx, my, mz);
        }
        if (ticks <= MARK_TICKS) {
            return; // The heartbeat of warning: mark only, no arrows yet.
        }
        if (ticks % 2 == 0) {
            for (int i = 0; i < ARROWS_PER_PULSE; i++) {
                spawnRainArrow(ctx, level, mob, mx, my, mz);
            }
        }
        if (ticks % 10 == 0) {
            addContributorArrows(ctx, level, mx, my, mz);
        }
        if (ticks % 20 == 0) {
            level.playSound(null, mx, my, mz, SoundEvents.ARROW_SHOOT,
                SoundSource.HOSTILE, 0.6f, 0.9f);
        }
    }

    /** Draws the red DUST rim and center dot of the mark (13 particles). */
    private void drawCircle(ServerLevel level, double mx, double my, double mz) {
        for (int i = 0; i < 12; i++) {
            double angle = i * Math.PI * 2.0 / 12.0;
            level.sendParticles(MARK_DUST,
                mx + Math.cos(angle) * RADIUS, my + 0.15, mz + Math.sin(angle) * RADIUS,
                1, 0.0, 0.0, 0.0, 0.0);
        }
        level.sendParticles(MARK_DUST, mx, my + 0.15, mz, 1, 0.0, 0.0, 0.0, 0.0);
    }

    /** Spawns one straight-down rain arrow inside the circle, owned by the given shooter. */
    private void spawnRainArrow(AbilityContext ctx, ServerLevel level, Mob shooter,
                                double mx, double my, double mz) {
        double angle = ctx.random().nextDouble() * Math.PI * 2.0;
        double rr = RADIUS * Math.sqrt(ctx.random().nextDouble());
        Arrow arrow = new Arrow(level, shooter, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        arrow.setBaseDamage(3.0);
        arrow.snapTo(mx + Math.cos(angle) * rr, my + SPAWN_HEIGHT, mz + Math.sin(angle) * rr,
            0.0f, 90.0f);
        arrow.shoot(0.0, -1.0, 0.0, 3.0f, 0.0f);
        level.addFreshEntity(arrow);
    }

    /** The contributor pulse: nearby crowd piglins each add one arrow (see the class comment). */
    private void addContributorArrows(AbilityContext ctx, ServerLevel level,
                                      double mx, double my, double mz) {
        List<Piglin> crowd = PiglinAbilitySupport.clusterPiglinsAround(ctx, mx, my, mz, 20.0, 10.0, 10.0);
        crowd.sort(Comparator.comparingDouble(p -> {
            double dx = p.getX() - mx;
            double dy = p.getY() - my;
            double dz = p.getZ() - mz;
            return dx * dx + dy * dy + dz * dz;
        }));
        int added = 0;
        for (Piglin piglin : crowd) {
            if (added >= MAX_CONTRIBUTORS) {
                break;
            }
            spawnRainArrow(ctx, level, piglin, mx, my, mz);
            added++;
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to clean up: loosed arrows belong to the world
        // (see the class comment).
    }
}
