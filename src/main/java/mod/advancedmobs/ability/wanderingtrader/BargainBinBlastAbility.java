package mod.advancedmobs.ability.wanderingtrader;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.List;

/**
 * Bargain Bin Blast (Wandering Trader ability 5 of 5, attack).
 *
 * WHAT IT DOES: the trader kicks over its bargain bin and
 * everything in it goes off at once. A flash and a bang at
 * the trader's feet, a spray of junk merchandise in every
 * direction, and a heavy knockback wave that scatters the
 * whole enemy group. The blast is more shove than harm: it
 * buys the trader the room its getaway needs.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the trader's current target within 8 blocks (distance
 *   squared 64) AND at least 2 enemies within 5.0 blocks of
 *   the trader (one bounded query in canTrigger, the Hero
 *   of the Village Rally precedent): this is a crowd-clearing
 *   blast and is not wasted on a lone enemy.
 * - The blast (start(), one shot): the trader first gains a
 *   transient EXPLOSION_KNOCKBACK_RESISTANCE modifier of
 *   +1.0 ADD_VALUE under a fixed id (the design's
 *   "explosion_knockback_resistance check" line; the blast
 *   is code, so the trader is simply never in its own victim
 *   list, and the modifier is the belt-and-braces guarantee
 *   that no explosion, its own or another's, moves it during
 *   the activation). end() removes it exactly. The look: 1
 *   EXPLOSION_EMITTER plus 12 EXPLOSION particles, junk ITEM
 *   particles sprayed from the package stock list (3 each of
 *   the first 6 GOODS entries, 18 total), 10 POOF, and
 *   GENERIC_EXPLODE at volume 1.2, pitch 0.9.
 * - The wave (start()): one bounded query, every enemy
 *   within 5.0 blocks of the trader (vertical reach 2.0,
 *   package enemy rule) is hit through an explosion-typed
 *   damage source (DamageSources.explosion with the trader
 *   as both the source and the causer, the design's "/damage
 *   explosion type in code" line): 4.0 damage within 2.5
 *   blocks, 2.0 damage out to 5.0. The shove (the design's
 *   "attack_knockback style impulse via Motion changes in
 *   code"): each victim's velocity is set to point directly
 *   away from the trader, horizontal speed 1.6 scaled by
 *   (1 - distance / 5.5) with a floor of 0.4, plus 0.5
 *   straight up, and hurtMarked is set so the launch syncs.
 *   A victim standing exactly on the trader is shoved along
 *   the trader's facing. There is NO block damage: the
 *   blast is a code impulse, never a Level.explode call, so
 *   the mobGriefing gate does not apply to it.
 * - The settle (ticks 1 to 20): the bin keeps dribbling: 2
 *   ITEM particles of random stock entries every 2 ticks at
 *   the blast center (recorded in start(), since the trader
 *   may already be running), plus 1 POOF every 4 ticks.
 * - Duration: 20 ticks, hard finish. end() removes the
 *   knockback resistance modifier and nothing else.
 *
 * WHAT IT LOOKS LIKE: the bin goes over with a bang and a
 * flash, dye pouches and saplings and a bucket pinwheeling
 * out through the smoke, and every enemy around the trader
 * is suddenly flying backwards in a ring while the trader
 * stands untouched at the center of its own mess.
 *
 * PERFORMANCE: canTrigger is one distance check plus one
 * bounded query. start() is one bounded query and a fixed
 * particle set. tick() is 2 to 3 particle sends every 2
 * ticks. Particle budget per activation: 41 at start, at
 * most 25 of settle, total at most 66 particles. No per-tick
 * allocations (the stock particle options are the package's
 * pre-built finals).
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply (see the wave note above).
 *
 * State tag keys: "bx", "by", "bz" (the blast center, the
 * trader's position at the kick).
 */
public final class BargainBinBlastAbility implements MobAbility {

    private static final int DURATION_TICKS = 20;
    private static final double BLAST_RADIUS = 5.0;
    private static final double INNER_RADIUS = 2.5;
    private static final double INNER_DAMAGE = 4.0;
    private static final double OUTER_DAMAGE = 2.0;
    private static final double SHOVE_SPEED = 1.6;
    private static final double SHOVE_UP = 0.5;
    private static final Identifier BLAST_KB_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "bargain_bin_blast_kb");

    @Override
    public String id() {
        return "bargain_bin_blast";
    }

    @Override
    public String displayName() {
        return "Bargain Bin Blast";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null || ctx.mob().distanceToSqr(target) > 64.0) {
            return false;
        }
        return WanderingTraderAbilitySupport.enemiesAroundSelf(ctx, BLAST_RADIUS, 2.0, 2.0).size() >= 2;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();
        ctx.state().putDouble("bx", caster.getX());
        ctx.state().putDouble("by", caster.getY());
        ctx.state().putDouble("bz", caster.getZ());

        // The trader braces against its own blast (see the class comment).
        WanderingTraderAbilitySupport.addModifier(caster, Attributes.EXPLOSION_KNOCKBACK_RESISTANCE,
            new AttributeModifier(BLAST_KB_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));

        if (level == null) {
            return;
        }
        double x = caster.getX();
        double y = caster.getY();
        double z = caster.getZ();

        // The look: flash, bang, and the whole bin in the air.
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, x, y + 0.8, z, 1, 0.0, 0.0, 0.0, 0.0);
        level.sendParticles(ParticleTypes.EXPLOSION, x, y + 0.8, z, 12, 0.8, 0.5, 0.8, 0.1);
        for (int i = 0; i < 6; i++) {
            level.sendParticles(WanderingTraderAbilitySupport.GOOD_PARTICLES[i],
                x, y + 1.0, z, 3, 0.7, 0.5, 0.7, 0.15);
        }
        level.sendParticles(ParticleTypes.POOF, x, y + 0.8, z, 10, 0.8, 0.5, 0.8, 0.08);
        Holder<SoundEvent> explode = SoundEvents.GENERIC_EXPLODE;
        level.playSound(null, x, y, z, explode, SoundSource.HOSTILE, 1.2f, 0.9f);

        // The wave: light damage, heavy shove (see the class comment).
        List<LivingEntity> caught = WanderingTraderAbilitySupport.enemiesInBox(ctx, x, y, z,
            BLAST_RADIUS, 2.0, 2.0);
        for (LivingEntity victim : caught) {
            double distance = Math.sqrt(caster.distanceToSqr(victim));
            double damage = distance <= INNER_RADIUS ? INNER_DAMAGE : OUTER_DAMAGE;
            victim.hurtServer(level, level.damageSources().explosion(caster, caster), (float) damage);
            double awayX = victim.getX() - x;
            double awayZ = victim.getZ() - z;
            double awayLength = Math.sqrt(awayX * awayX + awayZ * awayZ);
            if (awayLength < 1.0e-4) {
                // Standing on the trader: shove along its facing.
                awayX = caster.getLookAngle().x;
                awayZ = caster.getLookAngle().z;
                awayLength = Math.sqrt(awayX * awayX + awayZ * awayZ);
                if (awayLength < 1.0e-4) {
                    awayX = 1.0;
                    awayZ = 0.0;
                    awayLength = 1.0;
                }
            }
            double scale = Math.max(0.4, 1.0 - distance / 5.5);
            victim.setDeltaMovement(awayX / awayLength * SHOVE_SPEED * scale, SHOVE_UP,
                awayZ / awayLength * SHOVE_SPEED * scale);
            victim.hurtMarked = true;
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        // The settle: the kicked bin keeps dribbling junk.
        double x = ctx.state().getDoubleOr("bx", ctx.mob().getX());
        double y = ctx.state().getDoubleOr("by", ctx.mob().getY());
        double z = ctx.state().getDoubleOr("bz", ctx.mob().getZ());
        if (ticks % 2 == 0) {
            int first = ctx.random().nextInt(WanderingTraderAbilitySupport.GOODS.length);
            int second = ctx.random().nextInt(WanderingTraderAbilitySupport.GOODS.length);
            level.sendParticles(WanderingTraderAbilitySupport.GOOD_PARTICLES[first],
                x, y + 0.7, z, 1, 0.5, 0.3, 0.5, 0.05);
            level.sendParticles(WanderingTraderAbilitySupport.GOOD_PARTICLES[second],
                x, y + 0.7, z, 1, 0.5, 0.3, 0.5, 0.05);
        }
        if (ticks % 4 == 0) {
            level.sendParticles(ParticleTypes.POOF, x, y + 0.5, z, 1, 0.5, 0.3, 0.5, 0.04);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        WanderingTraderAbilitySupport.removeModifier(ctx.mob(), Attributes.EXPLOSION_KNOCKBACK_RESISTANCE,
            BLAST_KB_ID);
    }
}
