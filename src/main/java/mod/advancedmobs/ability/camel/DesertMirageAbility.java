package mod.advancedmobs.ability.camel;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
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
 * Desert Mirage (Camel ability 1 of 5, control).
 *
 * WHAT IT DOES: the camel kicks up a choking veil of dust
 * and smoke, vanishes inside it, and slips away while the
 * enemies caught in the haze are blinded and left swinging
 * at sand. When the veil settles the camel is somewhere
 * else, invisible, and the fight has to find it again.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the camel's current target within 14 blocks (distance
 *   squared 196).
 * - The veil (start(), one bounded query): 12 sand DUST
 *   motes (0xD8C08A, scale 1.5), 6 LARGE_SMOKE, and 4 CLOUD
 *   particles burst at the camel's body. Every enemy within
 *   radius 5.0 (vertical reach 2 below, 3 above) gains
 *   BLINDNESS for 100 ticks, amplifier 0 (for a player that
 *   is real vision fog; for a mob the bite is the next
 *   clause). Every enemy Mob in the veil whose current
 *   target is the camel has its target cleared: it loses
 *   the camel in the haze and swings at sand until its AI
 *   picks a new target on its own.
 * - The slip (start()): the camel gains INVISIBILITY for
 *   220 ticks (removed exactly in end()) and teleports to a
 *   reposition point: 6.5 blocks from its start position in
 *   the direction directly away from its target,
 *   horizontally, plus a random lateral offset of up to
 *   plus or minus 2.5 blocks, with the landing height from
 *   one bounded surface scan (CamelAbilitySupport.
 *   findGroundY around the camel's own height). CAMEL_
 *   AMBIENT plays at the landing point at volume 1.0, pitch
 *   0.8: a soft grunt from somewhere in the dust. If the
 *   target is somehow gone in start(), the camel stays put
 *   and only the veil and invisibility happen.
 * - The waver (tick(), every 4 ticks): 2 sand DUST motes
 *   and 1 CLOUD particle drift at the camel's body, the
 *   haze that follows it and makes its hidden outline
 *   shimmer at the edges.
 * - Duration: exactly 200 ticks (10 seconds), hard finish.
 * - end(): the INVISIBILITY effect is removed. The
 *   blindness on victims rides its own clock and is not
 *   stripped. Idempotent.
 *
 * WHAT IT LOOKS LIKE: a sudden wall of tan dust and grey
 * smoke where the camel stood, a grunt from inside it, and
 * then nothing: just drifting haze motes around an empty
 * patch of desert, while the blinded enemies mill about
 * where it used to be.
 *
 * PERFORMANCE: start() is one bounded radius-5.0 query, one
 * surface scan of at most 12 block reads, one teleport, and
 * one particle burst; tick() emits 3 particles every 4
 * ticks and runs no queries. Particle budget per
 * activation: 22 at start plus 50 pulses times 3 = 150,
 * total at most 172 particles.
 *
 * TERRAIN: this ability changes no blocks (dust, smoke, an
 * effect, and a teleport); the mobGriefing gate does not
 * apply.
 *
 * State tag keys: none beyond the framework's; the
 * activation is pure counters and the framework tick count.
 */
public final class DesertMirageAbility implements MobAbility {

    private static final int DURATION_TICKS = 200;
    private static final double VEIL_RADIUS = 5.0;
    private static final int BLINDNESS_TICKS = 100;
    private static final int INVISIBILITY_TICKS = DURATION_TICKS + 20;
    private static final double SLIP_DISTANCE = 6.5;
    private static final double SLIP_LATERAL = 2.5;
    private static final DustParticleOptions SAND_DUST = new DustParticleOptions(0xD8C08A, 1.5f);

    @Override
    public String id() {
        return "desert_mirage";
    }

    @Override
    public String displayName() {
        return "Desert Mirage";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        // The veil bursts where the camel stands.
        level.sendParticles(SAND_DUST, caster.getX(), caster.getY() + 1.0, caster.getZ(),
            12, 0.8, 0.8, 0.8, 0.06);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, caster.getX(), caster.getY() + 1.0, caster.getZ(),
            6, 0.7, 0.7, 0.7, 0.04);
        level.sendParticles(ParticleTypes.CLOUD, caster.getX(), caster.getY() + 1.2, caster.getZ(),
            4, 0.7, 0.5, 0.7, 0.03);

        // Blinded, and the mobs in the haze lose the camel entirely.
        List<LivingEntity> caught = CamelAbilitySupport.enemiesAroundSelf(ctx, VEIL_RADIUS, 2.0, 3.0);
        for (LivingEntity victim : caught) {
            victim.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, BLINDNESS_TICKS, 0));
            if (victim instanceof Mob victimMob && victimMob.getTarget() == caster) {
                victimMob.setTarget(null);
            }
        }

        // The slip: invisible, and gone from where it stood.
        caster.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, INVISIBILITY_TICKS, 0));
        LivingEntity target = ctx.target();
        if (target == null) {
            return;
        }
        Vec3 away = new Vec3(caster.getX() - target.getX(), 0.0, caster.getZ() - target.getZ());
        if (away.lengthSqr() < 1.0e-4) {
            away = new Vec3(1.0, 0.0, 0.0);
        }
        away = away.normalize();
        double lateral = (ctx.random().nextDouble() * 2.0 - 1.0) * SLIP_LATERAL;
        double destX = caster.getX() + away.x * SLIP_DISTANCE - away.z * lateral;
        double destZ = caster.getZ() + away.z * SLIP_DISTANCE + away.x * lateral;
        double destY = CamelAbilitySupport.findGroundY(level, destX, caster.getY() + 1.0, destZ);
        caster.teleportTo(destX, destY, destZ);
        level.playSound(null, destX, destY, destZ, SoundEvents.CAMEL_AMBIENT,
            SoundSource.HOSTILE, 1.0f, 0.8f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % 4 != 0) {
            return;
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        // The haze follows the hidden camel.
        level.sendParticles(SAND_DUST, caster.getX(), caster.getY() + 1.0, caster.getZ(),
            2, 0.5, 0.6, 0.5, 0.03);
        level.sendParticles(ParticleTypes.CLOUD, caster.getX(), caster.getY() + 1.2, caster.getZ(),
            1, 0.5, 0.4, 0.5, 0.02);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        ctx.mob().removeEffect(MobEffects.INVISIBILITY);
    }
}
