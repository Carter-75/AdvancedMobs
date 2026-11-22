package mod.advancedmobs.ability.elderguardian;

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
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/**
 * Turret Brood (Elder Guardian ability 5 of 5, summon).
 *
 * WHAT IT DOES: two younger guardians rise at the elder
 * guardian's sides and anchor themselves in place, living
 * turrets that pour beam fire into anything the elder has
 * cursed. BUBBLE link beams string each turret back to the
 * elder, and while a turret's beam is locked on, its damage
 * feeds a trickle of healing to its parent. The turrets are
 * frail once focused, but ignoring them means fighting three
 * beams at once.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   elder guardian's current target within 20 blocks (distance
 *   squared 400).
 * - Duration: exactly 600 ticks (30 seconds) from start(), or
 *   until both turrets are dead; dead turrets are not replaced.
 * - The turrets (start()): 2 vanilla guardians, spawned 3.0
 *   blocks to either side of the elder (east and west; the
 *   elder's own position as fallback when a side is blocked),
 *   created with EntitySpawnReason.MOB_SUMMONED and
 *   finalizeSpawn like every other summon in this mod. After
 *   finalizeSpawn their base attribute values are set, the
 *   design's summon sheet: MOVEMENT_SPEED base 0.0 (the anchor:
 *   a guardian with no movement speed cannot swim anywhere) and
 *   MAX_HEALTH base 20.0 (down from the vanilla 30: the frailty
 *   in the design), healed to full. Each turret's spawn position
 *   is recorded in the state tag as its anchor.
 * - Team assignment, per turret, both directions plus the
 *   registry: the caster's data gains the turret as an ally, the
 *   turret's data gains the caster, and the pair is registered
 *   in SummonerTracker (which is also what tags the turret
 *   umw_summoned for the natural spawn blocker, the same flow
 *   Scarab Brood uses). Each turret is ordered onto the caster's
 *   current target at spawn.
 * - Babysitting every 5 ticks: each living turret is re-anchored
 *   (velocity zeroed; if it has drifted more than 0.75 blocks
 *   horizontally or 1.5 vertically from its anchor, it is
 *   snapped back: knockback and currents move it, the anchor
 *   does not care) and re-targeted. The targeting rule: keep the
 *   current target while it is a living enemy; otherwise pick
 *   the nearest enemy within 20 blocks of the turret that
 *   carries MINING_FATIGUE (the elder's cursed, the design's
 *   priority); if none is cursed, fall back to the elder's own
 *   current target when it is an enemy within 24 blocks of the
 *   turret. The beam itself is the turret's vanilla guardian
 *   beam, unchanged: 6 damage per full charge on its target.
 * - The healing link: every 20 ticks, each turret whose vanilla
 *   beam is locked on (Guardian.hasActiveAttackTarget(), the
 *   same read Laser Lattice uses) feeds the elder 1.0 health of
 *   healing. That is the code-level form of "damage dealt by
 *   the turrets feeds healing": a locked beam is a damaging
 *   beam, and the trickle is 1 health per second per working
 *   turret, capped by the elder's max health as heal() always
 *   is. No beam, no trickle.
 * - end(): the alliance records are removed on both sides for
 *   every tracked turret, and each turret still alive is
 *   discarded in a puff of POOF and BUBBLE: the brood is the
 *   ability's, and it does not outlive it. If a turret died in
 *   the fight, end() simply finds nothing to discard for it. If
 *   the caster dies first, the framework termination path runs
 *   this same end(), so the brood never outlives its summoner
 *   either. SummonerTracker records are deliberately not
 *   unregistered here, matching Scarab Brood: that registry
 *   cleans up on the turret's own removal.
 *
 * WHAT IT LOOKS AND SOUNDS LIKE: at start, 10 BUBBLE particles
 * boil up around the elder with an ELDER_GUARDIAN_AMBIENT
 * (volume 1.2, pitch 0.9) as the turrets rise. Every 10 ticks,
 * link beams are drawn from each living turret to the elder: 2
 * BUBBLE particles strung along the line and 1 at the turret.
 * The turrets themselves are plain younger guardians, visibly
 * smaller than the elder, hanging motionless at their anchors
 * while their vanilla beams charge and fire.
 *
 * PERFORMANCE: start() does 2 bounded collision checks and 2
 * spawns, once. tick() returns immediately except every 5th
 * tick (babysit: at most 2 UUID resolves, 2 anchor checks, and
 * a retarget query only for a turret whose target went invalid:
 * one bounded box query over a 40 by 12 by 40 box at most), the
 * 10 tick marks (link particles: at most 2 UUID resolves and 6
 * particles) and the 20 tick marks (the heal read: at most 2
 * synched-data getters). Particle budget per activation: 10 at
 * start plus at most 60 link pulses times 6 = 360, plus at most
 * 2 turrets times 9 at end() = 18, total at most 388 particles.
 *
 * State tag keys: "turrets" (list of compounds, one per turret:
 * "id", the turret's UUID; "ax", "ay", "az", its anchor
 * position).
 */
public final class TurretBroodAbility implements MobAbility {

    static final int DURATION_TICKS = 600;
    private static final int BROOD_COUNT = 2;
    private static final int BABYSIT_INTERVAL = 5;
    private static final double[][] OFFSETS = {{3.0, 0.0}, {-3.0, 0.0}};

    @Override
    public String id() {
        return "turret_brood";
    }

    @Override
    public String displayName() {
        return "Turret Brood";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 400.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            ctx.state().put("turrets", new ListTag());
            return;
        }
        Mob caster = ctx.mob();
        ListTag turrets = new ListTag();
        LivingEntity target = ctx.target();
        for (int i = 0; i < BROOD_COUNT; i++) {
            Guardian turret = EntityTypes.GUARDIAN.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (turret == null) {
                continue;
            }
            double[] offset = OFFSETS[i];
            double spawnX = caster.getX() + offset[0];
            double spawnZ = caster.getZ() + offset[1];
            if (!isFreeSpot(level, turret, spawnX, caster.getY(), spawnZ)) {
                spawnX = caster.getX();
                spawnZ = caster.getZ();
            }
            turret.snapTo(spawnX, caster.getY(), spawnZ, ctx.random().nextFloat() * 360.0f, 0.0f);
            turret.finalizeSpawn(level, level.getCurrentDifficultyAt(turret.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
            if (!level.addFreshEntity(turret)) {
                continue;
            }

            // The summon sheet, applied after finalizeSpawn (see
            // the class comment): anchored and frail.
            AttributeInstance speed = turret.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speed != null) {
                speed.setBaseValue(0.0);
            }
            AttributeInstance health = turret.getAttribute(Attributes.MAX_HEALTH);
            if (health != null) {
                health.setBaseValue(20.0);
            }
            turret.setHealth(20.0f);

            // Team assignment, both directions, plus the
            // summoner registry.
            ctx.data().addAlly(turret.getUUID());
            MobWarData.get(turret).addAlly(caster.getUUID());
            SummonerTracker.registerSummoned(turret.getUUID(), caster.getUUID());
            if (target != null) {
                turret.setTarget(target);
            }
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, turret.getUUID());
            entry.putDouble("ax", turret.getX());
            entry.putDouble("ay", turret.getY());
            entry.putDouble("az", turret.getZ());
            turrets.add(entry);
        }
        ctx.state().put("turrets", turrets);

        // The turrets rise.
        level.sendParticles(ParticleTypes.BUBBLE, caster.getX(), caster.getY() + 1.0, caster.getZ(),
            10, 2.5, 0.8, 2.5, 0.15);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.ELDER_GUARDIAN_AMBIENT, SoundSource.HOSTILE, 1.2f, 0.9f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        boolean babysit = ticks % BABYSIT_INTERVAL == 0;
        boolean link = ticks % 10 == 0;
        boolean heal = ticks % 20 == 0;
        if (!babysit && !link && !heal) {
            return; // Cheap early-out: work runs on the 5, 10 and 20 tick marks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag turrets = ctx.state().getListOrEmpty("turrets");
        for (int i = 0; i < turrets.size(); i++) {
            CompoundTag entry = turrets.getCompoundOrEmpty(i);
            Guardian turret = resolveTurret(level, entry);
            if (turret == null || !turret.isAlive()) {
                continue;
            }
            if (babysit) {
                babysitTurret(ctx, entry, turret);
            }
            if (link) {
                drawLink(ctx, level, turret);
            }
            if (heal && turret.hasActiveAttackTarget()) {
                // The trickle: a locked beam feeds the parent.
                ctx.mob().heal(1.0f);
            }
        }
    }

    /**
     * Re-anchors one turret and keeps its target on the elder's
     * fight, cursed enemies first. See the class comment.
     */
    private void babysitTurret(AbilityContext ctx, CompoundTag entry, Guardian turret) {
        // The anchor: velocity zeroed, drift snapped back.
        turret.setDeltaMovement(0.0, 0.0, 0.0);
        double ax = entry.getDoubleOr("ax", turret.getX());
        double ay = entry.getDoubleOr("ay", turret.getY());
        double az = entry.getDoubleOr("az", turret.getZ());
        double dx = turret.getX() - ax;
        double dz = turret.getZ() - az;
        if (dx * dx + dz * dz > 0.5625 || Math.abs(turret.getY() - ay) > 1.5) {
            turret.snapTo(ax, ay, az, turret.getYRot(), turret.getXRot());
        }

        // The targeting rule: a living enemy target is kept;
        // anything else is re-picked.
        LivingEntity current = turret.getTarget();
        if (current != null && current.isAlive()
            && ElderGuardianAbilitySupport.isEnemy(ctx, current)) {
            return;
        }
        LivingEntity pick = nearestCursedEnemy(ctx, turret);
        if (pick == null) {
            LivingEntity elderTarget = ctx.target();
            if (elderTarget != null && elderTarget != turret
                && ElderGuardianAbilitySupport.isEnemy(ctx, elderTarget)
                && turret.distanceToSqr(elderTarget) <= 576.0) {
                pick = elderTarget;
            }
        }
        turret.setTarget(pick);
    }

    /**
     * The nearest enemy carrying MINING_FATIGUE (the elder's
     * cursed) within 20 blocks of the turret, or null. One
     * bounded box query; runs only for a turret whose target
     * went invalid.
     */
    private LivingEntity nearestCursedEnemy(AbilityContext ctx, Guardian turret) {
        List<LivingEntity> candidates = ElderGuardianAbilitySupport.enemiesInBox(ctx,
            turret.getX(), turret.getY(), turret.getZ(), 20.0, 6.0, 6.0);
        LivingEntity best = null;
        double bestSqr = 400.0;
        for (LivingEntity candidate : candidates) {
            if (!candidate.hasEffect(MobEffects.MINING_FATIGUE)) {
                continue;
            }
            double distSqr = candidate.distanceToSqr(turret);
            if (distSqr < bestSqr) {
                bestSqr = distSqr;
                best = candidate;
            }
        }
        return best;
    }

    /** Draws one turret's BUBBLE link beam back to the elder. */
    private void drawLink(AbilityContext ctx, ServerLevel level, Guardian turret) {
        Mob elder = ctx.mob();
        double x0 = turret.getX(), y0 = turret.getY() + 0.6, z0 = turret.getZ();
        double x1 = elder.getX(), y1 = elder.getY() + 1.2, z1 = elder.getZ();
        for (double t : new double[]{1.0 / 3.0, 2.0 / 3.0}) {
            level.sendParticles(ParticleTypes.BUBBLE,
                x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, z0 + (z1 - z0) * t,
                1, 0.05, 0.05, 0.05, 0.02);
        }
        level.sendParticles(ParticleTypes.BUBBLE, x0, y0, z0, 1, 0.15, 0.15, 0.15, 0.05);
    }

    /** Whether the turret's bounding box at the given feet position collides with nothing. */
    private boolean isFreeSpot(ServerLevel level, Guardian turret, double x, double y, double z) {
        double halfWidth = turret.getBbWidth() / 2.0;
        AABB box = new AABB(x - halfWidth, y, z - halfWidth, x + halfWidth, y + turret.getBbHeight(), z + halfWidth);
        return level.noCollision(turret, box);
    }

    /** Resolves a tracked turret from its brood entry, or null when gone. */
    private Guardian resolveTurret(ServerLevel level, CompoundTag entry) {
        if (!entry.contains("id")) {
            return null;
        }
        UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
        if (id != null && level.getEntity(id) instanceof Guardian guardian) {
            return guardian;
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
        ListTag turrets = ctx.state().getListOrEmpty("turrets");
        for (int i = 0; i < turrets.size(); i++) {
            CompoundTag entry = turrets.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID turretId = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (turretId == null) {
                continue;
            }
            // The alliance ends with the ability, whichever side
            // is still here.
            ctx.data().removeAlly(turretId);
            Guardian turret = resolveTurret(level, entry);
            if (turret == null) {
                continue;
            }
            MobWarData.get(turret).removeAlly(caster.getUUID());
            if (turret.isAlive()) {
                // The turret sinks back into the monument dark.
                level.sendParticles(ParticleTypes.POOF, turret.getX(), turret.getY() + 0.5, turret.getZ(),
                    6, 0.4, 0.4, 0.4, 0.05);
                level.sendParticles(ParticleTypes.BUBBLE, turret.getX(), turret.getY() + 0.5, turret.getZ(),
                    3, 0.4, 0.4, 0.4, 0.05);
                turret.discard();
            }
        }
    }
}
