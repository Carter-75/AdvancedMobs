package mod.advancedmobs.ability.armadillo;

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
import net.minecraft.world.entity.animal.armadillo.Armadillo;

/**
 * Scute Shield (Armadillo ability 2 of 5, defense).
 *
 * WHAT IT DOES: the armadillo hunkers down and its scutes
 * lock together like plate armor. For fifteen seconds it is
 * a planted bunker: hits land with a clink and a spray of
 * scute flecks, its attacker is shrugged off, and small
 * attackers simply glance off the shell and bounce away
 * harmlessly.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   its current target within 12 blocks (distance squared
 *   144).
 * - The hunker: the vanilla synced ArmadilloState is
 *   switched to SCARED at start() (the vanilla hunkered
 *   shell state) and re-asserted on any tick where the
 *   brain has wandered out of it. end() switches back to
 *   IDLE if the state is still SCARED.
 * - The plate kit (start(), all removed in end()): ARMOR
 *   add_value +16.0 (id advancedmobs:scute_shield_armor;
 *   the design's armor line, iron-to-diamond tier on a
 *   0-armor base), ARMOR_TOUGHNESS add_value +6.0 (id
 *   advancedmobs:scute_shield_toughness), and
 *   KNOCKBACK_RESISTANCE add_value +1.0 (id
 *   advancedmobs:scute_shield_knockback; total immunity,
 *   the design's knockback_resistance line), plus
 *   MOVEMENT_SPEED add_multiplied_total -1.0 (id
 *   advancedmobs:scute_shield_planted; speed zeroed) with a
 *   navigation stop every tick, never NoAi. RESISTANCE at
 *   amplifier 0 for 320 ticks is applied at start() (the
 *   design's resistance effect) and removed in end().
 * - Blocked-hit feedback: the armadillo's health is polled
 *   every 2 ticks (the health at activation start is stored
 *   in the state tag as "last_hp"). A fresh drop, at most
 *   once per 10 ticks, is a blocked hit: 8 scute ITEM
 *   flecks and 4 POOF burst at the shell (the design's
 *   shedding flecks), ARMADILLO_HURT_REDUCED plays at
 *   volume 1.0, and the shrug answers the attacker: the
 *   vanilla last-hurt-by entity, when it is an enemy of the
 *   armadillo within 6 blocks, is shoved directly away with
 *   hurtMarked set and gains SLOWNESS at amplifier 0 for 40
 *   ticks. Small attackers glance off harmlessly (the
 *   design's line): an attacker whose MAX_HEALTH attribute
 *   value is at most 20.0 is bounced the full 0.7
 *   horizontally and 0.3 up, while a larger attacker is
 *   heavier on the shell and only shrugged 0.35 and 0.15.
 *   Either way the shield itself deals no damage to anyone,
 *   ever: the glance is pure displacement.
 * - Duration: 300 ticks (15 seconds), then the framework
 *   cooldown is the rest.
 *
 * WHAT IT LOOKS LIKE: an armadillo snapping into its shell
 * and sitting like a riveted dome, hits sparking scute
 * flecks off the plates with a dull clink, attackers
 * stumbling back off the shell after every swing.
 *
 * PERFORMANCE: one navigation stop and one health compare
 * per 2 ticks; the blocked-hit answer is one damage-source
 * free shove on an already-resolved reference, no entity
 * queries anywhere. Particle budget per activation: at
 * most 30 blocked hits times 12 = 360, realistically far
 * fewer.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply. The flecks are item particles, not dropped
 * items.
 *
 * State tag keys: "last_hp" (health at the last poll),
 * "last_block" (tick of the last blocked-hit answer).
 */
public final class ScuteShieldAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int BLOCK_COOLDOWN_TICKS = 10;
    private static final double SMALL_ATTACKER_MAX_HEALTH = 20.0;
    private static final Identifier ARMOR_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "scute_shield_armor");
    private static final Identifier TOUGHNESS_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "scute_shield_toughness");
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "scute_shield_knockback");
    private static final Identifier PLANTED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "scute_shield_planted");

    @Override
    public String id() {
        return "scute_shield";
    }

    @Override
    public String displayName() {
        return "Scute Shield";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        state.putFloat("last_hp", caster.getHealth());
        state.putInt("last_block", -BLOCK_COOLDOWN_TICKS);

        ArmadilloAbilitySupport.addModifier(caster, Attributes.ARMOR,
            new AttributeModifier(ARMOR_ID, 16.0, AttributeModifier.Operation.ADD_VALUE));
        ArmadilloAbilitySupport.addModifier(caster, Attributes.ARMOR_TOUGHNESS,
            new AttributeModifier(TOUGHNESS_ID, 6.0, AttributeModifier.Operation.ADD_VALUE));
        ArmadilloAbilitySupport.addModifier(caster, Attributes.KNOCKBACK_RESISTANCE,
            new AttributeModifier(KNOCKBACK_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        ArmadilloAbilitySupport.addModifier(caster, Attributes.MOVEMENT_SPEED,
            new AttributeModifier(PLANTED_ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        caster.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, DURATION_TICKS + 20, 0));

        if (caster instanceof Armadillo armadillo) {
            armadillo.switchToState(Armadillo.ArmadilloState.SCARED);
        }
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.sendParticles(ArmadilloAbilitySupport.SCUTE_FLECK,
                caster.getX(), caster.getY() + 0.4, caster.getZ(),
                8, 0.4, 0.3, 0.4, 0.08);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.getNavigation().stop();
        if (caster instanceof Armadillo armadillo
            && armadillo.getState() != Armadillo.ArmadilloState.SCARED) {
            armadillo.switchToState(Armadillo.ArmadilloState.SCARED);
        }

        int ticks = ctx.ticksActive();
        if (ticks % 2 != 0) {
            return;
        }
        CompoundTag state = ctx.state();
        float health = caster.getHealth();
        float lastHp = state.getFloatOr("last_hp", health);
        state.putFloat("last_hp", health);
        if (health >= lastHp || ticks - state.getIntOr("last_block", -BLOCK_COOLDOWN_TICKS) < BLOCK_COOLDOWN_TICKS) {
            return;
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        state.putInt("last_block", ticks);

        // The blocked hit: flecks off the shell and the clink.
        level.sendParticles(ArmadilloAbilitySupport.SCUTE_FLECK,
            caster.getX(), caster.getY() + 0.4, caster.getZ(),
            8, 0.45, 0.35, 0.45, 0.12);
        level.sendParticles(ParticleTypes.POOF,
            caster.getX(), caster.getY() + 0.4, caster.getZ(),
            4, 0.4, 0.3, 0.4, 0.05);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.ARMADILLO_HURT_REDUCED, SoundSource.HOSTILE, 1.0f, 1.0f);

        // The shrug: the attacker glances off the shell.
        LivingEntity attacker = caster.getLastHurtByMob();
        if (attacker != null && attacker.isAlive()
            && ArmadilloAbilitySupport.isEnemy(ctx, attacker)
            && caster.distanceToSqr(attacker) <= 36.0) {
            double awayX = attacker.getX() - caster.getX();
            double awayZ = attacker.getZ() - caster.getZ();
            double awayLength = Math.sqrt(awayX * awayX + awayZ * awayZ);
            if (awayLength < 1.0e-4) {
                awayX = 1.0;
                awayZ = 0.0;
                awayLength = 1.0;
            }
            boolean small = attacker.getAttributeValue(Attributes.MAX_HEALTH) <= SMALL_ATTACKER_MAX_HEALTH;
            double shove = small ? 0.7 : 0.35;
            double lift = small ? 0.3 : 0.15;
            attacker.setDeltaMovement(awayX / awayLength * shove, lift, awayZ / awayLength * shove);
            attacker.hurtMarked = true;
            attacker.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 0));
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ArmadilloAbilitySupport.removeModifier(caster, Attributes.ARMOR, ARMOR_ID);
        ArmadilloAbilitySupport.removeModifier(caster, Attributes.ARMOR_TOUGHNESS, TOUGHNESS_ID);
        ArmadilloAbilitySupport.removeModifier(caster, Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_ID);
        ArmadilloAbilitySupport.removeModifier(caster, Attributes.MOVEMENT_SPEED, PLANTED_ID);
        caster.removeEffect(MobEffects.RESISTANCE);
        if (caster instanceof Armadillo armadillo
            && armadillo.getState() == Armadillo.ArmadilloState.SCARED) {
            armadillo.switchToState(Armadillo.ArmadilloState.IDLE);
        }
    }
}
