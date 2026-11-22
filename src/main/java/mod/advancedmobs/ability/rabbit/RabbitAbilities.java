package mod.advancedmobs.ability.rabbit;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * RabbitAbilities: the Rabbit's provider for the new
 * ability system. Supplies the rabbit's 5 designed
 * abilities (ABILITY-IDEAS.md, Rabbit): Killer Bunny
 * Form, Burrow Ambush, Carrot Rain, Lucky Foot, Warren
 * Call. The instances are shared singletons; all
 * per-activation state lives in AbilityContext.state(),
 * never in the ability objects.
 */
public final class RabbitAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new KillerBunnyFormAbility(),
        new BurrowAmbushAbility(),
        new CarrotRainAbility(),
        new LuckyFootAbility(),
        new WarrenCallAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
