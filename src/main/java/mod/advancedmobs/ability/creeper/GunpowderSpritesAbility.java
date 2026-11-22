package mod.advancedmobs.ability.creeper;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Gunpowder Sprites (Creeper ability 4 of 5, summon).
 *
 * WHAT IT DOES: the creeper sheds two small green slimes, living
 * gunpowder blobs that hop after its enemies trailing smoke and
 * green dust, swelling bigger as they go. When a sprite reaches
 * its target, or is killed on the way, it pops in a compact
 * explosion. The parent creeper stays at a safe distance and
 * watches its work.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   creeper's current target within 16 blocks (distance squared
 *   256). The sprites need a fight to run to.
 * - Duration: until both sprites have popped, with a hard finish
 *   at 300 ticks (15 seconds). Sprites still alive at the hard
 *   finish are recalled by end() WITHOUT popping (below).
 * - The sprites (start()): 2 slimes, size 1, spawned 1.0 blocks
 *   to either side of the creeper (or at the creeper's own
 *   position when that spot is not collision-free), created with
 *   EntitySpawnReason.MOB_SUMMONED and finalizeSpawn like every
 *   other summon in this mod. Each birth puffs 5 SMOKE and 3
 *   green DUST at the spawn spot.
 * - Team assignment, per sprite, both directions plus the
 *   registry: the caster's data gains the sprite as an ally, the
 *   sprite's data gains the caster, and the pair is registered
 *   in SummonerTracker (which also tags the sprite umw_summoned
 *   for the natural spawn blocker, the same flow Scarab Brood
 *   and Fungal Spawn use). Each sprite is ordered onto the
 *   caster's current target at spawn.
 * - Swelling: both sprites are born at activation tick 0, so the
 *   activation tick is their age. At age 50 a sprite grows to
 *   size 2, at age 110 to size 3 (setSize with a full heal to the
 *   new size's health: swelling is fresh gunpowder), each growth
 *   announced by 4 SMOKE puffs. Vanilla slime stats follow size:
 *   size 1 is 1 health and no contact damage, size 2 is 4 health
 *   and 2 damage, size 3 is 9 health and 3 damage. The pop below
 *   is the real threat; the contact damage is a bonus.
 * - The pop: a sprite pops in a Level.explode blast centered on
 *   itself, power 1.0 + 0.5 per size (size 1: 1.5, size 2: 2.0,
 *   size 3: 2.5; a vanilla creeper is 3.0, so even a fully swollen
 *   sprite stays compact), typed explosion damage with vanilla
 *   falloff. Two things trigger it, both checked every 2 ticks:
 *     REACHES ITS TARGET: the sprite is within 1.6 blocks
 *       (distance squared 2.56) of its prey (its own target, or
 *       the caster's live target when it has none). The sprite
 *       is discarded into the blast.
 *     KILLED: the sprite no longer resolves or is not alive.
 *       The pop runs at its last recorded position (refreshed
 *       every check while it lived). A killed slime of size 2 or
 *       more splits into vanilla minis by vanilla rules; those
 *       minis ARE the sprite's gunpowder scattering, so the pop
 *       consumes them: every slime inside a 2.0 block box around
 *       the pop that is not one of this ability's tracked
 *       sprites is discarded into the blast. (A wild slime that
 *       somehow stood inside that exact box at that exact moment
 *       would be consumed too; mid-combat, at a dying sprite's
 *       feet, that is accepted and effectively impossible to
 *       arrange by accident.)
 *   A sprite whose chunk unloads while the caster's stays loaded
 *   also stops resolving and is treated as killed: the pop runs
 *   and its ally records are cleaned. Sprites hunt within a few
 *   blocks of the caster, so this should not happen in practice.
 * - Babysitting, in the same every-2-tick pass: each living
 *   sprite whose target is empty or is the caster is re-ordered
 *   onto the caster's live target, so the pair stays on the
 *   creeper's fight. Nothing else is steered: hopping, splitting
 *   (handled above), and contact damage are vanilla slime
 *   behavior.
 * - end(): sprites still alive are recalled: alliance records
 *   removed on both sides and each is discarded in 6 POOF
 *   particles, WITHOUT popping. The pop is earned by reaching a
 *   target or dying in the fight; a timeout is neither. If the
 *   caster dies first, the framework termination path runs this
 *   same end(), so the sprites never outlive their creeper.
 *   SummonerTracker records are deliberately not unregistered,
 *   matching Scarab Brood: that registry cleans up on the
 *   sprite's own removal.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6): every pop picks its block
 * interaction from the shared AbilityContext.mobGriefingAllowed()
 * check at pop time: Level.ExplosionInteraction.MOB (blocks
 * break, permanently) when mobGriefing is on,
 * Level.ExplosionInteraction.NONE (no block is ever altered;
 * damage and knockback unchanged) when it is off.
 *
 * WHAT IT LOOKS LIKE: two palm-sized green slimes bounce out of
 * the creeper with a puff of smoke, trailing SMOKE and green
 * DUST (color 0x3DA33D) as they hop, visibly swelling twice on
 * the way in, and each ends in a tight little blast with the
 * vanilla explosion flash and boom.
 *
 * PERFORMANCE: all sprite work runs on one pass every 2 ticks:
 * at most 2 UUID resolves, 2 position writes, and the bounded
 * checks above. The split-mini query runs at most once per
 * sprite death, in a 4 by 3 by 4 box. Trails run every 6 ticks
 * inside the same pass. Particle budget per activation: births
 * 2 times 8 = 16, growth at most 2 sprites times 2 growths times
 * 4 = 16, trails at most 50 pulses times 2 sprites times 2 = 200,
 * end() at most 2 times 6 = 12, total at most 244 particles (the
 * pops' own particles are vanilla's).
 *
 * State tag keys: "sprites" (list of compounds, one per live
 * sprite: "id" the sprite's UUID, "x", "y", "z" its last known
 * position, refreshed every check, "size" its current size).
 */
public final class GunpowderSpritesAbility implements MobAbility {

    static final int DURATION_TICKS = 300;
    private static final int CHECK_INTERVAL = 2;
    private static final int TRAIL_INTERVAL = 6;
    private static final int SPRITE_COUNT = 2;
    private static final int GROW_FIRST_TICK = 50;
    private static final int GROW_SECOND_TICK = 110;
    private static final double POP_DISTANCE_SQR = 2.56;
    private static final DustParticleOptions GUNPOWDER_DUST = new DustParticleOptions(0x3DA33D, 1.0f);
    private static final double[][] OFFSETS = {
        {-1.0, 0.2}, {1.0, 0.2}
    };

    @Override
    public String id() {
        return "gunpowder_sprites";
    }

    @Override
    public String displayName() {
        return "Gunpowder Sprites";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        ListTag sprites = new ListTag();
        ctx.state().put("sprites", sprites);
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        LivingEntity target = ctx.target();
        for (int i = 0; i < SPRITE_COUNT; i++) {
            Slime sprite = EntityTypes.SLIME.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (sprite == null) {
                continue;
            }
            sprite.setSize(1, true);
            double spawnX = caster.getX() + OFFSETS[i][0];
            double spawnZ = caster.getZ() + OFFSETS[i][1];
            if (!isFreeSpot(level, sprite, spawnX, caster.getY(), spawnZ)) {
                spawnX = caster.getX();
                spawnZ = caster.getZ();
            }
            sprite.snapTo(spawnX, caster.getY(), spawnZ, ctx.random().nextFloat() * 360.0f, 0.0f);
            sprite.finalizeSpawn(level, level.getCurrentDifficultyAt(sprite.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
            if (!level.addFreshEntity(sprite)) {
                continue;
            }

            // Team assignment, both directions, plus the summoner registry.
            ctx.data().addAlly(sprite.getUUID());
            MobWarData.get(sprite).addAlly(caster.getUUID());
            SummonerTracker.registerSummoned(sprite.getUUID(), caster.getUUID());
            if (target != null) {
                sprite.setTarget(target);
            }
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, sprite.getUUID());
            entry.putDouble("x", sprite.getX());
            entry.putDouble("y", sprite.getY());
            entry.putDouble("z", sprite.getZ());
            entry.putInt("size", 1);
            sprites.add(entry);

            // The birth: a puff of smoke and green dust off the creeper's side.
            level.sendParticles(ParticleTypes.SMOKE, spawnX, caster.getY() + 0.5, spawnZ,
                5, 0.3, 0.3, 0.3, 0.04);
            level.sendParticles(GUNPOWDER_DUST, spawnX, caster.getY() + 0.5, spawnZ,
                3, 0.3, 0.3, 0.3, 0.02);
        }
        ctx.state().put("sprites", sprites);
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks % CHECK_INTERVAL != 0) {
            return; // Cheap early-out: sprite work runs every 2 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        ListTag sprites = state.getListOrEmpty("sprites");

        // Every tracked sprite id, so the split-mini cleanup at a
        // death pop never eats the surviving sprite.
        Set<UUID> trackedIds = new HashSet<>();
        for (int i = 0; i < sprites.size(); i++) {
            CompoundTag entry = sprites.getCompoundOrEmpty(i);
            if (entry.contains("id")) {
                entry.read("id", UUIDUtil.CODEC).ifPresent(trackedIds::add);
            }
        }

        LivingEntity desired = ctx.target();
        ListTag remaining = new ListTag();
        for (int i = 0; i < sprites.size(); i++) {
            CompoundTag entry = sprites.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            double x = entry.getDoubleOr("x", ctx.mob().getX());
            double y = entry.getDoubleOr("y", ctx.mob().getY());
            double z = entry.getDoubleOr("z", ctx.mob().getZ());
            int size = entry.getIntOr("size", 1);
            Slime sprite = resolveSprite(level, entry);

            if (sprite == null || !sprite.isAlive()) {
                // Killed (or lost) since the last check: the pop
                // runs at the last known position and consumes the
                // vanilla split minis (see the class comment).
                pop(ctx, level, x, y, z, size, null);
                consumeSplitMinis(level, x, y, z, trackedIds);
                if (id != null) {
                    ctx.data().removeAlly(id);
                }
                continue;
            }

            // Refresh the last known position for a possible death pop.
            entry.putDouble("x", sprite.getX());
            entry.putDouble("y", sprite.getY());
            entry.putDouble("z", sprite.getZ());

            // Swelling: size 2 at age 50, size 3 at age 110 (both
            // sprites are born at activation tick 0, so the
            // activation tick is their age).
            int wantSize = ticks >= GROW_SECOND_TICK ? 3 : ticks >= GROW_FIRST_TICK ? 2 : 1;
            if (wantSize > size) {
                sprite.setSize(wantSize, true);
                size = wantSize;
                entry.putInt("size", size);
                level.sendParticles(ParticleTypes.SMOKE, sprite.getX(), sprite.getY() + 0.3, sprite.getZ(),
                    4, 0.3, 0.25, 0.3, 0.04);
            }

            // Babysitting: keep the sprite on the creeper's fight.
            LivingEntity spriteTarget = sprite.getTarget();
            if ((spriteTarget == null || spriteTarget == ctx.mob())
                && desired != null && desired != sprite) {
                sprite.setTarget(desired);
                spriteTarget = desired;
            }

            // Reached its prey: pop on the spot.
            LivingEntity prey = spriteTarget != null ? spriteTarget : desired;
            if (prey != null && sprite.distanceToSqr(prey) <= POP_DISTANCE_SQR) {
                pop(ctx, level, sprite.getX(), sprite.getY(), sprite.getZ(), size, sprite);
                MobWarData.get(sprite).removeAlly(ctx.mob().getUUID());
                if (id != null) {
                    ctx.data().removeAlly(id);
                }
                sprite.discard();
                continue;
            }

            // The trail: smoke and green dust off the hopping blob.
            if (ticks % TRAIL_INTERVAL == 0) {
                level.sendParticles(ParticleTypes.SMOKE, sprite.getX(), sprite.getY() + 0.3, sprite.getZ(),
                    1, 0.2, 0.15, 0.2, 0.02);
                level.sendParticles(GUNPOWDER_DUST, sprite.getX(), sprite.getY() + 0.3, sprite.getZ(),
                    1, 0.2, 0.15, 0.2, 0.02);
            }
            remaining.add(entry);
        }
        state.put("sprites", remaining);
    }

    /** One sprite's pop: a compact explosion, terrain gated by the shared mobGriefing check. */
    private static void pop(AbilityContext ctx, ServerLevel level, double x, double y, double z,
                            int size, Slime source) {
        Level.ExplosionInteraction interaction = ctx.mobGriefingAllowed()
            ? Level.ExplosionInteraction.MOB
            : Level.ExplosionInteraction.NONE;
        level.explode(source, x, y, z, 1.0f + 0.5f * size, false, interaction);
    }

    /**
     * Discards every slime in a small box around a death pop that
     * is not one of this ability's tracked sprites: the vanilla
     * split minis of the sprite that just died (see the class
     * comment for the accepted wild-slime edge case).
     */
    private static void consumeSplitMinis(ServerLevel level, double x, double y, double z,
                                          Set<UUID> trackedIds) {
        AABB box = new AABB(x - 2.0, y - 1.5, z - 2.0, x + 2.0, y + 1.5, z + 2.0);
        for (Slime mini : level.getEntitiesOfClass(Slime.class, box,
            candidate -> !trackedIds.contains(candidate.getUUID()))) {
            mini.discard();
        }
    }

    /** Whether the sprite's bounding box at the given feet position collides with nothing. */
    private boolean isFreeSpot(ServerLevel level, Slime sprite, double x, double y, double z) {
        double halfWidth = sprite.getBbWidth() / 2.0;
        AABB box = new AABB(x - halfWidth, y, z - halfWidth, x + halfWidth, y + sprite.getBbHeight(), z + halfWidth);
        return level.noCollision(sprite, box);
    }

    /** Resolves a tracked sprite from its list entry, or null when gone. */
    private Slime resolveSprite(ServerLevel level, CompoundTag entry) {
        if (!entry.contains("id")) {
            return null;
        }
        UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
        if (id != null && level.getEntity(id) instanceof Slime slime) {
            return slime;
        }
        return null;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS
            || ctx.state().getListOrEmpty("sprites").isEmpty();
    }

    @Override
    public void end(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        ListTag sprites = ctx.state().getListOrEmpty("sprites");
        for (int i = 0; i < sprites.size(); i++) {
            CompoundTag entry = sprites.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID spriteId = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (spriteId == null) {
                continue;
            }
            // The alliance ends with the ability, whichever side is
            // still here.
            ctx.data().removeAlly(spriteId);
            Slime sprite = resolveSprite(level, entry);
            if (sprite == null) {
                continue;
            }
            MobWarData.get(sprite).removeAlly(caster.getUUID());
            if (sprite.isAlive()) {
                // Recalled, not popped: the blob melts away quietly.
                level.sendParticles(ParticleTypes.POOF, sprite.getX(), sprite.getY() + 0.3, sprite.getZ(),
                    6, 0.25, 0.2, 0.25, 0.04);
                sprite.discard();
            }
        }
    }
}
