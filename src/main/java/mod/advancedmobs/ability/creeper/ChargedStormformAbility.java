package mod.advancedmobs.ability.creeper;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;

import java.lang.reflect.Field;

/**
 * Charged Stormform (Creeper ability 5 of 5, transformation).
 *
 * WHAT IT DOES: a bolt from the sky remakes the creeper. Lightning
 * strikes it in a flash of white, the vanilla charged state takes
 * hold, and a crackling blue aura sheathes its body. In stormform
 * it moves fast, ELECTRIC_SPARK arcs snap off it constantly, and
 * its outline glows electric blue, visible through walls and
 * weather. When the storm passes, the charge leaves it and it is
 * an ordinary creeper again.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   creeper's current target within 18 blocks (distance squared
 *   324), and the creeper is not already powered: a creeper that
 *   real lightning already charged keeps its permanent charge
 *   and never spends an activation pretending to gain it.
 * - Duration: 300 ticks (15 seconds) from start().
 * - The strike (start()): a LightningBolt is created at the
 *   creeper's position with setVisualOnly(true), so the bolt is
 *   pure theatre: it deals no damage, sets no fires, and calls no
 *   thunderHit on bystanders. The charged state itself is applied
 *   by calling Creeper.thunderHit with that bolt, the exact
 *   vanilla entry point a real strike uses, so the state the
 *   creeper gains is the genuine vanilla powered state in every
 *   respect (the blue aura sheath, the doubled vanilla explosion
 *   power, head drops on charged kills). thunderHit's super call
 *   also ignites the creeper the way a real strike would; that
 *   fire is a side effect of the visual-only bolt, not part of
 *   this ability, so start() calls clearFire() immediately after
 *   and the stormform creeper never burns. If the bolt entity
 *   cannot be created, the powered state is set directly through
 *   the data accessor described in CLEANUP instead.
 * - The storm kit, applied in start() for the full 300 ticks:
 *   SPEED amplifier 1 (+40 percent movement) and GLOWING
 *   amplifier 0 (the vanilla glowing outline, which the blue
 *   charged aura tints electric). Both are removed in end().
 * - While stormform lasts, the creeper's own behavior is vanilla
 *   charged creeper behavior: if it primes and detonates on its
 *   own during the window, that blast is the vanilla charged
 *   power 6 explosion, and its death ends this ability through
 *   the framework termination path like any other death.
 * - Looks while active: every 5 ticks, 3 ELECTRIC_SPARK particles
 *   snap around the body. At the strike itself: 1 white FLASH
 *   particle at the body and 12 ELECTRIC_SPARK, plus the
 *   LIGHTNING_BOLT_IMPACT and LIGHTNING_BOLT_THUNDER sounds at
 *   the creeper (the visual-only bolt is silent on its own).
 *
 * WHAT IT LOOKS LIKE: a silent bolt spears down out of a clear
 * sky with a white flash and a thunder crack, and the creeper
 * stands in the ringing quiet with the vanilla blue charge aura
 * crawling over it, sparks snapping off its shoulders, its
 * outline burning blue through the walls as it comes at double
 * pace.
 *
 * PERFORMANCE: tick() returns immediately except every 5th tick,
 * where it sends 3 particles. No scans, no lookups, no state
 * needed at all: the whole activation is the powered flag plus
 * two effects. Particle budget per activation: 13 at the strike
 * plus at most 60 pulses times 3 = 180, total at most 193.
 *
 * CLEANUP: end() removes SPEED and GLOWING, and clears the
 * powered state so the creeper goes back to ordinary. Vanilla
 * exposes isPowered() but no setter (the flag lives in the
 * private DATA_IS_POWERED synched data accessor, verified
 * against the 26.2 jar), so the clear writes the accessor
 * directly through getEntityData().set, with the accessor
 * resolved once by reflection into a static final below; 26.2 is
 * unobfuscated, so the field name is stable. If that resolution
 * ever failed (accessor null), end() leaves the powered state in
 * place rather than crashing: the creeper simply stays charged,
 * a graceful degradation, and start()'s fallback path is
 * unavailable for the same reason. All of end() is safe on a dead
 * or removed caster, so the termination path after a stormform
 * creeper blows itself up is a harmless pass.
 *
 * State tag keys: none. Duration rides on the framework tick
 * counter, the rest is entity state end() reverses.
 */
public final class ChargedStormformAbility implements MobAbility {

    static final int DURATION_TICKS = 300;
    private static final ColorParticleOption FLASH_WHITE =
        ColorParticleOption.create(ParticleTypes.FLASH, 1.0f, 1.0f, 1.0f);

    /**
     * The creeper's private powered data accessor, resolved once.
     * Null only if reflection is denied, in which case the powered
     * state cannot be set or cleared directly (see CLEANUP).
     */
    private static final EntityDataAccessor<Boolean> POWERED_ACCESSOR = resolvePoweredAccessor();

    private static EntityDataAccessor<Boolean> resolvePoweredAccessor() {
        try {
            Field field = Creeper.class.getDeclaredField("DATA_IS_POWERED");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            EntityDataAccessor<Boolean> accessor = (EntityDataAccessor<Boolean>) field.get(null);
            return accessor;
        } catch (ReflectiveOperationException | SecurityException e) {
            return null;
        }
    }

    @Override
    public String id() {
        return "charged_stormform";
    }

    @Override
    public String displayName() {
        return "Charged Stormform";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null || ctx.mob().distanceToSqr(target) > 324.0) {
            return false;
        }
        return !(ctx.mob() instanceof Creeper creeper) || !creeper.isPowered();
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        ServerLevel level = ctx.serverLevel();

        if (mob instanceof Creeper creeper && level != null) {
            // The bolt: visual only, pure theatre (see the class
            // comment). The charge comes from thunderHit, the
            // vanilla path, not from the bolt's own tick.
            LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (bolt != null) {
                bolt.snapTo(creeper.getX(), creeper.getY(), creeper.getZ());
                bolt.setVisualOnly(true);
                level.addFreshEntity(bolt);
                creeper.thunderHit(level, bolt);
            } else if (POWERED_ACCESSOR != null) {
                creeper.getEntityData().set(POWERED_ACCESSOR, true);
            }
            // thunderHit ignites like a real strike; the storm is
            // not a fire, so the fire goes out the same tick.
            creeper.clearFire();

            // The strike's flash, sparks, and thunder.
            level.sendParticles(FLASH_WHITE, creeper.getX(), creeper.getY() + 1.0, creeper.getZ(),
                1, 0.1, 0.4, 0.1, 0.0);
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, creeper.getX(), creeper.getY() + 1.0, creeper.getZ(),
                12, 0.5, 0.8, 0.5, 0.15);
            level.playSound(null, creeper.getX(), creeper.getY(), creeper.getZ(),
                SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.HOSTILE, 1.0f, 1.0f);
            level.playSound(null, creeper.getX(), creeper.getY(), creeper.getZ(),
                SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.HOSTILE, 0.7f, 1.1f);
        }

        // The storm kit: fast, and glowing through the walls.
        mob.addEffect(new MobEffectInstance(MobEffects.SPEED, DURATION_TICKS, 1));
        mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, DURATION_TICKS, 0));
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % 5 != 0) {
            return; // Cheap early-out: sparks snap every 5 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, mob.getX(), mob.getY() + 1.0, mob.getZ(),
            3, 0.5, 0.8, 0.5, 0.1);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        mob.removeEffect(MobEffects.SPEED);
        mob.removeEffect(MobEffects.GLOWING);
        // The charge leaves with the storm (see CLEANUP).
        if (mob instanceof Creeper creeper && POWERED_ACCESSOR != null) {
            creeper.getEntityData().set(POWERED_ACCESSOR, false);
        }
    }
}
