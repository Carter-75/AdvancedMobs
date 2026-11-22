package mod.advancedmobs.ability.ocelot;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * OcelotAbilities: the Ocelot's provider for the new
 * ability system. Supplies the ocelot's 5 designed
 * abilities (ABILITY-IDEAS.md, Ocelot): Jungle Phantom,
 * Raking Fury, Treefall Ambush, Cub Guard, Spotted Mark.
 * The instances are shared singletons; all per-activation
 * state lives in AbilityContext.state(), never in the
 * ability objects.
 *
 * OCELOT FACTS (verified against the real 26.2 jar): the
 * Ocelot class sits in net.minecraft.world.entity.animal.
 * feline, extends Animal, and its createAttributes builds
 * on the Animal base with MAX_HEALTH fixed at 10.0,
 * MOVEMENT_SPEED 0.3, and ATTACK_DAMAGE 3.0. Like the cat,
 * the ocelot HAS an ATTACK_DAMAGE attribute instance, so
 * strike damage in this package reads the live attribute
 * value (the mod's damage upgrades and the STRENGTH effect
 * ride it) instead of flat constants. Unlike the cat, the
 * ocelot's sounds are plain SoundEvent constants in 26.2
 * (SoundEvents.OCELOT_AMBIENT, OCELOT_HURT, OCELOT_DEATH),
 * played directly. The ocelot has NO ATTACK_SPEED
 * attribute instance (LivingEntity's base attribute set
 * does not include one), which shapes Raking Fury: see
 * that class's comment.
 */
public final class OcelotAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new JunglePhantomAbility(),
        new RakingFuryAbility(),
        new TreefallAmbushAbility(),
        new CubGuardAbility(),
        new SpottedMarkAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
