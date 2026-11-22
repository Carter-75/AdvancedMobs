package mod.advancedmobs.ability.allay;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.List;

/**
 * Note Block Harmony (Allay ability 1 of 5, aura).
 *
 * WHAT IT DOES: the allay hums a circling tune over the
 * fight, musical notes streaming off it in a spiral while it
 * orbits its target. Allies inside the spiral (and the allay
 * itself) keep gaining speed and regeneration, dancing
 * through the fight, and every note the allay sings widens
 * and brightens the spiral a little more.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the allay's current target within 16 blocks (distance
 *   squared 256).
 * - Duration: 300 ticks (15 seconds), hard finish.
 * - The orbit (every tick): the allay steers by velocity
 *   write toward a point circling its target's live position
 *   at the current spiral radius, 1.8 blocks above the
 *   target's feet with a gentle bob of plus or minus 0.3,
 *   advancing 0.11 radians per tick at a capped speed of
 *   0.45 blocks per tick. With no live target it circles its
 *   own position instead. (Velocity steering per the Bee /
 *   Vex precedent: an allay's flight answers a per-tick
 *   velocity write; no navigation calls, no NoAi.)
 * - The song (every 10 ticks): one note is sung: a
 *   NOTE_BLOCK_HARP sound (volume 1.0) at the next pitch of
 *   an 11-step ladder (semitone offsets -12, -10, -8, -5,
 *   -3, 0, 2, 4, 7, 9, 12, pitch = 2 to the power of
 *   semitones over 12, so 0.5 to 2.0), 2 NOTE particles burst
 *   at the allay, and the sung-note count rises. The spiral
 *   radius is 2.0 plus 0.25 per note sung, capped at 6.0
 *   (reached at 16 notes, tick 160): every note literally
 *   makes the spiral wider, and the harp keeps climbing the
 *   ladder as it brightens.
 * - The spiral trail (every 2 ticks): 1 NOTE particle at a
 *   point circling the allay at radius 0.7, its angle
 *   advancing 0.35 radians per send and its height swaying
 *   plus or minus 0.4 on a slow sine. Vanilla note particles
 *   pick their own shifting colors client side.
 * - The aura (every 20 ticks): one bounded query around the
 *   orbit center (the target's live position, or the
 *   allay's when there is no target) at the CURRENT spiral
 *   radius, 3 blocks below to 3 above: every ally inside
 *   gains SPEED I and REGENERATION I refreshed to 40 ticks,
 *   and the allay refreshes the same two effects on itself
 *   for 40 ticks. Effects ride their own clocks after the
 *   ability ends; nothing is applied that end() must remove,
 *   so end() is empty.
 *
 * WHAT IT LOOKS AND SOUNDS LIKE: a little blue musician
 * circling the fight trailing a spiral of colored notes, a
 * harp arpeggio climbing as the spiral grows, and its
 * friends moving visibly faster and healing as they fight
 * inside the ring.
 *
 * PERFORMANCE: the per-tick work is one steering write and,
 * every 2nd tick, one particle send; the song is one sound
 * and 2 sends every 10 ticks; the aura is one bounded query
 * every 20 ticks. Nothing is resolved per tick from
 * registries or configs; the radius is integer arithmetic on
 * the state tag's note count. Particle budget per
 * activation: trail 150 sends plus song bursts 30 times 2 =
 * 60, total at most 210 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "notes" (notes sung so far, drives the
 * radius and the pitch ladder).
 */
public final class NoteBlockHarmonyAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int SONG_INTERVAL = 10;
    private static final int AURA_INTERVAL = 20;
    private static final int EFFECT_TICKS = 40;
    private static final double BASE_RADIUS = 2.0;
    private static final double RADIUS_PER_NOTE = 0.25;
    private static final double MAX_RADIUS = 6.0;
    private static final double ORBIT_SPEED = 0.45;
    private static final double ORBIT_STEP = 0.11;
    /** Harp pitch ladder as semitone offsets from the note block's base pitch. */
    private static final int[] LADDER = {-12, -10, -8, -5, -3, 0, 2, 4, 7, 9, 12};

    @Override
    public String id() {
        return "note_block_harmony";
    }

    @Override
    public String displayName() {
        return "Note Block Harmony";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().putInt("notes", 0);
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        // The opening hum: the allay's own voice, then the first harp note.
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.ALLAY_AMBIENT_WITH_ITEM, SoundSource.HOSTILE, 1.0f, 1.2f);
        singNote(ctx, level);
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
        double radius = spiralRadius(state);

        // The orbit center: the target's live position, or the
        // allay's own spot when the target is gone.
        LivingEntity target = ctx.target();
        double centerX = target != null ? target.getX() : caster.getX();
        double centerY = target != null ? target.getY() : caster.getY();
        double centerZ = target != null ? target.getZ() : caster.getZ();

        // The orbit: one steering write per tick.
        double angle = ticks * ORBIT_STEP;
        double bob = Math.sin(ticks * 0.05) * 0.3;
        AllayAbilitySupport.steerToward(ctx,
            centerX + Math.cos(angle) * radius,
            centerY + 1.8 + bob,
            centerZ + Math.sin(angle) * radius,
            ORBIT_SPEED);

        // The spiral trail of notes around the allay.
        if (ticks % 2 == 0) {
            double trailAngle = ticks * 0.35;
            level.sendParticles(ParticleTypes.NOTE,
                caster.getX() + Math.cos(trailAngle) * 0.7,
                caster.getY() + 0.6 + Math.sin(ticks * 0.2) * 0.4,
                caster.getZ() + Math.sin(trailAngle) * 0.7,
                1, 0.0, 0.0, 0.0, 0.0);
        }

        // The song: one note every 10 ticks, widening the spiral.
        if (ticks % SONG_INTERVAL == 0) {
            singNote(ctx, level);
        }

        // The aura pulse: allies inside the spiral dance and mend.
        if (ticks % AURA_INTERVAL == 0) {
            List<LivingEntity> allies = AllayAbilitySupport.alliesInBox(ctx,
                centerX, centerY, centerZ, radius, 3.0, 3.0);
            for (LivingEntity ally : allies) {
                ally.addEffect(new MobEffectInstance(MobEffects.SPEED, EFFECT_TICKS, 0));
                ally.addEffect(new MobEffectInstance(MobEffects.REGENERATION, EFFECT_TICKS, 0));
            }
            caster.addEffect(new MobEffectInstance(MobEffects.SPEED, EFFECT_TICKS, 0));
            caster.addEffect(new MobEffectInstance(MobEffects.REGENERATION, EFFECT_TICKS, 0));
        }
    }

    /** The current spiral radius: 2.0 plus 0.25 per note sung, capped at 6.0. */
    private static double spiralRadius(CompoundTag state) {
        return Math.min(MAX_RADIUS, BASE_RADIUS + RADIUS_PER_NOTE * state.getIntOr("notes", 0));
    }

    /** Sings the next note of the ladder: harp sound, note burst, count up. */
    private void singNote(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        Mob caster = ctx.mob();
        int notes = state.getIntOr("notes", 0);
        int semitones = LADDER[notes % LADDER.length];
        float pitch = (float) Math.pow(2.0, semitones / 12.0);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.NOTE_BLOCK_HARP, SoundSource.HOSTILE, 1.0f, pitch);
        level.sendParticles(ParticleTypes.NOTE, caster.getX(), caster.getY() + 0.9, caster.getZ(),
            2, 0.25, 0.2, 0.25, 0.05);
        state.putInt("notes", notes + 1);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to undo: the aura's effects ride their own
        // 40-tick clocks, and no modifier, summon, or display
        // was created. Deliberately empty.
    }
}
