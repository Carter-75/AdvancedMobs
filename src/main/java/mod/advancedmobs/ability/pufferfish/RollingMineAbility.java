package mod.advancedmobs.ability.pufferfish;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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
import net.minecraft.world.entity.animal.fish.Pufferfish;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Rolling Mine (Pufferfish ability 4 of 5, mobility /
 * attack).
 *
 * WHAT IT DOES: the inflated pufferfish tucks in and
 * becomes a bouncing hazard. For three seconds it
 * ricochets around the pool at gathering speed,
 * caroming off walls, the floor, and enemies, every
 * bounce off an enemy a heavy spiked hit and a poof
 * of bubbles. It steers only loosely at this speed,
 * trading aim for chaos, and at the end it deflates
 * safely back to small instead of bursting.
 *
 * MECHANICS (the spec; fix bugs against these
 * numbers):
 * - Trigger (canTrigger): the framework's combat
 *   gate, plus the pufferfish's current target within
 *   16 blocks (distance squared 256), plus the
 *   pufferfish in water (the mine rolls in water; on
 *   land it would just be a spiky ball in the air).
 * - The mine form (start()): the real synced puff
 *   state is forced to Pufferfish.STATE_FULL and
 *   re-asserted on any roll tick where the vanilla
 *   puff goal has wandered out of it (the Armadillo
 *   state refresh pattern; setPuffState is only
 *   called when the state differs). Four transient
 *   modifiers ride the activation, all removed in
 *   end(): SCALE +1.0 add_multiplied_base (id
 *   advancedmobs:rolling_mine_scale; the ball is
 *   double size on top of the full puff box),
 *   BOUNCINESS +0.9 add_value (id advancedmobs:
 *   rolling_mine_bounciness; the design's near-1
 *   bounciness line, and because it is a real
 *   attribute, vanilla emits the bounce game event
 *   for its collisions on its own), EXPLOSION_
 *   KNOCKBACK_RESISTANCE +1.0 add_value (id
 *   advancedmobs:rolling_mine_blast; the design's
 *   explosion knockback line, blasts do not knock
 *   the mine off its line), and SAFE_FALL_DISTANCE
 *   +30.0 add_value (id advancedmobs:rolling_mine_
 *   fall; beaching drops never hurt the mine).
 *   PUFFER_FISH_BLOW_UP plays at volume 1.2.
 * - Phase 0, the tuck (ticks 1 to 6): navigation
 *   stopped every tick (never NoAi), 3 BUBBLE at the
 *   fish every 2 ticks.
 * - Phase 1, the roll (ticks 7 to 66, 60 roll
 *   ticks): the direction is locked at start() toward
 *   the target ("dx", "dz", normalized). Each roll
 *   tick writes the horizontal velocity along the
 *   stored direction at the gathering speed: 0.5
 *   blocks per tick on the first roll tick rising
 *   linearly to 0.85 at roll tick 20, flat after.
 *   The vertical is the bounce: when the fish is on
 *   the ground (the pool floor) its vertical velocity
 *   is set to +0.55, a hop off the bottom; when it
 *   reports a vertical collision moving up (a ceiling
 *   or the surface film) its vertical velocity is set
 *   to -0.4; otherwise the vertical velocity decays
 *   by 0.9 per tick, so the ball porpoises through
 *   the water column instead of flying level. If the
 *   fish leaves the water mid-roll the drive stops
 *   (no velocity writes) until it is back in.
 * - The ricochet: each roll tick compares the actual
 *   horizontal displacement since the last tick
 *   against the intended step, exactly the Armadillo
 *   Rollout check. When the fish reports a horizontal
 *   collision, the direction component whose progress
 *   was blocked (less than a quarter of its intended
 *   step) flips sign; if both components made
 *   progress the dominant component flips (a graze);
 *   if neither did, both flip (a corner). The flipped
 *   direction is stored back, so the mine keeps
 *   caroming until the roll clock runs out.
 * - The enemy bounce (per roll tick, one bounded
 *   query, radius 2.0 around the fish, 1.5 below to
 *   2.0 above): the first enemy (the package enemy
 *   rule) whose last bounce this activation was at
 *   least 10 ticks ago takes 5.0 flat damage through
 *   the vanilla sting source credited to the
 *   pufferfish (the design's typed contact damage;
 *   the pufferfish has no ATTACK_DAMAGE instance, see
 *   the provider comment), gains POISON I for 60
 *   ticks, and is launched 1.2 horizontally directly
 *   away from the mine and 0.45 up with hurtMarked
 *   set, bursting 6 BUBBLE plus 4 POOF at its chest,
 *   with PUFFER_FISH_STING at pitch 1.2. The mine's
 *   own direction reflects across the ball-to-victim
 *   axis, so a bounce off an enemy visibly caroms it
 *   away at an angle. One bounce per tick keeps the
 *   carom readable. Bounce stamps live in the state
 *   tag's "hit" list ("id" plus "tick"), pruned to
 *   fresh stamps past 24 entries.
 * - The roll ends at roll tick 60 or the hard cap of
 *   tick 80. The deflation is in end(): the puff
 *   state is set to STATE_SMALL (the design's safe
 *   deflation; vanilla will re-inflate the fish on
 *   its own schedule afterward) with a closing burst
 *   of 8 POOF, instead of restoring the state found
 *   at start the way Full Inflation does.
 *
 * WHAT IT LOOKS LIKE: a pufferfish snapping into a
 * double-sized spiked ball, then a living naval mine
 * pinballing around the pool, porpoising off the
 * floor, shedding bubble poofs every time it caroms
 * off an enemy, bodies bouncing away from each
 * impact, until it sighs back down to a small fish.
 *
 * PERFORMANCE: the tuck does one navigation stop and
 * one particle emission every 2 ticks. The roll does
 * one velocity write, one bounded radius-2.0 entity
 * query, and a state-tag list pass per tick. Particle
 * budget per activation: tuck 3 times 3 = 9, roll
 * trail 30 times 2 = 60, enemy bounces 10 particles
 * each and at most one per tick over 60 roll ticks =
 * 600 theoretical, realistically a handful of
 * enemies; closing poof 8; stated cap 690.
 *
 * TERRAIN: this ability changes no blocks; the gate
 * does not apply. The bounces run through the real
 * BOUNCINESS attribute and code velocity, never
 * through block edits.
 *
 * CLEANUP: end() removes all four transient modifiers
 * by id and deflates the fish to STATE_SMALL with the
 * closing poof (a terminated mine deflates the same
 * way; it never bursts). Victim poison rides its own
 * 60-tick clock out.
 *
 * State tag keys: "dx", "dz" (current roll direction,
 * flipped by ricochets), "lx", "lz" (position at the
 * end of the last roll tick), "hit" (list of
 * compounds: "id", "tick", one per bounced enemy).
 */
public final class RollingMineAbility implements MobAbility {

    private static final int TUCK_TICKS = 6;
    private static final int ROLL_TICKS = 60;
    private static final int HARD_CAP_TICKS = 80;
    private static final double ROLL_START_SPEED = 0.5;
    private static final double ROLL_TOP_SPEED = 0.85;
    private static final int ROLL_RAMP_TICKS = 20;
    private static final double FLOOR_BOUNCE = 0.55;
    private static final double CEILING_BOUNCE = -0.4;
    private static final double HIT_RADIUS = 2.0;
    private static final float BOUNCE_DAMAGE = 5.0f;
    private static final int BOUNCE_COOLDOWN_TICKS = 10;
    private static final double LAUNCH_HORIZONTAL = 1.2;
    private static final double LAUNCH_VERTICAL = 0.45;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "rolling_mine_scale");
    private static final Identifier BOUNCINESS_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "rolling_mine_bounciness");
    private static final Identifier BLAST_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "rolling_mine_blast");
    private static final Identifier FALL_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "rolling_mine_fall");

    @Override
    public String id() {
        return "rolling_mine";
    }

    @Override
    public String displayName() {
        return "Rolling Mine";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().isInWater()
            && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        state.put("hit", new ListTag());

        // Lock the opening line at the target.
        double dx = 0.0;
        double dz = 1.0;
        LivingEntity target = ctx.target();
        if (target != null) {
            dx = target.getX() - caster.getX();
            dz = target.getZ() - caster.getZ();
        }
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.001) {
            Vec3 look = caster.getLookAngle();
            dx = look.x;
            dz = look.z;
            length = Math.sqrt(dx * dx + dz * dz);
            if (length < 0.001) {
                dx = 0.0;
                dz = 1.0;
                length = 1.0;
            }
        }
        state.putDouble("dx", dx / length);
        state.putDouble("dz", dz / length);
        state.putDouble("lx", caster.getX());
        state.putDouble("lz", caster.getZ());

        // The mine kit (see the class comment).
        PufferfishAbilitySupport.addModifier(caster, Attributes.SCALE,
            new AttributeModifier(SCALE_ID, 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        PufferfishAbilitySupport.addModifier(caster, Attributes.BOUNCINESS,
            new AttributeModifier(BOUNCINESS_ID, 0.9, AttributeModifier.Operation.ADD_VALUE));
        PufferfishAbilitySupport.addModifier(caster, Attributes.EXPLOSION_KNOCKBACK_RESISTANCE,
            new AttributeModifier(BLAST_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        PufferfishAbilitySupport.addModifier(caster, Attributes.SAFE_FALL_DISTANCE,
            new AttributeModifier(FALL_ID, 30.0, AttributeModifier.Operation.ADD_VALUE));

        if (caster instanceof Pufferfish pufferfish) {
            pufferfish.setPuffState(Pufferfish.STATE_FULL);
        }
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.PUFFER_FISH_BLOW_UP, SoundSource.HOSTILE, 1.2f, 1.0f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        CompoundTag state = ctx.state();
        int ticks = ctx.ticksActive();

        if (ticks <= TUCK_TICKS) {
            caster.getNavigation().stop();
            if (level != null && ticks % 2 == 0) {
                level.sendParticles(ParticleTypes.BUBBLE, caster.getX(), caster.getY() + 0.3, caster.getZ(),
                    3, 0.4, 0.3, 0.4, 0.05);
            }
            return;
        }
        if (level == null || !caster.isInWater()) {
            return;
        }
        if (caster instanceof Pufferfish pufferfish
            && pufferfish.getPuffState() != Pufferfish.STATE_FULL) {
            pufferfish.setPuffState(Pufferfish.STATE_FULL);
        }

        int rollTick = ticks - TUCK_TICKS;
        double ramp = Math.min(1.0, rollTick / (double) ROLL_RAMP_TICKS);
        double speed = ROLL_START_SPEED + (ROLL_TOP_SPEED - ROLL_START_SPEED) * ramp;
        double dx = state.getDoubleOr("dx", 0.0);
        double dz = state.getDoubleOr("dz", 1.0);

        // The ricochet: read last tick's actual progress
        // against its intended step before writing this one.
        double movedX = caster.getX() - state.getDoubleOr("lx", caster.getX());
        double movedZ = caster.getZ() - state.getDoubleOr("lz", caster.getZ());
        if (caster.horizontalCollision) {
            boolean xBlocked = Math.abs(movedX) < Math.abs(dx) * speed * 0.25;
            boolean zBlocked = Math.abs(movedZ) < Math.abs(dz) * speed * 0.25;
            if (xBlocked) {
                dx = -dx;
            }
            if (zBlocked) {
                dz = -dz;
            }
            if (!xBlocked && !zBlocked) {
                // A graze that still registered a collision:
                // flip the dominant component so the mine
                // leaves the wall instead of grinding it.
                if (Math.abs(dx) >= Math.abs(dz)) {
                    dx = -dx;
                } else {
                    dz = -dz;
                }
            }
            double flipLength = Math.sqrt(dx * dx + dz * dz);
            if (flipLength > 0.001) {
                dx /= flipLength;
                dz /= flipLength;
            }
            state.putDouble("dx", dx);
            state.putDouble("dz", dz);
        }

        // The drive: code velocity along the stored line,
        // with the vertical bounce off floor and ceiling.
        caster.getNavigation().stop();
        Vec3 velocity = caster.getDeltaMovement();
        double vy;
        if (caster.onGround()) {
            vy = FLOOR_BOUNCE;
        } else if (caster.verticalCollision && velocity.y > 0.0) {
            vy = CEILING_BOUNCE;
        } else {
            vy = velocity.y * 0.9;
        }
        caster.setDeltaMovement(dx * speed, vy, dz * speed);
        caster.hurtMarked = true;

        // The trail: bubbles off the tumbling mine every 2 ticks.
        if (ticks % 2 == 0) {
            level.sendParticles(ParticleTypes.BUBBLE, caster.getX(), caster.getY() + 0.3, caster.getZ(),
                2, 0.5, 0.4, 0.5, 0.05);
        }

        // The enemy bounce: one bounded query per roll tick.
        List<LivingEntity> victims = PufferfishAbilitySupport.enemiesInBox(
            ctx, caster.getX(), caster.getY(), caster.getZ(), HIT_RADIUS, 1.5, 2.0);
        for (LivingEntity victim : victims) {
            if (bounceStamp(state, victim.getUUID(), ticks)) {
                bounceOff(ctx, level, victim);
                // One bounce per tick keeps the carom readable.
                break;
            }
        }

        state.putDouble("lx", caster.getX());
        state.putDouble("lz", caster.getZ());
    }

    /**
     * Whether the given enemy may be bounced now; records the
     * bounce stamp when it may. Stamps older than the bounce
     * cooldown are refreshed in place; the list is pruned to
     * fresh stamps when it grows past 24 entries.
     */
    private boolean bounceStamp(CompoundTag state, UUID id, int ticks) {
        ListTag hit = state.getListOrEmpty("hit");
        for (int i = 0; i < hit.size(); i++) {
            CompoundTag entry = hit.getCompoundOrEmpty(i);
            UUID hitId = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id.equals(hitId)) {
                if (ticks - entry.getIntOr("tick", -BOUNCE_COOLDOWN_TICKS) < BOUNCE_COOLDOWN_TICKS) {
                    return false;
                }
                entry.putInt("tick", ticks);
                state.put("hit", hit);
                return true;
            }
        }
        if (hit.size() > 24) {
            ListTag fresh = new ListTag();
            for (int i = 0; i < hit.size(); i++) {
                CompoundTag entry = hit.getCompoundOrEmpty(i);
                if (ticks - entry.getIntOr("tick", 0) < BOUNCE_COOLDOWN_TICKS) {
                    fresh.add(entry);
                }
            }
            hit = fresh;
        }
        CompoundTag entry = new CompoundTag();
        entry.store("id", UUIDUtil.CODEC, id);
        entry.putInt("tick", ticks);
        hit.add(entry);
        state.put("hit", hit);
        return true;
    }

    /** Bounces one enemy off the mine and caroms the mine away from it. */
    private void bounceOff(AbilityContext ctx, ServerLevel level, LivingEntity victim) {
        Mob caster = ctx.mob();
        victim.hurtServer(level, level.damageSources().sting(caster), BOUNCE_DAMAGE);
        victim.addEffect(new MobEffectInstance(MobEffects.POISON, 60, 0));
        double awayX = victim.getX() - caster.getX();
        double awayZ = victim.getZ() - caster.getZ();
        double awayLength = Math.sqrt(awayX * awayX + awayZ * awayZ);
        if (awayLength < 1.0e-4) {
            awayX = 1.0;
            awayZ = 0.0;
            awayLength = 1.0;
        }
        awayX /= awayLength;
        awayZ /= awayLength;
        victim.setDeltaMovement(awayX * LAUNCH_HORIZONTAL, LAUNCH_VERTICAL, awayZ * LAUNCH_HORIZONTAL);
        victim.hurtMarked = true;

        level.sendParticles(ParticleTypes.BUBBLE,
            victim.getX(), victim.getY() + 0.5, victim.getZ(),
            6, 0.4, 0.4, 0.4, 0.08);
        level.sendParticles(ParticleTypes.POOF,
            victim.getX(), victim.getY() + 0.5, victim.getZ(),
            4, 0.4, 0.4, 0.4, 0.08);
        level.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
            SoundEvents.PUFFER_FISH_STING, SoundSource.HOSTILE, 0.9f, 1.2f);

        // The carom: reverse the direction component along
        // the mine-to-victim axis, so the mine leaves the
        // victim at a reflected angle.
        CompoundTag state = ctx.state();
        double dx = state.getDoubleOr("dx", 0.0);
        double dz = state.getDoubleOr("dz", 1.0);
        double dot = dx * awayX + dz * awayZ;
        dx -= 2.0 * dot * awayX;
        dz -= 2.0 * dot * awayZ;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length > 0.001) {
            state.putDouble("dx", dx / length);
            state.putDouble("dz", dz / length);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= TUCK_TICKS + ROLL_TICKS
            || ctx.ticksActive() >= HARD_CAP_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        PufferfishAbilitySupport.removeModifier(caster, Attributes.SCALE, SCALE_ID);
        PufferfishAbilitySupport.removeModifier(caster, Attributes.BOUNCINESS, BOUNCINESS_ID);
        PufferfishAbilitySupport.removeModifier(caster, Attributes.EXPLOSION_KNOCKBACK_RESISTANCE, BLAST_ID);
        PufferfishAbilitySupport.removeModifier(caster, Attributes.SAFE_FALL_DISTANCE, FALL_ID);
        // The safe deflation (see the class comment): the
        // mine sighs back down to small instead of bursting.
        if (caster instanceof Pufferfish pufferfish) {
            pufferfish.setPuffState(Pufferfish.STATE_SMALL);
        }
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.sendParticles(ParticleTypes.POOF, caster.getX(), caster.getY() + 0.3, caster.getZ(),
                8, 0.5, 0.4, 0.5, 0.05);
        }
    }
}
