package mod.advancedmobs.ability.evoker;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * Delayed Hex Fang (Evoker ability 3 of 5, attack, control).
 *
 * WHAT IT DOES: the evoker points at one enemy and speaks a
 * single word. Ominous motes coil up around the victim and
 * a three second countdown starts that only the victim can
 * see coming. When it expires, a full-sized evoker fang
 * erupts directly beneath the victim wherever they now
 * stand, and the hex lands with it: a dose of instant damage
 * magic that goes through armor. Running does not help,
 * because the fang is placed at the victim's feet at the
 * last moment, not where they were standing when the word
 * was spoken. Only two things cancel it: killing the
 * evoker, or hitting him hard enough to break his
 * concentration before the word completes.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the evoker's current target within 24 blocks (distance
 *   squared 576). The victim is that target, locked in at
 *   start() by UUID; the hex does not retarget.
 * - The countdown: 60 ticks (3 seconds) from start(). The
 *   evoker's health at start() is recorded ("caster_hp").
 *   EVOKER_PREPARE_ATTACK plays at the cast.
 * - The coil (tick(), every 2 ticks): 3 OMINOUS_SPAWNING
 *   motes are sent around the victim at radius 0.7, climbing
 *   from its feet to above its head across the countdown
 *   (height fraction = ticks / 60), so the coil visibly
 *   tightens upward as the word completes. If the victim is
 *   dead or gone the coil simply stops; the hex ends early
 *   (see the finish check below).
 * - The concentration rule (tick(), every 5 ticks): if the
 *   evoker's current health is at or below the recorded
 *   health minus 4.0, his concentration breaks: "cancelled"
 *   is set, 8 OMINOUS_SPAWNING motes burst and scatter at
 *   the victim, EVOKER_PREPARE_SUMMON plays at pitch 0.6
 *   (the word dying in his throat), and the ability ends
 *   with no fang and no damage. Killing the evoker cancels
 *   the hex the other way: the framework termination path
 *   runs end(), and end() deliberately does nothing, so a
 *   dead evoker's hex never fires.
 * - The strike (tick(), at tick 60): if the victim is still
 *   alive, one real vanilla EvokerFangs is spawned at the
 *   victim's CURRENT position, on the ground of its column
 *   (the shared scan from 3 above its block Y down 8; if the
 *   column has no ground, the fang is placed at the victim's
 *   own feet Y), with a warmup of 0, so its bite lands 8
 *   ticks later, at tick 68. The fang position is recorded.
 *   EVOKER_CAST_SPELL plays at the strike.
 * - The hex dose (tick(), at tick 68): if the victim is
 *   alive and within 3.0 blocks of the recorded fang
 *   position, it gains INSTANT_DAMAGE amplifier 1 for 1
 *   tick: 12 magic damage through armor, on top of whatever
 *   the fang's own 6.0 bite did. A victim who somehow left
 *   the spot in those 8 ticks (a knockback, a teleport)
 *   dodges the dose but not retroactively the fang. Vanilla
 *   rule, stated for the record: instant damage HEALS the
 *   undead instead of hurting them, so the dose is wasted
 *   on undead victims; the fang still bites them normally.
 * - Duration: hard finish at tick 80, after the dose. The
 *   ability also finishes early when cancelled, or when the
 *   victim is dead or gone at a 5-tick poll before the
 *   strike (a hex on a corpse is over).
 * - end(): nothing to undo. The fang is a short-lived
 *   vanilla entity; the dose is an instant effect.
 *
 * WHAT IT LOOKS LIKE: a pointed finger and a chanted word,
 * then three seconds of dark motes spiraling up around one
 * very unlucky enemy, faster and higher as the count runs
 * down, and a full-sized fang bursting out of the ground
 * under their feet at the end of it, wrapped in the purple
 * flash of the damage dose.
 *
 * PERFORMANCE: tick() work is one victim UUID resolve plus,
 * every 2nd tick, 3 particles, and every 5th tick one
 * health compare: no entity queries anywhere, no per-tick
 * allocations beyond the resolve. Particle budget per
 * activation: coil 30 sends times 3 = 90, strike burst 6,
 * cancel burst 8, total at most 104 particles.
 *
 * TERRAIN: the fang erupts through the ground but changes
 * no blocks; the mobGriefing gate does not apply.
 *
 * State tag keys: "victim" (the hexed enemy's UUID),
 * "caster_hp" (the evoker's health at cast, the
 * concentration baseline), "cancelled" (1 once concentration
 * breaks), "victim_gone" (1 once a poll finds the victim
 * dead or missing before the strike), "struck" (1 once the
 * fang is spawned), "fang_x", "fang_y", "fang_z" (the fang
 * position the dose is measured from).
 */
public final class DelayedHexFangAbility implements MobAbility {

    private static final int COUNTDOWN_TICKS = 60;
    private static final int DOSE_TICK = 68;
    private static final int FINISH_TICK = 80;
    private static final double CONCENTRATION_DROP = 4.0;
    private static final double DOSE_RADIUS_SQR = 9.0;

    @Override
    public String id() {
        return "delayed_hex_fang";
    }

    @Override
    public String displayName() {
        return "Delayed Hex Fang";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 576.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target != null) {
            ctx.state().store("victim", net.minecraft.core.UUIDUtil.CODEC, target.getUUID());
        }
        ctx.state().putFloat("caster_hp", ctx.mob().getHealth());
        ctx.state().putInt("cancelled", 0);
        ctx.state().putInt("victim_gone", 0);
        ctx.state().putInt("struck", 0);

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.EVOKER_PREPARE_ATTACK, SoundSource.HOSTILE, 1.2f, 1.1f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        LivingEntity victim = EvokerAbilitySupport.resolveByUuid(level, ctx.state(), "victim");

        // The strike: the fang erupts under the victim's
        // CURRENT position when the countdown expires.
        if (ticks == COUNTDOWN_TICKS && ctx.state().getIntOr("cancelled", 0) == 0
            && victim != null && victim.isAlive()) {
            double groundY = EvokerAbilitySupport.findGroundY(level,
                (int) Math.floor(victim.getX()), (int) Math.floor(victim.getZ()),
                (int) Math.floor(victim.getY()) + 3, 8);
            double fangY = Double.isNaN(groundY) ? victim.getY() : groundY;
            EvokerAbilitySupport.spawnFang(ctx, victim.getX(), fangY, victim.getZ(), 0);
            ctx.state().putDouble("fang_x", victim.getX());
            ctx.state().putDouble("fang_y", fangY);
            ctx.state().putDouble("fang_z", victim.getZ());
            ctx.state().putInt("struck", 1);
            Mob caster = ctx.mob();
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.EVOKER_CAST_SPELL, SoundSource.HOSTILE, 1.0f, 1.0f);
            level.sendParticles(ParticleTypes.OMINOUS_SPAWNING, victim.getX(), victim.getY() + 1.0, victim.getZ(),
                6, 0.5, 0.6, 0.5, 0.05);
            return;
        }

        // The dose: lands with the fang's bite, 8 ticks after
        // the strike, on a victim still standing in it.
        if (ticks == DOSE_TICK && ctx.state().getIntOr("struck", 0) == 1
            && victim != null && victim.isAlive()) {
            double fx = ctx.state().getDoubleOr("fang_x", victim.getX());
            double fy = ctx.state().getDoubleOr("fang_y", victim.getY());
            double fz = ctx.state().getDoubleOr("fang_z", victim.getZ());
            double dx = victim.getX() - fx;
            double dy = victim.getY() - fy;
            double dz = victim.getZ() - fz;
            if (dx * dx + dy * dy + dz * dz <= DOSE_RADIUS_SQR) {
                victim.addEffect(new MobEffectInstance(MobEffects.INSTANT_DAMAGE, 1, 1));
            }
            return;
        }

        if (ticks >= COUNTDOWN_TICKS) {
            return; // Cheap early-out: countdown over, only the dose was left.
        }

        // The concentration rule and the dead-victim check,
        // polled every 5 ticks.
        if (ticks % 5 == 0) {
            if (victim == null || !victim.isAlive()) {
                ctx.state().putInt("victim_gone", 1);
                return;
            }
            float baseline = ctx.state().getFloatOr("caster_hp", ctx.mob().getHealth());
            if (ctx.mob().getHealth() <= baseline - CONCENTRATION_DROP) {
                ctx.state().putInt("cancelled", 1);
                level.sendParticles(ParticleTypes.OMINOUS_SPAWNING, victim.getX(), victim.getY() + 1.0, victim.getZ(),
                    8, 0.6, 0.7, 0.6, 0.10);
                Mob caster = ctx.mob();
                level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                    SoundEvents.EVOKER_PREPARE_SUMMON, SoundSource.HOSTILE, 1.0f, 0.6f);
                return;
            }
        }

        // The coil: motes climbing around the victim as the
        // countdown runs down.
        if (ticks % 2 == 0 && victim != null && victim.isAlive()) {
            double height = 0.3 + (ticks / (double) COUNTDOWN_TICKS) * (victim.getBbHeight() + 0.6);
            double angle = ticks * 0.55;
            for (int i = 0; i < 3; i++) {
                double a = angle + i * (Math.PI * 2.0 / 3.0);
                level.sendParticles(ParticleTypes.OMINOUS_SPAWNING,
                    victim.getX() + Math.cos(a) * 0.7, victim.getY() + height, victim.getZ() + Math.sin(a) * 0.7,
                    1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= FINISH_TICK
            || ctx.state().getIntOr("cancelled", 0) == 1
            || ctx.state().getIntOr("victim_gone", 0) == 1;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to undo, deliberately: a killed evoker's
        // hex must never fire, and the termination path runs
        // this same end() (see the class comment).
    }
}
