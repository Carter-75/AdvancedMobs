package mod.advancedmobs.ability.warden;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SculkChargeParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Cataclysm Boom (Warden ability 1 of 5, attack).
 *
 * WHAT IT DOES: the warden rears back, sculk charge streaks
 * racing up its arms, and instead of the single vanilla sonic
 * boom it fires THREE sonic rings in a spread, one after
 * another. Each ring is a line of concussive force 17 blocks
 * long: anything it touches takes heavy typed damage and is
 * hurled backward, and survivors are wrapped in DARKNESS for
 * 5 seconds while the warden, which hunts by sound and smell
 * anyway, closes in unbothered.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the warden's current target within 20 blocks (distance
 *   squared 400).
 * - The whole activation the warden carries a transient
 *   ATTACK_KNOCKBACK modifier of +1.0 add_value (id
 *   advancedmobs:cataclysm_boom_knockback), the design's
 *   ATTACK_KNOCKBACK line: its ordinary melee swipes throw
 *   victims noticeably farther while the boom is cycling.
 *   Removed in end().
 * - The charge (ticks 1 to 20): the navigation is stopped
 *   each tick (never NoAi). Every 2 ticks, 3 SCULK_CHARGE
 *   particles streak at random points in a 1.2 block radius
 *   around the warden's torso (height 1.0 to 2.4), and a
 *   WARDEN_SONIC_CHARGE sound plays at tick 1 (volume 2.0).
 * - The rings (ticks 21, 29, 37): each ring fires from the
 *   warden's chest (feet height + 1.9). Ring 1 aims at the
 *   target's live position; ring 2 is rotated +0.35 radians
 *   and ring 3 is rotated -0.35 radians around the ring 1
 *   direction, the spread. If the target is gone at a ring
 *   tick, the ring reuses the last aimed direction (stored
 *   in the state tag and refreshed whenever a target is
 *   visible at a ring tick). A WARDEN_SONIC_BOOM sound plays
 *   per ring (volume 2.5, pitch 0.9).
 * - Ring shape: points every 1.0 block from 2.0 to 17.0
 *   blocks along the direction (16 points), one SONIC_BOOM
 *   particle per point at chest height. Victims are found
 *   with ONE bounded query over the box spanning the whole
 *   segment inflated by 2.5 blocks; an enemy (the package
 *   enemy rule) whose 3D distance to the segment is at most
 *   1.6 blocks and who has not already been hit by THIS ring
 *   takes:
 *   - 12.0 points of typed mob-attack damage credited to
 *     the warden (vanilla sonic boom deals 10.0; this is
 *     the heavy version),
 *   - a hurl: delta movement set to 1.5 blocks per tick
 *     horizontally away from the warden and 0.45 up,
 *     hurtMarked set so the hurl syncs,
 *   - DARKNESS amplifier 0 for 100 ticks (5 seconds).
 *   A victim can be hit once per ring, so a victim standing
 *   in the spread can be caught by two or even all three
 *   rings; that is intended, the spread rewards the warden
 *   for cornering its prey.
 * - Finish: tick 45, a short settle after the last ring.
 *
 * WHAT IT LOOKS LIKE: a rearing warden lit by teal sculk
 * streaks crawling up its body, then three visible rings of
 * SONIC_BOOM concussion rolling out in a fan, victims flying
 * backward out of them and stumbling blind in darkness.
 *
 * PERFORMANCE: the charge is one navigation stop plus 3
 * particles every 2 ticks; each ring is one bounded entity
 * query over the segment box and 16 particles. No per-tick
 * entity scans. Particle budget per activation: at most 30
 * charge streaks plus 3 rings times 16 = at most 78. The
 * SCULK_CHARGE particle option is a pre-built static final.
 *
 * CLEANUP: end() removes the ATTACK_KNOCKBACK modifier.
 * DARKNESS on victims is a timed vanilla effect and bleeds
 * off on its own.
 *
 * State tag keys: "dir_x" and "dir_z" (the last aimed ring
 * direction, unit length, horizontal).
 */
public final class CataclysmBoomAbility implements MobAbility {

    static final int DURATION_TICKS = 45;
    private static final int CHARGE_TICKS = 20;
    private static final int[] RING_TICKS = {21, 29, 37};
    private static final double[] RING_OFFSETS = {0.0, 0.35, -0.35};
    private static final double RING_START = 2.0;
    private static final double RING_LENGTH = 17.0;
    private static final double RING_HIT_RADIUS = 1.6;
    private static final float RING_DAMAGE = 12.0f;
    private static final double HURL_HORIZONTAL = 1.5;
    private static final double HURL_UP = 0.45;
    private static final int DARKNESS_TICKS = 100;
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "cataclysm_boom_knockback");
    private static final SculkChargeParticleOptions CHARGE_STREAK =
        new SculkChargeParticleOptions(0.0f);

    @Override
    public String id() {
        return "cataclysm_boom";
    }

    @Override
    public String displayName() {
        return "Cataclysm Boom";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 400.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();

        // The design's ATTACK_KNOCKBACK line: heavier swipes
        // for the whole activation.
        WardenAbilitySupport.addModifier(mob, Attributes.ATTACK_KNOCKBACK,
            new AttributeModifier(KNOCKBACK_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));

        // Seed the ring direction with the target's position
        // right now; ring ticks refresh it while a target lives.
        double dirX = 1.0;
        double dirZ = 0.0;
        LivingEntity target = ctx.target();
        if (target != null) {
            double dx = target.getX() - mob.getX();
            double dz = target.getZ() - mob.getZ();
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length > 1.0e-4) {
                dirX = dx / length;
                dirZ = dz / length;
            }
        }
        state.putDouble("dir_x", dirX);
        state.putDouble("dir_z", dirZ);

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.WARDEN_SONIC_CHARGE,
                SoundSource.HOSTILE, 2.0f, 1.0f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        Mob mob = ctx.mob();
        if (ticks <= CHARGE_TICKS) {
            // The charge: rooted by navigation stops, sculk
            // streaks racing up the arms and torso.
            mob.getNavigation().stop();
            if (ticks % 2 == 0) {
                ServerLevel level = ctx.serverLevel();
                if (level != null) {
                    for (int i = 0; i < 3; i++) {
                        double angle = ctx.random().nextDouble() * Math.PI * 2.0;
                        double radius = 0.6 + ctx.random().nextDouble() * 0.6;
                        level.sendParticles(CHARGE_STREAK,
                            mob.getX() + Math.cos(angle) * radius,
                            mob.getY() + 1.0 + ctx.random().nextDouble() * 1.4,
                            mob.getZ() + Math.sin(angle) * radius,
                            1, 0.0, 0.3, 0.0, 0.05);
                    }
                }
            }
            return;
        }
        for (int ring = 0; ring < RING_TICKS.length; ring++) {
            if (ticks == RING_TICKS[ring]) {
                fireRing(ctx, ring);
                return;
            }
        }
    }

    /** Fires one sonic ring: refresh the aim, draw the line, hit what it touches. */
    private void fireRing(AbilityContext ctx, int ring) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();

        // Refresh the base aim from the live target when there
        // is one; otherwise keep the last aimed direction.
        LivingEntity target = ctx.target();
        if (target != null) {
            double dx = target.getX() - mob.getX();
            double dz = target.getZ() - mob.getZ();
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length > 1.0e-4) {
                state.putDouble("dir_x", dx / length);
                state.putDouble("dir_z", dz / length);
            }
        }
        double baseX = state.getDoubleOr("dir_x", 1.0);
        double baseZ = state.getDoubleOr("dir_z", 0.0);
        double offset = RING_OFFSETS[ring];
        double cos = Math.cos(offset);
        double sin = Math.sin(offset);
        double dirX = baseX * cos - baseZ * sin;
        double dirZ = baseX * sin + baseZ * cos;

        double originX = mob.getX();
        double originY = mob.getY() + 1.9;
        double originZ = mob.getZ();
        Vec3 from = new Vec3(originX + dirX * RING_START, originY, originZ + dirZ * RING_START);
        Vec3 to = new Vec3(originX + dirX * RING_LENGTH, originY, originZ + dirZ * RING_LENGTH);

        level.playSound(null, originX, originY, originZ, SoundEvents.WARDEN_SONIC_BOOM,
            SoundSource.HOSTILE, 2.5f, 0.9f);

        // The visible ring: one SONIC_BOOM mote per block of
        // the line.
        for (double d = RING_START; d <= RING_LENGTH; d += 1.0) {
            level.sendParticles(ParticleTypes.SONIC_BOOM,
                originX + dirX * d, originY, originZ + dirZ * d,
                1, 0.0, 0.0, 0.0, 0.0);
        }

        // The hit pass: one bounded query over the segment
        // box, then a 3D distance check per candidate.
        AABB box = new AABB(Math.min(from.x, to.x) - 2.5, originY - 2.5, Math.min(from.z, to.z) - 2.5,
                            Math.max(from.x, to.x) + 2.5, originY + 2.5, Math.max(from.z, to.z) + 2.5);
        List<LivingEntity> victims = level.getEntitiesOfClass(LivingEntity.class, box,
            e -> WardenAbilitySupport.isEnemy(ctx, e));
        for (LivingEntity victim : victims) {
            Vec3 center = victim.position().add(0.0, victim.getBbHeight() * 0.5, 0.0);
            if (distanceToSegment(center, from, to) > RING_HIT_RADIUS) {
                continue;
            }
            victim.hurtServer(level, level.damageSources().mobAttack(mob), RING_DAMAGE);
            double awayX = victim.getX() - mob.getX();
            double awayZ = victim.getZ() - mob.getZ();
            double awayLength = Math.sqrt(awayX * awayX + awayZ * awayZ);
            if (awayLength < 1.0e-4) {
                awayX = dirX;
                awayZ = dirZ;
                awayLength = 1.0;
            }
            victim.setDeltaMovement(awayX / awayLength * HURL_HORIZONTAL, HURL_UP,
                awayZ / awayLength * HURL_HORIZONTAL);
            victim.hurtMarked = true;
            victim.addEffect(new MobEffectInstance(MobEffects.DARKNESS, DARKNESS_TICKS, 0));
        }
    }

    /** 3D distance from a point to the ring segment. */
    private static double distanceToSegment(Vec3 point, Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double lengthSqr = dx * dx + dy * dy + dz * dz;
        double t = 0.0;
        if (lengthSqr > 1.0e-8) {
            t = Math.max(0.0, Math.min(1.0,
                ((point.x - from.x) * dx + (point.y - from.y) * dy + (point.z - from.z) * dz) / lengthSqr));
        }
        double closestX = from.x + t * dx - point.x;
        double closestY = from.y + t * dy - point.y;
        double closestZ = from.z + t * dz - point.z;
        return Math.sqrt(closestX * closestX + closestY * closestY + closestZ * closestZ);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        WardenAbilitySupport.removeModifier(ctx.mob(), Attributes.ATTACK_KNOCKBACK, KNOCKBACK_ID);
    }
}
