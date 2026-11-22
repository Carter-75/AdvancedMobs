package mod.advancedmobs.ability.parrot;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.parrot.Parrot;

import java.util.UUID;

/**
 * Flock Raid (Parrot ability 3 of 5, summon).
 *
 * WHAT IT DOES: the parrot squawks a rally call and three
 * more parrots dive in from the canopy in rainbow dust
 * trails. The flock divebombs in rotation, each bird
 * striking once per pass and wheeling away while the next
 * comes in, impossible to swat them all.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the parrot's current target within 18 blocks (distance
 *   squared 324).
 * - The call (start()): a PARROT_AMBIENT squawk (volume
 *   1.5, pitch 1.2), and 6 POOF particles at each arrival.
 * - The flock (start()): 3 parrots, spawned 6 blocks out
 *   from the caller at angles 90, 210, and 330 degrees and
 *   5 blocks up (out of the canopy), created with
 *   EntitySpawnReason.MOB_SUMMONED and finalizeSpawn like
 *   every other summon in this mod; finalizeSpawn rolls
 *   each bird's plumage variant, so the flock arrives in
 *   mixed colors under its rainbow trails. Each bird is a
 *   plain vanilla parrot (6 health, 3 damage). Team
 *   assignment, per bird, both directions plus the
 *   registry: the caster's data gains the bird as an ally,
 *   the bird's data gains the caster, and the pair is
 *   registered in SummonerTracker (which also tags the
 *   bird umw_summoned for the natural spawn blocker). Each
 *   bird is ordered onto the caster's current target at
 *   spawn.
 * - The rotation (tick(), every tick, code-driven because
 *   vanilla parrots never attack): the raid runs on a
 *   120-tick cycle and each bird owns a 40-tick window of
 *   it, staggered by bird index (bird i's local clock is
 *   (ticks + i times 40) mod 120). While a bird's local
 *   clock is under 25 it dives: its velocity is written
 *   straight at the target's chest (feet plus 0.8) at 0.9
 *   blocks per tick, and the first time it closes within
 *   1.8 blocks during a cycle it strikes exactly once for
 *   its live ATTACK_DAMAGE value (3.0 unupgraded) as
 *   mob-attack damage credited to that bird, with 4 POOF
 *   particles at the hit; the struck cycle number is kept
 *   per bird in the state tag so a bird can never strike
 *   twice in one pass. While its local clock is 25 or
 *   more the bird wheels away: velocity written toward a
 *   point 6 blocks out from the target on the bird's own
 *   side and 3.5 blocks up, at 0.7 blocks per tick, which
 *   carries it clear for the next bird's pass. If the
 *   target dies mid-raid, the birds wheel around the
 *   caster instead (same wheel-away steering, centered on
 *   the caster) until the raid ends or a new target is
 *   picked up.
 * - Babysitting (tick(), every 5 ticks): each living
 *   tracked bird whose target is not the caster's current
 *   target is re-ordered onto it, keeping the flock's war
 *   bookkeeping on the caller's fight.
 * - The trails (tick(), every 2 ticks): each living bird
 *   sheds 1 DUST_COLOR_TRANSITION mote from the shared
 *   rainbow cycle (the option index advances with the bird
 *   index and the elapsed tens of ticks), the design's
 *   rainbow dust trails.
 * - Duration: 300 ticks (15 seconds), hard finish.
 * - end(): each surviving flock bird is discarded in 6
 *   POOF particles and the alliance records are removed on
 *   both sides for every tracked bird. If the caster dies
 *   first, the framework termination path runs this same
 *   end(), so the flock never outlives its caller.
 *   SummonerTracker records are deliberately not
 *   unregistered, matching the other summons in this mod:
 *   that registry cleans up on the bird's own removal.
 *
 * WHAT IT LOOKS LIKE: a piercing squawk, three puffs in
 * the canopy, and then a merry-go-round of wings: one bird
 * is always folding into a dive with rainbow dust streaming
 * off it, striking, and wheeling up and away while the
 * next rolls in.
 *
 * PERFORMANCE: start() does 3 spawns, once. tick() steers
 * at most 3 birds with one velocity write each, resolves
 * at most 3 UUIDs per tick, and emits at most 3 trail
 * motes every 2 ticks; the babysit pass runs every 5 ticks
 * over the same 3 UUIDs. No entity scans anywhere.
 * Particle budget per activation: spawn puffs 3 times 6 =
 * 18, strikes at most 3 birds times 3 cycles times 4 = 36,
 * trails at most 150 sends times 3 = 450, end() puffs at
 * most 18; total at most 522, stated cap 530.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "flock" (list of compounds, one per
 * summoned bird: "id", the bird's UUID), "struck_0",
 * "struck_1", "struck_2" (the cycle number each bird last
 * struck in, -1 until its first strike).
 */
public final class FlockRaidAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int FLOCK_COUNT = 3;
    private static final int CYCLE_TICKS = 120;
    private static final int WINDOW_TICKS = 40;
    private static final int DIVE_TICKS = 25;
    private static final double DIVE_SPEED = 0.9;
    private static final double WHEEL_SPEED = 0.7;
    private static final double STRIKE_DISTANCE = 1.8;
    private static final int BABYSIT_INTERVAL = 5;
    private static final double[][] SPAWN_DIRECTIONS = {
        {0.0, 1.0}, {-0.866, -0.5}, {0.866, -0.5}
    };

    @Override
    public String id() {
        return "flock_raid";
    }

    @Override
    public String displayName() {
        return "Flock Raid";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 324.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        state.put("flock", new ListTag());
        for (int i = 0; i < FLOCK_COUNT; i++) {
            state.putInt("struck_" + i, -1);
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.PARROT_AMBIENT,
            SoundSource.HOSTILE, 1.5f, 1.2f);

        LivingEntity target = ctx.target();
        for (int i = 0; i < FLOCK_COUNT; i++) {
            Parrot mate = EntityTypes.PARROT.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (mate == null) {
                continue;
            }
            double[] dir = SPAWN_DIRECTIONS[i];
            mate.snapTo(caster.getX() + dir[0] * 6.0, caster.getY() + 5.0, caster.getZ() + dir[1] * 6.0,
                ctx.random().nextFloat() * 360.0f, 0.0f);
            mate.finalizeSpawn(level, level.getCurrentDifficultyAt(mate.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
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
            level.sendParticles(ParticleTypes.POOF, mate.getX(), mate.getY() + 0.3, mate.getZ(),
                6, 0.3, 0.2, 0.3, 0.05);
            ParrotAbilitySupport.trackEntity(state, "flock", mate);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        CompoundTag state = ctx.state();
        LivingEntity target = ctx.target();
        ListTag flock = state.getListOrEmpty("flock");

        for (int i = 0; i < flock.size(); i++) {
            Entity found = ParrotAbilitySupport.resolveTracked(level, flock.getCompoundOrEmpty(i));
            if (!(found instanceof Parrot mate) || !mate.isAlive()) {
                continue;
            }
            // Babysitting: keep the flock ordered onto the caller's fight.
            if (ticks % BABYSIT_INTERVAL == 0 && target != null && mate.getTarget() != target) {
                mate.setTarget(target);
            }
            steerBird(ctx, mate, i, ticks, target);
            // The rainbow dust trail, every 2 ticks.
            if (ticks % 2 == 0) {
                level.sendParticles(
                    ParrotAbilitySupport.RAINBOW[(i + ticks / 10) % ParrotAbilitySupport.RAINBOW.length],
                    mate.getX(), mate.getY() + 0.3, mate.getZ(), 1, 0.1, 0.1, 0.1, 0.0);
            }
        }
    }

    /** One bird's steering and striking for this tick (see the class comment's rotation spec). */
    private static void steerBird(AbilityContext ctx, Parrot mate, int index, int ticks, LivingEntity target) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        // The wheel center: the target while it lives, else the caller.
        Entity center = target != null ? target : ctx.mob();
        int shifted = ticks + index * WINDOW_TICKS;
        int local = shifted % CYCLE_TICKS;
        int cycle = shifted / CYCLE_TICKS;

        if (target != null && local < DIVE_TICKS) {
            double dist = ParrotAbilitySupport.steerToward(mate,
                target.getX(), target.getY() + 0.8, target.getZ(), DIVE_SPEED);
            String struckKey = "struck_" + index;
            if (dist <= STRIKE_DISTANCE && ctx.state().getIntOr(struckKey, -1) != cycle) {
                ctx.state().putInt(struckKey, cycle);
                float damage = (float) mate.getAttributeValue(Attributes.ATTACK_DAMAGE);
                target.hurtServer(level, level.damageSources().mobAttack(mate), damage);
                level.sendParticles(ParticleTypes.POOF, mate.getX(), mate.getY() + 0.3, mate.getZ(),
                    4, 0.25, 0.2, 0.25, 0.05);
            }
        } else {
            // Wheel away: out from the center on this bird's own side, and up.
            double awayX = mate.getX() - center.getX();
            double awayZ = mate.getZ() - center.getZ();
            double len = Math.sqrt(awayX * awayX + awayZ * awayZ);
            if (len < 0.001) {
                awayX = 1.0;
                awayZ = 0.0;
                len = 1.0;
            }
            ParrotAbilitySupport.steerToward(mate,
                center.getX() + (awayX / len) * 6.0, center.getY() + 3.5,
                center.getZ() + (awayZ / len) * 6.0, WHEEL_SPEED);
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
        ListTag flock = ctx.state().getListOrEmpty("flock");
        for (int i = 0; i < flock.size(); i++) {
            CompoundTag entry = flock.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id == null) {
                continue;
            }
            // The alliance ends with the ability, whichever side is still here.
            ctx.data().removeAlly(id);
            Entity found = level.getEntity(id);
            if (found instanceof Parrot mate) {
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
