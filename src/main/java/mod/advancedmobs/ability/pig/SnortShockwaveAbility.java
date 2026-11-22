package mod.advancedmobs.ability.pig;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Snort Shockwave (Pig ability 5 of 5, aura / control).
 *
 * WHAT IT DOES: the pig plants its feet and lets out a
 * snort so violent it fires a visible gust ring and a
 * cloud of ash gray breath. Everything in front of it is
 * shoved back and weakened, and anything small is knocked
 * clean off its feet.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the pig's current target within 8 blocks
 *   (distance squared 64).
 * - Duration: 20 ticks. The blast itself fires once, in
 *   start(); the remaining ticks are the pig's heaving
 *   recovery (navigation stopped every tick, never NoAi,
 *   2 ASH breath motes at the snout every 4 ticks).
 * - The blast (start()): one bounded query at radius 6.0
 *   (1.5 below to 2.5 above). An enemy is caught when its
 *   horizontal direction from the pig is inside the
 *   frontal cone: the dot product of the pig's horizontal
 *   look direction with the normalized direction to the
 *   enemy is at least 0.3 (about a 145 degree cone), or
 *   the enemy is within 1.5 blocks (point blank is always
 *   in front). Each caught enemy gains:
 *   - WEAKNESS I for 100 ticks (the design's weakness
 *     line; it rides its own clock past the ability's
 *     end and is not removed in end()).
 *   - The shove: 1.3 horizontal away from the pig and
 *     0.4 up, hurtMarked set.
 *   - Small mobs are knocked clean off their feet: an
 *     enemy whose bounding box height is under 1.0 blocks
 *     (silverfish, endermites, small slimes and cubes,
 *     baby mobs, and the like) gets the big launch
 *     instead: 1.9 horizontal and 0.75 up.
 *   The blast deals NO direct damage: it is pure control.
 * - The gust's strength (start(), removed in end()):
 *   ATTACK_KNOCKBACK add_value +2.0 (id
 *   advancedmobs:snort_knockback; the design's
 *   attack_knockback line) on the pig for the whole
 *   20-tick activation, so any vanilla contact the pig
 *   makes while recovering also shoves hard. The blast's
 *   own shove is code velocity, above, and does not
 *   depend on the attribute.
 * - The look (start()): 10 GUST particles in a ring at
 *   radius 1.5 around the pig's head height plus 8 ASH
 *   breath motes bursting forward from the snout (the
 *   design's gust and ash lines), and the pig's classic
 *   ambient sound at volume 2.0, pitch 0.7: a deep,
 *   carrying snort.
 *
 * WHAT IT LOOKS LIKE: a visible ring of wind bursting off
 * the pig's face in a cloud of gray breath, enemies in
 * front of it stumbling back weakened, and the little
 * ones tumbling head over heels.
 *
 * PERFORMANCE: start() does one bounded radius-6.0 query
 * and 18 particles; tick() is one navigation stop and one
 * emission every 4 ticks; end() removes one modifier.
 * Particle budget per activation: 18 at start plus 5
 * times 2 = 10 in recovery, total 28 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply. GUST and ASH are particles, the shove is
 * velocity, the weakness is an effect.
 *
 * State tag keys: none beyond the framework's own; the
 * blast is one-shot in start() and the recovery reads
 * ticksActive() directly.
 */
public final class SnortShockwaveAbility implements MobAbility {

    private static final int DURATION_TICKS = 20;
    private static final double BLAST_RADIUS = 6.0;
    private static final double CONE_DOT = 0.3;
    private static final double POINT_BLANK = 1.5;
    private static final double SMALL_HEIGHT = 1.0;
    private static final double SHOVE_HORIZONTAL = 1.3;
    private static final double SHOVE_VERTICAL = 0.4;
    private static final double BIG_LAUNCH_HORIZONTAL = 1.9;
    private static final double BIG_LAUNCH_VERTICAL = 0.75;
    private static final int WEAKNESS_TICKS = 100;
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "snort_knockback");

    @Override
    public String id() {
        return "snort_shockwave";
    }

    @Override
    public String displayName() {
        return "Snort Shockwave";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 64.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        PigAbilitySupport.addModifier(caster, Attributes.ATTACK_KNOCKBACK,
            new AttributeModifier(KNOCKBACK_ID, 2.0, AttributeModifier.Operation.ADD_VALUE));

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Vec3 look = caster.getLookAngle();
        double lookX = look.x;
        double lookZ = look.z;
        double lookLength = Math.sqrt(lookX * lookX + lookZ * lookZ);
        if (lookLength < 0.001) {
            lookX = 0.0;
            lookZ = 1.0;
            lookLength = 1.0;
        }
        lookX /= lookLength;
        lookZ /= lookLength;

        // The blast: every enemy in the frontal cone.
        List<LivingEntity> caught = PigAbilitySupport.enemiesInBox(
            ctx, caster.getX(), caster.getY(), caster.getZ(), BLAST_RADIUS, 1.5, 2.5);
        for (LivingEntity victim : caught) {
            double dx = victim.getX() - caster.getX();
            double dz = victim.getZ() - caster.getZ();
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            if (horizontal > BLAST_RADIUS) {
                continue;
            }
            double dot = horizontal < 0.001 ? 1.0 : (dx * lookX + dz * lookZ) / horizontal;
            if (dot < CONE_DOT && horizontal > POINT_BLANK) {
                continue;
            }
            victim.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, WEAKNESS_TICKS, 0));
            boolean small = victim.getBbHeight() < SMALL_HEIGHT;
            double shove = small ? BIG_LAUNCH_HORIZONTAL : SHOVE_HORIZONTAL;
            double lift = small ? BIG_LAUNCH_VERTICAL : SHOVE_VERTICAL;
            double scale = horizontal < 0.001 ? 0.0 : shove / horizontal;
            victim.setDeltaMovement(dx * scale, lift, dz * scale);
            victim.hurtMarked = true;
        }

        // The look: a gust ring at head height and the
        // ash gray breath bursting forward.
        for (int i = 0; i < 10; i++) {
            double angle = Math.toRadians(i * 36.0);
            level.sendParticles(ParticleTypes.GUST,
                caster.getX() + Math.cos(angle) * 1.5, caster.getY() + 0.9,
                caster.getZ() + Math.sin(angle) * 1.5,
                1, 0.05, 0.05, 0.05, 0.02);
        }
        level.sendParticles(ParticleTypes.ASH,
            caster.getX() + lookX * 0.9, caster.getY() + 0.7, caster.getZ() + lookZ * 0.9,
            8, 0.3, 0.25, 0.3, 0.08);
        if (PigAbilitySupport.pigSound() != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                PigAbilitySupport.pigSound(), SoundSource.HOSTILE, 2.0f, 0.7f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.getNavigation().stop();
        ServerLevel level = ctx.serverLevel();
        if (level == null || ctx.ticksActive() % 4 != 0) {
            return;
        }
        // The heaving recovery: spent breath at the snout.
        Vec3 look = caster.getLookAngle();
        level.sendParticles(ParticleTypes.ASH,
            caster.getX() + look.x * 0.7, caster.getY() + 0.7, caster.getZ() + look.z * 0.7,
            2, 0.2, 0.15, 0.2, 0.04);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        PigAbilitySupport.removeModifier(ctx.mob(), Attributes.ATTACK_KNOCKBACK, KNOCKBACK_ID);
    }
}
