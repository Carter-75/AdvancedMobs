package mod.advancedmobs.ability.ocelot;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.particles.ParticleTypes;
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
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.feline.Ocelot;
import net.minecraft.world.phys.Vec3;

/**
 * Cub Guard (Ocelot ability 4 of 5, summon / defense).
 *
 * WHAT IT DOES: the ocelot calls her cubs: two small
 * ocelots that stay glued to her flanks like a bodyguard
 * detail. While at least one cub lives, the mother fights
 * harder and shrugs off hits, and the cubs dart in to nip
 * at whatever she is attacking before scurrying back to
 * her side.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the ocelot's current target within 16 blocks
 *   (distance squared 256).
 * - The call (start()): OCELOT_AMBIENT at volume 1.5,
 *   pitch 1.3. Two cubs are summoned 1.8 blocks to the
 *   mother's left and right (her look direction rotated
 *   plus and minus 90 degrees), each landing height from
 *   one bounded surface scan. Each cub is a baby ocelot
 *   (age -24000) spawned with MOB_SUMMONED and
 *   finalizeSpawn, with the design's juvenile scale
 *   carried as a PERMANENT base value on the new mob (the
 *   Warren Call and Alley Cat Swarm precedent): SCALE
 *   base 0.7. Each cub is allied with the mother in both
 *   directions in MobWarData and registered in
 *   SummonerTracker, and is tracked in the state tag. 6
 *   POOF motes burst at each arrival.
 * - The mother's guard (tick(), every 20 ticks): while at
 *   least one cub is confirmed alive, the mother gains
 *   RESISTANCE I and STRENGTH I refreshed to 40 ticks
 *   (the design's resistance and strength; STRENGTH rides
 *   her real ATTACK_DAMAGE attribute, so her own strikes
 *   hit harder while the guard stands). The refresh only
 *   happens while a cub lives: the moment both cubs are
 *   gone the effects stop being refreshed and fade on
 *   their own clocks, and the ability ends (below).
 * - The cubs (tick(), babysit every 5 ticks): each living
 *   cub works one of two jobs, checked in this order. THE
 *   NIP: if the mother's live target is within 6 blocks of
 *   the cub, the cub darts in, navigation-driven at speed
 *   1.4, and within 1.5 blocks (distance squared 2.25) it
 *   nips the target for 1.0 damage through a mob attack
 *   source credited to that cub, at most once per cub per
 *   15 ticks (the per-cub clock is stored in the state tag
 *   by cub list index). THE FLANK: otherwise the cub holds
 *   its station, the point 1.8 blocks to the mother's
 *   corresponding side, recomputed each babysit from her
 *   live position and facing: more than 1.0 blocks from
 *   the station, it navigation-drives there at speed 1.4;
 *   inside 1.0 blocks it stops. The cubs' own targets are
 *   set to the mother's target while nipping and cleared
 *   while flanking, so their AI agrees with the code.
 * - Cub deaths: the first babysit that finds a cub dead or
 *   gone bursts 4 POOF motes at the mother for that cub,
 *   once per cub (a per-cub flag in the state tag).
 * - Duration: exactly 400 ticks (20 seconds), ending
 *   early when both cubs are confirmed dead or gone (the
 *   babysit sets the "cubs_gone" flag).
 * - end(): every surviving cub is dismissed in 4 POOF
 *   motes and discarded, and the ally records are removed
 *   in both directions. The mother's guard effects are NOT
 *   stripped: their last refresh rides its own 40 tick
 *   clock out, the way the Cat package's escape effects do
 *   (documented there); the guard was earned while the
 *   cubs stood. Idempotent.
 *
 * WHAT IT LOOKS LIKE: a yowl, two puffs of dust at the
 * mother's flanks, and two kitten-sized ocelots pacing her
 * sides step for step. Every few seconds one breaks
 * formation, darts at her target's ankles, nips, and is
 * back at her flank before the target can turn. While they
 * live she hits harder and takes hits like they are
 * nothing; when the second cub falls, the fight visibly
 * goes out of her within two seconds.
 *
 * PERFORMANCE: start() is 2 spawns, 2 surface scans of at
 * most 12 block reads, and 2 bursts. tick() returns
 * immediately except every 5th tick (babysit: resolves at
 * most 2 UUIDs, at most 2 anchor computations, 2 distance
 * checks, and at most 2 damage calls) and every 20th tick
 * (the guard refresh: at most 2 effect applies, and only
 * when the babysit has confirmed a living cub). Particle
 * budget per activation: 2 times 6 at start = 12, cub
 * deaths at most 2 times 4 = 8, end at most 2 times 4 = 8,
 * total at most 28 particles.
 *
 * TERRAIN: this ability changes no blocks; the
 * mobGriefing gate does not apply.
 *
 * State tag keys: "cubs" (list of compounds, one per cub:
 * "id", its UUID), "nip_0" and "nip_1" (the next tick each
 * cub may nip again, by cub list index), "cub_gone_0" and
 * "cub_gone_1" (1 once that cub's death burst has fired),
 * "any_cub_alive" (1 while the last babysit found a living
 * cub; gates the guard refresh), "cubs_gone" (1 once both
 * cubs are confirmed dead or gone).
 */
public final class CubGuardAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int BABYSIT_INTERVAL = 5;
    private static final int GUARD_INTERVAL = 20;
    private static final int GUARD_EFFECT_TICKS = 40;
    private static final int CUB_COUNT = 2;
    private static final double FLANK_DISTANCE = 1.8;
    private static final double FLANK_HOLD_SQR = 1.0; // 1.0 blocks
    private static final double NIP_TRIGGER_RANGE_SQR = 36.0; // 6.0 blocks
    private static final double NIP_RANGE_SQR = 2.25; // 1.5 blocks
    private static final double NIP_DAMAGE = 1.0;
    private static final int NIP_COOLDOWN_TICKS = 15;

    @Override
    public String id() {
        return "cub_guard";
    }

    @Override
    public String displayName() {
        return "Cub Guard";
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
            ctx.state().put("cubs", new ListTag());
            return;
        }
        Mob caster = ctx.mob();
        ctx.state().putInt("any_cub_alive", 1);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.OCELOT_AMBIENT, SoundSource.HOSTILE, 1.5f, 1.3f);

        Vec3 look = caster.getLookAngle();
        for (int i = 0; i < CUB_COUNT; i++) {
            Ocelot cub = EntityTypes.OCELOT.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (cub == null) {
                continue;
            }
            // Flank stations: left cub first, then right.
            double side = (i == 0) ? 1.0 : -1.0;
            double spawnX = caster.getX() - look.z * FLANK_DISTANCE * side;
            double spawnZ = caster.getZ() + look.x * FLANK_DISTANCE * side;
            double spawnY = OcelotAbilitySupport.findGroundY(level, spawnX, caster.getY() + 1.0, spawnZ);
            cub.snapTo(spawnX, spawnY, spawnZ, ctx.random().nextFloat() * 360.0f, 0.0f);
            cub.setAge(-24000); // A baby: the design's juvenile cub.
            cub.finalizeSpawn(level, level.getCurrentDifficultyAt(cub.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
            if (!level.addFreshEntity(cub)) {
                continue;
            }
            // The design's juvenile scale, carried as a base
            // value on the new mob (small).
            if (cub.getAttribute(Attributes.SCALE) != null) {
                cub.getAttribute(Attributes.SCALE).setBaseValue(0.7);
            }
            // Team assignment, both directions, plus the summoner registry.
            ctx.data().addAlly(cub.getUUID());
            MobWarData.get(cub).addAlly(caster.getUUID());
            SummonerTracker.registerSummoned(cub.getUUID(), caster.getUUID());
            level.sendParticles(ParticleTypes.POOF, spawnX, spawnY + 0.4, spawnZ,
                6, 0.3, 0.3, 0.3, 0.05);
            OcelotAbilitySupport.trackEntity(ctx.state(), "cubs", cub);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks % BABYSIT_INTERVAL == 0) {
            babysit(ctx, ticks);
        }
        if (ticks % GUARD_INTERVAL == 0 && ctx.state().getIntOr("any_cub_alive", 0) == 1) {
            // The mother's guard: she fights harder while any cub stands.
            Mob caster = ctx.mob();
            caster.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, GUARD_EFFECT_TICKS, 0));
            caster.addEffect(new MobEffectInstance(MobEffects.STRENGTH, GUARD_EFFECT_TICKS, 0));
        }
    }

    /** Drives the cubs between their flank stations and their nip runs, every 5 ticks. */
    private void babysit(AbilityContext ctx, int ticks) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        LivingEntity desired = ctx.target();
        Vec3 look = caster.getLookAngle();
        ListTag cubs = ctx.state().getListOrEmpty("cubs");
        boolean anyAlive = false;
        for (int i = 0; i < cubs.size(); i++) {
            if (!(OcelotAbilitySupport.resolveTracked(level, cubs.getCompoundOrEmpty(i)) instanceof Ocelot cub)
                || !cub.isAlive()) {
                // A fallen cub gets one dust burst at the mother, once.
                String goneKey = "cub_gone_" + i;
                if (ctx.state().getIntOr(goneKey, 0) == 0) {
                    ctx.state().putInt(goneKey, 1);
                    level.sendParticles(ParticleTypes.POOF, caster.getX(), caster.getY() + 0.4, caster.getZ(),
                        4, 0.3, 0.3, 0.3, 0.05);
                }
                continue;
            }
            anyAlive = true;
            // THE NIP: the mother's target is close, so dart in and bite.
            if (desired != null && cub.distanceToSqr(desired) <= NIP_TRIGGER_RANGE_SQR) {
                if (cub.getTarget() != desired) {
                    cub.setTarget(desired);
                }
                if (cub.distanceToSqr(desired) > NIP_RANGE_SQR) {
                    cub.getNavigation().moveTo(desired, 1.4);
                    continue;
                }
                String nipKey = "nip_" + i;
                if (ticks >= ctx.state().getIntOr(nipKey, 0)) {
                    ctx.state().putInt(nipKey, ticks + NIP_COOLDOWN_TICKS);
                    desired.hurtServer(level, level.damageSources().mobAttack(cub), (float) NIP_DAMAGE);
                }
                continue;
            }
            // THE FLANK: hold station at the mother's side.
            if (cub.getTarget() != null) {
                cub.setTarget(null);
            }
            double side = (i == 0) ? 1.0 : -1.0;
            double anchorX = caster.getX() - look.z * FLANK_DISTANCE * side;
            double anchorZ = caster.getZ() + look.x * FLANK_DISTANCE * side;
            double dx = anchorX - cub.getX();
            double dz = anchorZ - cub.getZ();
            if (dx * dx + dz * dz > FLANK_HOLD_SQR) {
                cub.getNavigation().moveTo(anchorX, caster.getY(), anchorZ, 1.4);
            } else {
                cub.getNavigation().stop();
            }
        }
        ctx.state().putInt("any_cub_alive", anyAlive ? 1 : 0);
        if (!anyAlive && ticks >= BABYSIT_INTERVAL) {
            ctx.state().putInt("cubs_gone", 1);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS || ctx.state().getIntOr("cubs_gone", 0) == 1;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        // The guard stands down: survivors dismissed, ally records cleaned both sides.
        ListTag cubs = ctx.state().getListOrEmpty("cubs");
        for (int i = 0; i < cubs.size(); i++) {
            if (!(OcelotAbilitySupport.resolveTracked(level, cubs.getCompoundOrEmpty(i)) instanceof Ocelot cub)
                || !cub.isAlive()) {
                continue;
            }
            ctx.data().removeAlly(cub.getUUID());
            MobWarData.get(cub).removeAlly(caster.getUUID());
            level.sendParticles(ParticleTypes.POOF, cub.getX(), cub.getY() + 0.4, cub.getZ(),
                4, 0.3, 0.3, 0.3, 0.05);
            cub.discard();
        }
    }
}
