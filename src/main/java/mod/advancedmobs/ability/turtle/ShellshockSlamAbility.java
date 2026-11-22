package mod.advancedmobs.ability.turtle;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.List;
import java.util.UUID;

/**
 * Shellshock Slam (Turtle ability 5 of 5, attack / control).
 *
 * WHAT IT DOES: the turtle coils, snaps, and flips its
 * whole weight into a rising slam. The shockwave catches
 * every enemy around it and tosses them skyward with a
 * brief touch of levitation: they hang helpless at the top
 * of the arc for a moment, then fall hard, their fall
 * damage doubled, while the turtle watches smugly from
 * below.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the turtle's current target within 10 blocks (distance
 *   squared 100), plus the turtle on the ground (a slam
 *   launches from solid footing).
 * - The coil (ticks 1 to 8): navigation is stopped (never
 *   NoAi) and 2 BLOCK_CRUMBLE motes of the real ground
 *   state kick up at the turtle's feet every 2 ticks as it
 *   bunches down.
 * - The launch (tick 9, once): the turtle's velocity is
 *   written to 0.95 straight up plus 0.45 horizontally
 *   toward its target's live position (straight up when
 *   the target is already lost), hurtMarked so the leap
 *   syncs. The phase flips to airborne in the state tag.
 * - The slam (the first tick at least 3 ticks after the
 *   launch on which the turtle is back on the ground,
 *   once, guarded by the "slammed" flag): one bounded
 *   query, radius 3.5 around the turtle (vertical reach 2
 *   below, 3 above), at most the first 8 enemies found
 *   (the victim cap). Each victim takes 4.0 flat damage as
 *   a mob attack credited to the turtle (the turtle has no
 *   ATTACK_DAMAGE instance; see the provider comment),
 *   gains LEVITATION I for 25 ticks (the design's brief
 *   hang), is hurled upward at 0.8 blocks per tick with
 *   its horizontal motion damped to 30 percent,
 *   hurtMarked, and gains a transient FALL_DAMAGE_
 *   MULTIPLIER +1.0 ADD_VALUE modifier (modifier id
 *   advancedmobs:shellshock_slam_fall, the design's fall
 *   multiplier line: the victim's fall damage doubles, so
 *   the landing hurts at vanilla fall rates times two,
 *   dealt by vanilla itself as fall damage, the design's
 *   fall-typed landing line). Victims are tracked in the
 *   state tag. 16 BLOCK_CRUMBLE motes of the real ground
 *   state burst at the turtle's feet and GENERIC_EXPLODE
 *   plays at volume 0.8, pitch 1.1 as the thud.
 * - The landing watch (every 5 ticks after the slam): each
 *   tracked victim is resolved; a victim that is dead or
 *   gone is dropped (its transient modifier died with it),
 *   and a victim back on the ground has its fall modifier
 *   removed exactly and is dropped: the doubled fall has
 *   landed (or the toss never got it airborne, in which
 *   case the modifier simply comes off).
 * - isFinished: the hard cap is 160 ticks; the ability
 *   also finishes once the slam has landed and every
 *   tracked victim has been pruned, or at tick 60 if the
 *   turtle somehow never landed to slam.
 * - end(): every still-tracked victim that can be resolved
 *   has its fall modifier removed, so a terminated slam
 *   never leaves doubled fall damage behind. Idempotent.
 *
 * WHAT IT LOOKS LIKE: the turtle bunches down in a scuff
 * of dirt, launches itself in a clumsy arc, and comes down
 * with a thud that pops every enemy around it into the
 * air: they rise, hang a heartbeat too long, and drop.
 *
 * PERFORMANCE: the coil is one navigation stop and a
 * 2-particle emission every 2 ticks; the launch is one
 * velocity write; the airborne phase is one onGround check
 * per tick; the slam is one bounded query once; the watch
 * resolves at most 8 UUIDs every 5 ticks. Particle budget
 * per activation: coil 4 emissions times 2 = 8, slam 16,
 * total at most 24 particles.
 *
 * TERRAIN: this ability changes no blocks; the
 * BLOCK_CRUMBLE motes are particles carrying the ground
 * state, not placed or broken blocks, so the mobGriefing
 * gate does not apply. There is no explosion: the thud is
 * a sound and code damage only.
 *
 * State tag keys: "phase" (0 coil, 1 airborne), "launch"
 * (the activation tick of the launch), "slammed" (1 once
 * the slam has fired), "victims" (list of compounds, one
 * per tossed enemy: "id", the victim's UUID).
 */
public final class ShellshockSlamAbility implements MobAbility {

    private static final int HARD_CAP_TICKS = 160;
    private static final int LAUNCH_TICK = 9;
    private static final int NEVER_LANDED_TICK = 60;
    private static final double SLAM_RADIUS = 3.5;
    private static final int VICTIM_CAP = 8;
    private static final float SLAM_DAMAGE = 4.0f;
    private static final Identifier FALL_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "shellshock_slam_fall");

    @Override
    public String id() {
        return "shellshock_slam";
    }

    @Override
    public String displayName() {
        return "Shellshock Slam";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        Mob caster = ctx.mob();
        return target != null && caster.onGround()
            && caster.distanceToSqr(target) <= 100.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().putInt("phase", 0);
        ctx.state().putInt("launch", -1);
        ctx.state().putInt("slammed", 0);
        ctx.state().put("victims", new ListTag());
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        int ticks = ctx.ticksActive();
        int phase = ctx.state().getIntOr("phase", 0);

        if (phase == 0) {
            // The coil: bunched down, navigation stopped.
            caster.getNavigation().stop();
            if (ticks % 2 == 0) {
                level.sendParticles(
                    TurtleAbilitySupport.groundCrumble(level, caster.getX(), caster.getY(), caster.getZ()),
                    caster.getX(), caster.getY() + 0.2, caster.getZ(),
                    2, 0.4, 0.1, 0.4, 0.03);
            }
            if (ticks >= LAUNCH_TICK) {
                launch(ctx);
            }
            return;
        }

        // Airborne: watch for the landing, then slam once.
        if (ctx.state().getIntOr("slammed", 0) == 0) {
            int launchTick = ctx.state().getIntOr("launch", -1);
            if (caster.onGround() && launchTick >= 0 && ticks >= launchTick + 3) {
                slam(ctx, level);
            }
        } else if (ticks % 5 == 0) {
            pruneVictims(ctx, level);
        }
    }

    /** The launch: up hard, drifting toward the target. See the class comment. */
    private void launch(AbilityContext ctx) {
        Mob caster = ctx.mob();
        double hx = 0.0;
        double hz = 0.0;
        LivingEntity target = ctx.target();
        if (target != null) {
            double dx = target.getX() - caster.getX();
            double dz = target.getZ() - caster.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 0.001) {
                hx = dx / len * 0.45;
                hz = dz / len * 0.45;
            }
        }
        caster.setDeltaMovement(hx, 0.95, hz);
        caster.hurtMarked = true;
        ctx.state().putInt("phase", 1);
        ctx.state().putInt("launch", ctx.ticksActive());
    }

    /** The slam: damage, levitation, the toss, and the doubled fall. See the class comment. */
    private void slam(AbilityContext ctx, ServerLevel level) {
        ctx.state().putInt("slammed", 1);
        Mob caster = ctx.mob();
        List<LivingEntity> victims = TurtleAbilitySupport.enemiesAroundSelf(ctx, SLAM_RADIUS, 2.0, 3.0);
        int tossed = 0;
        for (LivingEntity victim : victims) {
            if (tossed >= VICTIM_CAP) {
                break;
            }
            tossed++;
            victim.hurtServer(level, level.damageSources().mobAttack(caster), SLAM_DAMAGE);
            victim.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 25, 0));
            victim.setDeltaMovement(
                victim.getDeltaMovement().x * 0.3, 0.8, victim.getDeltaMovement().z * 0.3);
            victim.hurtMarked = true;
            TurtleAbilitySupport.addModifier(victim, Attributes.FALL_DAMAGE_MULTIPLIER,
                new AttributeModifier(FALL_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
            TurtleAbilitySupport.trackEntity(ctx.state(), "victims", victim);
        }
        level.sendParticles(
            TurtleAbilitySupport.groundCrumble(level, caster.getX(), caster.getY(), caster.getZ()),
            caster.getX(), caster.getY() + 0.2, caster.getZ(),
            16, 0.7, 0.2, 0.7, 0.08);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 0.8f, 1.1f);
    }

    /**
     * The landing watch: drop victims that are gone, and
     * remove the fall modifier from victims back on the
     * ground. Every 5 ticks after the slam.
     */
    private void pruneVictims(AbilityContext ctx, ServerLevel level) {
        ListTag victims = ctx.state().getListOrEmpty("victims");
        ListTag survivors = new ListTag();
        for (int i = 0; i < victims.size(); i++) {
            CompoundTag entry = victims.getCompoundOrEmpty(i);
            Entity found = TurtleAbilitySupport.resolveTracked(level, entry);
            if (!(found instanceof LivingEntity victim) || !victim.isAlive()) {
                continue; // Gone: its transient modifier died with it.
            }
            if (victim.onGround()) {
                TurtleAbilitySupport.removeModifier(victim, Attributes.FALL_DAMAGE_MULTIPLIER, FALL_ID);
                continue;
            }
            survivors.add(entry);
        }
        ctx.state().put("victims", survivors);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks >= HARD_CAP_TICKS) {
            return true;
        }
        if (ctx.state().getIntOr("phase", 0) != 1) {
            return false;
        }
        if (ctx.state().getIntOr("slammed", 0) == 0) {
            return ticks >= NEVER_LANDED_TICK;
        }
        return ctx.state().getListOrEmpty("victims").isEmpty();
    }

    @Override
    public void end(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag victims = ctx.state().getListOrEmpty("victims");
        for (int i = 0; i < victims.size(); i++) {
            CompoundTag entry = victims.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id == null) {
                continue;
            }
            Entity found = level.getEntity(id);
            if (found instanceof LivingEntity victim) {
                TurtleAbilitySupport.removeModifier(victim, Attributes.FALL_DAMAGE_MULTIPLIER, FALL_ID);
            }
        }
    }
}
