package mod.advancedmobs.ability.rabbit;

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
import net.minecraft.world.entity.animal.rabbit.Rabbit;

import java.util.List;
import java.util.UUID;

/**
 * Warren Call (Rabbit ability 5 of 5, summon).
 *
 * WHAT IT DOES: the rabbit thumps the ground hard, and
 * the warren answers: four adult rabbits burst out
 * around it and scatter at high speed in every
 * direction, a bouncing screen of bodies that draws
 * enemy attention and gnaws at ankles, buying the
 * caller room to line up its next move.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the rabbit's current target within 16 blocks
 *   (distance squared 256).
 * - Duration: 300 ticks (15 seconds), hard finish.
 * - The thump (start()): 10 brown DUST motes burst at
 *   the caller's feet and RABBIT_AMBIENT plays (volume
 *   1.5, pitch 1.1). The caller is marked with the
 *   entity tag "advancedmobs_warren_caller" (the
 *   design's tag to mark the caller), removed in end().
 * - The warren (start()): 4 adult rabbits, spawned one
 *   per offset around the caller (radius 1.5 at angles
 *   45, 135, 225, and 315 degrees), each at the first
 *   collision-free spot in that offset then the caller's
 *   own position as fallback, created with
 *   EntitySpawnReason.MOB_SUMMONED and finalizeSpawn
 *   like every other summon in this mod. Each warren
 *   rabbit is FAST: its MOVEMENT_SPEED base value is
 *   set to 0.5 after finalizeSpawn (vanilla rabbit base
 *   is 0.3), the design's movement_speed modifier
 *   carried as a permanent base value on a brand-new
 *   mob, the summon sheet precedent (a real mob, not a
 *   prop, so there is no transient modifier to clean
 *   up). Team assignment, per rabbit, both directions
 *   plus the registry: the caller's data gains the
 *   rabbit as an ally, the rabbit's data gains the
 *   caller, and the pair is registered in
 *   SummonerTracker (which also tags the rabbit
 *   umw_summoned for the natural spawn blocker). Each
 *   rabbit is ordered onto the caller's current target
 *   at spawn.
 * - Babysitting (tick(), every 5 ticks): each living
 *   rabbit's position is recorded into the state tag, a
 *   rabbit whose target is not the caller's current
 *   target is re-ordered onto it, and the rabbit's
 *   navigation is driven at the target at speed 1.3
 *   whenever the target is more than 1.2 blocks away: a
 *   passive animal's AI never chases a set target on
 *   its own, so without the drive the warren would mill
 *   around the caller instead of scattering onto the
 *   enemy (the Sporeling Swarm precedent).
 * - The gnawing (same babysit pass): a warren rabbit
 *   within 1.2 blocks of the caller's current target
 *   gnaws it for 1.0 mob-attack damage credited to that
 *   rabbit, at most once per rabbit per 20 ticks (each
 *   rabbit's last gnaw tick is recorded in its entry),
 *   with 2 DAMAGE_INDICATOR particles at the victim's
 *   feet. Four rabbits all in range gnaw for at most
 *   4.0 damage a second between them: ankle pressure,
 *   not a real threat on its own.
 * - The screen (tick(), every 20 ticks): one bounded
 *   query at radius 8.0 around the caller; every enemy
 *   MOB whose current target is the caller is
 *   retargeted onto a random living warren rabbit (the
 *   draw of the design). Players are never retargeted,
 *   and neither is the caller's own combat target
 *   unless it is a Mob aiming at the caller. With no
 *   living warren rabbit the pass does nothing.
 * - A rabbit's death (same babysit pass): a tracked
 *   rabbit that no longer resolves, or resolves dead,
 *   pops exactly once at its last recorded position: 6
 *   brown DUST motes, the caller's ally record for it
 *   is removed, and the entry is marked gone. A rabbit
 *   that unloads with its chunk instead of dying reads
 *   as gone to this pass and pops the same way; if it
 *   later reloads it is an ordinary fast allied rabbit
 *   with no babysitter, an accepted edge case shared
 *   with every tracked summon in this mod.
 * - end(): each surviving warren rabbit is discarded
 *   in 6 brown DUST motes at its position, the alliance
 *   records are removed on both sides for every tracked
 *   rabbit, and the caller's tag is removed. If the
 *   caller dies first, the framework termination path
 *   runs this same end(), so the warren never outlives
 *   its caller. SummonerTracker records are
 *   deliberately not unregistered, matching the other
 *   summons in this mod: that registry cleans up on the
 *   rabbit's own removal.
 *
 * WHAT IT LOOKS LIKE: a thump, a burst of dust, and
 * suddenly rabbits are everywhere, four fast brown
 * shapes bouncing off in all directions and converging
 * on the enemy's ankles while the enemy keeps swinging
 * at the wrong rabbit.
 *
 * PERFORMANCE: start() does 4 bounded collision checks
 * and 4 spawns, once. tick() returns immediately except
 * every 5th tick, where it resolves at most 4 UUIDs,
 * drives at most 4 navigations, and lands at most 4
 * gnaw damage calls; the screen pass is one bounded
 * query every 20 ticks. Particle budget per activation:
 * 10 at start, gnaws at most 4 rabbits times 15 gnaws
 * times 2 = 120, death pops at most 4 times 6 = 24,
 * end() puffs at most 4 times 6 = 24, total at most 178
 * particles.
 *
 * TERRAIN: this ability changes no blocks; the gate
 * does not apply.
 *
 * State tag keys: "warren" (list of compounds, one per
 * warren rabbit: "id" the rabbit's UUID, "x", "y", "z"
 * its last recorded position, "gone" 1 once its pop
 * ran, "gnaw" the activation tick of its last gnaw).
 */
public final class WarrenCallAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int BABYSIT_INTERVAL = 5;
    private static final int SCREEN_INTERVAL_TICKS = 20;
    private static final int WARREN_COUNT = 4;
    private static final double SPAWN_RADIUS = 1.5;
    private static final double WARREN_SPEED_BASE = 0.5;
    private static final double DRIVE_SPEED = 1.3;
    private static final double GNAW_RANGE = 1.2;
    private static final float GNAW_DAMAGE = 1.0f;
    private static final int GNAW_INTERVAL_TICKS = 20;
    private static final double SCREEN_RADIUS = 8.0;
    private static final String CALLER_TAG = "advancedmobs_warren_caller";
    private static final DustParticleOptions WARREN_DUST = new DustParticleOptions(0x8A6B48, 1.0f);
    private static final double[] OFFSET_ANGLES = {45.0, 135.0, 225.0, 315.0};

    @Override
    public String id() {
        return "warren_call";
    }

    @Override
    public String displayName() {
        return "Warren Call";
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
        ListTag warren = new ListTag();
        ctx.state().put("warren", warren);
        if (level == null) {
            return;
        }

        caster.addTag(CALLER_TAG);
        level.sendParticles(WARREN_DUST, caster.getX(), caster.getY() + 0.2, caster.getZ(),
            10, 0.5, 0.2, 0.5, 0.08);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.RABBIT_AMBIENT,
            SoundSource.HOSTILE, 1.5f, 1.1f);

        LivingEntity target = ctx.target();
        for (int i = 0; i < WARREN_COUNT; i++) {
            Rabbit mate = EntityTypes.RABBIT.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (mate == null) {
                continue;
            }
            double angle = Math.toRadians(OFFSET_ANGLES[i]);
            double spawnX = caster.getX() + Math.cos(angle) * SPAWN_RADIUS;
            double spawnZ = caster.getZ() + Math.sin(angle) * SPAWN_RADIUS;
            if (!RabbitAbilitySupport.isFreeSpot(level, mate, spawnX, caster.getY(), spawnZ)) {
                spawnX = caster.getX();
                spawnZ = caster.getZ();
            }
            mate.snapTo(spawnX, caster.getY(), spawnZ, ctx.random().nextFloat() * 360.0f, 0.0f);
            mate.finalizeSpawn(level, level.getCurrentDifficultyAt(mate.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
            // The warren sheet (see the class comment):
            // the design's movement speed, set as a base
            // value after finalizeSpawn so nothing
            // re-rolls it.
            AttributeInstance speed = mate.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speed != null) {
                speed.setBaseValue(WARREN_SPEED_BASE);
            }
            if (!level.addFreshEntity(mate)) {
                continue;
            }
            ctx.data().addAlly(mate.getUUID());
            MobWarData.get(mate).addAlly(caster.getUUID());
            SummonerTracker.registerSummoned(mate.getUUID(), caster.getUUID());
            if (target != null) {
                mate.setTarget(target);
            }

            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, mate.getUUID());
            entry.putDouble("x", mate.getX());
            entry.putDouble("y", mate.getY());
            entry.putDouble("z", mate.getZ());
            entry.putInt("gone", 0);
            entry.putInt("gnaw", -GNAW_INTERVAL_TICKS);
            warren.add(entry);
        }
        ctx.state().put("warren", warren);
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
        LivingEntity target = ctx.target();
        ListTag warren = ctx.state().getListOrEmpty("warren");
        for (int i = 0; i < warren.size(); i++) {
            CompoundTag entry = warren.getCompoundOrEmpty(i);
            if (entry.getIntOr("gone", 0) == 1) {
                continue;
            }
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            Entity found = id != null ? level.getEntity(id) : null;
            if (found instanceof Rabbit mate && mate.isAlive()) {
                entry.putDouble("x", mate.getX());
                entry.putDouble("y", mate.getY());
                entry.putDouble("z", mate.getZ());
                if (target != null) {
                    if (mate.getTarget() != target) {
                        mate.setTarget(target);
                    }
                    double distSqr = mate.distanceToSqr(target);
                    if (distSqr > GNAW_RANGE * GNAW_RANGE) {
                        mate.getNavigation().moveTo(target, DRIVE_SPEED);
                    } else if (ticks - entry.getIntOr("gnaw", -GNAW_INTERVAL_TICKS)
                            >= GNAW_INTERVAL_TICKS) {
                        entry.putInt("gnaw", ticks);
                        target.hurtServer(level, level.damageSources().mobAttack(mate), GNAW_DAMAGE);
                        level.sendParticles(ParticleTypes.DAMAGE_INDICATOR,
                            target.getX(), target.getY() + 0.3, target.getZ(),
                            2, 0.25, 0.2, 0.25, 0.05);
                    }
                }
            } else {
                pop(ctx, level, entry);
            }
            warren.set(i, entry);
        }
        ctx.state().put("warren", warren);

        if (ticks % SCREEN_INTERVAL_TICKS == 0) {
            screen(ctx, level);
        }
    }

    /** The attention draw: enemy mobs aiming at the caller are pointed at the warren instead. */
    private void screen(AbilityContext ctx, ServerLevel level) {
        Mob caster = ctx.mob();
        // One living warren rabbit to draw onto, picked at
        // random from the tracked survivors.
        Rabbit decoy = null;
        ListTag warren = ctx.state().getListOrEmpty("warren");
        int alive = 0;
        for (int i = 0; i < warren.size(); i++) {
            CompoundTag entry = warren.getCompoundOrEmpty(i);
            if (entry.getIntOr("gone", 0) == 1) {
                continue;
            }
            Entity found = RabbitAbilitySupport.resolveTracked(level, entry);
            if (found instanceof Rabbit mate && mate.isAlive()) {
                alive++;
                if (ctx.random().nextInt(alive) == 0) {
                    decoy = mate;
                }
            }
        }
        if (decoy == null) {
            return;
        }
        List<LivingEntity> enemies = RabbitAbilitySupport.enemiesAroundSelf(ctx,
            SCREEN_RADIUS, 3.0, 3.0);
        for (LivingEntity enemy : enemies) {
            if (enemy instanceof Mob enemyMob && enemyMob.getTarget() == caster) {
                enemyMob.setTarget(decoy);
            }
        }
    }

    /** The dust puff for one destroyed warren rabbit (see the class comment). Runs once per rabbit. */
    private static void pop(AbilityContext ctx, ServerLevel level, CompoundTag entry) {
        entry.putInt("gone", 1);
        UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
        if (id != null) {
            ctx.data().removeAlly(id);
        }
        double x = entry.getDoubleOr("x", ctx.mob().getX());
        double y = entry.getDoubleOr("y", ctx.mob().getY());
        double z = entry.getDoubleOr("z", ctx.mob().getZ());
        level.sendParticles(WARREN_DUST, x, y + 0.2, z, 6, 0.25, 0.2, 0.25, 0.05);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.removeTag(CALLER_TAG);
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag warren = ctx.state().getListOrEmpty("warren");
        for (int i = 0; i < warren.size(); i++) {
            CompoundTag entry = warren.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id == null) {
                continue;
            }
            ctx.data().removeAlly(id);
            Entity found = level.getEntity(id);
            if (found instanceof Rabbit mate) {
                MobWarData.get(mate).removeAlly(caster.getUUID());
                if (mate.isAlive()) {
                    level.sendParticles(WARREN_DUST, mate.getX(), mate.getY() + 0.2, mate.getZ(),
                        6, 0.25, 0.2, 0.25, 0.05);
                    mate.discard();
                }
            }
        }
    }
}
