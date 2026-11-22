package mod.advancedmobs.ability.zombie;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Grave Eruption (Zombie ability 5 of 5, mobility).
 *
 * WHAT IT DOES: the zombie sinks into a fresh dirt mound and vanishes
 * from sight for a few seconds, then bursts out of the ground beside its
 * target in a spray of dirt, with one free heavy swing: Strength II for
 * 3 seconds after the eruption.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists and is between 5 and 16 blocks
 *   away (distance squared 25 to 256). The zombie must have room to
 *   appear next to its target, so the eruption position is chosen at
 *   eruption time (see below), not at start.
 * - Burrow (ticks 0 to 59, 3 seconds): at start the zombie gains
 *   INVISIBILITY (75 ticks, amplifier 0, no particles, no icon) and its
 *   navigation is stopped; every tick during the burrow the navigation
 *   is stopped again and horizontal velocity is zeroed, so the war goals
 *   cannot walk it out of its hole. The mound is a BlockDisplay of dirt
 *   (see VISUALS). The zombie is NOT made invulnerable and is NOT set
 *   NoAi: NoAi would stop serverAiStep, which is exactly where the
 *   framework ticks this ability, so the zombie would freeze in its hole
 *   forever. It can still be hit while burrowed if an enemy finds it.
 * - Eruption (tick 60): the zombie teleports beside its target:
 *   candidate spots, tried in order, are 1.6 and 2.6 blocks from the
 *   target on the line from the target back toward the burrow, then the
 *   target's own position, then (if none is free) it stays put. A spot
 *   is free when the zombie's bounding box at that spot collides with
 *   nothing (Level.noCollision). If the target died, the snapshot of its
 *   position from start() is used instead. The invisibility effect is
 *   removed at eruption (including any invisibility the zombie already
 *   had: the eruption is meant to reveal it). The zombie retargets the
 *   live target and gains STRENGTH II (amplifier 1) for 60 ticks (3
 *   seconds): the free heavy swing window.
 * - The ability finishes at tick 66, six ticks after the eruption, so
 *   end() runs while the Strength window is still active.
 *
 * WHAT IT LOOKS LIKE: the mound is a block display of dirt, scaled
 * 1.5 wide by 0.8 high, centered on the burrow spot (translation -0.75
 * in X and Z so the scaled block centers on the zombie), created through
 * EntityType.loadEntityRecursive from NBT (Display setters are private
 * in 26.2; the NBT summon path is the supported way to configure a
 * display). Every 6 ticks during the burrow, 2 FALLING_DUST particles
 * of dirt stream off the mound. At the eruption, 14 BLOCK particles of
 * dirt burst at the zombie and 6 more FALLING_DUST particles follow.
 *
 * PERFORMANCE: the burrow tick does one navigation stop, one velocity
 * write, and one 2-particle burst every 6 ticks. The eruption is a
 * single teleport plus a handful of bounded collision checks. Particle
 * budget per activation: at most 10 burrow pulses times 2 = 20, plus 20
 * at the eruption, total at most 40 particles. The display is one extra
 * entity for 3 seconds, discarded at the eruption.
 *
 * CLEANUP: end() removes the invisibility effect when the eruption never
 * happened (the only path where it can still be active) and discards the
 * mound display if it still resolves, so the display never outlives the
 * ability. If the caster dies mid-burrow, the framework's termination
 * path (AbilitySystem.terminateActiveAbility, called from the death and
 * removal hooks) runs this same end(), so the display is discarded then
 * too. This ability used to carry a TickScheduler backup for that case;
 * the framework fix in the Nov 21, 2026 audit made it provably
 * redundant (same end(), same discard, strictly earlier), so it was
 * removed. Behavior is unchanged: the mound never outlives the ability
 * or its caster.
 *
 * State tag keys: "target" (target UUID), "tx", "ty", "tz" (target
 * position snapshot), "display" (mound display UUID), "erupted"
 * (whether the eruption already fired).
 */
public final class GraveEruptionAbility implements MobAbility {

    private static final int ERUPT_TICK = 60;
    private static final int END_TICK = 66;
    private static final int INVISIBILITY_TICKS = 75;
    private static final int STRENGTH_TICKS = 60;
    private static final BlockParticleOption DIRT_BLOCK =
        new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DIRT.defaultBlockState());
    private static final BlockParticleOption DIRT_FALLING_DUST =
        new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.DIRT.defaultBlockState());

    @Override
    public String id() {
        return "grave_eruption";
    }

    @Override
    public String displayName() {
        return "Grave Eruption";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null) {
            return false;
        }
        double distanceSqr = ctx.mob().distanceToSqr(target);
        return distanceSqr >= 25.0 && distanceSqr <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob mob = ctx.mob();
        LivingEntity target = ctx.target();
        CompoundTag state = ctx.state();
        if (target != null) {
            state.store("target", UUIDUtil.CODEC, target.getUUID());
            state.putDouble("tx", target.getX());
            state.putDouble("ty", target.getY());
            state.putDouble("tz", target.getZ());
        }
        state.putBoolean("erupted", false);

        // Vanish and stay put. Navigation is re-stopped every burrow tick.
        mob.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, INVISIBILITY_TICKS, 0, false, false, false));
        mob.getNavigation().stop();
        Vec3 velocity = mob.getDeltaMovement();
        mob.setDeltaMovement(0.0, velocity.y, 0.0);

        if (level == null) {
            return;
        }

        // The mound: a dirt block display, 1.5 wide and 0.8 high, centered
        // on the zombie's block. Displays are configured through NBT in
        // 26.2 (their setters are private); loadEntityRecursive is the
        // supported spawn path for them.
        CompoundTag tag = new CompoundTag();
        tag.put("block_state", NbtUtils.writeBlockState(Blocks.DIRT.defaultBlockState()));
        CompoundTag transformation = new CompoundTag();
        transformation.put("translation", floatList(-0.75f, 0.0f, -0.75f));
        transformation.put("scale", floatList(1.5f, 0.8f, 1.5f));
        transformation.put("left_rotation", floatList(0.0f, 0.0f, 0.0f, 1.0f));
        transformation.put("right_rotation", floatList(0.0f, 0.0f, 0.0f, 1.0f));
        tag.put("transformation", transformation);
        Entity display = EntityType.loadEntityRecursive(EntityTypes.BLOCK_DISPLAY, tag, level,
            EntitySpawnReason.COMMAND, net.minecraft.world.entity.EntityProcessor.NOP);
        if (display != null) {
            BlockPos feet = mob.blockPosition();
            display.snapTo(feet.getX(), feet.getY(), feet.getZ());
            level.addFreshEntity(display);
            state.store("display", UUIDUtil.CODEC, display.getUUID());
            // No death backup is scheduled: if the caster dies, the
            // framework termination path runs end(), which discards this
            // display through discardDisplay(ctx). See CLEANUP above.
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        if (state.getBooleanOr("erupted", false)) {
            return; // Post-eruption: nothing to do until the finish tick.
        }
        // Hold position while burrowed: goals re-path every tick, so stop
        // the navigation every tick. (NoAi is NOT used: it would stop
        // serverAiStep, where this ability itself is ticked.)
        mob.getNavigation().stop();
        Vec3 velocity = mob.getDeltaMovement();
        if (velocity.x != 0.0 || velocity.z != 0.0) {
            mob.setDeltaMovement(0.0, velocity.y, 0.0);
        }

        ServerLevel level = ctx.serverLevel();
        if (level != null && ctx.ticksActive() % 6 == 0) {
            level.sendParticles(DIRT_FALLING_DUST, mob.getX(), mob.getY() + 0.9, mob.getZ(), 2, 0.5, 0.2, 0.5, 0.02);
        }
        if (ctx.ticksActive() >= ERUPT_TICK) {
            erupt(ctx);
            state.putBoolean("erupted", true);
        }
    }

    /** The eruption: teleport beside the target, reveal, arm the heavy swing, discard the mound. */
    private void erupt(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();

        LivingEntity target = null;
        double anchorX = state.getDoubleOr("tx", mob.getX());
        double anchorY = state.getDoubleOr("ty", mob.getY());
        double anchorZ = state.getDoubleOr("tz", mob.getZ());
        if (level != null && state.contains("target")) {
            UUID targetId = state.read("target", UUIDUtil.CODEC).orElse(null);
            if (targetId != null && level.getEntity(targetId) instanceof LivingEntity living && living.isAlive()) {
                target = living;
                anchorX = target.getX();
                anchorY = target.getY();
                anchorZ = target.getZ();
            }
        }

        // Pick the first collision-free spot near the anchor, on the line
        // from the anchor back toward the burrow.
        double towardX = mob.getX() - anchorX;
        double towardZ = mob.getZ() - anchorZ;
        double towardLen = Math.sqrt(towardX * towardX + towardZ * towardZ);
        if (towardLen < 1.0e-4) {
            towardX = 0.0;
            towardZ = 1.0;
        } else {
            towardX /= towardLen;
            towardZ /= towardLen;
        }
        double destX = mob.getX(), destY = mob.getY(), destZ = mob.getZ();
        double[][] candidates = {
            {anchorX + towardX * 1.6, anchorY, anchorZ + towardZ * 1.6},
            {anchorX + towardX * 2.6, anchorY, anchorZ + towardZ * 2.6},
            {anchorX, anchorY, anchorZ}
        };
        for (double[] candidate : candidates) {
            if (level != null && isFreeSpot(level, mob, candidate[0], candidate[1], candidate[2])) {
                destX = candidate[0];
                destY = candidate[1];
                destZ = candidate[2];
                break;
            }
        }
        mob.teleportTo(destX, destY, destZ);
        mob.getNavigation().stop();
        if (target != null) {
            mob.setTarget(target);
        }

        // Reveal: the eruption is meant to be seen.
        mob.removeEffect(MobEffects.INVISIBILITY);
        // One free heavy swing: Strength II for 3 seconds.
        mob.addEffect(new MobEffectInstance(MobEffects.STRENGTH, STRENGTH_TICKS, 1));

        if (level != null) {
            level.sendParticles(DIRT_BLOCK, destX, destY + 0.5, destZ, 14, 0.5, 0.4, 0.5, 0.3);
            level.sendParticles(DIRT_FALLING_DUST, destX, destY + 0.5, destZ, 6, 0.6, 0.3, 0.6, 0.05);
        }
        discardDisplay(ctx);
    }

    /** Whether the zombie's bounding box at the given feet position collides with nothing. */
    private boolean isFreeSpot(ServerLevel level, Mob mob, double x, double y, double z) {
        double halfWidth = mob.getBbWidth() / 2.0;
        AABB box = new AABB(x - halfWidth, y, z - halfWidth, x + halfWidth, y + mob.getBbHeight(), z + halfWidth);
        return level.noCollision(mob, box);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= END_TICK;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        if (!ctx.state().getBooleanOr("erupted", false)) {
            // Finished without erupting: reveal the zombie.
            mob.removeEffect(MobEffects.INVISIBILITY);
        }
        discardDisplay(ctx);
    }

    /** Discards the mound display from this activation's state, if it still exists. Idempotent. */
    private void discardDisplay(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null || !ctx.state().contains("display")) {
            return;
        }
        UUID displayId = ctx.state().read("display", UUIDUtil.CODEC).orElse(null);
        if (displayId != null) {
            discardDisplay(level, displayId);
        }
    }

    /** Discards the display with the given id in the given level, if present. Idempotent. */
    private static void discardDisplay(ServerLevel level, UUID displayId) {
        Entity display = level.getEntity(displayId);
        if (display != null) {
            display.discard();
        }
    }

    private static ListTag floatList(float... values) {
        ListTag list = new ListTag();
        for (float value : values) {
            list.add(FloatTag.valueOf(value));
        }
        return list;
    }
}
