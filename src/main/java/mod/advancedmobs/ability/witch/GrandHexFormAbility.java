package mod.advancedmobs.ability.witch;

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
 * Grand Hex Form (Witch ability 3 of 5, transformation).
 *
 * WHAT IT DOES: the witch drinks something she should not
 * have. She grows tall and terrible (SCALE 1.5x), GLOWING with
 * an outline, WITCH particles pouring off her hat and sleeves,
 * and for the form's 20 seconds her throwing arm becomes a
 * blur: on top of her vanilla potion tossing she hurls an
 * extra code-thrown splash potion every 2.5 seconds, and she
 * stops kiting, wading in to hold mid range (4 to 8 blocks)
 * where her splashes cannot miss by much. When the form burns
 * out she collapses into a coughing fit of purple smoke and is
 * SLOWED briefly.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the witch's current target within 18 blocks (distance
 *   squared 324).
 * - Duration: exactly 400 ticks (20 seconds) from start().
 * - The growth (start()): SCALE add_multiplied_base +0.5
 *   (modifier id advancedmobs:grand_hex_scale, 1.5x size) and
 *   GLOWING for 420 ticks (20 ticks past the form, so the
 *   outline never flickers before end() removes it). Both are
 *   removed in end(). The GLOWING outline's color is the
 *   vanilla team-based one (white with no team); the design's
 *   violet is carried by the constant WITCH particle pour,
 *   the documented approximation (the Mirror Ghasts shimmer
 *   precedent: no client render hook exists in this
 *   framework).
 * - The arm (design's ATTACK_SPEED and collapsed cooldowns):
 *   an ATTACK_SPEED add_value +4.0 modifier (id
 *   advancedmobs:grand_hex_arm) is applied through the
 *   null-tolerant helper. Verified against the 26.2 jar: a
 *   witch has no ATTACK_SPEED attribute instance (it is not
 *   in createLivingAttributes and the witch's own supplier
 *   does not add it), and a witch's throwing cadence is
 *   goal-driven, so the modifier is inert today and takes
 *   effect with no code change if a future version grants
 *   the attribute (the Frenzy Gore precedent). The real
 *   collapsed cooldown is carried in code: every 50 ticks the
 *   witch throws one extra real splash potion at her target,
 *   payload rolled fresh from the debuff table, from hand
 *   height (eye height), speed 1.6, inaccuracy 1.0, with a
 *   WITCH_THROW sound: 8 bonus bottles over the form, on top
 *   of her vanilla throws. The design's grown splash radius
 *   is the vanilla splash radius of those real bottles; no
 *   code fakes a bigger one.
 * - The wade (every 10 ticks): if the target is alive and
 *   farther than 8 blocks, the witch's navigation is driven
 *   to the target at speed 1.2; if it is closer than 4
 *   blocks, navigation stops (she holds mid range instead of
 *   retreating, the opposite of her vanilla kiting). Between
 *   4 and 8 blocks nothing is steered. Navigation only, never
 *   NoAi.
 * - The pour: every 2 ticks, 2 WITCH particles drift off the
 *   witch's body (spread by her grown bounding box).
 * - end(): the SCALE and ATTACK_SPEED modifiers are removed
 *   by id, GLOWING is removed, and the burnout lands: 16
 *   WITCH particles burst, a low WITCH_AMBIENT cough (pitch
 *   0.7), and SLOWNESS I for 40 ticks applied in end() so it
 *   outlives the form (the Colossal Grief vulnerability
 *   precedent). If the witch owns a permanent slowness-free
 *   state from upgrades there is nothing to conflict with;
 *   the burnout slowness is a plain timed effect.
 *
 * WHAT IT LOOKS LIKE: the witch swells half again as tall,
 * outlined and smoking violet from hat and sleeves, striding
 * INTO the fight instead of away from it, bottles leaving her
 * hand twice as often, then the coughing collapse as the
 * smoke bursts and she shrinks back down.
 *
 * PERFORMANCE: per tick the cost is two modulo checks. The
 * pour runs every 2 ticks (2 particles), the wade check every
 * 10 ticks (distance reads and at most one navigation call),
 * the bonus throw every 50 ticks (one projectile). No entity
 * queries anywhere. Particle budget per activation: pour 200
 * pulses times 2 = 400, the drink burst 12, the burnout 16,
 * total at most 428 particles.
 *
 * TERRAIN: this ability places and breaks no blocks, so the
 * mobGriefing gate does not apply.
 *
 * State tag keys: none of its own. The activation is fully
 * described by the framework tick counter, the two modifier
 * ids, and the GLOWING instance.
 */
public final class GrandHexFormAbility implements MobAbility {

    static final int DURATION_TICKS = 400;
    private static final int THROW_INTERVAL = 50;
    private static final int WADE_INTERVAL = 10;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "grand_hex_scale");
    private static final Identifier ARM_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "grand_hex_arm");

    @Override
    public String id() {
        return "grand_hex_form";
    }

    @Override
    public String displayName() {
        return "Grand Hex Form";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 324.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob witch = ctx.mob();
        // Tall and terrible: the growth and the outline.
        WitchAbilitySupport.addModifier(witch, Attributes.SCALE,
            new AttributeModifier(SCALE_ID, 0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        // The blur arm: inert on a 26.2 witch (see the class
        // comment), applied so the design's attribute is on
        // record and live the moment the attribute exists.
        WitchAbilitySupport.addModifier(witch, Attributes.ATTACK_SPEED,
            new AttributeModifier(ARM_ID, 4.0, AttributeModifier.Operation.ADD_VALUE));
        witch.addEffect(new MobEffectInstance(MobEffects.GLOWING, DURATION_TICKS + 20, 0));
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            // She drinks: a gulp and a first burst of smoke.
            level.playSound(null, witch.getX(), witch.getY(), witch.getZ(),
                SoundEvents.WITCH_DRINK, SoundSource.HOSTILE, 1.0f, 0.8f);
            level.sendParticles(ParticleTypes.WITCH, witch.getX(), witch.getY() + 1.4, witch.getZ(),
                12, 0.5, 0.7, 0.5, 0.05);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        Mob witch = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }

        // The pour: WITCH motes off hat and sleeves.
        if (ticks % 2 == 0) {
            double halfWidth = witch.getBbWidth() / 2.0;
            level.sendParticles(ParticleTypes.WITCH, witch.getX(), witch.getY() + witch.getBbHeight() * 0.7,
                witch.getZ(), 2, halfWidth, 0.5, halfWidth, 0.03);
        }

        // The wade: hold mid range, never kite.
        if (ticks % WADE_INTERVAL == 0) {
            LivingEntity target = ctx.target();
            if (target != null) {
                double distance = witch.distanceTo(target);
                if (distance > 8.0) {
                    witch.getNavigation().moveTo(target, 1.2);
                } else if (distance < 4.0) {
                    witch.getNavigation().stop();
                }
            }
        }

        // The blur arm's real teeth: a bonus bottle.
        if (ticks % THROW_INTERVAL == 0) {
            LivingEntity target = ctx.target();
            if (target != null) {
                Vec3 hand = new Vec3(witch.getX(), witch.getEyeY(), witch.getZ());
                Vec3 aim = new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ());
                int roll = WitchAbilitySupport.rollDebuff(ctx);
                WitchAbilitySupport.throwSplash(ctx, level, hand, aim,
                    WitchAbilitySupport.splashStack(roll), 1.6f, 1.0f);
                level.playSound(null, witch.getX(), witch.getY(), witch.getZ(),
                    SoundEvents.WITCH_THROW, SoundSource.HOSTILE, 0.8f, 1.1f);
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob witch = ctx.mob();
        WitchAbilitySupport.removeModifier(witch, Attributes.SCALE, SCALE_ID);
        WitchAbilitySupport.removeModifier(witch, Attributes.ATTACK_SPEED, ARM_ID);
        witch.removeEffect(MobEffects.GLOWING);
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            // The burnout: a coughing fit of purple smoke.
            level.sendParticles(ParticleTypes.WITCH, witch.getX(), witch.getY() + 1.0, witch.getZ(),
                16, 0.5, 0.6, 0.5, 0.06);
            level.playSound(null, witch.getX(), witch.getY(), witch.getZ(),
                SoundEvents.WITCH_AMBIENT, SoundSource.HOSTILE, 0.9f, 0.7f);
        }
        // The collapse slows her, applied here so it outlives
        // the form.
        witch.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 0));
    }
}
