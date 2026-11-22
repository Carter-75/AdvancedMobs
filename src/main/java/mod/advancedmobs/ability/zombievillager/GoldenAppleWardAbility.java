package mod.advancedmobs.ability.zombievillager;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Golden Apple Ward (Zombie Villager ability 2 of 5, defense).
 *
 * WHAT IT DOES: remembering its own cure, the zombie villager raises a
 * shimmering golden ward around itself. While the ward holds it carries
 * a thick layer of absorption hearts and steadily regenerates, and the
 * dome sheds gold dust every time it soaks a hit.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the zombie
 *   villager's current target within 10 blocks (distance squared 100).
 *   This is a defensive stance, raised when a fight is already close.
 * - Duration: exactly 240 ticks (12 seconds) from start().
 * - Absorption, set up once in start():
 *     A transient MAX_ABSORPTION modifier, add_value +20.0 (id
 *       advancedmobs:golden_apple_ward_capacity, removed in end()),
 *       raises the absorption cap so the ward's hearts always fit,
 *       whatever else has modified the attribute.
 *     The ABSORPTION effect, amplifier 3, for the full 240 ticks: the
 *       vanilla golden-apple look (yellow hearts) for exactly the
 *       ward's lifetime.
 *     The absorption amount itself is set directly to 16.0 (four rows
 *       of hearts, the Absorption IV amount) at start, clamped by the
 *       raised cap. Setting it directly, not only through the effect,
 *       guarantees the ward arrives full regardless of how the effect's
 *       own grant interacts with pre-existing absorption.
 *   The ward does NOT refill: soaked hearts are gone until the ability
 *   fires again. What keeps the zombie villager alive through the ward
 *   is the regeneration below plus the 16 hearts buying time.
 * - Regeneration: REGENERATION I (amplifier 0) for 30 ticks, refreshed
 *   every 20 ticks (12 refreshes), so it never lapses while the ward
 *   holds: roughly 1 health every 2.5 seconds, about 10 health over
 *   the ward if the zombie villager stays hurt.
 * - Hit shed: every tick compares health plus absorption against the
 *   previous tick's total (state keys "last_total", "last_shed"). When
 *   the total dropped and at least 5 ticks passed since the last shed,
 *   the dome sheds 4 gold DUST particles at the body. Purely visual;
 *   it is how a watcher can see the ward working.
 *
 * WHAT IT LOOKS LIKE: a swirling dome of glyphs and totem motes around
 * the zombie villager: every 5 ticks, 3 ENCHANT particles and 2
 * TOTEM_OF_UNDYING particles on a ring of radius 1.3 at chest height,
 * the ring angle advancing 0.25 radians per pulse so the motes spiral.
 * The absorption hearts are the vanilla yellow hearts. A soaked hit
 * sheds 4 gold DUST motes (color 1.0, 0.84, 0.2, scale 1.2) off the
 * dome.
 *
 * PERFORMANCE: the per-tick work is two float reads and a compare (the
 * hit shed check); everything else runs on the 5-tick visual pulse or
 * the 20-tick regeneration refresh. No entity scans at all. Particle
 * budget per activation: 48 visual pulses times 5 particles = 240,
 * plus hit sheds capped at 1 per 5 ticks times 4 particles = at most
 * 192 in the worst case of being hit constantly, realistic total well
 * under 300.
 *
 * CLEANUP: end() removes the MAX_ABSORPTION modifier by id and clamps
 * the current absorption amount down to the restored cap, so no
 * phantom capacity lingers. The ABSORPTION effect is NOT removed: its
 * duration equals the ability duration and it expires on its own at
 * the same tick (and removing effects in end() could strip an
 * unrelated same-type effect from another system; modifiers carry
 * unique ids, effects do not). On caster death the framework
 * termination path runs this same end(); the modifier dies with the
 * entity anyway.
 *
 * State tag keys: "last_total" (float: health + absorption last tick),
 * "last_shed" (tick of the last gold dust shed).
 */
public final class GoldenAppleWardAbility implements MobAbility {

    static final int DURATION_TICKS = 240;
    private static final float WARD_ABSORPTION = 16.0f;
    private static final Identifier CAPACITY_ID = Identifier.fromNamespaceAndPath("advancedmobs", "golden_apple_ward_capacity");
    // 26.2 DustParticleOptions takes an RGB int color: 1.0, 0.84, 0.2 as 0xFFD633.
    private static final DustParticleOptions GOLD_DUST = new DustParticleOptions(0xFFD633, 1.2f);

    @Override
    public String id() {
        return "golden_apple_ward";
    }

    @Override
    public String displayName() {
        return "Golden Apple Ward";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return ctx.target() != null && ctx.mob().distanceToSqr(ctx.target()) <= 100.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        AttributeInstance capacity = mob.getAttribute(Attributes.MAX_ABSORPTION);
        if (capacity != null) {
            capacity.addOrUpdateTransientModifier(
                new AttributeModifier(CAPACITY_ID, 20.0, AttributeModifier.Operation.ADD_VALUE));
        }
        mob.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, DURATION_TICKS, 3));
        // The ward arrives full: 16 absorption hearts, clamped by the cap.
        mob.setAbsorptionAmount(Math.min(mob.getMaxAbsorption(), Math.max(mob.getAbsorptionAmount(), WARD_ABSORPTION)));
        mob.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 30, 0));
        CompoundTag state = ctx.state();
        state.putFloat("last_total", mob.getHealth() + mob.getAbsorptionAmount());
        state.putInt("last_shed", -100);
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob mob = ctx.mob();
        int ticks = ctx.ticksActive();
        CompoundTag state = ctx.state();
        ServerLevel level = ctx.serverLevel();

        // Hit shed: the dome sheds gold dust when it soaks damage.
        float total = mob.getHealth() + mob.getAbsorptionAmount();
        if (level != null && total < state.getFloatOr("last_total", total) - 0.01f
            && ticks - state.getIntOr("last_shed", -100) >= 5) {
            level.sendParticles(GOLD_DUST, mob.getX(), mob.getY() + 1.2, mob.getZ(),
                4, 0.8, 0.7, 0.8, 0.05);
            state.putInt("last_shed", ticks);
        }
        state.putFloat("last_total", total);

        if (ticks % 20 == 0) {
            // Regeneration refresh: never lapses while the ward holds.
            mob.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 30, 0));
        }
        if (level != null && ticks % 5 == 0) {
            // Dome swirl: glyphs and totem motes spiraling around the body.
            double angle = ticks * 0.25;
            for (int i = 0; i < 3; i++) {
                double a = angle + i * (Math.PI * 2.0 / 3.0);
                level.sendParticles(ParticleTypes.ENCHANT,
                    mob.getX() + Math.cos(a) * 1.3, mob.getY() + 1.2, mob.getZ() + Math.sin(a) * 1.3,
                    1, 0.0, 0.15, 0.0, 0.05);
            }
            for (int i = 0; i < 2; i++) {
                double a = angle + Math.PI + i * Math.PI;
                level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING,
                    mob.getX() + Math.cos(a) * 1.3, mob.getY() + 1.0, mob.getZ() + Math.sin(a) * 1.3,
                    1, 0.0, 0.1, 0.0, 0.03);
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
        AttributeInstance capacity = mob.getAttribute(Attributes.MAX_ABSORPTION);
        if (capacity != null) {
            capacity.removeModifier(CAPACITY_ID);
        }
        // Clamp leftover absorption to the restored cap.
        if (mob.getAbsorptionAmount() > mob.getMaxAbsorption()) {
            mob.setAbsorptionAmount(mob.getMaxAbsorption());
        }
    }
}
