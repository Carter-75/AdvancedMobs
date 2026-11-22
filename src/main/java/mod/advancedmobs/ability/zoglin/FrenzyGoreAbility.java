package mod.advancedmobs.ability.zoglin;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
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
 * Frenzy Gore (Zoglin ability 4 of 5, attack).
 *
 * WHAT IT DOES: the zoglin throws itself into a frenzy of
 * rapid goring strikes, far faster than its bulk suggests,
 * SWEEP_ATTACK arcs flashing with every toss of its head. The
 * frenzy is aimed at one victim, the zoglin's current target,
 * and every gore that lands stacks WEAKNESS on that victim:
 * a zoglin left alone on one target grinds it down to
 * nothing, each hit making the next ones hurt more by
 * comparison. When the frenzy burns out the zoglin is winded
 * and slow for a few seconds.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the zoglin's current target within 4.5 blocks (distance
 *   squared 20.25). A frenzy is a point blank mauling; it
 *   never starts at range.
 * - Duration: exactly 160 ticks (8 seconds) from start().
 * - The gore cycle: every 8 ticks (20 cycles per activation)
 *   the zoglin gores its current target, when the target is
 *   alive and within 3.4 blocks (distance squared 11.56). A
 *   cycle with the target out of reach is simply skipped: no
 *   damage, no particles, no weakness. Each landed gore deals
 *   5.0 points of typed mob-attack damage credited to the
 *   zoglin (a partial hit next to its 6.0 base melee, landed
 *   two and a half times a second at full reach) and stacks
 *   WEAKNESS on the victim: the new application is amplifier
 *   min(3, current amplifier + 1), or amplifier 0 when the
 *   victim has none, for 120 ticks. A victim gored without
 *   pause climbs to WEAKNESS IV (amplifier 3) after four
 *   gores and stays there while the frenzy lasts, its own
 *   melee damage cut by 4 times 4 points per level the way
 *   vanilla weakness cuts it. The zoglin's ordinary melee
 *   attacks during the frenzy are untouched and land on top
 *   of the cycle.
 * - ATTACK_SPEED (the design's listed system): start()
 *   applies a transient ATTACK_SPEED add_value modifier of
 *   +4.0 (id advancedmobs:frenzy_gore_attack_speed, removed
 *   in end()). Documented honestly: a zoglin has no
 *   ATTACK_SPEED attribute instance in 26.2 (verified against
 *   the jar: neither its own supplier nor
 *   LivingEntity.createLivingAttributes carries ATTACK_SPEED,
 *   and a mob's melee cadence is goal-driven, not
 *   attribute-driven), so the helper applies it as a no-op
 *   and the visible rapid strikes are the gore cycle above,
 *   driven in code. If a future version grants the attribute,
 *   the modifier takes effect with no code change.
 * - Winded: when the frenzy ends, end() applies SLOWNESS
 *   amplifier 0 for 60 ticks (3 seconds) to the zoglin,
 *   applied in end() so the winded window outlives the frenzy
 *   itself (the Stubborn Hide Brace stagger precedent). If the
 *   zoglin dies mid-frenzy the framework termination path runs
 *   this same end() on a dead mob, where the slowness lands on
 *   a corpse and does nothing.
 *
 * WHAT IT LOOKS LIKE: a ZOGLIN_ANGRY snarl and an 8-particle
 * ANGRY_VILLAGER burst at start() as the frenzy takes it. Each
 * landed gore throws 6 SWEEP_ATTACK particles at the victim's
 * chest and plays ZOGLIN_ATTACK at volume 0.8 with a pitch
 * jittered between 0.9 and 1.1 from the random source: a
 * visible, audible flurry of head tosses. The victim shows
 * the vanilla WEAKNESS sparkles, deepening as the stacks
 * climb; the winded zoglin afterwards shows SLOWNESS
 * sparkles.
 *
 * PERFORMANCE: tick() returns immediately except every 8th
 * tick, which does one distance check against the already
 * resolved target and, on a landed gore, one damage call, one
 * effect read and apply, and one particle send. No entity
 * queries anywhere in this ability. Particle budget per
 * activation: 8 at start plus at most 20 gores times 6 = 120,
 * total at most 128 particles.
 *
 * CLEANUP: end() removes the ATTACK_SPEED modifier by id (a
 * no-op where the attribute does not exist) and applies the
 * winded SLOWNESS described above.
 *
 * State tag keys: none. The whole activation is a function of
 * ticksActive() and the target's live position and effects.
 */
public final class FrenzyGoreAbility implements MobAbility {

    static final int DURATION_TICKS = 160;
    private static final int GORE_INTERVAL = 8;
    private static final double GORE_RANGE_SQR = 3.4 * 3.4;
    private static final float GORE_DAMAGE = 5.0f;
    private static final int WEAKNESS_DURATION = 120;
    private static final int WEAKNESS_MAX_AMPLIFIER = 3;
    private static final Identifier ATTACK_SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "frenzy_gore_attack_speed");

    @Override
    public String id() {
        return "frenzy_gore";
    }

    @Override
    public String displayName() {
        return "Frenzy Gore";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 20.25;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        // The design's ATTACK_SPEED entry: applied through the
        // null-tolerant helper; a no-op on a 26.2 zoglin, see
        // the class comment for the honest accounting.
        ZoglinAbilitySupport.addModifier(mob, Attributes.ATTACK_SPEED,
            new AttributeModifier(ATTACK_SPEED_ID, 4.0, AttributeModifier.Operation.ADD_VALUE));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.ZOGLIN_ANGRY,
                SoundSource.HOSTILE, 1.0f, 1.1f);
            level.sendParticles(ParticleTypes.ANGRY_VILLAGER, mob.getX(), mob.getY() + 1.2, mob.getZ(),
                8, 0.7, 0.5, 0.7, 0.05);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % GORE_INTERVAL != 0) {
            return; // Cheap early-out: the gore cycle runs every 8 ticks only.
        }
        Mob mob = ctx.mob();
        LivingEntity target = ctx.target();
        if (target == null || !target.isAlive() || mob.distanceToSqr(target) > GORE_RANGE_SQR) {
            return; // Out of reach this cycle: the frenzy tosses at air.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }

        // The gore: a partial hit, and the weakness stack climbs.
        target.hurtServer(level, level.damageSources().mobAttack(mob), GORE_DAMAGE);
        MobEffectInstance current = target.getEffect(MobEffects.WEAKNESS);
        int amplifier = current != null
            ? Math.min(WEAKNESS_MAX_AMPLIFIER, current.getAmplifier() + 1)
            : 0;
        target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, WEAKNESS_DURATION, amplifier));

        // The flash of the head toss.
        level.sendParticles(ParticleTypes.SWEEP_ATTACK,
            target.getX(), target.getY() + target.getBbHeight() * 0.6, target.getZ(),
            6, 0.4, 0.3, 0.4, 0.05);
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.ZOGLIN_ATTACK,
            SoundSource.HOSTILE, 0.8f, 0.9f + ctx.random().nextFloat() * 0.2f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        ZoglinAbilitySupport.removeModifier(mob, Attributes.ATTACK_SPEED, ATTACK_SPEED_ID);
        // Winded: slow for 3 seconds after the frenzy, applied
        // here so it outlives the ability itself.
        mob.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 0));
    }
}
