package mod.advancedmobs.ability.bee;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Honey Coat (Bee ability 4 of 5, defense).
 *
 * WHAT IT DOES: the bee coats itself in a glossy layer of
 * honey, shown as a honey block display riding with it and
 * honey drips falling as it flies. The coat absorbs the
 * next few hits, makes the bee resistant to knockback, and
 * attackers who strike it get briefly stuck with slowness
 * from the sticky mess.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the bee's current target within 12 blocks (distance
 *   squared 144).
 * - Duration: at most 300 ticks (15 seconds); the ability
 *   ends early when the coat's 4 charges are spent.
 * - The coat (start()): ABSORPTION II (amplifier 1, 8
 *   points of absorption) for 320 ticks on the bee, a
 *   transient KNOCKBACK_RESISTANCE modifier of +0.6
 *   ADD_VALUE under a fixed id, and one honey block DISPLAY
 *   (0.7 scale, full brightness) created through the
 *   package's NBT display factory and added to the level.
 *   The display is a pure visual, not a block in the world.
 *   The bee's current last-hurt-by timestamp is recorded so
 *   a hit from before the cast never counts as a coat hit.
 * - Riding (tick(), every tick): the display is snapped to
 *   the bee's position (its transformation centers the 0.7
 *   cube on the snap point, and its 2 tick teleport duration
 *   glides it), so the coat visibly rides the bee as it
 *   flies. One DRIPPING_HONEY particle falls at the bee
 *   every 4 ticks.
 * - The sticky answer (tick(), every tick): the bee's
 *   last-hurt-by timestamp is compared against the recorded
 *   one (an integer compare, the Rod Aegis pattern). A fresh
 *   timestamp means a new hit landed: one coat charge is
 *   spent (4 down to 0), and the attacker, when it resolves
 *   and is an enemy, gains SLOWNESS II (amplifier 1) for 60
 *   ticks, with 8 LANDING_HONEY particles bursting at it and
 *   a BEEHIVE_DRIP sound (volume 0.8, pitch 1.0). Hits whose
 *   attacker does not resolve still spend the charge: the
 *   coat took the hit either way. Absorption itself is the
 *   vanilla ABSORPTION effect doing its own work; the
 *   charges only track how many hits the sticky answer can
 *   still punish, and the ability ends when they run out.
 * - end(): the ABSORPTION effect is removed from the bee
 *   (a bee that somehow had absorption from another source
 *   loses it too, an accepted edge case shared with every
 *   fixed-duration effect in this system), the knockback
 *   modifier is removed, and the display is discarded. If
 *   the caster dies first, the framework termination path
 *   runs this same end(), so the coat never outlives the
 *   bee.
 *
 * WHAT IT LOOKS LIKE: a bee flying inside a glossy
 * translucent cube of honey, drips falling off it as it
 * moves, its health bar wearing a row of golden absorption
 * hearts; every attacker that connects gets splashed with
 * landing honey and drags for a few seconds.
 *
 * PERFORMANCE: per tick the cost is one display snap, one
 * integer timestamp compare, and a particle every 4 ticks;
 * the sticky answer runs at most once per new hit. No
 * entity queries anywhere. Particle budget per activation:
 * drips at most 75, answers at most 4 times 8 = 32, total
 * at most 107 particles.
 *
 * TERRAIN: this ability changes no blocks; the honey block
 * is a display entity, so the mobGriefing gate does not
 * apply.
 *
 * State tag keys: "charges" (coat charges left), "hurt_ts"
 * (the last seen hurt-by timestamp), "display" (the honey
 * display's UUID).
 */
public final class HoneyCoatAbility implements MobAbility {

    private static final int MAX_TICKS = 300;
    private static final int COAT_CHARGES = 4;
    private static final Identifier GRIP_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "honey_coat_grip");

    @Override
    public String id() {
        return "honey_coat";
    }

    @Override
    public String displayName() {
        return "Honey Coat";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 320, 1));
        BeeAbilitySupport.addModifier(caster, Attributes.KNOCKBACK_RESISTANCE,
            new AttributeModifier(GRIP_ID, 0.6, AttributeModifier.Operation.ADD_VALUE));
        ctx.state().putInt("charges", COAT_CHARGES);
        ctx.state().putInt("hurt_ts", caster.getLastHurtByMobTimestamp());

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Entity display = BeeAbilitySupport.createHoneyDisplay(level);
        if (display != null) {
            display.snapTo(caster.getX(), caster.getY() + 0.3, caster.getZ(), 0.0f, 0.0f);
            if (level.addFreshEntity(display)) {
                ctx.state().store("display", UUIDUtil.CODEC, display.getUUID());
            }
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        int ticks = ctx.ticksActive();

        // The coat rides the bee, and drips as it flies.
        Entity display = resolveDisplay(ctx, level);
        if (display != null) {
            display.snapTo(caster.getX(), caster.getY() + 0.3, caster.getZ(), 0.0f, 0.0f);
        }
        if (ticks % 4 == 0) {
            level.sendParticles(ParticleTypes.DRIPPING_HONEY, caster.getX(), caster.getY(), caster.getZ(),
                1, 0.25, 0.1, 0.25, 0.02);
        }

        // The sticky answer: a fresh hit spends a charge and
        // sticks the attacker (see the class comment).
        int timestamp = caster.getLastHurtByMobTimestamp();
        if (timestamp != ctx.state().getIntOr("hurt_ts", -1)) {
            ctx.state().putInt("hurt_ts", timestamp);
            int charges = ctx.state().getIntOr("charges", 0);
            if (charges > 0) {
                ctx.state().putInt("charges", charges - 1);
                LivingEntity attacker = caster.getLastHurtByMob();
                if (attacker != null && BeeAbilitySupport.isEnemy(ctx, attacker)) {
                    attacker.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 1));
                    level.sendParticles(ParticleTypes.LANDING_HONEY,
                        attacker.getX(), attacker.getY() + 0.5, attacker.getZ(),
                        8, 0.4, 0.4, 0.4, 0.05);
                    level.playSound(null, attacker.getX(), attacker.getY(), attacker.getZ(),
                        SoundEvents.BEEHIVE_DRIP, SoundSource.HOSTILE, 0.8f, 1.0f);
                }
            }
        }
    }

    /** Resolves the honey display from the state tag, or null when gone. */
    private static Entity resolveDisplay(AbilityContext ctx, ServerLevel level) {
        if (!ctx.state().contains("display")) {
            return null;
        }
        return ctx.state().read("display", UUIDUtil.CODEC)
            .map(id -> level.getEntity(id)).orElse(null);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.state().getIntOr("charges", COAT_CHARGES) <= 0 || ctx.ticksActive() >= MAX_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.removeEffect(MobEffects.ABSORPTION);
        BeeAbilitySupport.removeModifier(caster, Attributes.KNOCKBACK_RESISTANCE, GRIP_ID);
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Entity display = resolveDisplay(ctx, level);
        if (display != null) {
            display.discard();
        }
    }
}
