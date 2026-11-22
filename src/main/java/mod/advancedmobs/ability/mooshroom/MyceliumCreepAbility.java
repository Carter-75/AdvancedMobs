package mod.advancedmobs.ability.mooshroom;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * Mycelium Creep (Mooshroom ability 4 of 5, terrain).
 *
 * WHAT IT DOES: wherever the mooshroom fights, the ground
 * itself converts to mycelium in a spreading patch that
 * follows it, veined with purple mycelium particles. Enemies
 * standing on the creep are slowed, and the mooshroom moves
 * faster and regenerates on its own fungus.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6 and the MobAbility
 * contract): terrain changes from abilities are PERMANENT
 * and gated by the vanilla mobGriefing game rule, checked
 * through AbilityContext.mobGriefingAllowed(). This ability
 * has two modes:
 * - mobGriefing ON: the ground converts and STAYS. There is
 *   no restore: end() touches no blocks, and nothing dries
 *   the creep out later. The zone's debuff and buff last
 *   only the ability duration; the mycelium itself is just
 *   terrain afterwards.
 * - mobGriefing OFF: no blocks are converted at all. The
 *   zone still runs for its duration over whatever natural
 *   mycelium is already there (a mushroom island mooshroom
 *   keeps its home turf bonuses), and the world is left
 *   exactly as it was.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the mooshroom's current target within 14 blocks
 *   (distance squared 196) and the mooshroom standing on the
 *   ground.
 * - Duration: exactly 400 ticks (20 seconds) from start().
 *   This is the zone's lifetime; in mobGriefing mode the
 *   converted ground outlives it.
 * - The creep (mobGriefing mode only, growth pulse every 20
 *   ticks, the gate re-checked at every pulse): the disc of
 *   radius 2 around the mooshroom's CURRENT block position
 *   (dx and dz in -2..2 with dx*dx + dz*dz <= 5, 13 columns),
 *   at the floor level one below the mooshroom's feet. A
 *   column converts only when its floor block is soft
 *   natural ground (grass block, dirt, coarse dirt, podzol,
 *   sand, red sand, gravel, soul sand, soul soil, or mud;
 *   MooshroomAbilitySupport.isSoftGround), has no block
 *   entity, is not unbreakable, and the block below it is
 *   face-sturdy in the live level, so the creep never
 *   floats. Each converted column becomes MYCELIUM. At most
 *   6 columns convert per pulse, so the patch visibly creeps
 *   outward as the mooshroom moves instead of flashing into
 *   place: at most 20 pulses times 6 = 120 blocks over the
 *   whole activation.
 * - The zone pulse (every 10 ticks): one bounded query at
 *   radius 5.0 around the mooshroom (2 below to 2.5 above).
 *   Every enemy found that is standing on mycelium (the
 *   block at its feet level minus one is MYCELIUM) gains
 *   SLOWNESS I (amplifier 0) for 30 ticks, refreshed by the
 *   next pulse. Enemies off the creep are untouched: the
 *   fungus only grips what stands on it.
 * - The home turf buff (same pulse): when the mooshroom
 *   itself is standing on mycelium, it gains REGENERATION I
 *   refreshed to 30 ticks and a MOVEMENT_SPEED
 *   add_multiplied_base modifier of +0.30 (modifier id
 *   advancedmobs:mycelium_creep_speed), applied on the
 *   first pulse that finds it on its fungus and removed on
 *   the first pulse that finds it off it, so the buff tracks
 *   the ground truthfully. end() removes the modifier if it
 *   is still applied.
 * - Veins: 3 MYCELIUM particles every 5 ticks drift at the
 *   mooshroom's feet.
 *
 * WHAT IT LOOKS LIKE: a purple-grey fungal stain spreading
 * under a fighting mooshroom's hooves, pulse by pulse,
 * enemies limping where they stand on it while the
 * mooshroom trots and mends on its own ground.
 *
 * PERFORMANCE: the growth pulse runs every 20 ticks (at
 * most 13 column checks of at most 3 block reads each,
 * capped at 6 writes), the zone pulse every 10 ticks (one
 * bounded query plus one block read per enemy found), the
 * veins every 5 ticks. Nothing runs per tick. Particle
 * budget per activation: 80 emissions times 3 = 240
 * particles.
 *
 * State tag keys: "buffed" (1 while the home turf speed
 * modifier is applied to the caster, else 0). No block list
 * is stored: converted blocks are permanent and never
 * revisited.
 */
public final class MyceliumCreepAbility implements MobAbility {

    static final int DURATION_TICKS = 400;
    private static final int GROWTH_INTERVAL = 20;
    private static final int ZONE_INTERVAL = 10;
    private static final int CREEP_RADIUS = 2;
    private static final int MAX_CONVERT_PER_PULSE = 6;
    private static final double ZONE_RADIUS = 5.0;
    private static final Identifier SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "mycelium_creep_speed");

    @Override
    public String id() {
        return "mycelium_creep";
    }

    @Override
    public String displayName() {
        return "Mycelium Creep";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().onGround() && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().putInt("buffed", 0);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        int ticks = ctx.ticksActive();
        if (ticks % 5 == 0) {
            level.sendParticles(ParticleTypes.MYCELIUM,
                caster.getX(), caster.getY() + 0.2, caster.getZ(),
                3, 1.2, 0.2, 1.2, 0.02);
        }
        if (ticks % GROWTH_INTERVAL == 0 && ctx.mobGriefingAllowed()) {
            growCreep(level, caster);
        }
        if (ticks % ZONE_INTERVAL != 0) {
            return; // Cheap early-out: the zone pulses every 10 ticks only.
        }
        zonePulse(ctx, level);
    }

    /**
     * One growth pulse: convert up to 6 soft ground columns
     * in the disc under the mooshroom to mycelium, for good.
     * See the TERRAIN RULE in the class comment.
     */
    private void growCreep(ServerLevel level, Mob caster) {
        BlockPos center = caster.blockPosition();
        int floorY = center.getY() - 1;
        int converted = 0;
        for (int dx = -CREEP_RADIUS; dx <= CREEP_RADIUS && converted < MAX_CONVERT_PER_PULSE; dx++) {
            for (int dz = -CREEP_RADIUS; dz <= CREEP_RADIUS && converted < MAX_CONVERT_PER_PULSE; dz++) {
                if (dx * dx + dz * dz > 5) {
                    continue;
                }
                BlockPos floorPos = new BlockPos(center.getX() + dx, floorY, center.getZ() + dz);
                if (!MooshroomAbilitySupport.isSoftGround(level, floorPos)) {
                    continue;
                }
                BlockPos belowPos = floorPos.below();
                if (!level.getBlockState(belowPos).isFaceSturdy(level, belowPos, Direction.UP)) {
                    continue; // The creep never floats.
                }
                level.setBlock(floorPos, Blocks.MYCELIUM.defaultBlockState(), Block.UPDATE_ALL);
                converted++;
            }
        }
    }

    /** One zone pulse: slow enemies standing on the creep, buff the mooshroom on its own fungus. */
    private void zonePulse(AbilityContext ctx, ServerLevel level) {
        Mob caster = ctx.mob();
        List<LivingEntity> enemies = MooshroomAbilitySupport.enemiesAroundSelf(ctx, ZONE_RADIUS, 2.0, 2.5);
        for (LivingEntity enemy : enemies) {
            if (standingOnMycelium(level, enemy)) {
                enemy.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 30, 0));
            }
        }

        // The home turf buff tracks the ground under the
        // mooshroom's own hooves.
        CompoundTag state = ctx.state();
        boolean buffed = state.getIntOr("buffed", 0) == 1;
        if (standingOnMycelium(level, caster)) {
            caster.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 30, 0));
            if (!buffed) {
                MooshroomAbilitySupport.addModifier(caster, Attributes.MOVEMENT_SPEED,
                    new AttributeModifier(SPEED_ID, 0.30, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
                state.putInt("buffed", 1);
            }
        } else if (buffed) {
            MooshroomAbilitySupport.removeModifier(caster, Attributes.MOVEMENT_SPEED, SPEED_ID);
            state.putInt("buffed", 0);
        }
    }

    /** Whether the entity is standing on a mycelium block (the block at its feet level minus one). */
    private boolean standingOnMycelium(ServerLevel level, LivingEntity entity) {
        return level.getBlockState(entity.blockPosition().below()).is(Blocks.MYCELIUM);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // The speed modifier comes off if it is still applied.
        // The converted mycelium, when placed, is permanent
        // terrain and is deliberately NOT touched here
        // (terrain rule, see the class comment).
        if (ctx.state().getIntOr("buffed", 0) == 1) {
            MooshroomAbilitySupport.removeModifier(ctx.mob(), Attributes.MOVEMENT_SPEED, SPEED_ID);
            ctx.state().putInt("buffed", 0);
        }
    }
}
