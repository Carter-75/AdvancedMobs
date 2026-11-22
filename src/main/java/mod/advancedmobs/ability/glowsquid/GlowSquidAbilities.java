package mod.advancedmobs.ability.glowsquid;

import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;

import java.util.List;

/**
 * GlowSquidAbilities: the Glow Squid's provider for
 * the new ability system. Supplies the glow squid's
 * 5 designed abilities (ABILITY-IDEAS.md, Glow
 * Squid): Biolume Pulse, Hypno Flicker, Starfall
 * Ink, Deep Dark Cloak, Lure Light. The instances
 * are shared singletons; all per-activation state
 * lives in AbilityContext.state(), never in the
 * ability objects.
 *
 * GLOW SQUID FACTS (verified against the real 26.2
 * jar): the GlowSquid class sits in
 * net.minecraft.world.entity.animal.squid, extends
 * Squid (which extends AgeableWaterCreature), and
 * inherits Squid.createAttributes, which adds only
 * MAX_HEALTH fixed at 10.0 on top of
 * Mob.createMobAttributes (which itself adds only
 * FOLLOW_RANGE on top of the living base). There is
 * NO ATTACK_DAMAGE attribute instance anywhere in
 * the glow squid's chain, so every damage number in
 * this package is a flat code value dealt through a
 * damage source credited to the dealer, the
 * treatment the Cow, Sheep, Chicken, Cod, and Squid
 * packages use for their attack-less mobs; the one
 * design line that names an attack_damage modifier
 * (Biolume Pulse, against marked targets) is
 * therefore carried as a code damage bonus against
 * the marked list, stated in that ability's
 * comments. The glow squid's sounds are plain
 * SoundEvent constants in 26.2 (GLOW_SQUID_AMBIENT,
 * GLOW_SQUID_HURT, GLOW_SQUID_DEATH,
 * GLOW_SQUID_SQUIRT). Its glow is real: the GLOW
 * and GLOW_SQUID_INK particles are SimpleParticle
 * Types, and a hurt glow squid darkens (its vanilla
 * dark ticks), which this package never fights: no
 * ability here forces the vanilla glow state, the
 * light shows are all particles and the GLOWING
 * effect. The glow squid's vanilla swim is a slow
 * wander with no combat navigation, so every
 * movement in this package is per-tick velocity
 * steering (see the support class). The glow squid
 * is fully aquatic: it swims and flops on land, so
 * every ability in this package gates on the caster
 * being in water.
 */
public final class GlowSquidAbilities implements MobAbilityProvider {

    private static final List<MobAbility> ABILITIES = List.of(
        new BiolumePulseAbility(),
        new HypnoFlickerAbility(),
        new StarfallInkAbility(),
        new DeepDarkCloakAbility(),
        new LureLightAbility()
    );

    @Override
    public List<MobAbility> abilities() {
        return ABILITIES;
    }
}
