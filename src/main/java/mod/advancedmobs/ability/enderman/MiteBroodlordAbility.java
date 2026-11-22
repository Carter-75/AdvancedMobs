package mod.advancedmobs.ability.enderman;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Endermite;
import net.minecraft.world.phys.AABB;

import java.util.UUID;

/**
 * Mite Broodlord (Enderman ability 3 of 5, summon).
 *
 * WHAT IT DOES: the enderman's bond with its parasites turns
 * deliberate. PORTAL bursts tear along its body and endermites
 * pour onto the field on its side, a crawling screen that
 * swarms ankles and draws blades while the enderman fights
 * from behind it. And the brood keeps coming: every hit the
 * enderman takes while pressed shakes another mite loose.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the enderman's current target within 14 blocks (distance
 *   squared 196). The brood answers being pressed in a fight.
 * - Duration: exactly 300 ticks (15 seconds) from start().
 * - The first wave (start()): 6 endermites, spawned one per
 *   offset on a ring of radius 1.0 around the enderman (every
 *   60 degrees), each at the first collision-free spot in that
 *   offset then the enderman's own position as fallback,
 *   created with EntitySpawnReason.MOB_SUMMONED and
 *   finalizeSpawn like every other summon in this mod. Each
 *   mite is 1 vanilla endermite: 8 health, 2 damage per bite;
 *   the threat is six of them arriving at once from every
 *   side, not any single mite.
 * - Team assignment, per mite, both directions plus the
 *   registry: the caster's data gains the mite as an ally,
 *   the mite's data gains the caster, and the pair is
 *   registered in SummonerTracker (which is also what tags
 *   the mite umw_summoned for the natural spawn blocker, the
 *   same flow Scarab Brood uses). Each mite is ordered onto
 *   the caster's current target at spawn. This is the
 *   design's "/team team assignment", done through the war
 *   system's ally records, which is what targeting and
 *   friendly fire in this mod actually read.
 * - Babysitting every 5 ticks: each living tracked mite whose
 *   target is empty or is the caster is re-ordered onto the
 *   caster's live target. Endermite AI only hunts players on
 *   its own, so without this the brood would ignore the mob
 *   the enderman is fighting; with it, the swarm stays on the
 *   enderman's fight.
 * - On-hit shedding, in code (the design's "INFESTED style
 *   on-hit spawning via code"; vanilla INFESTED spawns
 *   silverfish, not endermites, so the effect cannot be
 *   reused and the behavior is implemented directly): every 5
 *   ticks the ability compares the enderman's last-hurt-by-mob
 *   timestamp with the stored one. A new timestamp means a hit
 *   landed. If at least 20 ticks passed since the last shed
 *   and fewer than 12 mites are tracked, 1 more endermite
 *   sheds at the enderman's position in a 6 particle PORTAL
 *   burst, allied and ordered like the first wave. At most 6
 *   bonus mites can shed per activation (12 tracked total:
 *   the first wave of 6 plus 6 shed), so a focused beating
 *   grows the screen but never floods the field. The stored
 *   timestamp is initialized in start(), so a hit that landed
 *   before the brood poured out is never answered.
 * - end(): the alliance records are removed on both sides for
 *   every tracked mite, and each mite still alive is discarded
 *   in a puff of 4 PORTAL particles: the brood is the
 *   ability's, and it does not outlive it. If a mite died in
 *   the fight, end() simply finds nothing to discard for it.
 *   If the caster dies first, the framework termination path
 *   runs this same end(), so the brood never outlives its
 *   summoner either. SummonerTracker records are deliberately
 *   not unregistered here, matching Scarab Brood: that
 *   registry cleans up on the mite's own removal.
 *
 * WHAT IT LOOKS LIKE: at start, an 18 particle PORTAL burst
 * tears down the enderman's body as the first wave pours out,
 * and each shed mite arrives in its own small PORTAL burst.
 * The mites are plain endermites, skittering at the target
 * from every side.
 *
 * PERFORMANCE: start() does 6 bounded collision checks and 6
 * spawns, once. tick() returns immediately except every 5th
 * tick, where it resolves at most 12 UUIDs and reads or
 * writes targets and one timestamp only: no entity scans, no
 * allocations beyond the resolved references. Particle budget
 * per activation: 18 at start, sheds at most 6 times 6 = 36,
 * end() at most 12 mites times 4 = 48, total at most 102
 * particles.
 *
 * State tag keys: "brood" (list of compounds, one per mite:
 * "id", the mite's UUID), "hurt_ts" (the last-hurt-by-mob
 * timestamp already answered), "last_shed" (tick of the last
 * on-hit shed).
 */
public final class MiteBroodlordAbility implements MobAbility {

    static final int DURATION_TICKS = 300;
    private static final int FIRST_WAVE = 6;
    private static final int MAX_MITES = 12;
    private static final int BABYSIT_INTERVAL = 5;
    private static final int SHED_COOLDOWN = 20;
    private static final double RING_RADIUS = 1.0;

    @Override
    public String id() {
        return "mite_broodlord";
    }

    @Override
    public String displayName() {
        return "Mite Broodlord";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        CompoundTag state = ctx.state();
        state.put("brood", new ListTag());
        state.putInt("hurt_ts", ctx.mob().getLastHurtByMobTimestamp());
        state.putInt("last_shed", -SHED_COOLDOWN);
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        for (int i = 0; i < FIRST_WAVE; i++) {
            double angle = i * (Math.PI * 2.0 / FIRST_WAVE);
            spawnMite(ctx, level, caster.getX() + Math.cos(angle) * RING_RADIUS,
                caster.getZ() + Math.sin(angle) * RING_RADIUS);
        }
        // The first wave pours out of the void along its body.
        level.sendParticles(ParticleTypes.PORTAL, caster.getX(), caster.getY() + 1.5, caster.getZ(),
            18, 0.5, 1.2, 0.5, 0.4);
    }

    /**
     * Spawns one brood mite near the given spot (the caster's
     * own position as fallback), allies it both directions,
     * registers it in SummonerTracker, orders it onto the
     * caster's live target, and tracks it in the state tag.
     */
    private void spawnMite(AbilityContext ctx, ServerLevel level, double spawnX, double spawnZ) {
        Mob caster = ctx.mob();
        Endermite mite = EntityTypes.ENDERMITE.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (mite == null) {
            return;
        }
        if (!isFreeSpot(level, mite, spawnX, caster.getY(), spawnZ)) {
            spawnX = caster.getX();
            spawnZ = caster.getZ();
        }
        mite.snapTo(spawnX, caster.getY(), spawnZ, ctx.random().nextFloat() * 360.0f, 0.0f);
        mite.finalizeSpawn(level, level.getCurrentDifficultyAt(mite.blockPosition()),
            EntitySpawnReason.MOB_SUMMONED, null);
        if (!level.addFreshEntity(mite)) {
            return;
        }

        // Team assignment, both directions, plus the summoner registry.
        ctx.data().addAlly(mite.getUUID());
        MobWarData.get(mite).addAlly(caster.getUUID());
        SummonerTracker.registerSummoned(mite.getUUID(), caster.getUUID());
        LivingEntity target = ctx.target();
        if (target != null) {
            mite.setTarget(target);
        }
        CompoundTag entry = new CompoundTag();
        entry.store("id", UUIDUtil.CODEC, mite.getUUID());
        ListTag brood = ctx.state().getListOrEmpty("brood");
        brood.add(entry);
        ctx.state().put("brood", brood);
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % BABYSIT_INTERVAL != 0) {
            return; // Cheap early-out: babysitting and shedding run every 5 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        LivingEntity desired = ctx.target();
        ListTag brood = state.getListOrEmpty("brood");
        for (int i = 0; i < brood.size(); i++) {
            Endermite mite = resolveMite(level, brood.getCompoundOrEmpty(i));
            if (mite == null || !mite.isAlive()) {
                continue;
            }
            LivingEntity miteTarget = mite.getTarget();
            if ((miteTarget == null || miteTarget == ctx.mob())
                && desired != null && desired != mite) {
                mite.setTarget(desired);
            }
        }

        // On-hit shedding: a fresh hit on the enderman shakes a
        // mite loose, cooldown and cap permitting.
        Mob mob = ctx.mob();
        int timestamp = mob.getLastHurtByMobTimestamp();
        if (timestamp != state.getIntOr("hurt_ts", -1)) {
            state.putInt("hurt_ts", timestamp);
            int ticks = ctx.ticksActive();
            if (brood.size() < MAX_MITES && ticks - state.getIntOr("last_shed", 0) >= SHED_COOLDOWN) {
                spawnMite(ctx, level, mob.getX(), mob.getZ());
                level.sendParticles(ParticleTypes.PORTAL, mob.getX(), mob.getY() + 1.2, mob.getZ(),
                    6, 0.4, 0.8, 0.4, 0.3);
                state.putInt("last_shed", ticks);
            }
        }
    }

    /** Whether the mite's bounding box at the given feet position collides with nothing. */
    private boolean isFreeSpot(ServerLevel level, Endermite mite, double x, double y, double z) {
        double halfWidth = mite.getBbWidth() / 2.0;
        AABB box = new AABB(x - halfWidth, y, z - halfWidth, x + halfWidth, y + mite.getBbHeight(), z + halfWidth);
        return level.noCollision(mite, box);
    }

    /** Resolves a tracked mite from its brood entry, or null when gone. */
    private Endermite resolveMite(ServerLevel level, CompoundTag entry) {
        if (!entry.contains("id")) {
            return null;
        }
        UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
        if (id != null && level.getEntity(id) instanceof Endermite mite) {
            return mite;
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
        ListTag brood = ctx.state().getListOrEmpty("brood");
        for (int i = 0; i < brood.size(); i++) {
            CompoundTag entry = brood.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID miteId = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (miteId == null) {
                continue;
            }
            // The alliance ends with the ability, whichever side is
            // still here.
            ctx.data().removeAlly(miteId);
            Endermite mite = resolveMite(level, entry);
            if (mite == null) {
                continue;
            }
            MobWarData.get(mite).removeAlly(caster.getUUID());
            if (mite.isAlive()) {
                // The brood slips back into the void it poured from.
                level.sendParticles(ParticleTypes.PORTAL, mite.getX(), mite.getY() + 0.2, mite.getZ(),
                    4, 0.25, 0.2, 0.25, 0.3);
                mite.discard();
            }
        }
    }
}
