package mod.advancedmobs.ability.sniffer;

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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.List;
import java.util.UUID;

/**
 * Sniff Out Weakness (Sniffer ability 2 of 5, control /
 * aura).
 *
 * WHAT IT DOES: the great snout works the air, enchant
 * sparkles streaming into the nostrils, and the sniffer
 * reads the fight perfectly. Every enemy it sniffs out is
 * marked: a glowing outline, a scoreboard tag, and a
 * weakening hex. The sniffer then hunts the fight down
 * and hits marked targets noticeably harder than unmarked
 * ones.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the sniffer's current target within 16 blocks
 *   (distance squared 256).
 * - Duration: 400 ticks (20 seconds).
 * - The sniffing (start() and tick()): SNIFFER_SNIFFING
 *   plays at start (volume 1.2, pitch 0.9). Every 2 ticks,
 *   2 ENCHANT motes stream at the sniffer's snout (sent at
 *   the head with a small spread, the design's enchant
 *   line).
 * - The marking (pulse, every 20 ticks): one bounded
 *   query (radius 10, 3 below and 3 above the sniffer).
 *   Every enemy in it that is not already marked is added
 *   to the marked list in the state tag and given the
 *   real scoreboard tag "advancedmobs_sniffed" (the
 *   design's tag line). Every enemy in the query, marked
 *   or just marked, gains GLOWING and WEAKNESS I
 *   refreshed to 60 ticks (the design's glowing and
 *   weakness lines). A marked enemy that leaves the
 *   radius keeps its tag and its mark; its effects simply
 *   stop refreshing and fade.
 * - The hunt (every 5 ticks): the sniffer is
 *   navigation-driven onto its live target at speed 1.1
 *   while the target is beyond 2.6 blocks (the sniffer's
 *   base speed is a crawl, so the drive speed multiplier
 *   does the work). Within 2.6 blocks, at most once per
 *   20 ticks, a bite lands: 7.0 flat damage when the
 *   target is marked, 4.0 when it is not (the design's
 *   damage bonus against tagged targets, in code), plus
 *   6 CRIT motes on a marked bite, 3 on an unmarked one,
 *   and SNIFFER_HURT at pitch 1.2 as the chomp (the Mule
 *   package's strike-sound pattern). All damage is flat
 *   code values through a mob attack source credited to
 *   the sniffer, which has no ATTACK_DAMAGE instance (see
 *   the provider comment).
 * - end(): for every marked entity still resolvable, the
 *   scoreboard tag is removed and the GLOWING and
 *   WEAKNESS effects are stripped, so a finished or
 *   terminated sniff leaves no mark behind. Idempotent.
 *
 * WHAT IT LOOKS LIKE: violet sparkles pouring into a
 * working nose, then the whole enemy line lights up with
 * outlines one pulse at a time, and the slow old beast
 * waddles straight at the brightest one and bites it
 * hard.
 *
 * PERFORMANCE: the sniff motes are 2 sends every 2 ticks;
 * the marking pulse is one bounded query every 20 ticks
 * plus a state-tag list pass over the marked set; the
 * hunt is one navigation call every 5 ticks and one
 * distance check per tick. Particle budget per
 * activation: sniffing 200 sends times 2 = 400, bites at
 * most 20 times 6 = 120, total at most 520.
 *
 * TERRAIN: this ability changes no blocks; the
 * mobGriefing gate does not apply.
 *
 * State tag keys: "marked" (list of compounds: "id", one
 * per marked enemy), "last_bite" (the activation tick of
 * the most recent bite, for the 20-tick bite clock).
 */
public final class SniffOutWeaknessAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int MARK_INTERVAL = 20;
    private static final double MARK_RADIUS = 10.0;
    private static final int MARK_EFFECT_TICKS = 60;
    private static final String MARK_TAG = "advancedmobs_sniffed";
    private static final double BITE_RANGE = 2.6;
    private static final int BITE_INTERVAL = 20;
    private static final float BITE_DAMAGE = 4.0f;
    private static final float MARKED_BITE_DAMAGE = 7.0f;

    @Override
    public String id() {
        return "sniff_out_weakness";
    }

    @Override
    public String displayName() {
        return "Sniff Out Weakness";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().put("marked", new ListTag());
        ctx.state().putInt("last_bite", -BITE_INTERVAL);
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            Mob caster = ctx.mob();
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.SNIFFER_SNIFFING, SoundSource.HOSTILE, 1.2f, 0.9f);
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

        // The sniffing: enchant sparkles streaming into the nostrils.
        if (ticks % 2 == 0) {
            level.sendParticles(ParticleTypes.ENCHANT, caster.getX(), caster.getEyeY(), caster.getZ(),
                2, 0.5, 0.3, 0.5, 0.4);
        }

        // The marking pulse.
        if (ticks % MARK_INTERVAL == 0) {
            markPulse(ctx);
        }

        // The hunt.
        LivingEntity target = ctx.target();
        if (target == null) {
            return;
        }
        double distance = caster.distanceTo(target);
        if (distance > BITE_RANGE) {
            if (ticks % 5 == 0) {
                caster.getNavigation().moveTo(target, 1.1);
            }
            return;
        }
        int lastBite = ctx.state().getIntOr("last_bite", -BITE_INTERVAL);
        if (ticks - lastBite < BITE_INTERVAL) {
            return;
        }
        ctx.state().putInt("last_bite", ticks);
        boolean marked = isMarked(ctx, target.getUUID());
        float damage = marked ? MARKED_BITE_DAMAGE : BITE_DAMAGE;
        target.hurtServer(level, level.damageSources().mobAttack(caster), damage);
        level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY() + target.getBbHeight() * 0.5,
            target.getZ(), marked ? 6 : 3, 0.3, 0.3, 0.3, 0.1);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.SNIFFER_HURT, SoundSource.HOSTILE, 1.0f, 1.2f);
    }

    /** One marking pulse (class comment): tags new enemies, refreshes the hex on all in range. */
    private void markPulse(AbilityContext ctx) {
        ListTag marked = ctx.state().getListOrEmpty("marked");
        List<LivingEntity> enemies = SnifferAbilitySupport.enemiesAroundSelf(ctx, MARK_RADIUS, 3.0, 3.0);
        for (LivingEntity enemy : enemies) {
            if (!isMarked(marked, enemy.getUUID())) {
                CompoundTag entry = new CompoundTag();
                entry.store("id", UUIDUtil.CODEC, enemy.getUUID());
                marked.add(entry);
                enemy.addTag(MARK_TAG);
            }
            enemy.addEffect(new MobEffectInstance(MobEffects.GLOWING, MARK_EFFECT_TICKS, 0));
            enemy.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, MARK_EFFECT_TICKS, 0));
        }
        ctx.state().put("marked", marked);
    }

    /** Whether the given UUID is in the state tag's marked list. */
    private boolean isMarked(AbilityContext ctx, UUID id) {
        return isMarked(ctx.state().getListOrEmpty("marked"), id);
    }

    /** Whether the given UUID is in the given marked list (compounds of "id"). */
    private boolean isMarked(ListTag marked, UUID id) {
        for (int i = 0; i < marked.size(); i++) {
            CompoundTag entry = marked.getCompoundOrEmpty(i);
            if (entry.contains("id") && id.equals(entry.read("id", UUIDUtil.CODEC).orElse(null))) {
                return true;
            }
        }
        return false;
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
        // Unmark everyone still resolvable: tag off, hex off.
        ListTag marked = ctx.state().getListOrEmpty("marked");
        for (int i = 0; i < marked.size(); i++) {
            if (SnifferAbilitySupport.resolveTracked(level, marked.getCompoundOrEmpty(i))
                instanceof LivingEntity enemy) {
                enemy.removeTag(MARK_TAG);
                enemy.removeEffect(MobEffects.GLOWING);
                enemy.removeEffect(MobEffects.WEAKNESS);
            }
        }
    }
}
