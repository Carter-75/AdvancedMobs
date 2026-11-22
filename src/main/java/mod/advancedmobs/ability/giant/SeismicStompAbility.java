package mod.advancedmobs.ability.giant;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Seismic Stomp (Giant ability 1 of 5, attack / terrain).
 *
 * WHAT IT DOES: the giant plants itself, heaves one foot up
 * over 9 ticks, and slams it down in a deep EXPLOSION_EMITTER
 * shockwave that throws BLOCK debris from the ground outward.
 * The top layer of earth in a wide ring around the foot cracks
 * into gravel and coarse dirt, and every enemy caught in the
 * ring is hurled straight up; a FALL_DAMAGE_MULTIPLIER curse
 * rides them down, so the landing they were launched into
 * deals double fall damage.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the giant on the ground with its target within 14 blocks
 *   (distance squared 196).
 * - Wind-up (ticks 1 to 9): the giant holds position
 *   (navigation stopped and horizontal velocity zeroed every
 *   tick; NoAi is never used). 2 BLOCK_CRUMBLE particles of
 *   the ground block shift at its feet every 3 ticks as the
 *   foot comes up.
 * - The stomp (tick 10), all at the giant's feet:
 *     Sound: GENERIC_EXPLODE at volume 1.5, pitch 0.6.
 *     Particles: 1 EXPLOSION_EMITTER, 24 BLOCK particles of
 *       the ground block underfoot spread over radius 3.0 at
 *       speed 0.25, 12 DUST_PILLAR of the same block.
 *     Terrain (only when mobGriefingAllowed()): the surface
 *       block of every column whose horizontal distance from
 *       the giant is between 3.0 and 6.0 blocks converts to
 *       GRAVEL or COARSE_DIRT, alternating by (x + z) parity.
 *       The surface is found by the shared scan (one above to
 *       two below the giant's feet height) and must pass the
 *       shared crackable check (solid, dry, no block entity,
 *       breakable, not already gravel or coarse dirt). Hard
 *       cap 32 conversions. PERMANENT, per the standing
 *       terrain rule: nothing restores them. With mobGriefing
 *       off, no block changes and everything else still runs.
 *     Launch: every enemy (GiantAbilitySupport rules) in a
 *       radius 6.0 box (1.5 below to 3.0 above the feet) gets
 *       a transient FALL_DAMAGE_MULTIPLIER modifier,
 *       add_multiplied_total +1.0 (fall damage doubled), id
 *       advancedmobs:seismic_stomp_fall, and its velocity is
 *       set to 0.35 horizontal away from the giant and 1.05
 *       straight up (about a 6-block rise). Victim UUIDs are
 *       recorded in the state tag.
 * - Linger (ticks 11 to 130): every 5 ticks, each recorded
 *   victim that is back on the ground has its multiplier
 *   removed and is pruned from the list, so the curse lasts
 *   exactly until the landing it was meant to multiply (plus
 *   up to 5 ticks of poll slack). Victims that died or
 *   unloaded are pruned silently; a transient modifier dies
 *   with its entity anyway.
 * - Hard finish at tick 130 even if a victim never lands;
 *   end() removes any multipliers still recorded.
 *
 * WHAT IT LOOKS LIKE: a slow, telegraphed foot raise with
 * grit shifting at the giant's feet, then one deep boom: the
 * emitter flash, a rolling ring of real ground debris, dust
 * pillars, and a permanent cracked ring of gravel and coarse
 * dirt (mobGriefing on) while enemies sail up and come down
 * hard.
 *
 * PERFORMANCE: wind-up ticks are one navigation stop plus a
 * 2-particle pulse every 3 ticks. The stomp is one bounded
 * entity query (radius 6.0) and, in mobGriefing mode, at most
 * 169 column scans of at most 4 block reads each with
 * conversions capped at 32. The linger is one UUID pass every
 * 5 ticks over at most the victims of one stomp. Particle
 * budget per activation: at most 6 wind-up, 37 at the stomp,
 * total at most 43 particles.
 *
 * State tag keys: "victims" (list of compounds, one per
 * launched victim: "id", the victim's UUID).
 */
public final class SeismicStompAbility implements MobAbility {

    private static final int STOMP_TICK = 10;
    private static final int MAX_TICKS = 130;
    private static final int MAX_CONVERSIONS = 32;
    private static final double RING_INNER = 3.0;
    private static final double RING_OUTER = 6.0;
    private static final Identifier FALL_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "seismic_stomp_fall");

    @Override
    public String id() {
        return "seismic_stomp";
    }

    @Override
    public String displayName() {
        return "Seismic Stomp";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().onGround()
            && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().put("victims", new ListTag());
        ctx.mob().getNavigation().stop();
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob mob = ctx.mob();
        int ticks = ctx.ticksActive();
        if (ticks < STOMP_TICK) {
            // Wind-up: hold position while the foot comes up.
            mob.getNavigation().stop();
            Vec3 velocity = mob.getDeltaMovement();
            if (velocity.x != 0.0 || velocity.z != 0.0) {
                mob.setDeltaMovement(0.0, velocity.y, 0.0);
            }
            ServerLevel level = ctx.serverLevel();
            if (level != null && ticks % 3 == 0) {
                BlockState ground = level.getBlockState(mob.blockPosition().below());
                if (!ground.isAir()) {
                    level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, ground),
                        mob.getX(), mob.getY() + 0.2, mob.getZ(), 2, 1.2, 0.1, 1.2, 0.03);
                }
            }
            return;
        }
        if (ticks == STOMP_TICK) {
            doStomp(ctx);
            return;
        }
        if (ticks % 5 == 0) {
            pruneLandedVictims(ctx);
        }
    }

    /** The stomp itself: boom, cracked ring, and the launch. Runs once, at tick 10. */
    private void doStomp(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        double cx = mob.getX(), cy = mob.getY(), cz = mob.getZ();
        BlockPos feet = mob.blockPosition();

        level.playSound(null, cx, cy, cz, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1.5f, 0.6f);
        BlockState ground = level.getBlockState(feet.below());
        BlockState particleState = ground.isAir() ? Blocks.DIRT.defaultBlockState() : ground;
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, cx, cy + 0.3, cz, 1, 0.0, 0.0, 0.0, 0.0);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, particleState),
            cx, cy + 0.3, cz, 24, 3.0, 0.4, 3.0, 0.25);
        level.sendParticles(new BlockParticleOption(ParticleTypes.DUST_PILLAR, particleState),
            cx, cy + 0.3, cz, 12, 2.5, 0.5, 2.5, 0.08);

        if (ctx.mobGriefingAllowed()) {
            crackRing(ctx, level, feet);
        }

        List<LivingEntity> victims = GiantAbilitySupport.enemiesInBox(ctx, cx, cy, cz, RING_OUTER, 1.5, 3.0);
        ListTag victimList = new ListTag();
        for (LivingEntity victim : victims) {
            GiantAbilitySupport.addModifier(victim, Attributes.FALL_DAMAGE_MULTIPLIER,
                new AttributeModifier(FALL_ID, 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            Vec3 away = new Vec3(victim.getX() - cx, 0.0, victim.getZ() - cz);
            away = away.lengthSqr() < 1.0e-4 ? new Vec3(1.0, 0.0, 0.0) : away.normalize();
            victim.setDeltaMovement(away.x * 0.35, 1.05, away.z * 0.35);
            victim.hurtMarked = true;
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, victim.getUUID());
            victimList.add(entry);
        }
        ctx.state().put("victims", victimList);
    }

    /** Cracks the surface layer of the ring into gravel and coarse dirt. mobGriefing mode only. */
    private void crackRing(AbilityContext ctx, ServerLevel level, BlockPos feet) {
        int conversions = 0;
        int radius = (int) Math.ceil(RING_OUTER);
        for (int dx = -radius; dx <= radius && conversions < MAX_CONVERSIONS; dx++) {
            for (int dz = -radius; dz <= radius && conversions < MAX_CONVERSIONS; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist < RING_INNER || dist > RING_OUTER) {
                    continue;
                }
                int x = feet.getX() + dx;
                int z = feet.getZ() + dz;
                BlockPos groundPos = GiantAbilitySupport.findGround(level, x, z, feet.getY());
                if (groundPos == null) {
                    continue;
                }
                BlockState cracked = ((x + z) & 1) == 0
                    ? Blocks.GRAVEL.defaultBlockState()
                    : Blocks.COARSE_DIRT.defaultBlockState();
                if (level.setBlock(groundPos, cracked, 3)) {
                    conversions++;
                }
            }
        }
    }

    /** Removes the fall multiplier from every recorded victim that has landed, and prunes the list. */
    private void pruneLandedVictims(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag victims = ctx.state().getListOrEmpty("victims");
        if (victims.isEmpty()) {
            return;
        }
        ListTag remaining = new ListTag();
        for (int i = 0; i < victims.size(); i++) {
            CompoundTag entry = victims.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id == null || !(level.getEntity(id) instanceof LivingEntity victim) || !victim.isAlive()) {
                continue; // Gone: the transient modifier went with it.
            }
            if (victim.onGround()) {
                GiantAbilitySupport.removeModifier(victim, Attributes.FALL_DAMAGE_MULTIPLIER, FALL_ID);
                continue;
            }
            remaining.add(entry);
        }
        ctx.state().put("victims", remaining);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= MAX_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Remove any fall multipliers still out: victims that
        // never landed inside the linger window.
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag victims = ctx.state().getListOrEmpty("victims");
        for (int i = 0; i < victims.size(); i++) {
            CompoundTag entry = victims.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id != null && level.getEntity(id) instanceof LivingEntity victim) {
                GiantAbilitySupport.removeModifier(victim, Attributes.FALL_DAMAGE_MULTIPLIER, FALL_ID);
            }
        }
    }
}
