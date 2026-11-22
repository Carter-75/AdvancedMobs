package mod.advancedmobs.ability.horse;

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
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Warhorse Charge (Horse ability 1 of 5, attack / mobility).
 *
 * WHAT IT DOES: the horse paws the ground, dust kicking up,
 * snorts, and then launches into a full gallop charge down a
 * locked line, trailing dust the whole way. Unlike the goat's
 * single headbutt, the charge does not stop at the first
 * victim: the horse tramples straight through every enemy in
 * its path, each one taking heavy damage and a hard launch
 * off the charge line, and its raised step height lets it
 * thunder up and over blocks that would stop a normal charge.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the horse on the ground and its current target within 16
 *   blocks (distance squared 256).
 * - Direction (start()): the charge line is locked at start:
 *   the horizontal direction from the horse to its target,
 *   stored in the state tag ("dx", "dz", normalized; the
 *   horse's look direction if the target is exactly
 *   overhead), and the start position ("sx", "sy", "sz").
 *   The horse does NOT steer during the charge.
 * - Charge kit (start(), removed exactly in end()): three
 *   transient modifiers for the whole activation:
 *   MOVEMENT_SPEED +0.5 ADD_MULTIPLIED_BASE (the design's
 *   movement_speed line; the gallop itself is code velocity,
 *   below, and the modifier governs any vanilla movement the
 *   horse makes while the activation runs), STEP_HEIGHT
 *   +1.0 ADD_VALUE (the vanilla horse's 1.0 becomes 2.0, so
 *   the charge steps up two-block rises instead of stopping
 *   at them: the design's step_height line), and
 *   ATTACK_KNOCKBACK +3.0 ADD_VALUE (the design's
 *   attack_knockback line, so any vanilla melee the horse
 *   lands mid-activation hits at the boosted value; the
 *   trample's own launch is code velocity and does not
 *   depend on it).
 * - Phase 0, the pawing (ticks 1 to 10): navigation is
 *   stopped every tick (never NoAi), 3 tan DUST kick up at
 *   the front hooves every 2 ticks, and HORSE_ANGRY plays
 *   at start (volume 1.2, pitch 0.9: the pawing snort).
 * - Phase 1, the charge (from tick 11, at most 30 ticks):
 *   navigation stopped, horizontal velocity written along
 *   the locked direction at 1.0 blocks per tick (20 blocks
 *   per second), vertical left to gravity so the charge
 *   follows the ground. HORSE_GALLOP plays as the charge
 *   begins (volume 1.5). 4 tan DUST trail at the hooves
 *   every tick.
 * - The trample (per-tick hit check, radius 2.2 box, 1.0
 *   below to 2.0 above): every enemy not already in the
 *   state tag's "hit" list takes 8.0 damage as a mob attack
 *   credited to the horse (a flat code value: a horse has
 *   no ATTACK_DAMAGE attribute instance in 26.2, verified
 *   against the jar; see the provider comment) plus a
 *   launch of 1.6 horizontal along the charge direction and
 *   0.55 up, hurtMarked set, and is recorded in the hit
 *   list so nobody is trampled twice by one charge. The
 *   charge does NOT stop: it plows on through the whole
 *   crowd. The hit list is capped at 16 victims per charge.
 *   Each trample bursts 8 tan DUST at the victim.
 * - Charge stop conditions (first one wins):
 *   horizontalCollision (a wall the raised step height
 *   cannot climb); 24 blocks travelled from the start; the
 *   horse falls 5 or more blocks below the start height;
 *   30 charge ticks run out.
 * - Phase 2, the skid (20 ticks from the stop): horizontal
 *   velocity is damped to a fifth once at phase entry,
 *   navigation stays stopped, and 2 tan DUST puff at the
 *   hooves every 2 ticks. The ability finishes when the
 *   skid is over, or at the hard cap of tick 90.
 * - end(): the three modifiers are removed. Nothing else
 *   needs cleanup: the trample leaves no marks on victims
 *   beyond the damage and the launch.
 *
 * WHAT IT LOOKS LIKE: a warhorse scraping the dirt and
 * snorting, then a full-speed gallop with a long tan dust
 * trail, enemies bowling off the line one after another as
 * it thunders through and up over the terrain, and a
 * skidding halt in a last puff of dust.
 *
 * PERFORMANCE: the pawing and skid phases do one navigation
 * stop and at most one particle emission every 2 ticks. The
 * charge does one velocity write and one bounded radius-2.2
 * entity query per tick, with hit-list membership checked
 * over at most 16 UUIDs. Particle budget per activation:
 * pawing 5 times 3 = 15, charge trail at most 30 times 4 =
 * 120, tramples at most 16 times 8 = 128, skid 10 times 2 =
 * 20, total at most 283 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply. DUST is a colored particle, not a placed block.
 *
 * State tag keys: "phase" (0 pawing, 1 charging, 2 skid),
 * "phase_end" (tick the skid ends), "charge_start" (tick
 * the charge began), "dx", "dz" (locked charge direction),
 * "sx", "sy", "sz" (start position), "hit" (list of
 * compounds, one per trampled enemy: "id", its UUID).
 */
public final class WarhorseChargeAbility implements MobAbility {

    private static final int PAW_TICKS = 10;
    private static final int CHARGE_MAX_TICKS = 30;
    private static final int SKID_TICKS = 20;
    private static final int HARD_CAP_TICKS = 90;
    private static final double CHARGE_SPEED = 1.0;
    private static final double MAX_TRAVEL_SQR = 576.0; // 24 blocks
    private static final double FALL_STOP_DROP = 5.0;
    private static final double HIT_RADIUS = 2.2;
    private static final double TRAMPLE_DAMAGE = 8.0;
    private static final double LAUNCH_HORIZONTAL = 1.6;
    private static final double LAUNCH_VERTICAL = 0.55;
    private static final int MAX_VICTIMS = 16;
    private static final Identifier SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "warhorse_charge_speed");
    private static final Identifier STEP_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "warhorse_charge_step");
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "warhorse_charge_knockback");

    @Override
    public String id() {
        return "warhorse_charge";
    }

    @Override
    public String displayName() {
        return "Warhorse Charge";
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
        state.putInt("charge_start", 0);
        state.put("hit", new ListTag());
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

        // The charge kit.
        HorseAbilitySupport.addModifier(caster, Attributes.MOVEMENT_SPEED,
            new AttributeModifier(SPEED_ID, 0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        HorseAbilitySupport.addModifier(caster, Attributes.STEP_HEIGHT,
            new AttributeModifier(STEP_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        HorseAbilitySupport.addModifier(caster, Attributes.ATTACK_KNOCKBACK,
            new AttributeModifier(KNOCKBACK_ID, 3.0, AttributeModifier.Operation.ADD_VALUE));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.HORSE_ANGRY, SoundSource.HOSTILE, 1.2f, 0.9f);
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
                level.sendParticles(HorseAbilitySupport.TAN_DUST,
                    caster.getX(), caster.getY() + 0.15, caster.getZ(),
                    3, 0.45, 0.1, 0.45, 0.08);
            }
            if (ticks >= PAW_TICKS) {
                state.putInt("phase", 1);
                state.putInt("charge_start", ticks);
                if (level != null) {
                    level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                        SoundEvents.HORSE_GALLOP, SoundSource.HOSTILE, 1.5f, 1.0f);
                }
            }
        } else if (phase == 1) {
            chargeTick(ctx, level, ticks);
        } else {
            // The skid: damped and still, dust settling.
            caster.getNavigation().stop();
            if (level != null && ticks % 2 == 0) {
                level.sendParticles(HorseAbilitySupport.TAN_DUST,
                    caster.getX(), caster.getY() + 0.15, caster.getZ(),
                    2, 0.4, 0.1, 0.4, 0.05);
            }
        }
    }

    /** One charge tick: stop conditions, drive, dust trail, and the trample check. */
    private void chargeTick(AbilityContext ctx, ServerLevel level, int ticks) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        if (level == null) {
            return;
        }
        double dx = state.getDoubleOr("dx", 0.0);
        double dz = state.getDoubleOr("dz", 1.0);

        // Stop conditions that end the charge.
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

        // The long dust trail at the hooves.
        level.sendParticles(HorseAbilitySupport.TAN_DUST,
            caster.getX(), caster.getY() + 0.2, caster.getZ(),
            4, 0.55, 0.12, 0.55, 0.1);

        // The trample: every fresh enemy in the plow box is
        // bowled off the line, and the charge rolls on.
        List<LivingEntity> victims = HorseAbilitySupport.enemiesInBox(
            ctx, caster.getX(), caster.getY(), caster.getZ(), HIT_RADIUS, 1.0, 2.0);
        ListTag hit = state.getListOrEmpty("hit");
        for (LivingEntity victim : victims) {
            if (hit.size() >= MAX_VICTIMS || alreadyHit(hit, victim.getUUID())) {
                continue;
            }
            victim.hurtServer(level, level.damageSources().mobAttack(caster), (float) TRAMPLE_DAMAGE);
            victim.setDeltaMovement(dx * LAUNCH_HORIZONTAL, LAUNCH_VERTICAL, dz * LAUNCH_HORIZONTAL);
            victim.hurtMarked = true;
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, victim.getUUID());
            hit.add(entry);
            level.sendParticles(HorseAbilitySupport.TAN_DUST,
                victim.getX(), victim.getY() + 0.6, victim.getZ(),
                8, 0.45, 0.4, 0.45, 0.15);
        }
        state.put("hit", hit);
    }

    /** Whether the UUID is already in the hit list. */
    private static boolean alreadyHit(ListTag hit, UUID id) {
        for (int i = 0; i < hit.size(); i++) {
            UUID recorded = hit.getCompoundOrEmpty(i).read("id", UUIDUtil.CODEC).orElse(null);
            if (id.equals(recorded)) {
                return true;
            }
        }
        return false;
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
        HorseAbilitySupport.removeModifier(caster, Attributes.MOVEMENT_SPEED, SPEED_ID);
        HorseAbilitySupport.removeModifier(caster, Attributes.STEP_HEIGHT, STEP_ID);
        HorseAbilitySupport.removeModifier(caster, Attributes.ATTACK_KNOCKBACK, KNOCKBACK_ID);
    }
}
