package mod.advancedmobs.ability.witherskeleton;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;

/**
 * Obsidian Bones (Wither Skeleton ability 2 of 5, defense).
 *
 * WHAT IT DOES: the wither skeleton's bones blacken to obsidian
 * density. Its ARMOR and ARMOR_TOUGHNESS climb sharply and it
 * gains RESISTANCE, turning it into a wall that trades blows
 * and wins on attrition. Every hit it takes flakes obsidian
 * chips off its frame, and ASH curls off it the whole time.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   wither skeleton's current target within 10 blocks (distance
 *   squared 100): the bones harden when a fight is on top of it,
 *   not at bow range.
 * - Duration: exactly 300 ticks (15 seconds) from start().
 * - The hardening, transient attribute modifiers added in
 *   start() and removed in end(), ids under
 *   advancedmobs:obsidian_bones_*:
 *     ARMOR           add_value +8.0 (a wither skeleton's base
 *                     armor is 2, so 10 total while hardened:
 *                     roughly an iron chestplate's worth spread
 *                     over the whole body)
 *     ARMOR_TOUGHNESS add_value +6.0 (toughness is what keeps
 *                     armor effective against heavy hits; +6 is
 *                     three quarters of a full diamond set's
 *                     toughness, so big hits keep most of their
 *                     reduction instead of punching through)
 *   Plus RESISTANCE I (amplifier 0, 20 percent off all damage)
 *   applied in start() for the full 300 ticks and removed in
 *   end(). Removing it in end() is the standing fixed-duration
 *   effect contract of this system: if the skeleton somehow had
 *   resistance from another source, it loses it at end(), an
 *   accepted edge case shared with every effect ability in the
 *   mod.
 * - The flaking: every 5 ticks the ability compares the
 *   skeleton's last-hurt-by-mob timestamp with the stored one.
 *   A new timestamp means a hit landed (melee, or a projectile
 *   whose owner the timestamp names). Each answered hit sheds
 *   5 BLOCK obsidian chip particles and 3 ASH at the skeleton's
 *   chest. Answers are at most one per 20 ticks, so focus fire
 *   cannot turn the flaking into a particle fountain; the
 *   timestamp still updates on every check, so a hit swallowed
 *   by the answer cooldown is simply answered by the next one.
 *   The stored timestamp is initialized in start() to the
 *   skeleton's current one, so a hit that landed before the
 *   bones hardened is never answered.
 *
 * WHAT IT LOOKS AND SOUNDS LIKE: no model change (the blackening
 * is read through the particles): 8 ASH motes curl off the frame
 * as the bones set, with a low WITHER_SKELETON_AMBIENT groan
 * (volume 1.0, pitch 0.6). Every 10 ticks, 2 more ASH motes
 * drift off its shoulders. Answered hits crack off the obsidian
 * chips described above.
 *
 * PERFORMANCE: tick() returns immediately except every 5th tick
 * (one timestamp read, and only on a new hit one particle burst)
 * and every 10th tick (2 particles). No entity scans, no
 * allocations in the tick path: the obsidian chip particle
 * option is a pre-built static final. Particle budget per
 * activation: 8 at start plus at most 30 drift pulses times 2
 * = 60, plus at most 15 answers times 8 = 120, total at most
 * 188 particles.
 *
 * CLEANUP: end() removes both attribute modifiers by id and the
 * RESISTANCE effect. If the caster dies mid-hardening the
 * modifiers and effect die with the entity; nothing else was
 * created, so there is no leftover state in the world.
 *
 * State tag keys: "answer_ts" (the last-hurt-by-mob timestamp
 * already answered), "last_answer" (tick of the last flake
 * answer).
 */
public final class ObsidianBonesAbility implements MobAbility {

    static final int DURATION_TICKS = 300;
    private static final int ANSWER_INTERVAL = 5;
    private static final int ANSWER_COOLDOWN = 20;
    private static final Identifier ARMOR_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "obsidian_bones_armor");
    private static final Identifier TOUGHNESS_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "obsidian_bones_toughness");
    private static final BlockParticleOption OBSIDIAN_CHIPS =
        new BlockParticleOption(ParticleTypes.BLOCK, Blocks.OBSIDIAN.defaultBlockState());

    @Override
    public String id() {
        return "obsidian_bones";
    }

    @Override
    public String displayName() {
        return "Obsidian Bones";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return ctx.target() != null && ctx.mob().distanceToSqr(ctx.target()) <= 100.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        ctx.state().putInt("answer_ts", mob.getLastHurtByMobTimestamp());
        ctx.state().putInt("last_answer", -ANSWER_COOLDOWN);

        // The hardening: armor, toughness, and resistance.
        AttributeInstance armor = mob.getAttribute(Attributes.ARMOR);
        if (armor != null) {
            armor.addOrUpdateTransientModifier(
                new AttributeModifier(ARMOR_ID, 8.0, AttributeModifier.Operation.ADD_VALUE));
        }
        AttributeInstance toughness = mob.getAttribute(Attributes.ARMOR_TOUGHNESS);
        if (toughness != null) {
            toughness.addOrUpdateTransientModifier(
                new AttributeModifier(TOUGHNESS_ID, 6.0, AttributeModifier.Operation.ADD_VALUE));
        }
        mob.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, DURATION_TICKS, 0));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.sendParticles(ParticleTypes.ASH, mob.getX(), mob.getY() + 1.4, mob.getZ(),
                8, 0.5, 0.7, 0.5, 0.03);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.WITHER_SKELETON_AMBIENT, SoundSource.HOSTILE, 1.0f, 0.6f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        boolean answerTick = ticks % ANSWER_INTERVAL == 0;
        boolean driftTick = ticks % 10 == 0;
        if (!answerTick && !driftTick) {
            return; // Cheap early-out: nothing runs off the 5 and 10 tick marks.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();

        if (driftTick) {
            // Ash curling off the blackened frame.
            level.sendParticles(ParticleTypes.ASH, mob.getX(), mob.getY() + 1.6, mob.getZ(),
                2, 0.5, 0.5, 0.5, 0.02);
        }

        if (answerTick) {
            // The flaking: a fresh hit cracks obsidian chips off.
            int timestamp = mob.getLastHurtByMobTimestamp();
            if (timestamp != ctx.state().getIntOr("answer_ts", -1)) {
                ctx.state().putInt("answer_ts", timestamp);
                if (ticks - ctx.state().getIntOr("last_answer", 0) >= ANSWER_COOLDOWN) {
                    level.sendParticles(OBSIDIAN_CHIPS, mob.getX(), mob.getY() + 1.2, mob.getZ(),
                        5, 0.4, 0.6, 0.4, 0.08);
                    level.sendParticles(ParticleTypes.ASH, mob.getX(), mob.getY() + 1.2, mob.getZ(),
                        3, 0.4, 0.6, 0.4, 0.04);
                    ctx.state().putInt("last_answer", ticks);
                }
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
        AttributeInstance armor = mob.getAttribute(Attributes.ARMOR);
        if (armor != null) {
            armor.removeModifier(ARMOR_ID);
        }
        AttributeInstance toughness = mob.getAttribute(Attributes.ARMOR_TOUGHNESS);
        if (toughness != null) {
            toughness.removeModifier(TOUGHNESS_ID);
        }
        mob.removeEffect(MobEffects.RESISTANCE);
    }
}
