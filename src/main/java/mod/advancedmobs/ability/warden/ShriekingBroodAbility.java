package mod.advancedmobs.ability.warden;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SculkChargeParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/**
 * Shrieking Brood (Warden ability 4 of 5, summon).
 *
 * WHAT IT DOES: the warden shrieks and the ground around it
 * erupts with sculk charge streaks as three silverfish burst
 * out of the dark, glowing faintly and unnaturally fast,
 * straight onto the warden's target. The brood is a
 * distraction with teeth: it swarms from three sides at
 * once, and every broodling that dies pops in a burst of
 * sculk charge that briefly slows whatever is standing in
 * the wreckage, usually its killer.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the warden's current target within 14 blocks (distance
 *   squared 196).
 * - Duration: exactly 600 ticks (30 seconds) from start(),
 *   or until every broodling is gone and popped.
 * - The brood (start()): 3 silverfish, spawned one per
 *   offset around the warden (ring radius 1.3 at angles 90,
 *   210, and 330 degrees), each at the first collision-free
 *   spot at that offset, then the warden's own position as
 *   fallback, created with EntitySpawnReason.MOB_SUMMONED
 *   and finalizeSpawn like every other summon in this mod.
 *   Each broodling is 1 vanilla silverfish (8 health, 1
 *   damage per bite) carrying GLOWING amplifier 0 and SPEED
 *   amplifier 0 for 620 ticks, applied at spawn: the faint
 *   glow and the unnatural pace from the design. (The glow's
 *   color is vanilla's team-based white; a fixed teal has no
 *   server-side equivalent, the Mirror Ghasts approximation,
 *   documented here rather than faked.)
 * - Team assignment, per broodling, both directions plus
 *   the registry: the caster's data gains the broodling as
 *   an ally, the broodling's data gains the caster, and the
 *   pair is registered in SummonerTracker (which is also
 *   what tags the fish umw_summoned for the natural spawn
 *   blocker, the same flow Call the Cracks uses). Each
 *   broodling is ordered onto the caster's current target
 *   at spawn.
 * - Babysitting every 5 ticks: each living tracked
 *   broodling's position is recorded into its state entry,
 *   and a broodling whose target is empty or is the caster
 *   is re-ordered onto the caster's live target (silverfish
 *   AI only hunts players on its own; without this the brood
 *   would ignore the mob the warden is fighting). Nothing
 *   else is steered.
 * - The death pop: a broodling that was tracked and is no
 *   longer resolvable in the level has died (or burrowed
 *   into a stone block, which resolves the same way; the
 *   block stays infested as ordinary terrain, the Scarab
 *   Brood precedent). It pops exactly once, at its last
 *   recorded position: 6 SCULK_CHARGE_POP particles, and
 *   every enemy (the package enemy rule) within 2.5 blocks
 *   of the pop gains SLOWNESS amplifier 0 for 60 ticks. The
 *   design says the pop slows what killed it; the killer is
 *   not recoverable after the entity is gone, so the slow
 *   lands on the enemies standing in the burst, which in
 *   practice is the fight that killed it.
 * - end(): the alliance records are removed on both sides
 *   for every tracked broodling, and each broodling still
 *   alive is discarded in a puff of POOF: the brood is the
 *   ability's, and it does not outlive it. If the warden
 *   dies first, the framework termination path runs this
 *   same end(), so the brood never outlives its summoner
 *   either. SummonerTracker records are deliberately not
 *   unregistered here, matching Call the Cracks: that
 *   registry cleans up on the broodling's own removal.
 *
 * WHAT IT LOOKS LIKE: a WARDEN_ROAR at pitch 1.1, sculk
 * charge streaks tearing out of the ground, and three pale
 * glowing silverfish sprinting at the target from three
 * sides. Each death is a small teal pop and a visible hitch
 * in the stride of everything near it.
 *
 * PERFORMANCE: start() does 3 bounded collision checks and
 * 3 spawns, once. tick() returns immediately except every
 * 5th tick, where it resolves at most 3 UUIDs and reads or
 * writes targets and positions only; a pop is one bounded
 * query. No entity scans, no per-tick allocations.
 * Particle budget per activation: 12 shriek streaks plus 3
 * spawn bursts of 8 plus at most 3 pops of 6 plus at most 3
 * end puffs of 4 = at most 66.
 *
 * State tag keys: "brood" (list of compounds, one per
 * broodling: "id" the UUID, "lx"/"ly"/"lz" the last recorded
 * position, "popped" whether its death pop already fired).
 */
public final class ShriekingBroodAbility implements MobAbility {

    static final int DURATION_TICKS = 600;
    private static final int BROOD_COUNT = 3;
    private static final double RING_RADIUS = 1.3;
    private static final int BABYSIT_INTERVAL = 5;
    private static final double POP_RADIUS = 2.5;
    private static final SculkChargeParticleOptions CHARGE_STREAK =
        new SculkChargeParticleOptions(0.0f);

    @Override
    public String id() {
        return "shrieking_brood";
    }

    @Override
    public String displayName() {
        return "Shrieking Brood";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            ctx.state().put("brood", new ListTag());
            return;
        }
        Mob caster = ctx.mob();

        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.WARDEN_ROAR,
            SoundSource.HOSTILE, 2.0f, 1.1f);
        for (int i = 0; i < 12; i++) {
            double angle = ctx.random().nextDouble() * Math.PI * 2.0;
            level.sendParticles(CHARGE_STREAK, caster.getX() + Math.cos(angle) * 1.5,
                caster.getY() + 0.2, caster.getZ() + Math.sin(angle) * 1.5,
                1, 0.0, 0.25, 0.0, 0.05);
        }

        ListTag brood = new ListTag();
        LivingEntity target = ctx.target();
        for (int i = 0; i < BROOD_COUNT; i++) {
            double angle = Math.toRadians(90.0 + i * 120.0);
            Silverfish fish = spawnBroodling(ctx, level,
                caster.getX() + Math.cos(angle) * RING_RADIUS,
                caster.getZ() + Math.sin(angle) * RING_RADIUS, target);
            if (fish == null) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, fish.getUUID());
            entry.putDouble("lx", fish.getX());
            entry.putDouble("ly", fish.getY());
            entry.putDouble("lz", fish.getZ());
            entry.putBoolean("popped", false);
            brood.add(entry);
        }
        ctx.state().put("brood", brood);
    }

    /**
     * Spawns one broodling near the given spot (the caster's
     * own position as fallback), allies it both directions,
     * registers it in SummonerTracker, orders it onto the
     * target, and bursts the sculk spray it erupts from.
     */
    private Silverfish spawnBroodling(AbilityContext ctx, ServerLevel level,
                                      double spawnX, double spawnZ, LivingEntity target) {
        Mob caster = ctx.mob();
        Silverfish fish = EntityTypes.SILVERFISH.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (fish == null) {
            return null;
        }
        if (!isFreeSpot(level, fish, spawnX, caster.getY(), spawnZ)) {
            spawnX = caster.getX();
            spawnZ = caster.getZ();
        }
        fish.snapTo(spawnX, caster.getY(), spawnZ, ctx.random().nextFloat() * 360.0f, 0.0f);
        fish.finalizeSpawn(level, level.getCurrentDifficultyAt(fish.blockPosition()),
            EntitySpawnReason.MOB_SUMMONED, null);
        if (!level.addFreshEntity(fish)) {
            return null;
        }

        // The brood's marks: the faint glow and the pace.
        fish.addEffect(new MobEffectInstance(MobEffects.GLOWING, 620, 0));
        fish.addEffect(new MobEffectInstance(MobEffects.SPEED, 620, 0));

        // Team assignment, both directions, plus the summoner registry.
        ctx.data().addAlly(fish.getUUID());
        MobWarData.get(fish).addAlly(caster.getUUID());
        SummonerTracker.registerSummoned(fish.getUUID(), caster.getUUID());
        if (target != null) {
            fish.setTarget(target);
        }

        // The ground splits and spits it out.
        level.sendParticles(ParticleTypes.SCULK_CHARGE_POP, spawnX, caster.getY() + 0.3, spawnZ,
            8, 0.3, 0.25, 0.3, 0.1);
        return fish;
    }

    /** Whether the fish's bounding box at the given feet position collides with nothing. */
    private boolean isFreeSpot(ServerLevel level, Silverfish fish, double x, double y, double z) {
        double halfWidth = fish.getBbWidth() / 2.0;
        AABB box = new AABB(x - halfWidth, y, z - halfWidth, x + halfWidth, y + fish.getBbHeight(), z + halfWidth);
        return level.noCollision(fish, box);
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
        ListTag brood = ctx.state().getListOrEmpty("brood");
        for (int i = 0; i < brood.size(); i++) {
            CompoundTag entry = brood.getCompoundOrEmpty(i);
            if (!entry.contains("id") || entry.getBooleanOr("popped", false)) {
                continue;
            }
            UUID fishId = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (fishId == null) {
                continue;
            }
            if (level.getEntity(fishId) instanceof Silverfish fish && fish.isAlive()) {
                entry.putDouble("lx", fish.getX());
                entry.putDouble("ly", fish.getY());
                entry.putDouble("lz", fish.getZ());
                LivingEntity fishTarget = fish.getTarget();
                if ((fishTarget == null || fishTarget == ctx.mob())
                    && desired != null && desired != fish) {
                    fish.setTarget(desired);
                }
            } else {
                // Gone: the death pop fires exactly once, at
                // the last recorded position.
                entry.putBoolean("popped", true);
                double px = entry.getDoubleOr("lx", ctx.mob().getX());
                double py = entry.getDoubleOr("ly", ctx.mob().getY());
                double pz = entry.getDoubleOr("lz", ctx.mob().getZ());
                level.sendParticles(ParticleTypes.SCULK_CHARGE_POP, px, py + 0.3, pz,
                    6, 0.3, 0.25, 0.3, 0.1);
                List<LivingEntity> victims = WardenAbilitySupport.enemiesInBox(ctx, px, py, pz,
                    POP_RADIUS, 1.5, 2.0);
                for (LivingEntity victim : victims) {
                    victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 0));
                }
            }
        }
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
        ListTag brood = ctx.state().getListOrEmpty("brood");
        for (int i = 0; i < brood.size(); i++) {
            CompoundTag entry = brood.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID fishId = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (fishId == null) {
                continue;
            }
            // The alliance ends with the ability, whichever
            // side is still here.
            ctx.data().removeAlly(fishId);
            if (level.getEntity(fishId) instanceof Silverfish fish) {
                MobWarData.get(fish).removeAlly(caster.getUUID());
                if (fish.isAlive()) {
                    level.sendParticles(ParticleTypes.POOF, fish.getX(), fish.getY() + 0.2, fish.getZ(),
                        4, 0.25, 0.2, 0.25, 0.05);
                    fish.discard();
                }
            }
        }
    }
}
