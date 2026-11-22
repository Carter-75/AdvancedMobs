package mod.advancedmobs.ability.zombievillager;

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
import net.minecraft.world.entity.Entity;
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
 * Bell of the Fallen Village (Zombie Villager ability 3 of 5, summon).
 *
 * WHAT IT DOES: the zombie villager throws its head back and a phantom
 * bell tolls over the battlefield, NOTE particles ringing outward in
 * expanding circles. An iron golem answers the toll: rust streaked and
 * mossy, already cracked with age, and it lumbers straight for whatever
 * the zombie villager is fighting, on the zombie villager's side of the
 * war for as long as the bell's echo lasts.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the zombie
 *   villager's current target within 16 blocks (distance squared 256).
 * - Duration: exactly 300 ticks (15 seconds) from start(). That is how
 *   long the golem stays called; see CLEANUP for what happens at the
 *   end.
 * - The golem (summoned once, in start()):
 *     Spawn: EntityTypes.IRON_GOLEM created with
 *       EntitySpawnReason.MOB_SUMMONED (so the summon tracking mixin
 *       tags it umw_summoned and the natural spawn blocker lets it
 *       through), finalizeSpawn, placed at the first collision-free
 *       offset beside the caster, tried in order: 2 blocks east, west,
 *       south, north of the caster, then the caster's own position.
 *     Weathered look: its health is set to 75 of 100 after spawning,
 *       which puts the vanilla crack overlay at its middle stage: the
 *       rust streaks. (The moss is flavor the crack texture has to
 *       carry; there is no moss data on a golem to set.)
 *     Team: the alliance is recorded BOTH ways in MobWarData (the
 *       caster's data gains the golem as an ally, the golem's data
 *       gains the caster), which is the war system's team assignment,
 *       and the pair is registered in SummonerTracker like every other
 *       summoned ally in this mod. Both records are removed in end().
 *     Reach: a transient FOLLOW_RANGE modifier, add_value +24.0 (id
 *       advancedmobs:bell_golem_follow, removed in end()), so the
 *       golem can lock onto the caster's target across the field
 *       instead of only noticing enemies at arm's length.
 *     Orders: its target is set to the caster's current target at
 *       summon time, and the pair is babysat every 5 ticks (below).
 * - Babysitting (every 5 ticks): vanilla iron golem AI counts a zombie
 *   villager as an enemy, and vanilla zombie AI counts an iron golem
 *   as prey, so without supervision the pair would turn on each other
 *   despite the alliance records (those records govern the war
 *   system's targeting, not vanilla goal selectors). Every 5 ticks:
 *   if the golem's anger is aimed at the caster it is calmed
 *   (NeutralMob.stopBeingAngry); if the golem is targeting the caster
 *   or has no target while the caster has a live non-golem target, the
 *   golem is ordered onto the caster's target; if the CASTER is
 *   targeting the golem, the caster is retargeted to its war-tracked
 *   target (MobWarData.getCurrentTarget) when that resolves to a
 *   living entity other than the golem, and cleared otherwise. Each
 *   check is one UUID resolution plus field reads, no entity scans.
 * - The tolling: every 20 ticks a ring of 8 NOTE particles expands
 *   around the caster at height 2.2: ring radii cycle 2, 4, 6, 8
 *   blocks across successive pulses (15 rings over the duration).
 *
 * WHAT IT LOOKS LIKE: musical NOTE particles in growing circles over
 * the fight, and a full-size iron golem, visibly cracked, crashing in
 * beside the zombie villager in a burst of 8 NOTE particles and 6 POOF
 * puffs. When the echo ends the golem crumbles where it stands (see
 * CLEANUP): 10 POOF particles and 8 BLOCK particles of iron block.
 *
 * PERFORMANCE: start() does one summon and a handful of bounded
 * collision checks. tick() returns immediately except every 5th tick
 * (babysitting: one UUID lookup, a few comparisons) and every 20th
 * tick (one 8-particle ring). No entity scans anywhere. Particle
 * budget per activation: 14 at the summon, 15 rings times 8 = 120,
 * 18 at the crumble, total at most 152 particles.
 *
 * CLEANUP: end() removes the FOLLOW_RANGE modifier and both alliance
 * records, unregisters nothing from SummonerTracker (that registry is
 * keyed by summoned UUID and the entry is inert once the golem is
 * gone, the same as dead warlord minions leave it), and DISCARDS the
 * golem if it is still alive: the golem is a called ally for the
 * bell's duration only, per the framework contract that end() despawns
 * what start() summoned. If the golem died in the fight, end() simply
 * finds nothing to discard. If the caster dies first, the framework
 * termination path runs this same end(), so the golem never outlives
 * its summoner either.
 *
 * State tag keys: "golem" (the golem's UUID).
 */
public final class BellOfTheFallenVillageAbility implements MobAbility {

    static final int DURATION_TICKS = 300;
    private static final int RING_INTERVAL = 20;
    private static final int BABYSIT_INTERVAL = 5;
    private static final float GOLEM_HEALTH = 75.0f;
    private static final Identifier FOLLOW_ID = Identifier.fromNamespaceAndPath("advancedmobs", "bell_golem_follow");
    private static final BlockParticleOption IRON_BLOCK =
        new BlockParticleOption(ParticleTypes.BLOCK, Blocks.IRON_BLOCK.defaultBlockState());

    @Override
    public String id() {
        return "bell_of_the_fallen_village";
    }

    @Override
    public String displayName() {
        return "Bell of the Fallen Village";
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
        // Weathered: middle crack stage reads as rust streaks.
        golem.setHealth(GOLEM_HEALTH);

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

        // Arrival: a toll burst over the summon point.
        level.sendParticles(ParticleTypes.NOTE, spawnX, caster.getY() + 2.2, spawnZ, 8, 0.8, 0.3, 0.8, 0.1);
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
        if (ticks % RING_INTERVAL == 0) {
            // The tolling: an expanding ring of notes over the battlefield.
            Mob caster = ctx.mob();
            int ringIndex = (ticks / RING_INTERVAL - 1) % 4;
            double radius = 2.0 + ringIndex * 2.0;
            for (int i = 0; i < 8; i++) {
                double angle = i * (Math.PI / 4.0);
                level.sendParticles(ParticleTypes.NOTE,
                    caster.getX() + Math.cos(angle) * radius, caster.getY() + 2.2,
                    caster.getZ() + Math.sin(angle) * radius, 1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }

    /**
     * Keeps the summoned pair fighting the caster's enemies instead of
     * each other (see the class comment for why vanilla AI needs this).
     * One UUID resolution and field reads only; no scans.
     */
    private void babysitPair(AbilityContext ctx, ServerLevel level) {
        IronGolem golem = resolveGolem(ctx, level);
        if (golem == null || !golem.isAlive()) {
            return;
        }
        Mob caster = ctx.mob();

        // Calm any golem anger aimed at the caster.
        if (golem instanceof NeutralMob neutral && neutral.isAngryAt(caster, level)) {
            neutral.stopBeingAngry();
        }

        // The caster's intended victim: its live target, unless that is
        // the golem itself, in which case fall back to the war-tracked
        // target.
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

        // Order the golem onto the desired victim when it is idle or
        // has turned on the caster.
        LivingEntity golemTarget = golem.getTarget();
        if ((golemTarget == caster || golemTarget == null) && desired != null && desired.isAlive()) {
            golem.setTarget(desired);
        }
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
        // The alliance ends with the echo, whichever side is still here.
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
            // The called ally crumbles: the bell's echo is spent.
            level.sendParticles(ParticleTypes.POOF, golem.getX(), golem.getY() + 1.2, golem.getZ(),
                10, 0.6, 0.9, 0.6, 0.05);
            level.sendParticles(IRON_BLOCK, golem.getX(), golem.getY() + 0.8, golem.getZ(),
                8, 0.6, 0.6, 0.6, 0.15);
            golem.discard();
        }
    }
}
