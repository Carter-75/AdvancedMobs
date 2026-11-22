package mod.advancedmobs.ability.witherskeleton;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Items;

import java.util.UUID;

/**
 * Wither Brand (Wither Skeleton ability 1 of 5, attack).
 *
 * WHAT IT DOES: the wither skeleton strikes a searing black
 * brand onto its victim. A coal item display burns above the
 * victim's head while DUST_COLOR_TRANSITION motes fade from
 * gray to black around the wound. The branded victim suffers
 * WITHER for the brand's whole life, and when the brand burns
 * out it detonates: a burst of INSTANT_DAMAGE wrapped in SOUL
 * wisps.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   wither skeleton's current target within 12 blocks (distance
 *   squared 144): the brand is struck in a melee lunge, not
 *   sniped across the arena.
 * - Duration: exactly 200 ticks (10 seconds) from start().
 * - The mark: start() records the victim's UUID in the state
 *   tag under "victim" (the /tag state marking the design names;
 *   the framework persists it with the activation, so a brand
 *   resumed after a chunk reload still knows its victim) and
 *   applies WITHER I (amplifier 0) for the full 200 ticks: 10
 *   damage over the brand's life before armor and the usual
 *   wither ticks, on top of whatever the skeleton's sword does.
 * - The coal: one item display (a plain coal stack, scale 0.6,
 *   "fixed" transform context) created in start() and repositioned
 *   every tick to 0.55 blocks above the victim's head (victim
 *   position plus its full height plus 0.55). It is a pure
 *   visual marker: displays have no hitbox and block nothing.
 * - The burn-out (at tick 200, inside tick(), before the finish
 *   check): if the victim still resolves, is alive, and is
 *   within 24 blocks of the caster (distance squared 576), the
 *   brand detonates on it: INSTANT_DAMAGE I (amplifier 0, the
 *   vanilla instant damage effect, 6 magic damage on application)
 *   and a burst of 12 SOUL particles at the victim's chest, with
 *   a FIRECHARGE_USE crack (volume 0.9, pitch 0.6). A victim who
 *   died or escaped beyond 24 blocks is simply unmarked: no
 *   detonation, no damage. Doing the detonation in tick() rather
 *   than end() is deliberate: end() also runs on the framework
 *   termination path when the CASTER dies, and a dead skeleton's
 *   brand must fizzle, not explode.
 * - If the victim dies mid-brand, the display stops following
 *   (nothing resolves) and the remaining ticks idle until the
 *   duration runs out; the framework cooldown only starts at
 *   end(), as with every ability.
 *
 * WHAT IT LOOKS AND SOUNDS LIKE: the branding lands with a
 * WITHER_SKELETON_HURT growl (volume 0.8, pitch 0.7) and 10
 * DUST_COLOR_TRANSITION motes (from gray 0x666666 to near-black
 * 0x0A0A0A, scale 1.2) searing in around the victim. Every 5
 * ticks, 3 more motes drift off the wound. The coal display
 * hangs burning above the victim's head the whole time, and
 * the burn-out is the SOUL burst described above.
 *
 * PERFORMANCE: per tick the work is at most 2 UUID resolves
 * (victim and display) and 1 snapTo; the mote pulse runs every
 * 5 ticks; the detonation is one effect application at tick
 * 200. No entity scans at all. Particle budget per activation:
 * 10 at start plus at most 40 pulses times 3 = 120, plus the
 * 12 SOUL at the burn-out, total at most 142 particles. The
 * transition options are a pre-built static final, so the tick
 * path allocates nothing.
 *
 * CLEANUP: end() discards the coal display if it still
 * resolves. If the caster dies mid-brand, the framework
 * termination path runs this same end(), so the display never
 * outlives the ability and the brand never detonates (see the
 * burn-out note above). The WITHER effect is a normal timed
 * effect on the victim and simply expires; nothing else was
 * changed.
 *
 * State tag keys: "victim" (the branded entity's UUID),
 * "display" (the coal display's UUID).
 */
public final class WitherBrandAbility implements MobAbility {

    static final int DURATION_TICKS = 200;
    private static final int DETONATION_TICK = 200;
    private static final double DETONATION_RANGE_SQR = 576.0;
    // 26.2 DustColorTransitionOptions takes (from RGB int, to RGB int, scale):
    // gray 0x666666 fading to near-black 0x0A0A0A.
    private static final DustColorTransitionOptions BRAND_MOTE =
        new DustColorTransitionOptions(0x666666, 0x0A0A0A, 1.2f);

    @Override
    public String id() {
        return "wither_brand";
    }

    @Override
    public String displayName() {
        return "Wither Brand";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        CompoundTag state = ctx.state();
        LivingEntity victim = ctx.target();
        if (victim == null) {
            return;
        }
        state.store("victim", UUIDUtil.CODEC, victim.getUUID());

        // The brand itself: WITHER for the brand's whole life.
        victim.addEffect(new MobEffectInstance(MobEffects.WITHER, DURATION_TICKS, 0));

        if (level == null) {
            return;
        }
        // The burning coal that marks the victim.
        CompoundTag coal = new CompoundTag();
        coal.putString("id", BuiltInRegistries.ITEM.getKey(Items.COAL).toString());
        coal.putInt("count", 1);
        Entity display = WitherSkeletonAbilitySupport.createItemDisplay(level, coal, 0.6f, "fixed");
        if (display != null) {
            display.snapTo(victim.getX(), victim.getY() + victim.getBbHeight() + 0.55, victim.getZ());
            level.addFreshEntity(display);
            state.store("display", UUIDUtil.CODEC, display.getUUID());
        }

        // The sear: transition motes and a growl.
        level.sendParticles(BRAND_MOTE, victim.getX(), victim.getY() + 1.2, victim.getZ(),
            10, 0.5, 0.7, 0.5, 0.02);
        level.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
            SoundEvents.WITHER_SKELETON_HURT, SoundSource.HOSTILE, 0.8f, 0.7f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        int ticks = ctx.ticksActive();
        LivingEntity victim = resolveVictim(ctx, level, state);

        // The coal hangs above the victim's head, following it.
        Entity display = resolveDisplay(level, state);
        if (display != null && victim != null) {
            display.snapTo(victim.getX(), victim.getY() + victim.getBbHeight() + 0.55, victim.getZ());
        }

        if (victim != null && ticks % 5 == 0) {
            // The wound smoulders: motes fading gray to black.
            level.sendParticles(BRAND_MOTE, victim.getX(), victim.getY() + 1.2, victim.getZ(),
                3, 0.45, 0.7, 0.45, 0.02);
        }

        if (ticks == DETONATION_TICK) {
            detonate(ctx, level, victim);
        }
    }

    /** The burn-out: INSTANT_DAMAGE and a SOUL burst on a victim still in reach. */
    private void detonate(AbilityContext ctx, ServerLevel level, LivingEntity victim) {
        if (victim == null || !victim.isAlive()) {
            return;
        }
        Mob caster = ctx.mob();
        if (caster.distanceToSqr(victim) > DETONATION_RANGE_SQR) {
            return; // Escaped the brand's reach: it fizzles out unmarked.
        }
        victim.addEffect(new MobEffectInstance(MobEffects.INSTANT_DAMAGE, 1, 0));
        level.sendParticles(ParticleTypes.SOUL, victim.getX(), victim.getY() + 1.0, victim.getZ(),
            12, 0.5, 0.7, 0.5, 0.06);
        level.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
            SoundEvents.FIRECHARGE_USE, SoundSource.HOSTILE, 0.9f, 0.6f);
    }

    /** Resolves the branded victim from the state tag, or null when gone. */
    private LivingEntity resolveVictim(AbilityContext ctx, ServerLevel level, CompoundTag state) {
        if (!state.contains("victim")) {
            return null;
        }
        UUID id = state.read("victim", UUIDUtil.CODEC).orElse(null);
        if (id != null && level.getEntity(id) instanceof LivingEntity living && living.isAlive()) {
            return living;
        }
        return null;
    }

    /** Resolves the coal display from the state tag, or null when gone. */
    private Entity resolveDisplay(ServerLevel level, CompoundTag state) {
        if (!state.contains("display")) {
            return null;
        }
        UUID id = state.read("display", UUIDUtil.CODEC).orElse(null);
        return id != null ? level.getEntity(id) : null;
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
        Entity display = resolveDisplay(level, ctx.state());
        if (display != null) {
            display.discard();
        }
    }
}
