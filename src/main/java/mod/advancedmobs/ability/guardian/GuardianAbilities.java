package mod.advancedmobs.ability.guardian;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * GuardianAbilities: the Guardian's provider for the new ability
 * system. Supplies the guardian's 5 designed abilities
 * (ABILITY-IDEAS.md, Guardian): Prismarine Spike Mantle, Laser
 * Lattice, Tidal Bulwark Ring, Shard Mine Bloom, Crushing Depth
 * Aura. The instances are shared singletons; all per-activation
 * state lives in AbilityContext.state(), never in the ability
 * objects.
 */
public final class GuardianAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new PrismarineSpikeMantleAbility(),
        new LaserLatticeAbility(),
        new TidalBulwarkRingAbility(),
        new ShardMineBloomAbility(),
        new CrushingDepthAuraAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
