package mod.advancedmobs.ability.armadillo;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Bug Buffet (Armadillo ability 4 of 5, support).
 *
 * WHAT IT DOES: mid fight, the armadillo drops its nose to
 * the dirt and snuffles up grubs. Every few seconds it
 * finds a snack: a little burst of composter flecks and
 * hearts, a crunch, and a bite of health back. The protein
 * adds up: once it has eaten enough, the armadillo fights
 * stronger for the rest of the buffet.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   its current target within 12 blocks (distance squared
 *   144), plus the block-based ground check from the
 *   design's Uses line, in code: the block directly under
 *   the armadillo must be grub ground, a block in the
 *   vanilla DIRT tag (dirt, grass block, coarse dirt,
 *   podzol, rooted dirt, mycelium, mud) or the vanilla SAND
 *   tag, or gravel. On stone, wood, or any other floor the
 *   buffet is never offered.
 * - The feeding (duration 240 ticks, 12 seconds): the
 *   armadillo is rooted by navigation stops every tick
 *   (never NoAi), nose down. A sniff of 2 COMPOSTER
 *   particles at the nose every 10 ticks.
 * - The snack (every 30 ticks, at ticks 30, 60, 90, 120,
 *   150, 180, 210, 240: 8 snacks at most): the snack count
 *   is kept in the state tag ("snacks"). Each snack:
 *   - heals 2.0 health directly with heal() (the design's
 *     instant_health line carried as a direct heal, so the
 *     number is exact and never overheals past max),
 *   - applies SATURATION at amplifier 0 for 100 ticks,
 *     refreshed per snack (the design's stacking
 *     saturation: the refresh is the stack, the effect
 *     never lapses while the buffet runs),
 *   - bursts 6 COMPOSTER and 3 HEART particles at the head
 *     (the design's two particle lines) and plays
 *     ARMADILLO_EAT at volume 1.0, pitch rising 0.05 per
 *     snack from 0.9 (a happier crunch each bite).
 * - The protein (the design's strength line): from the 3rd
 *   snack onward, each snack also applies STRENGTH at
 *   amplifier 0 for 140 ticks, refreshed per snack, so a
 *   well fed armadillo (3 or more snacks) carries the bonus
 *   through the rest of the buffet and a short tail after.
 * - end(): SATURATION and STRENGTH are removed (both are
 *   this ability's own applications; the durations were
 *   sized to the buffet, so a natural finish strips the
 *   short remainder rather than leaving a free tail).
 *
 * WHAT IT LOOKS LIKE: an armadillo with its nose in the
 * dirt in the middle of a fight, composter flecks puffing
 * up around its snout, little hearts drifting up every few
 * seconds as it crunches another grub, visibly steadier
 * and meaner once it has eaten its fill.
 *
 * PERFORMANCE: one navigation stop per tick, one block read
 * per canTrigger call and none per tick beyond it, one
 * particle emission every 10 ticks, and the snack work once
 * per 30 ticks. No entity queries anywhere. Particle budget
 * per activation: sniffs 24 times 2 = 48, snacks 8 times
 * 9 = 72, total at most 120.
 *
 * TERRAIN: this ability changes no blocks; it only reads
 * the one block under the caster for the trigger. The gate
 * does not apply.
 *
 * State tag keys: "snacks" (snacks eaten this activation).
 */
public final class BugBuffetAbility implements MobAbility {

    private static final int DURATION_TICKS = 240;
    private static final int SNACK_INTERVAL_TICKS = 30;
    private static final float SNACK_HEAL = 2.0f;
    private static final int PROTEIN_SNACKS = 3;

    @Override
    public String id() {
        return "bug_buffet";
    }

    @Override
    public String displayName() {
        return "Bug Buffet";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null || ctx.mob().distanceToSqr(target) > 144.0) {
            return false;
        }
        // The ground check: grubs only live in soft ground.
        Mob caster = ctx.mob();
        BlockState ground = ctx.level().getBlockState(
            BlockPos.containing(caster.getX(), caster.getY() - 0.5, caster.getZ()));
        return ground.is(BlockTags.DIRT) || ground.is(BlockTags.SAND) || ground.is(Blocks.GRAVEL);
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().putInt("snacks", 0);
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.getNavigation().stop();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        double noseX = caster.getX() + caster.getLookAngle().x * 0.6;
        double noseZ = caster.getZ() + caster.getLookAngle().z * 0.6;

        if (ticks % 10 == 0) {
            level.sendParticles(ParticleTypes.COMPOSTER,
                noseX, caster.getY() + 0.25, noseZ, 2, 0.2, 0.1, 0.2, 0.03);
        }
        if (ticks % SNACK_INTERVAL_TICKS == 0) {
            snack(ctx, level, noseX, noseZ);
        }
    }

    /** One snack: heal, saturation, the protein once fed, flecks and hearts. */
    private void snack(AbilityContext ctx, ServerLevel level, double noseX, double noseZ) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        int snacks = state.getIntOr("snacks", 0) + 1;
        state.putInt("snacks", snacks);

        caster.heal(SNACK_HEAL);
        caster.addEffect(new MobEffectInstance(MobEffects.SATURATION, 100, 0));
        if (snacks >= PROTEIN_SNACKS) {
            caster.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 140, 0));
        }
        level.sendParticles(ParticleTypes.COMPOSTER,
            noseX, caster.getY() + 0.3, noseZ, 6, 0.3, 0.2, 0.3, 0.05);
        level.sendParticles(ParticleTypes.HEART,
            caster.getX(), caster.getY() + 0.7, caster.getZ(), 3, 0.3, 0.25, 0.3, 0.02);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.ARMADILLO_EAT, SoundSource.HOSTILE, 1.0f, 0.9f + 0.05f * snacks);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.removeEffect(MobEffects.SATURATION);
        caster.removeEffect(MobEffects.STRENGTH);
    }
}
