package mod.advancedmobs.ability.vex;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
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
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Vex;

import java.util.UUID;

/**
 * Mirage Split (Vex ability 1 of 5, summon, defense).
 *
 * WHAT IT DOES: the vex flickers and there are suddenly three
 * of them. Two summoned mirror vexes appear at its shoulders,
 * each a real vex with a single point of health and a sword
 * that cannot hurt anyone, and all three weave the same
 * flight pattern so the eye cannot hold the real one. The
 * real vex slips invisible for the first two seconds of the
 * split. A mirror that takes any solid hit pops in a puff of
 * smoke; every mirror destroyed narrows the guessing game, so
 * the split buys seconds, not safety.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the vex's current target within 16 blocks (distance
 *   squared 256).
 * - The mirrors (start()): 2 vexes, spawned 1.3 blocks to
 *   either side of the caster along the horizontal axis
 *   perpendicular to its facing, at the caster's own height,
 *   created with EntitySpawnReason.MOB_SUMMONED and
 *   finalizeSpawn like every other summon in this mod (which
 *   also arms each with the vanilla iron sword: the harmless
 *   sword of the design). Each mirror's sheet is then set by
 *   BASE attribute values, per the design's summon sheet:
 *   MAX_HEALTH base 1.0 with health set to 1.0, and
 *   ATTACK_DAMAGE base 0.0 (a mirror's sword connects, wiggles
 *   the victim's screen, and deals nothing). No limited life
 *   is set: the mirrors live exactly as long as the ability
 *   does and are discarded in end(). Team assignment, per
 *   mirror, both directions plus the registry: the caster's
 *   data gains the mirror as an ally, the mirror's data gains
 *   the caster, and the pair is registered in SummonerTracker
 *   (which also tags the mirror umw_summoned for the natural
 *   spawn blocker). Each mirror is ordered onto the caster's
 *   current target at spawn.
 * - The vanishing: the caster gains INVISIBILITY for 40
 *   ticks (2 seconds, "the first moment of the split") at
 *   start, particles and icon hidden.
 * - The shared weave (tick(), every 5 ticks): each living
 *   mirror is steered by its move control to the caster's
 *   current position plus its own shoulder offset rotated by
 *   the caster's current yaw, with a bob of plus or minus 0.4
 *   blocks (sin of ticks times 0.3 plus a per-mirror phase),
 *   at speed 1.2. Because both mirrors chase points rigidly
 *   fixed to the caster's body, the three fly one weaving
 *   pattern, which is the design's "flight pattern mirroring
 *   in code". A mirror whose target is not the caster's
 *   target is re-ordered onto it in the same pass. Each
 *   mirror's position is recorded into the state tag in the
 *   same pass (the pop burst needs a last known spot).
 * - The pop (tick(), every 5 ticks, same pass): a tracked
 *   mirror that no longer resolves, or resolves dead, has
 *   popped: at its last recorded position the ability throws
 *   10 SMOKE and 4 POOF, plays a VEX_HURT (volume 0.8, pitch
 *   1.5), removes the caster's ally record for it, and marks
 *   it popped so the burst runs exactly once. A mirror that
 *   unloads with its chunk instead of dying also reads as
 *   gone to this pass and pops the same way; if it later
 *   reloads it is an ordinary 1-health allied vex with no
 *   babysitter, an accepted edge case shared with every
 *   tracked summon in this mod.
 * - Duration: 240 ticks (12 seconds), hard finish.
 * - end(): each surviving mirror is discarded in 6 SMOKE at
 *   its position, the alliance records are removed on both
 *   sides for every tracked mirror, and the caster's
 *   INVISIBILITY is removed if it is somehow still running
 *   (it normally expires on its own at tick 40). If the
 *   caster dies first, the framework termination path runs
 *   this same end(), so the mirrors never outlive their
 *   summoner. SummonerTracker records are deliberately not
 *   unregistered, matching the other summons in this mod:
 *   that registry cleans up on the mirror's own removal.
 *
 * WHAT IT LOOKS LIKE: an 8-SMOKE flicker burst, then three
 * identical sword vexes weaving in lockstep around the fight,
 * one of them simply not there for the first two seconds; a
 * mirror that takes a hit bursts into smoke.
 *
 * PERFORMANCE: start() does 2 spawns, once. tick() returns
 * immediately except every 5th tick, where it resolves at
 * most 2 UUIDs, reads positions, writes at most 2 move
 * control wanted positions, and re-orders targets: no entity
 * scans anywhere. Particle budget per activation: 8 at start,
 * pops at most 2 times 14 = 28, end() puffs at most 2 times
 * 6 = 12, total at most 48 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "mirrors" (list of compounds, one per
 * mirror: "id" the mirror's UUID, "side" -1 or 1, "x", "y",
 * "z" its last recorded position, "popped" 1 once its pop
 * burst ran).
 */
public final class MirageSplitAbility implements MobAbility {

    private static final int DURATION_TICKS = 240;
    private static final int BABYSIT_INTERVAL = 5;
    private static final int INVISIBILITY_TICKS = 40;
    private static final double SHOULDER_OFFSET = 1.3;

    @Override
    public String id() {
        return "mirage_split";
    }

    @Override
    public String displayName() {
        return "Mirage Split";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
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

        caster.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, INVISIBILITY_TICKS, 0, false, false, false));
        level.sendParticles(ParticleTypes.SMOKE, caster.getX(), caster.getY() + 0.5, caster.getZ(),
            8, 0.3, 0.4, 0.3, 0.05);

        // The shoulder axis: perpendicular to the caster's facing.
        double yawRad = Math.toRadians(caster.getYRot());
        double perpX = Math.cos(yawRad);
        double perpZ = Math.sin(yawRad);
        LivingEntity target = ctx.target();
        int[] sides = {-1, 1};
        for (int side : sides) {
            Vex mirror = EntityTypes.VEX.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (mirror == null) {
                continue;
            }
            mirror.snapTo(caster.getX() + perpX * SHOULDER_OFFSET * side, caster.getY(),
                caster.getZ() + perpZ * SHOULDER_OFFSET * side, caster.getYRot(), 0.0f);
            mirror.finalizeSpawn(level, level.getCurrentDifficultyAt(mirror.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
            // The mirror's sheet (see the class comment): base
            // values, set after finalizeSpawn so nothing
            // re-rolls them.
            AttributeInstance health = mirror.getAttribute(Attributes.MAX_HEALTH);
            if (health != null) {
                health.setBaseValue(1.0);
            }
            mirror.setHealth(1.0f);
            AttributeInstance damage = mirror.getAttribute(Attributes.ATTACK_DAMAGE);
            if (damage != null) {
                damage.setBaseValue(0.0);
            }
            if (!level.addFreshEntity(mirror)) {
                continue;
            }
            ctx.data().addAlly(mirror.getUUID());
            MobWarData.get(mirror).addAlly(caster.getUUID());
            SummonerTracker.registerSummoned(mirror.getUUID(), caster.getUUID());
            if (target != null) {
                mirror.setTarget(target);
            }

            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, mirror.getUUID());
            entry.putInt("side", side);
            entry.putDouble("x", mirror.getX());
            entry.putDouble("y", mirror.getY());
            entry.putDouble("z", mirror.getZ());
            entry.putInt("popped", 0);
            mirrors.add(entry);
        }
        ctx.state().put("mirrors", mirrors);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.VEX_AMBIENT,
            SoundSource.HOSTILE, 1.0f, 1.3f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % BABYSIT_INTERVAL != 0) {
            return; // Cheap early-out: babysitting runs every 5 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        LivingEntity target = ctx.target();
        double yawRad = Math.toRadians(caster.getYRot());
        double perpX = Math.cos(yawRad);
        double perpZ = Math.sin(yawRad);
        ListTag mirrors = ctx.state().getListOrEmpty("mirrors");
        for (int i = 0; i < mirrors.size(); i++) {
            CompoundTag entry = mirrors.getCompoundOrEmpty(i);
            if (entry.getIntOr("popped", 0) == 1) {
                continue;
            }
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            Entity found = id != null ? level.getEntity(id) : null;
            if (found instanceof Vex mirror && mirror.isAlive()) {
                entry.putDouble("x", mirror.getX());
                entry.putDouble("y", mirror.getY());
                entry.putDouble("z", mirror.getZ());
                int side = entry.getIntOr("side", 1);
                // The shared weave: a wanted point rigidly fixed
                // to the caster's body (see the class comment).
                double bob = Math.sin(ctx.ticksActive() * 0.3 + side * 2.1) * 0.4;
                mirror.getMoveControl().setWantedPosition(
                    caster.getX() + perpX * SHOULDER_OFFSET * side,
                    caster.getY() + bob,
                    caster.getZ() + perpZ * SHOULDER_OFFSET * side, 1.2);
                if (target != null && mirror.getTarget() != target) {
                    mirror.setTarget(target);
                }
            } else {
                popMirror(ctx, level, entry);
            }
            mirrors.set(i, entry);
        }
        ctx.state().put("mirrors", mirrors);
    }

    /** The pop burst for one destroyed mirror (see the class comment). Runs once per mirror. */
    private static void popMirror(AbilityContext ctx, ServerLevel level, CompoundTag entry) {
        entry.putInt("popped", 1);
        UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
        if (id != null) {
            ctx.data().removeAlly(id);
        }
        double x = entry.getDoubleOr("x", ctx.mob().getX());
        double y = entry.getDoubleOr("y", ctx.mob().getY());
        double z = entry.getDoubleOr("z", ctx.mob().getZ());
        level.sendParticles(ParticleTypes.SMOKE, x, y + 0.4, z, 10, 0.25, 0.3, 0.25, 0.05);
        level.sendParticles(ParticleTypes.POOF, x, y + 0.4, z, 4, 0.25, 0.3, 0.25, 0.05);
        level.playSound(null, x, y, z, SoundEvents.VEX_HURT, SoundSource.HOSTILE, 0.8f, 1.5f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.removeEffect(MobEffects.INVISIBILITY);
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag mirrors = ctx.state().getListOrEmpty("mirrors");
        for (int i = 0; i < mirrors.size(); i++) {
            CompoundTag entry = mirrors.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id == null) {
                continue;
            }
            ctx.data().removeAlly(id);
            Entity found = level.getEntity(id);
            if (found instanceof Vex mirror) {
                MobWarData.get(mirror).removeAlly(caster.getUUID());
                if (mirror.isAlive()) {
                    level.sendParticles(ParticleTypes.SMOKE, mirror.getX(), mirror.getY() + 0.4, mirror.getZ(),
                        6, 0.25, 0.3, 0.25, 0.05);
                    mirror.discard();
                }
            }
        }
    }
}
