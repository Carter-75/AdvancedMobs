package mod.advancedmobs.ability.enderman;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
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
 * Ender Spike Field (Enderman ability 1 of 5, attack).
 *
 * WHAT IT DOES: the enderman tears the ground open along a
 * line toward its target and obsidian spikes spear up out of
 * the earth one after another, PORTAL particles screaming off
 * them as they rise. An enemy standing where a spike punches
 * through takes typed piercing damage, and the risen field
 * stands briefly as a jagged wall of obsidian along the line.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists, between 4 and 20
 *   blocks away (distance squared 16 to 400). The field is a
 *   line laid toward a target at mid range; point blank and
 *   sniper range are not its ground.
 * - The line (start()): 7 spikes, the first 1.8 blocks from
 *   the caster on the horizontal line toward the target's
 *   position at start, then one every 1.6 blocks (the last
 *   lands 11.4 blocks out), each jittered up to 0.3 blocks
 *   sideways off the line so the wall reads jagged, not
 *   machined. Each spike's ground height is found at start by
 *   scanning down from 3 above to 6 below the caster's feet
 *   level for the first block with a collision shape; a spike
 *   over a pit or a ledge with nothing in that window anchors
 *   at the caster's own feet level instead.
 * - The spikes: obsidian block displays, 0.85 wide and 2.4
 *   tall, NOT full brightness (they are torn earth, lit by the
 *   world), each tilted up to 0.12 radians about the X axis
 *   (random sign, chosen at start) for the jagged read. Spike
 *   i starts rising at tick i times 3 and rises for 6 ticks,
 *   its center climbing 1.9 blocks from buried to full height;
 *   the rise is driven by repositioning the display every tick
 *   of its rise window (the same travel pattern Frozen
 *   Carapace uses for its plates), so it reads as interpolation
 *   without any client-side interpolation state to resume
 *   after a reload: the position is a pure function of the
 *   tick count and the stored spike data.
 * - The punch: on the tick a spike reaches full height, every
 *   enemy (EndermanAbilitySupport rules) within horizontal
 *   radius 1.15 of the spike's point, from 1.0 below its
 *   ground line to 2.2 above it, takes 6.0 MAGIC damage
 *   (level.damageSources().magic(), the typed piercing damage
 *   the design calls for: magic damage ignores armor, so the
 *   spike punches through plate the way it punches through
 *   earth; no attacker is attached, the same approach Seismic
 *   Slam uses for its fall damage). Spikes are spaced 1.6
 *   apart against a 1.15 radius, so a standing enemy is
 *   normally punched by one spike; an enemy straddling two
 *   spike points can be punched by both, which is intended.
 *   The punch moment also bursts 6 obsidian BLOCK particles
 *   and 4 PORTAL particles at the spike.
 * - The wall: after rising, the spikes stand until the
 *   ability ends at tick 100 (5 seconds from start). The wall
 *   is VISUAL ONLY: displays have no hitbox and block nothing
 *   (see the support class comment), so the wall blocks
 *   movement the way a threat blocks movement, by the damage
 *   it already dealt and the ground it visibly owns, not by
 *   collision. Nothing here places a real block.
 * - Ambient: every 10 ticks, 6 REVERSE_PORTAL wisps drift
 *   along the middle of the line at spike height.
 *
 * TERRAIN RULE: this ability changes NO real blocks (the
 * spikes are displays), so the mobGriefing gate does not
 * apply and the field works identically with mobGriefing on
 * or off. See IMPLEMENTATION-RULES.md rule 6.
 *
 * WHAT IT LOOKS AND SOUNDS LIKE: an ENDERMAN_TELEPORT tear
 * (volume 1.0, pitch 0.5) as the line is chosen, then seven
 * dark obsidian spikes punching up in sequence down the line,
 * each landing with a burst of obsidian shards and screaming
 * PORTAL motes, REVERSE_PORTAL wisps leaking off the standing
 * wall until it sinks away at the end.
 *
 * PERFORMANCE: start() does 7 bounded ground scans (at most
 * 10 block reads each) and creates 7 displays, once. tick()
 * repositions only the spikes inside their 6 tick rise window
 * (at most 3 at once, given the 3 tick stagger) and resolves
 * nothing else except on punch ticks, which run one bounded
 * enemy query each (7 total per activation). The ambient runs
 * every 10 ticks. No per-tick allocations beyond resolved
 * references. Particle budget per activation: 12 PORTAL at
 * start, punches 7 times (6 BLOCK + 4 PORTAL) = 70, ambient
 * at most 9 pulses times 6 = 54, total at most 136 particles.
 * The 7 displays are the only extra entities, alive only for
 * the ability's 5 seconds.
 *
 * CLEANUP: end() discards every spike display that still
 * resolves. If the caster dies mid-field, the framework
 * termination path runs this same end(), so the wall never
 * outlives the ability or its caster.
 *
 * State tag keys: "spikes" (list of compounds, one per spike:
 * "id" the display's UUID, "x" and "z" the spike point,
 * "gy" the ground top the spike rises from).
 */
public final class EnderSpikeFieldAbility implements MobAbility {

    static final int DURATION_TICKS = 100;
    private static final int SPIKE_COUNT = 7;
    private static final double FIRST_SPIKE_DISTANCE = 1.8;
    private static final double SPIKE_SPACING = 1.6;
    private static final int RISE_TICKS = 6;
    private static final int STAGGER_TICKS = 3;
    private static final float SPIKE_WIDTH = 0.85f;
    private static final float SPIKE_HEIGHT = 2.4f;
    private static final double RISE_DEPTH = 1.9;
    private static final double DAMAGE_RADIUS = 1.15;
    private static final float PIERCE_DAMAGE = 6.0f;
    private static final BlockParticleOption OBSIDIAN_SHARDS =
        new BlockParticleOption(ParticleTypes.BLOCK, Blocks.OBSIDIAN.defaultBlockState());

    @Override
    public String id() {
        return "ender_spike_field";
    }

    @Override
    public String displayName() {
        return "Ender Spike Field";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null) {
            return false;
        }
        double distanceSqr = ctx.mob().distanceToSqr(target);
        return distanceSqr >= 16.0 && distanceSqr <= 400.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        CompoundTag state = ctx.state();
        ListTag spikes = new ListTag();
        state.put("spikes", spikes);
        LivingEntity target = ctx.target();
        if (level == null || target == null) {
            return;
        }
        Mob mob = ctx.mob();

        // The line: from the caster toward where the target
        // stands now, horizontal only.
        double dirX = target.getX() - mob.getX();
        double dirZ = target.getZ() - mob.getZ();
        double length = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (length < 1.0e-4) {
            dirX = -Math.sin(Math.toRadians(mob.getYRot()));
            dirZ = Math.cos(Math.toRadians(mob.getYRot()));
        } else {
            dirX /= length;
            dirZ /= length;
        }
        double perpX = -dirZ;
        double perpZ = dirX;
        float yaw = (float) Math.toDegrees(Math.atan2(-dirX, dirZ));

        for (int i = 0; i < SPIKE_COUNT; i++) {
            double distance = FIRST_SPIKE_DISTANCE + i * SPIKE_SPACING;
            double jitter = (ctx.random().nextDouble() * 2.0 - 1.0) * 0.3;
            double x = mob.getX() + dirX * distance + perpX * jitter;
            double z = mob.getZ() + dirZ * distance + perpZ * jitter;
            double groundY = groundTop(level, x, z, mob.getY());

            CompoundTag entry = new CompoundTag();
            entry.putDouble("x", x);
            entry.putDouble("z", z);
            entry.putDouble("gy", groundY);

            // A jagged tilt about the X axis, random per spike.
            double tilt = (ctx.random().nextDouble() * 2.0 - 1.0) * 0.12;
            Entity spike = EndermanAbilitySupport.createBlockDisplay(level,
                Blocks.OBSIDIAN.defaultBlockState(), SPIKE_WIDTH, SPIKE_HEIGHT, SPIKE_WIDTH, false,
                (float) Math.sin(tilt / 2.0), 0.0f, 0.0f, (float) Math.cos(tilt / 2.0));
            if (spike != null) {
                // Buried start position: the tick rise takes it
                // from here to full height.
                spike.snapTo(x, centerY(groundY, 0.0), z, yaw, 0.0f);
                level.addFreshEntity(spike);
                entry.store("id", UUIDUtil.CODEC, spike.getUUID());
            }
            spikes.add(entry);
        }

        // The tear: the ground splits along the line.
        double midX = mob.getX() + dirX * (FIRST_SPIKE_DISTANCE + 3.0 * SPIKE_SPACING);
        double midZ = mob.getZ() + dirZ * (FIRST_SPIKE_DISTANCE + 3.0 * SPIKE_SPACING);
        level.sendParticles(ParticleTypes.PORTAL, midX, mob.getY() + 0.6, midZ,
            12, 3.0, 0.5, 3.0, 0.4);
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
            SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0f, 0.5f);
    }

    /** The display center height for a spike at the given rise progress (0 buried, 1 full height). */
    private static double centerY(double groundY, double progress) {
        return groundY + SPIKE_HEIGHT / 2.0 - RISE_DEPTH * (1.0 - progress);
    }

    /**
     * The top of the ground at a spike point: scans down from
     * 3 above to 6 below the reference height for the first
     * block with a collision shape and returns the top of that
     * block. Falls back to the reference height when the
     * window holds nothing solid (a pit or a high ledge).
     */
    private static double groundTop(ServerLevel level, double x, double z, double referenceY) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int topY = (int) Math.floor(referenceY) + 3;
        for (int y = topY; y >= topY - 9; y--) {
            BlockPos pos = new BlockPos(bx, y, bz);
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                return y + 1.0;
            }
        }
        return Math.floor(referenceY);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        ListTag spikes = ctx.state().getListOrEmpty("spikes");

        for (int i = 0; i < spikes.size(); i++) {
            CompoundTag entry = spikes.getCompoundOrEmpty(i);
            int born = i * STAGGER_TICKS;
            int age = ticks - born;
            if (age < 0 || age > RISE_TICKS) {
                continue; // Not rising this tick: nothing to move.
            }
            double x = entry.getDoubleOr("x", 0.0);
            double z = entry.getDoubleOr("z", 0.0);
            double groundY = entry.getDoubleOr("gy", ctx.mob().getY());
            Entity spike = resolveSpike(level, entry);
            if (spike != null) {
                double progress = Math.min(1.0, age / (double) RISE_TICKS);
                spike.snapTo(x, centerY(groundY, progress), z, spike.getYRot(), 0.0f);
            }
            if (age == RISE_TICKS) {
                punch(ctx, level, x, groundY, z);
            }
        }

        if (ticks % 10 == 0 && !spikes.isEmpty()) {
            // Wisps leaking off the standing wall, at its middle spike.
            CompoundTag mid = spikes.getCompoundOrEmpty(spikes.size() / 2);
            level.sendParticles(ParticleTypes.REVERSE_PORTAL,
                mid.getDoubleOr("x", ctx.mob().getX()),
                mid.getDoubleOr("gy", ctx.mob().getY()) + 1.2,
                mid.getDoubleOr("z", ctx.mob().getZ()),
                6, 2.5, 0.8, 2.5, 0.05);
        }
    }

    /** One spike's punch: typed piercing damage to the enemies standing on its point, plus the burst. */
    private static void punch(AbilityContext ctx, ServerLevel level, double x, double groundY, double z) {
        List<LivingEntity> victims = EndermanAbilitySupport.enemiesInBox(
            ctx, x, groundY + 1.0, z, DAMAGE_RADIUS, 1.0, 2.2);
        for (LivingEntity victim : victims) {
            victim.hurtServer(level, level.damageSources().magic(), PIERCE_DAMAGE);
        }
        level.sendParticles(OBSIDIAN_SHARDS, x, groundY + 0.6, z, 6, 0.4, 0.6, 0.4, 0.2);
        level.sendParticles(ParticleTypes.PORTAL, x, groundY + 0.8, z, 4, 0.4, 0.7, 0.4, 0.3);
    }

    /** Resolves a tracked spike display from its list entry, or null when gone. */
    private Entity resolveSpike(ServerLevel level, CompoundTag entry) {
        if (!entry.contains("id")) {
            return null;
        }
        UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
        return id != null ? level.getEntity(id) : null;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag spikes = ctx.state().getListOrEmpty("spikes");
        for (int i = 0; i < spikes.size(); i++) {
            Entity spike = resolveSpike(level, spikes.getCompoundOrEmpty(i));
            if (spike != null) {
                spike.discard();
            }
        }
    }
}
