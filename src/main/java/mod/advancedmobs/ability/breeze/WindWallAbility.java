package mod.advancedmobs.ability.breeze;

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
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Wind Wall (Breeze ability 5 of 5, defense).
 *
 * WHAT IT DOES: a curved wall of spinning wind rises in front
 * of the breeze for eight seconds, visibly bending the air.
 * Enemy shots that fly into the wall are turned back on their
 * senders, and melee attackers who push through the wall line
 * are slowed and knocked back.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   breeze's current target within 12 blocks (distance squared
 *   144).
 * - Duration: exactly 160 ticks (8 seconds). The wall is fixed
 *   at cast: its anchor is the breeze's position in start() and
 *   its facing is the horizontal direction from the breeze to
 *   its target at cast. The wall does NOT follow the breeze
 *   afterward; the breeze defends the ground it cast on.
 * - The wall band: a point is in the wall when its horizontal
 *   distance from the anchor is between 1.8 and 4.2 blocks, the
 *   angle between its offset from the anchor and the facing is
 *   at most 65 degrees, and it is within 3.5 blocks vertically
 *   of the anchor.
 * - The breeze itself: RESISTANCE amplifier 0 for 170 ticks
 *   applied in start() (it outlasts the wall by half a second),
 *   removed in end(). If the breeze somehow had RESISTANCE from
 *   another source it loses it here, an accepted edge case
 *   shared with every fixed-duration effect in this system.
 * - Projectile deflection, polled every 2 ticks with one
 *   bounded Projectile query over the wall box: a projectile in
 *   the band that is enemy owned or ownerless (the shared
 *   hostile rule; the breeze's own and its allies' shots pass
 *   through untouched) is deflected: its velocity is reversed
 *   and scaled by 1.1, and its owner is set to the breeze, so a
 *   turned shot can hurt its own side (the Wailing Shockwave
 *   pattern). One BREEZE_DEFLECT sound plays per poll that
 *   deflects at least one shot, plus 3 GUST particles at each
 *   deflected shot. A reversed shot leaves the band moving the
 *   other way, so it is not deflected twice.
 * - Melee pushback, polled every 5 ticks: an enemy in the band
 *   gains SLOWNESS amplifier 1 for 40 ticks and is knocked
 *   directly away from the anchor at 0.95 blocks per tick
 *   horizontal and 0.3 up (hurtMarked set). Each enemy can be
 *   pushed at most once per 20 ticks: the state tag keeps a
 *   small list of pushed UUIDs with the tick of their last
 *   push, pruned on every melee poll. The slow still refreshes
 *   on every poll while the attacker stays in the band.
 * - Terrain: the wall is particles and velocity only. No blocks
 *   are placed or broken, so the mobGriefing gate does not
 *   apply.
 *
 * WHAT IT LOOKS AND SOUNDS LIKE: every 3 ticks, one
 * GUST_EMITTER_SMALL particle at each of 5 points along the
 * wall arc (radius 3.0 from the anchor, at angles minus 50,
 * minus 25, 0, plus 25, plus 50 degrees from the facing, at
 * anchor height plus 1.2), so the wall reads as a curved sheet
 * of spinning bursts. BREEZE_CHARGE at the start, BREEZE_DEFLECT
 * on each deflecting poll.
 *
 * PERFORMANCE: per tick the cost is a modulo check; the
 * projectile poll every 2 ticks is one bounded query over the
 * wall box, the melee poll every 5 ticks is one bounded query
 * over the same box, and the arc draw every 3 ticks is 5
 * particles. The pushed list never holds more entries than the
 * enemies currently near the wall. Particle budget per
 * activation: arc draws 54 times 5 = 270, deflection bursts at
 * most 3 per deflected shot (bounded by the shots enemies
 * actually fire), total stated as at most 270 plus deflections.
 *
 * State tag keys: "ax", "ay", "az" (the wall anchor), "fx",
 * "fz" (the facing unit vector), "pushed" (list of compounds,
 * one per recently pushed enemy: "id", its UUID, and "tick",
 * the activation tick of its last push).
 */
public final class WindWallAbility implements MobAbility {

    static final int DURATION_TICKS = 160;
    private static final double BAND_MIN = 1.8;
    private static final double BAND_MAX = 4.2;
    private static final double BAND_COS = Math.cos(Math.toRadians(65.0));
    private static final int PUSH_COOLDOWN_TICKS = 20;

    @Override
    public String id() {
        return "wind_wall";
    }

    @Override
    public String displayName() {
        return "Wind Wall";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        Mob mob = ctx.mob();
        state.putDouble("ax", mob.getX());
        state.putDouble("ay", mob.getY());
        state.putDouble("az", mob.getZ());
        state.put("pushed", new ListTag());

        LivingEntity target = ctx.target();
        double fx;
        double fz;
        if (target != null) {
            fx = target.getX() - mob.getX();
            fz = target.getZ() - mob.getZ();
        } else {
            Vec3 look = mob.getLookAngle();
            fx = look.x;
            fz = look.z;
        }
        double length = Math.sqrt(fx * fx + fz * fz);
        if (length < 1.0e-4) {
            fx = 1.0;
            fz = 0.0;
            length = 1.0;
        }
        state.putDouble("fx", fx / length);
        state.putDouble("fz", fz / length);

        mob.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, DURATION_TICKS + 10, 0));
        ctx.level().playSound(null, mob.getX(), mob.getY(), mob.getZ(),
            SoundEvents.BREEZE_CHARGE, SoundSource.HOSTILE, 1.0f, 1.0f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        int ticks = ctx.ticksActive();

        if (ticks % 3 == 0) {
            drawArc(level, state);
        }
        if (ticks % 2 == 0) {
            deflectProjectiles(ctx, level, state);
        }
        if (ticks % 5 == 0) {
            pushMelee(ctx, level, state, ticks);
        }
    }

    /** Whether a point is inside the wall band (see the class comment). */
    private boolean inBand(CompoundTag state, double x, double y, double z) {
        double ax = state.getDoubleOr("ax", 0.0);
        double ay = state.getDoubleOr("ay", 0.0);
        double az = state.getDoubleOr("az", 0.0);
        double dx = x - ax;
        double dz = z - az;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < BAND_MIN || horizontal > BAND_MAX) {
            return false;
        }
        if (Math.abs(y - ay) > 3.5) {
            return false;
        }
        double dot = (dx * state.getDoubleOr("fx", 1.0) + dz * state.getDoubleOr("fz", 0.0)) / horizontal;
        return dot >= BAND_COS;
    }

    /** The wall box: the bounded query volume shared by both polls. */
    private AABB wallBox(CompoundTag state) {
        double ax = state.getDoubleOr("ax", 0.0);
        double ay = state.getDoubleOr("ay", 0.0);
        double az = state.getDoubleOr("az", 0.0);
        return new AABB(ax - BAND_MAX, ay - 3.5, az - BAND_MAX, ax + BAND_MAX, ay + 3.5, az + BAND_MAX);
    }

    /** The arc draw: 5 GUST_EMITTER_SMALL along the wall curve. */
    private void drawArc(ServerLevel level, CompoundTag state) {
        double ax = state.getDoubleOr("ax", 0.0);
        double ay = state.getDoubleOr("ay", 0.0);
        double az = state.getDoubleOr("az", 0.0);
        double fx = state.getDoubleOr("fx", 1.0);
        double fz = state.getDoubleOr("fz", 0.0);
        double baseAngle = Math.atan2(fz, fx);
        for (int i = -2; i <= 2; i++) {
            double theta = baseAngle + Math.toRadians(25.0 * i);
            level.sendParticles(ParticleTypes.GUST_EMITTER_SMALL,
                ax + Math.cos(theta) * 3.0, ay + 1.2, az + Math.sin(theta) * 3.0,
                1, 0.1, 0.3, 0.1, 0.02);
        }
    }

    /** The projectile deflection poll (see the class comment). */
    private void deflectProjectiles(AbilityContext ctx, ServerLevel level, CompoundTag state) {
        List<Projectile> projectiles = level.getEntitiesOfClass(Projectile.class, wallBox(state));
        boolean deflectedAny = false;
        for (Projectile projectile : projectiles) {
            if (!BreezeAbilitySupport.isHostileProjectile(ctx, projectile)) {
                continue;
            }
            if (!inBand(state, projectile.getX(), projectile.getY(), projectile.getZ())) {
                continue;
            }
            Vec3 velocity = projectile.getDeltaMovement();
            projectile.setDeltaMovement(velocity.scale(-1.1));
            projectile.setOwner(ctx.mob());
            level.sendParticles(ParticleTypes.GUST, projectile.getX(), projectile.getY(), projectile.getZ(),
                3, 0.15, 0.15, 0.15, 0.05);
            deflectedAny = true;
        }
        if (deflectedAny) {
            Mob mob = ctx.mob();
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.BREEZE_DEFLECT, SoundSource.HOSTILE, 1.0f, 1.0f);
        }
    }

    /** The melee pushback poll (see the class comment). */
    private void pushMelee(AbilityContext ctx, ServerLevel level, CompoundTag state, int ticks) {
        double ax = state.getDoubleOr("ax", 0.0);
        double az = state.getDoubleOr("az", 0.0);
        List<LivingEntity> enemies = level.getEntitiesOfClass(LivingEntity.class, wallBox(state),
            e -> BreezeAbilitySupport.isEnemy(ctx, e));
        ListTag pushed = state.getListOrEmpty("pushed");
        // Carry forward every entry still inside its cooldown
        // window, whether or not its enemy is in the band right
        // now, so leaving and re-entering cannot reset the
        // per-enemy push cooldown.
        ListTag kept = new ListTag();
        for (int i = 0; i < pushed.size(); i++) {
            CompoundTag entry = pushed.getCompoundOrEmpty(i);
            if (entry.contains("id") && ticks - entry.getIntOr("tick", -1000) < PUSH_COOLDOWN_TICKS) {
                kept.add(entry);
            }
        }
        for (LivingEntity enemy : enemies) {
            if (!inBand(state, enemy.getX(), enemy.getY(), enemy.getZ())) {
                continue;
            }
            enemy.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 1));
            int lastPush = lastPushTick(kept, enemy);
            if (ticks - lastPush < PUSH_COOLDOWN_TICKS) {
                continue;
            }
            double dx = enemy.getX() - ax;
            double dz = enemy.getZ() - az;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            double scale = horizontal > 1.0e-4 ? 0.95 / horizontal : 0.0;
            Vec3 velocity = enemy.getDeltaMovement();
            enemy.setDeltaMovement(velocity.x + dx * scale, 0.3, velocity.z + dz * scale);
            enemy.hurtMarked = true;
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, enemy.getUUID());
            entry.putInt("tick", ticks);
            kept.add(entry);
        }
        state.put("pushed", kept);
    }

    /** The activation tick of the enemy's last push, or -1000 when never pushed. */
    private int lastPushTick(ListTag pushed, LivingEntity enemy) {
        for (int i = 0; i < pushed.size(); i++) {
            CompoundTag entry = pushed.getCompoundOrEmpty(i);
            if (entry.contains("id")
                && enemy.getUUID().equals(entry.read("id", UUIDUtil.CODEC).orElse(null))) {
                return entry.getIntOr("tick", -1000);
            }
        }
        return -1000;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        ctx.mob().removeEffect(MobEffects.RESISTANCE);
    }
}
