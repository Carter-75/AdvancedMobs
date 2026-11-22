package mod.advancedmobs.ability.allay;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * AllayAbilities: the Allay's provider for the new ability
 * system. Supplies the allay's 5 designed abilities
 * (ABILITY-IDEAS.md, Allay): Note Block Harmony, Fetch and
 * Drop, Duet, Sonic Chime, Keepsake Cyclone. The instances
 * are shared singletons; all per-activation state lives in
 * AbilityContext.state(), never in the ability objects.
 */
public final class AllayAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new NoteBlockHarmonyAbility(),
        new FetchAndDropAbility(),
        new DuetAbility(),
        new SonicChimeAbility(),
        new KeepsakeCycloneAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
