package mod.advancedmobs.ability.wither;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Death Nova (Wither ability 4 of 5, attack, aura).
 *
 * WHAT IT DOES: the wither gathers itself with a rising whine
 * of sculk sparks and black-to-violet motes, then detonates a
 * nova that flattens weak blocks, hurls enemies outward, and
 * leaves the ground cracked. And it does not stop at one:
 * each nova makes the next charge faster, so the activation
 * is a chain of three novas with shrinking telegraphs, and
 * standing near the wither gets more dangerous the longer it
 * runs. (The design's "each nova makes the next one charge a
 * little faster" lives inside the activation: ability state
 * is per-activation by framework contract, so the chain is
 * where the speedup is expressed.)
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the wither's current target within 14 blocks (distance
 *   squared 196). The nova is a close-range answer to being
 *   crowded.
 * - The chain: 3 novas. Charge times are 50 ticks for the
 *   first, 38 for the second, 28 for the third (detonations
 *   land on ticks 50, 88, and 116), and the ability finishes
 *   10 ticks after the third detonation, at tick 126.
 * - The charge (the telegraph): every tick, 2 SCULK_CHARGE_POP
 *   sparks spiral in at a ring radius that shrinks from 6
 *   blocks to 0.8 as the charge builds, at 0.6 above the
 *   wither's feet. Every 4 ticks, 2 DUST_COLOR_TRANSITION
 *   motes (black 0x05050A shifting to violet 0x7A1FD0, scale
 *   1.2) drift up the wither's body. The whine: a
 *   SCULK_BLOCK_CHARGE sound every 12 ticks of the charge
 *   (volume 0.7) whose pitch climbs with charge progress, from
 *   0.6 at the start of a charge to 1.8 at its end. The
 *   telegraph is honest: a full first charge is 2.5 seconds,
 *   long enough to run from, per the design.
 * - The detonation: Level.explode centered on the wither at
 *   1.0 above its feet, power 3.0, no fire. The explosion is
 *   vanilla in every respect but one (the brace below): its
 *   damage and knockback follow vanilla explosion falloff, and
 *   its block destruction follows vanilla blast resistance,
 *   which is what "flattens weak blocks" means (dirt, wood,
 *   and stone go; obsidian and bedrock do not). The terrain
 *   gate is the standing one: Level.ExplosionInteraction.MOB
 *   when AbilityContext.mobGriefingAllowed() is true (blocks
 *   break, permanently), NONE when it is false (no block is
 *   touched, and the damage, withering, and hurl below still
 *   land in full). On top of the blast, every enemy
 *   (WitherAbilitySupport rules) within radius 7 of the
 *   wither, between 3 below and 3 above its feet, gains WITHER
 *   amplifier 1 for 120 ticks (6 seconds) and is hurled
 *   outward: velocity set away from the wither at 1.2
 *   horizontal and 0.45 up (added to whatever the explosion
 *   itself did, so enemies at the edge of the blast, where its
 *   own knockback fades, are still thrown clear).
 * - The brace: a nova does not wound its own wither. At each
 *   detonation the wither gains RESISTANCE amplifier 4 for 10
 *   ticks (full damage reduction while the blast passes),
 *   applied before the explode call; it expires on its own,
 *   and end() removes it early if the activation somehow ends
 *   inside those 10 ticks. This is a deliberate, documented
 *   deviation from a raw self-explosion: the nova is channeled
 *   through the wither, not set off under it.
 *
 * WHAT IT LOOKS AND SOUNDS LIKE: the charge is the show:
 * teal SCULK_CHARGE_POP sparks collapsing inward, motes
 * shifting from black to violet as they rise, and a whine that
 * climbs in pitch three times, faster each time. Each
 * detonation fires 1 EXPLOSION_EMITTER and 2 EXPLOSION
 * particles at the wither's center on top of the vanilla
 * explosion's own flash and sound.
 *
 * PERFORMANCE: the per-tick charge work is 2 particle spawns
 * and arithmetic; the dust motes run every 4 ticks and the
 * whine every 12. Each detonation is one explode call plus
 * one bounded radius 7 entity query. No scans, no allocations
 * in the charge path beyond the particle option (a pre-built
 * static final). Particle budget per activation: charge
 * sparks 116 ticks times 2 = 232, dust motes at most 29
 * pulses times 2 = 58, detonations 3 times 3 = 9, total at
 * most 299 particles.
 *
 * State tag keys: none of its own. The chain position is a
 * pure function of ctx.ticksActive() (detonation ticks 50, 88,
 * 116), which the framework persists, so a resumed activation
 * after a reload detonates on schedule.
 */
public final class DeathNovaAbility implements MobAbility {

    static final int FINISH_TICKS = 126;
    private static final int[] DETONATION_TICKS = {50, 88, 116};
    private static final float NOVA_POWER = 3.0f;
    private static final double HURL_RADIUS = 7.0;
    private static final DustColorTransitionOptions NOVA_DUST =
        new DustColorTransitionOptions(0x05050A, 0x7A1FD0, 1.2f);

    @Override
    public String id() {
        return "death_nova";
    }

    @Override
    public String displayName() {
        return "Death Nova";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            Mob mob = ctx.mob();
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.WITHER_AMBIENT, SoundSource.HOSTILE, 1.0f, 1.4f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        for (int detonation : DETONATION_TICKS) {
            if (ticks == detonation) {
                detonate(ctx, level);
                return;
            }
        }
        if (ticks > DETONATION_TICKS[DETONATION_TICKS.length - 1]) {
            return; // The chain is spent; the last blast is still settling.
        }
        chargeVisuals(ctx, level, ticks);
    }

    /** The charge telegraph for one tick (see the class comment). */
    private void chargeVisuals(AbilityContext ctx, ServerLevel level, int ticks) {
        Mob mob = ctx.mob();
        // Where this tick sits inside its charge: progress 0 at
        // the charge's first tick, approaching 1 at detonation.
        int chargeStart = 0;
        int chargeEnd = DETONATION_TICKS[0];
        for (int detonation : DETONATION_TICKS) {
            if (ticks < detonation) {
                chargeEnd = detonation;
                break;
            }
            chargeStart = detonation;
        }
        double progress = (double) (ticks - chargeStart) / (chargeEnd - chargeStart);

        // Sparks collapsing inward as the charge builds.
        double radius = 6.0 - 5.2 * progress;
        double angle = ticks * 0.7;
        for (int i = 0; i < 2; i++) {
            double a = angle + i * Math.PI;
            level.sendParticles(ParticleTypes.SCULK_CHARGE_POP,
                mob.getX() + Math.cos(a) * radius, mob.getY() + 0.6, mob.getZ() + Math.sin(a) * radius,
                1, 0.1, 0.2, 0.1, 0.02);
        }
        if (ticks % 4 == 0) {
            level.sendParticles(NOVA_DUST, mob.getX(), mob.getY() + 1.5, mob.getZ(),
                2, 0.5, 0.9, 0.5, 0.03);
        }
        if ((ticks - chargeStart) % 12 == 0) {
            // The rising whine.
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.SCULK_BLOCK_CHARGE, SoundSource.HOSTILE, 0.7f,
                (float) (0.6 + progress * 1.2));
        }
    }

    /**
     * One detonation (see the class comment): the brace, the
     * vanilla explosion under the terrain gate, the flash, and
     * the withering hurl out to the full nova radius.
     */
    private void detonate(AbilityContext ctx, ServerLevel level) {
        Mob mob = ctx.mob();
        // The brace goes on before the blast so the nova never
        // wounds its own wither.
        mob.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 10, 4, false, false, false));

        Level.ExplosionInteraction interaction = ctx.mobGriefingAllowed()
            ? Level.ExplosionInteraction.MOB
            : Level.ExplosionInteraction.NONE;
        double cx = mob.getX();
        double cy = mob.getY() + 1.0;
        double cz = mob.getZ();
        level.explode(mob, cx, cy, cz, NOVA_POWER, false, interaction);
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, cx, cy, cz, 1, 0.0, 0.0, 0.0, 0.0);
        level.sendParticles(ParticleTypes.EXPLOSION, cx, cy, cz, 2, 0.4, 0.4, 0.4, 0.0);

        // The hurl: enemies out to the full nova radius are
        // withered and thrown, even where the blast's own
        // knockback has faded.
        List<LivingEntity> victims = WitherAbilitySupport.enemiesInBox(ctx, cx, mob.getY(), cz,
            HURL_RADIUS, 3.0, 3.0);
        for (LivingEntity victim : victims) {
            victim.addEffect(new MobEffectInstance(MobEffects.WITHER, 120, 1));
            Vec3 away = new Vec3(victim.getX() - cx, 0.0, victim.getZ() - cz);
            if (away.lengthSqr() < 1.0e-4) {
                away = new Vec3(1.0, 0.0, 0.0);
            }
            away = away.normalize();
            Vec3 velocity = victim.getDeltaMovement();
            victim.setDeltaMovement(velocity.x + away.x * 1.2, 0.45, velocity.z + away.z * 1.2);
            victim.hurtMarked = true;
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= FINISH_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // The brace is the only caster change, and it is already
        // gone unless the activation ended inside its 10 ticks.
        ctx.mob().removeEffect(MobEffects.RESISTANCE);
    }
}
