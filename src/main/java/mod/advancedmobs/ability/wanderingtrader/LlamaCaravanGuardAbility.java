package mod.advancedmobs.ability.wanderingtrader;

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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.equine.TraderLlama;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Llama Caravan Guard (Wandering Trader ability 1 of 5, summon).
 *
 * WHAT IT DOES: the trader whistles its caravan in. Two trader
 * llamas in their blue caravan carpets hurry in from its flanks
 * in a swirl of happy villager motes and take up bodyguard
 * positions. For the rest of the activation they spit at
 * whatever threatens the trader with real llama spit, and when
 * a threat closes in they physically step between it and their
 * owner, blocking the charge with their own bodies.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the trader's current target within 16 blocks (distance
 *   squared 256).
 * - The whistle (start()): WANDERING_TRADER_AMBIENT at volume
 *   1.5, pitch 1.2 (the call), with 8 HAPPY_VILLAGER particles
 *   over the trader (the design's swirl).
 * - The guards (start()): 2 trader llamas (EntityTypes.
 *   TRADER_LLAMA), spawned 2.5 blocks to the trader's left and
 *   right (the side axis is the trader's look direction
 *   rotated 90 degrees, so "flanking" is true from the
 *   trader's own facing), created with EntitySpawnReason.
 *   MOB_SUMMONED and finalizeSpawn like every other summon in
 *   this mod. Each is fitted through its BODY equipment slot
 *   with a blue carpet (Items.CARPET.blue(), the design's
 *   "equipment via /item replace in code" line; a trader
 *   llama renders its blue and gold caravan pattern from that
 *   carpet, the way the Llama package's War Carpet works).
 *   Each guard also gains a transient FOLLOW_RANGE modifier
 *   of +16 ADD_VALUE under a fixed id, so the guards can see
 *   the fight they are guarding. Team assignment, per guard,
 *   both directions plus the registry (the design's "/team
 *   assignment for alliance" line): the trader's data gains
 *   the guard as an ally, the guard's data gains the trader,
 *   and the pair is registered in SummonerTracker (which also
 *   tags the guard umw_summoned for the natural spawn
 *   blocker). Each guard is ordered onto the trader's
 *   current target at spawn. Unlike the Trader Llama
 *   package's Caravan Guard, the guards cannot join a vanilla
 *   caravan here: the caravan head must be a llama, and the
 *   trader is not one, so the formation is held by the
 *   babysit below instead.
 * - Duration: 400 ticks (20 seconds), hard finish.
 * - Bodyguarding (tick(), every 5 ticks): the interception
 *   target is the trader's last hurt-by mob when it is a
 *   living enemy (the mob actually attacking the trader),
 *   falling back to the trader's current combat target. Each
 *   living guard whose target is not the interception target
 *   is re-ordered onto it; their spitting is their own
 *   vanilla ranged combat (real LlamaSpit projectiles, 1.0
 *   contact damage, fired by their vanilla goal, the
 *   design's "streams of real llama spit projectiles").
 *   Body blocking: when the interception target is within
 *   4.0 blocks of the trader, each guard is navigation-driven
 *   at speed 1.2 to the midpoint between the trader and the
 *   target, so the llamas physically stand in the charge's
 *   path; when no threat is that close, a guard farther than
 *   3.5 blocks from its flank anchor (the trader's position
 *   plus its spawn side offset, recomputed live from the
 *   trader's facing) is driven back to it at speed 1.0.
 * - end(): each surviving guard is discarded in 6 POOF
 *   particles, and the alliance records are removed on both
 *   sides for every tracked guard. If the trader dies first,
 *   the framework termination path runs this same end(), so
 *   the guard never outlives the trader it was guarding.
 *   SummonerTracker records are deliberately not
 *   unregistered, matching the other summons in this mod:
 *   that registry cleans up on the guard's own removal.
 *
 * WHAT IT LOOKS LIKE: a sharp whistle, green sparkles, and two
 * carpeted trader llamas swinging in from the flanks. They
 * hover at their owner's shoulders, spitting at its enemies,
 * and lunge into the gap whenever something gets close enough
 * to swing at the trader.
 *
 * PERFORMANCE: start() does 2 spawns, once. tick() returns
 * immediately except every 5th tick, where it resolves at
 * most 2 UUIDs, reads the trader's last hurt-by mob, and
 * issues at most 2 navigation drives: no entity scans, no
 * per-tick allocations. Particle budget per activation: 8 at
 * start plus at most 2 times 6 = 12 at end(), total at most
 * 20 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does not
 * apply.
 *
 * State tag keys: "guards" (list of compounds, one per
 * summoned llama: "id", the guard's UUID, and "side", the
 * spawn side sign as a double).
 */
public final class LlamaCaravanGuardAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int GUARD_COUNT = 2;
    private static final int BABYSIT_INTERVAL = 5;
    private static final double SPAWN_DISTANCE = 2.5;
    private static final double BLOCK_RANGE = 4.0;
    private static final Identifier FOLLOW_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "llama_caravan_guard_follow");

    @Override
    public String id() {
        return "llama_caravan_guard";
    }

    @Override
    public String displayName() {
        return "Llama Caravan Guard";
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
        ctx.state().put("guards", new ListTag());
        if (level == null) {
            return;
        }

        // The whistle: the trader's call, with the design's happy swirl.
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.WANDERING_TRADER_AMBIENT, SoundSource.HOSTILE, 1.5f, 1.2f);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, caster.getX(), caster.getY() + 1.4, caster.getZ(),
            8, 0.6, 0.3, 0.6, 0.05);

        // The side axis: the trader's look direction rotated 90 degrees.
        double[] side = sideAxis(caster);

        LivingEntity target = ctx.target();
        for (int i = 0; i < GUARD_COUNT; i++) {
            TraderLlama guard = EntityTypes.TRADER_LLAMA.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (guard == null) {
                continue;
            }
            double sign = i == 0 ? 1.0 : -1.0;
            guard.snapTo(caster.getX() + side[0] * SPAWN_DISTANCE * sign, caster.getY(),
                caster.getZ() + side[1] * SPAWN_DISTANCE * sign,
                ctx.random().nextFloat() * 360.0f, 0.0f);
            guard.finalizeSpawn(level, level.getCurrentDifficultyAt(guard.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
            // The caravan carpet, fitted through the BODY slot in code.
            guard.setItemSlot(EquipmentSlot.BODY, new ItemStack(Items.CARPET.blue()));
            WanderingTraderAbilitySupport.addModifier(guard, Attributes.FOLLOW_RANGE,
                new AttributeModifier(FOLLOW_ID, 16.0, AttributeModifier.Operation.ADD_VALUE));
            if (!level.addFreshEntity(guard)) {
                continue;
            }
            // Team assignment, both directions, plus the summoner registry.
            ctx.data().addAlly(guard.getUUID());
            MobWarData.get(guard).addAlly(caster.getUUID());
            SummonerTracker.registerSummoned(guard.getUUID(), caster.getUUID());
            if (target != null) {
                guard.setTarget(target);
            }
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, guard.getUUID());
            entry.putDouble("side", sign);
            ListTag guards = ctx.state().getListOrEmpty("guards");
            guards.add(entry);
            ctx.state().put("guards", guards);
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
        // trader, else the trader's own target.
        LivingEntity intercept = null;
        LivingEntity attacker = caster.getLastHurtByMob();
        if (attacker != null && WanderingTraderAbilitySupport.isEnemy(ctx, attacker)) {
            intercept = attacker;
        } else if (ctx.target() != null) {
            intercept = ctx.target();
        }
        boolean threatClose = intercept != null
            && caster.distanceToSqr(intercept) <= BLOCK_RANGE * BLOCK_RANGE;
        double[] side = sideAxis(caster);

        ListTag guards = ctx.state().getListOrEmpty("guards");
        for (int i = 0; i < guards.size(); i++) {
            CompoundTag entry = guards.getCompoundOrEmpty(i);
            Entity found = WanderingTraderAbilitySupport.resolveTracked(level, entry);
            if (!(found instanceof TraderLlama guard) || !guard.isAlive()) {
                continue;
            }
            if (intercept != null && guard.getTarget() != intercept) {
                guard.setTarget(intercept);
            }
            if (threatClose) {
                // Body block: stand on the line between trader and threat.
                double midX = (caster.getX() + intercept.getX()) / 2.0;
                double midZ = (caster.getZ() + intercept.getZ()) / 2.0;
                guard.getNavigation().moveTo(midX, caster.getY(), midZ, 1.2);
            } else {
                // Hold the flank anchor beside the trader.
                double sign = entry.getDoubleOr("side", 1.0);
                double anchorX = caster.getX() + side[0] * SPAWN_DISTANCE * sign;
                double anchorZ = caster.getZ() + side[1] * SPAWN_DISTANCE * sign;
                double dx = guard.getX() - anchorX;
                double dz = guard.getZ() - anchorZ;
                if (dx * dx + dz * dz > 3.5 * 3.5) {
                    guard.getNavigation().moveTo(anchorX, caster.getY(), anchorZ, 1.0);
                }
            }
        }
    }

    /** The trader's horizontal side axis (look direction rotated 90 degrees), as {x, z}. */
    private double[] sideAxis(Mob caster) {
        Vec3 look = caster.getLookAngle();
        double sideX = -look.z;
        double sideZ = look.x;
        double length = Math.sqrt(sideX * sideX + sideZ * sideZ);
        if (length < 1.0e-4) {
            return new double[]{1.0, 0.0};
        }
        return new double[]{sideX / length, sideZ / length};
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
            if (found instanceof TraderLlama guard) {
                MobWarData.get(guard).removeAlly(caster.getUUID());
                if (guard.isAlive()) {
                    level.sendParticles(ParticleTypes.POOF, guard.getX(), guard.getY() + 0.5, guard.getZ(),
                        6, 0.3, 0.3, 0.3, 0.05);
                    guard.discard();
                }
            }
        }
    }
}
