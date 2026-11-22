package mod.advancedmobs.ability.rabbit;

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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.List;
import java.util.UUID;

/**
 * Carrot Rain (Rabbit ability 3 of 5, attack / terrain).
 *
 * WHAT IT DOES: the rabbit kicks up its hidden stash and
 * eight carrots arc high into the sky in a staggered
 * volley, spearing down across the ground around its
 * target. Each carrot that lands thumps into the earth,
 * hurting and slowing whatever it lands next to, and
 * sticks there quivering for a few seconds while enemies
 * weave between the planted carrots.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the rabbit's current target within 16 blocks
 *   (distance squared 256). The impact center is the
 *   target's position recorded in start(); the volley
 *   does not track a moving target afterwards.
 * - The volley (start()): 8 carrot item displays (scale
 *   1.3) are created at the rabbit's head. Each carrot
 *   gets a landing point scattered around the impact
 *   center: angle i times 45 degrees plus a random
 *   jitter of up to 0.5 radians, radius 0.8 to 3.0
 *   blocks, with the landing height found by the
 *   support class's surface scan (at most 12 block reads
 *   per carrot, once). Launch ticks are staggered, one
 *   carrot every 3 ticks: carrots launch at activation
 *   ticks 1, 4, 7, 10, 13, 16, 19, 22. The kick-up is 6
 *   POOF particles and RABBIT_JUMP (volume 1.2, pitch
 *   1.0) at start().
 * - The arc (tick()): before its launch tick a carrot
 *   rides at the rabbit's head (snapped to the rabbit's
 *   live position plus 0.8 up each tick, and its flight
 *   start point is updated to match, so the arc begins
 *   where the carrot actually was). In flight, over
 *   exactly 16 ticks, the carrot is snapped each tick
 *   along the straight line from its start point to its
 *   landing point with a sine arc added: height =
 *   sin(progress times pi) times 5.0 blocks, so the
 *   volley peaks 5 blocks up mid-flight. This code arc
 *   is the design's interpolation arc, carried by the
 *   codebase's per-tick display snapping (the display
 *   pattern every implemented mob uses).
 * - The landing (the tick progress reaches 1): the
 *   carrot snaps to its landing point 0.25 above the
 *   ground, 4 POOF particles puff at the impact, and
 *   every enemy (the package enemy rule) within 1.5
 *   blocks horizontally of the landing point takes 3.0
 *   mob-attack damage credited to the rabbit and gains
 *   SLOWNESS I for 60 ticks: the thump and the tangle
 *   of the planted patch. One bounded query per landing,
 *   8 landings at most.
 * - The stick: a landed carrot stays planted for 60
 *   ticks, then is discarded. A display is not a block
 *   (see the support class comment), so the planted
 *   carrots are pure visuals: nothing about them is a
 *   terrain change and the mobGriefing gate does not
 *   apply. The slowness comes from the landing thump,
 *   not from standing near a stuck carrot.
 * - Duration: 110 ticks (the last carrot launches at
 *   tick 22, lands at 38, and sticks until 98; the
 *   activation outlives it with margin). A carrot whose
 *   display no longer resolves (its chunk unloaded
 *   mid-flight) is marked gone with no landing; if it
 *   later reloads it is an orphaned display carrot, an
 *   accepted edge case shared with every display in
 *   this mod.
 * - end(): every carrot display still tracked and alive
 *   is discarded, landed or not.
 *
 * WHAT IT LOOKS LIKE: a rabbit kicking up a puff of
 * dust, and then carrots raining out of the sky in a
 * loose ring around the target, each one thumping into
 * the dirt with a little puff and standing there
 * quivering while the fight stumbles through them.
 *
 * PERFORMANCE: start() does 8 surface scans (at most 12
 * block reads each) and 8 display creations, once.
 * tick() resolves at most 8 UUIDs and snaps at most 8
 * displays; the only entity queries are the 8 landing
 * bursts. Particle budget per activation: 6 at start,
 * landings 8 times 4 = 32, total at most 38 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate
 * does not apply (the carrots are display entities).
 *
 * State tag keys: "carrots" (list of compounds, one per
 * carrot: "id" the display's UUID, "sx", "sy", "sz" the
 * flight start point, "lx", "ly", "lz" the landing
 * point, "launch" the launch tick, "landed" 1 once the
 * landing ran, "land_tick" the landing tick, "gone" 1
 * once resolved or discarded).
 */
public final class CarrotRainAbility implements MobAbility {

    private static final int DURATION_TICKS = 110;
    private static final int CARROT_COUNT = 8;
    private static final int LAUNCH_STAGGER_TICKS = 3;
    private static final int FLIGHT_TICKS = 16;
    private static final int STICK_TICKS = 60;
    private static final double ARC_HEIGHT = 5.0;
    private static final float LAND_DAMAGE = 3.0f;
    private static final double LAND_RADIUS = 1.5;
    private static final float DISPLAY_SCALE = 1.3f;

    @Override
    public String id() {
        return "carrot_rain";
    }

    @Override
    public String displayName() {
        return "Carrot Rain";
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
        ListTag carrots = new ListTag();
        ctx.state().put("carrots", carrots);
        if (level == null) {
            return;
        }

        level.sendParticles(ParticleTypes.POOF, caster.getX(), caster.getY() + 0.5, caster.getZ(),
            6, 0.4, 0.3, 0.4, 0.05);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.RABBIT_JUMP,
            SoundSource.HOSTILE, 1.2f, 1.0f);

        LivingEntity target = ctx.target();
        double centerX = target != null ? target.getX() : caster.getX();
        double centerY = target != null ? target.getY() : caster.getY();
        double centerZ = target != null ? target.getZ() : caster.getZ();
        double startX = caster.getX();
        double startY = caster.getY() + 0.8;
        double startZ = caster.getZ();

        for (int i = 0; i < CARROT_COUNT; i++) {
            Entity display = RabbitAbilitySupport.createCarrotDisplay(level, DISPLAY_SCALE);
            if (display == null) {
                continue;
            }
            double angle = Math.toRadians(i * 45.0) + (ctx.random().nextDouble() - 0.5);
            double radius = 0.8 + ctx.random().nextDouble() * 2.2;
            double landX = centerX + Math.cos(angle) * radius;
            double landZ = centerZ + Math.sin(angle) * radius;
            double landY = RabbitAbilitySupport.findGroundY(level, landX, centerY, landZ);
            display.snapTo(startX, startY, startZ, 0.0f, 0.0f);
            if (!level.addFreshEntity(display)) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, display.getUUID());
            entry.putDouble("sx", startX);
            entry.putDouble("sy", startY);
            entry.putDouble("sz", startZ);
            entry.putDouble("lx", landX);
            entry.putDouble("ly", landY);
            entry.putDouble("lz", landZ);
            entry.putInt("launch", 1 + i * LAUNCH_STAGGER_TICKS);
            entry.putInt("landed", 0);
            entry.putInt("land_tick", 0);
            entry.putInt("gone", 0);
            carrots.add(entry);
        }
        ctx.state().put("carrots", carrots);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        int ticks = ctx.ticksActive();
        ListTag carrots = ctx.state().getListOrEmpty("carrots");
        for (int i = 0; i < carrots.size(); i++) {
            CompoundTag entry = carrots.getCompoundOrEmpty(i);
            if (entry.getIntOr("gone", 0) == 1) {
                continue;
            }
            Entity found = RabbitAbilitySupport.resolveTracked(level, entry);
            if (found == null) {
                entry.putInt("gone", 1);
                carrots.set(i, entry);
                continue;
            }
            int launch = entry.getIntOr("launch", 1);
            if (entry.getIntOr("landed", 0) == 1) {
                if (ticks - entry.getIntOr("land_tick", 0) > STICK_TICKS) {
                    found.discard();
                    entry.putInt("gone", 1);
                }
                carrots.set(i, entry);
                continue;
            }
            if (ticks < launch) {
                // Still in the stash: ride at the rabbit's
                // head, and the flight will start from here.
                double headX = caster.getX();
                double headY = caster.getY() + 0.8;
                double headZ = caster.getZ();
                found.snapTo(headX, headY, headZ, 0.0f, 0.0f);
                entry.putDouble("sx", headX);
                entry.putDouble("sy", headY);
                entry.putDouble("sz", headZ);
                carrots.set(i, entry);
                continue;
            }
            double progress = Math.min(1.0, (ticks - launch + 1) / (double) FLIGHT_TICKS);
            double sx = entry.getDoubleOr("sx", caster.getX());
            double sy = entry.getDoubleOr("sy", caster.getY());
            double sz = entry.getDoubleOr("sz", caster.getZ());
            double lx = entry.getDoubleOr("lx", sx);
            double ly = entry.getDoubleOr("ly", sy);
            double lz = entry.getDoubleOr("lz", sz);
            double x = sx + (lx - sx) * progress;
            double z = sz + (lz - sz) * progress;
            double y = sy + (ly + 0.25 - sy) * progress + Math.sin(progress * Math.PI) * ARC_HEIGHT;
            found.snapTo(x, y, z, 0.0f, 0.0f);
            if (progress >= 1.0) {
                land(ctx, level, entry, lx, ly, lz);
            }
            carrots.set(i, entry);
        }
        ctx.state().put("carrots", carrots);
    }

    /** One carrot's landing: the thump, the damage, and the slow (see the class comment). */
    private static void land(AbilityContext ctx, ServerLevel level, CompoundTag entry,
                             double lx, double ly, double lz) {
        entry.putInt("landed", 1);
        entry.putInt("land_tick", ctx.ticksActive());
        level.sendParticles(ParticleTypes.POOF, lx, ly + 0.3, lz, 4, 0.3, 0.2, 0.3, 0.05);
        List<LivingEntity> hit = RabbitAbilitySupport.enemiesInBox(ctx, lx, ly, lz,
            LAND_RADIUS, 1.0, 2.0);
        for (LivingEntity victim : hit) {
            victim.hurtServer(level, level.damageSources().mobAttack(ctx.mob()), LAND_DAMAGE);
            victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 0));
        }
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
        ListTag carrots = ctx.state().getListOrEmpty("carrots");
        for (int i = 0; i < carrots.size(); i++) {
            Entity found = RabbitAbilitySupport.resolveTracked(level, carrots.getCompoundOrEmpty(i));
            if (found != null) {
                found.discard();
            }
        }
    }
}
