package mod.advancedmobs.ability.goat;

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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Ram Catapult (Goat ability 1 of 5, attack).
 *
 * WHAT IT DOES: the goat paws the ground, dust kicking up,
 * then charges horn first at terrifying speed. The headbutt
 * carries the biggest knockback in the mod: the victim is
 * launched in a high arc and takes doubled fall damage
 * wherever it comes down, while the goat skids to a stop,
 * shaking its head.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the goat on the ground and its current target within 14
 *   blocks (distance squared 196).
 * - Direction (start()): the charge line is locked at start:
 *   the horizontal direction from the goat to its target,
 *   stored in the state tag ("dx", "dz", normalized; the
 *   goat's look direction if the target is exactly
 *   overhead), and the start position ("sx", "sy", "sz").
 *   The goat does NOT steer during the charge.
 * - Charge attribute (start(), removed in end()): one
 *   transient modifier for the whole activation:
 *   ATTACK_KNOCKBACK add_value +3.0 (id
 *   advancedmobs:ram_catapult_knockback; the design's
 *   ATTACK_KNOCKBACK line, so any vanilla melee the goat
 *   lands mid-activation also hits at the boosted value).
 *   The headbutt's own launch is code velocity, below, and
 *   does not depend on the attribute.
 * - Phase 0, the pawing (ticks 1 to 10): navigation is
 *   stopped every tick (never NoAi) and 3 DUST_PILLAR of
 *   the ground block kick up at the goat's front every 2
 *   ticks. GOAT_PREPARE_RAM plays at start (the screaming
 *   variant's prepare sound when the goat is a screaming
 *   goat, checked once in start()).
 * - Phase 1, the charge (from tick 11, at most 30 ticks):
 *   navigation stopped, horizontal velocity written along
 *   the locked direction at 1.05 blocks per tick (21 blocks
 *   per second, terrifying for a goat), vertical left to
 *   gravity so the charge follows the ground. 3 DUST_PILLAR
 *   of the ground block spray at the hooves every tick.
 * - The headbutt (the first enemy found by the per-tick
 *   radius-2.0 hit check, 1.0 below to 2.0 above): damage
 *   equal to the goat's live ATTACK_DAMAGE attribute value
 *   (2.0 for an unupgraded goat) as a mob attack credited
 *   to the goat, plus the launch: 2.4 horizontal along the
 *   charge direction and 0.95 up, the biggest knockback in
 *   the mod, hurtMarked set. The victim also gains a
 *   transient FALL_DAMAGE_MULTIPLIER add_multiplied_total
 *   +1.0 (fall damage doubled, id
 *   advancedmobs:ram_catapult_fall), is recorded in the
 *   state tag's "victims" list, and the modifier is pruned
 *   the first poll after the victim lands (every 5 ticks)
 *   or stripped from everyone left in end(). GOAT_RAM_IMPACT
 *   (screaming variant likewise) plays and a burst of 12
 *   BLOCK plus 8 DUST_PILLAR of the ground block fountains
 *   at the impact. Only ONE victim: the headbutt ends the
 *   charge.
 * - Charge stop conditions (first one wins): the headbutt
 *   connects; horizontalCollision (a wall); 20 blocks
 *   travelled from the start; the goat falls 5 or more
 *   blocks below the start height; 30 charge ticks run out.
 * - Phase 2, the skid (30 ticks from the stop): horizontal
 *   velocity is damped to a fifth once at phase entry,
 *   navigation stays stopped, and 2 DUST puff at the head
 *   every 2 ticks (the head shake). The ability finishes
 *   when the skid is over AND the victims list has emptied
 *   (every launched victim has landed and been pruned), or
 *   at the hard cap of tick 120.
 * - end(): the ATTACK_KNOCKBACK modifier is removed and
 *   any fall multipliers still out are stripped (a victim
 *   still airborne at the hard cap loses the doubled fall
 *   damage: an accepted edge, the Seismic Stomp linger
 *   precedent).
 *
 * WHAT IT LOOKS LIKE: a goat scraping the dirt with one
 * hoof, dust pillars rising, the vanilla ram wind-up call,
 * then a horn-first blur trailing dust, a crack of impact,
 * the victim sailing in a high arc, and the goat skidding
 * to a halt shaking its head in a little dust cloud.
 *
 * PERFORMANCE: the pawing and skid phases do one
 * navigation stop and at most one particle emission every
 * 2 ticks. The charge does one velocity write, at most one
 * ground block read, and one bounded radius-2.0 entity
 * query per tick. The victim prune is one list pass every
 * 5 ticks. Particle budget per activation: pawing 5 times
 * 3 = 15, charge at most 30 times 3 = 90, impact burst 20,
 * skid 15 times 2 = 30, total at most 155 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply. DUST_PILLAR and BLOCK are particles of the
 * ground block's state, not placed blocks.
 *
 * State tag keys: "phase" (0 pawing, 1 charging, 2 skid),
 * "phase_end" (tick the skid ends), "dx", "dz" (locked
 * charge direction), "sx", "sy", "sz" (start position),
 * "charge_start" (tick the charge began), "victims" (list
 * of compounds, one per launched victim: "id", its UUID).
 */
public final class RamCatapultAbility implements MobAbility {

    private static final int PAW_TICKS = 10;
    private static final int CHARGE_MAX_TICKS = 30;
    private static final int SKID_TICKS = 30;
    private static final int HARD_CAP_TICKS = 120;
    private static final double CHARGE_SPEED = 1.05;
    private static final double MAX_TRAVEL_SQR = 400.0; // 20 blocks
    private static final double FALL_STOP_DROP = 5.0;
    private static final double HIT_RADIUS = 2.0;
    private static final double LAUNCH_HORIZONTAL = 2.4;
    private static final double LAUNCH_VERTICAL = 0.95;
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "ram_catapult_knockback");
    private static final Identifier FALL_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "ram_catapult_fall");

    @Override
    public String id() {
        return "ram_catapult";
    }

    @Override
    public String displayName() {
        return "Ram Catapult";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().onGround()
            && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        state.putInt("phase", 0);
        state.putInt("phase_end", 0);
        state.putInt("charge_start", 0);
        state.put("victims", new ListTag());
        state.putDouble("sx", caster.getX());
        state.putDouble("sy", caster.getY());
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

        GoatAbilitySupport.addModifier(caster, Attributes.ATTACK_KNOCKBACK,
            new AttributeModifier(KNOCKBACK_ID, 3.0, AttributeModifier.Operation.ADD_VALUE));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            boolean screaming = caster instanceof Goat goat && goat.isScreamingGoat();
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                screaming ? SoundEvents.GOAT_SCREAMING_PREPARE_RAM : SoundEvents.GOAT_PREPARE_RAM,
                SoundSource.HOSTILE, 1.2f, 1.0f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        CompoundTag state = ctx.state();
        int ticks = ctx.ticksActive();
        int phase = state.getIntOr("phase", 0);

        if (phase == 0) {
            // The pawing: rooted, dust kicking up at the front.
            caster.getNavigation().stop();
            if (level != null && ticks % 2 == 0) {
                level.sendParticles(groundPillar(level, caster),
                    caster.getX(), caster.getY() + 0.15, caster.getZ(),
                    3, 0.45, 0.1, 0.45, 0.08);
            }
            if (ticks >= PAW_TICKS) {
                state.putInt("phase", 1);
                state.putInt("charge_start", ticks);
            }
        } else if (phase == 1) {
            chargeTick(ctx, level, ticks);
        } else {
            // The skid: damped and still, shaking its head.
            caster.getNavigation().stop();
            if (level != null && ticks % 2 == 0) {
                level.sendParticles(groundPillar(level, caster),
                    caster.getX(), caster.getY() + 0.9, caster.getZ(),
                    2, 0.3, 0.2, 0.3, 0.05);
            }
        }

        if (ticks % 5 == 0) {
            pruneLandedVictims(ctx);
        }
    }

    /** One charge tick: drive, spray, hit check, stop conditions. */
    private void chargeTick(AbilityContext ctx, ServerLevel level, int ticks) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        if (level == null) {
            return;
        }
        double dx = state.getDoubleOr("dx", 0.0);
        double dz = state.getDoubleOr("dz", 1.0);

        // Stop conditions that end the charge with no headbutt.
        double travelledX = caster.getX() - state.getDoubleOr("sx", caster.getX());
        double travelledZ = caster.getZ() - state.getDoubleOr("sz", caster.getZ());
        if (caster.horizontalCollision
            || travelledX * travelledX + travelledZ * travelledZ >= MAX_TRAVEL_SQR
            || caster.getY() < state.getDoubleOr("sy", caster.getY()) - FALL_STOP_DROP
            || ticks - state.getIntOr("charge_start", 0) >= CHARGE_MAX_TICKS) {
            enterSkid(ctx, ticks);
            return;
        }

        // The drive: code velocity along the locked line.
        caster.getNavigation().stop();
        Vec3 velocity = caster.getDeltaMovement();
        caster.setDeltaMovement(dx * CHARGE_SPEED, velocity.y, dz * CHARGE_SPEED);
        caster.hurtMarked = true;

        // Hoof spray with every stride.
        level.sendParticles(groundPillar(level, caster),
            caster.getX(), caster.getY() + 0.2, caster.getZ(),
            3, 0.5, 0.12, 0.5, 0.1);

        // The headbutt: the first enemy in the plow box takes it.
        List<LivingEntity> victims = GoatAbilitySupport.enemiesInBox(
            ctx, caster.getX(), caster.getY(), caster.getZ(), HIT_RADIUS, 1.0, 2.0);
        if (!victims.isEmpty()) {
            headbutt(ctx, level, victims.get(0), dx, dz);
            enterSkid(ctx, ticks);
        }
    }

    /** The headbutt itself: damage, the launch, and the fall damage curse. */
    private void headbutt(AbilityContext ctx, ServerLevel level, LivingEntity victim, double dx, double dz) {
        Mob caster = ctx.mob();
        double damage = caster.getAttributeValue(Attributes.ATTACK_DAMAGE);
        victim.hurtServer(level, level.damageSources().mobAttack(caster), (float) damage);
        GoatAbilitySupport.addModifier(victim, Attributes.FALL_DAMAGE_MULTIPLIER,
            new AttributeModifier(FALL_ID, 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        victim.setDeltaMovement(dx * LAUNCH_HORIZONTAL, LAUNCH_VERTICAL, dz * LAUNCH_HORIZONTAL);
        victim.hurtMarked = true;

        ListTag victimList = ctx.state().getListOrEmpty("victims");
        CompoundTag entry = new CompoundTag();
        entry.store("id", UUIDUtil.CODEC, victim.getUUID());
        victimList.add(entry);
        ctx.state().put("victims", victimList);

        boolean screaming = caster instanceof Goat goat && goat.isScreamingGoat();
        level.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
            screaming ? SoundEvents.GOAT_SCREAMING_RAM_IMPACT : SoundEvents.GOAT_RAM_IMPACT,
            SoundSource.HOSTILE, 1.5f, 1.0f);
        BlockParticleOption ground = groundPillar(level, caster);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground.getState()),
            victim.getX(), victim.getY() + 0.8, victim.getZ(),
            12, 0.4, 0.5, 0.4, 0.2);
        level.sendParticles(ground,
            victim.getX(), victim.getY() + 0.3, victim.getZ(),
            8, 0.5, 0.3, 0.5, 0.12);
    }

    /** Ends the charge: damp the velocity and start the skid clock. */
    private void enterSkid(AbilityContext ctx, int ticks) {
        Mob caster = ctx.mob();
        ctx.state().putInt("phase", 2);
        ctx.state().putInt("phase_end", ticks + SKID_TICKS);
        Vec3 velocity = caster.getDeltaMovement();
        caster.setDeltaMovement(velocity.x * 0.2, velocity.y, velocity.z * 0.2);
        caster.hurtMarked = true;
    }

    /** Removes the fall multiplier from every recorded victim that has landed, and prunes the list. */
    private void pruneLandedVictims(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag victims = ctx.state().getListOrEmpty("victims");
        if (victims.isEmpty()) {
            return;
        }
        ListTag remaining = new ListTag();
        for (int i = 0; i < victims.size(); i++) {
            CompoundTag entry = victims.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id == null || !(level.getEntity(id) instanceof LivingEntity victim) || !victim.isAlive()) {
                continue; // Gone: the transient modifier went with it.
            }
            if (victim.onGround()) {
                GoatAbilitySupport.removeModifier(victim, Attributes.FALL_DAMAGE_MULTIPLIER, FALL_ID);
                continue;
            }
            remaining.add(entry);
        }
        ctx.state().put("victims", remaining);
    }

    /** A DUST_PILLAR option carrying the block state under the goat (dirt when there is none). */
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
            && ctx.ticksActive() >= state.getIntOr("phase_end", 0)
            && state.getListOrEmpty("victims").isEmpty();
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        GoatAbilitySupport.removeModifier(caster, Attributes.ATTACK_KNOCKBACK, KNOCKBACK_ID);
        // Remove any fall multipliers still out: victims that
        // never landed inside the skid window.
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag victims = ctx.state().getListOrEmpty("victims");
        for (int i = 0; i < victims.size(); i++) {
            CompoundTag entry = victims.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id != null && level.getEntity(id) instanceof LivingEntity victim) {
                GoatAbilitySupport.removeModifier(victim, Attributes.FALL_DAMAGE_MULTIPLIER, FALL_ID);
            }
        }
    }
}
