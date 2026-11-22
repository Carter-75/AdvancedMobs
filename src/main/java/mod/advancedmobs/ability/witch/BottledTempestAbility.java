package mod.advancedmobs.ability.witch;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Bottled Tempest (Witch ability 5 of 5, attack).
 *
 * WHAT IT DOES: the witch's masterpiece brew is a bottled
 * storm. She throws it like any potion (a strong harming brew
 * is the bottle's own payload), but where it breaks a bolt of
 * lightning crashes down in a burst of ELECTRIC_SPARK, and
 * the broken glass leaves a fizzing puddle that keeps
 * snapping sparks at anything standing in it for 10 seconds.
 * In rain the storm answers bigger: a second fork of
 * lightning strikes beside the first and the puddle spreads
 * wider. It is the only bottle in her bag she handles with
 * genuine respect.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the witch's current target within 18 blocks (distance
 *   squared 324).
 * - The throw (start()): one real ThrownSplashPotion carrying
 *   STRONG_HARMING, from the witch's hand (eye height) at her
 *   target's chest, speed 1.7, inaccuracy 1.0, WITCH_THROW
 *   sound. The bottle is tracked by UUID and its position is
 *   recorded every tick.
 * - The strike (the first tick the tracked bottle is gone,
 *   i.e. it broke; forced at tick 100 at the bottle's live
 *   position if it somehow never breaks): a lightning bolt
 *   crashes at the bottle's last recorded position.
 *   - mobGriefing ON: a real LightningBolt entity (not visual
 *     only): vanilla 5.0 lightning damage in its strike area
 *     and whatever fire vanilla lightning starts, which is
 *     vanilla's own behavior.
 *   - mobGriefing OFF: a visual-only bolt (no fire, no block
 *     change of any kind), and the strike damage is applied
 *     in code instead: enemies (WitchAbilitySupport rules)
 *     within radius 2.5 of the strike point take 5.0 damage
 *     from the lightning damage source
 *     (level.damageSources().lightningBolt(), the same
 *     uncredited environmental source a real bolt uses).
 *   Either way: 12 ELECTRIC_SPARK particles burst at the
 *   strike point and a LIGHTNING_BOLT_IMPACT sound plays.
 * - Rain (checked at the strike with level.isRainingAt): a
 *   second, visual-only fork bolt strikes at a random point
 *   within 2 blocks of the strike point (pure theatre, no
 *   extra damage), with 6 more ELECTRIC_SPARK, and the
 *   puddle radius is 3.5 instead of 2.5.
 * - The puddle (a zone in code, from the strike tick for 200
 *   ticks): every 10 ticks, enemies within the puddle radius
 *   of its center (between 1 below and 2 above its height)
 *   take 2.0 lightning-source damage, the snap. Every 4
 *   ticks, 4 ELECTRIC_SPARK particles fizz at random points
 *   in the disc. The puddle is not an entity and places no
 *   blocks; it is recorded center, radius, and expiry in the
 *   state tag.
 * - Duration: the activation ends when the puddle expires
 *   (strike tick + 200), hard-capped at tick 320. end() has
 *   nothing to clean: the bolt is fire and forget, and if
 *   the witch dies mid-puddle the framework termination runs
 *   end() and the snapping simply stops with her, which the
 *   design accepts (the puddle is her maintained brew, not a
 *   permanent hazard).
 *
 * WHAT IT LOOKS LIKE: a dark bottle arcs out, glass breaks,
 * the sky answers with a bolt and a crack of thunder, and
 * for ten seconds the wet patch of ground where it landed
 * crawls with snapping blue-white sparks, wider and forked
 * when it is raining.
 *
 * PERFORMANCE: before the strike, one UUID resolve and one
 * position read per tick. The strike is one entity spawn and
 * (in mobGriefing-off mode only) one bounded query. The
 * puddle pulses one bounded query every 10 ticks and 4
 * particles every 4 ticks. Particle budget per activation:
 * strike 12 (18 in rain), puddle fizz 50 pulses times 4 =
 * 200, total at most 218 particles, plus the bottle's own
 * vanilla splash particles.
 *
 * TERRAIN: the only possible block change is a real bolt's
 * vanilla lightning fire, and it happens only in mobGriefing
 * mode; with the rule off the bolt is visual only and the
 * damage is code. The puddle itself changes no blocks in
 * either mode.
 *
 * State tag keys: "bottle" (the thrown potion's UUID),
 * "lx", "ly", "lz" (its last recorded position), "struck"
 * (1 once the bolt has fallen), "px", "py", "pz" (puddle
 * center), "pr" (puddle radius), "expiry" (the tick the
 * puddle ends).
 */
public final class BottledTempestAbility implements MobAbility {

    private static final int FORCE_STRIKE_TICK = 100;
    private static final int HARD_FINISH_TICK = 320;
    private static final int PUDDLE_TICKS = 200;
    private static final int SNAP_INTERVAL = 10;
    private static final double PUDDLE_RADIUS = 2.5;
    private static final double PUDDLE_RADIUS_RAIN = 3.5;
    private static final float STRIKE_DAMAGE = 5.0f;
    private static final float SNAP_DAMAGE = 2.0f;

    @Override
    public String id() {
        return "bottled_tempest";
    }

    @Override
    public String displayName() {
        return "Bottled Tempest";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 324.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        LivingEntity target = ctx.target();
        if (level == null || target == null) {
            ctx.state().putInt("struck", 1);
            ctx.state().putInt("expiry", 0);
            return;
        }
        // The masterpiece: a strong harming brew, thrown like
        // any potion. The storm is what happens where it lands.
        ItemStack stack = new ItemStack(Items.SPLASH_POTION);
        stack.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.STRONG_HARMING));
        ThrownSplashPotion bottle = new ThrownSplashPotion(level, ctx.mob(), stack);
        Vec3 hand = new Vec3(ctx.mob().getX(), ctx.mob().getEyeY(), ctx.mob().getZ());
        bottle.setPos(hand.x, hand.y, hand.z);
        Vec3 aim = new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ());
        Vec3 dir = aim.subtract(hand);
        bottle.shoot(dir.x, dir.y, dir.z, 1.7f, 1.0f);
        level.addFreshEntity(bottle);

        CompoundTag state = ctx.state();
        state.store("bottle", UUIDUtil.CODEC, bottle.getUUID());
        state.putDouble("lx", hand.x);
        state.putDouble("ly", hand.y);
        state.putDouble("lz", hand.z);
        state.putInt("struck", 0);
        level.playSound(null, hand.x, hand.y, hand.z,
            SoundEvents.WITCH_THROW, SoundSource.HOSTILE, 0.9f, 0.9f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();

        if (state.getIntOr("struck", 0) == 0) {
            // Track the bottle until it breaks.
            UUID bottleId = state.read("bottle", UUIDUtil.CODEC).orElse(null);
            Entity bottle = bottleId != null ? level.getEntity(bottleId) : null;
            if (bottle != null && bottle.isAlive()) {
                state.putDouble("lx", bottle.getX());
                state.putDouble("ly", bottle.getY());
                state.putDouble("lz", bottle.getZ());
                if (ticks < FORCE_STRIKE_TICK) {
                    return;
                }
                // It never broke (a one in a million flight):
                // the storm goes off where it is.
                bottle.discard();
            }
            strike(ctx, level, ticks,
                state.getDoubleOr("lx", 0.0), state.getDoubleOr("ly", 0.0), state.getDoubleOr("lz", 0.0));
            return;
        }

        // The puddle: fizz and snaps until it dries.
        double px = state.getDoubleOr("px", 0.0);
        double py = state.getDoubleOr("py", 0.0);
        double pz = state.getDoubleOr("pz", 0.0);
        double radius = state.getDoubleOr("pr", PUDDLE_RADIUS);
        if (ticks % 4 == 0) {
            double angle = ctx.random().nextDouble() * Math.PI * 2.0;
            double distance = Math.sqrt(ctx.random().nextDouble()) * radius;
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                px + Math.cos(angle) * distance, py + 0.3, pz + Math.sin(angle) * distance,
                4, 0.15, 0.1, 0.15, 0.05);
        }
        if (ticks % SNAP_INTERVAL == 0) {
            List<LivingEntity> victims = WitchAbilitySupport.enemiesInBox(ctx, px, py, pz, radius, 1.0, 2.0);
            for (LivingEntity victim : victims) {
                victim.hurtServer(level, level.damageSources().lightningBolt(), SNAP_DAMAGE);
            }
        }
    }

    /** The bolt falls at the given point and the puddle is laid. See the class comment. */
    private void strike(AbilityContext ctx, ServerLevel level, int ticks, double x, double y, double z) {
        CompoundTag state = ctx.state();
        boolean raining = level.isRainingAt(BlockPos.containing(x, y, z));

        LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (bolt != null) {
            bolt.snapTo(x, y, z);
            // Terrain rule: a real bolt only when mobGriefing
            // allows its fire; otherwise the bolt is theatre
            // and the damage is applied in code below.
            bolt.setVisualOnly(!ctx.mobGriefingAllowed());
            level.addFreshEntity(bolt);
        }
        if (!ctx.mobGriefingAllowed()) {
            List<LivingEntity> victims = WitchAbilitySupport.enemiesInBox(ctx, x, y, z, 2.5, 2.0, 3.0);
            for (LivingEntity victim : victims) {
                victim.hurtServer(level, level.damageSources().lightningBolt(), STRIKE_DAMAGE);
            }
        }
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, y + 0.5, z, 12, 0.6, 0.6, 0.6, 0.2);
        level.playSound(null, x, y, z,
            SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.HOSTILE, 1.0f, 1.0f);

        double radius = PUDDLE_RADIUS;
        if (raining) {
            // The bigger storm: a fork beside the strike and a
            // wider puddle.
            radius = PUDDLE_RADIUS_RAIN;
            double forkX = x + (ctx.random().nextDouble() * 4.0 - 2.0);
            double forkZ = z + (ctx.random().nextDouble() * 4.0 - 2.0);
            LightningBolt fork = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (fork != null) {
                fork.snapTo(forkX, y, forkZ);
                fork.setVisualOnly(true);
                level.addFreshEntity(fork);
            }
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, forkX, y + 0.5, forkZ, 6, 0.5, 0.5, 0.5, 0.2);
        }

        state.putInt("struck", 1);
        state.putDouble("px", x);
        state.putDouble("py", y);
        state.putDouble("pz", z);
        state.putDouble("pr", radius);
        state.putInt("expiry", ticks + PUDDLE_TICKS);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        if (ctx.ticksActive() >= HARD_FINISH_TICK) {
            return true;
        }
        return state.getIntOr("struck", 0) == 1
            && ctx.ticksActive() >= state.getIntOr("expiry", 0);
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to clean: the bolt was fire and forget, and
        // the puddle is zone state in the tag that dies with
        // the activation (see the class comment).
    }
}
