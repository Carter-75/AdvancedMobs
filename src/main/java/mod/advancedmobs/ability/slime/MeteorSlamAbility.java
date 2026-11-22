package mod.advancedmobs.ability.slime;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Meteor Slam (Slime ability 1 of 5, attack).
 *
 * WHAT IT DOES: the slime launches itself into the sky,
 * ITEM_SLIME flecks shedding off its body as it climbs, hangs
 * at the peak for a heartbeat, then slams down with multiplied
 * gravity in a DUST_PILLAR ring and an EXPLOSION_EMITTER
 * flash. The landing deals typed impact damage in a radius and
 * hurls survivors outward, and it costs the slime nothing
 * thanks to its negated fall damage.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists, within 12 blocks
 *   (distance squared 144), and the slime is on the ground (it
 *   needs ground under it to launch).
 * - Phase 0, LAUNCH (start()): the slime's velocity is set to
 *   1.45 straight up plus 0.3 toward its target horizontally,
 *   so the arc carries it onto the target's head. A
 *   FALL_DAMAGE_MULTIPLIER modifier (add_multiplied_total
 *   -1.0, id advancedmobs:meteor_slam_fall) is applied for the
 *   whole activation: the slime's own fall damage is zeroed,
 *   which is the design's "negated fall damage". Every 2 ticks
 *   of the climb, 2 ITEM_SLIME flecks shed at the body.
 * - Phase 1, HANG: begins when the climb dies (vertical
 *   velocity at or below 0.05, and at least 4 ticks in, so the
 *   launch tick itself never reads as the peak). For 10 ticks
 *   the slime hangs: each tick its velocity is damped to a
 *   near-hover (horizontal times 0.8, vertical 0.03), a fat
 *   target hanging in the sky over the fight.
 * - Phase 2, SLAM: a GRAVITY modifier (add_multiplied_total
 *   +2.0, three times gravity, id
 *   advancedmobs:meteor_slam_gravity) is applied and the
 *   slime's downward velocity is set to -1.6; gravity does the
 *   rest. The slime's own AI keeps hopping during the flight,
 *   but the hang and slam phases overwrite its velocity every
 *   tick, so the dive stays true.
 * - IMPACT: the first tick the slime is on the ground at or
 *   after tick 6 in any phase (an early landing on a ceiling
 *   edge or ledge still slams). One bounded enemy query
 *   (SlimeAbilitySupport rules) in radius 3.5, from 1.5 below
 *   to 2 above the landing point: every enemy takes 8.0 FALL
 *   damage (level.damageSources().fall(), the typed impact
 *   damage the design calls for; no attacker is attached, the
 *   same approach Seismic Slam uses) and is hurled outward:
 *   horizontal velocity 1.15 away from the slam center plus
 *   0.5 up (a victim dead center, with no outward direction,
 *   is hurled along a random heading). The impact fires once;
 *   the ability then lingers 2 ticks and finishes. Hard finish
 *   at tick 120 in case the slime somehow never lands.
 *
 * WHAT IT LOOKS LIKE: a slime streaking upward shedding green
 * flecks, a half-second hang at the peak, then a screaming
 * dive that lands in 1 EXPLOSION_EMITTER flash and a ring of
 * 10 DUST_PILLAR columns (2 particles each at radius 2.8),
 * with a SLIME_SQUISH at the launch and a GENERIC_EXPLODE
 * (volume 0.7, pitch 1.1) under a deeper SLIME_SQUISH (volume
 * 1.0, pitch 0.7) at the impact.
 *
 * PERFORMANCE: tick() reads velocity and the ground flag only;
 * the single entity query runs once, at the impact. The fall
 * and gravity modifiers are resolved at start() and at the
 * slam transition, never per tick. Particle budget per
 * activation, hard caps by construction: climb at most 10
 * bursts times 2 = 20 ITEM_SLIME, impact 1 EXPLOSION_EMITTER
 * plus 20 DUST_PILLAR, total at most 41 particles.
 *
 * CLEANUP: end() removes both attribute modifiers by id
 * (removeModifier is a no-op when absent, so a slam that never
 * reached phase 2 cleans up fine). Nothing else was created.
 *
 * State tag keys: "phase" (0 climb, 1 hang, 2 slam, 3 done),
 * "hang" (ticks spent hanging), "impact_tick" (the tick the
 * impact fired, or -1).
 */
public final class MeteorSlamAbility implements MobAbility {

    private static final int HARD_FINISH_TICKS = 120;
    private static final int HANG_TICKS = 10;
    private static final double LAUNCH_UP = 1.45;
    private static final double LAUNCH_TOWARD = 0.3;
    private static final double SLAM_DOWN = -1.6;
    private static final double IMPACT_RADIUS = 3.5;
    private static final float IMPACT_DAMAGE = 8.0f;
    private static final Identifier FALL_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "meteor_slam_fall");
    private static final Identifier GRAVITY_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "meteor_slam_gravity");

    @Override
    public String id() {
        return "meteor_slam";
    }

    @Override
    public String displayName() {
        return "Meteor Slam";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return ctx.target() != null
            && ctx.mob().onGround()
            && ctx.mob().distanceToSqr(ctx.target()) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        ctx.state().putInt("phase", 0);
        ctx.state().putInt("hang", 0);
        ctx.state().putInt("impact_tick", -1);
        // The meteor takes no fall damage from its own slam.
        SlimeAbilitySupport.addModifier(mob, Attributes.FALL_DAMAGE_MULTIPLIER,
            new AttributeModifier(FALL_ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        // The launch: straight up, leaning toward the target so
        // the arc lands on it.
        LivingEntity target = ctx.target();
        double towardX = 0.0;
        double towardZ = 0.0;
        if (target != null) {
            double dx = target.getX() - mob.getX();
            double dz = target.getZ() - mob.getZ();
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length > 0.001) {
                towardX = dx / length * LAUNCH_TOWARD;
                towardZ = dz / length * LAUNCH_TOWARD;
            }
        }
        mob.setDeltaMovement(towardX, LAUNCH_UP, towardZ);
        mob.hurtMarked = true;
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.SLIME_SQUISH,
                SoundSource.HOSTILE, 0.8f, 0.6f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        int ticks = ctx.ticksActive();
        int phase = ctx.state().getIntOr("phase", 0);
        Vec3 velocity = mob.getDeltaMovement();

        if (phase == 0) {
            // The climb: flecks shed every 2 ticks.
            if (ticks % 2 == 0) {
                level.sendParticles(ParticleTypes.ITEM_SLIME, mob.getX(), mob.getY() + 0.4, mob.getZ(),
                    2, mob.getBbWidth() * 0.4, 0.3, mob.getBbWidth() * 0.4, 0.05);
            }
            if (ticks >= 6 && mob.onGround()) {
                runImpact(ctx, level);
                return;
            }
            if (ticks >= 4 && velocity.y <= 0.05) {
                ctx.state().putInt("phase", 1);
            }
            return;
        }
        if (phase == 1) {
            // The hang: hover almost still at the peak.
            mob.setDeltaMovement(velocity.x * 0.8, 0.03, velocity.z * 0.8);
            mob.hurtMarked = true;
            int hang = ctx.state().getIntOr("hang", 0) + 1;
            ctx.state().putInt("hang", hang);
            if (hang >= HANG_TICKS) {
                // The slam: triple gravity and a hard downward push.
                SlimeAbilitySupport.addModifier(mob, Attributes.GRAVITY,
                    new AttributeModifier(GRAVITY_ID, 2.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
                mob.setDeltaMovement(velocity.x, SLAM_DOWN, velocity.z);
                mob.hurtMarked = true;
                ctx.state().putInt("phase", 2);
            }
            return;
        }
        if (phase == 2 && mob.onGround()) {
            runImpact(ctx, level);
        }
    }

    /** The landing impact: fall damage and the hurl for every enemy at the slam point. */
    private static void runImpact(AbilityContext ctx, ServerLevel level) {
        Mob mob = ctx.mob();
        ctx.state().putInt("phase", 3);
        ctx.state().putInt("impact_tick", ctx.ticksActive());
        double cx = mob.getX();
        double cy = mob.getY();
        double cz = mob.getZ();
        List<LivingEntity> victims = SlimeAbilitySupport.enemiesInBox(ctx, cx, cy, cz, IMPACT_RADIUS, 1.5, 2.0);
        for (LivingEntity victim : victims) {
            victim.hurtServer(level, level.damageSources().fall(), IMPACT_DAMAGE);
            double dx = victim.getX() - cx;
            double dz = victim.getZ() - cz;
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length < 0.001) {
                double angle = ctx.random().nextDouble() * Math.PI * 2.0;
                dx = Math.cos(angle);
                dz = Math.sin(angle);
                length = 1.0;
            }
            Vec3 current = victim.getDeltaMovement();
            victim.setDeltaMovement(current.x + dx / length * 1.15, 0.5, current.z + dz / length * 1.15);
            victim.hurtMarked = true;
        }
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, cx, cy + 0.3, cz, 1, 0.0, 0.0, 0.0, 0.0);
        // DUST_PILLAR carries a block state: the pillars are made
        // of the ground the meteor landed on (dirt as fallback).
        BlockState ground = level.getBlockState(mob.blockPosition().below());
        BlockParticleOption pillar = new BlockParticleOption(ParticleTypes.DUST_PILLAR,
            ground.isAir() ? Blocks.DIRT.defaultBlockState() : ground);
        for (int i = 0; i < 10; i++) {
            double angle = Math.PI * 2.0 * i / 10.0;
            level.sendParticles(pillar,
                cx + Math.cos(angle) * 2.8, cy + 0.2, cz + Math.sin(angle) * 2.8,
                2, 0.25, 0.4, 0.25, 0.05);
        }
        level.playSound(null, cx, cy, cz, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 0.7f, 1.1f);
        level.playSound(null, cx, cy, cz, SoundEvents.SLIME_SQUISH, SoundSource.HOSTILE, 1.0f, 0.7f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks >= HARD_FINISH_TICKS) {
            return true;
        }
        int impactTick = ctx.state().getIntOr("impact_tick", -1);
        return impactTick >= 0 && ticks >= impactTick + 2;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        SlimeAbilitySupport.removeModifier(mob, Attributes.FALL_DAMAGE_MULTIPLIER, FALL_ID);
        SlimeAbilitySupport.removeModifier(mob, Attributes.GRAVITY, GRAVITY_ID);
    }
}
