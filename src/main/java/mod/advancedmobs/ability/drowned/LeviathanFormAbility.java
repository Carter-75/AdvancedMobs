package mod.advancedmobs.ability.drowned;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Items;

import java.util.UUID;

/**
 * Leviathan Form (Drowned ability 5 of 5, transformation).
 *
 * WHAT IT DOES: the drowned swells into a deep sea brute, half
 * again its normal size, with three prismarine shards orbiting its
 * bulk at full brightness and electricity snapping between them.
 * The water itself feeds it through a conduit's power, its blows
 * land harder, and its eyes burn with a glowing outline that shows
 * through the murk.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists and is within 16 blocks
 *   (distance squared 256).
 * - Duration: exactly 300 ticks (15 seconds) from start().
 * - The surge (start(), all removed in end()): a SCALE add_value
 *   attribute modifier of +0.75 (modifier id
 *   advancedmobs:leviathan_scale), so the drowned stands 1.75 times
 *   its normal height with the bounding box to match, plus three
 *   effects at amplifier 0 for the full 300 ticks: STRENGTH (its
 *   melee hits land 3 points harder per hit), CONDUIT_POWER
 *   (underwater sight, faster underwater mining, and no drowning:
 *   the water itself feeding it), and GLOWING (the burning outline
 *   visible through blocks and murk). (A drowned that somehow had
 *   one of these effects from another source would lose it at
 *   end(), the accepted edge case shared with every fixed-duration
 *   effect in this system.)
 * - The orbiting shards: 3 ItemDisplay entities showing a
 *   prismarine shard, created in start() through
 *   EntityType.loadEntityRecursive from NBT (display setters are
 *   private in 26.2; the NBT summon path is the supported way to
 *   configure a display, the same path Grave Eruption uses). The
 *   NBT sets the item stack, the "fixed" item display context, a
 *   brightness override of block 15 and sky 15 (full brightness in
 *   any murk), and a teleport duration of 3 ticks so the orbit
 *   glides instead of snapping. Every tick, each shard is
 *   teleported to its orbit position: radius 1.3 blocks from the
 *   drowned's center, 1.3 blocks above its feet plus a gentle bob
 *   of plus or minus 0.15, at an angle that advances 4 degrees per
 *   tick with the shards spaced 120 degrees apart, so the ring
 *   circles the brute once every 4.5 seconds. The shard UUIDs are
 *   tracked in the state tag ("shards"); a shard that no longer
 *   resolves is simply skipped.
 * - ELECTRIC_SPARK snaps: every 5 ticks, 2 ELECTRIC_SPARK particles
 *   at one shard's orbit position, computed from the same orbit
 *   math (no entity resolution needed for the particles), cycling
 *   through the three shards in turn.
 * - end(): the SCALE modifier is removed (the brute shrinks back),
 *   the three effects are removed, and every tracked shard display
 *   is discarded. If the caster dies mid-form, the framework
 *   termination path runs this same end(), so the shards never
 *   outlive the ability or their brute.
 *
 * WHAT IT LOOKS LIKE: a drowned at 1.75 times size, outlined in
 * glowing light, ringed by three bright prismarine shards circling
 * it in the water, with ELECTRIC_SPARK crackling between them every
 * quarter second and a 12-spark burst at the moment the form takes.
 * Its effect sparkles (STRENGTH, CONDUIT_POWER, GLOWING) ride on the
 * vanilla effect rendering.
 *
 * PERFORMANCE: tick() is deliberately small: 3 orbit position
 * computations (trig on the tick count) and at most 3 entity
 * resolutions and teleports per tick, no entity scans, no registry
 * or config lookups (the shard item id is resolved once in start()
 * for the NBT). The spark pulses every 5 ticks are pure math.
 * Particle budget per activation: 12 at start plus 60 spark pulses
 * times 2 = 120, total at most 132 particles. The 3 displays are 3
 * extra entities for 15 seconds, discarded in end().
 *
 * State tag keys: "shards" (list of compounds, one per shard
 * display: "id", the display's UUID).
 */
public final class LeviathanFormAbility implements MobAbility {

    static final int DURATION_TICKS = 300;
    private static final int SHARD_COUNT = 3;
    private static final int SPARK_INTERVAL = 5;
    private static final double ORBIT_RADIUS = 1.3;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "leviathan_scale");

    @Override
    public String id() {
        return "leviathan_form";
    }

    @Override
    public String displayName() {
        return "Leviathan Form";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return ctx.target() != null && ctx.mob().distanceToSqr(ctx.target()) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();

        // The surge: size and strength, fed by the water itself.
        AttributeInstance scale = mob.getAttribute(Attributes.SCALE);
        if (scale != null) {
            scale.addOrUpdateTransientModifier(
                new AttributeModifier(SCALE_ID, 0.75, AttributeModifier.Operation.ADD_VALUE));
        }
        mob.addEffect(new MobEffectInstance(MobEffects.STRENGTH, DURATION_TICKS, 0));
        mob.addEffect(new MobEffectInstance(MobEffects.CONDUIT_POWER, DURATION_TICKS, 0));
        mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, DURATION_TICKS, 0));

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            ctx.state().put("shards", new ListTag());
            return;
        }

        // The orbiting shards: prismarine item displays at full
        // brightness, configured through NBT (display setters are
        // private in 26.2; loadEntityRecursive is the supported
        // spawn path for them).
        ListTag shards = new ListTag();
        CompoundTag template = shardTag();
        for (int i = 0; i < SHARD_COUNT; i++) {
            Entity display = EntityType.loadEntityRecursive(EntityTypes.ITEM_DISPLAY, template.copy(), level,
                EntitySpawnReason.COMMAND, net.minecraft.world.entity.EntityProcessor.NOP);
            if (display == null) {
                continue;
            }
            double[] pos = orbitPosition(mob, 0, i);
            display.snapTo(pos[0], pos[1], pos[2]);
            if (!level.addFreshEntity(display)) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, display.getUUID());
            shards.add(entry);
        }
        ctx.state().put("shards", shards);

        // The form takes: a burst of sparks off the brute's bulk.
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, mob.getX(), mob.getY() + 1.2, mob.getZ(),
            12, 0.7, 0.8, 0.7, 0.15);
    }

    /** The NBT for one orbiting shard display: a prismarine shard at full brightness, gliding. */
    private static CompoundTag shardTag() {
        CompoundTag tag = new CompoundTag();
        CompoundTag item = new CompoundTag();
        item.putString("id", BuiltInRegistries.ITEM.getKey(Items.PRISMARINE_SHARD).toString());
        item.putInt("count", 1);
        tag.put("item", item);
        tag.putString("item_display", "fixed");
        CompoundTag brightness = new CompoundTag();
        brightness.putInt("block", 15);
        brightness.putInt("sky", 15);
        tag.put("brightness", brightness);
        tag.putInt("teleport_duration", 3);
        return tag;
    }

    /**
     * The orbit position of shard index at the given activation tick:
     * radius 1.3 around the mob, 1.3 above its feet with a gentle
     * bob, angle advancing 4 degrees per tick, shards 120 degrees
     * apart. Pure math, used for both the teleports and the sparks.
     */
    private static double[] orbitPosition(Mob mob, int ticks, int index) {
        double angle = Math.toRadians(ticks * 4.0 + index * 120.0);
        double bob = 0.15 * Math.sin(ticks * 0.3 + index * 2.1);
        return new double[]{
            mob.getX() + Math.cos(angle) * ORBIT_RADIUS,
            mob.getY() + 1.3 + bob,
            mob.getZ() + Math.sin(angle) * ORBIT_RADIUS
        };
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        int ticks = ctx.ticksActive();

        // The orbit: each shard glides to its position for this tick.
        ListTag shards = ctx.state().getListOrEmpty("shards");
        for (int i = 0; i < shards.size(); i++) {
            CompoundTag entry = shards.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID shardId = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (shardId == null) {
                continue;
            }
            Entity display = level.getEntity(shardId);
            if (display != null) {
                double[] pos = orbitPosition(mob, ticks, i);
                display.teleportTo(pos[0], pos[1], pos[2]);
            }
        }

        if (ticks % SPARK_INTERVAL == 0) {
            // Sparks snap between the shards: 2 at one shard's
            // position, cycling through the ring in turn.
            int index = (ticks / SPARK_INTERVAL) % SHARD_COUNT;
            double[] pos = orbitPosition(mob, ticks, index);
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, pos[0], pos[1], pos[2],
                2, 0.15, 0.15, 0.15, 0.05);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        // The brute shrinks back and the water lets go of it.
        AttributeInstance scale = mob.getAttribute(Attributes.SCALE);
        if (scale != null) {
            scale.removeModifier(SCALE_ID);
        }
        mob.removeEffect(MobEffects.STRENGTH);
        mob.removeEffect(MobEffects.CONDUIT_POWER);
        mob.removeEffect(MobEffects.GLOWING);

        // The shards wink out with the form.
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag shards = ctx.state().getListOrEmpty("shards");
        for (int i = 0; i < shards.size(); i++) {
            CompoundTag entry = shards.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID shardId = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (shardId == null) {
                continue;
            }
            Entity display = level.getEntity(shardId);
            if (display != null) {
                display.discard();
            }
        }
    }
}
