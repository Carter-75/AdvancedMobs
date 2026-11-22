package mod.advancedmobs.ability.llama;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * War Carpet (Llama ability 2 of 5, defense).
 *
 * WHAT IT DOES: the llama's decorative carpet thickens
 * into real armor: the plain carpet on its back is swapped
 * for a deep red war carpet, a faint ENCHANT shimmer runs
 * across the weave, and in its war carpet the llama shrugs
 * off a large share of physical damage and cannot be
 * knocked back by ordinary hits, standing like a furry
 * wall.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the llama's current target within 12 blocks (distance
 *   squared 144).
 * - The swap (start()): the item in the llama's BODY
 *   equipment slot (the llama's carpet slot) is stored into
 *   the state tag through ItemStack.CODEC, and a red carpet
 *   is equipped in its place through the
 *   standard LivingEntity equipment API (carpets are a
 *   color collection in 26.2, so the red carpet item is
 *   Items.CARPET.red()). The LLAMA_SWAG
 *   carpet sound plays at volume 1.0, pitch 0.9. Because
 *   the framework termination path runs end() at the head
 *   of death, a llama killed in its war carpet drops its
 *   ORIGINAL carpet, never the ability's red one (the
 *   Gilded Plate Swap rule).
 * - The armor (start(), removed exactly in end()): two
 *   transient attribute modifiers under fixed ids: ARMOR
 *   +10.0 ADD_VALUE (a plain llama has 0 armor, so this is
 *   10 armor points: about 40 percent off physical damage
 *   before toughness, the design's "large share") and
 *   KNOCKBACK_RESISTANCE +1.0 ADD_VALUE (the attribute
 *   clamps at total resistance, so ordinary hits cannot
 *   move it at all).
 * - Duration: exactly 300 ticks (15 seconds), hard finish.
 * - The shimmer (tick(), every 5 ticks): 3 ENCHANT
 *   particles drift across the llama's back at body height.
 * - end(): both modifiers are removed and the stored
 *   carpet is restored to the BODY slot (an empty stored
 *   stack restores an empty slot). Idempotent: safe when
 *   start() never got to run its swap.
 *
 * WHAT IT LOOKS LIKE: the llama's carpet turns deep red
 * with a magical shimmer crawling over it, and hits that
 * would stagger another mob land with the llama standing
 * planted.
 *
 * PERFORMANCE: start() is one codec store, one equipment
 * write, and two modifier writes, once. tick() returns
 * immediately except every 5th tick, where it sends 3
 * particles; there are no entity queries anywhere in the
 * ability. Particle budget per activation: 60 pulses times
 * 3 = at most 180 particles.
 *
 * TERRAIN: this ability changes no blocks; the swap is an
 * equipment item on the caster itself, so the mobGriefing
 * gate does not apply.
 *
 * State tag keys: "old_carpet" (the stored BODY slot stack,
 * ItemStack.CODEC; absent when the slot was empty).
 */
public final class WarCarpetAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int SHIMMER_INTERVAL = 5;
    private static final Identifier ARMOR_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "war_carpet_armor");
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "war_carpet_knockback");

    @Override
    public String id() {
        return "war_carpet";
    }

    @Override
    public String displayName() {
        return "War Carpet";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();
        if (level == null) {
            return;
        }
        // The swap: store what the llama was wearing, equip the war carpet.
        LlamaAbilitySupport.storeStack(ctx.state(), "old_carpet",
            caster.getItemBySlot(EquipmentSlot.BODY), level.registryAccess());
        caster.setItemSlot(EquipmentSlot.BODY, new ItemStack(Items.CARPET.red()));
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.LLAMA_SWAG,
            SoundSource.HOSTILE, 1.0f, 0.9f);

        // The armor of the weave.
        LlamaAbilitySupport.addModifier(caster, Attributes.ARMOR,
            new AttributeModifier(ARMOR_ID, 10.0, AttributeModifier.Operation.ADD_VALUE));
        LlamaAbilitySupport.addModifier(caster, Attributes.KNOCKBACK_RESISTANCE,
            new AttributeModifier(KNOCKBACK_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % SHIMMER_INTERVAL != 0) {
            return; // Cheap early-out: the shimmer pulses every 5 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        level.sendParticles(ParticleTypes.ENCHANT, caster.getX(), caster.getY() + 1.1, caster.getZ(),
            3, 0.5, 0.25, 0.5, 0.4);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        LlamaAbilitySupport.removeModifier(caster, Attributes.ARMOR, ARMOR_ID);
        LlamaAbilitySupport.removeModifier(caster, Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_ID);

        // The llama's own carpet comes back, whatever it was.
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            ItemStack original = LlamaAbilitySupport.loadStack(ctx.state(), "old_carpet",
                level.registryAccess());
            caster.setItemSlot(EquipmentSlot.BODY, original);
        }
    }
}
