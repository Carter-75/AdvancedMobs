package mod.advancedmobs.ability.wolf;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.wolf.Wolf;

import java.util.List;
import java.util.UUID;

/**
 * Pack Howl (Wolf ability 1 of 5, summon / aura).
 *
 * WHAT IT DOES: the wolf throws its head back and howls.
 * NOTE particles rise in a ring around it, two wild wolves
 * lope in from the sides to fight beside it, and the whole
 * pack (the caller, the summoned pair, and any allied wolves
 * nearby) hunts with SPEED I and STRENGTH I for the duration.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the wolf's current target within 20 blocks (distance
 *   squared 400).
 * - The howl (start()): the classic wolf variant's ambient
 *   sound plays at volume 2.0, pitch 0.8 (a long low howl,
 *   resolved through WolfAbilitySupport.wolfSound; skipped
 *   if the variant map has no classic entry), and 12 NOTE
 *   particles burst above the caller.
 * - The pack (start()): 2 wolves, spawned at horizontal
 *   offsets (2.5, 0) and (-2.5, 0) from the caller, created
 *   with EntitySpawnReason.MOB_SUMMONED and finalizeSpawn
 *   like every other summon in this mod. Each is a plain
 *   vanilla wolf (base 8 health, 4 damage in 26.2) plus a
 *   transient FOLLOW_RANGE modifier of +16 ADD_VALUE under
 *   a fixed id (the design's FOLLOW_RANGE line), so the pair
 *   holds onto the caller's fight from farther away;
 *   transient modifiers are not persisted, and the pair is
 *   discarded at end() anyway. Team assignment, per wolf,
 *   both directions plus the registry: the caster's data
 *   gains the wolf as an ally, the wolf's data gains the
 *   caster, and the pair is registered in SummonerTracker
 *   (which also tags the wolf umw_summoned for the natural
 *   spawn blocker). Each wolf is ordered onto the caster's
 *   current target at spawn.
 * - Duration: 300 ticks (15 seconds), hard finish.
 * - Babysitting (tick(), every 5 ticks): each living tracked
 *   wolf whose target is not the caster's current target is
 *   re-ordered onto it, keeping the pair on the caller's
 *   fight instead of wandering off after bystanders.
 * - The hunt buff (tick(), every 20 ticks): a ring of 8
 *   NOTE particles rises over the caller, and SPEED I and
 *   STRENGTH I (amplifier 0) are refreshed for 40 ticks on
 *   the caster, on every living tracked pack wolf, and on
 *   every allied Wolf found by one bounded query of radius
 *   12 around the caster (the design's "whole pack": nearby
 *   allied wolves that were already in the fight share the
 *   buff, but only the summoned pair is tracked, babysat,
 *   and discarded). The effects are ordinary effect
 *   instances on their own 40 tick clocks; end() does not
 *   strip them, they expire naturally just after the ability
 *   ends.
 * - end(): each surviving summoned wolf is discarded in 6
 *   POOF particles and the alliance records are removed on
 *   both sides for every tracked wolf. If the caster dies
 *   first, the framework termination path runs this same
 *   end(), so the pair never outlives its caller.
 *   SummonerTracker records are deliberately not
 *   unregistered, matching the other summons in this mod:
 *   that registry cleans up on the wolf's own removal.
 *
 * WHAT IT LOOKS LIKE: a low howl, a ring of musical notes
 * rising over the caller, and two wolves arriving from the
 * flanks; every 20 ticks another small ring of notes marks
 * the pack still hunting together.
 *
 * PERFORMANCE: start() does 2 spawns, once. tick() returns
 * immediately except every 5th tick, where it resolves at
 * most 2 UUIDs and writes at most 2 target re-orders; the
 * buff pass every 20 ticks adds one bounded radius-12 query
 * and touches at most the caster plus the wolves it finds.
 * Particle budget per activation: 12 at start, 15 buff
 * pulses times 8 = 120, end() puffs at most 2 times 6 = 12,
 * total at most 144 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "pack" (list of compounds, one per
 * summoned wolf: "id", the wolf's UUID).
 */
public final class PackHowlAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int PACK_COUNT = 2;
    private static final int BABYSIT_INTERVAL = 5;
    private static final int BUFF_INTERVAL = 20;
    private static final double BUFF_RADIUS = 12.0;
    private static final Identifier FOLLOW_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "pack_howl_follow");
    private static final double[][] OFFSETS = {
        {2.5, 0.0}, {-2.5, 0.0}
    };

    @Override
    public String id() {
        return "pack_howl";
    }

    @Override
    public String displayName() {
        return "Pack Howl";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 400.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();
        ListTag pack = new ListTag();
        ctx.state().put("pack", pack);
        if (level == null) {
            return;
        }

        // The howl: a low classic wolf call and a ring of notes.
        Holder<SoundEvent> howl = WolfAbilitySupport.wolfSound(false);
        if (howl != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), howl,
                SoundSource.HOSTILE, 2.0f, 0.8f);
        }
        level.sendParticles(ParticleTypes.NOTE, caster.getX(), caster.getY() + 1.2, caster.getZ(),
            12, 0.6, 0.3, 0.6, 0.1);

        LivingEntity target = ctx.target();
        for (int i = 0; i < PACK_COUNT; i++) {
            Wolf mate = EntityTypes.WOLF.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (mate == null) {
                continue;
            }
            double[] offset = OFFSETS[i];
            mate.snapTo(caster.getX() + offset[0], caster.getY(), caster.getZ() + offset[1],
                ctx.random().nextFloat() * 360.0f, 0.0f);
            mate.finalizeSpawn(level, level.getCurrentDifficultyAt(mate.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
            WolfAbilitySupport.addModifier(mate, Attributes.FOLLOW_RANGE,
                new AttributeModifier(FOLLOW_ID, 16.0, AttributeModifier.Operation.ADD_VALUE));
            if (!level.addFreshEntity(mate)) {
                continue;
            }
            // Team assignment, both directions, plus the summoner registry.
            ctx.data().addAlly(mate.getUUID());
            MobWarData.get(mate).addAlly(caster.getUUID());
            SummonerTracker.registerSummoned(mate.getUUID(), caster.getUUID());
            if (target != null) {
                mate.setTarget(target);
            }
            WolfAbilitySupport.trackEntity(ctx.state(), "pack", mate);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks % BABYSIT_INTERVAL != 0) {
            return; // Cheap early-out: babysitting runs every 5 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        LivingEntity target = ctx.target();
        ListTag pack = ctx.state().getListOrEmpty("pack");
        for (int i = 0; i < pack.size(); i++) {
            Entity found = WolfAbilitySupport.resolveTracked(level, pack.getCompoundOrEmpty(i));
            if (found instanceof Wolf mate && mate.isAlive()
                && target != null && mate.getTarget() != target) {
                mate.setTarget(target);
            }
        }
        // The hunt buff, refreshed every 20 ticks (see the class comment).
        if (ticks % BUFF_INTERVAL == 0) {
            level.sendParticles(ParticleTypes.NOTE, caster.getX(), caster.getY() + 1.2, caster.getZ(),
                8, 0.6, 0.3, 0.6, 0.1);
            caster.addEffect(new MobEffectInstance(MobEffects.SPEED, 40, 0));
            caster.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 40, 0));
            for (int i = 0; i < pack.size(); i++) {
                Entity found = WolfAbilitySupport.resolveTracked(level, pack.getCompoundOrEmpty(i));
                if (found instanceof Wolf mate && mate.isAlive()) {
                    mate.addEffect(new MobEffectInstance(MobEffects.SPEED, 40, 0));
                    mate.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 40, 0));
                }
            }
            List<LivingEntity> allies = WolfAbilitySupport.alliesInBox(ctx,
                caster.getX(), caster.getY(), caster.getZ(), BUFF_RADIUS, 4.0, 4.0);
            for (LivingEntity ally : allies) {
                if (ally instanceof Wolf wolf) {
                    wolf.addEffect(new MobEffectInstance(MobEffects.SPEED, 40, 0));
                    wolf.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 40, 0));
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
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag pack = ctx.state().getListOrEmpty("pack");
        for (int i = 0; i < pack.size(); i++) {
            CompoundTag entry = pack.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id == null) {
                continue;
            }
            // The alliance ends with the ability, whichever side is still here.
            ctx.data().removeAlly(id);
            Entity found = level.getEntity(id);
            if (found instanceof Wolf mate) {
                MobWarData.get(mate).removeAlly(caster.getUUID());
                if (mate.isAlive()) {
                    level.sendParticles(ParticleTypes.POOF, mate.getX(), mate.getY() + 0.3, mate.getZ(),
                        6, 0.25, 0.2, 0.25, 0.05);
                    mate.discard();
                }
            }
        }
    }
}
