package mod.advancedmobs.ability.shulker;

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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Endstone Bunker (Shulker ability 2 of 5, defense, terrain).
 *
 * WHAT IT DOES: end stone and purpur rise around the shulker
 * in a closed 3x3 bunker with no door, and inside it the
 * shulker sits at heavy RESISTANCE, peeking out only to fire
 * a single homing bullet at a time through a gap on the
 * target's side. Enemies must mine through or wait it out,
 * and mining toward a shulker is exactly as dangerous as it
 * sounds.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the shulker's current target within 16 blocks (distance
 *   squared 256).
 * - Duration: exactly 400 ticks (20 seconds) from start().
 * - The bunker (start(), only when mobGriefing is on): the
 *   shell is built around the shulker's block position: the
 *   8 ring cells at the shulker's own level and the level
 *   above, plus all 9 roof cells two levels above. Materials
 *   alternate in a checker: (dx + dz + level) even is END
 *   STONE, odd is PURPUR BLOCK. Each cell is placed only
 *   through the shared safety check (air or replaceable, dry,
 *   no block entity, not unbreakable), so the bunker never
 *   overwrites anything and simply has gaps where the world
 *   refuses. At most 25 blocks are placed; the placed count
 *   is recorded in the state tag.
 * - RESISTANCE: amplifier 1 (40 percent damage reduction)
 *   for 420 ticks, applied in start() and removed in end()
 *   (the bunker's own instance; a shulker that somehow had
 *   RESISTANCE from another source would lose it at end(),
 *   an accepted edge case shared with every fixed-duration
 *   effect in this system).
 * - Peek shots: every 40 ticks (ticks 40, 80, ... 400), if
 *   the target is alive and within 24 blocks, one real
 *   ShulkerBullet is fired at it. A bullet spawned inside a
 *   sealed box would die on the wall, so the shot spawns
 *   OUTSIDE the bunker: at the shulker's position plus 2.2
 *   blocks toward the target horizontally, at shulker height
 *   + 0.5: the gap it "opens" for the shot. The gap reads as
 *   6 BLOCK_CRUMBLE motes of end stone at the wall cell on
 *   that side plus the SHULKER_SHOOT sound.
 * - The shulker itself never moves: shulkers hold position
 *   by nature, and no navigation or NoAi is touched.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026): the design text says
 * the bunker "crumbles to rubble when the shulker teleports
 * away or the timer runs out". The standing terrain rule
 * supersedes that line: ability terrain is PERMANENT and
 * there is no restore machinery (the Gravemud Mire, Magma
 * Ring Rising, and Graveyard Bloom precedent). The shell
 * stays as a permanent scar; the shulker's own vanilla
 * teleport (it relocates when its support is mined or it is
 * hurt in its closed shell) is how it leaves, and players
 * mine in. With mobGriefing OFF, no blocks are placed at
 * all: the shulker still gains its RESISTANCE and still
 * fires its peek shots from its own position (the Monument
 * Rising off-mode precedent: the non-terrain half runs in
 * full).
 *
 * WHAT IT LOOKS LIKE: at start, 2 BLOCK_CRUMBLE motes puff
 * off each placed block as the shell rises (end stone
 * crumbles on end stone cells, purpur on purpur cells).
 * While it lasts, the bunker is a squat checker-patterned
 * box of pale end stone and purple purpur with a shulker
 * muttering inside; every 40 ticks one wall cell sheds
 * crumbles and a bullet slips out.
 *
 * PERFORMANCE: all block work is one-shot in start() (at
 * most 25 cell reads and writes). tick() returns immediately
 * except every 40th tick, where it is one range check, one
 * bullet spawn, and one particle emission. Particle budget
 * per activation: build puffs at most 25 x 2 = 50, peek gaps
 * 10 x 6 = 60, total at most 110 sent particles.
 *
 * State tag keys: "cx", "cy", "cz" (the bunker's center
 * block), "placed" (how many shell blocks were placed, 0
 * when mobGriefing was off).
 */
public final class EndstoneBunkerAbility implements MobAbility {

    static final int DURATION_TICKS = 400;
    private static final int PEEK_INTERVAL = 40;
    private static final BlockParticleOption END_STONE_CRUMBLE =
        new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, Blocks.END_STONE.defaultBlockState());
    private static final BlockParticleOption PURPUR_CRUMBLE =
        new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, Blocks.PURPUR_BLOCK.defaultBlockState());

    @Override
    public String id() {
        return "endstone_bunker";
    }

    @Override
    public String displayName() {
        return "Endstone Bunker";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        BlockPos center = caster.blockPosition();
        ctx.state().putInt("cx", center.getX());
        ctx.state().putInt("cy", center.getY());
        ctx.state().putInt("cz", center.getZ());

        caster.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, DURATION_TICKS + 20, 1));

        int placed = 0;
        ServerLevel level = ctx.serverLevel();
        if (level != null && ctx.mobGriefingAllowed()) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int dy = 0; dy <= 2; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dy < 2 && dx == 0 && dz == 0) {
                            continue; // The shulker's own column stays open.
                        }
                        pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                        if (!ShulkerAbilitySupport.canPlaceAbilityBlock(level, pos)) {
                            continue;
                        }
                        boolean endStone = ((dx + dz + dy) & 1) == 0;
                        BlockState state = endStone
                            ? Blocks.END_STONE.defaultBlockState()
                            : Blocks.PURPUR_BLOCK.defaultBlockState();
                        if (level.setBlock(pos, state, Block.UPDATE_ALL)) {
                            placed++;
                            level.sendParticles(endStone ? END_STONE_CRUMBLE : PURPUR_CRUMBLE,
                                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                                2, 0.3, 0.3, 0.3, 0.05);
                        }
                    }
                }
            }
        }
        ctx.state().putInt("placed", placed);
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % PEEK_INTERVAL != 0) {
            return; // Cheap early-out: peek shots every 40 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        LivingEntity target = ctx.target();
        if (target == null || ctx.mob().distanceToSqr(target) > 576.0) {
            return;
        }
        Mob caster = ctx.mob();

        // The gap: the wall cell on the target's side sheds
        // crumbles, and the bullet spawns just outside it.
        double dirX = target.getX() - caster.getX();
        double dirZ = target.getZ() - caster.getZ();
        double length = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (length < 0.001) {
            dirX = 1.0;
            dirZ = 0.0;
            length = 1.0;
        }
        double outsideX = caster.getX() + dirX / length * 2.2;
        double outsideZ = caster.getZ() + dirZ / length * 2.2;
        level.sendParticles(END_STONE_CRUMBLE,
            caster.getX() + dirX / length * 1.5, caster.getY() + 0.8, caster.getZ() + dirZ / length * 1.5,
            6, 0.25, 0.35, 0.25, 0.05);
        if (ShulkerAbilitySupport.fireBullet(ctx, target, outsideX, caster.getY() + 0.5, outsideZ)) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.SHULKER_SHOOT, SoundSource.HOSTILE, 1.5f, 1.0f);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // The shell is permanent terrain and stays (see the
        // TERRAIN RULE note in the class comment). Only the
        // bunker's RESISTANCE is the ability's to remove.
        ctx.mob().removeEffect(MobEffects.RESISTANCE);
    }
}
