package mod.advancedmobs.ability.villager;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Emerald Barrage (Villager ability 1 of 5, attack).
 *
 * WHAT IT DOES: the villager panics, digs into its robes, and starts
 * throwing its savings. Emerald after emerald sails at the enemy
 * like a thrown brick, and the longer the barrage runs the faster
 * and wilder the throwing gets, until the pockets are empty.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   villager's current target within 16 blocks (distance squared
 *   256).
 * - The panic dig (ticks 1 to 4): the villager holds position
 *   through navigation stops (never NoAi), 2 emerald ITEM particles
 *   spill at its chest on ticks 2 and 4, and VILLAGER_AMBIENT plays
 *   at start at pitch 1.3 (the panic squeak). The target's live
 *   position is recorded every tick it lives (state tag tx/ty/tz),
 *   so shots still aim at the last known spot if it dies.
 * - The barrage (first shot at tick 5, 10 emeralds total): each
 *   emerald is a real vanilla Snowball projectile created from
 *   EntityTypes.SNOWBALL, owned by the villager through
 *   Projectile.setOwner, with its item set to an emerald stack so
 *   it renders as a flying emerald (the Berry Bombardier carrier
 *   pattern: the snowball's own contact hit is harmless, so every
 *   point of damage comes from the burst below, in code). Each
 *   shot starts at the villager's eye pushed 0.7 blocks along its
 *   look direction and is shot at speed 1.6 toward the target's
 *   recorded chest, raised by 0.20 per horizontal block for a
 *   lobbed arc. VILLAGER_TRADE plays per shot at volume 0.8, pitch
 *   rising 1.0 to 1.4 across the barrage.
 * - Faster and wilder (the design's escalation, in code, the
 *   design's attack_speed style cooldown control): the gap between
 *   shots starts at 5 ticks and shrinks by 1 tick after every 3
 *   shots, down to a floor of 2 ticks (shots land on ticks 5, 10,
 *   15, 19, 23, 27, 30, 33, 36, 38). The shot inaccuracy starts at
 *   1.5 and grows by 0.8 after every 3 shots, up to 4.0. The next
 *   shot tick is kept in the state tag ("next_shot"), never
 *   recomputed from a formula per tick.
 * - The burst (every tick): each tracked emerald that is still
 *   alive refreshes its recorded position and trails 1 emerald
 *   ITEM particle every 2 ticks. An emerald that has resolved (hit
 *   something or expired) bursts once at its last recorded
 *   position: 6 emerald ITEM particles plus 4 CRIT, and every
 *   enemy within 1.6 blocks (vertical reach 1.5, package enemy
 *   rule) takes 3.0 damage through a projectile-typed damage
 *   source (DamageSources.mobProjectile with the villager as both
 *   the projectile stand-in and the owner, the design's "/damage
 *   with projectile damage type" line; the carrier itself has
 *   already resolved, which is why the burst exists). If all 10
 *   bursts catch one victim that is 30 damage total; the growing
 *   inaccuracy makes that unlikely against a moving target.
 * - Duration: finishes at tick 40 at the earliest once all 10
 *   shots are fired and every emerald has burst; hard finish at
 *   tick 120 whatever is still flying (an unresolved emerald is
 *   simply dropped from tracking; end() needs no cleanup because
 *   live emeralds are vanilla projectiles that finish their own
 *   flight).
 *
 * WHAT IT LOOKS LIKE: a villager squeaking and flinging both hands
 * into its robes, then a quickening stream of green gems arcing
 * across the fight, each one popping in a spray of emerald shards
 * where it lands.
 *
 * PERFORMANCE: the dig is one navigation stop per tick plus 2
 * particles on 2 ticks; a shot tick is one spawn and one sound;
 * the tracking pass resolves at most 10 UUIDs per tick with one
 * bounded radius-1.6 query only on a tick where an emerald
 * actually burst. Particle budget per activation: dig 4, trails
 * at most 10 emeralds times 10 = 100, bursts at most 10 times 10
 * = 100, total at most 204 particles.
 *
 * TERRAIN: this ability changes no blocks (the emeralds are
 * vanilla projectiles whose hits never alter blocks); the
 * mobGriefing gate does not apply.
 *
 * State tag keys: "shots" (emeralds fired), "next_shot" (tick of
 * the next shot), "tx"/"ty"/"tz" (last known target position),
 * "emeralds" (list of compounds, one per live emerald: "id",
 * "x", "y", "z").
 */
public final class EmeraldBarrageAbility implements MobAbility {

    private static final int DIG_TICKS = 4;
    private static final int FIRST_SHOT_TICK = 5;
    private static final int SHOT_COUNT = 10;
    private static final int EARLY_FINISH_TICK = 40;
    private static final int HARD_FINISH_TICK = 120;
    private static final double EMERALD_SPEED = 1.6;
    private static final double ARC_LIFT_PER_BLOCK = 0.20;
    private static final double BURST_RADIUS = 1.6;
    private static final double BURST_DAMAGE = 3.0;

    @Override
    public String id() {
        return "emerald_barrage";
    }

    @Override
    public String displayName() {
        return "Emerald Barrage";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().putInt("shots", 0);
        ctx.state().putInt("next_shot", FIRST_SHOT_TICK);
        ctx.state().put("emeralds", new ListTag());
        LivingEntity target = ctx.target();
        if (target != null) {
            recordTarget(ctx, target);
        }
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            Mob caster = ctx.mob();
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.VILLAGER_AMBIENT,
                SoundSource.HOSTILE, 1.0f, 1.3f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        int ticks = ctx.ticksActive();

        LivingEntity target = ctx.target();
        if (target != null) {
            recordTarget(ctx, target);
        }

        if (ticks <= DIG_TICKS) {
            // The panic dig: hold position through navigation, never NoAi.
            caster.getNavigation().stop();
            if (ticks % 2 == 0) {
                level.sendParticles(VillagerAbilitySupport.EMERALD_ITEM,
                    caster.getX(), caster.getY() + 1.2, caster.getZ(), 2, 0.2, 0.2, 0.2, 0.02);
            }
            return;
        }

        int shots = ctx.state().getIntOr("shots", 0);
        if (shots < SHOT_COUNT && ticks >= ctx.state().getIntOr("next_shot", FIRST_SHOT_TICK)) {
            fireEmerald(ctx, level, shots);
            shots++;
            ctx.state().putInt("shots", shots);
            // The escalation: the gap shrinks by 1 after every 3 shots, floor 2.
            int gap = Math.max(2, 5 - shots / 3);
            ctx.state().putInt("next_shot", ticks + gap);
        }

        trackEmeralds(ctx, level, ticks);
    }

    /** Fires one emerald of the barrage (see the class comment). */
    private void fireEmerald(AbilityContext ctx, ServerLevel level, int shotIndex) {
        Mob caster = ctx.mob();
        double tx = ctx.state().getDoubleOr("tx", caster.getX());
        double ty = ctx.state().getDoubleOr("ty", caster.getEyeY());
        double tz = ctx.state().getDoubleOr("tz", caster.getZ());

        Vec3 origin = caster.getEyePosition().add(caster.getLookAngle().scale(0.7));
        double dx = tx - origin.x;
        double dz = tz - origin.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double dy = (ty + 0.5 + horizontal * ARC_LIFT_PER_BLOCK) - origin.y;

        Snowball emerald = EntityTypes.SNOWBALL.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (emerald == null) {
            return;
        }
        emerald.setOwner(caster);
        emerald.setItem(new ItemStack(Items.EMERALD));
        emerald.setPos(origin.x, origin.y, origin.z);
        // The escalation: inaccuracy grows by 0.8 after every 3 shots, cap 4.0.
        float inaccuracy = (float) Math.min(4.0, 1.5 + 0.8 * (shotIndex / 3));
        emerald.shoot(dx, dy, dz, (float) EMERALD_SPEED, inaccuracy);
        if (level.addFreshEntity(emerald)) {
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, emerald.getUUID());
            entry.putDouble("x", emerald.getX());
            entry.putDouble("y", emerald.getY());
            entry.putDouble("z", emerald.getZ());
            ListTag emeralds = ctx.state().getListOrEmpty("emeralds");
            emeralds.add(entry);
            ctx.state().put("emeralds", emeralds);
        }
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.VILLAGER_TRADE,
            SoundSource.HOSTILE, 0.8f, 1.0f + 0.04f * shotIndex);
    }

    /**
     * Refreshes live emeralds' recorded positions, trails them
     * with emerald particles, and bursts the ones that resolved
     * since the last tick (see the class comment). Resolved
     * emeralds are pruned from the tracking list in the same pass.
     */
    private void trackEmeralds(AbilityContext ctx, ServerLevel level, int ticks) {
        ListTag tracked = ctx.state().getListOrEmpty("emeralds");
        if (tracked.isEmpty()) {
            return;
        }
        ListTag remaining = new ListTag();
        for (int i = 0; i < tracked.size(); i++) {
            CompoundTag entry = tracked.getCompoundOrEmpty(i);
            Entity entity = VillagerAbilitySupport.resolveTracked(level, entry);
            if (entity != null && entity.isAlive()) {
                entry.putDouble("x", entity.getX());
                entry.putDouble("y", entity.getY());
                entry.putDouble("z", entity.getZ());
                if (ticks % 2 == 0) {
                    level.sendParticles(VillagerAbilitySupport.EMERALD_ITEM,
                        entity.getX(), entity.getY(), entity.getZ(), 1, 0.05, 0.05, 0.05, 0.01);
                }
                remaining.add(entry);
                continue;
            }
            // Resolved: the burst lands at its last known position.
            double x = entry.getDoubleOr("x", 0.0);
            double y = entry.getDoubleOr("y", 0.0);
            double z = entry.getDoubleOr("z", 0.0);
            level.sendParticles(VillagerAbilitySupport.EMERALD_ITEM, x, y, z, 6, 0.3, 0.25, 0.3, 0.06);
            level.sendParticles(ParticleTypes.CRIT, x, y, z, 4, 0.25, 0.25, 0.25, 0.08);
            Mob caster = ctx.mob();
            List<LivingEntity> caught = VillagerAbilitySupport.enemiesInBox(ctx, x, y, z,
                BURST_RADIUS, 1.5, 1.5);
            for (LivingEntity victim : caught) {
                victim.hurtServer(level, level.damageSources().mobProjectile(caster, caster),
                    (float) BURST_DAMAGE);
            }
        }
        ctx.state().put("emeralds", remaining);
    }

    /** Records the target's live position for aiming after it dies or is lost. */
    private void recordTarget(AbilityContext ctx, LivingEntity target) {
        ctx.state().putDouble("tx", target.getX());
        ctx.state().putDouble("ty", target.getY());
        ctx.state().putDouble("tz", target.getZ());
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks >= HARD_FINISH_TICK) {
            return true;
        }
        return ticks >= EARLY_FINISH_TICK
            && ctx.state().getIntOr("shots", 0) >= SHOT_COUNT
            && ctx.state().getListOrEmpty("emeralds").isEmpty();
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to undo: live emeralds are vanilla projectiles
        // that finish their own flight.
    }
}
