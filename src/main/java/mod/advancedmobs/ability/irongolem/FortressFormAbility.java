package mod.advancedmobs.ability.irongolem;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.BlockParticleOption;
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
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * Fortress Form (Iron Golem ability 3 of 5, transformation).
 *
 * WHAT IT DOES: the golem grows broader and heavier, moss
 * and vines thickening over its shoulders while dust trickles
 * off its frame. In fortress form it is nearly immovable,
 * hugely armored, and its arms sweep so wide that every
 * swing catches the enemies standing around the one it hit.
 * When the duration runs out it settles back to its normal
 * frame with all bonuses removed.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the golem's current target within 16 blocks (distance
 *   squared 256). A vanilla iron golem's base numbers, for
 *   scale: 100 health, 15 attack damage, 0.25 movement
 *   speed, knockback resistance 1.0 (verified against the
 *   26.2 jar).
 * - Duration: exactly 400 ticks (20 seconds) from start().
 * - Attribute changes, all transient modifiers added in
 *   start() and removed in end(), ids under
 *   advancedmobs:fortress_form_*:
 *     SCALE                  add_multiplied_base  +0.30 (1.3x broader
 *                                                        and taller)
 *     ARMOR                  add_value            +20.0 (a second full
 *                                                        armor set's worth)
 *     KNOCKBACK_RESISTANCE   add_value            +0.5  (the golem's
 *                                                        vanilla base is
 *                                                        already 1.0, the
 *                                                        total, so this
 *                                                        clamps and simply
 *                                                        guarantees the
 *                                                        form stays
 *                                                        immovable even
 *                                                        if something
 *                                                        else lowered it)
 *     SWEEPING_DAMAGE_RATIO  add_value            +1.0  (the design's
 *                                                        sweeping line;
 *                                                        vanilla sweeping
 *                                                        only fires for
 *                                                        players, so the
 *                                                        code sweep below
 *                                                        is what actually
 *                                                        delivers it)
 *   All four go through the null-tolerant package helper.
 * - RESISTANCE I for 420 ticks is applied in start() and
 *   removed in end() (it is the ability's own effect; the
 *   Living Mountain precedent).
 * - The wide sweep (tick(), every tick is one integer
 *   compare): the golem's last-hurt-mob timestamp is polled;
 *   when it changes, the golem just landed a strike. Every
 *   OTHER enemy within a radius 2.8 box of the victim (2
 *   below to 2 above) takes half the golem's live
 *   ATTACK_DAMAGE as a mob attack credited to the golem,
 *   with one SWEEP_ATTACK particle drawn at the victim. The
 *   timestamp is baselined in start(), so a strike landed
 *   before the form began never sweeps.
 * - No state tag entries beyond the baseline timestamp: the
 *   rest of the activation is the four modifiers, the
 *   effect, and the tick counter, so a resumed activation
 *   after a chunk reload behaves identically.
 *
 * WHAT IT LOOKS LIKE: the model and hitbox grow to 1.3x via
 * the SCALE attribute. FALLING_DUST trickles off the
 * shoulders: 2 particles at shoulder height spread across
 * the body width every 4 ticks for the whole form, moving
 * or not (a fortress sheds dust standing still). The crack
 * lines and stony tint of the design are carried by this
 * dust and the vanilla crackiness the golem shows as it
 * takes damage; there is no server-side retexture hook in
 * 26.2. Each code sweep draws its SWEEP_ATTACK arc, and
 * IRON_GOLEM_ATTACK plays at volume 0.8, pitch 0.7 on a
 * sweep that catches at least one extra enemy.
 *
 * PERFORMANCE: tick() is one integer compare per tick; the
 * sweep runs one bounded query (radius 2.8) only on a
 * landed-strike tick, and the dust pulse is 2 particles
 * every 4 ticks. Particle budget per activation: at most
 * 100 pulses times 2 dust, plus 1 per sweep, total at most
 * about 220 particles.
 *
 * State tag keys: "struck_ts" (the golem's last-hurt-mob
 * timestamp baseline).
 */
public final class FortressFormAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "fortress_form_scale");
    private static final Identifier ARMOR_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "fortress_form_armor");
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "fortress_form_knockback");
    private static final Identifier SWEEP_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "fortress_form_sweep");
    private static final BlockParticleOption STONE_DUST =
        new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.STONE.defaultBlockState());

    @Override
    public String id() {
        return "fortress_form";
    }

    @Override
    public String displayName() {
        return "Fortress Form";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return ctx.target() != null && ctx.mob().distanceToSqr(ctx.target()) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        IronGolemAbilitySupport.addModifier(mob, Attributes.SCALE,
            new AttributeModifier(SCALE_ID, 0.30, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        IronGolemAbilitySupport.addModifier(mob, Attributes.ARMOR,
            new AttributeModifier(ARMOR_ID, 20.0, AttributeModifier.Operation.ADD_VALUE));
        IronGolemAbilitySupport.addModifier(mob, Attributes.KNOCKBACK_RESISTANCE,
            new AttributeModifier(KNOCKBACK_ID, 0.5, AttributeModifier.Operation.ADD_VALUE));
        IronGolemAbilitySupport.addModifier(mob, Attributes.SWEEPING_DAMAGE_RATIO,
            new AttributeModifier(SWEEP_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        mob.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, DURATION_TICKS + 20, 0));
        ctx.state().putInt("struck_ts", mob.getLastHurtMobTimestamp());
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob mob = ctx.mob();
        int ticks = ctx.ticksActive();

        // The wide sweep: one integer compare per tick, work
        // only on a landed-strike tick.
        int struckTs = mob.getLastHurtMobTimestamp();
        if (struckTs != ctx.state().getIntOr("struck_ts", 0)) {
            ctx.state().putInt("struck_ts", struckTs);
            runSweep(ctx);
        }

        if (ticks % 4 != 0) {
            return; // The dust trickle runs every 4 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        double spread = mob.getBbWidth() * 0.5;
        level.sendParticles(STONE_DUST,
            mob.getX(), mob.getY() + mob.getBbHeight() * 0.85, mob.getZ(),
            2, spread, 0.3, spread, 0.02);
    }

    /** Catches every other enemy around the golem's latest victim for half damage. */
    private void runSweep(AbilityContext ctx) {
        Mob mob = ctx.mob();
        LivingEntity victim = mob.getLastHurtMob();
        if (victim == null || !victim.isAlive()) {
            return;
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        List<LivingEntity> caught = IronGolemAbilitySupport.enemiesInBox(
            ctx, victim.getX(), victim.getY(), victim.getZ(), 2.8, 2.0, 2.0);
        float damage = (float) (mob.getAttributeValue(Attributes.ATTACK_DAMAGE) * 0.5);
        boolean any = false;
        for (LivingEntity enemy : caught) {
            if (enemy == victim) {
                continue; // The victim already took the real swing.
            }
            enemy.hurtServer(level, level.damageSources().mobAttack(mob), damage);
            any = true;
        }
        if (any) {
            level.sendParticles(ParticleTypes.SWEEP_ATTACK,
                victim.getX(), victim.getY() + 1.0, victim.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.IRON_GOLEM_ATTACK, SoundSource.HOSTILE, 0.8f, 0.7f);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        IronGolemAbilitySupport.removeModifier(mob, Attributes.SCALE, SCALE_ID);
        IronGolemAbilitySupport.removeModifier(mob, Attributes.ARMOR, ARMOR_ID);
        IronGolemAbilitySupport.removeModifier(mob, Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_ID);
        IronGolemAbilitySupport.removeModifier(mob, Attributes.SWEEPING_DAMAGE_RATIO, SWEEP_ID);
        mob.removeEffect(MobEffects.RESISTANCE);
    }
}
