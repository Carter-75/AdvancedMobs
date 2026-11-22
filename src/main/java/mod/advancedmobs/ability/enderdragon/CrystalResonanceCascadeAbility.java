package mod.advancedmobs.ability.enderdragon;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/**
 * Crystal Resonance Cascade (Ender Dragon ability 1 of 5,
 * attack, aura).
 *
 * WHAT IT DOES: the dragon roars and visible beams of END_ROD
 * light lance out from her to every surviving end crystal.
 * The crystals pulse in time with her heartbeat, and each
 * pulse rains a dragon breath strike down on an enemy below
 * that crystal, turning her healing network into an artillery
 * grid. Destroying a crystal mid-cascade breaks its beam in
 * an explosion and staggers the cascade: the volleys pause
 * while she recovers, so the crystals become targets under
 * fire.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the dragon's current target within 128 blocks (distance
 *   squared 16384). The arena is about 200 blocks across, so
 *   this is effectively "in the fight". canTrigger stays
 *   cheap (a distance check only): whether any crystal
 *   survives is decided once in start(), and a cascade that
 *   finds no crystals ends on its first tick.
 * - The crystal scan (start(), one shot): one bounded entity
 *   query for EndCrystal in a box reaching 120 blocks
 *   horizontally and 80 vertically around the dragon, which
 *   covers every vanilla pillar from anywhere in the arena.
 *   At most 10 crystals are tracked (the vanilla fight has
 *   exactly 10). Each tracked crystal's UUID and position
 *   are stored in the state tag. The cascade does NOT touch
 *   the crystals' real beam targets or their healing: the
 *   vanilla fight keeps healing her exactly as it always
 *   does, and the END_ROD beams below are a visual overlay
 *   drawn by this ability, not the crystals' own beams.
 * - Duration: 200 ticks (10 seconds), or until every tracked
 *   crystal is destroyed, whichever comes first.
 * - The beams: every 5 ticks, each surviving crystal gets a
 *   beam of 3 END_ROD particles sampled along the line from
 *   the dragon's head (her position at 60 percent of her
 *   bounding box height) to the crystal's center, at 30, 55,
 *   and 80 percent of the way across. Survival is resolved
 *   on the same 5-tick pass (a UUID lookup per crystal), so
 *   a destroyed crystal's beam stops within 5 ticks.
 * - The volleys (the heartbeat): every 20 ticks, when the
 *   cascade is not staggered, each surviving crystal picks
 *   its victim: the enemy (EnderDragonAbilitySupport rules)
 *   with the smallest horizontal distance to the crystal's
 *   column, among enemies in a box reaching 14 blocks
 *   horizontally around the column and from 45 blocks below
 *   the crystal to 2 above it (one bounded query per
 *   crystal). At most 1 victim per crystal per pulse. The
 *   strike is a breath cloud (radius 2.5, duration 60 ticks,
 *   instant damage amplifier 0) spawned at the victim's feet
 *   by EnderDragonAbilitySupport.spawnBreathCloud, with a
 *   4-particle DRAGON_BREATH flash at the crystal and a
 *   6-particle DRAGON_BREATH burst at the victim. The cloud
 *   is an entity, not a block change, so the terrain rule
 *   does not gate it.
 * - A crystal destroyed: on the 5-tick resolve pass, a
 *   tracked crystal that no longer resolves fires its break
 *   exactly once: 1 EXPLOSION_EMITTER and 2 EXPLOSION
 *   particles at its stored position and a GENERIC_EXPLODE
 *   sound (volume 1.5), and the cascade staggers: the state
 *   tag's stagger deadline is set 40 ticks out, and volley
 *   pulses before that deadline are skipped (the beams keep
 *   drawing; only the artillery pauses). The crystal is
 *   dropped from the tracked list, and if the list empties
 *   the ability finishes.
 *
 * WHAT IT LOOKS AND SOUNDS LIKE: a roar
 * (ENDER_DRAGON_GROWL, volume 2.5, pitch 0.9) and a
 * 12-particle END_ROD flare at the dragon as the beams latch
 * on, then a web of pale beams across the arena snapping
 * between dragon and crystals, a purple flash over a crystal
 * and a breath burst over its victim once a second, and a
 * hard explosion flash wherever a crystal dies.
 *
 * PERFORMANCE: the per-tick cost when nothing is due is one
 * modulo check. The 5-tick pass resolves at most 10 UUIDs
 * and sends the beam particles; the 20-tick pulse runs at
 * most 10 bounded column queries. The start() scan is one
 * bounded query. No per-tick allocations beyond the survivor
 * list built on resolve ticks, and no registry or config
 * lookups in the tick path. Particle budget per activation:
 * start flare 12, beams 40 passes times 10 crystals times 3
 * = at most 1200, volleys 10 pulses times 10 crystals times
 * 10 = at most 1000, crystal breaks at most 10 times 3 =
 * 30, total at most about 2242 particles, and the real count
 * is far lower once crystals start dying or columns stand
 * empty.
 *
 * State tag keys: "crystals" (list of compounds, one per
 * tracked crystal: "id" the crystal's UUID, "x", "y", "z"
 * its block position), "scanned" (true once start() has run
 * its scan, so isFinished can tell an empty list that means
 * "no crystals" from a tag that was never initialized),
 * "stagger_until" (the tick count before which volleys are
 * skipped).
 */
public final class CrystalResonanceCascadeAbility implements MobAbility {

    static final int DURATION_TICKS = 200;
    private static final int BEAM_INTERVAL = 5;
    private static final int PULSE_INTERVAL = 20;
    private static final int STAGGER_TICKS = 40;
    private static final int MAX_CRYSTALS = 10;
    private static final float VOLLEY_CLOUD_RADIUS = 2.5f;
    private static final int VOLLEY_CLOUD_DURATION = 60;

    @Override
    public String id() {
        return "crystal_resonance_cascade";
    }

    @Override
    public String displayName() {
        return "Crystal Resonance Cascade";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 16384.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        ctx.state().putBoolean("scanned", true);
        ctx.state().put("crystals", new ListTag());
        ctx.state().putInt("stagger_until", 0);
        if (level == null) {
            return;
        }
        Mob dragon = ctx.mob();

        // The one-shot crystal scan (see the class comment).
        AABB arena = new AABB(dragon.getX() - 120.0, dragon.getY() - 80.0, dragon.getZ() - 120.0,
                              dragon.getX() + 120.0, dragon.getY() + 80.0, dragon.getZ() + 120.0);
        List<EndCrystal> found = level.getEntitiesOfClass(EndCrystal.class, arena, Entity::isAlive);
        ListTag crystals = new ListTag();
        for (EndCrystal crystal : found) {
            if (crystals.size() >= MAX_CRYSTALS) {
                break;
            }
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, crystal.getUUID());
            entry.putInt("x", crystal.getBlockX());
            entry.putInt("y", crystal.getBlockY());
            entry.putInt("z", crystal.getBlockZ());
            crystals.add(entry);
        }
        ctx.state().put("crystals", crystals);

        // The roar and the flare as the beams latch on.
        level.playSound(null, dragon.getX(), dragon.getY(), dragon.getZ(),
            SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 2.5f, 0.9f);
        level.sendParticles(ParticleTypes.END_ROD, dragon.getX(), dragon.getY() + 4.0, dragon.getZ(),
            12, 2.5, 1.5, 2.5, 0.1);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        if (ticks % BEAM_INTERVAL == 0) {
            resolveAndDrawBeams(ctx, level, ticks);
        }
        if (ticks % PULSE_INTERVAL == 0 && ticks >= ctx.state().getIntOr("stagger_until", 0)) {
            fireVolleys(ctx, level);
        }
    }

    /**
     * The 5-tick pass: resolves every tracked crystal, fires
     * the break burst and the stagger for any that are gone,
     * rewrites the tracked list with the survivors, and draws
     * the survivors' beams.
     */
    private void resolveAndDrawBeams(AbilityContext ctx, ServerLevel level, int ticks) {
        Mob dragon = ctx.mob();
        double headX = dragon.getX();
        double headY = dragon.getY() + dragon.getBbHeight() * 0.6;
        double headZ = dragon.getZ();
        ListTag tracked = ctx.state().getListOrEmpty("crystals");
        ListTag survivors = new ListTag();
        for (int i = 0; i < tracked.size(); i++) {
            CompoundTag entry = tracked.getCompoundOrEmpty(i);
            UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
            EndCrystal crystal = null;
            if (id != null && level.getEntity(id) instanceof EndCrystal found && found.isAlive()) {
                crystal = found;
            }
            if (crystal == null) {
                // The break: one explosion burst at the stored
                // position, and the cascade staggers.
                double bx = entry.getIntOr("x", 0) + 0.5;
                double by = entry.getIntOr("y", 0) + 1.0;
                double bz = entry.getIntOr("z", 0) + 0.5;
                level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, bx, by, bz, 1, 0.0, 0.0, 0.0, 0.0);
                level.sendParticles(ParticleTypes.EXPLOSION, bx, by, bz, 2, 0.3, 0.3, 0.3, 0.0);
                level.playSound(null, bx, by, bz,
                    SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1.5f, 1.0f);
                ctx.state().putInt("stagger_until", ticks + STAGGER_TICKS);
                continue;
            }
            survivors.add(entry);
            // The beam: 3 END_ROD motes along the line from the
            // dragon's head to the crystal's center.
            double cx = crystal.getX();
            double cy = crystal.getY() + 1.0;
            double cz = crystal.getZ();
            for (double t : new double[]{0.3, 0.55, 0.8}) {
                level.sendParticles(ParticleTypes.END_ROD,
                    headX + (cx - headX) * t, headY + (cy - headY) * t, headZ + (cz - headZ) * t,
                    1, 0.05, 0.05, 0.05, 0.0);
            }
        }
        ctx.state().put("crystals", survivors);
    }

    /**
     * One heartbeat pulse: every surviving crystal strikes the
     * enemy standing closest to its column (see the class
     * comment). Runs only on pulse ticks and never while the
     * cascade is staggered.
     */
    private void fireVolleys(AbilityContext ctx, ServerLevel level) {
        Mob dragon = ctx.mob();
        ListTag tracked = ctx.state().getListOrEmpty("crystals");
        for (int i = 0; i < tracked.size(); i++) {
            CompoundTag entry = tracked.getCompoundOrEmpty(i);
            double columnX = entry.getIntOr("x", 0) + 0.5;
            double columnY = entry.getIntOr("y", 0);
            double columnZ = entry.getIntOr("z", 0) + 0.5;
            List<LivingEntity> below = EnderDragonAbilitySupport.enemiesInBox(
                ctx, columnX, columnY - 20.0, columnZ, 14.0, 25.0, 22.0);
            LivingEntity victim = null;
            double bestSqr = Double.MAX_VALUE;
            for (LivingEntity candidate : below) {
                double dx = candidate.getX() - columnX;
                double dz = candidate.getZ() - columnZ;
                double distSqr = dx * dx + dz * dz;
                if (distSqr < bestSqr) {
                    bestSqr = distSqr;
                    victim = candidate;
                }
            }
            if (victim == null) {
                continue;
            }
            // The orb leaves the crystal, and the breath blooms
            // at the victim's feet.
            level.sendParticles(EnderDragonAbilitySupport.BREATH_PARTICLE,
                columnX, columnY + 1.0, columnZ, 4, 0.4, 0.4, 0.4, 0.05);
            EnderDragonAbilitySupport.spawnBreathCloud(level, dragon,
                victim.getX(), victim.getY(), victim.getZ(),
                VOLLEY_CLOUD_RADIUS, VOLLEY_CLOUD_DURATION);
            level.sendParticles(EnderDragonAbilitySupport.BREATH_PARTICLE,
                victim.getX(), victim.getY() + 0.3, victim.getZ(), 6, 0.8, 0.3, 0.8, 0.05);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        if (ctx.ticksActive() >= DURATION_TICKS) {
            return true;
        }
        // An empty tracked list after the scan means every
        // crystal is gone (or none ever stood): the grid is
        // dark and the cascade is over.
        return ctx.state().getBooleanOr("scanned", false)
            && ctx.state().getListOrEmpty("crystals").isEmpty();
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to clean up: the beams are particles, the
        // breath clouds are self-expiring entities, and the
        // crystals themselves were never modified.
    }
}
