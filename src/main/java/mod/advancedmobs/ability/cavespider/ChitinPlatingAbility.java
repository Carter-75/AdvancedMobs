package mod.advancedmobs.ability.cavespider;

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
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Chitin Plating (Cave Spider ability 3 of 5, defense).
 *
 * WHAT IT DOES: the cave spider hunkers down and its
 * exoskeleton thickens into dark plating, SCRAPE sparks
 * flying as it grinds its legs against the stone to settle
 * the armor in. It shrinks slightly, presenting a smaller
 * target, while its ARMOR_TOUGHNESS climbs and blows that
 * used to crush it start glancing off, backed by RESISTANCE.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the spider's current target within 10 blocks (distance
 *   squared 100). The plating is settled for a fight already
 *   on top of the spider.
 * - The settle (start()): two transient attribute modifiers,
 *   both removed in end(): ARMOR_TOUGHNESS +8.0 add_value
 *   (Identifier advancedmobs:chitin_toughness; toughness is
 *   the attribute that keeps heavy hits from punching through
 *   armor) and SCALE -0.25 add_value (Identifier
 *   advancedmobs:chitin_scale; the spider renders and
 *   collides at 0.75 of its normal size). RESISTANCE
 *   (amplifier 0) is applied for 205 ticks, a timed effect
 *   that expires on its own just past the duration. 16
 *   SCRAPE sparks fly at the spider and a GRINDSTONE_USE
 *   grind plays at volume 0.7, pitch 1.1: the legs settling
 *   the plates against the stone.
 * - The wear-in (every 20 ticks for 200 ticks): 2 more
 *   SCRAPE sparks at the spider's feet, the plates still
 *   shifting and grinding as it moves.
 * - Duration: 200 ticks (10 seconds). Nothing about the
 *   plating is permanent: end() strips both modifiers and
 *   the spider returns to its normal size and toughness.
 *
 * WHAT IT LOOKS LIKE: the spider drops low, sparks scrape
 * off its legs in a ring, and it visibly compacts, darker
 * and smaller, shrugging off hits that would have folded it,
 * with the odd fresh spark as the plates grind home.
 *
 * PERFORMANCE: the only per-tick cost is the interval check;
 * the spark pulse runs every 20 ticks with no entity query
 * and no block query. The modifiers and the effect are
 * resolved once at start(). Particle budget per activation:
 * opening 16, wear-in 9 pulses times 2 = 18, total at most
 * 34 particles.
 *
 * CLEANUP: end() removes the ARMOR_TOUGHNESS and SCALE
 * modifiers by id. The RESISTANCE effect is timed and
 * expires on its own. Runs identically on a natural finish
 * and on framework termination.
 *
 * State tag keys: none. The whole activation reads live
 * attribute state and the tick counter.
 */
public final class ChitinPlatingAbility implements MobAbility {

    static final int DURATION_TICKS = 200;
    private static final int SPARK_INTERVAL = 20;
    private static final Identifier TOUGHNESS_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "chitin_toughness");
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "chitin_scale");

    @Override
    public String id() {
        return "chitin_plating";
    }

    @Override
    public String displayName() {
        return "Chitin Plating";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 100.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();

        // The plates: tougher against heavy blows, and a
        // smaller target while they hold. Both transient,
        // removed in end().
        AttributeInstance toughness = mob.getAttribute(Attributes.ARMOR_TOUGHNESS);
        if (toughness != null) {
            toughness.addOrUpdateTransientModifier(
                new AttributeModifier(TOUGHNESS_ID, 8.0, AttributeModifier.Operation.ADD_VALUE));
        }
        AttributeInstance scale = mob.getAttribute(Attributes.SCALE);
        if (scale != null) {
            scale.addOrUpdateTransientModifier(
                new AttributeModifier(SCALE_ID, -0.25, AttributeModifier.Operation.ADD_VALUE));
        }
        mob.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, DURATION_TICKS + 5, 0));

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        level.sendParticles(ParticleTypes.SCRAPE, mob.getX(), mob.getY() + 0.3, mob.getZ(),
            16, 0.5, 0.3, 0.5, 0.1);
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
            SoundEvents.GRINDSTONE_USE, SoundSource.HOSTILE, 0.7f, 1.1f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % SPARK_INTERVAL != 0) {
            return; // Cheap early-out: the wear-in sparks run every 20 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        level.sendParticles(ParticleTypes.SCRAPE, mob.getX(), mob.getY() + 0.2, mob.getZ(),
            2, 0.4, 0.2, 0.4, 0.08);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        AttributeInstance toughness = mob.getAttribute(Attributes.ARMOR_TOUGHNESS);
        if (toughness != null) {
            toughness.removeModifier(TOUGHNESS_ID);
        }
        AttributeInstance scale = mob.getAttribute(Attributes.SCALE);
        if (scale != null) {
            scale.removeModifier(SCALE_ID);
        }
        // The RESISTANCE effect is timed and expires on its own.
    }
}
