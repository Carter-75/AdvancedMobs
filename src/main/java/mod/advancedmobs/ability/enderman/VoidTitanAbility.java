package mod.advancedmobs.ability.enderman;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.PowerParticleOption;
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
import net.minecraft.world.item.Items;

import java.util.UUID;

/**
 * Void Titan (Enderman ability 4 of 5, transformation).
 *
 * WHAT IT DOES: the enderman unfolds to a terrible height,
 * doubling in size while DRAGON_BREATH spills around its feet
 * and chorus fruit displays orbit its head like a crown of
 * the End. It hits harder, it notices enemies across the
 * whole arena, and its eyes blaze violet through any darkness
 * or wall.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists within 20 blocks
 *   (distance squared 400). The titan is a mid-fight
 *   escalation, not an opener at sniper range.
 * - Duration: exactly 300 ticks (15 seconds) from start().
 * - The unfolding (start(), all removed in end()): a SCALE
 *   add_value modifier of +1.0 (modifier id
 *   advancedmobs:void_titan_scale; the enderman's scale
 *   doubles, so it stands about 5.8 blocks tall), a
 *   FOLLOW_RANGE add_value modifier of +32 (modifier id
 *   advancedmobs:void_titan_reach; the enderman's base follow
 *   range is 40, so the titan tracks targets out to 72
 *   blocks, the arena-spanning notice the design calls for),
 *   STRENGTH I (amplifier 0, 300 ticks) and GLOWING
 *   (amplifier 0, 300 ticks: the whole outline blazes violet
 *   through walls, the readable form of the design's blazing
 *   eyes, since an effect cannot glow only the eyes).
 * - The crown: 5 item displays of chorus fruit, uniform scale
 *   0.55, "fixed" transform context, at full brightness (set
 *   by the display helper). They orbit at radius 1.7 around
 *   the enderman's head, one full orbit every 120 ticks (6
 *   seconds), at a height read live every tick (the
 *   enderman's current bounding box height plus 0.6), so the
 *   crown sits on the doubled head, not where the head would
 *   be at normal size. They are repositioned every tick: the
 *   crown travels with the titan. Pure visuals; displays have
 *   no hitbox.
 * - The breath: every 10 ticks, 4 DRAGON_BREATH particles
 *   spill at the titan's feet, spread over radius 1.3: the
 *   End's breath leaking off a body too big for the world.
 *   Purely visual; no breath cloud entity is created and no
 *   damage comes from it.
 *
 * TERRAIN RULE: this ability changes NO real blocks, so the
 * mobGriefing gate does not apply. See IMPLEMENTATION-RULES.md
 * rule 6.
 *
 * WHAT IT LOOKS AND SOUNDS LIKE: an ENDERMAN_SCREAM (volume
 * 1.0, pitch 0.55, slowed by the size) as it unfolds, in a
 * burst of 16 DRAGON_BREATH and 10 PORTAL particles; then a
 * doubled enderman with a glowing outline, a crown of chorus
 * fruit turning slowly over its head, and dragon breath
 * curling off its feet for 15 seconds.
 *
 * PERFORMANCE: per tick the work is 5 UUID resolves and 5
 * snapTo writes for the crown, plus one height read; the
 * breath runs every 10 ticks. No entity scans, no allocations
 * in the tick path beyond the resolved references. Particle
 * budget per activation: 26 at start, breath at most 30
 * pulses times 4 = 120, total at most 146 particles. The 5
 * displays are the only extra entities, alive only for the
 * ability's 15 seconds.
 *
 * CLEANUP: end() removes both attribute modifiers and the
 * STRENGTH and GLOWING effects and discards every crown
 * display that still resolves (the enderman shrinks back to
 * its normal height as the SCALE modifier leaves). If the
 * caster dies mid-titan, the framework termination path runs
 * this same end(), so the crown never outlives the ability or
 * its caster.
 *
 * State tag keys: "crown" (list of compounds, one per fruit:
 * "id", the display's UUID).
 */
public final class VoidTitanAbility implements MobAbility {

    static final int DURATION_TICKS = 300;
    private static final int FRUIT_COUNT = 5;
    private static final double ORBIT_RADIUS = 1.7;
    private static final float FRUIT_SCALE = 0.55f;
    private static final double ORBIT_PERIOD_TICKS = 120.0;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "void_titan_scale");
    private static final Identifier REACH_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "void_titan_reach");
    // DRAGON_BREATH is a power-carrying particle type in 26.2, so
    // the option is built once with the standard power of 1.0 and
    // reused, never allocated in the tick path.
    private static final PowerParticleOption DRAGON_BREATH =
        PowerParticleOption.create(ParticleTypes.DRAGON_BREATH, 1.0f);

    @Override
    public String id() {
        return "void_titan";
    }

    @Override
    public String displayName() {
        return "Void Titan";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 400.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();

        // The unfolding: size, reach, strength, blazing outline.
        AttributeInstance scale = mob.getAttribute(Attributes.SCALE);
        if (scale != null) {
            scale.addOrUpdateTransientModifier(
                new AttributeModifier(SCALE_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        }
        AttributeInstance reach = mob.getAttribute(Attributes.FOLLOW_RANGE);
        if (reach != null) {
            reach.addOrUpdateTransientModifier(
                new AttributeModifier(REACH_ID, 32.0, AttributeModifier.Operation.ADD_VALUE));
        }
        mob.addEffect(new MobEffectInstance(MobEffects.STRENGTH, DURATION_TICKS, 0));
        mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, DURATION_TICKS, 0));

        // The crown of the End.
        ListTag crown = new ListTag();
        if (level != null) {
            for (int i = 0; i < FRUIT_COUNT; i++) {
                Entity fruit = EndermanAbilitySupport.createItemDisplay(level,
                    Items.CHORUS_FRUIT, FRUIT_SCALE, "fixed");
                if (fruit == null) {
                    continue;
                }
                positionFruit(ctx, fruit, i, 0);
                level.addFreshEntity(fruit);
                CompoundTag entry = new CompoundTag();
                entry.store("id", UUIDUtil.CODEC, fruit.getUUID());
                crown.add(entry);
            }
            level.sendParticles(DRAGON_BREATH, mob.getX(), mob.getY() + 0.4, mob.getZ(),
                16, 1.2, 0.3, 1.2, 0.05);
            level.sendParticles(ParticleTypes.PORTAL, mob.getX(), mob.getY() + 1.5, mob.getZ(),
                10, 0.7, 1.2, 0.7, 0.4);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.ENDERMAN_SCREAM, SoundSource.HOSTILE, 1.0f, 0.55f);
        }
        state.put("crown", crown);
    }

    /** Places one crown fruit on its orbiting bearing around the titan's head for the given tick. */
    private void positionFruit(AbilityContext ctx, Entity fruit, int index, int ticks) {
        Mob mob = ctx.mob();
        double angle = index * (Math.PI * 2.0 / FRUIT_COUNT)
            + ticks * (Math.PI * 2.0 / ORBIT_PERIOD_TICKS);
        double x = mob.getX() + Math.cos(angle) * ORBIT_RADIUS;
        double z = mob.getZ() + Math.sin(angle) * ORBIT_RADIUS;
        fruit.snapTo(x, mob.getY() + mob.getBbHeight() + 0.6, z, (float) Math.toDegrees(-angle), 0.0f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        ListTag crown = ctx.state().getListOrEmpty("crown");

        // The crown travels and turns with the titan: every
        // fruit is repositioned every tick.
        for (int i = 0; i < crown.size(); i++) {
            Entity fruit = resolveFruit(level, crown.getCompoundOrEmpty(i));
            if (fruit != null) {
                positionFruit(ctx, fruit, i, ticks);
            }
        }

        if (ticks % 10 == 0) {
            Mob mob = ctx.mob();
            level.sendParticles(DRAGON_BREATH, mob.getX(), mob.getY() + 0.3, mob.getZ(),
                4, 1.3, 0.2, 1.3, 0.03);
        }
    }

    /** Resolves a tracked crown display from its list entry, or null when gone. */
    private Entity resolveFruit(ServerLevel level, CompoundTag entry) {
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
        AttributeInstance scale = mob.getAttribute(Attributes.SCALE);
        if (scale != null) {
            scale.removeModifier(SCALE_ID);
        }
        AttributeInstance reach = mob.getAttribute(Attributes.FOLLOW_RANGE);
        if (reach != null) {
            reach.removeModifier(REACH_ID);
        }
        mob.removeEffect(MobEffects.STRENGTH);
        mob.removeEffect(MobEffects.GLOWING);

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag crown = ctx.state().getListOrEmpty("crown");
        for (int i = 0; i < crown.size(); i++) {
            Entity fruit = resolveFruit(level, crown.getCompoundOrEmpty(i));
            if (fruit != null) {
                fruit.discard();
            }
        }
    }
}
