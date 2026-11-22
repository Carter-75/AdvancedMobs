package mod.advancedmobs.ability.slime;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * Superball Mode (Slime ability 4 of 5, mobility).
 *
 * WHAT IT DOES: the slime's physics go feral for 10 seconds:
 * its bounciness is maxed and its air drag slashed, so it
 * ricochets off walls, floors, and enemies in a pinball
 * streak of ITEM_SLIME. Every bounce off an enemy deals
 * contact knockback, and the slime crosses arenas in seconds
 * along paths no one can predict or intercept.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists and is within 14
 *   blocks (distance squared 196).
 * - Duration: exactly 200 ticks (10 seconds) from start().
 * - The physics (all transient modifiers added in start(),
 *   removed in end(), ids under
 *   advancedmobs:superball_*):
 *     BOUNCINESS          add_value +1.0  (0 to the 1.0 cap: every
 *                                          collision with a block or a
 *                                          collidable entity returns the
 *                                          slime's full velocity, and the
 *                                          collisions emit the vanilla
 *                                          bounce game event)
 *     AIR_DRAG_MODIFIER   add_value -0.9  (1.0 to 0.1: the slime keeps
 *                                          nine tenths of the speed air
 *                                          drag would have eaten, so
 *                                          ricochets carry across arenas)
 *     FRICTION_MODIFIER   add_value -0.75 (1.0 to 0.25: ground contact
 *                                          barely slows it between bounces)
 *     ATTACK_KNOCKBACK    add_value +1.5  (the slime's own attacks, the
 *                                          contact hits it lands as it
 *                                          caroms into enemies mid-bounce,
 *                                          knock their victims back hard:
 *                                          the design's contact knockback)
 * - The bounce feedback: the tick code remembers last tick's
 *   velocity in the state tag. A bounce reads as a velocity
 *   reversal: the X or Z component flipping sign against a
 *   previous magnitude above 0.25 (a wall or an enemy), or the
 *   Y component flipping from below -0.4 to above 0.1 (a floor
 *   bounce). A detected bounce plays a SLIME_SQUISH (volume
 *   0.6, pitch 1.2 plus a random 0.3) and sheds 3 ITEM_SLIME
 *   flecks, at most once per 6 ticks so a vibrating corner
 *   cannot spam it.
 *
 * WHAT IT LOOKS LIKE: no model change; the show is the motion
 * itself, a slime pinballing around the fight at speeds
 * slimes never reach, shedding 1 ITEM_SLIME fleck every 2
 * ticks while it moves (horizontal speed squared above 0.01)
 * and bursting 3 more with a bright squish at every ricochet.
 *
 * PERFORMANCE: tick() is velocity reads, one comparison set,
 * and the 2-tick trail pulse; there are no entity queries at
 * all (the bounciness attribute does the colliding, in the
 * vanilla physics step). The four modifiers are resolved at
 * start() and removed at end(), never touched per tick.
 * Particle budget per activation, hard caps by construction:
 * trail at most 100 pulses times 1 = 100, bounces at most 33
 * events times 3 = 99, total at most 199 particles.
 *
 * CLEANUP: end() removes all four modifiers by id. Nothing
 * else was created; the slime's physics return to vanilla the
 * tick the mode ends, wherever it has ricocheted to.
 *
 * State tag keys: "prev_vx", "prev_vy", "prev_vz" (last
 * tick's velocity), "last_bounce" (tick of the last bounce
 * feedback).
 */
public final class SuperballModeAbility implements MobAbility {

    static final int DURATION_TICKS = 200;
    private static final int BOUNCE_FEEDBACK_INTERVAL = 6;
    private static final Identifier BOUNCINESS_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "superball_bounciness");
    private static final Identifier AIR_DRAG_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "superball_air_drag");
    private static final Identifier FRICTION_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "superball_friction");
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "superball_knockback");

    @Override
    public String id() {
        return "superball_mode";
    }

    @Override
    public String displayName() {
        return "Superball Mode";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return ctx.target() != null && ctx.mob().distanceToSqr(ctx.target()) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        SlimeAbilitySupport.addModifier(mob, Attributes.BOUNCINESS,
            new AttributeModifier(BOUNCINESS_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        SlimeAbilitySupport.addModifier(mob, Attributes.AIR_DRAG_MODIFIER,
            new AttributeModifier(AIR_DRAG_ID, -0.9, AttributeModifier.Operation.ADD_VALUE));
        SlimeAbilitySupport.addModifier(mob, Attributes.FRICTION_MODIFIER,
            new AttributeModifier(FRICTION_ID, -0.75, AttributeModifier.Operation.ADD_VALUE));
        SlimeAbilitySupport.addModifier(mob, Attributes.ATTACK_KNOCKBACK,
            new AttributeModifier(KNOCKBACK_ID, 1.5, AttributeModifier.Operation.ADD_VALUE));
        Vec3 velocity = mob.getDeltaMovement();
        ctx.state().putDouble("prev_vx", velocity.x);
        ctx.state().putDouble("prev_vy", velocity.y);
        ctx.state().putDouble("prev_vz", velocity.z);
        ctx.state().putInt("last_bounce", -BOUNCE_FEEDBACK_INTERVAL);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        int ticks = ctx.ticksActive();
        Vec3 velocity = mob.getDeltaMovement();
        double prevVx = ctx.state().getDoubleOr("prev_vx", velocity.x);
        double prevVy = ctx.state().getDoubleOr("prev_vy", velocity.y);
        double prevVz = ctx.state().getDoubleOr("prev_vz", velocity.z);

        // The pinball streak: a fleck every 2 ticks while moving.
        if (ticks % 2 == 0 && velocity.horizontalDistanceSqr() > 0.01) {
            level.sendParticles(ParticleTypes.ITEM_SLIME, mob.getX(), mob.getY() + 0.4, mob.getZ(),
                1, mob.getBbWidth() * 0.35, 0.3, mob.getBbWidth() * 0.35, 0.03);
        }

        // The ricochet feedback: a velocity reversal is a bounce.
        boolean bounced = (prevVx * velocity.x < 0.0 && Math.abs(prevVx) > 0.25)
            || (prevVz * velocity.z < 0.0 && Math.abs(prevVz) > 0.25)
            || (prevVy < -0.4 && velocity.y > 0.1);
        if (bounced && ticks - ctx.state().getIntOr("last_bounce", -BOUNCE_FEEDBACK_INTERVAL)
            >= BOUNCE_FEEDBACK_INTERVAL) {
            ctx.state().putInt("last_bounce", ticks);
            level.sendParticles(ParticleTypes.ITEM_SLIME, mob.getX(), mob.getY() + 0.4, mob.getZ(),
                3, mob.getBbWidth() * 0.45, 0.35, mob.getBbWidth() * 0.45, 0.08);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.SLIME_SQUISH,
                SoundSource.HOSTILE, 0.6f, 1.2f + ctx.random().nextFloat() * 0.3f);
        }

        ctx.state().putDouble("prev_vx", velocity.x);
        ctx.state().putDouble("prev_vy", velocity.y);
        ctx.state().putDouble("prev_vz", velocity.z);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        SlimeAbilitySupport.removeModifier(mob, Attributes.BOUNCINESS, BOUNCINESS_ID);
        SlimeAbilitySupport.removeModifier(mob, Attributes.AIR_DRAG_MODIFIER, AIR_DRAG_ID);
        SlimeAbilitySupport.removeModifier(mob, Attributes.FRICTION_MODIFIER, FRICTION_ID);
        SlimeAbilitySupport.removeModifier(mob, Attributes.ATTACK_KNOCKBACK, KNOCKBACK_ID);
    }
}
