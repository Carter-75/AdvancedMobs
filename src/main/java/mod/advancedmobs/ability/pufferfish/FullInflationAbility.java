package mod.advancedmobs.ability.pufferfish;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.fish.Pufferfish;

import java.util.List;
import java.util.UUID;

/**
 * Full Inflation (Pufferfish ability 1 of 5,
 * transformation / defense).
 *
 * WHAT IT DOES: the pufferfish gulps water and
 * balloons to triple size in a spray of bubbles and
 * poof, spikes bristling in every direction. For
 * fifteen seconds it is a living obstacle: armored,
 * immovable, big enough to body block a doorway or a
 * current, and anything that bumps into the spikes
 * takes a sting for its trouble.
 *
 * MECHANICS (the spec; fix bugs against these
 * numbers):
 * - Trigger (canTrigger): the framework's combat
 *   gate, plus the pufferfish's current target within
 *   12 blocks (distance squared 144), plus the
 *   pufferfish in water (the gulp needs water).
 * - The inflation (start()): the puff state the fish
 *   was found in is recorded ("puff_before"), then
 *   the real synced puff state is forced to
 *   Pufferfish.STATE_FULL, so the render and the
 *   bounding box swell the vanilla way. Three
 *   transient modifiers ride the activation, all
 *   removed in end(): SCALE +2.0 add_multiplied_base
 *   (id advancedmobs:full_inflation_scale; base 1
 *   plus 2 is the design's triple size, and it stacks
 *   on the full puff state's own larger box), ARMOR
 *   +12.0 add_value (id advancedmobs:full_inflation_
 *   armor; diamond tier on a 0-armor fish), and
 *   KNOCKBACK_RESISTANCE +1.0 add_value (id
 *   advancedmobs:full_inflation_knockback; total
 *   immunity, the obstacle does not get shoved out of
 *   the doorway). PUFFER_FISH_BLOW_UP plays at volume
 *   1.2, and the gulp bursts 16 BUBBLE plus 10 POOF
 *   around the fish. The fish is held in place for
 *   the whole activation by per-tick navigation stops
 *   (never NoAi; see the framework contract).
 * - Duration: 300 ticks (15 seconds), hard finish.
 * - The spikes (every 2 ticks, one bounded query,
 *   radius 1.9 around the fish, vertical reach 1.5
 *   both ways, roughly the tripled body's own
 *   footprint): every enemy (the package enemy rule)
 *   whose last sting this activation was at least 20
 *   ticks ago takes 2.0 flat damage through the
 *   vanilla sting damage source credited to the
 *   pufferfish (level.damageSources().sting(caster),
 *   the design's typed contact damage; the pufferfish
 *   has no ATTACK_DAMAGE instance, see the provider
 *   comment), gains POISON I for 60 ticks, and is
 *   pushed 0.4 blocks per tick directly away from the
 *   fish with hurtMarked set. PUFFER_FISH_STING plays
 *   at volume 0.9, pitch 1.1 on each sting, with 3
 *   pufferfish SPINE_FLECK item particles at the
 *   victim. Sting stamps live in the state tag's
 *   "stung" list ("id" plus "tick"), capped at 16
 *   entries and pruned to fresh stamps past that.
 * - The drift (every 4 ticks): 2 BUBBLE rise off the
 *   swollen body, the obstacle breathing.
 *
 * WHAT IT LOOKS LIKE: a small fish snapping into a
 * spiked ball three times its size in a burst of
 * bubbles, sitting in the middle of the fight like a
 * naval mine while enemies that brush it recoil
 * poisoned, flecks of spine sprite spraying off each
 * contact.
 *
 * PERFORMANCE: the contact pass is one bounded
 * radius-1.9 query every 2 ticks with a sting list of
 * at most 16 UUIDs; the drift is 2 particles every 4
 * ticks; the hold is one navigation stop per tick.
 * Particle budget per activation: opening 26, drift
 * at most 75 times 2 = 150, stings at most 16 enemies
 * times 15 windows times 3 = 720 theoretical but in
 * practice bounded by how many enemies crowd a fish,
 * stated cap 900.
 *
 * TERRAIN: this ability changes no blocks; the gate
 * does not apply.
 *
 * CLEANUP: end() removes all three transient
 * modifiers by id and restores the recorded puff
 * state. Enemy poison rides its own 60-tick clock
 * out. The modifiers are transient, so a terminated
 * activation on an unloaded fish loses them on its
 * own; end() is the normal path.
 *
 * State tag keys: "puff_before" (the puff state found
 * at start), "stung" (list of compounds: "id",
 * "tick", one per enemy stung this activation).
 */
public final class FullInflationAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final double CONTACT_RADIUS = 1.9;
    private static final float STING_DAMAGE = 2.0f;
    private static final int STING_COOLDOWN_TICKS = 20;
    private static final int MAX_STUNG = 16;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "full_inflation_scale");
    private static final Identifier ARMOR_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "full_inflation_armor");
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "full_inflation_knockback");

    @Override
    public String id() {
        return "full_inflation";
    }

    @Override
    public String displayName() {
        return "Full Inflation";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().isInWater()
            && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        state.put("stung", new ListTag());
        if (caster instanceof Pufferfish pufferfish) {
            state.putInt("puff_before", pufferfish.getPuffState());
            pufferfish.setPuffState(Pufferfish.STATE_FULL);
        }

        // The design's three lines, for the activation only.
        PufferfishAbilitySupport.addModifier(caster, Attributes.SCALE,
            new AttributeModifier(SCALE_ID, 2.0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        PufferfishAbilitySupport.addModifier(caster, Attributes.ARMOR,
            new AttributeModifier(ARMOR_ID, 12.0, AttributeModifier.Operation.ADD_VALUE));
        PufferfishAbilitySupport.addModifier(caster, Attributes.KNOCKBACK_RESISTANCE,
            new AttributeModifier(KNOCKBACK_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.PUFFER_FISH_BLOW_UP, SoundSource.HOSTILE, 1.2f, 1.0f);
            level.sendParticles(ParticleTypes.BUBBLE, caster.getX(), caster.getY() + 0.4, caster.getZ(),
                16, 0.6, 0.6, 0.6, 0.05);
            level.sendParticles(ParticleTypes.POOF, caster.getX(), caster.getY() + 0.4, caster.getZ(),
                10, 0.6, 0.6, 0.6, 0.05);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        // The obstacle holds its ground.
        caster.getNavigation().stop();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        if (caster instanceof Pufferfish pufferfish
            && pufferfish.getPuffState() != Pufferfish.STATE_FULL) {
            // The vanilla puff goal may deflate the fish
            // mid-activation; the inflation re-asserts
            // itself (the Armadillo state refresh pattern).
            pufferfish.setPuffState(Pufferfish.STATE_FULL);
        }
        if (ticks % 4 == 0) {
            level.sendParticles(ParticleTypes.BUBBLE, caster.getX(), caster.getY() + 0.5, caster.getZ(),
                2, 0.7, 0.7, 0.7, 0.03);
        }
        if (ticks % 2 == 0) {
            runContactPass(ctx, level, ticks);
        }
    }

    /** One spike pass: every freshly touching enemy takes the sting. */
    private void runContactPass(AbilityContext ctx, ServerLevel level, int ticks) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        List<LivingEntity> victims = PufferfishAbilitySupport.enemiesAroundSelf(ctx, CONTACT_RADIUS, 1.5, 1.5);
        for (LivingEntity victim : victims) {
            if (!stingStamp(state, victim.getUUID(), ticks)) {
                continue;
            }
            victim.hurtServer(level, level.damageSources().sting(caster), STING_DAMAGE);
            victim.addEffect(new MobEffectInstance(MobEffects.POISON, 60, 0));
            double awayX = victim.getX() - caster.getX();
            double awayZ = victim.getZ() - caster.getZ();
            double awayLength = Math.sqrt(awayX * awayX + awayZ * awayZ);
            if (awayLength < 1.0e-4) {
                awayX = 1.0;
                awayZ = 0.0;
                awayLength = 1.0;
            }
            victim.setDeltaMovement(awayX / awayLength * 0.4,
                Math.max(victim.getDeltaMovement().y, 0.1),
                awayZ / awayLength * 0.4);
            victim.hurtMarked = true;
            level.sendParticles(PufferfishAbilitySupport.SPINE_FLECK,
                victim.getX(), victim.getY() + 0.5, victim.getZ(),
                3, 0.3, 0.3, 0.3, 0.08);
            level.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
                SoundEvents.PUFFER_FISH_STING, SoundSource.HOSTILE, 0.9f, 1.1f);
        }
    }

    /**
     * Whether the given enemy may be stung now; records the
     * sting stamp when it may. Stamps older than the sting
     * cooldown are refreshed in place; the list is pruned to
     * fresh stamps when it grows past the cap.
     */
    private boolean stingStamp(CompoundTag state, UUID id, int ticks) {
        ListTag stung = state.getListOrEmpty("stung");
        for (int i = 0; i < stung.size(); i++) {
            CompoundTag entry = stung.getCompoundOrEmpty(i);
            UUID stungId = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id.equals(stungId)) {
                if (ticks - entry.getIntOr("tick", -STING_COOLDOWN_TICKS) < STING_COOLDOWN_TICKS) {
                    return false;
                }
                entry.putInt("tick", ticks);
                state.put("stung", stung);
                return true;
            }
        }
        if (stung.size() >= MAX_STUNG) {
            ListTag fresh = new ListTag();
            for (int i = 0; i < stung.size(); i++) {
                CompoundTag entry = stung.getCompoundOrEmpty(i);
                if (ticks - entry.getIntOr("tick", 0) < STING_COOLDOWN_TICKS) {
                    fresh.add(entry);
                }
            }
            stung = fresh;
        }
        CompoundTag entry = new CompoundTag();
        entry.store("id", UUIDUtil.CODEC, id);
        entry.putInt("tick", ticks);
        stung.add(entry);
        state.put("stung", stung);
        return true;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        PufferfishAbilitySupport.removeModifier(caster, Attributes.SCALE, SCALE_ID);
        PufferfishAbilitySupport.removeModifier(caster, Attributes.ARMOR, ARMOR_ID);
        PufferfishAbilitySupport.removeModifier(caster, Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_ID);
        if (caster instanceof Pufferfish pufferfish) {
            pufferfish.setPuffState(ctx.state().getIntOr("puff_before", Pufferfish.STATE_SMALL));
        }
    }
}
