package mod.advancedmobs.ability.irongolem;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.List;
import java.util.UUID;

/**
 * Poppy's Revenge (Iron Golem ability 2 of 5, aura /
 * defense).
 *
 * WHAT IT DOES: the golem stands guard over the smaller
 * lives around it. The moment a villager type ally, or any
 * smaller ally, near the golem takes damage, the golem's
 * eyes flare poppy red, a red glow washes over its frame,
 * and it charges the attacker with SPEED and STRENGTH,
 * beelining straight for whatever did the damage. It does
 * not calm down until that attacker is dead or far away;
 * then the watch ends.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the golem's current target within 24 blocks (distance
 *   squared 576). The incident itself is detected during the
 *   watch, below.
 * - The watch (start() and every 5 ticks): one bounded query
 *   (radius 16 box, 3 below to 3 above) lists the protected
 *   allies (IronGolemAbilitySupport: villager types, or
 *   allies whose max health is below the golem's). Each
 *   protected ally's last-hurt-by-mob timestamp is baselined
 *   in the state tag; an ally first seen mid-watch is
 *   baselined silently, so old wounds never trigger a
 *   revenge. When a baselined ally's timestamp changes, it
 *   was just hurt: the golem reads the ally's last hurt-by
 *   mob, and if that attacker is alive, an enemy (package
 *   rule), and within 32 blocks of the golem, the revenge
 *   begins.
 * - The revenge (on the incident tick): the attacker's UUID
 *   is recorded, the golem's vanilla target is set to the
 *   attacker (the defend style beeline), and the golem gains
 *   SPEED I and STRENGTH I for 240 ticks. While the revenge
 *   runs, every 20 ticks the two effects are refreshed to
 *   240 ticks and the golem's target is re-set to the
 *   attacker if something else stole its attention. The
 *   revenge ends when the attacker is dead, gone, or more
 *   than 48 blocks from the golem (distance squared 2304);
 *   the ability then finishes.
 * - FOLLOW_RANGE: a transient +16.0 add_value modifier (id
 *   advancedmobs:poppys_revenge_range) is on the golem for
 *   the whole activation, so its senses reach the attacker
 *   it is beelining for; removed in end().
 * - Expiry: if no incident happens within 300 ticks, the
 *   watch ends quietly. Hard finish at tick 600 even mid
 *   revenge. end() removes the FOLLOW_RANGE modifier and
 *   the SPEED and STRENGTH effects (they are the ability's
 *   own; a golem that had them from another source would
 *   lose them, an accepted edge case shared with every
 *   fixed-duration effect in this system).
 *
 * WHAT IT LOOKS LIKE: at the incident, 12 poppy red
 * ENTITY_EFFECT motes (color 0.85, 0.08, 0.08) burst across
 * the golem's frame at chest height: the eyes flaring and
 * the glow washing over it. While the revenge runs, 3 more
 * red motes shed at chest height every 20 ticks with the
 * effect refresh.
 *
 * PERFORMANCE: start() is one bounded query plus baseline
 * writes. tick() returns immediately except every 5th tick,
 * where it runs one bounded query (radius 16) and compares
 * timestamps; the revenge refresh adds 3 particles every 20
 * ticks. Particle budget per activation: 12 per incident
 * plus at most 30 refresh pulses times 3, total at most
 * about 102 particles.
 *
 * State tag keys: "watch" (list of compounds, one per
 * baselined protected ally: "id" the ally's UUID, "ts" its
 * last-hurt-by-mob timestamp at baseline), "attacker" (the
 * revenge target's UUID, present only while a revenge
 * runs), "done" (boolean, set when the revenge resolves).
 */
public final class PoppysRevengeAbility implements MobAbility {

    private static final int WATCH_TICKS = 300;
    private static final int MAX_TICKS = 600;
    private static final Identifier RANGE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "poppys_revenge_range");
    private static final ColorParticleOption POPPY_GLOW =
        ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, 0.85f, 0.08f, 0.08f);

    @Override
    public String id() {
        return "poppys_revenge";
    }

    @Override
    public String displayName() {
        return "Poppy's Revenge";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return ctx.target() != null && ctx.mob().distanceToSqr(ctx.target()) <= 576.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        IronGolemAbilitySupport.addModifier(mob, Attributes.FOLLOW_RANGE,
            new AttributeModifier(RANGE_ID, 16.0, AttributeModifier.Operation.ADD_VALUE));
        ctx.state().put("watch", baselineWatch(ctx, new ListTag()));
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks % 5 != 0) {
            return; // Cheap early-out: the watch polls every 5 ticks only.
        }
        if (ctx.state().contains("attacker")) {
            tickRevenge(ctx, ticks);
            return;
        }
        pollForIncident(ctx);
    }

    /** Polls the protected allies for a fresh wound and starts the revenge if one is found. */
    private void pollForIncident(AbilityContext ctx) {
        Mob mob = ctx.mob();
        List<LivingEntity> allies = IronGolemAbilitySupport.protectedAlliesInBox(
            ctx, mob.getX(), mob.getY(), mob.getZ(), 16.0, 3.0, 3.0);
        ListTag watch = ctx.state().getListOrEmpty("watch");
        for (LivingEntity ally : allies) {
            int index = watchIndex(watch, ally.getUUID());
            int timestamp = ally.getLastHurtByMobTimestamp();
            if (index < 0) {
                // First sighting mid-watch: baseline silently.
                CompoundTag entry = new CompoundTag();
                entry.store("id", UUIDUtil.CODEC, ally.getUUID());
                entry.putInt("ts", timestamp);
                watch.add(entry);
                continue;
            }
            CompoundTag entry = watch.getCompoundOrEmpty(index);
            if (entry.getIntOr("ts", 0) == timestamp) {
                continue;
            }
            // A fresh wound: re-baseline, then judge the attacker.
            entry.putInt("ts", timestamp);
            LivingEntity attacker = ally.getLastHurtByMob();
            if (attacker == null || !attacker.isAlive()
                || !IronGolemAbilitySupport.isEnemy(ctx, attacker)
                || mob.distanceToSqr(attacker) > 1024.0) {
                continue;
            }
            beginRevenge(ctx, attacker);
            return;
        }
        ctx.state().put("watch", watch);
    }

    /** Starts the revenge on the given attacker: record, retarget, buff, flare. */
    private void beginRevenge(AbilityContext ctx, LivingEntity attacker) {
        Mob mob = ctx.mob();
        ctx.state().store("attacker", UUIDUtil.CODEC, attacker.getUUID());
        mob.setTarget(attacker);
        mob.addEffect(new MobEffectInstance(MobEffects.SPEED, 240, 0));
        mob.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 240, 0));
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.sendParticles(POPPY_GLOW, mob.getX(), mob.getY() + 1.8, mob.getZ(),
                12, 0.7, 0.7, 0.7, 0.05);
        }
    }

    /** Runs the active revenge: refresh the buffs, hold the beeline, watch for the end. */
    private void tickRevenge(AbilityContext ctx, int ticks) {
        Mob mob = ctx.mob();
        LivingEntity attacker = resolveAttacker(ctx);
        if (attacker == null || !attacker.isAlive() || mob.distanceToSqr(attacker) > 2304.0) {
            // Dead, gone, or far away: the golem calms down.
            ctx.state().remove("attacker");
            ctx.state().putBoolean("done", true);
            return;
        }
        if (ticks % 20 == 0) {
            mob.addEffect(new MobEffectInstance(MobEffects.SPEED, 240, 0));
            mob.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 240, 0));
            if (mob.getTarget() != attacker) {
                mob.setTarget(attacker);
            }
            ServerLevel level = ctx.serverLevel();
            if (level != null) {
                level.sendParticles(POPPY_GLOW, mob.getX(), mob.getY() + 1.8, mob.getZ(),
                    3, 0.6, 0.6, 0.6, 0.05);
            }
        }
    }

    /** Builds the baseline watch list for every protected ally currently in range. */
    private ListTag baselineWatch(AbilityContext ctx, ListTag watch) {
        Mob mob = ctx.mob();
        List<LivingEntity> allies = IronGolemAbilitySupport.protectedAlliesInBox(
            ctx, mob.getX(), mob.getY(), mob.getZ(), 16.0, 3.0, 3.0);
        for (LivingEntity ally : allies) {
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

    /** Resolves the recorded revenge target against the level, or null when gone. */
    private LivingEntity resolveAttacker(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null || !ctx.state().contains("attacker")) {
            return null;
        }
        UUID id = ctx.state().read("attacker", UUIDUtil.CODEC).orElse(null);
        return id != null && level.getEntity(id) instanceof LivingEntity living ? living : null;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        if (ctx.state().getBooleanOr("done", false) || ctx.ticksActive() >= MAX_TICKS) {
            return true;
        }
        // The quiet watch expires at 300 ticks; a running
        // revenge is allowed to play out to the hard cap.
        return ctx.ticksActive() >= WATCH_TICKS && !ctx.state().contains("attacker");
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        IronGolemAbilitySupport.removeModifier(mob, Attributes.FOLLOW_RANGE, RANGE_ID);
        mob.removeEffect(MobEffects.SPEED);
        mob.removeEffect(MobEffects.STRENGTH);
    }
}
