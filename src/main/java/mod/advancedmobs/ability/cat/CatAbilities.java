package mod.advancedmobs.ability.cat;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * CatAbilities: the Cat's provider for the new ability
 * system. Supplies the cat's 5 designed abilities
 * (ABILITY-IDEAS.md, Cat): Nine Lives, Creeperbane Aura,
 * Pounce Assassin, Alley Cat Swarm, Night Prowl. The
 * instances are shared singletons; all per-activation
 * state lives in AbilityContext.state(), never in the
 * ability objects.
 *
 * CAT FACTS (verified against the real 26.2 jar): the Cat
 * class sits in net.minecraft.world.entity.animal.feline,
 * extends TamableAnimal, and its createAttributes builds
 * on the Animal base with MAX_HEALTH fixed at 10.0,
 * MOVEMENT_SPEED 0.3, and ATTACK_DAMAGE 3.0. Unlike the
 * horse chain, the cat HAS an ATTACK_DAMAGE attribute
 * instance, so strike damage in this package reads the
 * live attribute value (the mod's damage upgrades and the
 * STRENGTH effect ride it) instead of flat constants. The
 * cat's sounds are not plain SoundEvent constants in
 * 26.2: they resolve from SoundEvents.CAT_SOUNDS, the
 * CLASSIC feline variant's adult sound set (see
 * CatAbilitySupport). The sitting pose used by Creeperbane
 * Aura is TamableAnimal.setInSittingPose, a synced pose
 * flag, not NoAi.
 */
public final class CatAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new NineLivesAbility(),
        new CreeperbaneAuraAbility(),
        new PounceAssassinAbility(),
        new AlleyCatSwarmAbility(),
        new NightProwlAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
