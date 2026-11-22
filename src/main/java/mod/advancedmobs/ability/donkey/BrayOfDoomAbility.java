package mod.advancedmobs.ability.donkey;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Bray of Doom (Donkey ability 5 of 5, aura / control).
 *
 * WHAT IT DOES: the donkey throws its head back and brays
 * fit to end the world. Three sonic boom rings roll off it
 * in widening circles with note particles scattering in
 * their wake, and every enemy a ring catches is left
 * reeling: nauseated, weakened, and shoved back by the
 * sheer noise, easy meat for the donkey's next kick.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the donkey's current target within 12 blocks (distance
 *   squared 144).
 * - The wind-up (start()): DONKEY_ANGRY at volume 1.2,
 *   pitch 0.8, and the hit record in the state tag starts
 *   empty: each enemy can be caught by the bray only once,
 *   however many rings wash over it.
 * - The rings (tick(), at ticks 10, 22, and 34): ring radii
 *   4.0, 7.0, and 10.0 blocks, drawn at chest height (the
 *   donkey's feet plus 1.1) with 12, 18, and 24 SONIC_BOOM
 *   particles placed evenly on the circle. Each ring also
 *   plays the bray itself, DONKEY_AMBIENT at volume 2.0
 *   with pitch stepping down 0.8, 0.75, 0.7 ring by ring,
 *   and scatters 8 NOTE particles around the donkey's head.
 * - The catch (each ring): one bounded query at the ring
 *   radius plus 1.5 (2.5 below to 2.5 above the donkey);
 *   enemies whose horizontal distance from the donkey is
 *   within 1.5 blocks of the ring radius (the wave band)
 *   and who have not been caught yet are caught: NAUSEA
 *   for 200 ticks and WEAKNESS I for 200 ticks, and a
 *   radial impulse (the design's code radial impulse):
 *   velocity set to 0.9 blocks per tick horizontally away
 *   from the donkey and 0.3 up, hurtMarked set. Caught
 *   UUIDs are recorded in the state tag's hit compound.
 * - Duration: the last ring fires at tick 34 and the
 *   ability finishes at tick 50. end() has nothing to
 *   clean: the bray applies no modifiers or effects to the
 *   donkey itself, and the victims' nausea and weakness
 *   ride their own clocks past the end (documented, the
 *   Caravan Call precedent).
 *
 * WHAT IT LOOKS LIKE: a donkey screaming at the sky while
 * visible rings of compressed noise roll outward through
 * the fight, music notes bursting off it, enemies
 * staggering green-faced in the wash.
 *
 * PERFORMANCE: tick() returns immediately except on the
 * three ring ticks; a ring is one bounded entity query
 * plus its ring drawing. Particle budget per activation:
 * 54 SONIC_BOOM plus 24 NOTE = 78 particles.
 *
 * TERRAIN: this ability changes no blocks; the mobGriefing
 * gate does not apply.
 *
 * State tag keys: "hit" (compound keyed by victim UUID
 * string, the set of enemies already caught by this bray).
 */
public final class BrayOfDoomAbility implements MobAbility {

    private static final int FINISH_TICKS = 50;
    private static final int[] RING_TICKS = {10, 22, 34};
    private static final double[] RING_RADII = {4.0, 7.0, 10.0};
    private static final int[] RING_MOTES = {12, 18, 24};
    private static final float[] RING_PITCHES = {0.8f, 0.75f, 0.7f};
    private static final double BAND_HALF_WIDTH = 1.5;
    private static final int DEBUFF_TICKS = 200;
    private static final double IMPULSE_HORIZONTAL = 0.9;
    private static final double IMPULSE_VERTICAL = 0.3;

    @Override
    public String id() {
        return "bray_of_doom";
    }

    @Override
    public String displayName() {
        return "Bray of Doom";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().put("hit", new CompoundTag());
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            Mob caster = ctx.mob();
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.DONKEY_ANGRY,
                SoundSource.HOSTILE, 1.2f, 0.8f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        int ring = -1;
        for (int i = 0; i < RING_TICKS.length; i++) {
            if (ticks == RING_TICKS[i]) {
                ring = i;
                break;
            }
        }
        if (ring < 0) {
            return; // Cheap early-out: only the three ring ticks work.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        double radius = RING_RADII[ring];

        // The bray, and the notes scattering off it.
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.DONKEY_AMBIENT,
            SoundSource.HOSTILE, 2.0f, RING_PITCHES[ring]);
        level.sendParticles(ParticleTypes.NOTE, caster.getX(), caster.getY() + 1.6, caster.getZ(),
            8, 0.6, 0.3, 0.6, 0.1);

        // Draw the ring at chest height.
        int motes = RING_MOTES[ring];
        for (int i = 0; i < motes; i++) {
            double angle = (Math.PI * 2.0 * i) / motes;
            level.sendParticles(ParticleTypes.SONIC_BOOM,
                caster.getX() + Math.cos(angle) * radius, caster.getY() + 1.1,
                caster.getZ() + Math.sin(angle) * radius,
                1, 0.0, 0.0, 0.0, 0.0);
        }

        // Catch everyone standing in the wave band, once per bray.
        CompoundTag hit = ctx.state().getCompoundOrEmpty("hit");
        List<LivingEntity> victims = DonkeyAbilitySupport.enemiesAroundSelf(ctx,
            radius + BAND_HALF_WIDTH, 2.5, 2.5);
        for (LivingEntity victim : victims) {
            double dx = victim.getX() - caster.getX();
            double dz = victim.getZ() - caster.getZ();
            double distance = Math.sqrt(dx * dx + dz * dz);
            if (distance < radius - BAND_HALF_WIDTH || distance > radius + BAND_HALF_WIDTH) {
                continue;
            }
            String key = victim.getUUID().toString();
            if (hit.contains(key)) {
                continue;
            }
            hit.putBoolean(key, true);
            victim.addEffect(new MobEffectInstance(MobEffects.NAUSEA, DEBUFF_TICKS, 0));
            victim.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, DEBUFF_TICKS, 0));
            Vec3 away = distance < 1.0e-4 ? new Vec3(1.0, 0.0, 0.0)
                : new Vec3(dx / distance, 0.0, dz / distance);
            victim.setDeltaMovement(away.x * IMPULSE_HORIZONTAL, IMPULSE_VERTICAL,
                away.z * IMPULSE_HORIZONTAL);
            victim.hurtMarked = true;
        }
        ctx.state().put("hit", hit);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= FINISH_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to clean: the bray leaves no modifiers or
        // effects on the donkey, and the victims' debuffs
        // ride their own clocks (see the class comment).
    }
}
