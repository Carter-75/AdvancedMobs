package mod.advancedmobs.ability.turtle;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.entity.animal.turtle.Turtle;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TurtleEggBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

/**
 * Egg Bastion (Turtle ability 3 of 5, summon / terrain).
 *
 * WHAT IT DOES: the turtle hauls out of the fight, digs in
 * on land, and lays a clutch of real turtle eggs in a ring
 * around herself, settling over them fat with fresh
 * absorption. Eight seconds later the clutch hatches in
 * puffs of egg crack particles: four baby turtles boil out
 * and immediately join the fight at her side, snapping at
 * her target while she guards the nest.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the turtle's current target within 12 blocks (distance
 *   squared 144), plus the turtle on the ground and NOT in
 *   water (a bastion is a land nest; a swimming turtle
 *   cannot lay).
 * - The laying (start()): TURTLE_LAY_EGG at volume 1.0, and
 *   the mother gains ABSORPTION II (8 absorption points,
 *   the design's fresh absorption line) for 420 ticks,
 *   removed in end(). Four nest points are picked on a
 *   radius 1.8 ring around her at the diagonal angles (45,
 *   135, 225, 315 degrees); each point's ground height is
 *   found with one bounded surface scan (the support
 *   helper) and the point is recorded in the state tag.
 * - The eggs (start(), TERRAIN, gated): when
 *   AbilityContext.mobGriefingAllowed() is true, one real
 *   turtle egg block is placed at each nest point whose
 *   cell is replaceable, holds no block entity, and has a
 *   solid, dry block directly below (never a container,
 *   never unbreakable, never floating), with the EGGS
 *   property set to 2 and HATCH to 0 (the design's setblock
 *   clutch line). Per the standing terrain rule the placed
 *   eggs are PERMANENT: they are not removed when the
 *   ability ends, and the hatch below is a code summon at
 *   the nest points, not the blocks hatching (vanilla eggs
 *   only progress on sand, so off sand the blocks simply
 *   stay as a laid clutch). With mobGriefing off, no eggs
 *   are placed; the absorption, the settle, and the
 *   hatchlings all still run.
 * - The settle (tick(), every tick): the mother's
 *   navigation is stopped (she stays over the clutch,
 *   never NoAi).
 * - The cracks (tick(), ticks 80 and 120): at one random
 *   nest point, 6 EGG_CRACK particles and TURTLE_EGG_CRACK
 *   at volume 0.7: the clutch is stirring.
 * - The hatch (tick(), tick 160, once, guarded by the
 *   "hatched" flag): TURTLE_EGG_HATCH at volume 1.0 at the
 *   mother, and at every nest point 10 EGG_CRACK particles
 *   plus one baby turtle: created with
 *   EntitySpawnReason.MOB_SUMMONED, age set to -24000 (a
 *   juvenile), finalizeSpawn, snapped to the nest point.
 *   Team assignment per baby, both directions plus the
 *   registry: the mother's data gains the baby as an ally,
 *   the baby's data gains the mother, and the pair is
 *   registered in SummonerTracker (which also tags the
 *   baby umw_summoned for the natural spawn blocker).
 * - The guard (tick(), every 5 ticks after the hatch):
 *   each living baby is navigation-driven onto the
 *   mother's live target at speed 1.2; within 1.7 blocks
 *   and at least 15 ticks since its last bite (kept per
 *   baby in its tracked entry), it bites for 1.5 flat
 *   damage as a mob attack credited to the baby (the
 *   turtle has no ATTACK_DAMAGE instance; see the provider
 *   comment). With no live target, a baby farther than 2.5
 *   blocks from the mother is driven back to her at speed
 *   1.0, so the brood huddles at the nest instead of
 *   wandering off.
 * - Duration: exactly 400 ticks (20 seconds), hard finish.
 * - end(): the ABSORPTION effect is removed, and each
 *   surviving baby is discarded in 6 EGG_CRACK particles
 *   with the alliance records removed on both sides. The
 *   babies are a temporary summon, not a permanent brood
 *   (the Schooling Surge / Cub Guard precedent). If the
 *   mother dies first, the framework termination path runs
 *   this same end(), so the brood never outlives her. The
 *   placed egg blocks are NOT touched (permanent terrain,
 *   above). Idempotent.
 *
 * WHAT IT LOOKS LIKE: the turtle scrapes herself a hollow
 * mid-fight, pale eggs appear in a neat ring around her,
 * and she settles over them like a scaly hen. The shells
 * stir, crack, and four coin-sized turtles pour out and
 * charge the enemy in a line.
 *
 * PERFORMANCE: start() is 4 surface scans (at most 12 block
 * reads each), at most 4 gated placements, one effect, and
 * one sound, once. The settle is one navigation stop per
 * tick; the crack previews are 2 one-shot moments; the
 * hatch is 4 spawns once; the guard resolves at most 4
 * UUIDs and issues at most 4 navigation calls every 5
 * ticks. No bounded area queries anywhere. Particle budget
 * per activation: 2 crack previews times 6 = 12, hatch 4
 * times 10 = 40, end() puffs at most 4 times 6 = 24, total
 * at most 76 particles.
 *
 * State tag keys: "nests" (list of compounds, one per nest
 * point: "x", "y", "z" doubles), "hatched" (1 once the
 * hatch has run), "babies" (list of compounds, one per
 * hatchling: "id", the baby's UUID; "bite", the activation
 * tick of its last landed bite).
 */
public final class EggBastionAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int HATCH_TICK = 160;
    private static final int NEST_COUNT = 4;
    private static final double NEST_RADIUS = 1.8;
    private static final int EGGS_PER_CLUSTER = 2;
    private static final int BITE_INTERVAL_TICKS = 15;
    private static final float BITE_DAMAGE = 1.5f;

    @Override
    public String id() {
        return "egg_bastion";
    }

    @Override
    public String displayName() {
        return "Egg Bastion";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        Mob caster = ctx.mob();
        return target != null && caster.onGround() && !caster.isInWater()
            && caster.distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ctx.state().put("nests", new ListTag());
        ctx.state().put("babies", new ListTag());
        ctx.state().putInt("hatched", 0);

        // The design's fresh absorption for the nesting mother.
        caster.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, DURATION_TICKS + 20, 1));

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.TURTLE_LAY_EGG, SoundSource.HOSTILE, 1.0f, 1.0f);

        // The ring: four nest points on the diagonals.
        boolean mayPlace = ctx.mobGriefingAllowed();
        for (int i = 0; i < NEST_COUNT; i++) {
            double angle = Math.PI / 4.0 + i * Math.PI / 2.0;
            double nx = caster.getX() + Math.cos(angle) * NEST_RADIUS;
            double nz = caster.getZ() + Math.sin(angle) * NEST_RADIUS;
            double ny = TurtleAbilitySupport.findGroundY(level, nx, caster.getY(), nz);
            CompoundTag nest = new CompoundTag();
            nest.putDouble("x", nx);
            nest.putDouble("y", ny);
            nest.putDouble("z", nz);
            ListTag nests = ctx.state().getListOrEmpty("nests");
            nests.add(nest);
            ctx.state().put("nests", nests);
            if (mayPlace) {
                placeEgg(level, nx, ny, nz);
            }
        }
    }

    /**
     * Places one egg cluster block at the nest point when the
     * cell is safe: replaceable, no block entity, and a solid
     * dry block directly below. See the TERRAIN note in the
     * class comment.
     */
    private void placeEgg(ServerLevel level, double x, double y, double z) {
        BlockPos cell = new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        BlockState cellState = level.getBlockState(cell);
        if (!cellState.canBeReplaced() || level.getBlockEntity(cell) != null) {
            return;
        }
        BlockState below = level.getBlockState(cell.below());
        if (below.isAir() || !below.getFluidState().isEmpty()) {
            return;
        }
        BlockState egg = Blocks.TURTLE_EGG.defaultBlockState()
            .setValue(TurtleEggBlock.EGGS, EGGS_PER_CLUSTER)
            .setValue(TurtleEggBlock.HATCH, 0);
        level.setBlock(cell, egg, 3);
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        // Settled over the clutch: hold position through
        // navigation stops, never NoAi.
        caster.getNavigation().stop();

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        if ((ticks == 80 || ticks == 120) && ctx.state().getIntOr("hatched", 0) == 0) {
            crackPreview(ctx, level);
        }
        if (ticks >= HATCH_TICK && ctx.state().getIntOr("hatched", 0) == 0) {
            hatch(ctx, level);
        }
        if (ctx.state().getIntOr("hatched", 0) == 1 && ticks % 5 == 0) {
            guardPass(ctx, level, ticks);
        }
    }

    /** One crack preview: a random nest point stirs. See the class comment. */
    private void crackPreview(AbilityContext ctx, ServerLevel level) {
        ListTag nests = ctx.state().getListOrEmpty("nests");
        if (nests.isEmpty()) {
            return;
        }
        CompoundTag nest = nests.getCompoundOrEmpty(ctx.random().nextInt(nests.size()));
        double nx = nest.getDoubleOr("x", ctx.mob().getX());
        double ny = nest.getDoubleOr("y", ctx.mob().getY());
        double nz = nest.getDoubleOr("z", ctx.mob().getZ());
        level.sendParticles(ParticleTypes.EGG_CRACK, nx, ny + 0.3, nz, 6, 0.25, 0.15, 0.25, 0.03);
        level.playSound(null, nx, ny, nz, SoundEvents.TURTLE_EGG_CRACK, SoundSource.HOSTILE, 0.7f, 1.0f);
    }

    /** The hatch: one baby turtle per nest point, allied and registered. See the class comment. */
    private void hatch(AbilityContext ctx, ServerLevel level) {
        ctx.state().putInt("hatched", 1);
        Mob caster = ctx.mob();
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.TURTLE_EGG_HATCH, SoundSource.HOSTILE, 1.0f, 1.0f);
        ListTag nests = ctx.state().getListOrEmpty("nests");
        for (int i = 0; i < nests.size(); i++) {
            CompoundTag nest = nests.getCompoundOrEmpty(i);
            double nx = nest.getDoubleOr("x", caster.getX());
            double ny = nest.getDoubleOr("y", caster.getY());
            double nz = nest.getDoubleOr("z", caster.getZ());
            level.sendParticles(ParticleTypes.EGG_CRACK, nx, ny + 0.3, nz, 10, 0.3, 0.2, 0.3, 0.05);

            Turtle baby = EntityTypes.TURTLE.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (baby == null) {
                continue;
            }
            baby.setAge(-24000);
            baby.snapTo(nx, ny, nz, ctx.random().nextFloat() * 360.0f, 0.0f);
            baby.finalizeSpawn(level, level.getCurrentDifficultyAt(baby.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
            if (!level.addFreshEntity(baby)) {
                continue;
            }
            // Team assignment, both directions, plus the summoner registry.
            ctx.data().addAlly(baby.getUUID());
            MobWarData.get(baby).addAlly(caster.getUUID());
            SummonerTracker.registerSummoned(baby.getUUID(), caster.getUUID());
            TurtleAbilitySupport.trackEntity(ctx.state(), "babies", baby);
        }
    }

    /** One guard pass: drive the brood onto the target, land due bites. Every 5 ticks after the hatch. */
    private void guardPass(AbilityContext ctx, ServerLevel level, int ticks) {
        Mob caster = ctx.mob();
        LivingEntity target = ctx.target();
        ListTag babies = ctx.state().getListOrEmpty("babies");
        for (int i = 0; i < babies.size(); i++) {
            CompoundTag entry = babies.getCompoundOrEmpty(i);
            Entity found = TurtleAbilitySupport.resolveTracked(level, entry);
            if (!(found instanceof Turtle baby) || !baby.isAlive()) {
                continue;
            }
            if (target != null) {
                baby.getNavigation().moveTo(target, 1.2);
                if (baby.distanceToSqr(target) <= 1.7 * 1.7
                    && ticks - entry.getIntOr("bite", -BITE_INTERVAL_TICKS) >= BITE_INTERVAL_TICKS) {
                    entry.putInt("bite", ticks);
                    target.hurtServer(level, level.damageSources().mobAttack(baby), BITE_DAMAGE);
                }
            } else if (baby.distanceToSqr(caster) > 2.5 * 2.5) {
                baby.getNavigation().moveTo(caster, 1.0);
            }
        }
        // The tracked entries were mutated in place (bite
        // ticks); write the list back so the framework
        // persists them with the rest of the state tag.
        ctx.state().put("babies", babies);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.removeEffect(MobEffects.ABSORPTION);

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag babies = ctx.state().getListOrEmpty("babies");
        for (int i = 0; i < babies.size(); i++) {
            CompoundTag entry = babies.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id == null) {
                continue;
            }
            // The alliance ends with the ability, whichever side is still here.
            ctx.data().removeAlly(id);
            Entity found = level.getEntity(id);
            if (found instanceof Turtle baby) {
                MobWarData.get(baby).removeAlly(caster.getUUID());
                if (baby.isAlive()) {
                    level.sendParticles(ParticleTypes.EGG_CRACK,
                        baby.getX(), baby.getY() + 0.2, baby.getZ(),
                        6, 0.25, 0.15, 0.25, 0.05);
                    baby.discard();
                }
            }
        }
        // The placed egg blocks are deliberately NOT removed:
        // permanent terrain under the standing rule.
    }
}
