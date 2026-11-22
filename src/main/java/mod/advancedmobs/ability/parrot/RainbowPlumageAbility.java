package mod.advancedmobs.ability.parrot;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Rainbow Plumage (Parrot ability 4 of 5, transformation).
 *
 * WHAT IT DOES: the parrot's plumage ignites into cycling
 * rainbow color, dust shifting hue across its body as it
 * gains a glowing outline, bursts of speed, and a spring
 * in its hop. A gem display hovers over it, always facing
 * the viewer. Enemies that actually look at the dazzling
 * display get blindness pulsed onto them.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the parrot's current target within 14 blocks (distance
 *   squared 196).
 * - The transformation (start(), all removed in end()):
 *   GLOWING, SPEED I, and JUMP_BOOST I (all amplifier 0)
 *   applied to the caster for 320 ticks. The glowing
 *   outline renders in vanilla team white for a teamless
 *   mob; the rainbow lives in the dust, not the outline
 *   (the Mirror Ghasts precedent: no server-side per-entity
 *   glow color exists in 26.2). A PARROT_AMBIENT plays at
 *   start (volume 1.2, pitch 1.3).
 * - The gem (start()): one amethyst shard item display
 *   (scale 0.8) with billboard mode "center", so it always
 *   faces the viewer (the design's billboard center accent),
 *   created through the support's NBT factory, tracked in
 *   the state tag, and snapped every tick to 0.9 blocks
 *   above the parrot. Discarded in end().
 * - The shimmer (tick(), every 2 ticks): 3
 *   DUST_COLOR_TRANSITION motes at the parrot's body, the
 *   option cycling through the shared six-step rainbow by
 *   elapsed pairs of ticks, so the dust hue sweeps the
 *   whole spectrum about every 1.2 seconds.
 * - Holding the display (tick(), every 5 ticks): the
 *   parrot's velocity is written toward a point 3.0 blocks
 *   above its target's live position at a step of 0.4
 *   blocks per tick, so it hovers in the fight showing off
 *   instead of wandering off on vanilla flight goals. With
 *   no target it holds over its own position.
 * - The blindness pulse (tick(), every 40 ticks): one
 *   bounded query (radius 8.0, reach 4.0 below and 4.0
 *   above the parrot): an enemy is dazzled only when it is
 *   FACING the parrot, meaning the dot product of its look
 *   vector with the normalized direction from the enemy to
 *   the parrot is at least 0.5 (inside a 60 degree half
 *   angle cone). Dazzled enemies gain BLINDNESS (amplifier
 *   0) for 60 ticks, so a victim that keeps staring is
 *   re-blinded by the next pulse before the last one fades,
 *   and one that looks away recovers.
 * - Duration: 300 ticks (15 seconds), hard finish.
 * - end(): removes GLOWING, SPEED, and JUMP_BOOST from the
 *   caster and discards the gem display. The victims'
 *   BLINDNESS rides its own 60 tick clock.
 *
 * WHAT IT LOOKS LIKE: the little bird lights up with a
 * white-hot outline and a floating amethyst over its head,
 * rainbow dust pouring off its feathers in a slow spectral
 * sweep while it hovers over the fight, and anything that
 * looks straight at it keeps going blind.
 *
 * PERFORMANCE: per tick at most 1 display resolve and snap;
 * the shimmer emits 3 motes every 2 ticks; the steering
 * write runs every 5 ticks; the blindness query runs every
 * 40 ticks only, with one look-vector dot product per enemy
 * found. All dust options are the pre-built static rainbow
 * array. Particle budget per activation: 150 shimmer sends
 * times 3 = 450 motes, stated cap 460.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "gem" (the gem display's UUID, stored
 * directly under the key).
 */
public final class RainbowPlumageAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int EFFECT_TICKS = 320;
    private static final int SHIMMER_INTERVAL = 2;
    private static final int STEER_INTERVAL = 5;
    private static final int PULSE_INTERVAL = 40;
    private static final double PULSE_RADIUS = 8.0;
    private static final double FACING_DOT = 0.5;
    private static final int BLINDNESS_TICKS = 60;
    private static final double HOVER_HEIGHT = 3.0;
    private static final double HOVER_SPEED = 0.4;

    @Override
    public String id() {
        return "rainbow_plumage";
    }

    @Override
    public String displayName() {
        return "Rainbow Plumage";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.addEffect(new MobEffectInstance(MobEffects.GLOWING, EFFECT_TICKS, 0));
        caster.addEffect(new MobEffectInstance(MobEffects.SPEED, EFFECT_TICKS, 0));
        caster.addEffect(new MobEffectInstance(MobEffects.JUMP_BOOST, EFFECT_TICKS, 0));

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.PARROT_AMBIENT,
            SoundSource.HOSTILE, 1.2f, 1.3f);
        Entity gem = ParrotAbilitySupport.createItemDisplay(level,
            new ItemStack(Items.AMETHYST_SHARD), 0.8f, "center");
        if (gem != null) {
            gem.snapTo(caster.getX(), caster.getY() + 0.9, caster.getZ(), 0.0f, 0.0f);
            if (level.addFreshEntity(gem)) {
                ctx.state().store("gem", UUIDUtil.CODEC, gem.getUUID());
            }
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        CompoundTag state = ctx.state();

        // The gem hovers over the parrot.
        if (state.contains("gem")) {
            UUID id = state.read("gem", UUIDUtil.CODEC).orElse(null);
            Entity gem = id != null ? level.getEntity(id) : null;
            if (gem != null) {
                gem.snapTo(caster.getX(), caster.getY() + 0.9, caster.getZ(), ticks * 4.0f, 0.0f);
            }
        }

        // The rainbow shimmer, cycling one hue step every 2 ticks.
        if (ticks % SHIMMER_INTERVAL == 0) {
            level.sendParticles(
                ParrotAbilitySupport.RAINBOW[(ticks / SHIMMER_INTERVAL) % ParrotAbilitySupport.RAINBOW.length],
                caster.getX(), caster.getY() + 0.35, caster.getZ(), 3, 0.25, 0.2, 0.25, 0.02);
        }

        // Hold the display over the fight.
        if (ticks % STEER_INTERVAL == 0) {
            LivingEntity target = ctx.target();
            double hx = target != null ? target.getX() : caster.getX();
            double hy = (target != null ? target.getY() : caster.getY()) + HOVER_HEIGHT;
            double hz = target != null ? target.getZ() : caster.getZ();
            ParrotAbilitySupport.steerToward(caster, hx, hy, hz, HOVER_SPEED);
        }

        // The blindness pulse on enemies facing the display.
        if (ticks % PULSE_INTERVAL == 0) {
            List<LivingEntity> onlookers = ParrotAbilitySupport.enemiesInBox(ctx,
                caster.getX(), caster.getY(), caster.getZ(), PULSE_RADIUS, 4.0, 4.0);
            for (LivingEntity onlooker : onlookers) {
                Vec3 look = onlooker.getLookAngle();
                double dx = caster.getX() - onlooker.getX();
                double dy = (caster.getY() + 0.35) - onlooker.getEyeY();
                double dz = caster.getZ() - onlooker.getZ();
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len < 0.001) {
                    continue;
                }
                double dot = (look.x * dx + look.y * dy + look.z * dz) / len;
                if (dot >= FACING_DOT) {
                    onlooker.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, BLINDNESS_TICKS, 0));
                }
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.removeEffect(MobEffects.GLOWING);
        caster.removeEffect(MobEffects.SPEED);
        caster.removeEffect(MobEffects.JUMP_BOOST);
        ServerLevel level = ctx.serverLevel();
        CompoundTag state = ctx.state();
        if (level != null && state.contains("gem")) {
            UUID id = state.read("gem", UUIDUtil.CODEC).orElse(null);
            Entity gem = id != null ? level.getEntity(id) : null;
            if (gem != null) {
                gem.discard();
            }
        }
    }
}
