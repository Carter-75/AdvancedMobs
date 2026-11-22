package mod.advancedmobs.ability.strider;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
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

/**
 * Obsidian Skin (Strider ability 4 of 5, defense).
 *
 * WHAT IT DOES: the strider's hide cools and darkens into an
 * obsidian shell, FALLING_OBSIDIAN_TEAR flecks shedding off
 * it as it moves. In the shell the strider is heavily
 * armored, shakes off a share of everything else through
 * RESISTANCE, and is immune to fire entirely. The price is
 * weight: it moves a little slower until the shell cracks
 * away at the end of the duration.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): a live target within 12 blocks
 *   (distance squared 144).
 * - The shell (start()): three changes, all reversed exactly
 *   in end():
 *   - A transient ARMOR modifier (id
 *     advancedmobs:strider_obsidian_armor) of +20.0 add_value:
 *     the obsidian plate itself, worth about 45 percent
 *     damage reduction on its own at the armor formula.
 *   - RESISTANCE I and FIRE_RESISTANCE, both applied for 320
 *     ticks (the duration plus a small margin) and removed
 *     in end(). A strider is already fire immune by type in
 *     vanilla; the FIRE_RESISTANCE is the design's stated
 *     shell property, kept so the read is plain and so the
 *     shell holds even if a future change touches the type
 *     immunity.
 *   - A transient MOVEMENT_SPEED modifier (id
 *     advancedmobs:strider_obsidian_slow) of -0.25
 *     add_multiplied_base: base 0.175 becomes about 0.13, the
 *     design's "a little slower".
 * - Duration: exactly 300 ticks (15 seconds) from start().
 *   There is no early crack-off: the shell is timed, not a
 *   health pool (the design says it lasts "until the shell
 *   cracks away", and the crack-away moment is the end of
 *   the duration).
 *
 * WHAT IT LOOKS LIKE: an opening shed of 10
 * FALLING_OBSIDIAN_TEAR over the body with a low STRIDER_HURT
 * creak (volume 0.8, pitch 0.6, the shell setting), then 2
 * FALLING_OBSIDIAN_TEAR flecks every 4 ticks while the
 * strider is actually moving, and 1 DRIPPING_OBSIDIAN_TEAR
 * every 20 ticks whether it moves or not. The deep purple
 * black render tint of the design has no server-side hook in
 * 26.2; the look is carried by the tear particles, the same
 * documented limit as the other form abilities.
 *
 * PERFORMANCE: per tick the cost is one horizontal speed
 * read; the flecks ride 4 and 20 tick intervals. No entity
 * queries at all. Particle budget per activation: opening
 * 10, moving flecks at most 75 times 2 = 150, drips 15,
 * total at most 175 particles.
 *
 * CLEANUP: end() removes both attribute modifiers and both
 * effects. Nothing else persists.
 *
 * State tag keys: none beyond the framework's; the shell is
 * pure timed modifiers and effects.
 */
public final class ObsidianSkinAbility implements MobAbility {

    static final int DURATION_TICKS = 300;
    private static final Identifier ARMOR_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "strider_obsidian_armor");
    private static final Identifier SLOW_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "strider_obsidian_slow");

    @Override
    public String id() {
        return "obsidian_skin";
    }

    @Override
    public String displayName() {
        return "Obsidian Skin";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        StriderAbilitySupport.addModifier(mob, Attributes.ARMOR,
            new AttributeModifier(ARMOR_ID, 20.0, AttributeModifier.Operation.ADD_VALUE));
        StriderAbilitySupport.addModifier(mob, Attributes.MOVEMENT_SPEED,
            new AttributeModifier(SLOW_ID, -0.25, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        mob.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, DURATION_TICKS + 20, 0, false, false, false));
        mob.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, DURATION_TICKS + 20, 0, false, false, false));
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.sendParticles(ParticleTypes.FALLING_OBSIDIAN_TEAR,
                mob.getX(), mob.getY() + mob.getBbHeight() * 0.6, mob.getZ(),
                10, 0.5, 0.5, 0.5, 0.05);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.STRIDER_HURT,
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
        if (ticks % 4 == 0) {
            // Shedding only while moving: a still strider's
            // shell sits quiet.
            var motion = mob.getDeltaMovement();
            if (motion.x * motion.x + motion.z * motion.z > 0.0025) {
                level.sendParticles(ParticleTypes.FALLING_OBSIDIAN_TEAR,
                    mob.getX(), mob.getY() + mob.getBbHeight() * 0.6, mob.getZ(),
                    2, 0.4, 0.4, 0.4, 0.03);
            }
        }
        if (ticks % 20 == 0) {
            level.sendParticles(ParticleTypes.DRIPPING_OBSIDIAN_TEAR,
                mob.getX(), mob.getY() + mob.getBbHeight(), mob.getZ(),
                1, 0.3, 0.2, 0.3, 0.0);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        StriderAbilitySupport.removeModifier(mob, Attributes.ARMOR, ARMOR_ID);
        StriderAbilitySupport.removeModifier(mob, Attributes.MOVEMENT_SPEED, SLOW_ID);
        mob.removeEffect(MobEffects.RESISTANCE);
        mob.removeEffect(MobEffects.FIRE_RESISTANCE);
    }
}
