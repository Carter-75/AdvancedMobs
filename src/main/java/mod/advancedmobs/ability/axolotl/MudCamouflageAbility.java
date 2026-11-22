package mod.advancedmobs.ability.axolotl;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
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
import net.minecraft.world.level.block.Blocks;

/**
 * Mud Camouflage (Axolotl ability 4 of 5, defense /
 * transformation).
 *
 * WHAT IT DOES: the axolotl burrows half into the mud and
 * goes still, mud and clay puffing over its back as it dulls
 * to the color of the bottom. Half buried and nearly
 * invisible in the murky water, it waits out the danger, and
 * whatever wanders over its hiding spot gets a free first
 * strike from the mud.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the axolotl's current target within 14 blocks (distance
 *   squared 196), plus the axolotl in water (the camouflage
 *   only works submerged in the murk).
 * - The burrow (start()): the caster gains INVISIBILITY for
 *   320 ticks (removed in end(); this is the design's
 *   invisibility in water, carried by the real effect) and an
 *   ATTACK_DAMAGE add_value modifier of +4.0 (modifier id
 *   advancedmobs:mud_camouflage_ambush; the design's ambush
 *   attribute line, real because the axolotl has an
 *   ATTACK_DAMAGE instance, base 2.0 in 26.2). One bounded
 *   query at radius 16 clears the caster as a target from
 *   every enemy Mob hunting it (the support class's Ashen
 *   Veil pattern), 8 mud BLOCK_CRUMBLE particles puff over
 *   its back, and its navigation is stopped. The design's
 *   render state texture swap has no server-side hook in
 *   26.2, so the dulled look is carried by the invisibility
 *   plus the mud puffs, the same documented tradeoff as the
 *   Horse package's Nightmare Steed.
 * - The wait (tick()): the caster holds its hiding spot:
 *   navigation stopped and horizontal velocity zeroed every
 *   tick, so it settles onto the bottom and stays (vertical
 *   velocity is left alone so it sinks naturally). Every 20
 *   ticks 3 more mud particles puff over its back and the
 *   target clearing repeats (one bounded query, radius 16),
 *   so hunters that re-acquire it through the murk keep
 *   losing it. The ambush check is one distance read per
 *   tick against the caster's own combat target: when the
 *   target comes within 2.2 blocks of the hidden axolotl,
 *   the ambush fires.
 * - The ambush: exactly once, the axolotl strikes from the
 *   mud for its live ATTACK_DAMAGE (6.0 while the modifier
 *   lasts, more if the war system's upgrades raised the
 *   base) as a mob attack credited to the axolotl, with 8
 *   CRIT particles at the victim and AXOLOTL_ATTACK at volume
 *   1.2. The ability ends 5 ticks later.
 * - Leaving the water ends the wait at once: out of the murk
 *   there is nothing to hide in. The hard cap is 300 ticks
 *   (15 seconds) either way.
 *
 * WHAT IT LOOKS LIKE: a puff of mud over the axolotl's back,
 * then nothing but settling silt where it was, an occasional
 * small mud puff marking the hiding spot, and one sudden
 * crit burst when something drifts too close.
 *
 * PERFORMANCE: per tick the work is one navigation stop, one
 * velocity write, and one distance read. The bounded query
 * and the mud puffs run once in start() and once per 20
 * ticks. Particle budget per activation: 8 at the burrow,
 * 3 per 20 ticks over at most 300 ticks (45), and 8 at the
 * ambush, total at most 61 particles. The mud particle
 * option is a pre-built static final.
 *
 * TERRAIN: this ability changes no blocks (the mud is
 * particles only, nothing is placed or dug), so the
 * mobGriefing gate does not apply.
 *
 * CLEANUP: end() removes the INVISIBILITY effect and the
 * ATTACK_DAMAGE modifier.
 *
 * State tag keys: "ambushed" (1 once the ambush strike has
 * landed), "ambush_tick" (the tick it landed on).
 */
public final class MudCamouflageAbility implements MobAbility {

    private static final int HARD_CAP_TICKS = 300;
    private static final int AFTER_AMBUSH_TICKS = 5;
    private static final int PULSE_INTERVAL = 20;
    private static final double CLEAR_RADIUS = 16.0;
    private static final double AMBUSH_RANGE = 2.2;
    private static final double AMBUSH_DAMAGE_BONUS = 4.0;
    private static final Identifier DAMAGE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "mud_camouflage_ambush");
    private static final BlockParticleOption MUD_CRUMBLE =
        new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, Blocks.MUD.defaultBlockState());

    @Override
    public String id() {
        return "mud_camouflage";
    }

    @Override
    public String displayName() {
        return "Mud Camouflage";
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
        ctx.state().putInt("ambushed", 0);
        ctx.state().putInt("ambush_tick", -1);

        caster.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, HARD_CAP_TICKS + 20, 0));
        AxolotlAbilitySupport.addModifier(caster, Attributes.ATTACK_DAMAGE,
            new AttributeModifier(DAMAGE_ID, AMBUSH_DAMAGE_BONUS, AttributeModifier.Operation.ADD_VALUE));
        caster.getNavigation().stop();
        AxolotlAbilitySupport.clearEnemyTargetsOnCaster(ctx, CLEAR_RADIUS);

        if (ctx.level() instanceof ServerLevel level) {
            mudPuff(level, caster, 8);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();

        // Hold the hiding spot on the bottom.
        caster.getNavigation().stop();
        caster.setDeltaMovement(0.0, caster.getDeltaMovement().y, 0.0);

        if (ctx.ticksActive() % PULSE_INTERVAL == 0) {
            AxolotlAbilitySupport.clearEnemyTargetsOnCaster(ctx, CLEAR_RADIUS);
            mudPuff(level, caster, 3);
        }

        // The ambush: the caster's own target wandered over
        // the hiding spot.
        if (ctx.state().getIntOr("ambushed", 0) == 0) {
            LivingEntity target = ctx.target();
            if (target != null && caster.distanceToSqr(target) <= AMBUSH_RANGE * AMBUSH_RANGE) {
                ambush(ctx, level, target);
            }
        }
    }

    /** Puffs mud particles over the caster's back. See the class comment. */
    private void mudPuff(ServerLevel level, Mob caster, int count) {
        level.sendParticles(MUD_CRUMBLE,
            caster.getX(), caster.getY() + caster.getBbHeight() * 0.5, caster.getZ(),
            count, 0.3, 0.2, 0.3, 0.05);
    }

    /** The free first strike from the mud. See the class comment. */
    private void ambush(AbilityContext ctx, ServerLevel level, LivingEntity target) {
        Mob caster = ctx.mob();
        ctx.state().putInt("ambushed", 1);
        ctx.state().putInt("ambush_tick", ctx.ticksActive());

        double damage = caster.getAttributeValue(Attributes.ATTACK_DAMAGE);
        target.hurtServer(level, level.damageSources().mobAttack(caster), (float) damage);

        level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY() + 0.5, target.getZ(),
            8, 0.3, 0.3, 0.3, 0.1);
        level.playSound(null, target.getX(), target.getY(), target.getZ(),
            SoundEvents.AXOLOTL_ATTACK, SoundSource.HOSTILE, 1.2f, 1.0f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks >= HARD_CAP_TICKS) {
            return true;
        }
        if (!ctx.mob().isInWater()) {
            return true;
        }
        int ambushTick = ctx.state().getIntOr("ambush_tick", -1);
        return ambushTick >= 0 && ticks >= ambushTick + AFTER_AMBUSH_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.removeEffect(MobEffects.INVISIBILITY);
        AxolotlAbilitySupport.removeModifier(caster, Attributes.ATTACK_DAMAGE, DAMAGE_ID);
    }
}
