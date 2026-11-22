package mod.advancedmobs.ability.irongolem;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.BlockParticleOption;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Catapult Arms (Iron Golem ability 1 of 5, attack).
 *
 * WHAT IT DOES: the golem scoops its target up in both arms
 * and hurls it straight into the sky, vines cracking off its
 * frame as it throws. The victim rockets up, hangs at the
 * top of the arc for a heartbeat on a pulse of levitation,
 * then plummets; a fall damage curse rides it down, so the
 * landing deals double fall damage. The golem does not watch
 * the fall: the moment the victim leaves its hands it picks
 * the nearest other enemy in range and walks toward it.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the golem on the ground with its target within 4.5 blocks
 *   (distance squared 20.25, inside arm reach). The victim is
 *   the golem's current target. A vanilla iron golem's base
 *   numbers, for scale: 100 health, 15 attack damage, 0.25
 *   movement speed, knockback resistance 1.0 (verified
 *   against the 26.2 jar).
 * - The throw (start(), all at once): the victim's UUID is
 *   recorded in the state tag. The victim gains a transient
 *   FALL_DAMAGE_MULTIPLIER modifier, add_multiplied_total
 *   +1.0 (fall damage doubled), id
 *   advancedmobs:catapult_arms_fall, and LEVITATION
 *   amplifier 0 for 14 ticks (the design's levitation pulse:
 *   it stretches the rise and creates the hang at the top).
 *   Its velocity is set to straight up 1.30 blocks per tick
 *   (horizontal zeroed): combined with the levitation pulse
 *   the victim rises about 10 to 12 blocks before gravity
 *   takes it. No direct damage is dealt by the throw itself;
 *   the whole payload is the doubled fall (about 14 to 18
 *   damage from that height, armor permitting).
 * - Retarget (also start()): one bounded query (radius 16)
 *   finds the nearest other enemy; if one exists, the
 *   golem's vanilla target is set to it, so the golem is
 *   already walking toward the next enemy while the victim
 *   is still airborne. If none exists the golem keeps its
 *   current target.
 * - Linger (ticks 1 to 130): every 5 ticks, once the victim
 *   is back on the ground, its fall multiplier is removed
 *   and the ability finishes (the curse lasts exactly until
 *   the landing it was meant to multiply, plus up to 5 ticks
 *   of poll slack; the Seismic Stomp pattern). If the victim
 *   dies or unloads first, the ability finishes at the next
 *   poll; a transient modifier dies with its entity anyway.
 *   Hard finish at tick 130 even if the victim never lands
 *   (thrown into water, caught on a ledge mid-air, and so
 *   on); end() removes the multiplier if it is still out.
 *
 * WHAT IT LOOKS LIKE: IRON_GOLEM_ATTACK at volume 1.2,
 * pitch 0.9 on the throw, and 16 BLOCK_CRUMBLE particles of
 * mossy cobblestone bursting at the golem's chest height as
 * the vines crack off its frame, then the victim's silhouette
 * shrinking straight up, pausing, and dropping.
 *
 * PERFORMANCE: start() is one bounded entity query (radius
 * 16, only for the retarget), one modifier write, one effect
 * write, one velocity write, and 16 particles. tick() does
 * nothing except every 5th tick, where it resolves one UUID
 * and reads onGround. Particle budget per activation: 16.
 *
 * State tag keys: "victim" (the thrown victim's UUID,
 * stored with UUIDUtil.CODEC).
 */
public final class CatapultArmsAbility implements MobAbility {

    private static final int MAX_TICKS = 130;
    private static final Identifier FALL_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "catapult_arms_fall");
    private static final BlockParticleOption VINE_CRUMBLE =
        new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, Blocks.MOSSY_COBBLESTONE.defaultBlockState());

    @Override
    public String id() {
        return "catapult_arms";
    }

    @Override
    public String displayName() {
        return "Catapult Arms";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().onGround()
            && ctx.mob().distanceToSqr(target) <= 20.25;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        LivingEntity victim = ctx.target();
        if (victim == null) {
            return;
        }
        ctx.state().store("victim", UUIDUtil.CODEC, victim.getUUID());

        // The curse rides the victim down; the throw itself
        // deals no direct damage.
        IronGolemAbilitySupport.addModifier(victim, Attributes.FALL_DAMAGE_MULTIPLIER,
            new AttributeModifier(FALL_ID, 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        victim.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 14, 0));
        victim.setDeltaMovement(0.0, 1.30, 0.0);
        victim.hurtMarked = true;

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.IRON_GOLEM_ATTACK, SoundSource.HOSTILE, 1.2f, 0.9f);
            level.sendParticles(VINE_CRUMBLE, mob.getX(), mob.getY() + 1.8, mob.getZ(),
                16, 0.7, 0.6, 0.7, 0.12);
        }

        // The golem does not watch the fall: it picks the
        // nearest other enemy and walks toward it.
        List<LivingEntity> others = IronGolemAbilitySupport.enemiesInBox(
            ctx, mob.getX(), mob.getY(), mob.getZ(), 16.0, 3.0, 3.0);
        LivingEntity next = null;
        double nextSqr = Double.MAX_VALUE;
        for (LivingEntity other : others) {
            if (other == victim) {
                continue;
            }
            double distSqr = mob.distanceToSqr(other);
            if (distSqr < nextSqr) {
                nextSqr = distSqr;
                next = other;
            }
        }
        if (next != null) {
            mob.setTarget(next);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % 5 != 0) {
            return; // Cheap early-out: the landing poll runs every 5 ticks only.
        }
        LivingEntity victim = resolveVictim(ctx);
        if (victim == null || !victim.isAlive() || victim.onGround()) {
            // Landed (or gone): the curse has done its work.
            if (victim != null) {
                IronGolemAbilitySupport.removeModifier(victim, Attributes.FALL_DAMAGE_MULTIPLIER, FALL_ID);
            }
            ctx.state().putBoolean("landed", true);
        }
    }

    /** Resolves the recorded victim against the level, or null when gone. */
    private LivingEntity resolveVictim(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null || !ctx.state().contains("victim")) {
            return null;
        }
        UUID id = ctx.state().read("victim", UUIDUtil.CODEC).orElse(null);
        return id != null && level.getEntity(id) instanceof LivingEntity living ? living : null;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= MAX_TICKS || ctx.state().getBooleanOr("landed", false);
    }

    @Override
    public void end(AbilityContext ctx) {
        // Remove the fall multiplier if the victim never
        // landed inside the linger window.
        LivingEntity victim = resolveVictim(ctx);
        if (victim != null) {
            IronGolemAbilitySupport.removeModifier(victim, Attributes.FALL_DAMAGE_MULTIPLIER, FALL_ID);
        }
    }
}
