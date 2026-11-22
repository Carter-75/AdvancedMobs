package mod.advancedmobs.ability.snowgolem;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
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

import java.util.UUID;

/**
 * Orbiting Snow Shield (Snow Golem ability 4 of 5,
 * defense).
 *
 * WHAT IT DOES: three snow blocks tear free of the ground
 * and take up a slow orbit around the golem, trailing
 * SNOWFLAKE sparkles. While any block remains the golem is
 * hardened by RESISTANCE, and every hit that lands on the
 * golem shatters one orbiting block into a burst of
 * snowball bits instead of passing clean: the shield can
 * eat three hits before it is gone.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the golem's current target within 12 blocks (distance
 *   squared 144).
 * - Duration: up to 400 ticks (20 seconds) from start(),
 *   ending early the moment the last block shatters.
 * - The blocks (start()): 3 block displays showing snow
 *   blocks at scale 0.55, built through the shared factory
 *   (full brightness, teleport duration 2 so the per-tick
 *   snaps glide). Documented delivery of the design's
 *   "block_display passengers with interpolation": 26.2
 *   displays are configured through NBT and a display
 *   passenger's offset does not inherit the vehicle's
 *   facing, so the blocks are separate entities snapped in
 *   code on an orbit path (the Obsidian Crust / Blackstone
 *   Bulwark precedent), which reads identically in game. A
 *   display is a pure visual: no hitbox, no collision, and
 *   NOT a block change, so the mobGriefing gate does not
 *   apply to it.
 * - The orbit (every tick): block i rides at angle
 *   (ticks times 0.06 radians) plus i times 120 degrees,
 *   radius 1.7 blocks around the golem, at height 1.1 above
 *   its feet plus a gentle bob of 0.15 times the sine of
 *   (ticks times 0.1 plus i times 2.1). One SNOWFLAKE
 *   trails off each block every 4 ticks.
 * - The hardening: RESISTANCE I for 420 ticks applied in
 *   start() and removed in end() (the Fortress Form
 *   pattern; if the golem owns a permanent resistance
 *   upgrade from its config tree, ScalingSystem re-applies
 *   it on its own schedule after the removal).
 * - Interception (health poll every tick, the Living
 *   Mountain / Rod Aegis pattern): the golem's health is
 *   compared against the last polled value; any drop means
 *   a hit landed, and the first surviving block shatters:
 *   10 ITEM_SNOWBALL and 4 SNOWFLAKE at the block's
 *   position, a SNOW_BREAK (volume 1.0, pitch 0.9), and the
 *   display is discarded. At most one block shatters per
 *   tick, so a burst of simultaneous hits still costs one
 *   block per tick at most. Any health drop counts (melee,
 *   projectiles, fire): the poll cannot tell hits apart,
 *   which is documented here as intended: the shield eats
 *   hits, not hit types. Health gains simply re-baseline
 *   the poll.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6): this ability places and
 * breaks no real blocks, so the mobGriefing gate does not
 * apply: the shield blocks are display entities, and
 * everything else is an effect and particles.
 *
 * WHAT IT LOOKS LIKE: a SNOW_GOLEM_AMBIENT (volume 1.0,
 * pitch 1.1) and a lift-off swirl of 8 SNOWFLAKE at the
 * start, three snow blocks circling the golem at chest
 * height trailing flakes, and a SNOW_BREAK crack with a
 * spray of snowball bits each time one shatters.
 *
 * PERFORMANCE: per tick at most 3 display resolves and
 * snaps, one health compare, and 3 particles every 4
 * ticks; a shatter is one bounded burst, at most 3 per
 * activation. Particle budget per activation: opening 8,
 * trails at most 100 emissions times 3 = 300, shatters at
 * most 3 times 14 = 42, total at most 350 particles.
 *
 * State tag keys: "blocks" (list of compounds, one per
 * surviving shield block: "id", the display's UUID; a
 * shattered block's entry is removed), "lastHealth" (the
 * health poll baseline, a float).
 */
public final class OrbitingSnowShieldAbility implements MobAbility {

    static final int DURATION_TICKS = 400;
    private static final int BLOCK_COUNT = 3;
    private static final float BLOCK_SCALE = 0.55f;
    private static final double ORBIT_RADIUS = 1.7;
    private static final double ORBIT_HEIGHT = 1.1;
    private static final double ORBIT_SPEED = 0.06;

    @Override
    public String id() {
        return "orbiting_snow_shield";
    }

    @Override
    public String displayName() {
        return "Orbiting Snow Shield";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        ListTag blocks = new ListTag();
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            for (int i = 0; i < BLOCK_COUNT; i++) {
                Entity display = SnowGolemAbilitySupport.createSnowBlockDisplay(level, BLOCK_SCALE);
                if (display == null) {
                    continue;
                }
                double angle = i * (Math.PI * 2.0 / BLOCK_COUNT);
                display.snapTo(caster.getX() + Math.cos(angle) * ORBIT_RADIUS,
                    caster.getY() + ORBIT_HEIGHT, caster.getZ() + Math.sin(angle) * ORBIT_RADIUS,
                    0.0f, 0.0f);
                if (!level.addFreshEntity(display)) {
                    continue;
                }
                CompoundTag entry = new CompoundTag();
                entry.store("id", UUIDUtil.CODEC, display.getUUID());
                blocks.add(entry);
            }
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.SNOW_GOLEM_AMBIENT, SoundSource.HOSTILE, 1.0f, 1.1f);
            level.sendParticles(ParticleTypes.SNOWFLAKE, caster.getX(), caster.getY() + 0.4, caster.getZ(),
                8, 1.2, 0.4, 1.2, 0.05);
        }
        state.put("blocks", blocks);
        state.putFloat("lastHealth", caster.getHealth());
        caster.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, DURATION_TICKS + 20, 0));
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
        ListTag blocks = state.getListOrEmpty("blocks");

        // The orbit: snap every surviving block to its path
        // point for this tick.
        double baseAngle = ticks * ORBIT_SPEED;
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag entry = blocks.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            Entity display = id != null ? level.getEntity(id) : null;
            if (display == null) {
                continue;
            }
            double angle = baseAngle + i * (Math.PI * 2.0 / BLOCK_COUNT);
            double x = caster.getX() + Math.cos(angle) * ORBIT_RADIUS;
            double z = caster.getZ() + Math.sin(angle) * ORBIT_RADIUS;
            double y = caster.getY() + ORBIT_HEIGHT + 0.15 * Math.sin(ticks * 0.1 + i * 2.1);
            display.snapTo(x, y, z, 0.0f, 0.0f);
            if (ticks % 4 == 0) {
                level.sendParticles(ParticleTypes.SNOWFLAKE, x, y, z, 1, 0.15, 0.15, 0.15, 0.02);
            }
        }

        // The interception poll: a health drop shatters one
        // block. The baseline always re-reads afterwards.
        float health = caster.getHealth();
        float lastHealth = state.getFloatOr("lastHealth", health);
        if (health < lastHealth - 0.01f && !blocks.isEmpty()) {
            shatterFirstBlock(ctx, level, blocks);
        }
        state.putFloat("lastHealth", health);
    }

    /** Shatters the first surviving shield block and removes its entry from the list. */
    private static void shatterFirstBlock(AbilityContext ctx, ServerLevel level, ListTag blocks) {
        CompoundTag entry = blocks.getCompoundOrEmpty(0);
        blocks.remove(0);
        ctx.state().put("blocks", blocks);
        UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
        Entity display = id != null ? level.getEntity(id) : null;
        Mob caster = ctx.mob();
        double x = display != null ? display.getX() : caster.getX();
        double y = display != null ? display.getY() : caster.getY() + ORBIT_HEIGHT;
        double z = display != null ? display.getZ() : caster.getZ();
        if (display != null) {
            display.discard();
        }
        level.sendParticles(ParticleTypes.ITEM_SNOWBALL, x, y, z, 10, 0.3, 0.3, 0.3, 0.15);
        level.sendParticles(ParticleTypes.SNOWFLAKE, x, y, z, 4, 0.3, 0.3, 0.3, 0.05);
        level.playSound(null, x, y, z, SoundEvents.SNOW_BREAK, SoundSource.HOSTILE, 1.0f, 0.9f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS
            || ctx.state().getListOrEmpty("blocks").isEmpty();
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.removeEffect(MobEffects.RESISTANCE);
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag blocks = ctx.state().getListOrEmpty("blocks");
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag entry = blocks.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            Entity display = id != null ? level.getEntity(id) : null;
            if (display != null) {
                display.discard();
            }
        }
    }
}
