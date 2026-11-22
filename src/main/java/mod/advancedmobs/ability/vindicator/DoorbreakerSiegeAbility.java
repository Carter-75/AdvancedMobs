package mod.advancedmobs.ability.vindicator;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Doorbreaker Siege (Vindicator ability 3 of 5, terrain and
 * attack).
 *
 * WHAT IT DOES: locked doors and walls stop meaning
 * anything. The vindicator lowers its head, grips its axe
 * like a pick, and simply mines through whatever stands
 * between it and its target, debris and crumble dust flying,
 * carving a vindicator-shaped tunnel in a straight line. The
 * tunnel stays: a permanent breach the whole raid can pour
 * through. Wood, stone, it makes no difference to the axe.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the vindicator's current target within 24 blocks
 *   (distance squared 576), the vindicator on the ground, AND
 *   something actually in the way: either the vindicator has
 *   no line of sight to its target, or the target is more
 *   than 8 blocks away (distance squared over 64). In an open
 *   field at axe range the siege never starts; there is
 *   nothing to break.
 * - BLOCK_BREAK_SPEED (the design's Uses list): a transient
 *   +9.0 add_multiplied_base modifier (10x total) for the
 *   whole activation, applied in start() and removed in
 *   end(). Honest note: this attribute feeds vanilla's block
 *   breaking progress, which a mob only uses through its
 *   door-breaking goal; the tunnel itself is carved in code
 *   below, so the siege works whether or not the vindicator
 *   carries the attribute instance (the helper tolerates its
 *   absence, the Frenzy Gore precedent). The modifier is the
 *   design's dial, applied as specified; the code is the
 *   teeth.
 * - The carve, every 4 ticks: the vindicator's navigation is
 *   refreshed onto its live target (speed 1.1, on the 20-tick
 *   marks), and on every carve pulse the column directly in
 *   front of it is checked: the cell 1.0 block ahead of its
 *   center along the horizontal direction to its target (or
 *   its look direction when the target is gone), at feet
 *   level and head level (feet +1), plus its own cell at head
 *   level (the lintel it is walking into). Each cell that
 *   passes the shared isBreakable check (a real, dry block,
 *   no block entity, destroy speed not negative) is mined
 *   out: set to air with no drops, 8 BLOCK particles and 4
 *   BLOCK_CRUMBLE particles of the broken state at the cell
 *   center, and the block's own break sound at volume 0.8.
 *   Two cells high is a vindicator-shaped tunnel: 1.95 blocks
 *   of vindicator walks through it upright.
 * - Break cap: at most 48 blocks per activation. A siege
 *   that runs out of axe work just walks for the rest of its
 *   duration.
 * - The end: the activation finishes the moment the
 *   vindicator closes to within 3.0 blocks of its target
 *   (checked every 5 ticks: it has emerged, and vanilla melee
 *   takes over, already swinging), or at 400 ticks (20
 *   seconds) regardless.
 *
 * WHAT IT LOOKS LIKE: a vindicator shouldering straight
 * through a wall that was there a second ago, BLOCK debris
 * spraying off the axe side, dust shaking loose, the block's
 * own crack-and-break sounds in a steady rhythm, and a clean
 * two-high hole behind it that was not on the architect's
 * plans.
 *
 * TERRAIN (the standing terrain rule): the mined blocks are
 * the point of the ability, and they are PERMANENT: removed
 * for good, no restore machinery, and only removed when
 * AbilityContext.mobGriefingAllowed() is true, re-checked on
 * every carve pulse in case the rule is toggled mid-siege.
 * With mobGriefing off, no block is ever touched: the
 * vindicator keeps the (harmless) break-speed modifier,
 * paths at the wall with its vanilla AI, and the activation
 * ends on reach or timeout with the fortification intact.
 * Containers and other block entities are never mined, and
 * unbreakable blocks stop the tunnel cold, both by the
 * shared isBreakable check.
 *
 * PERFORMANCE: the carve pulse runs every 4 ticks and reads
 * at most 3 block positions; navigation refreshes every 20
 * ticks; the reach check is one distance read every 5 ticks.
 * No entity queries at all. Particle budget per activation:
 * 48 capped breaks times 12 = at most 576, stated here so a
 * future pass can trim the per-break count if it must.
 *
 * CLEANUP: end() removes the BLOCK_BREAK_SPEED modifier.
 * The tunnel, being terrain, is deliberately not touched.
 *
 * State tag keys: "breaks" (blocks mined this activation),
 * "reached" (1 once the vindicator has closed on its
 * target).
 */
public final class DoorbreakerSiegeAbility implements MobAbility {

    static final int MAX_TICKS = 400;
    private static final int CARVE_INTERVAL = 4;
    private static final int MAX_BREAKS = 48;
    private static final Identifier BREAK_SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "doorbreaker_siege_break_speed");

    @Override
    public String id() {
        return "doorbreaker_siege";
    }

    @Override
    public String displayName() {
        return "Doorbreaker Siege";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null || !ctx.mob().onGround()) {
            return false;
        }
        double distanceSqr = ctx.mob().distanceToSqr(target);
        if (distanceSqr > 576.0) {
            return false;
        }
        return distanceSqr > 64.0 || !ctx.mob().hasLineOfSight(target);
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().putInt("breaks", 0);
        ctx.state().putInt("reached", 0);
        VindicatorAbilitySupport.addModifier(ctx.mob(), Attributes.BLOCK_BREAK_SPEED,
            new AttributeModifier(BREAK_SPEED_ID, 9.0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        LivingEntity target = ctx.target();
        if (target != null) {
            ctx.mob().getNavigation().moveTo(target, 1.1);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob mob = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        LivingEntity target = ctx.target();

        if (ticks % 20 == 0 && target != null) {
            mob.getNavigation().moveTo(target, 1.1);
        }
        if (ticks % 5 == 0 && target != null && mob.distanceToSqr(target) <= 9.0) {
            ctx.state().putInt("reached", 1);
            return;
        }
        if (ticks % CARVE_INTERVAL != 0) {
            return; // Cheap early-out: carving runs every 4 ticks only.
        }
        if (!ctx.mobGriefingAllowed() || ctx.state().getIntOr("breaks", 0) >= MAX_BREAKS) {
            return; // Terrain gate (standing rule) and the per-activation cap.
        }

        // The direction of the siege: at the target, or
        // wherever the vindicator is looking when it is gone.
        double dirX;
        double dirZ;
        if (target != null) {
            dirX = target.getX() - mob.getX();
            dirZ = target.getZ() - mob.getZ();
        } else {
            dirX = mob.getLookAngle().x;
            dirZ = mob.getLookAngle().z;
        }
        double length = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (length < 1.0e-4) {
            return;
        }
        dirX /= length;
        dirZ /= length;
        int feetY = (int) Math.floor(mob.getY());
        int frontX = (int) Math.floor(mob.getX() + dirX * 1.0);
        int frontZ = (int) Math.floor(mob.getZ() + dirZ * 1.0);
        int ownX = (int) Math.floor(mob.getX());
        int ownZ = (int) Math.floor(mob.getZ());

        int breaks = ctx.state().getIntOr("breaks", 0);
        breaks += mineCell(level, new BlockPos(frontX, feetY, frontZ), breaks);
        breaks += mineCell(level, new BlockPos(frontX, feetY + 1, frontZ), breaks);
        breaks += mineCell(level, new BlockPos(ownX, feetY + 1, ownZ), breaks);
        ctx.state().putInt("breaks", breaks);
    }

    /**
     * Mines one cell if it passes the shared safety check:
     * sets it to air (no drops), sprays its debris, and plays
     * its own break sound. Returns 1 when a block was mined,
     * 0 otherwise. The caller has already checked the terrain
     * gate; breaksSoFar is the running count for this pulse,
     * so the per-activation cap is exact.
     */
    private int mineCell(ServerLevel level, BlockPos pos, int breaksSoFar) {
        if (breaksSoFar >= MAX_BREAKS) {
            return 0;
        }
        if (!VindicatorAbilitySupport.isBreakable(level, pos)) {
            return 0;
        }
        BlockState state = level.getBlockState(pos);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.5;
        double cz = pos.getZ() + 0.5;
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
            cx, cy, cz, 8, 0.3, 0.3, 0.3, 0.1);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, state),
            cx, cy, cz, 4, 0.3, 0.3, 0.3, 0.05);
        level.playSound(null, cx, cy, cz, state.getSoundType().getBreakSound(),
            SoundSource.HOSTILE, 0.8f, 1.0f);
        return 1;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.state().getIntOr("reached", 0) == 1 || ctx.ticksActive() >= MAX_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        VindicatorAbilitySupport.removeModifier(ctx.mob(), Attributes.BLOCK_BREAK_SPEED, BREAK_SPEED_ID);
    }
}
