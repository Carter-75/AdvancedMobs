package mod.advancedmobs.ability.zoglin;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
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
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Carrion Call (Zoglin ability 5 of 5, aura).
 *
 * WHAT IT DOES: the zoglin throws back its head and bellows,
 * and every undead ally in earshot answers. For the length of
 * the call, allied zombies, skeletons, and their kin around
 * the zoglin surge with STRENGTH and SPEED, ANGRY_VILLAGER
 * particles boiling off the zoglin's flanks the whole time.
 * The zoglin is the heart of the push: the rally is refreshed
 * from it in pulses, and when the call ends, or when the
 * zoglin is killed mid-call, the buff drops from everything
 * it rallied. The bellow itself is a physical thing at close
 * range: living enemies near the zoglin when it sounds are
 * shoved back by the sheer force of it.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the zoglin's current target within 12 blocks (distance
 *   squared 144), plus at least one undead ally within 14
 *   blocks (one bounded query, the Hive Mind Link precedent:
 *   an empty call never burns the cooldown). Undead ally is
 *   the ZoglinAbilitySupport rule: zombie family, skeleton
 *   family, zoglins, and phantoms with an alliance record to
 *   this caster in either direction.
 * - Duration: exactly 400 ticks (20 seconds) from start().
 * - The bellow (start()): living enemies
 *   (ZoglinAbilitySupport rules) within radius 5.0 of the
 *   zoglin (2.0 below to 3.0 above its feet) are shoved
 *   directly away from it: delta movement set to 1.2
 *   horizontal away and 0.4 up, hurtMarked set so the shove
 *   syncs. The shove deals no damage; it is force, not a hit.
 *   An enemy dead center on top of the zoglin has no away
 *   direction and is skipped.
 * - The rally pulse, every 20 ticks (20 pulses per
 *   activation): every undead ally within radius 12.0 of the
 *   zoglin (2.0 below to 3.0 above its feet) gains STRENGTH
 *   amplifier 0 and SPEED amplifier 0 for 30 ticks. The 30
 *   against the 20 tick pulse means an ally that leaves the
 *   radius keeps the surge about half a second past its last
 *   pulse, and an ally that stays in is refreshed every
 *   pulse. Every rallied ally's UUID is recorded in the state
 *   tag's "rallied" compound, which is what end() strips.
 * - The drop: end() resolves every recorded ally still in the
 *   level and removes its STRENGTH and SPEED effects. That is
 *   the design's "killing it drops the buff from everything
 *   it rallied": on the zoglin's death the framework
 *   termination path runs this same end(), so the surge dies
 *   with the heart that called it. Documented edge, shared
 *   with every fixed effect removal in this system: if an
 *   ally had STRENGTH or SPEED from another source, the
 *   removal takes that too.
 * - The zoglin itself is not buffed: it is the heart of the
 *   push, not a beneficiary of its own call.
 *
 * WHAT IT LOOKS LIKE: the bellow is a ZOGLIN_ANGRY at volume
 * 1.5 and a dropped pitch, with a 16-particle ANGRY_VILLAGER
 * burst off the zoglin's whole body. During the call, every
 * 10 ticks 2 ANGRY_VILLAGER particles boil off each flank
 * (offset perpendicular to its look direction at three
 * quarters of its width, at chest height), 4 per emission
 * across both flanks. Rallied allies show the vanilla
 * STRENGTH and SPEED sparkles while surged.
 *
 * TERRAIN: this ability changes no blocks; the bellow shove
 * is movement, not terrain, so the mobGriefing gate does not
 * apply anywhere in it.
 *
 * PERFORMANCE: the rally pulse runs every 20th tick (one
 * bounded query plus effect applications and state writes for
 * the handful inside), the flank particles every 10th tick
 * (arithmetic only), and every other tick returns
 * immediately. canTrigger runs one bounded query, the
 * accepted Hive Mind Link pattern. end() resolves at most
 * the recorded rallied set. Particle budget per activation:
 * 16 at start plus 40 flank emissions times 4 = 160, total at
 * most 176 particles.
 *
 * CLEANUP: end() strips STRENGTH and SPEED from every
 * recorded rallied ally (see the drop, above). It is
 * idempotent: allies already gone resolve to nothing.
 *
 * State tag keys: "rallied" (compound: ally UUID string to
 * 1, everyone this call has buffed).
 */
public final class CarrionCallAbility implements MobAbility {

    static final int DURATION_TICKS = 400;
    private static final int RALLY_INTERVAL = 20;
    private static final int FLANK_INTERVAL = 10;
    private static final double RALLY_RADIUS = 12.0;
    private static final double SHOVE_RADIUS = 5.0;

    @Override
    public String id() {
        return "carrion_call";
    }

    @Override
    public String displayName() {
        return "Carrion Call";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null || ctx.mob().distanceToSqr(target) > 144.0) {
            return false;
        }
        // An empty call never burns the cooldown.
        return !ZoglinAbilitySupport.undeadAlliesAroundSelf(ctx, 14.0, 2.0, 3.0).isEmpty();
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        ctx.state().put("rallied", new CompoundTag());

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.ZOGLIN_ANGRY,
            SoundSource.HOSTILE, 1.5f, 0.8f);
        level.sendParticles(ParticleTypes.ANGRY_VILLAGER, mob.getX(), mob.getY() + 1.2, mob.getZ(),
            16, 0.9, 0.6, 0.9, 0.08);

        // The shove: living enemies at close range are thrown
        // back by the force of the bellow. No damage.
        List<LivingEntity> victims = ZoglinAbilitySupport.enemiesAroundSelf(ctx, SHOVE_RADIUS, 2.0, 3.0);
        for (LivingEntity victim : victims) {
            double awayX = victim.getX() - mob.getX();
            double awayZ = victim.getZ() - mob.getZ();
            double length = Math.sqrt(awayX * awayX + awayZ * awayZ);
            if (length < 1.0e-4) {
                continue; // Dead center: no away direction.
            }
            victim.setDeltaMovement(awayX / length * 1.2, 0.4, awayZ / length * 1.2);
            victim.hurtMarked = true;
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();

        if (ticks % FLANK_INTERVAL == 0) {
            flankParticles(ctx);
        }
        if (ticks % RALLY_INTERVAL == 0) {
            rallyPulse(ctx);
        }
    }

    /** Boiling rage off the zoglin's flanks: 2 particles a side, perpendicular to its facing. */
    private void flankParticles(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        Vec3 look = mob.getLookAngle();
        double perpX = -look.z;
        double perpZ = look.x;
        double offset = mob.getBbWidth() * 0.75;
        double py = mob.getY() + mob.getBbHeight() * 0.6;
        level.sendParticles(ParticleTypes.ANGRY_VILLAGER,
            mob.getX() + perpX * offset, py, mob.getZ() + perpZ * offset,
            2, 0.2, 0.3, 0.2, 0.03);
        level.sendParticles(ParticleTypes.ANGRY_VILLAGER,
            mob.getX() - perpX * offset, py, mob.getZ() - perpZ * offset,
            2, 0.2, 0.3, 0.2, 0.03);
    }

    /** One rally pulse: the undead allies in earshot surge, and are recorded for the drop. */
    private void rallyPulse(AbilityContext ctx) {
        CompoundTag rallied = ctx.state().getCompoundOrEmpty("rallied");
        List<LivingEntity> allies = ZoglinAbilitySupport.undeadAlliesAroundSelf(ctx, RALLY_RADIUS, 2.0, 3.0);
        for (LivingEntity ally : allies) {
            ally.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 30, 0));
            ally.addEffect(new MobEffectInstance(MobEffects.SPEED, 30, 0));
            rallied.putInt(ally.getUUID().toString(), 1);
        }
        ctx.state().put("rallied", rallied);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // The drop: the surge dies with the call, and with the
        // zoglin when the termination path lands here.
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag rallied = ctx.state().getCompoundOrEmpty("rallied");
        for (String key : rallied.keySet()) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            Entity entity = level.getEntity(id);
            if (entity instanceof LivingEntity ally && ally.isAlive()) {
                ally.removeEffect(MobEffects.STRENGTH);
                ally.removeEffect(MobEffects.SPEED);
            }
        }
    }
}
