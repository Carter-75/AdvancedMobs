package mod.advancedmobs.ability.endermite;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * Call the Cracks (Endermite ability 2 of 5, summon).
 *
 * WHAT IT DOES: the endermite chitters, and the stone itself
 * answers: 3 silverfish burst out of the ground around it in
 * sprays of stone shards, already allied with the mite and
 * already ordered onto the mite's target. The cracks seal
 * behind them, and the squad STAYS: unlike a temporary summon
 * screen, these silverfish are a real ambush squad that
 * remains in the world, on the mite's team, after the ability
 * is over.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the mite's current target within 10 blocks (distance
 *   squared 100). The squad answers a fight the mite can see,
 *   not one across the map.
 * - Instant: all the work happens in start(); isFinished()
 *   returns true immediately, so the framework calls tick()
 *   once and then end() on the next tick, and the normal
 *   post-ability cooldown applies.
 * - The squad: exactly 3 silverfish, spawned at ring offsets
 *   of radius 1.0 around the mite (angles 90, 210, and 330
 *   degrees), each at the first collision-free spot at that
 *   offset, with the mite's own position as fallback, created
 *   with EntitySpawnReason.MOB_SUMMONED and finalizeSpawn like
 *   every other summon in this mod. Each fish is 1 vanilla
 *   silverfish: 8 health, 1 damage per bite; the threat is
 *   three more bodies in the fight, not any single fish.
 * - Team assignment, per fish, both directions plus the
 *   registry: the caster's data gains the fish as an ally, the
 *   fish's data gains the caster, and the pair is registered
 *   in SummonerTracker (which is also what tags the fish
 *   umw_summoned for the natural spawn blocker, the same flow
 *   Mite Broodlord uses). Each fish is ordered onto the
 *   caster's current target at spawn. This is the design's
 *   "/team team assignment", done through the war system's
 *   ally records, which is what targeting and friendly fire in
 *   this mod actually read.
 * - Permanence: end() deliberately does nothing. The alliance
 *   records stay and the silverfish live out their natural
 *   lives (or die in the fight) like any other allied mob.
 *   This is the deliberate difference from Mite Broodlord,
 *   whose brood is a temporary screen that end() discards:
 *   this design's text leaves "a fresh ambush squad where bare
 *   rock stood", so the squad is permanent.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026): no block is placed,
 * broken, or moved. The "cracks" are stone BLOCK and
 * BLOCK_CRUMBLE particles only, pure theater over unbroken
 * ground, so the mobGriefing gate does not apply to this
 * ability at all.
 *
 * WHAT IT LOOKS AND SOUNDS LIKE: the mite's chitter
 * (ENDERMITE_AMBIENT, volume 1.0, pitch 1.2), then each fish
 * erupts at its ring spot in 8 stone BLOCK particles and 6
 * BLOCK_CRUMBLE particles, as if the ground split and spat it
 * out. The fish are plain silverfish, already charging the
 * mite's target.
 *
 * PERFORMANCE: start() does 3 bounded collision checks, 3
 * spawns, and 3 fixed particle bursts, once. tick() and end()
 * do nothing. Particle budget per activation: 3 times (8 + 6)
 * = 42 particles.
 *
 * State tag keys: none (the squad is permanent and needs no
 * tracking after start()).
 */
public final class CallTheCracksAbility implements MobAbility {

    private static final int SQUAD_SIZE = 3;
    private static final double RING_RADIUS = 1.0;
    private static final BlockParticleOption STONE_SHARDS =
        new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState());
    private static final BlockParticleOption STONE_CRUMBLE =
        new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, Blocks.STONE.defaultBlockState());

    @Override
    public String id() {
        return "call_the_cracks";
    }

    @Override
    public String displayName() {
        return "Call the Cracks";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 100.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();

        // The chitter the stone answers.
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.ENDERMITE_AMBIENT, SoundSource.HOSTILE, 1.0f, 1.2f);

        for (int i = 0; i < SQUAD_SIZE; i++) {
            double angle = Math.toRadians(90.0 + i * 120.0);
            spawnFish(ctx, level, caster.getX() + Math.cos(angle) * RING_RADIUS,
                caster.getZ() + Math.sin(angle) * RING_RADIUS);
        }
    }

    /**
     * Spawns one squad silverfish near the given spot (the
     * caster's own position as fallback), allies it both
     * directions, registers it in SummonerTracker, orders it
     * onto the caster's live target, and bursts the stone
     * spray it erupts from.
     */
    private void spawnFish(AbilityContext ctx, ServerLevel level, double spawnX, double spawnZ) {
        Mob caster = ctx.mob();
        Silverfish fish = EntityTypes.SILVERFISH.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (fish == null) {
            return;
        }
        if (!isFreeSpot(level, fish, spawnX, caster.getY(), spawnZ)) {
            spawnX = caster.getX();
            spawnZ = caster.getZ();
        }
        fish.snapTo(spawnX, caster.getY(), spawnZ, ctx.random().nextFloat() * 360.0f, 0.0f);
        fish.finalizeSpawn(level, level.getCurrentDifficultyAt(fish.blockPosition()),
            EntitySpawnReason.MOB_SUMMONED, null);
        if (!level.addFreshEntity(fish)) {
            return;
        }

        // Team assignment, both directions, plus the summoner registry.
        ctx.data().addAlly(fish.getUUID());
        MobWarData.get(fish).addAlly(caster.getUUID());
        SummonerTracker.registerSummoned(fish.getUUID(), caster.getUUID());
        LivingEntity target = ctx.target();
        if (target != null) {
            fish.setTarget(target);
        }

        // The ground splits and spits it out.
        level.sendParticles(STONE_SHARDS, spawnX, caster.getY() + 0.2, spawnZ,
            8, 0.3, 0.25, 0.3, 0.15);
        level.sendParticles(STONE_CRUMBLE, spawnX, caster.getY() + 0.2, spawnZ,
            6, 0.3, 0.25, 0.3, 0.1);
    }

    /** Whether the fish's bounding box at the given feet position collides with nothing. */
    private boolean isFreeSpot(ServerLevel level, Silverfish fish, double x, double y, double z) {
        double halfWidth = fish.getBbWidth() / 2.0;
        AABB box = new AABB(x - halfWidth, y, z - halfWidth, x + halfWidth, y + fish.getBbHeight(), z + halfWidth);
        return level.noCollision(fish, box);
    }

    @Override
    public void tick(AbilityContext ctx) {
        // Instant ability: all the work happened in start().
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return true;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Intentionally empty: the squad and its alliance are
        // permanent (see the class comment).
    }
}
