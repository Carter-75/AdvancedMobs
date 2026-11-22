package mod.advancedmobs.ability.bee;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
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
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.bee.Bee;

import java.util.UUID;

/**
 * Swarm Call (Bee ability 2 of 5, summon).
 *
 * WHAT IT DOES: the bee releases a burst of angry villager
 * particles and a loud buzzing note, and three more bees
 * dive in from above to join the fight. The swarm
 * coordinates on the caller's target, and while at least
 * two swarm mates live, every bee in the group fights with
 * a small strength bonus.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the bee's current target within 16 blocks (distance
 *   squared 256).
 * - The call (start()): 12 ANGRY_VILLAGER and 8 NOTE
 *   particles burst at the caller, and a BEE_LOOP_AGGRESSIVE
 *   sound plays (volume 1.5, pitch 1.0): the loud angry buzz
 *   of the summons arriving.
 * - The swarm (start()): 3 bees, spawned 2.5 blocks above
 *   the caller at horizontal offsets (1.2, 0), (-1.2, 0),
 *   and (0, 1.2), created with EntitySpawnReason.MOB_SUMMONED
 *   and finalizeSpawn like every other summon in this mod.
 *   Each is a plain vanilla bee (10 health, 2 damage) plus a
 *   transient FOLLOW_RANGE modifier of +16 ADD_VALUE under
 *   a fixed id (the design's FOLLOW_RANGE line), so the
 *   swarm holds onto the caller's fight from farther away;
 *   transient modifiers are not persisted, and the swarm is
 *   discarded at end() anyway. Team assignment, per bee,
 *   both directions plus the registry: the caster's data
 *   gains the bee as an ally, the bee's data gains the
 *   caster, and the pair is registered in SummonerTracker
 *   (which also tags the bee umw_summoned for the natural
 *   spawn blocker). Each bee is ordered onto the caster's
 *   current target at spawn; their stinging is their own
 *   vanilla bee AI from there.
 * - Duration: 400 ticks (20 seconds), hard finish.
 * - Babysitting (tick(), every 5 ticks): each living tracked
 *   bee whose target is not the caster's current target is
 *   re-ordered onto it, keeping the swarm on the caller's
 *   fight instead of wandering off after bystanders.
 * - The strength of the swarm (tick(), every 20 ticks): the
 *   living group is counted (the caster plus its living
 *   tracked bees). While the group holds at least 3 living
 *   members, that is the caster with at least two swarm
 *   mates alive, STRENGTH I (amplifier 0) is refreshed for
 *   40 ticks on the caster and on every living tracked bee.
 *   Below 3 living members no refresh happens and the last
 *   refresh expires on its own clock.
 * - end(): each surviving swarm bee is discarded in 6 POOF
 *   particles and the alliance records are removed on both
 *   sides for every tracked bee. If the caster dies first,
 *   the framework termination path runs this same end(), so
 *   the swarm never outlives its caller. SummonerTracker
 *   records are deliberately not unregistered, matching the
 *   other summons in this mod: that registry cleans up on
 *   the bee's own removal.
 *
 * WHAT IT LOOKS LIKE: a puff of angry red faces and musical
 * notes over the caller, a loud aggressive buzz, and three
 * bees dropping out of the sky onto the same victim; while
 * the swarm holds together, all four bees hit a little
 * harder.
 *
 * PERFORMANCE: start() does 3 spawns, once. tick() returns
 * immediately except every 5th tick, where it resolves at
 * most 3 UUIDs and writes at most 3 target re-orders; the
 * strength pass every 20 ticks touches at most 4 entities.
 * No entity scans anywhere. Particle budget per activation:
 * 20 at start, end() puffs at most 3 times 6 = 18, total at
 * most 38 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "swarm" (list of compounds, one per
 * summoned bee: "id", the bee's UUID).
 */
public final class SwarmCallAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int SWARM_COUNT = 3;
    private static final int BABYSIT_INTERVAL = 5;
    private static final int STRENGTH_INTERVAL = 20;
    private static final Identifier FOLLOW_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "swarm_call_follow");
    private static final double[][] OFFSETS = {
        {1.2, 0.0}, {-1.2, 0.0}, {0.0, 1.2}
    };

    @Override
    public String id() {
        return "swarm_call";
    }

    @Override
    public String displayName() {
        return "Swarm Call";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();
        ListTag swarm = new ListTag();
        ctx.state().put("swarm", swarm);
        if (level == null) {
            return;
        }

        // The call: angry faces, notes, and the loud buzz.
        level.sendParticles(ParticleTypes.ANGRY_VILLAGER, caster.getX(), caster.getY() + 0.6, caster.getZ(),
            12, 0.4, 0.3, 0.4, 0.05);
        level.sendParticles(ParticleTypes.NOTE, caster.getX(), caster.getY() + 1.2, caster.getZ(),
            8, 0.5, 0.3, 0.5, 0.1);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.BEE_LOOP_AGGRESSIVE,
            SoundSource.HOSTILE, 1.5f, 1.0f);

        LivingEntity target = ctx.target();
        for (int i = 0; i < SWARM_COUNT; i++) {
            Bee mate = EntityTypes.BEE.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (mate == null) {
                continue;
            }
            double[] offset = OFFSETS[i];
            mate.snapTo(caster.getX() + offset[0], caster.getY() + 2.5, caster.getZ() + offset[1],
                ctx.random().nextFloat() * 360.0f, 0.0f);
            mate.finalizeSpawn(level, level.getCurrentDifficultyAt(mate.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
            BeeAbilitySupport.addModifier(mate, Attributes.FOLLOW_RANGE,
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
            BeeAbilitySupport.trackEntity(ctx.state(), "swarm", mate);
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
        LivingEntity target = ctx.target();
        ListTag swarm = ctx.state().getListOrEmpty("swarm");
        int living = 1; // The caster itself.
        for (int i = 0; i < swarm.size(); i++) {
            Entity found = BeeAbilitySupport.resolveTracked(level, swarm.getCompoundOrEmpty(i));
            if (found instanceof Bee mate && mate.isAlive()) {
                living++;
                if (target != null && mate.getTarget() != target) {
                    mate.setTarget(target);
                }
            }
        }
        // The strength of the swarm, refreshed every 20 ticks
        // while the group holds together (see the class comment).
        if (ticks % STRENGTH_INTERVAL == 0 && living >= 3) {
            ctx.mob().addEffect(new MobEffectInstance(MobEffects.STRENGTH, 40, 0));
            for (int i = 0; i < swarm.size(); i++) {
                Entity found = BeeAbilitySupport.resolveTracked(level, swarm.getCompoundOrEmpty(i));
                if (found instanceof Bee mate && mate.isAlive()) {
                    mate.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 40, 0));
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
        ListTag swarm = ctx.state().getListOrEmpty("swarm");
        for (int i = 0; i < swarm.size(); i++) {
            CompoundTag entry = swarm.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id == null) {
                continue;
            }
            // The alliance ends with the ability, whichever side is still here.
            ctx.data().removeAlly(id);
            Entity found = level.getEntity(id);
            if (found instanceof Bee mate) {
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
