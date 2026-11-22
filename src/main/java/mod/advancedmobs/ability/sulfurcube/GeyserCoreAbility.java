package mod.advancedmobs.ability.sulfurcube;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.GeyserBaseParticleOptions;
import net.minecraft.core.particles.GeyserParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Geyser Core (Sulfur Cube ability 1 of 5, attack).
 *
 * WHAT IT DOES: the cube plants itself, swells up fat with
 * sulfur bubbles and steam, and then vents a full geyser
 * eruption from beneath itself: a towering GEYSER_PLUME built
 * from the 26.2 geyser particle family at water depth 4. The
 * eruption does NO damage at all; instead it launches the cube
 * and every enemy standing close straight up into the air, and
 * the fight simply continues wherever everyone lands.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the cube's current target within 12 blocks (distance
 *   squared 144).
 * - Duration: exactly 60 ticks (3 seconds) from start(): a
 *   20 tick swell, the eruption on tick 21, then a fall watch
 *   until tick 60.
 * - The swell (ticks 1 to 20): the cube holds position
 *   (navigation stopped every tick; NoAi is never used, per
 *   the standing contract) and carries a transient SCALE
 *   modifier of +0.25 add_multiplied_base (id
 *   advancedmobs:geyser_core_swell), so it visibly bulges to
 *   1.25 times size. SULFUR_BUBBLES shed 3 at a time every 2
 *   ticks and a small GEYSER_POOF (water depth 2) puffs at its
 *   base every 5 ticks.
 * - The eruption (tick 21, one-shot): the SCALE modifier comes
 *   off, and the plume fires: 3 GEYSER_PLUME, 2 GEYSER, and 2
 *   GEYSER_BASE particles, all at water_blocks 4 (the family's
 *   water depth scaling is what makes the plume tower), plus
 *   12 SULFUR_BUBBLES, with a SULFUR_CUBE_BOUNCE at volume 1.5
 *   and pitch 0.7. The cube launches at vertical velocity
 *   1.25 (keeping 30 percent of its horizontal velocity), and
 *   every enemy inside radius 3.0 of the cube (one bounded
 *   query, this tick only) launches at vertical velocity 1.1
 *   plus 0.25 horizontal away from the cube. Launch velocities
 *   are written directly and flagged hurtMarked so they sync.
 * - No fall damage from the launch: at the eruption the cube
 *   and every launched enemy gain a transient
 *   FALL_DAMAGE_MULTIPLIER modifier of -1.0
 *   add_multiplied_total (id
 *   advancedmobs:geyser_core_soft_landing), which multiplies
 *   their fall damage to zero. Launched victims are tracked in
 *   the state tag; a prune pulse every 5 ticks removes the
 *   modifier from each one that has landed (onGround), and
 *   end() removes it from everyone still tracked and from the
 *   cube. The design's "no eruption damage" covers the landing
 *   too: the eruption itself never hurts anyone.
 * - No blocks are placed or broken anywhere in this ability,
 *   so the mobGriefing terrain gate does not apply.
 *
 * WHAT IT LOOKS LIKE: a sulfur cube swelling up like a kettle,
 * bubbles streaming off it and little steam poofs at its base,
 * then a white water plume four deep erupting under it and
 * everything nearby simply leaving the ground at once, cube
 * and victims arcing up together and raining back down around
 * the vent.
 *
 * PERFORMANCE: the swell is a navigation stop and two small
 * particle pulses on intervals; the eruption is one bounded
 * entity query on one tick; the fall watch resolves at most a
 * handful of tracked UUIDs every 5 ticks. The particle options
 * are pre-built static finals. Particle budget per activation,
 * hard caps by construction: swell bubbles 10 pulses times 3 =
 * 30, swell poofs 4 times 2 = 8, eruption 3 + 2 + 2 + 12 = 19,
 * total at most 57 particles.
 *
 * CLEANUP: end() removes the swell SCALE modifier (already off
 * on a natural finish; harmless) and the soft landing modifier
 * from the cube and every launched victim still tracked.
 *
 * State tag keys: "launched" (list of compounds, one per
 * launched enemy: "id", the victim's UUID).
 */
public final class GeyserCoreAbility implements MobAbility {

    static final int DURATION_TICKS = 60;
    private static final int SWELL_TICKS = 20;
    private static final int ERUPTION_TICK = 21;
    private static final double LAUNCH_RADIUS = 3.0;
    private static final Identifier SWELL_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "geyser_core_swell");
    private static final Identifier SOFT_LANDING_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "geyser_core_soft_landing");
    private static final GeyserParticleOptions PLUME_DEEP =
        new GeyserParticleOptions(ParticleTypes.GEYSER_PLUME, 4);
    private static final GeyserParticleOptions GEYSER_DEEP =
        new GeyserParticleOptions(ParticleTypes.GEYSER, 4);
    private static final GeyserBaseParticleOptions BASE_DEEP =
        new GeyserBaseParticleOptions(ParticleTypes.GEYSER_BASE, 4, 1.0f);
    private static final GeyserBaseParticleOptions POOF_SHALLOW =
        new GeyserBaseParticleOptions(ParticleTypes.GEYSER_POOF, 2, 0.6f);

    @Override
    public String id() {
        return "geyser_core";
    }

    @Override
    public String displayName() {
        return "Geyser Core";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        SulfurCubeAbilitySupport.addModifier(mob, Attributes.SCALE,
            new AttributeModifier(SWELL_ID, 0.25, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        ctx.state().put("launched", new ListTag());
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        int ticks = ctx.ticksActive();

        if (ticks <= SWELL_TICKS) {
            // The swell: rooted, bubbling, steaming at the base.
            mob.getNavigation().stop();
            if (ticks % 2 == 0) {
                level.sendParticles(ParticleTypes.SULFUR_BUBBLES, mob.getX(), mob.getY() + 0.5, mob.getZ(),
                    3, mob.getBbWidth() * 0.5, 0.5, mob.getBbWidth() * 0.5, 0.05);
            }
            if (ticks % 5 == 0) {
                level.sendParticles(POOF_SHALLOW, mob.getX(), mob.getY() + 0.1, mob.getZ(),
                    2, 0.3, 0.1, 0.3, 0.05);
            }
            return;
        }
        if (ticks == ERUPTION_TICK) {
            erupt(ctx, level);
            return;
        }
        // The fall watch: soft landings come off as victims land.
        if (ticks % 5 == 0) {
            pruneLanded(ctx, level);
        }
    }

    /** The one-shot eruption: plume, launch, soft landings. */
    private void erupt(AbilityContext ctx, ServerLevel level) {
        Mob mob = ctx.mob();
        SulfurCubeAbilitySupport.removeModifier(mob, Attributes.SCALE, SWELL_ID);
        level.sendParticles(PLUME_DEEP, mob.getX(), mob.getY() + 0.2, mob.getZ(),
            3, 0.4, 0.2, 0.4, 0.1);
        level.sendParticles(GEYSER_DEEP, mob.getX(), mob.getY() + 0.2, mob.getZ(),
            2, 0.4, 0.2, 0.4, 0.1);
        level.sendParticles(BASE_DEEP, mob.getX(), mob.getY() + 0.1, mob.getZ(),
            2, 0.5, 0.1, 0.5, 0.1);
        level.sendParticles(ParticleTypes.SULFUR_BUBBLES, mob.getX(), mob.getY() + 0.5, mob.getZ(),
            12, mob.getBbWidth() * 0.6, 0.6, mob.getBbWidth() * 0.6, 0.15);
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.SULFUR_CUBE_BOUNCE,
            SoundSource.HOSTILE, 1.5f, 0.7f);

        // The cube rides its own eruption.
        applySoftLanding(mob);
        Vec3 velocity = mob.getDeltaMovement();
        mob.setDeltaMovement(velocity.x * 0.3, 1.25, velocity.z * 0.3);
        mob.hurtMarked = true;

        // Everyone standing close goes up with it, unharmed.
        ListTag launched = new ListTag();
        for (LivingEntity enemy : SulfurCubeAbilitySupport.enemiesInBox(ctx,
                mob.getX(), mob.getY(), mob.getZ(), LAUNCH_RADIUS, 1.0, 3.0)) {
            double awayX = enemy.getX() - mob.getX();
            double awayZ = enemy.getZ() - mob.getZ();
            double length = Math.sqrt(awayX * awayX + awayZ * awayZ);
            double pushX = length > 0.001 ? awayX / length * 0.25 : 0.0;
            double pushZ = length > 0.001 ? awayZ / length * 0.25 : 0.0;
            enemy.setDeltaMovement(pushX, 1.1, pushZ);
            enemy.hurtMarked = true;
            applySoftLanding(enemy);
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, enemy.getUUID());
            launched.add(entry);
        }
        ctx.state().put("launched", launched);
    }

    /** Gives one entity the zero fall damage modifier for its launch landing. */
    private static void applySoftLanding(LivingEntity entity) {
        SulfurCubeAbilitySupport.addModifier(entity, Attributes.FALL_DAMAGE_MULTIPLIER,
            new AttributeModifier(SOFT_LANDING_ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    /** Removes the soft landing modifier from every tracked victim that has landed. */
    private void pruneLanded(AbilityContext ctx, ServerLevel level) {
        ListTag tracked = ctx.state().getListOrEmpty("launched");
        ListTag remaining = new ListTag();
        for (int i = 0; i < tracked.size(); i++) {
            CompoundTag entry = tracked.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            Entity entity = id != null ? level.getEntity(id) : null;
            if (entity instanceof LivingEntity living && living.isAlive() && !living.onGround()) {
                remaining.add(entry);
            } else if (entity instanceof LivingEntity living) {
                SulfurCubeAbilitySupport.removeModifier(living, Attributes.FALL_DAMAGE_MULTIPLIER, SOFT_LANDING_ID);
            }
        }
        ctx.state().put("launched", remaining);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        SulfurCubeAbilitySupport.removeModifier(mob, Attributes.SCALE, SWELL_ID);
        SulfurCubeAbilitySupport.removeModifier(mob, Attributes.FALL_DAMAGE_MULTIPLIER, SOFT_LANDING_ID);
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag tracked = ctx.state().getListOrEmpty("launched");
        for (int i = 0; i < tracked.size(); i++) {
            CompoundTag entry = tracked.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id != null && level.getEntity(id) instanceof LivingEntity living) {
                SulfurCubeAbilitySupport.removeModifier(living, Attributes.FALL_DAMAGE_MULTIPLIER, SOFT_LANDING_ID);
            }
        }
    }
}
