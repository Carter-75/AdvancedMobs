package mod.advancedmobs.ability.mooshroom;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.cow.MushroomCow;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/**
 * Sporeling Swarm (Mooshroom ability 5 of 5, summon).
 *
 * WHAT IT DOES: the mooshroom sheds a clutch of tiny
 * sporelings, baby mooshrooms at half scale that waddle
 * straight at the enemy. They are fragile and deal no
 * damage of their own (a mooshroom has no melee attack at
 * any size), but each one that dies pops in a puff of spores
 * that poisons whatever is standing in the burst, killer
 * included in the common melee case.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the mooshroom's current target within 16 blocks
 *   (distance squared 256).
 * - Duration: exactly 400 ticks (20 seconds) from start().
 * - The clutch (start()): 4 sporelings, spawned one per
 *   offset around the mooshroom (1.2 blocks east, west,
 *   south, north, or the mooshroom's own position when that
 *   spot is not collision-free), each announced by a 10
 *   particle MYCELIUM burst at the birth spot. Each sporeling
 *   is a baby MushroomCow (setBaby(true) after creation)
 *   with PERMANENT base values set after finalizeSpawn:
 *   SCALE base 0.5 (half size) and MAX_HEALTH base 4.0,
 *   health set full at 4.0. They are created with
 *   EntitySpawnReason.MOB_SUMMONED and finalizeSpawn like
 *   every other summon in this mod. The permanent bases are
 *   the design's own sheet for its summons (the Brood Eggs /
 *   Noxious Split pattern), not modifiers, so nothing about
 *   them is reversed later.
 * - Team assignment, per sporeling, both directions plus
 *   the registry: the caster's data gains the sporeling as
 *   an ally, the sporeling's data gains the caster, and the
 *   pair is registered in SummonerTracker (which is also
 *   what tags the sporeling umw_summoned for the natural
 *   spawn blocker, the same flow Fungal Spawn uses). Each
 *   sporeling is ordered onto the caster's current target at
 *   spawn.
 * - Babysitting every 5 ticks: each living tracked
 *   sporeling's position is recorded into its state entry,
 *   and it is steered onto the caster's live target: its
 *   target is set when empty or set to the caster, and its
 *   navigation is driven to the target's position at speed
 *   1.0 whenever it is more than 2 blocks away (a passive
 *   animal's own AI never chases a set target, so the
 *   navigation drive is what makes the clutch waddle
 *   straight at the enemy the design describes).
 * - The death pop: a sporeling that was tracked and is no
 *   longer resolvable as alive (dead and removed, or
 *   resolved dead) pops exactly once, at its last recorded
 *   position: 8 POOF plus 6 MYCELIUM particles, and every
 *   enemy within 2.5 blocks of the pop gains POISON I
 *   (amplifier 0) for 100 ticks (5 seconds, roughly 4 damage
 *   over its run before the poison floor). The design says
 *   the pop poisons whatever killed it; the killer is
 *   unrecoverable once the sporeling is removed, so the pop
 *   poisons the enemies standing in the burst, which
 *   includes the melee killer in the common case (the
 *   Shrieking Brood precedent, documented the same way).
 * - end(): the alliance records are removed on both sides
 *   for every tracked sporeling, and each sporeling still
 *   alive is discarded in a puff of 6 MYCELIUM and 4 POOF,
 *   WITHOUT a poison pop: being dismissed is not dying. If
 *   the caster dies first, the framework termination path
 *   runs this same end(), so the clutch never outlives its
 *   summoner either. SummonerTracker records are
 *   deliberately not unregistered here, matching Fungal
 *   Spawn: that registry cleans up on the sporeling's own
 *   removal.
 *
 * WHAT IT LOOKS LIKE: four calf-sized mooshrooms at half
 * scale shedding off the parent's flanks in puffs of
 * mycelium, waddling in a line at the enemy, and bursting
 * into spore clouds as they are cut down.
 *
 * PERFORMANCE: start() does 4 bounded collision checks and
 * 4 spawns, once. tick() returns immediately except every
 * 5th tick (babysitting: at most 4 UUID resolves, target and
 * position writes, and a navigation drive only for
 * sporelings out of reach; a death pop is one bounded query
 * and happens at most 4 times). No entity scans beyond the
 * pop query. Particle budget per activation: births 4 times
 * 10 = 40, pops at most 4 times 14 = 56, end() at most 4
 * times 10 = 40, total at most 136 particles.
 *
 * State tag keys: "clutch" (list of compounds, one per
 * sporeling: "id", the sporeling's UUID; "lx"/"ly"/"lz",
 * its last recorded position; "popped", whether its death
 * pop already fired).
 */
public final class SporelingSwarmAbility implements MobAbility {

    static final int DURATION_TICKS = 400;
    private static final int CLUTCH_COUNT = 4;
    private static final int BABYSIT_INTERVAL = 5;
    private static final double POP_RADIUS = 2.5;
    private static final double[][] OFFSETS = {
        {1.2, 0.0}, {-1.2, 0.0}, {0.0, 1.2}, {0.0, -1.2}
    };

    @Override
    public String id() {
        return "sporeling_swarm";
    }

    @Override
    public String displayName() {
        return "Sporeling Swarm";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().put("clutch", new ListTag());
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        for (int i = 0; i < CLUTCH_COUNT; i++) {
            spawnSporeling(ctx, level, i);
        }
    }

    /** Sheds one sporeling at the given offset slot and teams it up. */
    private void spawnSporeling(AbilityContext ctx, ServerLevel level, int slot) {
        Mob caster = ctx.mob();
        MushroomCow sporeling = EntityTypes.MOOSHROOM.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (sporeling == null) {
            return;
        }
        sporeling.setBaby(true);
        double[] offset = OFFSETS[slot];
        double spawnX = caster.getX() + offset[0];
        double spawnZ = caster.getZ() + offset[1];
        if (!isFreeSpot(level, sporeling, spawnX, caster.getY(), spawnZ)) {
            spawnX = caster.getX();
            spawnZ = caster.getZ();
        }
        sporeling.snapTo(spawnX, caster.getY(), spawnZ, ctx.random().nextFloat() * 360.0f, 0.0f);
        sporeling.finalizeSpawn(level, level.getCurrentDifficultyAt(sporeling.blockPosition()),
            EntitySpawnReason.MOB_SUMMONED, null);
        // The design's sheet for its summons, as permanent
        // base values: half scale, fragile health.
        if (sporeling.getAttribute(Attributes.SCALE) != null) {
            sporeling.getAttribute(Attributes.SCALE).setBaseValue(0.5);
        }
        if (sporeling.getAttribute(Attributes.MAX_HEALTH) != null) {
            sporeling.getAttribute(Attributes.MAX_HEALTH).setBaseValue(4.0);
        }
        sporeling.setHealth(4.0f);
        if (!level.addFreshEntity(sporeling)) {
            return;
        }

        // Team assignment, both directions, plus the summoner registry.
        ctx.data().addAlly(sporeling.getUUID());
        MobWarData.get(sporeling).addAlly(caster.getUUID());
        SummonerTracker.registerSummoned(sporeling.getUUID(), caster.getUUID());
        LivingEntity target = ctx.target();
        if (target != null) {
            sporeling.setTarget(target);
        }
        CompoundTag entry = new CompoundTag();
        entry.store("id", UUIDUtil.CODEC, sporeling.getUUID());
        entry.putDouble("lx", sporeling.getX());
        entry.putDouble("ly", sporeling.getY());
        entry.putDouble("lz", sporeling.getZ());
        entry.putBoolean("popped", false);
        ListTag clutch = ctx.state().getListOrEmpty("clutch");
        clutch.add(entry);
        ctx.state().put("clutch", clutch);

        // The shedding: a damp mycelium puff at the birth spot.
        level.sendParticles(ParticleTypes.MYCELIUM, spawnX, caster.getY() + 0.5, spawnZ,
            10, 0.35, 0.35, 0.35, 0.05);
    }

    /** Whether the sporeling's bounding box at the given feet position collides with nothing. */
    private boolean isFreeSpot(ServerLevel level, MushroomCow sporeling, double x, double y, double z) {
        double halfWidth = sporeling.getBbWidth() / 2.0;
        AABB box = new AABB(x - halfWidth, y, z - halfWidth, x + halfWidth, y + sporeling.getBbHeight(), z + halfWidth);
        return level.noCollision(sporeling, box);
    }

    /** Resolves a tracked sporeling from its clutch entry, or null when gone. */
    private MushroomCow resolveSporeling(ServerLevel level, CompoundTag entry) {
        if (!entry.contains("id")) {
            return null;
        }
        UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
        if (id != null && level.getEntity(id) instanceof MushroomCow sporeling) {
            return sporeling;
        }
        return null;
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % BABYSIT_INTERVAL != 0) {
            return; // Cheap early-out: babysitting runs every 5 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        LivingEntity desired = ctx.target();
        ListTag clutch = ctx.state().getListOrEmpty("clutch");
        for (int i = 0; i < clutch.size(); i++) {
            CompoundTag entry = clutch.getCompoundOrEmpty(i);
            if (!entry.contains("id") || entry.getBooleanOr("popped", false)) {
                continue;
            }
            MushroomCow sporeling = resolveSporeling(level, entry);
            if (sporeling == null || !sporeling.isAlive()) {
                // Gone: the death pop fires exactly once, at
                // the last recorded position.
                entry.putBoolean("popped", true);
                deathPop(ctx, level, entry);
                continue;
            }
            // Record the position the pop would use, and steer
            // the sporeling onto the caster's fight.
            entry.putDouble("lx", sporeling.getX());
            entry.putDouble("ly", sporeling.getY());
            entry.putDouble("lz", sporeling.getZ());
            if (desired != null && desired != sporeling) {
                LivingEntity sporelingTarget = sporeling.getTarget();
                if (sporelingTarget == null || sporelingTarget == ctx.mob()) {
                    sporeling.setTarget(desired);
                }
                if (sporeling.distanceToSqr(desired) > 4.0) {
                    sporeling.getNavigation().moveTo(desired.getX(), desired.getY(), desired.getZ(), 1.0);
                }
            }
        }
        ctx.state().put("clutch", clutch);
    }

    /** Fires one sporeling's death pop at its last recorded position. */
    private void deathPop(AbilityContext ctx, ServerLevel level, CompoundTag entry) {
        Mob caster = ctx.mob();
        double x = entry.getDoubleOr("lx", caster.getX());
        double y = entry.getDoubleOr("ly", caster.getY());
        double z = entry.getDoubleOr("lz", caster.getZ());
        level.sendParticles(ParticleTypes.POOF, x, y + 0.4, z,
            8, 0.35, 0.3, 0.35, 0.05);
        level.sendParticles(ParticleTypes.MYCELIUM, x, y + 0.4, z,
            6, 0.4, 0.3, 0.4, 0.06);
        List<LivingEntity> victims = MooshroomAbilitySupport.enemiesInBox(ctx, x, y, z, POP_RADIUS, 1.5, 1.5);
        for (LivingEntity victim : victims) {
            victim.addEffect(new MobEffectInstance(MobEffects.POISON, 100, 0));
        }
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
        ListTag clutch = ctx.state().getListOrEmpty("clutch");
        for (int i = 0; i < clutch.size(); i++) {
            CompoundTag entry = clutch.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID sporelingId = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (sporelingId == null) {
                continue;
            }
            // The alliance ends with the ability, whichever side
            // is still here.
            ctx.data().removeAlly(sporelingId);
            MushroomCow sporeling = resolveSporeling(level, entry);
            if (sporeling == null) {
                continue;
            }
            MobWarData.get(sporeling).removeAlly(caster.getUUID());
            if (sporeling.isAlive()) {
                // Dismissed, not killed: a plain puff, no
                // poison pop.
                level.sendParticles(ParticleTypes.MYCELIUM, sporeling.getX(), sporeling.getY() + 0.3, sporeling.getZ(),
                    6, 0.25, 0.2, 0.25, 0.05);
                level.sendParticles(ParticleTypes.POOF, sporeling.getX(), sporeling.getY() + 0.3, sporeling.getZ(),
                    4, 0.2, 0.15, 0.2, 0.04);
                sporeling.discard();
            }
        }
    }
}
