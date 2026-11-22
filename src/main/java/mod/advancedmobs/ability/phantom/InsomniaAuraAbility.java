package mod.advancedmobs.ability.phantom;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.List;

/**
 * Insomnia Aura (Phantom ability 2 of 5, aura).
 *
 * WHAT IT DOES: the phantom takes up a slow station over
 * the fight and a ring of purple sparkles drifts around it
 * while the sleepless dread builds in everything beneath
 * it. Enemies caught in the aura are kept nauseous and
 * weak, and every moment they spend under it feeds the
 * phantom: its own strength grows in steps the longer the
 * aura has been biting, so the dive that finally comes out
 * of the circling hits harder.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the phantom's current target within 20 blocks (distance
 *   squared 400).
 * - Duration: 400 ticks (20 seconds), hard finish.
 * - The aura volume: a box of horizontal radius 10 around
 *   the phantom, reaching 14 blocks below it and 4 above
 *   (phantoms fight from above; the dread is for the
 *   enemies beneath it, per the design).
 * - The pulse (every 10 ticks): one bounded query finds
 *   the enemies in the volume; each gains NAUSEA
 *   (amplifier 0) for 60 ticks and WEAKNESS I (amplifier
 *   0) for 40 ticks, refreshed each pulse, so an enemy that
 *   stays under the phantom stays sick and feeble and one
 *   that escapes bleeds both off within about 3 seconds.
 * - Dread (the design's scaling): each pulse adds 1 dread
 *   stack per enemy found in the volume, capped at 10
 *   stacks for the activation, stored in the state tag.
 *   After the dread is counted, the phantom's own STRENGTH
 *   is refreshed for 30 ticks at an amplifier set by the
 *   stack count: 1 to 3 stacks give amplifier 0 (STRENGTH
 *   I), 4 to 7 give amplifier 1 (STRENGTH II), 8 to 10
 *   give amplifier 2 (STRENGTH III). With no dread yet,
 *   no STRENGTH is applied. The stacks never decay during
 *   the activation: dread built is dread kept until the
 *   aura ends.
 * - The ring (every 5 ticks): 6 purple ENTITY_EFFECT motes
 *   (color 0.55, 0.20, 0.90, the pre-built option below) on
 *   a ring of radius 6.0 at 1 block below the phantom, the
 *   ring's angle offset advancing 0.15 radians per draw so
 *   the sparkles slowly orbit. Purely visual: the real
 *   aura is the pulse volume above.
 * - end(): the phantom's STRENGTH is removed (it is the
 *   ability's own timed application; a STRENGTH the
 *   phantom somehow also carried from another source is
 *   removed too, an accepted edge case shared with every
 *   fixed-duration effect in this system). The enemies'
 *   NAUSEA and WEAKNESS are NOT removed: they are
 *   fixed-duration debuffs that expire on their own clocks.
 *
 * WHAT IT LOOKS LIKE: a phantom circling high over the
 * fight inside a slow-turning ring of violet sparkles,
 * while everything on the ground below it sways with
 * nausea, swings weakly, and watches the circling shape
 * grow stronger the longer it stays.
 *
 * PERFORMANCE: the pulse is one bounded query every 10
 * ticks plus one effect application per enemy found; the
 * ring is 6 particles every 5 ticks; every other tick
 * returns immediately. No per-tick allocations beyond the
 * pulse's result list, which the query itself builds.
 * Particle budget per activation: ring 6 times 80 draws =
 * 480 particles, and nothing else.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply. The aura is a zone effect around the phantom,
 * not block placement.
 *
 * State tag keys: "dread" (stacks built so far, 0 to 10).
 */
public final class InsomniaAuraAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int PULSE_INTERVAL = 10;
    private static final int RING_INTERVAL = 5;
    private static final double AURA_RADIUS = 10.0;
    private static final double AURA_BELOW = 14.0;
    private static final double AURA_ABOVE = 4.0;
    private static final int NAUSEA_TICKS = 60;
    private static final int WEAKNESS_TICKS = 40;
    private static final int STRENGTH_TICKS = 30;
    private static final int DREAD_CAP = 10;
    // The design's purple ENTITY_EFFECT sparkle, pre-built once.
    private static final ColorParticleOption DREAD_SPARKLE =
        ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, 0.55f, 0.20f, 0.90f);

    @Override
    public String id() {
        return "insomnia_aura";
    }

    @Override
    public String displayName() {
        return "Insomnia Aura";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 400.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().putInt("dread", 0);
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks % PULSE_INTERVAL == 0) {
            pulse(ctx);
        }
        if (ticks % RING_INTERVAL == 0) {
            ring(ctx);
        }
    }

    /** The dread pulse (see the class comment): debuffs below, strength above. */
    private static void pulse(AbilityContext ctx) {
        Mob caster = ctx.mob();
        List<LivingEntity> enemies = PhantomAbilitySupport.enemiesInBox(ctx,
            caster.getX(), caster.getY(), caster.getZ(), AURA_RADIUS, AURA_BELOW, AURA_ABOVE);
        for (LivingEntity enemy : enemies) {
            enemy.addEffect(new MobEffectInstance(MobEffects.NAUSEA, NAUSEA_TICKS, 0));
            enemy.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, WEAKNESS_TICKS, 0));
        }
        int dread = Math.min(DREAD_CAP, ctx.state().getIntOr("dread", 0) + enemies.size());
        ctx.state().putInt("dread", dread);
        if (dread >= 1) {
            int amplifier = dread >= 8 ? 2 : dread >= 4 ? 1 : 0;
            caster.addEffect(new MobEffectInstance(MobEffects.STRENGTH, STRENGTH_TICKS, amplifier));
        }
    }

    /** The drifting purple sparkle ring (see the class comment). Visual only. */
    private static void ring(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        double offset = (ctx.ticksActive() / RING_INTERVAL) * 0.15;
        for (int i = 0; i < 6; i++) {
            double angle = offset + (Math.PI * 2.0 * i) / 6.0;
            level.sendParticles(DREAD_SPARKLE,
                caster.getX() + Math.cos(angle) * 6.0, caster.getY() - 1.0, caster.getZ() + Math.sin(angle) * 6.0,
                1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        ctx.mob().removeEffect(MobEffects.STRENGTH);
    }
}
