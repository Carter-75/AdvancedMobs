package mod.advancedmobs.ability.enderdragon;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * EnderDragonAbilities: the Ender Dragon's provider for the
 * new ability system. Supplies the dragon's 5 designed
 * abilities (ABILITY-IDEAS.md, Ender Dragon): Crystal
 * Resonance Cascade, Fountain Spike Slam, Void Dive, Scale
 * of the End, Breathfire Ring. The instances are shared
 * singletons; all per-activation state lives in
 * AbilityContext.state(), never in the ability objects.
 */
public final class EnderDragonAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new CrystalResonanceCascadeAbility(),
        new FountainSpikeSlamAbility(),
        new VoidDiveAbility(),
        new ScaleOfTheEndAbility(),
        new BreathfireRingAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
