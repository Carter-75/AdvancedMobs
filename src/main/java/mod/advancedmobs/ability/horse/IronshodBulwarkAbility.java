package mod.advancedmobs.ability.horse;

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
 * Ironshod Bulwark (Horse ability 2 of 5, defense).
 *
 * WHAT IT DOES: the horse's gear flashes and it is suddenly
 * barded in diamond horse armor, fitted live through its
 * body equipment slot. Diamond barding plus the ability's
 * own reinforcement makes its armor value jump, it cannot
 * be knocked back at all while barded, and every blocked
 * hit rings out in a spray of electric sparks.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the horse's current target within 12 blocks (distance
 *   squared 144).
 * - The barding (start()): the item in the horse's BODY
 *   equipment slot (the horse armor slot, the same slot
 *   AbstractHorse.equipBodyArmor writes, verified against
 *   the jar) is stored into the state tag through
 *   ItemStack.CODEC, and a diamond horse armor
 *   (Items.DIAMOND_HORSE_ARMOR) is equipped in its place
 *   through the standard LivingEntity equipment API. The
 *   HORSE_ARMOR sound plays (it is a Holder in 26.2, played
 *   through the Holder overload, volume 1.0) and 12
 *   ELECTRIC_SPARK particles flash across the barding.
 *   Because the framework termination path runs end() at
 *   the head of death, a horse killed in its bulwark drops
 *   its ORIGINAL body item, never the ability's diamond
 *   armor (the Gilded Plate Swap rule).
 * - The armor (start(), removed exactly in end()): two
 *   transient attribute modifiers under fixed ids: ARMOR
 *   +4.0 ADD_VALUE (the design's armor attribute line: the
 *   diamond barding itself carries its own vanilla armor
 *   value through the body slot, and this reinforcement is
 *   on top of it, so the jump is guaranteed whatever the
 *   item data says) and KNOCKBACK_RESISTANCE +1.0
 *   ADD_VALUE (the attribute clamps at total resistance,
 *   so no hit can move the horse at all: it holds its
 *   ground against charges, the design's line).
 * - Duration: exactly 300 ticks (15 seconds), hard finish.
 * - The sparks (tick(), health polled every 2 ticks): the
 *   horse's health is compared against the last polled
 *   value; a fresh drop means a hit was blocked, and at
 *   most once per 10 ticks it bursts 8 ELECTRIC_SPARK at
 *   chest height and rings out with IRON_GOLEM_HURT
 *   (volume 0.7, pitch 1.3: the metallic ring of a blocked
 *   hit). The polled value is refreshed every poll whether
 *   or not a burst fired.
 * - end(): both modifiers are removed and the stored body
 *   item is restored to the BODY slot (an empty stored
 *   stack restores an empty slot). Idempotent: safe when
 *   start() never got to run its swap.
 *
 * WHAT IT LOOKS LIKE: a flash of sparks, and the plain
 * horse is suddenly gleaming in diamond barding; hits land
 * with a ringing crackle of electric sparks and the horse
 * does not give an inch.
 *
 * PERFORMANCE: start() is one codec store, one equipment
 * write, and two modifier writes, once. tick() does one
 * health read and compare every 2 ticks and nothing else;
 * there are no entity queries anywhere in the ability.
 * Particle budget per activation: 12 at start plus at most
 * 30 spark bursts times 8 = 240, total at most 252
 * particles.
 *
 * TERRAIN: this ability changes no blocks; the swap is an
 * equipment item on the caster itself, so the mobGriefing
 * gate does not apply.
 *
 * State tag keys: "old_armor" (the stored BODY slot stack,
 * ItemStack.CODEC; absent when the slot was empty),
 * "last_health" (the last polled health, stored as a float
 * in a double), "next_spark" (first tick another spark
 * burst may fire).
 */
public final class IronshodBulwarkAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int POLL_INTERVAL = 2;
    private static final int SPARK_COOLDOWN_TICKS = 10;
    private static final Identifier ARMOR_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "ironshod_bulwark_armor");
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "ironshod_bulwark_knockback");

    @Override
    public String id() {
        return "ironshod_bulwark";
    }

    @Override
    public String displayName() {
        return "Ironshod Bulwark";
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
        ctx.state().putDouble("last_health", caster.getHealth());
        ctx.state().putInt("next_spark", 0);
        if (level == null) {
            return;
        }
        // The barding: store what the horse was wearing, equip the diamond armor.
        HorseAbilitySupport.storeStack(ctx.state(), "old_armor",
            caster.getItemBySlot(EquipmentSlot.BODY), level.registryAccess());
        caster.setItemSlot(EquipmentSlot.BODY, new ItemStack(Items.DIAMOND_HORSE_ARMOR));
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.HORSE_ARMOR,
            SoundSource.HOSTILE, 1.0f, 1.0f);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, caster.getX(), caster.getY() + 1.2, caster.getZ(),
            12, 0.6, 0.5, 0.6, 0.3);

        // The armor of the barding.
        HorseAbilitySupport.addModifier(caster, Attributes.ARMOR,
            new AttributeModifier(ARMOR_ID, 4.0, AttributeModifier.Operation.ADD_VALUE));
        HorseAbilitySupport.addModifier(caster, Attributes.KNOCKBACK_RESISTANCE,
            new AttributeModifier(KNOCKBACK_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks % POLL_INTERVAL != 0) {
            return; // Cheap early-out: the health poll runs every 2 ticks only.
        }
        Mob caster = ctx.mob();
        double lastHealth = ctx.state().getDoubleOr("last_health", caster.getHealth());
        double health = caster.getHealth();
        ctx.state().putDouble("last_health", health);
        if (health >= lastHealth - 0.01 || ticks < ctx.state().getIntOr("next_spark", 0)) {
            return; // No fresh hit, or the last burst is still ringing.
        }
        ctx.state().putInt("next_spark", ticks + SPARK_COOLDOWN_TICKS);
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        // A blocked hit rings out.
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, caster.getX(), caster.getY() + 1.2, caster.getZ(),
            8, 0.6, 0.5, 0.6, 0.3);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.IRON_GOLEM_HURT, SoundSource.HOSTILE, 0.7f, 1.3f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        HorseAbilitySupport.removeModifier(caster, Attributes.ARMOR, ARMOR_ID);
        HorseAbilitySupport.removeModifier(caster, Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_ID);

        // The horse's own body item comes back, whatever it was.
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            ItemStack original = HorseAbilitySupport.loadStack(ctx.state(), "old_armor",
                level.registryAccess());
            caster.setItemSlot(EquipmentSlot.BODY, original);
        }
    }
}
