package mod.advancedmobs.ability.panda;

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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.panda.Panda;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Rolling Boulder (Panda ability 1 of 5, attack and mobility).
 *
 * WHAT IT DOES: the panda tucks into a ball and rolls,
 * gathering speed with a trail of dust and leaves, its body
 * visibly tumbling. Everything in the roll path is bowled
 * over with knockback and damage. The panda only unrolls
 * when it hits a wall or runs out of roll, slightly dizzy
 * but unhurt.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the panda on the ground and its current target within
 *   16 blocks (distance squared 256).
 * - Direction (start()): the roll line is locked at start:
 *   the horizontal direction from the panda to its target,
 *   stored in the state tag ("dx", "dz", normalized; the
 *   panda's look direction if the target is exactly
 *   overhead), and the start position ("sx", "sy", "sz").
 *   The panda does NOT steer during the roll.
 * - Roll attributes (start(), removed in end()): two
 *   transient modifiers for the whole activation:
 *   ATTACK_KNOCKBACK add_value +2.0 (id
 *   advancedmobs:rolling_boulder_knockback; the design's
 *   ATTACK_KNOCKBACK line, so any vanilla melee the panda
 *   lands mid-activation hits at the boosted value; the
 *   bowling launch itself is code velocity, below) and
 *   SAFE_FALL_DISTANCE add_value +30.0 (id
 *   advancedmobs:rolling_boulder_fall; the design's
 *   SAFE_FALL_DISTANCE line: the roll's own drops and
 *   bounces never hurt the panda, "unhurt" by design).
 * - Phase 0, the tuck (ticks 1 to 10): navigation is
 *   stopped every tick (never NoAi) and 3 DUST_PILLAR of
 *   the ground block kick up at the panda's feet every 2
 *   ticks as it curls up.
 * - Phase 1, the roll (from tick 11, at most 40 ticks):
 *   navigation stopped, horizontal velocity written along
 *   the locked direction, vertical left to gravity so the
 *   roll follows the ground. Speed GATHERS: 0.50 blocks per
 *   tick for the first roll tick, rising linearly to 0.95
 *   blocks per tick at roll tick 20 and flat after that
 *   (the design's "gathering speed"). The tumbling look is
 *   the panda's own vanilla roll pose: when the caster is a
 *   Panda, roll(true) is set at roll entry and refreshed
 *   every roll tick (the vanilla roll data flag; the
 *   panda's render tumbles while it is set). 3 DUST_PILLAR
 *   of the ground block plus 2 TINTED_LEAVES spray at the
 *   panda every roll tick.
 * - The bowling (per-tick hit check, radius 2.2 box, 1.0
 *   below to 2.0 above): every enemy in the box that has
 *   not already been hit this roll takes damage equal to
 *   the panda's live ATTACK_DAMAGE attribute value (6.0
 *   for an unupgraded panda) as a mob attack credited to
 *   the panda, plus the launch: 1.6 horizontal along the
 *   roll direction and 0.5 up, hurtMarked set. Hit victims
 *   are recorded in the state tag's "hit" list so each
 *   enemy is bowled at most once per roll.
 * - Roll stop conditions (first one wins): horizontal
 *   collision (a wall, the design's unroll trigger); 24
 *   blocks travelled from the start; the panda falls 5 or
 *   more blocks below the start height; 40 roll ticks run
 *   out.
 * - Phase 2, the dizzy spell (40 ticks from the stop): the
 *   panda unrolls (roll(false)), horizontal velocity is
 *   damped to a fifth once at phase entry, navigation stays
 *   stopped, and the panda carries NAUSEA at amplifier 0
 *   for 40 ticks applied at phase entry (the design's
 *   "slightly dizzy"; it rides its own clock and is not
 *   stripped in end()). 2 TINTED_LEAVES drift down over the
 *   panda every 4 ticks. The ability finishes when the
 *   dizzy spell ends, or at the hard cap of tick 130.
 * - end(): both transient modifiers are removed and the
 *   roll pose is cleared if it is still set.
 *
 * WHAT IT LOOKS LIKE: a panda curling into a ball in a puff
 * of dust, then a tumbling black and white boulder trailing
 * dust pillars and green leaves, enemies flying off the
 * roll path, and the panda wobbling upright at the end with
 * leaves settling over it, queasy but unharmed.
 *
 * PERFORMANCE: the tuck and dizzy phases do one navigation
 * stop and at most one particle emission every 2 to 4
 * ticks. The roll does one velocity write, at most one
 * ground block read, and one bounded radius-2.2 entity
 * query per tick. The hit list is a state tag list pass
 * only on ticks where the box query found someone.
 * Particle budget per activation: tuck 5 times 3 = 15,
 * roll at most 40 times 5 = 200, dizzy 10 times 2 = 20,
 * total at most 235 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply. DUST_PILLAR is a particle of the ground
 * block's state, not a placed block.
 *
 * State tag keys: "phase" (0 tuck, 1 rolling, 2 dizzy),
 * "phase_end" (tick the dizzy spell ends), "dx", "dz"
 * (locked roll direction), "sx", "sy", "sz" (start
 * position), "roll_start" (tick the roll began), "hit"
 * (list of compounds, one per bowled enemy: "id", its
 * UUID).
 */
public final class RollingBoulderAbility implements MobAbility {

    private static final int TUCK_TICKS = 10;
    private static final int ROLL_MAX_TICKS = 40;
    private static final int DIZZY_TICKS = 40;
    private static final int HARD_CAP_TICKS = 130;
    private static final double ROLL_START_SPEED = 0.50;
    private static final double ROLL_TOP_SPEED = 0.95;
    private static final int ROLL_RAMP_TICKS = 20;
    private static final double MAX_TRAVEL_SQR = 576.0; // 24 blocks
    private static final double FALL_STOP_DROP = 5.0;
    private static final double HIT_RADIUS = 2.2;
    private static final double LAUNCH_HORIZONTAL = 1.6;
    private static final double LAUNCH_VERTICAL = 0.5;
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "rolling_boulder_knockback");
    private static final Identifier FALL_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "rolling_boulder_fall");

    @Override
    public String id() {
        return "rolling_boulder";
    }

    @Override
    public String displayName() {
        return "Rolling Boulder";
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
        state.putInt("phase", 0);
        state.putInt("phase_end", 0);
        state.putInt("roll_start", 0);
        state.put("hit", new ListTag());
        state.putDouble("sx", caster.getX());
        state.putDouble("sy", caster.getY());
        state.putDouble("sz", caster.getZ());

        // Lock the roll line at the target's start position.
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
        caster.setYRot((float) Math.toDegrees(Math.atan2(-(dx / length), dz / length)));

        PandaAbilitySupport.addModifier(caster, Attributes.ATTACK_KNOCKBACK,
            new AttributeModifier(KNOCKBACK_ID, 2.0, AttributeModifier.Operation.ADD_VALUE));
        PandaAbilitySupport.addModifier(caster, Attributes.SAFE_FALL_DISTANCE,
            new AttributeModifier(FALL_ID, 30.0, AttributeModifier.Operation.ADD_VALUE));
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        CompoundTag state = ctx.state();
        int ticks = ctx.ticksActive();
        int phase = state.getIntOr("phase", 0);

        if (phase == 0) {
            // The tuck: rooted, curling up, dust at the feet.
            caster.getNavigation().stop();
            if (level != null && ticks % 2 == 0) {
                level.sendParticles(groundPillar(level, caster),
                    caster.getX(), caster.getY() + 0.15, caster.getZ(),
                    3, 0.45, 0.1, 0.45, 0.08);
            }
            if (ticks >= TUCK_TICKS) {
                state.putInt("phase", 1);
                state.putInt("roll_start", ticks);
                if (caster instanceof Panda panda) {
                    panda.roll(true);
                }
            }
        } else if (phase == 1) {
            rollTick(ctx, level, ticks);
        } else {
            // The dizzy spell: damped and still, leaves settling.
            caster.getNavigation().stop();
            if (level != null && ticks % 4 == 0) {
                level.sendParticles(PandaAbilitySupport.TINTED_LEAVES,
                    caster.getX(), caster.getY() + 2.2, caster.getZ(),
                    2, 0.6, 0.3, 0.6, 0.02);
            }
        }
    }

    /** One roll tick: drive, spray, bowling check, stop conditions. */
    private void rollTick(AbilityContext ctx, ServerLevel level, int ticks) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        if (level == null) {
            return;
        }
        if (caster instanceof Panda panda && !panda.isRolling()) {
            panda.roll(true);
        }
        double dx = state.getDoubleOr("dx", 0.0);
        double dz = state.getDoubleOr("dz", 1.0);

        // Stop conditions that end the roll with no more bowling.
        double travelledX = caster.getX() - state.getDoubleOr("sx", caster.getX());
        double travelledZ = caster.getZ() - state.getDoubleOr("sz", caster.getZ());
        int rollTicks = ticks - state.getIntOr("roll_start", 0);
        if (caster.horizontalCollision
            || travelledX * travelledX + travelledZ * travelledZ >= MAX_TRAVEL_SQR
            || caster.getY() < state.getDoubleOr("sy", caster.getY()) - FALL_STOP_DROP
            || rollTicks >= ROLL_MAX_TICKS) {
            enterDizzy(ctx, ticks);
            return;
        }

        // The drive: code velocity along the locked line,
        // speed gathering over the ramp.
        caster.getNavigation().stop();
        double ramp = Math.min(1.0, rollTicks / (double) ROLL_RAMP_TICKS);
        double speed = ROLL_START_SPEED + (ROLL_TOP_SPEED - ROLL_START_SPEED) * ramp;
        Vec3 velocity = caster.getDeltaMovement();
        caster.setDeltaMovement(dx * speed, velocity.y, dz * speed);
        caster.hurtMarked = true;

        // The trail: dust pillars and leaves off the tumbling body.
        level.sendParticles(groundPillar(level, caster),
            caster.getX(), caster.getY() + 0.3, caster.getZ(),
            3, 0.55, 0.3, 0.55, 0.1);
        level.sendParticles(PandaAbilitySupport.TINTED_LEAVES,
            caster.getX(), caster.getY() + 0.8, caster.getZ(),
            2, 0.5, 0.4, 0.5, 0.05);

        // The bowling: every fresh enemy in the plow box is bowled.
        List<LivingEntity> victims = PandaAbilitySupport.enemiesInBox(
            ctx, caster.getX(), caster.getY(), caster.getZ(), HIT_RADIUS, 1.0, 2.0);
        for (LivingEntity victim : victims) {
            if (alreadyHit(state, victim.getUUID())) {
                continue;
            }
            bowl(ctx, level, victim, dx, dz);
        }
    }

    /** Whether the given enemy has already been bowled this roll. */
    private static boolean alreadyHit(CompoundTag state, UUID id) {
        ListTag hit = state.getListOrEmpty("hit");
        for (int i = 0; i < hit.size(); i++) {
            CompoundTag entry = hit.getCompoundOrEmpty(i);
            UUID hitId = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id.equals(hitId)) {
                return true;
            }
        }
        return false;
    }

    /** Bowls one enemy: damage and the launch off the roll path. */
    private void bowl(AbilityContext ctx, ServerLevel level, LivingEntity victim, double dx, double dz) {
        Mob caster = ctx.mob();
        double damage = caster.getAttributeValue(Attributes.ATTACK_DAMAGE);
        victim.hurtServer(level, level.damageSources().mobAttack(caster), (float) damage);
        victim.setDeltaMovement(dx * LAUNCH_HORIZONTAL, LAUNCH_VERTICAL, dz * LAUNCH_HORIZONTAL);
        victim.hurtMarked = true;

        ListTag hit = ctx.state().getListOrEmpty("hit");
        CompoundTag entry = new CompoundTag();
        entry.store("id", UUIDUtil.CODEC, victim.getUUID());
        hit.add(entry);
        ctx.state().put("hit", hit);
    }

    /** Ends the roll: unroll, damp the velocity, start the dizzy clock. */
    private void enterDizzy(AbilityContext ctx, int ticks) {
        Mob caster = ctx.mob();
        ctx.state().putInt("phase", 2);
        ctx.state().putInt("phase_end", ticks + DIZZY_TICKS);
        if (caster instanceof Panda panda) {
            panda.roll(false);
        }
        caster.addEffect(new MobEffectInstance(MobEffects.NAUSEA, DIZZY_TICKS, 0));
        Vec3 velocity = caster.getDeltaMovement();
        caster.setDeltaMovement(velocity.x * 0.2, velocity.y, velocity.z * 0.2);
        caster.hurtMarked = true;
    }

    /** A DUST_PILLAR option carrying the block state under the panda (dirt when there is none). */
    private static BlockParticleOption groundPillar(ServerLevel level, Mob caster) {
        BlockState ground = level.getBlockState(BlockPos.containing(caster.getX(), caster.getY() - 0.5, caster.getZ()));
        BlockState state = ground.isAir() ? Blocks.DIRT.defaultBlockState() : ground;
        return new BlockParticleOption(ParticleTypes.DUST_PILLAR, state);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        if (ctx.ticksActive() >= HARD_CAP_TICKS) {
            return true;
        }
        CompoundTag state = ctx.state();
        return state.getIntOr("phase", 0) == 2
            && ctx.ticksActive() >= state.getIntOr("phase_end", 0);
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        PandaAbilitySupport.removeModifier(caster, Attributes.ATTACK_KNOCKBACK, KNOCKBACK_ID);
        PandaAbilitySupport.removeModifier(caster, Attributes.SAFE_FALL_DISTANCE, FALL_ID);
        if (caster instanceof Panda panda && panda.isRolling()) {
            panda.roll(false);
        }
    }
}
