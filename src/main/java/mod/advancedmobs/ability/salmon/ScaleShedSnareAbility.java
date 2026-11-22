package mod.advancedmobs.ability.salmon;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.List;

/**
 * Scale Shed Snare (Salmon ability 3 of 5, defense /
 * terrain).
 *
 * WHAT IT DOES: when hit hard, the salmon bursts out
 * of a cloud of its own loose scales and leaves the
 * slick patch behind on the ground or in the water.
 * Enemies crossing the patch slide with almost no
 * grip, overshooting their turns, while the salmon
 * escapes with a short speed burst.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the salmon's current target within 12 blocks
 *   (distance squared 144), plus the salmon at or
 *   below 60 percent of its max health: the design's
 *   "when hit hard" read as a health gate, since
 *   canTrigger must be a cheap side-effect free check
 *   and a salmon at 3 health max is below the line
 *   after any solid hit (1.8 health or less). The
 *   salmon does not need to be in water: a beached
 *   salmon sheds its scales on land just as well.
 * - The burst (start()): the patch center is fixed at
 *   the salmon's position right now (state tag, radius
 *   2.5) and the shed goes off in one burst: 12 ITEM
 *   particles of the package's pre-built salmon item
 *   option (the loose scales; the design's item stack
 *   debris, the raw salmon sprite being the closest
 *   vanilla stand-in for a scale), 8 POOF, and 6
 *   BLOCK_CRUMBLE particles of the block state under
 *   the salmon (the design's block state debris), plus
 *   SALMON_HURT at volume 1.0, pitch 1.4. The escape:
 *   the salmon's velocity is set directly away from
 *   its target at 1.1 blocks per tick horizontally
 *   and 0.3 up, hurtMarked, and it gains the design's
 *   SPEED I effect for 100 ticks (removed in end() if
 *   the activation ends first).
 * - Duration: 200 ticks (10 seconds) of slick patch.
 * - The slick (every 10 ticks, one bounded query,
 *   radius 2.5 around the center, vertical reach 2.0
 *   both ways): every enemy (the package enemy rule)
 *   standing in the patch that is not already slicked
 *   gains the design's friction line as a transient
 *   modifier: FRICTION_MODIFIER -1.0 add_value (id
 *   advancedmobs:scale_shed_snare_friction; the
 *   attribute runs 0 to 2048 with default 1, so this
 *   pins a victim's ground friction to 0: ice physics,
 *   no grip, turns overshoot). Victims are tracked in
 *   the state tag (cap 16). The same pulse prunes the
 *   list: a tracked victim that died, unloaded, or
 *   left the patch (beyond 3.0 blocks of the center)
 *   has the modifier removed exactly and is dropped.
 *   Honest scope note: friction_modifier multiplies
 *   ground friction from the block below, so the
 *   slick bites hardest on enemies crossing the patch
 *   along the bottom or on land; a swimmer in open
 *   water above the patch feels little of it. That is
 *   the design's chosen system, applied faithfully.
 * - The shimmer (every 4 ticks): 2 salmon ITEM
 *   particles plus 1 POOF at a random point inside
 *   the patch, so the slick reads as a drifting cloud
 *   of loose scales for its whole life.
 *
 * WHAT IT LOOKS LIKE: a burst of silvery scale debris
 * and dust where the salmon was, a shimmering slick
 * patch hanging in the water or lying on the bank,
 * and enemies skating through it while the salmon is
 * already gone.
 *
 * PERFORMANCE: the burst is one-shot in start(); the
 * slick pulse is one bounded query and a tracked list
 * pass of at most 16 UUIDs every 10 ticks; the
 * shimmer is 3 particles every 4 ticks. Particle
 * budget per activation: 26 at the burst, 50 shimmer
 * pulses times 3 = 150, total at most 176.
 *
 * TERRAIN: this ability changes no blocks; the patch
 * is a zone recorded in the state tag, drawn with
 * particles, so the mobGriefing gate does not apply.
 *
 * CLEANUP: end() removes the friction modifier from
 * every tracked victim still resolvable and removes
 * the SPEED effect from the salmon. The modifiers are
 * transient, so a victim that unloaded mid-activation
 * loses its slick on its own; end() covers everyone
 * still in the world.
 *
 * State tag keys: "cx", "cy", "cz" (the patch's fixed
 * center), "slicked" (list of compounds: "id", one
 * per enemy currently carrying the friction modifier).
 */
public final class ScaleShedSnareAbility implements MobAbility {

    private static final int DURATION_TICKS = 200;
    private static final int PULSE_INTERVAL = 10;
    private static final double PATCH_RADIUS = 2.5;
    private static final double LEAVE_RADIUS = 3.0;
    private static final float TRIGGER_HEALTH_FRACTION = 0.6f;
    private static final int MAX_SLICKED = 16;
    private static final Identifier FRICTION_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "scale_shed_snare_friction");

    @Override
    public String id() {
        return "scale_shed_snare";
    }

    @Override
    public String displayName() {
        return "Scale Shed Snare";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        Mob caster = ctx.mob();
        return target != null
            && caster.distanceToSqr(target) <= 144.0
            && caster.getHealth() <= caster.getMaxHealth() * TRIGGER_HEALTH_FRACTION;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        state.putDouble("cx", caster.getX());
        state.putDouble("cy", caster.getY());
        state.putDouble("cz", caster.getZ());
        state.put("slicked", new ListTag());

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        // The shed: scale debris, dust, and block state debris in one burst.
        level.sendParticles(SalmonAbilitySupport.SCALE_ITEM,
            caster.getX(), caster.getY() + 0.4, caster.getZ(),
            12, 0.4, 0.3, 0.4, 0.08);
        level.sendParticles(ParticleTypes.POOF,
            caster.getX(), caster.getY() + 0.4, caster.getZ(),
            8, 0.4, 0.3, 0.4, 0.05);
        level.sendParticles(SalmonAbilitySupport.groundCrumble(level, caster.getX(), caster.getY(), caster.getZ()),
            caster.getX(), caster.getY() + 0.2, caster.getZ(),
            6, 0.4, 0.2, 0.4, 0.05);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.SALMON_HURT, SoundSource.HOSTILE, 1.0f, 1.4f);

        // The escape: one hard dart away from the target, plus the speed burst.
        LivingEntity target = ctx.target();
        double awayX = 1.0;
        double awayZ = 0.0;
        if (target != null) {
            double dx = caster.getX() - target.getX();
            double dz = caster.getZ() - target.getZ();
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length > 0.001) {
                awayX = dx / length;
                awayZ = dz / length;
            }
        }
        caster.setDeltaMovement(awayX * 1.1, 0.3, awayZ * 1.1);
        caster.hurtMarked = true;
        caster.addEffect(new MobEffectInstance(MobEffects.SPEED, 100, 0, false, false, true));
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        int ticks = ctx.ticksActive();

        if (ticks % 4 == 0) {
            double cx = state.getDoubleOr("cx", caster.getX());
            double cy = state.getDoubleOr("cy", caster.getY());
            double cz = state.getDoubleOr("cz", caster.getZ());
            double angle = ctx.random().nextDouble() * Math.PI * 2.0;
            double radius = ctx.random().nextDouble() * PATCH_RADIUS;
            double px = cx + Math.cos(angle) * radius;
            double pz = cz + Math.sin(angle) * radius;
            level.sendParticles(SalmonAbilitySupport.SCALE_ITEM, px, cy + 0.3, pz,
                2, 0.2, 0.2, 0.2, 0.03);
            level.sendParticles(ParticleTypes.POOF, px, cy + 0.3, pz,
                1, 0.2, 0.2, 0.2, 0.02);
        }

        if (ticks % PULSE_INTERVAL == 0) {
            runSlickPulse(ctx, level);
        }
    }

    /** One slick pulse: slick every enemy standing in the patch, prune the ones that left. */
    private void runSlickPulse(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        double cx = state.getDoubleOr("cx", 0.0);
        double cy = state.getDoubleOr("cy", 0.0);
        double cz = state.getDoubleOr("cz", 0.0);
        ListTag slicked = state.getListOrEmpty("slicked");

        // Prune first: gone, dead, or out of the patch means the grip comes back.
        ListTag kept = new ListTag();
        for (int i = 0; i < slicked.size(); i++) {
            CompoundTag entry = slicked.getCompoundOrEmpty(i);
            Entity resolved = SalmonAbilitySupport.resolveTracked(level, entry);
            if (resolved instanceof LivingEntity victim && victim.isAlive()
                && horizontalDistanceSqr(victim.getX(), victim.getZ(), cx, cz) <= LEAVE_RADIUS * LEAVE_RADIUS) {
                kept.add(entry);
            } else if (resolved instanceof LivingEntity victim) {
                SalmonAbilitySupport.removeModifier(victim, Attributes.FRICTION_MODIFIER, FRICTION_ID);
            }
        }
        state.put("slicked", kept);
        slicked = kept;

        if (slicked.size() >= MAX_SLICKED) {
            return;
        }
        List<LivingEntity> found = SalmonAbilitySupport.enemiesInBox(ctx, cx, cy, cz, PATCH_RADIUS, 2.0, 2.0);
        for (LivingEntity enemy : found) {
            if (slicked.size() >= MAX_SLICKED || alreadySlicked(slicked, enemy)) {
                continue;
            }
            SalmonAbilitySupport.addModifier(enemy, Attributes.FRICTION_MODIFIER,
                new AttributeModifier(FRICTION_ID, -1.0, AttributeModifier.Operation.ADD_VALUE));
            SalmonAbilitySupport.trackEntity(state, "slicked", enemy);
            slicked = state.getListOrEmpty("slicked");
        }
    }

    /** Whether the victim's UUID is already in the slicked list. */
    private boolean alreadySlicked(ListTag slicked, LivingEntity victim) {
        for (int i = 0; i < slicked.size(); i++) {
            CompoundTag entry = slicked.getCompoundOrEmpty(i);
            if (entry.contains("id")
                && victim.getUUID().equals(entry.read("id", UUIDUtil.CODEC).orElse(null))) {
                return true;
            }
        }
        return false;
    }

    /** Horizontal distance squared between two points. */
    private double horizontalDistanceSqr(double x1, double z1, double x2, double z2) {
        double dx = x1 - x2;
        double dz = z1 - z2;
        return dx * dx + dz * dz;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            ListTag slicked = ctx.state().getListOrEmpty("slicked");
            for (int i = 0; i < slicked.size(); i++) {
                Entity resolved = SalmonAbilitySupport.resolveTracked(level, slicked.getCompoundOrEmpty(i));
                if (resolved instanceof LivingEntity victim) {
                    SalmonAbilitySupport.removeModifier(victim, Attributes.FRICTION_MODIFIER, FRICTION_ID);
                }
            }
        }
        ctx.mob().removeEffect(MobEffects.SPEED);
    }
}
