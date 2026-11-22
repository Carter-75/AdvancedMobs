package mod.advancedmobs.ability.giant;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.UUID;

/**
 * Stomp Call (Giant ability 5 of 5, summon).
 *
 * WHAT IT DOES: the giant stomps twice in a slow rhythm,
 * boom and boom, and on the second stomp two husks claw
 * their way out of the cracked ground at its feet in a spray
 * of dust and BLOCK debris. The husks are oversized for their
 * kind, half again as tall, and they fight at the giant's
 * side for the duration, rallying to whatever the giant is
 * currently stomping toward.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the giant's current target within 18 blocks (distance
 *   squared 324).
 * - The stomps (ticks 10 and 24): each is a GENERIC_EXPLODE
 *   at volume 1.2, pitch 0.7, with 12 BLOCK particles of the
 *   ground block underfoot and 6 DUST_PILLAR at the giant's
 *   feet. The stomps change no blocks: the "cracked ground"
 *   is particle theater, so no mobGriefing gate applies to
 *   this ability anywhere.
 * - The summons (tick 24, with the second stomp): 2 husks,
 *   one at 3.0 blocks east and one at 3.0 blocks west of the
 *   giant, each at the first collision-free of its offset
 *   spot then the giant's own position as fallback, created
 *   with EntitySpawnReason.MOB_SUMMONED and finalizeSpawn
 *   like every other summon in this mod. Each husk bursts
 *   out with 16 BLOCK particles of the ground block and 8
 *   DUST_PILLAR, and a ZOMBIE_AMBIENT groan at pitch 0.8.
 * - Oversized sheet, set as BASE attribute values right
 *   after finalizeSpawn (the Turret Brood / Split Image
 *   summon-sheet pattern): SCALE base 1.5 (half again as
 *   tall as a normal husk) and FOLLOW_RANGE base raised by
 *   16 over the husk's rolled base (a vanilla husk's 35
 *   becomes 51), so the pair keeps the giant's target in
 *   range across a big battlefield: the design's "rally to
 *   whatever the giant is stomping toward".
 * - Team assignment, per husk, both directions plus the
 *   registry: the caster's data gains the husk as an ally,
 *   the husk's data gains the caster, and the pair is
 *   registered in SummonerTracker (which is also what tags
 *   the husk umw_summoned for the natural spawn blocker).
 *   Each husk is ordered onto the caster's current target
 *   at spawn.
 * - Duration: exactly 400 ticks (20 seconds) from start().
 *   Babysitting every 5 ticks: each living tracked husk
 *   whose target is empty or is the caster is re-ordered
 *   onto the caster's live target (the Scarab Brood
 *   pattern), so the pair stays on the giant's fight.
 * - end(): the alliance records are removed on both sides
 *   for every tracked husk, and each husk still alive is
 *   discarded in 6 POOF and 4 BLOCK particles: the pair is
 *   the ability's, and it does not outlive it. If a husk
 *   died in the fight, end() simply finds nothing to discard
 *   for it. If the caster dies first, the framework
 *   termination path runs this same end(), so the pair never
 *   outlives its summoner either. SummonerTracker records
 *   are deliberately not unregistered here, matching Scarab
 *   Brood: that registry cleans up on the husk's own removal.
 *
 * WHAT IT LOOKS LIKE: two deep, spaced booms rolling out of
 * the giant's feet, debris spraying on the second, and two
 * too-big husks hauling themselves upright out of the dirt
 * and charging the giant's target.
 *
 * PERFORMANCE: the two stomp ticks are one ground read and
 * 18 particles each; the summon tick does 2 bounded
 * collision checks and 2 spawns, once. tick() returns
 * immediately except every 5th tick, where it resolves at
 * most 2 UUIDs and reads or writes targets only. Particle
 * budget per activation: 36 stomps plus 48 summon bursts
 * plus at most 20 at end(), total at most 104 particles.
 *
 * State tag keys: "husks" (list of compounds, one per husk:
 * "id", the husk's UUID), "summoned" (1 once the pair has
 * been summoned).
 */
public final class StompCallAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int FIRST_STOMP_TICK = 10;
    private static final int SECOND_STOMP_TICK = 24;
    private static final int BABYSIT_INTERVAL = 5;
    private static final double[][] OFFSETS = {{3.0, 0.0}, {-3.0, 0.0}};

    @Override
    public String id() {
        return "stomp_call";
    }

    @Override
    public String displayName() {
        return "Stomp Call";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 324.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().put("husks", new ListTag());
        ctx.state().putInt("summoned", 0);
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks == FIRST_STOMP_TICK || ticks == SECOND_STOMP_TICK) {
            stomp(ctx);
            if (ticks == SECOND_STOMP_TICK) {
                summonHusks(ctx);
            }
            return;
        }
        if (ticks % BABYSIT_INTERVAL != 0) {
            return; // Cheap early-out: babysitting runs every 5 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        LivingEntity desired = ctx.target();
        ListTag husks = ctx.state().getListOrEmpty("husks");
        for (int i = 0; i < husks.size(); i++) {
            Husk husk = resolveHusk(level, husks.getCompoundOrEmpty(i));
            if (husk == null || !husk.isAlive()) {
                continue;
            }
            LivingEntity huskTarget = husk.getTarget();
            if ((huskTarget == null || huskTarget == ctx.mob())
                && desired != null && desired != husk) {
                husk.setTarget(desired);
            }
        }
    }

    /** One stomp of the rhythm: boom and debris at the giant's feet. Changes no blocks. */
    private void stomp(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
            SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1.2f, 0.7f);
        BlockState ground = level.getBlockState(mob.blockPosition().below());
        if (ground.isAir()) {
            return;
        }
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground),
            mob.getX(), mob.getY() + 0.3, mob.getZ(), 12, 1.8, 0.3, 1.8, 0.15);
        level.sendParticles(new BlockParticleOption(ParticleTypes.DUST_PILLAR, ground),
            mob.getX(), mob.getY() + 0.3, mob.getZ(), 6, 1.8, 0.3, 1.8, 0.05);
    }

    /** Summons the oversized husk pair at the giant's feet and assigns them to its fight. */
    private void summonHusks(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        ListTag husks = new ListTag();
        LivingEntity target = ctx.target();
        BlockState ground = level.getBlockState(caster.blockPosition().below());
        for (double[] offset : OFFSETS) {
            Husk husk = EntityTypes.HUSK.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (husk == null) {
                continue;
            }
            double spawnX = caster.getX() + offset[0];
            double spawnZ = caster.getZ() + offset[1];
            if (!isFreeSpot(level, husk, spawnX, caster.getY(), spawnZ)) {
                spawnX = caster.getX();
                spawnZ = caster.getZ();
            }
            husk.snapTo(spawnX, caster.getY(), spawnZ, ctx.random().nextFloat() * 360.0f, 0.0f);
            husk.finalizeSpawn(level, level.getCurrentDifficultyAt(husk.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
            // The oversized sheet: base values, set after
            // finalizeSpawn (the summon-sheet pattern).
            AttributeInstance scale = husk.getAttribute(Attributes.SCALE);
            if (scale != null) {
                scale.setBaseValue(1.5);
            }
            AttributeInstance follow = husk.getAttribute(Attributes.FOLLOW_RANGE);
            if (follow != null) {
                follow.setBaseValue(follow.getBaseValue() + 16.0);
            }
            if (!level.addFreshEntity(husk)) {
                continue;
            }

            // Team assignment, both directions, plus the summoner registry.
            ctx.data().addAlly(husk.getUUID());
            MobWarData.get(husk).addAlly(caster.getUUID());
            SummonerTracker.registerSummoned(husk.getUUID(), caster.getUUID());
            if (target != null) {
                husk.setTarget(target);
            }
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, husk.getUUID());
            husks.add(entry);

            // The claw-out burst at the husk's feet.
            if (!ground.isAir()) {
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground),
                    spawnX, caster.getY() + 0.3, spawnZ, 16, 0.6, 0.4, 0.6, 0.15);
                level.sendParticles(new BlockParticleOption(ParticleTypes.DUST_PILLAR, ground),
                    spawnX, caster.getY() + 0.3, spawnZ, 8, 0.6, 0.4, 0.6, 0.05);
            }
            level.playSound(null, spawnX, caster.getY(), spawnZ,
                SoundEvents.ZOMBIE_AMBIENT, SoundSource.HOSTILE, 1.0f, 0.8f);
        }
        ctx.state().put("husks", husks);
        ctx.state().putInt("summoned", 1);
    }

    /** Whether the husk's bounding box at the given feet position collides with nothing. */
    private boolean isFreeSpot(ServerLevel level, Husk husk, double x, double y, double z) {
        double halfWidth = husk.getBbWidth() / 2.0;
        AABB box = new AABB(x - halfWidth, y, z - halfWidth, x + halfWidth, y + husk.getBbHeight(), z + halfWidth);
        return level.noCollision(husk, box);
    }

    /** Resolves a tracked husk from its list entry, or null when gone. */
    private Husk resolveHusk(ServerLevel level, CompoundTag entry) {
        if (!entry.contains("id")) {
            return null;
        }
        UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
        if (id != null && level.getEntity(id) instanceof Husk husk) {
            return husk;
        }
        return null;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        BlockState ground = level.getBlockState(caster.blockPosition().below());
        ListTag husks = ctx.state().getListOrEmpty("husks");
        for (int i = 0; i < husks.size(); i++) {
            CompoundTag entry = husks.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID huskId = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (huskId == null) {
                continue;
            }
            // The alliance ends with the ability, whichever side is
            // still here.
            ctx.data().removeAlly(huskId);
            Husk husk = resolveHusk(level, entry);
            if (husk == null) {
                continue;
            }
            MobWarData.get(husk).removeAlly(caster.getUUID());
            if (husk.isAlive()) {
                // The pair crumbles back into the dirt it rose from.
                level.sendParticles(ParticleTypes.POOF, husk.getX(), husk.getY() + 0.5, husk.getZ(),
                    6, 0.4, 0.5, 0.4, 0.05);
                if (!ground.isAir()) {
                    level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground),
                        husk.getX(), husk.getY() + 0.3, husk.getZ(), 4, 0.4, 0.3, 0.4, 0.1);
                }
                husk.discard();
            }
        }
    }
}
