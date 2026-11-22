package mod.advancedmobs.ability.zoglin;

import mod.advancedmobs.data.MobWarData;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zoglin;

/**
 * UndyingBulkSave: the death-save hook behind the Undying Bulk
 * ability (see UndyingBulkAbility for the ability itself and
 * ZoglinUndyingBulkMixin for the injection point).
 *
 * WHY THIS EXISTS: an ability ticks once per AI step, but a
 * killing blow lands between ticks and vanilla processes the
 * death inside LivingEntity.hurtServer itself (verified
 * against the 26.2 jar: hurtServer applies the damage, then,
 * when the entity is dead or dying, calls
 * checkTotemDeathProtection and only calls die() when that
 * returns false). No tick-side polling can intercept that. The
 * one clean interception point is the totem check itself: the
 * mixin asks trySave() at the head of
 * LivingEntity.checkTotemDeathProtection, and a true return
 * makes hurtServer skip die() exactly the way a real totem of
 * undying does.
 *
 * THE SAVE (all of it happens here, at the moment of the
 * killing blow, so the revival is immediate and loud):
 * - Guards, mirroring the vanilla totem's own refusals: a
 *   damage source that bypasses invulnerability (the void,
 *   /kill) is never saved from. One addition of the design's
 *   own: FIRE is never saved from either (DamageTypeTags
 *   .IS_FIRE). The design says fire finishes the job properly
 *   if it lands during the revival moment, so the save grants
 *   no fire protection at all: it does not trigger on a fire
 *   killing blow, it applies no FIRE_RESISTANCE, and it does
 *   not clear the zoglin's burning, so a zoglin that revives
 *   while standing in fire burns back down and dies for real,
 *   the save already spent.
 * - The zoglin must have Undying Bulk active with the save
 *   armed and unspent (the ability's own state tag, read
 *   through MobWarData: active id "undying_bulk", "armed" 1,
 *   "revived" 0).
 * - ONCE ONLY, per mob, forever: the revival's MAX_HEALTH
 *   raise is a permanent attribute modifier (below), and its
 *   presence is the durable record that the save has fired.
 *   If the modifier is already there, the save refuses, so a
 *   later activation of Undying Bulk can arm all it wants and
 *   will never save a second time (the ability's canTrigger
 *   also checks this, so a spent zoglin stops rolling the
 *   ability at all).
 * - The revival itself: the state tag's "revived" flag is set
 *   (the ability sees it on its next tick and ends, starting
 *   its cooldown; the raises below stay). Three PERMANENT
 *   attribute modifiers are applied with
 *   addOrReplacePermanentModifier: MAX_HEALTH add_value +20
 *   (the zoglin's base 40 becomes 60, "permanently raised for
 *   the rest of the fight" in the design's own words, and a
 *   permanent modifier persists with the entity), SCALE
 *   add_multiplied_base +0.30 (it comes back at 1.3 times its
 *   size: "bigger"), ATTACK_DAMAGE add_value +3.0 (base 6
 *   becomes 9: "angrier"). All effects are cleared, the way a
 *   totem clears them (ScalingSystem re-applies the zoglin's
 *   permanent upgrade effects on its own tick, the Living
 *   Stone Shell precedent), and health is set to half of the
 *   NEW maximum: 30 of 60.
 * - The show: the level broadcasts entity event 35, the
 *   vanilla totem pop, so every nearby client renders the
 *   TOTEM_OF_UNDYING particle burst and the totem sound at the
 *   zoglin (the revival is loud and obvious, as designed),
 *   plus a server-sent burst of 24 SOUL wisps and a
 *   ZOGLIN_ANGRY bellow at volume 1.5.
 *
 * Base values quoted above (health 40, attack damage 6) were
 * verified against the real 26.2 jar (Zoglin.createAttributes).
 */
public final class UndyingBulkSave {

    /** The ability id the save belongs to; also the active-id the hook looks for. */
    static final String ABILITY_ID = "undying_bulk";

    private static final Identifier HEALTH_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "undying_bulk_health");
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "undying_bulk_scale");
    private static final Identifier DAMAGE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "undying_bulk_damage");

    private static final double MAX_HEALTH_BONUS = 20.0;
    private static final double SCALE_BONUS = 0.30;
    private static final double DAMAGE_BONUS = 3.0;

    private UndyingBulkSave() {}

    /** Whether this zoglin has already spent its one save (the permanent bulk is on it). */
    static boolean hasSpentBulk(Zoglin zoglin) {
        AttributeInstance maxHealth = zoglin.getAttribute(Attributes.MAX_HEALTH);
        return maxHealth != null && maxHealth.getModifier(HEALTH_ID) != null;
    }

    /**
     * Attempts the death save for a zoglin whose killing blow
     * is being processed. Returns true when the save fired and
     * the death must be prevented (the mixin then returns true
     * from checkTotemDeathProtection); false leaves vanilla
     * death exactly as it was.
     */
    public static boolean trySave(Zoglin zoglin, DamageSource source) {
        // The vanilla totem's own refusal, mirrored: nothing
        // saves from damage that bypasses invulnerability.
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return false;
        }
        // The design's fire rule: fire finishes the job.
        if (source.is(DamageTypeTags.IS_FIRE)) {
            return false;
        }
        if (!(zoglin.level() instanceof ServerLevel level)) {
            return false;
        }
        MobWarData data = MobWarData.get(zoglin);
        if (data == null || !ABILITY_ID.equals(data.getAbilityActiveId())) {
            return false;
        }
        CompoundTag state = data.getAbilityActiveState();
        if (state.getIntOr("armed", 0) != 1 || state.getIntOr("revived", 0) != 0) {
            return false;
        }
        AttributeInstance maxHealth = zoglin.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth == null || maxHealth.getModifier(HEALTH_ID) != null) {
            return false; // No health attribute, or the one save is already spent.
        }

        // The save fires. Mark it spent first: the ability ends
        // on its next tick, and the permanent modifier below is
        // the durable once-only record.
        state.putInt("revived", 1);

        // Bigger and angrier, permanently: these modifiers are
        // the design's explicit exception to the rule that
        // end() reverses what an ability applies, and they are
        // applied here, at the revival, not by start() or end().
        maxHealth.addOrReplacePermanentModifier(
            new AttributeModifier(HEALTH_ID, MAX_HEALTH_BONUS, AttributeModifier.Operation.ADD_VALUE));
        AttributeInstance scale = zoglin.getAttribute(Attributes.SCALE);
        if (scale != null) {
            scale.addOrReplacePermanentModifier(
                new AttributeModifier(SCALE_ID, SCALE_BONUS, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
        AttributeInstance damage = zoglin.getAttribute(Attributes.ATTACK_DAMAGE);
        if (damage != null) {
            damage.addOrReplacePermanentModifier(
                new AttributeModifier(DAMAGE_ID, DAMAGE_BONUS, AttributeModifier.Operation.ADD_VALUE));
        }

        // Stands back up at half health, the totem way: effects
        // cleared, health set against the NEW maximum.
        zoglin.removeAllEffects();
        zoglin.setHealth(zoglin.getMaxHealth() * 0.5f);

        // Loud and obvious: the vanilla totem pop on every
        // nearby client, a burst of soul wisps, and a bellow.
        level.broadcastEntityEvent(zoglin, (byte) 35);
        level.sendParticles(ParticleTypes.SOUL, zoglin.getX(), zoglin.getY() + 1.2, zoglin.getZ(),
            24, 0.8, 0.8, 0.8, 0.10);
        level.playSound(null, zoglin.getX(), zoglin.getY(), zoglin.getZ(), SoundEvents.ZOGLIN_ANGRY,
            SoundSource.HOSTILE, 1.5f, 0.8f);
        return true;
    }
}
