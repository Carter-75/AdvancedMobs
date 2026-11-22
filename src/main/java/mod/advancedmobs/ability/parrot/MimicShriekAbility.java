package mod.advancedmobs.ability.parrot;

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

import java.util.List;

/**
 * Mimic Shriek (Parrot ability 1 of 5, control).
 *
 * WHAT IT DOES: the parrot throws back its head and shrieks
 * a deafening copy of its enemy's war cry back at the whole
 * pack around it, note particles bursting from its beak in
 * a ring. Enemies caught in the shriek are nauseated and
 * weakened, reeling from hearing themselves screamed back
 * at them.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the parrot's current target within 12 blocks (distance
 *   squared 144).
 * - The shriek (start(), one bounded radius query): every
 *   enemy within 6.0 blocks of the parrot (box reach 3.0
 *   below and 3.0 above its body) gains NAUSEA (amplifier
 *   0) for 200 ticks and WEAKNESS I (amplifier 0) for 200
 *   ticks. The enemy rule is the package rule, so nearby
 *   creative or spectator players who are not the target
 *   are never debuffed.
 * - The sound (start()): the mimicry is two layered
 *   PARROT_AMBIENT cries played at the parrot, volume 2.0:
 *   one at pitch 0.7 (the deepened copy) and one at pitch
 *   1.4 (the parrot's own screech on top). HONEST LIMIT,
 *   documented rather than faked: a true per-mob sound copy
 *   is not available server-side in 26.2 (Mob's ambient
 *   sound accessor is protected, and the vanilla parrot
 *   imitation system only picks the parrot's own ambient
 *   sound on the client), so the copy is carried by the
 *   doubled, pitch-shifted shriek and the note ring.
 * - The visuals (start() and tick()): start() fires 1
 *   SONIC_BOOM particle at the parrot's head (the small
 *   shockwave ring of the design) and 12 NOTE particles in
 *   a ring of radius 1.2 at head height. tick() sheds 2
 *   more NOTE particles at the beak every 5 ticks while
 *   the echo lasts.
 * - Duration: 20 ticks of echo, hard finish at 30 ticks.
 *   The debuffs are the payload and ride their own 200
 *   tick clocks.
 * - end(): nothing to reverse. This ability applies no
 *   attribute modifiers and no effects to the caster; the
 *   victims' NAUSEA and WEAKNESS are fixed-duration
 *   effects that expire on their own clocks, the standard
 *   treatment for victim debuffs in this system.
 *
 * WHAT IT LOOKS LIKE: the little bird rears back, a sonic
 * ring pops off its beak inside a spinning ring of musical
 * notes, and a doubled squawk, half deep war cry and half
 * parrot screech, rolls over the pack; everything it
 * touches staggers, sick and weak, while a few last notes
 * drift out of its beak.
 *
 * PERFORMANCE: start() runs one bounded radius-6.0 query
 * and a fixed particle burst; tick() emits 2 particles
 * every 5 ticks and does nothing else. Particle budget per
 * activation: 1 sonic boom plus 12 ring notes plus at most
 * 4 times 2 echo notes = at most 21 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: none beyond the framework's. The shriek
 * is a one-shot in start(); tick() only sheds echo notes
 * and isFinished() reads only the framework tick counter.
 */
public final class MimicShriekAbility implements MobAbility {

    private static final int DURATION_TICKS = 20;
    private static final int HARD_FINISH_TICKS = 30;
    private static final double SHRIEK_RADIUS = 6.0;
    private static final int DEBUFF_TICKS = 200;
    private static final int NOTE_RING_COUNT = 12;
    private static final double NOTE_RING_RADIUS = 1.2;

    @Override
    public String id() {
        return "mimic_shriek";
    }

    @Override
    public String displayName() {
        return "Mimic Shriek";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        double cx = caster.getX();
        double cy = caster.getY();
        double cz = caster.getZ();

        // The doubled shriek: the deepened copy under the
        // parrot's own screech (see the class comment).
        level.playSound(null, cx, cy, cz, SoundEvents.PARROT_AMBIENT, SoundSource.HOSTILE, 2.0f, 0.7f);
        level.playSound(null, cx, cy, cz, SoundEvents.PARROT_AMBIENT, SoundSource.HOSTILE, 2.0f, 1.4f);

        // The shockwave ring and the ring of notes at head height.
        level.sendParticles(ParticleTypes.SONIC_BOOM, cx, cy + 0.5, cz, 1, 0.0, 0.0, 0.0, 0.0);
        for (int i = 0; i < NOTE_RING_COUNT; i++) {
            double angle = (Math.PI * 2.0 * i) / NOTE_RING_COUNT;
            level.sendParticles(ParticleTypes.NOTE,
                cx + Math.cos(angle) * NOTE_RING_RADIUS, cy + 0.6, cz + Math.sin(angle) * NOTE_RING_RADIUS,
                1, 0.0, 0.0, 0.0, 0.0);
        }

        // The payload: nausea and weakness on every enemy in earshot.
        List<LivingEntity> victims = ParrotAbilitySupport.enemiesInBox(ctx, cx, cy, cz,
            SHRIEK_RADIUS, 3.0, 3.0);
        for (LivingEntity victim : victims) {
            victim.addEffect(new MobEffectInstance(MobEffects.NAUSEA, DEBUFF_TICKS, 0));
            victim.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, DEBUFF_TICKS, 0));
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        // The echo: a few last notes drift from the beak every 5 ticks.
        if (ctx.ticksActive() % 5 != 0) {
            return;
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        level.sendParticles(ParticleTypes.NOTE, caster.getX(), caster.getY() + 0.6, caster.getZ(),
            2, 0.3, 0.15, 0.3, 0.05);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS || ctx.ticksActive() >= HARD_FINISH_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to reverse: no caster modifiers or effects
        // were applied (see the class comment).
    }
}
