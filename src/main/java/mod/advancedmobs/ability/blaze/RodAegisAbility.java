package mod.advancedmobs.ability.blaze;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/**
 * Rod Aegis (Blaze ability 5 of 5, summon, construct).
 *
 * WHAT IT DOES: six of the blaze's rods detach and hang in
 * the air around it as a slow burning halo. The halo swats
 * incoming projectiles out of the air and sears any melee
 * attacker who connects with the blaze. Every rod can absorb
 * two projectile blocks before it shatters into blaze rod
 * fragments; when the last rod is gone, the aegis is gone,
 * and the blaze stands open.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists within 14 blocks
 *   (distance squared 196).
 * - The halo (start()): 6 item displays, each showing a blaze
 *   rod at scale 1.0 and full brightness, gliding with a 2
 *   tick teleport duration, each recorded in the state tag
 *   with 2 charges. Every tick each surviving rod is
 *   repositioned onto a slowly rotating bearing (its index
 *   spread evenly around the circle, the whole ring turning
 *   0.03 radians per tick) at radius (half the blaze's width
 *   plus 0.7) and height 55 percent of the blaze's height:
 *   the halo orbits and travels with the blaze. The rods are
 *   pure visuals: displays have no hitbox and block nothing
 *   themselves; the interception below is the blocking.
 * - Projectile interception (tick(), every tick): one bounded
 *   query for Projectile entities in a box of radius 3.0
 *   around the blaze's center. A projectile is hostile when
 *   its owner is an enemy of the blaze by the shared enemy
 *   rule, or when it has no owner at all (a dispenser shot);
 *   the blaze's own fireballs and its allies' shots are never
 *   touched. The first hostile projectile found is swatted by
 *   the first rod that still has charges: the projectile is
 *   discarded, the rod flares with 6 FLAME at the projectile's
 *   position and a FIRECHARGE_USE (volume 0.5, pitch 1.4),
 *   and the rod loses 1 charge. At most one projectile is
 *   blocked per tick: the halo answers shots one at a time,
 *   so a dense volley leaks through between ticks. A rod
 *   whose charges reach 0 shatters on the spot: 8 ITEM
 *   particles of blaze rod fragments at the rod's position, a
 *   GLASS_BREAK (volume 0.7, pitch 1.2), the display is
 *   discarded, and the rod leaves the list.
 * - The melee sear (tick(), every tick): the blaze's last
 *   hurt record is polled against the timestamp stored in the
 *   state tag (primed in start(), so damage taken before the
 *   aegis rose never sears anyone). A fresh timestamp with an
 *   attacker who is an enemy of the blaze and within 4.5
 *   blocks (a melee connect; damage events are already rate
 *   limited by vanilla hurt immunity) sears the attacker:
 *   ignited for 3 seconds, with 6 FLAME at the attacker's body
 *   and a BLAZE_HURT (volume 0.6, pitch 1.1) from the blaze.
 *   The sear consumes NO rod charge: rods are spent only on
 *   projectiles, per the design (a rod shatters after
 *   absorbing two blocks). The sear needs at least one rod
 *   still standing; with no halo there is nothing to sear
 *   with.
 * - Ambient burn (tick(), every 20 ticks): 1 FLAME at each
 *   surviving rod's position, the halo idling hot.
 * - Duration: until every rod has shattered, with a hard
 *   finish at 600 ticks (30 seconds). The aegis's long
 *   framework cooldown after it ends is the design's "can
 *   rebuild only after a long cooldown": the cooldown system
 *   is shared by all abilities and is not this class's to
 *   lengthen.
 *
 * TERRAIN RULE: this ability changes no blocks at all: the
 * rods are displays and the interception is code. The
 * mobGriefing gate does not apply anywhere in it.
 *
 * WHAT IT LOOKS LIKE: six burning rods circling the blaze in
 * a slow halo, flaring bright each time one knocks a shot out
 * of the air, and every so often one of them cracking apart
 * into a rain of rod fragments with a glassy snap, the halo
 * thinning rod by rod until nothing is left.
 *
 * PERFORMANCE: per tick the work is at most 6 UUID resolves
 * and snapTo writes for the rods, one bounded projectile
 * query in a radius 3.0 box, and one timestamp compare for
 * the sear poll. No whole-level scans, no allocations in the
 * tick path beyond the query results and the rebuilt rod
 * list on ticks where a rod shatters. Particle budget per
 * activation: the start burst 12, blocks at most 12 (the
 * halo's total charges) times 6 = 72 FLAME, shatters 6 times
 * 8 = 48 ITEM, sears at most a generous 20 connects times 6
 * = 120 FLAME, ambient at most 30 pulses times 6 = 180
 * FLAME, total at most 432 particles. The 6 displays are the
 * only extra entities, alive only while the aegis stands.
 *
 * CLEANUP: end() discards every rod display that still
 * resolves. If the caster dies mid-aegis, the framework
 * termination path runs this same end(), so the halo never
 * outlives the ability or its caster.
 *
 * State tag keys: "rods" (list of compounds, one per
 * surviving rod: "id", the display's UUID; "charges", blocks
 * left before it shatters), "hurt_ts" (the last hurt
 * timestamp the sear has answered).
 */
public final class RodAegisAbility implements MobAbility {

    private static final int HARD_FINISH_TICKS = 600;
    private static final int ROD_COUNT = 6;
    private static final int ROD_CHARGES = 2;
    private static final double INTERCEPT_RADIUS = 3.0;
    private static final double ORBIT_SPEED = 0.03;
    // 26.2 ItemParticleOption takes the item itself: the
    // shatter debris of a blaze rod.
    private static final ItemParticleOption ROD_DEBRIS =
        new ItemParticleOption(ParticleTypes.ITEM, Items.BLAZE_ROD);

    @Override
    public String id() {
        return "rod_aegis";
    }

    @Override
    public String displayName() {
        return "Rod Aegis";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return ctx.target() != null && ctx.mob().distanceToSqr(ctx.target()) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        ListTag rods = new ListTag();
        state.put("rods", rods);
        state.putInt("hurt_ts", mob.getLastHurtByMobTimestamp());
        if (level != null) {
            for (int i = 0; i < ROD_COUNT; i++) {
                Entity rod = BlazeAbilitySupport.createRodDisplay(level);
                if (rod == null) {
                    continue;
                }
                level.addFreshEntity(rod);
                CompoundTag entry = new CompoundTag();
                entry.store("id", UUIDUtil.CODEC, rod.getUUID());
                entry.putInt("charges", ROD_CHARGES);
                rods.add(entry);
            }
            state.put("rods", rods);
            positionRods(ctx, level);
            level.sendParticles(ParticleTypes.FLAME, mob.getX(), mob.getY() + 1.2, mob.getZ(),
                12, mob.getBbWidth() * 0.6, 0.7, mob.getBbWidth() * 0.6, 0.06);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.BLAZE_BURN,
                SoundSource.HOSTILE, 0.8f, 1.0f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        positionRods(ctx, level);
        if (ctx.state().getListOrEmpty("rods").isEmpty()) {
            return;
        }
        interceptProjectile(ctx, level);
        searAttacker(ctx, level);
        if (ctx.ticksActive() % 20 == 0) {
            ambientBurn(ctx, level);
        }
    }

    /** Places every surviving rod on its rotating bearing around the blaze. */
    private void positionRods(AbilityContext ctx, ServerLevel level) {
        Mob mob = ctx.mob();
        ListTag rods = ctx.state().getListOrEmpty("rods");
        int count = rods.size();
        if (count == 0) {
            return;
        }
        double radius = mob.getBbWidth() / 2.0 + 0.7;
        double y = mob.getY() + mob.getBbHeight() * 0.55;
        double turn = ctx.ticksActive() * ORBIT_SPEED;
        for (int i = 0; i < count; i++) {
            Entity rod = resolveRod(level, rods.getCompoundOrEmpty(i));
            if (rod == null) {
                continue;
            }
            double angle = turn + i * (Math.PI * 2.0 / count);
            rod.snapTo(mob.getX() + Math.cos(angle) * radius, y, mob.getZ() + Math.sin(angle) * radius,
                (float) Math.toDegrees(-angle), 0.0f);
        }
    }

    /**
     * The interception: the first hostile projectile in reach
     * is swatted by the first charged rod (see the class
     * comment for the hostility and charge rules).
     */
    private void interceptProjectile(AbilityContext ctx, ServerLevel level) {
        Mob mob = ctx.mob();
        AABB box = new AABB(mob.getX() - INTERCEPT_RADIUS, mob.getY() - 1.0, mob.getZ() - INTERCEPT_RADIUS,
                            mob.getX() + INTERCEPT_RADIUS, mob.getY() + mob.getBbHeight() + 1.0,
                            mob.getZ() + INTERCEPT_RADIUS);
        List<Projectile> projectiles = level.getEntitiesOfClass(Projectile.class, box);
        for (Projectile projectile : projectiles) {
            if (!isHostile(ctx, projectile)) {
                continue;
            }
            spendRodOnBlock(ctx, level, projectile);
            return;
        }
    }

    /** Whether the projectile is one the aegis answers (see the class comment). */
    private static boolean isHostile(AbilityContext ctx, Projectile projectile) {
        Entity owner = projectile.getOwner();
        if (owner == null) {
            return true;
        }
        if (owner == ctx.mob()) {
            return false;
        }
        return owner instanceof LivingEntity living && BlazeAbilitySupport.isEnemy(ctx, living);
    }

    /**
     * Spends one charge of the first charged rod on blocking
     * the projectile, and shatters the rod if that was its
     * last charge (see the class comment).
     */
    private void spendRodOnBlock(AbilityContext ctx, ServerLevel level, Projectile projectile) {
        CompoundTag state = ctx.state();
        ListTag rods = state.getListOrEmpty("rods");
        for (int i = 0; i < rods.size(); i++) {
            CompoundTag entry = rods.getCompoundOrEmpty(i);
            int charges = entry.getIntOr("charges", 0);
            if (charges <= 0) {
                continue;
            }
            charges--;
            entry.putInt("charges", charges);
            rods.set(i, entry);
            state.put("rods", rods);
            level.sendParticles(ParticleTypes.FLAME, projectile.getX(), projectile.getY(), projectile.getZ(),
                6, 0.25, 0.25, 0.25, 0.05);
            level.playSound(null, projectile.getX(), projectile.getY(), projectile.getZ(),
                SoundEvents.FIRECHARGE_USE, SoundSource.HOSTILE, 0.5f, 1.4f);
            projectile.discard();
            if (charges == 0) {
                shatterRod(ctx, level, i);
            }
            return;
        }
    }

    /** Shatters the rod at the given list index: debris, the snap, the display discarded. */
    private void shatterRod(AbilityContext ctx, ServerLevel level, int index) {
        CompoundTag state = ctx.state();
        ListTag rods = state.getListOrEmpty("rods");
        if (index >= rods.size()) {
            return;
        }
        Entity rod = resolveRod(level, rods.getCompoundOrEmpty(index));
        if (rod != null) {
            level.sendParticles(ROD_DEBRIS, rod.getX(), rod.getY(), rod.getZ(),
                8, 0.3, 0.3, 0.3, 0.08);
            level.playSound(null, rod.getX(), rod.getY(), rod.getZ(), SoundEvents.GLASS_BREAK,
                SoundSource.HOSTILE, 0.7f, 1.2f);
            rod.discard();
        }
        rods.remove(index);
        state.put("rods", rods);
    }

    /**
     * The melee sear: a fresh hurt record from an enemy in
     * melee range answers with ignition (see the class
     * comment).
     */
    private void searAttacker(AbilityContext ctx, ServerLevel level) {
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        int timestamp = mob.getLastHurtByMobTimestamp();
        if (timestamp == state.getIntOr("hurt_ts", timestamp)) {
            return;
        }
        state.putInt("hurt_ts", timestamp);
        LivingEntity attacker = mob.getLastHurtByMob();
        if (attacker == null || !BlazeAbilitySupport.isEnemy(ctx, attacker)) {
            return;
        }
        if (mob.distanceToSqr(attacker) > 20.25) {
            return; // Not a melee connect: a shot, a burn, a fall.
        }
        attacker.igniteForSeconds(3.0f);
        level.sendParticles(ParticleTypes.FLAME, attacker.getX(), attacker.getY() + 0.6, attacker.getZ(),
            6, 0.35, 0.5, 0.35, 0.05);
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.BLAZE_HURT,
            SoundSource.HOSTILE, 0.6f, 1.1f);
    }

    /** The ambient burn: 1 FLAME at each surviving rod. */
    private void ambientBurn(AbilityContext ctx, ServerLevel level) {
        ListTag rods = ctx.state().getListOrEmpty("rods");
        for (int i = 0; i < rods.size(); i++) {
            Entity rod = resolveRod(level, rods.getCompoundOrEmpty(i));
            if (rod != null) {
                level.sendParticles(ParticleTypes.FLAME, rod.getX(), rod.getY() + 0.3, rod.getZ(),
                    1, 0.1, 0.1, 0.1, 0.01);
            }
        }
    }

    /** Resolves a tracked rod display from its list entry, or null when gone. */
    private Entity resolveRod(ServerLevel level, CompoundTag entry) {
        if (!entry.contains("id")) {
            return null;
        }
        UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
        return id != null ? level.getEntity(id) : null;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= HARD_FINISH_TICKS
            || ctx.state().getListOrEmpty("rods").isEmpty();
    }

    @Override
    public void end(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag rods = ctx.state().getListOrEmpty("rods");
        for (int i = 0; i < rods.size(); i++) {
            Entity rod = resolveRod(level, rods.getCompoundOrEmpty(i));
            if (rod != null) {
                rod.discard();
            }
        }
    }
}
