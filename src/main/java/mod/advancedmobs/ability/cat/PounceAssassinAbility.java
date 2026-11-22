package mod.advancedmobs.ability.cat;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * Pounce Assassin (Cat ability 3 of 5, mobility / attack).
 *
 * WHAT IT DOES: the cat crouches, tail twitching, then
 * launches in a high arcing pounce with boosted jump
 * strength, landing claws first on its target. The landing
 * hit lands as a crit with bonus damage, and the cat
 * springs away before retaliation.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the cat's current target within 12 blocks
 *   (distance squared 144), plus the cat on the ground (a
 *   pounce starts from a crouch, not mid-air).
 * - The crouch (start() through tick 12): a transient
 *   JUMP_STRENGTH modifier of +0.5 add_value (id
 *   advancedmobs:pounce_assassin_jump) is applied for the
 *   whole activation and removed exactly in end(); the
 *   design's boosted jump strength, and the leap below
 *   reads the live attribute value, so the boost is what
 *   sets the arc. The cat is planted by navigation stops
 *   (never NoAi), hisses once at pitch 1.1, and its tail
 *   twitches: 1 POOF mote behind it every 4 ticks.
 * - The leap (tick 12): one code impulse (the design's
 *   Motion line) written toward the target's live position:
 *   horizontal speed is the distance times 0.09, clamped to
 *   [0.55, 1.15] blocks per tick, and vertical speed is the
 *   cat's live JUMP_STRENGTH value times 1.05 (0.97 with
 *   the crouch boost on a base cat: the high arc). The
 *   phase flips to airborne and the leap tick is recorded.
 *   If the target is gone at tick 12 the leap aims at the
 *   cat's facing instead and simply whiffs (below).
 * - The landing (the first tick at or after 3 ticks past
 *   the leap where the cat is back on the ground): if the
 *   target is alive and within 3.0 blocks, the claws land:
 *   damage is the cat's live ATTACK_DAMAGE attribute value
 *   times 1.5 plus 2.0 (the crit: a base cat hits for 6.5),
 *   dealt through a mob attack source credited to the cat,
 *   with 12 CRIT particles at the target and the cat's hurt
 *   sound at pitch 1.3. If the target is out of reach, the
 *   pounce whiffs: 4 POOF motes at the cat's feet and no
 *   damage. Either way the escape follows immediately:
 *   SPEED II (amplifier 1) for 100 ticks and a spring-away
 *   impulse of 0.7 horizontal directly away from the target
 *   (or along the cat's facing on a targetless whiff) and
 *   0.45 up.
 * - Duration: the activation ends 10 ticks after the
 *   landing, with a hard cap of 80 ticks (a leap that never
 *   lands, down a cliff or into water, still ends).
 * - end(): the JUMP_STRENGTH modifier is removed. The
 *   escape SPEED rides its own 100 tick clock and is not
 *   stripped; it is the getaway, not a stance. Idempotent.
 *
 * WHAT IT LOOKS LIKE: a hiss, a crouched cat with its tail
 * flicking dust, then a small furry comet on a high arc, a
 * burst of crit stars where it lands, and the cat already
 * bouncing away backwards before anything can swing back.
 *
 * PERFORMANCE: the crouch is one navigation stop and at
 * most 1 particle every 4 ticks; the leap is one velocity
 * write; the airborne phase is one onGround check per
 * tick; the landing is one distance check and one damage
 * call. No entity queries anywhere. Particle budget per
 * activation: 3 crouch twitches plus 12 crit (or 4 whiff)
 * plus none for the escape, at most 15 particles.
 *
 * TERRAIN: this ability changes no blocks; the
 * mobGriefing gate does not apply.
 *
 * State tag keys: "phase" (0 crouching, 1 airborne, 2
 * escaped), "leap_tick" and "land_tick" (activation ticks
 * of the leap and the landing).
 */
public final class PounceAssassinAbility implements MobAbility {

    private static final int CROUCH_TICKS = 12;
    private static final int HARD_CAP_TICKS = 80;
    private static final int ESCAPE_LINGER_TICKS = 10;
    private static final double JUMP_BONUS = 0.5;
    private static final int ESCAPE_SPEED_TICKS = 100;
    private static final Identifier JUMP_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "pounce_assassin_jump");

    @Override
    public String id() {
        return "pounce_assassin";
    }

    @Override
    public String displayName() {
        return "Pounce Assassin";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0 && ctx.mob().onGround();
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ctx.state().putInt("phase", 0);
        // The boosted jump strength the design names; the leap reads it live.
        CatAbilitySupport.addModifier(caster, Attributes.JUMP_STRENGTH,
            new AttributeModifier(JUMP_ID, JUMP_BONUS, AttributeModifier.Operation.ADD_VALUE));
        caster.getNavigation().stop();
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            CatAbilitySupport.playCatSound(level, caster.getX(), caster.getY(), caster.getZ(),
                CatAbilitySupport.CatSound.HISS, 1.0f, 1.1f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        int ticks = ctx.ticksActive();
        int phase = ctx.state().getIntOr("phase", 0);
        ServerLevel level = ctx.serverLevel();
        if (phase == 0) {
            // The crouch: planted, tail twitching, until the leap tick.
            caster.getNavigation().stop();
            if (level != null && ticks % 4 == 0) {
                Vec3 look = caster.getLookAngle();
                level.sendParticles(ParticleTypes.POOF,
                    caster.getX() - look.x * 0.4, caster.getY() + 0.25, caster.getZ() - look.z * 0.4,
                    1, 0.08, 0.08, 0.08, 0.02);
            }
            if (ticks >= CROUCH_TICKS) {
                leap(ctx, ticks);
            }
            return;
        }
        if (phase == 1 && ticks - ctx.state().getIntOr("leap_tick", 0) >= 3 && caster.onGround()) {
            land(ctx, ticks);
        }
    }

    /** The leap: one impulse on a high arc toward the target's live position. */
    private void leap(AbilityContext ctx, int ticks) {
        Mob caster = ctx.mob();
        LivingEntity target = ctx.target();
        double dirX;
        double dirZ;
        double distance;
        if (target != null) {
            dirX = target.getX() - caster.getX();
            dirZ = target.getZ() - caster.getZ();
            distance = Math.sqrt(dirX * dirX + dirZ * dirZ);
            if (distance < 1.0e-4) {
                Vec3 look = caster.getLookAngle();
                dirX = look.x;
                dirZ = look.z;
                distance = 1.0;
            }
        } else {
            Vec3 look = caster.getLookAngle();
            dirX = look.x;
            dirZ = look.z;
            distance = 4.0;
        }
        double length = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (length < 1.0e-4) {
            dirX = 1.0;
            dirZ = 0.0;
            length = 1.0;
        }
        double horizontal = Math.max(0.55, Math.min(1.15, distance * 0.09));
        double vertical = caster.getAttributeValue(Attributes.JUMP_STRENGTH) * 1.05;
        caster.setDeltaMovement(dirX / length * horizontal, vertical, dirZ / length * horizontal);
        caster.hurtMarked = true;
        ctx.state().putInt("leap_tick", ticks);
        ctx.state().putInt("phase", 1);
    }

    /** The landing: the crit when the claws connect, then the spring away. */
    private void land(AbilityContext ctx, int ticks) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        LivingEntity target = ctx.target();
        boolean hit = target != null && target.isAlive() && caster.distanceToSqr(target) <= 9.0;
        if (level != null && hit) {
            // The crit: one and a half times the cat's live
            // attack damage (upgrades and effects ride the
            // attribute), plus a flat 2.0 assassin bonus.
            double damage = caster.getAttributeValue(Attributes.ATTACK_DAMAGE) * 1.5 + 2.0;
            target.hurtServer(level, level.damageSources().mobAttack(caster), (float) damage);
            level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY() + 0.6, target.getZ(),
                12, 0.4, 0.4, 0.4, 0.15);
            CatAbilitySupport.playCatSound(level, caster.getX(), caster.getY(), caster.getZ(),
                CatAbilitySupport.CatSound.HURT, 1.0f, 1.3f);
        } else if (level != null) {
            level.sendParticles(ParticleTypes.POOF, caster.getX(), caster.getY() + 0.2, caster.getZ(),
                4, 0.3, 0.15, 0.3, 0.05);
        }
        // The escape: speed burst and a spring away before retaliation.
        caster.addEffect(new MobEffectInstance(MobEffects.SPEED, ESCAPE_SPEED_TICKS, 1));
        double awayX;
        double awayZ;
        if (target != null) {
            awayX = caster.getX() - target.getX();
            awayZ = caster.getZ() - target.getZ();
        } else {
            Vec3 look = caster.getLookAngle();
            awayX = look.x;
            awayZ = look.z;
        }
        double awayLength = Math.sqrt(awayX * awayX + awayZ * awayZ);
        if (awayLength < 1.0e-4) {
            awayX = 1.0;
            awayZ = 0.0;
            awayLength = 1.0;
        }
        caster.setDeltaMovement(awayX / awayLength * 0.7, 0.45, awayZ / awayLength * 0.7);
        caster.hurtMarked = true;
        ctx.state().putInt("land_tick", ticks);
        ctx.state().putInt("phase", 2);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks >= HARD_CAP_TICKS) {
            return true;
        }
        return ctx.state().getIntOr("phase", 0) == 2
            && ticks - ctx.state().getIntOr("land_tick", 0) >= ESCAPE_LINGER_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        CatAbilitySupport.removeModifier(ctx.mob(), Attributes.JUMP_STRENGTH, JUMP_ID);
    }
}
