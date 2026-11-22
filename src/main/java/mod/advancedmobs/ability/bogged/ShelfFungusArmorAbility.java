package mod.advancedmobs.ability.bogged;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;

import java.util.UUID;

/**
 * Shelf Fungus Armor (Bogged ability 4 of 5, defense).
 *
 * WHAT IT DOES: broad mushroom shelves sprout across the
 * bogged's shoulders and skull: two brown mushroom shelves over
 * the shoulders and a red mushroom cap on the skull, grown as
 * block displays that ride the bogged's body. The fungal plating
 * adds ARMOR_TOUGHNESS, so heavy hits lose their bite, and every
 * blow that lands on the bogged shakes loose a puff of spores
 * that poisons the attacker.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists within 12 blocks
 *   (distance squared 144).
 * - Duration: exactly 240 ticks (12 seconds) from start().
 * - The shelves: 3 block displays at world brightness (no
 *   brightness override; the fungus is lit by the swamp like the
 *   real thing). The two shoulder shelves are brown mushroom
 *   block, scale 0.8, tilted 0.3 radians about their local Z
 *   axis in opposite directions so they flare like brackets; the
 *   skull cap is red mushroom block, scale 0.95, untilted. They
 *   sit at body-relative offsets rotated by the bogged's yaw
 *   every tick: shoulders 0.58 blocks to either side at height
 *   1.42, cap centered at height 2.02, so the shell turns and
 *   travels with the bogged. They are pure visuals: displays
 *   have no hitbox and block nothing themselves; the defense is
 *   the attribute below.
 * - The plating, for the duration: an ARMOR_TOUGHNESS add_value
 *   modifier of +4.0 (modifier id
 *   advancedmobs:shelf_fungus_toughness). Toughness is what
 *   keeps armor effective against heavy hits: +4 is half the
 *   toughness of a full diamond set, so a hard blow that would
 *   punch through the bogged's light armor keeps most of its
 *   reduction. Removed in end().
 * - The spore answer: every 5 ticks the ability compares the
 *   bogged's last-hurt-by-mob timestamp with the stored one. A
 *   new timestamp means a hit landed (melee, or a projectile
 *   whose owner the timestamp names). The attacker, when it is
 *   an enemy (BoggedAbilitySupport rules), gets POISON I
 *   (amplifier 0) for 100 ticks (5 seconds: weak poison, 3 to 4
 *   damage over its run before the usual poison floor) and a 6
 *   particle MYCELIUM puff at its position, with a soft MUD_HIT
 *   thud (volume 0.6, pitch 1.1). Answers are at most one per 20
 *   ticks, so focus fire cannot turn the shelves into a particle
 *   fountain or a poison-lock; the timestamp still updates on
 *   every check, so a hit swallowed by the answer cooldown is
 *   simply answered by the next one. The stored timestamp is
 *   initialized in start() to the bogged's current one, so a hit
 *   that landed before the shelves grew is never answered.
 *
 * WHAT IT LOOKS LIKE: the shelves squelch on with a MUD_PLACE
 * sound (volume 1.0, pitch 0.7) in a burst of 8 SPORE_BLOSSOM_AIR
 * and 6 MYCELIUM particles, and SPORE_BLOSSOM_AIR drifts lazily
 * off the caps the whole time: 2 motes every 20 ticks at
 * shoulder height, the lazy drift the design calls for.
 *
 * PERFORMANCE: per tick the work is 3 UUID resolves and 3
 * snapTo writes for the shelves, nothing else; the answer check
 * runs every 5 ticks (one timestamp read, and only on a new hit
 * one attacker resolve and one effect application), the drift
 * every 20 ticks. No entity scans, no allocations in the tick
 * path beyond the resolved references. Particle budget per
 * activation: 14 at start, drift at most 12 pulses times 2 =
 * 24, answers at most 12 times 6 = 72, total at most 110
 * particles. The 3 displays are the only extra entities, alive
 * only for the ability's 12 seconds.
 *
 * CLEANUP: end() removes the toughness modifier and discards
 * every shelf display that still resolves. If the caster dies
 * mid-armor, the framework termination path runs this same
 * end(), so the shelves never outlive the ability or its caster.
 *
 * State tag keys: "plates" (list of compounds, one per shelf:
 * "id", the display's UUID; "side", -1 for the left shoulder,
 * +1 for the right shoulder, 0 for the skull cap), "answer_ts"
 * (the last-hurt-by-mob timestamp already answered),
 * "last_answer" (tick of the last spore answer).
 */
public final class ShelfFungusArmorAbility implements MobAbility {

    static final int DURATION_TICKS = 240;
    private static final int ANSWER_INTERVAL = 5;
    private static final int ANSWER_COOLDOWN = 20;
    private static final double SHOULDER_OFFSET = 0.58;
    private static final double SHOULDER_HEIGHT = 1.42;
    private static final double CAP_HEIGHT = 2.02;
    private static final Identifier TOUGHNESS_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "shelf_fungus_toughness");

    @Override
    public String id() {
        return "shelf_fungus_armor";
    }

    @Override
    public String displayName() {
        return "Shelf Fungus Armor";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        state.putInt("answer_ts", mob.getLastHurtByMobTimestamp());
        state.putInt("last_answer", -ANSWER_COOLDOWN);

        // The plating.
        AttributeInstance toughness = mob.getAttribute(Attributes.ARMOR_TOUGHNESS);
        if (toughness != null) {
            toughness.addOrUpdateTransientModifier(
                new AttributeModifier(TOUGHNESS_ID, 4.0, AttributeModifier.Operation.ADD_VALUE));
        }

        // The shelves sprout.
        ListTag plates = new ListTag();
        if (level != null) {
            // side -1: left shoulder shelf, brown mushroom, flared left.
            addPlate(ctx, level, plates, -1, Blocks.BROWN_MUSHROOM_BLOCK.defaultBlockState(), 0.8f, -0.3);
            // side +1: right shoulder shelf, brown mushroom, flared right.
            addPlate(ctx, level, plates, 1, Blocks.BROWN_MUSHROOM_BLOCK.defaultBlockState(), 0.8f, 0.3);
            // side 0: the skull cap, red mushroom, untilted.
            addPlate(ctx, level, plates, 0, Blocks.RED_MUSHROOM_BLOCK.defaultBlockState(), 0.95f, 0.0);
            level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, mob.getX(), mob.getY() + 1.5, mob.getZ(),
                8, 0.6, 0.5, 0.6, 0.03);
            level.sendParticles(ParticleTypes.MYCELIUM, mob.getX(), mob.getY() + 1.2, mob.getZ(),
                6, 0.5, 0.6, 0.5, 0.05);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.MUD_PLACE, SoundSource.HOSTILE, 1.0f, 0.7f);
        }
        state.put("plates", plates);
    }

    /** Creates one shelf display at its body-relative spot and records it in the plates list. */
    private void addPlate(AbilityContext ctx, ServerLevel level, ListTag plates, int side,
                          net.minecraft.world.level.block.state.BlockState blockState,
                          float scale, double tiltRadians) {
        // Tilt about the local Z axis, as a quaternion (x, y, z, w).
        double halfTilt = tiltRadians / 2.0;
        Entity plate = BoggedAbilitySupport.createBlockDisplay(level, blockState, scale,
            0.0f, 0.0f, (float) Math.sin(halfTilt), (float) Math.cos(halfTilt));
        if (plate == null) {
            return;
        }
        positionPlate(ctx, plate, side);
        level.addFreshEntity(plate);
        CompoundTag entry = new CompoundTag();
        entry.store("id", UUIDUtil.CODEC, plate.getUUID());
        entry.putInt("side", side);
        plates.add(entry);
    }

    /**
     * Places one shelf on its body-relative bearing: the offsets
     * rotate with the bogged's yaw, so the shelves turn with it.
     * Minecraft yaw 0 faces south (+z); the forward vector is
     * (-sin, cos) and the right vector is (-cos, -sin) in xz.
     */
    private void positionPlate(AbilityContext ctx, Entity plate, int side) {
        Mob mob = ctx.mob();
        double yawRad = Math.toRadians(mob.getYRot());
        double rightX = -Math.cos(yawRad);
        double rightZ = -Math.sin(yawRad);
        double x = mob.getX() + rightX * SHOULDER_OFFSET * side;
        double z = mob.getZ() + rightZ * SHOULDER_OFFSET * side;
        double y = mob.getY() + (side == 0 ? CAP_HEIGHT : SHOULDER_HEIGHT);
        plate.snapTo(x, y, z, mob.getYRot(), 0.0f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        CompoundTag state = ctx.state();
        ListTag plates = state.getListOrEmpty("plates");

        // The shelves travel and turn with the bogged: every
        // shelf is repositioned every tick.
        for (int i = 0; i < plates.size(); i++) {
            CompoundTag entry = plates.getCompoundOrEmpty(i);
            Entity plate = resolvePlate(level, entry);
            if (plate != null) {
                positionPlate(ctx, plate, entry.getIntOr("side", 0));
            }
        }

        // The spore answer: a fresh hit on the shelves poisons
        // the attacker.
        if (ticks % ANSWER_INTERVAL == 0) {
            Mob mob = ctx.mob();
            int timestamp = mob.getLastHurtByMobTimestamp();
            if (timestamp != state.getIntOr("answer_ts", -1)) {
                state.putInt("answer_ts", timestamp);
                LivingEntity attacker = mob.getLastHurtByMob();
                if (attacker != null && BoggedAbilitySupport.isEnemy(ctx, attacker)
                    && ticks - state.getIntOr("last_answer", 0) >= ANSWER_COOLDOWN) {
                    attacker.addEffect(new MobEffectInstance(MobEffects.POISON, 100, 0));
                    level.sendParticles(ParticleTypes.MYCELIUM,
                        attacker.getX(), attacker.getY() + 1.0, attacker.getZ(),
                        6, 0.4, 0.5, 0.4, 0.06);
                    level.playSound(null, attacker.getX(), attacker.getY(), attacker.getZ(),
                        SoundEvents.MUD_HIT, SoundSource.HOSTILE, 0.6f, 1.1f);
                    state.putInt("last_answer", ticks);
                }
            }
        }

        if (ticks % 20 == 0) {
            Mob mob = ctx.mob();
            level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, mob.getX(), mob.getY() + 1.6, mob.getZ(),
                2, 0.6, 0.4, 0.6, 0.02);
        }
    }

    /** Resolves a tracked shelf display from its list entry, or null when gone. */
    private Entity resolvePlate(ServerLevel level, CompoundTag entry) {
        if (!entry.contains("id")) {
            return null;
        }
        UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
        return id != null ? level.getEntity(id) : null;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        AttributeInstance toughness = mob.getAttribute(Attributes.ARMOR_TOUGHNESS);
        if (toughness != null) {
            toughness.removeModifier(TOUGHNESS_ID);
        }

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag plates = ctx.state().getListOrEmpty("plates");
        for (int i = 0; i < plates.size(); i++) {
            Entity plate = resolvePlate(level, plates.getCompoundOrEmpty(i));
            if (plate != null) {
                plate.discard();
            }
        }
    }
}
