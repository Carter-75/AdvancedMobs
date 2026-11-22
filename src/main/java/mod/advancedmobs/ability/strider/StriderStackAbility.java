package mod.advancedmobs.ability.strider;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Strider Stack (Strider ability 5 of 5, summon and
 * mobility).
 *
 * WHAT IT DOES: a baby strider drops in from above and lands
 * squarely on the adult's head, settling in as a passenger
 * in a proper strider stack, HEART particles drifting from
 * both. The stack moves faster than either strider alone,
 * and the baby spits small lava globs at the adult's enemies
 * from its perch. The baby is a real mob and a permanent
 * teammate: when the ride ends it steps down and stays in
 * the fight, and if the adult is killed first, the baby it
 * leaves behind is very angry indeed.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the caster is a strider with a
 *   live target within 16 blocks (distance squared 256) and
 *   NO passenger right now (a stack of two is the design;
 *   stacking a third is not).
 * - The drop (start()): one baby strider spawns 4 blocks
 *   above the caster (EntityTypes.STRIDER, MOB_SUMMONED,
 *   finalizeSpawn, then setBaby(true): finalizeSpawn may
 *   randomize age, so the baby is forced after it). The
 *   design's half scale is set as a permanent SCALE
 *   attribute BASE value of 0.5 on the new mob (a real mob,
 *   not a prop, so a base value rather than a transient
 *   modifier; this is on top of the baby's own age scaling,
 *   so the rider reads tiny). The baby is allied with the
 *   caster in both directions (MobWarData.addAlly both ways)
 *   and registered in SummonerTracker (which also tags it
 *   umw_summoned for the natural spawn blocker), ordered onto
 *   the caster's current target, and given SLOW_FALLING for
 *   100 ticks so the drop is a float down, not a splat.
 * - The landing (tick(), until mounted): every tick the baby
 *   is steered toward the perch point (the caster's position,
 *   1.6 blocks up) with a velocity write of at most 0.3
 *   blocks per tick toward it. Within 1.3 blocks of the
 *   perch, the baby mounts with startRiding(caster, true,
 *   true). If 100 ticks pass unmounted (the perch kept moving
 *   out of reach), the baby is teleported to the perch and
 *   the mount is forced once more; if even that fails, the
 *   baby fights afoot as an ordinary ally for the rest of the
 *   activation (the "afoot" fallback) and the ability simply
 *   runs its duration without the stack bonuses.
 * - The stack (once mounted): the caster gains a transient
 *   MOVEMENT_SPEED modifier (id
 *   advancedmobs:strider_stack_speed) of +0.35
 *   add_multiplied_base, applied on the mount tick and
 *   removed in end(). Every 20 ticks, 2 HEART particles drift
 *   from each strider. Every 60 ticks, if the caster's live
 *   target is within 12 blocks of the baby, the baby spits
 *   one glob: a SmallFireball owned by the baby, spawned at
 *   its head, accelerated straight at the target's chest
 *   (accelerationPower along the aim line at 0.12, initial
 *   velocity 0.6 along it). The glob is fire and forget:
 *   vanilla handles its flight and impact (5.0 fireball
 *   damage and 5 seconds of fire on a direct hit, and its own
 *   mobGriefing check for block fire), so nothing is tracked.
 * - Losing the baby: if the baby dies or vanishes at any
 *   point, the activation ends at the next isFinished check
 *   (the "lost" flag), mounted or not.
 * - Duration: exactly 400 ticks (20 seconds) from start(),
 *   unless the baby is lost first.
 *
 * CLEANUP AND THE ANGRY BABY: end() removes the caster's
 * speed modifier. The baby is NOT discarded and its ally
 * records are NOT removed: it is a permanent teammate (the
 * Noxious Split precedent). If the caster is still alive, the
 * baby is dismounted (stopRiding) and fights on afoot. If the
 * caster is DEAD (this end() is the framework's termination
 * call), the baby is left where it stands and gains STRENGTH
 * I and SPEED I for 600 ticks each: the design's "killing the
 * adult first leaves a very angry baby behind". Vanilla
 * already dismounts a passenger when its vehicle dies, so no
 * explicit dismount is needed on that path.
 *
 * WHAT IT LOOKS LIKE: a tiny strider floating down out of
 * the sky on slow falling, HEART particles (6 at the drop, 2
 * from each strider every 20 ticks once stacked), a happy
 * STRIDER_HAPPY chirp when it lands on the perch, and small
 * fireballs arcing off the top of the stack every 3 seconds.
 *
 * PERFORMANCE: the drop phase is one UUID resolve and one
 * velocity write per tick; the stack phase resolves the baby
 * once per tick for the lost check and works on 20 and 60
 * tick intervals otherwise. No bounded entity queries at all
 * (the target comes from the context). Particle budget per
 * activation: drop 6, stack hearts at most 20 pulses times 4
 * = 80, total at most 86 particles, plus the globs' own
 * vanilla fire trails.
 *
 * State tag keys: "baby" (the baby strider's UUID),
 * "mounted" (0 still dropping, 1 riding, 2 afoot fallback),
 * "lost" (1 once the baby is gone and the activation should
 * end).
 */
public final class StriderStackAbility implements MobAbility {

    static final int DURATION_TICKS = 400;
    private static final int FORCE_MOUNT_TICKS = 100;
    private static final int SPIT_INTERVAL = 60;
    private static final int HEART_INTERVAL = 20;
    private static final Identifier SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "strider_stack_speed");

    @Override
    public String id() {
        return "strider_stack";
    }

    @Override
    public String displayName() {
        return "Strider Stack";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return ctx.mob() instanceof Strider
            && target != null
            && ctx.mob().distanceToSqr(target) <= 256.0
            && ctx.mob().getPassengers().isEmpty();
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        state.putInt("mounted", 0);
        state.putInt("lost", 0);
        if (level == null || !(mob instanceof Strider caster)) {
            state.putInt("lost", 1);
            return;
        }
        Strider baby = EntityTypes.STRIDER.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (baby == null) {
            state.putInt("lost", 1);
            return;
        }
        baby.snapTo(caster.getX(), caster.getY() + 4.0, caster.getZ(),
            ctx.random().nextFloat() * 360.0f, 0.0f);
        baby.finalizeSpawn(level, level.getCurrentDifficultyAt(baby.blockPosition()),
            EntitySpawnReason.MOB_SUMMONED, null);
        // Forced after finalizeSpawn, which may randomize age.
        baby.setBaby(true);
        // The design's half scale, as a permanent base value on
        // a brand new mob (see the class comment).
        AttributeInstance scale = baby.getAttribute(Attributes.SCALE);
        if (scale != null) {
            scale.setBaseValue(0.5);
        }
        if (!level.addFreshEntity(baby)) {
            state.putInt("lost", 1);
            return;
        }
        // Team assignment, both directions, plus the summoner
        // registry: the same flow every summon in this mod uses.
        ctx.data().addAlly(baby.getUUID());
        MobWarData.get(baby).addAlly(caster.getUUID());
        SummonerTracker.registerSummoned(baby.getUUID(), caster.getUUID());
        LivingEntity target = ctx.target();
        if (target != null) {
            baby.setTarget(target);
        }
        baby.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 100, 0, false, false, false));
        state.store("baby", UUIDUtil.CODEC, baby.getUUID());

        level.sendParticles(ParticleTypes.HEART, caster.getX(), caster.getY() + 2.0, caster.getZ(),
            6, 0.5, 0.5, 0.5, 0.02);
        level.sendParticles(ParticleTypes.HEART, baby.getX(), baby.getY() + 0.5, baby.getZ(),
            6, 0.4, 0.3, 0.4, 0.02);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        Strider baby = resolveBaby(ctx, level);
        if (baby == null || !baby.isAlive()) {
            state.putInt("lost", 1);
            return;
        }
        Mob caster = ctx.mob();
        int ticks = ctx.ticksActive();
        int mounted = state.getIntOr("mounted", 0);

        if (mounted == 0) {
            dropStep(ctx, level, baby, ticks);
            return;
        }
        if (mounted != 1) {
            return;
        }

        // The stack at work: hearts, and the baby's spit.
        if (ticks % HEART_INTERVAL == 0) {
            level.sendParticles(ParticleTypes.HEART, caster.getX(), caster.getY() + 2.0, caster.getZ(),
                2, 0.4, 0.3, 0.4, 0.02);
            level.sendParticles(ParticleTypes.HEART, baby.getX(), baby.getY() + 0.4, baby.getZ(),
                2, 0.3, 0.2, 0.3, 0.02);
        }
        if (ticks % SPIT_INTERVAL == 0) {
            babySpit(ctx, level, baby);
        }
    }

    /**
     * One tick of the drop: steer the baby onto the perch,
     * mount it when it arrives, and force the mount at the
     * deadline (with the afoot fallback if even that fails).
     */
    private static void dropStep(AbilityContext ctx, ServerLevel level, Strider baby, int ticks) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        double perchX = caster.getX();
        double perchY = caster.getY() + 1.6;
        double perchZ = caster.getZ();
        double dx = perchX - baby.getX();
        double dy = perchY - baby.getY();
        double dz = perchZ - baby.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance <= 1.3 || ticks >= FORCE_MOUNT_TICKS) {
            if (ticks >= FORCE_MOUNT_TICKS && distance > 1.3) {
                baby.snapTo(perchX, perchY, perchZ, baby.getYRot(), 0.0f);
            }
            if (baby.startRiding(caster, true, true)) {
                state.putInt("mounted", 1);
                StriderAbilitySupport.addModifier(caster, Attributes.MOVEMENT_SPEED,
                    new AttributeModifier(SPEED_ID, 0.35, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
                level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                    SoundEvents.STRIDER_HAPPY, SoundSource.HOSTILE, 1.0f, 1.2f);
            } else if (ticks >= FORCE_MOUNT_TICKS) {
                // The perch refused the rider: the baby fights
                // afoot as an ordinary ally (see the class
                // comment).
                state.putInt("mounted", 2);
                LivingEntity target = ctx.target();
                if (target != null) {
                    baby.setTarget(target);
                }
            }
            return;
        }
        double step = Math.min(0.3, distance);
        baby.setDeltaMovement(dx / distance * step, dy / distance * step, dz / distance * step);
        baby.hurtMarked = true;
    }

    /**
     * The baby's spit: one small fireball at the caster's live
     * target, vanilla-handled from the moment it leaves (see
     * the class comment).
     */
    private static void babySpit(AbilityContext ctx, ServerLevel level, Strider baby) {
        LivingEntity target = ctx.target();
        if (target == null || baby.distanceToSqr(target) > 144.0) {
            return;
        }
        Vec3 from = new Vec3(baby.getX(), baby.getY() + baby.getBbHeight() + 0.3, baby.getZ());
        Vec3 aim = new Vec3(target.getX() - from.x,
            target.getY() + target.getBbHeight() * 0.6 - from.y,
            target.getZ() - from.z).normalize();
        SmallFireball glob = new SmallFireball(level, baby, aim.scale(0.12));
        glob.snapTo(from.x, from.y, from.z, 0.0f, 0.0f);
        glob.setDeltaMovement(aim.scale(0.6));
        level.addFreshEntity(glob);
    }

    /** Resolves the tracked baby from the state tag, or null when it is gone. */
    private Strider resolveBaby(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        if (!state.contains("baby")) {
            return null;
        }
        UUID id = state.read("baby", UUIDUtil.CODEC).orElse(null);
        Entity entity = id != null ? level.getEntity(id) : null;
        return entity instanceof Strider strider ? strider : null;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS
            || ctx.state().getIntOr("lost", 0) == 1;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        StriderAbilitySupport.removeModifier(caster, Attributes.MOVEMENT_SPEED, SPEED_ID);
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Strider baby = resolveBaby(ctx, level);
        if (baby == null || !baby.isAlive()) {
            return;
        }
        if (caster.isAlive()) {
            // The ride is over; the teammate stays.
            if (baby.isPassenger()) {
                baby.stopRiding();
            }
        } else {
            // The angry baby (see the class comment). Vanilla
            // has already dismounted it from the dead caster.
            baby.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 600, 0, false, false, false));
            baby.addEffect(new MobEffectInstance(MobEffects.SPEED, 600, 0, false, false, false));
        }
    }
}
