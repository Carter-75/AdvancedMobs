package mod.advancedmobs.ability.wolf;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.List;

/**
 * Guardian Stance (Wolf ability 5 of 5, defense).
 *
 * WHAT IT DOES: the wolf plants itself between its pack
 * and the enemy, hackles up, growling low. While the stance
 * holds it is hardened with RESISTANCE and a pool of
 * absorption hearts, and every few seconds its growl pulls
 * the attention of nearby enemies off its packmates and
 * onto itself.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the wolf's current target within 14 blocks (distance
 *   squared 196) AND at least one ally within 12 blocks
 *   (one bounded query). A guardian with nobody to guard
 *   never burns its cooldown on the stance.
 * - Duration: 300 ticks (15 seconds), hard finish.
 * - The stance (start()): RESISTANCE I (amplifier 0) for
 *   320 ticks and ABSORPTION II (amplifier 1, a pool of 8
 *   absorption hearts) for 320 ticks on the wolf, the
 *   classic wolf variant's growl at volume 1.5, pitch 0.7
 *   (resolved through WolfAbilitySupport.wolfSound; skipped
 *   if the variant map has no classic entry), and a burst
 *   of 8 ANGRY_VILLAGER particles over the wolf (the
 *   design's raised-hackles snarl). Both effects are removed
 *   in end(), so the absorption pool does not outlive the
 *   stance (the Honey Coat precedent); removing ABSORPTION
 *   also clears any unspent absorption hearts with it.
 * - Holding ground (tick()): navigation is stopped every
 *   tick (never NoAi, per the standing contract). The wolf
 *   plants itself; its own vanilla melee goal may still
 *   snap at anything that comes into reach, but it does not
 *   chase.
 * - The taunt (tick(), every 10 ticks): one bounded query
 *   of radius 10 around the wolf. Every enemy Mob in it
 *   whose current target is one of the wolf's allies (the
 *   either-direction ally rule) or the wolf's own combat
 *   target has its target set to the wolf: the design's
 *   "attacks pulled toward the wolf instead of its
 *   packmates," done as target selection changes in code.
 *   Enemies fighting unrelated third parties are left
 *   alone, and players are never retargeted (a player's aim
 *   is their own). Each taunt pulse also puffs 2
 *   ANGRY_VILLAGER particles over the wolf, but only when
 *   at least one enemy was actually pulled, so an empty
 *   stance stays quiet.
 * - end(): removes the RESISTANCE and ABSORPTION effects
 *   (see above). Targets already pulled stay pulled; vanilla
 *   targeting re-evaluates them on its own from there.
 *
 * WHAT IT LOOKS LIKE: a wolf gone still and bristling,
 * golden absorption hearts over its health, a low growl,
 * red angry faces puffing over it whenever an enemy's
 * attention snaps onto it instead of the pack.
 *
 * PERFORMANCE: per tick one navigation stop. The taunt
 * runs every 10 ticks as one bounded radius-10 query plus
 * at most one ally check per enemy found; canTrigger's
 * ally check is one bounded query and only runs while the
 * framework is picking an ability. Particle budget per
 * activation: 8 at start plus at most 30 taunt pulses
 * times 2 = 60, total at most 68 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: none (the duration runs off the
 * framework's tick counter).
 */
public final class GuardianStanceAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int EFFECT_TICKS = 320;
    private static final int TAUNT_INTERVAL = 10;
    private static final double TAUNT_RADIUS = 10.0;
    private static final double GUARD_RADIUS = 12.0;

    @Override
    public String id() {
        return "guardian_stance";
    }

    @Override
    public String displayName() {
        return "Guardian Stance";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null || ctx.mob().distanceToSqr(target) > 196.0) {
            return false;
        }
        // Nobody to guard, no stance (see the class comment).
        Mob caster = ctx.mob();
        List<LivingEntity> allies = WolfAbilitySupport.alliesInBox(ctx,
            caster.getX(), caster.getY(), caster.getZ(), GUARD_RADIUS, 4.0, 4.0);
        return !allies.isEmpty();
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, EFFECT_TICKS, 0));
        caster.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, EFFECT_TICKS, 1));
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            Holder<SoundEvent> growl = WolfAbilitySupport.wolfSound(true);
            if (growl != null) {
                level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), growl,
                    SoundSource.HOSTILE, 1.5f, 0.7f);
            }
            level.sendParticles(ParticleTypes.ANGRY_VILLAGER, caster.getX(), caster.getY() + 0.9, caster.getZ(),
                8, 0.4, 0.3, 0.4, 0.05);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        // Holding ground: rooted via navigation stops, never NoAi.
        caster.getNavigation().stop();
        if (ctx.ticksActive() % TAUNT_INTERVAL != 0) {
            return; // Cheap early-out: the taunt pulses every 10 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        LivingEntity ownTarget = ctx.target();
        boolean pulled = false;
        List<LivingEntity> enemies = WolfAbilitySupport.enemiesInBox(ctx,
            caster.getX(), caster.getY(), caster.getZ(), TAUNT_RADIUS, 4.0, 4.0);
        for (LivingEntity enemy : enemies) {
            if (!(enemy instanceof Mob enemyMob)) {
                continue; // Players aim themselves; only mob targeting is pulled.
            }
            LivingEntity enemyTarget = enemyMob.getTarget();
            if (enemyTarget == caster) {
                continue;
            }
            if (enemyTarget == ownTarget || (enemyTarget != null && WolfAbilitySupport.isAlly(ctx, enemyTarget))) {
                enemyMob.setTarget(caster);
                pulled = true;
            }
        }
        if (pulled) {
            level.sendParticles(ParticleTypes.ANGRY_VILLAGER, caster.getX(), caster.getY() + 0.9, caster.getZ(),
                2, 0.4, 0.3, 0.4, 0.05);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.removeEffect(MobEffects.RESISTANCE);
        caster.removeEffect(MobEffects.ABSORPTION);
    }
}
