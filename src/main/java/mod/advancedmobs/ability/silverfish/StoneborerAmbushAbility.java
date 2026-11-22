package mod.advancedmobs.ability.silverfish;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Stoneborer Ambush (Silverfish ability 1 of 5, attack).
 *
 * WHAT IT DOES: the silverfish vanishes into the floor, stone
 * dust sifting where it went down, and a moment later erupts
 * directly beneath its target in a blast of stone shards. The
 * victim takes typed damage from the strike below and gains
 * MINING_FATIGUE from the shock of the burst, suddenly unable
 * to dig or fight its way down to where the fish went.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists, between 3 and 14
 *   blocks away (distance squared 9 to 196), and the fish is
 *   on the ground. A burrower strikes from a floor it is
 *   standing on, not mid-leap.
 * - Burrow (ticks 0 to 14): at start() the fish gains
 *   INVISIBILITY for 30 ticks (amplifier 0, no particles, no
 *   icon: a shimmer would give the burrow away) and its
 *   navigation is stopped. Every burrow tick the navigation is
 *   stopped again and horizontal velocity is zeroed, so the
 *   war goals cannot walk it out of its hole. The fish is NOT
 *   invulnerable and NOT set NoAi: NoAi would stop
 *   serverAiStep, which is exactly where the framework ticks
 *   this ability, so the fish would freeze forever. It can
 *   still be hit while burrowed if an enemy finds it. Every 3
 *   ticks, 3 stone BLOCK_CRUMBLE particles sift at its feet,
 *   and start() plays a muffled SILVERFISH_AMBIENT (volume 0.8,
 *   pitch 0.7): something small chewing into rock.
 * - Eruption (tick 15): the fish teleports to its target.
 *   Candidate spots, tried in order, are the target's own feet
 *   position (the design's "directly beneath": the fish comes
 *   up through the floor the victim is standing on, sharing
 *   the spot for the instant of the strike), then 1.0 block
 *   from the target on the line back toward the burrow, then
 *   2.0 blocks on that line; if none is collision-free the
 *   fish stays where it burrowed and erupts there. If the
 *   target died, the position snapshot from start() anchors
 *   the eruption and there is no victim. At the eruption the
 *   fish pops upward (vertical velocity 0.42), the
 *   invisibility is removed, and a STONE_BREAK sound plays
 *   (volume 1.0, pitch 1.1).
 * - The strike: the live target, when it is an enemy
 *   (SilverfishAbilitySupport rules) and within 2.5 blocks of
 *   the eruption point, takes 4.0 typed damage as a mob attack
 *   by the fish (level.damageSources().mobAttack(fish): the
 *   /damage typed damage of the design, credited to the fish
 *   so kills and retaliation read correctly; a vanilla
 *   silverfish bite is 1.0, so the ambush strike is four bites
 *   in one burst) and gains MINING_FATIGUE II (amplifier 1)
 *   for 100 ticks (5 seconds). The fish retargets the victim.
 * - The ability finishes at tick 21, six ticks after the
 *   eruption, so the shard spray settles before end().
 *
 * TERRAIN RULE (Carter, Nov 21, 2026): no block is placed,
 * broken, or moved. The burrow and the eruption are BLOCK and
 * BLOCK_CRUMBLE particle theater over unbroken ground, and the
 * teleport is movement, not terrain: the mobGriefing gate does
 * not apply to this ability at all.
 *
 * WHAT IT LOOKS LIKE: the fish winks out with a sift of stone
 * crumble at its feet. Three quarters of a second later the
 * floor under the target bursts: 12 stone BLOCK shards and 8
 * BLOCK_CRUMBLE chunks blast upward, a crack of breaking
 * stone, and the fish is there, already biting.
 *
 * PERFORMANCE: the burrow tick is one navigation stop, one
 * velocity write, and a 3-particle burst every 3 ticks. The
 * eruption is one bounded candidate check (at most 3 collision
 * tests), one teleport, one damage call. No entity scans at
 * all: the victim is the resolved target only. Particle budget
 * per activation: burrow 5 bursts times 3 = 15, eruption 12 +
 * 8 = 20, total at most 35 particles.
 *
 * CLEANUP: end() removes the invisibility when the eruption
 * never happened (the only path where it can still be active),
 * so a fish whose activation is terminated mid-burrow (caster
 * death runs this same end() through the framework) never
 * stays invisible.
 *
 * State tag keys: "target" (target UUID), "tx", "ty", "tz"
 * (target position snapshot), "erupted" (whether the eruption
 * already fired).
 */
public final class StoneborerAmbushAbility implements MobAbility {

    private static final int ERUPT_TICK = 15;
    private static final int END_TICK = 21;
    private static final int INVISIBILITY_TICKS = 30;
    private static final float STRIKE_DAMAGE = 4.0f;
    private static final int FATIGUE_TICKS = 100;
    private static final BlockParticleOption STONE_SHARDS =
        new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState());
    private static final BlockParticleOption STONE_CRUMBLE =
        new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, Blocks.STONE.defaultBlockState());

    @Override
    public String id() {
        return "stoneborer_ambush";
    }

    @Override
    public String displayName() {
        return "Stoneborer Ambush";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null || !ctx.mob().onGround()) {
            return false;
        }
        double distanceSqr = ctx.mob().distanceToSqr(target);
        return distanceSqr >= 9.0 && distanceSqr <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob mob = ctx.mob();
        LivingEntity target = ctx.target();
        CompoundTag state = ctx.state();
        if (target != null) {
            state.store("target", UUIDUtil.CODEC, target.getUUID());
            state.putDouble("tx", target.getX());
            state.putDouble("ty", target.getY());
            state.putDouble("tz", target.getZ());
        }
        state.putBoolean("erupted", false);

        // Vanish into the floor and stay put. Navigation is
        // re-stopped every burrow tick.
        mob.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, INVISIBILITY_TICKS, 0, false, false, false));
        mob.getNavigation().stop();
        Vec3 velocity = mob.getDeltaMovement();
        mob.setDeltaMovement(0.0, velocity.y, 0.0);

        if (level != null) {
            // Something small chewing into rock.
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.SILVERFISH_AMBIENT, SoundSource.HOSTILE, 0.8f, 0.7f);
            level.sendParticles(STONE_CRUMBLE, mob.getX(), mob.getY() + 0.1, mob.getZ(),
                3, 0.25, 0.1, 0.25, 0.05);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        if (state.getBooleanOr("erupted", false)) {
            return; // Post-eruption: nothing to do until the finish tick.
        }
        // Hold position while burrowed: goals re-path every
        // tick, so stop the navigation every tick. (NoAi is NOT
        // used: it would stop serverAiStep, where this ability
        // itself is ticked.)
        mob.getNavigation().stop();
        Vec3 velocity = mob.getDeltaMovement();
        if (velocity.x != 0.0 || velocity.z != 0.0) {
            mob.setDeltaMovement(0.0, velocity.y, 0.0);
        }

        ServerLevel level = ctx.serverLevel();
        int ticks = ctx.ticksActive();
        if (level != null && ticks % 3 == 0 && ticks < ERUPT_TICK) {
            level.sendParticles(STONE_CRUMBLE, mob.getX(), mob.getY() + 0.1, mob.getZ(),
                3, 0.25, 0.1, 0.25, 0.05);
        }
        if (ticks >= ERUPT_TICK) {
            erupt(ctx);
            state.putBoolean("erupted", true);
        }
    }

    /**
     * The eruption: teleport beneath the target, reveal, and
     * strike the victim from below (see the class comment for
     * the candidate order and the strike numbers).
     */
    private void erupt(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();

        LivingEntity target = null;
        double anchorX = state.getDoubleOr("tx", mob.getX());
        double anchorY = state.getDoubleOr("ty", mob.getY());
        double anchorZ = state.getDoubleOr("tz", mob.getZ());
        if (level != null && state.contains("target")) {
            UUID targetId = state.read("target", UUIDUtil.CODEC).orElse(null);
            if (targetId != null && level.getEntity(targetId) instanceof LivingEntity living && living.isAlive()) {
                target = living;
                anchorX = target.getX();
                anchorY = target.getY();
                anchorZ = target.getZ();
            }
        }

        // Candidate spots: the target's own feet first (directly
        // beneath), then back along the line toward the burrow.
        double towardX = mob.getX() - anchorX;
        double towardZ = mob.getZ() - anchorZ;
        double towardLen = Math.sqrt(towardX * towardX + towardZ * towardZ);
        if (towardLen < 1.0e-4) {
            towardX = 0.0;
            towardZ = 1.0;
        } else {
            towardX /= towardLen;
            towardZ /= towardLen;
        }
        double destX = mob.getX(), destY = mob.getY(), destZ = mob.getZ();
        double[][] candidates = {
            {anchorX, anchorY, anchorZ},
            {anchorX + towardX * 1.0, anchorY, anchorZ + towardZ * 1.0},
            {anchorX + towardX * 2.0, anchorY, anchorZ + towardZ * 2.0}
        };
        for (double[] candidate : candidates) {
            if (level != null && SilverfishAbilitySupport.isFreeSpot(level, mob, candidate[0], candidate[1], candidate[2])) {
                destX = candidate[0];
                destY = candidate[1];
                destZ = candidate[2];
                break;
            }
        }
        mob.teleportTo(destX, destY, destZ);
        // Pop up out of the floor it burst through.
        Vec3 velocity = mob.getDeltaMovement();
        mob.setDeltaMovement(velocity.x, 0.42, velocity.z);
        mob.getNavigation().stop();

        // Reveal: the eruption is meant to be seen.
        mob.removeEffect(MobEffects.INVISIBILITY);

        if (level == null) {
            return;
        }
        level.sendParticles(STONE_SHARDS, destX, destY + 0.3, destZ, 12, 0.4, 0.3, 0.4, 0.3);
        level.sendParticles(STONE_CRUMBLE, destX, destY + 0.3, destZ, 8, 0.45, 0.3, 0.45, 0.15);
        level.playSound(null, destX, destY, destZ, SoundEvents.STONE_BREAK, SoundSource.HOSTILE,
            1.0f, 1.1f);

        // The strike from below: typed damage plus the shock
        // that keeps the victim from digging after it.
        if (target != null && SilverfishAbilitySupport.isEnemy(ctx, target)
            && target.distanceToSqr(destX, destY, destZ) <= 6.25) {
            target.hurtServer(level, level.damageSources().mobAttack(mob), STRIKE_DAMAGE);
            target.addEffect(new MobEffectInstance(MobEffects.MINING_FATIGUE, FATIGUE_TICKS, 1));
            mob.setTarget(target);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= END_TICK;
    }

    @Override
    public void end(AbilityContext ctx) {
        if (!ctx.state().getBooleanOr("erupted", false)) {
            // Finished or terminated without erupting: reveal
            // the fish.
            ctx.mob().removeEffect(MobEffects.INVISIBILITY);
        }
    }
}
