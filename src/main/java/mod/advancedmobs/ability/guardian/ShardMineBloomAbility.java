package mod.advancedmobs.ability.guardian;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.UUID;

/**
 * Shard Mine Bloom (Guardian ability 4 of 5, summon, construct).
 *
 * WHAT IT DOES: the guardian sheds a bloom of prismarine shard
 * displays that drift slowly outward from where it shed them,
 * hanging in the water like a minefield of glittering crystals.
 * When an enemy swims too close to a shard it detonates into
 * prismarine debris and a spike burst, and shards caught in a
 * detonation go off a beat later, so chain reactions can clear a
 * whole bloom in one sparkling cascade. The guardian sheds the
 * field and keeps its distance; the field does the work.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   guardian's current target within 14 blocks (distance squared
 *   196).
 * - Duration: at most 240 ticks (12 seconds) from start(), ending
 *   early when every shard has detonated.
 * - The bloom (start()): 8 block displays showing the PRISMARINE
 *   block state at 0.4 scale, full brightness, created through
 *   the package display helper (NBT loadEntityRecursive path;
 *   display setters are private in 26.2). The shed center is the
 *   guardian's position at start() and is recorded in the state
 *   tag: the field stays where it was shed even as the guardian
 *   swims off. Each shard has a fixed outward angle (45 degrees
 *   apart, rotated by a random offset rolled in start()) stored
 *   per shard in the state tag.
 * - The drift: a shard's radius from the shed center grows from
 *   1.6 blocks at 0.03 blocks per tick, capped at 5.6 blocks; its
 *   height is 1.0 above the shed center's feet level plus a bob
 *   of plus or minus 0.3 (sine of the tick count and the shard's
 *   index). Positions are pure math from the state tag, so tick()
 *   teleports each live display to its computed spot each tick;
 *   the displays carry a 5 tick teleport duration, so the drift
 *   glides on interpolation paths instead of stepping.
 * - Detonation check every 5 ticks: ONE bounded enemy query
 *   around the shed center (radius 7.5, 3 below, 3 above), then
 *   every live shard is distance-checked against that list in
 *   memory (no per-shard queries). A shard detonates when an
 *   enemy is within 1.3 blocks of its computed position, or when
 *   its stored chain tick has been reached (see below).
 * - Detonation: the display is discarded; enemies within 1.8
 *   blocks of the shard take 4.0 damage typed as the guardian's
 *   mob attack (level.damageSources().mobAttack(mob)); 10
 *   BLOCK_CRUMBLE prismarine particles and 4 ELECTRIC_SPARK
 *   particles burst at the shard's position, with a GLASS_BREAK
 *   sound (volume 0.8, pitch 1.2). Chain reaction: every other
 *   live shard within 2.6 blocks of the detonating one that is
 *   not already chained gets its chain tick set to 3 ticks later,
 *   so a cascade ripples through the bloom instead of firing it
 *   all in one tick.
 * - The guardian itself is never a detonation trigger and never
 *   takes bloom damage: triggers and victims both follow the
 *   package enemy rule, which excludes the caster and its allies.
 *
 * WHAT IT LOOKS AND SOUNDS LIKE: eight bright prismarine crystals
 * glide outward in a widening ring, bobbing gently, lit at full
 * brightness against the murk. Detonations are a crack of glass, a
 * spray of prismarine crumbs, and a snap of sparks.
 *
 * PERFORMANCE: tick() per tick is 8 position computations and at
 * most 8 entity resolutions and teleports; the detonation check
 * every 5 ticks is one bounded entity query plus in-memory
 * distance math over at most 8 shards and the query results. No
 * registry or config lookups in the tick path (angles and the
 * center are resolved in start()). Particle budget per
 * activation: 8 ELECTRIC_SPARK at start plus at most 8
 * detonations times 14 = 112, total at most 120 particles. The 8
 * displays are 8 extra entities for at most 12 seconds, all
 * discarded by detonation or in end().
 *
 * CLEANUP: end() discards every surviving shard display. If the
 * caster dies mid-bloom, the framework termination path runs this
 * same end(), so the minefield never outlives the ability.
 *
 * State tag keys: "cx", "cy", "cz" (shed center), "shards" (list
 * of compounds, one per shard: "id" the display UUID, "angle" the
 * outward angle in radians, "boom" the tick at which a chained
 * shard detonates, -1 when not chained, "dead" true once the
 * shard has detonated).
 */
public final class ShardMineBloomAbility implements MobAbility {

    static final int DURATION_TICKS = 240;
    private static final int SHARD_COUNT = 8;
    private static final int CHECK_INTERVAL = 5;
    private static final int CHAIN_DELAY = 3;
    private static final float SHARD_DAMAGE = 4.0f;
    private static final BlockParticleOption PRISMARINE_CRUMBLE =
        new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, Blocks.PRISMARINE.defaultBlockState());

    @Override
    public String id() {
        return "shard_mine_bloom";
    }

    @Override
    public String displayName() {
        return "Shard Mine Bloom";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return ctx.target() != null && ctx.mob().distanceToSqr(ctx.target()) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        state.putDouble("cx", mob.getX());
        state.putDouble("cy", mob.getY());
        state.putDouble("cz", mob.getZ());

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            state.put("shards", new ListTag());
            return;
        }

        // The bloom is shed: 8 bright shards around the guardian.
        double offset = ctx.random().nextDouble() * Math.PI * 2.0;
        ListTag shards = new ListTag();
        for (int i = 0; i < SHARD_COUNT; i++) {
            double angle = offset + i * (Math.PI * 2.0 / SHARD_COUNT);
            Entity display = GuardianAbilitySupport.createBlockDisplay(level,
                Blocks.PRISMARINE.defaultBlockState(), 0.4f, angle);
            if (display == null) {
                continue;
            }
            double[] pos = shardPosition(state, 0, i, angle);
            display.snapTo(pos[0], pos[1], pos[2]);
            if (!level.addFreshEntity(display)) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, display.getUUID());
            entry.putDouble("angle", angle);
            entry.putInt("boom", -1);
            entry.putBoolean("dead", false);
            shards.add(entry);
        }
        state.put("shards", shards);

        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, mob.getX(), mob.getY() + 0.7, mob.getZ(),
            8, 0.8, 0.5, 0.8, 0.12);
    }

    /**
     * The computed position of shard index at the given activation
     * tick: pure math from the state tag (see the drift spec in
     * the class comment). Used for the teleports, the proximity
     * checks, and the detonation bursts alike.
     */
    private static double[] shardPosition(CompoundTag state, int ticks, int index, double angle) {
        double radius = Math.min(1.6 + 0.03 * ticks, 5.6);
        double bob = 0.3 * Math.sin(ticks * 0.15 + index * 1.3);
        return new double[]{
            state.getDoubleOr("cx", 0.0) + Math.cos(angle) * radius,
            state.getDoubleOr("cy", 0.0) + 1.0 + bob,
            state.getDoubleOr("cz", 0.0) + Math.sin(angle) * radius
        };
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

        // The drift: each live shard glides to its computed spot.
        for (int i = 0; i < shards.size(); i++) {
            CompoundTag entry = shards.getCompoundOrEmpty(i);
            if (entry.getBooleanOr("dead", false) || !entry.contains("id")) {
                continue;
            }
            UUID shardId = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (shardId == null) {
                continue;
            }
            Entity display = level.getEntity(shardId);
            if (display != null) {
                double[] pos = shardPosition(state, ticks, i, entry.getDoubleOr("angle", 0.0));
                display.teleportTo(pos[0], pos[1], pos[2]);
            }
        }

        if (ticks % CHECK_INTERVAL != 0) {
            return; // Cheap early-out: proximity checks run every 5 ticks only.
        }

        // One query for the whole field, then in-memory distance
        // checks per shard.
        List<LivingEntity> enemies = GuardianAbilitySupport.enemiesInBox(ctx,
            state.getDoubleOr("cx", 0.0), state.getDoubleOr("cy", 0.0), state.getDoubleOr("cz", 0.0),
            7.5, 3.0, 3.0);
        if (enemies.isEmpty() && !anyChained(shards, ticks)) {
            return;
        }
        for (int i = 0; i < shards.size(); i++) {
            CompoundTag entry = shards.getCompoundOrEmpty(i);
            if (entry.getBooleanOr("dead", false)) {
                continue;
            }
            double[] pos = shardPosition(state, ticks, i, entry.getDoubleOr("angle", 0.0));
            int boom = entry.getIntOr("boom", -1);
            boolean chained = boom >= 0 && ticks >= boom;
            boolean touched = false;
            for (LivingEntity enemy : enemies) {
                if (enemy.distanceToSqr(pos[0], pos[1], pos[2]) <= 1.69) {
                    touched = true;
                    break;
                }
            }
            if (chained || touched) {
                detonate(ctx, level, shards, i, pos, enemies, ticks);
            }
        }
    }

    /** Whether any live shard has a chain tick that has come due. */
    private boolean anyChained(ListTag shards, int ticks) {
        for (int i = 0; i < shards.size(); i++) {
            CompoundTag entry = shards.getCompoundOrEmpty(i);
            int boom = entry.getIntOr("boom", -1);
            if (!entry.getBooleanOr("dead", false) && boom >= 0 && ticks >= boom) {
                return true;
            }
        }
        return false;
    }

    /**
     * Detonates one shard: discards its display, damages enemies
     * in the burst, plays the burst, and chains nearby shards.
     * See the class comment for the numbers.
     */
    private void detonate(AbilityContext ctx, ServerLevel level, ListTag shards, int index,
                          double[] pos, List<LivingEntity> enemies, int ticks) {
        CompoundTag state = ctx.state();
        CompoundTag entry = shards.getCompoundOrEmpty(index);
        entry.putBoolean("dead", true);

        if (entry.contains("id")) {
            UUID shardId = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (shardId != null) {
                Entity display = level.getEntity(shardId);
                if (display != null) {
                    display.discard();
                }
            }
        }

        // The burst: enemies caught close eat the spike burst.
        for (LivingEntity enemy : enemies) {
            if (enemy.distanceToSqr(pos[0], pos[1], pos[2]) <= 3.24) {
                enemy.hurtServer(level, level.damageSources().mobAttack(ctx.mob()), SHARD_DAMAGE);
            }
        }
        level.sendParticles(PRISMARINE_CRUMBLE, pos[0], pos[1], pos[2], 10, 0.3, 0.3, 0.3, 0.15);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, pos[0], pos[1], pos[2], 4, 0.3, 0.3, 0.3, 0.12);
        level.playSound(null, pos[0], pos[1], pos[2],
            SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 0.8f, 1.2f);

        // The chain: nearby live shards go off a beat later.
        for (int j = 0; j < shards.size(); j++) {
            if (j == index) {
                continue;
            }
            CompoundTag other = shards.getCompoundOrEmpty(j);
            if (other.getBooleanOr("dead", false) || other.getIntOr("boom", -1) >= 0) {
                continue;
            }
            double[] otherPos = shardPosition(state, ticks, j, other.getDoubleOr("angle", 0.0));
            double dx = otherPos[0] - pos[0];
            double dy = otherPos[1] - pos[1];
            double dz = otherPos[2] - pos[2];
            if (dx * dx + dy * dy + dz * dz <= 6.76) {
                other.putInt("boom", ticks + CHAIN_DELAY);
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        if (ctx.ticksActive() >= DURATION_TICKS) {
            return true;
        }
        ListTag shards = ctx.state().getListOrEmpty("shards");
        for (int i = 0; i < shards.size(); i++) {
            if (!shards.getCompoundOrEmpty(i).getBooleanOr("dead", false)) {
                return false;
            }
        }
        return true; // The whole bloom has gone off (or none was shed).
    }

    @Override
    public void end(AbilityContext ctx) {
        // Surviving shards wink out with the ability.
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag shards = ctx.state().getListOrEmpty("shards");
        for (int i = 0; i < shards.size(); i++) {
            CompoundTag entry = shards.getCompoundOrEmpty(i);
            if (entry.getBooleanOr("dead", false) || !entry.contains("id")) {
                continue;
            }
            UUID shardId = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (shardId == null) {
                continue;
            }
            Entity display = level.getEntity(shardId);
            if (display != null) {
                display.discard();
            }
        }
    }
}
