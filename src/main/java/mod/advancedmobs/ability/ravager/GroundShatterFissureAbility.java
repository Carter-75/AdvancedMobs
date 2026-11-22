package mod.advancedmobs.ability.ravager;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Ground Shatter Fissure (Ravager ability 4 of 5, terrain).
 *
 * WHAT IT DOES: the ravager rears and brings both front legs
 * down, and the earth answers: a fissure of coarse dirt and
 * gravel tears across the ground in a line from its hooves,
 * FALLING_DUST boiling up from the crack as it runs. Enemies
 * standing on the line as each stretch of it erupts are
 * launched into the air by the heaving ground. The torn earth
 * stays torn: while the ability lingers, the fissure line is
 * difficult terrain that drags at enemies crossing it, and
 * afterwards it is simply permanent ravager country.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the ravager's current target within 10 blocks (distance
 *   squared 100) and the ravager on the ground. The fissure
 *   line is fixed at start(): origin at the ravager's feet,
 *   direction toward the target's position at that moment,
 *   so a target that sidesteps dodges the later columns.
 * - The rear (ticks 1 to 4): the navigation is stopped each
 *   tick (never NoAi) and 2 dirt DUST_PILLAR particles puff
 *   at the hooves every 2 ticks. At tick 5 the slam lands
 *   with a RAVAGER_ATTACK at volume 1.2 and pitch 0.7, and
 *   the first column erupts.
 * - The fissure (10 columns): column i (0 to 9) is centered
 *   1.5 + i times 1.2 blocks along the line from the origin
 *   and erupts at tick 5 + i times 4. Each eruption first
 *   finds its surface: the highest block in the column
 *   between origin height +2 and -5 (scanned in that order)
 *   that is not air and carries no fluid, with air above it;
 *   a column with no such block does not erupt (no surface,
 *   no launch, no particles). On eruption:
 *   - Terrain (gated below): the surface block converts to
 *     COARSE_DIRT on even columns and GRAVEL on odd columns
 *     when it passes the shared isDiggable check (never
 *     fluids, block entities, or unbreakable blocks) and is
 *     not already the target block.
 *   - The heave: enemies (RavagerAbilitySupport rules)
 *     within 1.3 blocks horizontally of the column center
 *     and within 2.5 blocks vertically of the surface take
 *     4.0 points of typed mob-attack damage credited to the
 *     ravager and are launched: delta movement set to 0.3
 *     along the fissure direction and 0.95 straight up,
 *     hurtMarked set so the launch syncs.
 *   - The look: 8 FALLING_DUST particles of the surface
 *     block's state boil up from the crack, plus 4 dirt
 *     DUST_PILLAR particles.
 * - The linger (until tick 200): every 10 ticks a fissure
 *   pulse runs one bounded query over the box spanning the
 *   whole line (origin to the last column's center, inflated
 *   by 2.0 horizontally, 2.5 below to 2.0 above the origin
 *   height). Enemies inside whose horizontal distance to the
 *   line segment is at most 1.6 blocks and who are standing
 *   on COARSE_DIRT or GRAVEL (the block below their feet)
 *   gain SLOWNESS amplifier 0 for 15 ticks: the torn ground
 *   drags at them while they cross. After the ability ends
 *   the pulse stops; the fissure itself remains as plain
 *   terrain (the Stampede Trench precedent: the debuff zone
 *   is the ability's, the torn ground is the world's).
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6 and the MobAbility contract):
 * the fissure's coarse dirt and gravel are PERMANENT and
 * gated by AbilityContext.mobGriefingAllowed(), captured
 * once per eruption:
 * - mobGriefing ON: the fissure converts as above and STAYS.
 *   end() touches no blocks.
 * - mobGriefing OFF: no block is converted. The rear, the
 *   slam, every launch, and the dust all run exactly the
 *   same; the linger pulse then finds no fissure blocks of
 *   this ability to slow on (natural coarse dirt or gravel
 *   on the line still counts, it is rough ground either way)
 *   and the world is left exactly as it was.
 *
 * WHAT IT LOOKS LIKE: a rearing ravager, dirt puffing at its
 * hooves, then a RAVAGER_ATTACK slam and a running crack of
 * FALLING_DUST tearing away from it in a straight line,
 * enemies popping into the air as the crack passes under
 * them, and a visible stripe of coarse dirt and gravel left
 * in the ground. Victims show vanilla SLOWNESS sparkles
 * while crossing the fissure during the linger.
 *
 * PERFORMANCE: the rear is one navigation stop per tick;
 * each eruption is at most 8 block reads, one bounded entity
 * query over a 2.6-wide box, and 12 particles, and only 10
 * eruptions happen; the linger does nothing except every
 * 10th tick (one bounded query over the line box plus
 * per-victim block reads for the handful inside). Particle
 * budget per activation: at most 4 rear puffs plus 10
 * eruptions times 12 = at most 124.
 *
 * CLEANUP: end() does nothing. No modifiers or effects are
 * held by the ability, and the fissure blocks, when placed,
 * are permanent terrain and are deliberately NOT touched.
 *
 * State tag keys: "dir_x" and "dir_z" (the fissure
 * direction, unit length), "ox", "oy", "oz" (the line
 * origin: the ravager's feet at start), "ex" and "ez" (the
 * last column's center, written in start()).
 */
public final class GroundShatterFissureAbility implements MobAbility {

    static final int DURATION_TICKS = 200;
    private static final int REAR_TICKS = 4;
    private static final int COLUMN_COUNT = 10;
    private static final int COLUMN_INTERVAL = 4;
    private static final double COLUMN_FIRST = 1.5;
    private static final double COLUMN_SPACING = 1.2;
    private static final float HEAVE_DAMAGE = 4.0f;
    private static final double HEAVE_UP = 0.95;
    private static final double HEAVE_ALONG = 0.3;
    private static final int PULSE_INTERVAL = 10;
    private static final BlockParticleOption DIRT_PILLAR =
        new BlockParticleOption(ParticleTypes.DUST_PILLAR, Blocks.DIRT.defaultBlockState());

    @Override
    public String id() {
        return "ground_shatter_fissure";
    }

    @Override
    public String displayName() {
        return "Ground Shatter Fissure";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().onGround()
            && ctx.mob().distanceToSqr(target) <= 100.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();

        // The fissure line is fixed here: from the ravager's
        // feet toward the target's position right now.
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
        double dirX = dx / length;
        double dirZ = dz / length;
        state.putDouble("dir_x", dirX);
        state.putDouble("dir_z", dirZ);
        state.putDouble("ox", mob.getX());
        state.putDouble("oy", mob.getY());
        state.putDouble("oz", mob.getZ());
        double endDistance = COLUMN_FIRST + (COLUMN_COUNT - 1) * COLUMN_SPACING;
        state.putDouble("ex", mob.getX() + dirX * endDistance);
        state.putDouble("ez", mob.getZ() + dirZ * endDistance);

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.RAVAGER_AMBIENT,
                SoundSource.HOSTILE, 1.2f, 0.7f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks <= REAR_TICKS) {
            // The rear: rooted by navigation stops, dirt
            // puffing at the hooves.
            ctx.mob().getNavigation().stop();
            if (ticks % 2 == 0) {
                ServerLevel level = ctx.serverLevel();
                if (level != null) {
                    Mob mob = ctx.mob();
                    level.sendParticles(DIRT_PILLAR, mob.getX(), mob.getY() + 0.2, mob.getZ(),
                        2, 0.8, 0.2, 0.8, 0.08);
                }
            }
            return;
        }
        int sinceSlam = ticks - (REAR_TICKS + 1);
        if (sinceSlam >= 0 && sinceSlam % COLUMN_INTERVAL == 0) {
            int column = sinceSlam / COLUMN_INTERVAL;
            if (column < COLUMN_COUNT) {
                eruptColumn(ctx, column);
            }
        }
        if (ticks % PULSE_INTERVAL == 0) {
            pulseFissure(ctx);
        }
    }

    /** One column of the fissure: convert the surface, heave its enemies, boil the dust. */
    private void eruptColumn(AbilityContext ctx, int column) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        Mob mob = ctx.mob();
        double dirX = state.getDoubleOr("dir_x", 1.0);
        double dirZ = state.getDoubleOr("dir_z", 0.0);
        double distance = COLUMN_FIRST + column * COLUMN_SPACING;
        double cx = state.getDoubleOr("ox", mob.getX()) + dirX * distance;
        double cz = state.getDoubleOr("oz", mob.getZ()) + dirZ * distance;
        int originY = (int) Math.floor(state.getDoubleOr("oy", mob.getY()));
        int blockX = (int) Math.floor(cx);
        int blockZ = (int) Math.floor(cz);

        if (column == 0) {
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.RAVAGER_ATTACK,
                SoundSource.HOSTILE, 1.2f, 0.7f);
        }

        // Find the surface: the highest dry, non-air block in
        // the scan range with air above it.
        BlockPos surface = null;
        for (int dy = 2; dy >= -5; dy--) {
            BlockPos pos = new BlockPos(blockX, originY + dy, blockZ);
            BlockState candidate = level.getBlockState(pos);
            if (candidate.isAir() || !candidate.getFluidState().isEmpty()) {
                continue;
            }
            if (!level.getBlockState(pos.above()).isAir()) {
                continue;
            }
            surface = pos;
            break;
        }
        if (surface == null) {
            return; // No ground in reach: this stretch of the fissure never forms.
        }
        BlockState surfaceState = level.getBlockState(surface);
        double surfaceY = surface.getY();

        // The conversion: permanent, mobGriefing mode only.
        if (ctx.mobGriefingAllowed() && RavagerAbilitySupport.isDiggable(level, surface)) {
            net.minecraft.world.level.block.Block targetBlock =
                (column % 2 == 0) ? Blocks.COARSE_DIRT : Blocks.GRAVEL;
            if (!surfaceState.is(targetBlock)) {
                level.setBlock(surface, targetBlock.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
                surfaceState = level.getBlockState(surface);
            }
        }

        // The heave: enemies standing on this stretch are
        // launched by the rising ground.
        List<LivingEntity> victims = RavagerAbilitySupport.enemiesInBox(ctx, cx, surfaceY + 1.0, cz, 1.3, 1.5, 2.5);
        for (LivingEntity victim : victims) {
            victim.hurtServer(level, level.damageSources().mobAttack(mob), HEAVE_DAMAGE);
            // The heave replaces the victim's motion outright.
            victim.setDeltaMovement(dirX * HEAVE_ALONG, HEAVE_UP, dirZ * HEAVE_ALONG);
            victim.hurtMarked = true;
        }

        // The look: dust boiling up from the crack.
        level.sendParticles(new BlockParticleOption(ParticleTypes.FALLING_DUST, surfaceState),
            cx, surfaceY + 1.1, cz, 8, 0.5, 0.3, 0.5, 0.05);
        level.sendParticles(DIRT_PILLAR, cx, surfaceY + 1.0, cz,
            4, 0.5, 0.3, 0.5, 0.08);
    }

    /** One linger pulse: enemies crossing the fissure on its torn ground are slowed. */
    private void pulseFissure(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        double ox = state.getDoubleOr("ox", 0.0);
        double oy = state.getDoubleOr("oy", 0.0);
        double oz = state.getDoubleOr("oz", 0.0);
        double ex = state.getDoubleOr("ex", ox);
        double ez = state.getDoubleOr("ez", oz);
        AABB box = new AABB(Math.min(ox, ex) - 2.0, oy - 2.5, Math.min(oz, ez) - 2.0,
                            Math.max(ox, ex) + 2.0, oy + 2.0, Math.max(oz, ez) + 2.0);
        List<LivingEntity> victims = ctx.level().getEntitiesOfClass(LivingEntity.class, box,
            e -> RavagerAbilitySupport.isEnemy(ctx, e));
        for (LivingEntity victim : victims) {
            if (distanceToSegment(victim.getX(), victim.getZ(), ox, oz, ex, ez) > 1.6) {
                continue;
            }
            BlockState below = level.getBlockState(victim.blockPosition().below());
            if (below.is(Blocks.COARSE_DIRT) || below.is(Blocks.GRAVEL)) {
                victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 15, 0));
            }
        }
    }

    /** Horizontal distance from a point to the fissure line segment. */
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
        // Nothing to undo: no modifiers or effects are held,
        // and the fissure blocks, when placed, are permanent
        // terrain and are deliberately NOT touched here
        // (terrain rule, see the class comment).
    }
}
