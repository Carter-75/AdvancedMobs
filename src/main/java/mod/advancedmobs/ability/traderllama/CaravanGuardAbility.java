package mod.advancedmobs.ability.traderllama;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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
import net.minecraft.world.entity.animal.equine.Llama;
import net.minecraft.world.entity.projectile.LlamaSpit;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Caravan Guard (Trader Llama ability 1 of 5, summon).
 *
 * WHAT IT DOES: the trader llama stamps and calls, and two
 * plain llamas hurry in to flank it, HEART motes over the
 * reunion. As the formation sets, all three gain a shimmer
 * of ABSORPTION hearts. The guards bodyguard the trader:
 * they throw themselves at whatever is attacking it, and the
 * trader llama repays them by spitting at whatever they are
 * fighting.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the trader llama's current target within 16 blocks
 *   (distance squared 256), and the caster really is a Llama
 *   (TraderLlama extends Llama; the vanilla spit projectile
 *   and the caravan API both need one).
 * - The call (start()): LLAMA_AMBIENT plays at volume 1.5,
 *   pitch 0.9 (the stamp and call), with 8 HEART and 6 NOTE
 *   particles over the caster.
 * - The guards (start()): 2 plain llamas, spawned 2.5
 *   blocks to the caster's left and right (the side axis is
 *   the caster's look direction rotated 90 degrees, so
 *   "flanking" is true from the trader llama's own facing),
 *   created with EntitySpawnReason.MOB_SUMMONED and
 *   finalizeSpawn like every other summon in this mod. Each
 *   is a plain vanilla llama plus a transient FOLLOW_RANGE
 *   modifier of +16 ADD_VALUE under a fixed id (so the
 *   guards can see the fight they are guarding). Formation:
 *   each guard joins the caster's caravan through the
 *   vanilla Llama.joinCaravan API. Team assignment, per
 *   guard, both directions plus the registry: the caster's
 *   data gains the guard as an ally, the guard's data gains
 *   the caster, and the pair is registered in
 *   SummonerTracker (which also tags the guard umw_summoned
 *   for the natural spawn blocker). Each guard is ordered
 *   onto the caster's current target at spawn.
 * - The shimmer (start()): ABSORPTION I (amplifier 0, 4
 *   absorption points) for 420 ticks on the caster and on
 *   each guard as it spawns: the design's shimmer as the
 *   formation sets, one application riding its own clock.
 *   end() removes the caster's ABSORPTION (the guards are
 *   discarded there anyway); a trader llama that somehow had
 *   ABSORPTION from another source would lose it at end(),
 *   an accepted edge case shared with every fixed-duration
 *   effect in this system.
 * - Duration: 400 ticks (20 seconds), hard finish.
 * - Bodyguarding (tick(), every 5 ticks): the interception
 *   target is the caster's last hurt-by mob when it is a
 *   living enemy (the mob actually attacking the trader
 *   llama), falling back to the caster's current combat
 *   target. Each living guard whose target is not the
 *   interception target is re-ordered onto it, so the
 *   guards pile onto whatever threatens the trader instead
 *   of wandering into their own fights. Their biting and
 *   spitting is their own vanilla combat.
 * - The repayment (tick(), every 5 ticks, gated to at most
 *   one spit per 40 ticks, tracked as "last_spit"): the
 *   first living guard found fighting a living enemy that
 *   is not the caster gets a repaid spit: one real LlamaSpit
 *   created with the vanilla LlamaSpit(Level, Llama)
 *   constructor (the caster is its owner, vanilla runs its
 *   flight and its 1.0 contact damage), placed at the
 *   caster's eye position pushed 0.6 blocks along its look
 *   direction and shot at speed 1.5 with inaccuracy 1.0
 *   toward that enemy's live chest. LLAMA_SPIT plays at
 *   volume 1.0, pitch 1.0. If no guard is fighting anything
 *   of its own, no spit is repaid that cycle.
 * - end(): each surviving guard leaves the caravan
 *   (leaveCaravan), is discarded in 6 POOF particles, and
 *   the alliance records are removed on both sides for
 *   every tracked guard; the caster's ABSORPTION is
 *   removed. If the caster dies first, the framework
 *   termination path runs this same end(), so the guard
 *   never outlives the trader it was guarding.
 *   SummonerTracker records are deliberately not
 *   unregistered, matching the other summons in this mod:
 *   that registry cleans up on the guard's own removal.
 *
 * WHAT IT LOOKS LIKE: a stamp, a call, and two plain llamas
 * swinging in from the flanks with hearts over the reunion,
 * golden absorption hearts shimmering on all three. The
 * guards charge the trader's attacker while the trader
 * llama itself hangs back and lobs repayment spits into
 * their fight.
 *
 * PERFORMANCE: start() does 2 spawns, once. tick() returns
 * immediately except every 5th tick, where it resolves at
 * most 2 UUIDs, reads the caster's last hurt-by mob, and
 * fires at most one projectile per 40 ticks: no entity
 * scans, no per-tick allocations. Particle budget per
 * activation: 14 at start plus at most 2 times 6 = 12 at
 * end(), total at most 26 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "guards" (list of compounds, one per
 * summoned llama: "id", the guard's UUID), "last_spit"
 * (the tick of the last repaid spit).
 */
public final class CaravanGuardAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int GUARD_COUNT = 2;
    private static final int BABYSIT_INTERVAL = 5;
    private static final int SPIT_INTERVAL = 40;
    private static final int ABSORPTION_TICKS = 420;
    private static final double SPAWN_DISTANCE = 2.5;
    private static final double SPIT_SPEED = 1.5;
    private static final Identifier FOLLOW_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "caravan_guard_follow");

    @Override
    public String id() {
        return "caravan_guard";
    }

    @Override
    public String displayName() {
        return "Caravan Guard";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob() instanceof Llama
            && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();
        ctx.state().put("guards", new ListTag());
        ctx.state().putInt("last_spit", -SPIT_INTERVAL);
        if (level == null) {
            return;
        }

        // The stamp and call: hearts over the reunion, notes for the summons.
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.LLAMA_AMBIENT,
            SoundSource.HOSTILE, 1.5f, 0.9f);
        level.sendParticles(ParticleTypes.HEART, caster.getX(), caster.getY() + 1.2, caster.getZ(),
            8, 0.5, 0.3, 0.5, 0.05);
        level.sendParticles(ParticleTypes.NOTE, caster.getX(), caster.getY() + 1.4, caster.getZ(),
            6, 0.6, 0.3, 0.6, 0.1);

        // The shimmer settles on the trader as the formation sets.
        caster.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, ABSORPTION_TICKS, 0));

        // The side axis: the caster's look direction rotated 90 degrees.
        Vec3 look = caster.getLookAngle();
        double sideX = -look.z;
        double sideZ = look.x;
        double sideLength = Math.sqrt(sideX * sideX + sideZ * sideZ);
        if (sideLength < 1.0e-4) {
            sideX = 1.0;
            sideZ = 0.0;
        } else {
            sideX /= sideLength;
            sideZ /= sideLength;
        }

        LivingEntity target = ctx.target();
        for (int i = 0; i < GUARD_COUNT; i++) {
            Llama guard = EntityTypes.LLAMA.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (guard == null) {
                continue;
            }
            double sign = i == 0 ? 1.0 : -1.0;
            guard.snapTo(caster.getX() + sideX * SPAWN_DISTANCE * sign, caster.getY(),
                caster.getZ() + sideZ * SPAWN_DISTANCE * sign,
                ctx.random().nextFloat() * 360.0f, 0.0f);
            guard.finalizeSpawn(level, level.getCurrentDifficultyAt(guard.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
            TraderLlamaAbilitySupport.addModifier(guard, Attributes.FOLLOW_RANGE,
                new AttributeModifier(FOLLOW_ID, 16.0, AttributeModifier.Operation.ADD_VALUE));
            if (!level.addFreshEntity(guard)) {
                continue;
            }
            // Formation: the vanilla caravan, behind the caster.
            if (caster instanceof Llama head) {
                guard.joinCaravan(head);
            }
            // Team assignment, both directions, plus the summoner registry.
            ctx.data().addAlly(guard.getUUID());
            MobWarData.get(guard).addAlly(caster.getUUID());
            SummonerTracker.registerSummoned(guard.getUUID(), caster.getUUID());
            guard.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, ABSORPTION_TICKS, 0));
            if (target != null) {
                guard.setTarget(target);
            }
            TraderLlamaAbilitySupport.trackEntity(ctx.state(), "guards", guard);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks % BABYSIT_INTERVAL != 0) {
            return; // Cheap early-out: bodyguarding runs every 5 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();

        // The interception target: whoever is attacking the
        // trader llama, else the trader llama's own target.
        LivingEntity intercept = null;
        LivingEntity attacker = caster.getLastHurtByMob();
        if (attacker != null && TraderLlamaAbilitySupport.isEnemy(ctx, attacker)) {
            intercept = attacker;
        } else if (ctx.target() != null) {
            intercept = ctx.target();
        }

        ListTag guards = ctx.state().getListOrEmpty("guards");
        LivingEntity repayTarget = null;
        for (int i = 0; i < guards.size(); i++) {
            Entity found = TraderLlamaAbilitySupport.resolveTracked(level, guards.getCompoundOrEmpty(i));
            if (found instanceof Llama guard && guard.isAlive()) {
                if (intercept != null && guard.getTarget() != intercept) {
                    guard.setTarget(intercept);
                }
                // A guard fighting something of its own earns a repaid spit.
                LivingEntity guardTarget = guard.getTarget();
                if (repayTarget == null && guardTarget != null && guardTarget != caster
                    && TraderLlamaAbilitySupport.isEnemy(ctx, guardTarget)) {
                    repayTarget = guardTarget;
                }
            }
        }

        // The repayment: at most one spit per SPIT_INTERVAL ticks.
        if (repayTarget != null && ticks - ctx.state().getIntOr("last_spit", -SPIT_INTERVAL) >= SPIT_INTERVAL
            && caster instanceof Llama llama) {
            fireRepaySpit(ctx, level, llama, repayTarget);
            ctx.state().putInt("last_spit", ticks);
        }
    }

    /** Fires the trader llama's repaid spit at the guard's opponent. */
    private void fireRepaySpit(AbilityContext ctx, ServerLevel level, Llama llama, LivingEntity victim) {
        Vec3 origin = llama.getEyePosition().add(llama.getLookAngle().scale(0.6));
        double dx = victim.getX() - origin.x;
        double dy = (victim.getY() + victim.getBbHeight() * 0.6) - origin.y;
        double dz = victim.getZ() - origin.z;
        LlamaSpit spit = new LlamaSpit(level, llama);
        spit.setPos(origin.x, origin.y, origin.z);
        spit.shoot(dx, dy, dz, (float) SPIT_SPEED, 1.0f);
        if (level.addFreshEntity(spit)) {
            level.playSound(null, llama.getX(), llama.getY(), llama.getZ(), SoundEvents.LLAMA_SPIT,
                SoundSource.HOSTILE, 1.0f, 1.0f);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        // The shimmer ends with the guard.
        caster.removeEffect(MobEffects.ABSORPTION);

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag guards = ctx.state().getListOrEmpty("guards");
        for (int i = 0; i < guards.size(); i++) {
            CompoundTag entry = guards.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (id == null) {
                continue;
            }
            // The alliance ends with the ability, whichever side is still here.
            ctx.data().removeAlly(id);
            Entity found = level.getEntity(id);
            if (found instanceof Llama guard) {
                MobWarData.get(guard).removeAlly(caster.getUUID());
                if (guard.isAlive()) {
                    guard.leaveCaravan();
                    level.sendParticles(ParticleTypes.POOF, guard.getX(), guard.getY() + 0.5, guard.getZ(),
                        6, 0.3, 0.3, 0.3, 0.05);
                    guard.discard();
                }
            }
        }
    }
}
