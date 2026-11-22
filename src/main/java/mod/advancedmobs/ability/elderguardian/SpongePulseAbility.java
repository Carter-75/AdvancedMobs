package mod.advancedmobs.ability.elderguardian;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Sponge Pulse (Elder Guardian ability 4 of 5, defense).
 *
 * WHAT IT DOES: the elder guardian inhales the whole fight for
 * a moment: every hostile projectile within a wide radius pops
 * into POOF particles and vanishes, and a ring of SPLASH and
 * BUBBLE bursts outward as it exhales. While it drinks in the
 * water it regenerates steadily, so the pulse doubles as its
 * sustain tool in a long fight. The inhale is visible and
 * audible a full second before it fires, giving quick enemies
 * one chance to hold their shots.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   elder guardian's current target within 20 blocks (distance
 *   squared 400).
 * - Duration: exactly 300 ticks (15 seconds) from start(). The
 *   pulse itself fires once, at tick 20; the rest of the
 *   activation is the sustain window below.
 * - The sustain: at start() the elder gains REGENERATION I for
 *   the full 300 ticks, removed in end(). Removing it is safe
 *   even for an elder that owns permanent regeneration from its
 *   upgrade tree: ScalingSystem re-applies permanent effects it
 *   finds missing, the same reasoning Living Stone Shell
 *   documents for its own timed regeneration.
 * - The inhale (ticks 1 to 19): every 2 ticks, 3 BUBBLE
 *   particles appear on a ring around the elder whose radius
 *   shrinks from 12 blocks to 2 as the fire tick approaches (the
 *   drawn-in water; the particles are stationary, the shrinking
 *   ring is the visual). ELDER_GUARDIAN_AMBIENT at volume 1.2
 *   and pitch 1.5 sounds at start(): the audible tell.
 * - The pulse (tick 20): one bounded query for Projectile
 *   entities in a radius 16 box around the elder (8 below, 8
 *   above). A projectile is hostile when it has no owner, or
 *   its owner is a living enemy by the package rule; the
 *   elder's own and its allies' projectiles are never touched.
 *   (Guardians fire no projectiles of their own, so in practice
 *   the carve-out protects allied archers in a mob war.) Every
 *   hostile projectile bursts into 4 POOF particles at its
 *   position and is discarded. No damage is dealt to anything:
 *   the pulse is pure denial.
 * - The exhale (also tick 20): 16 SPLASH and 16 BUBBLE
 *   particles burst outward around the elder, ELDER_GUARDIAN_
 *   AMBIENT sounds at volume 1.5 and pitch 0.6, and if the elder
 *   is in water at that moment it drinks deep and heals 4.0
 *   health on top of the regeneration.
 * - After the pulse: every 20 ticks, 3 BUBBLE wisps rise off
 *   the elder for the rest of the sustain window. Nothing else
 *   happens; a second pulse never fires in one activation.
 *
 * WHAT IT LOOKS AND SOUNDS LIKE: a ring of bubbles visibly
 * collapsing inward toward the elder over one second, a high
 * inhale tone, then the pop: every arrow, trident, fireball and
 * skull in the radius winks out in a POOF, a splash ring bursts
 * outward, and the tone drops into the exhale. The elder shows
 * REGENERATION sparkles for the whole window.
 *
 * PERFORMANCE: tick() returns immediately except on the 2 tick
 * marks before the pulse (3 particles of pure math), the single
 * fire tick (one bounded projectile query plus one discard and
 * 4 particles per projectile found), and the 20 tick marks
 * after it (3 particles). Particle budget per activation: 30
 * inhale bubbles plus 32 exhale particles plus at most 20
 * projectiles times 4 POOF = 80, plus at most 42 wisps, total
 * at most 184 particles. No attribute modifiers; the one effect
 * is removed in end().
 *
 * State tag keys: "fired" (whether the tick 20 pulse has gone
 * off; kept in the tag so a chunk reload mid-inhale resumes the
 * telegraph instead of refiring or skipping the pulse).
 */
public final class SpongePulseAbility implements MobAbility {

    static final int DURATION_TICKS = 300;
    private static final int FIRE_TICK = 20;
    private static final double PULSE_RADIUS = 16.0;

    @Override
    public String id() {
        return "sponge_pulse";
    }

    @Override
    public String displayName() {
        return "Sponge Pulse";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 400.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        ctx.state().putBoolean("fired", false);

        // The sustain starts with the inhale: the elder drinks
        // in the water for the whole window.
        mob.addEffect(new MobEffectInstance(MobEffects.REGENERATION, DURATION_TICKS, 0));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            // The audible tell: the inhale.
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.ELDER_GUARDIAN_AMBIENT, SoundSource.HOSTILE, 1.2f, 1.5f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        boolean fired = ctx.state().getBooleanOr("fired", false);

        if (!fired && ticks < FIRE_TICK) {
            // The inhale: a bubble ring collapsing inward.
            if (ticks % 2 != 0) {
                return; // Cheap early-out: inhale bubbles on even ticks only.
            }
            double radius = 12.0 - 10.0 * (ticks / (double) FIRE_TICK);
            for (int i = 0; i < 3; i++) {
                double angle = ctx.random().nextDouble() * Math.PI * 2.0;
                level.sendParticles(ParticleTypes.BUBBLE,
                    mob.getX() + Math.cos(angle) * radius, mob.getY() + 1.0,
                    mob.getZ() + Math.sin(angle) * radius,
                    1, 0.0, 0.0, 0.0, 0.02);
            }
            return;
        }
        if (!fired) {
            firePulse(ctx, level);
            ctx.state().putBoolean("fired", true);
            return;
        }
        // After the pulse: quiet sustain wisps on the 20 tick
        // marks.
        if (ticks % 20 == 0) {
            level.sendParticles(ParticleTypes.BUBBLE, mob.getX(), mob.getY() + 1.5, mob.getZ(),
                3, 1.2, 0.8, 1.2, 0.08);
        }
    }

    /**
     * The tick 20 pulse: every hostile projectile in the radius
     * pops and vanishes, the exhale ring bursts out, and a
     * submerged elder drinks deep. See the class comment.
     */
    private void firePulse(AbilityContext ctx, ServerLevel level) {
        Mob mob = ctx.mob();
        AABB box = new AABB(mob.getX() - PULSE_RADIUS, mob.getY() - 8.0, mob.getZ() - PULSE_RADIUS,
                            mob.getX() + PULSE_RADIUS, mob.getY() + 8.0, mob.getZ() + PULSE_RADIUS);
        List<Projectile> projectiles = level.getEntitiesOfClass(Projectile.class, box);
        for (Projectile projectile : projectiles) {
            if (!isHostile(ctx, projectile)) {
                continue;
            }
            level.sendParticles(ParticleTypes.POOF, projectile.getX(), projectile.getY(), projectile.getZ(),
                4, 0.15, 0.15, 0.15, 0.05);
            projectile.discard();
        }

        // The exhale.
        level.sendParticles(ParticleTypes.SPLASH, mob.getX(), mob.getY() + 1.0, mob.getZ(),
            16, 5.0, 0.6, 5.0, 0.25);
        level.sendParticles(ParticleTypes.BUBBLE, mob.getX(), mob.getY() + 1.0, mob.getZ(),
            16, 5.0, 0.6, 5.0, 0.2);
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
            SoundEvents.ELDER_GUARDIAN_AMBIENT, SoundSource.HOSTILE, 1.5f, 0.6f);
        if (mob.isInWater()) {
            mob.heal(4.0f);
        }
    }

    /**
     * Whether a projectile is hostile to the elder: ownerless, or
     * owned by a living enemy (package rule). The elder's own and
     * its allies' projectiles are never hostile.
     */
    private static boolean isHostile(AbilityContext ctx, Projectile projectile) {
        Entity owner = projectile.getOwner();
        if (owner == null) {
            return true;
        }
        if (owner == ctx.mob()) {
            return false;
        }
        return owner instanceof LivingEntity living
            && ElderGuardianAbilitySupport.isEnemy(ctx, living);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // The sustain ends with the window. Permanent upgrade
        // regeneration, if the elder owns any, is re-applied by
        // ScalingSystem (see the class comment).
        ctx.mob().removeEffect(MobEffects.REGENERATION);
    }
}
