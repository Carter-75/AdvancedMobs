package mod.advancedmobs.ability.evoker;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Vex;

import java.util.List;

/**
 * Totem Tyrant (Evoker ability 2 of 5, transformation).
 *
 * WHAT IT DOES: the evoker produces a totem of undying and
 * rules through it. For as long as the form lasts he stands
 * taller on his maximum health and glows with a golden
 * outline, and the first blow that would kill him instead
 * triggers the totem: the totem burst throws nearby enemies
 * back, and he comes back to his feet restored, furious,
 * and suddenly just an evoker again. While the tyrant form
 * lasts, every vex he has summoned fights with STRENGTH,
 * so his whole court is more dangerous for the same price.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the evoker's current target within 16 blocks (distance
 *   squared 256).
 * - Duration: 600 ticks (30 seconds) from start(), or until
 *   the totem fires, whichever comes first. The form's buffs
 *   are applied for 620 ticks so they cannot expire mid-form
 *   on a tick boundary; end() removes them exactly.
 * - The form (start()): HEALTH_BOOST amplifier 1 for 620
 *   ticks (the evoker's base 24 maximum health becomes 32,
 *   arriving full; the design's "gains HEALTH_BOOST" taken
 *   as the vanilla effect, amplifier 1 = 8 extra health) and
 *   GLOWING for 620 ticks (the golden outline is the effect
 *   itself; its outline color is vanilla team-based white,
 *   and the tyrant's gold is carried by the TOTEM_OF_UNDYING
 *   motes below, a documented approximation on the Mirror
 *   Ghasts precedent: there is no per-entity glow color in
 *   26.2). A 10-mote TOTEM_OF_UNDYING burst at start() sells
 *   the totem appearing in his hands, with an
 *   EVOKER_CAST_SPELL at volume 1.2.
 * - The totem (see TotemTyrantSave and
 *   EvokerTotemTyrantMixin): the save itself does NOT live in
 *   this class, because a killing blow is processed inside
 *   LivingEntity.hurtServer between ticks and no tick-side
 *   polling can intercept it. The mixin injects at the head
 *   of the vanilla totem check, and the save hook performs
 *   the whole revival at the moment of the blow: the
 *   invulnerability-bypass guard, effects cleared, health
 *   set to half of the maximum that remains (12 of 24),
 *   REGENERATION I for 200 ticks and ABSORPTION I for 100
 *   ticks, a 2.0-damage shove of every enemy within 5
 *   blocks, and the vanilla totem pop (entity event 35)
 *   with a server-sent mote ring and the tyrant's
 *   EVOKER_CELEBRATE. The totem is once per activation: the
 *   hook sets "totem_spent" 1, and this ability ends on the
 *   next tick it sees it (see isFinished).
 * - The court (tick(), every 20 ticks): every summoned vex
 *   of his within 16 blocks (an alliance record in his data,
 *   or a SummonerTracker record naming him as the summoner,
 *   which is how his own vanilla vex summons register) gains
 *   STRENGTH I refreshed to 30 ticks. Only his own court is
 *   blessed: an unallied vex passing through keeps its own
 *   stats. One bounded Vex query per pulse.
 * - The tell (tick(), every 40 ticks): 3
 *   TOTEM_OF_UNDYING motes drift off the evoker, the
 *   visible promise that killing him once will not be
 *   enough. A low EVOKER_AMBIENT at pitch 0.7 plays every
 *   200 ticks while the form holds.
 * - end(): removes HEALTH_BOOST and GLOWING from the evoker.
 *   If the totem fired, the hook already cleared the
 *   effects, so these removals are no-ops; if the window
 *   lapses unspent, they take the form off cleanly. The
 *   vexes' STRENGTH is a timed effect that simply bleeds off
 *   on its own clock (at most 30 ticks past the form), the
 *   documented bleed-off the other auras use, so end()
 *   strips nothing from the court. Removing HEALTH_BOOST
 *   drops the maximum health back to 24 and clamps current
 *   health to it, which is the standard form-end cost.
 *
 * WHAT IT LOOKS LIKE: a burst of golden totem motes as he
 * produces it, then an evoker standing a little taller
 * behind a glowing outline, golden motes drifting off him,
 * and his vexes hitting visibly harder while it lasts. The
 * kill that lands on him anyway ends in the full vanilla
 * totem pop and his laugh, and he is suddenly just an
 * evoker again.
 *
 * PERFORMANCE: tick() returns immediately except every
 * 20th tick (one bounded Vex query, radius 16) and every
 * 40th tick (3 particles). canTrigger does one distance
 * check. No per-tick work, no whole-level scans. Particle
 * budget per activation: 10 at start plus at most 15 tell
 * pulses times 3 = 45, total at most 55 particles (the
 * revival's own burst is the save hook's, counted there).
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "armed" (1 while the totem is armed; the
 * save hook requires it), "totem_spent" (set to 1 by the
 * save hook at the moment of the revival; ends the
 * ability).
 */
public final class TotemTyrantAbility implements MobAbility {

    private static final int DURATION_TICKS = 600;
    private static final int BUFF_TICKS = 620;
    private static final int COURT_INTERVAL = 20;
    private static final double COURT_RADIUS = 16.0;

    @Override
    public String id() {
        return TotemTyrantSave.ABILITY_ID;
    }

    @Override
    public String displayName() {
        return "Totem Tyrant";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        // Arm the totem. The hook reads exactly these two flags.
        ctx.state().putInt("armed", 1);
        ctx.state().putInt("totem_spent", 0);

        Mob caster = ctx.mob();
        caster.addEffect(new MobEffectInstance(MobEffects.HEALTH_BOOST, BUFF_TICKS, 1));
        caster.addEffect(new MobEffectInstance(MobEffects.GLOWING, BUFF_TICKS, 0, false, false, false));
        caster.setHealth(caster.getMaxHealth());

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, caster.getX(), caster.getY() + 1.2, caster.getZ(),
            10, 0.4, 0.6, 0.4, 0.10);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.EVOKER_CAST_SPELL, SoundSource.HOSTILE, 1.2f, 1.0f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        if (ticks % COURT_INTERVAL == 0) {
            // The court fights harder while the tyrant rules.
            List<Vex> court = level.getEntitiesOfClass(Vex.class,
                caster.getBoundingBox().inflate(COURT_RADIUS),
                vex -> vex.isAlive() && (ctx.data().isAlly(vex.getUUID())
                    || SummonerTracker.isSummonedBy(vex.getUUID(), caster.getUUID())));
            for (Vex vex : court) {
                vex.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 30, 0));
            }
        }
        if (ticks % 40 == 0) {
            level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, caster.getX(), caster.getY() + 1.4, caster.getZ(),
                3, 0.3, 0.4, 0.3, 0.05);
        }
        if (ticks % 200 == 0) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.EVOKER_AMBIENT, SoundSource.HOSTILE, 1.0f, 0.7f);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        // Ends when the totem fires (the hook sets the flag)
        // or the form's window runs out.
        return ctx.state().getIntOr("totem_spent", 0) == 1 || ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.removeEffect(MobEffects.HEALTH_BOOST);
        caster.removeEffect(MobEffects.GLOWING);
    }
}
