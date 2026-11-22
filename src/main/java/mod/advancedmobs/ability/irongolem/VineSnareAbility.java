package mod.advancedmobs.ability.irongolem;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Vine Snare (Iron Golem ability 5 of 5, defense /
 * control).
 *
 * WHAT IT DOES: thick vines burst from the golem's arms and
 * the ground around it, webbing the area in real cobwebs
 * while weaving energy runs through the snare. Enemies
 * caught in the area are slowed to a crawl by the webs
 * themselves and poisoned by the tightening vines, and the
 * golem uses the pause to reposition its bulk between the
 * snared enemies and its allies.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the golem on the ground with its target within 12
 *   blocks (distance squared 144).
 * - Duration: exactly 300 ticks (15 seconds) from start().
 * - The webs (start(), only when mobGriefingAllowed()):
 *   every column within Chebyshev radius 3 of the golem is
 *   a candidate; its surface is found by the shared scan
 *   (one above to two below the golem's feet height) and a
 *   cobweb is placed in the first replaceable cell directly
 *   above that surface, through the shared placeCobweb
 *   safety check (replaceable, dry, no block entity, not
 *   unbreakable). Hard cap 14 webs. PERMANENT, per the
 *   standing terrain rule: the webs stay after the ability
 *   ends. With mobGriefing off, no webs are placed and the
 *   effects below still run in full.
 * - The snare effects (start(), then every 20 ticks): every
 *   enemy (package rule) in a radius 4.0 box (2 below to 3
 *   above the golem's feet) gains WEAVING I and POISON I,
 *   each refreshed to 40 ticks. The webs themselves do the
 *   slowing (vanilla cobweb movement), WEAVING is the
 *   design's energy running through the snare (it also
 *   makes victims shed webs less easily, per vanilla), and
 *   POISON I is the tightening vines' damage over time
 *   (1 damage every 25 ticks at amplifier 0, and poison
 *   alone never kills). The effects ride their own clocks
 *   past the end of the ability.
 * - Reposition (start(), then every 40 ticks): one bounded
 *   query (radius 12) finds the nearest protected ally
 *   (package rule: villager types and smaller allies); if
 *   one exists, the golem's navigation is ordered to a
 *   point 2 blocks from that ally toward the golem's
 *   current target, at speed 1.0: its bulk interposed
 *   between the ally and the fight. With no protected ally
 *   nearby, the golem simply holds its ground.
 * - end() changes nothing: the webs are permanent terrain
 *   and the effects expire on their own clocks.
 *
 * WHAT IT LOOKS LIKE: at start, 20 BLOCK_CRUMBLE particles
 * of mossy cobblestone burst around the golem's feet as the
 * vines crack out of its frame, 12 ITEM_COBWEB flecks drift
 * over the webbed area, and IRON_GOLEM_ATTACK plays at
 * volume 1.0, pitch 0.8 as the arms sweep the vines out.
 * Each 20-tick effect pulse sheds 4 more ITEM_COBWEB flecks
 * over the snare.
 *
 * PERFORMANCE: start() is at most 49 column scans of at
 * most 4 block reads each with placements capped at 14, one
 * bounded query for the effects, and one bounded query for
 * the reposition. tick() returns immediately except every
 * 20th tick (one bounded query, radius 4.0) and every 40th
 * tick (one bounded query, radius 12). Particle budget per
 * activation: 32 at start plus at most 15 pulses times 4,
 * total at most 92 particles.
 *
 * State tag keys: none. The whole activation is the tick
 * counter plus world state (the permanent webs), so a
 * resumed activation after a chunk reload behaves
 * identically.
 */
public final class VineSnareAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int MAX_WEBS = 14;
    private static final BlockParticleOption VINE_CRUMBLE =
        new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, Blocks.MOSSY_COBBLESTONE.defaultBlockState());

    @Override
    public String id() {
        return "vine_snare";
    }

    @Override
    public String displayName() {
        return "Vine Snare";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().onGround()
            && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
            SoundEvents.IRON_GOLEM_ATTACK, SoundSource.HOSTILE, 1.0f, 0.8f);
        level.sendParticles(VINE_CRUMBLE, mob.getX(), mob.getY() + 0.3, mob.getZ(),
            20, 1.8, 0.4, 1.8, 0.12);
        level.sendParticles(ParticleTypes.ITEM_COBWEB, mob.getX(), mob.getY() + 1.0, mob.getZ(),
            12, 2.5, 0.8, 2.5, 0.05);

        if (ctx.mobGriefingAllowed()) {
            placeWebs(level, mob);
        }
        applySnareEffects(ctx);
        reposition(ctx);
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks % 20 == 0) {
            applySnareEffects(ctx);
        }
        if (ticks % 40 == 0) {
            reposition(ctx);
        }
    }

    /** Webs the ground around the golem in real cobwebs. mobGriefing mode only; permanent. */
    private void placeWebs(ServerLevel level, Mob mob) {
        BlockPos feet = mob.blockPosition();
        int placed = 0;
        for (int dx = -3; dx <= 3 && placed < MAX_WEBS; dx++) {
            for (int dz = -3; dz <= 3 && placed < MAX_WEBS; dz++) {
                int x = feet.getX() + dx;
                int z = feet.getZ() + dz;
                BlockPos ground = IronGolemAbilitySupport.findGround(level, x, z, feet.getY());
                if (ground == null) {
                    continue;
                }
                BlockPos webPos = ground.above();
                BlockState above = level.getBlockState(webPos);
                if (above.is(Blocks.COBWEB)) {
                    continue; // Already webbed (by this or an earlier snare).
                }
                if (IronGolemAbilitySupport.placeCobweb(level, webPos)) {
                    placed++;
                }
            }
        }
    }

    /** Refreshes WEAVING and POISON on every enemy inside the snare area. */
    private void applySnareEffects(AbilityContext ctx) {
        Mob mob = ctx.mob();
        List<LivingEntity> enemies = IronGolemAbilitySupport.enemiesInBox(
            ctx, mob.getX(), mob.getY(), mob.getZ(), 4.0, 2.0, 3.0);
        for (LivingEntity enemy : enemies) {
            enemy.addEffect(new MobEffectInstance(MobEffects.WEAVING, 40, 0));
            enemy.addEffect(new MobEffectInstance(MobEffects.POISON, 40, 0));
        }
        ServerLevel level = ctx.serverLevel();
        if (level != null && ctx.ticksActive() > 0) {
            level.sendParticles(ParticleTypes.ITEM_COBWEB, mob.getX(), mob.getY() + 1.0, mob.getZ(),
                4, 2.5, 0.8, 2.5, 0.05);
        }
    }

    /** Orders the golem to stand between its nearest protected ally and the fight. */
    private void reposition(AbilityContext ctx) {
        Mob mob = ctx.mob();
        List<LivingEntity> allies = IronGolemAbilitySupport.protectedAlliesInBox(
            ctx, mob.getX(), mob.getY(), mob.getZ(), 12.0, 3.0, 3.0);
        LivingEntity nearest = null;
        double nearestSqr = Double.MAX_VALUE;
        for (LivingEntity ally : allies) {
            double distSqr = mob.distanceToSqr(ally);
            if (distSqr < nearestSqr) {
                nearestSqr = distSqr;
                nearest = ally;
            }
        }
        if (nearest == null) {
            return; // Nobody to guard: the golem holds its ground.
        }
        LivingEntity target = ctx.target();
        double anchorX = nearest.getX();
        double anchorZ = nearest.getZ();
        if (target != null) {
            // Step 2 blocks from the ally toward the enemy:
            // the bulk lands between them.
            double dx = target.getX() - anchorX;
            double dz = target.getZ() - anchorZ;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 0.001) {
                anchorX += dx / len * 2.0;
                anchorZ += dz / len * 2.0;
            }
        }
        mob.getNavigation().moveTo(anchorX, nearest.getY(), anchorZ, 1.0);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to undo: the webs are permanent (standing
        // terrain rule) and the snare effects ride their own
        // clocks.
    }
}
