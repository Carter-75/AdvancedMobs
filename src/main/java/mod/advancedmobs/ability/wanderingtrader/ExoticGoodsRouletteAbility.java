package mod.advancedmobs.ability.wanderingtrader;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
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
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Exotic Goods Roulette (Wandering Trader ability 3 of 5,
 * attack).
 *
 * WHAT IT DOES: the trader upends its whole pack at the
 * enemy. Dyes, saplings, a bucket, and stranger merchandise
 * fountain out one piece at a time, and every piece carries a
 * random small curse rolled the moment it leaves the hand:
 * one hit poisons, the next slows, the next weakens, and
 * every fourth is just a bonk. Neither side knows which until
 * it lands.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the trader's current target within 16 blocks (distance
 *   squared 256).
 * - The stock (the design's "/loot driven random item
 *   selection in code", carried as a fixed table): each shot
 *   draws its merchandise uniformly at random from the
 *   package stock list (WanderingTraderAbilitySupport.GOODS:
 *   red dye, blue dye, oak sapling, bucket, bone, string,
 *   feather, glass bottle, wheat seeds, clay ball). The draw
 *   is made with the trader's own random source at fire time
 *   and stored in the shot's tracking entry, so a resumed
 *   activation after a reload bursts with the right item.
 * - The curse roll (also at fire time, same random source):
 *   0 = POISON I, 1 = SLOWNESS I, 2 = WEAKNESS I, 3 = plain
 *   bonk. The rolled effect applies for 100 ticks on the
 *   burst (below), on top of the bonk damage every burst
 *   deals. The roll is stored in the tracking entry ("fx"),
 *   never re-rolled.
 * - The throw (8 shots, first at tick 5, then one every 6
 *   ticks: ticks 5, 11, 17, 23, 29, 35, 41, 47): each piece
 *   is a real vanilla Snowball projectile created from
 *   EntityTypes.SNOWBALL, owned by the trader through
 *   Projectile.setOwner, with its item set to the drawn
 *   merchandise stack so it renders as that flying item (the
 *   Berry Bombardier / Emerald Barrage carrier pattern: the
 *   snowball's own contact hit is harmless, so every point
 *   of damage comes from the burst below, in code). Each
 *   shot starts at the trader's eye pushed 0.7 blocks along
 *   its look direction and is shot at speed 1.6 toward the
 *   target's recorded chest, raised by 0.20 per horizontal
 *   block for a lobbed arc, inaccuracy 2.5. The target's
 *   live position is recorded every tick it lives (state
 *   tag tx/ty/tz), so shots still aim at the last known spot
 *   if it dies. WANDERING_TRADER_TRADE plays per shot at
 *   volume 0.8, pitch 1.0 plus 0.03 per shot.
 * - The burst (every tick): each tracked piece that is still
 *   alive refreshes its recorded position and trails 1 ITEM
 *   particle of its own merchandise every 2 ticks. A piece
 *   that has resolved (hit something or expired) bursts once
 *   at its last recorded position: 6 ITEM particles of its
 *   merchandise plus 4 CRIT, and every enemy within 1.6
 *   blocks (vertical reach 1.5, package enemy rule) takes
 *   2.0 damage through a projectile-typed damage source
 *   (DamageSources.mobProjectile with the trader as both the
 *   projectile stand-in and the owner) and, when the roll
 *   was not the plain bonk, gains the rolled effect at
 *   amplifier 0 for 100 ticks. If all 8 bursts catch one
 *   victim that is 16 damage total plus up to 8 curses; the
 *   spread and the moving target make that unlikely.
 * - Duration: finishes at tick 60 at the earliest once all
 *   8 shots are fired and every piece has burst; hard finish
 *   at tick 120 whatever is still flying (an unresolved
 *   piece is simply dropped from tracking; end() needs no
 *   cleanup because live pieces are vanilla projectiles
 *   that finish their own flight).
 *
 * WHAT IT LOOKS LIKE: the trader hauling double handfuls out
 * of its pack and hurling them in a clattering stream, a
 * bucket sailing past a sapling, dye pouches bursting in
 * colored sprays where they land, and every so often a
 * victim turning green, or slowing, or sagging, on a hit
 * nobody predicted.
 *
 * PERFORMANCE: a shot tick is one spawn, one sound, and two
 * random draws; the tracking pass resolves at most 8 UUIDs
 * per tick with one bounded radius-1.6 query only on a tick
 * where a piece actually burst. Particle budget per
 * activation: trails at most 8 pieces times 10 = 80, bursts
 * at most 8 times 10 = 80, total at most 160 particles.
 *
 * TERRAIN: this ability changes no blocks (the merchandise
 * is vanilla projectiles whose hits never alter blocks);
 * the mobGriefing gate does not apply.
 *
 * State tag keys: "shots" (pieces thrown), "next_shot"
 * (tick of the next throw), "tx"/"ty"/"tz" (last known
 * target position), "goods" (list of compounds, one per
 * live piece: "id", "x", "y", "z", "gi" the stock index,
 * "fx" the rolled curse: 0 poison, 1 slowness, 2 weakness,
 * 3 plain bonk).
 */
public final class ExoticGoodsRouletteAbility implements MobAbility {

    private static final int FIRST_SHOT_TICK = 5;
    private static final int SHOT_INTERVAL = 6;
    private static final int SHOT_COUNT = 8;
    private static final int EARLY_FINISH_TICK = 60;
    private static final int HARD_FINISH_TICK = 120;
    private static final double GOODS_SPEED = 1.6;
    private static final double ARC_LIFT_PER_BLOCK = 0.20;
    private static final double BURST_RADIUS = 1.6;
    private static final double BURST_DAMAGE = 2.0;
    private static final int CURSE_TICKS = 100;

    @Override
    public String id() {
        return "exotic_goods_roulette";
    }

    @Override
    public String displayName() {
        return "Exotic Goods Roulette";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().putInt("shots", 0);
        ctx.state().putInt("next_shot", FIRST_SHOT_TICK);
        ctx.state().put("goods", new ListTag());
        LivingEntity target = ctx.target();
        if (target != null) {
            recordTarget(ctx, target);
        }
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            Mob caster = ctx.mob();
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.WANDERING_TRADER_YES, SoundSource.HOSTILE, 1.0f, 1.1f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();

        LivingEntity target = ctx.target();
        if (target != null) {
            recordTarget(ctx, target);
        }

        int shots = ctx.state().getIntOr("shots", 0);
        if (shots < SHOT_COUNT && ticks >= ctx.state().getIntOr("next_shot", FIRST_SHOT_TICK)) {
            fireGoods(ctx, level, shots);
            shots++;
            ctx.state().putInt("shots", shots);
            ctx.state().putInt("next_shot", ticks + SHOT_INTERVAL);
        }

        trackGoods(ctx, level, ticks);
    }

    /** Throws one piece of merchandise (see the class comment). */
    private void fireGoods(AbilityContext ctx, ServerLevel level, int shotIndex) {
        Mob caster = ctx.mob();
        double tx = ctx.state().getDoubleOr("tx", caster.getX());
        double ty = ctx.state().getDoubleOr("ty", caster.getEyeY());
        double tz = ctx.state().getDoubleOr("tz", caster.getZ());

        // The roulette: the merchandise and its curse are drawn now.
        int goodsIndex = ctx.random().nextInt(WanderingTraderAbilitySupport.GOODS.length);
        int curse = ctx.random().nextInt(4);

        Vec3 origin = caster.getEyePosition().add(caster.getLookAngle().scale(0.7));
        double dx = tx - origin.x;
        double dz = tz - origin.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double dy = (ty + 0.5 + horizontal * ARC_LIFT_PER_BLOCK) - origin.y;

        Snowball piece = EntityTypes.SNOWBALL.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (piece == null) {
            return;
        }
        piece.setOwner(caster);
        piece.setItem(new ItemStack(WanderingTraderAbilitySupport.GOODS[goodsIndex]));
        piece.setPos(origin.x, origin.y, origin.z);
        piece.shoot(dx, dy, dz, (float) GOODS_SPEED, 2.5f);
        if (level.addFreshEntity(piece)) {
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, piece.getUUID());
            entry.putDouble("x", piece.getX());
            entry.putDouble("y", piece.getY());
            entry.putDouble("z", piece.getZ());
            entry.putInt("gi", goodsIndex);
            entry.putInt("fx", curse);
            ListTag goods = ctx.state().getListOrEmpty("goods");
            goods.add(entry);
            ctx.state().put("goods", goods);
        }
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.WANDERING_TRADER_TRADE, SoundSource.HOSTILE, 0.8f, 1.0f + 0.03f * shotIndex);
    }

    /**
     * Refreshes live pieces' recorded positions, trails them
     * with their own merchandise particles, and bursts the
     * ones that resolved since the last tick (see the class
     * comment). Resolved pieces are pruned from the tracking
     * list in the same pass.
     */
    private void trackGoods(AbilityContext ctx, ServerLevel level, int ticks) {
        ListTag tracked = ctx.state().getListOrEmpty("goods");
        if (tracked.isEmpty()) {
            return;
        }
        ListTag remaining = new ListTag();
        for (int i = 0; i < tracked.size(); i++) {
            CompoundTag entry = tracked.getCompoundOrEmpty(i);
            int goodsIndex = entry.getIntOr("gi", 0);
            Entity entity = WanderingTraderAbilitySupport.resolveTracked(level, entry);
            if (entity != null && entity.isAlive()) {
                entry.putDouble("x", entity.getX());
                entry.putDouble("y", entity.getY());
                entry.putDouble("z", entity.getZ());
                if (ticks % 2 == 0) {
                    level.sendParticles(WanderingTraderAbilitySupport.GOOD_PARTICLES[goodsIndex],
                        entity.getX(), entity.getY(), entity.getZ(), 1, 0.05, 0.05, 0.05, 0.01);
                }
                remaining.add(entry);
                continue;
            }
            // Resolved: the burst lands at its last known position.
            double x = entry.getDoubleOr("x", 0.0);
            double y = entry.getDoubleOr("y", 0.0);
            double z = entry.getDoubleOr("z", 0.0);
            level.sendParticles(WanderingTraderAbilitySupport.GOOD_PARTICLES[goodsIndex],
                x, y, z, 6, 0.3, 0.25, 0.3, 0.06);
            level.sendParticles(ParticleTypes.CRIT, x, y, z, 4, 0.25, 0.25, 0.25, 0.08);
            Mob caster = ctx.mob();
            int curse = entry.getIntOr("fx", 3);
            List<LivingEntity> caught = WanderingTraderAbilitySupport.enemiesInBox(ctx, x, y, z,
                BURST_RADIUS, 1.5, 1.5);
            for (LivingEntity victim : caught) {
                victim.hurtServer(level, level.damageSources().mobProjectile(caster, caster),
                    (float) BURST_DAMAGE);
                switch (curse) {
                    case 0 -> victim.addEffect(new MobEffectInstance(MobEffects.POISON, CURSE_TICKS, 0));
                    case 1 -> victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, CURSE_TICKS, 0));
                    case 2 -> victim.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, CURSE_TICKS, 0));
                    default -> {
                        // Roll 3: the plain bonk, no curse.
                    }
                }
            }
        }
        ctx.state().put("goods", remaining);
    }

    /** Records the target's live position for aiming after it dies or is lost. */
    private void recordTarget(AbilityContext ctx, LivingEntity target) {
        ctx.state().putDouble("tx", target.getX());
        ctx.state().putDouble("ty", target.getY());
        ctx.state().putDouble("tz", target.getZ());
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks >= HARD_FINISH_TICK) {
            return true;
        }
        return ticks >= EARLY_FINISH_TICK
            && ctx.state().getIntOr("shots", 0) >= SHOT_COUNT
            && ctx.state().getListOrEmpty("goods").isEmpty();
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to undo: live pieces are vanilla projectiles
        // that finish their own flight, and the curses ride
        // their own 100-tick clocks.
    }
}
