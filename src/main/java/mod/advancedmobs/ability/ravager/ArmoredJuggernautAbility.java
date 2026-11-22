package mod.advancedmobs.ability.ravager;

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
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Armored Juggernaut (Ravager ability 2 of 5, transformation).
 *
 * WHAT IT DOES: iron plate closes over the beast. The ravager
 * grows by a third, six iron block display plates ride across
 * its back, flanks, and shoulders at full brightness, and its
 * armor climbs until ordinary hits ring off it like rain. Its
 * knockback resistance becomes nearly total: the juggernaut
 * does not stop and does not flinch. The price is weight: it
 * moves a tenth slower and turns only as fast as vanilla lets
 * it. Under sustained fire the plates crack and fall away one
 * by one, each loss visibly stripping armor, until the last
 * plate goes and the form collapses early.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the ravager's current target within 14 blocks (distance
 *   squared 196).
 * - Duration: 400 ticks (20 seconds), ending early the moment
 *   the last plate is shot away (see below).
 * - The growth and the hide (all transient modifiers, applied
 *   in start() and removed in end()): SCALE +0.34
 *   add_multiplied_base (1.34 times birth size, the design's
 *   "grows by a third", id advancedmobs:
 *   armored_juggernaut_scale); KNOCKBACK_RESISTANCE +0.9
 *   add_value (id advancedmobs:armored_juggernaut_knockback;
 *   the attribute clamps at 1.0, so with the ravager's base
 *   resistance the total is effectively total); MOVEMENT_
 *   SPEED -0.10 add_multiplied_base (id advancedmobs:
 *   armored_juggernaut_speed, the weight of the plate).
 * - The plates: 6 iron block displays (scale 0.95) created in
 *   start() through the shared factory (full brightness,
 *   teleport duration 2), added to the level, and snapped
 *   every tick to anchors fixed to the ravager's body: back
 *   center, both flanks, both shoulders, and the rump. Anchor
 *   math uses the live look direction as forward and its
 *   perpendicular as the side axis, with heights read off the
 *   live bounding box (which already includes the growth, so
 *   the plates ride the grown beast). The design calls the
 *   plates passengers; they are separate displays snapped in
 *   code instead, the Obsidian Crust and Blackstone Bulwark
 *   Form pattern, because a display passenger's model offset
 *   does not inherit the vehicle's facing and would drift off
 *   the body whenever the ravager turns. Each plate's UUID
 *   and remaining hits are tracked in the state tag's
 *   "plates" list.
 * - Plate damage: the ravager's health is polled every 2
 *   ticks against the value stored at the last poll. Any
 *   drop counts as one hit on the first surviving plate
 *   (lowest index with hits left): a plate has 3 hits. At 0
 *   the plate shatters: the display is discarded in a burst
 *   of 12 iron BLOCK_CRUMBLE particles with an IRON_GOLEM_
 *   HURT clang at pitch 0.8, and the armor steps down. One
 *   poll can cost at most one hit, so a single huge blow
 *   never strips more than one hit of plate: sustained fire
 *   is what the design asks for. Healing between polls masks
 *   a hit (the stored value simply follows the health up);
 *   that is accepted and documented here.
 * - The armor the plates give: ARMOR add_value of +3.0 per
 *   surviving plate (id advancedmobs:armored_juggernaut_
 *   armor; 6 plates = +18, on top of the ravager's base 4,
 *   for 22 total at full plate) and ARMOR_TOUGHNESS add_value
 *   of +1.0 per surviving plate (id advancedmobs:
 *   armored_juggernaut_toughness; +6 at full plate). Both
 *   are refreshed through addOrUpdateTransientModifier every
 *   time a plate falls, and removed in end(). When the last
 *   plate falls the form ends early: isFinished() sees the
 *   "plates_alive" count reach 0 after having been above 0.
 *   If display creation failed at start (plates_alive 0 from
 *   the beginning), the form simply runs its full duration
 *   on the growth and knockback alone.
 *
 * WHAT IT LOOKS LIKE: six bright iron slabs riding the
 * beast's back and sides, gliding as it turns; 4 iron
 * BLOCK_CRUMBLE flecks shed at the body every 20 ticks while
 * any plate survives; each plate loss is a clang and a burst
 * of iron crumbs where the plate rode. No blocks are placed
 * or broken: the plates are display entities, and the
 * terrain rule does not apply (stated here per the standing
 * convention).
 *
 * PERFORMANCE: per tick at most 6 display resolves and
 * snapTo writes, one health compare every 2 ticks, 4
 * particles every 20 ticks. No entity queries at all.
 * Particle budget per activation: at most 6 shatters times
 * 12 = 72, plus 20 fleck pulses times 4 = 80: at most 152.
 *
 * CLEANUP: end() removes all five modifiers by id and
 * discards every surviving plate display. The framework's
 * termination path runs this same end() on death, so the
 * plates never outlive the beast.
 *
 * State tag keys: "plates" (list of compounds, one per
 * plate: "id" the display's UUID, "hits" its remaining
 * hits), "plates_alive" (surviving plate count), "had_
 * plates" (1 when at least one plate was created), "last_hp"
 * (the health value at the last damage poll).
 */
public final class ArmoredJuggernautAbility implements MobAbility {

    static final int DURATION_TICKS = 400;
    private static final int PLATE_COUNT = 6;
    private static final int PLATE_HITS = 3;
    private static final float PLATE_SCALE = 0.95f;
    private static final double ARMOR_PER_PLATE = 3.0;
    private static final double TOUGHNESS_PER_PLATE = 1.0;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "armored_juggernaut_scale");
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "armored_juggernaut_knockback");
    private static final Identifier SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "armored_juggernaut_speed");
    private static final Identifier ARMOR_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "armored_juggernaut_armor");
    private static final Identifier TOUGHNESS_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "armored_juggernaut_toughness");
    private static final BlockParticleOption IRON_CRUMBLE =
        new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, Blocks.IRON_BLOCK.defaultBlockState());
    // Plate anchors: {side offset, forward offset, height as a
    // fraction of the live bounding box height}. Index order is
    // the damage order: plates are shot away front to back.
    private static final double[][] ANCHORS = {
        {0.0, 0.85, 0.88},   // 0: shoulders center, takes fire first
        {-0.80, 0.80, 0.85}, // 1: left shoulder
        {0.80, 0.80, 0.85},  // 2: right shoulder
        {-1.10, 0.10, 0.62}, // 3: left flank
        {1.10, 0.10, 0.62},  // 4: right flank
        {0.0, -1.00, 0.80}   // 5: rump
    };

    @Override
    public String id() {
        return "armored_juggernaut";
    }

    @Override
    public String displayName() {
        return "Armored Juggernaut";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();

        // The growth, the flinch-proofing, and the weight.
        RavagerAbilitySupport.addModifier(mob, Attributes.SCALE,
            new AttributeModifier(SCALE_ID, 0.34, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        RavagerAbilitySupport.addModifier(mob, Attributes.KNOCKBACK_RESISTANCE,
            new AttributeModifier(KNOCKBACK_ID, 0.9, AttributeModifier.Operation.ADD_VALUE));
        RavagerAbilitySupport.addModifier(mob, Attributes.MOVEMENT_SPEED,
            new AttributeModifier(SPEED_ID, -0.10, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));

        // The plates themselves.
        ListTag plates = new ListTag();
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            for (int i = 0; i < PLATE_COUNT; i++) {
                Entity plate = RavagerAbilitySupport.createIronPlateDisplay(level, PLATE_SCALE);
                if (plate == null) {
                    continue;
                }
                plate.snapTo(mob.getX(), mob.getY() + mob.getBbHeight(), mob.getZ(), 0.0f, 0.0f);
                if (!level.addFreshEntity(plate)) {
                    continue;
                }
                CompoundTag entry = new CompoundTag();
                entry.store("id", UUIDUtil.CODEC, plate.getUUID());
                entry.putInt("hits", PLATE_HITS);
                plates.add(entry);
            }
        }
        state.put("plates", plates);
        state.putInt("plates_alive", plates.size());
        state.putInt("had_plates", plates.isEmpty() ? 0 : 1);
        state.putFloat("last_hp", mob.getHealth());
        refreshArmor(ctx, plates.size());

        if (level != null) {
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.IRON_GOLEM_HURT,
                SoundSource.HOSTILE, 0.8f, 0.6f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        ListTag plates = state.getListOrEmpty("plates");

        // Ride the plates on the body, every tick.
        Vec3 look = mob.getLookAngle();
        double sideX = -look.z;
        double sideZ = look.x;
        double height = mob.getBbHeight();
        for (int i = 0; i < plates.size(); i++) {
            CompoundTag entry = plates.getCompoundOrEmpty(i);
            if (entry.getIntOr("hits", 0) <= 0 || !entry.contains("id")) {
                continue;
            }
            UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
            Entity plate = RavagerAbilitySupport.resolve(level, id);
            if (plate == null) {
                continue;
            }
            double[] anchor = ANCHORS[i];
            plate.snapTo(mob.getX() + sideX * anchor[0] + look.x * anchor[1],
                         mob.getY() + height * anchor[2],
                         mob.getZ() + sideZ * anchor[0] + look.z * anchor[1],
                         mob.getYRot(), 0.0f);
        }

        // Plate damage: one health poll every 2 ticks, at most
        // one hit of plate per poll.
        if (ctx.ticksActive() % 2 == 0) {
            float hp = mob.getHealth();
            float last = state.getFloatOr("last_hp", hp);
            state.putFloat("last_hp", hp);
            if (hp < last) {
                damageFirstPlate(ctx, plates);
            }
        }

        // Shed iron flecks while any plate survives.
        if (ctx.ticksActive() % 20 == 0 && state.getIntOr("plates_alive", 0) > 0) {
            level.sendParticles(IRON_CRUMBLE, mob.getX(), mob.getY() + height * 0.8, mob.getZ(),
                4, 0.9, 0.4, 0.9, 0.05);
        }
    }

    /** Costs the first surviving plate one hit; shatters it at 0 and steps the armor down. */
    private void damageFirstPlate(AbilityContext ctx, ListTag plates) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        for (int i = 0; i < plates.size(); i++) {
            CompoundTag entry = plates.getCompoundOrEmpty(i);
            int hits = entry.getIntOr("hits", 0);
            if (hits <= 0) {
                continue;
            }
            hits--;
            entry.putInt("hits", hits);
            if (hits > 0) {
                return;
            }
            // The plate shatters: discard the display in a
            // burst of iron crumbs and step the armor down.
            UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
            Entity plate = RavagerAbilitySupport.resolve(level, id);
            if (plate != null) {
                level.sendParticles(IRON_CRUMBLE, plate.getX(), plate.getY(), plate.getZ(),
                    12, 0.4, 0.4, 0.4, 0.1);
                plate.discard();
            }
            level.playSound(null, ctx.mob().getX(), ctx.mob().getY(), ctx.mob().getZ(),
                SoundEvents.IRON_GOLEM_HURT, SoundSource.HOSTILE, 1.0f, 0.8f);
            int alive = ctx.state().getIntOr("plates_alive", 0) - 1;
            ctx.state().putInt("plates_alive", Math.max(0, alive));
            refreshArmor(ctx, Math.max(0, alive));
            return;
        }
    }

    /** Recomputes the plate armor modifiers for the surviving plate count. */
    private void refreshArmor(AbilityContext ctx, int platesAlive) {
        Mob mob = ctx.mob();
        RavagerAbilitySupport.addModifier(mob, Attributes.ARMOR,
            new AttributeModifier(ARMOR_ID, ARMOR_PER_PLATE * platesAlive, AttributeModifier.Operation.ADD_VALUE));
        RavagerAbilitySupport.addModifier(mob, Attributes.ARMOR_TOUGHNESS,
            new AttributeModifier(TOUGHNESS_ID, TOUGHNESS_PER_PLATE * platesAlive, AttributeModifier.Operation.ADD_VALUE));
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        if (state.getIntOr("had_plates", 0) == 1 && state.getIntOr("plates_alive", 0) <= 0) {
            return true; // Shot bare: the form collapses early.
        }
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        RavagerAbilitySupport.removeModifier(mob, Attributes.SCALE, SCALE_ID);
        RavagerAbilitySupport.removeModifier(mob, Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_ID);
        RavagerAbilitySupport.removeModifier(mob, Attributes.MOVEMENT_SPEED, SPEED_ID);
        RavagerAbilitySupport.removeModifier(mob, Attributes.ARMOR, ARMOR_ID);
        RavagerAbilitySupport.removeModifier(mob, Attributes.ARMOR_TOUGHNESS, TOUGHNESS_ID);

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag plates = ctx.state().getListOrEmpty("plates");
        for (int i = 0; i < plates.size(); i++) {
            CompoundTag entry = plates.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
            Entity plate = RavagerAbilitySupport.resolve(level, id);
            if (plate != null) {
                plate.discard();
            }
        }
    }
}
