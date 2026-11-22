package mod.advancedmobs.ability.frog;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.phys.Vec3;

/**
 * Tongue Lash (Frog ability 1 of 5, attack / control).
 *
 * WHAT IT DOES: the frog's tongue fires out in a red dust
 * streak faster than the eye can follow and sticks to its
 * target. Instead of pulling prey in the vanilla way, the
 * frog reels the enemy across the arena to itself,
 * dealing damage on arrival and leaving it in biting
 * range.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the frog's current target within 10 blocks
 *   (distance squared 100).
 * - The shot (start()): the target's UUID is stored, the
 *   frog is planted by a navigation stop (never NoAi),
 *   FROG_TONGUE plays at volume 1.0, pitch 1.0, and when
 *   the caster is a real Frog its vanilla tongue target is
 *   set on the victim, so the actual tongue model reaches
 *   for it. end() erases that tongue target.
 * - The streak (ticks 1 to 4, phase 0): the frog stays
 *   planted. Each tick, 8 red DUST motes (RGB 0xC22424,
 *   scale 1.2, a pre-built static final) are laid along
 *   the line from the frog's mouth (its position plus 0.3
 *   up) to the target's chest, the design's red dust
 *   tongue streak.
 * - The reel (tick 5 onward, phase 1): every tick the
 *   target's velocity is written (the design's Motion
 *   line, in code) toward an anchor point 1.3 blocks in
 *   front of the frog at the frog's own height: horizontal
 *   speed 0.85 blocks per tick, vertical speed the height
 *   gap to the anchor times 0.2 clamped to [-0.3, 0.3],
 *   with hurtMarked set so the drag syncs. 2 red DUST
 *   motes trail at the target per tick. The frog stays
 *   planted the whole reel.
 * - Arrival: when the target's horizontal distance to the
 *   frog drops to 1.7 blocks or less, the tongue lets go
 *   with a bite: damage is the frog's live ATTACK_DAMAGE
 *   attribute value times 0.8 (8.0 on a base frog; the
 *   mod's damage upgrades ride the attribute) through a
 *   mob attack source credited to the frog (the design's
 *   typed mob attack damage), with 10 CRIT particles at
 *   the victim (the design's crit on arrival) and
 *   FROG_TONGUE again at pitch 0.8. The phase flips to
 *   done and the arrival tick is recorded.
 * - Snap: if the reel runs 12 ticks (activation tick 16)
 *   without arrival, or the target dies or is removed
 *   mid-reel, the tongue snaps back with no damage.
 * - Duration: the activation ends 3 ticks after arrival
 *   or the snap, with a hard cap of 30 ticks.
 * - end(): the vanilla tongue target is erased when the
 *   caster is a real Frog. Nothing else was changed.
 *   Idempotent.
 *
 * WHAT IT LOOKS LIKE: a red streak snaps across the gap,
 * sticks, and the victim skids helplessly across the
 * arena trailing red dust until it lands in front of the
 * frog in a burst of crit stars.
 *
 * PERFORMANCE: the streak is 8 fixed particle sends per
 * tick for 4 ticks; the reel is one UUID resolve, one
 * velocity write, and 2 particle sends per tick; arrival
 * is one damage call. No entity queries anywhere.
 * Particle budget per activation: 32 streak plus at most
 * 24 reel trail plus 10 crit, at most 66 particles.
 *
 * TERRAIN: this ability changes no blocks; the
 * mobGriefing gate does not apply.
 *
 * State tag keys: "phase" (0 streak, 1 reeling, 2 done),
 * "target_id" (the victim's UUID), "done_tick" (the
 * activation tick the arrival or snap happened).
 */
public final class TongueLashAbility implements MobAbility {

    private static final int STREAK_TICKS = 4;
    private static final int REEL_TIMEOUT_TICK = 16;
    private static final int HARD_CAP_TICKS = 30;
    private static final int LINGER_TICKS = 3;
    private static final double REEL_SPEED = 0.85;
    private static final double ARRIVE_DISTANCE = 1.7;
    private static final DustParticleOptions TONGUE_DUST = new DustParticleOptions(0xC22424, 1.2f);

    @Override
    public String id() {
        return "tongue_lash";
    }

    @Override
    public String displayName() {
        return "Tongue Lash";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 100.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        LivingEntity target = ctx.target();
        ctx.state().putInt("phase", 0);
        if (target != null) {
            ctx.state().store("target_id", net.minecraft.core.UUIDUtil.CODEC, target.getUUID());
            if (caster instanceof Frog frog) {
                // The vanilla tongue visual reaches for the victim.
                frog.setTongueTarget(target);
            }
        }
        caster.getNavigation().stop();
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.FROG_TONGUE, SoundSource.HOSTILE, 1.0f, 1.0f);
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
        int phase = ctx.state().getIntOr("phase", 0);
        caster.getNavigation().stop();
        Entity resolved = FrogAbilitySupport.resolveStored(level, ctx.state(), "target_id");
        LivingEntity target = resolved instanceof LivingEntity living ? living : null;
        if (phase == 0) {
            // The streak: red dust along the mouth-to-chest line.
            if (target != null && target.isAlive()) {
                double x0 = caster.getX();
                double y0 = caster.getY() + 0.3;
                double z0 = caster.getZ();
                double x1 = target.getX();
                double y1 = target.getY() + target.getBbHeight() * 0.6;
                double z1 = target.getZ();
                for (int i = 1; i <= 8; i++) {
                    double f = i / 9.0;
                    level.sendParticles(TONGUE_DUST,
                        x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, z0 + (z1 - z0) * f,
                        1, 0.02, 0.02, 0.02, 0.0);
                }
            }
            if (ticks >= STREAK_TICKS) {
                ctx.state().putInt("phase", 1);
            }
            return;
        }
        if (phase == 1) {
            if (target == null || !target.isAlive()) {
                finish(ctx, ticks);
                return;
            }
            // The reel: drag the victim toward the anchor in
            // front of the frog (the design's Motion drag).
            Vec3 look = caster.getLookAngle();
            double anchorX = caster.getX() + look.x * 1.3;
            double anchorZ = caster.getZ() + look.z * 1.3;
            double dx = anchorX - target.getX();
            double dz = anchorZ - target.getZ();
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            double vx = 0.0;
            double vz = 0.0;
            if (horizontal > 1.0e-4) {
                vx = dx / horizontal * REEL_SPEED;
                vz = dz / horizontal * REEL_SPEED;
            }
            double vy = Math.max(-0.3, Math.min(0.3, (caster.getY() - target.getY()) * 0.2));
            target.setDeltaMovement(vx, vy, vz);
            target.hurtMarked = true;
            level.sendParticles(TONGUE_DUST, target.getX(), target.getY() + 0.4, target.getZ(),
                2, 0.2, 0.2, 0.2, 0.02);
            double frogDx = target.getX() - caster.getX();
            double frogDz = target.getZ() - caster.getZ();
            if (frogDx * frogDx + frogDz * frogDz <= ARRIVE_DISTANCE * ARRIVE_DISTANCE) {
                arrive(ctx, target, ticks);
            } else if (ticks >= REEL_TIMEOUT_TICK) {
                finish(ctx, ticks);
            }
        }
    }

    /** Arrival: the bite, the crit burst, and the phase flip to done. */
    private void arrive(AbilityContext ctx, LivingEntity target, int ticks) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            double damage = caster.getAttributeValue(Attributes.ATTACK_DAMAGE) * 0.8;
            target.hurtServer(level, level.damageSources().mobAttack(caster), (float) damage);
            level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY() + 0.6, target.getZ(),
                10, 0.35, 0.35, 0.35, 0.12);
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.FROG_TONGUE, SoundSource.HOSTILE, 1.0f, 0.8f);
        }
        finish(ctx, ticks);
    }

    /** Marks the activation done (arrival or snap) at the given tick. */
    private void finish(AbilityContext ctx, int ticks) {
        ctx.state().putInt("phase", 2);
        ctx.state().putInt("done_tick", ticks);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks >= HARD_CAP_TICKS) {
            return true;
        }
        return ctx.state().getIntOr("phase", 0) == 2
            && ticks - ctx.state().getIntOr("done_tick", 0) >= LINGER_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        if (ctx.mob() instanceof Frog frog) {
            frog.eraseTongueTarget();
        }
    }
}
