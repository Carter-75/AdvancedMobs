package mod.advancedmobs.ability.skeletonhorse;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.List;

/**
 * Thunder Herald (Skeleton Horse ability 4 of 5,
 * transformation).
 *
 * WHAT IT DOES: while a thunderstorm rages, the skeleton
 * horse becomes the storm's herald. It grows larger behind
 * a glowing outline, speed and strength surge through its
 * frame, and lightning keeps striking around it as it hunts;
 * every enemy caught near a strike is rattled and slowed.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the horse's current target within 18 blocks (distance
 *   squared 324), plus the level actually thundering
 *   (Level.isThundering(), the design's weather hook). In
 *   clear or merely rainy weather this ability can never
 *   fire.
 * - The herald form (start(), all removed exactly in
 *   end()): SCALE +0.35 ADD_MULTIPLIED_BASE (1.35 times
 *   size, model and hitbox together), GLOWING for 620
 *   ticks (the design's glowing outline), STRENGTH I for
 *   620 ticks, and SPEED I for 620 ticks. The ambient skeleton
 *   horse sound plays at volume 1.5, pitch 0.6, and an opening
 *   lightning strike lands 3 blocks from the horse toward
 *   its target (see the strike spec below).
 * - The hunt (every 5 ticks): the herald is
 *   navigation-driven onto its live target at speed 1.2
 *   (a plain skeleton horse's AI never closes on its own;
 *   the hunt is code-driven, the Nightmare Steed
 *   precedent). The form's strength rides the horse's own
 *   war-system attacks; this package adds no separate
 *   strike damage of its own here (a skeleton horse has no
 *   ATTACK_DAMAGE attribute instance in 26.2, verified
 *   against the jar, and the herald's bite is the storm).
 * - Lightning strikes (the opening strike, then every 100
 *   ticks): the strike point is the target's position when
 *   the target is within 12 blocks, otherwise a random
 *   point on a ring 3 to 6 blocks around the horse. A
 *   LightningBolt is created with MOB_SUMMONED at that
 *   point. TERRAIN GATE: a real bolt can ignite blocks, so
 *   the bolt is REAL only when
 *   AbilityContext.mobGriefingAllowed() is true; when it
 *   is false the bolt is visual-only (setVisualOnly(true),
 *   the Bottled Tempest pattern), and the strike's bite is
 *   dealt in code instead (below). LIGHTNING_BOLT_THUNDER
 *   plays at volume 0.7, pitch 1.1 with each strike.
 * - The rattle (with each strike): one bounded enemy query
 *   of radius 3.0 around the strike point; every enemy
 *   caught gains SLOWNESS I for 100 ticks (the design's
 *   rattled and slowed). When the bolt is real, its own
 *   vanilla hit (5.0 lightning damage to whatever it
 *   strikes) is the strike's damage. When the bolt is
 *   visual-only, each rattled enemy also takes 3.0 damage
 *   as a mob attack credited to the horse, so the strike
 *   still bites with the gate off.
 * - Storm sparks (every 5 ticks): 1 ELECTRIC_SPARK at the
 *   herald's body, the storm crawling over bone.
 * - The storm watch (every 20 ticks): if the level has
 *   stopped thundering, the state tag's "storm_gone" flag
 *   is set and the ability ends early (the design: the
 *   surge lasts while thunder lasts).
 * - Duration: at most 600 ticks (30 seconds), ending early
 *   on the storm_gone flag.
 * - end(): the SCALE modifier is removed and GLOWING,
 *   STRENGTH, and SPEED are removed. Slowness already on
 *   victims is an ordinary vanilla effect and ticks out on
 *   its own clock.
 *
 * WHAT IT LOOKS LIKE: a towering skeleton horse outlined in
 * light, sparks crawling over its bones, thunder rolling as
 * bolt after bolt crashes down around it while it runs its
 * target down through the storm.
 *
 * PERFORMANCE: the hunt and sparks share a 5-tick interval
 * (one navigation drive, 1 particle); a strike is one bolt
 * spawn plus one tight bounded query, at most 7 strikes per
 * activation (the opening strike plus ticks 100 to 600);
 * the storm watch is one weather read every 20 ticks.
 * Particle budget per activation: sparks 120 times 1 = 120
 * particles; the bolts are entities, not particles.
 *
 * TERRAIN: the lightning is the package's one gated effect;
 * see the strike spec above. No blocks are placed or broken
 * by code anywhere in this ability.
 *
 * State tag keys: "storm_gone" (1 once the thunder has
 * stopped).
 */
public final class ThunderHeraldAbility implements MobAbility {

    private static final int DURATION_TICKS = 600;
    private static final int EFFECT_TICKS = 620;
    private static final int CHASE_INTERVAL = 5;
    private static final int STRIKE_INTERVAL = 100;
    private static final int STORM_WATCH_INTERVAL = 20;
    private static final double STRIKE_NEAR_TARGET_SQR = 144.0; // 12 blocks
    private static final double RATTLE_RADIUS = 3.0;
    private static final double CODE_STRIKE_DAMAGE = 3.0;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "thunder_herald_scale");

    @Override
    public String id() {
        return "thunder_herald";
    }

    @Override
    public String displayName() {
        return "Thunder Herald";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 324.0 && ctx.level().isThundering();
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();

        // The herald form: larger, outlined, surging.
        SkeletonHorseAbilitySupport.addModifier(caster, Attributes.SCALE,
            new AttributeModifier(SCALE_ID, 0.35, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        caster.addEffect(new MobEffectInstance(MobEffects.GLOWING, EFFECT_TICKS, 0));
        caster.addEffect(new MobEffectInstance(MobEffects.STRENGTH, EFFECT_TICKS, 0));
        caster.addEffect(new MobEffectInstance(MobEffects.SPEED, EFFECT_TICKS, 0));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.SKELETON_HORSE_AMBIENT, SoundSource.HOSTILE, 1.5f, 0.6f);
            // The opening strike: 3 blocks from the horse toward its target.
            LivingEntity target = ctx.target();
            double strikeX = caster.getX();
            double strikeZ = caster.getZ();
            if (target != null) {
                double dx = target.getX() - caster.getX();
                double dz = target.getZ() - caster.getZ();
                double length = Math.sqrt(dx * dx + dz * dz);
                if (length > 0.001) {
                    strikeX += dx / length * 3.0;
                    strikeZ += dz / length * 3.0;
                }
            }
            strike(ctx, strikeX, caster.getY(), strikeZ);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        int ticks = ctx.ticksActive();
        if (level == null) {
            return;
        }

        if (ticks % CHASE_INTERVAL == 0) {
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, caster.getX(), caster.getY() + 1.1, caster.getZ(),
                1, 0.5, 0.5, 0.5, 0.05);
            LivingEntity target = ctx.target();
            if (target != null && caster.distanceToSqr(target) > 4.0) {
                caster.getNavigation().moveTo(target, 1.2);
            }
        }
        if (ticks % STRIKE_INTERVAL == 0) {
            LivingEntity target = ctx.target();
            double strikeX;
            double strikeZ;
            if (target != null && caster.distanceToSqr(target) <= STRIKE_NEAR_TARGET_SQR) {
                strikeX = target.getX();
                strikeZ = target.getZ();
            } else {
                double angle = ctx.random().nextDouble() * Math.PI * 2.0;
                double distance = 3.0 + ctx.random().nextDouble() * 3.0;
                strikeX = caster.getX() + Math.cos(angle) * distance;
                strikeZ = caster.getZ() + Math.sin(angle) * distance;
            }
            strike(ctx, strikeX, caster.getY(), strikeZ);
        }
        if (ticks % STORM_WATCH_INTERVAL == 0 && !ctx.level().isThundering()) {
            ctx.state().putInt("storm_gone", 1);
        }
    }

    /**
     * One lightning strike at the given point: the bolt
     * (real or visual-only by the mobGriefing gate), the
     * thunder, and the rattle on every enemy caught near it.
     */
    private void strike(AbilityContext ctx, double x, double y, double z) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        boolean real = ctx.mobGriefingAllowed();
        LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (bolt != null) {
            bolt.snapTo(x, y, z);
            if (!real) {
                bolt.setVisualOnly(true);
            }
            level.addFreshEntity(bolt);
        }
        level.playSound(null, x, y, z,
            SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.HOSTILE, 0.7f, 1.1f);

        // The rattle: enemies near the strike are slowed, and bitten
        // in code when the bolt itself was only a picture.
        List<LivingEntity> rattled = SkeletonHorseAbilitySupport.enemiesInBox(ctx, x, y, z,
            RATTLE_RADIUS, 2.0, 3.0);
        for (LivingEntity enemy : rattled) {
            enemy.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 100, 0));
            if (!real) {
                enemy.hurtServer(level, level.damageSources().mobAttack(ctx.mob()), (float) CODE_STRIKE_DAMAGE);
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS
            || ctx.state().getIntOr("storm_gone", 0) == 1;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        SkeletonHorseAbilitySupport.removeModifier(caster, Attributes.SCALE, SCALE_ID);
        caster.removeEffect(MobEffects.GLOWING);
        caster.removeEffect(MobEffects.STRENGTH);
        caster.removeEffect(MobEffects.SPEED);
    }
}
