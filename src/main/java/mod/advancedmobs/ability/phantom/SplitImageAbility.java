package mod.advancedmobs.ability.phantom;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Phantom;

import java.util.UUID;

/**
 * Split Image (Phantom ability 4 of 5, summon).
 *
 * WHAT IT DOES: the phantom screeches and tears into
 * three: itself at full size, and two smaller, faster
 * copies at half scale that peel off to either side. The
 * copies bite for light damage but constantly harass and
 * body block, diving through the fight on their own
 * vanilla wings, and a copy that is killed just dissolves
 * into portal sparks instead of dying like a real phantom.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the phantom's current target within 20 blocks (distance
 *   squared 400).
 * - The copies (start()): 2 phantoms, spawned 1.5 blocks
 *   to either side of the caster along the horizontal axis
 *   perpendicular to its facing, at the caster's own
 *   height, created with EntitySpawnReason.MOB_SUMMONED
 *   and finalizeSpawn like every other summon in this mod.
 *   Each copy's sheet is then set by BASE attribute
 *   values, per the design: SCALE base 0.5 (half scale,
 *   model and hitbox together), MAX_HEALTH base 10.0 with
 *   health set to 10.0 (half the caster's 20), and
 *   ATTACK_DAMAGE base 3.0 (light damage, half a vanilla
 *   phantom's 6). On top of the sheet each copy carries a
 *   transient FLYING_SPEED modifier of +0.5
 *   ADD_MULTIPLIED_BASE (half again as fast, the design's
 *   "faster copies" and its MOVEMENT_SPEED line carried on
 *   the flying attribute a phantom actually steers by)
 *   under a fixed modifier id: transient modifiers are not
 *   persisted, so a copy that somehow outlives the ability
 *   keeps only its base sheet, and copies alive at end()
 *   are discarded anyway. Team assignment, per copy, both
 *   directions plus the registry: the caster's data gains
 *   the copy as an ally, the copy's data gains the caster,
 *   and the pair is registered in SummonerTracker (which
 *   also tags the copy umw_summoned for the natural spawn
 *   blocker). Each copy is ordered onto the caster's
 *   current target at spawn; their swooping is their own
 *   vanilla phantom AI from there.
 * - The tear (start()): 16 REVERSE_PORTAL particles burst
 *   at the caster and a PHANTOM_SWOOP plays (volume 1.2,
 *   pitch 1.1).
 * - Babysitting (tick(), every 5 ticks): each tracked
 *   copy's position is recorded into the state tag (the
 *   dissolve burst needs a last known spot), and a copy
 *   whose target is not the caster's current target is
 *   re-ordered onto it, which keeps the pair harassing the
 *   phantom's own fight instead of wandering off after
 *   bystanders.
 * - The dissolve (tick(), every 5 ticks, same pass): a
 *   tracked copy that no longer resolves, or resolves
 *   dead, has dissolved: at its last recorded position the
 *   ability throws 10 REVERSE_PORTAL and 4 PORTAL
 *   particles, plays a PHANTOM_DEATH (volume 0.7, pitch
 *   1.5, a copy's thinner death cry), removes the caster's
 *   ally record for it, and marks it dissolved so the
 *   burst runs exactly once. A copy that unloads with its
 *   chunk instead of dying also reads as gone to this pass
 *   and dissolves the same way; if it later reloads it is
 *   an ordinary half-scale allied phantom with no
 *   babysitter, an accepted edge case shared with every
 *   tracked summon in this mod.
 * - Duration: 300 ticks (15 seconds), hard finish.
 * - end(): each surviving copy is discarded in 8
 *   REVERSE_PORTAL particles at its position and the
 *   alliance records are removed on both sides for every
 *   tracked copy. If the caster dies first, the framework
 *   termination path runs this same end(), so the copies
 *   never outlive their summoner. SummonerTracker records
 *   are deliberately not unregistered, matching the other
 *   summons in this mod: that registry cleans up on the
 *   copy's own removal.
 *
 * WHAT IT LOOKS LIKE: a screech and a burst of reversed
 * portal sparks, and suddenly the sky holds three
 * phantoms: one full-size shape and two half-size ones
 * streaking faster, all three diving at the same victim;
 * swat one of the small ones down and it comes apart into
 * portal sparks instead of a corpse.
 *
 * PERFORMANCE: start() does 2 spawns, once. tick() returns
 * immediately except every 5th tick, where it resolves at
 * most 2 UUIDs and writes at most 2 target re-orders: no
 * entity scans anywhere. Particle budget per activation:
 * 16 at start, dissolves at most 2 times 14 = 28, end()
 * puffs at most 2 times 8 = 16, total at most 60
 * particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "copies" (list of compounds, one per
 * copy: "id" the copy's UUID, "x", "y", "z" its last
 * recorded position, "gone" 1 once its dissolve burst ran).
 */
public final class SplitImageAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int BABYSIT_INTERVAL = 5;
    private static final double SIDE_OFFSET = 1.5;
    private static final Identifier SWIFT_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "split_image_swift");

    @Override
    public String id() {
        return "split_image";
    }

    @Override
    public String displayName() {
        return "Split Image";
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
        ListTag copies = new ListTag();
        ctx.state().put("copies", copies);
        if (level == null) {
            return;
        }

        level.sendParticles(ParticleTypes.REVERSE_PORTAL, caster.getX(), caster.getY() + 0.4, caster.getZ(),
            16, 0.4, 0.4, 0.4, 0.1);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.PHANTOM_SWOOP,
            SoundSource.HOSTILE, 1.2f, 1.1f);

        // The split axis: perpendicular to the caster's facing.
        double yawRad = Math.toRadians(caster.getYRot());
        double perpX = Math.cos(yawRad);
        double perpZ = Math.sin(yawRad);
        LivingEntity target = ctx.target();
        int[] sides = {-1, 1};
        for (int side : sides) {
            Phantom copy = EntityTypes.PHANTOM.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (copy == null) {
                continue;
            }
            copy.snapTo(caster.getX() + perpX * SIDE_OFFSET * side, caster.getY(),
                caster.getZ() + perpZ * SIDE_OFFSET * side, caster.getYRot(), 0.0f);
            copy.finalizeSpawn(level, level.getCurrentDifficultyAt(copy.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
            // The copy's sheet (see the class comment): base
            // values, set after finalizeSpawn so nothing
            // re-rolls them.
            AttributeInstance scale = copy.getAttribute(Attributes.SCALE);
            if (scale != null) {
                scale.setBaseValue(0.5);
            }
            AttributeInstance health = copy.getAttribute(Attributes.MAX_HEALTH);
            if (health != null) {
                health.setBaseValue(10.0);
            }
            copy.setHealth(10.0f);
            AttributeInstance damage = copy.getAttribute(Attributes.ATTACK_DAMAGE);
            if (damage != null) {
                damage.setBaseValue(3.0);
            }
            PhantomAbilitySupport.addModifier(copy, Attributes.FLYING_SPEED,
                new AttributeModifier(SWIFT_ID, 0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
            if (!level.addFreshEntity(copy)) {
                continue;
            }
            ctx.data().addAlly(copy.getUUID());
            MobWarData.get(copy).addAlly(caster.getUUID());
            SummonerTracker.registerSummoned(copy.getUUID(), caster.getUUID());
            if (target != null) {
                copy.setTarget(target);
            }

            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, copy.getUUID());
            entry.putDouble("x", copy.getX());
            entry.putDouble("y", copy.getY());
            entry.putDouble("z", copy.getZ());
            entry.putInt("gone", 0);
            copies.add(entry);
        }
        ctx.state().put("copies", copies);
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
        LivingEntity target = ctx.target();
        ListTag copies = ctx.state().getListOrEmpty("copies");
        for (int i = 0; i < copies.size(); i++) {
            CompoundTag entry = copies.getCompoundOrEmpty(i);
            if (entry.getIntOr("gone", 0) == 1) {
                continue;
            }
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            Entity found = id != null ? level.getEntity(id) : null;
            if (found instanceof Phantom copy && copy.isAlive()) {
                entry.putDouble("x", copy.getX());
                entry.putDouble("y", copy.getY());
                entry.putDouble("z", copy.getZ());
                if (target != null && copy.getTarget() != target) {
                    copy.setTarget(target);
                }
            } else {
                dissolve(ctx, level, entry);
            }
            copies.set(i, entry);
        }
        ctx.state().put("copies", copies);
    }

    /** The dissolve burst for one destroyed copy (see the class comment). Runs once per copy. */
    private static void dissolve(AbilityContext ctx, ServerLevel level, CompoundTag entry) {
        entry.putInt("gone", 1);
        UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
        if (id != null) {
            ctx.data().removeAlly(id);
        }
        double x = entry.getDoubleOr("x", ctx.mob().getX());
        double y = entry.getDoubleOr("y", ctx.mob().getY());
        double z = entry.getDoubleOr("z", ctx.mob().getZ());
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, x, y + 0.3, z, 10, 0.3, 0.3, 0.3, 0.08);
        level.sendParticles(ParticleTypes.PORTAL, x, y + 0.3, z, 4, 0.3, 0.3, 0.3, 0.08);
        level.playSound(null, x, y, z, SoundEvents.PHANTOM_DEATH, SoundSource.HOSTILE, 0.7f, 1.5f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag copies = ctx.state().getListOrEmpty("copies");
        for (int i = 0; i < copies.size(); i++) {
            CompoundTag entry = copies.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id == null) {
                continue;
            }
            ctx.data().removeAlly(id);
            Entity found = level.getEntity(id);
            if (found instanceof Phantom copy) {
                MobWarData.get(copy).removeAlly(caster.getUUID());
                if (copy.isAlive()) {
                    level.sendParticles(ParticleTypes.REVERSE_PORTAL, copy.getX(), copy.getY() + 0.3, copy.getZ(),
                        8, 0.3, 0.3, 0.3, 0.08);
                    copy.discard();
                }
            }
        }
    }
}
