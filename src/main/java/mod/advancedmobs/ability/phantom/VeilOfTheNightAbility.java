package mod.advancedmobs.ability.phantom;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
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

/**
 * Veil of the Night (Phantom ability 5 of 5, transformation).
 *
 * WHAT IT DOES: the phantom's body thins to translucence
 * and then is simply gone, leaving only a glowing outline
 * hanging in the night sky and a faint trail of portal
 * sparks where it flies. It hunts under the veil until the
 * moment it attacks, and the attack that breaks the veil
 * comes out of nowhere with extra force and a sweeping
 * slash.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the phantom's current target within 20 blocks (distance
 *   squared 400).
 * - The veil (start()): INVISIBILITY for 320 ticks with
 *   particles and icon hidden, and GLOWING for 320 ticks,
 *   both removed in end(). In vanilla, a glowing invisible
 *   entity shows its glowing outline while its body stays
 *   invisible, which is the design's "leaving only two
 *   glowing eyes" carried as far as the server-side effect
 *   pair reaches (a true eyes-only render needs a client
 *   render hook this framework does not have; the Mirror
 *   Ghasts approximation precedent, documented rather than
 *   faked). A low PHANTOM_AMBIENT (volume 1.0, pitch 0.7)
 *   plays as the veil falls.
 * - The breaking bonus (start(), reversed in end()): a
 *   transient ATTACK_DAMAGE modifier of +4.0 ADD_VALUE
 *   under a fixed modifier id. It is live for the whole
 *   veil, but the veil breaks on the phantom's FIRST
 *   landed hit (below), so in practice only the breaking
 *   hit ever benefits: an unupgraded phantom's breaking
 *   bite reads 10.0 instead of 6.0.
 * - The trail (tick(), every 2 ticks): 2 PORTAL particles
 *   at the phantom's body, the faint spark trail of the
 *   design and the only positional tell besides the
 *   outline.
 * - The break (tick(), every tick, one integer compare):
 *   the bite clock. The phantom's last-hurt-mob timestamp
 *   is primed into the state tag in start(); when it
 *   advances, the phantom has landed a melee hit since the
 *   last poll. The stored timestamp is updated either way.
 *   If the victim (the phantom's last hurt mob) is an
 *   enemy by the package rule, the veil breaks: 1
 *   SWEEP_ATTACK and 6 CRIT particles burst at the
 *   victim's chest, a PHANTOM_BITE plays (volume 1.2,
 *   pitch 1.2), the state tag marks the veil broken, and
 *   isFinished() then ends the ability, so end() strips
 *   the invisibility, the glow, and the damage bonus on
 *   the same tick the hit lands. A hit on a non-enemy (an
 *   ally caught by a swoop) does not break the veil; only
 *   the timestamp is consumed.
 * - Duration: until the breaking hit, with a hard finish
 *   at 300 ticks (15 seconds): a phantom that never lands
 *   a hit simply has the veil lift at the cap, its timed
 *   effects and bonus removed by end() exactly as on a
 *   break.
 * - end(): INVISIBILITY and GLOWING are removed and the
 *   ATTACK_DAMAGE modifier is removed by id, so the
 *   phantom returns to exactly its pre-veil state. If the
 *   caster dies first, the framework termination path runs
 *   this same end(); the effects and the transient modifier
 *   would die with the entity anyway.
 *
 * WHAT IT LOOKS LIKE: a phantom winking out mid-flight
 * until only a pale glowing outline and a sparse trail of
 * purple sparks cross the sky, the outline growing as it
 * dives, and then a bite out of empty air with a sweep of
 * claws and crit stars as the shape snaps back into view.
 *
 * PERFORMANCE: per tick the cost is one integer compare
 * against the stored timestamp; the trail is 2 particles
 * every 2 ticks; the break is a one-shot burst. No entity
 * queries anywhere. Particle budget per activation: trail
 * 2 times 150 draws = 300, break burst 7, total at most
 * 307 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "hit_ts" (the primed last-hurt-mob
 * timestamp), "broken" (1 once the breaking hit lands).
 */
public final class VeilOfTheNightAbility implements MobAbility {

    private static final int HARD_FINISH_TICKS = 300;
    private static final int EFFECT_TICKS = 320;
    private static final Identifier DAMAGE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "veil_of_the_night_damage");

    @Override
    public String id() {
        return "veil_of_the_night";
    }

    @Override
    public String displayName() {
        return "Veil of the Night";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 400.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, EFFECT_TICKS, 0, false, false, false));
        caster.addEffect(new MobEffectInstance(MobEffects.GLOWING, EFFECT_TICKS, 0, false, false, false));
        PhantomAbilitySupport.addModifier(caster, Attributes.ATTACK_DAMAGE,
            new AttributeModifier(DAMAGE_ID, 4.0, AttributeModifier.Operation.ADD_VALUE));
        // Prime the bite clock: only hits landed after this
        // moment can break the veil.
        ctx.state().putInt("hit_ts", caster.getLastHurtMobTimestamp());
        ctx.state().putInt("broken", 0);
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.PHANTOM_AMBIENT,
                SoundSource.HOSTILE, 1.0f, 0.7f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        if (ctx.ticksActive() % 2 == 0) {
            ServerLevel level = ctx.serverLevel();
            if (level != null) {
                level.sendParticles(ParticleTypes.PORTAL, caster.getX(), caster.getY() + 0.3, caster.getZ(),
                    2, 0.25, 0.2, 0.25, 0.05);
            }
        }
        // The bite clock (see the class comment): one integer
        // compare per tick.
        int timestamp = caster.getLastHurtMobTimestamp();
        if (timestamp != ctx.state().getIntOr("hit_ts", 0)) {
            ctx.state().putInt("hit_ts", timestamp);
            LivingEntity victim = caster.getLastHurtMob();
            if (victim != null && PhantomAbilitySupport.isEnemy(ctx, victim)) {
                breakVeil(ctx, victim);
            }
        }
    }

    /** The breaking hit's burst (see the class comment). Runs exactly once per activation. */
    private static void breakVeil(AbilityContext ctx, LivingEntity victim) {
        ctx.state().putInt("broken", 1);
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        double cx = victim.getX();
        double cy = victim.getY() + 0.9;
        double cz = victim.getZ();
        level.sendParticles(ParticleTypes.SWEEP_ATTACK, cx, cy, cz, 1, 0.0, 0.0, 0.0, 0.0);
        level.sendParticles(ParticleTypes.CRIT, cx, cy, cz, 6, 0.3, 0.4, 0.3, 0.15);
        level.playSound(null, cx, cy, cz, SoundEvents.PHANTOM_BITE, SoundSource.HOSTILE, 1.2f, 1.2f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.state().getIntOr("broken", 0) == 1 || ctx.ticksActive() >= HARD_FINISH_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.removeEffect(MobEffects.INVISIBILITY);
        caster.removeEffect(MobEffects.GLOWING);
        PhantomAbilitySupport.removeModifier(caster, Attributes.ATTACK_DAMAGE, DAMAGE_ID);
    }
}
