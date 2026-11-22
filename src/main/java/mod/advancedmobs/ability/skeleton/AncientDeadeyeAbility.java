package mod.advancedmobs.ability.skeleton;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.List;

/**
 * Ancient Deadeye (Skeleton ability 5 of 5, transformation).
 *
 * WHAT IT DOES: the skeleton's eyes ignite with a cold light and
 * enchantment runes crawl across a gleaming bow it swaps in by
 * direct equipment change. For 15 seconds it is an ancient sniper:
 * its follow range stretches to extreme distance, every enemy
 * anywhere near it is marked GLOWING through walls, and it picks
 * its targets long before they know it is there.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists within 32 blocks (distance
 *   squared 1024).
 * - Duration: exactly 300 ticks (15 seconds) from start().
 * - The weapon swap (start()): the skeleton's current main hand
 *   stack is copied into the state tag (ItemStack CODEC, with a
 *   "had_weapon" flag for the empty-hand case) and replaced with a
 *   fresh bow enchanted with Power V and Flame I (the gleaming
 *   enchanted weapon of the design; the enchantment holders are
 *   resolved from the enchantment registry once, here in start(),
 *   never in the tick path, and if a lookup ever failed the swap
 *   would fall back to a plain bow rather than fail). The
 *   skeleton's ordinary bow goal fires this bow for the duration,
 *   so its real shots hit harder and set targets alight: the swap
 *   is functional, not cosmetic. end() restores the saved stack to
 *   the main hand exactly, or empties the hand when there was
 *   none. If the skeleton picks up a different weapon mid-ability,
 *   the swap still restores the saved one at the end: the deadeye
 *   bow is the ability's and never stays in the world.
 * - Extreme follow range: a FOLLOW_RANGE add_value modifier of
 *   +64 (modifier id advancedmobs:ancient_deadeye_follow_range,
 *   removed in end()). On top of the skeleton's native follow
 *   range that puts its notice and tracking distance at about 80
 *   blocks, far beyond any ordinary skeleton: it acquires and
 *   keeps targets long before they close in.
 * - The ignited eyes: the skeleton itself carries GLOWING and
 *   NIGHT_VISION for the duration (320 ticks each, one application
 *   at start; both removed in end(), which also clears any glowing
 *   or night vision it already had, the same accepted trade Grave
 *   Eruption documents for its invisibility). The glowing outline
 *   is the visible cold light in its eyes, readable through walls;
 *   night vision is the mechanical half of the deadeye's sight.
 * - The marking, every 20 ticks (15 pulses): every enemy
 *   (SkeletonAbilitySupport rules) within 24 blocks of the
 *   skeleton (box reach 4 below and 4 above) gets GLOWING for 30
 *   ticks, refreshed by the next pulse, so marked enemies shine
 *   through walls for as long as they stay in range and for about
 *   a second after the last pulse. Marking is by range, not line
 *   of sight: glowing through walls is the point of the mark.
 *   Marked victims keep no state in the tag: GLOWING is an effect
 *   and expires on its own, so there is nothing to track or undo.
 *
 * WHAT IT LOOKS LIKE: at the swap, 16 ENCHANT glyphs burst around
 * the skeleton's hands and skull; during the ability, 3 more
 * ENCHANT glyphs crawl off the bow every 20 ticks, the skeleton
 * itself burns with the cold glowing outline, and its enemies light
 * up through the terrain around it.
 *
 * PERFORMANCE: start() does the registry lookups and the equipment
 * swap once. tick() returns immediately except every 20th tick,
 * where it runs one bounded entity query (48 by 8 by 48 box) and
 * one 3-particle burst: no per-tick work at all. Particle budget
 * per activation: 16 at start plus at most 15 pulses times 3 = 45,
 * total at most 61 particles.
 *
 * State tag keys: "had_weapon" (whether the main hand held a stack
 * at start), "old_weapon" (the saved main hand stack, ItemStack
 * CODEC; present only when "had_weapon" is true).
 */
public final class AncientDeadeyeAbility implements MobAbility {

    static final int DURATION_TICKS = 300;
    private static final int PULSE_INTERVAL = 20;
    private static final double MARK_RADIUS = 24.0;
    private static final Identifier FOLLOW_RANGE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "ancient_deadeye_follow_range");

    @Override
    public String id() {
        return "ancient_deadeye";
    }

    @Override
    public String displayName() {
        return "Ancient Deadeye";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 1024.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob mob = ctx.mob();

        // The weapon swap: save the old main hand stack, then equip
        // the gleaming bow.
        ItemStack old = mob.getItemBySlot(EquipmentSlot.MAINHAND);
        if (old.isEmpty()) {
            ctx.state().putBoolean("had_weapon", false);
        } else {
            ctx.state().putBoolean("had_weapon", true);
            ctx.state().store("old_weapon", ItemStack.CODEC, old.copy());
        }
        mob.setItemSlot(EquipmentSlot.MAINHAND, gleamingBow(ctx));

        // Extreme follow range for the duration.
        AttributeInstance follow = mob.getAttribute(Attributes.FOLLOW_RANGE);
        if (follow != null) {
            follow.addOrUpdateTransientModifier(
                new AttributeModifier(FOLLOW_RANGE_ID, 64.0, AttributeModifier.Operation.ADD_VALUE));
        }

        // The ignited eyes.
        mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, DURATION_TICKS + 20, 0));
        mob.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, DURATION_TICKS + 20, 0));

        if (level != null) {
            level.sendParticles(ParticleTypes.ENCHANT, mob.getX(), mob.getY() + 1.4, mob.getZ(),
                16, 0.6, 0.7, 0.6, 0.5);
        }
    }

    /** The deadeye's bow: Power V and Flame I, resolved from the registry at start() only. */
    private ItemStack gleamingBow(AbilityContext ctx) {
        ItemStack bow = new ItemStack(Items.BOW);
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return bow;
        }
        try {
            Holder<Enchantment> power =
                level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.POWER);
            Holder<Enchantment> flame =
                level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.FLAME);
            bow.enchant(power, 5);
            bow.enchant(flame, 1);
        } catch (RuntimeException ignored) {
            // A missing enchantment entry can only mean a damaged
            // registry; the swap still happens, with a plain bow.
            return new ItemStack(Items.BOW);
        }
        return bow;
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks % PULSE_INTERVAL != 0) {
            return; // Cheap early-out: marking pulses run every 20 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();

        // The marking pulse: every enemy in range shines through walls.
        List<LivingEntity> marked = SkeletonAbilitySupport.enemiesAroundSelf(ctx, MARK_RADIUS, 4.0, 4.0);
        for (LivingEntity enemy : marked) {
            enemy.addEffect(new MobEffectInstance(MobEffects.GLOWING, 30, 0));
        }

        // Runes crawl across the bow.
        level.sendParticles(ParticleTypes.ENCHANT, mob.getX(), mob.getY() + 1.3, mob.getZ(),
            3, 0.5, 0.5, 0.5, 0.4);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();

        // The old weapon comes back, exactly as it was.
        if (ctx.state().getBooleanOr("had_weapon", false) && ctx.state().contains("old_weapon")) {
            ItemStack old = ctx.state().read("old_weapon", ItemStack.CODEC).orElse(ItemStack.EMPTY);
            mob.setItemSlot(EquipmentSlot.MAINHAND, old);
        } else {
            mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }

        AttributeInstance follow = mob.getAttribute(Attributes.FOLLOW_RANGE);
        if (follow != null) {
            follow.removeModifier(FOLLOW_RANGE_ID);
        }
        mob.removeEffect(MobEffects.GLOWING);
        mob.removeEffect(MobEffects.NIGHT_VISION);
    }
}
