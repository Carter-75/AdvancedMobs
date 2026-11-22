package mod.advancedmobs.ability.warden;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/**
 * Burrow Ambush (Warden ability 5 of 5, mobility).
 *
 * WHAT IT DOES: the warden dives headfirst into the floor
 * in a spray of torn earth and is gone. For two seconds the
 * only sign of it is a moving ridge of sculk dust crawling
 * across the ground toward its target and a muffled
 * heartbeat under the stone. Then the floor beneath the
 * target explodes: the warden erupts out of the earth in a
 * burst of debris, everything nearby is launched skyward,
 * and the fall back down hits twice as hard as it should.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the warden's current target within 24 blocks (distance
 *   squared 576) and the warden on the ground. The dive
 *   point (the warden's feet) and the target's position are
 *   recorded in start().
 * - The dive (ticks 1 to 10): the navigation is stopped
 *   each tick (never NoAi). Every 2 ticks, 6 BLOCK
 *   particles of the ground block under the warden spray at
 *   its feet. A WARDEN_DIG sound (volume 2.0, pitch 0.8)
 *   plays at start().
 * - The travel (ticks 11 to 50): at tick 10 the warden
 *   gains INVISIBILITY for 60 ticks with particles and icon
 *   hidden, and stays rooted at the dive point by navigation
 *   stops. This is the Stoneborer Ambush pattern: the body
 *   waits hidden at the dive point while the ridge shows
 *   the path, because teleporting the body under the floor
 *   mid-travel would suffocate it in solid stone. The ridge:
 *   every 2 ticks, at the point lerped from the dive point
 *   to the recorded target position by travel progress, 2
 *   SCULK_CHARGE_POP sparks and 2 BLOCK particles of the
 *   dive point's ground block. The recorded target position
 *   refreshes every 5 ticks while the target lives, so the
 *   ridge tracks a moving target. A muffled WARDEN_HEARTBEAT
 *   (volume 0.8, pitch 0.6) sounds at the ridge at ticks 20
 *   and 40.
 * - The eruption (tick 50): the warden teleports to the
 *   recorded target position: that exact spot if its
 *   bounding box fits there, else the first fitting spot at
 *   radius 1.5 at angles 0, 90, 180, 270 degrees, else it
 *   erupts where it dove. INVISIBILITY is removed. The
 *   eruption plays WARDEN_EMERGE (volume 2.0), bursts 4
 *   EXPLOSION_EMITTER and 12 BLOCK particles of the ground
 *   block at the landing point, and hits every enemy (the
 *   package enemy rule) within radius 4.0 of the landing
 *   point:
 *   - 8.0 points of typed mob-attack damage credited to
 *     the warden,
 *   - a launch: delta movement set to 0.4 blocks per tick
 *     horizontally away from the landing point and 1.1
 *     straight up, hurtMarked set so the launch syncs,
 *   - a transient FALL_DAMAGE_MULTIPLIER modifier of +1.0
 *     add_multiplied_total (fall damage doubled; id
 *     advancedmobs:burrow_ambush_fall), held until end()
 *     and recorded per victim in the state tag. The design's
 *     "heavy fall damage on the way down": launched 1.1 up
 *     from flat ground is roughly an 11 block fall, about 8
 *     damage, doubled to about 16 by the modifier.
 * - Duration: 250 ticks total (the fall window runs 200
 *   ticks past the eruption). If the warden is terminated
 *   mid-travel, end() removes the invisibility and no
 *   eruption ever happens; if terminated after the eruption,
 *   end() strips the fall modifiers early.
 *
 * TERRAIN: this ability changes no blocks. The dive and the
 * eruption are particle theater over a teleport, so the
 * mobGriefing gate does not apply anywhere, and this comment
 * is the statement of that.
 *
 * WHAT IT LOOKS LIKE: a warden vanishing into a spray of
 * its own floor, a low ridge of teal dust crawling across
 * the ground with a heartbeat under it, then the earth
 * bursting open under the target and bodies flying.
 *
 * PERFORMANCE: the dive and travel are one navigation stop
 * per tick plus particles every 2 ticks and one target
 * position read every 5 ticks; the eruption is one bounded
 * query and at most 5 collision checks. No entity scans.
 * Particle budget per activation: dive 5 pulses of 6 = 30,
 * ridge 20 pulses of 4 = 80, eruption 16, total at most 126.
 *
 * CLEANUP: end() removes INVISIBILITY from the warden (a
 * safety net for termination mid-travel; on a natural finish
 * it is long gone) and removes the FALL_DAMAGE_MULTIPLIER
 * modifier from every recorded victim still resolvable. The
 * modifier is transient, so a victim that unloaded loses it
 * on its own.
 *
 * State tag keys: "dx", "dy", "dz" (the dive point),
 * "tx", "ty", "tz" (the tracked target position), "erupted"
 * (whether the eruption has fired), "victims" (list of
 * compounds: "id", one per launched enemy).
 */
public final class BurrowAmbushAbility implements MobAbility {

    static final int DURATION_TICKS = 250;
    private static final int DIVE_TICKS = 10;
    private static final int ERUPT_TICK = 50;
    private static final double ERUPT_RADIUS = 4.0;
    private static final float ERUPT_DAMAGE = 8.0f;
    private static final double LAUNCH_UP = 1.1;
    private static final double LAUNCH_OUT = 0.4;
    private static final Identifier FALL_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "burrow_ambush_fall");

    @Override
    public String id() {
        return "burrow_ambush";
    }

    @Override
    public String displayName() {
        return "Burrow Ambush";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().onGround()
            && ctx.mob().distanceToSqr(target) <= 576.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        state.putDouble("dx", mob.getX());
        state.putDouble("dy", mob.getY());
        state.putDouble("dz", mob.getZ());
        LivingEntity target = ctx.target();
        if (target != null) {
            state.putDouble("tx", target.getX());
            state.putDouble("ty", target.getY());
            state.putDouble("tz", target.getZ());
        } else {
            state.putDouble("tx", mob.getX());
            state.putDouble("ty", mob.getY());
            state.putDouble("tz", mob.getZ());
        }
        state.putBoolean("erupted", false);
        state.put("victims", new ListTag());

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.WARDEN_DIG,
                SoundSource.HOSTILE, 2.0f, 0.8f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        Mob mob = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();

        if (ticks <= DIVE_TICKS) {
            mob.getNavigation().stop();
            if (ticks % 2 == 0) {
                sprayGround(level, mob.getX(), mob.getY(), mob.getZ(), 6);
            }
            if (ticks == DIVE_TICKS) {
                // Under the floor: hidden for the whole travel.
                mob.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 60, 0, false, false, false));
            }
            return;
        }

        if (ticks < ERUPT_TICK) {
            mob.getNavigation().stop();
            // The ridge tracks a living target.
            if (ticks % 5 == 0) {
                LivingEntity target = ctx.target();
                if (target != null) {
                    state.putDouble("tx", target.getX());
                    state.putDouble("ty", target.getY());
                    state.putDouble("tz", target.getZ());
                }
            }
            if (ticks % 2 == 0) {
                double progress = (ticks - DIVE_TICKS) / (double) (ERUPT_TICK - DIVE_TICKS);
                double ridgeX = state.getDoubleOr("dx", mob.getX())
                    + (state.getDoubleOr("tx", mob.getX()) - state.getDoubleOr("dx", mob.getX())) * progress;
                double ridgeZ = state.getDoubleOr("dz", mob.getZ())
                    + (state.getDoubleOr("tz", mob.getZ()) - state.getDoubleOr("dz", mob.getZ())) * progress;
                double ridgeY = state.getDoubleOr("dy", mob.getY());
                level.sendParticles(ParticleTypes.SCULK_CHARGE_POP, ridgeX, ridgeY + 0.3, ridgeZ,
                    2, 0.4, 0.15, 0.4, 0.05);
                sprayGround(level, ridgeX, ridgeY, ridgeZ, 2);
                if (ticks == 20 || ticks == 40) {
                    level.playSound(null, ridgeX, ridgeY, ridgeZ, SoundEvents.WARDEN_HEARTBEAT,
                        SoundSource.HOSTILE, 0.8f, 0.6f);
                }
            }
            return;
        }

        if (ticks == ERUPT_TICK && !state.getBooleanOr("erupted", false)) {
            erupt(ctx);
        }
    }

    /** Sprays BLOCK particles of the ground block under the given feet position. */
    private void sprayGround(ServerLevel level, double x, double y, double z, int count) {
        BlockPos below = BlockPos.containing(x, y - 0.5, z);
        BlockState ground = level.getBlockState(below);
        if (ground.isAir()) {
            return;
        }
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground),
            x, y + 0.2, z, count, 0.5, 0.3, 0.5, 0.12);
    }

    /** The eruption: teleport under the target, burst out, launch everyone close. */
    private void erupt(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        state.putBoolean("erupted", true);

        double tx = state.getDoubleOr("tx", mob.getX());
        double ty = state.getDoubleOr("ty", mob.getY());
        double tz = state.getDoubleOr("tz", mob.getZ());

        // Landing spot: the target's feet, then the ring
        // around it, then the dive point as the last resort.
        double landX = state.getDoubleOr("dx", mob.getX());
        double landY = state.getDoubleOr("dy", mob.getY());
        double landZ = state.getDoubleOr("dz", mob.getZ());
        if (isFreeSpot(level, mob, tx, ty, tz)) {
            landX = tx;
            landY = ty;
            landZ = tz;
        } else {
            for (int i = 0; i < 4; i++) {
                double angle = Math.toRadians(i * 90.0);
                double candidateX = tx + Math.cos(angle) * 1.5;
                double candidateZ = tz + Math.sin(angle) * 1.5;
                if (isFreeSpot(level, mob, candidateX, ty, candidateZ)) {
                    landX = candidateX;
                    landY = ty;
                    landZ = candidateZ;
                    break;
                }
            }
        }
        mob.snapTo(landX, landY, landZ, mob.getYRot(), 0.0f);
        mob.removeEffect(MobEffects.INVISIBILITY);

        level.playSound(null, landX, landY, landZ, SoundEvents.WARDEN_EMERGE,
            SoundSource.HOSTILE, 2.0f, 1.0f);
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, landX, landY + 0.5, landZ,
            4, 0.5, 0.5, 0.5, 0.0);
        sprayGround(level, landX, landY, landZ, 12);

        ListTag victims = new ListTag();
        List<LivingEntity> hit = WardenAbilitySupport.enemiesInBox(ctx, landX, landY, landZ,
            ERUPT_RADIUS, 2.0, 3.0);
        for (LivingEntity victim : hit) {
            victim.hurtServer(level, level.damageSources().mobAttack(mob), ERUPT_DAMAGE);
            double awayX = victim.getX() - landX;
            double awayZ = victim.getZ() - landZ;
            double awayLength = Math.sqrt(awayX * awayX + awayZ * awayZ);
            if (awayLength < 1.0e-4) {
                awayX = 1.0;
                awayZ = 0.0;
                awayLength = 1.0;
            }
            victim.setDeltaMovement(awayX / awayLength * LAUNCH_OUT, LAUNCH_UP,
                awayZ / awayLength * LAUNCH_OUT);
            victim.hurtMarked = true;
            // The way down hurts twice as much.
            WardenAbilitySupport.addModifier(victim, Attributes.FALL_DAMAGE_MULTIPLIER,
                new AttributeModifier(FALL_ID, 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, victim.getUUID());
            victims.add(entry);
        }
        state.put("victims", victims);
    }

    /** Whether the mob's bounding box at the given feet position collides with nothing. */
    private boolean isFreeSpot(ServerLevel level, Mob mob, double x, double y, double z) {
        double halfWidth = mob.getBbWidth() / 2.0;
        AABB box = new AABB(x - halfWidth, y, z - halfWidth, x + halfWidth, y + mob.getBbHeight(), z + halfWidth);
        return level.noCollision(mob, box);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        // Safety net for termination mid-travel.
        mob.removeEffect(MobEffects.INVISIBILITY);

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag victims = ctx.state().getListOrEmpty("victims");
        for (int i = 0; i < victims.size(); i++) {
            CompoundTag entry = victims.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (id != null && level.getEntity(id) instanceof LivingEntity victim) {
                WardenAbilitySupport.removeModifier(victim, Attributes.FALL_DAMAGE_MULTIPLIER, FALL_ID);
            }
        }
    }
}
