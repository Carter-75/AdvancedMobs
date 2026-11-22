package mod.advancedmobs.ability.cat;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.data.MobWarData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.animal.feline.CatSoundVariant;
import net.minecraft.world.entity.animal.feline.CatSoundVariants;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/**
 * CatAbilitySupport: helpers shared by the Cat's 5 abilities.
 *
 * ENEMY RULE (used by every area or victim check in this
 * package: the creeperbane pulse, the swarm's rake target is
 * the caster's target only): a living entity counts as an
 * enemy of the caster when it is alive, is not the caster,
 * is not riding the caster or being ridden by it, is not in
 * the caster's ally set (MobWarData.isAlly, the war system's
 * alliance record), and is either the caster's current
 * combat target or another Mob. Players are only affected
 * when they are the caster's current target and are in
 * survival or adventure mode, so area abilities never grief
 * creative or spectator players who happen to stand nearby.
 * This is the same rule the other implemented mobs'
 * abilities use; it is duplicated here because those classes
 * are package-private to their own packages.
 *
 * ALLY RULE: a living entity counts as an ally of the caster
 * when it is alive, is not the caster, and either side's war
 * data lists the other as an ally (the either-direction check
 * the Breeze, Bee, and Wolf packages use).
 *
 * TERRAIN (the standing terrain rule, Carter, Nov 21, 2026;
 * IMPLEMENTATION-RULES.md rule 6): no ability in this
 * package places or breaks blocks at all, so the
 * mobGriefing gate does not apply anywhere. Everything here
 * is effects, velocity, navigation steering, particles, and
 * one cat summon.
 *
 * SOUNDS: in 26.2 the cat has no plain CAT_AMBIENT style
 * SoundEvent constants. Its sounds live in the feline sound
 * variant registry, exposed through SoundEvents.CAT_SOUNDS
 * (a map from CatSoundVariants.SoundSet to CatSoundVariant),
 * the same shape the Wolf package resolves. playCatSound()
 * resolves the CLASSIC variant's adult sound set once per
 * call (calls are rare: starts, strikes, saves) and plays
 * the requested sound through the Holder overload of
 * Level.playSound; a missing variant simply plays nothing.
 *
 * NIGHT: isNight() reads the level's day time directly:
 * vanilla night runs from day time 13000 to 23000 of the
 * 24000 tick day (the /time query gate of the design, in
 * code).
 *
 * MODIFIERS: addModifier() and removeModifier() are the
 * transient attribute modifier helpers the package uses.
 * Transient modifiers are not persisted, so one applied to a
 * victim vanishes on its own if that victim unloads;
 * removing one that is absent is a no-op. Both tolerate a
 * missing attribute instance (a mob that lacks the attribute
 * simply never feels the modifier).
 *
 * PERFORMANCE: area queries use Level.getEntitiesOfClass
 * with a tight AABB built from the given radius, and callers
 * only run them on a pulse interval or a one-shot moment,
 * never as whole-level scans.
 */
final class CatAbilitySupport {

    private CatAbilitySupport() {}

    /** The cat sounds this package plays, resolved from the CLASSIC variant's adult set. */
    enum CatSound { AMBIENT, HISS, HURT }

    /** Whether the given living entity is an enemy of the context mob. */
    static boolean isEnemy(AbilityContext ctx, LivingEntity candidate) {
        Mob self = ctx.mob();
        if (candidate == null || candidate == self || !candidate.isAlive()) {
            return false;
        }
        if (candidate.getVehicle() == self || self.getVehicle() == candidate) {
            return false;
        }
        if (ctx.data().isAlly(candidate.getUUID())) {
            return false;
        }
        if (candidate == ctx.target()) {
            return !(candidate instanceof Player player) || (!player.isCreative() && !player.isSpectator());
        }
        return candidate instanceof Mob;
    }

    /** Whether the given living entity is an ally of the context mob (either-direction record). */
    static boolean isAlly(AbilityContext ctx, LivingEntity candidate) {
        Mob self = ctx.mob();
        if (candidate == null || candidate == self || !candidate.isAlive()) {
            return false;
        }
        if (ctx.data().isAlly(candidate.getUUID())) {
            return true;
        }
        return candidate instanceof Mob candidateMob
            && MobWarData.get(candidateMob).isAlly(self.getUUID());
    }

    /**
     * All enemies of the context mob inside a box of the given
     * horizontal radius and vertical reach around a center point.
     * One bounded entity query; call on a pulse interval or a
     * one-shot moment, not per tick.
     */
    static List<LivingEntity> enemiesInBox(AbilityContext ctx, double centerX, double centerY, double centerZ,
                                           double radius, double yBelow, double yAbove) {
        AABB box = new AABB(centerX - radius, centerY - yBelow, centerZ - radius,
                            centerX + radius, centerY + yAbove, centerZ + radius);
        return ctx.level().getEntitiesOfClass(LivingEntity.class, box, e -> isEnemy(ctx, e));
    }

    /** All enemies of the context mob within the given radius of the mob itself. */
    static List<LivingEntity> enemiesAroundSelf(AbilityContext ctx, double radius, double yBelow, double yAbove) {
        Mob self = ctx.mob();
        return enemiesInBox(ctx, self.getX(), self.getY(), self.getZ(), radius, yBelow, yAbove);
    }

    /** Stores a spawned entity's UUID in the state tag's named list (one compound per entity: "id"). */
    static void trackEntity(CompoundTag state, String key, Entity entity) {
        ListTag list = state.getListOrEmpty(key);
        CompoundTag entry = new CompoundTag();
        entry.store("id", UUIDUtil.CODEC, entity.getUUID());
        list.add(entry);
        state.put(key, list);
    }

    /** Resolves a tracked entity from its list entry, or null when gone. */
    static Entity resolveTracked(ServerLevel level, CompoundTag entry) {
        if (!entry.contains("id")) {
            return null;
        }
        UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
        return id != null ? level.getEntity(id) : null;
    }

    /**
     * Finds the feet height (the top of the highest solid,
     * non-fluid block with air directly above it) in the
     * column at x/z, scanning from 3 blocks above the hint
     * down to 8 blocks below it: at most 12 block reads, and
     * callers run it once per placement or teleport, never
     * per tick. Returns the hint unchanged when the column
     * has no usable surface in range (the Rabbit package's
     * helper, same contract).
     */
    static double findGroundY(ServerLevel level, double x, double yHint, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int top = (int) Math.floor(yHint) + 3;
        int bottom = (int) Math.floor(yHint) - 8;
        for (int y = top; y >= bottom; y--) {
            BlockPos pos = new BlockPos(bx, y, bz);
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || !state.getFluidState().isEmpty()) {
                continue;
            }
            if (level.getBlockState(pos.above()).isAir()) {
                return y + 1.0;
            }
        }
        return yHint;
    }

    /**
     * Whether it is night in the given level: the overworld
     * clock time (Level.getOverworldClockTime, the 26.2 day
     * time accessor) within the 24000 tick day sits in the
     * vanilla night window, 13000 to 23000. One arithmetic
     * read; safe to call on a pulse interval.
     */
    static boolean isNight(Level level) {
        long dayTime = level.getOverworldClockTime() % 24000L;
        if (dayTime < 0) {
            dayTime += 24000L;
        }
        return dayTime >= 13000L && dayTime <= 23000L;
    }

    /**
     * Plays one of the cat's own sounds at the given position,
     * resolved from the CLASSIC feline sound variant's adult
     * set (see the SOUNDS note in the class comment). A
     * missing variant or sound plays nothing.
     */
    static void playCatSound(ServerLevel level, double x, double y, double z,
                             CatSound sound, float volume, float pitch) {
        CatSoundVariant variant = SoundEvents.CAT_SOUNDS.get(CatSoundVariants.SoundSet.CLASSIC);
        if (variant == null) {
            return;
        }
        Holder<SoundEvent> event = switch (sound) {
            case AMBIENT -> variant.adultSounds().ambientSound();
            case HISS -> variant.adultSounds().hissSound();
            case HURT -> variant.adultSounds().hurtSound();
        };
        if (event != null) {
            level.playSound(null, x, y, z, event, SoundSource.HOSTILE, volume, pitch);
        }
    }

    /** Adds or refreshes a transient attribute modifier on the given entity; tolerates a missing attribute. */
    static void addModifier(LivingEntity entity, Holder<Attribute> attribute, AttributeModifier modifier) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.addOrUpdateTransientModifier(modifier);
        }
    }

    /** Removes a transient attribute modifier by id; a no-op when absent or the attribute is missing. */
    static void removeModifier(LivingEntity entity, Holder<Attribute> attribute, Identifier id) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.removeModifier(id);
        }
    }
}
