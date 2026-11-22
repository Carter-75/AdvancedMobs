package mod.advancedmobs.ability.villager;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.UUID;

/**
 * Iron Guardian Call (Villager ability 2 of 5, summon).
 *
 * WHAT IT DOES: the villager rings for help, HAPPY_VILLAGER
 * sparkles bursting overhead, and an iron golem hauls itself out
 * of the ground beside it in a shower of dirt. The golem fights
 * at the villager's side for the length of the call, and the
 * villager sensibly keeps the golem between itself and the fight,
 * backing away behind its guardian whenever the enemy closes in.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   villager's current target within 16 blocks (distance squared
 *   256).
 * - Duration: exactly 400 ticks (20 seconds) from start(). That
 *   is how long the golem stays called; see CLEANUP.
 * - The call (in start()): VILLAGER_YES at volume 1.2, pitch 1.1,
 *   and a burst of 10 HAPPY_VILLAGER particles over the villager's
 *   head (the design's ringing sparkles).
 * - The golem (summoned once, in start()):
 *     Spawn: EntityTypes.IRON_GOLEM created with
 *       EntitySpawnReason.MOB_SUMMONED (so the summon tracking
 *       mixin tags it umw_summoned and the natural spawn blocker
 *       lets it through), finalizeSpawn, placed at the first
 *       collision-free offset beside the caster, tried in order:
 *       2 blocks east, west, south, north of the caster, then the
 *       caster's own position. It rises at full health (100), a
 *       fresh guardian rather than the zombie villager's rusted
 *       one. Arrival burst at the spawn point: 12 BLOCK particles
 *       of dirt and 6 POOF (the shower of dirt it hauls itself
 *       out of).
 *     Team: the alliance is recorded BOTH ways in MobWarData
 *       (the caster's data gains the golem as an ally, the
 *       golem's data gains the caster), which is the war system's
 *       team assignment (the design's /team line), and the pair
 *       is registered in SummonerTracker like every other
 *       summoned ally in this mod. Both records are removed in
 *       end().
 *     Reach: a transient FOLLOW_RANGE modifier, add_value +24.0
 *       (id advancedmobs:guardian_golem_follow, removed in
 *       end()), so the golem can lock onto the caster's target
 *       across the field.
 *     Orders: its target is set to the caster's current target
 *       at summon time, and the pair is babysat every 5 ticks
 *       (below).
 * - Babysitting (every 5 ticks): if the golem's anger is aimed
 *   at the caster it is calmed (NeutralMob.stopBeingAngry); if
 *   the golem is targeting the caster or has no target while the
 *   caster has a live non-golem target, the golem is ordered
 *   onto the caster's target; if the CASTER's war targeting has
 *   settled on the golem, the caster is retargeted to its
 *   war-tracked target when that resolves to a living entity
 *   other than the golem, and cleared otherwise. Each check is
 *   one UUID resolution plus field reads, no entity scans. (The
 *   Bell of the Fallen Village pattern; a villager has no attack
 *   AI of its own, but the war system's targeting can still pin
 *   it to its own guardian.)
 * - Keeping behind the guardian (every 10 ticks): if the
 *   caster's target is within 6 blocks of the caster and the
 *   golem is alive, the caster's navigation is driven at speed
 *   1.0 to a point 2.5 blocks behind the golem, on the line from
 *   the target through the golem and past it, so the golem
 *   stays between the villager and the fight (the design's
 *   defend positioning, in code).
 * - The ringing: every 20 ticks, 6 HAPPY_VILLAGER particles
 *   burst over the caster's head at height 2.2.
 *
 * WHAT IT LOOKS LIKE: green sparkles popping over a calling
 * villager, the ground beside it bursting up in dirt as a full
 * iron golem climbs into the fight, and the villager scurrying
 * to keep that wall of iron between itself and its enemy.
 *
 * PERFORMANCE: start() does one summon and a handful of bounded
 * collision checks. tick() returns immediately except every 5th
 * tick (babysitting: one UUID lookup, a few comparisons), every
 * 10th tick (one distance check and at most one navigation
 * call), and every 20th tick (one 6-particle burst). No entity
 * scans anywhere. Particle budget per activation: 10 at the
 * call, 18 at the rise, 20 bursts times 6 = 120, 18 at the
 * crumble, total at most 166 particles.
 *
 * CLEANUP: end() removes the FOLLOW_RANGE modifier and both
 * alliance records, and DISCARDS the golem if it is still alive:
 * the golem is a called guardian for the call's duration only,
 * per the framework contract that end() despawns what start()
 * summoned. If the golem died in the fight, end() simply finds
 * nothing to discard. If the caster dies first, the framework
 * termination path runs this same end(), so the golem never
 * outlives its summoner either.
 *
 * State tag keys: "golem" (the golem's UUID).
 */
public final class IronGuardianCallAbility implements MobAbility {

    static final int DURATION_TICKS = 400;
    private static final int BABYSIT_INTERVAL = 5;
    private static final int REPOSITION_INTERVAL = 10;
    private static final int RING_INTERVAL = 20;
    private static final Identifier FOLLOW_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "guardian_golem_follow");
    private static final BlockParticleOption DIRT_BLOCK =
        new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DIRT.defaultBlockState());
    private static final BlockParticleOption IRON_BLOCK =
        new BlockParticleOption(ParticleTypes.BLOCK, Blocks.IRON_BLOCK.defaultBlockState());

    @Override
    public String id() {
        return "iron_guardian_call";
    }

    @Override
    public String displayName() {
        return "Iron Guardian Call";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return ctx.target() != null && ctx.mob().distanceToSqr(ctx.target()) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();

        // The call: a shout and the ringing sparkles overhead.
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.VILLAGER_YES, SoundSource.HOSTILE, 1.2f, 1.1f);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, caster.getX(), caster.getY() + 2.2, caster.getZ(),
            10, 0.6, 0.3, 0.6, 0.05);

        IronGolem golem = EntityTypes.IRON_GOLEM.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (golem == null) {
            return;
        }

        // First collision-free spot beside the caster, in a fixed order.
        double[][] offsets = {{2.0, 0.0}, {-2.0, 0.0}, {0.0, 2.0}, {0.0, -2.0}, {0.0, 0.0}};
        double spawnX = caster.getX(), spawnZ = caster.getZ();
        for (double[] offset : offsets) {
            double x = caster.getX() + offset[0];
            double z = caster.getZ() + offset[1];
            if (isFreeSpot(level, golem, x, caster.getY(), z)) {
                spawnX = x;
                spawnZ = z;
                break;
            }
        }
        golem.snapTo(spawnX, caster.getY(), spawnZ, ctx.random().nextFloat() * 360.0f, 0.0f);
        golem.finalizeSpawn(level, level.getCurrentDifficultyAt(golem.blockPosition()),
            EntitySpawnReason.MOB_SUMMONED, null);
        if (!level.addFreshEntity(golem)) {
            return;
        }

        // Team assignment, both directions, plus the summoner registry.
        ctx.data().addAlly(golem.getUUID());
        MobWarData.get(golem).addAlly(caster.getUUID());
        SummonerTracker.registerSummoned(golem.getUUID(), caster.getUUID());

        // Reach across the field, and orders: whatever the caster fights.
        AttributeInstance follow = golem.getAttribute(Attributes.FOLLOW_RANGE);
        if (follow != null) {
            follow.addOrUpdateTransientModifier(
                new AttributeModifier(FOLLOW_ID, 24.0, AttributeModifier.Operation.ADD_VALUE));
        }
        LivingEntity target = ctx.target();
        if (target != null) {
            golem.setTarget(target);
        }
        ctx.state().store("golem", UUIDUtil.CODEC, golem.getUUID());

        // The rise: a shower of dirt at the summon point.
        level.sendParticles(DIRT_BLOCK, spawnX, caster.getY() + 0.5, spawnZ, 12, 0.6, 0.5, 0.6, 0.15);
        level.sendParticles(ParticleTypes.POOF, spawnX, caster.getY() + 1.0, spawnZ, 6, 0.5, 0.8, 0.5, 0.05);
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        if (ticks % BABYSIT_INTERVAL == 0) {
            babysitPair(ctx, level);
        }
        if (ticks % REPOSITION_INTERVAL == 0) {
            keepBehindGuardian(ctx, level);
        }
        if (ticks % RING_INTERVAL == 0) {
            Mob caster = ctx.mob();
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, caster.getX(), caster.getY() + 2.2, caster.getZ(),
                6, 0.6, 0.3, 0.6, 0.05);
        }
    }

    /**
     * Keeps the summoned pair fighting the caster's enemies
     * instead of each other (see the class comment). One UUID
     * resolution and field reads only; no scans.
     */
    private void babysitPair(AbilityContext ctx, ServerLevel level) {
        IronGolem golem = resolveGolem(ctx, level);
        if (golem == null || !golem.isAlive()) {
            return;
        }
        Mob caster = ctx.mob();

        if (golem instanceof NeutralMob neutral && neutral.isAngryAt(caster, level)) {
            neutral.stopBeingAngry();
        }

        LivingEntity desired = caster.getTarget();
        if (desired == golem) {
            desired = null;
            UUID tracked = ctx.data().getCurrentTarget();
            if (tracked != null && level.getEntity(tracked) instanceof LivingEntity living
                && living.isAlive() && living != golem) {
                desired = living;
            }
            caster.setTarget(desired);
        }

        LivingEntity golemTarget = golem.getTarget();
        if ((golemTarget == caster || golemTarget == null) && desired != null && desired.isAlive()) {
            golem.setTarget(desired);
        }
    }

    /**
     * Drives the caster to a point behind its golem when the
     * enemy gets close, so the golem stays between them (see
     * the class comment). One UUID resolution and one distance
     * check; no scans.
     */
    private void keepBehindGuardian(AbilityContext ctx, ServerLevel level) {
        LivingEntity target = ctx.target();
        if (target == null) {
            return;
        }
        Mob caster = ctx.mob();
        if (caster.distanceToSqr(target) > 36.0) {
            return;
        }
        IronGolem golem = resolveGolem(ctx, level);
        if (golem == null || !golem.isAlive()) {
            return;
        }
        double awayX = golem.getX() - target.getX();
        double awayZ = golem.getZ() - target.getZ();
        double len = Math.sqrt(awayX * awayX + awayZ * awayZ);
        if (len < 1.0e-4) {
            awayX = 1.0;
            awayZ = 0.0;
            len = 1.0;
        }
        double hideX = golem.getX() + awayX / len * 2.5;
        double hideZ = golem.getZ() + awayZ / len * 2.5;
        caster.getNavigation().moveTo(hideX, golem.getY(), hideZ, 1.0);
    }

    /** Whether the golem's bounding box at the given feet position collides with nothing. */
    private boolean isFreeSpot(ServerLevel level, IronGolem golem, double x, double y, double z) {
        double halfWidth = golem.getBbWidth() / 2.0;
        AABB box = new AABB(x - halfWidth, y, z - halfWidth, x + halfWidth, y + golem.getBbHeight(), z + halfWidth);
        return level.noCollision(golem, box);
    }

    /** Resolves the summoned golem from the state tag, or null when gone. */
    private IronGolem resolveGolem(AbilityContext ctx, ServerLevel level) {
        if (!ctx.state().contains("golem")) {
            return null;
        }
        UUID id = ctx.state().read("golem", UUIDUtil.CODEC).orElse(null);
        if (id != null && level.getEntity(id) instanceof IronGolem golem) {
            return golem;
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
        if (level == null || !ctx.state().contains("golem")) {
            return;
        }
        UUID golemId = ctx.state().read("golem", UUIDUtil.CODEC).orElse(null);
        if (golemId == null) {
            return;
        }
        // The alliance ends with the call, whichever side is still here.
        ctx.data().removeAlly(golemId);
        IronGolem golem = resolveGolem(ctx, level);
        if (golem == null) {
            return;
        }
        MobWarData.get(golem).removeAlly(ctx.mob().getUUID());
        AttributeInstance follow = golem.getAttribute(Attributes.FOLLOW_RANGE);
        if (follow != null) {
            follow.removeModifier(FOLLOW_ID);
        }
        if (golem.isAlive()) {
            // The called guardian crumbles: the call is spent.
            level.sendParticles(ParticleTypes.POOF, golem.getX(), golem.getY() + 1.2, golem.getZ(),
                10, 0.6, 0.9, 0.6, 0.05);
            level.sendParticles(IRON_BLOCK, golem.getX(), golem.getY() + 0.8, golem.getZ(),
                8, 0.6, 0.6, 0.6, 0.15);
            golem.discard();
        }
    }
}
