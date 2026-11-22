package mod.advancedmobs.ability.glowsquid;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.List;

/**
 * Starfall Ink (Glow Squid ability 3 of 5, attack).
 *
 * WHAT IT DOES: the glow squid rises above the
 * fight and rains glowing ink. Fat droplets of
 * glow_squid_ink arc down in a sparkling curtain
 * over its target, and every droplet that lands on
 * an enemy burns it with magic damage and leaves it
 * glowing, until the whole enemy school hangs in
 * the water as a constellation of outlined targets.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat
 *   gate, plus the squid's current target within 16
 *   blocks (distance squared 256), plus the squid in
 *   water.
 * - The rise (every tick): the caster is steered at
 *   0.6 blocks per tick toward a hover point 5.5
 *   blocks above its live target (the target's last
 *   recorded position when the target is lost), so
 *   it climbs over the fight and stays there while
 *   the rain lasts.
 * - The rain (ticks 10 to 110, every 5 ticks): 2 new
 *   droplets spawn at random horizontal offsets
 *   within 3.5 blocks of the target's live position,
 *   at 6.0 blocks above it. A droplet is pure state
 *   (position plus age in the state tag's drop list,
 *   capped at 40): each tick it falls 0.8 blocks and
 *   draws 1 GLOW_SQUID_INK particle at its position.
 *   At age 8 (about 6.4 blocks fallen) it bursts.
 *   GLOW_SQUID_SQUIRT plays at the first rain tick
 *   (volume 1.0, pitch 1.1).
 * - The burst: one bounded query at the droplet's
 *   position, radius 1.5, vertical reach 2.0 both
 *   ways: every enemy (the package enemy rule)
 *   takes 2.5 points of magic damage through the
 *   indirect magic source credited to the caster
 *   (the design's "/damage with magic damage type":
 *   magic bypasses armor, and the caster keeps the
 *   kill credit) and gains GLOWING for 100 ticks.
 *   The burst draws 4 GLOW_SQUID_INK.
 * - Duration: the rain ends at tick 110; the ability
 *   finishes when the last droplet has burst, with a
 *   hard cap of 150 ticks (a droplet still falling
 *   at the cap simply never bursts).
 *
 * WHAT IT LOOKS LIKE: a glow squid climbing into
 * the dark above its enemies, then a slow sparkling
 * rain of bright droplets, each one that connects
 * flaring and leaving another enemy outlined.
 *
 * PERFORMANCE: spawning is 2 state entries every 5
 * ticks; the droplet pass resolves at most 40
 * entries per tick with 1 particle send each and a
 * bounded query only on a burst tick (at most 40
 * bursts). The rise is one steering write per tick.
 * Particle budget per activation: droplet draws at
 * most 40 times 8 = 320, bursts at most 40 times 4
 * = 160, total at most 480.
 *
 * TERRAIN: this ability changes no blocks; the ink
 * is particles and the damage is a typed damage
 * call, so the mobGriefing gate does not apply.
 *
 * CLEANUP: end() has nothing to strip: the caster
 * takes no modifiers or self effects, and unburst
 * droplets are state only and die with the tag.
 * Victim GLOWING expires on its own clock.
 *
 * State tag keys: "drops" (list of compounds: "x",
 * "y", "z", "age", one per falling droplet), "tx",
 * "ty", "tz" (the target's last recorded position,
 * the rain and hover fallback).
 */
public final class StarfallInkAbility implements MobAbility {

    private static final int HARD_CAP_TICKS = 150;
    private static final int RAIN_START_TICK = 10;
    private static final int RAIN_END_TICK = 110;
    private static final int SPAWN_INTERVAL = 5;
    private static final int DROPS_PER_SPAWN = 2;
    private static final int DROP_CAP = 40;
    private static final double RAIN_RADIUS = 3.5;
    private static final double RAIN_HEIGHT = 6.0;
    private static final double FALL_SPEED = 0.8;
    private static final int BURST_AGE = 8;
    private static final float DROP_DAMAGE = 2.5f;

    @Override
    public String id() {
        return "starfall_ink";
    }

    @Override
    public String displayName() {
        return "Starfall Ink";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().isInWater()
            && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        state.put("drops", new ListTag());
        LivingEntity target = ctx.target();
        Mob caster = ctx.mob();
        state.putDouble("tx", target != null ? target.getX() : caster.getX());
        state.putDouble("ty", target != null ? target.getY() : caster.getY());
        state.putDouble("tz", target != null ? target.getZ() : caster.getZ());
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();

        // Track the target for the hover and the rain.
        LivingEntity target = ctx.target();
        if (target != null) {
            state.putDouble("tx", target.getX());
            state.putDouble("ty", target.getY());
            state.putDouble("tz", target.getZ());
        }
        double tx = state.getDoubleOr("tx", caster.getX());
        double ty = state.getDoubleOr("ty", caster.getY());
        double tz = state.getDoubleOr("tz", caster.getZ());

        // The rise: hover above the fight.
        GlowSquidAbilitySupport.steerToward(caster, tx, ty + 5.5, tz, 0.6);

        // The rain: spawn new droplets on the interval.
        if (ticks >= RAIN_START_TICK && ticks <= RAIN_END_TICK && ticks % SPAWN_INTERVAL == 0) {
            if (ticks == RAIN_START_TICK) {
                level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                    SoundEvents.GLOW_SQUID_SQUIRT, SoundSource.HOSTILE, 1.0f, 1.1f);
            }
            ListTag drops = state.getListOrEmpty("drops");
            for (int i = 0; i < DROPS_PER_SPAWN && drops.size() < DROP_CAP; i++) {
                double angle = ctx.random().nextDouble() * Math.PI * 2.0;
                double dist = ctx.random().nextDouble() * RAIN_RADIUS;
                CompoundTag drop = new CompoundTag();
                drop.putDouble("x", tx + Math.cos(angle) * dist);
                drop.putDouble("y", ty + RAIN_HEIGHT);
                drop.putDouble("z", tz + Math.sin(angle) * dist);
                drop.putInt("age", 0);
                drops.add(drop);
            }
            state.put("drops", drops);
        }

        // The fall: move, draw, and burst the droplets.
        ListTag drops = state.getListOrEmpty("drops");
        ListTag next = new ListTag();
        for (int i = 0; i < drops.size(); i++) {
            CompoundTag drop = drops.getCompoundOrEmpty(i);
            double x = drop.getDoubleOr("x", caster.getX());
            double y = drop.getDoubleOr("y", caster.getY()) - FALL_SPEED;
            double z = drop.getDoubleOr("z", caster.getZ());
            int age = drop.getIntOr("age", 0) + 1;
            level.sendParticles(ParticleTypes.GLOW_SQUID_INK, x, y, z,
                1, 0.05, 0.05, 0.05, 0.0);
            if (age >= BURST_AGE) {
                burst(ctx, level, x, y, z);
            } else {
                drop.putDouble("y", y);
                drop.putInt("age", age);
                next.add(drop);
            }
        }
        state.put("drops", next);
    }

    /** One droplet landing: magic damage and the glowing mark on everything it touches. */
    private void burst(AbilityContext ctx, ServerLevel level, double x, double y, double z) {
        level.sendParticles(ParticleTypes.GLOW_SQUID_INK, x, y, z,
            4, 0.4, 0.3, 0.4, 0.05);
        List<LivingEntity> caught = GlowSquidAbilitySupport.enemiesInBox(ctx, x, y, z, 1.5, 2.0, 2.0);
        Mob caster = ctx.mob();
        for (LivingEntity victim : caught) {
            victim.hurtServer(level, level.damageSources().indirectMagic(caster, caster), DROP_DAMAGE);
            victim.addEffect(new MobEffectInstance(MobEffects.GLOWING, 100, 0));
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks >= HARD_CAP_TICKS) {
            return true;
        }
        return ticks > RAIN_END_TICK && ctx.state().getListOrEmpty("drops").isEmpty();
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to strip: no caster modifiers or self effects.
        // Unburst droplets are state only and die with the tag.
    }
}
