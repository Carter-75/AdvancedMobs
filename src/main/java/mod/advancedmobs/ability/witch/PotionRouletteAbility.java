package mod.advancedmobs.ability.witch;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Potion Roulette (Witch ability 2 of 5, summon, construct).
 *
 * WHAT IT DOES: three potion bottles take up orbit around the
 * witch as item displays, spinning slowly, each glowing with
 * its contents (the bottle's tint is its real potion contents:
 * green poison, red harming, grey-blue slowness, dark
 * weakness). Every 3 seconds the wheel stops, one bottle
 * launches itself at the nearest enemy as a real splash potion
 * carrying its rolled effect, and a fresh bottle with a fresh
 * roll fades in to replace it. Enemies can shoot the orbiting
 * bottles down, and the witch hates that: a broken bottle
 * leaves a gap in the wheel for 3 seconds while she scampers
 * (SPEED I) to rebuild it.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the witch's current target within 16 blocks (distance
 *   squared 256).
 * - Duration: exactly 600 ticks (30 seconds) from start().
 * - The wheel (start()): 3 bottles, slots 0 to 2. Each slot's
 *   effect is rolled fresh at creation from the debuff table
 *   (WitchAbilitySupport.DEBUFFS: poison, harming, slowness,
 *   weakness) and stored as an int in the state tag. A bottle
 *   is an item display (splash potion stack with that slot's
 *   potion contents, scale 0.9, full brightness, teleport
 *   duration 2 so the orbit glides).
 * - The orbit (every tick): slot i sits at angle
 *   ticks * 0.05 + i * 120 degrees around the witch, radius
 *   1.3, height 1.7 above her feet plus a gentle bob
 *   (0.15 * sin(ticks * 0.1 + i * 2.1)). The display is
 *   snapped to that point each tick; the teleport duration
 *   smooths it into a slow spin.
 * - The launch (every 60 ticks, first at tick 60): one
 *   present slot is picked at random. A real ThrownSplashPotion
 *   with that slot's effect is thrown from the bottle's
 *   position at the nearest enemy within 16 blocks (one
 *   bounded query; the framework target when none is nearer),
 *   aimed at its chest, speed 1.6, inaccuracy 1.5, with a
 *   WITCH_THROW sound. The slot then immediately re-rolls its
 *   effect and its display is rebuilt with the new contents:
 *   the fresh bottle that fades in.
 * - Shooting the bottles down (polled every 5 ticks): display
 *   entities have no hitbox and no health in vanilla (see
 *   CAPABILITIES.md), so hits are detected by proximity, the
 *   documented stand-in: a bottle breaks when an enemy-owned
 *   or ownerless projectile passes within 0.9 blocks of it, or
 *   when an enemy body comes within 1.1 blocks of it (the
 *   swat). The projectile is discarded with the bottle. A
 *   break plays GLASS_BREAK, bursts 8 ITEM particles of the
 *   bottle's own stack, empties the slot, sets its rebuild
 *   tick 60 ticks out, and gives the witch SPEED I for 60
 *   ticks (the retreat to rebuild). The caster's and allies'
 *   projectiles never break bottles.
 * - Rebuild: a broken slot whose rebuild tick has arrived
 *   gets a fresh display with a freshly rolled effect.
 * - Orbit sparkles: every 20 ticks, each present bottle sheds
 *   1 EFFECT sparkle in its own effect's color.
 * - end(): every present display is discarded. Broken slots
 *   have nothing to discard. If the witch dies, the framework
 *   termination path runs this same end(), so the wheel never
 *   orbits a corpse.
 *
 * WHAT IT LOOKS LIKE: three glowing bottles circling the
 * witch's hat, a bottle peeling off every few seconds and
 * arcing at the nearest foe in a spray of its own color, glass
 * and potion shards when one is shot out of the air.
 *
 * PERFORMANCE: per tick the orbit is 3 snapTo calls and
 * angle math. The launch runs one bounded entity query every
 * 60 ticks; the defense poll runs one small projectile query
 * and one small enemy query per present bottle every 5 ticks
 * (at most 3 of each). No whole-level scans. Particle budget
 * per activation: sparkles at most 30 pulses times 3 = 90,
 * launches 10 times 4 = 40, breaks bounded by the 60 tick
 * rebuild cycle (at most 30 breaks times 8 = 240 in the
 * absurd worst case), total at most 370 particles.
 *
 * TERRAIN: this ability places and breaks no blocks, so the
 * mobGriefing gate does not apply. The bottles are display
 * entities, not blocks.
 *
 * State tag keys: "bottles" (list of 3 compounds, one per
 * slot: "fx" the debuff table index, "id" the display UUID,
 * absent while broken, "rebuild" the tick a broken slot is
 * rebuilt, 0 while present).
 */
public final class PotionRouletteAbility implements MobAbility {

    static final int DURATION_TICKS = 600;
    private static final int SLOT_COUNT = 3;
    private static final int LAUNCH_INTERVAL = 60;
    private static final int DEFENSE_INTERVAL = 5;
    private static final int REBUILD_DELAY = 60;
    private static final double ORBIT_RADIUS = 1.3;
    private static final double ORBIT_HEIGHT = 1.7;
    private static final double ORBIT_SPEED = 0.05;
    private static final float BOTTLE_SCALE = 0.9f;

    @Override
    public String id() {
        return "potion_roulette";
    }

    @Override
    public String displayName() {
        return "Potion Roulette";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag bottles = new ListTag();
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("fx", WitchAbilitySupport.rollDebuff(ctx));
            entry.putInt("rebuild", 0);
            bottles.add(entry);
            spawnBottle(ctx, level, entry, slot, 0);
        }
        ctx.state().put("bottles", bottles);
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag bottles = ctx.state().getListOrEmpty("bottles");

        // Rebuilds and the orbit itself, every tick.
        for (int slot = 0; slot < bottles.size(); slot++) {
            CompoundTag entry = bottles.getCompoundOrEmpty(slot);
            if (!entry.contains("id")) {
                int rebuild = entry.getIntOr("rebuild", 0);
                if (rebuild > 0 && ticks >= rebuild) {
                    entry.putInt("fx", WitchAbilitySupport.rollDebuff(ctx));
                    entry.putInt("rebuild", 0);
                    spawnBottle(ctx, level, entry, slot, ticks);
                }
                continue;
            }
            Entity display = resolveDisplay(level, entry);
            if (display == null) {
                // The display is gone (chunk edge, command):
                // treat the slot as broken and rebuild on the
                // normal delay.
                entry.remove("id");
                entry.putInt("rebuild", ticks + REBUILD_DELAY);
                continue;
            }
            Vec3 pos = orbitPosition(ctx, slot, ticks);
            display.snapTo(pos.x, pos.y, pos.z, 0.0f, 0.0f);
        }
        ctx.state().put("bottles", bottles);

        if (ticks % DEFENSE_INTERVAL == 0) {
            pollBottleDefense(ctx, level, bottles, ticks);
            ctx.state().put("bottles", bottles);
        }
        if (ticks % 20 == 0) {
            orbitSparkles(ctx, level, bottles, ticks);
        }
        if (ticks % LAUNCH_INTERVAL == 0) {
            launchBottle(ctx, level, bottles, ticks);
            ctx.state().put("bottles", bottles);
        }
    }

    /** The world position of the given slot's bottle at the given tick. */
    private Vec3 orbitPosition(AbilityContext ctx, int slot, int ticks) {
        Mob witch = ctx.mob();
        double angle = ticks * ORBIT_SPEED + slot * (Math.PI * 2.0 / SLOT_COUNT);
        double bob = 0.15 * Math.sin(ticks * 0.1 + slot * 2.1);
        return new Vec3(
            witch.getX() + Math.cos(angle) * ORBIT_RADIUS,
            witch.getY() + ORBIT_HEIGHT + bob,
            witch.getZ() + Math.sin(angle) * ORBIT_RADIUS);
    }

    /** Creates a slot's display from its stored effect roll and records its UUID in the entry. */
    private void spawnBottle(AbilityContext ctx, ServerLevel level, CompoundTag entry, int slot, int ticks) {
        int fx = entry.getIntOr("fx", 0);
        Entity display = WitchAbilitySupport.createItemDisplay(level,
            WitchAbilitySupport.splashStack(fx), BOTTLE_SCALE);
        if (display == null) {
            return;
        }
        Vec3 pos = orbitPosition(ctx, slot, ticks);
        display.snapTo(pos.x, pos.y, pos.z, 0.0f, 0.0f);
        if (level.addFreshEntity(display)) {
            entry.store("id", UUIDUtil.CODEC, display.getUUID());
        }
    }

    /** Resolves a slot's display from its entry, or null when broken or gone. */
    private Entity resolveDisplay(ServerLevel level, CompoundTag entry) {
        UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
        return id != null ? level.getEntity(id) : null;
    }

    /**
     * The launch: one random present bottle throws itself at
     * the nearest enemy as a real splash potion, then the slot
     * re-rolls and its display is rebuilt with the new effect.
     */
    private void launchBottle(AbilityContext ctx, ServerLevel level, ListTag bottles, int ticks) {
        List<Integer> present = new ArrayList<>();
        for (int slot = 0; slot < bottles.size(); slot++) {
            if (bottles.getCompoundOrEmpty(slot).contains("id")) {
                present.add(slot);
            }
        }
        if (present.isEmpty()) {
            return;
        }
        // Nearest enemy within 16 blocks, else the framework
        // target: one bounded query.
        LivingEntity victim = null;
        double best = Double.MAX_VALUE;
        for (LivingEntity enemy : WitchAbilitySupport.enemiesAroundSelf(ctx, 16.0, 4.0, 4.0)) {
            double distance = ctx.mob().distanceToSqr(enemy);
            if (distance < best) {
                best = distance;
                victim = enemy;
            }
        }
        if (victim == null) {
            victim = ctx.target();
        }
        if (victim == null) {
            return;
        }
        int slot = present.get(ctx.random().nextInt(present.size()));
        CompoundTag entry = bottles.getCompoundOrEmpty(slot);
        Vec3 from = orbitPosition(ctx, slot, ticks);
        Vec3 aim = new Vec3(victim.getX(), victim.getY() + victim.getBbHeight() * 0.6, victim.getZ());
        WitchAbilitySupport.throwSplash(ctx, level, from, aim,
            WitchAbilitySupport.splashStack(entry.getIntOr("fx", 0)), 1.6f, 1.5f);
        level.playSound(null, from.x, from.y, from.z,
            SoundEvents.WITCH_THROW, SoundSource.HOSTILE, 0.8f, 1.0f);
        level.sendParticles(WitchAbilitySupport.DEBUFF_SPARKLES[entry.getIntOr("fx", 0)],
            from.x, from.y, from.z, 4, 0.2, 0.2, 0.2, 0.03);

        // The fresh bottle fades in: re-roll and rebuild the
        // display with the new contents.
        Entity old = resolveDisplay(level, entry);
        if (old != null) {
            old.discard();
        }
        entry.remove("id");
        entry.putInt("fx", WitchAbilitySupport.rollDebuff(ctx));
        spawnBottle(ctx, level, entry, slot, ticks);
    }

    /**
     * The defense poll: breaks any bottle an enemy projectile
     * or an enemy body has reached (see the class comment for
     * why proximity stands in for display hitboxes).
     */
    private void pollBottleDefense(AbilityContext ctx, ServerLevel level, ListTag bottles, int ticks) {
        for (int slot = 0; slot < bottles.size(); slot++) {
            CompoundTag entry = bottles.getCompoundOrEmpty(slot);
            if (!entry.contains("id")) {
                continue;
            }
            Vec3 pos = orbitPosition(ctx, slot, ticks);
            Projectile hit = null;
            AABB shotBox = new AABB(pos.x - 0.9, pos.y - 0.9, pos.z - 0.9,
                                    pos.x + 0.9, pos.y + 0.9, pos.z + 0.9);
            for (Projectile projectile : level.getEntitiesOfClass(Projectile.class, shotBox, p -> p.isAlive())) {
                Entity owner = projectile.getOwner();
                if (owner == null || (owner instanceof LivingEntity living
                    && WitchAbilitySupport.isEnemy(ctx, living))) {
                    hit = projectile;
                    break;
                }
            }
            boolean swatted = hit == null
                && !WitchAbilitySupport.enemiesInBox(ctx, pos.x, pos.y, pos.z, 1.1, 1.1, 1.1).isEmpty();
            if (hit == null && !swatted) {
                continue;
            }
            if (hit != null) {
                hit.discard();
            }
            breakBottle(ctx, level, entry, pos, ticks);
        }
    }

    /** Breaks one bottle: shards, sound, an empty slot, and the witch's retreat speed. */
    private void breakBottle(AbilityContext ctx, ServerLevel level, CompoundTag entry, Vec3 pos, int ticks) {
        Entity display = resolveDisplay(level, entry);
        if (display != null) {
            display.discard();
        }
        ItemStack stack = WitchAbilitySupport.splashStack(entry.getIntOr("fx", 0));
        level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, ItemStackTemplate.fromStack(stack)),
            pos.x, pos.y, pos.z, 8, 0.25, 0.25, 0.25, 0.08);
        level.playSound(null, pos.x, pos.y, pos.z,
            SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 0.9f, 1.1f);
        entry.remove("id");
        entry.putInt("rebuild", ticks + REBUILD_DELAY);
        // The witch hates that: she scampers while the wheel is
        // short a bottle.
        ctx.mob().addEffect(new MobEffectInstance(MobEffects.SPEED, REBUILD_DELAY, 0));
    }

    /** Each present bottle sheds one sparkle in its own effect's color. */
    private void orbitSparkles(AbilityContext ctx, ServerLevel level, ListTag bottles, int ticks) {
        for (int slot = 0; slot < bottles.size(); slot++) {
            CompoundTag entry = bottles.getCompoundOrEmpty(slot);
            if (!entry.contains("id")) {
                continue;
            }
            Vec3 pos = orbitPosition(ctx, slot, ticks);
            level.sendParticles(WitchAbilitySupport.DEBUFF_SPARKLES[entry.getIntOr("fx", 0)],
                pos.x, pos.y, pos.z, 1, 0.1, 0.1, 0.1, 0.01);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag bottles = ctx.state().getListOrEmpty("bottles");
        for (int i = 0; i < bottles.size(); i++) {
            Entity display = resolveDisplay(level, bottles.getCompoundOrEmpty(i));
            if (display != null) {
                display.discard();
            }
        }
    }
}
