package mod.advancedmobs.ability.illusioner;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * IllusionerAbilities: the Illusioner's provider for the new
 * ability system. Supplies the illusioner's 5 designed
 * abilities (ABILITY-IDEAS.md, Illusioner): Hall of Mirrors,
 * Blindspot Barrage, Kaleidoscope Shift, Phantom Archer
 * Ring, Shattered Image Nova. The instances are shared
 * singletons; all per-activation state lives in
 * AbilityContext.state(), never in the ability objects.
 */
public final class IllusionerAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new HallOfMirrorsAbility(),
        new BlindspotBarrageAbility(),
        new KaleidoscopeShiftAbility(),
        new PhantomArcherRingAbility(),
        new ShatteredImageNovaAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
