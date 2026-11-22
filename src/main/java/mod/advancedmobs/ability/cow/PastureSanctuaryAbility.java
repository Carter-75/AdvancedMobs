package mod.advancedmobs.ability.cow;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.List;
import java.util.UUID;

/**
 * Pasture Sanctuary (Cow ability 5 of 5, aura / support).
 *
 * WHAT IT DOES: the cow settles into a calm, steady
 * presence, hearts and soft green sparkles drifting off
 * it, and the whole herd inside its aura mends. The cow
 * and every allied mob nearby slowly regenerate for as
 * long as the sanctuary lasts, and the cow draws strength
 * from the herd's suffering: whenever an ally inside the
 * aura takes a hit, the cow gains a layer of absorption.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the cow's current target within 14 blocks
 *   (distance squared 196) AND at least one ally within 10
 *   blocks (one bounded query here, the Guardian Stance
 *   precedent: a cow with nobody to shelter never burns
 *   the cooldown on an empty pasture).
 * - Duration: exactly 400 ticks (20 seconds) from start().
 * - The mend pulse (every 10 ticks): one bounded query at
 *   radius 8.0 around the moving cow (2.5 below to 2.5
 *   above). The cow and every ally found gain REGENERATION
 *   I refreshed to 30 ticks, so the mend never lapses
 *   between pulses while the aura lasts.
 * - The hurt watch (same pulse): each ally in the aura has
 *   its last-hurt-by-mob timestamp compared against the
 *   watch list in the state tag (baselined in start() and
 *   rebuilt every pulse from the allies actually present,
 *   so an ally seen for the first time mid-aura baselines
 *   silently and its old wounds never count; the Poppy's
 *   Revenge watch pattern). If any watched ally's
 *   timestamp advanced since the last pulse, that ally
 *   took a hit inside the aura, and the cow gains
 *   ABSORPTION I (4 absorption points) for 200 ticks,
 *   refreshed: at most one grant per pulse no matter how
 *   many allies were hit. The watch tracks at most 16
 *   allies (the first 16 the pulse finds); the mend itself
 *   has no cap.
 * - The effects ride their own clocks past the end (the
 *   Caravan Call precedent): end() removes nothing, and
 *   the last regeneration refresh fades within 30 ticks
 *   and the last absorption within 200 ticks of the aura
 *   ending.
 * - Sound: the cow's classic variant ambient sound plays
 *   at start at volume 1.2, pitch 1.0 (a calm low).
 *
 * WHAT IT LOOKS LIKE: a serene cow wreathed in 2 HEART and
 * 2 HAPPY_VILLAGER motes every 5 ticks, the herd around it
 * quietly knitting itself back together, and a soft golden
 * absorption sheen settling on the cow each time one of
 * its herd is struck.
 *
 * PERFORMANCE: one bounded query every 10 ticks does both
 * the mend and the watch (the watch pass is at most 16
 * timestamp compares against the rebuilt list); particles
 * run every 5 ticks; nothing else runs per tick. Particle
 * budget per activation: 80 emissions times 4 = 320
 * particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply. Everything is effects and particles.
 *
 * State tag keys: "watch" (list of compounds, one per
 * watched ally: "id", the ally's UUID; "ts", its last
 * seen hurt-by-mob timestamp).
 */
public final class PastureSanctuaryAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int PULSE_INTERVAL = 10;
    private static final int PARTICLE_INTERVAL = 5;
    private static final int WATCH_CAP = 16;
    private static final double AURA_RADIUS = 8.0;

    @Override
    public String id() {
        return "pasture_sanctuary";
    }

    @Override
    public String displayName() {
        return "Pasture Sanctuary";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null || ctx.mob().distanceToSqr(target) > 196.0) {
            return false;
        }
        Mob caster = ctx.mob();
        return !CowAbilitySupport.alliesInBox(ctx,
            caster.getX(), caster.getY(), caster.getZ(), 10.0, 2.5, 2.5).isEmpty();
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().put("watch", baselineWatch(ctx, new ListTag()));
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();
        if (level != null && CowAbilitySupport.cowSound() != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                CowAbilitySupport.cowSound(), SoundSource.HOSTILE, 1.2f, 1.0f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        if (ticks % PARTICLE_INTERVAL == 0) {
            level.sendParticles(ParticleTypes.HEART,
                caster.getX(), caster.getY() + 1.2, caster.getZ(),
                2, 0.8, 0.5, 0.8, 0.02);
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                caster.getX(), caster.getY() + 0.8, caster.getZ(),
                2, 0.9, 0.5, 0.9, 0.02);
        }
        if (ticks % PULSE_INTERVAL != 0) {
            return; // Cheap early-out: the mend and the watch pulse every 10 ticks.
        }
        pulse(ctx);
    }

    /** One mend pulse: refresh the herd's regeneration and run the hurt watch. */
    private void pulse(AbilityContext ctx) {
        Mob caster = ctx.mob();
        List<LivingEntity> allies = CowAbilitySupport.alliesInBox(ctx,
            caster.getX(), caster.getY(), caster.getZ(), AURA_RADIUS, 2.5, 2.5);

        caster.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 30, 0));
        for (LivingEntity ally : allies) {
            ally.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 30, 0));
        }

        // The hurt watch: rebuild the list from the allies
        // present, flagging any whose hurt clock advanced.
        ListTag oldWatch = ctx.state().getListOrEmpty("watch");
        ListTag newWatch = new ListTag();
        boolean allyHurt = false;
        int tracked = 0;
        for (LivingEntity ally : allies) {
            if (tracked >= WATCH_CAP) {
                break;
            }
            tracked++;
            int timestamp = ally.getLastHurtByMobTimestamp();
            int index = watchIndex(oldWatch, ally.getUUID());
            if (index >= 0 && oldWatch.getCompoundOrEmpty(index).getIntOr("ts", 0) != timestamp) {
                allyHurt = true;
            }
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, ally.getUUID());
            entry.putInt("ts", timestamp);
            newWatch.add(entry);
        }
        ctx.state().put("watch", newWatch);
        if (allyHurt) {
            // The herd's pain becomes the cow's shield.
            caster.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 200, 0));
        }
    }

    /** Builds the baseline watch list for every ally currently in the aura. */
    private ListTag baselineWatch(AbilityContext ctx, ListTag watch) {
        Mob caster = ctx.mob();
        List<LivingEntity> allies = CowAbilitySupport.alliesInBox(ctx,
            caster.getX(), caster.getY(), caster.getZ(), AURA_RADIUS, 2.5, 2.5);
        int tracked = 0;
        for (LivingEntity ally : allies) {
            if (tracked >= WATCH_CAP) {
                break;
            }
            tracked++;
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, ally.getUUID());
            entry.putInt("ts", ally.getLastHurtByMobTimestamp());
            watch.add(entry);
        }
        return watch;
    }

    /** The index of the ally's entry in the watch list, or -1 when not baselined. */
    private int watchIndex(ListTag watch, UUID allyId) {
        for (int i = 0; i < watch.size(); i++) {
            CompoundTag entry = watch.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (allyId.equals(id)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to undo: the regeneration and absorption
        // ride their own clocks and fade on their own (see
        // the class comment).
    }
}
