package mod.advancedmobs.ability.axolotl;

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
 * Predator Snap (Axolotl ability 3 of 5, attack).
 *
 * WHAT IT DOES: the lazy swimmer explodes into a lunge, a
 * blur of splash and crit particles, jaws snapping shut on
 * whatever drifted too close. The snap deals heavy damage
 * and briefly pins the target with slowness, the way real
 * axolotls vacuum up prey before it can react.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the axolotl's current target within 10 blocks (distance
 *   squared 100), plus the axolotl in water (the lunge is a
 *   swim burst; a beached axolotl cannot snap).
 * - The wind-up (start()): the axolotl gains an ATTACK_DAMAGE
 *   add_value modifier of +6.0 (modifier id
 *   advancedmobs:predator_snap_damage; the design's attribute
 *   line). The axolotl HAS an ATTACK_DAMAGE instance in 26.2
 *   (base 2.0, verified against the jar), so the modifier is
 *   real: while it lasts the axolotl's live attack value is
 *   8.0. The modifier is removed in end().
 * - The lunge (tick()): the axolotl is steered straight at
 *   the target's live chest at 1.25 blocks per tick (code
 *   velocity, the package steering helper), and its last
 *   known position is refreshed every tick so a target that
 *   dies mid-lunge is still lunged at its last spot. 2 SPLASH
 *   particles trail at the axolotl each tick. If the axolotl
 *   leaves the water mid-lunge the snap is over (the strike
 *   simply never lands).
 * - The snap: the first tick the axolotl comes within 1.7
 *   blocks of the target, the jaws close exactly once: damage
 *   equal to the axolotl's live ATTACK_DAMAGE (8.0 while the
 *   modifier lasts, more if the war system's upgrades raised
 *   the base) as a mob attack credited to the axolotl, then
 *   the pin: the victim gains SLOWNESS II for 60 ticks. 10
 *   CRIT and 8 SPLASH particles burst at the victim and
 *   AXOLOTL_ATTACK plays at volume 1.2.
 * - After the snap the axolotl swims on for 6 more ticks
 *   (steered past the victim on its last heading) and the
 *   ability ends. Without a snap, reaching within 1.0 blocks
 *   of the recorded last position also ends it (the lunge ran
 *   through empty water), and the hard cap is 40 ticks either
 *   way.
 *
 * WHAT IT LOOKS LIKE: a still axolotl suddenly blurring
 * forward through its own splash trail, one hard crit burst
 * as the jaws close, and the victim hanging slowed in the
 * water while the axolotl drifts past.
 *
 * PERFORMANCE: per tick the lunge is one steering write, one
 * distance computation, one position record, and 2 particles;
 * the snap is one damage call and one effect call. No entity
 * queries anywhere. Particle budget per activation: at most
 * 40 ticks times 2 = 80 trail plus 18 at the snap, total at
 * most 98 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * CLEANUP: end() removes the ATTACK_DAMAGE modifier. The
 * victim's SLOWNESS is an ordinary 60 tick effect that
 * expires on its own.
 *
 * State tag keys: "tx", "ty", "tz" (the target's last known
 * chest position), "snapped" (1 once the jaws have closed),
 * "snap_tick" (the tick the snap landed on).
 */
public final class PredatorSnapAbility implements MobAbility {

    private static final int HARD_CAP_TICKS = 40;
    private static final int SWIM_THROUGH_TICKS = 6;
    private static final double LUNGE_SPEED = 1.25;
    private static final double SNAP_RANGE = 1.7;
    private static final double SNAP_DAMAGE_BONUS = 6.0;
    private static final Identifier DAMAGE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "predator_snap_damage");

    @Override
    public String id() {
        return "predator_snap";
    }

    @Override
    public String displayName() {
        return "Predator Snap";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().isInWater()
            && ctx.mob().distanceToSqr(target) <= 100.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        LivingEntity target = ctx.target();
        if (target != null) {
            recordTarget(ctx, target);
        } else {
            ctx.state().putDouble("tx", caster.getX());
            ctx.state().putDouble("ty", caster.getY());
            ctx.state().putDouble("tz", caster.getZ());
        }
        ctx.state().putInt("snapped", 0);
        ctx.state().putInt("snap_tick", -1);

        // The design's attribute line: the jaws are wound up
        // for the whole lunge, so the snap hits at the raised
        // value whenever it lands.
        AxolotlAbilitySupport.addModifier(caster, Attributes.ATTACK_DAMAGE,
            new AttributeModifier(DAMAGE_ID, SNAP_DAMAGE_BONUS, AttributeModifier.Operation.ADD_VALUE));
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        if (!caster.isInWater()) {
            return; // Out of water the lunge is dead; isFinished ends it.
        }
        LivingEntity target = ctx.target();
        if (target != null) {
            recordTarget(ctx, target);
        }
        double tx = ctx.state().getDoubleOr("tx", caster.getX());
        double ty = ctx.state().getDoubleOr("ty", caster.getY());
        double tz = ctx.state().getDoubleOr("tz", caster.getZ());

        double dist = AxolotlAbilitySupport.steerToward(caster, tx, ty, tz, LUNGE_SPEED);

        level.sendParticles(ParticleTypes.SPLASH,
            caster.getX(), caster.getY() + 0.2, caster.getZ(),
            2, 0.15, 0.15, 0.15, 0.05);

        if (ctx.state().getIntOr("snapped", 0) == 0 && target != null && dist <= SNAP_RANGE) {
            snap(ctx, level, target);
        }
    }

    /** Records the target's chest position as the lunge point. */
    private void recordTarget(AbilityContext ctx, LivingEntity target) {
        ctx.state().putDouble("tx", target.getX());
        ctx.state().putDouble("ty", target.getY() + target.getBbHeight() * 0.5);
        ctx.state().putDouble("tz", target.getZ());
    }

    /** The jaws close: damage at the raised value, the slowness pin, and the burst. See the class comment. */
    private void snap(AbilityContext ctx, ServerLevel level, LivingEntity target) {
        Mob caster = ctx.mob();
        ctx.state().putInt("snapped", 1);
        ctx.state().putInt("snap_tick", ctx.ticksActive());

        double damage = caster.getAttributeValue(Attributes.ATTACK_DAMAGE);
        target.hurtServer(level, level.damageSources().mobAttack(caster), (float) damage);
        target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 1));

        level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY() + 0.5, target.getZ(),
            10, 0.3, 0.3, 0.3, 0.1);
        level.sendParticles(ParticleTypes.SPLASH, target.getX(), target.getY() + 0.5, target.getZ(),
            8, 0.4, 0.3, 0.4, 0.1);
        level.playSound(null, target.getX(), target.getY(), target.getZ(),
            SoundEvents.AXOLOTL_ATTACK, SoundSource.HOSTILE, 1.2f, 1.0f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks >= HARD_CAP_TICKS) {
            return true;
        }
        if (!ctx.mob().isInWater()) {
            return true;
        }
        int snapTick = ctx.state().getIntOr("snap_tick", -1);
        if (snapTick >= 0) {
            return ticks >= snapTick + SWIM_THROUGH_TICKS;
        }
        // No snap yet: running through the recorded position
        // ends the lunge.
        Mob caster = ctx.mob();
        double dx = ctx.state().getDoubleOr("tx", caster.getX()) - caster.getX();
        double dy = ctx.state().getDoubleOr("ty", caster.getY()) - caster.getY();
        double dz = ctx.state().getDoubleOr("tz", caster.getZ()) - caster.getZ();
        return ticks > 5 && dx * dx + dy * dy + dz * dz <= 1.0;
    }

    @Override
    public void end(AbilityContext ctx) {
        AxolotlAbilitySupport.removeModifier(ctx.mob(), Attributes.ATTACK_DAMAGE, DAMAGE_ID);
    }
}
