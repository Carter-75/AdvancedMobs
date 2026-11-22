package mod.advancedmobs.ability.tropicalfish;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustColorTransitionOptions;
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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Prism Scale Aura (Tropical Fish ability 5 of 5,
 * aura / transformation).
 *
 * WHAT IT DOES: the fish swells slightly and its
 * scales catch impossible light. Slow rings of
 * rainbow dust roll off its body and wash over the
 * water around it, and every ally the rings touch
 * is mended, emboldened, and grown a little
 * grander for as long as it stays inside the light.
 * When the aura ends it goes out the way it came:
 * one final bright flash.
 *
 * MECHANICS (the spec; fix bugs against these
 * numbers):
 * - Trigger (canTrigger): the framework's combat
 *   gate, plus the fish's current target within 14
 *   blocks (distance squared 196), plus the fish in
 *   water.
 * - Duration: 400 ticks (20 seconds).
 * - The swelling (start()): the caster gains a
 *   transient SCALE modifier of +0.25
 *   ADD_MULTIPLIED_BASE (1.25 times size, the
 *   design's scale line on self) under the id
 *   advancedmobs:prism_scale_self, removed exactly
 *   in end(). TROPICAL_FISH_AMBIENT at volume 1.0,
 *   pitch 1.2.
 * - The rings (every 5 ticks): 6
 *   DUST_COLOR_TRANSITION motes on a ring around
 *   the caster at its height plus 0.3. The ring's
 *   radius breathes with the pulse cycle: 1.0 plus
 *   the fraction of the current 20-tick cycle times
 *   5.0, so each ring swells from 1.0 to 6.0 blocks
 *   over a second and the next begins. The motes'
 *   color pair advances one step per cycle through
 *   a six-step rainbow (red to orange, orange to
 *   yellow, yellow to green, green to blue, blue to
 *   indigo, indigo to red; scale 1.3), and the
 *   ring's angular offset advances 0.05 radians per
 *   emission so successive rings spiral.
 * - The blessing (pulse, every 20 ticks): one
 *   bounded query (radius 6.0 around the caster,
 *   3.0 vertical both ways): every ally in it (the
 *   package ally rule) gains REGENERATION I and
 *   STRENGTH I refreshed to 40 ticks (the design's
 *   regeneration on allies line, and its "hitting
 *   a little harder" line: strength is an effect,
 *   and on an ally with an attack damage attribute
 *   it raises that attribute the vanilla way; on an
 *   attack-less ally it simply shows as the effect,
 *   documented here rather than faked in code). The
 *   scale blessing is tracked, not refreshed: an
 *   ally inside the query that is not yet in the
 *   state tag's buffed list gains a transient SCALE
 *   modifier of +0.15 ADD_MULTIPLIED_BASE under the
 *   id advancedmobs:prism_scale_ally and joins the
 *   list (the design's scale line on allies); an
 *   ally in the list that is no longer inside the
 *   query has the modifier removed exactly and
 *   leaves the list, and one that no longer
 *   resolves (dead or unloaded) simply leaves the
 *   list, since a transient modifier vanishes with
 *   an unloaded entity on its own. An ally can
 *   therefore never keep the growth by wandering
 *   off mid-aura, and can never stack it twice.
 * - The final flash (end()): 3 FLASH particles in
 *   gold (ARGB 0xFFFFD60A) and 12
 *   DUST_COLOR_TRANSITION motes of the rainbow's
 *   last step bursting at the caster (the design's
 *   flash on end line), the caster's own scale
 *   modifier is removed, and every ally still in
 *   the buffed list has its scale modifier removed
 *   exactly. The regeneration and strength already
 *   handed out ride their own 40-tick clocks out.
 *   Idempotent, and safe on the framework
 *   termination path: a dead caster's aura still
 *   flashes out and un-grows its allies.
 *
 * WHAT IT LOOKS LIKE: a slightly swollen little
 * fish at the center of slow rainbow weather,
 * rings of shifting color rolling outward through
 * the water again and again, its allies inside the
 * rings visibly grander and mending as they fight,
 * and one last golden blink as the light lets go.
 *
 * PERFORMANCE: the rings are 6 particle sends
 * every 5 ticks with no queries; the blessing is
 * one bounded query every 20 ticks plus a pass over
 * the buffed list (bounded by the number of allies
 * actually blessed, realistically a handful). No
 * per-tick allocations beyond the pulse's result
 * set, built once per pulse. Particle budget per
 * activation: rings 80 emissions times 6 = 480,
 * final flash 15, total at most 495. All particle
 * options are pre-built static finals; the two
 * modifiers are built at start() or on an ally's
 * first blessing and removed exactly.
 *
 * TERRAIN: this ability changes no blocks; the gate
 * does not apply.
 *
 * State tag keys: "buffed" (list of compounds, one
 * per scale-blessed ally: "id").
 */
public final class PrismScaleAuraAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final double AURA_RADIUS = 6.0;
    private static final int PULSE_INTERVAL = 20;
    private static final int RING_INTERVAL = 5;
    private static final int BUFF_TICKS = 40;
    private static final Identifier SELF_SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "prism_scale_self");
    private static final Identifier ALLY_SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "prism_scale_ally");
    private static final AttributeModifier SELF_SCALE =
        new AttributeModifier(SELF_SCALE_ID, 0.25, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
    private static final AttributeModifier ALLY_SCALE =
        new AttributeModifier(ALLY_SCALE_ID, 0.15, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);

    /** The final flash's gold, ARGB. Built once; particle options are immutable. */
    private static final ColorParticleOption GOLD_FLASH =
        ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFD60A);

    /** The six-step rainbow the rings cycle through (from RGB, to RGB, scale). Built once. */
    private static final DustColorTransitionOptions[] RAINBOW = {
        new DustColorTransitionOptions(0xFF0000, 0xFF7F00, 1.3f),
        new DustColorTransitionOptions(0xFF7F00, 0xFFFF00, 1.3f),
        new DustColorTransitionOptions(0xFFFF00, 0x00FF00, 1.3f),
        new DustColorTransitionOptions(0x00FF00, 0x0000FF, 1.3f),
        new DustColorTransitionOptions(0x0000FF, 0x4B0082, 1.3f),
        new DustColorTransitionOptions(0x4B0082, 0xFF0000, 1.3f)
    };

    @Override
    public String id() {
        return "prism_scale_aura";
    }

    @Override
    public String displayName() {
        return "Prism Scale Aura";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().isInWater()
            && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ctx.state().put("buffed", new ListTag());
        TropicalFishAbilitySupport.addModifier(caster, Attributes.SCALE, SELF_SCALE);
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.TROPICAL_FISH_AMBIENT, SoundSource.HOSTILE, 1.0f, 1.2f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        Mob caster = ctx.mob();

        // The rings: rainbow weather rolling off the caster.
        if (ticks % RING_INTERVAL == 0) {
            double radius = 1.0 + (ticks % PULSE_INTERVAL) / (double) PULSE_INTERVAL * 5.0;
            DustColorTransitionOptions mote = RAINBOW[(ticks / PULSE_INTERVAL) % RAINBOW.length];
            double offset = ticks * 0.05;
            for (int i = 0; i < 6; i++) {
                double angle = offset + i * Math.PI / 3.0;
                level.sendParticles(mote,
                    caster.getX() + Math.cos(angle) * radius, caster.getY() + 0.3,
                    caster.getZ() + Math.sin(angle) * radius, 1, 0.05, 0.05, 0.05, 0.0);
            }
        }

        // The blessing: mend, embolden, and grow the allies inside.
        if (ticks % PULSE_INTERVAL == 0) {
            runBlessing(ctx);
        }
    }

    /** One blessing pulse: refresh ally effects, and keep the tracked scale blessings exact. */
    private void runBlessing(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        List<LivingEntity> allies = TropicalFishAbilitySupport.alliesAroundSelf(ctx, AURA_RADIUS, 3.0, 3.0);
        Set<UUID> inside = new HashSet<>();
        for (LivingEntity ally : allies) {
            inside.add(ally.getUUID());
            ally.addEffect(new MobEffectInstance(MobEffects.REGENERATION, BUFF_TICKS, 0));
            ally.addEffect(new MobEffectInstance(MobEffects.STRENGTH, BUFF_TICKS, 0));
        }

        // Grow the newly inside; un-grow the no longer inside.
        ListTag buffed = state.getListOrEmpty("buffed");
        Set<UUID> tracked = new HashSet<>();
        for (int i = 0; i < buffed.size(); i++) {
            CompoundTag entry = buffed.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id != null) {
                tracked.add(id);
            }
        }
        ServerLevel level = ctx.serverLevel();
        ListTag next = new ListTag();
        for (LivingEntity ally : allies) {
            if (!tracked.contains(ally.getUUID())) {
                TropicalFishAbilitySupport.addModifier(ally, Attributes.SCALE, ALLY_SCALE);
            }
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, ally.getUUID());
            next.add(entry);
        }
        if (level != null) {
            for (UUID id : tracked) {
                if (inside.contains(id)) {
                    continue;
                }
                Entity found = level.getEntity(id);
                if (found instanceof LivingEntity living) {
                    TropicalFishAbilitySupport.removeModifier(living, Attributes.SCALE, ALLY_SCALE_ID);
                }
            }
        }
        state.put("buffed", next);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        TropicalFishAbilitySupport.removeModifier(caster, Attributes.SCALE, SELF_SCALE_ID);

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        // The final flash.
        level.sendParticles(GOLD_FLASH, caster.getX(), caster.getY() + 0.3, caster.getZ(),
            3, 0.4, 0.3, 0.4, 0.0);
        level.sendParticles(RAINBOW[RAINBOW.length - 1], caster.getX(), caster.getY() + 0.3, caster.getZ(),
            12, 0.9, 0.5, 0.9, 0.06);

        // Un-grow every ally still carrying the blessing.
        ListTag buffed = ctx.state().getListOrEmpty("buffed");
        for (int i = 0; i < buffed.size(); i++) {
            Entity found = TropicalFishAbilitySupport.resolveTracked(level, buffed.getCompoundOrEmpty(i));
            if (found instanceof LivingEntity living) {
                TropicalFishAbilitySupport.removeModifier(living, Attributes.SCALE, ALLY_SCALE_ID);
            }
        }
    }
}
