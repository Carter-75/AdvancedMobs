package mod.advancedmobs.ability.pig;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Truffle Tremor (Pig ability 2 of 5, terrain / attack).
 *
 * WHAT IT DOES: the pig roots its snout and stomps, and a
 * ring of dirt mounds rises smoothly out of the ground
 * around it, hangs for a breath, then crashes back down.
 * Enemies standing in the ring are hit by the falling
 * earth, thrown outward, and the stomp shakes loose a
 * burst of composter sparkles, as if the pig just rooted
 * up something tasty.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the pig on the ground and its current target
 *   within 10 blocks (distance squared 100).
 * - Duration: exactly 30 ticks. The pig is rooted for the
 *   whole activation: navigation stopped every tick
 *   (never NoAi).
 * - The ring (start()): 8 dirt block DISPLAYS (scale 1.4)
 *   at radius 3.0 around the pig, one every 45 degrees.
 *   Each mound's base position is the ground surface at
 *   its column (scanned from 2 above to 5 below the pig's
 *   feet for the first solid block with air above; the
 *   pig's own feet level when no surface is found).
 *   Displays are pure visuals: no hitbox, no collision,
 *   and NOT block changes, so the terrain rule's gate does
 *   not apply; the real ground never moves. Each mound is
 *   recorded in the state tag's "mounds" list ("id", the
 *   display UUID; "x", "y", "z", its base position).
 * - The rise (ticks 1 to 10): every tick each mound is
 *   snapped to base y plus (tick / 10) times 1.3 blocks.
 *   The displays carry a teleport duration of 2 ticks, so
 *   the per-tick snaps read as one smooth rise (the
 *   design's transformation interpolation carried by the
 *   display glide, the pattern every display package in
 *   this mod uses). 2 BLOCK_CRUMBLE particles crumble off
 *   a random mound every 2 ticks while it rises.
 * - The hold (ticks 11 to 19): the mounds hang at full
 *   height; 2 BLOCK_CRUMBLE every 4 ticks.
 * - The crash (tick 20): every mound snaps back to its
 *   base y in one tick. The ring hits: one bounded query
 *   at radius 4.5 (1.5 below to 2.5 above); every enemy
 *   whose horizontal distance from the pig is between 2.0
 *   and 4.5 blocks takes 5.0 damage as a mob attack
 *   credited to the pig (the design's typed ground damage
 *   line, dealt through the mob attack damage source;
 *   a plain pig has no ATTACK_DAMAGE attribute, so the
 *   number is a flat code value) and is thrown outward
 *   from the pig at 1.2 horizontal and 0.5 up, hurtMarked
 *   set. At the same moment: 16 BLOCK_CRUMBLE of dirt at
 *   ring height around the pig, and 12 COMPOSTER sparkles
 *   burst at the pig's snout (the unearthed treat). The
 *   pig's classic ambient sound plays at volume 1.8,
 *   pitch 0.8.
 * - Ticks 21 to 30: settling dust, 2 BLOCK_CRUMBLE every
 *   5 ticks; the mounds stay down.
 * - end(): every surviving mound display is discarded.
 *   Nothing else needs cleanup: no modifiers, no effects.
 *
 * WHAT IT LOOKS LIKE: the earth around the pig bulges up
 * in a perfect ring of dirt mounds, crumbs raining off
 * them, then the whole ring slams flat in a ring of dust
 * and green composter sparkles while enemies in the ring
 * are flung outward.
 *
 * PERFORMANCE: start() does 8 surface scans of at most 8
 * block reads and 8 display spawns, once. Per tick the
 * work is one navigation stop and at most 8 display snaps;
 * particle emissions run every 2 to 5 ticks; the crash is
 * one bounded query. Particle budget per activation:
 * rise 5 times 2 = 10, hold 2 times 2 = 4, crash 28,
 * settle 2 times 2 = 4, total at most 46 particles.
 *
 * TERRAIN: the mounds are display entities, not blocks;
 * no real block changes happen, so the mobGriefing gate
 * does not apply. See the class comment of
 * PigAbilitySupport.
 *
 * State tag keys: "mounds" (list of compounds, one per
 * mound: "id", its UUID; "x", "y", "z", its base
 * position), "crashed" (1 once the crash has fired).
 */
public final class TruffleTremorAbility implements MobAbility {

    private static final int DURATION_TICKS = 30;
    private static final int RISE_TICKS = 10;
    private static final int CRASH_TICK = 20;
    private static final int MOUND_COUNT = 8;
    private static final double RING_RADIUS = 3.0;
    private static final double RISE_HEIGHT = 1.3;
    private static final float MOUND_SCALE = 1.4f;
    private static final double HIT_INNER = 2.0;
    private static final double HIT_OUTER = 4.5;
    private static final double RING_DAMAGE = 5.0;
    private static final double THROW_HORIZONTAL = 1.2;
    private static final double THROW_VERTICAL = 0.5;
    private static final BlockParticleOption DIRT_CRUMBLE =
        new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, Blocks.DIRT.defaultBlockState());

    @Override
    public String id() {
        return "truffle_tremor";
    }

    @Override
    public String displayName() {
        return "Truffle Tremor";
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
        CompoundTag state = ctx.state();
        state.putInt("crashed", 0);
        state.put("mounds", new ListTag());

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag mounds = new ListTag();
        for (int i = 0; i < MOUND_COUNT; i++) {
            double angle = Math.toRadians(i * 45.0);
            double x = caster.getX() + Math.cos(angle) * RING_RADIUS;
            double z = caster.getZ() + Math.sin(angle) * RING_RADIUS;
            double y = findSurfaceY(level, x, caster.getY(), z);
            Entity display = PigAbilitySupport.createDirtDisplay(level, MOUND_SCALE);
            if (display == null) {
                continue;
            }
            display.snapTo(x, y, z, 0.0f, 0.0f);
            if (!level.addFreshEntity(display)) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.store("id", net.minecraft.core.UUIDUtil.CODEC, display.getUUID());
            entry.putDouble("x", x);
            entry.putDouble("y", y);
            entry.putDouble("z", z);
            mounds.add(entry);
        }
        state.put("mounds", mounds);
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
        CompoundTag state = ctx.state();

        // The rise and the crash are per-tick mound snaps.
        if (ticks <= RISE_TICKS) {
            double lift = (ticks / (double) RISE_TICKS) * RISE_HEIGHT;
            snapMounds(ctx, level, lift);
            if (ticks % 2 == 0) {
                crumbleAtRandomMound(ctx, level, lift);
            }
        } else if (ticks < CRASH_TICK) {
            if (ticks % 4 == 0) {
                crumbleAtRandomMound(ctx, level, RISE_HEIGHT);
            }
        } else if (ticks == CRASH_TICK && state.getIntOr("crashed", 0) == 0) {
            state.putInt("crashed", 1);
            snapMounds(ctx, level, 0.0);
            crash(ctx, level);
        } else if (ticks % 5 == 0) {
            crumbleAtRandomMound(ctx, level, 0.0);
        }
    }

    /** Snaps every surviving mound to its base position plus the given lift. */
    private void snapMounds(AbilityContext ctx, ServerLevel level, double lift) {
        ListTag mounds = ctx.state().getListOrEmpty("mounds");
        for (int i = 0; i < mounds.size(); i++) {
            CompoundTag entry = mounds.getCompoundOrEmpty(i);
            Entity display = PigAbilitySupport.resolveTracked(level, entry);
            if (display != null) {
                display.snapTo(entry.getDoubleOr("x", 0.0),
                    entry.getDoubleOr("y", 0.0) + lift,
                    entry.getDoubleOr("z", 0.0), 0.0f, 0.0f);
            }
        }
    }

    /** Rains 2 BLOCK_CRUMBLE off one random mound at the given lift. */
    private void crumbleAtRandomMound(AbilityContext ctx, ServerLevel level, double lift) {
        ListTag mounds = ctx.state().getListOrEmpty("mounds");
        if (mounds.isEmpty()) {
            return;
        }
        CompoundTag entry = mounds.getCompoundOrEmpty(ctx.random().nextInt(mounds.size()));
        level.sendParticles(DIRT_CRUMBLE,
            entry.getDoubleOr("x", 0.0), entry.getDoubleOr("y", 0.0) + lift + 0.7,
            entry.getDoubleOr("z", 0.0),
            2, 0.5, 0.3, 0.5, 0.05);
    }

    /** The crash: ring damage, the outward throw, and the unearthing burst. */
    private void crash(AbilityContext ctx, ServerLevel level) {
        Mob caster = ctx.mob();
        List<LivingEntity> victims = PigAbilitySupport.enemiesInBox(
            ctx, caster.getX(), caster.getY(), caster.getZ(), HIT_OUTER, 1.5, 2.5);
        for (LivingEntity victim : victims) {
            double dx = victim.getX() - caster.getX();
            double dz = victim.getZ() - caster.getZ();
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            if (horizontal < HIT_INNER || horizontal > HIT_OUTER) {
                continue;
            }
            victim.hurtServer(level, level.damageSources().mobAttack(caster), (float) RING_DAMAGE);
            double scale = horizontal < 0.001 ? 0.0 : THROW_HORIZONTAL / horizontal;
            victim.setDeltaMovement(dx * scale, THROW_VERTICAL, dz * scale);
            victim.hurtMarked = true;
        }

        // The ring slams flat: crumble all the way around.
        for (int i = 0; i < MOUND_COUNT; i++) {
            double angle = Math.toRadians(i * 45.0);
            level.sendParticles(DIRT_CRUMBLE,
                caster.getX() + Math.cos(angle) * RING_RADIUS, caster.getY() + 0.6,
                caster.getZ() + Math.sin(angle) * RING_RADIUS,
                2, 0.4, 0.3, 0.4, 0.08);
        }
        // The unearthed treat: composter sparkles at the snout.
        Vec3 look = caster.getLookAngle();
        level.sendParticles(ParticleTypes.COMPOSTER,
            caster.getX() + look.x * 0.8, caster.getY() + 0.5, caster.getZ() + look.z * 0.8,
            12, 0.4, 0.3, 0.4, 0.1);
        if (PigAbilitySupport.pigSound() != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                PigAbilitySupport.pigSound(), SoundSource.HOSTILE, 1.8f, 0.8f);
        }
    }

    /**
     * The surface height at a column: the top of the first
     * solid block with air above, scanning from 2 above to
     * 5 below the reference height; the reference height
     * itself when no surface is found.
     */
    private static double findSurfaceY(ServerLevel level, double x, double refY, double z) {
        int base = (int) Math.floor(refY);
        for (int y = base + 2; y >= base - 5; y--) {
            BlockPos pos = BlockPos.containing(x, y, z);
            if (!level.getBlockState(pos).isAir()
                && level.getBlockState(pos).getCollisionShape(level, pos) != null
                && !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                && level.getBlockState(pos.above()).isAir()) {
                return y + 1.0;
            }
        }
        return refY;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag mounds = ctx.state().getListOrEmpty("mounds");
        for (int i = 0; i < mounds.size(); i++) {
            Entity display = PigAbilitySupport.resolveTracked(level, mounds.getCompoundOrEmpty(i));
            if (display != null && display.isAlive()) {
                display.discard();
            }
        }
    }
}
