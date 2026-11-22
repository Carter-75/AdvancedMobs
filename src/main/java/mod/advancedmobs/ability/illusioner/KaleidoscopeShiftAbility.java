package mod.advancedmobs.ability.illusioner;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Kaleidoscope Shift (Illusioner ability 3 of 5,
 * transformation).
 *
 * WHAT IT DOES: the illusioner's form dissolves into color:
 * DUST_COLOR_TRANSITION motes cycle through the spectrum
 * across its body, its outline smears with ENTITY_EFFECT
 * tint in the same cycling hues, and it gains SPEED and a
 * dancer's footwork. Every time it is struck in this form
 * it blinks behind its attacker in a swirl of
 * REVERSE_PORTAL particles and looses one instant arrow in
 * reply. The form is exhausting and ends in a slump of gray
 * dust, but while it lasts the illusioner is nearly
 * untouchable.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the illusioner's current target within 16 blocks
 *   (distance squared 256).
 * - The form (start()): SPEED amplifier 1 (Speed II) for
 *   320 ticks, applied once and removed in end(). The hurt
 *   clock is primed in start() from the caster's current
 *   last-hurt-by timestamp, so a blow landed before the form
 *   began never triggers a blink.
 * - The colors (tick()): every 2 ticks, 1
 *   DUST_COLOR_TRANSITION mote drifts across the body (0.35
 *   horizontal spread, 0.9 vertical, at body center height);
 *   every 4 ticks, 1 ENTITY_EFFECT mote in the same hue.
 *   The hue advances one spectrum step every 10 ticks
 *   through 6 pre-built pairs (red to orange, orange to
 *   yellow, yellow to green, green to blue, blue to violet,
 *   violet to red), so the whole body cycles the spectrum
 *   about every 3 seconds. Both option sets are static
 *   finals: the hot path allocates nothing.
 * - The blink (tick(), polled every tick as one int
 *   compare): when the caster's last-hurt-by timestamp has
 *   advanced past the stored one, the stored one is updated
 *   and the blow is answered, at most once per 10 ticks (a
 *   damage-over-time attacker cannot machine-gun the
 *   teleports). The answer, when the attacker is a living
 *   enemy within 12 blocks: the caster teleports to a spot
 *   2.0 blocks behind the attacker (opposite its horizontal
 *   look direction, at the attacker's own height), trying
 *   the straight-behind spot first, then 0.7 radians to
 *   either side; a spot is only taken when the caster's own
 *   bounding box fits there collision-free. 12
 *   REVERSE_PORTAL motes burst at the old position and 12
 *   at the new one, with an ILLUSIONER_MIRROR_MOVE. Whether
 *   or not a spot fit, one real Arrow is loosed in reply
 *   from the caster's (possibly new) eye position at the
 *   attacker: base damage 2.0, speed 2.8, inaccuracy 1.0,
 *   with an ARROW_SHOOT. A blow from beyond 12 blocks, from
 *   a non-enemy, or inside the 10-tick blink spacing is
 *   recorded and ignored.
 * - Duration: 300 ticks (15 seconds), hard finish.
 * - end(): the SPEED is removed, and the slump lands: 10
 *   gray DUST motes (0x8A8A8A) sag around the body, an
 *   ILLUSIONER_AMBIENT plays low (pitch 0.6), and the
 *   caster gains SLOWNESS amplifier 0 for 40 ticks, applied
 *   in end() so it outlives the form (the Stubborn Hide
 *   Brace and Frenzy Gore winded precedent). If the caster
 *   dies first, the framework termination path runs this
 *   same end(); a dead illusioner slumps where it falls,
 *   which is harmless. Edge case, documented: a SPEED the
 *   caster carried from another source is removed with the
 *   form's, the accepted edge shared by every fixed-duration
 *   effect in this system.
 *
 * WHAT IT LOOKS LIKE: a walking rainbow: the robes smear
 * red into orange into yellow as it dances away from every
 * blow, and each landed hit is answered by a purple swirl,
 * the illusioner suddenly behind its attacker, bow already
 * drawn. When the color drains out, it sags gray and slow.
 *
 * PERFORMANCE: per tick the cost is one int compare (the
 * hurt clock) plus the ambient motes on their 2 and 4 tick
 * cadences; a blink tick adds at most 3 bounded collision
 * checks and 1 arrow spawn. No entity scans anywhere (the
 * attacker is read from the caster's own hurt record).
 * Particle budget per activation: ambient dust at most 150
 * plus effect motes at most 75, blinks at most 30 times 24
 * = 720 worst case (one per 10 ticks over 300), the slump
 * 10, total at most 955 particles, with the realistic blink
 * count far below the cap.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply. The blink is a teleport of the caster itself.
 *
 * State tag keys: "hurt_ts" (the last answered
 * last-hurt-by timestamp), "last_blink" (the tick of the
 * last blink, for the 10-tick spacing).
 */
public final class KaleidoscopeShiftAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int BLINK_SPACING_TICKS = 10;
    private static final double BLINK_MAX_DISTANCE_SQR = 144.0;

    /** The 6 spectrum steps for the dust motes (from color to to color, scale 1.2). */
    private static final DustColorTransitionOptions[] DUST_CYCLE = {
        new DustColorTransitionOptions(0xFF2A00, 0xFF9500, 1.2f),
        new DustColorTransitionOptions(0xFF9500, 0xFFE600, 1.2f),
        new DustColorTransitionOptions(0xFFE600, 0x2EE66B, 1.2f),
        new DustColorTransitionOptions(0x2EE66B, 0x2266FF, 1.2f),
        new DustColorTransitionOptions(0x2266FF, 0x9B30FF, 1.2f),
        new DustColorTransitionOptions(0x9B30FF, 0xFF2A00, 1.2f)
    };

    /** The same 6 hues as ENTITY_EFFECT tints. */
    private static final ColorParticleOption[] TINT_CYCLE = {
        ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, 1.0f, 0.16f, 0.0f),
        ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, 1.0f, 0.58f, 0.0f),
        ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, 1.0f, 0.90f, 0.0f),
        ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, 0.18f, 0.90f, 0.42f),
        ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, 0.13f, 0.40f, 1.0f),
        ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, 0.61f, 0.19f, 1.0f)
    };

    /** The gray of the end-of-form slump. */
    private static final DustParticleOptions SLUMP_DUST = new DustParticleOptions(0x8A8A8A, 1.2f);

    @Override
    public String id() {
        return "kaleidoscope_shift";
    }

    @Override
    public String displayName() {
        return "Kaleidoscope Shift";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.addEffect(new MobEffectInstance(MobEffects.SPEED, DURATION_TICKS + 20, 1));
        ctx.state().putInt("hurt_ts", caster.getLastHurtByMobTimestamp());
        ctx.state().putInt("last_blink", -BLINK_SPACING_TICKS);
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.ILLUSIONER_CAST_SPELL, SoundSource.HOSTILE, 1.1f, 1.3f);
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
        int hue = (ticks / 10) % DUST_CYCLE.length;
        if (ticks % 2 == 0) {
            level.sendParticles(DUST_CYCLE[hue], caster.getX(), caster.getY() + 0.9, caster.getZ(),
                1, 0.35, 0.9, 0.35, 0.02);
        }
        if (ticks % 4 == 0) {
            level.sendParticles(TINT_CYCLE[hue], caster.getX(), caster.getY() + 0.9, caster.getZ(),
                1, 0.30, 0.8, 0.30, 0.02);
        }
        // The hurt clock: one int compare on quiet ticks.
        int timestamp = caster.getLastHurtByMobTimestamp();
        if (timestamp == ctx.state().getIntOr("hurt_ts", 0)) {
            return;
        }
        ctx.state().putInt("hurt_ts", timestamp);
        if (ticks - ctx.state().getIntOr("last_blink", -BLINK_SPACING_TICKS) < BLINK_SPACING_TICKS) {
            return; // Inside the blink spacing: the blow is recorded, not answered.
        }
        LivingEntity attacker = caster.getLastHurtByMob();
        if (attacker == null || !attacker.isAlive()
            || !IllusionerAbilitySupport.isEnemy(ctx, attacker)
            || caster.distanceToSqr(attacker) > BLINK_MAX_DISTANCE_SQR) {
            return;
        }
        ctx.state().putInt("last_blink", ticks);
        blinkBehind(ctx, level, attacker);
        // The instant arrow in reply, from wherever the
        // blink left the caster.
        IllusionerAbilitySupport.fireArrow(ctx, caster.getEyePosition(), attacker, 2.0, 2.8f, 1.0f);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.ARROW_SHOOT, SoundSource.HOSTILE, 0.9f, 1.2f);
    }

    /**
     * Teleports the caster to a spot 2.0 blocks behind the
     * attacker (see the class comment): straight behind
     * first, then 0.7 radians to either side, first
     * collision-free spot wins; no fit, no teleport.
     */
    private static void blinkBehind(AbilityContext ctx, ServerLevel level, LivingEntity attacker) {
        Mob caster = ctx.mob();
        Vec3 look = attacker.getLookAngle();
        double lookLen = Math.sqrt(look.x * look.x + look.z * look.z);
        double behindX = lookLen > 1.0e-4 ? -look.x / lookLen : 1.0;
        double behindZ = lookLen > 1.0e-4 ? -look.z / lookLen : 0.0;
        double baseAngle = Math.atan2(behindZ, behindX);
        double[] offsets = {0.0, 0.7, -0.7};
        double halfWidth = caster.getBbWidth() / 2.0;
        for (double offset : offsets) {
            double angle = baseAngle + offset;
            double spotX = attacker.getX() + Math.cos(angle) * 2.0;
            double spotZ = attacker.getZ() + Math.sin(angle) * 2.0;
            double spotY = attacker.getY();
            AABB box = new AABB(spotX - halfWidth, spotY, spotZ - halfWidth,
                                spotX + halfWidth, spotY + caster.getBbHeight(), spotZ + halfWidth);
            if (!level.noCollision(caster, box)) {
                continue;
            }
            level.sendParticles(ParticleTypes.REVERSE_PORTAL, caster.getX(), caster.getY() + 1.0, caster.getZ(),
                12, 0.4, 0.7, 0.4, 0.12);
            caster.teleportTo(spotX, spotY, spotZ);
            level.sendParticles(ParticleTypes.REVERSE_PORTAL, spotX, spotY + 1.0, spotZ,
                12, 0.4, 0.7, 0.4, 0.12);
            level.playSound(null, spotX, spotY, spotZ,
                SoundEvents.ILLUSIONER_MIRROR_MOVE, SoundSource.HOSTILE, 1.0f, 1.2f);
            return;
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.removeEffect(MobEffects.SPEED);
        // The slump (see the class comment): gray dust and a
        // slow that outlives the form.
        caster.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 0));
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.sendParticles(SLUMP_DUST, caster.getX(), caster.getY() + 0.9, caster.getZ(),
                10, 0.4, 0.7, 0.4, 0.03);
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.ILLUSIONER_AMBIENT, SoundSource.HOSTILE, 1.0f, 0.6f);
        }
    }
}
