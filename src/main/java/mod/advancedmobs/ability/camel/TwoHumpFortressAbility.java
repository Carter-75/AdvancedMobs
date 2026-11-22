package mod.advancedmobs.ability.camel;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.BlockParticleOption;
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
import net.minecraft.world.entity.animal.camel.Camel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Two Hump Fortress (Camel ability 2 of 5, defense).
 *
 * WHAT IT DOES: the camel kneels into a living dune.
 * Sandstone crests rise between its humps, it swells a size
 * step larger, its hide turns to armor plate, and it plants
 * where it kneels: a wall of a beast that ranged mobs break
 * against, too heavy to shove aside.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the camel's current target within 12 blocks (distance
 *   squared 144).
 * - The kneel (start()): if the caster is a Camel that is
 *   not already sitting, it calls the vanilla sitDown()
 *   (the real camel kneel pose) and records that in the
 *   state tag, so end() only stands up a camel this ability
 *   actually sat down. CAMEL_SIT plays at volume 1.0.
 * - The brace (start(), all removed exactly in end()):
 *   five transient attribute modifiers under fixed ids:
 *   SCALE +0.30 ADD_MULTIPLIED_BASE (1.3x, the design's size
 *   step), ARMOR +10.0 ADD_VALUE and ARMOR_TOUGHNESS +4.0
 *   ADD_VALUE (the design's armor line), KNOCKBACK_
 *   RESISTANCE +1.0 ADD_VALUE (the attribute clamps at total
 *   resistance, so no hit can move the camel at all), and
 *   MOVEMENT_SPEED -1.0 ADD_MULTIPLIED_TOTAL (speed
 *   multiplied to exactly 0: the fortress is planted, held
 *   additionally by per-tick navigation stops, never NoAi).
 *   RESISTANCE I is applied for 320 ticks and removed in
 *   end().
 * - The crests (start()): three sandstone block displays at
 *   scale 1.0 (the design's block_display passengers,
 *   carried as code-snapped displays rather than true
 *   passengers, per the Obsidian Crust / Pack Fortress
 *   precedent: a display passenger's offset does not inherit
 *   vehicle facing, so code snapping is what keeps the
 *   crests square on the back). Their UUIDs are stored in
 *   the state tag. A burst of 10 FALLING_DUST motes carrying
 *   the sandstone state sifts down over the kneeling camel
 *   as the dune settles.
 * - The ride (tick(), every tick): navigation is stopped,
 *   and each surviving display is snapped to a spine anchor:
 *   the camel's position raised to 0.66 of its bounding box
 *   height, offset along its look axis by +0.6 (front hump),
 *   0.0 (the crest, raised a further 0.35), and -0.6 (rear
 *   hump), so the sandstone ridge runs between and over the
 *   two humps. A display that is somehow gone is simply
 *   skipped; the fortress does not depend on the visuals.
 *   Every 10 ticks, 3 more FALLING_DUST motes sift off the
 *   crest.
 * - Duration: exactly 300 ticks (15 seconds), hard finish.
 * - end(): all five modifiers and the RESISTANCE effect are
 *   removed, the displays are discarded, and the camel
 *   stands back up (standUp(), with CAMEL_STAND at volume
 *   1.0) if this ability sat it down. Idempotent: safe when
 *   start() never created anything.
 *
 * WHAT IT LOOKS LIKE: the camel folds its long legs under
 * itself with a creaking sit sound, sand sifts down as a
 * sandstone ridge crests along its back, and the whole
 * animal is suddenly a third bigger: a kneeling dune with
 * eyes, and arrows just thock into it.
 *
 * PERFORMANCE: start() is five modifier writes, one effect,
 * one pose call, and three display spawns, once. tick() is
 * one navigation stop, at most three display resolves and
 * snaps, and a 3-particle emission every 10 ticks; there
 * are no entity queries anywhere in the ability. Particle
 * budget per activation: 10 at start plus 30 pulses times
 * 3 = 90, total at most 100 particles.
 *
 * TERRAIN: this ability changes no blocks; the crests are
 * display entities, not placed blocks, so the mobGriefing
 * gate does not apply.
 *
 * State tag keys: "crests" (list of compounds, one per
 * display: "id", the display's UUID), "sat" (whether this
 * activation sat the camel down).
 */
public final class TwoHumpFortressAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final float CREST_SCALE = 1.0f;
    private static final BlockParticleOption SANDSTONE_DUST =
        new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.SANDSTONE.defaultBlockState());
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "two_hump_fortress_scale");
    private static final Identifier ARMOR_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "two_hump_fortress_armor");
    private static final Identifier TOUGHNESS_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "two_hump_fortress_toughness");
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "two_hump_fortress_knockback");
    private static final Identifier SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "two_hump_fortress_speed");

    @Override
    public String id() {
        return "two_hump_fortress";
    }

    @Override
    public String displayName() {
        return "Two Hump Fortress";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().put("crests", new ListTag());
        Mob caster = ctx.mob();

        // The kneel: the vanilla sitting pose, recorded so
        // end() only stands up a camel this ability sat down.
        boolean sat = false;
        if (caster instanceof Camel camel && !camel.isCamelSitting()) {
            camel.sitDown();
            sat = true;
        }
        ctx.state().putBoolean("sat", sat);

        // The brace: bigger, plated, immovable, planted.
        CamelAbilitySupport.addModifier(caster, Attributes.SCALE,
            new AttributeModifier(SCALE_ID, 0.30, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        CamelAbilitySupport.addModifier(caster, Attributes.ARMOR,
            new AttributeModifier(ARMOR_ID, 10.0, AttributeModifier.Operation.ADD_VALUE));
        CamelAbilitySupport.addModifier(caster, Attributes.ARMOR_TOUGHNESS,
            new AttributeModifier(TOUGHNESS_ID, 4.0, AttributeModifier.Operation.ADD_VALUE));
        CamelAbilitySupport.addModifier(caster, Attributes.KNOCKBACK_RESISTANCE,
            new AttributeModifier(KNOCKBACK_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        CamelAbilitySupport.addModifier(caster, Attributes.MOVEMENT_SPEED,
            new AttributeModifier(SPEED_ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        caster.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, DURATION_TICKS + 20, 0));

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.CAMEL_SIT,
            SoundSource.HOSTILE, 1.0f, 1.0f);
        // The crests: three sandstone displays settle along the spine.
        for (int i = 0; i < 3; i++) {
            Entity crest = CamelAbilitySupport.createBlockDisplay(level,
                Blocks.SANDSTONE.defaultBlockState(), CREST_SCALE);
            if (crest == null) {
                continue;
            }
            crest.snapTo(caster.getX(), spineY(ctx, i), caster.getZ());
            level.addFreshEntity(crest);
            CamelAbilitySupport.trackEntity(ctx.state(), "crests", crest);
        }
        level.sendParticles(SANDSTONE_DUST, caster.getX(), caster.getY() + 2.0, caster.getZ(),
            10, 0.7, 0.4, 0.7, 0.05);
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
        // The ride: keep the crests square along the spine.
        ListTag crests = ctx.state().getListOrEmpty("crests");
        for (int i = 0; i < crests.size(); i++) {
            Entity crest = CamelAbilitySupport.resolveTracked(level, crests.getCompoundOrEmpty(i));
            if (crest == null) {
                continue;
            }
            Vec3 look = caster.getLookAngle();
            double along = i == 0 ? 0.6 : i == 1 ? 0.0 : -0.6;
            crest.snapTo(
                caster.getX() + look.x * along,
                spineY(ctx, i),
                caster.getZ() + look.z * along);
        }
        if (ctx.ticksActive() % 10 == 0) {
            level.sendParticles(SANDSTONE_DUST, caster.getX(), caster.getY() + 2.0, caster.getZ(),
                3, 0.7, 0.3, 0.7, 0.04);
        }
    }

    /** The height of a spine anchor: two thirds up the body, the middle crest raised further. */
    private static double spineY(AbilityContext ctx, int index) {
        Mob caster = ctx.mob();
        double y = caster.getY() + caster.getBbHeight() * 0.66;
        return index == 1 ? y + 0.35 : y;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CamelAbilitySupport.removeModifier(caster, Attributes.SCALE, SCALE_ID);
        CamelAbilitySupport.removeModifier(caster, Attributes.ARMOR, ARMOR_ID);
        CamelAbilitySupport.removeModifier(caster, Attributes.ARMOR_TOUGHNESS, TOUGHNESS_ID);
        CamelAbilitySupport.removeModifier(caster, Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_ID);
        CamelAbilitySupport.removeModifier(caster, Attributes.MOVEMENT_SPEED, SPEED_ID);
        caster.removeEffect(MobEffects.RESISTANCE);

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            ListTag crests = ctx.state().getListOrEmpty("crests");
            for (int i = 0; i < crests.size(); i++) {
                Entity crest = CamelAbilitySupport.resolveTracked(level, crests.getCompoundOrEmpty(i));
                if (crest != null) {
                    crest.discard();
                }
            }
        }
        if (ctx.state().getBooleanOr("sat", false) && caster instanceof Camel camel) {
            camel.standUp();
            if (level != null) {
                level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.CAMEL_STAND,
                    SoundSource.HOSTILE, 1.0f, 1.0f);
            }
        }
    }
}
