package mod.advancedmobs.ability.slime;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Slime Slick (Slime ability 5 of 5, terrain).
 *
 * WHAT IT DOES: the slime leaves a trail of real slime blocks
 * behind it, glistening patches set into the ground wherever
 * it bounces, ITEM_SLIME glistening on the fresh deposits.
 * Enemies who step on the slick are given uncontrolled
 * bounciness of their own, bouncing helplessly with every
 * step and losing all formation while the slime watches from
 * solid ground.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists and is within 12
 *   blocks (distance squared 144).
 * - Duration: exactly 300 ticks (15 seconds) from start().
 * - The trail: every 4 ticks, if the slime is on the ground,
 *   the ground block directly under its feet is converted
 *   into a slime block (level.setBlock, Block.UPDATE_ALL),
 *   at most 48 conversions per activation. Each fresh deposit
 *   glistens with 4 ITEM_SLIME particles and a soft
 *   SLIME_BLOCK_PLACE sound (volume 0.5). The slime block is
 *   a full solid block, so the trail reads as patches set
 *   flush into the ground, and the slime itself simply hops
 *   on over its own deposits.
 * - The slick debuff: every 10 ticks, one bounded enemy query
 *   (SlimeAbilitySupport rules) in radius 6.0 around the
 *   slime, 2 below to 2 above. An enemy whose supporting
 *   block (the block under its feet) is a slime block, any
 *   slime block, the trail's or a natural or player-placed
 *   one, gets two transient modifiers (ids
 *   advancedmobs:slime_slick_bounciness and
 *   advancedmobs:slime_slick_friction): BOUNCINESS add_value
 *   +0.85 (every step and landing bounces back at 85 percent)
 *   and FRICTION_MODIFIER add_value -0.85 (ground friction
 *   nearly gone, so the bouncing cannot be steered). Victims
 *   are recorded in the state tag ("slicked"); at each pulse,
 *   a recorded victim that is no longer standing on a slime
 *   block (or no longer resolves) has both modifiers removed
 *   and is dropped from the list, so the debuff tracks the
 *   slick and nothing else. The caster is never its own
 *   victim: the enemy rule excludes it.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6): the trail is a real,
 * PERMANENT block change, so conversions only happen when
 * AbilityContext.mobGriefingAllowed() is true, and only into
 * ground that passes SlimeAbilitySupport.isConvertibleGround
 * (a real, dry, breakable block with no block entity, never
 * a container, never an unbreakable block, never a block
 * already slime). The converted blocks stay converted
 * forever; end() deliberately does not touch them. With
 * mobGriefing OFF the slime places nothing, and the rest of
 * the ability still runs: the debuff pulse still fires on
 * any pre-existing slime blocks an enemy stands on, and the
 * framework cooldown behaves the same.
 *
 * WHAT IT LOOKS LIKE: a hopping slime paving the ground
 * behind it in green, each new patch flashing 4 ITEM_SLIME
 * motes as it sets, and any enemy that strays onto the green
 * suddenly bouncing and skidding with every step, formation
 * gone, while the slime watches from solid ground.
 *
 * PERFORMANCE: placement is one block read and at most one
 * setBlock per 4 ticks; the debuff pulse is one bounded
 * entity query per 10 ticks plus resolving the recorded
 * victim list (a handful of UUIDs). The griefing check is
 * read once per placement tick, not per block. No whole-level
 * scans, no per-tick allocations in the idle path. Particle
 * budget per activation, hard cap by construction: 48
 * deposits times 4 = at most 192 particles.
 *
 * CLEANUP: end() removes both slick modifiers from every
 * recorded victim that still resolves. The trail blocks are
 * permanent terrain (see the TERRAIN RULE) and are NOT
 * removed. The modifiers are transient, so a victim that
 * unloaded mid-ability lost them with its chunk anyway, and
 * removal against an absent modifier is a no-op.
 *
 * State tag keys: "placed" (slime blocks converted so far),
 * "slicked" (list of compounds, one per debuffed victim:
 * "id", the victim's UUID).
 */
public final class SlimeSlickAbility implements MobAbility {

    static final int DURATION_TICKS = 300;
    private static final int PLACE_INTERVAL = 4;
    private static final int PULSE_INTERVAL = 10;
    private static final int MAX_PLACED = 48;
    private static final double PULSE_RADIUS = 6.0;
    private static final Identifier BOUNCINESS_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "slime_slick_bounciness");
    private static final Identifier FRICTION_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "slime_slick_friction");

    @Override
    public String id() {
        return "slime_slick";
    }

    @Override
    public String displayName() {
        return "Slime Slick";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return ctx.target() != null && ctx.mob().distanceToSqr(ctx.target()) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().putInt("placed", 0);
        ctx.state().put("slicked", new ListTag());
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        if (ticks % PLACE_INTERVAL == 0) {
            placeDeposit(ctx, level);
        }
        if (ticks % PULSE_INTERVAL == 0) {
            pulseSlick(ctx, level);
        }
    }

    /** Converts the ground block under the slime into a slime block, when the terrain rule allows it. */
    private static void placeDeposit(AbilityContext ctx, ServerLevel level) {
        if (!ctx.mob().onGround() || !ctx.mobGriefingAllowed()) {
            return;
        }
        if (ctx.state().getIntOr("placed", 0) >= MAX_PLACED) {
            return;
        }
        BlockPos groundPos = ctx.mob().blockPosition().below();
        if (!SlimeAbilitySupport.isConvertibleGround(level, groundPos)) {
            return;
        }
        level.setBlock(groundPos, Blocks.SLIME_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        ctx.state().putInt("placed", ctx.state().getIntOr("placed", 0) + 1);
        level.sendParticles(ParticleTypes.ITEM_SLIME,
            groundPos.getX() + 0.5, groundPos.getY() + 1.1, groundPos.getZ() + 0.5,
            4, 0.4, 0.15, 0.4, 0.05);
        level.playSound(null, groundPos, SoundEvents.SLIME_BLOCK_PLACE, SoundSource.HOSTILE, 0.5f, 1.0f);
    }

    /**
     * One debuff pulse: enemies standing on a slime block gain
     * the slick modifiers, recorded victims who stepped off
     * (or vanished) lose them. See the MECHANICS note in the
     * class comment.
     */
    private static void pulseSlick(AbilityContext ctx, ServerLevel level) {
        List<LivingEntity> nearby = SlimeAbilitySupport.enemiesInBox(ctx,
            ctx.mob().getX(), ctx.mob().getY(), ctx.mob().getZ(), PULSE_RADIUS, 2.0, 2.0);
        Set<UUID> onSlick = new HashSet<>();
        for (LivingEntity enemy : nearby) {
            if (level.getBlockState(enemy.blockPosition().below()).is(Blocks.SLIME_BLOCK)) {
                SlimeAbilitySupport.addModifier(enemy, Attributes.BOUNCINESS,
                    new AttributeModifier(BOUNCINESS_ID, 0.85, AttributeModifier.Operation.ADD_VALUE));
                SlimeAbilitySupport.addModifier(enemy, Attributes.FRICTION_MODIFIER,
                    new AttributeModifier(FRICTION_ID, -0.85, AttributeModifier.Operation.ADD_VALUE));
                onSlick.add(enemy.getUUID());
            }
        }
        // Rebuild the recorded list: keep victims still on the
        // slick, strip the modifiers from everyone else.
        ListTag recorded = ctx.state().getListOrEmpty("slicked");
        ListTag kept = new ListTag();
        for (int i = 0; i < recorded.size(); i++) {
            CompoundTag entry = recorded.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (id == null) {
                continue;
            }
            if (onSlick.contains(id)) {
                kept.add(entry);
                onSlick.remove(id);
            } else {
                Entity entity = level.getEntity(id);
                if (entity instanceof LivingEntity victim) {
                    removeSlickModifiers(victim);
                }
            }
        }
        for (UUID id : onSlick) {
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, id);
            kept.add(entry);
        }
        ctx.state().put("slicked", kept);
    }

    /** Removes both slick modifiers from a victim. */
    private static void removeSlickModifiers(LivingEntity victim) {
        SlimeAbilitySupport.removeModifier(victim, Attributes.BOUNCINESS, BOUNCINESS_ID);
        SlimeAbilitySupport.removeModifier(victim, Attributes.FRICTION_MODIFIER, FRICTION_ID);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // The debuff ends with the ability; the trail is
        // permanent terrain and stays (see the TERRAIN RULE in
        // the class comment).
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag recorded = ctx.state().getListOrEmpty("slicked");
        for (int i = 0; i < recorded.size(); i++) {
            CompoundTag entry = recorded.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
            Entity entity = id != null ? level.getEntity(id) : null;
            if (entity instanceof LivingEntity victim) {
                removeSlickModifiers(victim);
            }
        }
    }
}
