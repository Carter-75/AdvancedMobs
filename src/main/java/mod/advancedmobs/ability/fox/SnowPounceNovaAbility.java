package mod.advancedmobs.ability.fox;

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
import net.minecraft.world.entity.animal.fox.Fox;
import net.minecraft.world.level.block.Blocks;

/**
 * Snow Pounce Nova (Fox ability 2 of 5, mobility /
 * attack).
 *
 * WHAT IT DOES: the fox does its famous headfirst snow
 * pounce, writ large. It crouches, springs in a high arc
 * at its target with its jump strength cranked, hangs for
 * a heartbeat at the apex, then spears straight down
 * muzzle first. The landing bursts snow everywhere and
 * stuns and damages everything at the point of impact.
 * The fall never hurts the fox.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the fox's current target within 12 blocks
 *   (distance squared 144), plus the fox on the ground (a
 *   pounce starts from the ground, not mid-air).
 * - The kit (start() through end()): two transient
 *   attribute modifiers, removed exactly in end():
 *   JUMP_STRENGTH +0.5 add_value (id advancedmobs:
 *   snow_pounce_jump, the design's cranked jump strength;
 *   the launch below reads the live attribute, so the
 *   boost genuinely sets the arc) and SAFE_FALL_DISTANCE
 *   +100 add_value (id advancedmobs:snow_pounce_safe_fall,
 *   the design's safe fall distance), which between the
 *   fox's base 5.0 and the spear-down velocity means
 *   vanilla fall damage can never touch the fox during
 *   this ability.
 * - Phase 0, the crouch (ticks 1 to 10): the fox takes
 *   the real vanilla crouch pose (Fox.setIsCrouching,
 *   the synced pounce wind-up flag), navigation stopped,
 *   shedding 2 SNOWFLAKE motes every 2 ticks. The target's
 *   position is recorded every tick (state tag tx/ty/tz)
 *   so the launch aims at the last known spot if the
 *   target dies mid-crouch.
 * - The launch (tick 10): the crouch flag clears, the
 *   vanilla pounce flag goes on (Fox.setIsPouncing, the
 *   headfirst dive pose, held until the impact), FOX_
 *   SCREECH plays at volume 1.2, pitch 1.1, and one code
 *   impulse fires: horizontal toward the recorded target
 *   at distance times 0.11 clamped to [0.6, 1.2] blocks
 *   per tick, vertical the live JUMP_STRENGTH value times
 *   1.1 (0.42 base plus the 0.5 boost is 0.92, so about
 *   1.01: the high arc).
 * - Phase 1, the flight and the hang: the fox does not
 *   steer. The first tick its vertical velocity reads at
 *   or below 0.02 it reaches the apex and the hang
 *   begins: for 6 ticks its velocity is written each tick
 *   (vertical exactly 0, horizontal 0.2 blocks per tick
 *   drifting toward the target's live position, or its
 *   recorded position if the target is gone), so it hangs
 *   impossibly over the drop point. 2 SNOWFLAKE motes
 *   drift off it every 2 ticks through the flight.
 * - The spear (the tick after the hang): one velocity
 *   write, vertical -1.3, horizontal kept at the hang's
 *   last drift, and gravity plus the dive do the rest.
 * - The impact (the first spear tick where the fox is
 *   back on the ground): the pounce flag clears. One
 *   bounded radius-3.0 query around the landing point:
 *   every enemy in it takes 7.0 damage through a mob
 *   attack source credited to the fox and gains SLOWNESS
 *   IV (amplifier 3) for 40 ticks, the design's stun
 *   (slowness at high amplifier, brief). The look: 14
 *   BLOCK_CRUMBLE motes of the snow block state (the
 *   design's snow burst, a fixed snow state rather than
 *   the ground's so the nova reads as snow even off snow)
 *   plus 8 SNOWFLAKE, and FOX_HURT at pitch 0.8. The
 *   activation ends 5 ticks after the impact.
 * - Duration: hard cap 100 ticks (a spear that never
 *   lands, off a cliff edge, still ends, and the kit and
 *   the pounce flag are still cleaned by end()).
 * - end(): both modifiers are removed and both fox pose
 *   flags are cleared. Idempotent.
 *
 * WHAT IT LOOKS LIKE: a fox crouched flat and quivering,
 * then a screech and a soaring orange arc that stops dead
 * at the top, hangs, and spears down headfirst. Snow
 * explodes outward where it lands, everything nearby is
 * hammered and rooted for two seconds, and the fox shakes
 * itself off unhurt.
 *
 * PERFORMANCE: canTrigger is one distance check and one
 * onGround check. The crouch is one navigation stop and
 * at most 2 particles every 2 ticks; the flight is one
 * velocity read per tick and one velocity write per hang
 * tick; the impact is one bounded radius-3.0 query, once.
 * The crumble option is built once, at the impact.
 * Particle budget per activation: crouch at most 5 times
 * 2 = 10, flight at most 15 times 2 = 30, impact 22,
 * total at most 62 particles.
 *
 * TERRAIN: this ability changes no blocks; the
 * BLOCK_CRUMBLE burst is a particle carrying the snow
 * block state, not a block change, so the mobGriefing
 * gate does not apply.
 *
 * State tag keys: "phase" (0 crouching, 1 flying, 2
 * hanging counted inside phase 1 by "hang_left", 3
 * spearing, 4 impacted), "tx"/"ty"/"tz" (last known
 * target position), "hang_left" (hang ticks remaining),
 * "drift_x"/"drift_z" (the hang's horizontal drift, kept
 * into the spear), "land_tick" (the tick the impact
 * landed).
 */
public final class SnowPounceNovaAbility implements MobAbility {

    private static final int CROUCH_TICKS = 10;
    private static final int HANG_TICKS = 6;
    private static final int IMPACT_LINGER_TICKS = 5;
    private static final int HARD_CAP_TICKS = 100;
    private static final double IMPACT_RADIUS = 3.0;
    private static final double IMPACT_DAMAGE = 7.0;
    private static final int STUN_TICKS = 40;
    private static final int STUN_AMPLIFIER = 3;
    private static final Identifier JUMP_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "snow_pounce_jump");
    private static final Identifier SAFE_FALL_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "snow_pounce_safe_fall");

    @Override
    public String id() {
        return "snow_pounce_nova";
    }

    @Override
    public String displayName() {
        return "Snow Pounce Nova";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0 && ctx.mob().onGround();
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ctx.state().putInt("phase", 0);
        // The kit: the design's two attributes, held for
        // the whole activation, removed in end().
        FoxAbilitySupport.addModifier(caster, Attributes.JUMP_STRENGTH,
            new AttributeModifier(JUMP_ID, 0.5, AttributeModifier.Operation.ADD_VALUE));
        FoxAbilitySupport.addModifier(caster, Attributes.SAFE_FALL_DISTANCE,
            new AttributeModifier(SAFE_FALL_ID, 100.0, AttributeModifier.Operation.ADD_VALUE));
        if (caster instanceof Fox fox) {
            fox.setIsCrouching(true);
        }
        LivingEntity target = ctx.target();
        if (target != null) {
            recordTarget(ctx, target);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        int ticks = ctx.ticksActive();
        int phase = ctx.state().getIntOr("phase", 0);
        ServerLevel level = ctx.serverLevel();
        switch (phase) {
            case 0 -> {
                // The crouch: quiver in the wind-up pose until the launch tick.
                caster.getNavigation().stop();
                LivingEntity target = ctx.target();
                if (target != null) {
                    recordTarget(ctx, target);
                }
                if (level != null && ticks % 2 == 0) {
                    level.sendParticles(ParticleTypes.SNOWFLAKE, caster.getX(), caster.getY() + 0.3, caster.getZ(),
                        2, 0.3, 0.2, 0.3, 0.05);
                }
                if (ticks >= CROUCH_TICKS) {
                    launch(ctx, level);
                }
            }
            case 1 -> {
                // The flight: watch for the apex, then hang over the drop point.
                if (level != null && ticks % 2 == 0) {
                    level.sendParticles(ParticleTypes.SNOWFLAKE, caster.getX(), caster.getY() + 0.3, caster.getZ(),
                        2, 0.3, 0.2, 0.3, 0.05);
                }
                int hangLeft = ctx.state().getIntOr("hang_left", -1);
                if (hangLeft < 0) {
                    if (caster.getDeltaMovement().y <= 0.02) {
                        ctx.state().putInt("hang_left", HANG_TICKS);
                    }
                } else if (hangLeft > 0) {
                    // The hang: hold height, drift toward the target.
                    double aimX = ctx.state().getDoubleOr("tx", caster.getX());
                    double aimZ = ctx.state().getDoubleOr("tz", caster.getZ());
                    LivingEntity target = ctx.target();
                    if (target != null) {
                        aimX = target.getX();
                        aimZ = target.getZ();
                    }
                    double dx = aimX - caster.getX();
                    double dz = aimZ - caster.getZ();
                    double length = Math.sqrt(dx * dx + dz * dz);
                    double driftX = 0.0;
                    double driftZ = 0.0;
                    if (length > 0.3) {
                        driftX = dx / length * 0.2;
                        driftZ = dz / length * 0.2;
                    }
                    ctx.state().putDouble("drift_x", driftX);
                    ctx.state().putDouble("drift_z", driftZ);
                    caster.setDeltaMovement(driftX, 0.0, driftZ);
                    caster.hurtMarked = true;
                    ctx.state().putInt("hang_left", hangLeft - 1);
                } else {
                    // The spear: straight down, muzzle first.
                    caster.setDeltaMovement(
                        ctx.state().getDoubleOr("drift_x", 0.0), -1.3,
                        ctx.state().getDoubleOr("drift_z", 0.0));
                    caster.hurtMarked = true;
                    ctx.state().putInt("phase", 3);
                }
            }
            case 3 -> {
                // The spear falls: gravity and the dive until the ground arrives.
                if (caster.onGround()) {
                    impact(ctx, ticks);
                }
            }
            default -> {
                // Phase 4: the impact has landed; the linger runs out the clock.
            }
        }
    }

    /** The launch: pose swap, screech, and the one code impulse of the arc. */
    private void launch(AbilityContext ctx, ServerLevel level) {
        Mob caster = ctx.mob();
        if (caster instanceof Fox fox) {
            fox.setIsCrouching(false);
            fox.setIsPouncing(true);
        }
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.FOX_SCREECH, SoundSource.HOSTILE, 1.2f, 1.1f);
        }
        double tx = ctx.state().getDoubleOr("tx", caster.getX());
        double tz = ctx.state().getDoubleOr("tz", caster.getZ());
        double dx = tx - caster.getX();
        double dz = tz - caster.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double speed = Math.max(0.6, Math.min(1.2, horizontal * 0.11));
        double vx = horizontal > 0.01 ? dx / horizontal * speed : 0.0;
        double vz = horizontal > 0.01 ? dz / horizontal * speed : 0.0;
        // The vertical reads the live JUMP_STRENGTH, boost included.
        double vy = caster.getAttributeValue(Attributes.JUMP_STRENGTH) * 1.1;
        caster.setDeltaMovement(vx, vy, vz);
        caster.hurtMarked = true;
        ctx.state().putInt("hang_left", -1);
        ctx.state().putInt("phase", 1);
    }

    /** The impact: area damage, the stun, and the snow burst. */
    private void impact(AbilityContext ctx, int ticks) {
        Mob caster = ctx.mob();
        ctx.state().putInt("land_tick", ticks);
        ctx.state().putInt("phase", 4);
        if (caster instanceof Fox fox) {
            fox.setIsPouncing(false);
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        // One bounded query: everything at the landing point takes the nova.
        for (LivingEntity enemy : FoxAbilitySupport.enemiesAroundSelf(ctx, IMPACT_RADIUS, 2.0, 2.0)) {
            enemy.hurtServer(level, level.damageSources().mobAttack(caster), (float) IMPACT_DAMAGE);
            enemy.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, STUN_TICKS, STUN_AMPLIFIER));
        }
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE,
                Blocks.SNOW_BLOCK.defaultBlockState()),
            caster.getX(), caster.getY() + 0.2, caster.getZ(), 14, 0.7, 0.2, 0.7, 0.12);
        level.sendParticles(ParticleTypes.SNOWFLAKE, caster.getX(), caster.getY() + 0.5, caster.getZ(),
            8, 0.6, 0.4, 0.6, 0.1);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.FOX_HURT, SoundSource.HOSTILE, 1.0f, 0.8f);
    }

    /** Records the target's live position for aiming after it dies or is lost. */
    private void recordTarget(AbilityContext ctx, LivingEntity target) {
        ctx.state().putDouble("tx", target.getX());
        ctx.state().putDouble("ty", target.getY());
        ctx.state().putDouble("tz", target.getZ());
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks >= HARD_CAP_TICKS) {
            return true;
        }
        return ctx.state().getIntOr("phase", 0) == 4
            && ticks - ctx.state().getIntOr("land_tick", 0) >= IMPACT_LINGER_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        FoxAbilitySupport.removeModifier(caster, Attributes.JUMP_STRENGTH, JUMP_ID);
        FoxAbilitySupport.removeModifier(caster, Attributes.SAFE_FALL_DISTANCE, SAFE_FALL_ID);
        if (caster instanceof Fox fox) {
            fox.setIsCrouching(false);
            fox.setIsPouncing(false);
        }
    }
}
