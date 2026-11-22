package mod.advancedmobs.ability.parrot;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Feather Cyclone (Parrot ability 2 of 5, aura / attack).
 *
 * WHAT IT DOES: the parrot takes flight and orbits its
 * target in a tight, fast circle, three feather displays
 * circling with it while loose feathers fill the air.
 * Anything caught under the cyclone takes steady pecking
 * damage from the spinning plumage and cannot shake the
 * bird off.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the parrot's current target within 16 blocks (distance
 *   squared 256).
 * - Flight kit (start(), removed in end()): a transient
 *   FLYING_SPEED modifier of +0.2 ADD_VALUE under a fixed
 *   id (base 0.4 to 0.6), the design's flying_speed line,
 *   so the cyclone laps noticeably faster than the parrot's
 *   normal flutter. A PARROT_FLY sound plays at start
 *   (volume 1.2, pitch 1.1).
 * - The feathers (start()): 3 feather item displays (scale
 *   0.7), created through the support's NBT factory and
 *   tracked in the state tag. They are code-snapped, not
 *   true passengers (see the support class comment): every
 *   tick each one is snapped to an orbit point around the
 *   parrot at radius 1.1, angles 120 degrees apart,
 *   advancing 0.3 radians per tick, at the parrot's body
 *   height plus 0.3. Discarded in end().
 * - The orbit (tick(), every tick): the orbit center is the
 *   target's live position. The orbit point is radius 3.0
 *   around the target at the target's height plus 2.5 plus
 *   a gentle bob (sine of ticks times 0.1, amplitude 0.5),
 *   the angle advancing 0.15 radians per tick. The parrot's
 *   velocity is written straight at the orbit point at a
 *   step of 0.55 blocks per tick (the Phantom steering
 *   pattern). If the target dies mid-cyclone, the orbit
 *   center freezes at the last live position read and the
 *   cyclone winds out its remaining ticks over the spot.
 * - The pecking (tick(), every 10 ticks): one bounded query
 *   around the parrot (radius 2.5, reach 3.0 below and 1.0
 *   above, so it catches what stands under the cyclone):
 *   every enemy takes mob-attack damage credited to the
 *   parrot equal to half its live ATTACK_DAMAGE value
 *   (1.5 on an unupgraded parrot), the steady peck of the
 *   spinning plumage.
 * - The air (tick(), every tick): 2 feather ITEM particles
 *   shed at the parrot's body.
 * - Duration: 240 ticks (12 seconds), hard finish.
 * - end(): removes the FLYING_SPEED modifier and discards
 *   the 3 feather displays. Runs on the termination path
 *   too, so a killed parrot leaves no orbiting feathers
 *   behind.
 *
 * WHAT IT LOOKS LIKE: the parrot kicks into a fast, flat
 * circle three blocks out and two and a half up from its
 * victim, three big feathers whirling around it in the
 * opposite spin, loose feathers snowing down the whole
 * time, and every half second something under the circle
 * flinches from another peck.
 *
 * PERFORMANCE: per tick one steering computation, one
 * velocity write, at most 3 display resolves and snaps,
 * and 2 particles; the peck query runs every 10 ticks only.
 * All particle options are the pre-built static feather
 * option. Particle budget per activation: 240 times 2 =
 * 480 feather motes, stated cap 500.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "feathers" (list of compounds, one per
 * feather display: "id", the display's UUID), "center_x",
 * "center_y", "center_z" (the frozen orbit center used
 * once the target is gone).
 */
public final class FeatherCycloneAbility implements MobAbility {

    private static final int DURATION_TICKS = 240;
    private static final int PECK_INTERVAL = 10;
    private static final double ORBIT_RADIUS = 3.0;
    private static final double ORBIT_HEIGHT = 2.5;
    private static final double ORBIT_ANGLE_STEP = 0.15;
    private static final double ORBIT_SPEED = 0.55;
    private static final double FEATHER_ORBIT_RADIUS = 1.1;
    private static final double FEATHER_ANGLE_STEP = 0.3;
    private static final double PECK_RADIUS = 2.5;
    private static final Identifier FLYING_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "feather_cyclone_flying");

    @Override
    public String id() {
        return "feather_cyclone";
    }

    @Override
    public String displayName() {
        return "Feather Cyclone";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        state.put("feathers", new ListTag());
        Mob caster = ctx.mob();
        state.putDouble("center_x", caster.getX());
        state.putDouble("center_y", caster.getY());
        state.putDouble("center_z", caster.getZ());

        ParrotAbilitySupport.addModifier(caster, Attributes.FLYING_SPEED,
            new AttributeModifier(FLYING_ID, 0.2, AttributeModifier.Operation.ADD_VALUE));

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.PARROT_FLY,
            SoundSource.HOSTILE, 1.2f, 1.1f);
        for (int i = 0; i < 3; i++) {
            Entity feather = ParrotAbilitySupport.createItemDisplay(level,
                new ItemStack(Items.FEATHER), 0.7f, null);
            if (feather == null) {
                continue;
            }
            feather.snapTo(caster.getX(), caster.getY() + 0.3, caster.getZ(), 0.0f, 0.0f);
            if (level.addFreshEntity(feather)) {
                ParrotAbilitySupport.trackEntity(state, "feathers", feather);
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

        // The orbit center follows the live target; once the
        // target is gone the last read position stays frozen.
        LivingEntity target = ctx.target();
        if (target != null) {
            state.putDouble("center_x", target.getX());
            state.putDouble("center_y", target.getY());
            state.putDouble("center_z", target.getZ());
        }
        double cx = state.getDoubleOr("center_x", caster.getX());
        double cy = state.getDoubleOr("center_y", caster.getY());
        double cz = state.getDoubleOr("center_z", caster.getZ());

        double angle = ticks * ORBIT_ANGLE_STEP;
        double bob = Math.sin(ticks * 0.1) * 0.5;
        ParrotAbilitySupport.steerToward(caster,
            cx + Math.cos(angle) * ORBIT_RADIUS, cy + ORBIT_HEIGHT + bob,
            cz + Math.sin(angle) * ORBIT_RADIUS, ORBIT_SPEED);

        // The three feathers whirl around the parrot.
        ListTag feathers = state.getListOrEmpty("feathers");
        for (int i = 0; i < feathers.size(); i++) {
            Entity feather = ParrotAbilitySupport.resolveTracked(level, feathers.getCompoundOrEmpty(i));
            if (feather != null) {
                double fa = ticks * FEATHER_ANGLE_STEP + (Math.PI * 2.0 * i) / 3.0;
                feather.snapTo(caster.getX() + Math.cos(fa) * FEATHER_ORBIT_RADIUS,
                    caster.getY() + 0.3, caster.getZ() + Math.sin(fa) * FEATHER_ORBIT_RADIUS,
                    ticks * 6.0f, 0.0f);
            }
        }

        // Loose feathers fill the air.
        level.sendParticles(ParrotAbilitySupport.FEATHER_MOTE, caster.getX(), caster.getY() + 0.3, caster.getZ(),
            2, 0.3, 0.2, 0.3, 0.03);

        // The steady pecking under the cyclone, every 10 ticks.
        if (ticks % PECK_INTERVAL == 0) {
            float peck = (float) (caster.getAttributeValue(Attributes.ATTACK_DAMAGE) * 0.5);
            List<LivingEntity> victims = ParrotAbilitySupport.enemiesInBox(ctx,
                caster.getX(), caster.getY(), caster.getZ(), PECK_RADIUS, 3.0, 1.0);
            for (LivingEntity victim : victims) {
                victim.hurtServer(level, level.damageSources().mobAttack(caster), peck);
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
        ParrotAbilitySupport.removeModifier(caster, Attributes.FLYING_SPEED, FLYING_ID);
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag feathers = ctx.state().getListOrEmpty("feathers");
        for (int i = 0; i < feathers.size(); i++) {
            Entity feather = ParrotAbilitySupport.resolveTracked(level, feathers.getCompoundOrEmpty(i));
            if (feather != null) {
                feather.discard();
            }
        }
    }
}
