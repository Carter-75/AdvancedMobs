package mod.advancedmobs.ability.sulfurcube;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Hot Archetype (Sulfur Cube ability 2 of 5, transformation).
 *
 * WHAT IT DOES: the cube absorbs a lava flavored archetype
 * for 15 seconds. Its goo turns bright orange (the absorbed
 * magma block shows through its translucent body), FLAME and
 * LAVA drip off it, a constant shimmer of heat rises off its
 * back, and it leaves a short trail of fire flecks as it hops.
 * In this form, simple contact with the cube burns enemies.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the cube's current target within 10 blocks (distance
 *   squared 100).
 * - Duration: exactly 300 ticks (15 seconds) from start().
 * - The archetype (start(), the heart of the ability): this
 *   uses the REAL 26.2 sulfur cube archetype registry, not a
 *   reimplementation. A sulfur cube's archetype is selected by
 *   the block item it has absorbed in its BODY equipment slot
 *   (vanilla reapplies archetypes from that slot through the
 *   equipment change path). start() stores the cube's current
 *   body item in the state tag (ItemStack codec, the Gilded
 *   Plate Swap pattern) and puts a MAGMA BLOCK item in the
 *   slot: the magma block is the one item in the vanilla
 *   sulfur_cube_archetype/hot item tag, so vanilla selects the
 *   HOT archetype (SulfurCubeArchetypes.HOT). From that
 *   moment, vanilla itself applies the archetype's attribute
 *   modifiers and its contact damage: any enemy the cube
 *   touches takes damage of type minecraft:sulfur_cube_hot
 *   (DamageTypes.SULFUR_CUBE_HOT) and is set burning, with the
 *   burn duration on each victim scaled by that victim's
 *   BURNING_TIME attribute, all by vanilla rules. The ability
 *   code adds no contact damage of its own; if the burn feels
 *   wrong, the archetype data is where the numbers live.
 * - Fire immunity for the form: the cube gains FIRE_RESISTANCE
 *   at amplifier 0 for 320 ticks in start() (a lava flavored
 *   body does not burn itself), removed in end(). (A cube that
 *   somehow had FIRE_RESISTANCE from another source would lose
 *   it at end(), the accepted edge case shared with every
 *   fixed-duration effect in this system.)
 * - end(): the body slot comes back. If the slot still holds
 *   our magma block, or is empty, the stored original item is
 *   restored (or the slot cleared when there was none), which
 *   makes vanilla drop the HOT archetype and reapply whatever
 *   the original item selects. If the slot holds some foreign
 *   item (the cube absorbed something else mid-form, which its
 *   own AI does not do while it carries a block), the cube
 *   keeps its find and the stored original is discarded, the
 *   Gilded Plate Swap rule. Because the framework runs end()
 *   at the head of death through termination, a cube killed
 *   mid-form drops its original item, never the ability's
 *   magma block.
 * - A caster that is somehow not a SulfurCube (the provider
 *   only serves sulfur cubes, so this is a belt-and-braces
 *   guard) skips the slot swap and just runs the visuals.
 * - No blocks are placed or broken anywhere in this ability,
 *   so the mobGriefing terrain gate does not apply.
 *
 * WHAT IT LOOKS LIKE: a SULFUR_CUBE_ABSORB gulp at pitch 0.8
 * as the magma block goes in and the cube's core turns molten
 * orange; then 2 FLAME motes curling off its body every 2
 * ticks, 1 LAVA drip every 10 ticks, and 1 SMALL_FLAME fleck
 * dropped at its feet every 4 ticks while it is actually
 * moving, the short fire trail of the design.
 *
 * PERFORMANCE: tick() is three interval pulses and a movement
 * read; no entity queries at all (vanilla runs the contact
 * damage). The slot swap happens once in start() and once in
 * end(). Particle budget per activation, hard caps by
 * construction: start burst 10, FLAME 150 pulses times 2 =
 * 300, LAVA 30, trail at most 75, total at most 415 particles.
 *
 * State tag keys: "saved_body" (the stored body item stack,
 * absent when the slot was empty).
 */
public final class HotArchetypeAbility implements MobAbility {

    static final int DURATION_TICKS = 300;

    @Override
    public String id() {
        return "hot_archetype";
    }

    @Override
    public String displayName() {
        return "Hot Archetype";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 100.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        if (mob instanceof SulfurCube) {
            // The absorb: stash the real body item, slot the
            // magma block that selects the HOT archetype.
            SulfurCubeAbilitySupport.storeStack(ctx.state(), "saved_body",
                mob.getItemBySlot(EquipmentSlot.BODY), ctx.level().registryAccess());
            mob.setItemSlot(EquipmentSlot.BODY, new ItemStack(Items.MAGMA_BLOCK));
        }
        mob.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, DURATION_TICKS + 20, 0));
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.SULFUR_CUBE_ABSORB,
                SoundSource.HOSTILE, 1.0f, 0.8f);
            level.sendParticles(ParticleTypes.FLAME, mob.getX(), mob.getY() + 0.5, mob.getZ(),
                10, mob.getBbWidth() * 0.5, 0.5, mob.getBbWidth() * 0.5, 0.08);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        int ticks = ctx.ticksActive();
        // The heat shimmer: flames curling off the molten body.
        if (ticks % 2 == 0) {
            level.sendParticles(ParticleTypes.FLAME, mob.getX(), mob.getY() + 0.5, mob.getZ(),
                2, mob.getBbWidth() * 0.45, 0.45, mob.getBbWidth() * 0.45, 0.04);
        }
        if (ticks % 10 == 0) {
            level.sendParticles(ParticleTypes.LAVA, mob.getX(), mob.getY() + 0.3, mob.getZ(),
                1, mob.getBbWidth() * 0.4, 0.3, mob.getBbWidth() * 0.4, 0.02);
        }
        // The fire trail: a fleck at its feet while it hops.
        if (ticks % 4 == 0 && mob.getDeltaMovement().horizontalDistanceSqr() > 0.0009) {
            level.sendParticles(ParticleTypes.SMALL_FLAME, mob.getX(), mob.getY() + 0.1, mob.getZ(),
                1, 0.2, 0.05, 0.2, 0.02);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        mob.removeEffect(MobEffects.FIRE_RESISTANCE);
        if (mob instanceof SulfurCube) {
            ItemStack current = mob.getItemBySlot(EquipmentSlot.BODY);
            ItemStack saved = SulfurCubeAbilitySupport.loadStack(ctx.state(), "saved_body",
                ctx.level().registryAccess());
            if (current.is(Items.MAGMA_BLOCK) || current.isEmpty()) {
                // Our magma block comes out; the original goes
                // back in (or the slot stays empty).
                mob.setItemSlot(EquipmentSlot.BODY, saved);
            }
            // Foreign item in the slot: the cube keeps its
            // find and the stored original is discarded.
        }
    }
}
