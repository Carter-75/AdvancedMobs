package mod.advancedmobs.ability.vex;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
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
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * Ironbound Duelist (Vex ability 3 of 5, transformation).
 *
 * WHAT IT DOES: the little vex becomes a duelist. It grows
 * by half, its plain iron sword is swapped for a gleaming
 * enchanted blade trailing ENCHANT particles, and its damage
 * climbs. In this form it does not swarm: it fights like a
 * fencer, darting straight in for one committed strike and
 * straight back out again, five strikes in all, and then the
 * form fades and leaves it small and ordinary.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the vex's current target within 12 blocks (distance
 *   squared 144).
 * - The form (start()): SCALE modifier +0.5
 *   ADD_MULTIPLIED_BASE (1.5 times size) and ATTACK_DAMAGE
 *   modifier +3.0 ADD_VALUE, both transient under fixed ids
 *   and both removed in end(). The vex's main hand stack is
 *   stored in the state tag (ItemStack codec, the
 *   Ancient Deadeye pattern) and replaced with an iron sword
 *   carrying SHARPNESS III, resolved from the enchantment
 *   registry at start() only; if the registry lookup somehow
 *   fails the swap still happens, with a plain sword. 12
 *   ENCHANT particles burst at the swap and a VEX_CHARGE
 *   plays (volume 0.9, pitch 1.1).
 * - The fencing (tick()): a two-phase cycle, steered by
 *   direct velocity writes. IN phase: velocity straight at
 *   the target's chest at 0.95 blocks per tick; the first
 *   tick the vex is within 1.9 blocks of the target, the
 *   committed strike lands in code: mob-attack damage
 *   credited to the vex equal to its live ATTACK_DAMAGE
 *   attribute value (which includes the form's +3.0 and the
 *   gleaming sword), with 4 CRIT particles at the victim,
 *   and the strike counter rises by one. The cycle then
 *   flips to the OUT phase: velocity straight away from the
 *   target at 0.95 blocks per tick for exactly 15 ticks,
 *   then back IN. If an IN phase runs 40 ticks without
 *   reaching the target (it fled or hid), the cycle flips to
 *   OUT anyway so the vex never grinds against a wall of
 *   distance. If the target is dead or gone, the vex damps
 *   its velocity (times 0.6 per tick) and waits; the hard
 *   cap still ends the form.
 * - The look while fencing: 2 ENCHANT particles at the vex
 *   every 4 ticks, the gleam of the blade.
 * - Duration: the form ends when the 5th strike lands, with
 *   a hard finish at 400 ticks (20 seconds).
 * - end(): both attribute modifiers are removed by id, and
 *   the stored original main hand stack is restored exactly
 *   (or the slot cleared if the vex had no weapon). If the
 *   caster dies first, the framework termination path runs
 *   this same end() at the head of die(), before loot
 *   generation, so the restored original sword is what drops
 *   and the gleaming blade never enters the loot pool.
 *
 * WHAT IT LOOKS LIKE: a vex half again too big, wings
 * beating hard, a sword that sparkles with enchantment
 * glints as it lunges in, connects, and peels away, five
 * times, then shrinks back to a pest.
 *
 * PERFORMANCE: per tick at most one distance computation and
 * one velocity write; the strike is a single damage call on
 * the tick it lands; no entity queries at all (the victim is
 * the resolved target). The enchantment registry is touched
 * only in start(). Particle budget per activation: 12 at
 * start, ENCHANT at most 2 every 4 ticks over 400 ticks =
 * 200, strike bursts at most 5 times 4 = 20, total at most
 * 232 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "strikes" (committed strikes landed),
 * "phase" (0 in, 1 out), "phase_ticks", "had_weapon",
 * "old_weapon" (the stored main hand stack).
 */
public final class IronboundDuelistAbility implements MobAbility {

    private static final int MAX_STRIKES = 5;
    private static final int HARD_FINISH_TICKS = 400;
    private static final int OUT_TICKS = 15;
    private static final int IN_MAX_TICKS = 40;
    private static final double DART_SPEED = 0.95;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "ironbound_duelist_scale");
    private static final Identifier DAMAGE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "ironbound_duelist_damage");

    @Override
    public String id() {
        return "ironbound_duelist";
    }

    @Override
    public String displayName() {
        return "Ironbound Duelist";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        ctx.state().putInt("strikes", 0);
        ctx.state().putInt("phase", 0);
        ctx.state().putInt("phase_ticks", 0);

        VexAbilitySupport.addModifier(caster, Attributes.SCALE,
            new AttributeModifier(SCALE_ID, 0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        VexAbilitySupport.addModifier(caster, Attributes.ATTACK_DAMAGE,
            new AttributeModifier(DAMAGE_ID, 3.0, AttributeModifier.Operation.ADD_VALUE));

        // The blade swap: save the old main hand stack, then
        // equip the gleaming sword.
        ItemStack old = caster.getItemBySlot(EquipmentSlot.MAINHAND);
        if (old.isEmpty()) {
            ctx.state().putBoolean("had_weapon", false);
        } else {
            ctx.state().putBoolean("had_weapon", true);
            ctx.state().store("old_weapon", ItemStack.CODEC, old.copy());
        }
        caster.setItemSlot(EquipmentSlot.MAINHAND, gleamingSword(ctx));

        if (level != null) {
            level.sendParticles(ParticleTypes.ENCHANT, caster.getX(), caster.getY() + 0.5, caster.getZ(),
                12, 0.3, 0.4, 0.3, 0.5);
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.VEX_CHARGE,
                SoundSource.HOSTILE, 0.9f, 1.1f);
        }
    }

    /** The duelist's blade: an iron sword with SHARPNESS III, resolved from the registry at start() only. */
    private static ItemStack gleamingSword(AbilityContext ctx) {
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return sword;
        }
        try {
            Holder<Enchantment> sharpness =
                level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS);
            sword.enchant(sharpness, 3);
        } catch (RuntimeException ignored) {
            // A missing enchantment entry can only mean a damaged
            // registry; the swap still happens, with a plain sword.
            return new ItemStack(Items.IRON_SWORD);
        }
        return sword;
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        int ticks = ctx.ticksActive();
        if (level != null && ticks % 4 == 0) {
            level.sendParticles(ParticleTypes.ENCHANT, caster.getX(), caster.getY() + 0.5, caster.getZ(),
                2, 0.25, 0.35, 0.25, 0.4);
        }

        LivingEntity target = ctx.target();
        if (target == null) {
            // No one to fence: hover and wait out the form.
            caster.setDeltaMovement(caster.getDeltaMovement().scale(0.6));
            return;
        }
        int phase = ctx.state().getIntOr("phase", 0);
        int phaseTicks = ctx.state().getIntOr("phase_ticks", 0) + 1;
        ctx.state().putInt("phase_ticks", phaseTicks);

        double dx = target.getX() - caster.getX();
        double dy = (target.getY() + 0.8) - caster.getY();
        double dz = target.getZ() - caster.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 0.001) {
            return;
        }
        if (phase == 0) {
            caster.setDeltaMovement(dx / dist * DART_SPEED, dy / dist * DART_SPEED, dz / dist * DART_SPEED);
            if (dist <= 1.9) {
                strike(ctx, level, target);
                ctx.state().putInt("phase", 1);
                ctx.state().putInt("phase_ticks", 0);
            } else if (phaseTicks >= IN_MAX_TICKS) {
                ctx.state().putInt("phase", 1);
                ctx.state().putInt("phase_ticks", 0);
            }
        } else {
            caster.setDeltaMovement(-dx / dist * DART_SPEED, -dy / dist * DART_SPEED, -dz / dist * DART_SPEED);
            if (phaseTicks >= OUT_TICKS) {
                ctx.state().putInt("phase", 0);
                ctx.state().putInt("phase_ticks", 0);
            }
        }
    }

    /** One committed strike (see the class comment). */
    private static void strike(AbilityContext ctx, ServerLevel level, LivingEntity target) {
        ctx.state().putInt("strikes", ctx.state().getIntOr("strikes", 0) + 1);
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        float damage = (float) caster.getAttributeValue(Attributes.ATTACK_DAMAGE);
        target.hurtServer(level, level.damageSources().mobAttack(caster), damage);
        level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY() + 0.8, target.getZ(),
            4, 0.3, 0.4, 0.3, 0.1);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.state().getIntOr("strikes", 0) >= MAX_STRIKES || ctx.ticksActive() >= HARD_FINISH_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        VexAbilitySupport.removeModifier(caster, Attributes.SCALE, SCALE_ID);
        VexAbilitySupport.removeModifier(caster, Attributes.ATTACK_DAMAGE, DAMAGE_ID);

        // The old weapon comes back, exactly as it was.
        if (ctx.state().getBooleanOr("had_weapon", false) && ctx.state().contains("old_weapon")) {
            ItemStack old = ctx.state().read("old_weapon", ItemStack.CODEC).orElse(ItemStack.EMPTY);
            caster.setItemSlot(EquipmentSlot.MAINHAND, old);
        } else {
            caster.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
    }
}
