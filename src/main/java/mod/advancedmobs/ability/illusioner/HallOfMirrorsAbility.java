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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.illager.Illusioner;

import java.util.UUID;

/**
 * Hall of Mirrors (Illusioner ability 1 of 5, summon,
 * defense).
 *
 * WHAT IT DOES: the illusioner throws wide its arms and the
 * hall fills with itself: four summoned duplicates take
 * positions in a circle around it, each with a single point
 * of health and harmless arrows, all bowing and weaving in
 * formation around the real one. Every 3 seconds the whole
 * set shuffles positions in swirls of PORTAL particles, so
 * even a marked original is lost again, and the real
 * illusioner keeps casting from inside the pattern. Enemies
 * must burn hits on mirrors to find the caster; every mirror
 * broken is information bought with time.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the illusioner's current target within 20 blocks
 *   (distance squared 400).
 * - The mirrors (start()): 4 illusioners on the shared
 *   mirror sheet (see IllusionerAbilitySupport: MAX_HEALTH
 *   base 1.0, ATTACK_DAMAGE base 0.0, allied both directions
 *   plus SummonerTracker, no target of their own), spawned
 *   on a circle of radius 2.5 around the caster at slot
 *   angles 45, 135, 225, and 315 degrees relative to the
 *   caster's facing. A 16-PORTAL burst and an
 *   ILLUSIONER_PREPARE_MIRROR open the hall.
 * - The formation (tick(), every 5 ticks): each living
 *   mirror is steered by its move control to its current
 *   formation point, the caster's live position plus its
 *   slot offset at radius 2.5, at speed 1.2, and its target
 *   is cleared in the same pass (a targetless mirror never
 *   casts or shoots on its own; the five weave as one
 *   pattern because every point is fixed to the caster's
 *   body, the design's "synchronized movement in code").
 *   Each mirror's position is recorded in the same pass (the
 *   pop burst needs a last known spot).
 * - The harmless arrows (tick(), every 20 ticks): each
 *   living mirror looses one pantomime arrow at the
 *   framework target when it is within 24 blocks: a real
 *   Arrow owned by the caster, fired from the mirror's eye
 *   position, base damage 0.0, speed 2.4, inaccuracy 1.5.
 *   It flies and sticks and deals nothing, which is the
 *   design's "harmless arrows" in code; one ARROW_SHOOT at
 *   the volley's loudest mirror covers the sound.
 * - The shuffle (tick(), every 60 ticks, first at tick 60):
 *   the rotation counter advances by one, so every mirror's
 *   formation point steps 90 degrees around the circle and
 *   the set visibly rotates through itself; 6 PORTAL motes
 *   swirl at each living mirror and at the caster, with an
 *   ILLUSIONER_MIRROR_MOVE. A marked original is lost in
 *   the shuffle because the mirrors are identical and the
 *   caster never moves for it.
 * - The pop (tick(), every 5 ticks, same pass): a tracked
 *   mirror that no longer resolves, or resolves dead, has
 *   been broken: at its last recorded position the ability
 *   throws 10 POOF and 8 PORTAL, plays an ILLUSIONER_HURT
 *   (volume 0.9, pitch 1.5), removes the caster's ally
 *   record for it, and marks it popped so the burst runs
 *   exactly once. A mirror that unloads with its chunk
 *   reads as gone the same way (the Mirage Split edge case,
 *   shared by every tracked summon in this mod).
 * - Duration: 400 ticks (20 seconds), hard finish.
 * - end(): each surviving mirror is discarded in 6 POOF,
 *   and the alliance records are removed on both sides for
 *   every tracked mirror. If the caster dies first, the
 *   framework termination path runs this same end(), so the
 *   mirrors never outlive the trick. SummonerTracker
 *   records are deliberately not unregistered, matching the
 *   other summons in this mod.
 *
 * WHAT IT LOOKS LIKE: a swirl of portal motes, and suddenly
 * five illusioners stand in a ring, bowing and weaving in
 * lockstep as the ring turns; every few seconds the whole
 * set whirls a quarter turn in purple sparks, and flat
 * harmless arrows patter from the false hands. A mirror
 * that takes a hit bursts into smoke and sparks.
 *
 * PERFORMANCE: start() does 4 spawns, once. tick() returns
 * immediately except every 5th tick, where it resolves at
 * most 4 UUIDs, writes at most 4 move-control wanted
 * positions, and reads positions: no entity scans anywhere.
 * Particle budget per activation: 16 at start, shuffles at
 * most 6 times 30 = 180, pops at most 4 times 18 = 72,
 * end() puffs at most 4 times 6 = 24, total at most 292
 * particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "mirrors" (list of compounds, one per
 * mirror: "id" the mirror's UUID, "slot" its circle slot
 * 0 to 3, "x", "y", "z" its last recorded position,
 * "popped" 1 once its pop burst ran), "rotation" (the
 * shuffle counter).
 */
public final class HallOfMirrorsAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int BABYSIT_INTERVAL = 5;
    private static final int SHUFFLE_INTERVAL = 60;
    private static final int ARROW_INTERVAL = 20;
    private static final int MIRROR_COUNT = 4;
    private static final double FORMATION_RADIUS = 2.5;

    @Override
    public String id() {
        return "hall_of_mirrors";
    }

    @Override
    public String displayName() {
        return "Hall of Mirrors";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 400.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();
        ListTag mirrors = new ListTag();
        ctx.state().put("mirrors", mirrors);
        ctx.state().putInt("rotation", 0);
        if (level == null) {
            return;
        }
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
        level.sendParticles(ParticleTypes.PORTAL, caster.getX(), caster.getY() + 1.2, caster.getZ(),
            16, 0.6, 0.8, 0.6, 0.15);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.ILLUSIONER_PREPARE_MIRROR, SoundSource.HOSTILE, 1.2f, 1.0f);
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
        ListTag mirrors = ctx.state().getListOrEmpty("mirrors");
        int rotation = ctx.state().getIntOr("rotation", 0);
        boolean shuffle = ticks % SHUFFLE_INTERVAL == 0;
        if (shuffle) {
            rotation++;
            ctx.state().putInt("rotation", rotation);
            level.sendParticles(ParticleTypes.PORTAL, caster.getX(), caster.getY() + 1.2, caster.getZ(),
                6, 0.5, 0.7, 0.5, 0.12);
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.ILLUSIONER_MIRROR_MOVE, SoundSource.HOSTILE, 1.0f, 1.0f);
        }
        LivingEntity target = ctx.target();
        boolean volley = ticks % ARROW_INTERVAL == 0 && target != null
            && caster.distanceToSqr(target) <= 576.0;
        boolean volleySoundDone = false;
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
                // The formation hold (see the class comment):
                // steer to the slot point fixed to the caster,
                // and keep the mirror targetless.
                int slot = entry.getIntOr("slot", 0);
                double angle = yawRad + Math.PI / 4.0 + (slot + rotation) * Math.PI / 2.0;
                mirror.getMoveControl().setWantedPosition(
                    caster.getX() + Math.cos(angle) * FORMATION_RADIUS, caster.getY(),
                    caster.getZ() + Math.sin(angle) * FORMATION_RADIUS, 1.2);
                mirror.setTarget(null);
                if (shuffle) {
                    level.sendParticles(ParticleTypes.PORTAL, mirror.getX(), mirror.getY() + 1.2, mirror.getZ(),
                        6, 0.4, 0.6, 0.4, 0.12);
                }
                if (volley) {
                    // The pantomime arrow (see the class
                    // comment): real flight, zero damage.
                    IllusionerAbilitySupport.fireArrow(ctx, mirror.getEyePosition(), target, 0.0, 2.4f, 1.5f);
                    if (!volleySoundDone) {
                        volleySoundDone = true;
                        level.playSound(null, mirror.getX(), mirror.getY(), mirror.getZ(),
                            SoundEvents.ARROW_SHOOT, SoundSource.HOSTILE, 0.7f, 1.1f);
                    }
                }
            } else {
                popMirror(ctx, level, entry);
            }
            mirrors.set(i, entry);
        }
        ctx.state().put("mirrors", mirrors);
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
        level.sendParticles(ParticleTypes.PORTAL, x, y + 1.2, z, 8, 0.4, 0.7, 0.4, 0.12);
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
