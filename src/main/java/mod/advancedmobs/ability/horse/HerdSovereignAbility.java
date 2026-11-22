package mod.advancedmobs.ability.horse;

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
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Herd Sovereign (Horse ability 4 of 5, summon / aura).
 *
 * WHAT IT DOES: the horse throws its head up and whinnies,
 * and two allied horses gallop in from the edges of the
 * fight amid heart particles. While the herd lives, the
 * sovereign and its herd share a strength aura, and the
 * whole herd coordinates on the sovereign's target: the
 * herd horses run it down and trample it alongside their
 * sovereign.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the horse's current target within 18 blocks (distance
 *   squared 324).
 * - The call (start()): HORSE_AMBIENT at volume 1.5, pitch
 *   1.1 (the whinny), and 10 HEART particles over the
 *   sovereign. Two horses are summoned at the edges of the
 *   fight: 6.0 blocks to the sovereign's left and right
 *   (the side axis from its look direction), falling back
 *   to 4.0 blocks and then the sovereign's own position at
 *   the first collision-free spot. Each is created with
 *   EntitySpawnReason.MOB_SUMMONED, forced adult
 *   (setAge(0): a baby cannot carry the herd), and
 *   finalizeSpawn like every other summon in this mod,
 *   which rolls its vanilla randomized stats. Its
 *   FOLLOW_RANGE base value is then set to 32.0
 *   permanently on the new mob (vanilla 16.0 plus the
 *   design's summon-with-modifiers line, carried as a base
 *   value on a fresh mob: the Warren Call precedent).
 * - Team assignment, per herd horse, both directions plus
 *   the registry: the caster's data gains the herd horse
 *   as an ally, the herd horse's data gains the caster,
 *   and the pair is registered in SummonerTracker (which
 *   also tags it umw_summoned for the natural spawn
 *   blocker). Each is ordered onto the caster's current
 *   target at spawn.
 * - Babysitting every 5 ticks: each living herd horse
 *   whose target is not the caster's live target is
 *   re-ordered onto it, and is navigation-driven at speed
 *   1.3 toward it whenever it is more than 3 blocks away
 *   (a passive animal's AI never chases a set target on
 *   its own, so the drive is what gallops the herd in: the
 *   Sporeling Swarm precedent).
 * - The herd trample (checked on the babysit pass): a herd
 *   horse within 1.6 blocks of the shared target tramples
 *   it for 3.0 damage as a mob attack credited to THAT
 *   herd horse (a flat code value: a horse has no
 *   ATTACK_DAMAGE attribute instance in 26.2, verified
 *   against the jar), at most once per herd horse per 20
 *   ticks (each horse's next trample tick is kept in the
 *   state tag), with 4 tan DUST at the victim.
 * - The strength aura (every 20 ticks): the caster and
 *   every living herd horse gain STRENGTH I refreshed to
 *   40 ticks (the design's shared strength), with 2 HEART
 *   particles over the caster. The same pass counts the
 *   living herd: if both herd horses are dead or gone
 *   after tick 20, the state tag's "herd_gone" flag is set
 *   and the ability ends early (the herd is the ability;
 *   with no herd there is nothing to sustain).
 * - Duration: at most 400 ticks (20 seconds), ending early
 *   on the herd_gone flag.
 * - end(): the alliance records are removed on both sides
 *   for every tracked herd horse, and each one still alive
 *   is discarded in a puff of 6 HEART particles: the herd
 *   is the ability's, and it does not outlive it. If a
 *   herd horse died in the fight, end() simply finds
 *   nothing to discard for it. If the caster dies first,
 *   the framework termination path runs this same end(),
 *   so the herd never outlives its sovereign either.
 *   SummonerTracker records are deliberately not
 *   unregistered here, matching the other summon packages:
 *   that registry cleans up on the summoned mob's own
 *   removal.
 *
 * WHAT IT LOOKS LIKE: a ringing whinny, hearts over the
 * sovereign, and two horses thundering in from opposite
 * sides of the fight to run the sovereign's target down
 * under a shared strength aura.
 *
 * PERFORMANCE: start() does 2 bounded collision checks and
 * 2 spawns, once. tick() returns immediately except every
 * 5th tick (babysit: resolves at most 2 UUIDs, retargets,
 * drives navigation, and runs at most 2 distance checks)
 * and every 20th tick (the aura: touches at most 3
 * entities). Particle budget per activation: 10 at start,
 * aura 20 pulses times 2 = 40, tramples at most 40 times
 * 4 = 160, end at most 2 times 6 = 12, total at most 222
 * particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "herd" (list of compounds, one per herd
 * horse: "id", its UUID), "trample_0", "trample_1" (the
 * next tick each herd horse may trample again, by herd
 * list index), "herd_gone" (1 once both herd horses are
 * confirmed dead or gone).
 */
public final class HerdSovereignAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int BABYSIT_INTERVAL = 5;
    private static final int AURA_INTERVAL = 20;
    private static final int HERD_COUNT = 2;
    private static final double TRAMPLE_RANGE_SQR = 2.56; // 1.6 blocks
    private static final double TRAMPLE_DAMAGE = 3.0;
    private static final int TRAMPLE_COOLDOWN_TICKS = 20;

    @Override
    public String id() {
        return "herd_sovereign";
    }

    @Override
    public String displayName() {
        return "Herd Sovereign";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 324.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            ctx.state().put("herd", new ListTag());
            return;
        }
        Mob caster = ctx.mob();
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.HORSE_AMBIENT, SoundSource.HOSTILE, 1.5f, 1.1f);
        level.sendParticles(ParticleTypes.HEART, caster.getX(), caster.getY() + 1.6, caster.getZ(),
            10, 0.6, 0.5, 0.6, 0.05);

        // The side axis: the herd gallops in from the edges of the fight.
        Vec3 look = caster.getLookAngle();
        double sideX = -look.z;
        double sideZ = look.x;
        double sideLength = Math.sqrt(sideX * sideX + sideZ * sideZ);
        if (sideLength < 0.001) {
            sideX = 1.0;
            sideZ = 0.0;
            sideLength = 1.0;
        }
        sideX /= sideLength;
        sideZ /= sideLength;

        LivingEntity target = ctx.target();
        for (int i = 0; i < HERD_COUNT; i++) {
            Horse herdHorse = EntityTypes.HORSE.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (herdHorse == null) {
                continue;
            }
            double sign = i == 0 ? 1.0 : -1.0;
            double spawnX = caster.getX();
            double spawnZ = caster.getZ();
            for (double distance : new double[]{6.0, 4.0, 0.0}) {
                double candidateX = caster.getX() + sideX * sign * distance;
                double candidateZ = caster.getZ() + sideZ * sign * distance;
                if (HorseAbilitySupport.isFreeSpot(level, herdHorse, candidateX, caster.getY(), candidateZ)) {
                    spawnX = candidateX;
                    spawnZ = candidateZ;
                    break;
                }
            }
            herdHorse.snapTo(spawnX, caster.getY(), spawnZ, ctx.random().nextFloat() * 360.0f, 0.0f);
            herdHorse.setAge(0);
            herdHorse.finalizeSpawn(level, level.getCurrentDifficultyAt(herdHorse.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
            if (!level.addFreshEntity(herdHorse)) {
                continue;
            }
            // The design's summon modifier, carried as a base value on the new mob.
            if (herdHorse.getAttribute(Attributes.FOLLOW_RANGE) != null) {
                herdHorse.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(32.0);
            }

            // Team assignment, both directions, plus the summoner registry.
            ctx.data().addAlly(herdHorse.getUUID());
            MobWarData.get(herdHorse).addAlly(caster.getUUID());
            SummonerTracker.registerSummoned(herdHorse.getUUID(), caster.getUUID());
            if (target != null) {
                herdHorse.setTarget(target);
            }
            HorseAbilitySupport.trackEntity(ctx.state(), "herd", herdHorse);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks % BABYSIT_INTERVAL == 0) {
            babysit(ctx, ticks);
        }
        if (ticks % AURA_INTERVAL == 0) {
            auraPulse(ctx, ticks);
        }
    }

    /** Retargets, drives, and tramples with the herd, every 5 ticks. */
    private void babysit(AbilityContext ctx, int ticks) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        LivingEntity desired = ctx.target();
        ListTag herd = ctx.state().getListOrEmpty("herd");
        for (int i = 0; i < herd.size(); i++) {
            if (!(HorseAbilitySupport.resolveTracked(level, herd.getCompoundOrEmpty(i)) instanceof Horse herdHorse)
                || !herdHorse.isAlive()) {
                continue;
            }
            if (desired == null) {
                continue;
            }
            if (herdHorse.getTarget() != desired) {
                herdHorse.setTarget(desired);
            }
            if (herdHorse.distanceToSqr(desired) > 9.0) {
                herdHorse.getNavigation().moveTo(desired, 1.3);
            }
            // The herd trample: hooves on the shared target.
            String trampleKey = "trample_" + i;
            if (herdHorse.distanceToSqr(desired) <= TRAMPLE_RANGE_SQR
                && ticks >= ctx.state().getIntOr(trampleKey, 0)) {
                ctx.state().putInt(trampleKey, ticks + TRAMPLE_COOLDOWN_TICKS);
                desired.hurtServer(level, level.damageSources().mobAttack(herdHorse), (float) TRAMPLE_DAMAGE);
                level.sendParticles(HorseAbilitySupport.TAN_DUST,
                    desired.getX(), desired.getY() + 0.4, desired.getZ(),
                    4, 0.35, 0.3, 0.35, 0.1);
            }
        }
    }

    /** The shared strength aura, every 20 ticks; also watches for the herd's end. */
    private void auraPulse(AbilityContext ctx, int ticks) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        caster.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 40, 0));
        level.sendParticles(ParticleTypes.HEART, caster.getX(), caster.getY() + 1.6, caster.getZ(),
            2, 0.5, 0.4, 0.5, 0.05);

        int living = 0;
        ListTag herd = ctx.state().getListOrEmpty("herd");
        for (int i = 0; i < herd.size(); i++) {
            if (HorseAbilitySupport.resolveTracked(level, herd.getCompoundOrEmpty(i)) instanceof Horse herdHorse
                && herdHorse.isAlive()) {
                living++;
                herdHorse.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 40, 0));
            }
        }
        if (living == 0 && ticks > AURA_INTERVAL) {
            ctx.state().putInt("herd_gone", 1);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS
            || ctx.state().getIntOr("herd_gone", 0) == 1;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag herd = ctx.state().getListOrEmpty("herd");
        for (int i = 0; i < herd.size(); i++) {
            CompoundTag entry = herd.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID herdId = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (herdId == null) {
                continue;
            }
            // The alliance ends with the ability, whichever side is still here.
            ctx.data().removeAlly(herdId);
            if (HorseAbilitySupport.resolveTracked(level, entry) instanceof Horse herdHorse) {
                MobWarData.get(herdHorse).removeAlly(caster.getUUID());
                if (herdHorse.isAlive()) {
                    // The herd gallops off in a swirl of hearts.
                    level.sendParticles(ParticleTypes.HEART, herdHorse.getX(), herdHorse.getY() + 1.2, herdHorse.getZ(),
                        6, 0.5, 0.4, 0.5, 0.05);
                    herdHorse.discard();
                }
            }
        }
    }
}
