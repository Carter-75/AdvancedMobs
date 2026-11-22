package mod.advancedmobs.ability.silverfish;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
 * Rubble Form (Silverfish ability 3 of 5, defense).
 *
 * WHAT IT DOES: the silverfish flattens into a crack in the
 * ground: a flattened stone block display settles where it
 * hides, perfectly still and perfectly ordinary to look at.
 * The fish is inside it, invisible, holding strong ARMOR and
 * RESISTANCE, and it holds the disguise until an enemy steps
 * within striking range, then bursts out of the crack at them.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the fish's current target within 10 blocks (distance
 *   squared 100). The rubble is an ambush laid for a fight
 *   closing in, not a long-range trick.
 * - Duration: at most 240 ticks (12 seconds) from start(),
 *   ending early the moment an enemy steps within striking
 *   range (see the spring check below).
 * - The vanishing (start()): the fish gains INVISIBILITY for
 *   250 ticks (10 ticks past the max duration, so the reveal
 *   never flickers early), amplifier 0, with particles and
 *   icon hidden: a visible effect shimmer would give the
 *   crack away. A stone block display, scaled 1.0 wide, 0.55
 *   high, 1.0 deep (a flat cracked slab of stone, not a full
 *   cube: the fish flattens INTO the floor line), is created
 *   at the fish's position with a settling puff of 8 gray DUST
 *   particles (color 0x7D7D7D, scale 1.0).
 * - Inside the rubble: ARMOR +12 as a transient attribute
 *   modifier (modifier id "rubble_form_armor"; the fish's base
 *   armor is 0, so the rubble is the difference between a swat
 *   killing its 8 health and a swat glancing off), and
 *   RESISTANCE I (amplifier 0) for the full 240 ticks with
 *   particles hidden.
 * - Holding still: every tick, the fish's navigation is
 *   stopped and the display is snapped to the fish's current
 *   position, so a knockback that shifts the fish shifts the
 *   rubble with it instead of leaving an empty crack behind.
 *   NoAi is never used (standing contract: it would freeze
 *   the ability's own tick). The fish keeps its target and may
 *   still bite something that steps directly onto it; the
 *   design holds it in place, it does not pacify it.
 * - The spring check, every 5 ticks: one bounded enemy query
 *   (SilverfishAbilitySupport rules) within horizontal radius
 *   1.6 of the fish, 1.0 below to 1.5 above. Any enemy inside
 *   springs the ambush: the state flag "sprung" is set,
 *   isFinished() returns true, and end() runs the reveal burst
 *   (below). A full 240 ticks with no enemy that close simply
 *   times the disguise out.
 * - end(): the ARMOR modifier is removed by id, INVISIBILITY
 *   and RESISTANCE are removed, and the display is discarded.
 *   When the form was sprung (rather than timed out), end()
 *   also bursts 10 stone BLOCK particles at the fish and plays
 *   a STONE_BREAK (volume 0.9, pitch 1.2): the crack splits
 *   open and the fish is out. If the fish owns a permanent
 *   resistance upgrade from its config tree, ScalingSystem's
 *   refreshMissingEffects pass re-applies that permanent
 *   instance on its next processing pass, so removing the
 *   form's timed instance here is safe; the same pattern
 *   Living Stone Shell uses.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026): the rubble is a block
 * DISPLAY, not a placed block. No block changes, nothing to
 * gate on mobGriefing, and end() discarding the display
 * leaves the world exactly as it was.
 *
 * WHAT IT LOOKS LIKE: a puff of gray dust, the fish gone, and
 * a flat cracked patch of stone sitting in the floor where it
 * was, no shimmer, nothing moving. Then an enemy steps close,
 * the patch cracks open in a spray of stone, and the fish is
 * on them.
 *
 * PERFORMANCE: start() creates one display and one particle
 * burst. tick() is one navigation stop and one UUID resolve
 * plus position snap per tick, and one tight enemy query
 * every 5 ticks: no other scans, no allocations. Particle
 * budget per activation: 8 DUST at start, plus 10 BLOCK when
 * sprung, total at most 18 particles.
 *
 * State tag keys: "shell" (the block display's UUID),
 * "sprung" (1 once an enemy stepped within striking range).
 */
public final class RubbleFormAbility implements MobAbility {

    static final int DURATION_TICKS = 240;
    private static final int INVISIBILITY_TICKS = 250;
    private static final int SPRING_CHECK_INTERVAL = 5;
    private static final double SPRING_RADIUS = 1.6;
    private static final double ARMOR_BONUS = 12.0;
    private static final Identifier ARMOR_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "rubble_form_armor");
    private static final DustParticleOptions SETTLE_DUST = new DustParticleOptions(0x7D7D7D, 1.0f);
    private static final BlockParticleOption STONE_SHARDS =
        new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState());

    @Override
    public String id() {
        return "rubble_form";
    }

    @Override
    public String displayName() {
        return "Rubble Form";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 100.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob mob = ctx.mob();
        ctx.state().putInt("sprung", 0);

        // The vanishing: invisible, armored, damage resistant.
        mob.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, INVISIBILITY_TICKS, 0, false, false, false));
        mob.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, DURATION_TICKS, 0, false, false, false));
        AttributeInstance armor = mob.getAttribute(Attributes.ARMOR);
        if (armor != null) {
            armor.addOrUpdateTransientModifier(
                new AttributeModifier(ARMOR_ID, ARMOR_BONUS, AttributeModifier.Operation.ADD_VALUE));
        }

        if (level == null) {
            return;
        }
        Entity shell = SilverfishAbilitySupport.createBlockDisplay(level,
            Blocks.STONE.defaultBlockState(), 1.0f, 0.55f, 1.0f, false);
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

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        // The rubble follows the fish, so a knockback never
        // leaves an empty crack behind.
        if (ctx.state().contains("shell")) {
            UUID shellId = ctx.state().read("shell", UUIDUtil.CODEC).orElse(null);
            Entity shell = shellId != null ? level.getEntity(shellId) : null;
            if (shell != null) {
                shell.snapTo(mob.getX(), mob.getY(), mob.getZ());
            }
        }

        // The spring check: an enemy within striking range ends
        // the disguise early, on a 5 tick interval.
        if (ctx.ticksActive() % SPRING_CHECK_INTERVAL == 0
            && !SilverfishAbilitySupport.enemiesInBox(ctx, mob.getX(), mob.getY(), mob.getZ(),
                SPRING_RADIUS, 1.0, 1.5).isEmpty()) {
            ctx.state().putInt("sprung", 1);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS || ctx.state().getIntOr("sprung", 0) == 1;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        AttributeInstance armor = mob.getAttribute(Attributes.ARMOR);
        if (armor != null) {
            armor.removeModifier(ARMOR_ID);
        }
        mob.removeEffect(MobEffects.INVISIBILITY);
        mob.removeEffect(MobEffects.RESISTANCE);

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        if (ctx.state().contains("shell")) {
            UUID shellId = ctx.state().read("shell", UUIDUtil.CODEC).orElse(null);
            Entity shell = shellId != null ? level.getEntity(shellId) : null;
            if (shell != null) {
                shell.discard();
            }
        }
        if (ctx.state().getIntOr("sprung", 0) == 1) {
            // The crack splits open and the fish is out.
            level.sendParticles(STONE_SHARDS, mob.getX(), mob.getY() + 0.2, mob.getZ(),
                10, 0.4, 0.25, 0.4, 0.2);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.STONE_BREAK, SoundSource.HOSTILE, 0.9f, 1.2f);
        }
    }
}
