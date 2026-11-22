package mod.advancedmobs.ability.fox;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.fox.Fox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Trickster Decoy (Fox ability 3 of 5, control).
 *
 * WHAT IT DOES: the fox slips sideways into invisibility
 * and leaves a perfect decoy of itself standing exactly
 * where it was. Enemies commit their attacks to the
 * decoy while the real fox reappears behind its target
 * and circles in unseen.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the fox's current target within 14 blocks
 *   (distance squared 196).
 * - The swap (start()): a burst of 8 POOF and 8 autumn
 *   TINTED_LEAVES at the fox's position, and FOX_TELEPORT
 *   at volume 1.0, pitch 1.2. The decoy is summoned at
 *   the fox's exact position and facing: a real fox
 *   (MOB_SUMMONED, finalizeSpawn) with the design's low
 *   health carried as a PERMANENT base value on the new
 *   mob (the summon sheet precedent): MAX_HEALTH base
 *   4.0, healed to full 4.0 so it starts at its cap. It
 *   is tagged "advancedmobs_trickster_decoy" (the
 *   design's taunting tag) and registered in
 *   SummonerTracker. It is deliberately NOT entered in
 *   the war ally records: the whole point is that enemies
 *   treat it as the fox, and the caster's own side must
 *   not read it as a friendly (documented contrast with
 *   the summon packages, whose helpers ARE allies). The
 *   real fox is tagged "advancedmobs_trickster_original"
 *   (the design's original tag), gains INVISIBILITY for
 *   320 ticks (removed in end()), and teleports to the
 *   circle-behind point: the target's position minus its
 *   horizontal look direction times 3.0 blocks, landing
 *   height from one bounded surface scan, facing the
 *   target. If the target is already gone, the teleport
 *   is 5 blocks directly behind the fox's own facing
 *   instead. The misdirect below runs once immediately.
 * - The taunt (tick(), babysit every 5 ticks): the decoy
 *   is held standing where it was left: navigation
 *   stopped every babysit and any target it picks up is
 *   cleared, so it never fights back or wanders off (the
 *   hold is mechanical, through navigation stops, never
 *   NoAi). Its last known position is recorded every
 *   babysit for the death burst.
 * - The misdirect (start() and every 20 ticks): one
 *   bounded radius-14 query around the fox: every enemy
 *   Mob currently targeting the real fox is retargeted
 *   onto the decoy (the Sheep mirage pattern), as long
 *   as the decoy lives. Players are never retargeted by
 *   code; a player who keeps swinging at the invisible
 *   fox is making their own choice.
 * - The decoy's death: the first babysit that finds the
 *   decoy dead or gone bursts 10 POOF at its last known
 *   position, once. The ability keeps running: the real
 *   fox stays invisible and free until the duration ends.
 * - Duration: exactly 300 ticks (15 seconds).
 * - end(): the INVISIBILITY is removed from the real fox
 *   and both tags are removed; a surviving decoy is
 *   dismissed in 6 POOF motes and discarded. Idempotent.
 *
 * WHAT IT LOOKS LIKE: a puff of dust and orange leaves,
 * and the fox is still standing there, or so it seems,
 * while every enemy in the fight turns and commits to
 * it. Behind the target, empty air shimmers where the
 * real fox landed, and the decoy soaks the big hits
 * until it pops in a puff of dust.
 *
 * PERFORMANCE: start() is 1 spawn, 1 surface scan, 1
 * teleport, and 1 bounded query. The babysit every 5
 * ticks resolves at most 1 UUID and writes 3 doubles;
 * the misdirect query runs every 20 ticks only. Particle
 * budget per activation: swap 16, decoy death at most
 * 10, end at most 6, total at most 32 particles. The
 * leaf option is a pre-built static final.
 *
 * TERRAIN: this ability changes no blocks; the
 * mobGriefing gate does not apply.
 *
 * State tag keys: "decoy" (list with one compound: "id",
 * the decoy's UUID), "decoy_gone" (1 once the decoy's
 * death burst has fired), "dx"/"dy"/"dz" (the decoy's
 * last known position).
 */
public final class TricksterDecoyAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int BABYSIT_INTERVAL = 5;
    private static final int MISDIRECT_INTERVAL = 20;
    private static final double DECOY_MAX_HEALTH = 4.0;
    private static final double BEHIND_DISTANCE = 3.0;
    private static final String DECOY_TAG = "advancedmobs_trickster_decoy";
    private static final String ORIGINAL_TAG = "advancedmobs_trickster_original";
    private static final ColorParticleOption LEAF =
        ColorParticleOption.create(ParticleTypes.TINTED_LEAVES, 0.8f, 0.45f, 0.15f);

    @Override
    public String id() {
        return "trickster_decoy";
    }

    @Override
    public String displayName() {
        return "Trickster Decoy";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();
        ctx.state().put("decoy", new ListTag());
        if (level == null) {
            return;
        }
        double fromX = caster.getX();
        double fromY = caster.getY();
        double fromZ = caster.getZ();
        level.sendParticles(ParticleTypes.POOF, fromX, fromY + 0.4, fromZ, 8, 0.3, 0.3, 0.3, 0.06);
        level.sendParticles(LEAF, fromX, fromY + 0.4, fromZ, 8, 0.4, 0.3, 0.4, 0.1);
        level.playSound(null, fromX, fromY, fromZ, SoundEvents.FOX_TELEPORT,
            SoundSource.HOSTILE, 1.0f, 1.2f);

        // The decoy: a real fox left standing in the caster's place.
        Fox decoy = EntityTypes.FOX.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (decoy != null) {
            decoy.snapTo(fromX, fromY, fromZ, caster.getYRot(), 0.0f);
            decoy.finalizeSpawn(level, level.getCurrentDifficultyAt(decoy.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
            if (level.addFreshEntity(decoy)) {
                // The design's low max health, a base value on the new mob.
                if (decoy.getAttribute(Attributes.MAX_HEALTH) != null) {
                    decoy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(DECOY_MAX_HEALTH);
                    decoy.setHealth((float) DECOY_MAX_HEALTH);
                }
                decoy.addTag(DECOY_TAG);
                SummonerTracker.registerSummoned(decoy.getUUID(), caster.getUUID());
                FoxAbilitySupport.trackEntity(ctx.state(), "decoy", decoy);
                ctx.state().putDouble("dx", decoy.getX());
                ctx.state().putDouble("dy", decoy.getY());
                ctx.state().putDouble("dz", decoy.getZ());
            }
        }

        // The real fox: tagged, invisible, and gone behind its target.
        caster.addTag(ORIGINAL_TAG);
        caster.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, DURATION_TICKS + 20, 0, false, false, false));
        LivingEntity target = ctx.target();
        double landX;
        double landZ;
        float faceYaw;
        if (target != null) {
            Vec3 look = target.getLookAngle();
            landX = target.getX() - look.x * BEHIND_DISTANCE;
            landZ = target.getZ() - look.z * BEHIND_DISTANCE;
            faceYaw = target.getYRot();
        } else {
            Vec3 look = caster.getLookAngle();
            landX = caster.getX() - look.x * 5.0;
            landZ = caster.getZ() - look.z * 5.0;
            faceYaw = caster.getYRot();
        }
        double landY = FoxAbilitySupport.findGroundY(level, landX, caster.getY() + 1.0, landZ);
        caster.teleportTo(landX, landY, landZ);
        caster.setYRot(faceYaw);
        caster.getNavigation().stop();

        misdirect(ctx, level);
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks % BABYSIT_INTERVAL == 0) {
            babysit(ctx);
        }
        if (ticks % MISDIRECT_INTERVAL == 0) {
            ServerLevel level = ctx.serverLevel();
            if (level != null) {
                misdirect(ctx, level);
            }
        }
    }

    /** Holds the decoy standing on its spot and watches for its death, every 5 ticks. */
    private void babysit(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag decoys = ctx.state().getListOrEmpty("decoy");
        if (decoys.isEmpty()) {
            return;
        }
        if (FoxAbilitySupport.resolveTracked(level, decoys.getCompoundOrEmpty(0)) instanceof Fox decoy
            && decoy.isAlive()) {
            // The taunt: stand still, never fight back, never wander.
            decoy.getNavigation().stop();
            if (decoy.getTarget() != null) {
                decoy.setTarget(null);
            }
            ctx.state().putDouble("dx", decoy.getX());
            ctx.state().putDouble("dy", decoy.getY());
            ctx.state().putDouble("dz", decoy.getZ());
            return;
        }
        // The decoy has fallen: one dust burst at its last known position.
        if (ctx.state().getIntOr("decoy_gone", 0) == 0) {
            ctx.state().putInt("decoy_gone", 1);
            level.sendParticles(ParticleTypes.POOF,
                ctx.state().getDoubleOr("dx", ctx.mob().getX()),
                ctx.state().getDoubleOr("dy", ctx.mob().getY()) + 0.4,
                ctx.state().getDoubleOr("dz", ctx.mob().getZ()),
                10, 0.3, 0.3, 0.3, 0.06);
        }
    }

    /** Points every enemy hunting the real fox at the decoy instead (see the class comment). */
    private void misdirect(AbilityContext ctx, ServerLevel level) {
        Mob caster = ctx.mob();
        ListTag decoys = ctx.state().getListOrEmpty("decoy");
        if (decoys.isEmpty()) {
            return;
        }
        if (!(FoxAbilitySupport.resolveTracked(level, decoys.getCompoundOrEmpty(0)) instanceof Fox decoy)
            || !decoy.isAlive()) {
            return;
        }
        AABB box = new AABB(caster.getX() - 14.0, caster.getY() - 2.0, caster.getZ() - 14.0,
                            caster.getX() + 14.0, caster.getY() + 4.0, caster.getZ() + 14.0);
        List<Mob> hunters = level.getEntitiesOfClass(Mob.class, box,
            e -> e != caster && e != decoy && e.getTarget() == caster && FoxAbilitySupport.isEnemy(ctx, e));
        for (Mob hunter : hunters) {
            hunter.setTarget(decoy);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.removeEffect(MobEffects.INVISIBILITY);
        caster.removeTag(ORIGINAL_TAG);
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag decoys = ctx.state().getListOrEmpty("decoy");
        if (decoys.isEmpty()) {
            return;
        }
        if (FoxAbilitySupport.resolveTracked(level, decoys.getCompoundOrEmpty(0)) instanceof Fox decoy
            && decoy.isAlive()) {
            decoy.removeTag(DECOY_TAG);
            level.sendParticles(ParticleTypes.POOF, decoy.getX(), decoy.getY() + 0.4, decoy.getZ(),
                6, 0.3, 0.3, 0.3, 0.06);
            decoy.discard();
        }
    }
}
