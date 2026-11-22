package mod.advancedmobs.ability.zombievillager;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Cursed Market (Zombie Villager ability 4 of 5, terrain).
 *
 * WHAT IT DOES: a crooked little market square rises around the zombie
 * villager's target: plank stall posts, counters, and a lectern, all
 * block displays dripping with angry villager particles. The square is
 * cursed ground: enemies who wander into the stall zone are fleeced,
 * gaining MINING_FATIGUE and WEAKNESS, and their attacks slow to a
 * crawl until they escape the market.
 *
 * TERRAIN NOTE (the standing terrain rule, Carter, Nov 21, 2026): the
 * stalls are block DISPLAY entities, not block changes. No world block
 * is placed, broken, or altered by this ability in either mode, so the
 * mobGriefing gate does not apply to it: the market looks identical
 * with mobGriefing on or off, and end() removes the displays, leaving
 * the world exactly as it was. (Abilities that change real blocks,
 * like Gravemud Mire's wall, are the ones the rule governs.)
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the zombie
 *   villager's current target within 12 blocks (distance squared 144)
 *   and standing on the ground (the square is laid out on the target's
 *   ground level).
 * - Duration: exactly 200 ticks (10 seconds) from start().
 * - The square (built once in start(), 7 block displays): centered on
 *   the target's block position at start (stored as "cx"/"cy"/"cz").
 *     4 corner posts: oak planks, scale 0.35 by 2.4 by 0.35, at the
 *       corners of a 5 by 5 square (offsets plus or minus 2.5 in X and
 *       Z from the center), standing on the center's ground level.
 *     1 lectern: scale 1.1, at the north edge middle (offset 0, -2.5),
 *       the cursed shopkeeper's stand.
 *     2 counters: oak planks, scale 2.4 by 0.35 by 0.7, at counter
 *       height 0.9 on the south edge (offset 0, +2.5) and the east
 *       edge (offset +2.5, 0).
 *   Display UUIDs are recorded in the state tag ("displays") and every
 *   display is discarded in end().
 * - The curse (pulse every 10 ticks, 20 pulses): enemies
 *   (ZombieVillagerAbilitySupport rules) within horizontal radius 3.6
 *   of the square's center and between 1 below and 3 above its base
 *   level get:
 *     MINING_FATIGUE I (amplifier 0) and WEAKNESS I (amplifier 0),
 *       15 ticks each, refreshed by the next pulse while they stay.
 *     A transient ATTACK_SPEED modifier, add_multiplied_base -0.4
 *       (40 percent slower attacks), id
 *       advancedmobs:cursed_market_attack_speed. The pulse tracks the
 *       victim set in the state tag ("slowed"): a victim that leaves
 *       the zone has the modifier removed at the next pulse; every
 *       recorded victim has it removed in end(). Victims that die or
 *       unload take the transient modifier with them.
 *
 * WHAT IT LOOKS LIKE: a crooked plank market square with a lectern,
 * ANGRY_VILLAGER particles dripping off it: 8 at the raising in
 * start(), then 3 per pulse above the lectern. Victims also show the
 * vanilla effect sparkles of MINING_FATIGUE and WEAKNESS while cursed.
 *
 * PERFORMANCE: the displays are built once in start() (7 entity
 * creations) and discarded once in end(). tick() returns immediately
 * except every 10th tick, which runs one bounded entity query (7.2 by
 * 4 by 7.2 box) plus modifier bookkeeping over the handful of victims
 * inside. The slowed set is rebuilt per pulse with one entry per
 * current victim, no accumulation. Particle budget per activation:
 * 8 at the raising plus 20 pulses times 3 = 60, total at most 68
 * particles.
 *
 * CLEANUP: end() removes the ATTACK_SPEED modifier from every recorded
 * victim that still resolves, and discards all 7 displays. If the
 * caster dies mid-market the framework termination path runs this same
 * end(), so the square never outlives the ability or its caster. The
 * curse effects themselves last at most 15 ticks and expire on their
 * own.
 *
 * State tag keys: "cx", "cy", "cz" (square center block pos),
 * "displays" (list of display UUIDs), "slowed" (list of victim UUIDs
 * currently carrying the attack speed modifier).
 */
public final class CursedMarketAbility implements MobAbility {

    static final int DURATION_TICKS = 200;
    private static final int PULSE_INTERVAL = 10;
    private static final Identifier ATTACK_SPEED_ID = Identifier.fromNamespaceAndPath("advancedmobs", "cursed_market_attack_speed");

    @Override
    public String id() {
        return "cursed_market";
    }

    @Override
    public String displayName() {
        return "Cursed Market";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && target.onGround() && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        LivingEntity target = ctx.target();
        if (level == null || target == null) {
            return;
        }
        CompoundTag state = ctx.state();
        BlockPos center = target.blockPosition();
        state.putInt("cx", center.getX());
        state.putInt("cy", center.getY());
        state.putInt("cz", center.getZ());
        state.put("slowed", new ListTag());

        // Build the square: 4 posts, a lectern, 2 counters. Displays,
        // not blocks: see the TERRAIN NOTE in the class comment.
        ListTag displays = new ListTag();
        BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
        double baseX = center.getX(), baseY = center.getY(), baseZ = center.getZ();
        double[][] corners = {{-2.5, -2.5}, {2.5, -2.5}, {-2.5, 2.5}, {2.5, 2.5}};
        for (double[] corner : corners) {
            addDisplay(ctx, level, displays, planks,
                0.35f, 2.4f, 0.35f, -0.175f, 0.0f, -0.175f,
                baseX + corner[0], baseY, baseZ + corner[1]);
        }
        addDisplay(ctx, level, displays, Blocks.LECTERN.defaultBlockState(),
            1.1f, 1.1f, 1.1f, -0.55f, 0.0f, -0.55f,
            baseX, baseY, baseZ - 2.5);
        addDisplay(ctx, level, displays, planks,
            2.4f, 0.35f, 0.7f, -1.2f, 0.0f, -0.35f,
            baseX, baseY + 0.9, baseZ + 2.5);
        addDisplay(ctx, level, displays, planks,
            2.4f, 0.35f, 0.7f, -1.2f, 0.0f, -0.35f,
            baseX + 2.5, baseY + 0.9, baseZ);
        state.put("displays", displays);

        // The raising: angry sparkles over the fresh square.
        level.sendParticles(ParticleTypes.ANGRY_VILLAGER, baseX + 0.5, baseY + 1.8, baseZ + 0.5,
            8, 2.2, 0.6, 2.2, 0.05);
    }

    /** Creates one stall display, snaps it into place, and records its UUID. */
    private void addDisplay(AbilityContext ctx, ServerLevel level, ListTag displays, BlockState blockState,
                            float scaleX, float scaleY, float scaleZ,
                            float transX, float transY, float transZ,
                            double x, double y, double z) {
        Entity display = ZombieVillagerAbilitySupport.createBlockDisplay(level, blockState,
            scaleX, scaleY, scaleZ, transX, transY, transZ);
        if (display == null) {
            return;
        }
        display.snapTo(x, y, z);
        level.addFreshEntity(display);
        CompoundTag entry = new CompoundTag();
        entry.store("id", UUIDUtil.CODEC, display.getUUID());
        displays.add(entry);
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % PULSE_INTERVAL != 0) {
            return; // Cheap early-out: the curse pulses every 10 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        double cx = state.getIntOr("cx", 0) + 0.5;
        double cy = state.getIntOr("cy", 0);
        double cz = state.getIntOr("cz", 0) + 0.5;

        // Who is in the market this pulse.
        List<LivingEntity> victims = ZombieVillagerAbilitySupport.enemiesInBox(ctx, cx, cy, cz, 3.6, 1.0, 3.0);
        Set<UUID> current = new HashSet<>();
        for (LivingEntity victim : victims) {
            victim.addEffect(new MobEffectInstance(MobEffects.MINING_FATIGUE, 15, 0));
            victim.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 15, 0));
            AttributeInstance attackSpeed = victim.getAttribute(Attributes.ATTACK_SPEED);
            if (attackSpeed != null) {
                attackSpeed.addOrUpdateTransientModifier(
                    new AttributeModifier(ATTACK_SPEED_ID, -0.4, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
                current.add(victim.getUUID());
            }
        }

        // Whoever escaped since the last pulse loses the slow at once;
        // the recorded set becomes exactly this pulse's victim set.
        for (UUID previous : readUuidList(state.getListOrEmpty("slowed"))) {
            if (!current.contains(previous)) {
                removeAttackSpeedSlow(level, previous);
            }
        }
        ListTag slowed = new ListTag();
        for (UUID id : current) {
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, id);
            slowed.add(entry);
        }
        state.put("slowed", slowed);

        // Angry sparkles drip off the lectern.
        level.sendParticles(ParticleTypes.ANGRY_VILLAGER, cx, cy + 2.2, cz - 2.5,
            3, 0.4, 0.3, 0.4, 0.03);
    }

    /** Reads a state list of {"id": UUID} compounds into a UUID list. */
    private List<UUID> readUuidList(ListTag list) {
        List<UUID> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            UUID id = list.getCompoundOrEmpty(i).read("id", UUIDUtil.CODEC).orElse(null);
            if (id != null) {
                out.add(id);
            }
        }
        return out;
    }

    /** Removes this ability's attack speed modifier from one entity, if it still exists. */
    private void removeAttackSpeedSlow(ServerLevel level, UUID victimId) {
        if (level.getEntity(victimId) instanceof LivingEntity victim) {
            AttributeInstance attackSpeed = victim.getAttribute(Attributes.ATTACK_SPEED);
            if (attackSpeed != null) {
                attackSpeed.removeModifier(ATTACK_SPEED_ID);
            }
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
        CompoundTag state = ctx.state();
        // Un-slow every victim still carrying the modifier.
        for (UUID victimId : readUuidList(state.getListOrEmpty("slowed"))) {
            removeAttackSpeedSlow(level, victimId);
        }
        // Take the stalls down: displays never outlive the ability.
        for (UUID displayId : readUuidList(state.getListOrEmpty("displays"))) {
            Entity display = level.getEntity(displayId);
            if (display != null) {
                display.discard();
            }
        }
    }
}
