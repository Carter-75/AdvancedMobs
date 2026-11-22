package mod.advancedmobs.ability.pillager;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Outpost Sniper Perch (Pillager ability 4 of 5, terrain,
 * mobility).
 *
 * WHAT IT DOES: dark oak logs and planks assemble beneath the
 * pillager in moments, lifting it onto a four-block sniper
 * perch in the middle of the fight. From elevation its
 * code-fired bolts gain bonus damage, its FOLLOW_RANGE
 * stretches across the arena, and the narrow top keeps melee
 * enemies from ever reaching it. The tower is real wood: it
 * burns and breaks like it, and knocking the pillager off it
 * ends the perch.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the pillager's current target within 20 blocks (distance
 *   squared 400) and the pillager on the ground.
 * - Duration: exactly 300 ticks (15 seconds) from start(),
 *   or early when the drop check finds the pillager off the
 *   tower.
 * - The tower (start(), only when mobGriefing is on): legs
 *   are 4 DARK_OAK_LOG columns at the corners of the 3x3
 *   around the pillager's block position (offsets plus or
 *   minus 1 on both axes), 3 levels tall starting at his feet
 *   level (12 logs); the top is a 3x3 DARK_OAK_PLANKS platform
 *   at feet level + 3 (9 planks). Cap 21 blocks. Each cell is
 *   placed only through the shared safety check (air or
 *   replaceable, dry, no block entity, not unbreakable). The
 *   legs start at his own feet level on his ground line, so a
 *   corner over a drop floats from that line, the Skybox
 *   platform precedent; the center column is never filled, so
 *   the build cannot entomb him. If the platform's center
 *   cell is solid after the build (placed by the ability or
 *   already there), the pillager teleports onto it: feet at
 *   platform level + 1, exactly 4 blocks above where he
 *   stood, and the activation is "perched". If the center
 *   cell could not be made solid (a ceiling in the way), he
 *   stays on the ground and the rest of the ability still
 *   runs.
 * - FOLLOW_RANGE: +16 ADD_VALUE for the whole activation
 *   (the design's arena survey from elevation), applied in
 *   start() and removed in end(). It applies perched or not.
 * - The elevation bonus (the design's height-based bonus
 *   damage in code): every 30 ticks, when perched, the
 *   ability fires one extra real bolt at the framework target
 *   when it is within 32 blocks (distance squared 1024):
 *   base damage 4.0 against the vanilla arrow base of 2.0
 *   (the +2.0 is the elevation bonus), speed 3.2, inaccuracy
 *   0.5 (a sniper's steadiness). CROSSBOW_SHOOT per shot. The
 *   pillager's own vanilla crossbow goal also fires from the
 *   perch at vanilla damage; the code bolts are the
 *   guaranteed elevated shots that carry the bonus. When not
 *   perched there are no bonus bolts: the bonus belongs to
 *   the height.
 * - Drop check every 10 ticks (perched only): if the
 *   pillager's feet are more than 0.5 below the platform top
 *   (knocked off, jumped off, or the tower was broken out
 *   from under him and he fell), the activation finishes at
 *   once.
 * - The end (end()): the FOLLOW_RANGE modifier is removed. A
 *   pillager still standing on his tower stays there: the
 *   tower is permanent terrain (see below), the high ground
 *   is his to keep, and only the stretch and the bonus bolts
 *   stop. No drop cushion is applied; four blocks is a hop a
 *   pillager survives trivially if he paths down.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026): the tower is PERMANENT
 * and there is no restore machinery (the Gravemud Mire,
 * Magma Ring Rising, and Skybox Artillery Perch precedent).
 * The wooden scar stays in the world after the perch ends,
 * and it is ordinary wood in every way: it burns, it breaks,
 * it can be mined. With mobGriefing OFF, no blocks are placed
 * and no teleport happens: the FOLLOW_RANGE stretch still
 * runs from the ground (the Monument Rising off-mode
 * precedent), and there are no bonus bolts, since there is
 * no elevation to bonus from.
 *
 * WHAT IT LOOKS LIKE: a WOOD_PLACE clatter and dark oak
 * BLOCK_CRUMBLE puffs as the little outpost tower snaps
 * together under the pillager, who blinks to its top and
 * starts cracking bolts down at twice their usual bite,
 * untouchable by anything that cannot climb, fly, or shoot
 * back.
 *
 * PERFORMANCE: all block work is one-shot in start() (at
 * most 21 cell reads and writes). tick() returns immediately
 * except every 10th tick (the drop check: one position read)
 * and every 30th tick (one range check and one bolt spawn).
 * Particle budget per activation: build puffs at most 21
 * times 2 = 42. The FOLLOW_RANGE modifier is built once in
 * start() and removed in end().
 *
 * State tag keys: "perched" (whether the teleport onto the
 * built platform happened), "plat_top" (the Y of the
 * platform's top surface, the pillager's feet level while
 * perched).
 */
public final class OutpostSniperPerchAbility implements MobAbility {

    static final int DURATION_TICKS = 300;
    private static final int BONUS_BOLT_INTERVAL = 30;
    private static final double BONUS_BOLT_DAMAGE = 4.0;
    private static final Identifier FOLLOW_RANGE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "outpost_sniper_perch_follow_range");
    private static final BlockParticleOption PLANK_CRUMBLE =
        new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, Blocks.DARK_OAK_PLANKS.defaultBlockState());

    @Override
    public String id() {
        return "outpost_sniper_perch";
    }

    @Override
    public String displayName() {
        return "Outpost Sniper Perch";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 400.0 && ctx.mob().onGround();
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        PillagerAbilitySupport.addModifier(caster, Attributes.FOLLOW_RANGE,
            new AttributeModifier(FOLLOW_RANGE_ID, 16.0, AttributeModifier.Operation.ADD_VALUE));

        boolean perched = false;
        ServerLevel level = ctx.serverLevel();
        if (level != null && ctx.mobGriefingAllowed()) {
            BlockPos base = caster.blockPosition();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            int placed = 0;
            // Legs: 4 corner columns, 3 levels from the feet.
            int[][] corners = {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}};
            for (int[] corner : corners) {
                for (int levelOffset = 0; levelOffset < 3; levelOffset++) {
                    pos.set(base.getX() + corner[0], base.getY() + levelOffset, base.getZ() + corner[1]);
                    if (PillagerAbilitySupport.canPlaceAbilityBlock(level, pos)
                        && level.setBlock(pos, Blocks.DARK_OAK_LOG.defaultBlockState(), Block.UPDATE_ALL)) {
                        placed++;
                    }
                }
            }
            // Top: the 3x3 plank platform at feet level + 3.
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    pos.set(base.getX() + dx, base.getY() + 3, base.getZ() + dz);
                    if (PillagerAbilitySupport.canPlaceAbilityBlock(level, pos)
                        && level.setBlock(pos, Blocks.DARK_OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL)) {
                        placed++;
                    }
                }
            }
            if (placed > 0) {
                level.playSound(null, base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5,
                    SoundEvents.WOOD_PLACE, SoundSource.HOSTILE, 1.2f, 0.8f);
                level.sendParticles(PLANK_CRUMBLE, base.getX() + 0.5, base.getY() + 1.5, base.getZ() + 0.5,
                    Math.min(placed * 2, 42), 1.2, 1.5, 1.2, 0.1);
            }
            // Perched when the platform's center cell is solid
            // now, whether the ability placed it or it was
            // already there.
            if (!level.getBlockState(base.above(3)).isAir()) {
                double topY = base.getY() + 4.0;
                caster.snapTo(base.getX() + 0.5, topY, base.getZ() + 0.5, caster.getYRot(), 0.0f);
                ctx.state().putDouble("plat_top", topY);
                perched = true;
            }
        }
        ctx.state().putBoolean("perched", perched);
    }

    @Override
    public void tick(AbilityContext ctx) {
        int tick = ctx.ticksActive();
        boolean checkDrop = tick % 10 == 0;
        boolean fireBonus = tick % BONUS_BOLT_INTERVAL == 0;
        if (!checkDrop && !fireBonus) {
            return; // Cheap early-out: work happens on the 10 and 30 tick marks only.
        }
        Mob caster = ctx.mob();
        boolean perched = ctx.state().getBooleanOr("perched", false);
        if (checkDrop && perched
            && caster.getY() < ctx.state().getDoubleOr("plat_top", caster.getY()) - 0.5) {
            ctx.state().putBoolean("dropped", true);
            return;
        }
        if (fireBonus && perched) {
            LivingEntity target = ctx.target();
            if (target != null && caster.distanceToSqr(target) <= 1024.0
                && PillagerAbilitySupport.fireBolt(ctx, target, BONUS_BOLT_DAMAGE, 3.2f, 0.5f)) {
                ServerLevel level = ctx.serverLevel();
                if (level != null) {
                    level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                        SoundEvents.CROSSBOW_SHOOT, SoundSource.HOSTILE, 1.0f, 1.1f);
                }
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.state().getBooleanOr("dropped", false)
            || ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // The survey ends with the perch. The tower itself is
        // permanent terrain and stays (see the class comment),
        // with the pillager still standing on it if he held
        // the top.
        PillagerAbilitySupport.removeModifier(ctx.mob(), Attributes.FOLLOW_RANGE, FOLLOW_RANGE_ID);
    }
}
