package mod.advancedmobs.ability.pillager;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.illager.AbstractIllager;

import java.util.List;

/**
 * Raid Captain's Call (Pillager ability 3 of 5, aura).
 *
 * WHAT IT DOES: the pillager raises its banner and sounds
 * the call: RAID_OMEN pours off it as every illager ally in
 * range surges with SPEED and STRENGTH and turns onto the
 * captain's target, and the captain's own FOLLOW_RANGE
 * stretches so it keeps surveying the whole field. A skirmish
 * becomes a coordinated push for as long as the call lasts.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   at least one illager ally (PillagerAbilitySupport ILLAGER
 *   ALLY RULE: an AbstractIllager with an either-direction
 *   alliance record, or one fighting the same target as the
 *   captain) within 16 blocks of the pillager (one bounded
 *   query). A lone captain's call rallies nobody, so an empty
 *   warband never burns the cooldown.
 * - Duration: 400 ticks (20 seconds).
 * - The captain's survey (start(), removed in end()):
 *   FOLLOW_RANGE +16 ADD_VALUE for the whole activation, so
 *   the pillager's own targeting and the war system's tracked
 *   target hold enemies across the field instead of dropping
 *   them at vanilla range.
 * - The surge pulse, every 20 ticks: every illager ally
 *   within radius 16 of the CAPTAIN (3 below to 3 above; the
 *   aura moves with him, unlike the Vindicator's planted
 *   standard) gains SPEED I and STRENGTH I, each for 40
 *   ticks. The 40 against the 20 tick pulse is deliberate:
 *   an ally that steps out keeps its surge for up to two
 *   seconds of bleed-off, and one holding formation is
 *   refreshed forever. Team targeting (the design's Uses
 *   list): on the same pulse, any rallied ally with no target
 *   of its own is ordered onto the captain's current target,
 *   so the warband fights as one instead of scattering. The
 *   captain itself does NOT gain the surge effects: the
 *   design gives him the survey (above), and the patrol gets
 *   the muscle.
 * - RAID_OMEN: every 4 ticks, 3 RAID_OMEN motes pour off the
 *   captain's banner height (his position plus 2.2), spread
 *   0.3, slow rise.
 * - Killing the captain mid-call cuts the buff short (the
 *   design's line): the framework termination path runs
 *   end() on his death, the pulses stop at once, and the
 *   FOLLOW_RANGE stretch is removed. The surge effects
 *   already handed out are ordinary timed effects and bleed
 *   off on their own clocks, at most 40 ticks after the last
 *   pulse: end() deliberately does not strip them, the same
 *   bleed-off the Patrol Captain's Standard documents.
 * - Sounds: a PILLAGER_CELEBRATE shout (volume 1.0, pitch
 *   0.9) as the call sounds.
 *
 * WHAT IT LOOKS LIKE: purple RAID_OMEN smoke curling off the
 * captain's banner while the illagers around him suddenly
 * move faster, hit harder, and all turn on the same target
 * at once.
 *
 * TERRAIN: the call places and breaks no blocks, so the
 * terrain gate does not apply in either mobGriefing mode.
 *
 * PERFORMANCE: the surge is one bounded query every 20 ticks,
 * the omen drift one 3-particle send every 4 ticks, and
 * tick() returns immediately on every other tick. Particle
 * budget per activation: 100 omen draws times 3 = 300. The
 * FOLLOW_RANGE modifier is built once in start() and removed
 * in end(); nothing is resolved or allocated per tick.
 *
 * State tag keys: none of its own; the activation runs purely
 * on the framework tick counter.
 */
public final class RaidCaptainsCallAbility implements MobAbility {

    static final int DURATION_TICKS = 400;
    private static final double AURA_RADIUS = 16.0;
    private static final int PULSE_INTERVAL = 20;
    private static final int SURGE_TICKS = 40;
    private static final Identifier FOLLOW_RANGE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "raid_captains_call_follow_range");

    @Override
    public String id() {
        return "raid_captains_call";
    }

    @Override
    public String displayName() {
        return "Raid Captain's Call";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return !PillagerAbilitySupport.illagerAlliesAroundSelf(ctx, AURA_RADIUS, 3.0, 3.0).isEmpty();
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        PillagerAbilitySupport.addModifier(mob, Attributes.FOLLOW_RANGE,
            new AttributeModifier(FOLLOW_RANGE_ID, 16.0, AttributeModifier.Operation.ADD_VALUE));
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.PILLAGER_CELEBRATE,
                SoundSource.HOSTILE, 1.0f, 0.9f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        boolean pulse = ticks % PULSE_INTERVAL == 0;
        boolean omen = ticks % 4 == 0;
        if (!pulse && !omen) {
            return; // Cheap early-out: work happens on the 4 and 20 tick marks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        if (omen) {
            level.sendParticles(ParticleTypes.RAID_OMEN, mob.getX(), mob.getY() + 2.2, mob.getZ(),
                3, 0.3, 0.2, 0.3, 0.02);
        }
        if (pulse) {
            LivingEntity captainTarget = ctx.target();
            List<AbstractIllager> allies = PillagerAbilitySupport.illagerAlliesAroundSelf(
                ctx, AURA_RADIUS, 3.0, 3.0);
            for (AbstractIllager ally : allies) {
                ally.addEffect(new MobEffectInstance(MobEffects.SPEED, SURGE_TICKS, 0));
                ally.addEffect(new MobEffectInstance(MobEffects.STRENGTH, SURGE_TICKS, 0));
                if (captainTarget != null && ally.getTarget() == null) {
                    ally.setTarget(captainTarget);
                }
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // The survey ends with the call. The surge effects
        // already handed out bleed off on their own clocks
        // (see the class comment).
        PillagerAbilitySupport.removeModifier(ctx.mob(), Attributes.FOLLOW_RANGE, FOLLOW_RANGE_ID);
    }
}
