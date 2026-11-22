package mod.advancedmobs.ability.shulker;

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
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.UUID;

/**
 * Gravity Well Oscillation (Shulker ability 3 of 5, aura,
 * control).
 *
 * WHAT IT DOES: the shulker becomes the center of a pulsing
 * gravity anomaly wrapped in PORTAL particles. For two
 * seconds at a time every enemy nearby is hit with
 * LEVITATION and floats helplessly upward; then the shulker
 * slams their GRAVITY to four times normal and doubles their
 * fall damage, and they crash down hard. The oscillation
 * repeats three times while the shulker holds position,
 * turning the area around it into a deadly elevator. Leaving
 * the radius breaks the cycle cleanly: no refresh, no slam.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the shulker's current target within 12 blocks (distance
 *   squared 144).
 * - Duration: exactly 180 ticks from start(): three cycles
 *   of 60 ticks each. Cycle ticks 1-40 are the float phase,
 *   cycle ticks 41-60 are the slam phase.
 * - Float phase: at cycle ticks 1, 11, 21, and 31, every
 *   enemy within radius 8 of the shulker (4 below to 10
 *   above, so floaters stay in reach) gains LEVITATION
 *   amplifier 1 for 25 ticks: a helpless rise of roughly 3
 *   to 4 blocks over the phase. The cycle opening plays
 *   SHULKER_BOX_OPEN at pitch 1.3.
 * - Slam (cycle tick 41): every enemy still within radius 8
 *   (4 below to 12 above) has its LEVITATION removed and
 *   gains two transient attribute modifiers for the 20-tick
 *   slam phase: GRAVITY +3.0 ADD_MULTIPLIED_TOTAL (four
 *   times normal gravity) and FALL_DAMAGE_MULTIPLIER +1.0
 *   ADD_MULTIPLIED_TOTAL (double fall damage). Victims are
 *   recorded by UUID in the state tag; the slam plays
 *   SHULKER_BOX_CLOSE at pitch 0.7 and bursts 10 PORTAL
 *   motes at the shulker.
 * - Slam end (cycle tick 60, and in end()): both modifiers
 *   are removed from every recorded victim still resolvable,
 *   and the record is cleared. Removal in end() is what the
 *   framework's termination path runs if the shulker dies
 *   mid-slam, so no victim keeps the heavy gravity.
 * - This is a zone effect around the shulker: it places and
 *   breaks no blocks, so the mobGriefing gate does not
 *   apply. The fall damage itself is vanilla fall damage
 *   under the victim's modified attributes.
 *
 * WHAT IT LOOKS LIKE: a ring of 6 PORTAL motes circling the
 * shulker at radius 2.5, one ring drawn every 4 ticks with
 * the angle advancing 0.35 radians per tick, so the anomaly
 * visibly churns; floaters rise through the ring, then the
 * ring bursts inward (the slam's 10-mote burst) and
 * everything drops.
 *
 * PERFORMANCE: tick() work is the particle ring every 4
 * ticks; the float pulses are one bounded query every 10
 * ticks during float phases, the slam is one bounded query
 * per cycle, and the slam-end cleanup resolves at most a
 * radius-8 crowd of recorded UUIDs once per cycle. No
 * per-tick entity queries, no per-tick allocations beyond
 * the pulse lists. Particle budget per activation: rings
 * 45 emissions x 6 = 270, slam bursts 3 x 10 = 30, total at
 * most 300 sent particles.
 *
 * State tag keys: "slam_victims" (list of compounds, one per
 * slammed enemy: "id", the victim's UUID).
 */
public final class GravityWellOscillationAbility implements MobAbility {

    static final int DURATION_TICKS = 180;
    private static final int CYCLE_TICKS = 60;
    private static final int SLAM_TICK = 41;
    private static final double RADIUS = 8.0;
    private static final Identifier GRAVITY_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "gravity_well_oscillation_gravity");
    private static final Identifier FALL_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "gravity_well_oscillation_fall");

    @Override
    public String id() {
        return "gravity_well_oscillation";
    }

    @Override
    public String displayName() {
        return "Gravity Well Oscillation";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().put("slam_victims", new ListTag());
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        int tick = ctx.ticksActive();
        int cycleTick = (tick - 1) % CYCLE_TICKS + 1;

        // The churning PORTAL ring around the anomaly.
        if (tick % 4 == 0) {
            double baseAngle = tick * 0.35;
            for (int i = 0; i < 6; i++) {
                double angle = baseAngle + i * (Math.PI / 3.0);
                level.sendParticles(ParticleTypes.PORTAL,
                    caster.getX() + Math.cos(angle) * 2.5,
                    caster.getY() + 0.6,
                    caster.getZ() + Math.sin(angle) * 2.5,
                    1, 0.0, 0.0, 0.0, 0.0);
            }
        }

        if (cycleTick == 1) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.SHULKER_BOX_OPEN, SoundSource.HOSTILE, 1.2f, 1.3f);
        }

        if (cycleTick < SLAM_TICK) {
            // Float phase: LEVITATION refresh pulses.
            if (cycleTick % 10 == 1) {
                for (LivingEntity enemy : ShulkerAbilitySupport.enemiesAroundSelf(ctx, RADIUS, 4.0, 10.0)) {
                    enemy.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 25, 1));
                }
            }
            return;
        }

        if (cycleTick == SLAM_TICK) {
            // The slam: levitation cut, gravity and fall
            // damage slammed up, victims recorded for cleanup.
            ListTag victims = new ListTag();
            for (LivingEntity enemy : ShulkerAbilitySupport.enemiesAroundSelf(ctx, RADIUS, 4.0, 12.0)) {
                enemy.removeEffect(MobEffects.LEVITATION);
                ShulkerAbilitySupport.addModifier(enemy, Attributes.GRAVITY,
                    new AttributeModifier(GRAVITY_ID, 3.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
                ShulkerAbilitySupport.addModifier(enemy, Attributes.FALL_DAMAGE_MULTIPLIER,
                    new AttributeModifier(FALL_ID, 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
                CompoundTag entry = new CompoundTag();
                entry.store("id", UUIDUtil.CODEC, enemy.getUUID());
                victims.add(entry);
            }
            ctx.state().put("slam_victims", victims);
            level.sendParticles(ParticleTypes.PORTAL, caster.getX(), caster.getY() + 0.8, caster.getZ(),
                10, 1.2, 0.6, 1.2, 0.4);
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.SHULKER_BOX_CLOSE, SoundSource.HOSTILE, 1.4f, 0.7f);
            return;
        }

        if (cycleTick == CYCLE_TICKS) {
            clearSlamVictims(ctx);
        }
    }

    /** Removes the slam modifiers from every recorded victim and clears the record. */
    private void clearSlamVictims(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag victims = ctx.state().getListOrEmpty("slam_victims");
        for (int i = 0; i < victims.size(); i++) {
            CompoundTag entry = victims.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (id == null) {
                continue;
            }
            Entity entity = level.getEntity(id);
            if (entity instanceof LivingEntity victim) {
                ShulkerAbilitySupport.removeModifier(victim, Attributes.GRAVITY, GRAVITY_ID);
                ShulkerAbilitySupport.removeModifier(victim, Attributes.FALL_DAMAGE_MULTIPLIER, FALL_ID);
            }
        }
        ctx.state().put("slam_victims", new ListTag());
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // A slam in flight when the ability ends (or the
        // shulker dies) must not leave its gravity behind.
        clearSlamVictims(ctx);
    }
}
