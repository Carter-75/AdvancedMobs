package mod.advancedmobs.ability.panda;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.panda.Panda;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Sneeze Bomb (Panda ability 3 of 5, attack and summon).
 *
 * WHAT IT DOES: the panda's nose starts twitching, a baby
 * panda at its side copying it, until both let out an
 * enormous sneeze that blasts everything in front of them
 * backward. If no baby was already there, the sneeze calls
 * one into the fight properly: small, fast, and
 * surprisingly vicious.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the panda's current target within 12 blocks (distance
 *   squared 144).
 * - The baby (start()): one bounded query at radius 8.0
 *   looks for a baby Panda already allied with the caster
 *   (either-direction alliance record). If one is found it
 *   is adopted for this activation (recorded in the state
 *   tag, "summoned" 0): it copies the sneeze and joins the
 *   fight, and end() leaves it exactly as it was found. If
 *   none is found, one is summoned 1.2 blocks to the
 *   caster's left through PandaAbilitySupport (MOB_SUMMONED,
 *   baby age forced, SCALE base value 0.5 permanently,
 *   allied both directions, SummonerTracker registered,
 *   ordered onto the target; "summoned" 1). A summoned baby
 *   is a PERMANENT teammate: it is not discarded in end()
 *   and its ally records stay, per the design (it is
 *   summoned "into the fight properly") and the Noxious
 *   Split precedent. The start() search is what keeps this
 *   from piling up: a baby from an earlier activation is
 *   normally still nearby and gets adopted instead.
 * - The wind-up (ticks 1 to 14): the caster's and the
 *   baby's vanilla sneeze poses are set (sneeze(true)) at
 *   start, PANDA_PRE_SNEEZE plays, and 2 SNEEZE particles
 *   puff at the caster's nose every 3 ticks. Navigation is
 *   NOT stopped: the panda walks up to its victim with its
 *   nose twitching.
 * - The sneeze (tick 15): PANDA_SNEEZE at volume 1.5. The
 *   caster's blast is a frontal cone: every enemy within
 *   radius 6.0 of the caster whose horizontal direction
 *   from the caster has a dot product of at least 0.5 with
 *   the caster's horizontal look direction (a 120 degree
 *   cone) is launched directly away from the caster at 1.8
 *   horizontal and 0.5 up, hurtMarked set. NO damage: the
 *   sneeze is pure displacement, per the design. 30 SNEEZE
 *   particles spray in a cone in front of the caster's
 *   nose. The baby sneezes at the same moment (PANDA_SNEEZE
 *   at volume 1.0, pitch 1.4): a radial blast at radius 4.0
 *   around the baby, launching enemies away from the baby
 *   at 1.2 horizontal and 0.45 up, with 15 SNEEZE
 *   particles. Both sneeze poses are cleared right after
 *   the blast.
 * - The fight after (ticks 16 to 200): the baby is
 *   babysat every 5 ticks (re-ordered onto the caster's
 *   live target when its own target is empty or is the
 *   caster) and its SPEED at amplifier 0 is refreshed for
 *   40 ticks every 20 ticks (the design's SPEED line: the
 *   baby is fast for the whole fight; the effect rides its
 *   own clock past the end, the Caravan Call precedent).
 * - end(): the sneeze poses are cleared if still set
 *   (a termination inside the wind-up). The baby is
 *   deliberately NOT discarded and NOT de-allied, summoned
 *   or adopted: see the baby note above.
 *
 * WHAT IT LOOKS LIKE: two panda noses wrinkling in sync,
 * the little one copying the big one, a rising pre-sneeze
 * whine, then a double blast of SNEEZE spray that sends
 * everything in front of them tumbling backward, and a
 * half-size baby panda charging into the fight at a sprint.
 *
 * PERFORMANCE: start() is one bounded query plus at most
 * one spawn. The wind-up emits 2 particles every 3 ticks.
 * The sneeze tick is two bounded queries. The babysit
 * resolves at most 1 UUID every 5 ticks; the speed refresh
 * touches at most 1 entity every 20 ticks. Particle budget
 * per activation: wind-up 5 times 2 = 10, caster blast 30,
 * baby blast 15, total at most 55 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "baby" (the baby panda's UUID),
 * "summoned" (1 when this activation summoned the baby, 0
 * when it adopted an existing one), "sneezed" (1 once the
 * tick-15 blast has fired).
 */
public final class SneezeBombAbility implements MobAbility {

    private static final int DURATION_TICKS = 200;
    private static final int SNEEZE_TICK = 15;
    private static final double ADOPT_RADIUS = 8.0;
    private static final double CONE_RADIUS = 6.0;
    private static final double CONE_MIN_DOT = 0.5;
    private static final double BABY_BLAST_RADIUS = 4.0;

    @Override
    public String id() {
        return "sneeze_bomb";
    }

    @Override
    public String displayName() {
        return "Sneeze Bomb";
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
        state.putInt("summoned", 0);
        state.putInt("sneezed", 0);
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }

        // Adopt a nearby allied baby, or summon one at the
        // caster's left side.
        Panda baby = findAlliedBaby(ctx, level);
        if (baby == null) {
            Vec3 look = caster.getLookAngle();
            double leftX = -look.z;
            double leftZ = look.x;
            baby = PandaAbilitySupport.spawnBabyPanda(ctx, level,
                caster.getX() + leftX * 1.2, caster.getZ() + leftZ * 1.2);
            if (baby != null) {
                state.putInt("summoned", 1);
            }
        }
        if (baby != null) {
            state.store("baby", UUIDUtil.CODEC, baby.getUUID());
            LivingEntity target = ctx.target();
            if (target != null) {
                baby.setTarget(target);
            }
            baby.sneeze(true);
        }
        if (caster instanceof Panda panda) {
            panda.sneeze(true);
        }
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.PANDA_PRE_SNEEZE, SoundSource.HOSTILE, 1.2f, 1.0f);
    }

    /** The nearest allied baby panda within the adopt radius, or null when there is none. */
    private static Panda findAlliedBaby(AbilityContext ctx, ServerLevel level) {
        Mob caster = ctx.mob();
        AABB box = new AABB(caster.getX() - ADOPT_RADIUS, caster.getY() - 3.0, caster.getZ() - ADOPT_RADIUS,
                            caster.getX() + ADOPT_RADIUS, caster.getY() + 3.0, caster.getZ() + ADOPT_RADIUS);
        List<Panda> pandas = level.getEntitiesOfClass(Panda.class, box,
            p -> p != caster && p.isBaby() && PandaAbilitySupport.isAlly(ctx, p));
        return pandas.isEmpty() ? null : pandas.get(0);
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        CompoundTag state = ctx.state();

        if (ticks < SNEEZE_TICK) {
            // The wind-up: noses twitching, spray gathering.
            if (ticks % 3 == 0) {
                Vec3 look = caster.getLookAngle();
                level.sendParticles(ParticleTypes.SNEEZE,
                    caster.getX() + look.x * 0.6, caster.getY() + 1.2, caster.getZ() + look.z * 0.6,
                    2, 0.1, 0.1, 0.1, 0.02);
            }
            return;
        }
        if (ticks == SNEEZE_TICK && state.getIntOr("sneezed", 0) == 0) {
            state.putInt("sneezed", 1);
            sneeze(ctx, level);
            return;
        }

        // The fight after: babysit the baby and keep it fast.
        Panda baby = resolveBaby(ctx, level);
        if (baby == null || !baby.isAlive()) {
            return;
        }
        if (ticks % 5 == 0) {
            LivingEntity desired = ctx.target();
            LivingEntity babyTarget = baby.getTarget();
            if ((babyTarget == null || babyTarget == caster) && desired != null && desired != baby) {
                baby.setTarget(desired);
            }
        }
        if (ticks % 20 == 0) {
            baby.addEffect(new MobEffectInstance(MobEffects.SPEED, 40, 0));
        }
    }

    /** The double sneeze: the caster's frontal cone and the baby's radial blast. */
    private void sneeze(AbilityContext ctx, ServerLevel level) {
        Mob caster = ctx.mob();
        Vec3 look = caster.getLookAngle();
        double lookLength = Math.sqrt(look.x * look.x + look.z * look.z);
        double lookX = lookLength < 0.001 ? 0.0 : look.x / lookLength;
        double lookZ = lookLength < 0.001 ? 1.0 : look.z / lookLength;

        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.PANDA_SNEEZE, SoundSource.HOSTILE, 1.5f, 1.0f);
        level.sendParticles(ParticleTypes.SNEEZE,
            caster.getX() + lookX * 1.2, caster.getY() + 1.2, caster.getZ() + lookZ * 1.2,
            30, 0.5, 0.3, 0.5, 0.25);

        List<LivingEntity> cone = PandaAbilitySupport.enemiesInBox(
            ctx, caster.getX(), caster.getY(), caster.getZ(), CONE_RADIUS, 2.0, 3.0);
        for (LivingEntity enemy : cone) {
            double awayX = enemy.getX() - caster.getX();
            double awayZ = enemy.getZ() - caster.getZ();
            double awayLength = Math.sqrt(awayX * awayX + awayZ * awayZ);
            if (awayLength < 0.001) {
                continue;
            }
            awayX /= awayLength;
            awayZ /= awayLength;
            if (awayX * lookX + awayZ * lookZ < CONE_MIN_DOT) {
                continue;
            }
            enemy.setDeltaMovement(awayX * 1.8, 0.5, awayZ * 1.8);
            enemy.hurtMarked = true;
        }
        if (caster instanceof Panda panda) {
            panda.sneeze(false);
        }

        // The baby copies the sneeze, smaller and radial.
        Panda baby = resolveBaby(ctx, level);
        if (baby == null || !baby.isAlive()) {
            return;
        }
        level.playSound(null, baby.getX(), baby.getY(), baby.getZ(),
            SoundEvents.PANDA_SNEEZE, SoundSource.HOSTILE, 1.0f, 1.4f);
        level.sendParticles(ParticleTypes.SNEEZE,
            baby.getX(), baby.getY() + 0.7, baby.getZ(),
            15, 0.35, 0.2, 0.35, 0.2);
        List<LivingEntity> blast = PandaAbilitySupport.enemiesInBox(
            ctx, baby.getX(), baby.getY(), baby.getZ(), BABY_BLAST_RADIUS, 2.0, 2.5);
        for (LivingEntity enemy : blast) {
            double awayX = enemy.getX() - baby.getX();
            double awayZ = enemy.getZ() - baby.getZ();
            double awayLength = Math.sqrt(awayX * awayX + awayZ * awayZ);
            if (awayLength < 0.001) {
                awayX = lookX;
                awayZ = lookZ;
            } else {
                awayX /= awayLength;
                awayZ /= awayLength;
            }
            enemy.setDeltaMovement(awayX * 1.2, 0.45, awayZ * 1.2);
            enemy.hurtMarked = true;
        }
        baby.sneeze(false);
    }

    /** Resolves the tracked baby panda from the state tag, or null when gone. */
    private static Panda resolveBaby(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        UUID id = state.contains("baby") ? state.read("baby", UUIDUtil.CODEC).orElse(null) : null;
        if (id != null && level.getEntity(id) instanceof Panda baby) {
            return baby;
        }
        return null;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Clear a wind-up interrupted by termination. The
        // baby is deliberately left in the world with its
        // ally records intact: see the baby note in the
        // class comment.
        Mob caster = ctx.mob();
        if (caster instanceof Panda panda && panda.isSneezing()) {
            panda.sneeze(false);
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Panda baby = resolveBaby(ctx, level);
        if (baby != null && baby.isSneezing()) {
            baby.sneeze(false);
        }
    }
}
