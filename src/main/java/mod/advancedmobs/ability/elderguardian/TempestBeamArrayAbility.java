package mod.advancedmobs.ability.elderguardian;

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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Tempest Beam Array (Elder Guardian ability 2 of 5, attack).
 *
 * WHAT IT DOES: three beam emitters of crackling ELECTRIC_SPARK
 * light anchor around the elder guardian and sweep the arena in
 * slow rotating arcs, each throwing a radial beam outward from
 * the elder through its emitter. BUBBLE particles boil along
 * each beam line. Touching a beam means heavy damage and a jolt
 * of knockback, so enemies must keep moving in the gaps between
 * the sweeps. The array spins faster the longer it runs, turning
 * the arena into a deadly carousel.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   elder guardian's current target within 24 blocks (distance
 *   squared 576), so the array only rises in a real arena fight.
 * - Duration: exactly 400 ticks (20 seconds) from start().
 * - The emitters: 3 sea lantern block displays at scale 0.7,
 *   full brightness, created in start() and recorded by UUID.
 *   They orbit the elder at radius 6.5, at the elder's body
 *   height plus 1.2, spaced 120 degrees apart. The whole array
 *   is anchored to the elder: the orbit center is the elder's
 *   live position every tick, so the array follows it if it
 *   swims. Displays glide on a 2 tick teleport duration and are
 *   discarded in end(). The emitters are displays, not blocks:
 *   the terrain rule does not apply and nothing is placed in the
 *   world.
 * - The spin: the orbit angle lives in the state tag and
 *   advances every tick by 0.02 plus 0.0001 per tick already
 *   elapsed (radians per tick): about 0.57 revolutions over the
 *   first 10 seconds, ending at 0.06 radians per tick, roughly
 *   triple the opening speed, in the last seconds. The beams
 *   sweep with the emitters.
 * - The beams: one per emitter, the radial line from radius 2.0
 *   to radius 12.0 from the elder's center at beam height (the
 *   elder's Y plus 1.2), drawn every 4 ticks as 5 ELECTRIC_SPARK
 *   points (at radii 3.5, 5.5, 7.5, 9.5, 11.5) and 2 BUBBLE
 *   points (at radii 5.5 and 9.5) per beam. There is no beam
 *   inside radius 2.0: standing point-blank against the elder is
 *   inside the array's dead zone, as the geometry says.
 * - Beam contact, checked every 10 ticks: enemies (package
 *   rule) in a radius 12.5 box around the elder (3 below, 3
 *   above) are tested against all three beam lines: project the
 *   enemy's offset onto the beam direction; a hit needs the
 *   projection between 2.0 and 12.0, the perpendicular distance
 *   at most 1.0, and the enemy's center (its Y plus 0.9) within
 *   2.2 of the beam height. A hit deals 4.0 damage typed as the
 *   elder's mob attack and knocks the victim radially away from
 *   the elder (0.8 horizontal, 0.3 up, velocity flagged for
 *   sync). An enemy is hit at most once per contact pulse even
 *   if two beams cross it. At the 10 tick contact cadence a
 *   victim standing in a beam eats 8 damage per second: heavy,
 *   and dodgeable by moving with the gaps, exactly as designed.
 *
 * WHAT IT LOOKS AND SOUNDS LIKE: three glowing sea lantern
 * emitters orbit the elder trailing radial beams of
 * ELECTRIC_SPARK, with BUBBLE particles boiling up along each
 * line. ELDER_GUARDIAN_AMBIENT (volume 1.2, pitch 0.8) sounds as
 * the array rises. The beams are particles only: there is no
 * vanilla beam render for them, the same particle-beam approach
 * Laser Lattice's forks use.
 *
 * PERFORMANCE: per tick the cost is 3 display resolves and
 * snapTo writes plus angle arithmetic. Beams draw on the 4 tick
 * marks (100 pulses, pure math plus 21 particles each) and
 * contact resolves on the 10 tick marks (40 pulses, one bounded
 * entity query over a 25 by 6 by 25 box plus per-enemy line
 * math). Particle budget per activation: 100 drawing pulses
 * times 21 = 2100, plus 12 at start, plus at most 9 POOF in
 * end(), total at most 2121 particles. No attribute modifiers
 * and no effects: end() only discards the three displays.
 *
 * State tag keys: "angle" (the orbit angle in radians, a
 * double), "emitters" (list of compounds, one per emitter
 * display: "id", the display's UUID).
 */
public final class TempestBeamArrayAbility implements MobAbility {

    static final int DURATION_TICKS = 400;
    private static final int DRAW_INTERVAL = 4;
    private static final int CONTACT_INTERVAL = 10;
    private static final int EMITTER_COUNT = 3;
    private static final double ORBIT_RADIUS = 6.5;
    private static final double BEAM_INNER = 2.0;
    private static final double BEAM_OUTER = 12.0;
    private static final double BEAM_HEIGHT = 1.2;
    private static final float CONTACT_DAMAGE = 4.0f;
    private static final double BASE_SPIN = 0.02;
    private static final double SPIN_GROWTH = 0.0001;

    @Override
    public String id() {
        return "tempest_beam_array";
    }

    @Override
    public String displayName() {
        return "Tempest Beam Array";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 576.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        state.putDouble("angle", 0.0);
        ListTag emitters = new ListTag();
        state.put("emitters", emitters);

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        // The emitters rise around the elder.
        for (int i = 0; i < EMITTER_COUNT; i++) {
            Entity display = ElderGuardianAbilitySupport.createBlockDisplay(
                level, Blocks.SEA_LANTERN.defaultBlockState(), 0.7f);
            if (display == null) {
                continue;
            }
            double a = i * (Math.PI * 2.0 / EMITTER_COUNT);
            display.snapTo(mob.getX() + Math.cos(a) * ORBIT_RADIUS,
                mob.getY() + BEAM_HEIGHT, mob.getZ() + Math.sin(a) * ORBIT_RADIUS, 0.0f, 0.0f);
            if (!level.addFreshEntity(display)) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, display.getUUID());
            emitters.add(entry);
        }
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, mob.getX(), mob.getY() + BEAM_HEIGHT, mob.getZ(),
            12, 5.0, 0.5, 5.0, 0.2);
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
            SoundEvents.ELDER_GUARDIAN_AMBIENT, SoundSource.HOSTILE, 1.2f, 0.8f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        int ticks = ctx.ticksActive();

        // The spin: faster the longer the array runs.
        double angle = ctx.state().getDoubleOr("angle", 0.0) + BASE_SPIN + SPIN_GROWTH * ticks;
        ctx.state().putDouble("angle", angle);

        // The emitters hold their orbit around the elder's live
        // position.
        ListTag emitters = ctx.state().getListOrEmpty("emitters");
        for (int i = 0; i < emitters.size(); i++) {
            CompoundTag entry = emitters.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (id == null) {
                continue;
            }
            Entity display = level.getEntity(id);
            if (display == null) {
                continue;
            }
            double a = angle + i * (Math.PI * 2.0 / EMITTER_COUNT);
            display.snapTo(mob.getX() + Math.cos(a) * ORBIT_RADIUS,
                mob.getY() + BEAM_HEIGHT, mob.getZ() + Math.sin(a) * ORBIT_RADIUS, 0.0f, 0.0f);
        }

        if (ticks % DRAW_INTERVAL == 0) {
            drawBeams(ctx, level, angle);
        }
        if (ticks % CONTACT_INTERVAL == 0) {
            checkContacts(ctx, level, angle);
        }
    }

    /** Draws the three radial beams as spark and bubble particle lines. */
    private void drawBeams(AbilityContext ctx, ServerLevel level, double angle) {
        Mob mob = ctx.mob();
        double beamY = mob.getY() + BEAM_HEIGHT;
        for (int i = 0; i < EMITTER_COUNT; i++) {
            double a = angle + i * (Math.PI * 2.0 / EMITTER_COUNT);
            double cos = Math.cos(a), sin = Math.sin(a);
            for (double r : new double[]{3.5, 5.5, 7.5, 9.5, 11.5}) {
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                    mob.getX() + cos * r, beamY, mob.getZ() + sin * r,
                    1, 0.05, 0.05, 0.05, 0.02);
            }
            for (double r : new double[]{5.5, 9.5}) {
                level.sendParticles(ParticleTypes.BUBBLE,
                    mob.getX() + cos * r, beamY, mob.getZ() + sin * r,
                    1, 0.1, 0.1, 0.1, 0.05);
            }
        }
    }

    /**
     * One contact pulse: every enemy touching any beam line
     * takes the beam's damage and knockback once. See the class
     * comment for the line test and the numbers.
     */
    private void checkContacts(AbilityContext ctx, ServerLevel level, double angle) {
        Mob mob = ctx.mob();
        double beamY = mob.getY() + BEAM_HEIGHT;
        List<LivingEntity> victims = ElderGuardianAbilitySupport.enemiesAroundSelf(ctx, BEAM_OUTER + 0.5, 3.0, 3.0);
        for (LivingEntity victim : victims) {
            double dx = victim.getX() - mob.getX();
            double dz = victim.getZ() - mob.getZ();
            if (Math.abs(victim.getY() + 0.9 - beamY) > 2.2) {
                continue;
            }
            for (int i = 0; i < EMITTER_COUNT; i++) {
                double a = angle + i * (Math.PI * 2.0 / EMITTER_COUNT);
                double cos = Math.cos(a), sin = Math.sin(a);
                double along = dx * cos + dz * sin;
                if (along < BEAM_INNER || along > BEAM_OUTER) {
                    continue;
                }
                double across = Math.abs(-dx * sin + dz * cos);
                if (across > 1.0) {
                    continue;
                }
                // Touching the beam: heavy damage and a jolt
                // radially outward, away from the elder.
                victim.hurtServer(level, level.damageSources().mobAttack(mob), CONTACT_DAMAGE);
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > 0.001) {
                    Vec3 motion = victim.getDeltaMovement();
                    victim.setDeltaMovement(motion.x + dx / dist * 0.8, 0.3, motion.z + dz / dist * 0.8);
                    victim.hurtMarked = true;
                }
                break; // One hit per enemy per pulse, even where beams cross.
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // The emitters wink out with the array.
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag emitters = ctx.state().getListOrEmpty("emitters");
        for (int i = 0; i < emitters.size(); i++) {
            CompoundTag entry = emitters.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (id == null) {
                continue;
            }
            Entity display = level.getEntity(id);
            if (display != null) {
                level.sendParticles(ParticleTypes.POOF, display.getX(), display.getY(), display.getZ(),
                    3, 0.2, 0.2, 0.2, 0.05);
                display.discard();
            }
        }
    }
}
