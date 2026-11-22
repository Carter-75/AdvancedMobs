package mod.advancedmobs.ability.zombie;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Seismic Slam (Zombie ability 3 of 5, attack).
 *
 * WHAT IT DOES: the zombie launches itself skyward trailing dust, then
 * crashes down in a ring-shaped shockwave that damages every enemy around
 * the landing point and hurls them outward. Victims' knockback resistance
 * is stripped for a few seconds, so the launch (and any follow-up hit in
 * that window) sends them fully flying.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists, the zombie is on the ground, and
 *   the target is between 2 and 10 blocks away (distance squared 4 to 100).
 * - Launch (in start()): a direct velocity impulse toward the target:
 *   horizontal 0.55 in the target's direction, vertical 1.05 (roughly a
 *   5-block rise under normal gravity). A transient SAFE_FALL_DISTANCE
 *   modifier (add_value +100, id advancedmobs:seismic_slam_fall, removed
 *   in end()) plus a per-tick fallDistance reset while airborne mean the
 *   zombie never hurts itself on the way down.
 * - Landing: the first tick the zombie is on the ground again, at or after
 *   tick 3, the slam fires at the zombie's position:
 *     Damage: every enemy (ZombieAbilitySupport rules) within horizontal
 *       radius 3.5 (1.5 below to 2.5 above the landing point) takes 6.0
 *       points of fall-type damage (DamageSources.fall(), so it reads as
 *       impact damage and bypasses armor like a fall does).
 *     Knockback strip: each victim first gets a transient
 *       KNOCKBACK_RESISTANCE modifier, add_value -1.0, id
 *       advancedmobs:seismic_slam_kb_strip. Their UUIDs are recorded in
 *       the state tag and end() removes the modifier 10 ticks after the
 *       slam, so the strip lasts about half a second past the launch.
 *     Launch: each victim's velocity is set directly to horizontal 1.25
 *       away from the slam center and vertical 0.5, the full
 *       stripped-resistance value (the strip above is what licenses the
 *       full value, and it stays on for follow-up hits in the window).
 * - After the slam the ability lingers exactly 10 more ticks (so end()
 *   can remove the knockback strips), then finishes. Hard safety: the
 *   ability always finishes by tick 70 even if the zombie never lands
 *   (knocked into the void, teleported, and so on); in that case no slam
 *   happens and end() still removes the self fall modifier.
 *
 * WHAT IT LOOKS LIKE: at launch, 6 DUST_PILLAR particles burst at the
 * zombie's feet. While airborne, every 2 ticks, 2 DUST_PILLAR particles
 * trail at the zombie's position. At the slam: 1 EXPLOSION_EMITTER flash
 * at the center, 24 tan DUST motes (color 0.76, 0.70, 0.50, scale 1.6)
 * rolling outward, and 8 BLOCK_CRUMBLE particles of the ground block.
 *
 * PERFORMANCE: the airborne phase does one onGround check and one
 * fallDistance write per tick, plus a 2-particle trail every 2 ticks; the
 * linger phase returns immediately. The only entity scan is the single
 * bounded query at the slam moment (7 by 4 by 7 box). Particle budget per
 * activation: 6 at launch, at most 24 trail particles, 33 at the slam,
 * total at most 63 particles.
 *
 * State tag keys: "phase" (0 airborne, 1 lingering after the slam),
 * "slam_tick" (tick the slam fired), "victims" (list of victim UUID
 * strings whose knockback strip end() must remove).
 */
public final class SeismicSlamAbility implements MobAbility {

    private static final int MAX_TICKS = 70;
    private static final int LINGER_TICKS = 10;
    private static final float SLAM_DAMAGE = 6.0f;
    private static final Identifier FALL_ID = Identifier.fromNamespaceAndPath("advancedmobs", "seismic_slam_fall");
    private static final Identifier KB_STRIP_ID = Identifier.fromNamespaceAndPath("advancedmobs", "seismic_slam_kb_strip");
    // 26.2 DustParticleOptions takes an RGB int color: 0.76, 0.70, 0.50 as 0xC2B380.
    private static final DustParticleOptions SLAM_DUST = new DustParticleOptions(0xC2B380, 1.6f);
    private static final BlockParticleOption DIRT_PILLAR = new BlockParticleOption(ParticleTypes.DUST_PILLAR, Blocks.DIRT.defaultBlockState());
    private static final BlockParticleOption DIRT_CRUMBLE = new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, Blocks.DIRT.defaultBlockState());

    @Override
    public String id() {
        return "seismic_slam";
    }

    @Override
    public String displayName() {
        return "Seismic Slam";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null || !ctx.mob().onGround()) {
            return false;
        }
        double distanceSqr = ctx.mob().distanceToSqr(target);
        return distanceSqr >= 4.0 && distanceSqr <= 100.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        ctx.state().putInt("phase", 0);

        AttributeInstance fall = mob.getAttribute(Attributes.SAFE_FALL_DISTANCE);
        if (fall != null) {
            fall.addOrUpdateTransientModifier(
                new AttributeModifier(FALL_ID, 100.0, AttributeModifier.Operation.ADD_VALUE));
        }

        // Launch toward the target (fall back to the facing direction).
        Vec3 direction;
        LivingEntity target = ctx.target();
        if (target != null) {
            direction = new Vec3(target.getX() - mob.getX(), 0.0, target.getZ() - mob.getZ());
        } else {
            Vec3 look = mob.getLookAngle();
            direction = new Vec3(look.x, 0.0, look.z);
        }
        if (direction.lengthSqr() < 1.0e-4) {
            direction = new Vec3(0.0, 0.0, 1.0);
        }
        direction = direction.normalize();
        mob.setDeltaMovement(direction.x * 0.55, 1.05, direction.z * 0.55);
        mob.hurtMarked = true;

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.sendParticles(DIRT_PILLAR, mob.getX(), mob.getY() + 0.2, mob.getZ(), 6, 0.4, 0.1, 0.4, 0.05);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        if (state.getIntOr("phase", 0) != 0) {
            return; // Linger phase: nothing to do until end() cleans up.
        }
        Mob mob = ctx.mob();
        mob.fallDistance = 0.0f; // The slam landing must not hurt the zombie.
        int ticks = ctx.ticksActive();

        ServerLevel level = ctx.serverLevel();
        if (level != null && ticks % 2 == 0) {
            level.sendParticles(DIRT_PILLAR, mob.getX(), mob.getY() + 0.5, mob.getZ(), 2, 0.25, 0.4, 0.25, 0.02);
        }

        if (mob.onGround() && ticks >= 3) {
            doSlam(ctx);
            state.putInt("phase", 1);
            state.putInt("slam_tick", ticks);
        }
    }

    /** The landing shockwave: damage, knockback strip, and launch. Runs once, at landing. */
    private void doSlam(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        double cx = mob.getX(), cy = mob.getY(), cz = mob.getZ();

        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, cx, cy + 0.3, cz, 1, 0.0, 0.0, 0.0, 0.0);
        level.sendParticles(SLAM_DUST, cx, cy + 0.3, cz, 24, 2.8, 0.4, 2.8, 0.12);
        BlockState ground = level.getBlockState(mob.blockPosition().below());
        level.sendParticles(ground.isAir() ? DIRT_CRUMBLE : new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, ground),
            cx, cy + 0.2, cz, 8, 1.2, 0.2, 1.2, 0.05);

        List<LivingEntity> victims = ZombieAbilitySupport.enemiesInBox(ctx, cx, cy, cz, 3.5, 1.5, 2.5);
        ListTag victimIds = new ListTag();
        for (LivingEntity victim : victims) {
            // Strip knockback resistance first, then launch at full value.
            AttributeInstance kb = victim.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
            if (kb != null) {
                kb.addOrUpdateTransientModifier(
                    new AttributeModifier(KB_STRIP_ID, -1.0, AttributeModifier.Operation.ADD_VALUE));
                victimIds.add(StringTag.valueOf(victim.getUUID().toString()));
            }
            victim.hurtServer(level, level.damageSources().fall(), SLAM_DAMAGE);
            Vec3 away = new Vec3(victim.getX() - cx, 0.0, victim.getZ() - cz);
            if (away.lengthSqr() < 1.0e-4) {
                away = new Vec3(victim.getX() - mob.getX(), 0.0, victim.getZ() - mob.getZ());
            }
            if (away.lengthSqr() < 1.0e-4) {
                away = new Vec3(1.0, 0.0, 0.0);
            }
            away = away.normalize();
            victim.setDeltaMovement(away.x * 1.25, 0.5, away.z * 1.25);
            victim.hurtMarked = true;
        }
        ctx.state().put("victims", victimIds);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        if (state.getIntOr("phase", 0) == 1) {
            return ctx.ticksActive() >= state.getIntOr("slam_tick", 0) + LINGER_TICKS;
        }
        return ctx.ticksActive() >= MAX_TICKS; // Never landed: give up cleanly.
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        AttributeInstance fall = mob.getAttribute(Attributes.SAFE_FALL_DISTANCE);
        if (fall != null) {
            fall.removeModifier(FALL_ID);
        }
        // Remove the knockback strips from every recorded victim that is
        // still around. Victims that died or unloaded take the transient
        // modifier with them; there is nothing left to clean for them.
        ServerLevel level = ctx.serverLevel();
        if (level == null || !ctx.state().contains("victims")) {
            return;
        }
        ListTag victimIds = ctx.state().getListOrEmpty("victims");
        for (int i = 0; i < victimIds.size(); i++) {
            UUID uuid;
            try {
                uuid = UUID.fromString(victimIds.getStringOr(i, ""));
            } catch (IllegalArgumentException bad) {
                continue;
            }
            if (level.getEntity(uuid) instanceof LivingEntity victim) {
                AttributeInstance kb = victim.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
                if (kb != null) {
                    kb.removeModifier(KB_STRIP_ID);
                }
            }
        }
    }
}
