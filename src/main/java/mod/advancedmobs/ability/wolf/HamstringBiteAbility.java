package mod.advancedmobs.ability.wolf;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Hamstring Bite (Wolf ability 2 of 5, attack).
 *
 * WHAT IT DOES: the wolf lunges low and fast at its target
 * and tears at its legs. The bite itself is one ordinary
 * wolf bite, but it cripples: the victim is heavily slowed
 * and its jump strength is cut in half for 5 seconds, easy
 * prey for the rest of the pack.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the wolf's current target within 10 blocks (distance
 *   squared 100) and the wolf on the ground (the lunge is a
 *   ground leap; an airborne wolf does not start one).
 * - The lunge (start()): the target's UUID is recorded in
 *   the state tag, navigation is stopped, and the wolf's
 *   velocity is set toward the target's feet: horizontal
 *   speed 1.15 blocks per tick toward it, vertical 0.35.
 *   The lunge is a single code velocity write (the design's
 *   "velocity lunge in code"); vanilla movement and gravity
 *   carry it from there.
 * - The bite (tick()): on the first tick where the recorded
 *   victim is alive and within 2.2 blocks (distance squared
 *   4.84), the bite lands exactly once: damage equal to the
 *   wolf's live ATTACK_DAMAGE attribute value (4.0 for a
 *   plain wolf, more as its upgrades grow it) dealt as a mob
 *   attack credited to the wolf, SLOWNESS II (amplifier 1)
 *   for 100 ticks, and a transient JUMP_STRENGTH modifier
 *   of -0.5 ADD_MULTIPLIED_BASE (jump strength halved)
 *   under a fixed id. The victim's UUID is already in the
 *   state tag, so end() can strip the jump modifier.
 * - Duration: if the bite lands, the ability runs to tick
 *   100 so end() removes the jump modifier exactly when the
 *   slow expires. If the bite has not landed by tick 40
 *   (the target escaped the lunge), the ability finishes
 *   early with nothing applied. Hard finish at tick 100
 *   either way.
 * - end(): removes the victim's JUMP_STRENGTH modifier if
 *   the bite landed and the victim still exists. Transient
 *   modifiers are not persisted, so a victim that unloaded
 *   mid-cripple has already lost the modifier on its own;
 *   the SLOWNESS is an ordinary effect and expires on its
 *   own clock in step with the modifier's removal.
 *
 * WHAT IT LOOKS LIKE: a low flat lunge, then at the bite a
 * spray of 8 DAMAGE_INDICATOR particles and 10 red DUST
 * motes (color 0xA01818) at the victim's legs, and the
 * victim visibly hobbling for 5 seconds.
 *
 * PERFORMANCE: per tick at most one UUID resolve and one
 * distance check; no entity queries anywhere. Particle
 * budget per activation: at most 18, all at the bite.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "victim" (the target's UUID, stored at
 * start), "bitten" (1 once the bite has landed).
 */
public final class HamstringBiteAbility implements MobAbility {

    private static final int LUNGE_WINDOW_TICKS = 40;
    private static final int CRIPPLE_TICKS = 100;
    private static final double BITE_RANGE_SQR = 4.84; // 2.2 blocks
    private static final Identifier JUMP_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "hamstring_bite_jump");
    private static final DustParticleOptions BLOOD_DUST = new DustParticleOptions(0xA01818, 1.0f);

    @Override
    public String id() {
        return "hamstring_bite";
    }

    @Override
    public String displayName() {
        return "Hamstring Bite";
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
        LivingEntity target = ctx.target();
        ctx.state().putInt("bitten", 0);
        if (target == null) {
            return;
        }
        ctx.state().store("victim", UUIDUtil.CODEC, target.getUUID());
        // The lunge: one flat velocity write at the target's feet.
        caster.getNavigation().stop();
        Vec3 to = new Vec3(target.getX() - caster.getX(), 0.0, target.getZ() - caster.getZ());
        double length = to.length();
        if (length > 0.001) {
            caster.setDeltaMovement(to.x / length * 1.15, 0.35, to.z / length * 1.15);
            caster.hurtMarked = true;
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.state().getIntOr("bitten", 0) != 0) {
            return; // The bite already landed; the cripple clock runs out the duration.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null || !ctx.state().contains("victim")) {
            return;
        }
        UUID victimId = ctx.state().read("victim", UUIDUtil.CODEC).orElse(null);
        if (victimId == null) {
            return;
        }
        Entity found = level.getEntity(victimId);
        if (!(found instanceof LivingEntity victim) || !victim.isAlive()) {
            return;
        }
        Mob caster = ctx.mob();
        if (caster.distanceToSqr(victim) > BITE_RANGE_SQR) {
            return;
        }
        // The bite lands: one ordinary bite of damage, then the cripple.
        double damage = caster.getAttributeValue(Attributes.ATTACK_DAMAGE);
        victim.hurtServer(level, level.damageSources().mobAttack(caster), (float) damage);
        victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, CRIPPLE_TICKS, 1));
        WolfAbilitySupport.addModifier(victim, Attributes.JUMP_STRENGTH,
            new AttributeModifier(JUMP_ID, -0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        ctx.state().putInt("bitten", 1);
        level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, victim.getX(), victim.getY() + 0.4, victim.getZ(),
            8, 0.3, 0.3, 0.3, 0.1);
        level.sendParticles(BLOOD_DUST, victim.getX(), victim.getY() + 0.4, victim.getZ(),
            10, 0.35, 0.3, 0.35, 0.1);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks >= CRIPPLE_TICKS) {
            return true;
        }
        // No bite inside the lunge window: the target escaped, end early.
        return ctx.state().getIntOr("bitten", 0) == 0 && ticks >= LUNGE_WINDOW_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        if (ctx.state().getIntOr("bitten", 0) == 0 || !ctx.state().contains("victim")) {
            return;
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        UUID victimId = ctx.state().read("victim", UUIDUtil.CODEC).orElse(null);
        if (victimId == null) {
            return;
        }
        Entity found = level.getEntity(victimId);
        if (found instanceof LivingEntity victim) {
            WolfAbilitySupport.removeModifier(victim, Attributes.JUMP_STRENGTH, JUMP_ID);
        }
    }
}
