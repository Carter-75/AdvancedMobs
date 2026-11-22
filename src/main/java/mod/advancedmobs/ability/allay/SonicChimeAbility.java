package mod.advancedmobs.ability.allay;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.List;

/**
 * Sonic Chime (Allay ability 4 of 5, attack).
 *
 * WHAT IT DOES: the allay rings itself like a bell. Notes
 * spiral inward as the tone gathers, then a single clear
 * chime bursts outward as a compact sonic boom ring:
 * enemies caught by the ring take sonic boom damage, are
 * knocked spinning away, and are left with their ears
 * ringing in a short weakness.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the allay's current target within 10 blocks (distance
 *   squared 100), so the 6-block ring can actually reach.
 * - The charge (ticks 1 to 10): 2 NOTE particles per tick at
 *   a radius shrinking from 1.2 to 0.3 blocks around the
 *   allay at head height, angles spinning 0.6 radians per
 *   tick: the tone drawing in. A NOTE_BLOCK_CHIME sound
 *   plays at tick 1 (volume 1.0, pitch 1.0) and tick 6
 *   (volume 1.0, pitch 1.5), the pitch rising as it gathers.
 *   The allay holds its position by steering to its own
 *   start point each tick (a velocity hold, never NoAi).
 * - The burst (tick 11): a NOTE_BLOCK_BELL sound (volume
 *   1.6, pitch 1.0), the one clear tone, and one bounded
 *   query at radius 6.0 around the allay (2.5 below to 2.5
 *   above): every enemy (package rules) takes 5.0 damage
 *   through level.damageSources().sonicBoom(caster), the
 *   sonic boom damage type credited to the allay (armor
 *   bypassing, like the warden's own boom), is knocked away
 *   from the allay at 1.1 blocks per tick horizontally and
 *   0.45 up with hurtMarked set, is spun by a random yaw
 *   turn of 120 to 240 degrees (the knocked spinning of the
 *   design), and gains WEAKNESS I for 100 ticks (5 seconds
 *   of ringing ears).
 * - The ring visual (ticks 11 to 14): each tick the ring
 *   steps out by 1.5 blocks (radii 1.5, 3.0, 4.5, 6.0) as 8
 *   SONIC_BOOM particles at the compass points around the
 *   allay at head height: one compact shockwave ring rolling
 *   outward through the air.
 * - The ability finishes at tick 16. The weakness rides its
 *   own clock after the end; nothing else is applied, so
 *   end() is empty.
 *
 * WHAT IT LOOKS AND SOUNDS LIKE: notes spiraling into a
 * hovering allay, a bright bell tone, and a visible boom
 * ring rolling out in every direction as victims stagger
 * away spinning.
 *
 * PERFORMANCE: the charge is 2 particle sends and one
 * steering write per tick; the burst is one bounded query
 * once; the ring is 8 sends per tick for 4 ticks. No
 * per-tick entity queries, no registry or config lookups.
 * Particle budget per activation: charge 20 plus ring 32,
 * total at most 52 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "hold_x"/"hold_y"/"hold_z" (the position
 * the allay holds while charging, its position at start()).
 */
public final class SonicChimeAbility implements MobAbility {

    private static final int CHARGE_TICKS = 10;
    private static final int BURST_TICK = 11;
    private static final int FINISH_TICKS = 16;
    private static final float BURST_DAMAGE = 5.0f;
    private static final double BURST_RADIUS = 6.0;
    private static final double KNOCK_HORIZONTAL = 1.1;
    private static final double KNOCK_UP = 0.45;
    private static final int WEAKNESS_TICKS = 100;

    @Override
    public String id() {
        return "sonic_chime";
    }

    @Override
    public String displayName() {
        return "Sonic Chime";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 100.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ctx.state().putDouble("hold_x", caster.getX());
        ctx.state().putDouble("hold_y", caster.getY());
        ctx.state().putDouble("hold_z", caster.getZ());
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        int ticks = ctx.ticksActive();

        if (ticks <= CHARGE_TICKS) {
            // Hold position and draw the tone in.
            AllayAbilitySupport.steerToward(ctx,
                ctx.state().getDoubleOr("hold_x", caster.getX()),
                ctx.state().getDoubleOr("hold_y", caster.getY()),
                ctx.state().getDoubleOr("hold_z", caster.getZ()),
                0.3);
            double radius = 1.2 - 0.09 * ticks;
            double angle = ticks * 0.6;
            for (int i = 0; i < 2; i++) {
                double a = angle + i * Math.PI;
                level.sendParticles(ParticleTypes.NOTE,
                    caster.getX() + Math.cos(a) * radius,
                    caster.getY() + 0.6,
                    caster.getZ() + Math.sin(a) * radius,
                    1, 0.0, 0.0, 0.0, 0.0);
            }
            if (ticks == 1) {
                level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                    SoundEvents.NOTE_BLOCK_CHIME, SoundSource.HOSTILE, 1.0f, 1.0f);
            } else if (ticks == 6) {
                level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                    SoundEvents.NOTE_BLOCK_CHIME, SoundSource.HOSTILE, 1.0f, 1.5f);
            }
            return;
        }

        if (ticks == BURST_TICK) {
            burst(ctx, level);
        }
        if (ticks >= BURST_TICK && ticks < BURST_TICK + 4) {
            // The ring rolling outward: 8 boom motes per step.
            double radius = 1.5 * (ticks - BURST_TICK + 1);
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4.0;
                level.sendParticles(ParticleTypes.SONIC_BOOM,
                    caster.getX() + Math.cos(a) * radius,
                    caster.getY() + 0.6,
                    caster.getZ() + Math.sin(a) * radius,
                    1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }

    /** The burst: the bell tone and the one damage pass of the activation. */
    private void burst(AbilityContext ctx, ServerLevel level) {
        Mob caster = ctx.mob();
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.NOTE_BLOCK_BELL, SoundSource.HOSTILE, 1.6f, 1.0f);
        List<LivingEntity> victims = AllayAbilitySupport.enemiesInBox(ctx,
            caster.getX(), caster.getY(), caster.getZ(), BURST_RADIUS, 2.5, 2.5);
        for (LivingEntity victim : victims) {
            victim.hurtServer(level, level.damageSources().sonicBoom(caster), BURST_DAMAGE);
            double awayX = victim.getX() - caster.getX();
            double awayZ = victim.getZ() - caster.getZ();
            double length = Math.sqrt(awayX * awayX + awayZ * awayZ);
            if (length < 1.0e-4) {
                awayX = 1.0;
                awayZ = 0.0;
                length = 1.0;
            }
            victim.setDeltaMovement(awayX / length * KNOCK_HORIZONTAL, KNOCK_UP,
                awayZ / length * KNOCK_HORIZONTAL);
            victim.hurtMarked = true;
            // Knocked spinning: a one-time random yaw turn.
            victim.setYRot(victim.getYRot() + 120.0f + ctx.random().nextFloat() * 120.0f);
            victim.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, WEAKNESS_TICKS, 0));
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= FINISH_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to undo: the weakness rides its own clock,
        // and no modifier, summon, or display was created.
        // Deliberately empty.
    }
}
