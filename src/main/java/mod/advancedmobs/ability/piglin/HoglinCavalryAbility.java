package mod.advancedmobs.ability.piglin;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.phys.AABB;

import java.util.UUID;

/**
 * Hoglin Cavalry (Piglin ability 5 of 5, summon, mount).
 *
 * WHAT IT DOES: the piglin whistles, and a hoglin thunders in
 * from the edge of the fight. The hoglin charges straight to
 * its rider, the piglin vaults onto its back, and the two
 * become a single cavalry unit: the hoglin charges and gores
 * with SPEED while the piglin fights from its back, the pair
 * sharing the hoglin's knockback resistance so the unit is
 * hard to stop and harder to unhorse. Kill the hoglin and the
 * piglin fights on alone, furious.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the piglin's current target within 20 blocks (distance
 *   squared 400), plus the piglin is not already riding
 *   anything.
 * - The summon (start()): one hoglin, created with
 *   EntitySpawnReason.MOB_SUMMONED and finalizeSpawn like
 *   every other summon in this mod, at the first
 *   collision-free spot 9, then 6, then 3 blocks from the
 *   piglin on the line AWAY from its target (the edge of the
 *   fight), at the piglin's own height; the piglin's position
 *   is the last fallback. It is set immune to zombification
 *   (a war-trained mount does not turn mid-charge, whatever
 *   dimension the fight is in), allied to the piglin in both
 *   directions in MobWarData, registered in SummonerTracker
 *   (which also tags it umw_summoned for the natural spawn
 *   blocker), ordered onto the piglin's current target, and
 *   given SPEED I for 200 ticks, refreshed as below. A
 *   PIGLIN_CELEBRATE whistle plays at the piglin and a
 *   HOGLIN_AMBIENT answers from the mount. If the hoglin
 *   cannot be created or added at all, the activation stores
 *   "no_mount" and ends itself at tick 20 having done nothing
 *   but whistle: a spent cooldown, never a wedge.
 * - Phase 0, the thunder-in (checked every 5 ticks): the
 *   hoglin's navigation is driven to the piglin at speed 1.5,
 *   with 2 DUST_PLUME puffs at its feet per check. When the
 *   hoglin is within 2.6 blocks of the piglin, or at tick 60
 *   regardless, the piglin mounts: startRiding with force.
 *   If that refuses, the piglin teleports onto the hoglin's
 *   position and tries once more; if it still refuses, the
 *   pair drops to phase 2 and fights side by side (the
 *   documented fallback: some vehicle pairings refuse
 *   passengers, and a cavalry that cannot form still leaves
 *   a summoned hoglin in the fight).
 * - The mounting (phase 1): the pair share the hoglin's
 *   knockback resistance, exactly: the hoglin's current
 *   KNOCKBACK_RESISTANCE attribute value is read, the
 *   piglin's own current value is subtracted, and the
 *   difference (when above 0.01) is applied to the piglin as
 *   a transient ADD_VALUE modifier (id
 *   advancedmobs:hoglin_cavalry_knockback), removed in end()
 *   or the moment the mount is lost. A hoglin's base value is
 *   0.6 and a piglin's is 0.0, so in practice the rider gains
 *   the full 0.6 while mounted.
 * - Phases 1 and 2, the unit (checked every 5 ticks): combined
 *   targeting: the hoglin is ordered onto the piglin's live
 *   framework target whenever its own target differs, so the
 *   mount gores what the rider is fighting while the piglin
 *   shoots or swings from its back with its own AI untouched.
 *   Every 20 ticks the mount's SPEED I is refreshed for 60
 *   ticks. If the piglin is found dismounted in phase 1
 *   (knocked off, or the pairing broke), the knockback
 *   modifier comes off and the pair drops to phase 2.
 * - Losing the mount (polled every tick, one UUID resolve):
 *   when the hoglin is dead, removed, or unresolvable, the
 *   piglin turns furious: STRENGTH I for 200 ticks and a
 *   PIGLIN_ANGRY roar, applied once, in tick, NOT tracked for
 *   removal: it is the aftermath, and end() deliberately
 *   leaves it running (the Colossal Grief end-debuff
 *   precedent, inverted). The ability then ends 20 ticks
 *   later. The knockback modifier comes off at the same
 *   moment, since there is no mount left to share it.
 * - Duration: at most 1200 ticks (60 seconds) while the mount
 *   lives.
 * - end(): the knockback modifier is removed if still on. If
 *   the mount is still alive: the piglin dismounts, the
 *   alliance records are removed on both sides, and the
 *   hoglin is discarded in 8 POOF particles: the mount is the
 *   ability's, and it does not outlive it (the Scarab Brood
 *   pattern). If the mount is already gone, only the
 *   caster-side alliance record is removed, by stored UUID.
 *   If the piglin dies first, the framework termination path
 *   runs this same end(), so the mount never outlives its
 *   rider either. SummonerTracker records are deliberately
 *   not unregistered, matching the other summons: that
 *   registry cleans up on the mount's own removal.
 *
 * WHAT IT LOOKS LIKE: a whistle, a HOGLIN_AMBIENT bellow from
 * the edge of the fight, dust pluming off the hoglin's hooves
 * as it thunders in, then the piglin up on its back riding the
 * charge down, the two moving as one animal with a rider. When
 * the hoglin falls, the piglin's roar and the STRENGTH shimmer
 * say the rest.
 *
 * PERFORMANCE: per tick the cost is one UUID resolve of the
 * mount. Behavior runs every 5 ticks (one navigation write or
 * one target compare and write, one distance check), the
 * SPEED refresh every 20 ticks, and the mount attempt happens
 * at most twice per activation. No entity scans anywhere: the
 * only queries are the at most 3 collision checks at spawn.
 * Particle budget per activation: at most 12 approach checks
 * times 2 DUST_PLUME = 24, plus 8 POOF at end, total at most
 * 32 particles.
 *
 * State tag keys: "mount" (the hoglin's UUID), "phase" (0
 * approach, 1 mounted, 2 side by side), "fury" (the
 * activation tick the mount was lost, absent while it lives),
 * "kb" (whether the shared knockback modifier is on), and
 * "no_mount" (the failed-summon flag).
 */
public final class HoglinCavalryAbility implements MobAbility {

    static final int MAX_TICKS = 1200;
    private static final int CHECK_INTERVAL = 5;
    private static final int MOUNT_BY_TICK = 60;
    private static final int FURY_END_DELAY = 20;
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "hoglin_cavalry_knockback");

    @Override
    public String id() {
        return "hoglin_cavalry";
    }

    @Override
    public String displayName() {
        return "Hoglin Cavalry";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 400.0
            && ctx.mob().getVehicle() == null;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        ctx.state().putInt("phase", 0);
        ctx.state().putBoolean("kb", false);
        if (level == null) {
            ctx.state().putBoolean("no_mount", true);
            return;
        }
        Mob caster = ctx.mob();
        LivingEntity target = ctx.target();

        Hoglin mount = EntityTypes.HOGLIN.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (mount == null) {
            ctx.state().putBoolean("no_mount", true);
            return;
        }

        // Spawn at the edge of the fight: on the line away from
        // the target, first free spot of 9, 6, then 3 blocks.
        double awayX = 1.0;
        double awayZ = 0.0;
        if (target != null) {
            double dx = caster.getX() - target.getX();
            double dz = caster.getZ() - target.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 0.01) {
                awayX = dx / len;
                awayZ = dz / len;
            }
        } else {
            double angle = ctx.random().nextDouble() * Math.PI * 2.0;
            awayX = Math.cos(angle);
            awayZ = Math.sin(angle);
        }
        double spawnX = caster.getX();
        double spawnZ = caster.getZ();
        for (double dist : new double[]{9.0, 6.0, 3.0}) {
            double candX = caster.getX() + awayX * dist;
            double candZ = caster.getZ() + awayZ * dist;
            if (isFreeSpot(level, mount, candX, caster.getY(), candZ)) {
                spawnX = candX;
                spawnZ = candZ;
                break;
            }
        }
        mount.snapTo(spawnX, caster.getY(), spawnZ, ctx.random().nextFloat() * 360.0f, 0.0f);
        mount.finalizeSpawn(level, level.getCurrentDifficultyAt(mount.blockPosition()),
            EntitySpawnReason.MOB_SUMMONED, null);
        mount.setImmuneToZombification(true);
        if (!level.addFreshEntity(mount)) {
            ctx.state().putBoolean("no_mount", true);
            return;
        }

        // Team assignment, both directions, plus the summoner registry.
        ctx.data().addAlly(mount.getUUID());
        MobWarData.get(mount).addAlly(caster.getUUID());
        SummonerTracker.registerSummoned(mount.getUUID(), caster.getUUID());
        if (target != null) {
            mount.setTarget(target);
        }
        mount.addEffect(new MobEffectInstance(MobEffects.SPEED, 200, 0));
        ctx.state().store("mount", UUIDUtil.CODEC, mount.getUUID());

        // The whistle and the answer.
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.PIGLIN_CELEBRATE,
            SoundSource.HOSTILE, 1.0f, 1.3f);
        level.playSound(null, mount.getX(), mount.getY(), mount.getZ(), SoundEvents.HOGLIN_AMBIENT,
            SoundSource.HOSTILE, 1.2f, 0.9f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.state().getBooleanOr("no_mount", false)) {
            return; // The summon failed at start; isFinished() ends this quickly.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        Hoglin mount = resolveMount(ctx, level);

        if (mount == null || !mount.isAlive()) {
            onMountLost(ctx, level);
            return;
        }

        if (ctx.ticksActive() % CHECK_INTERVAL != 0) {
            return; // Cheap early-out: behavior runs every 5 ticks only.
        }
        int phase = ctx.state().getIntOr("phase", 0);
        if (phase == 0) {
            approachAndMount(ctx, level, mount);
        } else {
            rideAsUnit(ctx, mount, phase);
        }
        if (ctx.ticksActive() % 20 == 0) {
            mount.addEffect(new MobEffectInstance(MobEffects.SPEED, 60, 0));
        }
    }

    /** Phase 0: the hoglin thunders in, then the piglin vaults on (see the class comment). */
    private void approachAndMount(AbilityContext ctx, ServerLevel level, Hoglin mount) {
        Mob caster = ctx.mob();
        if (mount.distanceToSqr(caster) > 2.6 * 2.6 && ctx.ticksActive() < MOUNT_BY_TICK) {
            mount.getNavigation().moveTo(caster.getX(), caster.getY(), caster.getZ(), 1.5);
            level.sendParticles(ParticleTypes.DUST_PLUME, mount.getX(), mount.getY() + 0.2, mount.getZ(),
                2, 0.5, 0.15, 0.5, 0.05);
            return;
        }
        boolean mounted = caster.startRiding(mount, true, true);
        if (!mounted) {
            // One retry from directly on top of the mount.
            caster.teleportTo(mount.getX(), mount.getY(), mount.getZ());
            mounted = caster.startRiding(mount, true, true);
        }
        if (mounted) {
            ctx.state().putInt("phase", 1);
            shareKnockbackResistance(ctx, mount);
        } else {
            // The pairing refused: they fight side by side.
            ctx.state().putInt("phase", 2);
        }
    }

    /** Applies the shared knockback resistance modifier to the rider (see the class comment). */
    private void shareKnockbackResistance(AbilityContext ctx, Hoglin mount) {
        Mob caster = ctx.mob();
        double mountValue = mount.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE);
        double casterValue = caster.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE);
        double delta = mountValue - casterValue;
        if (delta > 0.01) {
            PiglinAbilitySupport.addModifier(caster, Attributes.KNOCKBACK_RESISTANCE,
                new AttributeModifier(KNOCKBACK_ID, delta, AttributeModifier.Operation.ADD_VALUE));
            ctx.state().putBoolean("kb", true);
        }
    }

    /** Phases 1 and 2: combined targeting and mount-state upkeep (see the class comment). */
    private void rideAsUnit(AbilityContext ctx, Hoglin mount, int phase) {
        Mob caster = ctx.mob();
        if (phase == 1 && caster.getVehicle() != mount) {
            // Knocked off or the pairing broke: the sharing ends.
            dropSharedKnockback(ctx);
            ctx.state().putInt("phase", 2);
        }
        LivingEntity target = ctx.target();
        if (target != null && mount.getTarget() != target) {
            mount.setTarget(target);
        }
    }

    /** The mount is gone: fury, once (see the class comment). */
    private void onMountLost(AbilityContext ctx, ServerLevel level) {
        if (ctx.state().contains("fury")) {
            return;
        }
        ctx.state().putInt("fury", ctx.ticksActive());
        dropSharedKnockback(ctx);
        Mob caster = ctx.mob();
        caster.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 200, 0));
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.PIGLIN_ANGRY,
            SoundSource.HOSTILE, 1.2f, 0.9f);
    }

    /** Removes the shared knockback modifier when it is on. */
    private void dropSharedKnockback(AbilityContext ctx) {
        if (ctx.state().getBooleanOr("kb", false)) {
            PiglinAbilitySupport.removeModifier(ctx.mob(), Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_ID);
            ctx.state().putBoolean("kb", false);
        }
    }

    /** Resolves the tracked mount from the state tag, or null when gone. */
    private Hoglin resolveMount(AbilityContext ctx, ServerLevel level) {
        if (!ctx.state().contains("mount")) {
            return null;
        }
        UUID id = ctx.state().read("mount", UUIDUtil.CODEC).orElse(null);
        if (id != null && level.getEntity(id) instanceof Hoglin mount) {
            return mount;
        }
        return null;
    }

    /** Whether the hoglin's bounding box at the given feet position collides with nothing. */
    private boolean isFreeSpot(ServerLevel level, Hoglin mount, double x, double y, double z) {
        double halfWidth = mount.getBbWidth() / 2.0;
        AABB box = new AABB(x - halfWidth, y, z - halfWidth, x + halfWidth, y + mount.getBbHeight(), z + halfWidth);
        return level.noCollision(mount, box);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        if (ctx.state().getBooleanOr("no_mount", false)) {
            return ctx.ticksActive() >= 20;
        }
        if (ctx.state().contains("fury")) {
            return ctx.ticksActive() >= ctx.state().getIntOr("fury", 0) + FURY_END_DELAY;
        }
        return ctx.ticksActive() >= MAX_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        dropSharedKnockback(ctx);

        if (!ctx.state().contains("mount")) {
            return;
        }
        UUID mountId = ctx.state().read("mount", UUIDUtil.CODEC).orElse(null);
        if (mountId == null) {
            return;
        }
        // The caster-side alliance ends with the ability either way.
        ctx.data().removeAlly(mountId);

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Entity entity = level.getEntity(mountId);
        if (entity instanceof Hoglin mount && mount.isAlive()) {
            if (caster.getVehicle() == mount) {
                caster.stopRiding();
            }
            MobWarData.get(mount).removeAlly(caster.getUUID());
            // The mount thunders back out of the fight.
            level.sendParticles(ParticleTypes.POOF, mount.getX(), mount.getY() + 0.8, mount.getZ(),
                8, 0.6, 0.5, 0.6, 0.08);
            mount.discard();
        }
    }
}
