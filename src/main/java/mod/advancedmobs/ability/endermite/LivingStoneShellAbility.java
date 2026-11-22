package mod.advancedmobs.ability.endermite;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * Living Stone Shell (Endermite ability 3 of 5, defense).
 *
 * WHAT IT DOES: the endermite vanishes and a chunk of stone
 * rubble sits where it stood. The rubble is a block display;
 * the mite is inside it, invisible, armored, and quietly
 * healing under REGENERATION while it holds perfectly still.
 * Enemies hunting a bug find a rock, and the rock waits out
 * the danger, mending.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the mite's current target within 12 blocks (distance
 *   squared 144). The shell is an answer to a fight closing
 *   in, not a long-range trick.
 * - Duration: exactly 160 ticks (8 seconds) from start().
 * - The vanishing (start()): the mite gains INVISIBILITY for
 *   170 ticks (10 ticks past the duration, so the reveal never
 *   flickers early), amplifier 0, with particles and icon
 *   hidden: a visible effect shimmer would give the rock away.
 *   A stone block display, uniform scale 0.7 (a rubble chunk
 *   about twice the mite's own 0.3 block body), is created at
 *   the mite's position with a settling puff of 8 gray DUST
 *   particles (color 0x7D7D7D, scale 1.0).
 * - Inside the shell: ARMOR +10 as a transient attribute
 *   modifier (modifier id "living_stone_shell_armor"; the
 *   mite's base armor is 0, so the shell is the difference
 *   between a swat killing it and a swat glancing off), and
 *   REGENERATION I (amplifier 0) for the full 160 ticks with
 *   particles hidden, healing about 4 health over the shell's
 *   life, half the mite's 8 health pool.
 * - Holding still: every tick, the mite's navigation is
 *   stopped and the display is snapped to the mite's current
 *   position, so a knockback that shifts the mite shifts the
 *   rock with it instead of leaving an empty shell behind.
 *   NoAi is never used (standing contract: it would freeze
 *   the ability's own tick). The mite keeps its target and may
 *   still bite something that steps directly onto it; the
 *   design holds it in place, it does not pacify it.
 * - end(): the ARMOR modifier is removed by id, INVISIBILITY
 *   and REGENERATION are removed, and the display is
 *   discarded. If the mite owns a permanent regeneration
 *   upgrade from its config tree, ScalingSystem's
 *   refreshMissingEffects pass re-applies that permanent
 *   instance on its next processing pass, so removing the
 *   shell's timed instance here is safe; the same pattern the
 *   Drowned's form abilities use.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026): the shell is a block
 * DISPLAY, not a placed block. No block changes, nothing to
 * gate on mobGriefing, and end() discarding the display
 * leaves the world exactly as it was.
 *
 * WHAT IT LOOKS LIKE: a puff of gray dust, the mite gone, and
 * a plain stone chunk sitting on the ground where it was, no
 * shimmer, no hearts, nothing moving. Eight seconds later the
 * rock pops out of existence and the mite is simply there
 * again, half healed.
 *
 * PERFORMANCE: start() creates one display and one particle
 * burst. tick() is one UUID resolve, one position snap, and
 * one navigation stop per tick: no scans, no allocations.
 * Particle budget per activation: 8 DUST at start, nothing
 * else, total 8 particles.
 *
 * State tag keys: "shell" (the block display's UUID).
 */
public final class LivingStoneShellAbility implements MobAbility {

    static final int DURATION_TICKS = 160;
    private static final int INVISIBILITY_TICKS = 170;
    private static final float SHELL_SCALE = 0.7f;
    private static final double ARMOR_BONUS = 10.0;
    private static final Identifier ARMOR_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "living_stone_shell_armor");
    private static final DustParticleOptions SETTLE_DUST = new DustParticleOptions(0x7D7D7D, 1.0f);

    @Override
    public String id() {
        return "living_stone_shell";
    }

    @Override
    public String displayName() {
        return "Living Stone Shell";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob mob = ctx.mob();

        // The vanishing: invisible, armored, quietly mending.
        mob.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, INVISIBILITY_TICKS, 0, false, false, false));
        mob.addEffect(new MobEffectInstance(MobEffects.REGENERATION, DURATION_TICKS, 0, false, false, false));
        AttributeInstance armor = mob.getAttribute(Attributes.ARMOR);
        if (armor != null) {
            armor.addOrUpdateTransientModifier(
                new AttributeModifier(ARMOR_ID, ARMOR_BONUS, AttributeModifier.Operation.ADD_VALUE));
        }

        if (level == null) {
            return;
        }
        Entity shell = EndermiteAbilitySupport.createBlockDisplay(level,
            Blocks.STONE.defaultBlockState(), SHELL_SCALE, false);
        if (shell != null) {
            shell.snapTo(mob.getX(), mob.getY(), mob.getZ());
            if (level.addFreshEntity(shell)) {
                ctx.state().store("shell", UUIDUtil.CODEC, shell.getUUID());
            }
        }
        // The rubble settles with a small puff of dust.
        level.sendParticles(SETTLE_DUST, mob.getX(), mob.getY() + 0.2, mob.getZ(),
            8, 0.3, 0.15, 0.3, 0.05);
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob mob = ctx.mob();
        // Hold still without NoAi: stop the navigation every
        // tick (the standing contract in MobAbility).
        mob.getNavigation().stop();

        // The rock follows the mite, so a knockback never
        // leaves an empty shell behind.
        ServerLevel level = ctx.serverLevel();
        if (level == null || !ctx.state().contains("shell")) {
            return;
        }
        UUID shellId = ctx.state().read("shell", UUIDUtil.CODEC).orElse(null);
        Entity shell = shellId != null ? level.getEntity(shellId) : null;
        if (shell != null) {
            shell.snapTo(mob.getX(), mob.getY(), mob.getZ());
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        AttributeInstance armor = mob.getAttribute(Attributes.ARMOR);
        if (armor != null) {
            armor.removeModifier(ARMOR_ID);
        }
        mob.removeEffect(MobEffects.INVISIBILITY);
        mob.removeEffect(MobEffects.REGENERATION);

        ServerLevel level = ctx.serverLevel();
        if (level != null && ctx.state().contains("shell")) {
            UUID shellId = ctx.state().read("shell", UUIDUtil.CODEC).orElse(null);
            Entity shell = shellId != null ? level.getEntity(shellId) : null;
            if (shell != null) {
                shell.discard();
            }
        }
    }
}
