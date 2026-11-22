package mod.advancedmobs.ability.mooshroom;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;

import java.util.UUID;

/**
 * Fungal Plate Armor (Mooshroom ability 2 of 5, defense).
 *
 * WHAT IT DOES: red mushroom plates sprout across the
 * mooshroom's back and sides until it looks like a walking
 * mushroom fortress. While plated its armor climbs sharply,
 * and every hit it takes knocks a chunk of fungus loose in a
 * spray of red mushroom block particles.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the mooshroom's current target within 12 blocks (distance
 *   squared 144).
 * - Duration: exactly 300 ticks (15 seconds) from start().
 * - The plates: 5 red mushroom block DISPLAYS (back plate
 *   scale 1.05, flank plates scale 0.9, shoulder plates
 *   scale 0.75), pure visuals with no hitbox; the defense is
 *   the attribute below. They sprout in sequence, one every
 *   4 ticks over ticks 0 to 16 (the grow-in: a display's
 *   scale is fixed at creation in 26.2, so growth reads as a
 *   sprouting sequence rather than a swell), each announced
 *   by 6 MYCELIUM motes at its spot. They are code-snapped
 *   every tick to body-relative offsets rotated by the
 *   mooshroom's yaw (back 0.55 behind at height 1.30, flanks
 *   0.62 to either side at height 1.05, shoulders 0.45 to
 *   either side at height 1.45), gliding on their 2 tick
 *   teleport duration: displays are snapped in code rather
 *   than made true passengers because a display passenger's
 *   offset does not inherit vehicle facing (the Obsidian
 *   Crust / Blackstone Bulwark precedent; the design's
 *   "riding as passengers with interpolation" is carried by
 *   the snap plus glide).
 * - The plating, for the duration: an ARMOR add_value
 *   modifier of +12.0 (modifier id
 *   advancedmobs:fungal_plate_armor; 12 armor points is 60
 *   percent of a full diamond set, so the soft grazer takes
 *   hits like a plated beast) and an ARMOR_TOUGHNESS
 *   add_value modifier of +4.0 (modifier id
 *   advancedmobs:fungal_plate_toughness; half a diamond
 *   set's toughness, so heavy hits keep most of their
 *   reduction). Both are removed exactly in end().
 * - The loose chunks: the mooshroom's health is polled every
 *   2 ticks; a detected drop, at most once per 10 ticks,
 *   knocks a chunk loose: 8 BLOCK particles of the red
 *   mushroom block state at the mooshroom's body plus a soft
 *   MUD_HIT thud (volume 0.7, pitch 0.9). The plates
 *   themselves do not break away; the chunks are shed
 *   fungus, and the armor holds for the full duration.
 * - Drift: 2 SPORE_BLOSSOM_AIR motes every 20 ticks drift
 *   off the plates.
 *
 * WHAT IT LOOKS LIKE: red mushroom slabs sprouting one by
 * one across a mooshroom's back and flanks until it reads
 * as a small walking fortress, chunks of red fungus
 * spraying off it with every blow it takes.
 *
 * PERFORMANCE: per tick the work is at most 5 UUID resolves
 * and 5 snapTo writes for the plates, plus one health read
 * every 2 ticks; the chunk answer is one particle burst at
 * most once per 10 ticks. No entity scans. Particle budget
 * per activation: sprouts 5 times 6 = 30, chunks at most 30
 * bursts times 8 = 240, drift 15 pulses times 2 = 30, total
 * at most 300 particles. The 5 displays are the only extra
 * entities, alive only for the ability's 15 seconds.
 *
 * CLEANUP: end() removes both modifiers and discards every
 * plate display that still resolves. If the caster dies
 * mid-armor, the framework termination path runs this same
 * end(), so the plates never outlive the ability or its
 * caster, and a killed mooshroom drops no display.
 *
 * TERRAIN: the plates are display entities, not blocks;
 * the gate does not apply.
 *
 * State tag keys: "plates" (list of compounds, one per
 * plate: "id", the display's UUID; "slot", 0 back, 1 left
 * flank, 2 right flank, 3 left shoulder, 4 right shoulder),
 * "sprouted" (how many plates have sprouted so far, 0 to
 * 5), "last_health" (the health seen at the last poll, as a
 * float), "last_chunk" (tick of the last loose chunk).
 */
public final class FungalPlateArmorAbility implements MobAbility {

    static final int DURATION_TICKS = 300;
    private static final int PLATE_COUNT = 5;
    private static final int SPROUT_INTERVAL = 4;
    private static final int HEALTH_POLL_INTERVAL = 2;
    private static final int CHUNK_COOLDOWN = 10;
    private static final Identifier ARMOR_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "fungal_plate_armor");
    private static final Identifier TOUGHNESS_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "fungal_plate_toughness");
    private static final BlockParticleOption RED_MUSHROOM_CHUNK =
        new BlockParticleOption(ParticleTypes.BLOCK, Blocks.RED_MUSHROOM_BLOCK.defaultBlockState());
    // Per slot: scale, side offset (along the right vector), back offset (along the forward vector), height.
    private static final float[] SCALES = {1.05f, 0.9f, 0.9f, 0.75f, 0.75f};
    private static final double[][] OFFSETS = {
        {0.0, -0.55, 1.30},
        {-0.62, 0.0, 1.05},
        {0.62, 0.0, 1.05},
        {-0.45, 0.25, 1.45},
        {0.45, 0.25, 1.45}
    };

    @Override
    public String id() {
        return "fungal_plate_armor";
    }

    @Override
    public String displayName() {
        return "Fungal Plate Armor";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        state.put("plates", new ListTag());
        state.putInt("sprouted", 0);
        state.putFloat("last_health", mob.getHealth());
        state.putInt("last_chunk", -CHUNK_COOLDOWN);

        // The plating.
        MooshroomAbilitySupport.addModifier(mob, Attributes.ARMOR,
            new AttributeModifier(ARMOR_ID, 12.0, AttributeModifier.Operation.ADD_VALUE));
        MooshroomAbilitySupport.addModifier(mob, Attributes.ARMOR_TOUGHNESS,
            new AttributeModifier(TOUGHNESS_ID, 4.0, AttributeModifier.Operation.ADD_VALUE));

        // The first plate sprouts immediately; the rest follow
        // over the next 16 ticks (see the class comment).
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            sproutPlate(ctx, level, 0);
        }
    }

    /** Sprouts the plate for the given slot at its body-relative spot and records it. */
    private void sproutPlate(AbilityContext ctx, ServerLevel level, int slot) {
        Entity plate = MooshroomAbilitySupport.createBlockDisplay(level,
            Blocks.RED_MUSHROOM_BLOCK.defaultBlockState(), SCALES[slot]);
        if (plate == null) {
            ctx.state().putInt("sprouted", slot + 1);
            return;
        }
        positionPlate(ctx, plate, slot);
        level.addFreshEntity(plate);
        CompoundTag entry = new CompoundTag();
        entry.store("id", UUIDUtil.CODEC, plate.getUUID());
        entry.putInt("slot", slot);
        ListTag plates = ctx.state().getListOrEmpty("plates");
        plates.add(entry);
        ctx.state().put("plates", plates);
        ctx.state().putInt("sprouted", slot + 1);

        Mob mob = ctx.mob();
        level.sendParticles(ParticleTypes.MYCELIUM, mob.getX(), mob.getY() + 1.2, mob.getZ(),
            6, 0.6, 0.5, 0.6, 0.05);
    }

    /**
     * Places one plate on its body-relative bearing: the
     * offsets rotate with the mooshroom's yaw, so the plates
     * turn with it. Minecraft yaw 0 faces south (+z); the
     * forward vector is (-sin, cos) and the right vector is
     * (-cos, -sin) in xz. Offset rows are (right, forward,
     * height).
     */
    private void positionPlate(AbilityContext ctx, Entity plate, int slot) {
        Mob mob = ctx.mob();
        double yawRad = Math.toRadians(mob.getYRot());
        double forwardX = -Math.sin(yawRad);
        double forwardZ = Math.cos(yawRad);
        double rightX = -Math.cos(yawRad);
        double rightZ = -Math.sin(yawRad);
        double[] offset = OFFSETS[slot];
        double x = mob.getX() + rightX * offset[0] + forwardX * offset[1];
        double z = mob.getZ() + rightZ * offset[0] + forwardZ * offset[1];
        double y = mob.getY() + offset[2];
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
        Mob mob = ctx.mob();

        // The grow-in: one more plate every 4 ticks until all
        // 5 stand.
        int sprouted = state.getIntOr("sprouted", 0);
        if (sprouted < PLATE_COUNT && ticks >= sprouted * SPROUT_INTERVAL) {
            sproutPlate(ctx, level, sprouted);
            sprouted++;
        }

        // The plates travel and turn with the mooshroom: every
        // plate is repositioned every tick.
        ListTag plates = state.getListOrEmpty("plates");
        for (int i = 0; i < plates.size(); i++) {
            CompoundTag entry = plates.getCompoundOrEmpty(i);
            Entity plate = resolvePlate(level, entry);
            if (plate != null) {
                positionPlate(ctx, plate, entry.getIntOr("slot", 0));
            }
        }

        // The loose chunks: a fresh hit knocks fungus off the
        // plating.
        if (ticks % HEALTH_POLL_INTERVAL == 0) {
            float health = mob.getHealth();
            float lastHealth = state.getFloatOr("last_health", health);
            if (health < lastHealth && ticks - state.getIntOr("last_chunk", 0) >= CHUNK_COOLDOWN) {
                level.sendParticles(RED_MUSHROOM_CHUNK, mob.getX(), mob.getY() + 1.1, mob.getZ(),
                    8, 0.6, 0.5, 0.6, 0.08);
                level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                    SoundEvents.MUD_HIT, SoundSource.HOSTILE, 0.7f, 0.9f);
                state.putInt("last_chunk", ticks);
            }
            state.putFloat("last_health", health);
        }

        if (ticks % 20 == 0) {
            level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, mob.getX(), mob.getY() + 1.6, mob.getZ(),
                2, 0.6, 0.4, 0.6, 0.02);
        }
    }

    /** Resolves a tracked plate display from its list entry, or null when gone. */
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
        MooshroomAbilitySupport.removeModifier(mob, Attributes.ARMOR, ARMOR_ID);
        MooshroomAbilitySupport.removeModifier(mob, Attributes.ARMOR_TOUGHNESS, TOUGHNESS_ID);

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
