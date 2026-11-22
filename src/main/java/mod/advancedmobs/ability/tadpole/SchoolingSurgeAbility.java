package mod.advancedmobs.ability.tadpole;

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
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.frog.Tadpole;

import java.util.UUID;

/**
 * Schooling Surge (Tadpole ability 1 of 5, summon).
 *
 * WHAT IT DOES: one tadpole becomes a school. Eight tiny
 * tadpoles boil out of a swirl of bubbles around it and
 * fold into a shimmering bait ball that wraps the enemy
 * on every side, each member darting in to nip for 1
 * damage. No single nip matters; eight mouths landing
 * one every second add up fast, and the ball never sits
 * still long enough to swat.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the tadpole's current target within 16 blocks
 *   (distance squared 256).
 * - The school (start()): FISH_SWIM at volume 1.0, pitch
 *   1.2, 16 BUBBLE plus 8 BUBBLE_POP at the caster, then
 *   8 tadpoles spawned on a radius 1.5 ring around the
 *   caster at its own height, created with
 *   EntitySpawnReason.MOB_SUMMONED and finalizeSpawn
 *   like every other summon in this mod. Each member is
 *   a plain vanilla tadpole plus SCALE base value 0.6
 *   set permanently on the new mob (the design's scale
 *   line; a base value on a fresh summon, not a
 *   transient modifier, the Warren Call precedent).
 *   Team assignment, per member, both directions plus
 *   the registry: the caster's data gains the member as
 *   an ally, the member's data gains the caster, and
 *   the pair is registered in SummonerTracker (which
 *   also tags the member umw_summoned for the natural
 *   spawn blocker). Members have no vanilla combat AI
 *   (a tadpole never attacks on its own), so the whole
 *   fight is the code steering below, the Flock Raid
 *   pattern.
 * - Duration: 300 ticks (15 seconds), hard finish.
 * - The bait ball (tick(), one pass every 2 ticks): the
 *   ball's center is the target's live position, or the
 *   caster's own position when the target is dead or
 *   lost (the school mills around its caller instead of
 *   scattering). Member i's anchor is the center plus a
 *   radius 1.7 offset at angle (i times 45 degrees plus
 *   the activation ticks times 0.06 radians), at the
 *   center's own height plus 0.2, so the ball slowly
 *   rotates as it holds. Each living member is steered
 *   straight at its anchor at 0.55 blocks per tick (the
 *   package steering helper, one velocity write per
 *   member per pass).
 * - The nips (same pass): when a member is within 1.3
 *   blocks of the live target and at least 20 ticks
 *   have passed since that member's last nip (the tick
 *   is kept per member in its tracked entry, key "nip"),
 *   the nip lands: 1.0 flat damage as a mob attack
 *   credited to the member (the tadpole has no
 *   ATTACK_DAMAGE instance; see the provider comment),
 *   and 2 BUBBLE_POP particles at the target. A member
 *   can therefore nip at most 15 times over the whole
 *   activation, and the whole school at most 120 times.
 * - The shimmer: every 10 ticks, 3 BUBBLE particles at
 *   one random living member (or the caster when the
 *   school is wiped out).
 * - end(): each surviving member is discarded in 4
 *   BUBBLE_POP particles and the alliance records are
 *   removed on both sides for every tracked member. The
 *   school is a temporary summon, not a split self, so
 *   it does not outlive the activation (the Pod Call
 *   precedent). If the caster dies first, the framework
 *   termination path runs this same end(), so the school
 *   never outlives its caller. SummonerTracker records
 *   are deliberately not unregistered, matching the
 *   other summons in this mod: that registry cleans up
 *   on the member's own removal.
 *
 * WHAT IT LOOKS LIKE: a burst of bubbles, eight specks
 * unfolding into a rotating ring of tiny tadpoles around
 * the enemy, the ring shimmering with rising bubbles
 * while little pops of spray mark each nip.
 *
 * PERFORMANCE: start() does 8 spawns, once. The ball
 * pass runs every 2 ticks and resolves at most 8 UUIDs
 * with at most 8 velocity writes; the shimmer is 3
 * particles every 10 ticks. No bounded area queries
 * anywhere. Particle budget per activation: 24 at start,
 * at most 120 nips times 2 = 240, at most 30 shimmer
 * passes times 3 = 90, end() puffs at most 8 times 4 =
 * 32, total at most 386.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "school" (list of compounds, one per
 * summoned member: "id", the member's UUID; "nip", the
 * activation tick of its last landed nip).
 */
public final class SchoolingSurgeAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int SCHOOL_SIZE = 8;
    private static final int PASS_INTERVAL = 2;
    private static final double MEMBER_SCALE = 0.6;
    private static final double BALL_RADIUS = 1.7;
    private static final double STEER_SPEED = 0.55;
    private static final double NIP_RANGE = 1.3;
    private static final int NIP_COOLDOWN_TICKS = 20;
    private static final float NIP_DAMAGE = 1.0f;

    @Override
    public String id() {
        return "schooling_surge";
    }

    @Override
    public String displayName() {
        return "Schooling Surge";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();
        ctx.state().put("school", new ListTag());
        if (level == null) {
            return;
        }

        // The boil-up: a swirl of bubbles as the school unfolds.
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.FISH_SWIM, SoundSource.HOSTILE, 1.0f, 1.2f);
        level.sendParticles(ParticleTypes.BUBBLE, caster.getX(), caster.getY() + 0.3, caster.getZ(),
            16, 0.8, 0.4, 0.8, 0.05);
        level.sendParticles(ParticleTypes.BUBBLE_POP, caster.getX(), caster.getY() + 0.3, caster.getZ(),
            8, 0.8, 0.4, 0.8, 0.05);

        for (int i = 0; i < SCHOOL_SIZE; i++) {
            Tadpole member = EntityTypes.TADPOLE.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (member == null) {
                continue;
            }
            double angle = i * Math.PI / 4.0;
            member.snapTo(caster.getX() + Math.cos(angle) * 1.5, caster.getY(),
                caster.getZ() + Math.sin(angle) * 1.5,
                ctx.random().nextFloat() * 360.0f, 0.0f);
            member.finalizeSpawn(level, level.getCurrentDifficultyAt(member.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
            // The design's scale line, as a permanent base
            // value on the new mob (see the class comment).
            if (member.getAttribute(Attributes.SCALE) != null) {
                member.getAttribute(Attributes.SCALE).setBaseValue(MEMBER_SCALE);
            }
            if (!level.addFreshEntity(member)) {
                continue;
            }
            // Team assignment, both directions, plus the summoner registry.
            ctx.data().addAlly(member.getUUID());
            MobWarData.get(member).addAlly(caster.getUUID());
            SummonerTracker.registerSummoned(member.getUUID(), caster.getUUID());
            TadpoleAbilitySupport.trackEntity(ctx.state(), "school", member);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        if (ticks % PASS_INTERVAL == 0) {
            runBallPass(ctx, level, ticks);
        }
        if (ticks % 10 == 0) {
            shimmer(ctx, level);
        }
    }

    /** One bait ball pass: steer every living member to its anchor, land due nips. */
    private void runBallPass(AbilityContext ctx, ServerLevel level, int ticks) {
        Mob caster = ctx.mob();
        LivingEntity target = ctx.target();
        // The ball wraps the target; with no target it mills around the caster.
        double centerX = target != null ? target.getX() : caster.getX();
        double centerY = (target != null ? target.getY() : caster.getY()) + 0.2;
        double centerZ = target != null ? target.getZ() : caster.getZ();
        ListTag school = ctx.state().getListOrEmpty("school");
        for (int i = 0; i < school.size(); i++) {
            CompoundTag entry = school.getCompoundOrEmpty(i);
            Entity found = TadpoleAbilitySupport.resolveTracked(level, entry);
            if (!(found instanceof Tadpole member) || !member.isAlive()) {
                continue;
            }
            double angle = i * Math.PI / 4.0 + ticks * 0.06;
            TadpoleAbilitySupport.steerToward(member,
                centerX + Math.cos(angle) * BALL_RADIUS, centerY,
                centerZ + Math.sin(angle) * BALL_RADIUS, STEER_SPEED);
            if (target != null && member.distanceToSqr(target) <= NIP_RANGE * NIP_RANGE
                && ticks - entry.getIntOr("nip", -NIP_COOLDOWN_TICKS) >= NIP_COOLDOWN_TICKS) {
                entry.putInt("nip", ticks);
                target.hurtServer(level, level.damageSources().mobAttack(member), NIP_DAMAGE);
                level.sendParticles(ParticleTypes.BUBBLE_POP,
                    target.getX(), target.getY() + 0.4, target.getZ(),
                    2, 0.3, 0.3, 0.3, 0.05);
            }
        }
        // The tracked entries were mutated in place (nip
        // ticks); write the list back so the framework
        // persists them with the rest of the state tag.
        ctx.state().put("school", school);
    }

    /** The shimmer: a few rising bubbles at one random living member. Every 10 ticks. */
    private void shimmer(AbilityContext ctx, ServerLevel level) {
        LivingEntity host = ctx.mob();
        ListTag school = ctx.state().getListOrEmpty("school");
        if (!school.isEmpty()) {
            CompoundTag entry = school.getCompoundOrEmpty(ctx.random().nextInt(school.size()));
            Entity found = TadpoleAbilitySupport.resolveTracked(level, entry);
            if (found instanceof Tadpole member && member.isAlive()) {
                host = member;
            }
        }
        level.sendParticles(ParticleTypes.BUBBLE, host.getX(), host.getY() + 0.2, host.getZ(),
            3, 0.3, 0.2, 0.3, 0.03);
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
            if (found instanceof Tadpole member) {
                MobWarData.get(member).removeAlly(caster.getUUID());
                if (member.isAlive()) {
                    level.sendParticles(ParticleTypes.BUBBLE_POP,
                        member.getX(), member.getY() + 0.2, member.getZ(),
                        4, 0.25, 0.2, 0.25, 0.05);
                    member.discard();
                }
            }
        }
    }
}
