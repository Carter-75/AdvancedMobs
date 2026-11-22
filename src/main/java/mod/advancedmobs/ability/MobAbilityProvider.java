package mod.advancedmobs.ability;

import java.util.List;

/**
 * MobAbilityProvider: supplies the full ability set of ONE mob type to the
 * framework. This is the ONLY thing a per-mob implementation step has to
 * write, besides the ability classes themselves:
 *
 *   1. Write the mob's 5 MobAbility implementations (each fully specified in
 *      its own comments, per the rule in MobAbility).
 *   2. Write one provider whose abilities() returns those 5 instances.
 *   3. Add one registration line in the provider's registration site:
 *      AbilityRegistry.register("<config mob_name>", new XyzAbilities());
 *   4. Check the mob off in IMPLEMENTATION-PROGRESS.md.
 *
 * The design target is exactly 5 abilities per mob (Carter, Nov 21, 2026:
 * all five designed abilities get implemented). The framework tolerates a
 * shorter list while a mob is mid-implementation, but a mob only counts as
 * done at 5.
 *
 * The returned list and the ability instances in it must be immutable and
 * shared: the same instances serve every mob of the type, concurrently, so
 * implementations keep all per-activation state in AbilityContext.state().
 * List order does not matter for gameplay (grants and firing picks are
 * random), but keep it stable for readability.
 */
public interface MobAbilityProvider {

    /**
     * The mob's abilities, exactly 5 for a finished mob. Never null, never
     * containing two abilities with the same id().
     */
    List<MobAbility> abilities();
}
