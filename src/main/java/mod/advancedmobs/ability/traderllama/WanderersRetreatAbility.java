package mod.advancedmobs.ability.traderllama;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.equine.Llama;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Wanderer's Retreat (Trader Llama ability 3 of 5, mobility).
 *
 * WHAT IT DOES: sensing danger, the trader llama and its
 * wandering trader vanish together in a swirl of PORTAL
 * particles and reappear a long way off in the direction
 * they were already traveling. Both arrive with SLOW_FALLING
 * so even a cliff edge retreat is safe, and behind them the
 * llama leaves a false trail of its own footprints in DUST,
 * leading off the wrong way.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the trader llama's current target within 16 blocks
 *   (distance squared 256), the caster really is a Llama,
 *   and real danger: the caster is at or below 60 percent
 *   health, or the target is within 5 blocks (distance
 *   squared 25). A healthy trader llama with room to spare
 *   does not abandon the fight.
 * - The trader (start()): the llama's own wandering trader,
 *   found in this order: the entity holding the llama's
 *   leash (vanilla trader llamas travel leashed to their
 *   trader) when it is a WanderingTrader; otherwise the
 *   nearest allied WanderingTrader within 24 blocks (one
 *   bounded query, the package's ally rule). If neither
 *   exists, the llama retreats alone and every "both" below
 *   is just the llama.
 * - The direction (start()): the direction they were
 *   already traveling: the caster's horizontal delta
 *   movement when it is moving faster than 0.05 blocks per
 *   tick, otherwise its horizontal look direction.
 * - The landing (start()): candidate distances of 24, 20,
 *   16, 12, then 8 blocks along that direction, tried in
 *   that order (the design's "a long way off", degrading
 *   gracefully). For each candidate, a landing scan: feet
 *   heights from 3 above to 10 below the caster's current
 *   feet, the first height where the block below has a
 *   collision shape (real ground), the feet block itself
 *   holds no fluid, and the caster's bounding box at the
 *   spot collides with nothing. The first candidate with a
 *   landing wins. If no candidate lands, the retreat
 *   fizzles: no teleport, no SLOW_FALLING, only the origin
 *   swirl, and the ability still finishes on schedule.
 * - The jump (start(), when a landing was found): 24
 *   PORTAL particles swirl at the caster's origin (12 at
 *   the trader's, when present) and CHORUS_FRUIT_TELEPORT
 *   plays at volume 1.0, pitch 1.0. The false trail is laid
 *   in the same moment: 8 footprint prints at 1.2 block
 *   spacing from the origin along a decoy direction (the
 *   travel direction rotated +65 degrees, deliberately NOT
 *   the way they went), 2 tan DUST motes (0xB09A72, scale
 *   0.8) per print at the caster's feet height. The caster
 *   teleports to the landing; the trader teleports to a
 *   landing searched the same way around a spot 1.5 blocks
 *   to the side of the caster's landing, falling back to
 *   the caster's own landing when no side spot lands. 24
 *   PORTAL particles (12 for the trader) and a second
 *   CHORUS_FRUIT_TELEPORT mark the arrival. Both mobs drop
 *   their combat targets and stop navigating: the retreat
 *   breaks the fight cleanly. Both gain SLOW_FALLING
 *   amplifier 0 for 200 ticks, riding its own clock (end()
 *   does not strip it: arriving safely IS the effect, and
 *   it must outlast the ability's 20 ticks).
 * - Arrival shimmer (tick(), ticks 1 to 10, only when the
 *   jump happened): 2 PORTAL motes drift off the caster
 *   every 2 ticks.
 * - Duration: finishes at tick 20, jump or fizzle.
 * - end(): nothing to undo. A teleport is movement, not a
 *   change to revert, and SLOW_FALLING expires on its own
 *   clock by design.
 *
 * WHAT IT LOOKS LIKE: a swirl of portal sparks around the
 * pair, a soft chorus fruit pop, and they are simply gone,
 * a line of dusty footprints wandering off in entirely the
 * wrong direction. Far away they reappear in a second
 * swirl, drifting down gently on slow falling.
 *
 * PERFORMANCE: start() runs at most 5 landing scans of at
 *   most 14 heights (each a few block reads and one
 *   collision check), doubled for the trader's side spot,
 *   plus at most one bounded radius-24 query when no leash
 *   holder is found: all one-shot work in start(). tick()
 *   is 2 particles every 2 ticks for 10 ticks. Particle
 *   budget per activation: origin 36, trail 16, arrival 36,
 *   shimmer 10, total at most 98 particles.
 *
 * TERRAIN: this ability changes no blocks (teleports move
 * entities, they do not place or break anything); the
 * mobGriefing gate does not apply.
 *
 * State tag keys: "moved" (1 when the jump happened, 0 on
 * a fizzle).
 */
public final class WanderersRetreatAbility implements MobAbility {

    private static final int DURATION_TICKS = 20;
    private static final int SLOW_FALLING_TICKS = 200;
    private static final int TRAIL_PRINTS = 8;
    private static final double TRAIL_SPACING = 1.2;
    private static final double[] DISTANCES = {24.0, 20.0, 16.0, 12.0, 8.0};
    private static final DustParticleOptions FOOTPRINT_DUST = new DustParticleOptions(0xB09A72, 0.8f);

    @Override
    public String id() {
        return "wanderers_retreat";
    }

    @Override
    public String displayName() {
        return "Wanderer's Retreat";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null || !(ctx.mob() instanceof Llama)) {
            return false;
        }
        Mob caster = ctx.mob();
        double distanceSqr = caster.distanceToSqr(target);
        if (distanceSqr > 256.0) {
            return false;
        }
        boolean hurt = caster.getHealth() <= caster.getMaxHealth() * 0.6f;
        return hurt || distanceSqr <= 25.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().putInt("moved", 0);
        ServerLevel level = ctx.serverLevel();
        if (level == null || !(ctx.mob() instanceof Mob caster)) {
            return;
        }

        WanderingTrader trader = findTrader(ctx, level, caster);

        // The direction they were already traveling.
        Vec3 motion = caster.getDeltaMovement();
        double dirX = motion.x;
        double dirZ = motion.z;
        if (dirX * dirX + dirZ * dirZ <= 0.0025) {
            Vec3 look = caster.getLookAngle();
            dirX = look.x;
            dirZ = look.z;
        }
        double dirLength = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (dirLength < 1.0e-4) {
            dirX = 1.0;
            dirZ = 0.0;
        } else {
            dirX /= dirLength;
            dirZ /= dirLength;
        }

        // The landing search, longest jump first.
        double[] landing = null;
        for (double distance : DISTANCES) {
            double candidateX = caster.getX() + dirX * distance;
            double candidateZ = caster.getZ() + dirZ * distance;
            landing = findLanding(level, caster, candidateX, candidateZ);
            if (landing != null) {
                break;
            }
        }

        // The origin swirl happens either way.
        level.sendParticles(ParticleTypes.PORTAL, caster.getX(), caster.getY() + 1.0, caster.getZ(),
            24, 0.5, 0.8, 0.5, 0.4);
        if (trader != null) {
            level.sendParticles(ParticleTypes.PORTAL, trader.getX(), trader.getY() + 1.0, trader.getZ(),
                12, 0.4, 0.7, 0.4, 0.4);
        }
        if (landing == null) {
            return; // The fizzle: nowhere safe to land, so they stay and fight.
        }

        // The false trail: footprints leading the wrong way.
        double decoyAngle = Math.atan2(dirZ, dirX) + Math.toRadians(65.0);
        double decoyX = Math.cos(decoyAngle);
        double decoyZ = Math.sin(decoyAngle);
        for (int i = 1; i <= TRAIL_PRINTS; i++) {
            level.sendParticles(FOOTPRINT_DUST,
                caster.getX() + decoyX * TRAIL_SPACING * i, caster.getY() + 0.1,
                caster.getZ() + decoyZ * TRAIL_SPACING * i,
                2, 0.15, 0.05, 0.15, 0.01);
        }

        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.HOSTILE, 1.0f, 1.0f);

        // The jump itself.
        caster.teleportTo(landing[0], landing[1], landing[2]);
        caster.getNavigation().stop();
        caster.setTarget(null);
        caster.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, SLOW_FALLING_TICKS, 0));
        level.sendParticles(ParticleTypes.PORTAL, landing[0], landing[1] + 1.0, landing[2],
            24, 0.5, 0.8, 0.5, 0.4);

        if (trader != null) {
            // The trader lands beside the llama: side spot if it lands, else the same spot.
            double sideX = -dirZ * 1.5;
            double sideZ = dirX * 1.5;
            double[] traderLanding = findLanding(level, trader,
                landing[0] + sideX, landing[2] + sideZ);
            double[] spot = traderLanding != null ? traderLanding : landing;
            trader.teleportTo(spot[0], spot[1], spot[2]);
            trader.getNavigation().stop();
            trader.setTarget(null);
            trader.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, SLOW_FALLING_TICKS, 0));
            level.sendParticles(ParticleTypes.PORTAL, spot[0], spot[1] + 1.0, spot[2],
                12, 0.4, 0.7, 0.4, 0.4);
        }

        level.playSound(null, landing[0], landing[1], landing[2],
            SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.HOSTILE, 1.0f, 0.9f);
        ctx.state().putInt("moved", 1);
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks > 10 || ticks % 2 != 0 || ctx.state().getIntOr("moved", 0) == 0) {
            return; // Cheap early-out: the shimmer is 5 small pulses after a real jump.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        level.sendParticles(ParticleTypes.PORTAL, caster.getX(), caster.getY() + 1.0, caster.getZ(),
            2, 0.4, 0.6, 0.4, 0.2);
    }

    /** The llama's own wandering trader: leash holder first, then the nearest allied one within 24 blocks. */
    private WanderingTrader findTrader(AbilityContext ctx, ServerLevel level, Mob caster) {
        Entity holder = caster.getLeashHolder();
        if (holder instanceof WanderingTrader leashed && leashed.isAlive()) {
            return leashed;
        }
        List<LivingEntity> nearby = TraderLlamaAbilitySupport.alliesInBox(ctx,
            caster.getX(), caster.getY(), caster.getZ(), 24.0, 6.0, 6.0);
        WanderingTrader nearest = null;
        double nearestSqr = Double.MAX_VALUE;
        for (LivingEntity candidate : nearby) {
            if (candidate instanceof WanderingTrader trader) {
                double distanceSqr = caster.distanceToSqr(trader);
                if (distanceSqr < nearestSqr) {
                    nearestSqr = distanceSqr;
                    nearest = trader;
                }
            }
        }
        return nearest;
    }

    /**
     * Finds a safe landing near the given column for the
     * given mob: feet heights from 3 above to 10 below the
     * mob's current feet, the first with real ground below,
     * no fluid at the feet, and a collision-free bounding
     * box. Returns {x, y, z} or null when nothing lands.
     */
    private double[] findLanding(ServerLevel level, Mob mob, double x, double z) {
        int baseFeet = (int) Math.floor(mob.getY());
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        double halfWidth = mob.getBbWidth() / 2.0;
        for (int dy = 3; dy >= -10; dy--) {
            int feetY = baseFeet + dy;
            BlockPos below = new BlockPos(blockX, feetY - 1, blockZ);
            BlockState belowState = level.getBlockState(below);
            if (belowState.getCollisionShape(level, below).isEmpty()) {
                continue;
            }
            BlockState feetState = level.getBlockState(new BlockPos(blockX, feetY, blockZ));
            if (!feetState.getFluidState().isEmpty()) {
                continue;
            }
            AABB box = new AABB(x - halfWidth, feetY, z - halfWidth,
                x + halfWidth, feetY + mob.getBbHeight(), z + halfWidth);
            if (!level.noCollision(mob, box)) {
                continue;
            }
            return new double[]{x, feetY, z};
        }
        return null;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to undo: the teleport is movement, and the
        // SLOW_FALLING is the arrival safety, expiring on its
        // own clock by design.
    }
}
