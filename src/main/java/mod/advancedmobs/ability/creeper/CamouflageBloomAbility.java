package mod.advancedmobs.ability.creeper;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ColorParticleOption;
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
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Camouflage Bloom (Creeper ability 3 of 5, defense).
 *
 * WHAT IT DOES: the creeper freezes and vanishes into the
 * foliage. Its body disappears, leaves drift down around the spot
 * where it stood, and a rustling leafy bush stands in its place.
 * While bloomed it quietly builds up absorption, a bush that is
 * watching its enemies wander past, waiting.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   creeper's current target within 14 blocks (distance squared
 *   196). Blooming is an ambush posture, taken while the enemy is
 *   near but has not closed yet.
 * - Duration: 240 ticks (12 seconds) from start(), ending early
 *   the moment the bloom is broken (below).
 * - The hide (start()): INVISIBILITY, amplifier 0, 240 ticks, no
 *   particles and no icon, so nothing about the creeper renders.
 *   In its place a BlockDisplay of oak leaves appears at the
 *   creeper's block: scaled 1.2 wide, 1.8 high, 1.2 deep with
 *   translation -0.6 in X and Z so the scaled block centers on
 *   the creeper (created through EntityType.loadEntityRecursive
 *   from NBT, the supported display path in 26.2, the same way
 *   Grave Eruption builds its mound). The display is a visual
 *   only: it places no block, so the terrain rule does not apply
 *   to it and mobGriefing is irrelevant here.
 * - Holding still: the creeper does not move for the whole bloom.
 *   Every tick its navigation is stopped and its horizontal
 *   velocity is zeroed, so its war goals cannot walk it out of
 *   its bush. It is NOT set NoAi: NoAi would stop serverAiStep,
 *   where the framework ticks this ability, and freeze the bloom
 *   forever (the standing tick-source contract). It can still be
 *   hit while bloomed if an enemy finds the bush.
 * - The build-up: every 20 ticks the creeper gains 2.0
 *   ABSORPTION (the absorption amount itself, the yellow hearts),
 *   up to 12.0 total built by this ability. The amount this
 *   ability added is tracked in the state tag; end() subtracts
 *   whatever of it is left, so absorption from any other source
 *   is never touched beyond the overlap the subtraction cannot
 *   distinguish (an accepted edge case, the same one Scarab
 *   Brood documents for its fixed-duration effect).
 * - Broken by damage: tick() watches the creeper's health. The
 *   first tick its health is lower than the last tick's, the
 *   bloom is broken: the ability finishes, end() reveals the
 *   creeper and drops the bush. A bush that gets shot does not
 *   stay a bush. Healing never breaks or extends the bloom; the
 *   stored health simply follows the current value each tick.
 *
 * WHAT IT LOOKS LIKE: the creeper blinks out and a slightly too
 * neat oak leaf bush stands where it was, shedding leaves the
 * whole time: every 6 ticks, 2 green TINTED_LEAVES and 1
 * CHERRY_LEAVES drift down around it. Nothing else moves.
 *
 * PERFORMANCE: the per-tick work is one navigation stop, one
 * velocity write, and one health read. Particles run every 6
 * ticks (at most 40 pulses times 3 = 120 per activation), the
 * absorption pulse every 20 ticks. No entity scans, no lookups
 * beyond the display resolve in end().
 *
 * CLEANUP: end() removes the INVISIBILITY (the bloom's own; a
 * creeper that had invisibility from another source would lose
 * it here, the accepted edge case Grave Eruption documents),
 * subtracts the absorption this ability built, and discards the
 * leaf display if it still resolves. If the creeper dies
 * mid-bloom, the framework termination path runs this same end(),
 * so the bush never outlives the ability or its caster.
 *
 * State tag keys: "display" (the leaf display's UUID), "broken"
 * (1 once damage has broken the bloom), "abs_added" (absorption
 * points this activation has built and not yet subtracted),
 * "last_health" (the creeper's health at the previous tick).
 */
public final class CamouflageBloomAbility implements MobAbility {

    static final int DURATION_TICKS = 240;
    private static final int ABSORPTION_PULSE_TICKS = 20;
    private static final float ABSORPTION_PER_PULSE = 2.0f;
    private static final float ABSORPTION_CAP = 12.0f;
    private static final ColorParticleOption LEAF_GREEN =
        ColorParticleOption.create(ParticleTypes.TINTED_LEAVES, 0.25f, 0.55f, 0.2f);

    @Override
    public String id() {
        return "camouflage_bloom";
    }

    @Override
    public String displayName() {
        return "Camouflage Bloom";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        state.putInt("broken", 0);
        state.putFloat("abs_added", 0.0f);
        state.putFloat("last_health", mob.getHealth());

        // Vanish, and stay put: navigation is re-stopped every tick.
        mob.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, DURATION_TICKS, 0, false, false, false));
        mob.getNavigation().stop();
        Vec3 velocity = mob.getDeltaMovement();
        mob.setDeltaMovement(0.0, velocity.y, 0.0);

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }

        // The bush: an oak leaves block display standing where the
        // creeper stood. Visual only, no block is ever placed.
        CompoundTag tag = new CompoundTag();
        tag.put("block_state", NbtUtils.writeBlockState(Blocks.OAK_LEAVES.defaultBlockState()));
        CompoundTag transformation = new CompoundTag();
        transformation.put("translation", floatList(-0.6f, 0.0f, -0.6f));
        transformation.put("scale", floatList(1.2f, 1.8f, 1.2f));
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
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        int ticks = ctx.ticksActive();

        // Hold position while bloomed: goals re-path every tick,
        // so stop the navigation every tick. (NoAi is NOT used: it
        // would stop serverAiStep, where this ability is ticked.)
        mob.getNavigation().stop();
        Vec3 velocity = mob.getDeltaMovement();
        if (velocity.x != 0.0 || velocity.z != 0.0) {
            mob.setDeltaMovement(0.0, velocity.y, 0.0);
        }

        // Broken by damage: any health drop ends the bloom.
        float health = mob.getHealth();
        if (health < state.getFloatOr("last_health", health)) {
            state.putInt("broken", 1);
            return;
        }
        state.putFloat("last_health", health);

        // The quiet build-up: 2 absorption every 20 ticks, cap 12.
        if (ticks % ABSORPTION_PULSE_TICKS == 0) {
            float added = state.getFloatOr("abs_added", 0.0f);
            if (added < ABSORPTION_CAP) {
                float add = Math.min(ABSORPTION_PER_PULSE, ABSORPTION_CAP - added);
                mob.setAbsorptionAmount(mob.getAbsorptionAmount() + add);
                state.putFloat("abs_added", added + add);
            }
        }

        // The rustle: leaves drifting down off the bush.
        ServerLevel level = ctx.serverLevel();
        if (level != null && ticks % 6 == 0) {
            level.sendParticles(LEAF_GREEN, mob.getX(), mob.getY() + 1.6, mob.getZ(),
                2, 0.55, 0.5, 0.55, 0.02);
            level.sendParticles(ParticleTypes.CHERRY_LEAVES, mob.getX(), mob.getY() + 1.6, mob.getZ(),
                1, 0.55, 0.5, 0.55, 0.02);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.state().getIntOr("broken", 0) != 0
            || ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        // Reveal, and take back only the absorption this ability built.
        mob.removeEffect(MobEffects.INVISIBILITY);
        float added = ctx.state().getFloatOr("abs_added", 0.0f);
        if (added > 0.0f) {
            mob.setAbsorptionAmount(Math.max(0.0f, mob.getAbsorptionAmount() - added));
        }
        discardDisplay(ctx);
    }

    /** Discards the leaf display from this activation's state, if it still exists. Idempotent. */
    private void discardDisplay(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null || !ctx.state().contains("display")) {
            return;
        }
        UUID displayId = ctx.state().read("display", UUIDUtil.CODEC).orElse(null);
        if (displayId != null) {
            Entity display = level.getEntity(displayId);
            if (display != null) {
                display.discard();
            }
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
