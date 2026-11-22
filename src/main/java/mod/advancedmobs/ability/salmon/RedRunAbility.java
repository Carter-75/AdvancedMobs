package mod.advancedmobs.ability.salmon;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Red Run (Salmon ability 4 of 5, transformation).
 *
 * WHAT IT DOES: the salmon flushes deep red along its
 * flanks and its jaw hooks for the spawning run. In
 * this form it gains strength and armor, ignores
 * knockback entirely, and its hits make enemies bleed
 * wither for a short time, but it slowly tires and
 * the form fades after the fight peaks.
 *
 * THE LOOK, HONESTLY (read before debugging the
 * render): the design calls for a texture swap done
 * in code and a visibly hooked jaw. Minecraft 26.2
 * gives a server-side ability neither hook: a mob's
 * texture is chosen by its client renderer from its
 * entity type and synced data, and the salmon's only
 * synced variant is its body size class, whose setter
 * (Salmon.setVariant) is private in the jar besides.
 * There is no server call that recolors a salmon or
 * bends its jaw. So the flush is carried the way the
 * Horse package's Nightmare Steed carries its coat:
 * by particles. Deep red dust (the package's pre-built
 * RED_DUST option) streams off both flanks for the
 * whole run, and angry_villager puffs vent from the
 * gills on a pulse: at combat distance the salmon
 * reads as a red, steaming, furious fish, which is
 * what the design is after.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the salmon's current target within 14 blocks
 *   (distance squared 196), plus the salmon in water.
 * - The form (start()): STRENGTH I as an effect for
 *   420 ticks (strength is an effect in 26.2, not an
 *   attribute; removed in end()), plus the design's
 *   attribute lines as transient modifiers, removed
 *   in end(): ARMOR +6.0 add_value (id advancedmobs:
 *   red_run_armor), KNOCKBACK_RESISTANCE +1.0
 *   add_value (id advancedmobs:red_run_knockback;
 *   total immunity, it ignores knockback), and
 *   ATTACK_DAMAGE +3.0 add_value (id advancedmobs:
 *   red_run_attack; the salmon has NO ATTACK_DAMAGE
 *   instance, so the modifier has nothing to ride and
 *   the real bite below is code; applied for the
 *   design's line through the null-tolerant helper).
 *   The opening tell: 8 ANGRY_VILLAGER particles at
 *   the head and SALMON_AMBIENT at volume 1.2, pitch
 *   0.7 (the deepened spawning croak).
 * - Duration: 400 ticks (20 seconds).
 * - The hunt (every tick): the salmon is steered at
 *   0.65 blocks per tick straight at the target's live
 *   position (velocity write via the package steering
 *   helper). The bite: within 2.0 blocks, at most once
 *   per 20 ticks, 4.0 flat typed mob-attack damage
 *   credited to the salmon, and the victim gains
 *   WITHER I for 100 ticks (the bleed: wither damage
 *   over time that can kill, unlike poison), with 4
 *   CRIT particles at the victim.
 * - The flush (visuals): 2 RED_DUST motes every 4
 *   ticks at the two flank offsets (perpendicular to
 *   the facing, at body height), and 2 ANGRY_VILLAGER
 *   every 20 ticks at the head (the gill vents).
 * - The tire (tick 300): the form peaks and fades:
 *   the salmon gains SLOWNESS I for 120 ticks, so the
 *   last quarter of the run is visibly slower even
 *   though strength, armor, and the bleed ride to the
 *   end. Removed in end() along with STRENGTH.
 *
 * WHAT IT LOOKS LIKE: a salmon trailing red along
 * both flanks, anger puffing out of its gills in
 * little bursts, shrugging off every hit as it bores
 * in and leaves its victims bleeding black.
 *
 * PERFORMANCE: per tick one steering write and at
 * most one distance check; particles on 4 and 20 tick
 * intervals; bites at most once per 20 ticks. No
 * entity queries. Particle budget per activation: 8
 * at the start, flank dust 100 pulses times 2 = 200,
 * gill puffs 20 pulses times 2 = 40, bites at most 20
 * times 4 = 80, total at most 328.
 *
 * TERRAIN: this ability changes no blocks; the gate
 * does not apply.
 *
 * CLEANUP: end() removes all three transient
 * modifiers by id and removes the STRENGTH and
 * SLOWNESS effects. Nothing else persists: the form
 * leaves the salmon exactly as it found it.
 *
 * State tag keys: "last_bite" (the tick of the last
 * bite), "tired" (whether the tick 300 slowness has
 * been applied).
 */
public final class RedRunAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int EFFECT_TICKS = 420;
    private static final int TIRE_TICK = 300;
    private static final double SWIM_SPEED = 0.65;
    private static final double BITE_RANGE = 2.0;
    private static final int BITE_COOLDOWN = 20;
    private static final float BITE_DAMAGE = 4.0f;
    private static final int BLEED_TICKS = 100;
    private static final Identifier ARMOR_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "red_run_armor");
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "red_run_knockback");
    private static final Identifier ATTACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "red_run_attack");

    @Override
    public String id() {
        return "red_run";
    }

    @Override
    public String displayName() {
        return "Red Run";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().isInWater()
            && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        state.putInt("last_bite", -100);
        state.putBoolean("tired", false);

        caster.addEffect(new MobEffectInstance(MobEffects.STRENGTH, EFFECT_TICKS, 0, false, false, true));
        SalmonAbilitySupport.addModifier(caster, Attributes.ARMOR,
            new AttributeModifier(ARMOR_ID, 6.0, AttributeModifier.Operation.ADD_VALUE));
        SalmonAbilitySupport.addModifier(caster, Attributes.KNOCKBACK_RESISTANCE,
            new AttributeModifier(KNOCKBACK_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        SalmonAbilitySupport.addModifier(caster, Attributes.ATTACK_DAMAGE,
            new AttributeModifier(ATTACK_ID, 3.0, AttributeModifier.Operation.ADD_VALUE));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.sendParticles(ParticleTypes.ANGRY_VILLAGER,
                caster.getX(), caster.getY() + 0.5, caster.getZ(),
                8, 0.3, 0.3, 0.3, 0.05);
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.SALMON_AMBIENT, SoundSource.HOSTILE, 1.2f, 0.7f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        int ticks = ctx.ticksActive();

        // The tire: once, at the peak of the run.
        if (ticks >= TIRE_TICK && !state.getBooleanOr("tired", false)) {
            state.putBoolean("tired", true);
            caster.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 120, 0, false, false, true));
        }

        LivingEntity target = ctx.target();
        if (target != null && target.isAlive()) {
            SalmonAbilitySupport.steerToward(caster, target.getX(), target.getY() + 0.3, target.getZ(), SWIM_SPEED);
            if (caster.distanceToSqr(target) <= BITE_RANGE * BITE_RANGE
                && ticks - state.getIntOr("last_bite", -100) >= BITE_COOLDOWN) {
                state.putInt("last_bite", ticks);
                target.hurtServer(level, level.damageSources().mobAttack(caster), BITE_DAMAGE);
                target.addEffect(new MobEffectInstance(MobEffects.WITHER, BLEED_TICKS, 0, false, false, true));
                level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY() + 0.4, target.getZ(),
                    4, 0.3, 0.3, 0.3, 0.05);
            }
        }

        // The flush: red dust off both flanks.
        if (ticks % 4 == 0) {
            double facing = Math.toRadians(caster.getYRot());
            double flankX = Math.cos(facing) * 0.35;
            double flankZ = Math.sin(facing) * 0.35;
            level.sendParticles(SalmonAbilitySupport.RED_DUST,
                caster.getX() + flankX, caster.getY() + 0.3, caster.getZ() + flankZ,
                1, 0.1, 0.15, 0.1, 0.0);
            level.sendParticles(SalmonAbilitySupport.RED_DUST,
                caster.getX() - flankX, caster.getY() + 0.3, caster.getZ() - flankZ,
                1, 0.1, 0.15, 0.1, 0.0);
        }
        // The gill vents.
        if (ticks % 20 == 0) {
            level.sendParticles(ParticleTypes.ANGRY_VILLAGER,
                caster.getX(), caster.getY() + 0.5, caster.getZ(),
                2, 0.25, 0.25, 0.25, 0.03);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        SalmonAbilitySupport.removeModifier(caster, Attributes.ARMOR, ARMOR_ID);
        SalmonAbilitySupport.removeModifier(caster, Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_ID);
        SalmonAbilitySupport.removeModifier(caster, Attributes.ATTACK_DAMAGE, ATTACK_ID);
        caster.removeEffect(MobEffects.STRENGTH);
        caster.removeEffect(MobEffects.SLOWNESS);
    }
}
