package mod.advancedmobs.ability.spider;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.List;

/**
 * Web Fortress (Spider ability 3 of 5, terrain).
 *
 * WHAT IT DOES: the spider weaves a fortress in seconds: real
 * cobweb blocks string across the gaps and doorways around it
 * in a layered lattice, ITEM_COBWEB motes drifting off the
 * fresh silk. For the duration the spider carries WEAVING and
 * skims across its own webs nearly unhindered, while enemies
 * pushing into the fortress are slowed to a crawl and announced
 * by silk flecks bursting off them as they struggle.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   spider's current target within 12 blocks (distance squared
 *   144). The fortress is woven around a fight in progress.
 * - The weaving (start(), mobGriefing mode only): the perimeter
 *   of the 7 by 7 square centered on the spider (Chebyshev
 *   radius 3, 24 positions) at the spider's feet level, and the
 *   same perimeter 1 block up with a checkerboard skip (only
 *   positions where x + z is even, about 12), at most 36
 *   candidate positions. A candidate is webbed only when the
 *   shared safety checks pass (replaceable, no fluid, no block
 *   entity, not unbreakable) AND the web would hang on something
 *   (SpiderAbilitySupport.hasWebSupport: a sturdy top below or
 *   a solid horizontal neighbor), so the lattice strings across
 *   gaps and doorways instead of floating in open air. The webs
 *   are PERMANENT terrain. With mobGriefing off, no block is
 *   placed and the ability is the buff and debuff zone below.
 * - The spider's own ease, for the full 240 ticks: WEAVING
 *   (amplifier 0, 245 ticks, refreshed once at start; weaving is
 *   the vanilla effect that lets its bearer cross cobwebs
 *   nearly unhindered) and a transient MOVEMENT_EFFICIENCY
 *   modifier of +1.0 (the same self-buff Bog Mire uses), removed
 *   in end(). The WEAVING effect is timed and expires on its
 *   own.
 * - The struggle, every 10 ticks for 240 ticks: every enemy
 *   within radius 4.5 of the spider (box reach 1.5 below, 2.5
 *   above) gets SLOWNESS I (amplifier 0) for 15 ticks, and the
 *   first 4 of them shed 3 ITEM_COBWEB flecks each: the visible
 *   announcement that something is caught in the fortress. This
 *   runs in both terrain modes.
 * - The opening look: 24 ITEM_COBWEB motes drift over the weave
 *   area at start and a WOOL_PLACE plays at volume 0.8.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6): the lattice is real block
 * placement, so it happens only when
 * AbilityContext.mobGriefingAllowed() is true, and placed webs
 * stay for good. end() never touches them.
 *
 * WHAT IT LOOKS LIKE: a hiss of silk and drifting cobweb motes,
 * then a two-layer lattice of fresh cobweb ringing the spider
 * across every gap and doorway, the spider gliding over its own
 * silk while everything that pushes in moves like it is
 * swimming through glue, shedding silk flecks as it struggles.
 *
 * PERFORMANCE: the weaving is one fixed pass of at most 36
 * position checks, once, at start. The struggle pulse runs every
 * 10 ticks with one bounded entity query (9 by 4 by 9 box) and
 * particle emission capped at 4 enemies. No per-tick work at
 * all outside the pulse. Particle budget per activation:
 * opening 24, pulses 24 times at most 12 = 288, total at most
 * 312 particles.
 *
 * CLEANUP: end() removes the MOVEMENT_EFFICIENCY modifier. The
 * WEAVING effect is timed and expires. The cobwebs are
 * permanent terrain and are deliberately NOT touched.
 *
 * State tag keys: none. The weaving happens entirely in start()
 * and the pulses read only live world state.
 */
public final class WebFortressAbility implements MobAbility {

    static final int DURATION_TICKS = 240;
    private static final int PULSE_INTERVAL = 10;
    private static final int RING_RADIUS = 3;
    private static final Identifier EFFICIENCY_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "web_fortress_efficiency");

    @Override
    public String id() {
        return "web_fortress";
    }

    @Override
    public String displayName() {
        return "Web Fortress";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob mob = ctx.mob();

        // The spider's own ease across its silk.
        mob.addEffect(new MobEffectInstance(MobEffects.WEAVING, DURATION_TICKS + 5, 0));
        AttributeInstance efficiency = mob.getAttribute(Attributes.MOVEMENT_EFFICIENCY);
        if (efficiency != null) {
            efficiency.addOrUpdateTransientModifier(
                new AttributeModifier(EFFICIENCY_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        }

        if (level == null) {
            return;
        }

        // Terrain (mobGriefing mode only): the permanent lattice.
        // With mobGriefing off, no block is touched. See the
        // TERRAIN RULE in the class comment.
        if (ctx.mobGriefingAllowed()) {
            weaveLattice(level, mob.blockPosition());
        }

        level.sendParticles(ParticleTypes.ITEM_COBWEB, mob.getX(), mob.getY() + 0.8, mob.getZ(),
            24, 2.8, 0.8, 2.8, 0.05);
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
            SoundEvents.WOOL_PLACE, SoundSource.HOSTILE, 0.8f, 1.0f);
    }

    /**
     * Weaves the two-layer perimeter lattice (see the class
     * comment for the shape, the checkerboard, and the support
     * rule). Placed webs are permanent: nothing records them for
     * removal.
     */
    private static void weaveLattice(ServerLevel level, BlockPos center) {
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = -RING_RADIUS; dx <= RING_RADIUS; dx++) {
                for (int dz = -RING_RADIUS; dz <= RING_RADIUS; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != RING_RADIUS) {
                        continue; // Perimeter only.
                    }
                    if (dy == 1 && ((dx + dz) & 1) != 0) {
                        continue; // The upper layer is a checkerboard.
                    }
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!SpiderAbilitySupport.hasWebSupport(level, pos)) {
                        continue; // The lattice never floats.
                    }
                    SpiderAbilitySupport.placeCobweb(level, pos);
                }
            }
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % PULSE_INTERVAL != 0) {
            return; // Cheap early-out: the struggle pulse runs every 10 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        List<LivingEntity> victims = SpiderAbilitySupport.enemiesAroundSelf(ctx, 4.5, 1.5, 2.5);
        int announced = 0;
        for (LivingEntity victim : victims) {
            victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 15, 0));
            if (announced < 4) {
                level.sendParticles(ParticleTypes.ITEM_COBWEB,
                    victim.getX(), victim.getY() + 0.8, victim.getZ(),
                    3, 0.4, 0.5, 0.4, 0.06);
                announced++;
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        AttributeInstance efficiency = ctx.mob().getAttribute(Attributes.MOVEMENT_EFFICIENCY);
        if (efficiency != null) {
            efficiency.removeModifier(EFFICIENCY_ID);
        }
        // The WEAVING effect is timed and expires on its own; the
        // cobwebs are permanent terrain and stay.
    }
}
