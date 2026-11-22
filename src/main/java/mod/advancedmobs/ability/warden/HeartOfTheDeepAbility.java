package mod.advancedmobs.ability.warden;

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
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.List;
import java.util.UUID;

/**
 * Heart of the Deep (Warden ability 3 of 5, transformation).
 *
 * WHAT IT DOES: the warden's chest splits open with teal
 * light and the deep dark pours in. It grows half again as
 * large and far heavier, hits harder, and shrugs off
 * knockback, while four sculk vein tendrils rise from its
 * shoulders and wave slowly over the fight. Every 3 seconds
 * the open heart pulses DARKNESS onto every enemy near it:
 * in this form the warden does not need to blind you first,
 * it simply fights in the dark it makes.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the warden's current target within 16 blocks (distance
 *   squared 256).
 * - Duration: 400 ticks (20 seconds).
 * - The form (start(), all transient modifiers, all removed
 *   in end()):
 *   - SCALE +0.5 add_multiplied_base (1.5 times birth size;
 *     id advancedmobs:heart_of_the_deep_scale),
 *   - MAX_HEALTH +100.0 add_value (the warden's 500 becomes
 *     600; id advancedmobs:heart_of_the_deep_health). The
 *     warden heals the full +100 on entry, arriving at the
 *     new size full of the deep's vigor; end() removes the
 *     modifier and clamps health back to the restored
 *     maximum (the Colossal Grief pattern),
 *   - ATTACK_DAMAGE +8.0 add_value (30 becomes 38; id
 *     advancedmobs:heart_of_the_deep_damage),
 *   - KNOCKBACK_RESISTANCE +0.5 add_value (id
 *     advancedmobs:heart_of_the_deep_knockback); the
 *     attribute itself clamps the total at 1.0.
 * - The tendrils: 4 sculk vein block displays (scale 1.4,
 *   full brightness, teleport duration 2, built through the
 *   shared factory) spawn in start() and are snapped every
 *   tick to shoulder anchors computed from the warden's live
 *   look direction: two per side at lateral offsets of plus
 *   or minus 0.55 times the bounding box width and forward
 *   offsets of +0.25 and -0.35 blocks, at 78 percent of the
 *   bounding box height, each bobbing on its own phase
 *   (sin of ticks times 0.06 plus phase 1.7 per index, times
 *   0.25 blocks): the slow wave, glided by the displays'
 *   teleport duration (the design's interpolation). The
 *   design calls them block display passengers; they are
 *   snapped in code instead, because a display passenger's
 *   model offset does not inherit the vehicle's facing (the
 *   Obsidian Crust and Blackstone Bulwark precedent). Their
 *   UUIDs live in the state tag; end() discards them.
 * - The heart light: every 10 ticks, 2 SCULK_SOUL particles
 *   curl up from the warden's chest (half a block ahead of
 *   its center at 55 percent height).
 * - The darkness pulse (every 60 ticks): one bounded query,
 *   enemies (the package enemy rule) within radius 8.0 of
 *   the warden gain DARKNESS amplifier 0 for 80 ticks, and
 *   8 SCULK_CHARGE_POP sparks ring the warden at radius 2.0
 *   to mark the pulse.
 * - A WARDEN_ROAR (volume 2.0, pitch 0.8) opens the form.
 *
 * WHAT IT LOOKS LIKE: a warden half again as tall with its
 * chest glowing teal, four dark vein tendrils swaying over
 * its shoulders like a living crown, soul wisps leaking
 * from the split in its chest, and waves of darkness rolling
 * off it every few seconds.
 *
 * PERFORMANCE: per tick the cost is at most 4 UUID resolves
 * and snapTo calls plus arithmetic; the soul wisps run every
 * 10 ticks and the only entity query is the 60-tick darkness
 * pulse. Modifiers are built once in start() and removed
 * exactly in end(). Particle budget per activation: 40
 * pulses of wisps times 2 = 80, plus at most 7 darkness
 * pulses times 8 sparks = 56, total at most 136.
 *
 * CLEANUP: end() removes all four modifiers (clamping health
 * after the MAX_HEALTH removal) and discards the tendril
 * displays. If the warden dies mid-form, the framework
 * termination path runs this same end(), so the tendrils
 * never outlive it.
 *
 * State tag keys: "tendrils" (list of compounds, one per
 * display: "id", the display's UUID).
 */
public final class HeartOfTheDeepAbility implements MobAbility {

    static final int DURATION_TICKS = 400;
    private static final int TENDRIL_COUNT = 4;
    private static final double MAX_HEALTH_BONUS = 100.0;
    private static final int WISP_INTERVAL = 10;
    private static final int DARKNESS_INTERVAL = 60;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "heart_of_the_deep_scale");
    private static final Identifier HEALTH_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "heart_of_the_deep_health");
    private static final Identifier DAMAGE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "heart_of_the_deep_damage");
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "heart_of_the_deep_knockback");

    @Override
    public String id() {
        return "heart_of_the_deep";
    }

    @Override
    public String displayName() {
        return "Heart of the Deep";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();

        WardenAbilitySupport.addModifier(mob, Attributes.SCALE,
            new AttributeModifier(SCALE_ID, 0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        WardenAbilitySupport.addModifier(mob, Attributes.MAX_HEALTH,
            new AttributeModifier(HEALTH_ID, MAX_HEALTH_BONUS, AttributeModifier.Operation.ADD_VALUE));
        // Arrive full: the deep fills the new room at once.
        mob.setHealth(Math.min(mob.getMaxHealth(), mob.getHealth() + (float) MAX_HEALTH_BONUS));
        WardenAbilitySupport.addModifier(mob, Attributes.ATTACK_DAMAGE,
            new AttributeModifier(DAMAGE_ID, 8.0, AttributeModifier.Operation.ADD_VALUE));
        WardenAbilitySupport.addModifier(mob, Attributes.KNOCKBACK_RESISTANCE,
            new AttributeModifier(KNOCKBACK_ID, 0.5, AttributeModifier.Operation.ADD_VALUE));

        ServerLevel level = ctx.serverLevel();
        ListTag tendrils = new ListTag();
        if (level != null) {
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.WARDEN_ROAR,
                SoundSource.HOSTILE, 2.0f, 0.8f);
            for (int i = 0; i < TENDRIL_COUNT; i++) {
                Entity display = WardenAbilitySupport.createVeinDisplay(level, 1.4f);
                if (display == null) {
                    continue;
                }
                display.snapTo(mob.getX(), mob.getY() + mob.getBbHeight() * 0.78, mob.getZ(),
                    0.0f, 0.0f);
                if (!level.addFreshEntity(display)) {
                    continue;
                }
                CompoundTag entry = new CompoundTag();
                entry.store("id", UUIDUtil.CODEC, display.getUUID());
                tendrils.add(entry);
            }
        }
        ctx.state().put("tendrils", tendrils);
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();

        // The tendrils ride the shoulders and wave.
        double lookX = mob.getLookAngle().x;
        double lookZ = mob.getLookAngle().z;
        double lookLength = Math.sqrt(lookX * lookX + lookZ * lookZ);
        if (lookLength < 1.0e-4) {
            lookX = 1.0;
            lookZ = 0.0;
            lookLength = 1.0;
        }
        double forwardX = lookX / lookLength;
        double forwardZ = lookZ / lookLength;
        double sideX = -forwardZ;
        double sideZ = forwardX;
        ListTag tendrils = ctx.state().getListOrEmpty("tendrils");
        for (int i = 0; i < tendrils.size(); i++) {
            CompoundTag entry = tendrils.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
            Entity display = WardenAbilitySupport.resolve(level, id);
            if (display == null) {
                continue;
            }
            double lateral = (i % 2 == 0 ? 1.0 : -1.0) * mob.getBbWidth() * 0.55;
            double forward = (i < 2 ? 0.25 : -0.35);
            double wave = Math.sin(ticks * 0.06 + i * 1.7) * 0.25;
            display.snapTo(
                mob.getX() + sideX * lateral + forwardX * forward,
                mob.getY() + mob.getBbHeight() * 0.78 + wave,
                mob.getZ() + sideZ * lateral + forwardZ * forward,
                0.0f, 0.0f);
        }

        // The heart light: soul wisps off the split chest.
        if (ticks % WISP_INTERVAL == 0) {
            level.sendParticles(ParticleTypes.SCULK_SOUL,
                mob.getX() + forwardX * 0.5, mob.getY() + mob.getBbHeight() * 0.55,
                mob.getZ() + forwardZ * 0.5,
                2, 0.25, 0.3, 0.25, 0.03);
        }

        // The darkness pulse.
        if (ticks % DARKNESS_INTERVAL == 0) {
            List<LivingEntity> victims = WardenAbilitySupport.enemiesAroundSelf(ctx, 8.0, 3.0, 3.0);
            for (LivingEntity victim : victims) {
                victim.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 80, 0));
            }
            for (int i = 0; i < 8; i++) {
                double angle = i * Math.PI / 4.0;
                level.sendParticles(ParticleTypes.SCULK_CHARGE_POP,
                    mob.getX() + Math.cos(angle) * 2.0, mob.getY() + 0.4,
                    mob.getZ() + Math.sin(angle) * 2.0,
                    1, 0.0, 0.1, 0.0, 0.02);
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        WardenAbilitySupport.removeModifier(mob, Attributes.SCALE, SCALE_ID);
        WardenAbilitySupport.removeModifier(mob, Attributes.MAX_HEALTH, HEALTH_ID);
        if (mob.getHealth() > mob.getMaxHealth()) {
            mob.setHealth(mob.getMaxHealth());
        }
        WardenAbilitySupport.removeModifier(mob, Attributes.ATTACK_DAMAGE, DAMAGE_ID);
        WardenAbilitySupport.removeModifier(mob, Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_ID);

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag tendrils = ctx.state().getListOrEmpty("tendrils");
        for (int i = 0; i < tendrils.size(); i++) {
            CompoundTag entry = tendrils.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
            Entity display = WardenAbilitySupport.resolve(level, id);
            if (display != null) {
                display.discard();
            }
        }
    }
}
