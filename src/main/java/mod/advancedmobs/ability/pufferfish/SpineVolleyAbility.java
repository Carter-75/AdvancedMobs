package mod.advancedmobs.ability.pufferfish;

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
import net.minecraft.world.entity.animal.fish.Pufferfish;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.UUID;

/**
 * Spine Volley (Pufferfish ability 3 of 5, attack).
 *
 * WHAT IT DOES: still inflated, the pufferfish
 * shudders once and fires a ring of its own spines in
 * every direction at once, twelve barbs streaking
 * outward through the water. Each spine is a real
 * projectile that pierces for real damage, and where
 * a spine lands or sticks it leaks tetrodotoxin into
 * the water around it, so a surrounded pufferfish
 * stops being cornered prey and becomes the most
 * dangerous thing in the pool.
 *
 * MECHANICS (the spec; fix bugs against these
 * numbers):
 * - Trigger (canTrigger): the framework's combat
 *   gate, plus the pufferfish's current target within
 *   14 blocks (distance squared 196), plus the
 *   pufferfish in water (the volley is an underwater
 *   ring; spines shed on land would just flop).
 * - The inflation (start()): the design says the
 *   volley fires "still inflated", and abilities
 *   never stack in this system, so the volley does
 *   its own quick inflation: the puff state found is
 *   recorded ("puff_before"), the real synced puff
 *   state is forced to Pufferfish.STATE_FULL, and
 *   PUFFER_FISH_BLOW_UP plays at volume 1.0, pitch
 *   1.2. No scale or armor kit rides along; that is
 *   Full Inflation's whole activation, this one is
 *   just the shudder. end() restores the recorded
 *   puff state.
 * - The volley (start(), all twelve at once): each
 *   spine is a real vanilla Arrow owned by the
 *   pufferfish (new Arrow(level, caster, an arrow
 *   stack, a bow stack), the Bogged package's
 *   construction), pickup DISALLOWED so it never
 *   becomes loot, base damage 2.0, shot from the
 *   fish's eye position at speed 1.8 with no
 *   inaccuracy. Directions are a flat ring: one spine
 *   every 30 degrees of yaw, with the vertical
 *   component cycling 0.0, +0.25, -0.25 by spine
 *   index, so the ring fans slightly up and down
 *   instead of running in one plane. Because the
 *   arrows are real projectiles owned by the fish,
 *   a spine that hits deals vanilla arrow damage
 *   through the projectile damage type credited to
 *   the pufferfish, which is the design's "/damage
 *   with projectile damage type" line; the ability
 *   code itself deals no hit damage. Water drag is
 *   heavy on arrows, so the ring is short ranged,
 *   about 4 to 6 blocks: a point blank answer to
 *   being surrounded, exactly as designed.
 * - The toxin leak (tick(), per spine): every spine
 *   is tracked in the state tag ("spines": "id",
 *   last known "lx", "ly", "lz", "stall", "done").
 *   While a spine flies, its last position updates
 *   each tick and it trails 1 CRIT particle every 2
 *   ticks. When a spine vanishes (it hit something
 *   and was consumed, burned, or left the world) or
 *   stalls (two consecutive near-zero-movement ticks
 *   after tick 3, the Sporeburst Arrow stuck check),
 *   it bursts at its last known position: one bounded
 *   query, radius 1.6, gives every enemy there (the
 *   package enemy rule) POISON I for 80 ticks, with
 *   4 CRIT plus 3 pufferfish SPINE_FLECK item
 *   particles at the point. A stalled spine is
 *   discarded as it bursts so it does not sit in a
 *   block as a plain arrow. A spine that hit an enemy
 *   directly is consumed by vanilla at the hit, so
 *   its burst lands on the victim's position and the
 *   victim takes the arrow's piercing hit and the
 *   poison together, which is the design's "each
 *   spine carries a short poison" carried by the
 *   mod's established arrow tracking pattern.
 * - The activation finishes when every spine has
 *   burst, with a hard cap of 60 ticks.
 *
 * WHAT IT LOOKS LIKE: the fish balloons, shudders,
 * and a halo of barbs streaks outward trailing crit
 * sparks, each one popping in a little spray of
 * spine sprites and green poison wherever it lands.
 *
 * PERFORMANCE: the volley is 12 entity spawns once
 * in start(). Per tick the tracking pass resolves at
 * most 12 UUIDs and writes at most 12 positions;
 * bursts are one bounded radius-1.6 query each, at
 * most 12 per activation. Particle budget per
 * activation: trails at most 12 spines times 30 sends
 * = 360 theoretical, realistically far less since
 * water drag stops spines fast; bursts at most 12
 * times 7 = 84; stated cap 450.
 *
 * TERRAIN: this ability changes no blocks; the
 * spines are ordinary arrows whose impacts never
 * alter blocks, so the mobGriefing gate does not
 * apply.
 *
 * CLEANUP: end() discards every still-flying spine
 * WITHOUT bursting it (a terminated volley leaves no
 * stray arrows and no free poison) and restores the
 * recorded puff state. Victim poison rides its own
 * 80-tick clock out.
 *
 * State tag keys: "puff_before" (the puff state found
 * at start), "spines" (list of compounds: "id",
 * "lx", "ly", "lz", "stall", "done", one per spine).
 */
public final class SpineVolleyAbility implements MobAbility {

    private static final int SPINE_COUNT = 12;
    private static final int HARD_CAP_TICKS = 60;
    private static final double SPINE_BASE_DAMAGE = 2.0;
    private static final float SPINE_SPEED = 1.8f;
    private static final double BURST_RADIUS = 1.6;
    private static final double STUCK_MOVE_SQR = 0.0001;

    @Override
    public String id() {
        return "spine_volley";
    }

    @Override
    public String displayName() {
        return "Spine Volley";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().isInWater()
            && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        state.put("spines", new ListTag());
        if (caster instanceof Pufferfish pufferfish) {
            state.putInt("puff_before", pufferfish.getPuffState());
            pufferfish.setPuffState(Pufferfish.STATE_FULL);
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.PUFFER_FISH_BLOW_UP, SoundSource.HOSTILE, 1.0f, 1.2f);

        // The ring: twelve real arrows, one every 30
        // degrees, fanning slightly up and down.
        for (int i = 0; i < SPINE_COUNT; i++) {
            double angle = Math.toRadians(i * 30.0);
            double dx = Math.cos(angle);
            double dz = Math.sin(angle);
            double dy = switch (i % 3) {
                case 1 -> 0.25;
                case 2 -> -0.25;
                default -> 0.0;
            };
            Arrow spine = new Arrow(level, caster, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
            spine.pickup = AbstractArrow.Pickup.DISALLOWED;
            spine.setBaseDamage(SPINE_BASE_DAMAGE);
            spine.snapTo(caster.getX(), caster.getEyeY(), caster.getZ(),
                (float) Math.toDegrees(Math.atan2(-dx, dz)), 0.0f);
            spine.shoot(dx, dy, dz, SPINE_SPEED, 0.0f);
            level.addFreshEntity(spine);

            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, spine.getUUID());
            entry.putDouble("lx", spine.getX());
            entry.putDouble("ly", spine.getY());
            entry.putDouble("lz", spine.getZ());
            entry.putInt("stall", 0);
            entry.putInt("done", 0);
            ListTag spines = state.getListOrEmpty("spines");
            spines.add(entry);
            state.put("spines", spines);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        int ticks = ctx.ticksActive();
        ListTag spines = state.getListOrEmpty("spines");
        for (int i = 0; i < spines.size(); i++) {
            CompoundTag entry = spines.getCompoundOrEmpty(i);
            if (entry.getIntOr("done", 0) != 0) {
                continue;
            }
            trackSpine(ctx, level, entry, ticks);
        }
        state.put("spines", spines);
    }

    /** Follows one spine: trail while it flies, toxin burst when it lands, sticks, or vanishes. */
    private void trackSpine(AbilityContext ctx, ServerLevel level, CompoundTag entry, int ticks) {
        UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
        Entity spine = id != null ? level.getEntity(id) : null;
        double lx = entry.getDoubleOr("lx", ctx.mob().getX());
        double ly = entry.getDoubleOr("ly", ctx.mob().getY());
        double lz = entry.getDoubleOr("lz", ctx.mob().getZ());

        if (spine == null) {
            // Hit something and was consumed, or left the
            // world: the toxin leaks where it was last seen.
            burst(ctx, level, entry, lx, ly, lz);
            return;
        }

        double mdx = spine.getX() - lx;
        double mdy = spine.getY() - ly;
        double mdz = spine.getZ() - lz;
        double movedSqr = mdx * mdx + mdy * mdy + mdz * mdz;
        entry.putDouble("lx", spine.getX());
        entry.putDouble("ly", spine.getY());
        entry.putDouble("lz", spine.getZ());
        if (ticks >= 3 && movedSqr < STUCK_MOVE_SQR) {
            int stall = entry.getIntOr("stall", 0) + 1;
            entry.putInt("stall", stall);
            if (stall >= 2) {
                // Stuck in a block: burst here and take the
                // spine with the leak.
                burst(ctx, level, entry, spine.getX(), spine.getY(), spine.getZ());
                spine.discard();
                return;
            }
        } else {
            entry.putInt("stall", 0);
        }

        // The streaking contrail.
        if (ticks % 2 == 0) {
            level.sendParticles(ParticleTypes.CRIT, spine.getX(), spine.getY(), spine.getZ(),
                1, 0.05, 0.05, 0.05, 0.0);
        }
    }

    /** Leaks the toxin at the impact point: poison for every enemy close, and the spray. */
    private void burst(AbilityContext ctx, ServerLevel level, CompoundTag entry,
                       double x, double y, double z) {
        entry.putInt("done", 1);
        List<LivingEntity> victims = PufferfishAbilitySupport.enemiesInBox(
            ctx, x, y, z, BURST_RADIUS, 1.2, 1.2);
        for (LivingEntity victim : victims) {
            victim.addEffect(new MobEffectInstance(MobEffects.POISON, 80, 0));
        }
        level.sendParticles(ParticleTypes.CRIT, x, y, z,
            4, 0.3, 0.3, 0.3, 0.08);
        level.sendParticles(PufferfishAbilitySupport.SPINE_FLECK, x, y, z,
            3, 0.3, 0.3, 0.3, 0.08);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        if (ctx.ticksActive() >= HARD_CAP_TICKS) {
            return true;
        }
        ListTag spines = ctx.state().getListOrEmpty("spines");
        for (int i = 0; i < spines.size(); i++) {
            if (spines.getCompoundOrEmpty(i).getIntOr("done", 0) == 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void end(AbilityContext ctx) {
        // A terminated volley leaves nothing behind:
        // still-flying spines are discarded unburst.
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            ListTag spines = ctx.state().getListOrEmpty("spines");
            for (int i = 0; i < spines.size(); i++) {
                CompoundTag entry = spines.getCompoundOrEmpty(i);
                if (entry.getIntOr("done", 0) != 0) {
                    continue;
                }
                Entity spine = PufferfishAbilitySupport.resolveTracked(level, entry);
                if (spine != null) {
                    spine.discard();
                }
            }
        }
        Mob caster = ctx.mob();
        if (caster instanceof Pufferfish pufferfish) {
            pufferfish.setPuffState(ctx.state().getIntOr("puff_before", Pufferfish.STATE_SMALL));
        }
    }
}
