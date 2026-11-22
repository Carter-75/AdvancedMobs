package mod.advancedmobs.ability.guardian;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.Blocks;

/**
 * Prismarine Spike Mantle (Guardian ability 1 of 5, defense,
 * transformation).
 *
 * WHAT IT DOES: the guardian flares its spikes to full extension
 * and locks them there, its body hardening under a sheath of
 * prismarine flecks as its ARMOR climbs. Anything that strikes it
 * in melee eats reflected spike damage and a burst of CRIT
 * particles; projectiles glance off with ELECTRIC_SPARK snaps and
 * do it no extra harm beyond their hit. The mantle sheds after
 * several solid hits, the spikes retracting as the defense runs
 * out.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   guardian's current target within 10 blocks (distance squared
 *   100): the mantle is a close-fight defense, not a sniping one.
 * - Duration: at most 400 ticks (20 seconds) from start(), ending
 *   early the moment the 6th solid hit is counted (see below).
 * - The hardening, transient attribute modifiers added in start()
 *   and removed in end(), ids under advancedmobs:spike_mantle_*:
 *     ARMOR           add_value +10.0 (a guardian's base armor is
 *                     0, so 10 total while mantled: 40 percent off
 *                     most physical hits before toughness)
 *     ARMOR_TOUGHNESS add_value +4.0 (half a diamond set's
 *                     toughness, so heavy hits keep a fair share
 *                     of the armor reduction)
 *   These stack with the guardian's vanilla spike thorns (2
 *   damage to melee attackers), which stay untouched.
 * - The extended spikes (the design's custom render state): in
 *   vanilla, a guardian's spikes retract while its "moving" flag
 *   is set and stand extended while it is clear. While the mantle
 *   lasts, tick() holds that flag clear with
 *   Guardian.setMoving(false), so the spikes stay visibly flared
 *   for the whole defense. The flag is a render and flop-sound
 *   flag; the guardian's swim goals keep driving its actual
 *   movement and set the flag again themselves once the ability
 *   ends, so end() does not restore it.
 * - Hit answers: every 5 ticks the ability compares the
 *   guardian's last-hurt-by-mob timestamp with the stored one. A
 *   new timestamp means a hit landed, and EVERY detected hit
 *   counts toward the 6-hit shed limit. The answer itself (the
 *   reflection or the spark snap) fires at most once per 10
 *   ticks, so focus fire sheds the mantle fast without turning
 *   the answers into a particle fountain or a reflect machine
 *   gun; the timestamp still updates on every check, so a hit
 *   swallowed by the answer cooldown is counted but not answered.
 *   The stored timestamp is initialized in start(), so a hit that
 *   landed before the mantle rose is never counted or answered.
 * - Melee answers: when the hit's damage source direct entity is
 *   not a projectile and the attacker (getLastHurtByMob) is a
 *   living enemy, the attacker takes 3.0 reflected damage typed
 *   as thorns credited to the guardian
 *   (level.damageSources().thorns(mob)) and 6 CRIT particles
 *   burst at the attacker's chest.
 * - Projectile answers: when the hit's direct entity is a
 *   Projectile, the shot glances off: 8 ELECTRIC_SPARK particles
 *   snap at the guardian's chest and nothing is reflected (the
 *   shooter is usually far away and the design gives projectiles
 *   no reflection).
 * - Flecks: every 10 ticks, 3 BLOCK_CRUMBLE particles with the
 *   prismarine block state shed off the guardian's body, the
 *   sheath of flecks the design describes.
 *
 * WHAT IT LOOKS AND SOUNDS LIKE: spikes visibly locked at full
 * extension for the whole duration (see the render note above),
 * prismarine flecks crumbling off the body every half second,
 * CRIT bursts on melee attackers, ELECTRIC_SPARK snaps when shots
 * glance off, and a low GUARDIAN_AMBIENT (volume 1.0, pitch 0.7)
 * as the mantle sets.
 *
 * PERFORMANCE: tick() does one setMoving call per tick (a flag
 * write) and returns immediately except every 5th tick (one
 * timestamp read, and only on a new hit one bounded answer) and
 * every 10th tick (3 particles). No entity scans, no allocations
 * in the tick path: the prismarine crumble particle option is a
 * pre-built static final. Particle budget per activation: 10 at
 * start plus at most 40 fleck pulses times 3 = 120, plus at most
 * 6 answers times at most 8 = 48, total at most 178 particles.
 *
 * CLEANUP: end() removes both attribute modifiers by id. If the
 * caster dies mid-mantle the modifiers die with the entity;
 * nothing else was created, so there is no leftover state in the
 * world.
 *
 * State tag keys: "answer_ts" (the last-hurt-by-mob timestamp
 * already seen), "last_answer" (tick of the last fired answer),
 * "hits" (solid hits counted toward the shed limit).
 */
public final class PrismarineSpikeMantleAbility implements MobAbility {

    static final int MAX_DURATION_TICKS = 400;
    static final int SHED_HITS = 6;
    private static final int ANSWER_INTERVAL = 5;
    private static final int ANSWER_COOLDOWN = 10;
    private static final float REFLECT_DAMAGE = 3.0f;
    private static final Identifier ARMOR_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "spike_mantle_armor");
    private static final Identifier TOUGHNESS_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "spike_mantle_toughness");
    private static final BlockParticleOption PRISMARINE_CRUMBLE =
        new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, Blocks.PRISMARINE.defaultBlockState());

    @Override
    public String id() {
        return "prismarine_spike_mantle";
    }

    @Override
    public String displayName() {
        return "Prismarine Spike Mantle";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return ctx.target() != null && ctx.mob().distanceToSqr(ctx.target()) <= 100.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        ctx.state().putInt("answer_ts", mob.getLastHurtByMobTimestamp());
        ctx.state().putInt("last_answer", -ANSWER_COOLDOWN);
        ctx.state().putInt("hits", 0);

        // The hardening: armor and toughness for the duration.
        AttributeInstance armor = mob.getAttribute(Attributes.ARMOR);
        if (armor != null) {
            armor.addOrUpdateTransientModifier(
                new AttributeModifier(ARMOR_ID, 10.0, AttributeModifier.Operation.ADD_VALUE));
        }
        AttributeInstance toughness = mob.getAttribute(Attributes.ARMOR_TOUGHNESS);
        if (toughness != null) {
            toughness.addOrUpdateTransientModifier(
                new AttributeModifier(TOUGHNESS_ID, 4.0, AttributeModifier.Operation.ADD_VALUE));
        }

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            // The sheath sets: prismarine flecks burst off the body.
            level.sendParticles(PRISMARINE_CRUMBLE, mob.getX(), mob.getY() + 0.6, mob.getZ(),
                10, 0.6, 0.5, 0.6, 0.1);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.GUARDIAN_AMBIENT, SoundSource.HOSTILE, 1.0f, 0.7f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob mob = ctx.mob();

        // The flared spikes: hold the vanilla moving flag clear so
        // the spikes stay at full extension for the whole mantle
        // (see the render note in the class comment).
        if (mob instanceof Guardian guardian) {
            guardian.setMoving(false);
        }

        int ticks = ctx.ticksActive();
        boolean answerTick = ticks % ANSWER_INTERVAL == 0;
        boolean fleckTick = ticks % 10 == 0;
        if (!answerTick && !fleckTick) {
            return; // Cheap early-out: work runs on the 5 and 10 tick marks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }

        if (fleckTick) {
            // Prismarine flecks shedding off the hardened body.
            level.sendParticles(PRISMARINE_CRUMBLE, mob.getX(), mob.getY() + 0.6, mob.getZ(),
                3, 0.6, 0.5, 0.6, 0.06);
        }

        if (answerTick) {
            // Hit counting and answers: a fresh timestamp is a hit.
            int timestamp = mob.getLastHurtByMobTimestamp();
            if (timestamp != ctx.state().getIntOr("answer_ts", -1)) {
                ctx.state().putInt("answer_ts", timestamp);
                ctx.state().putInt("hits", ctx.state().getIntOr("hits", 0) + 1);
                if (ticks - ctx.state().getIntOr("last_answer", 0) >= ANSWER_COOLDOWN) {
                    answerHit(ctx, level);
                    ctx.state().putInt("last_answer", ticks);
                }
            }
        }
    }

    /**
     * Answers one landed hit: melee attackers eat the reflected
     * spikes, projectiles glance off in sparks. See the class
     * comment for the exact split.
     */
    private void answerHit(AbilityContext ctx, ServerLevel level) {
        Mob mob = ctx.mob();
        DamageSource source = mob.getLastDamageSource();
        Entity direct = source != null ? source.getDirectEntity() : null;
        if (direct instanceof Projectile) {
            // The shot glances off the locked spikes.
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, mob.getX(), mob.getY() + 0.6, mob.getZ(),
                8, 0.5, 0.5, 0.5, 0.12);
            return;
        }
        LivingEntity attacker = mob.getLastHurtByMob();
        if (attacker != null && GuardianAbilitySupport.isEnemy(ctx, attacker)) {
            // The spikes answer in kind, on top of the vanilla
            // thorns the guardian already has.
            attacker.hurtServer(level, level.damageSources().thorns(mob), REFLECT_DAMAGE);
            level.sendParticles(ParticleTypes.CRIT, attacker.getX(), attacker.getY() + 1.0, attacker.getZ(),
                6, 0.4, 0.5, 0.4, 0.1);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= MAX_DURATION_TICKS
            || ctx.state().getIntOr("hits", 0) >= SHED_HITS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        AttributeInstance armor = mob.getAttribute(Attributes.ARMOR);
        if (armor != null) {
            armor.removeModifier(ARMOR_ID);
        }
        AttributeInstance toughness = mob.getAttribute(Attributes.ARMOR_TOUGHNESS);
        if (toughness != null) {
            toughness.removeModifier(TOUGHNESS_ID);
        }
    }
}
