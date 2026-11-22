package mod.advancedmobs.ability.pig;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Mudslide Charge (Pig ability 1 of 5, mobility / attack).
 *
 * WHAT IT DOES: the pig drops onto its belly and the ground
 * friction under it simply vanishes. It hurtles in a
 * straight line shedding brown dust and chunks of dirt,
 * slams into the first enemy in its path with heavy
 * knockback, and skids to a stop in a spray of mud.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the pig on the ground and its current target within 14
 *   blocks (distance squared 196).
 * - Base numbers: a plain pig has 10 health and 0.25
 *   movement speed and NO ATTACK_DAMAGE attribute instance
 *   (verified against the jar: Pig.createAttributes adds
 *   only MAX_HEALTH and MOVEMENT_SPEED), so every damage
 *   number in this package is a flat code value dealt with
 *   a mob attack source credited to the pig.
 * - Direction (start()): the slide line is locked at start:
 *   the horizontal direction from the pig to its target,
 *   stored in the state tag ("dx", "dz", normalized; the
 *   pig's look direction if the target is exactly
 *   overhead), and the start position ("sx", "sy", "sz").
 *   The pig does NOT steer during the slide.
 * - Slide attributes (start(), all removed in end()):
 *   FRICTION_MODIFIER add_value -1.0 (id
 *   advancedmobs:mudslide_friction; friction multiplied to
 *   exactly 0, the design's friction_modifier line, so the
 *   pig keeps every bit of its slide speed on any ground),
 *   MOVEMENT_SPEED add_multiplied_base +0.5 (id
 *   advancedmobs:mudslide_speed), and ATTACK_KNOCKBACK
 *   add_value +2.0 (id advancedmobs:mudslide_knockback;
 *   the design's attack_knockback line, so any vanilla
 *   contact the pig makes mid-slide also hits harder).
 *   The slide itself is code velocity, below, and does not
 *   depend on these attributes.
 * - Phase 0, the crouch (ticks 1 to 6): navigation stopped
 *   every tick (never NoAi), 3 brown DUST motes puff at the
 *   belly every 2 ticks as it settles in. The pig's classic
 *   ambient sound plays at start at volume 1.5, pitch 0.9.
 * - Phase 1, the slide (from tick 7, at most 30 ticks):
 *   navigation stopped, horizontal velocity written along
 *   the locked direction at 0.85 blocks per tick (17 blocks
 *   per second), vertical left to gravity so the slide
 *   follows the ground. Every tick: 2 brown DUST plus 2
 *   dirt BLOCK particles spray at the belly.
 * - The slam (the first enemy found by the per-tick
 *   radius-2.0 hit check, 1.0 below to 2.0 above): 6.0
 *   damage as a mob attack credited to the pig, plus the
 *   launch: 1.8 horizontal along the slide direction and
 *   0.5 up, hurtMarked set. A burst of 10 dirt BLOCK plus
 *   6 brown DUST fountains at the impact. Only ONE victim:
 *   the slam ends the slide.
 * - Slide stop conditions (first one wins): the slam
 *   connects; horizontalCollision (a wall); 20 blocks
 *   travelled from the start; the pig falls 5 or more
 *   blocks below the start height; 30 slide ticks run out.
 * - Phase 2, the skid (20 ticks from the stop): horizontal
 *   velocity damped to a fifth once at phase entry,
 *   navigation stays stopped, 2 dirt BLOCK particles spray
 *   every 2 ticks as it grinds to a halt. The ability
 *   finishes when the skid ends, or at the hard cap of
 *   tick 80.
 * - end(): all three transient modifiers are removed.
 *
 * WHAT IT LOOKS LIKE: a pig settling onto its belly in a
 * puff of brown dust, then a mud-slick blur trailing dirt
 * chunks, a wet crack of impact, the victim sailing off,
 * and the pig skidding sideways to a stop in a spray.
 *
 * PERFORMANCE: the crouch and skid do one navigation stop
 * and at most one particle emission every 2 ticks. The
 * slide does one velocity write and one bounded radius-2.0
 * entity query per tick. Particle budget per activation:
 * crouch 3 times 3 = 9, slide at most 30 times 4 = 120,
 * slam burst 16, skid 10 times 2 = 20, total at most 165
 * particles. The brown dust option is a pre-built static
 * final.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply. DUST and BLOCK are particles, not placed
 * blocks.
 *
 * State tag keys: "phase" (0 crouch, 1 sliding, 2 skid),
 * "phase_end" (tick the skid ends), "dx", "dz" (locked
 * slide direction), "sx", "sy", "sz" (start position),
 * "slide_start" (tick the slide began).
 */
public final class MudslideChargeAbility implements MobAbility {

    private static final int CROUCH_TICKS = 6;
    private static final int SLIDE_MAX_TICKS = 30;
    private static final int SKID_TICKS = 20;
    private static final int HARD_CAP_TICKS = 80;
    private static final double SLIDE_SPEED = 0.85;
    private static final double MAX_TRAVEL_SQR = 400.0; // 20 blocks
    private static final double FALL_STOP_DROP = 5.0;
    private static final double HIT_RADIUS = 2.0;
    private static final double SLAM_DAMAGE = 6.0;
    private static final double LAUNCH_HORIZONTAL = 1.8;
    private static final double LAUNCH_VERTICAL = 0.5;
    private static final DustParticleOptions BROWN_DUST = new DustParticleOptions(0x79553A, 1.2f);
    private static final BlockParticleOption DIRT_BLOCK =
        new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DIRT.defaultBlockState());
    private static final Identifier FRICTION_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "mudslide_friction");
    private static final Identifier SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "mudslide_speed");
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "mudslide_knockback");

    @Override
    public String id() {
        return "mudslide_charge";
    }

    @Override
    public String displayName() {
        return "Mudslide Charge";
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
        state.putInt("slide_start", 0);
        state.putDouble("sx", caster.getX());
        state.putDouble("sy", caster.getY());
        state.putDouble("sz", caster.getZ());

        // Lock the slide line at the target's start position.
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

        PigAbilitySupport.addModifier(caster, Attributes.FRICTION_MODIFIER,
            new AttributeModifier(FRICTION_ID, -1.0, AttributeModifier.Operation.ADD_VALUE));
        PigAbilitySupport.addModifier(caster, Attributes.MOVEMENT_SPEED,
            new AttributeModifier(SPEED_ID, 0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        PigAbilitySupport.addModifier(caster, Attributes.ATTACK_KNOCKBACK,
            new AttributeModifier(KNOCKBACK_ID, 2.0, AttributeModifier.Operation.ADD_VALUE));

        ServerLevel level = ctx.serverLevel();
        if (level != null && PigAbilitySupport.pigSound() != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                PigAbilitySupport.pigSound(), SoundSource.HOSTILE, 1.5f, 0.9f);
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
            // The crouch: settling onto the belly.
            caster.getNavigation().stop();
            if (level != null && ticks % 2 == 0) {
                level.sendParticles(BROWN_DUST,
                    caster.getX(), caster.getY() + 0.3, caster.getZ(),
                    3, 0.45, 0.15, 0.45, 0.06);
            }
            if (ticks >= CROUCH_TICKS) {
                state.putInt("phase", 1);
                state.putInt("slide_start", ticks);
            }
        } else if (phase == 1) {
            slideTick(ctx, level, ticks);
        } else {
            // The skid: grinding to a halt.
            caster.getNavigation().stop();
            if (level != null && ticks % 2 == 0) {
                level.sendParticles(DIRT_BLOCK,
                    caster.getX(), caster.getY() + 0.2, caster.getZ(),
                    2, 0.5, 0.15, 0.5, 0.1);
            }
        }
    }

    /** One slide tick: drive, spray, hit check, stop conditions. */
    private void slideTick(AbilityContext ctx, ServerLevel level, int ticks) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        if (level == null) {
            return;
        }
        double dx = state.getDoubleOr("dx", 0.0);
        double dz = state.getDoubleOr("dz", 1.0);

        // Stop conditions that end the slide with no slam.
        double travelledX = caster.getX() - state.getDoubleOr("sx", caster.getX());
        double travelledZ = caster.getZ() - state.getDoubleOr("sz", caster.getZ());
        if (caster.horizontalCollision
            || travelledX * travelledX + travelledZ * travelledZ >= MAX_TRAVEL_SQR
            || caster.getY() < state.getDoubleOr("sy", caster.getY()) - FALL_STOP_DROP
            || ticks - state.getIntOr("slide_start", 0) >= SLIDE_MAX_TICKS) {
            enterSkid(ctx, ticks);
            return;
        }

        // The drive: code velocity along the locked line.
        caster.getNavigation().stop();
        Vec3 velocity = caster.getDeltaMovement();
        caster.setDeltaMovement(dx * SLIDE_SPEED, velocity.y, dz * SLIDE_SPEED);
        caster.hurtMarked = true;

        // The mud spray with every stride of the slide.
        level.sendParticles(BROWN_DUST,
            caster.getX(), caster.getY() + 0.25, caster.getZ(),
            2, 0.5, 0.15, 0.5, 0.08);
        level.sendParticles(DIRT_BLOCK,
            caster.getX(), caster.getY() + 0.2, caster.getZ(),
            2, 0.5, 0.12, 0.5, 0.1);

        // The slam: the first enemy in the plow box takes it.
        List<LivingEntity> victims = PigAbilitySupport.enemiesInBox(
            ctx, caster.getX(), caster.getY(), caster.getZ(), HIT_RADIUS, 1.0, 2.0);
        if (!victims.isEmpty()) {
            slam(ctx, level, victims.get(0), dx, dz);
            enterSkid(ctx, ticks);
        }
    }

    /** The slam itself: flat code damage and the launch. */
    private void slam(AbilityContext ctx, ServerLevel level, LivingEntity victim, double dx, double dz) {
        Mob caster = ctx.mob();
        victim.hurtServer(level, level.damageSources().mobAttack(caster), (float) SLAM_DAMAGE);
        victim.setDeltaMovement(dx * LAUNCH_HORIZONTAL, LAUNCH_VERTICAL, dz * LAUNCH_HORIZONTAL);
        victim.hurtMarked = true;

        level.sendParticles(DIRT_BLOCK,
            victim.getX(), victim.getY() + 0.6, victim.getZ(),
            10, 0.4, 0.5, 0.4, 0.2);
        level.sendParticles(BROWN_DUST,
            victim.getX(), victim.getY() + 0.5, victim.getZ(),
            6, 0.5, 0.4, 0.5, 0.1);
    }

    /** Ends the slide: damp the velocity and start the skid clock. */
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
        PigAbilitySupport.removeModifier(caster, Attributes.FRICTION_MODIFIER, FRICTION_ID);
        PigAbilitySupport.removeModifier(caster, Attributes.MOVEMENT_SPEED, SPEED_ID);
        PigAbilitySupport.removeModifier(caster, Attributes.ATTACK_KNOCKBACK, KNOCKBACK_ID);
    }
}
