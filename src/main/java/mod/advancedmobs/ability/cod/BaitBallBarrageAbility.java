package mod.advancedmobs.ability.cod;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.fish.Cod;

import java.util.UUID;

/**
 * Bait Ball Barrage (Cod ability 1 of 5, summon / attack).
 *
 * WHAT IT DOES: the cod wheels in one tight circle,
 * trailing bubbles and splashes, and four more cod fold
 * out of the swirl around its target, caster included:
 * a five-fish bait ball that spins around the enemy and
 * batters it from every side, each landed hit flashing
 * silver with crit particles. When the ball breaks up,
 * the four called cod scatter in a burst of bubble_pop
 * and vanish; the caster stays and keeps fighting.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the cod's current target within 16 blocks
 *   (distance squared 256), plus the cod in water (a
 *   bait ball is a swimming formation; a beached cod
 *   never starts one).
 * - The call (start()): FISH_SWIM at volume 1.0, pitch
 *   1.3, 12 BUBBLE plus 6 SPLASH at the caster, then 4
 *   cod spawned on a radius 1.5 ring around the target
 *   at the target's own height, created with
 *   EntitySpawnReason.MOB_SUMMONED and finalizeSpawn
 *   like every other summon in this mod (which for a
 *   schooling fish only marks it leader of an empty
 *   school; see the provider comment). Team assignment,
 *   per member, both directions plus the registry: the
 *   caster's data gains the member as an ally, the
 *   member's data gains the caster, and the pair is
 *   registered in SummonerTracker (which also tags the
 *   member umw_summoned for the natural spawn blocker).
 *   Summoned cod have no vanilla combat AI worth the
 *   name, so the whole fight is the code steering below,
 *   the Schooling Surge pattern.
 * - Duration: 240 ticks (12 seconds), hard finish.
 * - The opening circle (ticks 1 to 20): the caster is
 *   steered around a radius 1.2 circle centered on its
 *   own start position at 0.5 blocks per tick (angle
 *   advances 0.42 radians per tick), trailing 2 BUBBLE
 *   plus 1 SPLASH per tick: the design's darting circle
 *   that calls the school in.
 * - The ball (one pass every 2 ticks, from tick 2): the
 *   ball's center is the target's live position, or the
 *   caster's own position when the target is dead or
 *   lost (the ball mills around its caller instead of
 *   scattering). Five anchors sit on a radius 1.6 ring
 *   around the center at the center's own height plus
 *   0.2, at angles (slot times 72 degrees plus the
 *   activation ticks times 0.08 radians), so the ball
 *   spins a full turn about every 79 ticks. Slot 0 is
 *   the caster; slots 1 to 4 are the summoned cod in
 *   tracked order. Every living fish is steered straight
 *   at its anchor at 0.55 blocks per tick (the package
 *   steering helper, one velocity write per fish per
 *   pass).
 * - The battering (same pass): when a summoned member
 *   is within 1.4 blocks of the live target and at
 *   least 15 ticks have passed since that member's
 *   last hit (the tick is kept per member in its
 *   tracked entry, key "hit"), the hit lands: 1.5 flat
 *   damage as a mob attack credited to the member (the
 *   cod has no ATTACK_DAMAGE instance; see the provider
 *   comment), and 3 CRIT particles at the target, the
 *   design's small silver flash per hit. A member can
 *   therefore hit at most 16 times over the activation
 *   and the school at most 64 times. The caster does
 *   not batter: it holds the ball together and fights
 *   with its own other tools.
 * - end(): each surviving summoned cod bursts into 6
 *   BUBBLE_POP particles at its position and is
 *   discarded, and the alliance records are removed on
 *   both sides for every tracked member. The school is
 *   a temporary summon, so it does not outlive the
 *   activation (the Schooling Surge and Pod Call
 *   precedent). If the caster dies first, the framework
 *   termination path runs this same end(), so the
 *   school never outlives its caller. SummonerTracker
 *   records are deliberately not unregistered, matching
 *   the other summons in this mod: that registry cleans
 *   up on the member's own removal.
 *
 * WHAT IT LOOKS LIKE: a swirl of bubbles and splashes,
 * four cod folding out of it into a spinning silver
 * ring around the enemy, crit sparks flashing around
 * the victim as the ring turns, then a pop of bubbles
 * as the extra fish vanish.
 *
 * PERFORMANCE: start() does 4 spawns, once. The ball
 * pass runs every 2 ticks and resolves at most 4 UUIDs
 * with at most 5 velocity writes; the opening circle is
 * one steering write and 3 particles per tick for 20
 * ticks. No bounded area queries anywhere. Particle
 * budget per activation: 18 at start, 20 ticks times 3
 * = 60 circle trail, at most 64 hits times 3 = 192
 * crit flashes, end() bursts at most 4 times 6 = 24,
 * total at most 294.
 *
 * TERRAIN: this ability changes no blocks; the gate
 * does not apply.
 *
 * State tag keys: "school" (list of compounds, one per
 * summoned member: "id", the member's UUID; "hit", the
 * activation tick of its last landed hit), "cx", "cy",
 * "cz" (the caster's start position, the opening
 * circle's center).
 */
public final class BaitBallBarrageAbility implements MobAbility {

    private static final int DURATION_TICKS = 240;
    private static final int SCHOOL_SIZE = 4;
    private static final int CIRCLE_TICKS = 20;
    private static final int PASS_INTERVAL = 2;
    private static final double BALL_RADIUS = 1.6;
    private static final double STEER_SPEED = 0.55;
    private static final double HIT_RANGE = 1.4;
    private static final int HIT_COOLDOWN_TICKS = 15;
    private static final float HIT_DAMAGE = 1.5f;

    @Override
    public String id() {
        return "bait_ball_barrage";
    }

    @Override
    public String displayName() {
        return "Bait Ball Barrage";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().isInWater()
            && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        state.put("school", new ListTag());
        state.putDouble("cx", caster.getX());
        state.putDouble("cy", caster.getY());
        state.putDouble("cz", caster.getZ());
        if (level == null) {
            return;
        }
        LivingEntity target = ctx.target();

        // The swirl the school folds out of.
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.FISH_SWIM, SoundSource.HOSTILE, 1.0f, 1.3f);
        level.sendParticles(ParticleTypes.BUBBLE, caster.getX(), caster.getY() + 0.3, caster.getZ(),
            12, 0.7, 0.4, 0.7, 0.05);
        level.sendParticles(ParticleTypes.SPLASH, caster.getX(), caster.getY() + 0.3, caster.getZ(),
            6, 0.7, 0.4, 0.7, 0.05);

        double ringX = target != null ? target.getX() : caster.getX();
        double ringY = target != null ? target.getY() : caster.getY();
        double ringZ = target != null ? target.getZ() : caster.getZ();
        for (int i = 0; i < SCHOOL_SIZE; i++) {
            Cod member = EntityTypes.COD.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (member == null) {
                continue;
            }
            double angle = i * Math.PI / 2.0;
            member.snapTo(ringX + Math.cos(angle) * 1.5, ringY, ringZ + Math.sin(angle) * 1.5,
                ctx.random().nextFloat() * 360.0f, 0.0f);
            member.finalizeSpawn(level, level.getCurrentDifficultyAt(member.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
            if (!level.addFreshEntity(member)) {
                continue;
            }
            // Team assignment, both directions, plus the summoner registry.
            ctx.data().addAlly(member.getUUID());
            MobWarData.get(member).addAlly(caster.getUUID());
            SummonerTracker.registerSummoned(member.getUUID(), caster.getUUID());
            CodAbilitySupport.trackEntity(state, "school", member);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        Mob caster = ctx.mob();

        if (ticks <= CIRCLE_TICKS) {
            // The opening circle: one tight wheel around the start point.
            CompoundTag state = ctx.state();
            double angle = ticks * 0.42;
            CodAbilitySupport.steerToward(caster,
                state.getDoubleOr("cx", caster.getX()) + Math.cos(angle) * 1.2,
                state.getDoubleOr("cy", caster.getY()),
                state.getDoubleOr("cz", caster.getZ()) + Math.sin(angle) * 1.2, 0.5);
            level.sendParticles(ParticleTypes.BUBBLE, caster.getX(), caster.getY() + 0.2, caster.getZ(),
                2, 0.25, 0.2, 0.25, 0.03);
            level.sendParticles(ParticleTypes.SPLASH, caster.getX(), caster.getY() + 0.2, caster.getZ(),
                1, 0.25, 0.2, 0.25, 0.03);
        }

        if (ticks % PASS_INTERVAL == 0) {
            runBallPass(ctx, level, ticks);
        }
    }

    /** One bait ball pass: steer the caster and every living member to their anchors, land due hits. */
    private void runBallPass(AbilityContext ctx, ServerLevel level, int ticks) {
        Mob caster = ctx.mob();
        LivingEntity target = ctx.target();
        // The ball wraps the target; with no target it mills around the caster.
        double centerX = target != null ? target.getX() : caster.getX();
        double centerY = (target != null ? target.getY() : caster.getY()) + 0.2;
        double centerZ = target != null ? target.getZ() : caster.getZ();

        // Slot 0: the caster holds the near side of the ring.
        double casterAngle = ticks * 0.08;
        CodAbilitySupport.steerToward(caster,
            centerX + Math.cos(casterAngle) * BALL_RADIUS, centerY,
            centerZ + Math.sin(casterAngle) * BALL_RADIUS, STEER_SPEED);

        ListTag school = ctx.state().getListOrEmpty("school");
        for (int i = 0; i < school.size(); i++) {
            CompoundTag entry = school.getCompoundOrEmpty(i);
            Entity found = CodAbilitySupport.resolveTracked(level, entry);
            if (!(found instanceof Cod member) || !member.isAlive()) {
                continue;
            }
            double angle = (i + 1) * (Math.PI * 2.0 / 5.0) + ticks * 0.08;
            CodAbilitySupport.steerToward(member,
                centerX + Math.cos(angle) * BALL_RADIUS, centerY,
                centerZ + Math.sin(angle) * BALL_RADIUS, STEER_SPEED);
            if (target != null && member.distanceToSqr(target) <= HIT_RANGE * HIT_RANGE
                && ticks - entry.getIntOr("hit", -HIT_COOLDOWN_TICKS) >= HIT_COOLDOWN_TICKS) {
                entry.putInt("hit", ticks);
                target.hurtServer(level, level.damageSources().mobAttack(member), HIT_DAMAGE);
                level.sendParticles(ParticleTypes.CRIT,
                    target.getX(), target.getY() + 0.4, target.getZ(),
                    3, 0.3, 0.3, 0.3, 0.05);
            }
        }
        // The tracked entries were mutated in place (hit
        // ticks); write the list back so the framework
        // persists them with the rest of the state tag.
        ctx.state().put("school", school);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag school = ctx.state().getListOrEmpty("school");
        for (int i = 0; i < school.size(); i++) {
            CompoundTag entry = school.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id == null) {
                continue;
            }
            // The alliance ends with the ability, whichever side is still here.
            ctx.data().removeAlly(id);
            Entity found = level.getEntity(id);
            if (found instanceof Cod member) {
                MobWarData.get(member).removeAlly(caster.getUUID());
                if (member.isAlive()) {
                    level.sendParticles(ParticleTypes.BUBBLE_POP,
                        member.getX(), member.getY() + 0.2, member.getZ(),
                        6, 0.3, 0.2, 0.3, 0.05);
                    member.discard();
                }
            }
        }
    }
}
