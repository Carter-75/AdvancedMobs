package mod.advancedmobs.ability.skeletonhorse;

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
 * Soulfire Hooves (Skeleton Horse ability 2 of 5, attack / aura).
 *
 * WHAT IT DOES: blue soul fire flames trail from the skeleton
 * horse's hooves and sculk soul wisps curl off its ribs. It
 * hunts its target down, and every enemy it tramples is
 * withered. The ground it crosses keeps burning with soul
 * flame for a few seconds after it passes, and any enemy
 * standing in that burning path catches fire.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the horse's current target within 14 blocks (distance
 *   squared 196) and the horse on the ground (a trample
 *   needs footing).
 * - The hunt (every 5 ticks): the horse is navigation-driven
 *   onto its live target at speed 1.25 whenever it is more
 *   than 2 blocks away (a plain skeleton horse's AI never
 *   closes to trample on its own; the hunt is code-driven,
 *   the Nightmare Steed precedent).
 * - The hoof trail (every 2 ticks): 1 SOUL_FIRE_FLAME at the
 *   hooves, offset 0.35 blocks to alternating sides of the
 *   body axis, so the flame steps left-right-left like a
 *   gallop.
 * - The rib glow (every 10 ticks): 2 SCULK_SOUL wisps at rib
 *   height (the design's sculk soul wisps on the ribs).
 * - Ember points (maintained every 10 ticks): the horse's
 *   position is recorded as an ember point whenever it has
 *   moved at least 1.5 blocks from the last recorded point.
 *   At most 8 points are kept (the oldest is dropped when a
 *   ninth is recorded) and each point lives 60 ticks (the
 *   design's "keeps burning for a few seconds"). Each
 *   maintenance pass sends 1 SOUL_FIRE_FLAME at every live
 *   point: the burning path rendered as particles. The path
 *   is PARTICLES ONLY: no fire blocks are ever placed, so
 *   the terrain rule's mobGriefing gate does not apply.
 * - The path burn (on the same 10-tick pass): one bounded
 *   enemy query of radius 9 around the horse; each enemy
 *   within 1.2 blocks of any live ember point is ignited
 *   for 2 seconds (igniteForSeconds(2): vanilla burning
 *   delivers the fire ticks; the design's "fire ticks on
 *   the path in code"). Allies and the caster are never
 *   ignited (the package enemy rule).
 * - The trample (checked every 4 ticks): one bounded enemy
 *   query of radius 1.8 around the horse. Each enemy found
 *   whose per-enemy trample clock allows it (at most once
 *   per enemy per 20 ticks; the clocks live in the state
 *   tag's "trample_cd" list, capped at 16 entries, oldest
 *   dropped) takes 4.0 damage as a mob attack credited to
 *   the horse (a flat code value: a skeleton horse has no
 *   ATTACK_DAMAGE attribute instance in 26.2, verified
 *   against the jar) and gains WITHER I for 100 ticks (the
 *   design's wither on trample), with 6 SOUL_FIRE_FLAME at
 *   the victim and SKELETON_HORSE_HURT at volume 1.0, pitch
 *   1.2.
 * - Duration: exactly 300 ticks (15 seconds), hard finish.
 * - end(): nothing to strip. The ember points live only in
 *   the state tag and die with it; wither and ignition
 *   already on victims are ordinary vanilla effects and
 *   burn or tick out on their own clocks.
 *
 * WHAT IT LOOKS LIKE: a skeleton horse wreathed in blue
 * flame at the hooves, soul wisps curling off its ribcage,
 * galloping a burning blue path across the fight that keeps
 * smoldering behind it and sets anything standing in it
 * alight, while everything it runs down withers.
 *
 * PERFORMANCE: the hoof trail is 1 particle every 2 ticks;
 * the hunt is one navigation drive every 5 ticks; the
 * trample is one tight bounded query every 4 ticks with
 * cooldown lookups over at most 16 stored UUIDs; the
 * 10-tick pass resolves at most 8 stored points and runs at
 * most one radius-9 query whose hits are checked against at
 * most 8 points. Particle budget per activation: hooves 150,
 * ribs 30 times 2 = 60, ember flames at most 30 passes
 * times 8 = 240, tramples at most 15 times 6 = 90, start 8,
 * total at most 548 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply. The burning path is particles and entity
 * ignition, never placed fire.
 *
 * State tag keys: "embers" (list of compounds, one per
 * ember point: "x", "y", "z" doubles, "born" the tick it
 * was recorded), "last_x", "last_z" (the last recorded
 * point's horizontal position), "trample_cd" (list of
 * compounds, one per trampled enemy: "id" its UUID, "next"
 * the first tick it may be trampled again).
 */
public final class SoulfireHoovesAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int TRAIL_INTERVAL = 2;
    private static final int CHASE_INTERVAL = 5;
    private static final int TRAMPLE_INTERVAL = 4;
    private static final int EMBER_INTERVAL = 10;
    private static final int EMBER_MAX_POINTS = 8;
    private static final int EMBER_LIFE_TICKS = 60;
    private static final double EMBER_RECORD_DISTANCE_SQR = 2.25; // 1.5 blocks
    private static final double EMBER_BURN_RADIUS_SQR = 1.44; // 1.2 blocks
    private static final float EMBER_IGNITE_SECONDS = 2.0f;
    private static final double TRAMPLE_RADIUS = 1.8;
    private static final double TRAMPLE_DAMAGE = 4.0;
    private static final int TRAMPLE_COOLDOWN_TICKS = 20;
    private static final int TRAMPLE_CD_CAP = 16;

    @Override
    public String id() {
        return "soulfire_hooves";
    }

    @Override
    public String displayName() {
        return "Soulfire Hooves";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 196.0 && ctx.mob().onGround();
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ctx.state().put("embers", new ListTag());
        ctx.state().put("trample_cd", new ListTag());
        ctx.state().putDouble("last_x", caster.getX());
        ctx.state().putDouble("last_z", caster.getZ());
        recordEmber(ctx, 0);

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.SKELETON_HORSE_AMBIENT, SoundSource.HOSTILE, 1.2f, 0.9f);
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, caster.getX(), caster.getY() + 0.4, caster.getZ(),
                8, 0.5, 0.3, 0.5, 0.08);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        int ticks = ctx.ticksActive();
        if (level == null) {
            return;
        }

        // The hoof trail: alternating sides, like a gallop.
        if (ticks % TRAIL_INTERVAL == 0) {
            double side = (ticks / TRAIL_INTERVAL) % 2 == 0 ? 0.35 : -0.35;
            double lookX = caster.getLookAngle().x;
            double lookZ = caster.getLookAngle().z;
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                caster.getX() - lookZ * side, caster.getY() + 0.25, caster.getZ() + lookX * side,
                1, 0.12, 0.08, 0.12, 0.02);
        }

        LivingEntity target = ctx.target();
        // The hunt: close on the target.
        if (target != null && ticks % CHASE_INTERVAL == 0 && caster.distanceToSqr(target) > 4.0) {
            caster.getNavigation().moveTo(target, 1.25);
        }
        if (ticks % TRAMPLE_INTERVAL == 0) {
            trample(ctx, ticks);
        }
        if (ticks % EMBER_INTERVAL == 0) {
            emberPass(ctx, ticks);
        }
    }

    /** The trample: withering damage to enemies under the hooves, every 4 ticks. */
    private void trample(AbilityContext ctx, int ticks) {
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();
        List<LivingEntity> victims = SkeletonHorseAbilitySupport.enemiesInBox(ctx,
            caster.getX(), caster.getY(), caster.getZ(), TRAMPLE_RADIUS, 1.5, 2.0);
        for (LivingEntity victim : victims) {
            if (!trampleReady(ctx, victim.getUUID(), ticks)) {
                continue;
            }
            markTrampled(ctx, victim.getUUID(), ticks + TRAMPLE_COOLDOWN_TICKS);
            victim.hurtServer(level, level.damageSources().mobAttack(caster), (float) TRAMPLE_DAMAGE);
            victim.addEffect(new MobEffectInstance(MobEffects.WITHER, 100, 0));
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, victim.getX(), victim.getY() + 0.6, victim.getZ(),
                6, 0.4, 0.4, 0.4, 0.08);
            level.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
                SoundEvents.SKELETON_HORSE_HURT, SoundSource.HOSTILE, 1.0f, 1.2f);
        }
    }

    /** Whether the given enemy may be trampled again at this tick. */
    private boolean trampleReady(AbilityContext ctx, UUID victimId, int ticks) {
        ListTag cooldowns = ctx.state().getListOrEmpty("trample_cd");
        for (int i = 0; i < cooldowns.size(); i++) {
            CompoundTag entry = cooldowns.getCompoundOrEmpty(i);
            UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (victimId.equals(id)) {
                return ticks >= entry.getIntOr("next", 0);
            }
        }
        return true;
    }

    /** Records the next tick the given enemy may be trampled again, capping the list. */
    private void markTrampled(AbilityContext ctx, UUID victimId, int nextTick) {
        ListTag cooldowns = ctx.state().getListOrEmpty("trample_cd");
        for (int i = 0; i < cooldowns.size(); i++) {
            CompoundTag entry = cooldowns.getCompoundOrEmpty(i);
            UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (victimId.equals(id)) {
                entry.putInt("next", nextTick);
                ctx.state().put("trample_cd", cooldowns);
                return;
            }
        }
        CompoundTag entry = new CompoundTag();
        entry.store("id", UUIDUtil.CODEC, victimId);
        entry.putInt("next", nextTick);
        cooldowns.add(entry);
        while (cooldowns.size() > TRAMPLE_CD_CAP) {
            cooldowns.remove(0);
        }
        ctx.state().put("trample_cd", cooldowns);
    }

    /** The 10-tick ember pass: rib wisps, point recording and expiry, path flames, path burn. */
    private void emberPass(AbilityContext ctx, int ticks) {
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();

        // The rib glow.
        level.sendParticles(ParticleTypes.SCULK_SOUL, caster.getX(), caster.getY() + 1.1, caster.getZ(),
            2, 0.45, 0.35, 0.45, 0.03);

        // Record a new point when the horse has crossed enough ground.
        double lastX = ctx.state().getDoubleOr("last_x", caster.getX());
        double lastZ = ctx.state().getDoubleOr("last_z", caster.getZ());
        double dx = caster.getX() - lastX;
        double dz = caster.getZ() - lastZ;
        if (dx * dx + dz * dz >= EMBER_RECORD_DISTANCE_SQR) {
            recordEmber(ctx, ticks);
            ctx.state().putDouble("last_x", caster.getX());
            ctx.state().putDouble("last_z", caster.getZ());
        }

        // Expire old points, flame the live ones, collect their positions.
        ListTag embers = ctx.state().getListOrEmpty("embers");
        double[][] points = new double[embers.size()][3];
        int live = 0;
        ListTag kept = new ListTag();
        for (int i = 0; i < embers.size(); i++) {
            CompoundTag point = embers.getCompoundOrEmpty(i);
            if (ticks - point.getIntOr("born", 0) > EMBER_LIFE_TICKS) {
                continue;
            }
            kept.add(point);
            double x = point.getDoubleOr("x", caster.getX());
            double y = point.getDoubleOr("y", caster.getY());
            double z = point.getDoubleOr("z", caster.getZ());
            points[live][0] = x;
            points[live][1] = y;
            points[live][2] = z;
            live++;
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, x, y + 0.2, z,
                1, 0.25, 0.1, 0.25, 0.02);
        }
        ctx.state().put("embers", kept);
        if (live == 0) {
            return;
        }

        // The path burn: enemies standing in the burning path catch fire.
        List<LivingEntity> nearby = SkeletonHorseAbilitySupport.enemiesInBox(ctx,
            caster.getX(), caster.getY(), caster.getZ(), 9.0, 2.0, 3.0);
        for (LivingEntity enemy : nearby) {
            for (int i = 0; i < live; i++) {
                double ex = enemy.getX() - points[i][0];
                double ez = enemy.getZ() - points[i][2];
                if (ex * ex + ez * ez <= EMBER_BURN_RADIUS_SQR) {
                    enemy.igniteForSeconds(EMBER_IGNITE_SECONDS);
                    break;
                }
            }
        }
    }

    /** Records the caster's current position as an ember point, capping the list. */
    private void recordEmber(AbilityContext ctx, int ticks) {
        Mob caster = ctx.mob();
        ListTag embers = ctx.state().getListOrEmpty("embers");
        CompoundTag point = new CompoundTag();
        point.putDouble("x", caster.getX());
        point.putDouble("y", caster.getY());
        point.putDouble("z", caster.getZ());
        point.putInt("born", ticks);
        embers.add(point);
        while (embers.size() > EMBER_MAX_POINTS) {
            embers.remove(0);
        }
        ctx.state().put("embers", embers);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to strip: the ember points live only in the state tag,
        // and wither and ignition on victims ride their own clocks.
    }
}
