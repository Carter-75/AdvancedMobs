package mod.advancedmobs.ability.guardian;

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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Crushing Depth Aura (Guardian ability 5 of 5, aura).
 *
 * WHAT IT DOES: the water around the guardian darkens and
 * thickens, BUBBLE_COLUMN_UP streams spiraling upward as the
 * pressure itself becomes a weapon. Enemies in range suffer
 * SLOWNESS and MINING_FATIGUE and feel their swim efficiency
 * crushed by an attribute modifier, while the guardian moves at
 * full land speed through the water at the aura's heart. The
 * aura pulses with each heartbeat of the guardian, and leaving
 * the radius lets the pressure bleed off over a few seconds.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   guardian's current target within 12 blocks (distance squared
 *   144).
 * - Duration: exactly 300 ticks (15 seconds) from start().
 * - The guardian itself, for the duration: a
 *   WATER_MOVEMENT_EFFICIENCY add_value modifier of +1.0
 *   (modifier id advancedmobs:crushing_depth_efficiency, removed
 *   in end()), so it swims and wades at full land speed through
 *   its own pressure field.
 * - Pressure pulse every 10 ticks (30 pulses): enemies (package
 *   rule) within radius 6 of the guardian (3 below, 3 above,
 *   round distance checked after the box query) get SLOWNESS I
 *   and MINING_FATIGUE I (amplifier 0) for 40 ticks each, plus
 *   the drag modifier below. The 40 tick effect duration against
 *   the 10 tick pulse is the bleed-off: an enemy that leaves the
 *   radius keeps the pressure for up to 2 more seconds and then
 *   shakes it off, exactly as designed.
 * - The drag: a WATER_MOVEMENT_EFFICIENCY add_value modifier of
 *   -0.5 (modifier id advancedmobs:crushing_depth_drag) applied
 *   to every pulsed enemy and tracked by UUID in the state tag.
 *   A tracked enemy that is no longer in a pulse has the modifier
 *   removed at that pulse; end() removes it from every tracked
 *   enemy still carrying it. Honest limit, stated for the bug
 *   fixing record: most entities have a base water movement
 *   efficiency of 0 and the attribute clamps at 0, so the drag
 *   bites hardest on enemies whose efficiency is above 0 (Depth
 *   Strider wearers, other guardians and drowned under their own
 *   boosts); for everyone else the SLOWNESS carries the swim
 *   slowdown. The modifier is still applied uniformly so the
 *   attribute pressure in the design is real wherever the
 *   attribute has room to move.
 * - (An enemy that somehow had SLOWNESS or MINING_FATIGUE from
 *   another source would lose it when its 40 ticks run out after
 *   the last pulse, the accepted edge case shared with every
 *   fixed-duration effect in this system. The effects are never
 *   removed early by end(): they bleed off on their own clock.)
 *
 * WHAT IT LOOKS AND SOUNDS LIKE: BUBBLE_COLUMN_UP streams spiral
 * up through the field: 4 at random spots within the radius on
 * every pulse, and on every 20 tick heartbeat 8 BUBBLE particles
 * ring the guardian with 4 more BUBBLE_COLUMN_UP streams, the
 * visible pulse of the aura. A deep GUARDIAN_AMBIENT (volume 1.2,
 * pitch 0.5) sounds as the pressure falls. Victims show the
 * vanilla SLOWNESS and MINING_FATIGUE effect sparkles.
 *
 * PERFORMANCE: tick() returns immediately except every 10th tick
 * (pulse: one bounded entity query over a 12 by 6 by 12 box, one
 * pass over the tracked UUID list, at most a few dozen modifier
 * and effect writes) and every 20th tick (12 particles of pure
 * math). No registry or config lookups in the tick path: both
 * modifier ids are pre-built static finals. Particle budget per
 * activation: 10 at start plus at most 30 pulses times 4 = 120,
 * plus at most 15 heartbeats times 12 = 180, total at most 310
 * particles.
 *
 * CLEANUP: end() removes the guardian's efficiency modifier and
 * the drag modifier from every tracked enemy. The pulse effects
 * are left to bleed off (see above). If the caster dies
 * mid-aura, the framework termination path runs this same end(),
 * so no enemy keeps the drag.
 *
 * State tag keys: "dragged" (list of compounds, one per enemy
 * currently carrying the drag modifier: "id", the enemy's UUID).
 */
public final class CrushingDepthAuraAbility implements MobAbility {

    static final int DURATION_TICKS = 300;
    private static final int PULSE_INTERVAL = 10;
    private static final int EFFECT_TICKS = 40;
    private static final double RADIUS = 6.0;
    private static final Identifier EFFICIENCY_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "crushing_depth_efficiency");
    private static final Identifier DRAG_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "crushing_depth_drag");

    @Override
    public String id() {
        return "crushing_depth_aura";
    }

    @Override
    public String displayName() {
        return "Crushing Depth Aura";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return ctx.target() != null && ctx.mob().distanceToSqr(ctx.target()) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        ctx.state().put("dragged", new ListTag());

        // The guardian moves at full land speed through its own
        // pressure.
        AttributeInstance efficiency = mob.getAttribute(Attributes.WATER_MOVEMENT_EFFICIENCY);
        if (efficiency != null) {
            efficiency.addOrUpdateTransientModifier(
                new AttributeModifier(EFFICIENCY_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        }

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            // The pressure falls: bubble streams boil upward.
            level.sendParticles(ParticleTypes.BUBBLE_COLUMN_UP, mob.getX(), mob.getY() + 0.4, mob.getZ(),
                10, 3.5, 0.6, 3.5, 0.15);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.GUARDIAN_AMBIENT, SoundSource.HOSTILE, 1.2f, 0.5f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        boolean pulse = ticks % PULSE_INTERVAL == 0;
        boolean heartbeat = ticks % 20 == 0;
        if (!pulse && !heartbeat) {
            return; // Cheap early-out: work runs on the 10 and 20 tick marks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();

        if (pulse) {
            applyPressure(ctx, level);
            // Bubble streams rising through the field.
            for (int i = 0; i < 4; i++) {
                double angle = ctx.random().nextDouble() * Math.PI * 2.0;
                double distance = ctx.random().nextDouble() * 5.0;
                level.sendParticles(ParticleTypes.BUBBLE_COLUMN_UP,
                    mob.getX() + Math.cos(angle) * distance, mob.getY() + 0.2,
                    mob.getZ() + Math.sin(angle) * distance,
                    1, 0.0, 0.0, 0.0, 0.05);
            }
        }
        if (heartbeat) {
            // The heartbeat pulse: a ring of bubbles off the
            // guardian's body.
            for (int i = 0; i < 8; i++) {
                double angle = i * (Math.PI * 2.0 / 8.0) + ticks * 0.05;
                level.sendParticles(ParticleTypes.BUBBLE,
                    mob.getX() + Math.cos(angle) * 3.0, mob.getY() + 0.5,
                    mob.getZ() + Math.sin(angle) * 3.0,
                    1, 0.05, 0.05, 0.05, 0.02);
            }
            level.sendParticles(ParticleTypes.BUBBLE_COLUMN_UP, mob.getX(), mob.getY() + 0.3, mob.getZ(),
                4, 1.2, 0.5, 1.2, 0.1);
        }
    }

    /**
     * One pressure pulse: refreshes the effects and the drag on
     * every enemy in the radius, and lifts the drag off tracked
     * enemies who have left it. See the class comment.
     */
    private void applyPressure(AbilityContext ctx, ServerLevel level) {
        Mob mob = ctx.mob();
        List<LivingEntity> victims = GuardianAbilitySupport.enemiesAroundSelf(ctx, RADIUS, 3.0, 3.0);
        List<UUID> current = new ArrayList<>();
        for (LivingEntity victim : victims) {
            if (victim.distanceToSqr(mob) > RADIUS * RADIUS) {
                continue; // The box query is square; the aura is round.
            }
            victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, EFFECT_TICKS, 0));
            victim.addEffect(new MobEffectInstance(MobEffects.MINING_FATIGUE, EFFECT_TICKS, 0));
            AttributeInstance efficiency = victim.getAttribute(Attributes.WATER_MOVEMENT_EFFICIENCY);
            if (efficiency != null) {
                efficiency.addOrUpdateTransientModifier(
                    new AttributeModifier(DRAG_ID, -0.5, AttributeModifier.Operation.ADD_VALUE));
            }
            current.add(victim.getUUID());
        }

        // Lift the drag off anyone tracked who is out of this
        // pulse, then store the new tracked set.
        ListTag dragged = ctx.state().getListOrEmpty("dragged");
        for (int i = 0; i < dragged.size(); i++) {
            CompoundTag entry = dragged.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (id == null || current.contains(id)) {
                continue;
            }
            removeDrag(level, id);
        }
        ListTag next = new ListTag();
        for (UUID id : current) {
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, id);
            next.add(entry);
        }
        ctx.state().put("dragged", next);
    }

    /** Removes the drag modifier from one resolved entity, if it is still around. */
    private void removeDrag(ServerLevel level, UUID id) {
        Entity entity = level.getEntity(id);
        if (entity instanceof LivingEntity living) {
            AttributeInstance efficiency = living.getAttribute(Attributes.WATER_MOVEMENT_EFFICIENCY);
            if (efficiency != null) {
                efficiency.removeModifier(DRAG_ID);
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        AttributeInstance efficiency = mob.getAttribute(Attributes.WATER_MOVEMENT_EFFICIENCY);
        if (efficiency != null) {
            efficiency.removeModifier(EFFICIENCY_ID);
        }

        // The drag lifts off every tracked enemy. The pulse
        // effects are NOT removed: they bleed off on their own 40
        // tick clock, as designed.
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag dragged = ctx.state().getListOrEmpty("dragged");
        for (int i = 0; i < dragged.size(); i++) {
            CompoundTag entry = dragged.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (id != null) {
                removeDrag(level, id);
            }
        }
    }
}
