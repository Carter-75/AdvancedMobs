package mod.advancedmobs.ability.goat;

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
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Cliff Master (Goat ability 3 of 5, mobility /
 * transformation).
 *
 * WHAT IT DOES: the goat's hooves spark as it becomes a
 * creature of the cliff face for 20 seconds: it leaps
 * impossibly high, steps up ledges other mobs would have
 * to climb around, shrugs off falls that would kill
 * anything else its size, and when it drops from the
 * heights onto an enemy it lands as a plunging headbutt.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the goat's current target within 16 blocks
 *   (distance squared 256).
 * - Duration: exactly 400 ticks (20 seconds) from start().
 * - The cliff attributes (start(), all transient under
 *   fixed ids, all removed in end()):
 *     JUMP_STRENGTH     add_value +0.5 (id
 *       advancedmobs:cliff_master_jump): the living base is
 *       0.42, so the goat's AI jumps leave the ground at
 *       0.92 jump power, well over double: the design's
 *       "jumps higher than any mob its size." (Verified
 *       against the jar: JUMP_STRENGTH is in
 *       LivingEntity.createLivingAttributes, so the goat
 *       has the instance and Mob's jump reads it.)
 *     SAFE_FALL_DISTANCE add_value +100.0 (id
 *       advancedmobs:cliff_master_fall): the design's
 *       "ignores almost all fall damage"; 100 extra safe
 *       blocks covers any cliff this goat can climb.
 *     STEP_HEIGHT       add_value +2.0 (id
 *       advancedmobs:cliff_master_step): the goat walks
 *       straight up ledges 2 blocks taller than its normal
 *       step, the "landing precise on a ledge" stride.
 *   The goat's own AI does the climbing: with these three
 *   attributes its vanilla pathing and jumping carry it up
 *   terrain it could never normally scale, in the series of
 *   leaps the design describes. No movement is forced from
 *   code.
 * - The plunging headbutt (tick()): while the goat is
 *   airborne and its vertical velocity is at or below
 *   -0.55 blocks per tick (a real drop, not a hop), the
 *   state flag "plunge" is set. On the first grounded tick
 *   after that, the landing resolves: a burst of 6 CLOUD
 *   at the goat's feet, one bounded query of radius 3.0
 *   (1.5 below to 2.0 above), and every enemy in it
 *   (GoatAbilitySupport rules) takes damage equal to the
 *   goat's live ATTACK_DAMAGE attribute value plus 4.0
 *   (6.0 total for an unupgraded goat) as a mob attack
 *   credited to the goat, plus a knockback of 1.2
 *   horizontal away from the goat and 0.45 up, hurtMarked
 *   set. GOAT_RAM_IMPACT plays when at least one enemy is
 *   hit (the screaming variant's impact sound when the
 *   goat is a screaming goat). The flag then clears, so
 *   each drop lands at most one headbutt. Ordinary hops
 *   and step-downs never arm the flag: their fall speed
 *   never reaches -0.55.
 * - end(): the three modifiers are removed. A goat caught
 *   mid-drop by the duration ending keeps the fall it is
 *   in: the SAFE_FALL_DISTANCE goes away with the form,
 *   which is the honest end of the transformation.
 *
 * WHAT IT LOOKS LIKE: SCRAPE sparks at the goat's hooves
 * whenever it moves (2 particles every 4 ticks while its
 * horizontal speed is above 0.05 blocks per tick), huge
 * bounding leaps up the cliff face, and a dust burst with
 * a crack of horns wherever it crashes down onto prey.
 *
 * PERFORMANCE: per tick one velocity read, one onGround
 * read, and the movement check for the scrape interval;
 * the only entity query in the ability runs once per
 * landing. Particle budget per activation: scrape at most
 * 100 emissions times 2 = 200 (only while moving), plus 6
 * CLOUD per landing, total at most about 230 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "plunge" (1 while the goat is in a real
 * drop and the landing headbutt is armed).
 */
public final class CliffMasterAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int SCRAPE_INTERVAL = 4;
    private static final double PLUNGE_FALL_SPEED = -0.55;
    private static final double PLUNGE_RADIUS = 3.0;
    private static final double PLUNGE_BONUS_DAMAGE = 4.0;
    private static final Identifier JUMP_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "cliff_master_jump");
    private static final Identifier FALL_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "cliff_master_fall");
    private static final Identifier STEP_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "cliff_master_step");

    @Override
    public String id() {
        return "cliff_master";
    }

    @Override
    public String displayName() {
        return "Cliff Master";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ctx.state().putInt("plunge", 0);
        GoatAbilitySupport.addModifier(caster, Attributes.JUMP_STRENGTH,
            new AttributeModifier(JUMP_ID, 0.5, AttributeModifier.Operation.ADD_VALUE));
        GoatAbilitySupport.addModifier(caster, Attributes.SAFE_FALL_DISTANCE,
            new AttributeModifier(FALL_ID, 100.0, AttributeModifier.Operation.ADD_VALUE));
        GoatAbilitySupport.addModifier(caster, Attributes.STEP_HEIGHT,
            new AttributeModifier(STEP_ID, 2.0, AttributeModifier.Operation.ADD_VALUE));
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Vec3 velocity = caster.getDeltaMovement();

        // Hoof sparks while the goat is actually moving.
        if (ctx.ticksActive() % SCRAPE_INTERVAL == 0
            && velocity.x * velocity.x + velocity.z * velocity.z > 0.0025) {
            level.sendParticles(ParticleTypes.SCRAPE, caster.getX(), caster.getY() + 0.1, caster.getZ(),
                2, 0.35, 0.05, 0.35, 0.05);
        }

        if (!caster.onGround()) {
            // Arm the plunging headbutt once the drop is real.
            if (velocity.y <= PLUNGE_FALL_SPEED) {
                ctx.state().putInt("plunge", 1);
            }
            return;
        }
        if (ctx.state().getIntOr("plunge", 0) != 0) {
            ctx.state().putInt("plunge", 0);
            land(ctx, level);
        }
    }

    /** The landing: dust burst, and the headbutt for every enemy under the drop. */
    private void land(AbilityContext ctx, ServerLevel level) {
        Mob caster = ctx.mob();
        level.sendParticles(ParticleTypes.CLOUD, caster.getX(), caster.getY() + 0.2, caster.getZ(),
            6, 0.5, 0.15, 0.5, 0.08);
        List<LivingEntity> victims = GoatAbilitySupport.enemiesAroundSelf(ctx, PLUNGE_RADIUS, 1.5, 2.0);
        if (victims.isEmpty()) {
            return;
        }
        double damage = caster.getAttributeValue(Attributes.ATTACK_DAMAGE) + PLUNGE_BONUS_DAMAGE;
        for (LivingEntity victim : victims) {
            victim.hurtServer(level, level.damageSources().mobAttack(caster), (float) damage);
            double kx = victim.getX() - caster.getX();
            double kz = victim.getZ() - caster.getZ();
            double kLength = Math.sqrt(kx * kx + kz * kz);
            if (kLength < 0.001) {
                kx = 1.0;
                kz = 0.0;
                kLength = 1.0;
            }
            victim.setDeltaMovement(kx / kLength * 1.2, 0.45, kz / kLength * 1.2);
            victim.hurtMarked = true;
        }
        boolean screaming = caster instanceof Goat goat && goat.isScreamingGoat();
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            screaming ? SoundEvents.GOAT_SCREAMING_RAM_IMPACT : SoundEvents.GOAT_RAM_IMPACT,
            SoundSource.HOSTILE, 1.2f, 1.0f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        GoatAbilitySupport.removeModifier(caster, Attributes.JUMP_STRENGTH, JUMP_ID);
        GoatAbilitySupport.removeModifier(caster, Attributes.SAFE_FALL_DISTANCE, FALL_ID);
        GoatAbilitySupport.removeModifier(caster, Attributes.STEP_HEIGHT, STEP_ID);
    }
}
