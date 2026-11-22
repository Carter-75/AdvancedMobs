package mod.advancedmobs.ability.spider;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.DustParticleOptions;
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
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.UUID;

/**
 * Brood Eggs (Spider ability 2 of 5, summon).
 *
 * WHAT IT DOES: the spider settles and lays a cluster of pale
 * egg sacs on the ground around itself: glistening block
 * displays that pulse slowly as something moves inside them.
 * After a short incubation the sacs split and baby spiders pour
 * out at half scale, on the mother's team, and charge the
 * nearest enemy.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   spider's current target within 16 blocks (distance squared
 *   256). No fight, no brood.
 * - The laying (start()): 4 egg displays (white wool block
 *   displays, scale 0.55, full brightness) at the four diagonal
 *   offsets 1.1 blocks from the spider. Each display is grounded:
 *   the ground is searched from 2 above to 2 below the spider's
 *   feet at that column and the display sits on the first solid
 *   top; a column with no ground in reach keeps the spider's own
 *   feet level. Each display's base position is stored for the
 *   pulse below. A soft WOOL_PLACE plays at volume 0.7.
 * - The incubation, ticks 1 to 99: every 20 ticks the sacs
 *   pulse: each display is re-snapped 0.04 blocks higher on
 *   alternating pulses (the visible swell of something moving
 *   inside; display transformations are NBT-set at creation in
 *   26.2 and cannot be re-scaled in code, so the pulse is a bob,
 *   documented here so it is not mistaken for a bug), and 1 pale
 *   DUST mote (color 0xE8E4D8, scale 1.0) glistens off each sac.
 * - The hatch, at tick 100: every display is discarded with a
 *   burst of 6 ITEM_COBWEB at its position and a WOOL_BREAK at
 *   volume 0.8, and a baby spider hatches at each sac: a vanilla
 *   spider with the SCALE attribute base set to 0.5 (half size,
 *   hitbox included) and MAX_HEALTH base set to 8.0 (half the
 *   adult 16), created with EntitySpawnReason.MOB_SUMMONED and
 *   finalizeSpawn like every other summon in this mod. If a sac
 *   display is somehow gone, its baby hatches at the stored sac
 *   position anyway.
 * - Team assignment, per baby, both directions plus the
 *   registry: the mother's data gains the baby as an ally, the
 *   baby's data gains the mother, and the pair is registered in
 *   SummonerTracker (which also tags the baby umw_summoned for
 *   the natural spawn blocker, the same flow Fungal Spawn and
 *   Gunpowder Sprites use).
 * - The charge: at hatch each baby is ordered onto the nearest
 *   enemy of the mother within 16 blocks (one bounded query,
 *   SpiderAbilitySupport rules); when no enemy is in reach, the
 *   mother's live target is used. After the hatch, a babysitting
 *   pass every 5 ticks prunes dead babies (removing the mother's
 *   ally record for each) and re-orders any baby whose target is
 *   empty or is the mother onto the mother's live target.
 * - Duration: hard finish at 400 ticks (20 seconds), or earlier
 *   once the brood has hatched and every baby is dead: the
 *   ability has nothing left to tend.
 * - end(): unhatched egg displays are discarded (no hatch: the
 *   laying was interrupted). Hatched babies still alive are
 *   recalled: alliance records removed on both sides and each is
 *   discarded in 6 POOF particles. The brood is the ability's,
 *   and it does not outlive it, matching Fungal Spawn and
 *   Gunpowder Sprites; if the mother dies first, the framework
 *   termination path runs this same end(). SummonerTracker
 *   records are deliberately not unregistered: that registry
 *   cleans up on the baby's own removal.
 *
 * WHAT IT LOOKS LIKE: four pale sacs settle into the dirt
 * around the spider, swelling in a slow 20-tick bob with a
 * glisten of pale dust, then all four split in bursts of silk
 * flecks and half-size spiders boil out and pour at the nearest
 * enemy.
 *
 * PERFORMANCE: the laying is 4 grounded display spawns, once.
 * The incubation pulse runs every 20 ticks (at most 4 snapTo
 * calls and 4 particles). The babysitting pass runs every 5
 * ticks after the hatch with at most 4 UUID resolves. The single
 * bounded enemy query runs once, at the hatch. Particle budget
 * per activation: incubation 5 pulses times 4 sacs = 20 DUST,
 * hatch 4 times 6 = 24 ITEM_COBWEB, end() at most 4 times 6 =
 * 24 POOF, total at most 68 particles.
 *
 * State tag keys: "eggs" (list of compounds, one per sac: "id"
 * the display's UUID, "x", "y", "z" its base position), "brood"
 * (list of compounds, one per live baby: "id" its UUID),
 * "hatched" (1 once the hatch has run).
 */
public final class BroodEggsAbility implements MobAbility {

    static final int HATCH_TICK = 100;
    static final int DURATION_TICKS = 400;
    private static final int EGG_COUNT = 4;
    private static final float EGG_SCALE = 0.55f;
    private static final int PULSE_INTERVAL = 20;
    private static final int BABYSIT_INTERVAL = 5;
    private static final DustParticleOptions PALE_DUST = new DustParticleOptions(0xE8E4D8, 1.0f);
    private static final double[][] OFFSETS = {
        {-1.1, -1.1}, {1.1, -1.1}, {-1.1, 1.1}, {1.1, 1.1}
    };

    @Override
    public String id() {
        return "brood_eggs";
    }

    @Override
    public String displayName() {
        return "Brood Eggs";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        ListTag eggs = new ListTag();
        ctx.state().put("eggs", eggs);
        ctx.state().put("brood", new ListTag());
        if (level == null) {
            return;
        }
        Mob mother = ctx.mob();
        for (double[] offset : OFFSETS) {
            double x = mother.getX() + offset[0];
            double z = mother.getZ() + offset[1];
            double y = groundLevel(level, x, mother.getY(), z);
            Entity egg = SpiderAbilitySupport.createBrightBlockDisplay(level,
                Blocks.WOOL.white().defaultBlockState(), EGG_SCALE);
            if (egg == null) {
                continue;
            }
            egg.snapTo(x, y + EGG_SCALE / 2.0, z, ctx.random().nextFloat() * 360.0f, 0.0f);
            level.addFreshEntity(egg);
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, egg.getUUID());
            entry.putDouble("x", x);
            entry.putDouble("y", y + EGG_SCALE / 2.0);
            entry.putDouble("z", z);
            eggs.add(entry);
        }
        ctx.state().put("eggs", eggs);
        level.playSound(null, mother.getX(), mother.getY(), mother.getZ(),
            SoundEvents.WOOL_PLACE, SoundSource.HOSTILE, 0.7f, 0.9f);
    }

    /**
     * The ground height for a sac column: the top of the first
     * solid block found scanning from 2 above to 2 below the
     * reference height, or the reference height itself when the
     * column has no ground in reach.
     */
    private static double groundLevel(ServerLevel level, double x, double refY, double z) {
        BlockPos base = BlockPos.containing(x, refY, z);
        for (int dy = 2; dy >= -2; dy--) {
            BlockPos pos = base.offset(0, dy, 0);
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                return pos.getY() + 1.0;
            }
        }
        return refY;
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        CompoundTag state = ctx.state();

        if (state.getIntOr("hatched", 0) == 0) {
            if (ticks >= HATCH_TICK) {
                hatch(ctx, level);
                return;
            }
            if (ticks % PULSE_INTERVAL == 0) {
                pulseEggs(ctx, level, ticks);
            }
            return;
        }

        // After the hatch: tend the brood every 5 ticks.
        if (ticks % BABYSIT_INTERVAL == 0) {
            babysit(ctx, level);
        }
    }

    /** One incubation pulse: the sacs bob and glisten (see the class comment). */
    private static void pulseEggs(AbilityContext ctx, ServerLevel level, int ticks) {
        double bob = (ticks / PULSE_INTERVAL) % 2 == 0 ? 0.0 : 0.04;
        ListTag eggs = ctx.state().getListOrEmpty("eggs");
        for (int i = 0; i < eggs.size(); i++) {
            CompoundTag entry = eggs.getCompoundOrEmpty(i);
            Entity egg = resolve(level, entry);
            if (egg == null) {
                continue;
            }
            double x = entry.getDoubleOr("x", egg.getX());
            double y = entry.getDoubleOr("y", egg.getY());
            double z = entry.getDoubleOr("z", egg.getZ());
            egg.snapTo(x, y + bob, z, egg.getYRot(), 0.0f);
            level.sendParticles(PALE_DUST, x, y + 0.3, z, 1, 0.2, 0.2, 0.2, 0.01);
        }
    }

    /** The hatch: sacs split, babies pour out and charge the nearest enemy. */
    private static void hatch(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        state.putInt("hatched", 1);
        Mob mother = ctx.mob();

        // The charge target: the nearest enemy of the mother in
        // reach, or her live target when the box query finds none.
        LivingEntity chargeTarget = null;
        double bestSqr = Double.MAX_VALUE;
        for (LivingEntity enemy : SpiderAbilitySupport.enemiesAroundSelf(ctx, 16.0, 4.0, 4.0)) {
            double distanceSqr = mother.distanceToSqr(enemy);
            if (distanceSqr < bestSqr) {
                bestSqr = distanceSqr;
                chargeTarget = enemy;
            }
        }
        if (chargeTarget == null) {
            chargeTarget = ctx.target();
        }

        ListTag brood = new ListTag();
        ListTag eggs = state.getListOrEmpty("eggs");
        for (int i = 0; i < eggs.size(); i++) {
            CompoundTag entry = eggs.getCompoundOrEmpty(i);
            double x = entry.getDoubleOr("x", mother.getX());
            double y = entry.getDoubleOr("y", mother.getY());
            double z = entry.getDoubleOr("z", mother.getZ());
            Entity egg = resolve(level, entry);
            if (egg != null) {
                egg.discard();
            }
            level.sendParticles(ParticleTypes.ITEM_COBWEB, x, y, z, 6, 0.3, 0.25, 0.3, 0.08);

            Spider baby = EntityTypes.SPIDER.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (baby == null) {
                continue;
            }
            // Half scale, half health: a baby, not a small adult.
            if (baby.getAttribute(Attributes.SCALE) != null) {
                baby.getAttribute(Attributes.SCALE).setBaseValue(0.5);
            }
            if (baby.getAttribute(Attributes.MAX_HEALTH) != null) {
                baby.getAttribute(Attributes.MAX_HEALTH).setBaseValue(8.0);
            }
            baby.setHealth(8.0f);
            baby.snapTo(x, y - EGG_SCALE / 2.0 + 0.1, z, ctx.random().nextFloat() * 360.0f, 0.0f);
            baby.finalizeSpawn(level, level.getCurrentDifficultyAt(baby.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
            if (!level.addFreshEntity(baby)) {
                continue;
            }
            // Team assignment, both directions, plus the registry.
            ctx.data().addAlly(baby.getUUID());
            MobWarData.get(baby).addAlly(mother.getUUID());
            SummonerTracker.registerSummoned(baby.getUUID(), mother.getUUID());
            if (chargeTarget != null) {
                baby.setTarget(chargeTarget);
            }
            CompoundTag babyEntry = new CompoundTag();
            babyEntry.store("id", UUIDUtil.CODEC, baby.getUUID());
            brood.add(babyEntry);
        }
        state.put("brood", brood);
        level.playSound(null, mother.getX(), mother.getY(), mother.getZ(),
            SoundEvents.WOOL_BREAK, SoundSource.HOSTILE, 0.8f, 1.2f);
    }

    /** Prunes dead babies and keeps the living ones on the mother's fight. */
    private static void babysit(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        ListTag brood = state.getListOrEmpty("brood");
        ListTag remaining = new ListTag();
        LivingEntity desired = ctx.target();
        for (int i = 0; i < brood.size(); i++) {
            CompoundTag entry = brood.getCompoundOrEmpty(i);
            Entity entity = resolve(level, entry);
            if (!(entity instanceof Spider baby) || !baby.isAlive()) {
                if (entry.contains("id")) {
                    entry.read("id", UUIDUtil.CODEC).ifPresent(ctx.data()::removeAlly);
                }
                continue;
            }
            LivingEntity babyTarget = baby.getTarget();
            if ((babyTarget == null || babyTarget == ctx.mob()) && desired != null && desired != baby) {
                baby.setTarget(desired);
            }
            remaining.add(entry);
        }
        state.put("brood", remaining);
    }

    /** Resolves a tracked entity (sac display or baby) from its list entry, or null when gone. */
    private static Entity resolve(ServerLevel level, CompoundTag entry) {
        if (!entry.contains("id")) {
            return null;
        }
        UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
        return id != null ? level.getEntity(id) : null;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        if (ctx.ticksActive() >= DURATION_TICKS) {
            return true;
        }
        return ctx.state().getIntOr("hatched", 0) == 1
            && ctx.state().getListOrEmpty("brood").isEmpty();
    }

    @Override
    public void end(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mother = ctx.mob();
        CompoundTag state = ctx.state();

        // Unhatched sacs are discarded where they sit.
        ListTag eggs = state.getListOrEmpty("eggs");
        for (int i = 0; i < eggs.size(); i++) {
            Entity egg = resolve(level, eggs.getCompoundOrEmpty(i));
            if (egg != null) {
                egg.discard();
            }
        }

        // Surviving babies are recalled (see the class comment).
        ListTag brood = state.getListOrEmpty("brood");
        for (int i = 0; i < brood.size(); i++) {
            CompoundTag entry = brood.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID babyId = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (babyId == null) {
                continue;
            }
            ctx.data().removeAlly(babyId);
            Entity entity = resolve(level, entry);
            if (entity instanceof Spider baby && baby.isAlive()) {
                MobWarData.get(baby).removeAlly(mother.getUUID());
                level.sendParticles(ParticleTypes.POOF, baby.getX(), baby.getY() + 0.3, baby.getZ(),
                    6, 0.25, 0.2, 0.25, 0.04);
                baby.discard();
            }
        }
    }
}
