package mod.advancedmobs.ability.armadillo;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.armadillo.Armadillo;

import java.util.List;
import java.util.UUID;

/**
 * Armored Convoy (Armadillo ability 5 of 5, summon).
 *
 * WHAT IT DOES: the armadillo calls, and three pups arrive
 * already rolling: a convoy of small bouncy armadillos that
 * caroms through the fight alongside their parent, each one
 * a little bowling ball scattering enemies out of its path.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   its current target within 16 blocks (distance squared
 *   256).
 * - The pups (start()): 3 baby armadillos (age -24000,
 *   MOB_SUMMONED plus finalizeSpawn), snapped to a radius
 *   1.5 ring around the caster at angles 90, 210, and 330
 *   degrees with surface-scanned heights (the support
 *   findGroundY). Each pup carries the design's summon
 *   lines as PERMANENT base values on the new mob (the
 *   Warren Call precedent): SCALE base value 0.6 (the
 *   reduced scale) and BOUNCINESS base value 0.7, so a pup
 *   keeps its bounce physics for its whole life, and
 *   because the bounciness is a real attribute vanilla
 *   emits the bounce game event for its collisions on its
 *   own. Each pup is switched to the vanilla ROLLING ball
 *   state. Pups are allied to the caster in both war data
 *   directions and registered in SummonerTracker (the
 *   design's team based swarm targeting, carried by the war
 *   ally records plus the babysit below). ARMADILLO_ROLL
 *   plays at the call, pitch 1.3 (little balls).
 * - The babysit (every 5 ticks): each living pup is
 *   retargeted onto the caster's live target (the swarm
 *   shares one target, the design's team targeting) and its
 *   ROLLING state is re-asserted if its brain wandered out
 *   of it. When the last pup dies the babysit records
 *   "all_dead" and the ability ends early.
 * - The carom (every tick): each living pup is velocity
 *   steered (the support steerToward) at 0.55 blocks per
 *   tick toward its target's chest, or toward the caster
 *   when there is no target, because a baby armadillo's
 *   vanilla brain never charges an enemy on its own. The
 *   steering writes horizontal and vertical velocity
 *   together, so pups ride over the fight's small rises
 *   instead of grinding at feet.
 * - The bowling (every 2 ticks, per pup, radius 1.3 box
 *   around the pup, 0.8 below to 1.6 above): the first
 *   enemy found takes 2.5 flat damage as a mob attack
 *   credited to the pup (no ATTACK_DAMAGE instance; see the
 *   provider comment) and is launched 0.9 horizontally
 *   directly away from the pup and 0.35 up with hurtMarked
 *   set, with 4 tan DUST at the impact. A pup may bowl at
 *   most once per 8 ticks (its stamp is kept in its tracked
 *   entry as "hit_tick"), so a pup parked inside a crowd
 *   deals at most 2.5 damage per 8 ticks, never a per-tick
 *   shred.
 * - The trail: 1 tan DUST mote at each living pup every 4
 *   ticks (the design's dust line; the tan dust option is a
 *   pre-built static final, RGB 0xC2A878).
 * - Duration: 400 ticks (20 seconds), ending early when
 *   the whole convoy is dead. end() dismisses surviving
 *   pups in a tan DUST puff and removes the ally records on
 *   both sides: the convoy is temporary (the Pod Call
 *   precedent), unlike Warren Call's permanent rabbits.
 *
 * WHAT IT LOOKS LIKE: three palm sized scute balls dropping
 * in around their parent and immediately rolling, a dusty
 * little convoy bouncing through the melee, enemies
 * stumbling aside wherever a pup caroms through.
 *
 * PERFORMANCE: 3 spawns and 3 surface scans once in
 * start(); the babysit resolves at most 3 UUIDs every 5
 * ticks; the carom is at most 3 velocity writes per tick;
 * the bowling is at most 3 bounded radius-1.3 queries every
 * 2 ticks. Particle budget per activation: trails 100
 * sends times 1 = 100, bowling at most 50 hits times 4 =
 * 200, dismissal 3 times 6 = 18, total at most 318.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply. The pups' bounce runs through their real
 * BOUNCINESS base value and code steering, never through
 * block edits.
 *
 * State tag keys: "pups" (list of compounds: "id", the
 * pup's UUID, and "hit_tick", its last bowling tick),
 * "all_dead" (1 once the babysit finds no living pup).
 */
public final class ArmoredConvoyAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int PUP_COUNT = 3;
    private static final double PUP_SCALE = 0.6;
    private static final double PUP_BOUNCINESS = 0.7;
    private static final double PUP_SPEED = 0.55;
    private static final double BOWL_RADIUS = 1.3;
    private static final float BOWL_DAMAGE = 2.5f;
    private static final int BOWL_COOLDOWN_TICKS = 8;
    private static final double LAUNCH_HORIZONTAL = 0.9;
    private static final double LAUNCH_VERTICAL = 0.35;
    private static final DustParticleOptions TAN_DUST = new DustParticleOptions(0xC2A878, 1.0f);

    @Override
    public String id() {
        return "armored_convoy";
    }

    @Override
    public String displayName() {
        return "Armored Convoy";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        state.put("pups", new ListTag());
        state.putInt("all_dead", 0);

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.ARMADILLO_ROLL, SoundSource.HOSTILE, 1.2f, 1.3f);
        for (int i = 0; i < PUP_COUNT; i++) {
            double angle = Math.toRadians(90.0 + i * 120.0);
            double px = caster.getX() + Math.cos(angle) * 1.5;
            double pz = caster.getZ() + Math.sin(angle) * 1.5;
            double py = ArmadilloAbilitySupport.findGroundY(level, px, caster.getY(), pz);

            Armadillo pup = EntityTypes.ARMADILLO.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (pup == null) {
                continue;
            }
            pup.setAge(-24000);
            pup.snapTo(px, py, pz, ctx.random().nextFloat() * 360.0f, 0.0f);
            pup.finalizeSpawn(level, level.getCurrentDifficultyAt(pup.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
            // The design's summon lines, as permanent base
            // values on the new mob (Warren Call precedent).
            AttributeInstance scale = pup.getAttribute(Attributes.SCALE);
            if (scale != null) {
                scale.setBaseValue(PUP_SCALE);
            }
            AttributeInstance bounciness = pup.getAttribute(Attributes.BOUNCINESS);
            if (bounciness != null) {
                bounciness.setBaseValue(PUP_BOUNCINESS);
            }
            pup.switchToState(Armadillo.ArmadilloState.ROLLING);
            if (!level.addFreshEntity(pup)) {
                continue;
            }
            // Team assignment, both directions, plus the summoner registry.
            ctx.data().addAlly(pup.getUUID());
            MobWarData.get(pup).addAlly(caster.getUUID());
            SummonerTracker.registerSummoned(pup.getUUID(), caster.getUUID());
            ArmadilloAbilitySupport.trackEntity(state, "pups", pup);
        }
        // The tracked entries need their bowling stamps.
        ListTag pups = state.getListOrEmpty("pups");
        for (int i = 0; i < pups.size(); i++) {
            pups.getCompoundOrEmpty(i).putInt("hit_tick", -BOWL_COOLDOWN_TICKS);
        }
        state.put("pups", pups);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        if (ticks % 5 == 0) {
            babysit(ctx, level);
        }
        carom(ctx, level, ticks);
    }

    /** Retargets the convoy onto the shared target and watches for the last pup's death. Every 5 ticks. */
    private void babysit(AbilityContext ctx, ServerLevel level) {
        LivingEntity target = ctx.target();
        boolean anyAlive = false;
        ListTag pups = ctx.state().getListOrEmpty("pups");
        for (int i = 0; i < pups.size(); i++) {
            Entity found = ArmadilloAbilitySupport.resolveTracked(level, pups.getCompoundOrEmpty(i));
            if (!(found instanceof Armadillo pup) || !pup.isAlive()) {
                continue;
            }
            anyAlive = true;
            pup.setTarget(target);
            if (pup.getState() != Armadillo.ArmadilloState.ROLLING) {
                pup.switchToState(Armadillo.ArmadilloState.ROLLING);
            }
        }
        if (!anyAlive && !pups.isEmpty()) {
            ctx.state().putInt("all_dead", 1);
        }
    }

    /** Steers every living pup and runs the bowling checks. The bowling pass runs every 2 ticks. */
    private void carom(AbilityContext ctx, ServerLevel level, int ticks) {
        Mob caster = ctx.mob();
        LivingEntity target = ctx.target();
        boolean bowlTick = ticks % 2 == 0;
        ListTag pups = ctx.state().getListOrEmpty("pups");
        for (int i = 0; i < pups.size(); i++) {
            CompoundTag entry = pups.getCompoundOrEmpty(i);
            Entity found = ArmadilloAbilitySupport.resolveTracked(level, entry);
            if (!(found instanceof Armadillo pup) || !pup.isAlive()) {
                continue;
            }
            // The carom: ride at the target, or fall back to the parent.
            LivingEntity aim = target != null ? target : caster;
            ArmadilloAbilitySupport.steerToward(pup,
                aim.getX(), aim.getY() + 0.3, aim.getZ(), PUP_SPEED);

            if (ticks % 4 == 0) {
                level.sendParticles(TAN_DUST, pup.getX(), pup.getY() + 0.2, pup.getZ(),
                    1, 0.25, 0.15, 0.25, 0.03);
            }
            if (bowlTick && ticks - entry.getIntOr("hit_tick", -BOWL_COOLDOWN_TICKS) >= BOWL_COOLDOWN_TICKS) {
                List<LivingEntity> victims = ArmadilloAbilitySupport.enemiesInBox(
                    ctx, pup.getX(), pup.getY(), pup.getZ(), BOWL_RADIUS, 0.8, 1.6);
                for (LivingEntity victim : victims) {
                    // A pup never bowls its own parent or a sibling.
                    if (victim == caster || victim instanceof Armadillo) {
                        continue;
                    }
                    bowl(ctx, level, pup, victim);
                    entry.putInt("hit_tick", ticks);
                    break;
                }
            }
        }
        // The tracked entries were mutated in place (bowling
        // stamps); write the list back so the framework
        // persists them with the rest of the state tag.
        ctx.state().put("pups", pups);
    }

    /** Bowls one enemy off one pup: damage and the launch away from the pup. */
    private void bowl(AbilityContext ctx, ServerLevel level, Armadillo pup, LivingEntity victim) {
        victim.hurtServer(level, level.damageSources().mobAttack(pup), BOWL_DAMAGE);
        double awayX = victim.getX() - pup.getX();
        double awayZ = victim.getZ() - pup.getZ();
        double awayLength = Math.sqrt(awayX * awayX + awayZ * awayZ);
        if (awayLength < 1.0e-4) {
            awayX = 1.0;
            awayZ = 0.0;
            awayLength = 1.0;
        }
        victim.setDeltaMovement(awayX / awayLength * LAUNCH_HORIZONTAL, LAUNCH_VERTICAL,
            awayZ / awayLength * LAUNCH_HORIZONTAL);
        victim.hurtMarked = true;
        level.sendParticles(TAN_DUST, victim.getX(), victim.getY() + 0.6, victim.getZ(),
            4, 0.3, 0.3, 0.3, 0.08);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS
            || ctx.state().getIntOr("all_dead", 0) == 1;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag pups = ctx.state().getListOrEmpty("pups");
        for (int i = 0; i < pups.size(); i++) {
            CompoundTag entry = pups.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id == null) {
                continue;
            }
            // The alliance ends with the ability, whichever side is still here.
            ctx.data().removeAlly(id);
            Entity found = level.getEntity(id);
            if (found instanceof Armadillo pup) {
                MobWarData.get(pup).removeAlly(caster.getUUID());
                if (pup.isAlive()) {
                    level.sendParticles(ParticleTypes.POOF,
                        pup.getX(), pup.getY() + 0.2, pup.getZ(),
                        6, 0.25, 0.15, 0.25, 0.05);
                    pup.discard();
                }
            }
        }
    }
}
