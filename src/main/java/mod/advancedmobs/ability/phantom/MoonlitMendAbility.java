package mod.advancedmobs.ability.phantom;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.DustParticleOptions;
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
 * Moonlit Mend (Phantom ability 3 of 5, defense).
 *
 * WHAT IT DOES: a hurt phantom breaks away from the fight
 * and climbs into a shaft of moonlight high above its
 * enemy, circling in the light while its wounds knit. For
 * the whole mend it is shrunk slightly smaller and harder
 * to hit, and it flies a touch faster, until the light
 * fades and it dives again on vanilla wings.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the phantom's current target within 24 blocks
 *   (distance squared 576), plus the phantom at or below
 *   60 percent of its maximum health (the design's "when
 *   hurt": an unhurt phantom never spends an ability slot
 *   on a heal it does not need).
 * - Duration: 200 ticks (10 seconds), hard finish.
 * - The mend (start()): REGENERATION II (amplifier 1) for
 *   200 ticks, which heals about 10 health over the
 *   activation at vanilla regeneration rates, removed in
 *   end() if it is somehow still running. A PHANTOM_AMBIENT
 *   cry at pitch 1.4 marks the break-away.
 * - The shrink (start(), reversed in end()): a transient
 *   SCALE modifier of -0.25 ADD_MULTIPLIED_BASE (0.75
 *   times size, model and hitbox together, the design's
 *   "shrinks slightly smaller and harder to hit") under a
 *   fixed modifier id, removed in end().
 * - The climb speed (start(), reversed in end()): a
 *   transient FLYING_SPEED modifier of +0.03 ADD_VALUE
 *   under a fixed id (the design's Uses line; applied
 *   through the null-tolerant helper, so on a phantom
 *   whose attribute set lacks the instance it is a
 *   documented no-op and the mend is carried by the
 *   steering below).
 * - The circling (tick(), every tick): the velocity is set
 *   toward a point on a circle of radius 4.0 around a
 *   center 13 blocks above the target's live position, the
 *   point advancing 0.1 radians per tick, at 0.9 blocks
 *   per tick. The phantom therefore climbs away from the
 *   fight and wheels in a slow circle in the shaft for the
 *   whole mend. If the target dies mid-mend, the circle
 *   centers over the phantom's own position instead and
 *   the mend simply runs out its clock.
 * - The light (every 2 ticks): 2 END_ROD sparkles fall
 *   from 1.2 blocks above the phantom (the shaft's bright
 *   core) and 2 pale DUST motes (color 0xD8E4F0, scale
 *   1.0, the pre-built option below) drift around its
 *   body.
 * - end(): REGENERATION is removed and both transient
 *   modifiers are removed by id, so the phantom returns to
 *   exactly its pre-mend size and speed. If the caster
 *   dies first, the framework termination path runs this
 *   same end(); transient modifiers die with the entity
 *   anyway.
 *
 * WHAT IT LOOKS LIKE: a wounded phantom peeling upward
 * with a high cry into a column of pale sparkles and
 * drifting moon motes, visibly smaller, wheeling slowly in
 * the light while its health climbs back, then the light
 * dies and the full-size shape folds back toward the
 * fight.
 *
 * PERFORMANCE: per tick one steering computation and one
 * velocity write; particles only every 2 ticks (4 per
 * draw); no entity queries anywhere. Particle budget per
 * activation: 4 times 100 draws = at most 400 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: none of its own; the activation is fully
 * described by the framework tick counter.
 */
public final class MoonlitMendAbility implements MobAbility {

    private static final int DURATION_TICKS = 200;
    private static final double CIRCLE_HEIGHT = 13.0;
    private static final double CIRCLE_RADIUS = 4.0;
    private static final double CIRCLE_SPEED = 0.9;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "moonlit_mend_scale");
    private static final Identifier FLYING_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "moonlit_mend_flying");
    private static final DustParticleOptions MOON_DUST = new DustParticleOptions(0xD8E4F0, 1.0f);

    @Override
    public String id() {
        return "moonlit_mend";
    }

    @Override
    public String displayName() {
        return "Moonlit Mend";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        Mob caster = ctx.mob();
        return target != null && caster.distanceToSqr(target) <= 576.0
            && caster.getHealth() <= caster.getMaxHealth() * 0.6f;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.addEffect(new MobEffectInstance(MobEffects.REGENERATION, DURATION_TICKS, 1, false, false, false));
        PhantomAbilitySupport.addModifier(caster, Attributes.SCALE,
            new AttributeModifier(SCALE_ID, -0.25, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        PhantomAbilitySupport.addModifier(caster, Attributes.FLYING_SPEED,
            new AttributeModifier(FLYING_ID, 0.03, AttributeModifier.Operation.ADD_VALUE));
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.PHANTOM_AMBIENT,
                SoundSource.HOSTILE, 1.0f, 1.4f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        LivingEntity target = ctx.target();
        double centerX = target != null ? target.getX() : caster.getX();
        double centerY = (target != null ? target.getY() : caster.getY()) + CIRCLE_HEIGHT;
        double centerZ = target != null ? target.getZ() : caster.getZ();
        double angle = ctx.ticksActive() * 0.1;
        PhantomAbilitySupport.steerToward(ctx,
            centerX + Math.cos(angle) * CIRCLE_RADIUS, centerY, centerZ + Math.sin(angle) * CIRCLE_RADIUS,
            CIRCLE_SPEED);

        if (ctx.ticksActive() % 2 == 0) {
            ServerLevel level = ctx.serverLevel();
            if (level != null) {
                level.sendParticles(ParticleTypes.END_ROD, caster.getX(), caster.getY() + 1.2, caster.getZ(),
                    2, 0.3, 0.2, 0.3, 0.02);
                level.sendParticles(MOON_DUST, caster.getX(), caster.getY() + 0.3, caster.getZ(),
                    2, 0.4, 0.3, 0.4, 0.02);
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.removeEffect(MobEffects.REGENERATION);
        PhantomAbilitySupport.removeModifier(caster, Attributes.SCALE, SCALE_ID);
        PhantomAbilitySupport.removeModifier(caster, Attributes.FLYING_SPEED, FLYING_ID);
    }
}
