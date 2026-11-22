package mod.advancedmobs.ability.tropicalfish;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Anemone Guard (Tropical Fish ability 3 of 5,
 * defense).
 *
 * WHAT IT DOES: the tropical fish backs into a
 * summoned anemone: a cluster of waving coral fan
 * and glowing plant pieces in neon greens and
 * pinks, lit at full brightness, that sways around
 * the fish like a living shelter. While the fish
 * shelters inside, the anemone grooms it back to
 * health, and anything that strikes the fish gets
 * stung: poisoned by the anemone's tentacles for
 * its trouble.
 *
 * MECHANICS (the spec; fix bugs against these
 * numbers):
 * - Trigger (canTrigger): the framework's combat
 *   gate, plus the fish's current target within 10
 *   blocks (distance squared 100), plus the fish in
 *   water (an anemone is a reef shelter).
 * - Duration: 300 ticks (15 seconds). The shelter
 *   anchor is the fish's start position, stored in
 *   the state tag; the prop is built there and the
 *   fish holds to it for the whole activation.
 * - The prop (start()): 6 display pieces around the
 *   anchor, all at full brightness (the design's
 *   brightness override line; display NBT brightness
 *   block and sky 15, see the support class):
 *   - 3 block displays of coral fan states at scale
 *     1.2 (brain coral fan for the pink, tube and
 *     horn coral fans), on a radius 0.7 ring at
 *     heights 0.1 to 0.3, tilted 0.25 radians with
 *     alternating axes and signs;
 *   - 3 item displays at scale 0.8 on a radius 0.5
 *     ring at heights 0.35 to 0.55: a sea pickle,
 *     glow berries, and a pink tulip (the design's
 *     neon greens and pinks; sea pickles and glow
 *     berries are themselves light sources, and the
 *     full brightness NBT makes every piece read as
 *     glowing).
 *   Each piece's base offset and a sway phase are
 *   recorded in its tracked entry. TROPICAL_FISH_AMBIENT
 *   plays at volume 1.0, pitch 1.1.
 * - The shelter (every tick): the caster is steered
 *   toward the anchor at 0.35 blocks per tick (the
 *   steering helper caps the step at the remaining
 *   distance, so the fish settles onto the anchor
 *   and hovers there instead of orbiting): the fish
 *   stays inside its anemone instead of chasing,
 *   for the whole activation.
 * - The sway (every 5 ticks): every living prop
 *   piece is snapped to its base offset with its
 *   height bobbing on a sine (amplitude 0.12,
 *   advancing 0.11 radians per tick, offset by the
 *   piece's own phase): the cluster waves like a
 *   real anemone in the current. The displays'
 *   2-tick teleport glide (see the support class)
 *   smooths the steps, the design's interpolation
 *   line.
 * - The grooming (start()): the caster gains
 *   REGENERATION I for 320 ticks (the design's
 *   regeneration on self line), removed exactly in
 *   end().
 * - The sting (health poll every 2 ticks, the
 *   Scute Shield pattern): when the caster's health
 *   has dropped since the last poll and at least 10
 *   ticks have passed since the last sting, the
 *   attacker (the caster's vanilla last-hurt-by
 *   record) is checked: if it is an enemy (the
 *   package enemy rule) within 8 blocks, it gains
 *   POISON I for 120 ticks (the design's poison on
 *   attackers line) and 6 SNEEZE motes burst at it,
 *   a puff of stinging green. The poll's stored
 *   health updates every poll either way, so one
 *   hit can only ever sting once.
 * - Ambience: 2 BUBBLE motes at the anchor every 10
 *   ticks, the anemone breathing.
 * - end(): removes REGENERATION from the caster and
 *   discards every prop piece. Idempotent; if the
 *   caster dies first, the framework termination
 *   path runs this same end(), so the anemone never
 *   outlives its fish.
 *
 * WHAT IT LOOKS LIKE: a neon thicket of pink fans
 * and green glowing fruit swaying in the current
 * around one small fish, bubbles drifting up
 * through the tentacles, and every attacker that
 * reaches in pulling back poisoned and green
 * around the gills.
 *
 * PERFORMANCE: start() is 6 display spawns and one
 * effect write. Per tick: one steering write. The
 * sway resolves at most 6 UUIDs every 5 ticks; the
 * sting poll is one health read every 2 ticks with
 * a damage check only on a fresh drop. Particle
 * budget per activation: ambience 30 times 2 = 60,
 * stings at most 30 times 6 = 180, total at most
 * 240.
 *
 * TERRAIN: this ability changes no blocks; the
 * anemone is display entities only, so the gate
 * does not apply.
 *
 * State tag keys: "ax", "ay", "az" (the shelter
 * anchor), "last_hp" (the sting poll's stored
 * health), "sting" (the activation tick of the
 * last sting), "prop" (list of compounds, one per
 * display piece: "id", "ox", "oy", "oz" base
 * offsets, "phase" sway phase).
 */
public final class AnemoneGuardAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int EFFECT_TICKS = 320;
    private static final int SWAY_INTERVAL = 5;
    private static final int POLL_INTERVAL = 2;
    private static final int STING_COOLDOWN_TICKS = 10;
    private static final int POISON_TICKS = 120;
    private static final double STING_RANGE_SQR = 64.0;
    private static final double SHELTER_SPEED = 0.35;

    /** The prop's fan states (pink first), cycled by piece index. */
    private static final BlockState[] FAN_STATES = {
        Blocks.BRAIN_CORAL_FAN.defaultBlockState(),
        Blocks.TUBE_CORAL_FAN.defaultBlockState(),
        Blocks.HORN_CORAL_FAN.defaultBlockState()
    };

    @Override
    public String id() {
        return "anemone_guard";
    }

    @Override
    public String displayName() {
        return "Anemone Guard";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().isInWater()
            && ctx.mob().distanceToSqr(target) <= 100.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        state.putDouble("ax", caster.getX());
        state.putDouble("ay", caster.getY());
        state.putDouble("az", caster.getZ());
        state.putFloat("last_hp", caster.getHealth());
        state.putInt("sting", -STING_COOLDOWN_TICKS);
        state.put("prop", new ListTag());

        caster.addEffect(new MobEffectInstance(MobEffects.REGENERATION, EFFECT_TICKS, 0, false, false, false));

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        double ax = caster.getX();
        double ay = caster.getY();
        double az = caster.getZ();

        // The fan pieces: coral fans on the wider, lower ring.
        for (int i = 0; i < 3; i++) {
            double angle = i * Math.PI * 2.0 / 3.0;
            double ox = Math.cos(angle) * 0.7;
            double oz = Math.sin(angle) * 0.7;
            double oy = 0.1 + i * 0.1;
            Entity display = TropicalFishAbilitySupport.createBlockDisplay(level,
                FAN_STATES[i], 1.2f, i % 2 == 0 ? 0.25 : -0.25, i % 2 == 1);
            addPiece(ctx, level, display, ax + ox, ay + oy, az + oz, ox, oy, oz, i * 1.7);
        }
        // The glow pieces: light-bearing items on the tighter, higher ring.
        ItemStack[] glowStacks = {
            new ItemStack(Items.SEA_PICKLE), new ItemStack(Items.GLOW_BERRIES), new ItemStack(Items.PINK_TULIP)
        };
        for (int i = 0; i < 3; i++) {
            double angle = i * Math.PI * 2.0 / 3.0 + Math.PI / 3.0;
            double ox = Math.cos(angle) * 0.5;
            double oz = Math.sin(angle) * 0.5;
            double oy = 0.35 + i * 0.1;
            Entity display = TropicalFishAbilitySupport.createItemDisplay(level, glowStacks[i], 0.8f);
            addPiece(ctx, level, display, ax + ox, ay + oy, az + oz, ox, oy, oz, i * 1.7 + 0.8);
        }
        level.playSound(null, ax, ay, az, SoundEvents.TROPICAL_FISH_AMBIENT,
            SoundSource.HOSTILE, 1.0f, 1.1f);
    }

    /** Places one prop piece, snaps it to its start spot, and tracks it with its sway data. */
    private void addPiece(AbilityContext ctx, ServerLevel level, Entity display,
                          double x, double y, double z, double ox, double oy, double oz, double phase) {
        if (display == null) {
            return;
        }
        display.snapTo(x, y, z, ctx.random().nextFloat() * 360.0f, 0.0f);
        if (!level.addFreshEntity(display)) {
            return;
        }
        TropicalFishAbilitySupport.trackEntity(ctx.state(), "prop", display);
        ListTag prop = ctx.state().getListOrEmpty("prop");
        CompoundTag entry = prop.getCompoundOrEmpty(prop.size() - 1);
        entry.putDouble("ox", ox);
        entry.putDouble("oy", oy);
        entry.putDouble("oz", oz);
        entry.putDouble("phase", phase);
        ctx.state().put("prop", prop);
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
        double ax = state.getDoubleOr("ax", caster.getX());
        double ay = state.getDoubleOr("ay", caster.getY());
        double az = state.getDoubleOr("az", caster.getZ());

        // The shelter: hold to the anchor.
        TropicalFishAbilitySupport.steerToward(caster, ax, ay, az, SHELTER_SPEED);

        // The sway: the cluster waves in the current.
        if (ticks % SWAY_INTERVAL == 0) {
            ListTag prop = state.getListOrEmpty("prop");
            for (int i = 0; i < prop.size(); i++) {
                CompoundTag entry = prop.getCompoundOrEmpty(i);
                Entity piece = TropicalFishAbilitySupport.resolveTracked(level, entry);
                if (piece == null) {
                    continue;
                }
                double bob = Math.sin(ticks * 0.11 + entry.getDoubleOr("phase", 0.0)) * 0.12;
                piece.snapTo(ax + entry.getDoubleOr("ox", 0.0),
                    ay + entry.getDoubleOr("oy", 0.0) + bob,
                    az + entry.getDoubleOr("oz", 0.0), piece.getYRot(), 0.0f);
            }
        }

        // Ambience: the anemone breathes.
        if (ticks % 10 == 0) {
            level.sendParticles(ParticleTypes.BUBBLE, ax, ay + 0.4, az, 2, 0.5, 0.3, 0.5, 0.03);
        }

        // The sting: a fresh health drop poisons whoever dealt it.
        if (ticks % POLL_INTERVAL == 0) {
            float health = caster.getHealth();
            float last = state.getFloatOr("last_hp", health);
            if (health < last - 0.01f && ticks - state.getIntOr("sting", -STING_COOLDOWN_TICKS) >= STING_COOLDOWN_TICKS) {
                LivingEntity attacker = caster.getLastHurtByMob();
                if (attacker != null && attacker.isAlive()
                    && TropicalFishAbilitySupport.isEnemy(ctx, attacker)
                    && caster.distanceToSqr(attacker) <= STING_RANGE_SQR) {
                    state.putInt("sting", ticks);
                    attacker.addEffect(new MobEffectInstance(MobEffects.POISON, POISON_TICKS, 0));
                    level.sendParticles(ParticleTypes.SNEEZE, attacker.getX(),
                        attacker.getY() + attacker.getBbHeight() * 0.6, attacker.getZ(),
                        6, 0.3, 0.3, 0.3, 0.05);
                }
            }
            state.putFloat("last_hp", health);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.removeEffect(MobEffects.REGENERATION);
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag prop = ctx.state().getListOrEmpty("prop");
        for (int i = 0; i < prop.size(); i++) {
            Entity found = TropicalFishAbilitySupport.resolveTracked(level, prop.getCompoundOrEmpty(i));
            if (found != null) {
                found.discard();
            }
        }
    }
}
