package mod.advancedmobs.ability.hoglin;

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
 * Crimson Maw (Hoglin ability 2 of 5, transformation).
 *
 * WHAT IT DOES: the hoglin swells into a frenzy form. Its body
 * grows, its bite damage climbs sharply, its goring hits toss
 * enemies into the air, and puffs of crimson spores roll from
 * its snout with every breath for the whole form. When the form
 * burns out the swell collapses in a spray of spores.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   hoglin's current target within 10 blocks (distance squared
 *   100). A brawling transformation, like the other brutes'.
 * - Duration: exactly 300 ticks (15 seconds) from start().
 * - Form modifiers, all applied once in start() as transient
 *   attribute modifiers and all removed in end():
 *     SCALE add_multiplied_base +0.25 (id
 *       advancedmobs:crimson_maw_scale): the beast swells to
 *       1.25 times its size, model and hitbox together.
 *     ATTACK_DAMAGE add_value +4.0 (id
 *       advancedmobs:crimson_maw_damage): the hoglin's base
 *       attack damage is 6, so its bite lands for 10 while the
 *       maw lasts. That is the design's "bite damage climbs
 *       sharply".
 *     ATTACK_KNOCKBACK add_value +1.0 (id
 *       advancedmobs:crimson_maw_knockback): the hoglin's base
 *       attack knockback is 0, so its goring hits now carry a
 *       full extra knockback unit and toss enemies up and back
 *       on every connect, the design's goring launch expressed
 *       through the vanilla knockback the attribute drives.
 * - The frenzy is the modifiers themselves: for 15 seconds the
 *   hoglin hits harder, tosses what it hits, and is a quarter
 *   again as large while it does it. No extra effects are
 *   stacked on top; the design's stat list is SCALE,
 *   ATTACK_DAMAGE, and knockback, and this ability implements
 *   exactly those.
 *
 * WHAT IT LOOKS LIKE: the design's lengthened crimson tusks
 * are a custom model layer, and this framework has no
 * client-side render hook (the same documented limit as the
 * Mirror Ghasts' close-range shimmer and Scale of the End's
 * eyes), so the maw is carried by the 1.25 size swell plus the
 * breath: every 10 ticks, 3 CRIMSON_SPORE particles puff from
 * the snout, positioned one head-length ahead of the body
 * center at three quarters of its height along its look
 * direction. A HOGLIN_ANGRY roar and a 12-spore burst open the
 * form; when it ends, a 12-spore spray bursts out as the swell
 * collapses (the design's tusks cracking back to normal) with
 * a short HOGLIN_HURT cry.
 *
 * PERFORMANCE: tick() returns immediately except every 10th
 * tick, which does one look-angle read and one 3-particle send.
 * No entity queries, no state reads or writes at all. Particle
 * budget per activation: 12 at start, 30 breath pulses times
 * 3 = 90, 12 at end, total at most 114 particles.
 *
 * CLEANUP: end() removes all three modifiers by id and plays
 * the collapse spray. If the hoglin dies mid-form the
 * framework termination path runs this same end() on a dead
 * mob, where the removals are harmless no-ops.
 *
 * State tag keys: none. The whole activation is a function of
 * ticksActive() and the three start() modifiers.
 */
public final class CrimsonMawAbility implements MobAbility {

    static final int DURATION_TICKS = 300;
    private static final int BREATH_INTERVAL = 10;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "crimson_maw_scale");
    private static final Identifier DAMAGE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "crimson_maw_damage");
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "crimson_maw_knockback");

    @Override
    public String id() {
        return "crimson_maw";
    }

    @Override
    public String displayName() {
        return "Crimson Maw";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 100.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        // The swell: a quarter again as large.
        HoglinAbilitySupport.addModifier(mob, Attributes.SCALE,
            new AttributeModifier(SCALE_ID, 0.25, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        // The bite: 6 base damage climbs to 10.
        HoglinAbilitySupport.addModifier(mob, Attributes.ATTACK_DAMAGE,
            new AttributeModifier(DAMAGE_ID, 4.0, AttributeModifier.Operation.ADD_VALUE));
        // The gore: every connect tosses.
        HoglinAbilitySupport.addModifier(mob, Attributes.ATTACK_KNOCKBACK,
            new AttributeModifier(KNOCKBACK_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.HOGLIN_ANGRY,
                SoundSource.HOSTILE, 1.0f, 1.0f);
            level.sendParticles(ParticleTypes.CRIMSON_SPORE, mob.getX(), mob.getY() + 1.0, mob.getZ(),
                12, 0.8, 0.6, 0.8, 0.05);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % BREATH_INTERVAL != 0) {
            return; // Cheap early-out: the breath puffs every 10 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        // Spore breath from the snout: ahead of the body along
        // its facing, at head height.
        Vec3 look = mob.getLookAngle();
        double snoutX = mob.getX() + look.x * mob.getBbWidth() * 0.75;
        double snoutZ = mob.getZ() + look.z * mob.getBbWidth() * 0.75;
        double snoutY = mob.getY() + mob.getBbHeight() * 0.75;
        level.sendParticles(ParticleTypes.CRIMSON_SPORE, snoutX, snoutY, snoutZ,
            3, 0.25, 0.2, 0.25, 0.03);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        HoglinAbilitySupport.removeModifier(mob, Attributes.SCALE, SCALE_ID);
        HoglinAbilitySupport.removeModifier(mob, Attributes.ATTACK_DAMAGE, DAMAGE_ID);
        HoglinAbilitySupport.removeModifier(mob, Attributes.ATTACK_KNOCKBACK, KNOCKBACK_ID);

        // The swell collapses in a spray of spores.
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.sendParticles(ParticleTypes.CRIMSON_SPORE, mob.getX(), mob.getY() + 1.0, mob.getZ(),
                12, 0.8, 0.6, 0.8, 0.08);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.HOGLIN_HURT,
                SoundSource.HOSTILE, 0.6f, 1.3f);
        }
    }
}
