package mod.advancedmobs.ability.allay;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Keepsake Cyclone (Allay ability 5 of 5, control).
 *
 * WHAT IT DOES: the allay spins up a gentle cyclone of its
 * loose treasures: five trinkets shown as item displays
 * orbiting it in a rising helix threaded with enchant
 * particles and drifting item motes. The cyclone tugs
 * enemies off balance toward its center and shreds their
 * aim, while the allay rides the top of its own storm,
 * hovering over the fight, untouchable and delighted.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the allay's current target within 14 blocks (distance
 *   squared 196).
 * - The treasures (start()): 5 item displays at scale 0.7,
 *   one per trinket, in this fixed order: EMERALD,
 *   AMETHYST_SHARD, GOLD_INGOT, DIAMOND, GLOW_BERRIES. Their
 *   UUIDs are stored in the state tag's "props" list. An
 *   ALLAY_AMBIENT_WITH_ITEM sound (volume 1.0, pitch 1.3)
 *   marks the spin-up.
 * - Duration: 300 ticks (15 seconds), hard finish.
 * - The ride (every tick): the allay steers by velocity
 *   write toward a hover point 3.0 blocks above its target's
 *   live position at a capped speed of 0.4 blocks per tick:
 *   riding the top of the storm. (The design's levitation
 *   micro doses are carried by this steering instead: the
 *   allay is a natural flier, and a levitation effect would
 *   fight the velocity writes that hold it on station. With
 *   no live target it hovers in place.)
 * - The helix (every tick): each prop is snapped to its
 *   orbit point around the allay: angle = tick times 0.16
 *   plus the prop's index times two fifths of a turn, radius
 *   2.2, height = the allay's height plus 0.4 plus a sine
 *   sway of 0.7 (phase tick times 0.12 plus index times
 *   1.3): a rising, weaving helix. The displays glide on
 *   their 2-tick teleport duration.
 * - The sparkle (every 2 ticks): 1 ENCHANT particle at the
 *   orbit point of the prop indexed by (tick over 2) modulo
 *   5. Every 4 ticks: 1 ITEM particle of the trinket indexed
 *   by (tick over 4) modulo 5 at that prop's orbit point,
 *   using the five pre-built item particle options (built
 *   once as static finals, never per tick).
 * - The tug (every 10 ticks): one bounded query at radius
 *   3.0 around the allay (3 below to 3 above): every enemy
 *   (package rules) is pulled toward the allay's center: its
 *   velocity gains 0.3 blocks per tick toward the center
 *   horizontally (normalized, added to its current motion,
 *   hurtMarked set), and it gains SLOWNESS I and NAUSEA I
 *   refreshed to 30 ticks: off balance and unable to aim,
 *   the design's shredded aim carried by the nausea.
 * - end(): the 5 prop displays are discarded. The effects
 *   ride their own 30-tick clocks; nothing else was changed.
 *
 * WHAT IT LOOKS LIKE: a tiny storm of glittering keepsakes
 * wheeling around a hovering allay, enchant motes threading
 * the helix, and enemies stumbling inward through it with
 * their heads swimming.
 *
 * PERFORMANCE: the per-tick work is one steering write and
 * 5 display snaps (5 UUID resolves); the sparkle is 1 send
 * every 2 ticks and 1 every 4; the tug is one bounded query
 * every 10 ticks. The item particle options are pre-built
 * static finals; the trinket table is a static final array.
 * Particle budget per activation: ENCHANT 150 plus ITEM 75,
 * total at most 225 particles.
 *
 * TERRAIN: despite the design's terrain tag, this ability
 * changes no blocks: the treasures are display entities and
 * the tug is velocity and effects, so the mobGriefing gate
 * does not apply.
 *
 * State tag keys: "props" (list of compounds, one per
 * trinket display: "id", the display's UUID).
 */
public final class KeepsakeCycloneAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int TUG_INTERVAL = 10;
    private static final double HOVER_HEIGHT = 3.0;
    private static final double RIDE_SPEED = 0.4;
    private static final double ORBIT_RADIUS = 2.2;
    private static final double ORBIT_STEP = 0.16;
    private static final double TUG_RADIUS = 3.0;
    private static final double TUG_STRENGTH = 0.3;
    private static final int EFFECT_TICKS = 30;
    private static final float PROP_SCALE = 0.7f;

    /** The five keepsakes, in orbit order. A static table, resolved once. */
    private static final Item[] TRINKETS = {
        Items.EMERALD, Items.AMETHYST_SHARD, Items.GOLD_INGOT, Items.DIAMOND, Items.GLOW_BERRIES
    };

    /** One pre-built ITEM particle option per trinket, same order as TRINKETS. */
    private static final ItemParticleOption[] TRINKET_PARTICLES = {
        new ItemParticleOption(ParticleTypes.ITEM, Items.EMERALD),
        new ItemParticleOption(ParticleTypes.ITEM, Items.AMETHYST_SHARD),
        new ItemParticleOption(ParticleTypes.ITEM, Items.GOLD_INGOT),
        new ItemParticleOption(ParticleTypes.ITEM, Items.DIAMOND),
        new ItemParticleOption(ParticleTypes.ITEM, Items.GLOW_BERRIES)
    };

    @Override
    public String id() {
        return "keepsake_cyclone";
    }

    @Override
    public String displayName() {
        return "Keepsake Cyclone";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();
        ListTag props = new ListTag();
        ctx.state().put("props", props);
        if (level == null) {
            return;
        }
        for (int i = 0; i < TRINKETS.length; i++) {
            Entity display = AllayAbilitySupport.createItemDisplay(level, TRINKETS[i], PROP_SCALE);
            if (display == null) {
                continue;
            }
            double[] point = orbitPoint(caster, 0, i);
            display.snapTo(point[0], point[1], point[2]);
            level.addFreshEntity(display);
            AllayAbilitySupport.trackEntity(ctx.state(), "props", display);
        }
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.ALLAY_AMBIENT_WITH_ITEM, SoundSource.HOSTILE, 1.0f, 1.3f);
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

        // The ride: hover over the fight at the storm's top.
        LivingEntity target = ctx.target();
        if (target != null) {
            AllayAbilitySupport.steerToward(ctx,
                target.getX(), target.getY() + HOVER_HEIGHT, target.getZ(), RIDE_SPEED);
        } else {
            AllayAbilitySupport.steerToward(ctx, caster.getX(), caster.getY(), caster.getZ(), RIDE_SPEED);
        }

        // The helix: snap every prop to its orbit point.
        ListTag props = state.getListOrEmpty("props");
        for (int i = 0; i < props.size(); i++) {
            Entity display = AllayAbilitySupport.resolveTracked(level, props.getCompoundOrEmpty(i));
            if (display != null) {
                double[] point = orbitPoint(caster, ticks, i);
                display.snapTo(point[0], point[1], point[2]);
            }
        }

        // The sparkle: enchant motes and drifting keepsake crumbs.
        if (ticks % 2 == 0) {
            double[] point = orbitPoint(caster, ticks, (ticks / 2) % TRINKETS.length);
            level.sendParticles(ParticleTypes.ENCHANT, point[0], point[1], point[2],
                1, 0.1, 0.1, 0.1, 0.0);
        }
        if (ticks % 4 == 0) {
            int index = (ticks / 4) % TRINKETS.length;
            double[] point = orbitPoint(caster, ticks, index);
            level.sendParticles(TRINKET_PARTICLES[index], point[0], point[1], point[2],
                1, 0.1, 0.1, 0.1, 0.0);
        }

        // The tug: pull enemies off balance toward the center.
        if (ticks % TUG_INTERVAL == 0) {
            List<LivingEntity> victims = AllayAbilitySupport.enemiesInBox(ctx,
                caster.getX(), caster.getY(), caster.getZ(), TUG_RADIUS, 3.0, 3.0);
            for (LivingEntity victim : victims) {
                double dx = caster.getX() - victim.getX();
                double dz = caster.getZ() - victim.getZ();
                double length = Math.sqrt(dx * dx + dz * dz);
                if (length > 1.0e-4) {
                    net.minecraft.world.phys.Vec3 motion = victim.getDeltaMovement();
                    victim.setDeltaMovement(
                        motion.x + dx / length * TUG_STRENGTH,
                        motion.y,
                        motion.z + dz / length * TUG_STRENGTH);
                    victim.hurtMarked = true;
                }
                victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, EFFECT_TICKS, 0));
                victim.addEffect(new MobEffectInstance(MobEffects.NAUSEA, EFFECT_TICKS, 0));
            }
        }
    }

    /** The orbit point of the prop with the given index at the given tick. */
    private static double[] orbitPoint(Mob caster, int ticks, int index) {
        double angle = ticks * ORBIT_STEP + index * (Math.PI * 2.0 / TRINKETS.length);
        return new double[] {
            caster.getX() + Math.cos(angle) * ORBIT_RADIUS,
            caster.getY() + 0.4 + Math.sin(ticks * 0.12 + index * 1.3) * 0.7,
            caster.getZ() + Math.sin(angle) * ORBIT_RADIUS
        };
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
        ListTag props = ctx.state().getListOrEmpty("props");
        for (int i = 0; i < props.size(); i++) {
            Entity display = AllayAbilitySupport.resolveTracked(level, props.getCompoundOrEmpty(i));
            if (display != null) {
                display.discard();
            }
        }
    }
}
