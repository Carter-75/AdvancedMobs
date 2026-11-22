package mod.advancedmobs.ability.ghast;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ItemParticleOption;
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
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.item.Items;

import java.util.UUID;

/**
 * Mirror Ghasts (Ghast ability 3 of 5, summon decoy).
 *
 * WHAT IT DOES: the sky splits into three ghasts. Two phantom
 * ghasts appear beside the real one, identical at a glance,
 * while the real ghast vanishes for five seconds. The phantoms
 * are real ghasts with a single point of health: any solid hit
 * pops one in a spray of poof and ghast tears. They drift and
 * wail on their own vanilla flight AI exactly like the
 * original, but they never shoot: their babysitter keeps their
 * combat target cleared, so their fireball goal never engages.
 * Enemies waste their biggest hits on the fakes while the real
 * ghast repositions unseen.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists within 20 blocks
 *   (distance squared 400).
 * - The phantoms (start()): 2 ghasts, spawned 4.0 blocks to
 *   either side of the caster (east and west at the caster's
 *   own height; if a side spot collides, the caster's own
 *   position is the fallback), created with
 *   EntitySpawnReason.MOB_SUMMONED and finalizeSpawn like
 *   every other summon in this mod. Each phantom is then set
 *   to the design's sheet by BASE attribute values (not
 *   modifiers, so they read as the phantom's real stats):
 *   MAX_HEALTH base 1.0 with health set to 1.0, and
 *   ATTACK_DAMAGE base 0.0. Team assignment, per phantom,
 *   both directions plus the registry: the caster's data
 *   gains the phantom as an ally, the phantom's data gains
 *   the caster, and the pair is registered in
 *   SummonerTracker (which is also what tags the phantom
 *   umw_summoned for the natural spawn blocker).
 * - The shimmer: each phantom carries GLOWING, refreshed
 *   every 20 ticks for 30 ticks so it never lapses. The
 *   design asks for a shimmer "only visible up close"; the
 *   vanilla GLOWING outline is the closest server-side
 *   effect 26.2 offers and it does show at range, an accepted
 *   approximation stated here so it is not mistaken for a
 *   bug. At a glance in a fight the three ghasts still read
 *   as identical pale shapes.
 * - The vanishing: the caster gains INVISIBILITY for 100
 *   ticks (5 seconds) at start, particles and icon hidden.
 *   The framework's combat gate already ran, so the caster's
 *   own attack goal pausing while it has no visible presence
 *   is vanilla behavior, not steered here.
 * - Babysitting (tick(), every 5 ticks): for each tracked
 *   phantom still alive, its position is recorded into the
 *   state tag (the pop burst needs a last known spot) and its
 *   combat target is cleared if it has one, which is the
 *   whole of the "no fireballs of their own" rule: a ghast
 *   with no target never enters its shoot goal. Nothing else
 *   is steered; drifting and wailing are vanilla.
 * - The pop (tick(), every 5 ticks, same pass): a tracked
 *   phantom that no longer resolves, or resolves dead, has
 *   popped: at its last recorded position the ability throws
 *   12 POOF and 8 ITEM particles of ghast tear, plays a
 *   GHAST_HURT (volume 1.0, pitch 1.4), removes the caster's
 *   ally record for it, and marks it popped so the burst runs
 *   exactly once. A phantom that unloads with its chunk
 *   instead of dying also reads as gone to this pass and pops
 *   the same way; if it later reloads it is an ordinary
 *   1-health allied ghast with no babysitter, an accepted
 *   edge case shared with every tracked summon in this mod.
 * - Duration: 240 ticks (12 seconds), hard finish.
 * - end(): each surviving phantom is discarded in 6 POOF at
 *   its position, the alliance records are removed on both
 *   sides for every tracked phantom, and the caster's
 *   INVISIBILITY is removed if it is somehow still running
 *   (it normally expires on its own at tick 100). If the
 *   caster dies first, the framework termination path runs
 *   this same end(), so the phantoms never outlive their
 *   summoner. SummonerTracker records are deliberately not
 *   unregistered, matching the other summons in this mod:
 *   that registry cleans up on the phantom's own removal.
 *
 * WHAT IT LOOKS LIKE: three identical ghasts hanging in the
 * sky, two of them faintly outlined, the third simply not
 * there for five seconds; a phantom that takes a hit bursts
 * into poof and a spray of falling ghast tears.
 *
 * PERFORMANCE: start() does 2 bounded collision checks and 2
 * spawns, once. tick() returns immediately except every 5th
 * tick, where it resolves at most 2 UUIDs, reads positions,
 * and clears targets; the GLOWING refresh runs every 20
 * ticks on the same pass. No entity scans anywhere. Particle
 * budget per activation: pops at most 2 times 20 = 40, end()
 * puffs at most 2 times 6 = 12, total at most 52 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does not
 * apply.
 *
 * State tag keys: "phantoms" (list of compounds, one per
 * phantom: "id" the phantom's UUID, "x", "y", "z" its last
 * recorded position, "popped" 1 once its pop burst ran).
 */
public final class MirrorGhastsAbility implements MobAbility {

    private static final int DURATION_TICKS = 240;
    private static final int BABYSIT_INTERVAL = 5;
    private static final int INVISIBILITY_TICKS = 100;
    private static final ItemParticleOption TEAR_PARTICLE =
        new ItemParticleOption(ParticleTypes.ITEM, Items.GHAST_TEAR);

    @Override
    public String id() {
        return "mirror_ghasts";
    }

    @Override
    public String displayName() {
        return "Mirror Ghasts";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return ctx.target() != null && ctx.mob().distanceToSqr(ctx.target()) <= 400.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();
        ListTag phantoms = new ListTag();
        ctx.state().put("phantoms", phantoms);
        if (level == null) {
            return;
        }

        caster.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, INVISIBILITY_TICKS, 0, false, false, false));

        double[][] offsets = {{4.0, 0.0}, {-4.0, 0.0}};
        for (double[] offset : offsets) {
            Ghast phantom = EntityTypes.GHAST.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (phantom == null) {
                continue;
            }
            double spawnX = caster.getX() + offset[0];
            double spawnZ = caster.getZ() + offset[1];
            double halfWidth = phantom.getBbWidth() / 2.0;
            net.minecraft.world.phys.AABB spawnBox = new net.minecraft.world.phys.AABB(
                spawnX - halfWidth, caster.getY(), spawnZ - halfWidth,
                spawnX + halfWidth, caster.getY() + phantom.getBbHeight(), spawnZ + halfWidth);
            if (!level.noCollision(phantom, spawnBox)) {
                spawnX = caster.getX();
                spawnZ = caster.getZ();
            }
            phantom.snapTo(spawnX, caster.getY(), spawnZ, ctx.random().nextFloat() * 360.0f, 0.0f);
            phantom.finalizeSpawn(level, level.getCurrentDifficultyAt(phantom.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
            // The phantom's sheet (see the class comment): base
            // values, set after finalizeSpawn so nothing
            // re-rolls them.
            AttributeInstance health = phantom.getAttribute(Attributes.MAX_HEALTH);
            if (health != null) {
                health.setBaseValue(1.0);
            }
            phantom.setHealth(1.0f);
            AttributeInstance damage = phantom.getAttribute(Attributes.ATTACK_DAMAGE);
            if (damage != null) {
                damage.setBaseValue(0.0);
            }
            if (!level.addFreshEntity(phantom)) {
                continue;
            }
            ctx.data().addAlly(phantom.getUUID());
            MobWarData.get(phantom).addAlly(caster.getUUID());
            SummonerTracker.registerSummoned(phantom.getUUID(), caster.getUUID());
            phantom.addEffect(new MobEffectInstance(MobEffects.GLOWING, 30, 0, false, false, false));

            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, phantom.getUUID());
            entry.putDouble("x", phantom.getX());
            entry.putDouble("y", phantom.getY());
            entry.putDouble("z", phantom.getZ());
            entry.putInt("popped", 0);
            phantoms.add(entry);
        }
        ctx.state().put("phantoms", phantoms);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.GHAST_AMBIENT,
            SoundSource.HOSTILE, 1.2f, 1.1f);
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
        boolean refreshGlow = ctx.ticksActive() % 20 == 0;
        ListTag phantoms = ctx.state().getListOrEmpty("phantoms");
        for (int i = 0; i < phantoms.size(); i++) {
            CompoundTag entry = phantoms.getCompoundOrEmpty(i);
            if (entry.getIntOr("popped", 0) == 1) {
                continue;
            }
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            Entity found = id != null ? level.getEntity(id) : null;
            if (found instanceof Ghast phantom && phantom.isAlive()) {
                entry.putDouble("x", phantom.getX());
                entry.putDouble("y", phantom.getY());
                entry.putDouble("z", phantom.getZ());
                if (phantom.getTarget() != null) {
                    phantom.setTarget(null);
                }
                if (refreshGlow) {
                    phantom.addEffect(new MobEffectInstance(MobEffects.GLOWING, 30, 0, false, false, false));
                }
            } else {
                popPhantom(ctx, level, entry);
            }
            phantoms.set(i, entry);
        }
        ctx.state().put("phantoms", phantoms);
    }

    /** The pop burst for one destroyed phantom (see the class comment). Runs once per phantom. */
    private static void popPhantom(AbilityContext ctx, ServerLevel level, CompoundTag entry) {
        entry.putInt("popped", 1);
        UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
        if (id != null) {
            ctx.data().removeAlly(id);
        }
        double x = entry.getDoubleOr("x", ctx.mob().getX());
        double y = entry.getDoubleOr("y", ctx.mob().getY());
        double z = entry.getDoubleOr("z", ctx.mob().getZ());
        level.sendParticles(ParticleTypes.POOF, x, y + 1.0, z, 12, 1.2, 1.2, 1.2, 0.05);
        level.sendParticles(TEAR_PARTICLE, x, y + 1.0, z, 8, 1.0, 1.0, 1.0, 0.1);
        level.playSound(null, x, y, z, SoundEvents.GHAST_HURT, SoundSource.HOSTILE, 1.0f, 1.4f);
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
        ListTag phantoms = ctx.state().getListOrEmpty("phantoms");
        for (int i = 0; i < phantoms.size(); i++) {
            CompoundTag entry = phantoms.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id == null) {
                continue;
            }
            ctx.data().removeAlly(id);
            Entity found = level.getEntity(id);
            if (found instanceof Ghast phantom) {
                MobWarData.get(phantom).removeAlly(caster.getUUID());
                if (phantom.isAlive()) {
                    level.sendParticles(ParticleTypes.POOF, phantom.getX(), phantom.getY() + 1.0, phantom.getZ(),
                        6, 1.0, 1.0, 1.0, 0.05);
                    phantom.discard();
                }
            }
        }
    }
}
