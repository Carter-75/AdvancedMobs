package mod.advancedmobs.ability.cod;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.List;

/**
 * Slipstream Snatch (Cod ability 2 of 5, mobility / attack).
 *
 * WHAT IT DOES: the cod lines up on its target and
 * rockets down one straight line, a sleeve of rising
 * bubble columns streaming behind it, fast enough to
 * leave a visible current in the water. Anything in
 * the line takes a ramming hit with heavy knockback as
 * the cod tears past, and the cod keeps most of its
 * speed through each impact instead of stopping: it
 * snatches its hit and is gone.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the cod's current target within 16 blocks
 *   (distance squared 256), plus the cod in water (the
 *   rocket needs water to run in).
 * - The line-up (start()): the charge direction is
 *   locked once, from the cod toward the target's live
 *   position (horizontal plus vertical, normalized), so
 *   the run is one straight line the way the design
 *   says; a target that sidesteps mid-run is simply
 *   missed. For the activation the cod gains the
 *   design's three attribute lines as transient
 *   modifiers, all removed in end(): MOVEMENT_SPEED
 *   +0.6 add_multiplied_base (id advancedmobs:
 *   slipstream_snatch_speed), WATER_MOVEMENT_EFFICIENCY
 *   +1.0 add_value (id advancedmobs:slipstream_snatch_
 *   efficiency; the attribute's maximum, the cod loses
 *   none of its speed to the water), and ATTACK_
 *   KNOCKBACK +2.0 add_value (id advancedmobs:
 *   slipstream_snatch_knockback; the cod has no vanilla
 *   melee path for the modifier to ride, so the real
 *   shove below is code, and the modifier is applied
 *   for the design's line through the null-tolerant
 *   helper). FISH_SWIM plays at volume 1.2, pitch 1.4.
 * - The charge (tick()): the cod's velocity is written
 *   along the locked direction at 1.2 blocks per tick.
 *   On the tick right after a landed hit the speed is
 *   1.0 instead: the cod keeps five sixths of its pace
 *   through an impact, the design's "keeps most of its
 *   speed", rather than stopping or bouncing off. 2
 *   BUBBLE_COLUMN_UP particles stream at the cod's tail
 *   each tick, the visible current. If the cod leaves
 *   the water mid-charge the run is over (the remaining
 *   distance is simply never covered).
 * - The ram pass (every 2 ticks): one bounded query,
 *   radius 1.6 around the cod, vertical reach 1.2 both
 *   ways. Every enemy (the package enemy rule) not yet
 *   hit this charge takes 3.0 flat damage as a mob
 *   attack credited to the cod (the cod has no
 *   ATTACK_DAMAGE instance; see the provider comment)
 *   plus a heavy shove: velocity set to the charge
 *   direction times 1.4 horizontally and 0.3 up,
 *   hurtMarked so it syncs. 4 SPLASH particles burst at
 *   the victim and COD_HURT plays at volume 1.0, pitch
 *   1.5 as the impact crack. Hit UUIDs are recorded in
 *   the state tag, capped at 16 entries; each victim is
 *   hit at most once per charge.
 * - The run ends when the cod has traveled 24 blocks
 *   along the line, with a hard cap of 40 ticks. After
 *   the run the cod keeps whatever the water does with
 *   it; nothing is restored because nothing positional
 *   was changed.
 *
 * WHAT IT LOOKS LIKE: a fish-shaped streak of rising
 * bubble columns down one straight line, victims
 * cracking sideways out of the line in bursts of
 * splash as it passes, and the cod already gone.
 *
 * PERFORMANCE: per tick the charge is one velocity
 * write and 2 particles; the ram pass is one bounded
 * radius 1.6 query every 2 ticks with a hit list of at
 * most 16 UUIDs. Particle budget per activation: at
 * most 40 ticks times 2 = 80 trail, at most 16 hits
 * times 4 = 64 splash, total at most 144.
 *
 * TERRAIN: this ability changes no blocks; the gate
 * does not apply.
 *
 * CLEANUP: end() removes all three transient modifiers
 * by id. The modifiers are transient, so a terminated
 * activation on an unloaded cod loses them on its own;
 * end() is the normal path.
 *
 * State tag keys: "dx", "dy", "dz" (the locked charge
 * direction, normalized), "traveled" (blocks covered
 * so far), "snagged" (the tick of the last landed hit,
 * for the one-tick speed dip), "hits" (list of
 * compounds: "id", one per victim already rammed).
 */
public final class SlipstreamSnatchAbility implements MobAbility {

    private static final int HARD_CAP_TICKS = 40;
    private static final double MAX_TRAVEL = 24.0;
    private static final double CHARGE_SPEED = 1.2;
    private static final double DIPPED_SPEED = 1.0;
    private static final double RAM_RADIUS = 1.6;
    private static final float RAM_DAMAGE = 3.0f;
    private static final double RAM_SHOVE = 1.4;
    private static final int MAX_HITS = 16;
    private static final Identifier SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "slipstream_snatch_speed");
    private static final Identifier EFFICIENCY_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "slipstream_snatch_efficiency");
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "slipstream_snatch_knockback");

    @Override
    public String id() {
        return "slipstream_snatch";
    }

    @Override
    public String displayName() {
        return "Slipstream Snatch";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().isInWater()
            && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        LivingEntity target = ctx.target();

        // The straight line, locked at the target's position right now.
        double dx = 1.0;
        double dy = 0.0;
        double dz = 0.0;
        if (target != null) {
            dx = target.getX() - caster.getX();
            dy = (target.getY() + 0.3) - caster.getY();
            dz = target.getZ() - caster.getZ();
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length > 0.001) {
                dx /= length;
                dy /= length;
                dz /= length;
            } else {
                dx = 1.0;
                dy = 0.0;
                dz = 0.0;
            }
        }
        state.putDouble("dx", dx);
        state.putDouble("dy", dy);
        state.putDouble("dz", dz);
        state.putDouble("traveled", 0.0);
        state.putInt("snagged", -10);
        state.put("hits", new ListTag());

        // The design's three attribute lines, for the run only.
        CodAbilitySupport.addModifier(caster, Attributes.MOVEMENT_SPEED,
            new AttributeModifier(SPEED_ID, 0.6, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        CodAbilitySupport.addModifier(caster, Attributes.WATER_MOVEMENT_EFFICIENCY,
            new AttributeModifier(EFFICIENCY_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        CodAbilitySupport.addModifier(caster, Attributes.ATTACK_KNOCKBACK,
            new AttributeModifier(KNOCKBACK_ID, 2.0, AttributeModifier.Operation.ADD_VALUE));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.FISH_SWIM, SoundSource.HOSTILE, 1.2f, 1.4f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        if (!caster.isInWater()) {
            return;
        }
        CompoundTag state = ctx.state();
        int ticks = ctx.ticksActive();

        double dx = state.getDoubleOr("dx", 1.0);
        double dy = state.getDoubleOr("dy", 0.0);
        double dz = state.getDoubleOr("dz", 0.0);
        // One tick of dipped speed right after a hit; full speed otherwise.
        double speed = ticks - state.getIntOr("snagged", -10) == 1 ? DIPPED_SPEED : CHARGE_SPEED;
        caster.setDeltaMovement(dx * speed, dy * speed, dz * speed);
        caster.hurtMarked = true;
        state.putDouble("traveled", state.getDoubleOr("traveled", 0.0) + speed);

        // The streaming sleeve of bubble columns at the tail.
        level.sendParticles(ParticleTypes.BUBBLE_COLUMN_UP,
            caster.getX() - dx * 0.6, caster.getY(), caster.getZ() - dz * 0.6,
            2, 0.2, 0.3, 0.2, 0.02);

        if (ticks % 2 == 0) {
            runRamPass(ctx, level, ticks);
        }
    }

    /** One ram pass: every not-yet-hit enemy in the line takes the hit and the shove. */
    private void runRamPass(AbilityContext ctx, ServerLevel level, int ticks) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        ListTag hits = state.getListOrEmpty("hits");
        if (hits.size() >= MAX_HITS) {
            return;
        }
        double dx = state.getDoubleOr("dx", 1.0);
        double dz = state.getDoubleOr("dz", 0.0);
        List<LivingEntity> victims = CodAbilitySupport.enemiesAroundSelf(ctx, RAM_RADIUS, 1.2, 1.2);
        for (LivingEntity victim : victims) {
            if (hits.size() >= MAX_HITS || alreadyHit(hits, victim)) {
                continue;
            }
            CodAbilitySupport.trackEntity(state, "hits", victim);
            hits = state.getListOrEmpty("hits");
            victim.hurtServer(level, level.damageSources().mobAttack(caster), RAM_DAMAGE);
            victim.setDeltaMovement(dx * RAM_SHOVE, 0.3, dz * RAM_SHOVE);
            victim.hurtMarked = true;
            state.putInt("snagged", ticks);
            level.sendParticles(ParticleTypes.SPLASH, victim.getX(), victim.getY() + 0.4, victim.getZ(),
                4, 0.3, 0.3, 0.3, 0.05);
            level.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
                SoundEvents.COD_HURT, SoundSource.HOSTILE, 1.0f, 1.5f);
        }
    }

    /** Whether the victim's UUID is already in the hit list. */
    private boolean alreadyHit(ListTag hits, LivingEntity victim) {
        for (int i = 0; i < hits.size(); i++) {
            CompoundTag entry = hits.getCompoundOrEmpty(i);
            if (entry.contains("id")
                && victim.getUUID().equals(entry.read("id", net.minecraft.core.UUIDUtil.CODEC).orElse(null))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= HARD_CAP_TICKS
            || ctx.state().getDoubleOr("traveled", 0.0) >= MAX_TRAVEL;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CodAbilitySupport.removeModifier(caster, Attributes.MOVEMENT_SPEED, SPEED_ID);
        CodAbilitySupport.removeModifier(caster, Attributes.WATER_MOVEMENT_EFFICIENCY, EFFICIENCY_ID);
        CodAbilitySupport.removeModifier(caster, Attributes.ATTACK_KNOCKBACK, KNOCKBACK_ID);
    }
}
