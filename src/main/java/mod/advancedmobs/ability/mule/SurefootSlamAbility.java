package mod.advancedmobs.ability.mule;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Surefoot Slam (Mule ability 2 of 5, terrain / attack).
 *
 * WHAT IT DOES: the mule picks its footing like a mountain
 * animal (its step height rises, so slopes stop mattering),
 * rears up, and slams its front hooves down: a fan of stone
 * slabs bursts out of the ground in front of it, and every
 * enemy standing in the fan is hurt and shoved back over
 * the rubble.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the mule's current target within 10 blocks (distance
 *   squared 100) and the mule standing on the ground (a
 *   slam starts from planted hooves, not mid-jump).
 * - Surefooting (start(), removed exactly in end()): one
 *   transient modifier under a fixed id, STEP_HEIGHT +2.0
 *   ADD_VALUE (the design's raised step height: the mule's
 *   base 1.0 becomes 3.0 for the whole activation, so it
 *   can walk straight up the slope it is fighting on).
 *   The slam direction is also locked in start(): the
 *   mule's horizontal look direction, normalized, stored
 *   in the state tag; the fan never re-aims mid-activation.
 * - The rear-up (ticks 1 to 9): navigation is stopped every
 *   tick (never NoAi) and 2 DUST_PILLAR of the ground state
 *   puff at the hooves every 2 ticks as it paws and rears.
 * - The slam (tick 10, once, guarded by a state flag):
 *   GENERIC_EXPLODE at volume 1.0, pitch 0.8; 12
 *   BLOCK_CRUMBLE of the stone slab state and 8 DUST_PILLAR
 *   of the ground state burst at the mule's feet. Five
 *   stone slab block displays (scale 1.0, Blocks.STONE_SLAB,
 *   the design's block_display line) pop out of the ground
 *   in a fan: each at 2.2 blocks from the mule along a
 *   direction rotated -0.6, -0.3, 0.0, +0.3, +0.6 radians
 *   from the locked slam direction, seated at the first
 *   solid ground found scanning down at most 4 blocks from
 *   the mule's foot height (the mule's own height when the
 *   scan finds nothing). Their UUIDs are stored in the
 *   state tag and end() discards them: they are displays,
 *   not placed blocks, so they leave no rubble behind and
 *   the mobGriefing gate does not apply to them.
 * - The fan's rise (ticks 11 to 16): each surviving slab is
 *   snapped upward in 0.1 block steps to +0.5 blocks above
 *   its seated height (the displays' 2-tick teleport glide
 *   carries the pop as a smooth heave, the design's
 *   interpolation line), then they hold as a rubble fan
 *   until end().
 * - The hit (at the slam, one bounded query): every enemy
 *   within radius 4.5 of the mule whose direction from the
 *   mule has a dot product of at least 0.45 with the locked
 *   slam direction (point-blank enemies within 1.5 blocks
 *   are always caught) takes 6.0 flat mob-attack damage
 *   credited to the mule (the /damage line of the design's
 *   Uses list, delivered as a code damage call like every
 *   damage number in this package) and is shoved directly
 *   away from the mule at 1.2 horizontal and 0.45 up (the
 *   design's code knockback impulse), hurtMarked.
 * - Duration: exactly 60 ticks (3 seconds): rear-up, slam,
 *   the slabs' rise and short hold, then end().
 *
 * WHAT IT LOOKS LIKE: the mule paws the ground, rears, and
 * comes down with a crack: dust pillars off its hooves, a
 * fan of stone slabs heaving out of the earth in front of
 * it, and the enemy line staggering back over the slabs.
 *
 * PERFORMANCE: start() is one modifier write and a few
 * state writes. The rear-up is one navigation stop and a
 * particle send every 2 ticks; the slam is at most 5
 * ground scans of at most 5 block reads, 5 display spawns,
 * and one bounded radius-4.5 query; the rise snaps at most
 * 5 displays per tick for 6 ticks. Particle budget per
 * activation: rear-up at most 5 sends of 2 = 10, slam 20,
 * total at most 30 particles.
 *
 * TERRAIN: the slabs are display entities, not blocks;
 * nothing is placed or broken, so the mobGriefing gate
 * does not apply (see the support class comment).
 *
 * State tag keys: "dx", "dz" (the locked slam direction,
 * normalized), "slammed" (1 once the slam has fired),
 * "slabs" (list of compounds, one per slab display: "id",
 * the display's UUID, "gy", its seated ground height).
 */
public final class SurefootSlamAbility implements MobAbility {

    private static final int DURATION_TICKS = 60;
    private static final int SLAM_TICK = 10;
    private static final double FAN_DISTANCE = 2.2;
    private static final double[] FAN_ANGLES = {-0.6, -0.3, 0.0, 0.3, 0.6};
    private static final float FAN_DAMAGE = 6.0f;
    private static final Identifier STEP_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "surefoot_slam_step");

    @Override
    public String id() {
        return "surefoot_slam";
    }

    @Override
    public String displayName() {
        return "Surefoot Slam";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().onGround()
            && ctx.mob().distanceToSqr(target) <= 100.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        // Surefooting for the whole activation (see the
        // class comment), removed in end().
        MuleAbilitySupport.addModifier(caster, Attributes.STEP_HEIGHT,
            new AttributeModifier(STEP_ID, 2.0, AttributeModifier.Operation.ADD_VALUE));

        Vec3 look = caster.getLookAngle();
        double dx = look.x;
        double dz = look.z;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0e-4) {
            dx = 1.0;
            dz = 0.0;
            length = 1.0;
        }
        CompoundTag state = ctx.state();
        state.putDouble("dx", dx / length);
        state.putDouble("dz", dz / length);
        state.putInt("slammed", 0);
        state.put("slabs", new ListTag());
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        int ticks = ctx.ticksActive();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        if (ticks < SLAM_TICK) {
            // The rear-up: rooted, pawing dust.
            caster.getNavigation().stop();
            if (ticks % 2 == 0) {
                level.sendParticles(
                    MuleAbilitySupport.groundDustPillar(level, caster.getX(), caster.getY(), caster.getZ()),
                    caster.getX(), caster.getY() + 0.2, caster.getZ(),
                    2, 0.4, 0.1, 0.4, 0.05);
            }
            return;
        }
        if (ctx.state().getIntOr("slammed", 0) == 0) {
            slam(ctx, level);
            return;
        }
        // The rise: the fan heaves up in steps, then holds.
        if (ticks <= SLAM_TICK + 6) {
            double lift = (ticks - SLAM_TICK) * 0.1;
            ListTag slabs = ctx.state().getListOrEmpty("slabs");
            for (int i = 0; i < slabs.size(); i++) {
                CompoundTag entry = slabs.getCompoundOrEmpty(i);
                Entity slab = MuleAbilitySupport.resolveTracked(level, entry);
                if (slab != null) {
                    slab.snapTo(slab.getX(), entry.getDoubleOr("gy", caster.getY()) + lift, slab.getZ());
                }
            }
        }
    }

    /** The slam itself: sound, bursts, the slab fan, and the fan hit. Runs exactly once. */
    private void slam(AbilityContext ctx, ServerLevel level) {
        ctx.state().putInt("slammed", 1);
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        double dx = state.getDoubleOr("dx", 1.0);
        double dz = state.getDoubleOr("dz", 0.0);

        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.GENERIC_EXPLODE,
            SoundSource.HOSTILE, 1.0f, 0.8f);
        level.sendParticles(
            new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, Blocks.STONE_SLAB.defaultBlockState()),
            caster.getX(), caster.getY() + 0.3, caster.getZ(),
            12, 0.8, 0.3, 0.8, 0.1);
        level.sendParticles(
            MuleAbilitySupport.groundDustPillar(level, caster.getX(), caster.getY(), caster.getZ()),
            caster.getX(), caster.getY() + 0.2, caster.getZ(),
            8, 0.8, 0.1, 0.8, 0.08);

        // The fan: five slab displays popping out of the
        // ground in front of the mule.
        for (double angle : FAN_ANGLES) {
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            double dirX = dx * cos - dz * sin;
            double dirZ = dx * sin + dz * cos;
            double px = caster.getX() + dirX * FAN_DISTANCE;
            double pz = caster.getZ() + dirZ * FAN_DISTANCE;
            double groundY = findGroundY(level, px, pz, caster.getY());
            Entity slab = MuleAbilitySupport.createBlockDisplay(level,
                Blocks.STONE_SLAB.defaultBlockState(), 1.0f);
            if (slab == null) {
                continue;
            }
            slab.snapTo(px, groundY, pz);
            level.addFreshEntity(slab);
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, slab.getUUID());
            entry.putDouble("gy", groundY);
            ListTag slabs = state.getListOrEmpty("slabs");
            slabs.add(entry);
            state.put("slabs", slabs);
        }

        // The hit: enemies standing in the fan are hurt and
        // shoved back over the rubble.
        for (LivingEntity enemy : MuleAbilitySupport.enemiesInBox(ctx,
                caster.getX(), caster.getY(), caster.getZ(), 4.5, 1.5, 2.5)) {
            double awayX = enemy.getX() - caster.getX();
            double awayZ = enemy.getZ() - caster.getZ();
            double length = Math.sqrt(awayX * awayX + awayZ * awayZ);
            double dot = length > 1.0e-4 ? (awayX * dx + awayZ * dz) / length : 1.0;
            if (dot < 0.45 && length > 1.5) {
                continue;
            }
            enemy.hurtServer(level, level.damageSources().mobAttack(caster), FAN_DAMAGE);
            if (length > 1.0e-4) {
                Vec3 current = enemy.getDeltaMovement();
                enemy.setDeltaMovement(awayX / length * 1.2, Math.max(current.y, 0.45), awayZ / length * 1.2);
                enemy.hurtMarked = true;
            }
        }
    }

    /** The ground height at a fan position: the mule's foot height, or the first solid top found scanning down 4 blocks. */
    private static double findGroundY(ServerLevel level, double x, double z, double fromY) {
        BlockPos base = BlockPos.containing(x, fromY + 1.0, z);
        for (int i = 0; i < 5; i++) {
            BlockPos pos = base.below(i);
            if (!level.getBlockState(pos).isAir()
                && !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                return pos.getY() + 1.0;
            }
        }
        return fromY;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        MuleAbilitySupport.removeModifier(caster, Attributes.STEP_HEIGHT, STEP_ID);

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag slabs = ctx.state().getListOrEmpty("slabs");
        for (int i = 0; i < slabs.size(); i++) {
            Entity slab = MuleAbilitySupport.resolveTracked(level, slabs.getCompoundOrEmpty(i));
            if (slab != null) {
                slab.discard();
            }
        }
    }
}
