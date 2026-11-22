package mod.advancedmobs.ability.drowned;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Trident Cyclone (Drowned ability 1 of 5, attack).
 *
 * WHAT IT DOES: the drowned spins up a waterspout where its target
 * stands and hurls a real trident through the middle of it. The
 * current drags every enemy near the spout toward its center for
 * two and a half seconds, and when the cyclone collapses it throws
 * them back out and up, clean out of the water column. While the
 * cyclone lasts the drowned's own blows land with far heavier
 * knockback.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists and is within 14 blocks
 *   (distance squared 196).
 * - Duration: exactly 50 ticks (2.5 seconds) from start().
 * - The spout center is the target's position snapshot at start()
 *   (stored as cx, cy, cz). The cyclone is anchored there; it does
 *   not follow the target afterwards.
 * - The trident (start()): one real ThrownTrident owned by the
 *   drowned, built from a plain trident item stack, spawned at the
 *   drowned and shot at the target's mid-body (position plus 60
 *   percent of its height) with speed 2.4 and no inaccuracy. It is
 *   an ordinary vanilla trident projectile in flight: 8 base damage
 *   of typed trident damage on a hit, vanilla behavior in water and
 *   on landing. Its pickup mode is set to DISALLOWED so the hurled
 *   trident can never be farmed, and end() discards it if it is
 *   still lying in the world (see CLEANUP).
 * - Heavy knockback: for the duration the drowned carries an
 *   ATTACK_KNOCKBACK add_value modifier of +2.0 (modifier id
 *   advancedmobs:trident_cyclone_knockback), removed in end(). Its
 *   melee hits in the window knock victims hard away, the "knocks
 *   victims out of the water column" half of the design that the
 *   ejection below completes for anything still in the spout.
 * - Drag pulses every 4 ticks (12 pulses): enemies
 *   (DrownedAbilitySupport rules) within horizontal radius 4.0 of
 *   the center and between 1 below and 4 above its feet level have
 *   their velocity set to 30 percent of their current horizontal
 *   velocity plus 0.45 toward the center, with a slight extra
 *   downward drag of 0.02 on the vertical (the CURRENT_DOWN pull).
 *   hurtMarked is set so player clients apply the shove.
 * - Ejection (tick 50, the last tick): every enemy still in that
 *   same box is thrown outward and up: horizontal velocity 1.0
 *   directly away from the center (straight up the column if it is
 *   standing exactly on the center) and vertical velocity 0.8.
 *   That is the cyclone collapsing and spitting its catch out.
 *
 * WHAT IT LOOKS LIKE: at start, 14 BUBBLE, 9 SPLASH, and 7
 * CURRENT_DOWN particles burst at the spout center as it spins up.
 * Each drag pulse throws 5 BUBBLE particles on a ring that rotates
 * with the tick count (angle from ticksActive, so the spout visibly
 * churns) plus 2 CURRENT_DOWN streaks at the center. The ejection is
 * a 12-particle SPLASH burst at the center. The trident itself is a
 * plain thrown trident, spinning through the middle of the spout.
 *
 * PERFORMANCE: tick() returns immediately except every 4th tick
 * (drag pulse: one bounded entity query over an 8 by 5 by 8 box,
 * plus the particle ring, which is pure math on the tick count) and
 * tick 50 (one more bounded query for the ejection). The trident is
 * spawned once in start(). No per-tick allocations beyond the pulse
 * query result, no registry or config lookups after start(). Particle
 * budget per activation: 30 at start plus 12 pulses times 7 = 84
 * plus 12 at the ejection, total at most 126 particles.
 *
 * CLEANUP: end() removes the ATTACK_KNOCKBACK modifier and discards
 * the hurled trident if it still resolves as an entity (stuck in a
 * block or still in flight). If it already hit, despawned, or was
 * destroyed, end() simply finds nothing. The modifier and the
 * trident are the only things start() leaves behind; both are undone
 * here, including on the framework termination path (caster death).
 *
 * State tag keys: "cx", "cy", "cz" (spout center snapshot),
 * "trident" (the hurled trident's UUID, when it spawned).
 */
public final class TridentCycloneAbility implements MobAbility {

    static final int DURATION_TICKS = 50;
    private static final int PULSE_INTERVAL = 4;
    private static final double SPOUT_RADIUS = 4.0;
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "trident_cyclone_knockback");

    @Override
    public String id() {
        return "trident_cyclone";
    }

    @Override
    public String displayName() {
        return "Trident Cyclone";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob mob = ctx.mob();
        LivingEntity target = ctx.target();
        CompoundTag state = ctx.state();
        if (target != null) {
            state.putDouble("cx", target.getX());
            state.putDouble("cy", target.getY());
            state.putDouble("cz", target.getZ());
        } else {
            state.putDouble("cx", mob.getX());
            state.putDouble("cy", mob.getY());
            state.putDouble("cz", mob.getZ());
        }

        // Heavy knockback on the drowned's own blows for the duration.
        AttributeInstance knockback = mob.getAttribute(Attributes.ATTACK_KNOCKBACK);
        if (knockback != null) {
            knockback.addOrUpdateTransientModifier(
                new AttributeModifier(KNOCKBACK_ID, 2.0, AttributeModifier.Operation.ADD_VALUE));
        }

        if (level == null || target == null) {
            return;
        }

        // The hurled trident: a real vanilla trident projectile owned
        // by the drowned, aimed at the target's mid-body.
        ThrownTrident trident = new ThrownTrident(level, mob, new ItemStack(Items.TRIDENT));
        trident.pickup = AbstractArrow.Pickup.DISALLOWED;
        double aimX = target.getX() - trident.getX();
        double aimY = (target.getY() + target.getBbHeight() * 0.6) - trident.getY();
        double aimZ = target.getZ() - trident.getZ();
        trident.shoot(aimX, aimY, aimZ, 2.4f, 0.0f);
        if (level.addFreshEntity(trident)) {
            state.store("trident", UUIDUtil.CODEC, trident.getUUID());
        }

        // The spout spins up around the target.
        double cx = state.getDoubleOr("cx", mob.getX());
        double cy = state.getDoubleOr("cy", mob.getY());
        double cz = state.getDoubleOr("cz", mob.getZ());
        level.sendParticles(ParticleTypes.BUBBLE, cx, cy + 1.0, cz, 14, 1.2, 1.0, 1.2, 0.1);
        level.sendParticles(ParticleTypes.SPLASH, cx, cy + 0.5, cz, 9, 1.4, 0.6, 1.4, 0.2);
        level.sendParticles(ParticleTypes.CURRENT_DOWN, cx, cy + 2.0, cz, 7, 1.0, 0.8, 1.0, 0.1);
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        double cx = state.getDoubleOr("cx", ctx.mob().getX());
        double cy = state.getDoubleOr("cy", ctx.mob().getY());
        double cz = state.getDoubleOr("cz", ctx.mob().getZ());

        if (ticks >= DURATION_TICKS) {
            eject(ctx, level, cx, cy, cz);
            return;
        }
        if (ticks % PULSE_INTERVAL != 0) {
            return; // Cheap early-out: drag pulses run every 4 ticks only.
        }

        // Drag pulse: the current pulls everything in the spout
        // toward its center and slightly down.
        List<LivingEntity> victims = DrownedAbilitySupport.enemiesInBox(ctx, cx, cy, cz, SPOUT_RADIUS, 1.0, 4.0);
        for (LivingEntity victim : victims) {
            double towardX = cx - victim.getX();
            double towardZ = cz - victim.getZ();
            double length = Math.sqrt(towardX * towardX + towardZ * towardZ);
            if (length < 1.0e-4) {
                towardX = 0.0;
                towardZ = 0.0;
            } else {
                towardX /= length;
                towardZ /= length;
            }
            Vec3 current = victim.getDeltaMovement();
            victim.setDeltaMovement(
                current.x * 0.3 + towardX * 0.45,
                current.y - 0.02,
                current.z * 0.3 + towardZ * 0.45);
            victim.hurtMarked = true;
        }

        // The churning ring: 5 bubbles on a rotating circle, 2
        // CURRENT_DOWN streaks at the core.
        double angle = Math.toRadians(ticks * 24.0);
        for (int i = 0; i < 5; i++) {
            double a = angle + i * (Math.PI * 2.0 / 5.0);
            level.sendParticles(ParticleTypes.BUBBLE,
                cx + Math.cos(a) * 1.6, cy + 0.4 + (i % 3) * 0.7, cz + Math.sin(a) * 1.6,
                1, 0.0, 0.0, 0.0, 0.02);
        }
        level.sendParticles(ParticleTypes.CURRENT_DOWN, cx, cy + 2.2, cz, 2, 0.3, 0.5, 0.3, 0.05);
    }

    /** The collapse: everything still in the spout is thrown out and up, out of the water column. */
    private static void eject(AbilityContext ctx, ServerLevel level, double cx, double cy, double cz) {
        List<LivingEntity> victims = DrownedAbilitySupport.enemiesInBox(ctx, cx, cy, cz, SPOUT_RADIUS, 1.0, 4.0);
        for (LivingEntity victim : victims) {
            double awayX = victim.getX() - cx;
            double awayZ = victim.getZ() - cz;
            double length = Math.sqrt(awayX * awayX + awayZ * awayZ);
            if (length < 1.0e-4) {
                awayX = 0.0;
                awayZ = 0.0;
            } else {
                awayX /= length;
                awayZ /= length;
            }
            victim.setDeltaMovement(awayX * 1.0, 0.8, awayZ * 1.0);
            victim.hurtMarked = true;
        }
        level.sendParticles(ParticleTypes.SPLASH, cx, cy + 1.0, cz, 12, 1.6, 0.8, 1.6, 0.3);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        AttributeInstance knockback = mob.getAttribute(Attributes.ATTACK_KNOCKBACK);
        if (knockback != null) {
            knockback.removeModifier(KNOCKBACK_ID);
        }
        // The hurled trident does not stay in the world as loot: if
        // it is still an entity (in flight or stuck), discard it.
        ServerLevel level = ctx.serverLevel();
        if (level == null || !ctx.state().contains("trident")) {
            return;
        }
        UUID tridentId = ctx.state().read("trident", UUIDUtil.CODEC).orElse(null);
        if (tridentId != null) {
            Entity trident = level.getEntity(tridentId);
            if (trident != null) {
                trident.discard();
            }
        }
    }
}
