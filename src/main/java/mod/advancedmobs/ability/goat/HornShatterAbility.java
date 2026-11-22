package mod.advancedmobs.ability.goat;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Horn Shatter (Goat ability 4 of 5, terrain).
 *
 * WHAT IT DOES: the goat lowers its head and rams the
 * terrain itself. Every 20 ticks it lunges horn first at
 * whatever stands in front of it, and stone, logs, and
 * walls crack apart in a spray of debris: real blocks
 * break. A few rams batter a hole clean through a
 * defensive wall, and enemies standing on the other side
 * of a shattered block are staggered by the flying debris.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the goat on the ground and its current target
 *   within 10 blocks (distance squared 100). The goat's
 *   own AI walks it to the fight (and into walls) between
 *   rams; each ram works the column directly ahead of
 *   wherever the goat is standing and facing.
 * - Duration: at most 120 ticks (6 seconds), with exactly
 *   6 ram pulses, at ticks 10, 30, 50, 70, 90, and 110.
 * - BLOCK_BREAK_SPEED (start(), removed in end()): a
 *   transient add_multiplied_base +0.5 modifier under id
 *   advancedmobs:horn_shatter_break_speed, the design's
 *   Uses line. VERIFIED LIMIT, documented honestly: in
 *   26.2 the goat has NO BLOCK_BREAK_SPEED attribute
 *   instance (checked against the jar: neither
 *   LivingEntity.createLivingAttributes nor the mob and
 *   animal builders add it; it is a player-side attribute),
 *   so the null-tolerant helper applies nothing and the
 *   modifier is inert on the goat. The battering rate is
 *   carried by the 20-tick ram interval in code, the same
 *   way Grand Hex Form documents its inert ATTACK_SPEED.
 * - Each ram pulse:
 *   - The goat lunges: if it is on the ground, its
 *     horizontal velocity is written at 0.35 blocks per
 *     tick along its look direction (vertical untouched),
 *     and GOAT_RAM_IMPACT plays at volume 1.2 (the
 *     screaming variant's impact sound for a screaming
 *     goat).
 *   - The worked column: the two cells 1.6 blocks ahead of
 *     the goat along its horizontal look direction, at its
 *     feet level and one above.
 *   - mobGriefing ON (the standing terrain rule): each
 *     cell that passes the shared safety check
 *     (GoatAbilitySupport.isDiggable: not air, no fluid,
 *     no block entity, destroy speed not negative) BREAKS:
 *     8 BLOCK and 6 BLOCK_CRUMBLE particles of its own
 *     state spray from it, then it is set to air with
 *     setBlock. setBlock produces no item drops, which is
 *     the design's "drops suppressed." The break is
 *     PERMANENT: the hole stays. Per-activation cap: 12
 *     blocks (the 2-cell column times 6 rams reaches it
 *     exactly, so the cap only bites if the column is ever
 *     widened).
 *   - The stagger: if at least one block broke on this
 *     ram, one bounded query of radius 2.5 around the
 *     column's center (1.5 below to 2.5 above): every
 *     enemy in it (GoatAbilitySupport rules) gains
 *     SLOWNESS II (amplifier 1) for 60 ticks, staggered by
 *     the debris coming through the wall.
 *   - mobGriefing OFF: no block ever breaks and nobody is
 *     staggered. The ram still lunges and sounds, and if
 *     the feet-level front cell holds a real block, 6
 *     BLOCK_CRUMBLE particles of its state spray off its
 *     face: the wall cracks, but holds.
 * - end(): removes the (inert, see above) BLOCK_BREAK_SPEED
 *   modifier. Broken blocks stay broken.
 *
 * WHAT IT LOOKS LIKE: a goat repeatedly throwing its head
 * into a wall with a crack of horns, chunks and crumble
 * fountaining from each impact point, the wall thinning
 * hit by hit until daylight comes through, and whatever
 * was hiding behind it stumbling as the debris hits.
 *
 * PERFORMANCE: tick() returns immediately except on ram
 * ticks. A ram is one look read, one velocity write, at
 * most 2 block reads plus 2 setBlock calls, and at most
 * one bounded radius-2.5 query (only when a block broke).
 * Particle budget per activation: at most 6 rams times 2
 * cells times 14 = 168 particles with mobGriefing on, or
 * 6 times 6 = 36 with it off.
 *
 * TERRAIN: the broken blocks are this package's only real
 * block changes: PERMANENT, gated on
 * AbilityContext.mobGriefingAllowed() re-checked at every
 * ram, through the shared safety check (no containers or
 * other block entities, no unbreakable blocks, no fluids).
 *
 * State tag keys: "breaks" (blocks broken this activation,
 * for the cap).
 */
public final class HornShatterAbility implements MobAbility {

    private static final int DURATION_TICKS = 120;
    private static final int FIRST_RAM_TICK = 10;
    private static final int RAM_INTERVAL = 20;
    private static final int MAX_BREAKS = 12;
    private static final double REACH = 1.6;
    private static final double STAGGER_RADIUS = 2.5;
    private static final Identifier BREAK_SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "horn_shatter_break_speed");

    @Override
    public String id() {
        return "horn_shatter";
    }

    @Override
    public String displayName() {
        return "Horn Shatter";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().onGround()
            && ctx.mob().distanceToSqr(target) <= 100.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().putInt("breaks", 0);
        // The design's BLOCK_BREAK_SPEED line: inert on the
        // goat in 26.2 (see the class comment), applied and
        // removed for contract completeness.
        GoatAbilitySupport.addModifier(ctx.mob(), Attributes.BLOCK_BREAK_SPEED,
            new AttributeModifier(BREAK_SPEED_ID, 0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks < FIRST_RAM_TICK || (ticks - FIRST_RAM_TICK) % RAM_INTERVAL != 0) {
            return; // Cheap early-out: work happens on ram ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            ram(ctx, level);
        }
    }

    /** One ram: the lunge, the sound, and the worked column ahead. */
    private void ram(AbilityContext ctx, ServerLevel level) {
        Mob caster = ctx.mob();
        Vec3 look = caster.getLookAngle();
        double dx = look.x;
        double dz = look.z;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.001) {
            dx = 0.0;
            dz = 1.0;
            length = 1.0;
        }
        dx /= length;
        dz /= length;

        // The lunge: a short horn-first throw at the wall.
        if (caster.onGround()) {
            Vec3 velocity = caster.getDeltaMovement();
            caster.setDeltaMovement(dx * 0.35, velocity.y, dz * 0.35);
            caster.hurtMarked = true;
        }
        boolean screaming = caster instanceof Goat goat && goat.isScreamingGoat();
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            screaming ? SoundEvents.GOAT_SCREAMING_RAM_IMPACT : SoundEvents.GOAT_RAM_IMPACT,
            SoundSource.HOSTILE, 1.2f, 1.0f);

        double frontX = caster.getX() + dx * REACH;
        double frontZ = caster.getZ() + dz * REACH;
        int feetY = caster.blockPosition().getY();
        BlockPos lower = BlockPos.containing(frontX, feetY, frontZ);
        BlockPos upper = lower.above();

        if (!ctx.mobGriefingAllowed()) {
            // The wall cracks but holds: crumble off its face only.
            BlockState face = level.getBlockState(lower);
            if (!face.isAir()) {
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, face),
                    frontX, feetY + 0.8, frontZ, 6, 0.3, 0.5, 0.3, 0.1);
            }
            return;
        }

        int breaks = ctx.state().getIntOr("breaks", 0);
        int breaksBefore = breaks;
        breaks += breakCell(level, lower, breaks);
        breaks += breakCell(level, upper, breaks);
        ctx.state().putInt("breaks", breaks);
        if (breaks > breaksBefore) {
            // Debris through the hole staggers whatever hid behind it.
            List<LivingEntity> staggered = GoatAbilitySupport.enemiesInBox(
                ctx, frontX, feetY + 1.0, frontZ, STAGGER_RADIUS, 1.5, 2.5);
            for (LivingEntity enemy : staggered) {
                enemy.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 1));
            }
        }
    }

    /**
     * Breaks one cell if it passes the shared safety check:
     * sprays its debris, then sets it to air (no drops).
     * Returns 1 when a block broke, 0 otherwise. The caller
     * has already checked the terrain gate; breaksSoFar is
     * the running count so the per-activation cap is exact.
     */
    private int breakCell(ServerLevel level, BlockPos pos, int breaksSoFar) {
        if (breaksSoFar >= MAX_BREAKS) {
            return 0;
        }
        if (!GoatAbilitySupport.isDiggable(level, pos)) {
            return 0;
        }
        BlockState state = level.getBlockState(pos);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
            8, 0.3, 0.3, 0.3, 0.15);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, state),
            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
            6, 0.3, 0.3, 0.3, 0.1);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        return 1;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        GoatAbilitySupport.removeModifier(ctx.mob(), Attributes.BLOCK_BREAK_SPEED, BREAK_SPEED_ID);
    }
}
