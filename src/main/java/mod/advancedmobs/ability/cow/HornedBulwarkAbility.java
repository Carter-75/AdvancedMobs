package mod.advancedmobs.ability.cow;

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
import net.minecraft.world.phys.Vec3;

/**
 * Horned Bulwark (Cow ability 2 of 5, defense /
 * transformation).
 *
 * WHAT IT DOES: the cow swells to half again its size,
 * plants all four hooves, and turns into a wall with horns.
 * For the duration it is nearly impossible to move or hurt
 * seriously, and anything foolish enough to charge it gets
 * a horn in the ribs for its trouble: every fresh hit the
 * cow takes from a nearby enemy is answered with a counter
 * hit that knocks the attacker back.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the cow's current target within 10 blocks (distance
 *   squared 100).
 * - Duration: exactly 300 ticks (15 seconds) from start().
 * - The bulwark (start(), all removed in end()):
 *   SCALE add_multiplied_base +0.4 (id
 *   advancedmobs:bulwark_scale; the cow stands 1.4x its
 *   normal size), ARMOR add_value +10.0 (id
 *   advancedmobs:bulwark_armor), KNOCKBACK_RESISTANCE
 *   add_value +1.0 (id advancedmobs:bulwark_knockback;
 *   attribute totals clamp at 1.0, so the cow cannot be
 *   knocked back at all), MOVEMENT_SPEED
 *   add_multiplied_total -1.0 (id
 *   advancedmobs:bulwark_planted; multiplied to exactly 0,
 *   the planted feet), and RESISTANCE I for 320 ticks (the
 *   design's resistance line; end() removes it, so it
 *   never outlives the form).
 * - Holding the plant (every tick): navigation stopped and
 *   horizontal velocity zeroed (never NoAi: the cow's AI,
 *   and this ability with it, keeps ticking; the
 *   MOVEMENT_SPEED modifier above is what keeps vanilla
 *   goals from walking it anywhere between stops).
 * - The counter hit: the cow's last-hurt-by-mob timestamp
 *   is polled every tick (one integer compare; the baseline
 *   is recorded in start() so a wound from before the form
 *   never triggers it). When the timestamp changes, the
 *   attacker (getLastHurtByMob) is judged: alive, an enemy
 *   by the package rule, and within 5 blocks (distance
 *   squared 25). A qualifying attacker takes the counter:
 *   4.0 damage as a mob attack credited to the cow, plus a
 *   knockback directly away from the cow of 1.2 horizontal
 *   and 0.4 up, hurtMarked set. At most one counter per 10
 *   ticks (the last counter tick is kept in the state tag),
 *   so a fast attacker cannot machine-gun itself on the
 *   horns, and a hit from beyond 5 blocks (an arrow, a
 *   spell) is simply weathered with no counter.
 * - Sound: the cow's classic variant ambient sound plays
 *   at start at volume 1.5, pitch 0.7 (the low bellow of
 *   the plant); each counter plays PLAYER_ATTACK_SWEEP at
 *   volume 1.0, pitch 0.9.
 *
 * WHAT IT LOOKS LIKE: a cow suddenly half again as big,
 * hooves planted, dust settling around it; every enemy that
 * strikes it is met by a SWEEP_ATTACK arc flash at the
 * horns and goes tumbling backwards.
 *
 * PERFORMANCE: per tick one navigation stop, one velocity
 * write, and one integer timestamp compare; the counter
 * itself is one distance check against an already-resolved
 * reference plus one damage call, at most once per 10
 * ticks, never an entity query. Particle budget per
 * activation: at most 30 counters times 1 SWEEP_ATTACK =
 * 30 particles. The modifiers are built at start() and
 * removed exactly in end().
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "last_ts" (the last seen hurt-by-mob
 * timestamp), "last_counter" (tick of the last counter
 * hit).
 */
public final class HornedBulwarkAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int COUNTER_COOLDOWN_TICKS = 10;
    private static final double COUNTER_RANGE_SQR = 25.0; // 5 blocks
    private static final double COUNTER_DAMAGE = 4.0;
    private static final double COUNTER_HORIZONTAL = 1.2;
    private static final double COUNTER_VERTICAL = 0.4;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "bulwark_scale");
    private static final Identifier ARMOR_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "bulwark_armor");
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "bulwark_knockback");
    private static final Identifier PLANTED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "bulwark_planted");

    @Override
    public String id() {
        return "horned_bulwark";
    }

    @Override
    public String displayName() {
        return "Horned Bulwark";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 100.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        // Baseline the hurt clock so an old wound never counters.
        ctx.state().putInt("last_ts", caster.getLastHurtByMobTimestamp());
        ctx.state().putInt("last_counter", -COUNTER_COOLDOWN_TICKS);

        CowAbilitySupport.addModifier(caster, Attributes.SCALE,
            new AttributeModifier(SCALE_ID, 0.4, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        CowAbilitySupport.addModifier(caster, Attributes.ARMOR,
            new AttributeModifier(ARMOR_ID, 10.0, AttributeModifier.Operation.ADD_VALUE));
        CowAbilitySupport.addModifier(caster, Attributes.KNOCKBACK_RESISTANCE,
            new AttributeModifier(KNOCKBACK_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        CowAbilitySupport.addModifier(caster, Attributes.MOVEMENT_SPEED,
            new AttributeModifier(PLANTED_ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        caster.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 320, 0));

        ServerLevel level = ctx.serverLevel();
        if (level != null && CowAbilitySupport.cowSound() != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                CowAbilitySupport.cowSound(), SoundSource.HOSTILE, 1.5f, 0.7f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        // The plant: hold position through navigation and
        // velocity control, never NoAi.
        caster.getNavigation().stop();
        Vec3 velocity = caster.getDeltaMovement();
        if (velocity.x != 0.0 || velocity.z != 0.0) {
            caster.setDeltaMovement(0.0, velocity.y, 0.0);
            caster.hurtMarked = true;
        }

        int timestamp = caster.getLastHurtByMobTimestamp();
        if (timestamp == ctx.state().getIntOr("last_ts", 0)) {
            return; // Cheap early-out: no fresh hit this tick.
        }
        ctx.state().putInt("last_ts", timestamp);
        int ticks = ctx.ticksActive();
        if (ticks - ctx.state().getIntOr("last_counter", -COUNTER_COOLDOWN_TICKS)
            < COUNTER_COOLDOWN_TICKS) {
            return;
        }
        LivingEntity attacker = caster.getLastHurtByMob();
        if (attacker == null || !attacker.isAlive()
            || !CowAbilitySupport.isEnemy(ctx, attacker)
            || caster.distanceToSqr(attacker) > COUNTER_RANGE_SQR) {
            return;
        }
        counterHit(ctx, attacker);
        ctx.state().putInt("last_counter", ticks);
    }

    /** The counter hit: horn damage and the bounce off the bulwark. */
    private void counterHit(AbilityContext ctx, LivingEntity attacker) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        attacker.hurtServer(level, level.damageSources().mobAttack(caster), (float) COUNTER_DAMAGE);
        double dx = attacker.getX() - caster.getX();
        double dz = attacker.getZ() - caster.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.001) {
            Vec3 look = caster.getLookAngle();
            dx = look.x;
            dz = look.z;
            length = Math.sqrt(dx * dx + dz * dz);
            if (length < 0.001) {
                dx = 0.0;
                dz = 1.0;
                length = 1.0;
            }
        }
        attacker.setDeltaMovement(dx / length * COUNTER_HORIZONTAL, COUNTER_VERTICAL,
            dz / length * COUNTER_HORIZONTAL);
        attacker.hurtMarked = true;

        level.sendParticles(ParticleTypes.SWEEP_ATTACK,
            attacker.getX(), attacker.getY() + 1.0, attacker.getZ(),
            1, 0.2, 0.2, 0.2, 0.0);
        level.playSound(null, attacker.getX(), attacker.getY(), attacker.getZ(),
            SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 1.0f, 0.9f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CowAbilitySupport.removeModifier(caster, Attributes.SCALE, SCALE_ID);
        CowAbilitySupport.removeModifier(caster, Attributes.ARMOR, ARMOR_ID);
        CowAbilitySupport.removeModifier(caster, Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_ID);
        CowAbilitySupport.removeModifier(caster, Attributes.MOVEMENT_SPEED, PLANTED_ID);
        caster.removeEffect(MobEffects.RESISTANCE);
    }
}
