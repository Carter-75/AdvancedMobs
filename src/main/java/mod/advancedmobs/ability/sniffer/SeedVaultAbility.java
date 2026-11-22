package mod.advancedmobs.ability.sniffer;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.List;

/**
 * Seed Vault (Sniffer ability 5 of 5, summon / terrain).
 *
 * WHAT IT DOES: the sniffer plants a living garden in
 * the middle of the fight: a ring of torchflowers and
 * pitcher plants sprouting around it in a moment. The
 * garden works for its planter. Every few seconds the
 * flowers puff happy green sparkles that heal the
 * sniffer and its allies standing in the ring, and the
 * pitcher plants spit poison spores at any enemy who
 * steps inside.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the sniffer's current target within 12 blocks
 *   (distance squared 144) and the sniffer on the ground:
 *   a garden needs footing.
 * - Duration: 400 ticks (20 seconds). The garden's center
 *   is fixed at the sniffer's start position and stored
 *   in the state tag; the pulses below run from that
 *   recorded center even if the sniffer wanders off.
 * - The planting (start(), the design's setblock ring):
 *   8 ring points at radius 2.5 around the center, one
 *   every 45 degrees, alternating TORCHFLOWER (even
 *   points) and PITCHER_PLANT (odd points). Each point's
 *   column is scanned from 2 above the center's height
 *   down to 4 below (at most 7 cell reads) for the first
 *   legal cell. TERRAIN RULE (Carter, Nov 21, 2026): the
 *   flowers are PERMANENT and are placed only when
 *   AbilityContext.mobGriefingAllowed() is true at
 *   planting time; they are never removed, not even in
 *   end(). A legal cell must pass every check:
 *   - the cell state canBeReplaced() and holds no block
 *     entity, so a container, an unbreakable block, or
 *     any other block entity is never overwritten;
 *   - the plant's own vanilla canSurvive rule passes
 *     there (torchflowers and pitcher plants both need
 *     dirt-like ground beneath them), so nothing ever
 *     floats or sprouts somewhere vanilla would pop it
 *     off next tick;
 *   - a pitcher plant additionally needs the cell above
 *     its planting cell replaceable and block-entity-free,
 *     and is placed as the two halves of the double
 *     plant (lower at the cell, upper above it).
 *   A point that finds no legal cell plants nothing.
 *   Planted flower positions are recorded in the state
 *   tag for the heal pulse's sparkles. SNIFFER_DROP_SEED
 *   plays at planting (volume 1.2, pitch 1.0). With
 *   mobGriefing off, no flowers are placed and every
 *   pulse below still runs from the recorded ring
 *   geometry (the Algae Bloom precedent: the zone is
 *   code-driven and behaves the same with or without the
 *   physical plants).
 * - The heal (pulse, every 40 ticks, the design's
 *   periodic trigger in code): one bounded query (radius
 *   3.5 around the center, 2.5 below and 2.5 above):
 *   every ally in it, plus the sniffer itself when it is
 *   standing in its garden, gains INSTANT_HEALTH I (the
 *   design's instant_health line; an instant effect
 *   applied for 1 tick heals 4 health on application).
 *   The flowers answer: 1 HAPPY_VILLAGER mote at each
 *   recorded flower position (at most 8) plus 2 at the
 *   center (the design's happy villager line).
 * - The spit (pulse, every 20 ticks): one bounded query
 *   (radius 3.5 around the center, 2.5 below and 2.5
 *   above): every enemy in it gains POISON I refreshed
 *   to 30 ticks (the design's poison line), and the
 *   first 6 enemies also get 2 SPORE_BLOSSOM_AIR motes
 *   spat at them.
 * - end(): nothing is removed. The flowers are permanent
 *   terrain (see above) and the pulse effects ride their
 *   own clocks. Idempotent.
 *
 * WHAT IT LOOKS LIKE: seeds patter out of the great
 * snout and a ring of orange torchflowers and towering
 * pitcher plants stands up around the fight in a breath;
 * green sparkles well up out of the blooms in waves, and
 * anything hostile that pushes into the garden comes out
 * coughing spores and poisoned.
 *
 * PERFORMANCE: planting is at most 8 points times 7 cell
 * reads, once, in start(). The heal pulse is one bounded
 * query every 40 ticks; the spit pulse is one bounded
 * query every 20 ticks. No per-tick work at all. Particle
 * budget per activation: heal 10 pulses times at most 10
 * = 100, spit 20 pulses times at most 12 = 240, total at
 * most 340.
 *
 * State tag keys: "cx", "cy", "cz" (the garden center),
 * "planted" (flowers successfully planted), "flowers"
 * (list of compounds: "x", "y", "z" per planted flower).
 */
public final class SeedVaultAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int RING_POINTS = 8;
    private static final double RING_RADIUS = 2.5;
    private static final double GARDEN_RADIUS = 3.5;
    private static final int HEAL_INTERVAL = 40;
    private static final int SPIT_INTERVAL = 20;
    private static final int POISON_TICKS = 30;

    @Override
    public String id() {
        return "seed_vault";
    }

    @Override
    public String displayName() {
        return "Seed Vault";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0 && ctx.mob().onGround();
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        state.putDouble("cx", caster.getX());
        state.putDouble("cy", caster.getY());
        state.putDouble("cz", caster.getZ());
        state.putInt("planted", 0);
        state.put("flowers", new ListTag());

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        if (ctx.mobGriefingAllowed()) {
            plantRing(ctx, level);
        }
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.SNIFFER_DROP_SEED, SoundSource.HOSTILE, 1.2f, 1.0f);
    }

    /** Plants the flower ring (class comment): permanent, gated, vanilla-survivable cells only. */
    private void plantRing(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        double cx = state.getDoubleOr("cx", 0.0);
        double cy = state.getDoubleOr("cy", 0.0);
        double cz = state.getDoubleOr("cz", 0.0);
        int centerY = (int) Math.floor(cy);
        ListTag flowers = state.getListOrEmpty("flowers");
        int planted = 0;
        for (int i = 0; i < RING_POINTS; i++) {
            double angle = i * Math.PI / 4.0;
            int bx = (int) Math.floor(cx + Math.cos(angle) * RING_RADIUS);
            int bz = (int) Math.floor(cz + Math.sin(angle) * RING_RADIUS);
            boolean pitcher = i % 2 == 1;
            for (int y = centerY + 2; y >= centerY - 4; y--) {
                BlockPos pos = new BlockPos(bx, y, bz);
                BlockState cell = level.getBlockState(pos);
                if (!cell.canBeReplaced() || level.getBlockEntity(pos) != null) {
                    continue;
                }
                if (pitcher) {
                    BlockState lower = Blocks.PITCHER_PLANT.defaultBlockState()
                        .setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER);
                    BlockPos above = pos.above();
                    BlockState aboveCell = level.getBlockState(above);
                    if (!aboveCell.canBeReplaced() || level.getBlockEntity(above) != null) {
                        continue;
                    }
                    if (!lower.canSurvive(level, pos)) {
                        continue;
                    }
                    level.setBlock(pos, lower, Block.UPDATE_ALL);
                    level.setBlock(above, Blocks.PITCHER_PLANT.defaultBlockState()
                        .setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
                } else {
                    BlockState flower = Blocks.TORCHFLOWER.defaultBlockState();
                    if (!flower.canSurvive(level, pos)) {
                        continue;
                    }
                    level.setBlock(pos, flower, Block.UPDATE_ALL);
                }
                CompoundTag spot = new CompoundTag();
                spot.putDouble("x", pos.getX() + 0.5);
                spot.putDouble("y", pos.getY() + 0.5);
                spot.putDouble("z", pos.getZ() + 0.5);
                flowers.add(spot);
                planted++;
                break;
            }
        }
        state.put("flowers", flowers);
        state.putInt("planted", planted);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        CompoundTag state = ctx.state();
        double cx = state.getDoubleOr("cx", ctx.mob().getX());
        double cy = state.getDoubleOr("cy", ctx.mob().getY());
        double cz = state.getDoubleOr("cz", ctx.mob().getZ());

        // The spit: pitcher plants poison enemies in the garden.
        if (ticks % SPIT_INTERVAL == 0) {
            List<LivingEntity> enemies = SnifferAbilitySupport.enemiesInBox(ctx, cx, cy, cz,
                GARDEN_RADIUS, 2.5, 2.5);
            int spat = 0;
            for (LivingEntity enemy : enemies) {
                enemy.addEffect(new MobEffectInstance(MobEffects.POISON, POISON_TICKS, 0));
                if (spat < 6) {
                    level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, enemy.getX(),
                        enemy.getY() + enemy.getBbHeight() * 0.6, enemy.getZ(), 2, 0.3, 0.3, 0.3, 0.03);
                    spat++;
                }
            }
        }

        // The heal: the flowers puff sparkles that mend friends in the garden.
        if (ticks % HEAL_INTERVAL == 0) {
            List<LivingEntity> allies = SnifferAbilitySupport.alliesInBox(ctx, cx, cy, cz,
                GARDEN_RADIUS, 2.5, 2.5);
            for (LivingEntity ally : allies) {
                ally.addEffect(new MobEffectInstance(MobEffects.INSTANT_HEALTH, 1, 0));
            }
            Mob caster = ctx.mob();
            double dx = caster.getX() - cx;
            double dz = caster.getZ() - cz;
            if (dx * dx + dz * dz <= GARDEN_RADIUS * GARDEN_RADIUS) {
                caster.addEffect(new MobEffectInstance(MobEffects.INSTANT_HEALTH, 1, 0));
            }
            ListTag flowers = state.getListOrEmpty("flowers");
            for (int i = 0; i < flowers.size(); i++) {
                CompoundTag spot = flowers.getCompoundOrEmpty(i);
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                    spot.getDoubleOr("x", cx), spot.getDoubleOr("y", cy + 0.5), spot.getDoubleOr("z", cz),
                    1, 0.2, 0.2, 0.2, 0.02);
            }
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, cx, cy + 0.6, cz, 2, 0.5, 0.3, 0.5, 0.02);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // The planted flowers are permanent terrain
        // (standing terrain rule) and are deliberately not
        // removed here; pulse effects ride their own clocks.
    }
}
