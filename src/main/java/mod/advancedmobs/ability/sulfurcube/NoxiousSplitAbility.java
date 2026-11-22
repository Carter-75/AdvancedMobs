package mod.advancedmobs.ability.sulfurcube;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;

/**
 * Noxious Split (Sulfur Cube ability 5 of 5, summon and
 * defense).
 *
 * WHAT IT DOES: when badly hurt, the cube bursts in a cloud
 * of noxious gas and two smaller sulfur cubes shear off its
 * body, bouncing away in opposite directions to regroup. The
 * greenish NOXIOUS_GAS_CLOUD hangs over the split site for 10
 * seconds, and every enemy standing in it is nauseated.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the caster is a sulfur cube whose
 *   current health is at or below 50 percent of its maximum
 *   (a real beating has landed) and it has a live target.
 * - The split (start(), one-shot): the caster pays for the
 *   sheared-off mass with 20 percent of its MAXIMUM health,
 *   applied as a direct health reduction (no damage event, so
 *   it cannot loop back into the war system's kill credit),
 *   never taking it below 1 health: the Gelatinous Split
 *   pattern. Then two allied sulfur cubes spawn at 1.0 block
 *   to either side of the caster (SulfurCubeAbilitySupport's
 *   summon path: MOB_SUMMONED, size forced after
 *   finalizeSpawn, allied in both directions, SummonerTracker
 *   registered, ordered onto the caster's target), each one
 *   size down from the caster (minimum size 1; sulfur cubes
 *   only come in sizes 1 and 2) and each carrying the
 *   design's half scale as a permanent SCALE attribute base
 *   value of 0.5: they are real mobs, not props, so the half
 *   scale is a base value on the new mob rather than a
 *   transient modifier to clean up.
 * - The escape bounce: each mini is launched away sideways,
 *   along the two directions perpendicular to the
 *   caster-to-target axis (left for one, right for the other),
 *   at 0.7 horizontal and 0.55 up, hurtMarked: the design's
 *   "bounce away in opposite directions, buying time to
 *   regroup". Their own AI takes over from the landing; they
 *   are ordered onto the caster's target at spawn, so the
 *   regroup turns back into the fight.
 * - The cloud: the split point is recorded in the state tag.
 *   For the ability's 200 ticks, a pulse every 10 ticks sheds
 *   3 NOXIOUS_GAS_CLOUD and 2 NOXIOUS_GAS at the center and
 *   applies NAUSEA at amplifier 0 for 80 ticks to every enemy
 *   within radius 3.5 of the center (one bounded query per
 *   pulse). The gas does no damage; nausea is the whole point,
 *   exactly what potent sulfur gas does to things standing in
 *   it. The minis are allies, so the enemy rule never
 *   nauseates them.
 * - The minis are NOT removed at end(): they are real mobs
 *   sheared off the caster's body, permanent members of its
 *   team, exactly like the split slimes of Gelatinous Split.
 * - Duration: exactly 200 ticks (10 seconds), which is the
 *   cloud's lifetime; the split itself is instant in start().
 * - No blocks are placed or broken anywhere in this ability
 *   (the cloud is particles and an effect zone, not blocks),
 *   so the mobGriefing terrain gate does not apply.
 *
 * WHAT IT LOOKS LIKE: a wet SULFUR_CUBE_EJECT pop, a burst of
 * 14 NOXIOUS_GAS_CLOUD and 8 NOXIOUS_GAS boiling out of the
 * caster, two half-size cubes bouncing away left and right,
 * and then the green cloud hanging and churning over the spot
 * for 10 seconds, enemies inside it swaying with nausea.
 *
 * PERFORMANCE: start() does the whole split (two spawns, one
 * burst); tick() is a single 10-tick pulse with one bounded
 * query. Particle budget per activation, hard caps by
 * construction: 22 at the split plus 20 pulses times 5 = 100,
 * total at most 122 particles.
 *
 * CLEANUP: end() has nothing to undo: the minis stay (see
 * MECHANICS), and the nausea already applied expires on its
 * own clock.
 *
 * State tag keys: "cx", "cy", "cz" (the split point, the
 * cloud's center).
 */
public final class NoxiousSplitAbility implements MobAbility {

    static final int DURATION_TICKS = 200;
    private static final double SPLIT_HEALTH_FRACTION = 0.5;
    private static final float MASS_COST_FRACTION = 0.2f;
    private static final double CLOUD_RADIUS = 3.5;

    @Override
    public String id() {
        return "noxious_split";
    }

    @Override
    public String displayName() {
        return "Noxious Split";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        if (!(ctx.mob() instanceof SulfurCube)) {
            return false;
        }
        return ctx.target() != null
            && ctx.mob().getHealth() <= ctx.mob().getMaxHealth() * SPLIT_HEALTH_FRACTION;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null || !(mob instanceof SulfurCube caster)) {
            return;
        }
        // The mass cost: 20 percent of maximum health, never
        // lethal (the caster always keeps at least 1 health).
        float cost = mob.getMaxHealth() * MASS_COST_FRACTION;
        float payable = Math.min(cost, mob.getHealth() - 1.0f);
        if (payable > 0.0f) {
            mob.setHealth(mob.getHealth() - payable);
        }

        // The split point is the cloud's center.
        ctx.state().putDouble("cx", mob.getX());
        ctx.state().putDouble("cy", mob.getY());
        ctx.state().putDouble("cz", mob.getZ());

        // The escape directions: perpendicular to the axis
        // toward the target, one mini each way.
        LivingEntity target = ctx.target();
        double axisX = target != null ? target.getX() - mob.getX() : mob.getLookAngle().x;
        double axisZ = target != null ? target.getZ() - mob.getZ() : mob.getLookAngle().z;
        double length = Math.sqrt(axisX * axisX + axisZ * axisZ);
        if (length < 0.001) {
            axisX = 1.0;
            axisZ = 0.0;
            length = 1.0;
        }
        double perpX = -axisZ / length;
        double perpZ = axisX / length;

        int childSize = Math.max(SulfurCube.MIN_SIZE, caster.getSize() - 1);
        SulfurCube left = SulfurCubeAbilitySupport.spawnAlliedSulfurCube(ctx, level,
            mob.getX() + perpX, mob.getZ() + perpZ, childSize, true, null);
        SulfurCube right = SulfurCubeAbilitySupport.spawnAlliedSulfurCube(ctx, level,
            mob.getX() - perpX, mob.getZ() - perpZ, childSize, true, null);
        if (left != null) {
            left.setDeltaMovement(perpX * 0.7, 0.55, perpZ * 0.7);
            left.hurtMarked = true;
        }
        if (right != null) {
            right.setDeltaMovement(-perpX * 0.7, 0.55, -perpZ * 0.7);
            right.hurtMarked = true;
        }

        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.SULFUR_CUBE_EJECT,
            SoundSource.HOSTILE, 1.0f, 1.0f);
        level.sendParticles(ParticleTypes.NOXIOUS_GAS_CLOUD, mob.getX(), mob.getY() + 0.5, mob.getZ(),
            14, 1.2, 0.6, 1.2, 0.08);
        level.sendParticles(ParticleTypes.NOXIOUS_GAS, mob.getX(), mob.getY() + 0.5, mob.getZ(),
            8, 1.0, 0.6, 1.0, 0.1);
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % 10 != 0) {
            return;
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        double cx = ctx.state().getDoubleOr("cx", ctx.mob().getX());
        double cy = ctx.state().getDoubleOr("cy", ctx.mob().getY());
        double cz = ctx.state().getDoubleOr("cz", ctx.mob().getZ());
        level.sendParticles(ParticleTypes.NOXIOUS_GAS_CLOUD, cx, cy + 0.5, cz,
            3, 2.5, 0.6, 2.5, 0.03);
        level.sendParticles(ParticleTypes.NOXIOUS_GAS, cx, cy + 0.5, cz,
            2, 2.2, 0.5, 2.2, 0.03);
        for (LivingEntity enemy : SulfurCubeAbilitySupport.enemiesInBox(ctx, cx, cy, cz,
                CLOUD_RADIUS, 2.0, 3.0)) {
            enemy.addEffect(new MobEffectInstance(MobEffects.NAUSEA, 80, 0));
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to undo: the split minis are permanent team
        // members, and applied nausea expires on its own.
    }
}
