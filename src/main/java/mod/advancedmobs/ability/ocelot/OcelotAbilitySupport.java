package mod.advancedmobs.ability.ocelot;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.data.MobWarData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/**
 * OcelotAbilitySupport: helpers shared by the Ocelot's 5
 * abilities.
 *
 * ENEMY RULE (used by every area or victim check in this
 * package: the phantom's track-clearing query, the treefall
 * impact, the cubs' nip target is the caster's target
 * only): a living entity counts as an enemy of the caster
 * when it is alive, is not the caster, is not riding the
 * caster or being ridden by it, is not in the caster's ally
 * set (MobWarData.isAlly, the war system's alliance
 * record), and is either the caster's current combat
 * target or another Mob. Players are only affected when
 * they are the caster's current target and are in survival
 * or adventure mode, so area abilities never grief creative
 * or spectator players who happen to stand nearby. This is
 * the same rule the other implemented mobs' abilities use;
 * it is duplicated here because those classes are
 * package-private to their own packages.
 *
 * ALLY RULE: a living entity counts as an ally of the
 * caster when it is alive, is not the caster, and either
 * side's war data lists the other as an ally (the
 * either-direction check the Breeze, Bee, and Wolf packages
 * use).
 *
 * TERRAIN (the standing terrain rule, Carter, Nov 21, 2026;
 * IMPLEMENTATION-RULES.md rule 6): no ability in this
 * package places or breaks blocks at all, so the
 * mobGriefing gate does not apply anywhere. The Treefall
 * Ambush's BLOCK_CRUMBLE burst is a particle carrying the
 * ground state, not a block change. Everything here is
 * effects, velocity, navigation steering, particles, and
 * one ocelot summon.
 *
 * LEAVES: LEAF is the package's shared TINTED_LEAVES
 * particle option (verified against the real 26.2 jar:
 * ParticleTypes.TINTED_LEAVES is a
 * ParticleType<ColorParticleOption>, and CAPABILITIES.md
 * confirms tinted_leaves exists in both 26.2 and 26.3),
 * tinted a jungle green (0.35, 0.65, 0.25). It is built
 * once as a static final, so no per-tick allocation.
 *
 * SOUNDS: unlike the cat, the ocelot's sounds ARE plain
 * SoundEvent constants in 26.2 (SoundEvents.OCELOT_AMBIENT
 * and OCELOT_HURT, verified against the jar), so abilities
 * play them directly through Level.playSound.
 *
 * MODIFIERS: addModifier() and removeModifier() are the
 * transient attribute modifier helpers the package uses.
 * Transient modifiers are not persisted, so one applied to
 * a victim vanishes on its own if that victim unloads;
 * removing one that is absent is a no-op. Both tolerate a
 * missing attribute instance (a mob that lacks the
 * attribute simply never feels the modifier).
 *
 * PERFORMANCE: area queries use Level.getEntitiesOfClass
 * with a tight AABB built from the given radius, and
 * callers only run them on a pulse interval or a one-shot
 * moment, never as whole-level scans.
 */
final class OcelotAbilitySupport {

    private OcelotAbilitySupport() {}

    /** The shared jungle-green tinted leaves particle option for this package. */
    static final ColorParticleOption LEAF =
        ColorParticleOption.create(ParticleTypes.TINTED_LEAVES, 0.35f, 0.65f, 0.25f);

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

    /** Resolves a single entity UUID stored directly in the state tag under the key, or null. */
    static Entity resolveStored(ServerLevel level, CompoundTag state, String key) {
        if (!state.contains(key)) {
            return null;
        }
        UUID id = state.read(key, UUIDUtil.CODEC).orElse(null);
        return id != null ? level.getEntity(id) : null;
    }

    /**
     * Finds the feet height (the top of the highest solid,
     * non-fluid block with air directly above it) in the
     * column at x/z, scanning from 3 blocks above the hint
     * down to 8 blocks below it: at most 12 block reads, and
     * callers run it once per placement, never per tick.
     * Returns the hint unchanged when the column has no
     * usable surface in range (the Rabbit package's helper,
     * same contract).
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
