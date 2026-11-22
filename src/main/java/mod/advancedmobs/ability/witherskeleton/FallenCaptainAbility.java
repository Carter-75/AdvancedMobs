package mod.advancedmobs.ability.witherskeleton;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.UUIDUtil;
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
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton;
import net.minecraft.world.phys.AABB;

import java.util.UUID;

/**
 * Fallen Captain (Wither Skeleton ability 3 of 5, summon).
 *
 * WHAT IT DOES: the wither skeleton plants its sword and calls
 * up one hulking lieutenant: a second wither skeleton summoned
 * at enlarged scale, SOUL_FIRE_FLAME wreathing its blade,
 * arriving on the summoner's team in a burst of SOUL wisps.
 * The captain wades straight into the thickest fighting and
 * soaks attention the summoner does not want.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   wither skeleton's current target within 16 blocks (distance
 *   squared 256).
 * - Duration: exactly 400 ticks (20 seconds) from start().
 * - The captain: one vanilla wither skeleton (its own stone
 *   sword, its own wither-on-hit, all vanilla), created with
 *   EntitySpawnReason.MOB_SUMMONED and finalizeSpawn like every
 *   other summon in this mod, spawned 1.4 blocks to the
 *   summoner's left (or at the summoner's own position when
 *   that spot is not collision-free). Two transient attribute
 *   modifiers make it the hulk the design calls for, ids under
 *   advancedmobs:fallen_captain_*:
 *     SCALE      add_value +0.4 (1.4 times normal size: about
 *                3.4 blocks tall against the normal 2.4)
 *     MAX_HEALTH add_value +12.0 (a base captain goes 20 to 32
 *                health, and the +12 arrives full)
 *   The modifiers live on the captain, not the caster; they are
 *   never removed by hand because the captain itself is
 *   discarded in end() (below), and a discarded entity takes its
 *   transient modifiers with it.
 * - Team assignment, both directions plus the registry: the
 *   caster's data gains the captain as an ally, the captain's
 *   data gains the caster, and the pair is registered in
 *   SummonerTracker (which is also what tags the captain
 *   umw_summoned for the natural spawn blocker, the same flow
 *   Fungal Spawn and Bell of the Fallen Village use). The
 *   captain is ordered onto the caster's current target at
 *   spawn.
 * - Babysitting every 5 ticks: a living captain whose target is
 *   empty or is the caster is re-ordered onto the caster's live
 *   target, so it stays in the thickest fighting instead of
 *   wandering off after whatever its own AI fancies. Nothing
 *   else is steered: movement, sword swings, and wither
 *   application are vanilla wither skeleton behavior.
 * - end(): the alliance records are removed on both sides, and
 *   a captain still alive is discarded in a puff of 8 SOUL
 *   particles: the lieutenant is the ability's, and it does not
 *   outlive it. If the captain died in the fight, end() simply
 *   finds nothing to discard. If the caster dies first, the
 *   framework termination path runs this same end(), so the
 *   captain never outlives its summoner either.
 *   SummonerTracker records are deliberately not unregistered
 *   here, matching Fungal Spawn: that registry cleans up on the
 *   captain's own removal.
 *
 * WHAT IT LOOKS AND SOUNDS LIKE: the call lands with a deep
 * WITHER_SKELETON_AMBIENT bellow (volume 1.2, pitch 0.5) and a
 * 14 particle SOUL burst at the captain's arrival spot. Every
 * 10 ticks, 2 SOUL_FIRE_FLAME motes wreath the captain's blade
 * hand (1.4 blocks above its feet, roughly where a raised
 * sword sits on the enlarged frame).
 *
 * PERFORMANCE: the spawn is 1 bounded collision check and 1
 * spawn, once. tick() returns immediately except every 5th tick
 * (babysitting: 1 UUID resolve and target reads) and every 10th
 * tick (2 particles at the resolved captain). No entity scans,
 * no allocations beyond the resolved references. Particle
 * budget per activation: 14 at start plus at most 40 flame
 * pulses times 2 = 80, plus 8 at end(), total at most 102
 * particles.
 *
 * State tag keys: "captain" (the summoned wither skeleton's
 * UUID).
 */
public final class FallenCaptainAbility implements MobAbility {

    static final int DURATION_TICKS = 400;
    private static final int BABYSIT_INTERVAL = 5;
    private static final int FLAME_INTERVAL = 10;
    private static final double SPAWN_OFFSET_X = -1.4;
    private static final double SPAWN_OFFSET_Z = 0.3;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "fallen_captain_scale");
    private static final Identifier HEALTH_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "fallen_captain_health");

    @Override
    public String id() {
        return "fallen_captain";
    }

    @Override
    public String displayName() {
        return "Fallen Captain";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        WitherSkeleton captain = EntityTypes.WITHER_SKELETON.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (captain == null) {
            return;
        }
        double spawnX = caster.getX() + SPAWN_OFFSET_X;
        double spawnZ = caster.getZ() + SPAWN_OFFSET_Z;
        if (!isFreeSpot(level, captain, spawnX, caster.getY(), spawnZ)) {
            spawnX = caster.getX();
            spawnZ = caster.getZ();
        }
        captain.snapTo(spawnX, caster.getY(), spawnZ, ctx.random().nextFloat() * 360.0f, 0.0f);
        captain.finalizeSpawn(level, level.getCurrentDifficultyAt(captain.blockPosition()),
            EntitySpawnReason.MOB_SUMMONED, null);
        if (!level.addFreshEntity(captain)) {
            return;
        }

        // The hulk: enlarged scale and a deeper health pool, the
        // extra health arriving full.
        addModifier(captain, Attributes.SCALE, SCALE_ID, 0.4);
        addModifier(captain, Attributes.MAX_HEALTH, HEALTH_ID, 12.0);
        captain.setHealth(Math.min(captain.getMaxHealth(), captain.getHealth() + 12.0f));

        // Team assignment, both directions, plus the summoner registry.
        ctx.data().addAlly(captain.getUUID());
        MobWarData.get(captain).addAlly(caster.getUUID());
        SummonerTracker.registerSummoned(captain.getUUID(), caster.getUUID());
        LivingEntity target = ctx.target();
        if (target != null) {
            captain.setTarget(target);
        }
        ctx.state().store("captain", UUIDUtil.CODEC, captain.getUUID());

        // The arrival: soul wisps and a bellow.
        level.sendParticles(ParticleTypes.SOUL, spawnX, caster.getY() + 1.2, spawnZ,
            14, 0.6, 0.9, 0.6, 0.06);
        level.playSound(null, spawnX, caster.getY(), spawnZ,
            SoundEvents.WITHER_SKELETON_AMBIENT, SoundSource.HOSTILE, 1.2f, 0.5f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks % BABYSIT_INTERVAL != 0) {
            return; // Cheap early-out: babysitting and flames run on the 5 and 10 tick marks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        WitherSkeleton captain = resolveCaptain(ctx, level);
        if (captain == null || !captain.isAlive()) {
            return;
        }

        // Babysitting: keep the captain on the caster's fight.
        LivingEntity captainTarget = captain.getTarget();
        LivingEntity desired = ctx.target();
        if ((captainTarget == null || captainTarget == ctx.mob()) && desired != null && desired != captain) {
            captain.setTarget(desired);
        }

        if (ticks % FLAME_INTERVAL == 0) {
            // Soul fire wreathing the captain's blade.
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                captain.getX(), captain.getY() + 1.4, captain.getZ(),
                2, 0.3, 0.3, 0.3, 0.02);
        }
    }

    /** Whether the captain's bounding box at the given feet position collides with nothing. */
    private boolean isFreeSpot(ServerLevel level, WitherSkeleton captain, double x, double y, double z) {
        double halfWidth = captain.getBbWidth() / 2.0;
        AABB box = new AABB(x - halfWidth, y, z - halfWidth, x + halfWidth, y + captain.getBbHeight(), z + halfWidth);
        return level.noCollision(captain, box);
    }

    /** Resolves the tracked captain from the state tag, or null when gone. */
    private WitherSkeleton resolveCaptain(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        if (!state.contains("captain")) {
            return null;
        }
        UUID id = state.read("captain", UUIDUtil.CODEC).orElse(null);
        if (id != null && level.getEntity(id) instanceof WitherSkeleton captain) {
            return captain;
        }
        return null;
    }

    private static void addModifier(Mob mob, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
                                    Identifier id, double amount) {
        AttributeInstance instance = mob.getAttribute(attribute);
        if (instance != null) {
            instance.addOrUpdateTransientModifier(
                new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_VALUE));
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
        CompoundTag state = ctx.state();
        if (!state.contains("captain")) {
            return;
        }
        UUID captainId = state.read("captain", UUIDUtil.CODEC).orElse(null);
        if (captainId == null) {
            return;
        }
        // The alliance ends with the ability, whichever side is
        // still here.
        ctx.data().removeAlly(captainId);
        WitherSkeleton captain = resolveCaptain(ctx, level);
        if (captain == null) {
            return;
        }
        MobWarData.get(captain).removeAlly(ctx.mob().getUUID());
        if (captain.isAlive()) {
            // The lieutenant sinks back into the soul sand.
            level.sendParticles(ParticleTypes.SOUL, captain.getX(), captain.getY() + 1.0, captain.getZ(),
                8, 0.5, 0.8, 0.5, 0.05);
            captain.discard();
        }
    }
}
