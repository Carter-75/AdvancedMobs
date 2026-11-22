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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.armadillo.Armadillo;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Rollout (Armadillo ability 1 of 5, mobility and attack).
 *
 * WHAT IT DOES: the armadillo tucks into a perfect ball and
 * physics takes the wheel. For two and a half seconds it
 * ricochets around the fight at gathering speed, bouncing
 * off walls without losing its line and caroming off
 * enemies, and every enemy it bounces off takes a hit with
 * a burst of scute flecks.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the armadillo on the ground and its current target
 *   within 16 blocks (distance squared 256).
 * - The ball form (the design's custom ball render state,
 *   in code): the vanilla synced ArmadilloState is switched
 *   to ROLLING at start() and re-asserted on any roll tick
 *   where the armadillo's brain has wandered out of it (the
 *   Panda roll-flag refresh pattern; switchToState is only
 *   called when the state differs, so the state's own tick
 *   counter is never reset by the refresh). ARMADILLO_ROLL
 *   plays at start(). end() switches back to IDLE if the
 *   state is still ROLLING.
 * - Physics kit (start(), all removed in end()): four
 *   transient modifiers for the whole activation, the
 *   design's three attribute lines plus fall safety:
 *   BOUNCINESS add_value +0.8 (id
 *   advancedmobs:rollout_bounciness; the ball returns 80
 *   percent of its collision velocity, and because the
 *   bounciness is a real attribute, vanilla emits the
 *   bounce game event for its collisions on its own, the
 *   design's bounce event hook), FRICTION_MODIFIER
 *   add_value -0.7 (id advancedmobs:rollout_friction;
 *   ground friction drops to 0.3 of normal so the ball
 *   keeps its speed across the floor), MOVEMENT_SPEED
 *   add_multiplied_base +0.30 (id
 *   advancedmobs:rollout_speed; the design's speed line,
 *   covering any vanilla movement between the code velocity
 *   writes), and SAFE_FALL_DISTANCE add_value +30.0 (id
 *   advancedmobs:rollout_fall; the ricochet's own drops
 *   never hurt the ball).
 * - Phase 0, the tuck (ticks 1 to 8): navigation stopped
 *   every tick (never NoAi), 3 DUST_PILLAR of the ground
 *   block at the feet every 2 ticks.
 * - Phase 1, the roll (ticks 9 to 58, 50 roll ticks): the
 *   direction is locked at start() toward the target
 *   ("dx", "dz", normalized). Each roll tick writes the
 *   horizontal velocity along the stored direction at the
 *   gathering speed: 0.55 blocks per tick on the first roll
 *   tick rising linearly to 0.95 at roll tick 20, flat
 *   after. Vertical velocity is left to gravity so the ball
 *   follows the ground and drops off ledges.
 * - The ricochet: each roll tick compares the actual
 *   horizontal displacement since the last tick against the
 *   intended step. When the armadillo reports a horizontal
 *   collision, the direction component whose progress was
 *   blocked (less than a quarter of its intended step)
 *   flips sign; if both components made progress the flip
 *   is skipped (a graze), and if neither did, both flip (a
 *   corner). The flipped direction is stored back, so the
 *   ball keeps caroming until the roll clock runs out
 *   instead of steering or stopping.
 * - The enemy bounce (per roll tick, radius 1.8 box, 1.0
 *   below to 2.0 above): every enemy in the box whose last
 *   bounce this activation was at least 10 ticks ago takes
 *   5.0 flat damage as a mob attack credited to the
 *   armadillo (the armadillo has no ATTACK_DAMAGE instance;
 *   see the provider comment), is launched 1.2 horizontally
 *   directly away from the ball and 0.45 up with hurtMarked
 *   set, and bursts 6 scute ITEM flecks and 4 CRIT at its
 *   chest, with ARMADILLO_LAND at pitch 1.2. The ball's own
 *   direction flips away from the victim (the component
 *   along the ball-to-victim axis reverses), so a bounce
 *   off an enemy visibly caroms the ball off at an angle.
 *   Bounce stamps live in the state tag's "hit" list
 *   ("id" plus "tick"); the list is pruned back to fresh
 *   stamps whenever it grows past 24 entries.
 * - The roll ends at roll tick 50 or the hard cap of tick
 *   70; there is no recovery phase, the framework cooldown
 *   is the rest.
 *
 * WHAT IT LOOKS LIKE: an armadillo snapping into a smooth
 * scute ball in a puff of dust, then a living bowling ball
 * gathering speed across the arena, pinging off walls and
 * shedding scute flecks every time it caroms off an enemy,
 * bodies bouncing away from each impact.
 *
 * PERFORMANCE: the tuck does one navigation stop and one
 * particle emission every 2 ticks. The roll does one
 * velocity write, one bounded radius-1.8 entity query, and
 * a state-tag list pass per tick, plus one ground block
 * read for the trail every 2 ticks. Particle budget per
 * activation: tuck 4 times 3 = 12, roll trail 25 times 2 =
 * 50, enemy bounces at most 5 per enemy per roll and
 * realistically a handful of enemies, 10 particles each:
 * total at most about 312.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply. The bounces run through the real BOUNCINESS
 * attribute and code velocity, never through block edits.
 *
 * State tag keys: "dx", "dz" (current roll direction,
 * flipped by ricochets), "lx", "lz" (position at the end
 * of the last roll tick), "hit" (list of compounds: "id",
 * "tick", one per bounced enemy).
 */
public final class RolloutAbility implements MobAbility {

    private static final int TUCK_TICKS = 8;
    private static final int ROLL_TICKS = 50;
    private static final int HARD_CAP_TICKS = 70;
    private static final double ROLL_START_SPEED = 0.55;
    private static final double ROLL_TOP_SPEED = 0.95;
    private static final int ROLL_RAMP_TICKS = 20;
    private static final double HIT_RADIUS = 1.8;
    private static final float BOUNCE_DAMAGE = 5.0f;
    private static final int BOUNCE_COOLDOWN_TICKS = 10;
    private static final double LAUNCH_HORIZONTAL = 1.2;
    private static final double LAUNCH_VERTICAL = 0.45;
    private static final Identifier BOUNCINESS_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "rollout_bounciness");
    private static final Identifier FRICTION_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "rollout_friction");
    private static final Identifier SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "rollout_speed");
    private static final Identifier FALL_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "rollout_fall");

    @Override
    public String id() {
        return "rollout";
    }

    @Override
    public String displayName() {
        return "Rollout";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().onGround()
            && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        state.put("hit", new ListTag());

        // Lock the opening line at the target.
        double dx = 0.0;
        double dz = 1.0;
        LivingEntity target = ctx.target();
        if (target != null) {
            dx = target.getX() - caster.getX();
            dz = target.getZ() - caster.getZ();
        }
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.001) {
            Vec3 look = caster.getLookAngle();
            dx = look.x;
            dz = look.z;
            length = Math.sqrt(dx * dx + dz * dz);
            if (length < 0.001) {
                dx = 0.0;
                dz = 1.0;
                length = 1.0;
            }
        }
        state.putDouble("dx", dx / length);
        state.putDouble("dz", dz / length);
        state.putDouble("lx", caster.getX());
        state.putDouble("lz", caster.getZ());

        // The physics kit (see the class comment).
        ArmadilloAbilitySupport.addModifier(caster, Attributes.BOUNCINESS,
            new AttributeModifier(BOUNCINESS_ID, 0.8, AttributeModifier.Operation.ADD_VALUE));
        ArmadilloAbilitySupport.addModifier(caster, Attributes.FRICTION_MODIFIER,
            new AttributeModifier(FRICTION_ID, -0.7, AttributeModifier.Operation.ADD_VALUE));
        ArmadilloAbilitySupport.addModifier(caster, Attributes.MOVEMENT_SPEED,
            new AttributeModifier(SPEED_ID, 0.30, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        ArmadilloAbilitySupport.addModifier(caster, Attributes.SAFE_FALL_DISTANCE,
            new AttributeModifier(FALL_ID, 30.0, AttributeModifier.Operation.ADD_VALUE));

        if (caster instanceof Armadillo armadillo) {
            armadillo.switchToState(Armadillo.ArmadilloState.ROLLING);
        }
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.ARMADILLO_ROLL, SoundSource.HOSTILE, 1.2f, 1.0f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        CompoundTag state = ctx.state();
        int ticks = ctx.ticksActive();

        if (ticks <= TUCK_TICKS) {
            caster.getNavigation().stop();
            if (level != null && ticks % 2 == 0) {
                level.sendParticles(ArmadilloAbilitySupport.groundDustPillar(level, caster.getX(), caster.getY(), caster.getZ()),
                    caster.getX(), caster.getY() + 0.15, caster.getZ(),
                    3, 0.4, 0.1, 0.4, 0.08);
            }
            return;
        }
        if (level == null) {
            return;
        }
        if (caster instanceof Armadillo armadillo
            && armadillo.getState() != Armadillo.ArmadilloState.ROLLING) {
            armadillo.switchToState(Armadillo.ArmadilloState.ROLLING);
        }

        int rollTick = ticks - TUCK_TICKS;
        double ramp = Math.min(1.0, rollTick / (double) ROLL_RAMP_TICKS);
        double speed = ROLL_START_SPEED + (ROLL_TOP_SPEED - ROLL_START_SPEED) * ramp;
        double dx = state.getDoubleOr("dx", 0.0);
        double dz = state.getDoubleOr("dz", 1.0);

        // The ricochet: read last tick's actual progress
        // against its intended step before writing this one.
        double movedX = caster.getX() - state.getDoubleOr("lx", caster.getX());
        double movedZ = caster.getZ() - state.getDoubleOr("lz", caster.getZ());
        if (caster.horizontalCollision) {
            boolean xBlocked = Math.abs(movedX) < Math.abs(dx) * speed * 0.25;
            boolean zBlocked = Math.abs(movedZ) < Math.abs(dz) * speed * 0.25;
            if (xBlocked) {
                dx = -dx;
            }
            if (zBlocked) {
                dz = -dz;
            }
            if (!xBlocked && !zBlocked) {
                // A graze that still registered a collision:
                // flip the dominant component so the ball
                // leaves the wall instead of grinding it.
                if (Math.abs(dx) >= Math.abs(dz)) {
                    dx = -dx;
                } else {
                    dz = -dz;
                }
            }
            double flipLength = Math.sqrt(dx * dx + dz * dz);
            if (flipLength > 0.001) {
                dx /= flipLength;
                dz /= flipLength;
            }
            state.putDouble("dx", dx);
            state.putDouble("dz", dz);
        }

        // The drive: code velocity along the stored line.
        caster.getNavigation().stop();
        Vec3 velocity = caster.getDeltaMovement();
        caster.setDeltaMovement(dx * speed, velocity.y, dz * speed);
        caster.hurtMarked = true;

        // The trail: dust off the tumbling ball every 2 ticks.
        if (ticks % 2 == 0) {
            level.sendParticles(ArmadilloAbilitySupport.groundDustPillar(level, caster.getX(), caster.getY(), caster.getZ()),
                caster.getX(), caster.getY() + 0.25, caster.getZ(),
                2, 0.45, 0.25, 0.45, 0.08);
        }

        // The enemy bounce: one bounded query per roll tick.
        List<LivingEntity> victims = ArmadilloAbilitySupport.enemiesInBox(
            ctx, caster.getX(), caster.getY(), caster.getZ(), HIT_RADIUS, 1.0, 2.0);
        for (LivingEntity victim : victims) {
            if (bounceStamp(state, victim.getUUID(), ticks)) {
                bounceOff(ctx, level, victim, ticks);
                // One bounce per tick keeps the carom readable.
                break;
            }
        }

        state.putDouble("lx", caster.getX());
        state.putDouble("lz", caster.getZ());
    }

    /**
     * Whether the given enemy may be bounced now; records the
     * bounce stamp when it may. Stamps older than the bounce
     * cooldown are refreshed in place; the list is pruned to
     * fresh stamps when it grows past 24 entries.
     */
    private boolean bounceStamp(CompoundTag state, UUID id, int ticks) {
        ListTag hit = state.getListOrEmpty("hit");
        for (int i = 0; i < hit.size(); i++) {
            CompoundTag entry = hit.getCompoundOrEmpty(i);
            UUID hitId = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id.equals(hitId)) {
                if (ticks - entry.getIntOr("tick", -BOUNCE_COOLDOWN_TICKS) < BOUNCE_COOLDOWN_TICKS) {
                    return false;
                }
                entry.putInt("tick", ticks);
                state.put("hit", hit);
                return true;
            }
        }
        if (hit.size() > 24) {
            ListTag fresh = new ListTag();
            for (int i = 0; i < hit.size(); i++) {
                CompoundTag entry = hit.getCompoundOrEmpty(i);
                if (ticks - entry.getIntOr("tick", 0) < BOUNCE_COOLDOWN_TICKS) {
                    fresh.add(entry);
                }
            }
            hit = fresh;
        }
        CompoundTag entry = new CompoundTag();
        entry.store("id", UUIDUtil.CODEC, id);
        entry.putInt("tick", ticks);
        hit.add(entry);
        state.put("hit", hit);
        return true;
    }

    /** Bounces one enemy off the ball and caroms the ball away from it. */
    private void bounceOff(AbilityContext ctx, ServerLevel level, LivingEntity victim, int ticks) {
        Mob caster = ctx.mob();
        victim.hurtServer(level, level.damageSources().mobAttack(caster), BOUNCE_DAMAGE);
        double awayX = victim.getX() - caster.getX();
        double awayZ = victim.getZ() - caster.getZ();
        double awayLength = Math.sqrt(awayX * awayX + awayZ * awayZ);
        if (awayLength < 1.0e-4) {
            awayX = 1.0;
            awayZ = 0.0;
            awayLength = 1.0;
        }
        awayX /= awayLength;
        awayZ /= awayLength;
        victim.setDeltaMovement(awayX * LAUNCH_HORIZONTAL, LAUNCH_VERTICAL, awayZ * LAUNCH_HORIZONTAL);
        victim.hurtMarked = true;

        level.sendParticles(ArmadilloAbilitySupport.SCUTE_FLECK,
            victim.getX(), victim.getY() + 0.8, victim.getZ(),
            6, 0.35, 0.35, 0.35, 0.1);
        level.sendParticles(ParticleTypes.CRIT,
            victim.getX(), victim.getY() + 0.8, victim.getZ(),
            4, 0.35, 0.35, 0.35, 0.1);
        level.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
            SoundEvents.ARMADILLO_LAND, SoundSource.HOSTILE, 0.9f, 1.2f);

        // The carom: reverse the direction component along
        // the ball-to-victim axis, so the ball leaves the
        // victim at a reflected angle.
        CompoundTag state = ctx.state();
        double dx = state.getDoubleOr("dx", 0.0);
        double dz = state.getDoubleOr("dz", 1.0);
        double dot = dx * awayX + dz * awayZ;
        dx -= 2.0 * dot * awayX;
        dz -= 2.0 * dot * awayZ;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length > 0.001) {
            state.putDouble("dx", dx / length);
            state.putDouble("dz", dz / length);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= TUCK_TICKS + ROLL_TICKS
            || ctx.ticksActive() >= HARD_CAP_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ArmadilloAbilitySupport.removeModifier(caster, Attributes.BOUNCINESS, BOUNCINESS_ID);
        ArmadilloAbilitySupport.removeModifier(caster, Attributes.FRICTION_MODIFIER, FRICTION_ID);
        ArmadilloAbilitySupport.removeModifier(caster, Attributes.MOVEMENT_SPEED, SPEED_ID);
        ArmadilloAbilitySupport.removeModifier(caster, Attributes.SAFE_FALL_DISTANCE, FALL_ID);
        if (caster instanceof Armadillo armadillo
            && armadillo.getState() == Armadillo.ArmadilloState.ROLLING) {
            armadillo.switchToState(Armadillo.ArmadilloState.IDLE);
        }
    }
}
