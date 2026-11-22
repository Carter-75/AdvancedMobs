package mod.advancedmobs.ability.armadillo;

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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/**
 * Burrow Blitz (Armadillo ability 3 of 5, mobility and
 * attack). The Warden's Burrow Ambush pattern at armadillo
 * scale: shorter tunnel, faster eruption, a lighter launch.
 *
 * WHAT IT DOES: the armadillo digs straight down in a spray
 * of dirt and is gone. A low ridge of kicked-up earth
 * crawls across the ground toward its target, and then the
 * ground under the target bursts open: the armadillo
 * erupts out, everything close is launched straight up,
 * and the armadillo lands the first hit on its target
 * before the target comes back down, while the fall itself
 * hits twice as hard as it should.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the armadillo on the ground and its current target
 *   within 16 blocks (distance squared 256). The dive point
 *   and the target's position are recorded in start().
 * - The dive (ticks 1 to 8): navigation stopped each tick
 *   (never NoAi). Every 2 ticks, 6 BLOCK particles of the
 *   ground block under the armadillo spray at its feet (the
 *   design's dirt spray). ARMADILLO_ROLL plays at start()
 *   at pitch 0.7, the digging scramble.
 * - The travel (ticks 9 to 38): at tick 8 the armadillo
 *   gains INVISIBILITY for 50 ticks with particles and icon
 *   hidden (the design's invisibility line) and stays
 *   rooted at the dive point by navigation stops, because
 *   teleporting the body under the floor mid-travel would
 *   suffocate it in solid ground (the Stoneborer Ambush
 *   pattern). The ridge: every 2 ticks, at the point lerped
 *   from the dive point to the recorded target position by
 *   travel progress, 3 BLOCK particles of the dive point's
 *   ground block and 2 DUST_PILLAR of the same state. The
 *   recorded target position refreshes every 5 ticks while
 *   the target lives, so the ridge tracks a moving target.
 * - The eruption (tick 38): the armadillo teleports to the
 *   recorded target position: that exact spot if its
 *   bounding box fits there, else the first fitting spot
 *   at radius 1.5 at angles 0, 90, 180, 270 degrees, else
 *   it erupts where it dove. INVISIBILITY is removed.
 *   ARMADILLO_LAND plays at volume 1.5, 12 BLOCK particles
 *   of the ground block and 8 DUST_PILLAR burst at the
 *   landing point, and every enemy (the package enemy
 *   rule) within radius 2.5 of the landing point:
 *   - is launched straight up: delta movement set to 0.15
 *     blocks per tick horizontally away from the landing
 *     point and 1.15 straight up (the design's upward
 *     impulse on the victim), hurtMarked set,
 *   - gains a transient FALL_DAMAGE_MULTIPLIER modifier of
 *     +1.0 add_multiplied_total (fall damage doubled; id
 *     advancedmobs:burrow_blitz_fall), recorded per victim
 *     in the state tag and held until end(),
 *   - and the armadillo's own target, when it is among
 *     them, takes the first hit: 6.0 flat damage as a mob
 *     attack credited to the armadillo (no ATTACK_DAMAGE
 *     instance; see the provider comment) with 10 CRIT
 *     particles at its chest (the design's crit line).
 * - Duration: 200 ticks total (the fall window runs past
 *   the eruption). If the armadillo is terminated
 *   mid-travel, end() removes the invisibility and no
 *   eruption ever happens; if terminated after the
 *   eruption, end() strips the fall modifiers early.
 *
 * WHAT IT LOOKS LIKE: an armadillo vanishing into a spray
 * of its own dirt, a low ridge of earth crawling across the
 * arena floor, then the ground bursting open under the
 * target in a fountain of dirt, bodies flying straight up,
 * and the armadillo already biting as they rise.
 *
 * PERFORMANCE: the dive and travel are one navigation stop
 * per tick plus particles every 2 ticks and one target
 * position read every 5 ticks; the eruption is one bounded
 * query and at most 5 collision checks. Particle budget per
 * activation: dive 4 pulses of 6 = 24, ridge 15 pulses of
 * 5 = 75, eruption 20 plus 10 crit, total at most 129.
 *
 * TERRAIN: this ability changes no blocks. The dive and the
 * eruption are particle theater over a teleport, so the
 * mobGriefing gate does not apply anywhere, and this
 * comment is the statement of that.
 *
 * CLEANUP: end() removes INVISIBILITY from the armadillo (a
 * safety net for termination mid-travel; on a natural
 * finish it is long gone) and removes the
 * FALL_DAMAGE_MULTIPLIER modifier from every recorded
 * victim still resolvable. The modifier is transient, so a
 * victim that unloaded loses it on its own.
 *
 * State tag keys: "dx", "dy", "dz" (the dive point),
 * "tx", "ty", "tz" (the tracked target position),
 * "erupted" (whether the eruption has fired), "victims"
 * (list of compounds: "id", one per launched enemy).
 */
public final class BurrowBlitzAbility implements MobAbility {

    private static final int DURATION_TICKS = 200;
    private static final int DIVE_TICKS = 8;
    private static final int ERUPT_TICK = 38;
    private static final double ERUPT_RADIUS = 2.5;
    private static final float FIRST_HIT_DAMAGE = 6.0f;
    private static final double LAUNCH_UP = 1.15;
    private static final double LAUNCH_OUT = 0.15;
    private static final Identifier FALL_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "burrow_blitz_fall");

    @Override
    public String id() {
        return "burrow_blitz";
    }

    @Override
    public String displayName() {
        return "Burrow Blitz";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().onGround()
            && ctx.mob().distanceToSqr(target) <= 256.0;
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
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.ARMADILLO_ROLL,
                SoundSource.HOSTILE, 1.2f, 0.7f);
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
                level.sendParticles(ArmadilloAbilitySupport.groundBlock(level, mob.getX(), mob.getY(), mob.getZ()),
                    mob.getX(), mob.getY() + 0.2, mob.getZ(), 6, 0.5, 0.3, 0.5, 0.12);
            }
            if (ticks == DIVE_TICKS) {
                // Under the floor: hidden for the whole travel.
                mob.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 50, 0, false, false, false));
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
                level.sendParticles(ArmadilloAbilitySupport.groundBlock(level, ridgeX, ridgeY, ridgeZ),
                    ridgeX, ridgeY + 0.2, ridgeZ, 3, 0.4, 0.2, 0.4, 0.1);
                level.sendParticles(ArmadilloAbilitySupport.groundDustPillar(level, ridgeX, ridgeY, ridgeZ),
                    ridgeX, ridgeY + 0.2, ridgeZ, 2, 0.4, 0.2, 0.4, 0.06);
            }
            return;
        }

        if (ticks == ERUPT_TICK && !state.getBooleanOr("erupted", false)) {
            erupt(ctx);
        }
    }

    /** The eruption: teleport under the target, burst out, launch everyone close, land the first hit. */
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

        level.playSound(null, landX, landY, landZ, SoundEvents.ARMADILLO_LAND,
            SoundSource.HOSTILE, 1.5f, 1.0f);
        level.sendParticles(ArmadilloAbilitySupport.groundBlock(level, landX, landY, landZ),
            landX, landY + 0.3, landZ, 12, 0.6, 0.4, 0.6, 0.15);
        level.sendParticles(ArmadilloAbilitySupport.groundDustPillar(level, landX, landY, landZ),
            landX, landY + 0.3, landZ, 8, 0.6, 0.4, 0.6, 0.1);

        LivingEntity target = ctx.target();
        ListTag victims = new ListTag();
        List<LivingEntity> hit = ArmadilloAbilitySupport.enemiesInBox(ctx, landX, landY, landZ,
            ERUPT_RADIUS, 2.0, 3.0);
        for (LivingEntity victim : hit) {
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
            ArmadilloAbilitySupport.addModifier(victim, Attributes.FALL_DAMAGE_MULTIPLIER,
                new AttributeModifier(FALL_ID, 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, victim.getUUID());
            victims.add(entry);

            // The first hit lands on the target before it falls.
            if (victim == target) {
                victim.hurtServer(level, level.damageSources().mobAttack(mob), FIRST_HIT_DAMAGE);
                level.sendParticles(ParticleTypes.CRIT,
                    victim.getX(), victim.getY() + 1.0, victim.getZ(),
                    10, 0.4, 0.5, 0.4, 0.12);
            }
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
                ArmadilloAbilitySupport.removeModifier(victim, Attributes.FALL_DAMAGE_MULTIPLIER, FALL_ID);
            }
        }
    }
}
