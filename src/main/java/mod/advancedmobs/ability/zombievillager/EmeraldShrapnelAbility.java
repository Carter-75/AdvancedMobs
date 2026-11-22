package mod.advancedmobs.ability.zombievillager;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.UUID;

/**
 * Emerald Shrapnel (Zombie Villager ability 1 of 5, attack).
 *
 * WHAT IT DOES: the zombie villager hurls a fan of spinning emeralds at
 * its target. Five emerald item displays rise and orbit it for half a
 * second, then streak out in a spread fan toward the target. Each shard
 * that connects deals projectile damage and sticks in glittering,
 * marking the victim GLOWING for a few seconds.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the zombie
 *   villager's current target between 3 and 14 blocks away (distance
 *   squared 9 to 196). Inside 3 blocks the fan cannot form; beyond 14
 *   the shards would fall short anyway (their range is 12).
 * - Orbit phase (ticks 1 to 10): 5 emerald item displays (scale 0.5,
 *   "fixed" transform context) circle the caster at radius 1.1 blocks,
 *   height 1.5 above its feet, one full revolution over the phase
 *   (angle speed 0.628 radians per tick, shards spaced 72 degrees
 *   apart), bobbing 0.15 blocks on a sine. Displays are repositioned
 *   with snapTo each tick; no hit checks happen in this phase.
 * - Launch (at tick 10): each shard's direction is computed toward the
 *   target's chest (live target position plus 60 percent of its height;
 *   the start() snapshot if the target is gone), with a fan spread: the
 *   5 shards aim at lateral offsets of -1.2, -0.6, 0, +0.6, +1.2 blocks
 *   perpendicular to the caster-to-target line, so the fan covers a
 *   2.4-block-wide swath at the target. Directions are stored per shard.
 * - Streak phase (ticks 11 onward): each unfinished shard advances 1.2
 *   blocks per tick along its stored direction. After each advance, one
 *   bounded enemy query (radius 1.0 around the shard, 1.0 below to 1.0
 *   above, ZombieVillagerAbilitySupport rules) checks for a hit. On the
 *   first enemy found the shard connects:
 *     Damage: 4.0 points of typed projectile damage
 *       (DamageSources.mobProjectile with the shard display as the
 *       projectile and the caster as the owner, so it reads as a thrown
 *       projectile hit, not a melee swing).
 *     Mark: GLOWING amplifier 0 for 80 ticks (4 seconds) on the victim.
 *     The shard display is discarded.
 *   A shard that travels 12 blocks without connecting fizzles: the
 *   display is discarded in a puff of sparkles. If all 5 shards connect
 *   on one victim that is 20 damage total; the fan spread makes that
 *   unlikely against a moving target.
 * - Hard finish: the ability always ends by tick 40 even if a shard is
 *   somehow still flying; end() discards any display still up.
 *
 * WHAT IT LOOKS LIKE: bright green emerald models spinning around the
 * caster's shoulders, then streaking out in a fan. Every 2 ticks during
 * the orbit, 1 HAPPY_VILLAGER sparkle per shard (25 total). During the
 * streak, 1 HAPPY_VILLAGER sparkle per shard every 2 ticks (at most 25
 * total). On a hit, 6 green CRIT particles burst at the victim and the
 * victim glows (the vanilla GLOWING outline) for 4 seconds. On a
 * fizzle, 2 HAPPY_VILLAGER sparkles at the shard's last position.
 *
 * PERFORMANCE: the orbit phase is 5 snapTo calls and a 5-particle burst
 * every 2 ticks, no entity queries. The streak phase runs at most 5
 * bounded queries per tick (2 by 2 by 2 boxes) for at most 10 ticks.
 * Shard bookkeeping lives in the state tag list; positions and
 * directions are read and written in place, no per-tick allocation
 * beyond the query result lists. Particle budget per activation: at
 * most 25 orbit plus 25 streak sparkles, at most 5 hits times 6 CRIT
 * = 30, at most 5 fizzles times 2 = 10, total at most 90 particles.
 *
 * CLEANUP: end() discards every shard display whose UUID is recorded
 * and still resolves (hit and fizzled shards already discarded
 * themselves and are skipped by the "done" flag). If the caster dies
 * mid-fan the framework termination path runs this same end(), so no
 * emerald display ever outlives the ability.
 *
 * State tag keys: "phase" (0 orbit, 1 streak), "shards" (list of
 * compounds, one per shard: "display" UUID, "x"/"y"/"z" current
 * position, "dx"/"dy"/"dz" streak direction, "dist" distance flown,
 * "done" boolean), "tx"/"ty"/"tz" (target position snapshot from
 * start(), the launch fallback).
 */
public final class EmeraldShrapnelAbility implements MobAbility {

    private static final int SHARD_COUNT = 5;
    private static final int ORBIT_TICKS = 10;
    private static final int MAX_TICKS = 40;
    private static final double ORBIT_RADIUS = 1.1;
    private static final double SHARD_SPEED = 1.2;
    private static final double SHARD_RANGE = 12.0;
    private static final float SHARD_DAMAGE = 4.0f;
    private static final int GLOW_TICKS = 80;
    private static final double[] FAN_OFFSETS = {-1.2, -0.6, 0.0, 0.6, 1.2};

    @Override
    public String id() {
        return "emerald_shrapnel";
    }

    @Override
    public String displayName() {
        return "Emerald Shrapnel";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null) {
            return false;
        }
        double distanceSqr = ctx.mob().distanceToSqr(target);
        return distanceSqr >= 9.0 && distanceSqr <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        state.putInt("phase", 0);
        LivingEntity target = ctx.target();
        if (target != null) {
            state.putDouble("tx", target.getX());
            state.putDouble("ty", target.getY());
            state.putDouble("tz", target.getZ());
        }
        if (level == null) {
            state.put("shards", new ListTag());
            return;
        }

        // The shard item: a plain emerald stack as display NBT.
        CompoundTag emerald = new CompoundTag();
        emerald.putString("id", BuiltInRegistries.ITEM.getKey(Items.EMERALD).toString());
        emerald.putInt("count", 1);

        ListTag shards = new ListTag();
        for (int i = 0; i < SHARD_COUNT; i++) {
            CompoundTag shard = new CompoundTag();
            double angle = i * (Math.PI * 2.0 / SHARD_COUNT);
            double x = mob.getX() + Math.cos(angle) * ORBIT_RADIUS;
            double y = mob.getY() + 1.5;
            double z = mob.getZ() + Math.sin(angle) * ORBIT_RADIUS;
            Entity display = ZombieVillagerAbilitySupport.createItemDisplay(level, emerald, 0.5f, "fixed");
            if (display != null) {
                display.snapTo(x, y, z);
                level.addFreshEntity(display);
                shard.store("display", UUIDUtil.CODEC, display.getUUID());
            }
            shard.putDouble("x", x);
            shard.putDouble("y", y);
            shard.putDouble("z", z);
            shard.putDouble("dx", 0.0);
            shard.putDouble("dy", 0.0);
            shard.putDouble("dz", 0.0);
            shard.putDouble("dist", 0.0);
            shard.putBoolean("done", false);
            shards.add(shard);
        }
        state.put("shards", shards);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        int ticks = ctx.ticksActive();
        ListTag shards = state.getListOrEmpty("shards");
        if (state.getIntOr("phase", 0) == 0) {
            tickOrbit(ctx, level, shards, ticks);
            if (ticks >= ORBIT_TICKS) {
                launch(ctx, shards);
                state.putInt("phase", 1);
            }
        } else {
            tickStreak(ctx, level, shards);
        }
    }

    /** Orbit phase: circle the shards around the caster's shoulders. */
    private void tickOrbit(AbilityContext ctx, ServerLevel level, ListTag shards, int ticks) {
        Mob mob = ctx.mob();
        double baseAngle = ticks * 0.628;
        for (int i = 0; i < shards.size(); i++) {
            CompoundTag shard = shards.getCompoundOrEmpty(i);
            if (shard.getBooleanOr("done", false)) {
                continue;
            }
            double angle = baseAngle + i * (Math.PI * 2.0 / SHARD_COUNT);
            double x = mob.getX() + Math.cos(angle) * ORBIT_RADIUS;
            double y = mob.getY() + 1.5 + Math.sin(ticks * 0.5 + i) * 0.15;
            double z = mob.getZ() + Math.sin(angle) * ORBIT_RADIUS;
            shard.putDouble("x", x);
            shard.putDouble("y", y);
            shard.putDouble("z", z);
            moveDisplay(ctx, level, shard, x, y, z);
            if (ticks % 2 == 0) {
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, x, y, z, 1, 0.05, 0.05, 0.05, 0.0);
            }
        }
    }

    /** Launch: fix each shard's streak direction toward the target, with the fan spread. */
    private void launch(AbilityContext ctx, ListTag shards) {
        CompoundTag state = ctx.state();
        LivingEntity target = ctx.target();
        double aimX, aimY, aimZ;
        if (target != null) {
            aimX = target.getX();
            aimY = target.getY() + target.getBbHeight() * 0.6;
            aimZ = target.getZ();
        } else {
            aimX = state.getDoubleOr("tx", ctx.mob().getX());
            aimY = state.getDoubleOr("ty", ctx.mob().getY()) + 1.0;
            aimZ = state.getDoubleOr("tz", ctx.mob().getZ());
        }
        // Perpendicular of the caster-to-target line, for the fan spread.
        double lineX = aimX - ctx.mob().getX();
        double lineZ = aimZ - ctx.mob().getZ();
        double lineLen = Math.sqrt(lineX * lineX + lineZ * lineZ);
        double perpX = 0.0, perpZ = 1.0;
        if (lineLen > 1.0e-4) {
            perpX = -lineZ / lineLen;
            perpZ = lineX / lineLen;
        }
        for (int i = 0; i < shards.size(); i++) {
            CompoundTag shard = shards.getCompoundOrEmpty(i);
            double offset = FAN_OFFSETS[Math.min(i, FAN_OFFSETS.length - 1)];
            double dx = aimX + perpX * offset - shard.getDoubleOr("x", 0.0);
            double dy = aimY - shard.getDoubleOr("y", 0.0);
            double dz = aimZ + perpZ * offset - shard.getDoubleOr("z", 0.0);
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1.0e-4) {
                dx = lineX;
                dy = 0.0;
                dz = lineZ;
                len = Math.sqrt(dx * dx + dz * dz);
                if (len < 1.0e-4) {
                    dx = 1.0;
                    len = 1.0;
                }
            }
            shard.putDouble("dx", dx / len);
            shard.putDouble("dy", dy / len);
            shard.putDouble("dz", dz / len);
        }
    }

    /** Streak phase: advance each shard, check for a hit, fizzle at max range. */
    private void tickStreak(AbilityContext ctx, ServerLevel level, ListTag shards) {
        int ticks = ctx.ticksActive();
        for (int i = 0; i < shards.size(); i++) {
            CompoundTag shard = shards.getCompoundOrEmpty(i);
            if (shard.getBooleanOr("done", false)) {
                continue;
            }
            double x = shard.getDoubleOr("x", 0.0) + shard.getDoubleOr("dx", 0.0) * SHARD_SPEED;
            double y = shard.getDoubleOr("y", 0.0) + shard.getDoubleOr("dy", 0.0) * SHARD_SPEED;
            double z = shard.getDoubleOr("z", 0.0) + shard.getDoubleOr("dz", 0.0) * SHARD_SPEED;
            double dist = shard.getDoubleOr("dist", 0.0) + SHARD_SPEED;
            shard.putDouble("x", x);
            shard.putDouble("y", y);
            shard.putDouble("z", z);
            shard.putDouble("dist", dist);
            moveDisplay(ctx, level, shard, x, y, z);
            if (ticks % 2 == 0) {
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, x, y, z, 1, 0.05, 0.05, 0.05, 0.0);
            }

            List<LivingEntity> hits = ZombieVillagerAbilitySupport.enemiesInBox(ctx, x, y, z, 1.0, 1.0, 1.0);
            if (!hits.isEmpty()) {
                LivingEntity victim = hits.get(0);
                Entity display = resolveDisplay(level, shard);
                victim.hurtServer(level, level.damageSources().mobProjectile(
                    display != null ? display : ctx.mob(), ctx.mob()), SHARD_DAMAGE);
                victim.addEffect(new MobEffectInstance(MobEffects.GLOWING, GLOW_TICKS, 0));
                level.sendParticles(ParticleTypes.CRIT, victim.getX(), victim.getY() + 1.0, victim.getZ(),
                    6, 0.3, 0.5, 0.3, 0.1);
                discardShard(level, shard);
            } else if (dist >= SHARD_RANGE) {
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, x, y, z, 2, 0.1, 0.1, 0.1, 0.02);
                discardShard(level, shard);
            }
        }
    }

    /** Moves a shard's display to the given position, if it still exists. */
    private void moveDisplay(AbilityContext ctx, ServerLevel level, CompoundTag shard, double x, double y, double z) {
        Entity display = resolveDisplay(level, shard);
        if (display != null) {
            display.snapTo(x, y, z);
        }
    }

    /** Resolves a shard's display entity from its recorded UUID, or null. */
    private Entity resolveDisplay(ServerLevel level, CompoundTag shard) {
        if (!shard.contains("display")) {
            return null;
        }
        UUID id = shard.read("display", UUIDUtil.CODEC).orElse(null);
        return id != null ? level.getEntity(id) : null;
    }

    /** Discards a shard's display and marks the shard done. Idempotent. */
    private void discardShard(ServerLevel level, CompoundTag shard) {
        Entity display = resolveDisplay(level, shard);
        if (display != null) {
            display.discard();
        }
        shard.putBoolean("done", true);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        if (ctx.ticksActive() >= MAX_TICKS) {
            return true;
        }
        CompoundTag state = ctx.state();
        if (state.getIntOr("phase", 0) == 0) {
            return false;
        }
        ListTag shards = state.getListOrEmpty("shards");
        for (int i = 0; i < shards.size(); i++) {
            if (!shards.getCompoundOrEmpty(i).getBooleanOr("done", false)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void end(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag shards = ctx.state().getListOrEmpty("shards");
        for (int i = 0; i < shards.size(); i++) {
            CompoundTag shard = shards.getCompoundOrEmpty(i);
            if (!shard.getBooleanOr("done", false)) {
                discardShard(level, shard);
            }
        }
    }
}
