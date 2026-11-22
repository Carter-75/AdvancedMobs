package mod.advancedmobs.ability.chicken;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.chicken.Chicken;

import java.util.UUID;

/**
 * Chick Swarm (Chicken ability 2 of 5, summon).
 *
 * WHAT IT DOES: the hen calls down the coop and five
 * baby chickens pour out around her in a puff of down, a
 * ankle-high flock at tiny scale that floods the enemy's
 * legs. Each chick is individually harmless, but together
 * they body block, distract, and peck away in a steady
 * drip of chip damage while the mother hen strikes from
 * behind the wall of fluff.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the chicken's current target within 14 blocks
 *   (distance squared 196).
 * - The flock (start()): 5 baby chickens, spawned one per
 *   offset around the caster (radius 1.2 at angles 90,
 *   162, 234, 306, and 18 degrees), each at the first
 *   collision-free spot in that offset then the caster's
 *   own position as fallback, created with
 *   EntitySpawnReason.MOB_SUMMONED and finalizeSpawn like
 *   every other summon in this mod. Each chick is a baby
 *   (age set to -24000 after finalizeSpawn, the vanilla
 *   baby age) with the design's tiny scale as a PERMANENT
 *   SCALE base value of 0.5 on the new mob (a real mob,
 *   not a prop, so the scale is a base value rather than
 *   a transient modifier to clean up: the Noxious Split
 *   precedent; on top of the baby's own half size the
 *   chicks stand about a quarter of an adult tall). Health
 *   is the plain chicken 4.0. Team assignment, per chick,
 *   both directions plus the registry: the caster's data
 *   gains the chick as an ally, the chick's data gains
 *   the caster, and the pair is registered in
 *   SummonerTracker (which also tags the chick
 *   umw_summoned for the natural spawn blocker). Each
 *   chick is ordered onto the caster's current target at
 *   spawn.
 * - The call (start()): 10 POOF and 6 HAPPY_VILLAGER
 *   particles burst at the caster (the design's two
 *   particles) and the classic variant ambient cluck
 *   plays (volume 1.3, pitch 1.2).
 * - Babysitting (tick(), every 5 ticks): each living
 *   chick's position is recorded into the state tag, a
 *   chick whose target is not the caster's current target
 *   is re-ordered onto it, and the chick's navigation is
 *   driven at the target at speed 1.2 whenever the target
 *   is more than 1.3 blocks away: a passive animal's AI
 *   never chases a set target on its own, so without the
 *   drive the flock would mill around the hen instead of
 *   swarming (the Sporeling Swarm precedent).
 * - The pecking (same babysit pass): a chick within 1.3
 *   blocks of the caster's current target pecks it for
 *   1.0 mob-attack damage credited to the CHICK (flat
 *   code value; a chicken has no ATTACK_DAMAGE instance,
 *   see the support class comment), at most once per
 *   chick per 20 ticks (each chick's last peck tick is
 *   recorded in its entry), with 2 DAMAGE_INDICATOR
 *   particles at the victim's feet. Five chicks all in
 *   range peck for at most 5.0 damage a second between
 *   them: the drip is the threat, not any single peck.
 * - A chick's death (same pass): a tracked chick that no
 *   longer resolves, or resolves dead, pops exactly once
 *   at its last recorded position: 6 POOF particles, the
 *   caster's ally record for it is removed, and the entry
 *   is marked gone. A chick that unloads with its chunk
 *   instead of dying reads as gone to this pass and pops
 *   the same way; if it later reloads it is an ordinary
 *   tiny allied baby chicken with no babysitter, an
 *   accepted edge case shared with every tracked summon
 *   in this mod.
 * - Duration: 300 ticks (15 seconds), hard finish.
 * - end(): each surviving chick is discarded in 6 POOF
 *   particles at its position and the alliance records
 *   are removed on both sides for every tracked chick.
 *   If the caster dies first, the framework termination
 *   path runs this same end(), so the flock never
 *   outlives its mother. SummonerTracker records are
 *   deliberately not unregistered, matching the other
 *   summons in this mod: that registry cleans up on the
 *   chick's own removal.
 *
 * WHAT IT LOOKS LIKE: a cluck and a puff of down, and
 * suddenly five quarter-size chicks are pouring around
 * the hen and flooding toward the enemy's ankles,
 * bouncing and pecking in a ring of tiny bodies while the
 * hen closes in behind them; swat one down and it pops
 * into a puff of down instead of falling like a bird.
 *
 * PERFORMANCE: start() does 5 bounded collision checks
 * and 5 spawns, once. tick() returns immediately except
 * every 5th tick, where it resolves at most 5 UUIDs,
 * drives at most 5 navigations, and lands at most 5 peck
 * damage calls: no entity scans anywhere. Particle budget
 * per activation: 16 at start, pecks at most 5 chicks
 * times 15 pecks times 2 = 150, death pops at most 5
 * times 6 = 30, end() puffs at most 5 times 6 = 30, total
 * at most 226 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "chicks" (list of compounds, one per
 * chick: "id" the chick's UUID, "x", "y", "z" its last
 * recorded position, "gone" 1 once its pop ran, "peck"
 * the activation tick of its last peck).
 */
public final class ChickSwarmAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int BABYSIT_INTERVAL = 5;
    private static final int CHICK_COUNT = 5;
    private static final double SPAWN_RADIUS = 1.2;
    private static final double DRIVE_SPEED = 1.2;
    private static final double PECK_RANGE = 1.3;
    private static final float PECK_DAMAGE = 1.0f;
    private static final int PECK_INTERVAL_TICKS = 20;
    private static final double[] OFFSET_ANGLES = {90.0, 162.0, 234.0, 306.0, 18.0};

    @Override
    public String id() {
        return "chick_swarm";
    }

    @Override
    public String displayName() {
        return "Chick Swarm";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();
        ListTag chicks = new ListTag();
        ctx.state().put("chicks", chicks);
        if (level == null) {
            return;
        }

        level.sendParticles(ParticleTypes.POOF, caster.getX(), caster.getY() + 0.4, caster.getZ(),
            10, 0.4, 0.3, 0.4, 0.05);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, caster.getX(), caster.getY() + 0.5, caster.getZ(),
            6, 0.4, 0.3, 0.4, 0.05);
        Holder<SoundEvent> cluck = ChickenAbilitySupport.chickenSound(false);
        if (cluck != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), cluck,
                SoundSource.HOSTILE, 1.3f, 1.2f);
        }

        LivingEntity target = ctx.target();
        for (int i = 0; i < CHICK_COUNT; i++) {
            Chicken chick = EntityTypes.CHICKEN.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (chick == null) {
                continue;
            }
            double angle = Math.toRadians(OFFSET_ANGLES[i]);
            double spawnX = caster.getX() + Math.cos(angle) * SPAWN_RADIUS;
            double spawnZ = caster.getZ() + Math.sin(angle) * SPAWN_RADIUS;
            if (!ChickenAbilitySupport.isFreeSpot(level, chick, spawnX, caster.getY(), spawnZ)) {
                spawnX = caster.getX();
                spawnZ = caster.getZ();
            }
            chick.snapTo(spawnX, caster.getY(), spawnZ, ctx.random().nextFloat() * 360.0f, 0.0f);
            chick.finalizeSpawn(level, level.getCurrentDifficultyAt(chick.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
            // The chick's sheet (see the class comment):
            // baby age and the permanent tiny scale, set
            // after finalizeSpawn so nothing re-rolls them.
            chick.setAge(-24000);
            AttributeInstance scale = chick.getAttribute(Attributes.SCALE);
            if (scale != null) {
                scale.setBaseValue(0.5);
            }
            if (!level.addFreshEntity(chick)) {
                continue;
            }
            ctx.data().addAlly(chick.getUUID());
            MobWarData.get(chick).addAlly(caster.getUUID());
            SummonerTracker.registerSummoned(chick.getUUID(), caster.getUUID());
            if (target != null) {
                chick.setTarget(target);
            }

            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, chick.getUUID());
            entry.putDouble("x", chick.getX());
            entry.putDouble("y", chick.getY());
            entry.putDouble("z", chick.getZ());
            entry.putInt("gone", 0);
            entry.putInt("peck", -PECK_INTERVAL_TICKS);
            chicks.add(entry);
        }
        ctx.state().put("chicks", chicks);
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % BABYSIT_INTERVAL != 0) {
            return; // Cheap early-out: babysitting runs every 5 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        LivingEntity target = ctx.target();
        ListTag chicks = ctx.state().getListOrEmpty("chicks");
        for (int i = 0; i < chicks.size(); i++) {
            CompoundTag entry = chicks.getCompoundOrEmpty(i);
            if (entry.getIntOr("gone", 0) == 1) {
                continue;
            }
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            Entity found = id != null ? level.getEntity(id) : null;
            if (found instanceof Chicken chick && chick.isAlive()) {
                entry.putDouble("x", chick.getX());
                entry.putDouble("y", chick.getY());
                entry.putDouble("z", chick.getZ());
                if (target != null) {
                    if (chick.getTarget() != target) {
                        chick.setTarget(target);
                    }
                    double distSqr = chick.distanceToSqr(target);
                    if (distSqr > PECK_RANGE * PECK_RANGE) {
                        chick.getNavigation().moveTo(target, DRIVE_SPEED);
                    } else if (ctx.ticksActive() - entry.getIntOr("peck", -PECK_INTERVAL_TICKS)
                            >= PECK_INTERVAL_TICKS) {
                        entry.putInt("peck", ctx.ticksActive());
                        target.hurtServer(level, level.damageSources().mobAttack(chick), PECK_DAMAGE);
                        level.sendParticles(ParticleTypes.DAMAGE_INDICATOR,
                            target.getX(), target.getY() + 0.3, target.getZ(),
                            2, 0.25, 0.2, 0.25, 0.05);
                    }
                }
            } else {
                pop(ctx, level, entry);
            }
            chicks.set(i, entry);
        }
        ctx.state().put("chicks", chicks);
    }

    /** The down puff for one destroyed chick (see the class comment). Runs once per chick. */
    private static void pop(AbilityContext ctx, ServerLevel level, CompoundTag entry) {
        entry.putInt("gone", 1);
        UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
        if (id != null) {
            ctx.data().removeAlly(id);
        }
        double x = entry.getDoubleOr("x", ctx.mob().getX());
        double y = entry.getDoubleOr("y", ctx.mob().getY());
        double z = entry.getDoubleOr("z", ctx.mob().getZ());
        level.sendParticles(ParticleTypes.POOF, x, y + 0.2, z, 6, 0.2, 0.2, 0.2, 0.05);
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
        ListTag chicks = ctx.state().getListOrEmpty("chicks");
        for (int i = 0; i < chicks.size(); i++) {
            CompoundTag entry = chicks.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id == null) {
                continue;
            }
            ctx.data().removeAlly(id);
            Entity found = level.getEntity(id);
            if (found instanceof Chicken chick) {
                MobWarData.get(chick).removeAlly(caster.getUUID());
                if (chick.isAlive()) {
                    level.sendParticles(ParticleTypes.POOF, chick.getX(), chick.getY() + 0.2, chick.getZ(),
                        6, 0.2, 0.2, 0.2, 0.05);
                    chick.discard();
                }
            }
        }
    }
}
