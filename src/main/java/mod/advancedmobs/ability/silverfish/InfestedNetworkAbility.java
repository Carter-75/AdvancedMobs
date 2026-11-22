package mod.advancedmobs.ability.silverfish;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.level.block.Blocks;

import java.util.UUID;

/**
 * Infested Network (Silverfish ability 2 of 5, summon).
 *
 * WHAT IT DOES: the silverfish seeds itself with INFESTED,
 * its stone gray body crawling with the effect's ENTITY_EFFECT
 * motes, and for the duration every wound it takes shakes
 * loose 1 to 3 more silverfish onto its team, bursting out of
 * the ground around it already ordered onto the attacker.
 * Hurting it becomes a losing trade: each hit grows the swarm
 * that is eating the attacker.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the fish's current target within 12 blocks (distance
 *   squared 144).
 * - Duration: exactly 240 ticks (12 seconds) from start().
 * - The seeding (start()): the caster gains INFESTED,
 *   amplifier 0, for the full 240 ticks, with the effect's
 *   particles visible: the crawling ENTITY_EFFECT motes are
 *   the design's look, so unlike most self-buffs in this
 *   system they are deliberately NOT hidden. The wound clock
 *   is primed (see below) so a wound taken before the ability
 *   began is never answered.
 * - Wound detection: a landed combat wound is read off the
 *   caster itself. LivingEntity.setLastHurtByMob is called
 *   when a hit from a mob or player lands, so the caster's
 *   getLastHurtByMobTimestamp() names the tick of the most
 *   recent combat wound; start() stores the current value in
 *   the state tag and tick() polls it every tick (one integer
 *   compare). A changed timestamp is one wound, answered
 *   exactly once. Environmental damage (falls, lava, drowning)
 *   names no attacker and shakes nothing loose: the design's
 *   trade is about an attacker hitting the fish, and the
 *   vanilla INFESTED riders below still answer some of those.
 * - The shake-loose: each answered wound spawns 1 to 3
 *   silverfish (1 + a 0 to 2 roll) at ring spots of radius 0.8
 *   around the caster, each through
 *   SilverfishAbilitySupport.spawnAlliedFish: allied with the
 *   caster in both directions plus the SummonerTracker
 *   registry (the design's "/team team assignment", done
 *   through the war system's ally records, which is what
 *   targeting and friendly fire in this mod actually read),
 *   and ordered onto the attacker that landed the wound (the
 *   caster's live target when the attacker is gone or not an
 *   enemy). Each shaken fish erupts in 6 stone BLOCK particles
 *   at its spawn spot. The per-activation cap is 12 shaken
 *   fish: a fish being wailed on grows a real swarm, not an
 *   unbounded one, and the cap is also the performance bound.
 * - Babysitting every 5 ticks: each living network fish whose
 *   target is empty or is the caster is re-ordered onto the
 *   caster's live target. Silverfish AI only hunts players on
 *   its own, so without this the network would ignore the mob
 *   the caster is fighting; with it, the swarm stays on the
 *   caster's fight. Nothing else is steered.
 * - Vanilla INFESTED riders: while INFESTED lasts, vanilla
 *   behavior also applies: every time the caster is hurt there
 *   is a 10 percent chance to spawn 1 to 3 wild silverfish at
 *   its position. Those are vanilla spawns: hostile to
 *   whatever hurt the caster, not allied, not tracked, and not
 *   cleaned up by this ability. Only the shaken-loose fish
 *   spawned in code are the network's own. (Same accepted edge
 *   as Scarab Brood, which documents it the same way.)
 * - Permanence: end() removes the INFESTED seeding and nothing
 *   else. The network fish and their alliance records STAY:
 *   the swarm the wounds grew is a real swarm that lives out
 *   its natural life on the caster's team, the same permanence
 *   Call the Cracks gives its squad. This is the deliberate
 *   difference from Scarab Brood, whose brood is a temporary
 *   screen that end() discards: this design's text leaves a
 *   growing swarm behind, not a screen.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026): no block is placed,
 * broken, or moved. The eruptions are stone BLOCK particles
 * only, pure theater over unbroken ground, so the mobGriefing
 * gate does not apply to this ability at all.
 *
 * WHAT IT LOOKS LIKE: the fish's gray body crawling with
 * infested motes. Every hit lands with a spray of stone shards
 * as one to three more fish burst out of the floor around it
 * and pile onto the attacker, and the swarm keeps growing for
 * twelve seconds.
 *
 * PERFORMANCE: the per-tick cost is one integer compare (the
 * wound poll); spawn work happens only on wound ticks, at most
 * 3 spawns each and at most 12 per activation. Babysitting runs
 * every 5 ticks and resolves at most 12 UUIDs. No entity scans
 * anywhere. Particle budget per activation: at most 12 shaken
 * fish times 6 = 72 particles (the INFESTED effect's own motes
 * are the effect renderer, not sent particles).
 *
 * State tag keys: "hurt_ts" (the last answered wound
 * timestamp), "spawned" (fish shaken loose so far), "network"
 * (list of compounds, one per shaken fish: "id", the fish's
 * UUID; kept for babysitting only, the fish are permanent).
 */
public final class InfestedNetworkAbility implements MobAbility {

    static final int DURATION_TICKS = 240;
    private static final int MAX_SHAKEN = 12;
    private static final int BABYSIT_INTERVAL = 5;
    private static final double RING_RADIUS = 0.8;
    private static final BlockParticleOption STONE_SHARDS =
        new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState());

    @Override
    public String id() {
        return "infested_network";
    }

    @Override
    public String displayName() {
        return "Infested Network";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        state.put("network", new ListTag());
        state.putInt("spawned", 0);
        // Prime the wound clock: wounds from before the seeding
        // are never answered.
        state.putInt("hurt_ts", caster.getLastHurtByMobTimestamp());

        // The seeding: INFESTED for the duration, motes visible
        // on purpose (see the class comment).
        caster.addEffect(new MobEffectInstance(MobEffects.INFESTED, DURATION_TICKS, 0));
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        // The wound poll runs every tick: one integer compare
        // when nothing landed.
        answerWound(ctx, level);
        if (ctx.ticksActive() % BABYSIT_INTERVAL == 0) {
            babysit(ctx, level);
        }
    }

    /**
     * Answers one new combat wound, if any landed since the
     * last poll: shakes loose 1 to 3 allied silverfish onto the
     * attacker (see the class comment for the cap and the team
     * flow). Each wound is answered exactly once: the stored
     * timestamp advances whether or not the cap left room.
     */
    private void answerWound(AbilityContext ctx, ServerLevel level) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        int timestamp = caster.getLastHurtByMobTimestamp();
        if (timestamp == state.getIntOr("hurt_ts", -1)) {
            return;
        }
        state.putInt("hurt_ts", timestamp);

        int spawned = state.getIntOr("spawned", 0);
        if (spawned >= MAX_SHAKEN) {
            return;
        }
        LivingEntity attacker = caster.getLastHurtByMob();
        LivingEntity order = attacker != null && SilverfishAbilitySupport.isEnemy(ctx, attacker)
            ? attacker : ctx.target();
        int count = Math.min(1 + ctx.random().nextInt(3), MAX_SHAKEN - spawned);
        ListTag network = state.getListOrEmpty("network");
        for (int i = 0; i < count; i++) {
            double angle = ctx.random().nextDouble() * Math.PI * 2.0;
            Silverfish fish = SilverfishAbilitySupport.spawnAlliedFish(ctx, level,
                caster.getX() + Math.cos(angle) * RING_RADIUS,
                caster.getZ() + Math.sin(angle) * RING_RADIUS, order);
            if (fish == null) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, fish.getUUID());
            network.add(entry);
            spawned++;
            // The ground splits and spits it out.
            level.sendParticles(STONE_SHARDS, fish.getX(), fish.getY() + 0.2, fish.getZ(),
                6, 0.25, 0.2, 0.25, 0.15);
        }
        state.put("network", network);
        state.putInt("spawned", spawned);
    }

    /**
     * Keeps the network on the caster's fight: every living
     * network fish with an empty target, or one locked onto the
     * caster itself, is re-ordered onto the caster's live
     * target. Runs every 5 ticks over at most 12 tracked fish.
     */
    private void babysit(AbilityContext ctx, ServerLevel level) {
        LivingEntity desired = ctx.target();
        if (desired == null) {
            return;
        }
        ListTag network = ctx.state().getListOrEmpty("network");
        for (int i = 0; i < network.size(); i++) {
            CompoundTag entry = network.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (id == null || !(level.getEntity(id) instanceof Silverfish fish) || !fish.isAlive()) {
                continue;
            }
            LivingEntity fishTarget = fish.getTarget();
            if ((fishTarget == null || fishTarget == ctx.mob()) && desired != fish) {
                fish.setTarget(desired);
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // The seeding ends; the network stays (see PERMANENCE in
        // the class comment).
        ctx.mob().removeEffect(MobEffects.INFESTED);
    }
}
