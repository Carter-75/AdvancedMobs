package mod.advancedmobs.ability.donkey;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
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
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Pack Fortress (Donkey ability 2 of 5, defense).
 *
 * WHAT IT DOES: the donkey braces into a living barricade.
 * Two chest block displays ride on its back like a loaded
 * caravan wall, it swells a size step larger, its hide
 * turns to armor plate, and nothing can shove it aside: it
 * plants where it stands and enemies break against it.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the donkey's current target within 12 blocks (distance
 *   squared 144).
 * - The brace (start(), all removed exactly in end()):
 *   five transient attribute modifiers under fixed ids:
 *   SCALE +0.35 ADD_MULTIPLIED_BASE (1.35x, the design's
 *   size step), ARMOR +12.0 ADD_VALUE and ARMOR_TOUGHNESS
 *   +4.0 ADD_VALUE (the design's armor line), and
 *   KNOCKBACK_RESISTANCE +1.0 ADD_VALUE (the attribute
 *   clamps at total resistance, so no hit can move the
 *   donkey at all), and MOVEMENT_SPEED -1.0
 *   ADD_MULTIPLIED_TOTAL (speed multiplied to exactly 0:
 *   the barricade is planted, held additionally by per-tick
 *   navigation stops, never NoAi). RESISTANCE I is applied
 *   for 320 ticks and removed in end().
 * - The cargo wall (start()): two chest block displays at
 *   scale 0.9 (the design's block_display passengers,
 *   carried as code-snapped displays rather than true
 *   passengers, per the Obsidian Crust / Blackstone Bulwark
 *   precedent: a display passenger's offset does not
 *   inherit vehicle facing, so code snapping is what keeps
 *   the chests square on the back). Their UUIDs are stored
 *   in the state tag. The DONKEY_CHEST sound plays (volume
 *   1.0) as the straps take the weight, with 8 POOF puffs.
 * - The ride (tick(), every tick): navigation is stopped,
 *   and each surviving display is snapped to a back anchor:
 *   the donkey's position raised to 0.75 of its bounding
 *   box height, offset along its side axis (the horizontal
 *   perpendicular of its look direction) by plus or minus
 *   0.45 blocks and 0.05 blocks behind center, so the pair
 *   sits like panniers across the back. A display that is
 *   somehow gone is simply skipped; the fortress does not
 *   depend on the visuals.
 * - Duration: exactly 300 ticks (15 seconds), hard finish.
 * - end(): all five modifiers and the RESISTANCE effect
 *   are removed, and both displays are discarded.
 *   Idempotent: safe when start() never created them.
 *
 * WHAT IT LOOKS LIKE: the chest straps creak, two loaded
 * chests settle onto a suddenly much bigger donkey, and it
 * stands there like a wall segment that wandered into the
 * fight: hits land, nothing moves.
 *
 * PERFORMANCE: start() is five modifier writes, one effect,
 * and two display spawns, once. tick() is one navigation
 * stop and at most two display resolves and snaps; there
 * are no entity queries anywhere in the ability. Particle
 * budget per activation: 8 at start, total 8 particles.
 *
 * TERRAIN: this ability changes no blocks; the chests are
 * display entities, not placed blocks, so the mobGriefing
 * gate does not apply.
 *
 * State tag keys: "chests" (list of compounds, one per
 * display: "id", the display's UUID).
 */
public final class PackFortressAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final float CHEST_SCALE = 0.9f;
    private static final double CHEST_SIDE_OFFSET = 0.45;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "pack_fortress_scale");
    private static final Identifier ARMOR_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "pack_fortress_armor");
    private static final Identifier TOUGHNESS_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "pack_fortress_toughness");
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "pack_fortress_knockback");
    private static final Identifier SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "pack_fortress_speed");

    @Override
    public String id() {
        return "pack_fortress";
    }

    @Override
    public String displayName() {
        return "Pack Fortress";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().put("chests", new ListTag());
        Mob caster = ctx.mob();
        // The brace: bigger, plated, immovable, planted.
        DonkeyAbilitySupport.addModifier(caster, Attributes.SCALE,
            new AttributeModifier(SCALE_ID, 0.35, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        DonkeyAbilitySupport.addModifier(caster, Attributes.ARMOR,
            new AttributeModifier(ARMOR_ID, 12.0, AttributeModifier.Operation.ADD_VALUE));
        DonkeyAbilitySupport.addModifier(caster, Attributes.ARMOR_TOUGHNESS,
            new AttributeModifier(TOUGHNESS_ID, 4.0, AttributeModifier.Operation.ADD_VALUE));
        DonkeyAbilitySupport.addModifier(caster, Attributes.KNOCKBACK_RESISTANCE,
            new AttributeModifier(KNOCKBACK_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        DonkeyAbilitySupport.addModifier(caster, Attributes.MOVEMENT_SPEED,
            new AttributeModifier(SPEED_ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        caster.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, DURATION_TICKS + 20, 0));

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        // The cargo wall: two chest displays settle onto the back.
        for (int i = 0; i < 2; i++) {
            Entity chest = DonkeyAbilitySupport.createBlockDisplay(level,
                Blocks.CHEST.defaultBlockState(), CHEST_SCALE);
            if (chest == null) {
                continue;
            }
            chest.snapTo(caster.getX(), backY(caster), caster.getZ());
            level.addFreshEntity(chest);
            DonkeyAbilitySupport.trackEntity(ctx.state(), "chests", chest);
        }
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.DONKEY_CHEST,
            SoundSource.HOSTILE, 1.0f, 1.0f);
        level.sendParticles(ParticleTypes.POOF, caster.getX(), caster.getY() + 1.0, caster.getZ(),
            8, 0.5, 0.5, 0.5, 0.05);
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        // Planted: hold position through navigation stops,
        // never NoAi (see the class comment).
        caster.getNavigation().stop();

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        // The ride: keep the chests square on the back.
        Vec3 look = caster.getLookAngle();
        double sideX = -look.z;
        double sideZ = look.x;
        double sideLength = Math.sqrt(sideX * sideX + sideZ * sideZ);
        if (sideLength < 1.0e-4) {
            sideX = 1.0;
            sideZ = 0.0;
            sideLength = 1.0;
        }
        sideX /= sideLength;
        sideZ /= sideLength;
        ListTag chests = ctx.state().getListOrEmpty("chests");
        for (int i = 0; i < chests.size(); i++) {
            Entity chest = DonkeyAbilitySupport.resolveTracked(level, chests.getCompoundOrEmpty(i));
            if (chest == null) {
                continue;
            }
            double sign = i == 0 ? 1.0 : -1.0;
            chest.snapTo(
                caster.getX() + sideX * CHEST_SIDE_OFFSET * sign - look.x * 0.05,
                backY(caster),
                caster.getZ() + sideZ * CHEST_SIDE_OFFSET * sign - look.z * 0.05);
        }
    }

    /** The height of the back anchors: three quarters of the donkey's bounding box height. */
    private static double backY(Mob caster) {
        return caster.getY() + caster.getBbHeight() * 0.75;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        DonkeyAbilitySupport.removeModifier(caster, Attributes.SCALE, SCALE_ID);
        DonkeyAbilitySupport.removeModifier(caster, Attributes.ARMOR, ARMOR_ID);
        DonkeyAbilitySupport.removeModifier(caster, Attributes.ARMOR_TOUGHNESS, TOUGHNESS_ID);
        DonkeyAbilitySupport.removeModifier(caster, Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_ID);
        DonkeyAbilitySupport.removeModifier(caster, Attributes.MOVEMENT_SPEED, SPEED_ID);
        caster.removeEffect(MobEffects.RESISTANCE);

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag chests = ctx.state().getListOrEmpty("chests");
        for (int i = 0; i < chests.size(); i++) {
            Entity chest = DonkeyAbilitySupport.resolveTracked(level, chests.getCompoundOrEmpty(i));
            if (chest != null) {
                chest.discard();
            }
        }
    }
}
