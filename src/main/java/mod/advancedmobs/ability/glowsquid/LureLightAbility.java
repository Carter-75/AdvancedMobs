package mod.advancedmobs.ability.glowsquid;

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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.squid.GlowSquid;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Lure Light (Glow Squid ability 5 of 5, summon /
 * control).
 *
 * WHAT IT DOES: the glow squid leaves a single
 * perfect light behind: a summoned glow squid decoy
 * burning at maximum brightness under a halo of glow
 * particles, hanging motionless in the water column
 * where the real one was. Enemy fish and predators
 * are drawn to investigate it and cluster around it,
 * packed tight and lit up, exactly where the real
 * glow squid wants them (a Starfall Ink on the
 * cluster is the classic follow-up, though the
 * framework picks abilities on its own schedule).
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat
 *   gate, plus the squid's current target within 14
 *   blocks (distance squared 196), plus the squid in
 *   water.
 * - The swap (start()): one real GlowSquid is
 *   created with EntitySpawnReason.MOB_SUMMONED and
 *   finalizeSpawn like every other summon in this
 *   mod, placed at the caster's exact position (the
 *   anchor, recorded in the state tag). Its
 *   MAX_HEALTH base is set to 10.0, the wild glow
 *   squid value, so it survives a few hits while the
 *   cluster forms, and it gains GLOWING for 440
 *   ticks: the design's maximum brightness, an
 *   outline that never dims for the whole lure. The
 *   pair is allied in both directions in MobWarData
 *   and registered in SummonerTracker (the design's
 *   team line), so the war system never turns the
 *   two on each other. GLOW_SQUID_SQUIRT plays
 *   (volume 1.2, pitch 1.0) over a 12 GLOW burst, and
 *   the real squid jets directly away from its
 *   target at 1.0 blocks per tick.
 * - Station keeping (every tick): the decoy is
 *   steered back onto the anchor at 0.3 blocks per
 *   tick, so it hangs nearly motionless in the
 *   column while vanilla wander tugs at it. The
 *   halo: 3 GLOW every 4 ticks in a 0.6 block sphere
 *   around the decoy.
 * - The draw (every 10 ticks): one bounded query,
 *   radius 10.0 around the decoy, vertical reach 4.0
 *   both ways. Every enemy Mob (the package enemy
 *   rule) whose current target is the caster, or
 *   which has no target at all, is retargeted onto
 *   the decoy: the design's target redirection in
 *   code. Players are never retargeted (a player's
 *   targeting is their own choice). Enemies within
 *   6.0 blocks of the decoy are also pulled toward
 *   it: 0.15 blocks per tick of velocity added
 *   toward the decoy's position, hurtMarked set, so
 *   even mobs whose AI will not hold a target drift
 *   in and pack around the light.
 * - Lit up (every 20 ticks): one bounded query,
 *   radius 6.0 around the decoy: every enemy in it
 *   gains GLOWING refreshed to 40 ticks. The cluster
 *   around the lure is outlined for everyone, the
 *   setup the design wants.
 * - If the decoy dies or is removed, the halo, draw,
 *   and lighting simply stop; there is no death
 *   burst (the design gives the lure none). The
 *   ability runs out its duration either way.
 * - Duration: 400 ticks, hard finish.
 *
 * WHAT IT LOOKS LIKE: one flawless bright squid
 * hanging still in dark water under a shimmer of
 * glow motes, and every hostile thing in the area
 * slowly converging on it, outlined and packed.
 *
 * PERFORMANCE: start() does 1 spawn, once. Per tick:
 * one steering write on the decoy. The halo is 3
 * sends every 4 ticks; the draw is one bounded query
 * every 10 ticks; the lighting is one bounded query
 * every 20 ticks. Particle budget per activation:
 * 12 at start, at most 100 halo pulses times 3 =
 * 300, 10 end puff, total at most 322.
 *
 * TERRAIN: this ability changes no blocks; the lure
 * is an entity and particles, so the mobGriefing
 * gate does not apply.
 *
 * CLEANUP: end() removes the alliance records on
 * both sides and discards a surviving decoy in a
 * puff of 10 GLOW. If the caster dies first, the
 * framework termination path runs this same end(),
 * so the decoy never outlives its maker.
 * SummonerTracker records are deliberately not
 * unregistered, matching the other summons in this
 * mod: that registry cleans up on the member's own
 * removal.
 *
 * State tag keys: "decoy" (list with one compound:
 * "id", the decoy's UUID), "ax", "ay", "az" (the
 * anchor, the swap point).
 */
public final class LureLightAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final double DECOY_MAX_HEALTH = 10.0;
    private static final double DRAW_RADIUS = 10.0;
    private static final double CLUSTER_RADIUS = 6.0;
    private static final double PULL_SPEED = 0.15;

    @Override
    public String id() {
        return "lure_light";
    }

    @Override
    public String displayName() {
        return "Lure Light";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().isInWater()
            && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        state.put("decoy", new ListTag());
        state.putDouble("ax", caster.getX());
        state.putDouble("ay", caster.getY());
        state.putDouble("az", caster.getZ());
        if (level == null) {
            return;
        }

        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.GLOW_SQUID_SQUIRT, SoundSource.HOSTILE, 1.2f, 1.0f);
        level.sendParticles(ParticleTypes.GLOW, caster.getX(), caster.getY() + 0.3, caster.getZ(),
            12, 0.5, 0.4, 0.5, 0.05);

        GlowSquid decoy = EntityTypes.GLOW_SQUID.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (decoy != null) {
            decoy.snapTo(caster.getX(), caster.getY(), caster.getZ(), caster.getYRot(), 0.0f);
            if (decoy.getAttribute(Attributes.MAX_HEALTH) != null) {
                decoy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(DECOY_MAX_HEALTH);
            }
            decoy.setHealth((float) DECOY_MAX_HEALTH);
            decoy.finalizeSpawn(level, level.getCurrentDifficultyAt(decoy.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
            if (level.addFreshEntity(decoy)) {
                decoy.addEffect(new MobEffectInstance(MobEffects.GLOWING, 440, 0));
                // Team assignment, both directions, plus the summoner registry.
                ctx.data().addAlly(decoy.getUUID());
                MobWarData.get(decoy).addAlly(caster.getUUID());
                SummonerTracker.registerSummoned(decoy.getUUID(), caster.getUUID());
                GlowSquidAbilitySupport.trackEntity(state, "decoy", decoy);
            }
        }

        // The real squid slips away.
        LivingEntity target = ctx.target();
        if (target != null) {
            double awayX = caster.getX() - target.getX();
            double awayZ = caster.getZ() - target.getZ();
            double length = Math.sqrt(awayX * awayX + awayZ * awayZ);
            if (length > 0.001) {
                caster.setDeltaMovement(awayX / length * 1.0, 0.1, awayZ / length * 1.0);
                caster.hurtMarked = true;
            }
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        CompoundTag state = ctx.state();
        GlowSquid decoy = decoy(ctx, level);
        if (decoy == null || !decoy.isAlive()) {
            return;
        }

        // Station keeping: hang nearly still at the anchor.
        GlowSquidAbilitySupport.steerToward(decoy,
            state.getDoubleOr("ax", decoy.getX()),
            state.getDoubleOr("ay", decoy.getY()),
            state.getDoubleOr("az", decoy.getZ()), 0.3);

        // The halo of glow motes.
        if (ticks % 4 == 0) {
            level.sendParticles(ParticleTypes.GLOW, decoy.getX(), decoy.getY() + 0.3, decoy.getZ(),
                3, 0.6, 0.5, 0.6, 0.02);
        }

        if (ticks % 10 == 0) {
            draw(ctx, decoy);
        }
        if (ticks % 20 == 0) {
            // Lit up: the cluster around the lure is outlined for everyone.
            List<LivingEntity> cluster = GlowSquidAbilitySupport.enemiesInBox(ctx,
                decoy.getX(), decoy.getY(), decoy.getZ(), CLUSTER_RADIUS, 4.0, 4.0);
            for (LivingEntity enemy : cluster) {
                enemy.addEffect(new MobEffectInstance(MobEffects.GLOWING, 40, 0));
            }
        }
    }

    /** The draw: redirect enemy Mobs onto the lure and drift the close ones in. One bounded query. */
    private void draw(AbilityContext ctx, GlowSquid decoy) {
        Mob caster = ctx.mob();
        List<LivingEntity> nearby = GlowSquidAbilitySupport.enemiesInBox(ctx,
            decoy.getX(), decoy.getY(), decoy.getZ(), DRAW_RADIUS, 4.0, 4.0);
        for (LivingEntity enemy : nearby) {
            if (enemy instanceof Mob enemyMob
                && (enemyMob.getTarget() == caster || enemyMob.getTarget() == null)) {
                enemyMob.setTarget(decoy);
            }
            // The drift in: pack the close ones around the light.
            double distSqr = enemy.distanceToSqr(decoy);
            if (distSqr <= CLUSTER_RADIUS * CLUSTER_RADIUS && distSqr > 1.0) {
                double dist = Math.sqrt(distSqr);
                Vec3 motion = enemy.getDeltaMovement();
                enemy.setDeltaMovement(
                    motion.x + (decoy.getX() - enemy.getX()) / dist * PULL_SPEED,
                    motion.y + (decoy.getY() - enemy.getY()) / dist * PULL_SPEED * 0.5,
                    motion.z + (decoy.getZ() - enemy.getZ()) / dist * PULL_SPEED);
                enemy.hurtMarked = true;
            }
        }
    }

    /** The tracked decoy, or null when it does not resolve right now. */
    private GlowSquid decoy(AbilityContext ctx, ServerLevel level) {
        ListTag list = ctx.state().getListOrEmpty("decoy");
        if (list.isEmpty()) {
            return null;
        }
        Entity found = GlowSquidAbilitySupport.resolveTracked(level, list.getCompoundOrEmpty(0));
        return found instanceof GlowSquid glowSquid ? glowSquid : null;
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
        ListTag list = ctx.state().getListOrEmpty("decoy");
        if (list.isEmpty()) {
            return;
        }
        CompoundTag entry = list.getCompoundOrEmpty(0);
        UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
        if (id == null) {
            return;
        }
        ctx.data().removeAlly(id);
        Entity found = level.getEntity(id);
        if (found instanceof GlowSquid decoy) {
            MobWarData.get(decoy).removeAlly(caster.getUUID());
            if (decoy.isAlive()) {
                level.sendParticles(ParticleTypes.GLOW,
                    decoy.getX(), decoy.getY() + 0.3, decoy.getZ(),
                    10, 0.5, 0.4, 0.5, 0.05);
                decoy.discard();
            }
        }
    }
}
