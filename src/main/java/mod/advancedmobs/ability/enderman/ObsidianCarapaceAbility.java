package mod.advancedmobs.ability.enderman;

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
 * Obsidian Carapace (Enderman ability 2 of 5, defense).
 *
 * WHAT IT DOES: shards of obsidian fold out of the void and
 * lock into a shell of plates slowly orbiting the enderman,
 * REVERSE_PORTAL wisps leaking between the plates. While the
 * shell holds, the enderman shrugs off damage with RESISTANCE
 * and explosions cannot move it at all: blasts and heavy hits
 * wash over the plates.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists within 14 blocks
 *   (distance squared 196). The carapace answers a fight that
 *   is already close, not one on the horizon.
 * - Duration: exactly 240 ticks (12 seconds) from start().
 * - The plates: 5 block displays of obsidian, uniform scale
 *   0.7, at FULL brightness (block and sky light 15, set by
 *   the display helper, so the shell glows violet-dark in any
 *   light, per the design), each tilted 0.3 radians about its
 *   local Z axis (alternating sign per plate, so the shell
 *   reads as locked shards, not a fence). They orbit at
 *   radius 1.05 around the enderman at height 1.5 above its
 *   feet, one full orbit every 160 ticks (8 seconds), and are
 *   repositioned onto the enderman every tick: the shell
 *   travels and turns with it. They are pure visuals: displays
 *   have no hitbox and block nothing themselves; the defense
 *   is the effect and attribute below.
 * - The hardening, for the duration: RESISTANCE I (amplifier
 *   0, 240 ticks, applied in start() and removed in end(); a
 *   fifth of all damage gone) and an
 *   EXPLOSION_KNOCKBACK_RESISTANCE add_value modifier of +1.0
 *   (modifier id advancedmobs:obsidian_carapace_blast_wall;
 *   the attribute's whole range is 0 to 1, so +1.0 is total:
 *   explosion knockback is reduced to nothing and the
 *   enderman stands in the blast unmoved, per the design).
 *   The modifier is removed in end().
 * - The wisps: every 10 ticks, 3 REVERSE_PORTAL particles
 *   leak off the shell at the enderman's mid-height.
 *
 * TERRAIN RULE: this ability changes NO real blocks (the
 * plates are displays), so the mobGriefing gate does not
 * apply. See IMPLEMENTATION-RULES.md rule 6.
 *
 * WHAT IT LOOKS AND SOUNDS LIKE: five glowing obsidian plates
 * folding out of a burst of 12 REVERSE_PORTAL wisps with an
 * ENDERMAN_TELEPORT fold (volume 0.8, pitch 0.5), then a slow
 * rotating shell leaking violet wisps for 12 seconds.
 *
 * PERFORMANCE: per tick the work is 5 UUID resolves and 5
 * snapTo writes for the plates, nothing else; the wisps run
 * every 10 ticks. No entity scans, no allocations in the tick
 * path beyond the resolved references. Particle budget per
 * activation: 12 at start, wisps at most 24 pulses times 3 =
 * 72, total at most 84 particles. The 5 displays are the only
 * extra entities, alive only for the ability's 12 seconds.
 *
 * CLEANUP: end() removes the attribute modifier and the
 * RESISTANCE effect and discards every plate display that
 * still resolves. If the caster dies mid-carapace, the
 * framework termination path runs this same end(), so the
 * shell never outlives the ability or its caster.
 *
 * State tag keys: "plates" (list of compounds, one per plate:
 * "id", the display's UUID).
 */
public final class ObsidianCarapaceAbility implements MobAbility {

    static final int DURATION_TICKS = 240;
    private static final int PLATE_COUNT = 5;
    private static final double PLATE_RADIUS = 1.05;
    private static final double PLATE_HEIGHT = 1.5;
    private static final float PLATE_SCALE = 0.7f;
    private static final double ORBIT_PERIOD_TICKS = 160.0;
    private static final Identifier BLAST_WALL_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "obsidian_carapace_blast_wall");

    @Override
    public String id() {
        return "obsidian_carapace";
    }

    @Override
    public String displayName() {
        return "Obsidian Carapace";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();

        // The hardening.
        mob.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, DURATION_TICKS, 0));
        AttributeInstance blastWall = mob.getAttribute(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE);
        if (blastWall != null) {
            blastWall.addOrUpdateTransientModifier(
                new AttributeModifier(BLAST_WALL_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        }

        // The plates fold out of the void.
        ListTag plates = new ListTag();
        if (level != null) {
            for (int i = 0; i < PLATE_COUNT; i++) {
                // Tilt about the local Z axis by +/- 0.3 radians,
                // alternating per plate, as a quaternion (x, y, z, w).
                double tilt = (i % 2 == 0 ? 0.3 : -0.3) / 2.0;
                Entity plate = EndermanAbilitySupport.createBlockDisplay(level,
                    Blocks.OBSIDIAN.defaultBlockState(), PLATE_SCALE, PLATE_SCALE, PLATE_SCALE, true,
                    0.0f, 0.0f, (float) Math.sin(tilt), (float) Math.cos(tilt));
                if (plate == null) {
                    continue;
                }
                positionPlate(ctx, plate, i, 0);
                level.addFreshEntity(plate);
                CompoundTag entry = new CompoundTag();
                entry.store("id", UUIDUtil.CODEC, plate.getUUID());
                plates.add(entry);
            }
            level.sendParticles(ParticleTypes.REVERSE_PORTAL, mob.getX(), mob.getY() + 1.5, mob.getZ(),
                12, 0.8, 0.8, 0.8, 0.1);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 0.8f, 0.5f);
        }
        state.put("plates", plates);
    }

    /** Places one plate on its orbiting bearing around the enderman for the given tick. */
    private void positionPlate(AbilityContext ctx, Entity plate, int index, int ticks) {
        Mob mob = ctx.mob();
        double angle = index * (Math.PI * 2.0 / PLATE_COUNT)
            + ticks * (Math.PI * 2.0 / ORBIT_PERIOD_TICKS);
        double x = mob.getX() + Math.cos(angle) * PLATE_RADIUS;
        double z = mob.getZ() + Math.sin(angle) * PLATE_RADIUS;
        plate.snapTo(x, mob.getY() + PLATE_HEIGHT, z, (float) Math.toDegrees(-angle), 0.0f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        ListTag plates = ctx.state().getListOrEmpty("plates");

        // The shell travels and turns with the enderman: every
        // plate is repositioned every tick.
        for (int i = 0; i < plates.size(); i++) {
            Entity plate = resolvePlate(level, plates.getCompoundOrEmpty(i));
            if (plate != null) {
                positionPlate(ctx, plate, i, ticks);
            }
        }

        if (ticks % 10 == 0) {
            Mob mob = ctx.mob();
            level.sendParticles(ParticleTypes.REVERSE_PORTAL, mob.getX(), mob.getY() + 1.5, mob.getZ(),
                3, 0.8, 0.7, 0.8, 0.05);
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
        mob.removeEffect(MobEffects.RESISTANCE);
        AttributeInstance blastWall = mob.getAttribute(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE);
        if (blastWall != null) {
            blastWall.removeModifier(BLAST_WALL_ID);
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
