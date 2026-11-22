package mod.advancedmobs.ability.chicken;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.UUID;

/**
 * Egg Barrage (Chicken ability 1 of 5, attack).
 *
 * WHAT IT DOES: the chicken flutters up onto its firing
 * perch, digs in, and machine guns a dozen real eggs
 * across the fight, one every few ticks. Every egg that
 * lands bursts in a spray of shell shards, and while one
 * egg barely stings, the stream staggers whatever it
 * peppers and pecks unarmored mobs apart.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the chicken's current target within 16 blocks
 *   (distance squared 256).
 * - The perch (start()): the chicken hops with a single
 *   upward velocity write of 0.55 (its vanilla flap
 *   physics slows the descent and chickens take no fall
 *   damage), and the classic variant ambient cluck plays
 *   (volume 1.2, pitch 1.1). For the whole activation the
 *   navigation is stopped every tick (never NoAi): the
 *   perch is a held position, not a pathing goal.
 * - The stream (ticks 3 to 36): one real ThrownEgg every
 *   3 ticks, 12 eggs total (the design's attack speed
 *   carried as a code firing interval). Each egg is
 *   created with the ThrownEgg(Level, LivingEntity,
 *   ItemStack) constructor owned by the chicken, spawns
 *   at the chicken's eye pushed 0.4 blocks along its look
 *   direction, and is shot at speed 1.6 with inaccuracy
 *   1.5 toward the target's chest (feet plus three
 *   quarters of its height), the aim lifted by 0.06 per
 *   block of horizontal distance so the eggs arc instead
 *   of flying flat (the Cud Cannon lift). If the target
 *   dies mid-stream the eggs keep coming at its last
 *   recorded position. A CHICKEN_EGG pop plays per shot
 *   (volume 0.7, pitch 1.5). The eggs are real vanilla
 *   eggs: their own contact hit registers 0 damage, and
 *   an egg that strikes a mob has vanilla's 1 in 8 chance
 *   to hatch a wild baby chicken on the spot; that chick
 *   is an ordinary wild animal, untracked and unallied,
 *   and is not this ability's concern.
 * - The burst (tracked per egg): every egg is tracked by
 *   UUID with its last known position, refreshed each
 *   tick. The first tick an egg no longer resolves it
 *   has impacted: at its last position, 6 EGG_CRACK
 *   particles spray, and every enemy within 1.5 blocks
 *   takes 2.0 damage from the projectile-typed (THROWN)
 *   source credited to the chicken (the design's typed
 *   damage line; a chicken has no ATTACK_DAMAGE instance,
 *   so the number is a flat code value, see the support
 *   class comment) plus SLOWNESS I (amplifier 0) for 30
 *   ticks, the stagger: a peppered victim keeps
 *   re-staggering as more eggs land, and 12 hits total
 *   24.0 damage. An egg that flies into an unloaded chunk
 *   also reads as resolved and bursts at its last
 *   position, the standing projectile tracker behavior in
 *   this mod.
 * - Duration: the ability finishes once all 12 eggs are
 *   fired and every tracked egg has burst; hard finish
 *   at 120 ticks.
 * - end(): any egg still in flight is discarded with no
 *   burst (the Magma Spit precedent: a terminated
 *   barrage never lands late hits). No attribute
 *   modifiers or caster effects are applied by this
 *   ability, so there is nothing else to reverse; the
 *   victims' SLOWNESS expires on its own clock.
 *
 * WHAT IT LOOKS LIKE: a cluck, a flutter up, and then a
 * hen sitting tall in one spot spitting a pop-pop-pop
 * stream of white eggs that arc across the fight and
 * crack open in little shell shard sprays, each splash
 * making the victim flinch and stumble.
 *
 * PERFORMANCE: per tick one navigation stop, at most one
 * egg spawn on a shot tick, and one tracking pass over at
 * most 12 UUIDs with position writes only; the burst's
 * radius-1.5 query runs at most 12 times, once per egg.
 * The thrown damage source is built only on a burst tick.
 * Particle budget per activation: 12 bursts times 6 =
 * 72 particles, nothing else.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "fired" (eggs shot so far), "tx", "ty",
 * "tz" (the target's last recorded position), "eggs"
 * (list of compounds, one per egg in flight: "id" the
 * egg's UUID, "x", "y", "z" its last recorded position).
 */
public final class EggBarrageAbility implements MobAbility {

    private static final int EGG_COUNT = 12;
    private static final int SHOT_INTERVAL = 3;
    private static final int FIRST_SHOT_TICK = 3;
    private static final int HARD_FINISH_TICKS = 120;
    private static final float EGG_SPEED = 1.6f;
    private static final float EGG_INACCURACY = 1.5f;
    private static final double AIM_LIFT_PER_BLOCK = 0.06;
    private static final double BURST_RADIUS = 1.5;
    private static final float BURST_DAMAGE = 2.0f;
    private static final int STAGGER_TICKS = 30;

    @Override
    public String id() {
        return "egg_barrage";
    }

    @Override
    public String displayName() {
        return "Egg Barrage";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ctx.state().putInt("fired", 0);
        ctx.state().put("eggs", new ListTag());
        LivingEntity target = ctx.target();
        if (target != null) {
            recordTarget(ctx, target);
        } else {
            ctx.state().putDouble("tx", caster.getX());
            ctx.state().putDouble("ty", caster.getY());
            ctx.state().putDouble("tz", caster.getZ());
        }
        // The flutter up onto the perch (see the class comment).
        caster.setDeltaMovement(caster.getDeltaMovement().x, 0.55, caster.getDeltaMovement().z);
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            Holder<SoundEvent> cluck = ChickenAbilitySupport.chickenSound(false);
            if (cluck != null) {
                level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), cluck,
                    SoundSource.HOSTILE, 1.2f, 1.1f);
            }
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.getNavigation().stop(); // The perch: hold position (never NoAi).
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        LivingEntity target = ctx.target();
        if (target != null) {
            recordTarget(ctx, target);
        }
        int ticks = ctx.ticksActive();
        int fired = ctx.state().getIntOr("fired", 0);
        if (fired < EGG_COUNT && ticks >= FIRST_SHOT_TICK + fired * SHOT_INTERVAL) {
            fireEgg(ctx, level);
            ctx.state().putInt("fired", fired + 1);
        }
        trackEggs(ctx, level);
    }

    /** Records the target's live position as the stream's aim point. */
    private static void recordTarget(AbilityContext ctx, LivingEntity target) {
        ctx.state().putDouble("tx", target.getX());
        ctx.state().putDouble("ty", target.getY());
        ctx.state().putDouble("tz", target.getZ());
    }

    /** Fires one egg of the stream at the recorded aim point (see the class comment). */
    private static void fireEgg(AbilityContext ctx, ServerLevel level) {
        Mob caster = ctx.mob();
        ThrownEgg egg = new ThrownEgg(level, caster, new ItemStack(Items.EGG));
        double eyeX = caster.getEyePosition().x + caster.getLookAngle().x * 0.4;
        double eyeY = caster.getEyePosition().y;
        double eyeZ = caster.getEyePosition().z + caster.getLookAngle().z * 0.4;
        egg.snapTo(eyeX, eyeY, eyeZ, caster.getYRot(), caster.getXRot());
        double tx = ctx.state().getDoubleOr("tx", caster.getX());
        double ty = ctx.state().getDoubleOr("ty", caster.getY());
        double tz = ctx.state().getDoubleOr("tz", caster.getZ());
        LivingEntity target = ctx.target();
        double aimY = ty + (target != null ? target.getBbHeight() * 0.75 : 0.75);
        double dx = tx - eyeX;
        double dz = tz - eyeZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double dy = aimY - eyeY + horizontal * AIM_LIFT_PER_BLOCK;
        egg.shoot(dx, dy, dz, EGG_SPEED, EGG_INACCURACY);
        if (!level.addFreshEntity(egg)) {
            return;
        }
        CompoundTag entry = new CompoundTag();
        entry.store("id", UUIDUtil.CODEC, egg.getUUID());
        entry.putDouble("x", egg.getX());
        entry.putDouble("y", egg.getY());
        entry.putDouble("z", egg.getZ());
        ListTag eggs = ctx.state().getListOrEmpty("eggs");
        eggs.add(entry);
        ctx.state().put("eggs", eggs);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.CHICKEN_EGG,
            SoundSource.HOSTILE, 0.7f, 1.5f);
    }

    /**
     * Refreshes the tracked eggs' positions and bursts the
     * ones that have impacted (see the class comment).
     * Resolved eggs are pruned from the state tag.
     */
    private static void trackEggs(AbilityContext ctx, ServerLevel level) {
        ListTag tracked = ctx.state().getListOrEmpty("eggs");
        if (tracked.isEmpty()) {
            return;
        }
        ListTag remaining = new ListTag();
        for (int i = 0; i < tracked.size(); i++) {
            CompoundTag entry = tracked.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            Entity found = id != null ? level.getEntity(id) : null;
            if (found instanceof ThrownEgg egg && egg.isAlive()) {
                entry.putDouble("x", egg.getX());
                entry.putDouble("y", egg.getY());
                entry.putDouble("z", egg.getZ());
                remaining.add(entry);
            } else {
                burst(ctx, level, entry.getDoubleOr("x", ctx.mob().getX()),
                    entry.getDoubleOr("y", ctx.mob().getY()), entry.getDoubleOr("z", ctx.mob().getZ()));
            }
        }
        ctx.state().put("eggs", remaining);
    }

    /** One egg's impact burst: shell shards, typed damage, and the stagger (see the class comment). */
    private static void burst(AbilityContext ctx, ServerLevel level, double x, double y, double z) {
        level.sendParticles(ParticleTypes.EGG_CRACK, x, y, z, 6, 0.2, 0.2, 0.2, 0.05);
        List<LivingEntity> victims = ChickenAbilitySupport.enemiesInBox(ctx, x, y, z,
            BURST_RADIUS, BURST_RADIUS, BURST_RADIUS);
        if (victims.isEmpty()) {
            return;
        }
        var source = ChickenAbilitySupport.thrownSource(ctx);
        for (LivingEntity victim : victims) {
            victim.hurtServer(level, source, BURST_DAMAGE);
            victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, STAGGER_TICKS, 0));
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        if (ctx.ticksActive() >= HARD_FINISH_TICKS) {
            return true;
        }
        return ctx.state().getIntOr("fired", 0) >= EGG_COUNT
            && ctx.state().getListOrEmpty("eggs").isEmpty();
    }

    @Override
    public void end(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        // Eggs still in flight are discarded with no burst
        // (see the class comment).
        ListTag tracked = ctx.state().getListOrEmpty("eggs");
        for (int i = 0; i < tracked.size(); i++) {
            Entity found = ChickenAbilitySupport.resolveTracked(level, tracked.getCompoundOrEmpty(i));
            if (found instanceof ThrownEgg egg && egg.isAlive()) {
                egg.discard();
            }
        }
    }
}
