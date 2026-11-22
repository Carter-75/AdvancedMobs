package mod.advancedmobs.ability.hoglin;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Stampede Trench (Hoglin ability 1 of 5, mobility and attack).
 *
 * WHAT IT DOES: the hoglin lowers its head and charges in one
 * straight thundering line, dirt exploding from its hooves. The
 * charge plows a visible trench of coarse dirt into the ground
 * behind it (permanent terrain, gated below). Anything caught in
 * the charge takes a heavy hit and is hurled down the charge
 * line. After the charge the trench stays as rough ground: while
 * the ability lingers, enemies crossing the trench line on its
 * coarse dirt are slowed, and afterwards the trench is simply
 * terrain.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   hoglin's current target within 14 blocks (distance squared
 *   196) and the hoglin on the ground. The charge aims at the
 *   target's position at start(); after that the line is fixed,
 *   so a target that sidesteps dodges the whole charge.
 * - Charge phase (phase 0), at most 50 ticks: the direction is
 *   recorded in start() (toward the target, or the hoglin's look
 *   direction if the target is somehow dead center). Each tick
 *   the navigation is stopped and the hoglin's horizontal
 *   velocity is set to the recorded direction times the current
 *   stride speed (vertical velocity is left alone, so gravity
 *   and steps behave normally). The stride ramps the way the
 *   design describes (a few strides to full speed): ticks 1 to
 *   10 at 0.35 blocks per tick, ticks 11 to 20 at 0.55, ticks
 *   21 on at 0.80. Tight arenas blunt the charge by construction:
 *   the phase ends early when the hoglin hits a wall
 *   (horizontalCollision) or has travelled 26 blocks from the
 *   start point, and the ramp means a charge stopped in the
 *   first strides never reached full speed or full damage
 *   distance. On the tick the charge ends, the end point is
 *   recorded and the phase flips to the linger.
 * - Charge hits: every charge tick, enemies (HoglinAbilitySupport
 *   rules) within radius 1.7 of the hoglin (1.0 below to 2.0
 *   above its feet) that this charge has not already hit take
 *   8.0 points of typed mob-attack damage credited to the
 *   hoglin and are hurled along the charge direction: delta
 *   movement set to 1.8 horizontal along the line and 0.55 up,
 *   hurtMarked set so the knockback syncs. Each enemy is hit at
 *   most once per charge (tracked in the state tag's "hit"
 *   compound), so the charge plows through a line instead of
 *   machine-gunning the first victim. The hoglin also carries
 *   an ATTACK_KNOCKBACK add_value modifier of +1.5 for the
 *   whole activation (id advancedmobs:stampede_trench_knockback,
 *   removed in end()), so any ordinary melee gore it lands
 *   mid-charge tosses as well.
 * - The trench (terrain, gated below): on each charge tick,
 *   when the block column under the hoglin changes, the floor
 *   block (one below its feet) is converted to COARSE_DIRT when
 *   it passes the shared isDiggable check (never air, fluids,
 *   block entities, or unbreakable blocks) and is not already
 *   coarse dirt. At most 48 conversions per charge.
 * - Linger phase (phase 1), until tick 240: every 10 ticks a
 *   trench pulse runs one bounded query over the box spanning
 *   the charge line (start point to end point, inflated by 2.0
 *   horizontally, 2.5 below to 2.0 above the start height).
 *   Enemies inside whose horizontal distance to the charge line
 *   segment is at most 1.8 blocks and who are standing on coarse
 *   dirt (the block below their feet is COARSE_DIRT) gain
 *   SLOWNESS amplifier 0 for 15 ticks: the rough ground drags
 *   at them while they cross. After the ability ends the pulse
 *   stops; the trench itself remains as plain terrain (the
 *   Quicksand Pit precedent: the debuff zone is the ability's,
 *   the dug ground is the world's).
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6 and the MobAbility contract):
 * the trench's coarse dirt is PERMANENT and gated by
 * AbilityContext.mobGriefingAllowed(), captured once in
 * start():
 * - mobGriefing ON: the trench is plowed as above and STAYS.
 *   end() touches no blocks.
 * - mobGriefing OFF: no block is converted. The charge, its
 *   hits and knockback, and the hoof particles all run exactly
 *   the same; the linger pulse then finds no coarse dirt of
 *   this charge to slow on (natural coarse dirt on the line
 *   still counts, it is rough ground either way) and the world
 *   is left exactly as it was.
 *
 * WHAT IT LOOKS LIKE: an angry HOGLIN_ANGRY bellow at the
 * start, then every charge tick 3 DUST_PILLAR and 3 BLOCK
 * particles of dirt bursting at the hoglin's hooves, and a
 * visible line of coarse dirt torn into the ground behind it.
 * Victims show the vanilla SLOWNESS sparkles while crossing
 * the trench during the linger.
 *
 * PERFORMANCE: the charge phase per tick does one navigation
 * stop, one velocity write, one bounded entity query (3.4 by
 * 3 by 3.4 box), one block read (plus a conversion on column
 * changes only), and 6 particle sends. The linger does nothing
 * except every 10th tick (one bounded query over the trench
 * box plus per-victim block reads for the handful inside).
 * Particle budget per activation: at most 50 charge ticks
 * times 6 = 300 particles.
 *
 * CLEANUP: end() removes the ATTACK_KNOCKBACK modifier. The
 * trench blocks, when plowed, are permanent terrain and are
 * deliberately NOT touched.
 *
 * State tag keys: "phase" (0 charging, 1 linger), "dir_x" and
 * "dir_z" (the charge direction, unit length), "sx", "sy",
 * "sz" (charge start point), "ex" and "ez" (charge end point,
 * written when the charge stops), "hit" (compound: victim
 * UUID string to 1, everyone this charge has already hit),
 * "tb_x" and "tb_z" (the last trench column converted),
 * "tcount" (trench conversions so far).
 */
public final class StampedeTrenchAbility implements MobAbility {

    static final int DURATION_TICKS = 240;
    private static final int CHARGE_MAX_TICKS = 50;
    private static final double CHARGE_MAX_DISTANCE_SQR = 26.0 * 26.0;
    private static final int TRENCH_MAX_CONVERSIONS = 48;
    private static final float CHARGE_DAMAGE = 8.0f;
    private static final double HIT_KNOCKBACK_HORIZONTAL = 1.8;
    private static final double HIT_KNOCKBACK_VERTICAL = 0.55;
    private static final int PULSE_INTERVAL = 10;
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "stampede_trench_knockback");
    private static final BlockParticleOption DIRT_PILLAR =
        new BlockParticleOption(ParticleTypes.DUST_PILLAR, Blocks.DIRT.defaultBlockState());
    private static final BlockParticleOption DIRT_DEBRIS =
        new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DIRT.defaultBlockState());

    @Override
    public String id() {
        return "stampede_trench";
    }

    @Override
    public String displayName() {
        return "Stampede Trench";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().onGround()
            && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();

        // The charge line is fixed here: toward the target's
        // position right now, or along the look direction when
        // the target is dead center on top of the hoglin.
        double dx = 0.0;
        double dz = 0.0;
        LivingEntity target = ctx.target();
        if (target != null) {
            dx = target.getX() - mob.getX();
            dz = target.getZ() - mob.getZ();
        }
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0e-4) {
            Vec3 look = mob.getLookAngle();
            dx = look.x;
            dz = look.z;
            length = Math.sqrt(dx * dx + dz * dz);
            if (length < 1.0e-4) {
                dx = 1.0;
                length = 1.0;
            }
        }
        state.putInt("phase", 0);
        state.putDouble("dir_x", dx / length);
        state.putDouble("dir_z", dz / length);
        state.putDouble("sx", mob.getX());
        state.putDouble("sy", mob.getY());
        state.putDouble("sz", mob.getZ());
        state.putDouble("ex", mob.getX());
        state.putDouble("ez", mob.getZ());
        state.put("hit", new CompoundTag());
        state.putInt("tb_x", Integer.MIN_VALUE);
        state.putInt("tb_z", Integer.MIN_VALUE);
        state.putInt("tcount", 0);

        // Gore insurance: the hoglin's own melee tosses during
        // the whole activation (removed in end()).
        HoglinAbilitySupport.addModifier(mob, Attributes.ATTACK_KNOCKBACK,
            new AttributeModifier(KNOCKBACK_ID, 1.5, AttributeModifier.Operation.ADD_VALUE));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.HOGLIN_ANGRY,
                SoundSource.HOSTILE, 1.0f, 0.9f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.state().getIntOr("phase", 0) == 0) {
            tickCharge(ctx);
        } else if (ctx.ticksActive() % PULSE_INTERVAL == 0) {
            pulseTrench(ctx);
        }
    }

    /** One charge tick: stride, hit checks, trench, hoof debris. */
    private void tickCharge(AbilityContext ctx) {
        Mob mob = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        CompoundTag state = ctx.state();
        int ticks = ctx.ticksActive();
        double dirX = state.getDoubleOr("dir_x", 1.0);
        double dirZ = state.getDoubleOr("dir_z", 0.0);

        // Stride: hold the recorded line at the ramped speed.
        double speed = ticks <= 10 ? 0.35 : (ticks <= 20 ? 0.55 : 0.80);
        mob.getNavigation().stop();
        Vec3 current = mob.getDeltaMovement();
        mob.setDeltaMovement(dirX * speed, current.y, dirZ * speed);
        mob.hurtMarked = true;

        if (level != null) {
            // Hit checks: everyone in the plow box not yet hit by
            // this charge takes the heavy hit and is hurled along
            // the line.
            CompoundTag hit = state.getCompoundOrEmpty("hit");
            List<LivingEntity> victims = HoglinAbilitySupport.enemiesAroundSelf(ctx, 1.7, 1.0, 2.0);
            for (LivingEntity victim : victims) {
                String key = victim.getUUID().toString();
                if (hit.contains(key)) {
                    continue;
                }
                hit.putInt(key, 1);
                victim.hurtServer(level, level.damageSources().mobAttack(mob), CHARGE_DAMAGE);
                victim.setDeltaMovement(dirX * HIT_KNOCKBACK_HORIZONTAL, HIT_KNOCKBACK_VERTICAL,
                    dirZ * HIT_KNOCKBACK_HORIZONTAL);
                victim.hurtMarked = true;
            }
            state.put("hit", hit);

            // Trench: one conversion per new column under the
            // hooves, permanent, mobGriefing mode only.
            if (ctx.mobGriefingAllowed() && state.getIntOr("tcount", 0) < TRENCH_MAX_CONVERSIONS) {
                BlockPos feet = mob.blockPosition();
                if (feet.getX() != state.getIntOr("tb_x", Integer.MIN_VALUE)
                    || feet.getZ() != state.getIntOr("tb_z", Integer.MIN_VALUE)) {
                    state.putInt("tb_x", feet.getX());
                    state.putInt("tb_z", feet.getZ());
                    BlockPos floor = feet.below();
                    if (!level.getBlockState(floor).is(Blocks.COARSE_DIRT)
                        && HoglinAbilitySupport.isDiggable(level, floor)) {
                        level.setBlock(floor, Blocks.COARSE_DIRT.defaultBlockState(), Block.UPDATE_ALL);
                        state.putInt("tcount", state.getIntOr("tcount", 0) + 1);
                    }
                }
            }

            // Hoof debris: dirt pillars and chunks bursting up.
            level.sendParticles(DIRT_PILLAR, mob.getX(), mob.getY() + 0.2, mob.getZ(),
                3, 0.7, 0.3, 0.7, 0.10);
            level.sendParticles(DIRT_DEBRIS, mob.getX(), mob.getY() + 0.2, mob.getZ(),
                3, 0.7, 0.3, 0.7, 0.15);
        }

        // Charge end conditions: time, wall, or distance.
        double dx = mob.getX() - state.getDoubleOr("sx", mob.getX());
        double dz = mob.getZ() - state.getDoubleOr("sz", mob.getZ());
        if (ticks >= CHARGE_MAX_TICKS || mob.horizontalCollision
            || dx * dx + dz * dz >= CHARGE_MAX_DISTANCE_SQR) {
            state.putDouble("ex", mob.getX());
            state.putDouble("ez", mob.getZ());
            state.putInt("phase", 1);
        }
    }

    /** One linger pulse: enemies crossing the trench on its coarse dirt are slowed. */
    private void pulseTrench(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        double sx = state.getDoubleOr("sx", 0.0);
        double sy = state.getDoubleOr("sy", 0.0);
        double sz = state.getDoubleOr("sz", 0.0);
        double ex = state.getDoubleOr("ex", sx);
        double ez = state.getDoubleOr("ez", sz);
        AABB box = new AABB(Math.min(sx, ex) - 2.0, sy - 2.5, Math.min(sz, ez) - 2.0,
                            Math.max(sx, ex) + 2.0, sy + 2.0, Math.max(sz, ez) + 2.0);
        List<LivingEntity> victims = ctx.level().getEntitiesOfClass(LivingEntity.class, box,
            e -> HoglinAbilitySupport.isEnemy(ctx, e));
        for (LivingEntity victim : victims) {
            if (distanceToSegment(victim.getX(), victim.getZ(), sx, sz, ex, ez) > 1.8) {
                continue;
            }
            if (level.getBlockState(victim.blockPosition().below()).is(Blocks.COARSE_DIRT)) {
                victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 15, 0));
            }
        }
    }

    /** Horizontal distance from a point to the charge line segment. */
    private static double distanceToSegment(double px, double pz, double sx, double sz, double ex, double ez) {
        double dx = ex - sx;
        double dz = ez - sz;
        double lengthSqr = dx * dx + dz * dz;
        double t = 0.0;
        if (lengthSqr > 1.0e-8) {
            t = Math.max(0.0, Math.min(1.0, ((px - sx) * dx + (pz - sz) * dz) / lengthSqr));
        }
        double closestX = sx + t * dx - px;
        double closestZ = sz + t * dz - pz;
        return Math.sqrt(closestX * closestX + closestZ * closestZ);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Only the knockback modifier is undone. The trench, when
        // plowed, is permanent terrain and is deliberately NOT
        // touched here (terrain rule, see the class comment).
        HoglinAbilitySupport.removeModifier(ctx.mob(), Attributes.ATTACK_KNOCKBACK, KNOCKBACK_ID);
    }
}
