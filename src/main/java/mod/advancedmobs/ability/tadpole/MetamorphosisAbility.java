package mod.advancedmobs.ability.tadpole;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.frog.Frog;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Metamorphosis (Tadpole ability 3 of 5, transformation).
 *
 * WHAT IT DOES: the fight forces the issue. Mid battle
 * the tadpole's tail shrinks, legs sprout, and in a burst
 * of happy green sparkles it comes out the other side a
 * frog: bigger, tougher, at home on land and water alike,
 * and already leaping at whatever it was fighting. The
 * tadpole is gone; the frog is the fight now.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the tadpole's current target within 14 blocks
 *   (distance squared 196), AND at least one pressure
 *   condition: the tadpole is at or below 50 percent of
 *   its max health, OR the tadpole is out of water
 *   (beached and flopping, where a tadpole is helpless
 *   and a frog is not). In open water at full health the
 *   change never comes early.
 * - The transformation happens entirely in start(), in
 *   this order (the Stormstruck Revenant pattern: build
 *   the new mob first, then discard the caster):
 *   1. The frog is created at the tadpole's position
 *      with the tadpole's facing, with
 *      EntitySpawnReason.MOB_SUMMONED and finalizeSpawn,
 *      which also picks the frog's variant from the
 *      biome the way vanilla growth does.
 *   2. The grown sheet, as PERMANENT base values on the
 *      new mob (not transient modifiers; there is no
 *      end() reversal because the frog is not an effect,
 *      it is a new mob): SCALE base value 1.2 (a big
 *      frog), MAX_HEALTH base value 20.0 (double a wild
 *      frog's 10, the design's fresh health surge), and
 *      health set to the full 20. The frog keeps its
 *      vanilla ATTACK_DAMAGE of 10. STRENGTH I for 600
 *      ticks rides on top (the design's strength line),
 *      an ordinary effect on its own clock.
 *   3. What the code carries over (the design's
 *      "passengers and data carried in code" line, for
 *      everything code can honestly carry): any
 *      passengers riding the tadpole are moved onto the
 *      frog; the tadpole's whole ally set is copied into
 *      the frog's war data, and every resolvable ally
 *      gains the frog in return, so the war network does
 *      not lose the fighter it had; the pair is
 *      registered in SummonerTracker (which also tags
 *      the frog umw_summoned for the natural spawn
 *      blocker); and the frog is ordered onto the
 *      tadpole's current target, so the fight continues
 *      seamlessly.
 *   4. The burst: TADPOLE_GROW_UP at volume 1.2, pitch
 *      1.0 (the vanilla sound of exactly this moment),
 *      20 HAPPY_VILLAGER plus 12 POOF particles at the
 *      transformation point (the design's two particle
 *      lines).
 *   5. The tadpole is discarded. This is deliberate and
 *      is the transformation: the tadpole does not hide
 *      or flee, it BECOMES the frog. Discarding also
 *      runs the framework's termination path, which
 *      calls end() exactly once and clears the
 *      activation with no cooldown.
 * - What does NOT carry over, stated plainly so it is
 *   not mistaken for a bug: the tadpole's own MobWarData
 *   (its earned points, purchased upgrades, and ability
 *   grants) goes with the tadpole. ScalingSystem
 *   recomputes a mob's points every tick from per-mob
 *   components that belong to the tadpole's own record,
 *   so there is no honest number to hand the frog; the
 *   frog starts its own record with a grown body's base
 *   sheet and the ally network above, which is the price
 *   of the transformation (the Stormstruck Revenant
 *   precedent, the same rule for the pig).
 * - Duration: none of its own. isFinished() returns true
 *   from the first tick as a backstop, in case a future
 *   change ever leaves the tadpole alive: the ability is
 *   one-shot either way.
 * - end(): deliberately does NOTHING to the frog. The
 *   frog is a permanent new combatant, the tadpole's
 *   second phase, and it stays in the world fighting on
 *   its own war data after the ability is gone.
 * - Failure handling: if the frog cannot be created or
 *   added to the level, the tadpole is NOT discarded and
 *   nothing else happens: the ability simply finishes
 *   with nothing changed, rather than deleting the
 *   tadpole for nothing.
 *
 * WHAT IT LOOKS LIKE: a spray of green sparkles and
 * poof, the grow-up croak-chime, and a frog a fifth again
 * too big sitting in the ripple where a tadpole was,
 * already turning on its enemy with fresh strength.
 *
 * PERFORMANCE: everything happens once in start(): one
 * mob spawn, a handful of attribute base writes, one
 * ally-set copy (bounded by the tadpole's own ally
 * count), 32 particles. tick() and end() do no world
 * work at all. Particle budget per activation: 32.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "frog" (the new frog's UUID stored as
 * a string for debugging; nothing reads it back, the
 * frog is autonomous once spawned).
 */
public final class MetamorphosisAbility implements MobAbility {

    private static final double LOW_HEALTH_FRACTION = 0.50;
    private static final double FROG_SCALE = 1.2;
    private static final double FROG_MAX_HEALTH = 20.0;
    private static final int STRENGTH_TICKS = 600;

    @Override
    public String id() {
        return "metamorphosis";
    }

    @Override
    public String displayName() {
        return "Metamorphosis";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null || ctx.mob().distanceToSqr(target) > 196.0) {
            return false;
        }
        Mob caster = ctx.mob();
        boolean lowHealth = caster.getHealth() <= caster.getMaxHealth() * LOW_HEALTH_FRACTION;
        return lowHealth || !caster.isInWater();
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();

        // Create the frog FIRST: if it cannot be made,
        // the tadpole keeps its life and nothing happens.
        Frog frog = EntityTypes.FROG.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (frog == null) {
            return;
        }
        frog.snapTo(caster.getX(), caster.getY(), caster.getZ(), caster.getYRot(), 0.0f);
        frog.finalizeSpawn(level, level.getCurrentDifficultyAt(frog.blockPosition()),
            EntitySpawnReason.MOB_SUMMONED, null);

        // The grown sheet, as permanent base values on
        // the new mob (see the class comment).
        if (frog.getAttribute(Attributes.SCALE) != null) {
            frog.getAttribute(Attributes.SCALE).setBaseValue(FROG_SCALE);
        }
        if (frog.getAttribute(Attributes.MAX_HEALTH) != null) {
            frog.getAttribute(Attributes.MAX_HEALTH).setBaseValue(FROG_MAX_HEALTH);
        }
        frog.setHealth((float) FROG_MAX_HEALTH);
        frog.addEffect(new MobEffectInstance(MobEffects.STRENGTH, STRENGTH_TICKS, 0));

        if (!level.addFreshEntity(frog)) {
            return;
        }

        // Passengers ride the new body (the design's
        // passengers line).
        for (Entity passenger : new ArrayList<>(caster.getPassengers())) {
            passenger.stopRiding();
            passenger.startRiding(frog, true, true);
        }

        // The war network carries over: the frog inherits
        // the tadpole's ally set, every resolvable ally
        // gains the frog in return, and the pair is
        // registered with the summoner tracker.
        MobWarData frogData = MobWarData.get(frog);
        List<UUID> allies = new ArrayList<>(ctx.data().getAllies());
        for (UUID allyId : allies) {
            frogData.addAlly(allyId);
            Entity allyEntity = level.getEntity(allyId);
            if (allyEntity instanceof Mob allyMob) {
                MobWarData.get(allyMob).addAlly(frog.getUUID());
            }
        }
        ctx.data().addAlly(frog.getUUID());
        frogData.addAlly(caster.getUUID());
        SummonerTracker.registerSummoned(frog.getUUID(), caster.getUUID());
        LivingEntity target = ctx.target();
        if (target != null) {
            frog.setTarget(target);
        }
        ctx.state().putString("frog", frog.getUUID().toString());

        // The burst: sparkles, poof, and the grow-up sound.
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.TADPOLE_GROW_UP, SoundSource.HOSTILE, 1.2f, 1.0f);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
            caster.getX(), caster.getY() + 0.4, caster.getZ(),
            20, 0.5, 0.4, 0.5, 0.05);
        level.sendParticles(ParticleTypes.POOF,
            caster.getX(), caster.getY() + 0.4, caster.getZ(),
            12, 0.5, 0.4, 0.5, 0.05);

        // The transformation itself: the tadpole becomes
        // the frog and does not come back.
        caster.discard();
    }

    @Override
    public void tick(AbilityContext ctx) {
        // One-shot: the work is all in start(). The caster
        // is normally already discarded, so this never runs.
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return true;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Deliberately empty: the frog is permanent and
        // autonomous. See the class comment.
    }
}
