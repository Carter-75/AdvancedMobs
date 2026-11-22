package mod.advancedmobs.ability.squid;

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
import net.minecraft.world.entity.animal.squid.Squid;

import java.util.List;
import java.util.UUID;

/**
 * Ink Clone Decoy (Squid ability 4 of 5, summon /
 * defense).
 *
 * WHAT IT DOES: the squid squirts out a perfect inky
 * double of itself and slips away. The decoy hangs
 * nearly still where the squid was, outlined in a
 * faint glow inside a slowly expanding cloud of ink,
 * and enemies commit to it: their attacks swing at
 * the double while the real squid is already gone.
 * When the decoy is destroyed, it bursts into one
 * last cloud of ink that blinds whatever killed it.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat
 *   gate, plus the squid's current target within 14
 *   blocks (distance squared 196), plus the squid in
 *   water.
 * - The swap (start()): SQUID_SQUIRT at volume 1.2,
 *   pitch 1.0 and 16 SQUID_INK at the caster. One
 *   squid is summoned at the caster's exact position
 *   with EntitySpawnReason.MOB_SUMMONED and
 *   finalizeSpawn like every other summon in this
 *   mod. Its MAX_HEALTH base value is set to 6.0
 *   (and its health to 6.0) before it enters the
 *   world, so it reads as a real squid but pops
 *   quickly under focus. It gains GLOWING for 340
 *   ticks (the design's outline). Team assignment,
 *   per the design's team line, both directions plus
 *   the registry: the caster's data gains the decoy
 *   as an ally, the decoy's data gains the caster,
 *   and the pair is registered in SummonerTracker
 *   (which also tags the decoy umw_summoned for the
 *   natural spawn blocker). The caster then jets
 *   off: one velocity write directly away from the
 *   target at 1.0 blocks per tick with 0.1 up.
 * - Station keeping (every tick): the decoy is
 *   steered back toward its anchor (the swap point,
 *   recorded in the state tag) at 0.3 blocks per
 *   tick whenever it drifts, so it hangs nearly
 *   still the way the design holds it. Its position
 *   is recorded every 5 ticks for the death burst.
 * - The ink cloud (every 4 ticks): 3 SQUID_INK at
 *   the decoy with a spread radius that grows from
 *   0.5 by 0.01 per activation tick, capped at 2.5:
 *   the slowly expanding cloud of the design.
 * - The commitment (every 20 ticks): one bounded
 *   query, radius 12 around the decoy, vertical
 *   reach 4 both ways: every enemy Mob whose current
 *   target is the caster is retargeted onto the
 *   decoy (players are never retargeted; they choose
 *   their own swings, the mirage precedent).
 * - The death burst (checked every 5 ticks): when
 *   the decoy was alive at the last check and is now
 *   dead or gone, it bursts at its last recorded
 *   position: 20 SQUID_INK, and one bounded query,
 *   radius 4.0, vertical reach 3 both ways: every
 *   enemy gains BLINDNESS for 100 ticks, and the
 *   decoy's last hurt-by entity, when it resolves
 *   and is an enemy, gains BLINDNESS for 140 ticks
 *   instead (the design's killer blind, the longer
 *   one). The burst fires exactly once, guarded by a
 *   state flag. A decoy lost to a plain chunk unload
 *   does not burst: an unresolved decoy that was
 *   never seen dead is treated as unloaded, not
 *   killed (the termination contract's distinction;
 *   if it never resolves again the ability simply
 *   runs out its clock).
 * - Duration: 300 ticks (15 seconds), ending early
 *   20 ticks after the death burst fires, so the
 *   blind lands and reads before the activation
 *   closes.
 *
 * WHAT IT LOOKS LIKE: an ink burst, a glowing squid
 * hanging motionless in a growing black cloud, enemy
 * strikes converging on it, and a final blinding
 * bloom when it pops.
 *
 * PERFORMANCE: start() does 1 spawn, once. Per tick:
 * one steering write on the decoy. The cloud is 3
 * sends every 4 ticks; the babysit resolve and
 * position record run every 5 ticks; the commitment
 * query every 20 ticks; the burst is one bounded
 * query, once ever. Particle budget per activation:
 * 16 at start, at most 75 cloud pulses times 3 =
 * 225, 20 burst, 10 end puff, total at most 271.
 *
 * TERRAIN: this ability changes no blocks; the
 * decoy and its cloud are an entity and particles,
 * so the mobGriefing gate does not apply.
 *
 * CLEANUP: end() removes the alliance records on
 * both sides and discards a surviving decoy in a
 * puff of 10 SQUID_INK. If the caster dies first,
 * the framework termination path runs this same
 * end(), so the decoy never outlives its maker.
 * SummonerTracker records are deliberately not
 * unregistered, matching the other summons in this
 * mod: that registry cleans up on the member's own
 * removal.
 *
 * State tag keys: "decoy" (list with one compound:
 * "id", the decoy's UUID), "ax", "ay", "az" (the
 * anchor, the swap point), "lx", "ly", "lz" (the
 * decoy's last recorded position), "killer" (the
 * UUID of the last entity seen hurting the decoy),
 * "seen_alive"
 * (the decoy resolved alive at the last check),
 * "burst_done", "burst_tick" (the activation tick
 * the burst fired, -1 while it has not).
 */
public final class InkCloneDecoyAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int END_AFTER_BURST_TICKS = 20;
    private static final double DECOY_MAX_HEALTH = 6.0;

    @Override
    public String id() {
        return "ink_clone_decoy";
    }

    @Override
    public String displayName() {
        return "Ink Clone Decoy";
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
        state.putDouble("lx", caster.getX());
        state.putDouble("ly", caster.getY());
        state.putDouble("lz", caster.getZ());
        state.putBoolean("seen_alive", false);
        state.putBoolean("burst_done", false);
        state.putInt("burst_tick", -1);
        if (level == null) {
            return;
        }

        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.SQUID_SQUIRT, SoundSource.HOSTILE, 1.2f, 1.0f);
        level.sendParticles(ParticleTypes.SQUID_INK, caster.getX(), caster.getY() + 0.3, caster.getZ(),
            16, 0.6, 0.5, 0.6, 0.05);

        Squid decoy = EntityTypes.SQUID.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (decoy != null) {
            decoy.snapTo(caster.getX(), caster.getY(), caster.getZ(), caster.getYRot(), 0.0f);
            if (decoy.getAttribute(Attributes.MAX_HEALTH) != null) {
                decoy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(DECOY_MAX_HEALTH);
            }
            decoy.setHealth((float) DECOY_MAX_HEALTH);
            decoy.finalizeSpawn(level, level.getCurrentDifficultyAt(decoy.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
            if (level.addFreshEntity(decoy)) {
                decoy.addEffect(new MobEffectInstance(MobEffects.GLOWING, 340, 0));
                // Team assignment, both directions, plus the summoner registry.
                ctx.data().addAlly(decoy.getUUID());
                MobWarData.get(decoy).addAlly(caster.getUUID());
                SummonerTracker.registerSummoned(decoy.getUUID(), caster.getUUID());
                SquidAbilitySupport.trackEntity(state, "decoy", decoy);
                state.putBoolean("seen_alive", true);
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
        Squid decoy = decoy(ctx, level);

        if (decoy != null && decoy.isAlive()) {
            // Station keeping: hang nearly still at the anchor.
            SquidAbilitySupport.steerToward(decoy,
                state.getDoubleOr("ax", decoy.getX()),
                state.getDoubleOr("ay", decoy.getY()),
                state.getDoubleOr("az", decoy.getZ()), 0.3);
            state.putBoolean("seen_alive", true);
            if (ticks % 5 == 0) {
                state.putDouble("lx", decoy.getX());
                state.putDouble("ly", decoy.getY());
                state.putDouble("lz", decoy.getZ());
                // Remember who is hurting it, for the killer blind:
                // once the decoy is gone its own hurt record is too.
                LivingEntity attacker = decoy.getLastHurtByMob();
                if (attacker != null) {
                    state.store("killer", UUIDUtil.CODEC, attacker.getUUID());
                }
            }
            // The slowly expanding ink cloud.
            if (ticks % 4 == 0) {
                double spread = Math.min(0.5 + ticks * 0.01, 2.5);
                level.sendParticles(ParticleTypes.SQUID_INK, decoy.getX(), decoy.getY() + 0.3, decoy.getZ(),
                    3, spread, spread * 0.7, spread, 0.02);
            }
            // The commitment: enemies locked onto the caster switch to the decoy.
            if (ticks % 20 == 0) {
                commitEnemies(ctx, decoy);
            }
            return;
        }

        // The decoy is gone: burst once, at the last recorded position,
        // only when it was seen alive before (a kill), never for a
        // decoy that simply never resolved (a chunk unload).
        if (decoy == null && state.getBooleanOr("seen_alive", false)
            && !state.getBooleanOr("burst_done", false) && ticks % 5 == 0) {
            burst(ctx, level);
        }
    }

    /** Retargets enemy Mobs aiming at the caster onto the decoy. One bounded query. */
    private void commitEnemies(AbilityContext ctx, Squid decoy) {
        Mob caster = ctx.mob();
        List<LivingEntity> nearby = SquidAbilitySupport.enemiesInBox(ctx,
            decoy.getX(), decoy.getY(), decoy.getZ(), 12.0, 4.0, 4.0);
        for (LivingEntity enemy : nearby) {
            if (enemy instanceof Mob enemyMob && enemyMob.getTarget() == caster) {
                enemyMob.setTarget(decoy);
            }
        }
    }

    /** The death burst: a last ink cloud at the decoy's last position, blinding its killer longest. */
    private void burst(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        state.putBoolean("burst_done", true);
        state.putInt("burst_tick", ctx.ticksActive());
        double bx = state.getDoubleOr("lx", ctx.mob().getX());
        double by = state.getDoubleOr("ly", ctx.mob().getY());
        double bz = state.getDoubleOr("lz", ctx.mob().getZ());

        level.sendParticles(ParticleTypes.SQUID_INK, bx, by + 0.3, bz,
            20, 1.2, 0.9, 1.2, 0.05);
        List<LivingEntity> caught = SquidAbilitySupport.enemiesInBox(ctx, bx, by, bz, 4.0, 3.0, 3.0);
        for (LivingEntity victim : caught) {
            victim.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 100, 0));
        }
        // The killer, recorded while the decoy lived, gets the longer blind.
        Entity killer = SquidAbilitySupport.resolveStored(level, state, "killer");
        if (killer instanceof LivingEntity killerLiving && SquidAbilitySupport.isEnemy(ctx, killerLiving)) {
            killerLiving.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 140, 0));
        }
    }

    /** The tracked decoy, or null when it does not resolve right now. */
    private Squid decoy(AbilityContext ctx, ServerLevel level) {
        ListTag list = ctx.state().getListOrEmpty("decoy");
        if (list.isEmpty()) {
            return null;
        }
        Entity found = SquidAbilitySupport.resolveTracked(level, list.getCompoundOrEmpty(0));
        return found instanceof Squid squid ? squid : null;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        int burstTick = ctx.state().getIntOr("burst_tick", -1);
        if (burstTick >= 0 && ctx.ticksActive() - burstTick >= END_AFTER_BURST_TICKS) {
            return true;
        }
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
        if (found instanceof Squid decoy) {
            MobWarData.get(decoy).removeAlly(caster.getUUID());
            if (decoy.isAlive()) {
                level.sendParticles(ParticleTypes.SQUID_INK,
                    decoy.getX(), decoy.getY() + 0.3, decoy.getZ(),
                    10, 0.5, 0.4, 0.5, 0.05);
                decoy.discard();
            }
        }
    }
}
