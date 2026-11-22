package mod.advancedmobs.ability.villager;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.ChatFormatting;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.List;

/**
 * Hero of the Village Rally (Villager ability 5 of 5, aura).
 *
 * WHAT IT DOES: the villager stands tall, a glowing banner
 * reading SALE in bold gold letters materializes overhead, and
 * it leads the charge it would rather watch. Allies nearby are
 * inspired by the discounts into strength and speed, confetti
 * drifts over the rally, and the villager itself glows with
 * hero of the village pride.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   villager's current target within 16 blocks (distance squared
 *   256) AND at least one ally within 10 blocks (one bounded
 *   query under the package ally rule; a rally with no audience
 *   is never offered, the Oasis Blessing precedent).
 * - Duration: exactly 300 ticks (15 seconds).
 * - The banner (created once, in start()): one text display
 *   showing the component "SALE" in bold gold, billboard
 *   "center" so it always faces its reader, semi-transparent
 *   dark background, scale 2.0 (each text pixel is 1/40 of a
 *   block before scaling, so the word stands about 1.6 blocks
 *   wide), full brightness. It is snapped every tick to 2.7
 *   blocks above the caster's feet, following the villager as
 *   it moves, and discarded in end(). The text display is a
 *   pure visual: no hitbox, no collision, not a block change.
 * - The pride (in start()): the caster gains HERO_OF_THE_VILLAGE
 *   I and GLOWING, each for 320 ticks, both removed exactly in
 *   end(). The glow renders in vanilla team white (there is no
 *   server-side per-entity glow color for a teamless mob, the
 *   standing precedent); the gold lives in the banner text.
 *   VILLAGER_CELEBRATE plays at start at volume 1.2, pitch 1.0.
 * - The inspiration (every 20 ticks): one bounded radius-8.0
 *   query; every ally in range gains STRENGTH I and SPEED I,
 *   each refreshed to 40 ticks. The caster itself gains SPEED I
 *   refreshed to 40 ticks (it has to keep up with the charge it
 *   is leading). STRENGTH rides each ally's own attack
 *   attribute where it has one (the Prism Scale Aura
 *   documentation); the caster gains no STRENGTH, having no
 *   attack attribute to ride. Pulse effects lapse on their own
 *   40-tick clocks after the last pulse, so end() strips only
 *   the caster's two long effects.
 * - The confetti (every 10 ticks): 3 HAPPY_VILLAGER plus 2
 *   COMPOSTER particles drift around the caster at radius 1.2,
 *   heights 1.5 to 2.5 (the design's confetti line).
 *
 * WHAT IT LOOKS LIKE: a villager suddenly outlined in light
 * under a floating golden SALE sign, green sparkles and papery
 * flecks drifting down like confetti, and every friendly mob
 * around it surging forward a step faster and hitting a step
 * harder.
 *
 * PERFORMANCE: the banner is one display resolve and snap per
 * tick; confetti is 5 particles every 10 ticks; the inspiration
 * is one bounded query every 20 ticks touching at most the
 * allies inside it. Particle budget per activation: 30 pulses
 * times 5 = 150 particles. No per-tick allocations beyond the
 * pulse query's result list.
 *
 * TERRAIN: this ability changes no blocks (the banner is a text
 * display, a pure visual); the mobGriefing gate does not apply.
 *
 * CLEANUP: end() removes the caster's HERO_OF_THE_VILLAGE and
 * GLOWING effects and discards the banner display. Ally pulse
 * effects are not stripped; they lapse on their own clocks.
 *
 * State tag keys: "banner" (the text display's UUID).
 */
public final class HeroOfTheVillageRallyAbility implements MobAbility {

    static final int DURATION_TICKS = 300;
    private static final int PRIDE_TICKS = 320;
    private static final int PULSE_INTERVAL = 20;
    private static final int CONFETTI_INTERVAL = 10;
    private static final double AURA_RADIUS = 8.0;
    private static final double BANNER_HEIGHT = 2.7;

    @Override
    public String id() {
        return "hero_of_the_village_rally";
    }

    @Override
    public String displayName() {
        return "Hero of the Village Rally";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null || ctx.mob().distanceToSqr(target) > 256.0) {
            return false;
        }
        return !VillagerAbilitySupport.alliesAroundSelf(ctx, 10.0, 3.0, 3.0).isEmpty();
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();

        // The pride: hero glow for the whole rally.
        caster.addEffect(new MobEffectInstance(MobEffects.HERO_OF_THE_VILLAGE, PRIDE_TICKS, 0));
        caster.addEffect(new MobEffectInstance(MobEffects.GLOWING, PRIDE_TICKS, 0));
        if (level == null) {
            return;
        }
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.VILLAGER_CELEBRATE,
            SoundSource.HOSTILE, 1.2f, 1.0f);

        // The banner: SALE, in bold gold, overhead.
        Component bannerText = Component.literal("SALE").withStyle(ChatFormatting.BOLD, ChatFormatting.GOLD);
        Entity banner = VillagerAbilitySupport.createTextDisplay(level, bannerText, 2.0f);
        if (banner != null) {
            banner.snapTo(caster.getX(), caster.getY() + BANNER_HEIGHT, caster.getZ());
            if (level.addFreshEntity(banner)) {
                ctx.state().store("banner", UUIDUtil.CODEC, banner.getUUID());
            }
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        int ticks = ctx.ticksActive();

        // The banner rides overhead wherever the villager goes.
        Entity banner = VillagerAbilitySupport.resolveStored(level, state, "banner");
        if (banner != null) {
            banner.snapTo(caster.getX(), caster.getY() + BANNER_HEIGHT, caster.getZ());
        }

        if (ticks % CONFETTI_INTERVAL == 0) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, caster.getX(), caster.getY() + 2.0, caster.getZ(),
                3, 1.2, 0.5, 1.2, 0.03);
            level.sendParticles(ParticleTypes.COMPOSTER, caster.getX(), caster.getY() + 2.0, caster.getZ(),
                2, 1.2, 0.5, 1.2, 0.03);
        }

        if (ticks % PULSE_INTERVAL == 0) {
            List<LivingEntity> allies = VillagerAbilitySupport.alliesAroundSelf(ctx, AURA_RADIUS, 3.0, 3.0);
            for (LivingEntity ally : allies) {
                ally.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 40, 0));
                ally.addEffect(new MobEffectInstance(MobEffects.SPEED, 40, 0));
            }
            caster.addEffect(new MobEffectInstance(MobEffects.SPEED, 40, 0));
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.removeEffect(MobEffects.HERO_OF_THE_VILLAGE);
        caster.removeEffect(MobEffects.GLOWING);
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Entity banner = VillagerAbilitySupport.resolveStored(level, ctx.state(), "banner");
        if (banner != null) {
            banner.discard();
        }
    }
}
