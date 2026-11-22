package mod.advancedmobs.ability.cod;

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
 * Downcurrent Net (Cod ability 3 of 5, control).
 *
 * WHAT IT DOES: the cod rings the fight with a net of
 * sinking currents. Columns of current_down and
 * underwater particles spiral down around a fixed
 * center for fifteen seconds, and any enemy swimming
 * inside the ring is dragged toward the floor and
 * slowed, pinned near the bottom where the cod and its
 * allies can strike it at leisure. The cod itself
 * swims through its own currents as if they were not
 * there.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the cod's current target within 14 blocks
 *   (distance squared 196), plus the cod in water (the
 *   net is a water formation).
 * - The planting (start()): the net's center is fixed
 *   at the target's live position (the caster's own
 *   position when there is somehow no target) and the
 *   ring radius is 5.0; both are recorded in the state
 *   tag and never move again, even if the target leaves.
 *   For the activation the cod gains a transient
 *   WATER_MOVEMENT_EFFICIENCY +1.0 add_value modifier
 *   (id advancedmobs:downcurrent_net_efficiency, the
 *   design's self line at the attribute's maximum: the
 *   cod loses none of its speed to the water inside its
 *   own net), removed in end(). FISH_SWIM plays at
 *   volume 1.0, pitch 0.9, low and deliberate.
 * - Duration: 300 ticks (15 seconds), hard finish.
 * - The columns (every 5 ticks): 6 points on the ring
 *   at angles (point times 60 degrees plus the
 *   activation ticks times 0.05 radians, so the ring of
 *   columns slowly rotates), each sending 1
 *   CURRENT_DOWN plus 1 UNDERWATER particle at the
 *   center's height, and 1 UNDERWATER at the center
 *   itself: the design's spiraling sinking columns,
 *   drawn in particles only. No blocks are placed; the
 *   currents are a zone effect in code, not real bubble
 *   column blocks, so the terrain gate does not apply.
 * - The drag (same 5-tick pulse, one bounded query,
 *   radius 5.0 around the center, vertical reach 3.0
 *   both ways): every enemy (the package enemy rule)
 *   that is in water gains SLOWNESS I refreshed to 40
 *   ticks and is pulled down and in: its vertical
 *   velocity is set to at most -0.35 blocks per tick
 *   (a gentle sink becomes a drag; a rising swimmer is
 *   stopped and pulled down), and its horizontal
 *   velocity gains a 0.06 blocks per tick pull toward
 *   the center axis, so the ring herds as well as pins.
 *   hurtMarked is set so the drag syncs. Enemies out of
 *   water (breaching the surface, standing on a ledge
 *   inside the ring) are untouched: the net only holds
 *   what swims in it. The caster is never its own enemy
 *   under the package rule, and its efficiency modifier
 *   is its free pass besides.
 *
 * WHAT IT LOOKS LIKE: a slow carousel of sinking
 * particle columns around the fight, everything hostile
 * inside it sinking and wallowing near the floor while
 * the cod darts through untouched.
 *
 * PERFORMANCE: all work runs on the 5-tick pulse: one
 * bounded query, 13 particle sends, and at most one
 * effect apply plus one velocity write per enemy found.
 * Nothing runs per tick. Particle budget per
 * activation: 60 pulses times 13 = 780 sends of single
 * particles, total at most 780.
 *
 * TERRAIN: this ability changes no blocks; the gate
 * does not apply (see the columns note above).
 *
 * CLEANUP: end() removes the efficiency modifier by
 * id. Enemy slowness rides its own 40-tick clock out;
 * the drag is a per-pulse velocity write and leaves
 * nothing behind.
 *
 * State tag keys: "cx", "cy", "cz" (the net's fixed
 * center).
 */
public final class DowncurrentNetAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int PULSE_INTERVAL = 5;
    private static final double RING_RADIUS = 5.0;
    private static final double SINK_SPEED = -0.35;
    private static final double INWARD_PULL = 0.06;
    private static final Identifier EFFICIENCY_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "downcurrent_net_efficiency");

    @Override
    public String id() {
        return "downcurrent_net";
    }

    @Override
    public String displayName() {
        return "Downcurrent Net";
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
        LivingEntity target = ctx.target();
        state.putDouble("cx", target != null ? target.getX() : caster.getX());
        state.putDouble("cy", target != null ? target.getY() : caster.getY());
        state.putDouble("cz", target != null ? target.getZ() : caster.getZ());

        // The design's self line: the cod swims its own net freely.
        CodAbilitySupport.addModifier(caster, Attributes.WATER_MOVEMENT_EFFICIENCY,
            new AttributeModifier(EFFICIENCY_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.FISH_SWIM, SoundSource.HOSTILE, 1.0f, 0.9f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks % PULSE_INTERVAL != 0) {
            return;
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        double centerX = state.getDoubleOr("cx", ctx.mob().getX());
        double centerY = state.getDoubleOr("cy", ctx.mob().getY());
        double centerZ = state.getDoubleOr("cz", ctx.mob().getZ());

        // The spiraling columns: particles only, no placed blocks.
        for (int i = 0; i < 6; i++) {
            double angle = i * Math.PI / 3.0 + ticks * 0.05;
            double px = centerX + Math.cos(angle) * RING_RADIUS;
            double pz = centerZ + Math.sin(angle) * RING_RADIUS;
            level.sendParticles(ParticleTypes.CURRENT_DOWN, px, centerY + 0.5, pz,
                1, 0.15, 0.6, 0.15, 0.02);
            level.sendParticles(ParticleTypes.UNDERWATER, px, centerY, pz,
                1, 0.15, 0.4, 0.15, 0.02);
        }
        level.sendParticles(ParticleTypes.UNDERWATER, centerX, centerY, centerZ,
            1, 0.5, 0.4, 0.5, 0.02);

        // The drag: slow and sink everything hostile that swims in the ring.
        List<LivingEntity> victims = CodAbilitySupport.enemiesInBox(ctx, centerX, centerY, centerZ,
            RING_RADIUS, 3.0, 3.0);
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
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        CodAbilitySupport.removeModifier(ctx.mob(), Attributes.WATER_MOVEMENT_EFFICIENCY, EFFICIENCY_ID);
    }
}
