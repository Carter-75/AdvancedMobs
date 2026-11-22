package mod.advancedmobs.ability.cavespider;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
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
 * Neurotoxin Webbing (Cave Spider ability 1 of 5, attack).
 *
 * WHAT IT DOES: the cave spider bites and weaves in the same
 * motion. The fangs land, real cobweb snaps around the victim
 * where it stands, and a venom tailored far beyond the old
 * cave spider poison takes hold: WITCH sparkles of violet and
 * green crawl over the wrapped target while it sickens under
 * POISON, its limbs slow under MINING_FATIGUE so it cannot
 * even dig itself free at speed, and WEAVING threads through
 * it, marking it as a thing of the webs.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists, within 4.5 blocks
 *   (distance squared 20.25). This is a bite delivered at fang
 *   range, not a ranged shot.
 * - The bite (start()): the target's UUID is recorded, then
 *   the victim takes 3.0 points of mob attack damage from the
 *   spider (the spider's fangs, on top of the venom below).
 *   Three effects land at once, all timed: POISON II
 *   (amplifier 1) for 200 ticks, MINING_FATIGUE II (amplifier
 *   1) for 200 ticks, and WEAVING (amplifier 0) for 200 ticks.
 *   A SPIDER_AMBIENT hiss plays at volume 0.7, pitch 1.4, and
 *   12 WITCH particles burst over the victim.
 * - The wrap (start(), mobGriefing mode only): cobwebs are
 *   placed at the victim's feet block and the block 1 up, at
 *   most 2 blocks, through the shared safety-checked placement
 *   path (replaceable, no fluid, no block entity, not
 *   unbreakable). The webs are PERMANENT terrain. With
 *   mobGriefing off no block changes; the bite, the venom,
 *   and the sparkles are identical.
 * - The crawl (every 4 ticks for 60 ticks): while the victim
 *   lives, 3 WITCH particles drift over its body (spread 0.35
 *   horizontal, 0.5 vertical around its center): the venom
 *   visibly working under the skin. The ability finishes at
 *   tick 60, or early when the victim dies or is gone: there
 *   is nothing left for the venom to crawl over.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6): the wrap's cobwebs are real
 * block changes, so they are placed only when
 * AbilityContext.mobGriefingAllowed() is true, and they stay
 * placed for good. Nothing in end() touches them.
 *
 * WHAT IT LOOKS LIKE: a hiss and a lunge, fresh cobweb
 * snapping into place around the victim's legs and waist,
 * and violet-green WITCH sparkles crawling over it for three
 * seconds while the poison does its work.
 *
 * PERFORMANCE: the bite and the wrap are one fixed pass at
 * start (at most 2 placement checks). The crawl sends 3
 * particles every 4 ticks against one UUID resolve. No entity
 * scans at all. Particle budget per activation: opening 12,
 * crawl 15 pulses times 3 = 45, total at most 57 particles.
 *
 * CLEANUP: nothing to undo. The cobwebs are permanent terrain
 * (mobGriefing mode) and the three effects are timed and
 * expire on their own. end() is intentionally empty.
 *
 * State tag keys: "tid" (the victim's UUID).
 */
public final class NeurotoxinWebbingAbility implements MobAbility {

    private static final int DURATION_TICKS = 60;
    private static final int CRAWL_INTERVAL = 4;
    private static final float BITE_DAMAGE = 3.0f;
    private static final int VENOM_TICKS = 200;

    @Override
    public String id() {
        return "neurotoxin_webbing";
    }

    @Override
    public String displayName() {
        return "Neurotoxin Webbing";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 20.25;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob mob = ctx.mob();
        LivingEntity victim = ctx.target();
        if (victim == null) {
            return;
        }
        ctx.state().store("tid", UUIDUtil.CODEC, victim.getUUID());

        // The bite and the venom land together.
        if (level != null) {
            victim.hurt(level.damageSources().mobAttack(mob), BITE_DAMAGE);
        }
        victim.addEffect(new MobEffectInstance(MobEffects.POISON, VENOM_TICKS, 1));
        victim.addEffect(new MobEffectInstance(MobEffects.MINING_FATIGUE, VENOM_TICKS, 1));
        victim.addEffect(new MobEffectInstance(MobEffects.WEAVING, VENOM_TICKS, 0));

        if (level == null) {
            return;
        }

        // The wrap (mobGriefing mode only): permanent cobweb at
        // the victim's feet and waist. See the TERRAIN RULE in
        // the class comment.
        if (ctx.mobGriefingAllowed()) {
            BlockPos feet = victim.blockPosition();
            CaveSpiderAbilitySupport.placeCobweb(level, feet);
            CaveSpiderAbilitySupport.placeCobweb(level, feet.above());
        }

        level.sendParticles(ParticleTypes.WITCH, victim.getX(), victim.getY() + 0.8, victim.getZ(),
            12, 0.4, 0.6, 0.4, 0.05);
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
            SoundEvents.SPIDER_AMBIENT, SoundSource.HOSTILE, 0.7f, 1.4f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % CRAWL_INTERVAL != 0) {
            return; // Cheap early-out: the venom crawl shows every 4 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        LivingEntity victim = resolveVictim(ctx, level);
        if (victim == null) {
            return;
        }
        level.sendParticles(ParticleTypes.WITCH, victim.getX(), victim.getY() + 0.7, victim.getZ(),
            3, 0.35, 0.5, 0.35, 0.03);
    }

    /** The recorded victim, resolved live; falls back to the context target. */
    private static LivingEntity resolveVictim(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        if (state.contains("tid")) {
            UUID id = state.read("tid", UUIDUtil.CODEC).orElse(null);
            Entity entity = id != null ? level.getEntity(id) : null;
            if (entity instanceof LivingEntity living && living.isAlive()) {
                return living;
            }
            return null;
        }
        return ctx.target();
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        if (ctx.ticksActive() >= DURATION_TICKS) {
            return true;
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return true;
        }
        // Early finish once the victim is dead or gone.
        return ctx.state().contains("tid") && resolveVictim(ctx, level) == null;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Intentionally empty: the cobwebs are permanent terrain,
        // the venom effects are timed and expire on their own.
        // See CLEANUP in the class comment.
    }
}
