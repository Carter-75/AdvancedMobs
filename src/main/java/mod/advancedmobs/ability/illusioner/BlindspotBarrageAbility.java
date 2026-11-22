package mod.advancedmobs.ability.illusioner;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.illager.Illusioner;

import java.util.List;
import java.util.UUID;

/**
 * Blindspot Barrage (Illusioner ability 2 of 5, attack).
 *
 * WHAT IT DOES: the illusioner snaps its fingers and
 * BLINDNESS rolls over its enemies, and in the dark the
 * sound of five bowstrings comes from five directions at
 * once: three real arrows converge from the illusioner's
 * own hands while a phantom shot whistles from each of its
 * four mirrors. Enemies who cannot see cannot dodge, and
 * the whole barrage lands inside the blindness window.
 * When sight returns, the mirrors vanish with the dark.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the illusioner's current target within 18 blocks
 *   (distance squared 324).
 * - The snap (start()): every enemy within 16 blocks of the
 *   caster (one bounded query) gains BLINDNESS, amplifier
 *   0, for 70 ticks (3.5 seconds), particles and icon shown
 *   (the victim should know it was hexed). An
 *   ILLUSIONER_PREPARE_BLINDNESS and a burst of 12 ENCHANT
 *   motes mark the snap. The blindness is applied once with
 *   a fixed clock and is NOT removed in end(): it expires
 *   at tick 70 on its own, before the ability ends at tick
 *   80, which is the design's "timed to land entirely
 *   inside the blindness window" expressed in durations.
 * - The mirrors (start()): 4 illusioners on the shared
 *   mirror sheet (see IllusionerAbilitySupport: MAX_HEALTH
 *   base 1.0, ATTACK_DAMAGE base 0.0, allied both directions
 *   plus SummonerTracker, no target of their own), spawned
 *   on a circle of radius 3.0 around the caster at slot
 *   angles 45, 135, 225, and 315 degrees relative to its
 *   facing. They are the barrage's phantom archers: the
 *   ability is self-contained and never relies on mirrors
 *   from another ability (only one ability runs at a time,
 *   so borrowed mirrors could never exist).
 * - The formation (tick(), every 5 ticks): each living
 *   mirror is steered by its move control to its slot point
 *   on the caster's live position at radius 3.0, speed 1.2,
 *   target cleared, position recorded; a mirror that no
 *   longer resolves, or resolves dead, pops once with 10
 *   POOF and an ILLUSIONER_HURT (the Hall of Mirrors pop,
 *   minus the portal swirl) and looses no more phantoms.
 * - The volleys (ticks 20, 40, and 60): the caster fires 3
 *   real Arrows from its own eye position at the framework
 *   target's live position: base damage 2.0 (the vanilla
 *   bow arrow), speed 2.6, inaccuracy 1.5, so the three
 *   converge with a hand's breadth of spread. Each living
 *   mirror fires 1 phantom Arrow from its own eye position
 *   at the same target: base damage 0.0, speed 2.6,
 *   inaccuracy 1.0. A phantom whistles and sticks and deals
 *   nothing: five bowstrings heard, one real threat, which
 *   is the trick. 3 ENCHANT motes sparkle at each firing
 *   mirror and 6 at the caster, and one ARROW_SHOOT (volume
 *   1.0) plays at the caster with an ILLUSIONER_CAST_SPELL
 *   under it. A volley with no living target is skipped
 *   whole (the framework target is re-read per volley).
 * - Duration: 80 ticks (4 seconds), hard finish: the last
 *   volley leaves at tick 60 and lands inside the blindness
 *   that lifts at tick 70.
 * - end(): each surviving mirror is discarded in 6 POOF
 *   and the alliance records are removed on both sides. If
 *   the caster dies first, the framework termination path
 *   runs this same end(). The blindness is not touched (see
 *   the snap above).
 *
 * WHAT IT LOOKS LIKE: a snap, a cackle, and the world goes
 * black; out of the dark, bowstrings sing from five points
 * of a ring, sparks trail the false archers, and arrows
 * arrive from everywhere at once, only some of them real.
 * Then sight returns and the ring is gone.
 *
 * PERFORMANCE: start() does 4 spawns and one bounded
 * radius-16 query. tick() returns immediately except every
 * 5th tick, where it resolves at most 4 UUIDs and writes at
 * most 4 move-control wanted positions; the 3 volleys are
 * at most 7 arrow spawns each. No other entity scans.
 * Particle budget per activation: 12 at start, volley
 * sparkles at most 3 times 18 = 54, pops at most 4 times 10
 * = 40, end() puffs at most 4 times 6 = 24, total at most
 * 130 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "mirrors" (list of compounds, one per
 * mirror: "id" the mirror's UUID, "slot" its circle slot
 * 0 to 3, "x", "y", "z" its last recorded position,
 * "popped" 1 once its pop burst ran).
 */
public final class BlindspotBarrageAbility implements MobAbility {

    private static final int DURATION_TICKS = 80;
    private static final int BABYSIT_INTERVAL = 5;
    private static final int BLINDNESS_TICKS = 70;
    private static final int MIRROR_COUNT = 4;
    private static final double FORMATION_RADIUS = 3.0;
    private static final int[] VOLLEY_TICKS = {20, 40, 60};

    @Override
    public String id() {
        return "blindspot_barrage";
    }

    @Override
    public String displayName() {
        return "Blindspot Barrage";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 324.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();
        ListTag mirrors = new ListTag();
        ctx.state().put("mirrors", mirrors);
        if (level == null) {
            return;
        }
        // The snap: blindness rolls over every enemy in reach.
        List<LivingEntity> victims = IllusionerAbilitySupport.enemiesAroundSelf(ctx, 16.0, 4.0, 4.0);
        for (LivingEntity victim : victims) {
            victim.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, BLINDNESS_TICKS, 0));
        }
        level.sendParticles(ParticleTypes.ENCHANT, caster.getX(), caster.getY() + 1.6, caster.getZ(),
            12, 0.5, 0.6, 0.5, 0.15);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.ILLUSIONER_PREPARE_BLINDNESS, SoundSource.HOSTILE, 1.2f, 1.0f);
        // The phantom archers take their ring.
        double yawRad = Math.toRadians(caster.getYRot());
        for (int slot = 0; slot < MIRROR_COUNT; slot++) {
            double angle = yawRad + Math.PI / 4.0 + slot * Math.PI / 2.0;
            Illusioner mirror = IllusionerAbilitySupport.summonMirror(ctx,
                caster.getX() + Math.cos(angle) * FORMATION_RADIUS, caster.getY(),
                caster.getZ() + Math.sin(angle) * FORMATION_RADIUS, caster.getYRot());
            if (mirror == null) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, mirror.getUUID());
            entry.putInt("slot", slot);
            entry.putDouble("x", mirror.getX());
            entry.putDouble("y", mirror.getY());
            entry.putDouble("z", mirror.getZ());
            entry.putInt("popped", 0);
            mirrors.add(entry);
        }
        ctx.state().put("mirrors", mirrors);
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks % BABYSIT_INTERVAL != 0) {
            return; // Cheap early-out: all work rides the 5-tick babysit.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        boolean volleyTick = ticks == VOLLEY_TICKS[0] || ticks == VOLLEY_TICKS[1] || ticks == VOLLEY_TICKS[2];
        LivingEntity target = volleyTick ? ctx.target() : null;
        ListTag mirrors = ctx.state().getListOrEmpty("mirrors");
        double yawRad = Math.toRadians(caster.getYRot());
        for (int i = 0; i < mirrors.size(); i++) {
            CompoundTag entry = mirrors.getCompoundOrEmpty(i);
            if (entry.getIntOr("popped", 0) == 1) {
                continue;
            }
            Entity found = IllusionerAbilitySupport.resolveTracked(level, entry);
            if (found instanceof Illusioner mirror && mirror.isAlive()) {
                entry.putDouble("x", mirror.getX());
                entry.putDouble("y", mirror.getY());
                entry.putDouble("z", mirror.getZ());
                int slot = entry.getIntOr("slot", 0);
                double angle = yawRad + Math.PI / 4.0 + slot * Math.PI / 2.0;
                mirror.getMoveControl().setWantedPosition(
                    caster.getX() + Math.cos(angle) * FORMATION_RADIUS, caster.getY(),
                    caster.getZ() + Math.sin(angle) * FORMATION_RADIUS, 1.2);
                mirror.setTarget(null);
                if (target != null) {
                    // The phantom shot (see the class comment):
                    // real flight from the mirror's hands,
                    // zero damage.
                    IllusionerAbilitySupport.fireArrow(ctx, mirror.getEyePosition(), target, 0.0, 2.6f, 1.0f);
                    level.sendParticles(ParticleTypes.ENCHANT, mirror.getX(), mirror.getY() + 1.6, mirror.getZ(),
                        3, 0.2, 0.3, 0.2, 0.10);
                }
            } else {
                popMirror(ctx, level, entry);
            }
            mirrors.set(i, entry);
        }
        ctx.state().put("mirrors", mirrors);
        if (target != null) {
            // The real arrows converge from the caster's own
            // hands in the same instant as the phantoms.
            for (int shot = 0; shot < 3; shot++) {
                IllusionerAbilitySupport.fireArrow(ctx, caster.getEyePosition(), target, 2.0, 2.6f, 1.5f);
            }
            level.sendParticles(ParticleTypes.ENCHANT, caster.getX(), caster.getY() + 1.6, caster.getZ(),
                6, 0.3, 0.4, 0.3, 0.10);
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.ARROW_SHOOT, SoundSource.HOSTILE, 1.0f, 1.0f);
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.ILLUSIONER_CAST_SPELL, SoundSource.HOSTILE, 0.8f, 1.2f);
        }
    }

    /** The pop burst for one broken mirror (see the class comment). Runs once per mirror. */
    private static void popMirror(AbilityContext ctx, ServerLevel level, CompoundTag entry) {
        entry.putInt("popped", 1);
        UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
        if (id != null) {
            ctx.data().removeAlly(id);
        }
        double x = entry.getDoubleOr("x", ctx.mob().getX());
        double y = entry.getDoubleOr("y", ctx.mob().getY());
        double z = entry.getDoubleOr("z", ctx.mob().getZ());
        level.sendParticles(ParticleTypes.POOF, x, y + 1.2, z, 10, 0.3, 0.7, 0.3, 0.05);
        level.playSound(null, x, y, z, SoundEvents.ILLUSIONER_HURT, SoundSource.HOSTILE, 0.9f, 1.5f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        ListTag mirrors = ctx.state().getListOrEmpty("mirrors");
        for (int i = 0; i < mirrors.size(); i++) {
            CompoundTag entry = mirrors.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id == null) {
                continue;
            }
            ctx.data().removeAlly(id);
            Entity found = level.getEntity(id);
            if (found instanceof Illusioner mirror) {
                MobWarData.get(mirror).removeAlly(caster.getUUID());
                if (mirror.isAlive()) {
                    level.sendParticles(ParticleTypes.POOF, mirror.getX(), mirror.getY() + 1.2, mirror.getZ(),
                        6, 0.3, 0.7, 0.3, 0.05);
                    mirror.discard();
                }
            }
        }
    }
}
