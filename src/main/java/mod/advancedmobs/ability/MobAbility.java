package mod.advancedmobs.ability;

/**
 * MobAbility: ONE special ability of ONE mob type, for the new ability system
 * built on top of the stripped mod (the old ability set was removed on the
 * new-abilities branch; this system replaces it).
 *
 * HOW THE SYSTEM USES AN ABILITY (the contract every implementation must fit):
 * Abilities are STATELESS singletons. One instance of an ability class exists
 * per mob type (created by that mob's MobAbilityProvider) and is shared by
 * every mob of that type. An implementation must therefore NEVER store
 * per-activation data in instance fields: everything an activation needs
 * lives in the per-mob scratch tag exposed by AbilityContext.state(), which
 * the framework persists inside MobWarData (key "am_ability_state"). This is
 * what lets an active ability survive a chunk unload or a server restart:
 * after a reload the framework finds the active ability id in MobWarData and
 * simply resumes calling tick() with the same state tag. start() is NOT
 * called again after a reload, so tick() and isFinished() must work purely
 * from the state tag that start() initialized.
 *
 * LIFECYCLE (driven by AbilitySystem, once per tick per mob):
 *   1. When the mob is in combat, has no active ability, and its cooldown has
 *      expired, the framework collects the mob's accessible abilities whose
 *      canTrigger(ctx) returns true and picks one uniformly at random.
 *   2. start(ctx) is called exactly once for that activation. Use it to
 *      initialize the state tag (durations, targets, phase counters) and to
 *      fire any one-shot opening effect.
 *   3. On each following tick the framework increments the tick counter
 *      (ctx.ticksActive()) and calls tick(ctx). Do the ongoing work here:
 *      particles, movement, damage over time, phase changes.
 *   4. After each tick() the framework calls isFinished(ctx). When it returns
 *      true, the framework calls end(ctx) exactly once (cleanup: remove
 *      attribute modifiers, despawn summons, restore anything start changed),
 *      clears the active ability, and starts the mob's cooldown. The cooldown
 *      clock starts when end() runs, never when start() ran.
 *   An ability that wants to be instant (fire once, no duration) should do
 *   its work in start() and return true from isFinished() immediately; the
 *   framework will call tick() once and then end() on the next tick.
 *
 * SPEC COMMENT RULE (Carter, Nov 20, 2026, still in force): every ability
 * implementation must carry comments stating exactly what the ability does,
 * how it works mechanically (trigger, cooldown behavior, range, numbers,
 * targets, what it spawns or changes), and what it looks like (particles,
 * model or render changes, sounds). The comments are the spec of intended
 * behavior; bugs are fixed against them.
 */
public interface MobAbility {

    /**
     * Stable unique id of this ability within its mob, for example
     * "grave_colossus". Ids are persisted in MobWarData (the mob's accessible
     * set and its active ability), so an id must NEVER be renamed or reused
     * for a different ability once shipped: a saved mob would silently gain
     * or lose abilities. Lowercase snake_case by convention.
     */
    String id();

    /**
     * Human readable display name, for example "Grave Colossus". Used in
     * logs and debug output; may be shown to players later.
     */
    String displayName();

    /**
     * Whether this ability may start right now for the context mob. Called
     * by the framework only when the mob is in combat, idle, and off
     * cooldown, potentially every tick until some ability starts, so it must
     * be cheap and side-effect free: check conditions (target in range,
     * enough room, a needed resource present), never change state here.
     * Returning false simply leaves the ability out of this firing pick.
     */
    boolean canTrigger(AbilityContext ctx);

    /**
     * Begin the activation. Initialize everything the activation needs into
     * ctx.state() and fire any opening effect. Called exactly once per
     * activation (not again after a chunk reload resumes the ability).
     */
    void start(AbilityContext ctx);

    /**
     * Ongoing work, called once per tick after start() until isFinished()
     * returns true. ctx.ticksActive() is 1 on the first tick() call.
     */
    void tick(AbilityContext ctx);

    /**
     * Whether the activation is complete. Checked after every tick() call.
     * Base it only on ctx.state() and the world, so a resumed activation
     * after a reload finishes correctly.
     */
    boolean isFinished(AbilityContext ctx);

    /**
     * Cleanup, called exactly once after isFinished() first returns true.
     * Undo anything start() or tick() changed (attribute modifiers, summoned
     * helpers that should not linger, tags) unless the design says the
     * change is permanent. The framework starts the mob's cooldown right
     * after end() returns.
     */
    void end(AbilityContext ctx);
}
