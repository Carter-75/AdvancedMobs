package mod.advancedmobs.ability.evoker;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.data.MobWarData;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.illager.Evoker;

import java.util.List;

/**
 * TotemTyrantSave: the death-save hook behind the Totem
 * Tyrant ability (see TotemTyrantAbility for the ability
 * itself and EvokerTotemTyrantMixin for the injection point).
 *
 * WHY THIS EXISTS: an ability ticks once per AI step, but a
 * killing blow lands between ticks and vanilla processes the
 * death inside LivingEntity.hurtServer itself (verified
 * against the 26.2 jar: hurtServer applies the damage, then,
 * when the entity is dead or dying, calls
 * checkTotemDeathProtection and only calls die() when that
 * returns false). No tick-side polling can intercept that.
 * The one clean interception point is the totem check
 * itself: the mixin asks trySave() at the head of
 * LivingEntity.checkTotemDeathProtection, and a true return
 * makes hurtServer skip die() exactly the way a real totem
 * of undying does. This is the Zoglin Undying Bulk pattern,
 * reused for a totem whose design is a held item rather than
 * an inborn trait.
 *
 * THE SAVE (all of it happens here, at the moment of the
 * killing blow):
 * - Guards: a damage source that bypasses invulnerability
 *   (the void, /kill) is never saved from, mirroring the
 *   vanilla totem's own refusal. Unlike the Zoglin's save,
 *   fire is NOT excluded: the design gives this totem no
 *   fire exception, so it behaves like the real item, which
 *   pops on a fire death like any other.
 * - The evoker must have Totem Tyrant active with the totem
 *   armed and unspent (the ability's own state tag, read
 *   through MobWarData: active id "totem_tyrant", "armed" 1,
 *   "totem_spent" 0). The totem is once per ACTIVATION, not
 *   once per lifetime: after the form ends the evoker may
 *   roll the ability again on a later cooldown and produce a
 *   fresh totem, which is the design's "once the totem is
 *   spent he is just an evoker again" read plainly.
 * - The revival, modeled on the real totem's own numbers
 *   where the design is silent: all effects are cleared (the
 *   tyrant's HEALTH_BOOST and GLOWING go with them, so his
 *   maximum health falls back from 32 to the base 24 before
 *   the heal is computed), health is set to half of the
 *   maximum (12 of 24), and he gains REGENERATION I for 200
 *   ticks and ABSORPTION I for 100 ticks.
 * - The blast: every enemy within 5 blocks (the package
 *   enemy rule, through a context built on the spot) takes
 *   2.0 mob-attack damage credited to the evoker and is
 *   hurled directly away from him at 1.6 horizontal and 0.55
 *   up, hurtMarked set so the velocity syncs. "Blasting
 *   nearby enemies back" is the design's words; the 2.0 is
 *   a token sting on top of the shove, not a second attack.
 * - The show: the level broadcasts entity event 35, the
 *   vanilla totem pop, so every nearby client renders the
 *   TOTEM_OF_UNDYING particle burst and the totem sound at
 *   the evoker, plus a server-sent ring of 24
 *   TOTEM_OF_UNDYING motes and an EVOKER_CELEBRATE at volume
 *   1.2: the tyrant laughs at the kill that failed.
 * - The state tag's "totem_spent" flag is set, so the
 *   ability sees it on its next tick and ends, starting its
 *   cooldown, visibly furious (the design's words) but
 *   mechanically just an evoker again.
 */
public final class TotemTyrantSave {

    /** The ability id the save belongs to; also the active-id the hook looks for. */
    static final String ABILITY_ID = "totem_tyrant";

    private static final double BLAST_RADIUS = 5.0;
    private static final double BLAST_DAMAGE = 2.0;
    private static final double BLAST_KNOCKBACK = 1.6;
    private static final double BLAST_LIFT = 0.55;

    private TotemTyrantSave() {}

    /**
     * Attempts the totem save for an evoker whose killing
     * blow is being processed. Returns true when the save
     * fired and the death must be prevented (the mixin then
     * returns true from checkTotemDeathProtection); false
     * leaves vanilla death exactly as it was.
     */
    public static boolean trySave(Evoker evoker, DamageSource source) {
        // The vanilla totem's own refusal, mirrored: nothing
        // saves from damage that bypasses invulnerability.
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return false;
        }
        if (!(evoker.level() instanceof ServerLevel level)) {
            return false;
        }
        MobWarData data = MobWarData.get(evoker);
        if (data == null || !ABILITY_ID.equals(data.getAbilityActiveId())) {
            return false;
        }
        CompoundTag state = data.getAbilityActiveState();
        if (state.getIntOr("armed", 0) != 1 || state.getIntOr("totem_spent", 0) != 0) {
            return false;
        }

        // The totem fires. Mark it spent first: the ability
        // ends on its next tick, and this flag is the record.
        state.putInt("totem_spent", 1);

        // The totem way: effects cleared (the tyrant form's
        // own buffs go with them), then health set against
        // the maximum that remains.
        evoker.removeAllEffects();
        evoker.setHealth(evoker.getMaxHealth() * 0.5f);
        evoker.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 200, 0));
        evoker.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 100, 1));

        // The blast: nearby enemies are thrown off him.
        AbilityContext ctx = new AbilityContext(evoker, level, data, state);
        List<LivingEntity> victims = EvokerAbilitySupport.enemiesInBox(ctx,
            evoker.getX(), evoker.getY(), evoker.getZ(), BLAST_RADIUS, 2.0, 3.0);
        for (LivingEntity victim : victims) {
            victim.hurtServer(level, level.damageSources().mobAttack(evoker), (float) BLAST_DAMAGE);
            double dx = victim.getX() - evoker.getX();
            double dz = victim.getZ() - evoker.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 0.001) {
                dx = 1.0;
                dz = 0.0;
                len = 1.0;
            }
            victim.setDeltaMovement(victim.getDeltaMovement().x + dx / len * BLAST_KNOCKBACK,
                BLAST_LIFT, victim.getDeltaMovement().z + dz / len * BLAST_KNOCKBACK);
            victim.hurtMarked = true;
        }

        // Loud and golden: the vanilla totem pop on every
        // nearby client, a server-sent mote ring, and the laugh.
        level.broadcastEntityEvent(evoker, (byte) 35);
        level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, evoker.getX(), evoker.getY() + 1.2, evoker.getZ(),
            24, 0.7, 0.8, 0.7, 0.15);
        level.playSound(null, evoker.getX(), evoker.getY(), evoker.getZ(), SoundEvents.EVOKER_CELEBRATE,
            SoundSource.HOSTILE, 1.2f, 0.9f);
        return true;
    }
}
