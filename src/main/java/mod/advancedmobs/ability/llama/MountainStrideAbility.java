package mod.advancedmobs.ability.llama;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * Mountain Stride (Llama ability 5 of 5, transformation /
 * mobility).
 *
 * WHAT IT DOES: the llama stretches taller, its legs
 * lengthening as it grows, and its step turns springy with
 * CLOUD dust puffing at every footfall. In this form it
 * strides straight up steep slopes without jumping, moves
 * noticeably faster, and can leap gaps that would stop
 * other mobs cold.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the llama's current target within 20 blocks (distance
 *   squared 400).
 * - The form (start(), every modifier removed exactly in
 *   end()): four transient attribute modifiers under fixed
 *   ids: SCALE +0.25 ADD_MULTIPLIED_BASE (1.25 times size,
 *   the visible stretch), STEP_HEIGHT +2.0 ADD_VALUE (on
 *   top of the llama's horse-family base step, so it walks
 *   up multi-block slopes as if they were stairs, the
 *   design's "strides straight up steep slopes"),
 *   MOVEMENT_SPEED +0.30 ADD_MULTIPLIED_BASE (30 percent
 *   faster), and JUMP_STRENGTH +0.25 ADD_VALUE (a much
 *   higher, longer leap when its AI does jump, the
 *   design's gap clearing). LLAMA_AMBIENT plays at volume
 *   1.2, pitch 1.1 as it stretches.
 * - Duration: exactly 400 ticks (20 seconds), hard finish.
 * - The footfalls (tick(), every 4 ticks): if the llama is
 *   actually moving (horizontal velocity length above
 *   0.05 blocks per tick), 3 CLOUD particles puff at its
 *   feet. A standing llama raises no dust.
 * - end(): all four modifiers are removed; the llama
 *   shrinks back to its natural size and gait in the same
 *   tick. Nothing else was changed, so there is nothing
 *   else to undo.
 *
 * WHAT IT LOOKS LIKE: a llama a quarter again taller than
 * it should be, loping up slopes it has no business
 * climbing, little clouds of dust kicking up under its
 * hooves as it comes.
 *
 * PERFORMANCE: start() is four modifier writes and one
 * sound, once. tick() returns immediately except every 4th
 * tick, where it reads one velocity and sends at most 3
 * particles; there are no entity queries anywhere in the
 * ability. Particle budget per activation: 100 footfall
 * pulses times 3 = at most 300 particles, and only while
 * the llama is actually moving.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: none (the duration rides the framework
 * tick counter and the modifiers ride their fixed ids).
 */
public final class MountainStrideAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int FOOTFALL_INTERVAL = 4;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "mountain_stride_scale");
    private static final Identifier STEP_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "mountain_stride_step");
    private static final Identifier SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "mountain_stride_speed");
    private static final Identifier JUMP_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "mountain_stride_jump");

    @Override
    public String id() {
        return "mountain_stride";
    }

    @Override
    public String displayName() {
        return "Mountain Stride";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 400.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();
        LlamaAbilitySupport.addModifier(caster, Attributes.SCALE,
            new AttributeModifier(SCALE_ID, 0.25, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        LlamaAbilitySupport.addModifier(caster, Attributes.STEP_HEIGHT,
            new AttributeModifier(STEP_ID, 2.0, AttributeModifier.Operation.ADD_VALUE));
        LlamaAbilitySupport.addModifier(caster, Attributes.MOVEMENT_SPEED,
            new AttributeModifier(SPEED_ID, 0.30, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        LlamaAbilitySupport.addModifier(caster, Attributes.JUMP_STRENGTH,
            new AttributeModifier(JUMP_ID, 0.25, AttributeModifier.Operation.ADD_VALUE));
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.LLAMA_AMBIENT,
                SoundSource.HOSTILE, 1.2f, 1.1f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % FOOTFALL_INTERVAL != 0) {
            return; // Cheap early-out: footfalls pulse every 4 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        Vec3 velocity = caster.getDeltaMovement();
        double horizontal = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        if (horizontal <= 0.05) {
            return; // Standing still raises no dust.
        }
        level.sendParticles(ParticleTypes.CLOUD, caster.getX(), caster.getY() + 0.15, caster.getZ(),
            3, 0.4, 0.1, 0.4, 0.03);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        LlamaAbilitySupport.removeModifier(caster, Attributes.SCALE, SCALE_ID);
        LlamaAbilitySupport.removeModifier(caster, Attributes.STEP_HEIGHT, STEP_ID);
        LlamaAbilitySupport.removeModifier(caster, Attributes.MOVEMENT_SPEED, SPEED_ID);
        LlamaAbilitySupport.removeModifier(caster, Attributes.JUMP_STRENGTH, JUMP_ID);
    }
}
