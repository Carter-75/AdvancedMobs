package mod.advancedmobs.ability.vindicator;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.illager.AbstractIllager;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * Patrol Captain's Standard (Vindicator ability 4 of 5,
 * aura).
 *
 * WHAT IT DOES: the vindicator plants an ominous banner in
 * the ground and takes command of the patrol around it.
 * Every illager ally near the standard gains ABSORPTION and
 * SPEED and fights on the captain's target instead of
 * scattering, while RAID_OMEN smoke drifts up from the
 * banner like a signal fire. The standard is a physical
 * thing: enemies who reach it tear it down, and a captain
 * whose standard falls flies into a rage.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   at least one illager ally (VindicatorAbilitySupport
 *   ILLAGER ALLY RULE: an AbstractIllager with an
 *   either-direction alliance record, or one fighting the
 *   same target as the captain) within 12 blocks of the
 *   vindicator (one bounded query). A lone captain's standard
 *   commands nobody, so an empty patrol never burns the
 *   cooldown.
 * - The standard: planted at the vindicator's feet position
 *   at cast (recorded in the state tag; it does NOT move with
 *   the captain). It is an item display at scale 1.7 showing
 *   a real ominous banner stack, built by
 *   Raid.getOminousBannerInstance, the same factory vanilla
 *   raid captains wear (verified against the 26.2 jar), its
 *   center snapped to 1.05 blocks above the plant point so
 *   the banner stands on the ground. Full brightness. The
 *   display is a pure visual with no hitbox: its destruction
 *   is tracked in code (next point), the same proximity
 *   stand-in the Witch's Potion Roulette uses for its
 *   displays.
 * - Standard health: 30 points, tracked in the state tag.
 *   Every 5 ticks, one bounded query checks for enemies
 *   (package enemy rule) within 1.6 blocks of the plant point
 *   (1 below to 2.5 above): if at least one is present, the
 *   standard takes 3 damage and sheds 4 white-wool
 *   BLOCK_CRUMBLE flecks. Ten such checks under attack tear
 *   it down: about 2.5 seconds of an enemy standing on it,
 *   unanswered.
 * - The blessing pulse, every 20 ticks: every illager ally
 *   within radius 12 of the STANDARD (3 below to 3 above)
 *   gains ABSORPTION I and SPEED I, each for 40 ticks. The
 *   40 against the 20 tick pulse is deliberate: an ally that
 *   steps out keeps its blessing for up to two seconds of
 *   bleed-off, and one holding formation is refreshed
 *   forever. The captain itself is NOT blessed: the design
 *   gives the protection to the patrol, and the captain's own
 *   payoff is the rage below. Team targeting (the design's
 *   Uses list): on the same pulse, any blessed ally with no
 *   target of its own is ordered onto the captain's current
 *   target, so the patrol fights as one instead of
 *   scattering.
 * - RAID_OMEN: every 4 ticks, 3 RAID_OMEN motes drift up
 *   from the banner's top (plant point plus 2.2), spread 0.3,
 *   slow rise.
 * - The fall: when the health reaches zero, the display is
 *   discarded in a burst of 12 white-wool BLOCK_CRUMBLE and
 *   an ITEM_BREAK crack, and the captain is enraged:
 *   STRENGTH II and SPEED I for 200 ticks, applied at the
 *   moment of the fall. The ability then ends; the rage is a
 *   timed effect and deliberately outlives it (end() does
 *   not remove it), the Hoglin Cavalry fury precedent.
 * - Duration: 400 ticks (20 seconds) if the standard stands.
 * - Sounds: a VINDICATOR_CELEBRATE shout (volume 1.0, pitch
 *   0.9) as the banner plants, and a VINDICATOR_AMBIENT roar
 *   (volume 1.2, pitch 0.7) with the ITEM_BREAK when it
 *   falls.
 *
 * WHAT IT LOOKS LIKE: the black-and-gray ominous banner
 * standing planted mid-battlefield, purple RAID_OMEN smoke
 * curling off it, the patrol around it glowing with fresh
 * absorption hearts and moving at a captain's pace, all of
 * them turning on the same target at once.
 *
 * TERRAIN: the standard is an item display, not a placed
 * block, so this ability changes no blocks in either
 * mobGriefing mode and the terrain gate does not apply.
 *
 * PERFORMANCE: the blessing is one bounded query every 20
 * ticks, the damage check one bounded query every 5 ticks,
 * the omen drift one 3-particle send every 4 ticks, and the
 * display never moves after planting, so there are no
 * per-tick resolves at all outside end(). Particle budget
 * per activation: 100 omen draws times 3 = 300, damage
 * flecks at most 10 checks times 4 = 40, the fall burst 12,
 * total at most 352.
 *
 * CLEANUP: end() discards the standard display when it is
 * still standing and resolvable. The blessings are timed at
 * 40 ticks and bleed off on their own clocks after the last
 * pulse; the fall rage is timed at 200 ticks and is
 * deliberately left running (see above). If the captain dies
 * mid-command, the framework termination path runs this same
 * end(), and the standard falls with him.
 *
 * State tag keys: "standard" (list holding the display's
 * entry), "std_x", "std_y", "std_z" (the plant point),
 * "std_hp" (the standard's remaining health), "broken" (1
 * once the standard has fallen).
 */
public final class PatrolCaptainsStandardAbility implements MobAbility {

    static final int DURATION_TICKS = 400;
    private static final double AURA_RADIUS = 12.0;
    private static final int PULSE_INTERVAL = 20;
    private static final int BLESSING_TICKS = 40;
    private static final int STANDARD_HEALTH = 30;
    private static final int STANDARD_DAMAGE_PER_CHECK = 3;
    private static final BlockParticleOption WOOL_CRUMBLE =
        new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, Blocks.WOOL.white().defaultBlockState());

    @Override
    public String id() {
        return "patrol_captains_standard";
    }

    @Override
    public String displayName() {
        return "Patrol Captain's Standard";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return !VindicatorAbilitySupport.illagerAlliesAroundSelf(ctx, AURA_RADIUS, 3.0, 3.0).isEmpty();
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        ctx.state().putDouble("std_x", mob.getX());
        ctx.state().putDouble("std_y", mob.getY());
        ctx.state().putDouble("std_z", mob.getZ());
        ctx.state().putInt("std_hp", STANDARD_HEALTH);
        ctx.state().putInt("broken", 0);

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            ItemStack banner = Raid.getOminousBannerInstance(
                level.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN));
            Entity display = VindicatorAbilitySupport.createItemDisplay(level, banner, 1.7f);
            if (display != null) {
                display.snapTo(mob.getX(), mob.getY() + 1.05, mob.getZ(), 0.0f, 0.0f);
                if (level.addFreshEntity(display)) {
                    VindicatorAbilitySupport.trackEntity(ctx.state(), "standard", display);
                }
            }
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.VINDICATOR_CELEBRATE,
                SoundSource.HOSTILE, 1.0f, 0.9f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        boolean pulse = ticks % PULSE_INTERVAL == 0;
        boolean damageCheck = ticks % 5 == 0;
        boolean omen = ticks % 4 == 0;
        if (!pulse && !damageCheck && !omen) {
            return; // Cheap early-out: work happens on the 4, 5, and 20 tick marks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        double stdX = ctx.state().getDoubleOr("std_x", ctx.mob().getX());
        double stdY = ctx.state().getDoubleOr("std_y", ctx.mob().getY());
        double stdZ = ctx.state().getDoubleOr("std_z", ctx.mob().getZ());

        if (omen) {
            level.sendParticles(ParticleTypes.RAID_OMEN, stdX, stdY + 2.2, stdZ,
                3, 0.3, 0.2, 0.3, 0.02);
        }
        if (pulse) {
            LivingEntity captainTarget = ctx.target();
            List<AbstractIllager> allies = VindicatorAbilitySupport.illagerAlliesInBox(
                ctx, stdX, stdY, stdZ, AURA_RADIUS, 3.0, 3.0);
            for (AbstractIllager ally : allies) {
                ally.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, BLESSING_TICKS, 0));
                ally.addEffect(new MobEffectInstance(MobEffects.SPEED, BLESSING_TICKS, 0));
                if (captainTarget != null && ally.getTarget() == null) {
                    ally.setTarget(captainTarget);
                }
            }
        }
        if (damageCheck) {
            List<LivingEntity> attackers = VindicatorAbilitySupport.enemiesInBox(
                ctx, stdX, stdY, stdZ, 1.6, 1.0, 2.5);
            if (!attackers.isEmpty()) {
                int hp = ctx.state().getIntOr("std_hp", STANDARD_HEALTH) - STANDARD_DAMAGE_PER_CHECK;
                ctx.state().putInt("std_hp", hp);
                level.sendParticles(WOOL_CRUMBLE, stdX, stdY + 1.2, stdZ, 4, 0.3, 0.5, 0.3, 0.08);
                if (hp <= 0) {
                    breakStandard(ctx, level, stdX, stdY, stdZ);
                }
            }
        }
    }

    /** The fall: the display bursts, and the captain's rage begins. */
    private void breakStandard(AbilityContext ctx, ServerLevel level, double stdX, double stdY, double stdZ) {
        ctx.state().putInt("broken", 1);
        Entity display = VindicatorAbilitySupport.resolveFirstTracked(level, ctx.state(), "standard");
        if (display != null) {
            display.discard();
        }
        level.sendParticles(WOOL_CRUMBLE, stdX, stdY + 1.2, stdZ, 12, 0.4, 0.6, 0.4, 0.12);
        level.playSound(null, stdX, stdY + 1.0, stdZ, SoundEvents.ITEM_BREAK,
            SoundSource.HOSTILE, 1.0f, 0.9f);
        Mob captain = ctx.mob();
        level.playSound(null, captain.getX(), captain.getY(), captain.getZ(), SoundEvents.VINDICATOR_AMBIENT,
            SoundSource.HOSTILE, 1.2f, 0.7f);
        // The rage: timed effects, deliberately NOT removed by
        // end(); they outlive the ability by design.
        captain.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 200, 1));
        captain.addEffect(new MobEffectInstance(MobEffects.SPEED, 200, 0));
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.state().getIntOr("broken", 0) == 1 || ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Entity display = VindicatorAbilitySupport.resolveFirstTracked(level, ctx.state(), "standard");
        if (display != null) {
            display.discard();
        }
    }
}
