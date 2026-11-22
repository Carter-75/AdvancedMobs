package mod.advancedmobs.ability.polarbear;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Avalanche Charge (Polar Bear ability 1 of 5, attack /
 * mobility).
 *
 * WHAT IT DOES: the bear drops to all fours with a warning
 * roar and charges in a straight line, snow spraying from
 * its paws with every stride. Anything it plows into is
 * smashed aside with huge knockback. The charge only stops
 * at a wall, after it has bowled through 4 enemies, or after
 * 24 blocks of ground.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the bear on the ground and its current target within 16
 *   blocks (distance squared 256).
 * - Direction (start()): the charge line is locked at start:
 *   the horizontal direction from the bear to its target,
 *   stored in the state tag ("dx", "dz", normalized; the
 *   bear's look direction if the target is exactly overhead).
 *   The start position is stored too ("sx", "sz"). The bear
 *   does NOT steer during the charge: it is a battering ram,
 *   not a homing missile.
 * - Charge attributes (start(), removed in end()): two
 *   transient modifiers under fixed ids for the whole
 *   activation: ATTACK_KNOCKBACK add_value +2.0 (modifier id
 *   advancedmobs:avalanche_charge_knockback; any vanilla
 *   melee the bear lands mid-charge also hits harder) and
 *   MOVEMENT_SPEED add_multiplied_base +0.5 (modifier id
 *   advancedmobs:avalanche_charge_speed; the design's speed
 *   line, carried on the bear so its stride matches the
 *   charge the moment the code drive lets go). The charge
 *   movement itself is code velocity, below.
 * - The drive (every tick): navigation is stopped (never
 *   NoAi) and the horizontal velocity is written along the
 *   locked direction at 0.85 blocks per tick (about 17
 *   blocks per second); the vertical velocity is left to
 *   gravity, so the charge follows the ground and small
 *   drops. hurtMarked is set so the velocity syncs.
 * - Hits (every tick, one bounded query, radius 2.0 around
 *   the bear, 1.5 below to 2.0 above): each enemy
 *   (PolarBearAbilitySupport rules) not already hit this
 *   charge (tracked in the state tag's "hit" compound, UUID
 *   string keys) takes damage equal to the bear's live
 *   ATTACK_DAMAGE attribute value (6.0 for an unupgraded
 *   bear) as a mob attack credited to the bear, plus a
 *   code knockback: 1.6 horizontal away from the bear and
 *   0.55 up, hurtMarked set. Each hit increments "hits".
 * - Stop conditions (checked each tick, first one wins):
 *   "hits" reaches 4 (the design's "plowed through several
 *   enemies"); horizontal distance travelled from the start
 *   reaches 24 blocks; a wall: the block column just ahead
 *   of the bear (bear position plus direction times half
 *   width plus 0.55) at feet level or one above is a wall
 *   block (PolarBearAbilitySupport.isWallBlock: non-empty
 *   collision shape). On a wall stop the bear slams to a
 *   halt with a burst of 10 BLOCK particles of the wall
 *   block and 8 SNOWFLAKE at the impact point. Hard cap:
 *   the ability finishes at tick 40 no matter what.
 * - end(): the two modifiers are removed and the horizontal
 *   velocity is damped to a fifth, so the bear does not
 *   keep sailing after the charge is over.
 *
 * WHAT IT LOOKS LIKE: POLAR_BEAR_WARNING at volume 1.5,
 * pitch 0.9 at the start, then every tick 4 SNOWFLAKE and
 * 2 ITEM snowball particles spray at the bear's paws (the
 * design's roaring wave of snow), and a snow-and-block
 * burst where it meets a wall.
 *
 * PERFORMANCE: per tick one navigation stop, one velocity
 * write, at most 2 block reads for the wall check, and one
 * bounded entity query (the hit check; the crowd it can
 * touch is capped by the 4-hit stop). Particle budget per
 * activation: at most 40 ticks times 6 spray = 240, plus at
 * most 18 wall burst = at most 258 particles.
 *
 * TERRAIN: the charge changes no blocks (a wall stops it
 * rather than breaking), so the mobGriefing gate does not
 * apply.
 *
 * State tag keys: "dx", "dz" (locked charge direction),
 * "sx", "sz" (start position), "hits" (enemies hit so far),
 * "hit" (compound: UUID string to 1, enemies already hit),
 * "stopped" (1 once any stop condition fired).
 */
public final class AvalancheChargeAbility implements MobAbility {

    private static final int HARD_CAP_TICKS = 40;
    private static final int MAX_HITS = 4;
    private static final double CHARGE_SPEED = 0.85;
    private static final double MAX_TRAVEL = 24.0;
    private static final double HIT_RADIUS = 2.0;
    private static final ItemParticleOption SNOWBALL_SPRAY =
        new ItemParticleOption(ParticleTypes.ITEM, Items.SNOWBALL);
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "avalanche_charge_knockback");
    private static final Identifier SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "avalanche_charge_speed");

    @Override
    public String id() {
        return "avalanche_charge";
    }

    @Override
    public String displayName() {
        return "Avalanche Charge";
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
        state.putInt("hits", 0);
        state.putInt("stopped", 0);
        state.put("hit", new CompoundTag());
        state.putDouble("sx", caster.getX());
        state.putDouble("sz", caster.getZ());

        // Lock the charge line at the target's start position.
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

        PolarBearAbilitySupport.addModifier(caster, Attributes.ATTACK_KNOCKBACK,
            new AttributeModifier(KNOCKBACK_ID, 2.0, AttributeModifier.Operation.ADD_VALUE));
        PolarBearAbilitySupport.addModifier(caster, Attributes.MOVEMENT_SPEED,
            new AttributeModifier(SPEED_ID, 0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.POLAR_BEAR_WARNING, SoundSource.HOSTILE, 1.5f, 0.9f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        CompoundTag state = ctx.state();
        if (level == null || state.getIntOr("stopped", 0) != 0) {
            return;
        }
        double dx = state.getDoubleOr("dx", 0.0);
        double dz = state.getDoubleOr("dz", 1.0);

        // Stop condition: travelled the full 24 blocks.
        double travelledX = caster.getX() - state.getDoubleOr("sx", caster.getX());
        double travelledZ = caster.getZ() - state.getDoubleOr("sz", caster.getZ());
        if (travelledX * travelledX + travelledZ * travelledZ >= MAX_TRAVEL * MAX_TRAVEL) {
            state.putInt("stopped", 1);
            return;
        }

        // Stop condition: a wall in the column just ahead.
        double ahead = caster.getBbWidth() / 2.0 + 0.55;
        BlockPos frontFeet = BlockPos.containing(
            caster.getX() + dx * ahead, caster.getY() + 0.1, caster.getZ() + dz * ahead);
        if (PolarBearAbilitySupport.isWallBlock(level, frontFeet)
            || PolarBearAbilitySupport.isWallBlock(level, frontFeet.above())) {
            state.putInt("stopped", 1);
            caster.setDeltaMovement(0.0, caster.getDeltaMovement().y, 0.0);
            caster.hurtMarked = true;
            level.sendParticles(
                new BlockParticleOption(ParticleTypes.BLOCK, level.getBlockState(frontFeet)),
                caster.getX() + dx * ahead, caster.getY() + 0.8, caster.getZ() + dz * ahead,
                10, 0.3, 0.5, 0.3, 0.15);
            level.sendParticles(ParticleTypes.SNOWFLAKE,
                caster.getX() + dx * ahead, caster.getY() + 0.8, caster.getZ() + dz * ahead,
                8, 0.4, 0.5, 0.4, 0.05);
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.POLAR_BEAR_STEP, SoundSource.HOSTILE, 1.5f, 0.6f);
            return;
        }

        // The drive: code velocity along the locked line.
        caster.getNavigation().stop();
        Vec3 velocity = caster.getDeltaMovement();
        caster.setDeltaMovement(dx * CHARGE_SPEED, velocity.y, dz * CHARGE_SPEED);
        caster.hurtMarked = true;

        // Snow spray at the paws with every stride.
        level.sendParticles(ParticleTypes.SNOWFLAKE, caster.getX(), caster.getY() + 0.25, caster.getZ(),
            4, 0.6, 0.15, 0.6, 0.06);
        level.sendParticles(SNOWBALL_SPRAY, caster.getX(), caster.getY() + 0.25, caster.getZ(),
            2, 0.6, 0.15, 0.6, 0.12);

        // Hits: everything in the path is smashed aside, once each.
        CompoundTag hit = state.getCompoundOrEmpty("hit");
        int hits = state.getIntOr("hits", 0);
        List<LivingEntity> victims = PolarBearAbilitySupport.enemiesInBox(
            ctx, caster.getX(), caster.getY(), caster.getZ(), HIT_RADIUS, 1.5, 2.0);
        for (LivingEntity victim : victims) {
            if (hits >= MAX_HITS) {
                break;
            }
            String key = victim.getUUID().toString();
            if (hit.contains(key)) {
                continue;
            }
            hit.putInt(key, 1);
            hits++;
            double damage = caster.getAttributeValue(Attributes.ATTACK_DAMAGE);
            victim.hurtServer(level, level.damageSources().mobAttack(caster), (float) damage);
            double kx = victim.getX() - caster.getX();
            double kz = victim.getZ() - caster.getZ();
            double kLength = Math.sqrt(kx * kx + kz * kz);
            if (kLength < 0.001) {
                kx = dx;
                kz = dz;
                kLength = 1.0;
            }
            victim.setDeltaMovement(kx / kLength * 1.6, 0.55, kz / kLength * 1.6);
            victim.hurtMarked = true;
        }
        state.put("hit", hit);
        state.putInt("hits", hits);
        if (hits >= MAX_HITS) {
            state.putInt("stopped", 1);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.state().getIntOr("stopped", 0) != 0
            || ctx.ticksActive() >= HARD_CAP_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        PolarBearAbilitySupport.removeModifier(caster, Attributes.ATTACK_KNOCKBACK, KNOCKBACK_ID);
        PolarBearAbilitySupport.removeModifier(caster, Attributes.MOVEMENT_SPEED, SPEED_ID);
        Vec3 velocity = caster.getDeltaMovement();
        caster.setDeltaMovement(velocity.x * 0.2, velocity.y, velocity.z * 0.2);
        caster.hurtMarked = true;
    }
}
