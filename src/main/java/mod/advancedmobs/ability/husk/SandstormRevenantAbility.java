package mod.advancedmobs.ability.husk;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * Sandstorm Revenant (Husk ability 3 of 5, transformation).
 *
 * WHAT IT DOES: the husk dissolves into a walking sandstorm. For 12
 * seconds its form is wrapped in a cyclone of sand pillars, white
 * ash, and falling sand that roars around it in a column. It moves at
 * SPEED through its own storm and shrugs off knockback, and enemies
 * who step into the column are battered by typed damage and pushed
 * back toward the edges.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   husk's current target within 8 blocks (distance squared 64). This
 *   is a brawling transformation, not a sniping tool.
 * - Duration: exactly 240 ticks (12 seconds) from start().
 * - Self buffs, applied once in start():
 *     SPEED amplifier 1 (Speed II) for 240 ticks, the full duration;
 *       it expires on its own as the ability ends.
 *     KNOCKBACK_RESISTANCE add_value +0.5 as a transient attribute
 *       modifier (id advancedmobs:sandstorm_revenant_knockback),
 *       removed in end(). The husk's base knockback resistance is 0,
 *       so while the storm lasts it keeps half of every knockback it
 *       takes.
 * - Batter pulse every 20 ticks (12 pulses): enemies
 *   (HuskAbilitySupport rules) within radius 2.5 of the husk (1 below
 *   to 3 above its feet) take 3.0 points of typed mob-attack damage
 *   (DamageSources.mobAttack) and are flung outward: delta movement
 *   set to 0.7 horizontal away from the husk and 0.30 up, replacing
 *   their current motion the way Seismic Slam's launch does. A
 *   victim standing dead center (no horizontal offset) is pushed
 *   along the husk's look direction instead. Being pushed out is
 *   what keeps the damage honest: a victim who keeps walking back in
 *   eats at most 12 pulses times 3.0 = 36 damage over the full storm,
 *   and only by fighting the push the whole time.
 *
 * WHAT IT LOOKS LIKE: every 2 ticks the cyclone emits 3 particles at
 * points rotating around the husk (angle advances 0.35 radians per
 * tick, so the column visibly churns): 1 DUST_PILLAR of sand at
 * radius 1.3 at the current angle and chest height, 1 WHITE_ASH at
 * radius 1.3 half a turn further around and head height, and 1
 * FALLING_DUST of sand above the husk's head at 2.2 blocks, sheeting
 * off the top of the column. The husk itself stays visible inside
 * its storm (this is a particle wrap, not a model change), moving at
 * Speed II.
 *
 * PERFORMANCE: tick() returns immediately on odd ticks. Even ticks do
 * 3 particle sends from pre-built static particle options, computed
 * from ticksActive() alone (no state reads or writes, no trig beyond
 * two points). The batter pulse runs one bounded entity query (5 by
 * 4 by 5 box) every 20 ticks. Particle budget per activation: 120
 * emissions times 3 = at most 360 particles.
 *
 * CLEANUP: end() removes the knockback resistance modifier. The
 * SPEED effect was applied for exactly the ability duration and
 * expires on its own; if the husk dies mid-storm the framework
 * termination path runs this same end() on a dead mob, where the
 * modifier removal is a harmless no-op.
 *
 * State tag keys: none. The whole activation is a function of
 * ticksActive() and the two start() buffs.
 */
public final class SandstormRevenantAbility implements MobAbility {

    static final int DURATION_TICKS = 240;
    private static final int PULSE_INTERVAL = 20;
    private static final float BATTER_DAMAGE = 3.0f;
    private static final double PUSH_HORIZONTAL = 0.7;
    private static final double PUSH_VERTICAL = 0.30;
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "sandstorm_revenant_knockback");
    private static final BlockParticleOption SAND_PILLAR =
        new BlockParticleOption(ParticleTypes.DUST_PILLAR, Blocks.SAND.defaultBlockState());
    private static final BlockParticleOption SAND_FALLING =
        new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.SAND.defaultBlockState());

    @Override
    public String id() {
        return "sandstorm_revenant";
    }

    @Override
    public String displayName() {
        return "Sandstorm Revenant";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 64.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        // Through its own storm at full speed.
        mob.addEffect(new MobEffectInstance(MobEffects.SPEED, DURATION_TICKS, 1));
        // Reduced knockback while the storm holds it together.
        AttributeInstance knockback = mob.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (knockback != null) {
            knockback.addOrUpdateTransientModifier(
                new AttributeModifier(KNOCKBACK_ID, 0.5, AttributeModifier.Operation.ADD_VALUE));
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks % 2 != 0) {
            return; // Cheap early-out: the cyclone emits every 2 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();

        // Cyclone visuals: three points churning around the husk.
        double angle = ticks * 0.35;
        double x1 = mob.getX() + Math.cos(angle) * 1.3;
        double z1 = mob.getZ() + Math.sin(angle) * 1.3;
        double x2 = mob.getX() - Math.cos(angle) * 1.3;
        double z2 = mob.getZ() - Math.sin(angle) * 1.3;
        level.sendParticles(SAND_PILLAR, x1, mob.getY() + 1.0, z1, 1, 0.15, 0.4, 0.15, 0.02);
        level.sendParticles(ParticleTypes.WHITE_ASH, x2, mob.getY() + 1.8, z2, 1, 0.15, 0.3, 0.15, 0.02);
        level.sendParticles(SAND_FALLING, mob.getX(), mob.getY() + 2.2, mob.getZ(), 1, 0.8, 0.2, 0.8, 0.02);

        if (ticks % PULSE_INTERVAL == 0) {
            pulse(ctx, level);
        }
    }

    /** One batter pulse: enemies in the column take damage and are flung outward. */
    private void pulse(AbilityContext ctx, ServerLevel level) {
        Mob mob = ctx.mob();
        List<LivingEntity> victims = HuskAbilitySupport.enemiesAroundSelf(ctx, 2.5, 1.0, 3.0);
        for (LivingEntity victim : victims) {
            victim.hurtServer(level, level.damageSources().mobAttack(mob), BATTER_DAMAGE);
            double ox = victim.getX() - mob.getX();
            double oz = victim.getZ() - mob.getZ();
            double horizontal = Math.sqrt(ox * ox + oz * oz);
            if (horizontal < 1.0e-4) {
                // Dead center: push along the husk's facing instead.
                ox = mob.getLookAngle().x;
                oz = mob.getLookAngle().z;
                horizontal = Math.sqrt(ox * ox + oz * oz);
                if (horizontal < 1.0e-4) {
                    ox = 1.0;
                    horizontal = 1.0;
                }
            }
            victim.setDeltaMovement(ox / horizontal * PUSH_HORIZONTAL, PUSH_VERTICAL,
                oz / horizontal * PUSH_HORIZONTAL);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Only the attribute modifier is undone; the SPEED effect was
        // applied for exactly the duration and expires on its own.
        AttributeInstance knockback = ctx.mob().getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (knockback != null) {
            knockback.removeModifier(KNOCKBACK_ID);
        }
    }
}
