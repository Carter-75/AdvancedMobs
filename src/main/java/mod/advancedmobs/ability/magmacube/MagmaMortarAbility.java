package mod.advancedmobs.ability.magmacube;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
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
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Magma Mortar (Magma Cube ability 1 of 5, attack).
 *
 * WHAT IT DOES: the magma cube lobs one arcing fireball at its
 * target. The shell bursts where it lands in FALLING_LAVA and
 * FLAME, splashing real fire blocks across the impact point.
 * Enemies caught in the splash are scorched: their BURNING_TIME
 * is doubled and they are lit, so they burn long and hard, and
 * the fire patch the shell leaves behind keeps hurting anything
 * that stands in it (vanilla fire blocks do that damage
 * themselves).
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists, between 6 and 24
 *   blocks away (distance squared 36 to 576), and the cube is
 *   on the ground. A mortar is a ranged weapon; point blank
 *   targets are for the cube's body, not its artillery.
 * - The shell (start()): the target's position at this moment
 *   is recorded (the shell aims at where the target stood, so a
 *   moving target can dodge). One SmallFireball is spawned 0.5
 *   blocks above the cube's top with the cube as its owner and
 *   its accelerationPower zeroed, so the shell is a pure
 *   ballistic: nothing propels it after the lob. The lob
 *   velocity is computed for a flight time of T = 20 + (target
 *   distance in blocks) ticks: the horizontal components divide
 *   the horizontal distance by the drag sum of a 0.95 inertia
 *   shell over T ticks, which exactly compensates the shell's
 *   vanilla air drag, and the vertical component covers the
 *   height difference plus half the gravity drop over T ticks.
 *   The aim model is deliberately approximate (a mortar, not a
 *   rifle); the 2.6 block splash below is the real effect.
 * - Flight (tick()): every tick 0.05 of gravity is subtracted
 *   from the shell's vertical velocity (vanilla fireballs have
 *   no gravity of their own; this subtraction is what makes the
 *   shell arc, giving a terminal fall of about 1 block per
 *   tick). The shell's position is recorded every tick. Every
 *   2 ticks, 2 FALLING_LAVA particles drip off the shell: the
 *   visible arc. Vanilla shell behavior on a direct hit is part
 *   of the design: a SmallFireball that strikes an entity
 *   directly deals its vanilla 5.0 fireball damage and lights
 *   it for 5 seconds, and one that strikes a block with
 *   mobGriefing on sets that one block on fire (vanilla checks
 *   the game rule itself).
 * - The splash (when the shell entity is gone, or at the hard
 *   timeout of 100 ticks of flight, when it is discarded where
 *   it flies): one bounded enemy query in radius 2.6 around the
 *   impact point, from 1.5 below to 2 above it. Every enemy
 *   gets the scorch: a BURNING_TIME modifier (add_multiplied_base
 *   +1.0, the victim burns twice as long once lit, modifier id
 *   advancedmobs:magma_mortar_scorch) held for 160 ticks, and
 *   is ignited for 6 seconds (igniteForSeconds), which the
 *   doubled burning time stretches to about 12 seconds of burn.
 *   Scorched victims are tracked in the state tag; every 10
 *   ticks the sweep removes the modifier from victims whose
 *   160 ticks ran out or who are gone, and end() removes it
 *   from everyone still tracked.
 * - Fizzle guard: a shell that vanishes within its first 3
 *   ticks while still within 2.5 blocks of the launch point
 *   (it clipped the cube or the ceiling above it) runs no
 *   splash: the mortar simply fails, nobody is scorched by a
 *   shell that never left.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6): the splash's fire blocks
 * are PERMANENT terrain and are gated by
 * AbilityContext.mobGriefingAllowed():
 * - mobGriefing ON: up to 6 fire blocks are placed around the
 *   impact point (the impact position itself, then random
 *   offsets inside radius 1.5, at the impact height and one
 *   block above and below it), each through the shared
 *   placeFire safety check (dry, replaceable, sturdy ground
 *   beneath, never a block entity). The patch stays and burns
 *   whatever stands in it until it burns out on vanilla's own
 *   schedule.
 * - mobGriefing OFF: no fire is placed (and the vanilla shell
 *   places none either, per its own rule check). The splash
 *   damage path is ignition only: enemies in the radius are
 *   still scorched and lit, exactly as above.
 *
 * WHAT IT LOOKS LIKE: a FIRECHARGE_USE crack (volume 0.9,
 * pitch 0.9) as the shell leaves, a dripping FALLING_LAVA arc
 * across the sky, then the burst: 12 FALLING_LAVA and 10 FLAME
 * particles with a GENERIC_EXPLODE (volume 0.5, pitch 1.2) and
 * a LAVA_POP (volume 0.8, pitch 0.9), over a patch of real fire.
 *
 * PERFORMANCE: flight tracking is one UUID resolve and one
 * position read per tick; the gravity write is one velocity
 * read and write. The single bounded enemy query runs once, at
 * the splash. The scorch sweep runs every 10 ticks over a list
 * that holds at most the handful of enemies one splash can
 * catch. Particle budget per activation: flight at most 50
 * ticks of drips times 2 = 100 FALLING_LAVA (a shell that
 * flies the full 100 tick timeout drips at most 100), splash
 * 22, total at most 122 particles.
 *
 * CLEANUP: end() discards the shell if it is somehow still
 * flying and removes the scorch modifier from every tracked
 * victim that still resolves. Fire blocks already placed
 * (mobGriefing on) are permanent terrain and are deliberately
 * NOT touched.
 *
 * State tag keys: "shell" (the fireball's UUID), "sx", "sy",
 * "sz" (launch point, doubles), "lx", "ly", "lz" (the shell's
 * last known position, doubles), "splashed" (1 once the splash
 * ran), "burning" (list of compounds, one per scorched victim:
 * "id" the victim's UUID, "until" the tick the scorch ends).
 */
public final class MagmaMortarAbility implements MobAbility {

    private static final int HARD_FINISH_TICKS = 280;
    private static final int SHELL_TIMEOUT_TICKS = 100;
    private static final int SCORCH_TICKS = 160;
    private static final double SPLASH_RADIUS = 2.6;
    private static final int MAX_FIRE_BLOCKS = 6;
    private static final double SHELL_GRAVITY = 0.05;
    private static final Identifier SCORCH_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "magma_mortar_scorch");

    @Override
    public String id() {
        return "magma_mortar";
    }

    @Override
    public String displayName() {
        return "Magma Mortar";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null || !ctx.mob().onGround()) {
            return false;
        }
        double distanceSqr = ctx.mob().distanceToSqr(target);
        return distanceSqr >= 36.0 && distanceSqr <= 576.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        state.put("burning", new ListTag());
        state.putInt("splashed", 0);
        LivingEntity target = ctx.target();
        if (level == null || target == null) {
            state.putInt("splashed", 1);
            return;
        }
        double sx = mob.getX();
        double sy = mob.getY() + mob.getBbHeight() + 0.5;
        double sz = mob.getZ();
        state.putDouble("sx", sx);
        state.putDouble("sy", sy);
        state.putDouble("sz", sz);
        state.putDouble("lx", sx);
        state.putDouble("ly", sy);
        state.putDouble("lz", sz);

        // The lob: ballistic velocity for a flight of T ticks,
        // horizontal components drag-compensated (see the class
        // comment), vertical covering the height gap plus the
        // gravity drop.
        double dx = target.getX() - sx;
        double dy = target.getY() - sy;
        double dz = target.getZ() - sz;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        int flightTicks = 20 + (int) horizontal;
        double dragSum = (1.0 - Math.pow(0.95, flightTicks)) / 0.05;
        Vec3 velocity = new Vec3(
            dx / dragSum,
            dy / flightTicks + 0.5 * SHELL_GRAVITY * flightTicks + 0.2,
            dz / dragSum);
        SmallFireball shell = new SmallFireball(level, mob, velocity);
        shell.snapTo(sx, sy, sz, 0.0f, 0.0f);
        // No self-propulsion: the arc comes from the gravity
        // subtraction in tick(), not from the shell's own power.
        shell.accelerationPower = 0.0;
        if (!level.addFreshEntity(shell)) {
            state.putInt("splashed", 1);
            return;
        }
        state.store("shell", UUIDUtil.CODEC, shell.getUUID());
        level.playSound(null, sx, sy, sz, SoundEvents.FIRECHARGE_USE,
            SoundSource.HOSTILE, 0.9f, 0.9f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        int ticks = ctx.ticksActive();

        if (state.getIntOr("splashed", 0) == 0) {
            Entity shell = resolveShell(ctx, level);
            if (shell == null) {
                // The shell hit something (or fizzled against the
                // launch point): the splash runs at its last
                // known position, unless the fizzle guard says it
                // never really left.
                double lx = state.getDoubleOr("lx", ctx.mob().getX());
                double ly = state.getDoubleOr("ly", ctx.mob().getY());
                double lz = state.getDoubleOr("lz", ctx.mob().getZ());
                if (!fizzled(ctx, lx, ly, lz)) {
                    runSplash(ctx, level, lx, ly, lz);
                } else {
                    state.putInt("splashed", 1);
                }
                return;
            }
            state.putDouble("lx", shell.getX());
            state.putDouble("ly", shell.getY());
            state.putDouble("lz", shell.getZ());
            // Gravity: the subtraction that bends the shell's
            // flight into an arc.
            Vec3 velocity = shell.getDeltaMovement();
            shell.setDeltaMovement(velocity.x, velocity.y - SHELL_GRAVITY, velocity.z);
            if (ticks % 2 == 0) {
                level.sendParticles(ParticleTypes.FALLING_LAVA, shell.getX(), shell.getY(), shell.getZ(),
                    2, 0.1, 0.1, 0.1, 0.0);
            }
            if (ticks >= SHELL_TIMEOUT_TICKS) {
                // A shell that never landed bursts where it flies.
                runSplash(ctx, level, shell.getX(), shell.getY(), shell.getZ());
                shell.discard();
            }
            return;
        }

        // The scorch sweep: expired or vanished victims lose the
        // burning time modifier.
        if (ticks % 10 == 0) {
            sweepScorched(ctx, level, false);
        }
    }

    /** Whether the vanished shell fizzled at the launch point (see the class comment). */
    private static boolean fizzled(AbilityContext ctx, double lx, double ly, double lz) {
        if (ctx.ticksActive() > 3) {
            return false;
        }
        CompoundTag state = ctx.state();
        double dx = lx - state.getDoubleOr("sx", lx);
        double dy = ly - state.getDoubleOr("sy", ly);
        double dz = lz - state.getDoubleOr("sz", lz);
        return dx * dx + dy * dy + dz * dz <= 6.25;
    }

    /**
     * The burst: scorch and ignite every enemy at the impact
     * point, and, when the terrain rule allows it, splash
     * permanent fire blocks across the spot.
     */
    private static void runSplash(AbilityContext ctx, ServerLevel level, double x, double y, double z) {
        CompoundTag state = ctx.state();
        state.putInt("splashed", 1);
        List<LivingEntity> victims = MagmaCubeAbilitySupport.enemiesInBox(ctx, x, y, z, SPLASH_RADIUS, 1.5, 2.0);
        ListTag burning = state.getListOrEmpty("burning");
        int until = ctx.ticksActive() + SCORCH_TICKS;
        for (LivingEntity victim : victims) {
            MagmaCubeAbilitySupport.addModifier(victim, Attributes.BURNING_TIME,
                new AttributeModifier(SCORCH_ID, 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
            victim.igniteForSeconds(6.0f);
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, victim.getUUID());
            entry.putInt("until", until);
            burning.add(entry);
        }
        state.put("burning", burning);

        if (ctx.mobGriefingAllowed()) {
            int placed = 0;
            BlockPos center = BlockPos.containing(x, y, z);
            if (MagmaCubeAbilitySupport.placeFire(level, center)) {
                placed++;
            }
            for (int attempt = 0; attempt < 12 && placed < MAX_FIRE_BLOCKS; attempt++) {
                double ox = (ctx.random().nextDouble() * 3.0 - 1.5);
                double oz = (ctx.random().nextDouble() * 3.0 - 1.5);
                int oy = ctx.random().nextInt(3) - 1;
                BlockPos candidate = BlockPos.containing(x + ox, y + oy, z + oz);
                if (MagmaCubeAbilitySupport.placeFire(level, candidate)) {
                    placed++;
                }
            }
        }

        level.sendParticles(ParticleTypes.FALLING_LAVA, x, y + 0.3, z, 12, 0.8, 0.4, 0.8, 0.1);
        level.sendParticles(ParticleTypes.FLAME, x, y + 0.3, z, 10, 0.8, 0.4, 0.8, 0.08);
        level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 0.5f, 1.2f);
        level.playSound(null, x, y, z, SoundEvents.LAVA_POP, SoundSource.HOSTILE, 0.8f, 0.9f);
    }

    /**
     * Removes the scorch modifier from tracked victims whose
     * time ran out or who no longer resolve. With finishAll set
     * (end()), every tracked victim is stripped and the list is
     * cleared.
     */
    private static void sweepScorched(AbilityContext ctx, ServerLevel level, boolean finishAll) {
        CompoundTag state = ctx.state();
        ListTag burning = state.getListOrEmpty("burning");
        ListTag kept = new ListTag();
        int ticks = ctx.ticksActive();
        for (int i = 0; i < burning.size(); i++) {
            CompoundTag entry = burning.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            Entity entity = id != null ? level.getEntity(id) : null;
            boolean expired = finishAll || ticks >= entry.getIntOr("until", 0);
            if (expired) {
                if (entity instanceof LivingEntity victim) {
                    MagmaCubeAbilitySupport.removeModifier(victim, Attributes.BURNING_TIME, SCORCH_ID);
                }
                continue;
            }
            if (entity != null) {
                kept.add(entry);
            }
        }
        state.put("burning", kept);
    }

    /** Resolves the tracked shell from the state tag, or null when it is gone. */
    private Entity resolveShell(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        if (!state.contains("shell")) {
            return null;
        }
        UUID id = state.read("shell", UUIDUtil.CODEC).orElse(null);
        return id != null ? level.getEntity(id) : null;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        if (ctx.ticksActive() >= HARD_FINISH_TICKS) {
            return true;
        }
        return ctx.state().getIntOr("splashed", 0) == 1
            && ctx.state().getListOrEmpty("burning").isEmpty();
    }

    @Override
    public void end(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Entity shell = resolveShell(ctx, level);
        if (shell != null) {
            shell.discard();
        }
        sweepScorched(ctx, level, true);
    }
}
