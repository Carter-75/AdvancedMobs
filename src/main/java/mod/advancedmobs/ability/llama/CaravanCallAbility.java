package mod.advancedmobs.ability.llama;

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
import net.minecraft.world.entity.animal.equine.TraderLlama;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Caravan Call (Llama ability 3 of 5, summon).
 *
 * WHAT IT DOES: the llama brays, and two trader llamas
 * trot in from opposite sides, NOTE particles ringing like
 * bells and a few HEART motes over the reunion. They slot
 * into formation behind the caller through the vanilla
 * caravan system, and the three llamas fight as a unit,
 * spitting at the same target; while the caravan holds
 * together, all three gain RESISTANCE from watching each
 * other's backs.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the llama's current target within 16 blocks (distance
 *   squared 256).
 * - The call (start()): LLAMA_AMBIENT plays at volume 1.5,
 *   pitch 0.9 (the bray), with 10 NOTE and 4 HEART
 *   particles over the caller.
 * - The caravan (start()): 2 trader llamas, spawned 3.0
 *   blocks to the caller's left and right (the side axis is
 *   the caller's look direction rotated 90 degrees, so
 *   "opposite sides" is true from the llama's own facing),
 *   created with EntitySpawnReason.MOB_SUMMONED and
 *   finalizeSpawn like every other summon in this mod.
 *   Each is a plain vanilla trader llama plus a transient
 *   FOLLOW_RANGE modifier of +16 ADD_VALUE under a fixed
 *   id (the design's FOLLOW_RANGE line). Formation: when
 *   the caster is a Llama, each mate joins its caravan
 *   through the vanilla Llama.joinCaravan API, which is
 *   the "leashed into formation" of the design carried by
 *   the game's own caravan system. Team assignment, per
 *   mate, both directions plus the registry: the caster's
 *   data gains the mate as an ally, the mate's data gains
 *   the caster, and the pair is registered in
 *   SummonerTracker (which also tags the mate umw_summoned
 *   for the natural spawn blocker). Each mate is ordered
 *   onto the caster's current target at spawn; their
 *   spitting is their own vanilla ranged attack.
 * - Duration: 400 ticks (20 seconds), hard finish.
 * - Babysitting (tick(), every 5 ticks): each living
 *   tracked mate whose target is not the caster's current
 *   target is re-ordered onto it, keeping the caravan
 *   spitting at one shared target.
 * - The shared guard (tick(), every 20 ticks): while at
 *   least one mate still lives (the caravan holds
 *   together), RESISTANCE I (amplifier 0) is refreshed for
 *   40 ticks on the caster and on every living tracked
 *   mate, with 4 NOTE particles over the caller. The
 *   effects ride their own 40 tick clocks; end() does not
 *   strip them, they expire naturally just after the
 *   ability ends. If both mates die, the refresh stops:
 *   a llama alone has no backs being watched.
 * - end(): each surviving mate leaves the caravan
 *   (leaveCaravan), is discarded in 6 POOF particles, and
 *   the alliance records are removed on both sides for
 *   every tracked mate. If the caster dies first, the
 *   framework termination path runs this same end(), so
 *   the caravan never outlives its caller. SummonerTracker
 *   records are deliberately not unregistered, matching
 *   the other summons in this mod: that registry cleans up
 *   on the mate's own removal.
 *
 * WHAT IT LOOKS LIKE: a bray, bell-like notes, and two
 * trader llamas swinging in from the flanks to fall into
 * step behind the caller, the three of them spitting in
 * ragged volleys at the same enemy.
 *
 * PERFORMANCE: start() does 2 spawns, once. tick() returns
 * immediately except every 5th tick, where it resolves at
 * most 2 UUIDs and writes at most 2 target re-orders; the
 * guard pass every 20 ticks touches at most 3 entities and
 * sends 4 particles. Particle budget per activation: 14 at
 * start, 20 guard pulses times 4 = 80, end() puffs at most
 * 2 times 6 = 12, total at most 106 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "caravan" (list of compounds, one per
 * summoned trader llama: "id", the mate's UUID).
 */
public final class CaravanCallAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int MATE_COUNT = 2;
    private static final int BABYSIT_INTERVAL = 5;
    private static final int GUARD_INTERVAL = 20;
    private static final double SPAWN_DISTANCE = 3.0;
    private static final Identifier FOLLOW_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "caravan_call_follow");

    @Override
    public String id() {
        return "caravan_call";
    }

    @Override
    public String displayName() {
        return "Caravan Call";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();
        ctx.state().put("caravan", new ListTag());
        if (level == null) {
            return;
        }

        // The bray: bells implied by notes, hearts over the reunion.
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.LLAMA_AMBIENT,
            SoundSource.HOSTILE, 1.5f, 0.9f);
        level.sendParticles(ParticleTypes.NOTE, caster.getX(), caster.getY() + 1.4, caster.getZ(),
            10, 0.6, 0.3, 0.6, 0.1);
        level.sendParticles(ParticleTypes.HEART, caster.getX(), caster.getY() + 1.2, caster.getZ(),
            4, 0.5, 0.3, 0.5, 0.05);

        // The side axis: the caller's look direction rotated 90 degrees.
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
        for (int i = 0; i < MATE_COUNT; i++) {
            TraderLlama mate = EntityTypes.TRADER_LLAMA.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (mate == null) {
                continue;
            }
            double sign = i == 0 ? 1.0 : -1.0;
            mate.snapTo(caster.getX() + sideX * SPAWN_DISTANCE * sign, caster.getY(),
                caster.getZ() + sideZ * SPAWN_DISTANCE * sign,
                ctx.random().nextFloat() * 360.0f, 0.0f);
            mate.finalizeSpawn(level, level.getCurrentDifficultyAt(mate.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
            LlamaAbilitySupport.addModifier(mate, Attributes.FOLLOW_RANGE,
                new AttributeModifier(FOLLOW_ID, 16.0, AttributeModifier.Operation.ADD_VALUE));
            if (!level.addFreshEntity(mate)) {
                continue;
            }
            // Formation: the vanilla caravan, behind the caller.
            if (caster instanceof Llama head) {
                mate.joinCaravan(head);
            }
            // Team assignment, both directions, plus the summoner registry.
            ctx.data().addAlly(mate.getUUID());
            MobWarData.get(mate).addAlly(caster.getUUID());
            SummonerTracker.registerSummoned(mate.getUUID(), caster.getUUID());
            if (target != null) {
                mate.setTarget(target);
            }
            LlamaAbilitySupport.trackEntity(ctx.state(), "caravan", mate);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks % BABYSIT_INTERVAL != 0) {
            return; // Cheap early-out: babysitting runs every 5 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        LivingEntity target = ctx.target();
        ListTag caravan = ctx.state().getListOrEmpty("caravan");
        boolean anyMateAlive = false;
        for (int i = 0; i < caravan.size(); i++) {
            Entity found = LlamaAbilitySupport.resolveTracked(level, caravan.getCompoundOrEmpty(i));
            if (found instanceof TraderLlama mate && mate.isAlive()) {
                anyMateAlive = true;
                if (target != null && mate.getTarget() != target) {
                    mate.setTarget(target);
                }
            }
        }
        // The shared guard, refreshed every 20 ticks while the caravan holds.
        if (ticks % GUARD_INTERVAL == 0 && anyMateAlive) {
            level.sendParticles(ParticleTypes.NOTE, caster.getX(), caster.getY() + 1.4, caster.getZ(),
                4, 0.6, 0.3, 0.6, 0.1);
            caster.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 40, 0));
            for (int i = 0; i < caravan.size(); i++) {
                Entity found = LlamaAbilitySupport.resolveTracked(level, caravan.getCompoundOrEmpty(i));
                if (found instanceof TraderLlama mate && mate.isAlive()) {
                    mate.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 40, 0));
                }
            }
        }
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
        ListTag caravan = ctx.state().getListOrEmpty("caravan");
        for (int i = 0; i < caravan.size(); i++) {
            CompoundTag entry = caravan.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id == null) {
                continue;
            }
            // The alliance ends with the ability, whichever side is still here.
            ctx.data().removeAlly(id);
            Entity found = level.getEntity(id);
            if (found instanceof TraderLlama mate) {
                MobWarData.get(mate).removeAlly(caster.getUUID());
                if (mate.isAlive()) {
                    mate.leaveCaravan();
                    level.sendParticles(ParticleTypes.POOF, mate.getX(), mate.getY() + 0.5, mate.getZ(),
                        6, 0.3, 0.3, 0.3, 0.05);
                    mate.discard();
                }
            }
        }
    }
}
