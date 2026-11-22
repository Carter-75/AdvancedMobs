package mod.advancedmobs.ability.parrot;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.parrot.Parrot;

import java.util.List;

/**
 * Death Squawk Revenge (Parrot ability 5 of 5, defense / attack).
 *
 * WHAT IT DOES: the parrot carries a live wind charge
 * packed into its feathers, shown by occasional gust puffs
 * leaking off its wings. While the charge is carried the
 * bird fights as normal. When it dies, the charge
 * detonates in a wind burst that hurls enemies away in an
 * explosion of feathers, and two angry parrots burst from
 * the blast to avenge it.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the parrot's current target within 16 blocks (distance
 *   squared 256).
 * - The charge (start()): the caster gains the real
 *   WIND_CHARGED effect (amplifier 0) for 620 ticks, the
 *   design's wind_charged line. A PARROT_AMBIENT plays at
 *   start (volume 1.2, pitch 0.9). The effect matters twice:
 *   it is the visible tell's backing state, and vanilla
 *   itself fires a wind burst when a wind-charged entity
 *   dies, which stacks with the code burst below exactly
 *   as the design's detonation describes.
 * - The tell (tick(), every 20 ticks): 2 GUST and 1
 *   SMALL_GUST particles puff off the parrot's body, the
 *   design's occasional gust puffs. Every 100 ticks a
 *   PARROT_FLY sound (volume 0.8, pitch 1.2) marks the
 *   wings shedding the pressure.
 * - Duration: 600 ticks (30 seconds) if the parrot lives.
 *   A natural finish simply disarms the charge: end()
 *   removes the WIND_CHARGED effect and nothing bursts.
 * - THE BURST (end(), death path only): when end() runs
 *   through the framework termination path with the caster
 *   genuinely dead or dying (isDeadOrDying, so a plain
 *   removal or discard never detonates), and the burst has
 *   not already fired (the "burst_done" state flag, the
 *   Creeper detonation flag precedent; end() stays
 *   idempotent), the charge goes off at the corpse:
 *   - Visuals and sound: 24 feather ITEM particles, 12
 *     GUST particles, and 1 GUST_EMITTER_SMALL burst at the
 *     corpse, with the PARROT_DEATH sound at volume 1.5.
 *   - The hurl: every enemy within 5.0 blocks (box reach
 *     2.5 below and 2.5 above the corpse) is hurled
 *     directly away from the corpse at 1.5 blocks per tick
 *     horizontal and 0.65 up, velocity written outright
 *     with hurtMarked set so the launch syncs (the Warden
 *     pattern). The code burst deals no direct damage: the
 *     hurl and the fall it causes are the payload, on top
 *     of vanilla's own wind-charged death burst from the
 *     real effect.
 *   - The avengers: 2 parrots burst from the blast at the
 *     corpse, offset 0.8 blocks to either side, created
 *     with EntitySpawnReason.MOB_SUMMONED and finalizeSpawn.
 *     Team assignment both directions plus SummonerTracker,
 *     the standard summon sheet. Their target is the
 *     caster's kill credit when it is still living, else
 *     the caster's last combat target. Each avenger lands
 *     ONE immediate revenge peck on that target when it is
 *     within 3.5 blocks of the corpse, for the avenger's
 *     live ATTACK_DAMAGE value (3.0 unupgraded) as
 *     mob-attack damage credited to the avenger. The
 *     avengers are PERMANENT: no driver survives the
 *     caster's death to babysit or dismiss them, so after
 *     the one strike they are ordinary parrots in the
 *     world (the Endermite package's permanent squad
 *     precedent). Documented limit: vanilla parrots have
 *     no attack AI, so the revenge is that single strike,
 *     not a hunt.
 * - end() on any non-death path (natural finish, removal):
 *   removes the WIND_CHARGED effect if the caster is still
 *   alive and does nothing else.
 *
 * WHAT IT LOOKS LIKE: a parrot trailing little puffs of
 * wind off its wings for half a minute, and if something
 * kills it, a bang of feathers and swirling gusts, enemies
 * flying backwards, and two furious parrots exploding out
 * of the feathers to take one bite out of the killer.
 *
 * PERFORMANCE: tick() returns immediately except every
 * 20th tick (3 particles) and every 100th (one sound). The
 * burst runs once, ever: one bounded radius-5.0 query, a
 * fixed particle burst, and 2 spawns. Particle budget per
 * activation: tell puffs at most 30 sends times 3 = 90,
 * burst 37, total at most 127, stated cap 130.
 *
 * TERRAIN: the burst is a pure impulse (velocity writes
 * plus particles), never an explosion, so it changes no
 * blocks and the mobGriefing gate does not apply.
 *
 * State tag keys: "burst_done" (1 once the death burst has
 * fired).
 */
public final class DeathSquawkRevengeAbility implements MobAbility {

    private static final int DURATION_TICKS = 600;
    private static final int CHARGE_TICKS = 620;
    private static final int PUFF_INTERVAL = 20;
    private static final double BURST_RADIUS = 5.0;
    private static final double HURL_HORIZONTAL = 1.5;
    private static final double HURL_UP = 0.65;
    private static final int AVENGER_COUNT = 2;
    private static final double AVENGER_PECK_RANGE_SQR = 12.25;

    @Override
    public String id() {
        return "death_squawk_revenge";
    }

    @Override
    public String displayName() {
        return "Death Squawk Revenge";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().putInt("burst_done", 0);
        Mob caster = ctx.mob();
        caster.addEffect(new MobEffectInstance(MobEffects.WIND_CHARGED, CHARGE_TICKS, 0));
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.PARROT_AMBIENT,
                SoundSource.HOSTILE, 1.2f, 0.9f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        if (ticks % PUFF_INTERVAL == 0) {
            level.sendParticles(ParticleTypes.GUST, caster.getX(), caster.getY() + 0.3, caster.getZ(),
                2, 0.3, 0.2, 0.3, 0.05);
            level.sendParticles(ParticleTypes.SMALL_GUST, caster.getX(), caster.getY() + 0.3, caster.getZ(),
                1, 0.3, 0.2, 0.3, 0.05);
        }
        if (ticks % 100 == 0) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.PARROT_FLY,
                SoundSource.HOSTILE, 0.8f, 1.2f);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        // Natural finish or plain removal: just disarm the charge.
        if (!caster.isDeadOrDying()) {
            if (caster.isAlive()) {
                caster.removeEffect(MobEffects.WIND_CHARGED);
            }
            return;
        }
        // Death path: the burst fires exactly once (see the class comment).
        if (ctx.state().getIntOr("burst_done", 0) != 0) {
            return;
        }
        ctx.state().putInt("burst_done", 1);
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        double cx = caster.getX();
        double cy = caster.getY();
        double cz = caster.getZ();

        level.playSound(null, cx, cy, cz, SoundEvents.PARROT_DEATH, SoundSource.HOSTILE, 1.5f, 1.0f);
        level.sendParticles(ParrotAbilitySupport.FEATHER_MOTE, cx, cy + 0.4, cz,
            24, 0.5, 0.4, 0.5, 0.15);
        level.sendParticles(ParticleTypes.GUST, cx, cy + 0.4, cz, 12, 0.5, 0.3, 0.5, 0.1);
        level.sendParticles(ParticleTypes.GUST_EMITTER_SMALL, cx, cy + 0.4, cz, 1, 0.0, 0.0, 0.0, 0.0);

        // The hurl: every enemy in the burst is thrown away from the corpse.
        List<LivingEntity> victims = ParrotAbilitySupport.enemiesInBox(ctx, cx, cy, cz,
            BURST_RADIUS, 2.5, 2.5);
        for (LivingEntity victim : victims) {
            double dx = victim.getX() - cx;
            double dz = victim.getZ() - cz;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 0.001) {
                dx = 1.0;
                dz = 0.0;
                len = 1.0;
            }
            victim.setDeltaMovement((dx / len) * HURL_HORIZONTAL, HURL_UP, (dz / len) * HURL_HORIZONTAL);
            victim.hurtMarked = true;
        }

        // The avengers: two angry parrots out of the blast.
        LivingEntity avengeTarget = caster.getKillCredit();
        if (avengeTarget == null || !avengeTarget.isAlive()) {
            avengeTarget = ctx.target();
        }
        for (int i = 0; i < AVENGER_COUNT; i++) {
            Parrot avenger = EntityTypes.PARROT.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (avenger == null) {
                continue;
            }
            double side = i == 0 ? -0.8 : 0.8;
            avenger.snapTo(cx + side, cy + 0.5, cz, ctx.random().nextFloat() * 360.0f, 0.0f);
            avenger.finalizeSpawn(level, level.getCurrentDifficultyAt(avenger.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
            if (!level.addFreshEntity(avenger)) {
                continue;
            }
            ctx.data().addAlly(avenger.getUUID());
            MobWarData.get(avenger).addAlly(caster.getUUID());
            SummonerTracker.registerSummoned(avenger.getUUID(), caster.getUUID());
            if (avengeTarget != null) {
                avenger.setTarget(avengeTarget);
                // The one revenge peck, landed as the avenger bursts out.
                if (avengeTarget.distanceToSqr(cx, cy, cz) <= AVENGER_PECK_RANGE_SQR) {
                    float damage = (float) avenger.getAttributeValue(Attributes.ATTACK_DAMAGE);
                    avengeTarget.hurtServer(level, level.damageSources().mobAttack(avenger), damage);
                }
            }
            level.playSound(null, cx, cy, cz, SoundEvents.PARROT_AMBIENT, SoundSource.HOSTILE, 1.2f, 0.8f);
        }
    }
}
