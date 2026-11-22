package mod.advancedmobs.ability.cat;

import mod.advancedmobs.data.MobWarData;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.feline.Cat;

import java.util.List;

/**
 * NineLivesSave: the death-save hook behind the Nine Lives
 * ability (see NineLivesAbility for the ability itself and
 * CatNineLivesMixin for the injection point).
 *
 * WHY THIS EXISTS: an ability ticks once per AI step, but a
 * killing blow lands between ticks and vanilla processes
 * the death inside LivingEntity.hurtServer itself (verified
 * against the 26.2 jar: hurtServer applies the damage,
 * then, when the entity is dead or dying, calls
 * checkTotemDeathProtection and only calls die() when that
 * returns false). No tick-side polling can intercept that.
 * The one clean interception point is the totem check
 * itself: the mixin asks trySave() at the head of
 * LivingEntity.checkTotemDeathProtection, and a true return
 * makes hurtServer skip die() exactly the way a real totem
 * of undying does. This is the Zoglin Undying Bulk and
 * Evoker Totem Tyrant pattern, unchanged.
 *
 * THE NINE LIVES COUNTER (the design's "per cat counter",
 * and this package's permanence decision): a cat has nine
 * lives in total. The life it is currently living is the
 * first, so it can cheat death at most MAX_SAVES (8) times.
 * Spent saves are counted PER CAT, PERMANENTLY: the count
 * lives in the cat's MobWarData skillData tag under
 * "am_cat_lives_spent", which MobWarData writes into the
 * entity's saved NBT, so the count survives chunk unloads,
 * restarts, and separate Nine Lives activations. It is
 * never refunded and never reset: once a cat has spent all
 * 8 saves, Nine Lives stops being offered to it at all
 * (NineLivesAbility.canTrigger checks the same counter).
 * This mirrors Undying Bulk's once-ever permanence, widened
 * to the cat's eight extra lives.
 *
 * THE SAVE (all of it happens here, at the moment of the
 * killing blow, so the revival is immediate):
 * - Guards, mirroring the vanilla totem's own refusal: a
 *   damage source that bypasses invulnerability (the void,
 *   /kill) is never saved from.
 * - The cat must have Nine Lives active with the save armed
 *   (the ability's own state tag, read through MobWarData:
 *   active id "nine_lives", "armed" 1) and at least one
 *   life unspent.
 * - On firing: the permanent counter increments first, and
 *   the active state tag's "saves" count increments with it
 *   (the ability watches nothing; the record is for spec
 *   and debugging). All effects are cleared, the way a
 *   totem clears them (ScalingSystem re-applies the cat's
 *   permanent upgrade effects on its own tick, the Living
 *   Stone Shell precedent). Health is set to half the
 *   cat's maximum (at least 2.0), then the design's
 *   instant_health lands as a real INSTANT_HEALTH effect,
 *   amplifier 0: the cat is not undead, so the effect heals
 *   its vanilla 4 health on top of the half restore. That
 *   is the "surge of health".
 * - The escape: INVISIBILITY for 200 ticks and SPEED II
 *   (amplifier 1) for 200 ticks, and every enemy Mob within
 *   radius 16 whose current target is the cat has its
 *   target cleared: the cat vanishes mid-fight and its
 *   attackers lose it, exactly as designed. These escape
 *   effects are applied here, not by the ability, and
 *   NineLivesAbility.end() deliberately does NOT strip
 *   them: they ride their own 200 tick clocks even if the
 *   activation ends a tick later.
 * - The show: the level broadcasts entity event 35, the
 *   vanilla totem pop, so every nearby client renders the
 *   TOTEM_OF_UNDYING particle burst and the totem sound at
 *   the cat, plus a server-sent burst of 20 TOTEM_OF_UNDYING
 *   particles and the cat's own hiss at pitch 1.2.
 */
public final class NineLivesSave {

    /** The ability id the save belongs to; also the active-id the hook looks for. */
    static final String ABILITY_ID = "nine_lives";

    /** MobWarData skillData key holding this cat's permanent spent-save count. */
    static final String LIVES_KEY = "am_cat_lives_spent";

    /** Total lives a cat owns; the living one plus MAX_SAVES cheats. */
    static final int TOTAL_LIVES = 9;

    /** How many killing blows one cat can ever be saved from: 8. */
    static final int MAX_SAVES = TOTAL_LIVES - 1;

    private static final int ESCAPE_TICKS = 200;
    private static final double VANISH_RADIUS = 16.0;

    private NineLivesSave() {}

    /** How many of its saves this cat has already spent, across its whole life. */
    static int livesSpent(Mob cat) {
        MobWarData data = MobWarData.get(cat);
        return data != null ? data.getSkillData().getIntOr(LIVES_KEY, 0) : 0;
    }

    /** Whether this cat still has an unspent life to save it with. */
    static boolean hasLifeLeft(Mob cat) {
        return livesSpent(cat) < MAX_SAVES;
    }

    /**
     * Attempts the death save for a cat whose killing blow
     * is being processed. Returns true when the save fired
     * and the death must be prevented (the mixin then
     * returns true from checkTotemDeathProtection); false
     * leaves vanilla death exactly as it was.
     */
    public static boolean trySave(Cat cat, DamageSource source) {
        // The vanilla totem's own refusal, mirrored: nothing
        // saves from damage that bypasses invulnerability.
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return false;
        }
        if (!(cat.level() instanceof ServerLevel level)) {
            return false;
        }
        MobWarData data = MobWarData.get(cat);
        if (data == null || !ABILITY_ID.equals(data.getAbilityActiveId())) {
            return false;
        }
        CompoundTag state = data.getAbilityActiveState();
        if (state.getIntOr("armed", 0) != 1) {
            return false;
        }
        CompoundTag skillData = data.getSkillData();
        int spent = skillData.getIntOr(LIVES_KEY, 0);
        if (spent >= MAX_SAVES) {
            return false; // All nine lives are gone; this death is real.
        }

        // The save fires. Spend the life first: the permanent
        // counter is the durable record, and the activation's
        // own count rides along for the spec.
        skillData.putInt(LIVES_KEY, spent + 1);
        state.putInt("saves", state.getIntOr("saves", 0) + 1);

        // Back on its feet with a surge of health, the totem
        // way: effects cleared, half health restored, then
        // the design's instant_health heals on top.
        cat.removeAllEffects();
        cat.setHealth(Math.max(2.0f, cat.getMaxHealth() * 0.5f));
        cat.addEffect(new MobEffectInstance(MobEffects.INSTANT_HEALTH, 1, 0));

        // The vanish: invisible, fast, and forgotten by its attackers.
        cat.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, ESCAPE_TICKS, 0));
        cat.addEffect(new MobEffectInstance(MobEffects.SPEED, ESCAPE_TICKS, 1));
        List<LivingEntity> nearby = level.getEntitiesOfClass(LivingEntity.class,
            cat.getBoundingBox().inflate(VANISH_RADIUS),
            e -> e instanceof Mob mob && mob.isAlive() && mob.getTarget() == cat);
        for (LivingEntity pursuer : nearby) {
            ((Mob) pursuer).setTarget(null);
        }

        // Loud and obvious: the vanilla totem pop on every
        // nearby client, a burst of totem motes, and a hiss.
        level.broadcastEntityEvent(cat, (byte) 35);
        level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, cat.getX(), cat.getY() + 0.5, cat.getZ(),
            20, 0.5, 0.5, 0.5, 0.15);
        CatAbilitySupport.playCatSound(level, cat.getX(), cat.getY(), cat.getZ(),
            CatAbilitySupport.CatSound.HISS, 1.2f, 1.2f);
        return true;
    }
}
