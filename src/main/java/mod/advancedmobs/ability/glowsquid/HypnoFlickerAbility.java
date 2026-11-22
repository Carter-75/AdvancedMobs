package mod.advancedmobs.ability.glowsquid;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Hypno Flicker (Glow Squid ability 2 of 5, control).
 *
 * WHAT IT DOES: the glow squid hangs in the water
 * and strobes. Its spots roll in a slow flashing
 * pattern, bright flash sprites pulsing over drifting
 * dust motes like marine snow. Enemies that watch
 * the display are nauseated and slowed, and instead
 * of fighting they drift helplessly toward the
 * light, until a hard hit snaps them out of it.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat
 *   gate, plus the squid's current target within 12
 *   blocks (distance squared 144), plus the squid in
 *   water.
 * - The anchor: the caster's position in start() is
 *   recorded, and the caster is steered back onto it
 *   at 0.3 blocks per tick every tick: the display
 *   hangs nearly motionless while it strobes (never
 *   NoAi; steering only).
 * - The strobe: every 10 ticks, 1 FLASH particle in
 *   pale cyan (ARGB 0xFF8FE8F5, pre-built option) at
 *   the caster plus GLOW_SQUID_AMBIENT at volume 0.5,
 *   pitch 1.3. Marine snow: 2 DUST motes (RGB
 *   0xBFE8E0, scale 1.0, pre-built option) every 4
 *   ticks at random offsets within 2.0 blocks of the
 *   caster.
 * - The audience (every 10 ticks, with the strobe):
 *   one bounded query, radius 8.0 around the caster,
 *   vertical reach 3.0 both ways. An enemy (the
 *   package enemy rule) is watching when the dot
 *   product of its look vector with the direction
 *   from its eyes to the caster is at least 0.3
 *   (about a 72 degree half-angle cone). A watching
 *   enemy that has not snapped out gains NAUSEA I
 *   and SLOWNESS I refreshed to 30 ticks, and is
 *   pulled toward the caster: 0.12 blocks per tick
 *   of horizontal velocity added toward the caster's
 *   position (hurtMarked set), the drift toward the
 *   light.
 * - The snap-out: the state tag's watch list records
 *   each affected enemy's health at every audience
 *   pulse (list capped at 16). When an enemy's
 *   health has dropped by 2.0 or more since its last
 *   record, it has taken a hard hit: it is moved to
 *   the snapped list and is never affected again
 *   this activation (its nausea and slowness ride
 *   out their remaining 30-tick clocks and expire).
 *   Enemies not seen at a pulse keep their last
 *   record, so a hit taken between pulses still
 *   counts at the next one.
 * - Duration: 300 ticks, hard finish.
 *
 * WHAT IT LOOKS LIKE: a glowing squid hanging still
 * in dark water, strobing pale flashes over falling
 * snow motes, while the fish and predators facing it
 * slow, wobble, and drift in toward the light.
 *
 * PERFORMANCE: the audience is one bounded query
 * every 10 ticks with a watch-list pass of at most
 * 16 entries; the anchor is one steering write per
 * tick; the snow is 2 sends every 4 ticks. Particle
 * budget per activation: 30 strobes, at most 150
 * snow, total at most 180.
 *
 * TERRAIN: this ability changes no blocks; the
 * display is particles and the trance is effects
 * plus velocity, so the mobGriefing gate does not
 * apply.
 *
 * CLEANUP: end() has nothing to strip: the caster
 * takes no modifiers or self effects. Victim nausea
 * and slowness expire on their own clocks.
 *
 * State tag keys: "ax", "ay", "az" (the anchor),
 * "watch" (list of compounds: "id", "hp", one per
 * entranced enemy), "snapped" (list of compounds:
 * "id", one per enemy that took a hard hit).
 */
public final class HypnoFlickerAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int PULSE_INTERVAL = 10;
    private static final double AUDIENCE_RADIUS = 8.0;
    private static final double WATCH_DOT = 0.3;
    private static final double PULL_SPEED = 0.12;
    private static final float SNAP_DROP = 2.0f;
    private static final int LIST_CAP = 16;

    /** The strobe flash: pale cyan, pre-built so the pulse never allocates it. */
    private static final ColorParticleOption STROBE_FLASH =
        ColorParticleOption.create(ParticleTypes.FLASH, 0xFF8FE8F5);
    /** The marine snow: pale blue-green dust at scale 1.0, pre-built. */
    private static final DustParticleOptions MARINE_SNOW =
        new DustParticleOptions(0xBFE8E0, 1.0f);

    @Override
    public String id() {
        return "hypno_flicker";
    }

    @Override
    public String displayName() {
        return "Hypno Flicker";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().isInWater()
            && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        state.putDouble("ax", caster.getX());
        state.putDouble("ay", caster.getY());
        state.putDouble("az", caster.getZ());
        state.put("watch", new ListTag());
        state.put("snapped", new ListTag());
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();

        // Hang on the anchor while the display runs.
        GlowSquidAbilitySupport.steerToward(caster,
            state.getDoubleOr("ax", caster.getX()),
            state.getDoubleOr("ay", caster.getY()),
            state.getDoubleOr("az", caster.getZ()), 0.3);

        if (ticks % 4 == 0) {
            double ox = (ctx.random().nextDouble() - 0.5) * 4.0;
            double oy = (ctx.random().nextDouble() - 0.5) * 3.0;
            double oz = (ctx.random().nextDouble() - 0.5) * 4.0;
            level.sendParticles(MARINE_SNOW, caster.getX() + ox, caster.getY() + oy, caster.getZ() + oz,
                2, 0.2, 0.2, 0.2, 0.01);
        }

        if (ticks % PULSE_INTERVAL == 0) {
            level.sendParticles(STROBE_FLASH, caster.getX(), caster.getY() + 0.3, caster.getZ(),
                1, 0.0, 0.0, 0.0, 0.0);
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.GLOW_SQUID_AMBIENT, SoundSource.HOSTILE, 0.5f, 1.3f);
            audience(ctx);
        }
    }

    /** One audience pulse: entrance the watchers, drift them in, record the snap-outs. */
    private void audience(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        ListTag watch = state.getListOrEmpty("watch");
        ListTag snapped = state.getListOrEmpty("snapped");
        ListTag nextWatch = new ListTag();

        List<LivingEntity> nearby = GlowSquidAbilitySupport.enemiesAroundSelf(ctx, AUDIENCE_RADIUS, 3.0, 3.0);
        for (LivingEntity enemy : nearby) {
            UUID id = enemy.getUUID();
            if (containsId(snapped, id)) {
                continue;
            }
            // The hard-hit check runs before the facing check: a
            // snapped enemy is out even if it looks away first.
            CompoundTag record = findRecord(watch, id);
            float health = enemy.getHealth();
            if (record != null && record.getFloatOr("hp", health) - health >= SNAP_DROP) {
                CompoundTag snap = new CompoundTag();
                snap.store("id", UUIDUtil.CODEC, id);
                if (snapped.size() < LIST_CAP) {
                    snapped.add(snap);
                }
                continue;
            }
            if (!isWatching(enemy, caster)) {
                // Not entranced this pulse, but keep its health
                // record fresh so a hit still registers later.
                if (record != null && nextWatch.size() < LIST_CAP) {
                    record.putFloat("hp", health);
                    nextWatch.add(record);
                }
                continue;
            }
            enemy.addEffect(new MobEffectInstance(MobEffects.NAUSEA, 30, 0));
            enemy.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 30, 0));
            // The drift toward the light.
            double dx = caster.getX() - enemy.getX();
            double dz = caster.getZ() - enemy.getZ();
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length > 0.001) {
                Vec3 motion = enemy.getDeltaMovement();
                enemy.setDeltaMovement(
                    motion.x + dx / length * PULL_SPEED,
                    motion.y,
                    motion.z + dz / length * PULL_SPEED);
                enemy.hurtMarked = true;
            }
            CompoundTag entry = record != null ? record : new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, id);
            entry.putFloat("hp", health);
            if (nextWatch.size() < LIST_CAP) {
                nextWatch.add(entry);
            }
        }
        // Records for enemies out of range this pulse carry over,
        // so a hard hit between pulses is still caught.
        for (int i = 0; i < watch.size() && nextWatch.size() < LIST_CAP; i++) {
            CompoundTag entry = watch.getCompoundOrEmpty(i);
            UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (id != null && !containsId(nextWatch, id) && !containsId(snapped, id)
                && find(nearby, id) == null) {
                nextWatch.add(entry);
            }
        }
        state.put("watch", nextWatch);
        state.put("snapped", snapped);
    }

    /** Whether the enemy is facing the caster closely enough to be watching the display. */
    private boolean isWatching(LivingEntity enemy, Mob caster) {
        Vec3 look = enemy.getLookAngle();
        Vec3 eye = enemy.getEyePosition();
        double dx = caster.getX() - eye.x;
        double dy = (caster.getY() + 0.3) - eye.y;
        double dz = caster.getZ() - eye.z;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 0.001) {
            return true;
        }
        return (look.x * dx + look.y * dy + look.z * dz) / length >= WATCH_DOT;
    }

    private CompoundTag findRecord(ListTag list, UUID id) {
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompoundOrEmpty(i);
            if (id.equals(entry.read("id", UUIDUtil.CODEC).orElse(null))) {
                return entry;
            }
        }
        return null;
    }

    private boolean containsId(ListTag list, UUID id) {
        return findRecord(list, id) != null;
    }

    private LivingEntity find(List<LivingEntity> entities, UUID id) {
        for (LivingEntity entity : entities) {
            if (entity.getUUID().equals(id)) {
                return entity;
            }
        }
        return null;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to strip: no caster modifiers or self effects.
        // Victim nausea and slowness expire on their own clocks.
    }
}
