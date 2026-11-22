package mod.advancedmobs.ability.bat;

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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ambient.Bat;

import java.util.UUID;

/**
 * Rabid Swarm (Bat ability 4 of 5, summon).
 *
 * WHAT IT DOES: a screech, and the cave ceiling gives up
 * its secrets: three more bats pour out of the dark in a
 * flutter of poof particles and join the attack. The swarm
 * is fragile but maddening: the copies dive at the caster's
 * victim on their own wings, each bite carrying a chance of
 * weakness, and enemies waste their big swings on empty air
 * while the little shapes keep coming.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the bat's current target within 16 blocks (distance
 *   squared 256).
 * - The copies (start()): 3 bats, spawned at offsets
 *   (-1.0, 0.5, 0.0), (1.0, 0.8, 0.5), (0.0, 1.1, -0.8)
 *   around the caster, created with
 *   EntitySpawnReason.MOB_SUMMONED and finalizeSpawn like
 *   every other summon in this mod. They keep the plain
 *   vanilla bat sheet (6 health, no attack attribute): the
 *   swarm's damage is code, below. Each copy is woken
 *   (setResting(false)) so it never casts from a ceiling.
 *   Team assignment, per copy, both directions plus the
 *   registry: the caster's data gains the copy as an ally,
 *   the copy's data gains the caster, and the pair is
 *   registered in SummonerTracker (which also tags the copy
 *   umw_summoned for the natural spawn blocker), the
 *   design's team assignment line. The caster itself takes
 *   off if it was resting. The pour-out is drawn as 12 POOF
 *   particles at the caster and a BAT_AMBIENT screech
 *   (volume 1.2, pitch 1.3).
 * - The swarm flight (every tick): each living copy is
 *   steered by velocity write at the caster's current
 *   target's chest (feet plus 0.6) at 0.85 blocks per tick,
 *   because a vanilla bat has no attack goal to order around
 *   (the Duet pattern: the summon's offense is code). A copy
 *   that has settled into resting is woken. With no live
 *   target the copies steer back to a point 1.0 above the
 *   caster at 0.6 blocks per tick and regroup.
 * - The bites (checked every tick per copy): a copy within
 *   1.4 blocks of the target whose per-copy bite clock has
 *   come due (20 ticks between a copy's bites, stored per
 *   copy in the state tag) bites: a flat 1.5 damage through
 *   damageSources().mobAttack(copy), credited to the copy
 *   itself, 3 CRIT particles at the victim's chest, and a
 *   35 percent roll on the caster's random source: on
 *   success the victim gains WEAKNESS I (amplifier 0) for
 *   80 ticks, the design's rabid bite.
 * - Babysitting and the dissolve (every 5 ticks): each
 *   tracked copy's position is recorded into the state tag.
 *   A copy that no longer resolves, or resolves dead, has
 *   been swatted down: at its last recorded position the
 *   ability throws 8 POOF particles, plays a BAT_DEATH
 *   (volume 0.6, pitch 1.4, a copy's thinner death cry),
 *   removes the caster's ally record for it, and marks it
 *   gone so the burst runs exactly once. A copy that unloads
 *   with its chunk instead of dying also reads as gone to
 *   this pass and dissolves the same way; if it later
 *   reloads it is an ordinary allied bat with no babysitter,
 *   an accepted edge case shared with every tracked summon
 *   in this mod.
 * - Duration: 300 ticks (15 seconds), hard finish.
 * - end(): each surviving copy is discarded in 6 POOF
 *   particles at its position and the alliance records are
 *   removed on both sides for every tracked copy. If the
 *   caster dies first, the framework termination path runs
 *   this same end(), so the swarm never outlives its
 *   summoner. SummonerTracker records are deliberately not
 *   unregistered, matching the other summons in this mod:
 *   that registry cleans up on the copy's own removal.
 *
 * WHAT IT LOOKS LIKE: a screech and a burst of poof, and
 * suddenly the cave holds four bats: the shapes pour off
 * the ceiling and mob the victim in a flutter of wings and
 * small bites, and every swat that connects just pops one
 * into a puff of dust.
 *
 * PERFORMANCE: start() does 3 spawns, once. tick() resolves
 * at most 3 UUIDs and writes at most 3 velocity writes per
 * tick, with bite checks as distance reads only; the
 * babysit pass runs every 5 ticks. No entity scans
 * anywhere. Particle budget per activation: 12 at start,
 * bites at most 45 times 3 = 135, dissolves at most 3 times
 * 8 = 24, end() puffs at most 3 times 6 = 18, total at most
 * 189 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "copies" (list of compounds, one per
 * copy: "id" the copy's UUID, "x", "y", "z" its last
 * recorded position, "gone" 1 once its dissolve burst ran,
 * "next_bite" the tick its next bite may land).
 */
public final class RabidSwarmAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int BABYSIT_INTERVAL = 5;
    private static final int COPY_COUNT = 3;
    private static final double SWARM_SPEED = 0.85;
    private static final double REGROUP_SPEED = 0.6;
    private static final double BITE_DISTANCE = 1.4;
    private static final int BITE_INTERVAL = 20;
    private static final float BITE_DAMAGE = 1.5f;
    private static final double WEAKNESS_CHANCE = 0.35;
    private static final int WEAKNESS_TICKS = 80;
    private static final double[][] OFFSETS = {
        {-1.0, 0.5, 0.0},
        {1.0, 0.8, 0.5},
        {0.0, 1.1, -0.8}
    };

    @Override
    public String id() {
        return "rabid_swarm";
    }

    @Override
    public String displayName() {
        return "Rabid Swarm";
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
        ListTag copies = new ListTag();
        ctx.state().put("copies", copies);
        if (level == null) {
            return;
        }
        if (caster instanceof Bat bat && bat.isResting()) {
            bat.setResting(false);
        }
        level.sendParticles(ParticleTypes.POOF, caster.getX(), caster.getY() + 0.4, caster.getZ(),
            12, 0.5, 0.4, 0.5, 0.08);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.BAT_AMBIENT,
            SoundSource.HOSTILE, 1.2f, 1.3f);

        for (int i = 0; i < COPY_COUNT; i++) {
            Bat copy = EntityTypes.BAT.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (copy == null) {
                continue;
            }
            copy.snapTo(caster.getX() + OFFSETS[i][0], caster.getY() + OFFSETS[i][1],
                caster.getZ() + OFFSETS[i][2], caster.getYRot(), 0.0f);
            copy.finalizeSpawn(level, level.getCurrentDifficultyAt(copy.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
            copy.setResting(false);
            if (!level.addFreshEntity(copy)) {
                continue;
            }
            ctx.data().addAlly(copy.getUUID());
            MobWarData.get(copy).addAlly(caster.getUUID());
            SummonerTracker.registerSummoned(copy.getUUID(), caster.getUUID());

            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, copy.getUUID());
            entry.putDouble("x", copy.getX());
            entry.putDouble("y", copy.getY());
            entry.putDouble("z", copy.getZ());
            entry.putInt("gone", 0);
            entry.putInt("next_bite", 0);
            copies.add(entry);
        }
        ctx.state().put("copies", copies);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        LivingEntity target = ctx.target();
        int ticks = ctx.ticksActive();
        boolean babysit = ticks % BABYSIT_INTERVAL == 0;
        ListTag copies = ctx.state().getListOrEmpty("copies");
        for (int i = 0; i < copies.size(); i++) {
            CompoundTag entry = copies.getCompoundOrEmpty(i);
            if (entry.getIntOr("gone", 0) == 1) {
                continue;
            }
            Entity found = BatAbilitySupport.resolveTracked(level, entry);
            if (found instanceof Bat copy && copy.isAlive()) {
                if (copy.isResting()) {
                    copy.setResting(false);
                }
                if (target != null) {
                    double dist = BatAbilitySupport.steerEntityToward(copy,
                        target.getX(), target.getY() + 0.6, target.getZ(), SWARM_SPEED);
                    if (dist <= BITE_DISTANCE && ticks >= entry.getIntOr("next_bite", 0)) {
                        entry.putInt("next_bite", ticks + BITE_INTERVAL);
                        bite(ctx, level, copy, target);
                    }
                } else {
                    BatAbilitySupport.steerEntityToward(copy,
                        caster.getX(), caster.getY() + 1.0, caster.getZ(), REGROUP_SPEED);
                }
                if (babysit) {
                    entry.putDouble("x", copy.getX());
                    entry.putDouble("y", copy.getY());
                    entry.putDouble("z", copy.getZ());
                }
            } else if (babysit) {
                dissolve(ctx, level, entry);
            }
            copies.set(i, entry);
        }
        ctx.state().put("copies", copies);
    }

    /** One rabid bite from one copy (see the class comment). */
    private static void bite(AbilityContext ctx, ServerLevel level, Bat copy, LivingEntity target) {
        target.hurtServer(level, level.damageSources().mobAttack(copy), BITE_DAMAGE);
        if (ctx.random().nextDouble() < WEAKNESS_CHANCE) {
            target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, WEAKNESS_TICKS, 0));
        }
        level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY() + 0.6, target.getZ(),
            3, 0.25, 0.3, 0.25, 0.1);
    }

    /** The dissolve burst for one swatted copy (see the class comment). Runs once per copy. */
    private static void dissolve(AbilityContext ctx, ServerLevel level, CompoundTag entry) {
        entry.putInt("gone", 1);
        UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
        if (id != null) {
            ctx.data().removeAlly(id);
        }
        double x = entry.getDoubleOr("x", ctx.mob().getX());
        double y = entry.getDoubleOr("y", ctx.mob().getY());
        double z = entry.getDoubleOr("z", ctx.mob().getZ());
        level.sendParticles(ParticleTypes.POOF, x, y + 0.3, z, 8, 0.3, 0.3, 0.3, 0.06);
        level.playSound(null, x, y, z, SoundEvents.BAT_DEATH, SoundSource.HOSTILE, 0.6f, 1.4f);
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
        ListTag copies = ctx.state().getListOrEmpty("copies");
        for (int i = 0; i < copies.size(); i++) {
            CompoundTag entry = copies.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id == null) {
                continue;
            }
            ctx.data().removeAlly(id);
            Entity found = level.getEntity(id);
            if (found instanceof Bat copy) {
                MobWarData.get(copy).removeAlly(caster.getUUID());
                if (copy.isAlive()) {
                    level.sendParticles(ParticleTypes.POOF, copy.getX(), copy.getY() + 0.3, copy.getZ(),
                        6, 0.3, 0.3, 0.3, 0.06);
                    copy.discard();
                }
            }
        }
    }
}
