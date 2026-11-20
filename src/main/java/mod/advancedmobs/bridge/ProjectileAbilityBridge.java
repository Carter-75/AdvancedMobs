package mod.advancedmobs.bridge;

/**
 * Bridge interface implemented by projectile mixins so we can mark
 * projectiles that already processed Advanced Mobs ability logic.
 */
public interface ProjectileAbilityBridge {
    void advancedmobs$setAbilitiesApplied(boolean applied);
}
