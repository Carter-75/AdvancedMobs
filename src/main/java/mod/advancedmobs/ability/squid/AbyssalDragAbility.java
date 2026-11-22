package mod.advancedmobs.ability.squid;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Abyssal Drag (Squid ability 5 of 5, terrain /
 * control).
 *
 * WHAT IT DOES: the squid anchors itself at the
 * edge of the fight and churns the water into a
 * wide, slow downcurrent: a dark column of sinking
 * water six blocks across that drags everything
 * hostile in it steadily toward the seafloor, slows
 * their swimming, and squeezes the air out of their
 * lungs while the squid circles the rim of the
 * column, picking at whoever is pinned inside.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat
 *   gate, plus the squid's current target within 14
 *   blocks (distance squared 196), plus the squid in
 *   water.
 * - The anchor (start()): the column's center is
 *   fixed at the caster's position and recorded in
 *   the state tag; it never moves again, even if the
 *   fight drifts. The caster gains a transient
 *   KNOCKBACK_RESISTANCE +1.0 add_value modifier (id
 *   advancedmobs:abyssal_drag_anchor; total immunity,
 *   the design's anchoring: nothing knocks the squid
 *   off its station while it holds the current),
 *   removed in end(). SQUID_SQUIRT at volume 1.2,
 *   pitch 0.6, deep and slow.
 * - Duration: 400 ticks (20 seconds), hard finish.
 * - The column (every 5 ticks): 8 points on the
 *   radius 6.0 ring at angles (point times 45 degrees
 *   plus the activation ticks times 0.04 radians, so
 *   the spiral slowly turns), each sending 1
 *   CURRENT_DOWN on even points and 1 UNDERWATER on
 *   odd points, at a height that steps down by 1
 *   block per pulse from 2 above the center to 4
 *   below it and wraps: the visible spiral sinking
 *   to the seafloor. Plus 1 UNDERWATER at the center.
 *   The currents are particles only; no bubble
 *   column blocks are placed, so the terrain gate
 *   does not apply (see the support class).
 * - The drag (same 5-tick pulse, one bounded query,
 *   radius 6.0 around the center, vertical reach 6.0
 *   both ways): every enemy (the package enemy rule)
 *   that is in water gains SLOWNESS I refreshed to
 *   40 ticks, is pulled down and in (vertical
 *   velocity set to at most -0.30 blocks per tick, a
 *   0.05 blocks per tick horizontal pull toward the
 *   center axis, hurtMarked set), and loses air: its
 *   air supply drops by 10 ticks per pulse (200 per
 *   second of sitting in the column), floored at 0,
 *   after which vanilla drowning rules do the rest
 *   (the design's oxygen pressure via drowning
 *   rules, in code: no custom effect is involved).
 *   Enemies out of water inside the ring (breaching,
 *   standing on a ledge) are untouched: the drag
 *   only holds what swims in it. The caster is never
 *   its own enemy under the package rule.
 * - The edge orbit (every 2 ticks): the caster is
 *   steered at 0.4 blocks per tick toward the point
 *   on the radius 5.0 ring at angle (activation
 *   ticks times 0.03 radians), circling the column
 *   it holds.
 * - The picking (every 20 ticks, on the drag pulse
 *   count): the nearest enemy inside the column to
 *   the caster takes 2.0 flat damage as a mob attack
 *   credited to the caster, with 3 CRIT at its
 *   position: the squid picking at the pinned from
 *   the edge, per the design.
 *
 * WHAT IT LOOKS LIKE: a turning spiral of sinking
 * flecks in a wide dark column, everything hostile
 * inside it wallowing downward and running out of
 * air, and a squid circling the rim, darting in a
 * nip at a time.
 *
 * PERFORMANCE: all heavy work runs on the 5-tick
 * pulse: one bounded query, 9 particle sends, and at
 * most one effect apply, one velocity write, and one
 * air write per enemy found; the picking reuses the
 * pulse's query results. The orbit is one steering
 * write every 2 ticks. Particle budget per
 * activation: 80 pulses times 9 = 720 column sends,
 * at most 20 picks times 3 = 60 crit, total at most
 * 780.
 *
 * TERRAIN: filed as terrain in the design, but this
 * ability changes no blocks; the downcurrent is a
 * code zone drawn in particles, so the mobGriefing
 * gate does not apply (the Downcurrent Net
 * precedent).
 *
 * CLEANUP: end() removes the anchor modifier by id.
 * Enemy slowness rides its own 40-tick clock out;
 * the drag and the air drain are per-pulse writes
 * and leave nothing behind.
 *
 * State tag keys: "cx", "cy", "cz" (the column's
 * fixed center).
 */
public final class AbyssalDragAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int PULSE_INTERVAL = 5;
    private static final double COLUMN_RADIUS = 6.0;
    private static final double SINK_SPEED = -0.30;
    private static final double INWARD_PULL = 0.05;
    private static final int AIR_DRAIN_PER_PULSE = 10;
    private static final float PICK_DAMAGE = 2.0f;
    private static final Identifier ANCHOR_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "abyssal_drag_anchor");

    @Override
    public String id() {
        return "abyssal_drag";
    }

    @Override
    public String displayName() {
        return "Abyssal Drag";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().isInWater()
            && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        state.putDouble("cx", caster.getX());
        state.putDouble("cy", caster.getY());
        state.putDouble("cz", caster.getZ());

        // The design's anchor: nothing knocks the squid off its station.
        SquidAbilitySupport.addModifier(caster, Attributes.KNOCKBACK_RESISTANCE,
            new AttributeModifier(ANCHOR_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.SQUID_SQUIRT, SoundSource.HOSTILE, 1.2f, 0.6f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        double centerX = state.getDoubleOr("cx", caster.getX());
        double centerY = state.getDoubleOr("cy", caster.getY());
        double centerZ = state.getDoubleOr("cz", caster.getZ());

        // The edge orbit: circle the rim of the held column.
        if (ticks % 2 == 0) {
            double angle = ticks * 0.03;
            SquidAbilitySupport.steerToward(caster,
                centerX + Math.cos(angle) * 5.0, centerY,
                centerZ + Math.sin(angle) * 5.0, 0.4);
        }

        if (ticks % PULSE_INTERVAL != 0) {
            return;
        }

        // The spiraling column: particles only, no placed blocks.
        int pulse = ticks / PULSE_INTERVAL;
        double sinkHeight = centerY + 2.0 - (pulse % 7);
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4.0 + ticks * 0.04;
            double px = centerX + Math.cos(angle) * COLUMN_RADIUS;
            double pz = centerZ + Math.sin(angle) * COLUMN_RADIUS;
            level.sendParticles(i % 2 == 0 ? ParticleTypes.CURRENT_DOWN : ParticleTypes.UNDERWATER,
                px, sinkHeight, pz, 1, 0.15, 0.5, 0.15, 0.02);
        }
        level.sendParticles(ParticleTypes.UNDERWATER, centerX, centerY, centerZ,
            1, 0.5, 0.4, 0.5, 0.02);

        // The drag: slow, sink, and suffocate everything hostile swimming in the column.
        List<LivingEntity> victims = SquidAbilitySupport.enemiesInBox(ctx,
            centerX, centerY, centerZ, COLUMN_RADIUS, 6.0, 6.0);
        LivingEntity nearest = null;
        double nearestSqr = Double.MAX_VALUE;
        for (LivingEntity victim : victims) {
            if (!victim.isInWater()) {
                continue;
            }
            victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 0));
            Vec3 current = victim.getDeltaMovement();
            double pullX = centerX - victim.getX();
            double pullZ = centerZ - victim.getZ();
            double pullLength = Math.sqrt(pullX * pullX + pullZ * pullZ);
            double addX = pullLength > 0.001 ? pullX / pullLength * INWARD_PULL : 0.0;
            double addZ = pullLength > 0.001 ? pullZ / pullLength * INWARD_PULL : 0.0;
            victim.setDeltaMovement(current.x + addX, Math.min(current.y, SINK_SPEED), current.z + addZ);
            victim.hurtMarked = true;
            // The oxygen pressure: air drains, then vanilla drowning rules bite.
            victim.setAirSupply(Math.max(0, victim.getAirSupply() - AIR_DRAIN_PER_PULSE));

            double distanceSqr = victim.distanceToSqr(caster);
            if (distanceSqr < nearestSqr) {
                nearestSqr = distanceSqr;
                nearest = victim;
            }
        }

        // The picking: every 20 ticks the nearest pinned enemy takes a nip.
        if (ticks % 20 == 0 && nearest != null) {
            nearest.hurtServer(level, level.damageSources().mobAttack(caster), PICK_DAMAGE);
            level.sendParticles(ParticleTypes.CRIT,
                nearest.getX(), nearest.getY() + 0.4, nearest.getZ(),
                3, 0.3, 0.3, 0.3, 0.05);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        SquidAbilitySupport.removeModifier(ctx.mob(), Attributes.KNOCKBACK_RESISTANCE, ANCHOR_ID);
    }
}
