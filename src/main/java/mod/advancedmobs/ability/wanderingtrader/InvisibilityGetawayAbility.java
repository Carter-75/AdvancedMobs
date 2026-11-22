package mod.advancedmobs.ability.wanderingtrader;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Invisibility Getaway (Wandering Trader ability 2 of 5,
 * defense / mobility).
 *
 * WHAT IT DOES: cornered, the trader does what its kind has
 * always done. It chugs a potion in a swirl of magic, vanishes
 * mid-sip, and a faint trail of poof is all that shows where
 * it went. A heartbeat later it reappears a safe distance
 * away, already at a jog with its speed boosted, doing its
 * best to look like the whole fight is beneath its notice.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the trader's current target within 10 blocks (distance
 *   squared 100): this is the cornered escape, not an opener.
 * - The drink (start()): WANDERING_TRADER_DRINK_POTION (the
 *   trader's real vanilla potion sound) at volume 1.0, pitch
 *   1.0, with 12 WITCH particles swirling at its chest (the
 *   design's potion swirl; EFFECT itself needs a spell color
 *   option in 26.2, so the witch motes carry the look). The
 *   trader gains INVISIBILITY
 *   for 200 ticks and SPEED II (amplifier 1) for 200 ticks.
 *   The escape direction is locked in start(): directly away
 *   from the target's position at the sip (falling back to
 *   the reverse of the trader's own facing when there is no
 *   target), stored as a unit vector in the state tag.
 * - The trail (ticks 1 to 10): 2 POOF particles per tick at
 *   the trader's feet (the design's faint trail; the trader
 *   itself is invisible, the puffs are the tell). The
 *   trader's navigation is stopped each of these ticks so
 *   its vanilla fleeing does not drag it off the escape line
 *   before the jump (navigation stops, never NoAi).
 * - The jump (tick 10, exactly once, flagged in the state
 *   tag): the trader teleports (the design's "/teleport in
 *   code") along the locked escape direction. Candidate
 *   landings are tried in order: distances 10, 8, and 6
 *   blocks, each at lateral offsets 0, +2, and -2 blocks
 *   (9 candidates); each candidate's ground height comes
 *   from one bounded surface scan, and the first candidate
 *   whose cell fits the trader's bounding box (Level.
 *   noCollision) wins. If none fits, the trader stays where
 *   it is (the invisibility and speed still carry the
 *   getaway). At the old position: WANDERING_TRADER_
 *   DISAPPEARED plus 10 POOF. At the landing:
 *   WANDERING_TRADER_REAPPEARED plus 10 POOF. INVISIBILITY
 *   is removed the same tick, so the trader reappears
 *   visibly at the landing, at a jog: SPEED II keeps
 *   running for its full 200 ticks unless end() strips it
 *   first.
 * - Duration: 100 ticks (5 seconds), hard finish. end()
 *   removes INVISIBILITY and SPEED from the trader exactly,
 *   so a terminated or naturally finished getaway never
 *   leaves the trader permanently invisible or hasted; on a
 *   natural finish the jump has long since removed the
 *   invisibility and only the speed is stripped.
 *
 * WHAT IT LOOKS LIKE: a gulp, a purple swirl, and the trader
 * is simply not there anymore, just a dotted line of little
 * puffs crossing the fight. Then a pop of white smoke ten
 * blocks away and the trader is back, trotting off with its
 * nose in the air.
 *
 * PERFORMANCE: start() is 2 effect applications, 1 sound,
 * and 12 particles. Ticks 1 to 10 are one navigation stop
 * and 2 particle sends each; the jump tick adds at most 9
 * surface scans of at most 8 block reads plus 9 collision
 * checks; ticks 11 to 100 do nothing at all. No entity
 * queries anywhere. Particle budget per activation: 12 at
 * start, 20 of trail, 20 at the jump, total at most 52
 * particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply. The teleport is an entity move, not a block
 * change.
 *
 * State tag keys: "dir_x", "dir_z" (the locked escape
 * direction, unit length), "jumped" (whether the teleport
 * has fired).
 */
public final class InvisibilityGetawayAbility implements MobAbility {

    private static final int DURATION_TICKS = 100;
    private static final int JUMP_TICK = 10;
    private static final int EFFECT_TICKS = 200;
    private static final double[] JUMP_DISTANCES = {10.0, 8.0, 6.0};
    private static final double[] JUMP_LATERALS = {0.0, 2.0, -2.0};

    @Override
    public String id() {
        return "invisibility_getaway";
    }

    @Override
    public String displayName() {
        return "Invisibility Getaway";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 100.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        state.putBoolean("jumped", false);

        // Lock the escape direction: directly away from the target.
        LivingEntity target = ctx.target();
        double dirX;
        double dirZ;
        if (target != null) {
            dirX = caster.getX() - target.getX();
            dirZ = caster.getZ() - target.getZ();
        } else {
            Vec3 look = caster.getLookAngle();
            dirX = -look.x;
            dirZ = -look.z;
        }
        double length = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (length < 1.0e-4) {
            dirX = 1.0;
            dirZ = 0.0;
        } else {
            dirX /= length;
            dirZ /= length;
        }
        state.putDouble("dir_x", dirX);
        state.putDouble("dir_z", dirZ);

        // The drink: vanish and pick up the pace.
        caster.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, EFFECT_TICKS, 0));
        caster.addEffect(new MobEffectInstance(MobEffects.SPEED, EFFECT_TICKS, 1));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.WANDERING_TRADER_DRINK_POTION, SoundSource.HOSTILE, 1.0f, 1.0f);
            level.sendParticles(ParticleTypes.WITCH, caster.getX(), caster.getY() + 1.2, caster.getZ(),
                12, 0.3, 0.4, 0.3, 0.05);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();

        if (ticks < JUMP_TICK) {
            // The faint trail: hold the line, puff at the feet.
            caster.getNavigation().stop();
            level.sendParticles(ParticleTypes.POOF, caster.getX(), caster.getY() + 0.2, caster.getZ(),
                2, 0.15, 0.1, 0.15, 0.02);
            return;
        }
        if (ticks == JUMP_TICK && !ctx.state().getBooleanOr("jumped", false)) {
            jump(ctx, level);
        }
        // Ticks after the jump do nothing: the speed carries the getaway.
    }

    /** The teleport jump along the locked escape direction (see the class comment). */
    private void jump(AbilityContext ctx, ServerLevel level) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        state.putBoolean("jumped", true);

        double dirX = state.getDoubleOr("dir_x", 1.0);
        double dirZ = state.getDoubleOr("dir_z", 0.0);
        // The lateral axis: the escape direction rotated 90 degrees.
        double latX = -dirZ;
        double latZ = dirX;

        double oldX = caster.getX();
        double oldY = caster.getY();
        double oldZ = caster.getZ();
        level.playSound(null, oldX, oldY, oldZ, SoundEvents.WANDERING_TRADER_DISAPPEARED,
            SoundSource.HOSTILE, 1.0f, 1.0f);
        level.sendParticles(ParticleTypes.POOF, oldX, oldY + 0.6, oldZ, 10, 0.3, 0.4, 0.3, 0.05);

        double halfWidth = caster.getBbWidth() / 2.0;
        boolean landed = false;
        outer:
        for (double distance : JUMP_DISTANCES) {
            for (double lateral : JUMP_LATERALS) {
                double candidateX = oldX + dirX * distance + latX * lateral;
                double candidateZ = oldZ + dirZ * distance + latZ * lateral;
                int groundY = WanderingTraderAbilitySupport.findGroundY(level, candidateX, oldY, candidateZ);
                AABB box = new AABB(candidateX - halfWidth, groundY, candidateZ - halfWidth,
                    candidateX + halfWidth, groundY + caster.getBbHeight(), candidateZ + halfWidth);
                if (level.noCollision(caster, box)) {
                    caster.snapTo(candidateX, groundY, candidateZ, caster.getYRot(), 0.0f);
                    landed = true;
                    break outer;
                }
            }
        }

        // Reappear visibly at the landing (or where it stands, if
        // every candidate was blocked): the jog is the speed's job.
        caster.removeEffect(MobEffects.INVISIBILITY);
        double newX = landed ? caster.getX() : oldX;
        double newY = landed ? caster.getY() : oldY;
        double newZ = landed ? caster.getZ() : oldZ;
        level.playSound(null, newX, newY, newZ, SoundEvents.WANDERING_TRADER_REAPPEARED,
            SoundSource.HOSTILE, 1.0f, 1.0f);
        level.sendParticles(ParticleTypes.POOF, newX, newY + 0.6, newZ, 10, 0.3, 0.4, 0.3, 0.05);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.removeEffect(MobEffects.INVISIBILITY);
        caster.removeEffect(MobEffects.SPEED);
    }
}
