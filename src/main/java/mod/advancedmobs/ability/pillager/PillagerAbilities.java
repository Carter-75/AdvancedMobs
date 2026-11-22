package mod.advancedmobs.ability.pillager;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * PillagerAbilities: the Pillager's provider for the new
 * ability system. Supplies the pillager's 5 designed
 * abilities (ABILITY-IDEAS.md, Pillager): Turret Stance,
 * Firework Broadside, Raid Captain's Call, Outpost Sniper
 * Perch, Powder Keg Toss. The instances are shared
 * singletons; all per-activation state lives in
 * AbilityContext.state(), never in the ability objects.
 */
public final class PillagerAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new TurretStanceAbility(),
        new FireworkBroadsideAbility(),
        new RaidCaptainsCallAbility(),
        new OutpostSniperPerchAbility(),
        new PowderKegTossAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
