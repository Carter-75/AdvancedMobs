package mod.advancedmobs.entity;

import mod.advancedmobs.AdvancedMobsMod;
import mod.advancedmobs.mixin.MobEntityAccessor;
import mod.advancedmobs.system.WarlordSystem;
import mod.advancedmobs.util.SummonerTracker;
import mod.advancedmobs.util.TickScheduler;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.BossEvent;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.world.entity.monster.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import mod.advancedmobs.config.ModConfig;
import java.util.ArrayList;

import java.util.*;

/**
 * WARLORD ENTITY - Part of the Warlord System
 * 
 * The Mob Warlord - A giant boss that summons and commands armies of mobs.
 * All spawned minions are loyal to the warlord and never attack it or each other.
 * Uses Monster for maximum compatibility with Iris Shaders and rendering mods.
 * 
 * Minion tracking is handled by WarlordSystem.java for centralized management.
 * 
 * This entity works with the Warlord System which can be enabled/disabled via:
 * - Config: warlordEnabled
 * 
 * Configurable options (via WarlordSystem):
 * - warlordSpawnChance (default 25%)
 * - warlordMinRaidLevel (default 3)
 * - warlordMinionCount (default 20)
 * - warlordHealthMultiplierPercent (default 300%)
 * - warlordDamageMultiplierPercent (default 200%)
 * 
 * Does NOT depend on: Targeting, Alliance, or Scaling systems
 */
public class MobWarlordEntity extends Monster {
    
    private static final EntityDataAccessor<Integer> MINION_COUNT = SynchedEntityData.defineId(MobWarlordEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> IS_RAID_BOSS = SynchedEntityData.defineId(MobWarlordEntity.class, EntityDataSerializers.BOOLEAN);
    
    // Local tracking of this warlord's minions (for quick access, syncs with WarlordSystem)
    private final Set<UUID> minionUuids = new HashSet<>();
    
    private final ServerBossEvent bossBar;
    private int attackCooldown = 0;
    
    // OPTIMIZATION: UUID-based tick offsets for staggering operations across multiple Warlords
    private final int particleOffset; // 0-30 tick offset for particle drawing
    private final int cleanupOffset; // 0-100 tick offset for cleanup cycles
    private final int validationOffset; // 0-60 tick offset for validation
    private final int summonOffset; // 0-40 tick offset for summoning checks
    
    // Get max minions from config via WarlordSystem
    private static int getMaxMinions() {
        return WarlordSystem.getMaxMinions();
    }
    private static final int SUMMON_COOLDOWN = 40; // 2 seconds - FASTER ally spawning!
    private static final int ATTACK_COOLDOWN = 40; // 2 seconds
    private static final int CLEANUP_COOLDOWN = 100; // 5 seconds - performance optimization for large modpacks
    private static final int PARTICLE_COOLDOWN = 30; // 1.5 seconds - optimized particle connections
    
    public MobWarlordEntity(EntityType<? extends Monster> entityType, Level world) {
        super(entityType, world);
        this.xpReward = 500; // Massive XP drop
        // Apply the config health and damage multipliers, which previously
        // did nothing at all. The attribute supplier values (1500 HP, 12
        // damage) are the values AT the default percents (300 and 200), so
        // scale relative to those defaults: default config changes nothing,
        // and the settings scale the boss proportionally from there. Done
        // in the constructor so every spawn path gets it. Warlords loaded
        // from a save overwrite these base values with their saved
        // attributes immediately after construction, so a loaded boss is
        // never double-scaled.
        ModConfig warlordConfig = ModConfig.getInstance();
        var healthAttribute = this.getAttribute(Attributes.MAX_HEALTH);
        if (healthAttribute != null) {
            healthAttribute.setBaseValue(1500.0 * warlordConfig.getWarlordHealthMultiplier() / 3.0);
            this.setHealth(this.getMaxHealth());
        }
        var damageAttribute = this.getAttribute(Attributes.ATTACK_DAMAGE);
        if (damageAttribute != null) {
            damageAttribute.setBaseValue(12.0 * warlordConfig.getWarlordDamageMultiplier() / 2.0);
        }
        this.bossBar = new ServerBossEvent(
            this.getUUID(),
            Component.literal("🔮 Mob Warlord 🔮").withStyle(style -> style.withColor(ChatFormatting.DARK_PURPLE).withBold(true)),
            BossEvent.BossBarColor.PURPLE,
            BossEvent.BossBarOverlay.NOTCHED_10
        );
        // FIX: Start with summon cooldown at 0 so boss summons immediately after initialization
        // (Field removed in cleanup; if cooldown logic is needed, re-add as appropriate)
        
        // OPTIMIZATION: Calculate UUID-based offsets for staggering operations
        int hash = Math.abs(this.getUUID().hashCode());
        this.particleOffset = hash % 30;
        this.cleanupOffset = hash % 100;
        this.validationOffset = hash % 60;
        this.summonOffset = hash % 40;
    }
    
    @Override
    public Component getName() {
        return Component.literal("Mob Warlord").withStyle(style -> style.withColor(ChatFormatting.DARK_PURPLE).withBold(true));
    }
    
    @Override
    protected void registerGoals() {
        // Don't call super - we're completely replacing witch behavior
        // Note: We can't clear() in this API version, but our high-priority goals will override witch's goals
        
        // Add custom warlord goals with highest priorities
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new MobWarlordAttackGoal(this));
        this.goalSelector.addGoal(2, new WaterAvoidingRandomStrollGoal(this, 0.8));
        this.goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 16.0f));
        this.goalSelector.addGoal(3, new RandomLookAroundGoal(this));
        
        // Targeting priorities (context-aware based on raid status):
        // 0. Protect minions - attack anyone who hurts them (HIGHEST PRIORITY)
        // 1. Revenge - attack anyone who attacks the warlord (except minions - friendly fire forgiven)
        // 2. AGGRESSIVE: Target ANY hostile/neutral mob that's angry at ANYTHING (NEW!)
        // 3. RAID MODE: Villagers (priority 2) → Iron Golems (priority 3) → Players (priority 5)
        // 3. NORMAL MODE: Players (priority 3) → Other mobs (priority 4)
        this.targetSelector.addGoal(0, new ProtectMinionsGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this) {
            @Override
            public boolean canUse() {
                // Don't retaliate against our own minions (forgive friendly fire)
                LivingEntity attacker = this.mob.getLastHurtByMob();
                if (attacker instanceof Mob attackerMob && isMinionOf(attackerMob)) {
                    return false;
                }
                return super.canUse();
            }
        });
        
        // PRIORITY 2: Target OTHER WARLORDS FIRST in chaos mode (kill boss = kill all minions!)
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, MobWarlordEntity.class, 1, true, false,
            (entity, level) -> {
                if (entity == this) return false; // Don't target self
                
                // Only target other warlords in chaos mode
                boolean ignoreSame = level.getGameRules().get(AdvancedMobsMod.IGNORE_SAME_SPECIES_RULE);
                return !ignoreSame; // Only in chaos mode
            }
        ));
        
        // PRIORITY 3: RAID-SPECIFIC TARGETING - Villagers (when in raid)
        this.targetSelector.addGoal(3, new RaidAwareTargetGoal<>(this, net.minecraft.world.entity.npc.villager.Villager.class, true));
        
        // PRIORITY 4: RAID-SPECIFIC TARGETING - Iron Golems (when in raid)
        this.targetSelector.addGoal(4, new RaidAwareTargetGoal<>(this, net.minecraft.world.entity.animal.golem.IronGolem.class, true));
        
        // PRIORITY 5: PLAYERS - Always target players aggressively
        this.targetSelector.addGoal(5, new NearestAttackableTargetGoal<Player>(this, Player.class, 1, true, false, null));
        
        // PRIORITY 6: ALL hostile mobs (including enemy minions, but NOT other warlords)
        this.targetSelector.addGoal(6, new NearestAttackableTargetGoal<>(this, Monster.class, 1, true, false,
            (entity, level) -> {
                if (!(entity instanceof Mob mob)) return false;
                if (isMinionOf(mob)) return false; // Never target own minions
                if (entity instanceof MobWarlordEntity) return false; // Warlords handled by priority 2
                
                return true;
            }
        ));
        
        // PRIORITY 7: Passive mobs and animals (lowest priority)
        this.targetSelector.addGoal(7, new NearestAttackableTargetGoal<net.minecraft.world.entity.animal.Animal>(this, net.minecraft.world.entity.animal.Animal.class, 10, true, false, null));
    }
    
    public static AttributeSupplier.Builder createMobWarlordAttributes() {
        return Monster.createMonsterAttributes()
            .add(Attributes.MAX_HEALTH, 1500.0) // 750 hearts!
            .add(Attributes.MOVEMENT_SPEED, 0.35) // Normal/fast speed
            .add(Attributes.ATTACK_DAMAGE, 12.0)
            .add(Attributes.ARMOR, 10.0)
            .add(Attributes.KNOCKBACK_RESISTANCE, 0.8)
            .add(Attributes.FOLLOW_RANGE, 64.0);
    }
    
    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(MINION_COUNT, 0);
        builder.define(IS_RAID_BOSS, false);
    }
    
    /**
     * Marks this warlord as spawned during a raid.
     */
    public void setRaidBoss(boolean isRaidBoss) {
        this.entityData.set(IS_RAID_BOSS, isRaidBoss);
    }
    
    /**
     * Checks if this warlord was spawned during a raid.
     */
    public boolean isRaidBoss() {
        return this.entityData.get(IS_RAID_BOSS);
    }
    
    /**
     * Marks a minion as a betrayer (attacked other minions).
     * Uses WarlordSystem for centralized tracking.
     */
    public void markBetrayer(UUID minionUuid) {
        if (minionUuids.contains(minionUuid)) {
            minionUuids.remove(minionUuid);
            // Use WarlordSystem for centralized betrayer tracking
            WarlordSystem.markBetrayer(minionUuid, this.getUUID());
        }
    }
    
    /**
     * Checks if a minion is a betrayer.
     * Uses WarlordSystem for centralized tracking.
     */
    public boolean isBetrayer(UUID minionUuid) {
        return WarlordSystem.isBetrayer(minionUuid, this.getUUID());
    }
    
    @Override
    public void tick() {
        // CRITICAL: Safety checks before calling super.tick()
        // This prevents crashes during chunk loading / world initialization
        if (this.level() == null) return;
        
        // Client-side rendering: wrap in try-catch for Iris Shaders compatibility
        if (this.level().isClientSide()) {
            try {
                super.tick();
            } catch (Exception e) {
                // Silently ignore rendering errors (Iris Shaders compatibility)
            }
            return;
        }
        
        if (!(this.level() instanceof ServerLevel serverWorld)) {
            try {
                super.tick();
            } catch (Exception e) {
                // Silently ignore errors
            }
            return;
        }
        
        // Additional safety: ensure entity is fully initialized
        // Wait longer (40 ticks = 2 seconds) to ensure all rendering systems are ready
        if (this.tickCount < 40) {
            try {
                super.tick();
            } catch (Exception e) {
                // Silently ignore initialization errors
            }
            return; // Skip first 2 seconds to ensure world/rendering is ready
        }
        
        // Wrap super.tick() to catch any particle/rendering errors from Witch
        try {
            super.tick();
        } catch (Exception e) {
            // Silently ignore witch particle errors
        }
        
        // Update boss bar safely
        try {
            if (this.bossBar != null) {
                this.bossBar.setProgress(this.getHealth() / this.getMaxHealth());
            }
        } catch (Exception e) {
            // Ignore boss bar errors during initialization
        }
        
        // Cooldowns
        if (attackCooldown > 0) attackCooldown--;
        
        // OPTIMIZED: Clean up dead minions with UUID offset (spreads multiple Warlords over 100 ticks)
        if ((this.tickCount + cleanupOffset) % CLEANUP_COOLDOWN == 0 && this.tickCount > 60) {
            cleanupDeadMinions();
        }
        
        // OPTIMIZED: Draw particle connections with UUID offset (spreads multiple Warlords over 30 ticks)
        if ((this.tickCount + particleOffset) % PARTICLE_COOLDOWN == 0 && this.tickCount > 60) {
            drawParticleConnections();
        }
        
        // OPTIMIZED: Auto-summon with UUID offset (spreads summon checks across 40 ticks)
        if ((this.tickCount + summonOffset) % SUMMON_COOLDOWN == 0 && this.tickCount > 60) {
            int currentMinions = minionUuids.size();
            float healthPercent = this.getHealth() / this.getMaxHealth();
            
            // More aggressive summoning when hurt
            boolean shouldSummon = currentMinions < getMaxMinions() && (
                currentMinions < 5 || 
                (healthPercent < 0.75f && currentMinions < 10) ||
                (healthPercent < 0.5f && currentMinions < 15)
            );
            
            // Boss summons when there's a target (combat situation)
            if (shouldSummon && this.getTarget() != null) {
                int toSummon = healthPercent < 0.5f ? 3 : healthPercent < 0.75f ? 2 : 1;
                AdvancedMobsMod.LOGGER.debug("Mob Warlord summoning {} minions (current: {}, health: {}%, target: {})", 
                    toSummon, currentMinions, (int)(healthPercent * 100), this.getTarget().getName().getString());
                summonMinions(toSummon);
            }
        }
        
        // DEBUG: Log if boss has no target but should be looking for one.
        // The 64-block entity scan below only feeds this debug line, so skip
        // the scan entirely unless debug logging is actually enabled.
        if (this.tickCount % 100 == 0 && this.tickCount > 60 && AdvancedMobsMod.LOGGER.isDebugEnabled()) { // Every 5 seconds
            LivingEntity currentTarget = this.getTarget();
            if (currentTarget == null && serverWorld != null) {
                // Count nearby hostile entities
                List<LivingEntity> nearbyHostiles = serverWorld.getEntitiesOfClass(
                    LivingEntity.class,
                    this.getBoundingBox().inflate(64.0),
                    entity -> entity != this && entity.isAlive() && 
                             !(entity instanceof MobWarlordEntity) &&
                             !(entity instanceof Mob mob && isMinionOf(mob))
                );
                AdvancedMobsMod.LOGGER.debug("Mob Warlord has NO TARGET! {} potential targets nearby within 64 blocks", 
                    nearbyHostiles.size());
            }
        }
        
        // MOB CONVERSION: When boss kills a mob, there's a 50% chance to convert it to a minion
        // This is handled in the damage method, not here
        
        // Particle effects (skip if world not ready, wait 60 ticks = 3 seconds)
        if (this.tickCount % 20 == 0 && this.tickCount > 60) {
            spawnParticles();
        }
        
        // OPTIMIZED: Periodically validate minion targets with UUID offset (spreads validation across 60 ticks)
        if ((this.tickCount + validationOffset) % 60 == 0 && this.tickCount > 60) {
            validateMinionTargets();
        }
        
        // FIX: Make minions copy boss's target every 2 seconds for coordinated attacks
        if (this.tickCount % 40 == 0 && this.tickCount > 60) {
            LivingEntity bossTarget = this.getTarget();
            if (bossTarget != null && bossTarget.isAlive() && serverWorld != null) {
                for (UUID minionUuid : minionUuids) {
                    try {
                        Entity entity = serverWorld.getEntity(minionUuid);
                        if (entity instanceof Mob minion && minion.isAlive()) {
                            // Only set target if minion doesn't already have one
                            if (minion.getTarget() == null || !minion.getTarget().isAlive()) {
                                minion.setTarget(bossTarget);
                            }
                        }
                    } catch (Exception e) {
                        // Skip problematic minions
                    }
                }
            }
        }
    }
    
    /**
     * Validates minion targets and clears invalid ones.
     * OPTIMIZED: Only checks 3 random minions per cycle to spread load.
     * Also detects betrayal (minions attacking other minions).
     * Uses WarlordSystem for centralized minion tracking.
     */
    private void validateMinionTargets() {
        if (!(this.level() instanceof ServerLevel serverWorld)) return;
        
        // OPTIMIZATION: Only validate 3 random minions per cycle
        List<UUID> minionsList = new ArrayList<>(minionUuids);
        Collections.shuffle(minionsList);
        int checksThisCycle = Math.min(3, minionsList.size());
        
        try {
            for (int i = 0; i < checksThisCycle; i++) {
                UUID minionUuid = minionsList.get(i);
                Entity entity = serverWorld.getEntity(minionUuid);
                if (entity instanceof Mob minion && minion.isAlive()) {
                    LivingEntity target = minion.getTarget();
                    
                    if (target != null) {
                        boolean invalidTarget = false;
                        
                        // Check if targeting the warlord (INVALID)
                        if (target == this) {
                            invalidTarget = true;
                        }
                        
                        // Check if targeting another minion (BETRAYAL DETECTION)
                        // Uses WarlordSystem for centralized tracking
                        if (target instanceof Mob targetMob) {
                            if (WarlordSystem.isMinionOf(targetMob.getUUID(), this.getUUID())) {
                                // BETRAYAL! Mark this minion as traitor
                                markBetrayer(minionUuid);
                                invalidTarget = false; // Allow targeting - they're now an enemy
                                
                                // Send message to nearby players
                                serverWorld.players().forEach(player -> {
                                    if (player.distanceToSqr(minion) <= 1024) // 32 block range
                                        player.sendOverlayMessage(
                                            Component.literal("A minion has betrayed the Warlord!")
                                                .withStyle(style -> style.withColor(ChatFormatting.RED).withBold(true))
                                        );
                                });
                            }
                        }
                        
                        // Clear invalid target (but not betrayers - they can fight)
                        if (invalidTarget) {
                            minion.setTarget(null);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Silently handle errors
        }
    }
    
    /**
     * Draws particle connections from the warlord to all minions.
     * OPTIMIZED: Reduced particle density for better performance.
     * Shows purple/dark purple lines so players can see who is allied.
     * Uses WarlordSystem for particle and betrayer settings.
     */
    private void drawParticleConnections() {
        if (WarlordSystem.areParticlesDisabled()) return; // Performance optimization
        if (!(this.level() instanceof ServerLevel serverWorld)) return;
        if (this.tickCount < 60) return; // Wait for full initialization
        
        try {
            Vec3 bossPos = this.position().add(0, this.getBbHeight() / 2, 0); // Center of boss
            
            // OPTIMIZATION: Limit connections drawn based on minion count
            int drawnCount = 0;
            int maxDraws = Math.min(10, minionUuids.size()); // Reduced from 15 to 10
            
            for (UUID minionUuid : minionUuids) {
                if (drawnCount++ >= maxDraws) break;
                
                try {
                    Entity entity = serverWorld.getEntity(minionUuid);
                    if (entity instanceof Mob minion && minion.isAlive()) {
                        Vec3 minionPos = minion.position().add(0, minion.getBbHeight() / 2, 0);
                        
                        // OPTIMIZATION: Skip particles if minion is very close
                        double distance = minionPos.distanceTo(bossPos);
                        if (distance < 3.0) continue; // Don't draw particles for minions within 3 blocks
                        
                        // Check if betrayer - different color (uses WarlordSystem)
                        boolean isBetrayerMinion = isBetrayer(minionUuid);
                        
                        // Draw particle line from boss to minion
                        Vec3 direction = minionPos.subtract(bossPos);
                        Vec3 step = direction.normalize().scale(1.5); // OPTIMIZED: Particle every 1.5 blocks (was 1.0)
                        
                        // OPTIMIZATION: Fewer particles per connection, scale with minion count
                        int maxParticles = minionUuids.size() > 10 ? 5 : 10; // Reduced from 8/15
                        int particleCount = Math.min((int)(distance / 1.5), maxParticles);
                        for (int i = 0; i < particleCount; i++) {
                            Vec3 particlePos = bossPos.add(step.scale(i));
                            
                            // Choose particle type based on status
                            if (isBetrayerMinion) {
                                // RED particles for betrayers
                                serverWorld.sendParticles(
                                    ParticleTypes.ANGRY_VILLAGER,
                                    particlePos.x, particlePos.y, particlePos.z,
                                    1, 0, 0, 0, 0
                                );
                            } else {
                                // PURPLE particles for loyal minions
                                serverWorld.sendParticles(
                                    ParticleTypes.PORTAL,
                                    particlePos.x, particlePos.y, particlePos.z,
                                    1, 0, 0, 0, 0
                                );
                            }
                        }
                    }
                } catch (Exception e) {
                    // Skip problematic minions
                }
            }
        } catch (Exception e) {
            // Silently handle errors
        }
    }
    
    /**
     * Converts a killed mob into a minion instead of letting it die.
     * Called when the boss deals a killing blow to a mob.
     * 50% chance to convert the mob into a loyal minion.
     * Uses WarlordSystem for centralized minion tracking.
     */
    public void tryConvertKilledMob(Mob killedMob) {
        if (!(this.level() instanceof ServerLevel serverWorld)) return;
        if (minionUuids.size() >= getMaxMinions()) return;
        if (killedMob == null || !killedMob.isAlive()) return;
        
        // CRITICAL: Never convert other Mob Warlords (prevents infinite loops and conflicts)
        if (killedMob instanceof MobWarlordEntity) return;
        
        // CRITICAL: Never convert another warlord's minions (prevents ownership conflicts)
        // Uses WarlordSystem for centralized tracking
        UUID existingMaster = WarlordSystem.getMasterUuid(killedMob.getUUID());
        if (existingMaster != null && !existingMaster.equals(this.getUUID())) {
            // This mob belongs to another warlord - can't steal them!
            return;
        }
        
        // 50% chance to convert
        if (this.random.nextFloat() > 0.5f) return;
        
        // Heal the mob instead of letting it die
        killedMob.setHealth(killedMob.getMaxHealth());
        
        // Add to minion tracking via WarlordSystem
        WarlordSystem.registerMinion(killedMob.getUUID(), this.getUUID());
        minionUuids.add(killedMob.getUUID());
        
        // Register with universal summoner tracker
        SummonerTracker.registerSummoned(killedMob.getUUID(), this.getUUID());
        
        // Make minion persistent
        killedMob.setPersistenceRequired();
        
        // Add tethering goal with HIGHEST priority - keeps minion within 14 blocks of warlord
        ((MobEntityAccessor) killedMob).getGoalSelector().addGoal(0, new StayNearWarlordGoal(killedMob, this));
        
        // Make sure the converted mob will actually attack enemies
        // The UniversalTargetGoal should already be on them, but if not, their normal AI will work
        // Clear any fear/fleeing states
        killedMob.setTarget(null);
        killedMob.setLastHurtByMob(null);
        
        // Force the mob to be aggressive by setting it to angry state if it's angerable
        if (killedMob instanceof NeutralMob angerable) {
            angerable.setLastHurtByMob(null); // Clear any anger at the boss
            angerable.stopBeingAngry(); // Reset anger
        }
        
        LivingEntity warlordTarget = this.getTarget();
        if (warlordTarget != null && warlordTarget.isAlive() && warlordTarget != killedMob) {
            // Validate target isn't another minion - use WarlordSystem
            if (warlordTarget instanceof Mob targetMob) {
                if (WarlordSystem.isMinionOf(targetMob.getUUID(), this.getUUID())) {
                    // Skip - don't set fellow minion as target
                } else {
                    killedMob.setTarget(warlordTarget);
                }
            } else {
                killedMob.setTarget(warlordTarget);
            }
        }
        
        // OPTIMIZED: Conversion particles - reduced count for better performance on kill events
        try {
            if (this.tickCount > 60) {
                serverWorld.sendParticles(ParticleTypes.SOUL,
                    killedMob.getX(), killedMob.getY() + killedMob.getBbHeight() / 2, killedMob.getZ(),
                    20, 0.5, 0.5, 0.5, 0.1); // Reduced from 50 to 20
                serverWorld.sendParticles(ParticleTypes.ENCHANT,
                    killedMob.getX(), killedMob.getY() + killedMob.getBbHeight() / 2, killedMob.getZ(),
                    15, 0.5, 0.5, 0.5, 0.5); // Reduced from 30 to 15
                serverWorld.sendParticles(ParticleTypes.PORTAL,
                    killedMob.getX(), killedMob.getY() + killedMob.getBbHeight() / 2, killedMob.getZ(),
                    10, 0.3, 0.3, 0.3, 0.3); // Reduced from 20 to 10
            }
        } catch (Exception e) {
            // Ignore particle errors
        }
        
        this.playSound(SoundEvents.EVOKER_PREPARE_SUMMON, 1.5f, 0.8f);
        this.playSound(SoundEvents.ZOMBIE_VILLAGER_CURE, 1.0f, 1.5f);
        
        this.entityData.set(MINION_COUNT, minionUuids.size());
        
        //noinspection deprecation
        AdvancedMobsMod.LOGGER.debug("Mob Warlord converted {} into a minion! (UUID: {})", 
            net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(killedMob.getType()), 
            killedMob.getUUID());
    }
    
    /**
     * Summons random hostile/neutral mobs to fight for the warlord.
     * Uses WarlordSystem for centralized minion tracking.
     */
    private void summonMinions(int count) {
        if (!(this.level() instanceof ServerLevel serverWorld)) return;
        
        // Check if neutral mobs should be aggressive
        boolean neutralAggressive = serverWorld.getGameRules().get(AdvancedMobsMod.NEUTRAL_MOBS_AGGRESSIVE_RULE);
        
        for (int i = 0; i < count && minionUuids.size() < getMaxMinions(); i++) {
            EntityType<?> minionType = getRandomMobType(neutralAggressive);
            if (minionType == null) continue;
            
            // Spawn near the warlord
            Vec3 spawnPos = this.position().add(
                (this.random.nextDouble() - 0.5) * 6,
                1,
                (this.random.nextDouble() - 0.5) * 6
            );
            
            Entity entity = minionType.create(serverWorld, EntitySpawnReason.MOB_SUMMONED);
            if (entity instanceof Mob minion) {
                minion.snapTo(spawnPos.x, spawnPos.y, spawnPos.z, this.random.nextFloat() * 360f, 0);
                minion.finalizeSpawn(serverWorld, serverWorld.getCurrentDifficultyAt(minion.blockPosition()), EntitySpawnReason.MOB_SUMMONED, null);
                
                // Ensure minion doesn't despawn
                minion.setPersistenceRequired();
                
                // Spawn the entity first
                boolean spawned = serverWorld.addFreshEntity(minion);
                
                if (spawned) {
                    // AFTER spawning, register as minion via WarlordSystem
                    WarlordSystem.registerMinion(minion.getUUID(), this.getUUID());
                    minionUuids.add(minion.getUUID());
                    
                    // ALSO register with universal summoner tracker
                    SummonerTracker.registerSummoned(minion.getUUID(), this.getUUID());
                    
                    // Add tethering goal with HIGHEST priority - keeps minion within 14 blocks of warlord
                    ((MobEntityAccessor) minion).getGoalSelector().addGoal(0, new StayNearWarlordGoal(minion, this));
                    
                    // Special AI for creepers - make them avoid allies when exploding
                    if (minion instanceof net.minecraft.world.entity.monster.Creeper creeper) {
                        // Add smart creeper goal that checks for allies before exploding
                        ((MobEntityAccessor) creeper).getGoalSelector().addGoal(1, new SmartCreeperGoal(creeper, this));
                    }
                    
                    // Set target to warlord's target (validate it's not another minion or the warlord)
                    LivingEntity warlordTarget = this.getTarget();
                    if (warlordTarget != null && warlordTarget.isAlive()) {
                        // Don't target other minions - use WarlordSystem
                        boolean targetIsMinion = false;
                        if (warlordTarget instanceof Mob targetMob) {
                            targetIsMinion = WarlordSystem.isMinionOf(targetMob.getUUID(), this.getUUID());
                        }
                        
                        // Only set valid targets
                        if (!targetIsMinion) {
                            minion.setTarget(warlordTarget);
                        }
                    }
                    
                    // Spawn effects (after initialization delay for Iris Shaders compatibility)
                    try {
                        if (this.tickCount > 60) { // Wait 3 seconds to ensure rendering is ready
                            serverWorld.sendParticles(ParticleTypes.PORTAL, 
                                spawnPos.x, spawnPos.y, spawnPos.z, 
                                20, 0.5, 0.5, 0.5, 0.1);
                        }
                    } catch (Exception e) {
                        // Silently ignore particle errors
                    }
                    this.playSound(SoundEvents.EVOKER_CAST_SPELL, 1.0f, 1.0f);
                }
            }
        }
        
        this.entityData.set(MINION_COUNT, minionUuids.size());
    }
    
    /**
     * Gets a random hostile or neutral mob type for the Warlord to summon.
     * 
     * HOSTILE MOBS (22 types):
     * - Common: Zombie, Skeleton, Creeper, Spider, Cave Spider
     * - Magical: Witch, Blaze, Enderman, Vex, Evoker
     * - Nether: Zombified Piglin, Piglin, Hoglin, Piglin Brute, Wither Skeleton
     * - Illagers: Vindicator, Pillager, Ravager, Illusioner
     * - Other: Stray, Husk, Drowned
     * 
     * NEUTRAL MOBS (5 types, when advancedMobsNeutralAggressive is enabled):
     * - Iron Golem, Wolf, Polar Bear, Panda, Bee
     */
    private EntityType<?> getRandomMobType(boolean includeNeutral) {
        List<EntityType<?>> possibleMobs = new ArrayList<>();
        
        // HOSTILE MOBS (always available)
        // Common undead
        possibleMobs.add(EntityTypes.ZOMBIE);
        possibleMobs.add(EntityTypes.SKELETON);
        possibleMobs.add(EntityTypes.HUSK);
        possibleMobs.add(EntityTypes.STRAY);
        possibleMobs.add(EntityTypes.DROWNED);
        possibleMobs.add(EntityTypes.WITHER_SKELETON);
        
        // Common monsters
        possibleMobs.add(EntityTypes.CREEPER);
        possibleMobs.add(EntityTypes.SPIDER);
        possibleMobs.add(EntityTypes.CAVE_SPIDER);
        
        // Magical mobs
        possibleMobs.add(EntityTypes.WITCH);
        possibleMobs.add(EntityTypes.BLAZE);
        possibleMobs.add(EntityTypes.ENDERMAN);
        possibleMobs.add(EntityTypes.VEX);          // Flying, phases through walls!
        possibleMobs.add(EntityTypes.EVOKER);       // Summons vexes, powerful caster
        
        // Nether mobs
        possibleMobs.add(EntityTypes.ZOMBIFIED_PIGLIN);
        possibleMobs.add(EntityTypes.PIGLIN);
        possibleMobs.add(EntityTypes.PIGLIN_BRUTE);
        possibleMobs.add(EntityTypes.HOGLIN);
        
        // Illagers (raid mobs)
        possibleMobs.add(EntityTypes.VINDICATOR);
        possibleMobs.add(EntityTypes.PILLAGER);
        possibleMobs.add(EntityTypes.RAVAGER);      // Tank, powerful charge attack
        
        // NEUTRAL MOBS (only if gamerule enables neutral aggression)
        if (includeNeutral) {
            possibleMobs.add(EntityTypes.IRON_GOLEM);  // Very tanky ally
            possibleMobs.add(EntityTypes.WOLF);
            possibleMobs.add(EntityTypes.POLAR_BEAR);
            possibleMobs.add(EntityTypes.PANDA);
            possibleMobs.add(EntityTypes.BEE);
        }
        
        // TODO: Future enhancement - scan for modded mobs dynamically
        
        return possibleMobs.get(this.random.nextInt(possibleMobs.size()));
    }
    
    /**
     * Cleans up dead or despawned minions.
     * Only runs every 5 seconds to avoid performance issues with large modpacks.
     */
    /**
     * Cleans up dead or despawned minions.
     * Only runs every 5 seconds to avoid performance issues with large modpacks.
     * Uses WarlordSystem for centralized minion tracking.
     */
    private void cleanupDeadMinions() {
        if (this.level() == null || !(this.level() instanceof ServerLevel serverWorld)) return;
        if (this.tickCount < 60) return; // Skip during initialization (3 seconds)
        
        // OPTIMIZATION: Skip cleanup if no minions
        if (minionUuids.isEmpty()) {
            return;
        }
        
        try {
            // Performance optimization: Create a list to avoid concurrent modification
            List<UUID> toRemove = new ArrayList<>();
            
            // Limit how many we check at once for large modpacks
            int checked = 0;
            int maxChecks = 10; // Check max 10 minions per cleanup cycle
            
            for (UUID uuid : minionUuids) {
                if (checked++ >= maxChecks) break;
                
                try {
                    Entity entity = serverWorld.getEntity(uuid);
                    boolean isDead = entity == null || !entity.isAlive();
                    
                    if (isDead) {
                        toRemove.add(uuid);
                        // Use WarlordSystem to unregister
                        WarlordSystem.unregisterMinion(uuid);
                    }
                } catch (Exception e) {
                    // Skip problematic UUIDs
                    toRemove.add(uuid); // Remove problematic minions to prevent future errors
                    WarlordSystem.unregisterMinion(uuid);
                }
            }
            
            // Remove all dead minions from local list
            minionUuids.removeAll(toRemove);
            
            // Update data tracker safely
            if (this.entityData != null) {
                this.entityData.set(MINION_COUNT, minionUuids.size());
            }
        } catch (Exception e) {
            // Silently ignore errors during world loading
        }
    }
    
    /**
     * Checks if a mob is a minion of this warlord.
     * Uses WarlordSystem for centralized tracking.
     */
    public boolean isMinionOf(Mob mob) {
        return WarlordSystem.isMinionOf(mob.getUUID(), this.getUUID());
    }
    
    /**
     * Static helper to check if a mob is a minion of any warlord.
     * Uses WarlordSystem for centralized tracking.
     */
    public static boolean isMinion(UUID mobUuid) {
        return WarlordSystem.isMinion(mobUuid);
    }
    
    /**
     * Static helper to get the warlord UUID for a minion.
     * Uses WarlordSystem for centralized tracking.
     */
    public static UUID getMasterUuid(UUID minionUuid) {
        return WarlordSystem.getMasterUuid(minionUuid);
    }
    
    /**
     * Checks if a mob is a raid mob (pillagers, vindicators, ravagers, etc.).
     * Used to avoid targeting raid allies when boss is spawned in a raid.
     */

    
    /**
     * Finds a low-health minion that needs healing.
     * Returns null if no minions need healing.
     */
    private Mob findLowHealthMinion() {
        if (!(this.level() instanceof ServerLevel serverWorld)) return null;
        
        Mob lowestHealthMinion = null;
        float lowestHealthPercent = 1.0f;
        
        for (UUID minionUuid : minionUuids) {
            try {
                Entity entity = serverWorld.getEntity(minionUuid);
                if (entity instanceof Mob minion && minion.isAlive()) {
                    float healthPercent = minion.getHealth() / minion.getMaxHealth();
                    
                    // Find minion with lowest health below 50%
                    if (healthPercent < 0.5f && healthPercent < lowestHealthPercent) {
                        lowestHealthPercent = healthPercent;
                        lowestHealthMinion = minion;
                    }
                }
            } catch (Exception e) {
                // Skip problematic minions
            }
        }
        
        return lowestHealthMinion;
    }
    
    /**
     * Checks if there are minions near the target that would be hit by splash damage.
     */
    private boolean minionsNearTarget(LivingEntity target, double radius) {
        if (!(this.level() instanceof ServerLevel serverWorld)) return false;
        
        List<Mob> nearbyMobs = serverWorld.getEntitiesOfClass(
            Mob.class,
            target.getBoundingBox().inflate(radius),
            mob -> mob != target && isMinionOf(mob)
        );
        
        return !nearbyMobs.isEmpty();
    }
    
    /**
     * Smart ranged attack system with dynamic decision making.
     * Priority:
     * 0. SELF-HEALING - heal the boss if health is low (HIGHEST PRIORITY)
     * 1. Self-defense - attack current target if being attacked
     * 2. Heal critical minions (below 50% health)
     * 3. Attack enemies (avoiding friendly fire)
     * 4. Buff healthy minions
     */
    public void performRangedAttack(LivingEntity target) {
        if (attackCooldown > 0) return;
        if (!(this.level() instanceof ServerLevel serverWorld)) return;
        
        // PRIORITY 0: SELF-HEALING - Boss heals itself when health is below 70%
        float healthPercent = this.getHealth() / this.getMaxHealth();
        if (healthPercent < 0.7f) { // Changed back to 0.7 - boss should heal more often!
            // Throw a super healing potion at self
            AdvancedMobsMod.LOGGER.debug("Mob Warlord self-healing! Health: {}% ({}/{})", 
                (int)(healthPercent * 100), (int)this.getHealth(), (int)this.getMaxHealth());
            throwSuperHealingPotion(this);
            attackCooldown = ATTACK_COOLDOWN / 2; // Shorter cooldown for self-healing
            return;
        }
        
        // PRIORITY 1: Self-defense - if boss has a target (being attacked), deal with threat first
        if (target != null && target.isAlive()) {
            // Check if minions would be hit by splash damage (4 block radius)
            boolean minionsInSplashRange = minionsNearTarget(target, 4.0);
            
            if (minionsInSplashRange) {
                // Don't throw harmful potion if it would hit minions
                // Try melee instead or skip this attack
                if (this.distanceToSqr(target) <= 6.0) {
                    // Close enough for melee, let melee goal handle it
                    return;
                } else {
                    // Too far for melee, skip this harmful potion to avoid friendly fire
                    attackCooldown = ATTACK_COOLDOWN / 2; // Shorter cooldown to try again soon
                    return;
                }
            }
            
            // Safe to throw harmful potion at target
            throwHarmfulPotion(target);
            attackCooldown = ATTACK_COOLDOWN;
            return;
        }
        
        // PRIORITY 2: Heal low-health minions
        Mob lowHealthMinion = findLowHealthMinion();
        if (lowHealthMinion != null) {
            double distanceToMinion = this.distanceToSqr(lowHealthMinion);
            
            // Check if we're in range to throw healing potion
            if (distanceToMinion <= 256.0) // 16 block range
                {
                // Check if enemies are nearby that would also get healed
                List<LivingEntity> nearbyEnemies = serverWorld.getEntitiesOfClass(
                    LivingEntity.class,
                    lowHealthMinion.getBoundingBox().inflate(4.0),
                    entity -> entity != this && entity != lowHealthMinion && 
                             !(entity instanceof Mob mob && isMinionOf(mob)) &&
                             entity.isAlive()
                );
                
                if (nearbyEnemies.isEmpty()) {
                    // Safe to heal - no enemies will benefit
                    throwHealingPotion(lowHealthMinion);
                    attackCooldown = ATTACK_COOLDOWN;
                    return;
                } else {
                    // Enemies nearby - don't heal yet, focus on combat
                    // Fall through to buff minions or do nothing
                }
            } else {
                // Out of range - navigation will be handled by combat goal
                // For now, just skip
            }
        }
        
        // PRIORITY 3: Buff minions (if we have target but can't safely attack)
        // Find nearest minion to buff
        Mob nearestMinion = null;
        double nearestDistance = Double.MAX_VALUE;
        
        for (UUID minionUuid : minionUuids) {
            try {
                Entity entity = serverWorld.getEntity(minionUuid);
                if (entity instanceof Mob minion && minion.isAlive()) {
                    double distance = this.distanceToSqr(minion);
                    if (distance < nearestDistance && distance <= 256.0) {
                        nearestDistance = distance;
                        nearestMinion = minion;
                    }
                }
            } catch (Exception e) {
                // Skip problematic minions
            }
        }
        
        if (nearestMinion != null) {
            // Check if enemies would also get buffed
            List<LivingEntity> nearbyEnemies = serverWorld.getEntitiesOfClass(
                LivingEntity.class,
                nearestMinion.getBoundingBox().inflate(4.0),
                entity -> entity != this && !(entity instanceof Mob mob && isMinionOf(mob)) && entity.isAlive()
            );
            
            if (nearbyEnemies.isEmpty()) {
                // Safe to buff - no enemies will benefit
                throwBuffPotion(nearestMinion);
                attackCooldown = ATTACK_COOLDOWN * 2; // Longer cooldown for buffs
                return;
            }
        }
        
        // If we can't do anything safely, just wait
        attackCooldown = ATTACK_COOLDOWN / 2;
    }
    
    /**
     * Throws a harmful potion at an enemy.
     */
    private void throwHarmfulPotion(LivingEntity target) {
        AbstractThrownPotion potionEntity = new net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion(this.level(), this, new ItemStack(Items.SPLASH_POTION));
        ItemStack potion = new ItemStack(Items.SPLASH_POTION);
        
        List<MobEffectInstance> harmfulEffects = List.of(
            new MobEffectInstance(MobEffects.POISON, 200, 1),     // 10 seconds, Poison II
            new MobEffectInstance(MobEffects.WEAKNESS, 300, 1),   // 15 seconds, Weakness II
            new MobEffectInstance(MobEffects.SLOWNESS, 200, 1),   // 10 seconds, Slowness II
            new MobEffectInstance(MobEffects.WITHER, 100, 0)      // 5 seconds, Wither I
        );
        
        potion.set(DataComponents.POTION_CONTENTS, 
            new PotionContents(java.util.Optional.empty(), java.util.Optional.of(0x330033), harmfulEffects, java.util.Optional.empty()));
        
        Vec3 vec3d = target.getEyePosition().subtract(this.getEyePosition());
        potionEntity.setItem(potion);
        potionEntity.shoot(vec3d.x, vec3d.y + vec3d.length() * 0.2, vec3d.z, 0.75f, 8.0f);
        this.level().addFreshEntity(potionEntity);
        
        // Dark purple particles
        if (this.level() instanceof ServerLevel serverWorld && this.tickCount > 60) {
            try {
                serverWorld.sendParticles(ParticleTypes.WITCH,
                    this.getX(), this.getY() + 1.5, this.getZ(),
                    10, 0.3, 0.3, 0.3, 0.05);
            } catch (Exception e) {
                // Ignore particle errors
            }
        }
        
        this.playSound(SoundEvents.WITCH_THROW, 1.0f, 0.8f + this.random.nextFloat() * 0.4f);
    }
    
    /**
     * Throws a SUPER healing potion at the boss itself or critical minions.
     * Much stronger than regular healing potions.
     */
    private void throwSuperHealingPotion(LivingEntity target) {
        AbstractThrownPotion potionEntity = new net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion(this.level(), this, new ItemStack(Items.SPLASH_POTION));
        ItemStack potion = new ItemStack(Items.SPLASH_POTION);
        
        List<MobEffectInstance> superHealingEffects = List.of(
            new MobEffectInstance(MobEffects.INSTANT_HEALTH, 1, 3),      // Instant Health IV (8 hearts)
            new MobEffectInstance(MobEffects.REGENERATION, 400, 2),      // 20 seconds, Regeneration III
            new MobEffectInstance(MobEffects.RESISTANCE, 600, 2),        // 30 seconds, Resistance III
            new MobEffectInstance(MobEffects.ABSORPTION, 600, 2)         // 30 seconds, Absorption III (6 extra hearts)
        );
        
        potion.set(DataComponents.POTION_CONTENTS,
            new PotionContents(java.util.Optional.empty(), java.util.Optional.of(0xFFD700), superHealingEffects, java.util.Optional.empty())); // Golden
        
        Vec3 vec3d = target.getEyePosition().subtract(this.getEyePosition());
        potionEntity.setItem(potion);
        potionEntity.shoot(vec3d.x, vec3d.y + vec3d.length() * 0.2, vec3d.z, 0.75f, 8.0f);
        this.level().addFreshEntity(potionEntity);
        
        // Golden healing particles
        if (this.level() instanceof ServerLevel serverWorld && this.tickCount > 60) {
            try {
                serverWorld.sendParticles(ParticleTypes.ENCHANTED_HIT,
                    this.getX(), this.getY() + 1.5, this.getZ(),
                    20, 0.5, 0.5, 0.5, 0.2);
            } catch (Exception e) {
                // Ignore particle errors
            }
        }
        
        this.playSound(SoundEvents.PLAYER_LEVELUP, 1.0f, 1.5f); // Epic healing sound
    }
    
    /**
     * Throws a healing potion at a low-health minion.
     */
    private void throwHealingPotion(Mob minion) {
        AbstractThrownPotion potionEntity = new net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion(this.level(), this, new ItemStack(Items.SPLASH_POTION));
        ItemStack potion = new ItemStack(Items.SPLASH_POTION);
        
        List<MobEffectInstance> healingEffects = List.of(
            new MobEffectInstance(MobEffects.INSTANT_HEALTH, 1, 1),     // Instant Health II
            new MobEffectInstance(MobEffects.REGENERATION, 200, 1),      // 10 seconds, Regeneration II
            new MobEffectInstance(MobEffects.RESISTANCE, 300, 1)         // 15 seconds, Resistance II
        );
        
        potion.set(DataComponents.POTION_CONTENTS,
            new PotionContents(java.util.Optional.empty(), java.util.Optional.of(0xFF1493), healingEffects, java.util.Optional.empty())); // Deep pink
        
        Vec3 vec3d = minion.getEyePosition().subtract(this.getEyePosition());
        potionEntity.setItem(potion);
        potionEntity.shoot(vec3d.x, vec3d.y + vec3d.length() * 0.2, vec3d.z, 0.75f, 8.0f);
        this.level().addFreshEntity(potionEntity);
        
        // Pink/healing particles
        if (this.level() instanceof ServerLevel serverWorld && this.tickCount > 60) {
            try {
                serverWorld.sendParticles(ParticleTypes.HEART,
                    this.getX(), this.getY() + 1.5, this.getZ(),
                    10, 0.3, 0.3, 0.3, 0.1);
            } catch (Exception e) {
                // Ignore particle errors
            }
        }
        
        this.playSound(SoundEvents.WITCH_THROW, 1.0f, 1.2f); // Higher pitch for healing
    }
    
    /**
     * Throws a buff potion at minions.
     */
    private void throwBuffPotion(Mob minion) {
        AbstractThrownPotion potionEntity = new net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion(this.level(), this, new ItemStack(Items.SPLASH_POTION));
        ItemStack potion = new ItemStack(Items.SPLASH_POTION);
        
        List<MobEffectInstance> buffEffects = List.of(
            new MobEffectInstance(MobEffects.STRENGTH, 400, 1),      // 20 seconds, Strength II
            new MobEffectInstance(MobEffects.SPEED, 400, 1),         // 20 seconds, Speed II
            new MobEffectInstance(MobEffects.RESISTANCE, 400, 0)     // 20 seconds, Resistance I
        );
        
        potion.set(DataComponents.POTION_CONTENTS,
            new PotionContents(java.util.Optional.empty(), java.util.Optional.of(0xFF00FF), buffEffects, java.util.Optional.empty())); // Bright purple
        
        Vec3 vec3d = minion.getEyePosition().subtract(this.getEyePosition());
        potionEntity.setItem(potion);
        potionEntity.shoot(vec3d.x, vec3d.y + vec3d.length() * 0.2, vec3d.z, 0.75f, 8.0f);
        this.level().addFreshEntity(potionEntity);
        
        // Purple particles
        if (this.level() instanceof ServerLevel serverWorld && this.tickCount > 60) {
            try {
                serverWorld.sendParticles(ParticleTypes.ENCHANT,
                    this.getX(), this.getY() + 1.5, this.getZ(),
                    15, 0.3, 0.3, 0.3, 0.1);
            } catch (Exception e) {
                // Ignore particle errors
            }
        }
        
        this.playSound(SoundEvents.WITCH_THROW, 1.0f, 1.0f);
    }
    
    /**
     * Performs a melee attack with knockback and particles.
     * Also handles mob conversion on killing blows.
     */
    public void performMeleeAttack(LivingEntity target) {
        if (attackCooldown > 0) return;
        
        // Check if this attack would kill the target (mob only, not players)
        boolean wouldKill = false;
        if (target instanceof Mob mobTarget && !(target instanceof MobWarlordEntity)) {
            float damage = (float)this.getAttributeValue(Attributes.ATTACK_DAMAGE);
            wouldKill = (mobTarget.getHealth() <= damage);
        }
        
        // Perform the attack
        if (this.level() instanceof ServerLevel attackWorld) {
            this.doHurtTarget(attackWorld, target);
        }
        
        // If we would have killed a mob, try to convert it
        if (wouldKill && target instanceof Mob mobTarget && mobTarget.isAlive()) {
            tryConvertKilledMob(mobTarget);
        }
        
        // Knockback effect. 26.2 removed the simple LivingEntity.knockback
        // (strength, x, z) overload, so the classic formula is applied
        // directly: strength scaled by (1 - knockback resistance).
        Vec3 direction = target.position().subtract(this.position()).normalize();
        double kbStrength = 2.0 * (1.0 - target.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
        if (kbStrength > 0.0) {
            Vec3 kbVelocity = target.getDeltaMovement();
            Vec3 kbPush = new Vec3(-direction.x, 0.0, -direction.z).normalize().scale(kbStrength);
            target.setDeltaMovement(
                kbVelocity.x / 2.0 - kbPush.x,
                target.onGround() ? Math.min(0.4, kbVelocity.y / 2.0 + kbStrength) : kbVelocity.y,
                kbVelocity.z / 2.0 - kbPush.z);
            target.needsSync = true;
        }
        
        // Attack particles (purple magic burst)
        if (this.level() instanceof ServerLevel serverWorld && this.tickCount > 60) {
            try {
                Vec3 targetPos = target.position();
                serverWorld.sendParticles(ParticleTypes.WITCH,
                    targetPos.x, targetPos.y + 1.0, targetPos.z,
                    20, 0.5, 0.5, 0.5, 0.1);
                serverWorld.sendParticles(net.minecraft.core.particles.PowerParticleOption.create(ParticleTypes.DRAGON_BREATH, 1.0f),
                    targetPos.x, targetPos.y + 1.0, targetPos.z,
                    10, 0.3, 0.3, 0.3, 0.05);
            } catch (Exception e) {
                // Ignore particle errors
            }
        }
        
        this.playSound(SoundEvents.PLAYER_ATTACK_STRONG, 1.0f, 0.8f);
        this.playSound(SoundEvents.WITHER_HURT, 0.5f, 1.5f); // Magical sound effect
        attackCooldown = ATTACK_COOLDOWN / 2; // Faster melee cooldown
    }
    
    private void spawnParticles() {
        if (ModConfig.getInstance().disableParticles) return; // Performance optimization
        if (this.level() == null) return;
        if (this.tickCount < 60) return; // Wait 3 seconds for full initialization
        if (!(this.level() instanceof ServerLevel serverWorld)) return;
        
        try {
            // Additional null check for serverWorld's particle manager
            if (serverWorld.getServer() != null && !serverWorld.isClientSide()) {
                serverWorld.sendParticles(ParticleTypes.WITCH,
                    this.getX(), this.getY() + 2.0, this.getZ(),
                    2, 0.5, 0.5, 0.5, 0.05); // Reduced from 5 to 2
            }
        } catch (Exception e) {
            // Silently ignore particle errors during initialization
        }
    }
    
    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        
        try {
            if (this.bossBar != null && player != null) {
                this.bossBar.addPlayer(player);
            }
        } catch (Exception e) {
            // Silently ignore errors during world loading
        }
    }
    
    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        
        try {
            if (this.bossBar != null && player != null) {
                this.bossBar.removePlayer(player);
            }
        } catch (Exception e) {
            // Silently ignore errors during world unloading
        }
    }
    
    @Override
    public void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput output) {
        super.addAdditionalSaveData(output);
        
        long[] minionArray = new long[minionUuids.size() * 2];
        int i = 0;
        for (UUID uuid : minionUuids) {
            minionArray[i++] = uuid.getMostSignificantBits();
            minionArray[i++] = uuid.getLeastSignificantBits();
        }
        output.store("Minions", com.mojang.serialization.Codec.LONG_STREAM, java.util.Arrays.stream(minionArray));
        
        // Betrayers are tracked in WarlordSystem, no local save needed
        
        // Save raid status
        output.putBoolean("IsRaidBoss", this.isRaidBoss());
    }
    
    @Override
    public void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput input) {
        super.readAdditionalSaveData(input);
        
        java.util.Optional<java.util.stream.LongStream> minionsTag = input.read("Minions", com.mojang.serialization.Codec.LONG_STREAM);
        if (minionsTag.isPresent()) {
            long[] minionArray = minionsTag.get().toArray();
            minionUuids.clear();
            // The i + 1 bound also guards against a corrupt odd-length array.
            for (int i = 0; i + 1 < minionArray.length; i += 2) {
                UUID uuid = new UUID(minionArray[i], minionArray[i + 1]);
                minionUuids.add(uuid);
                // Re-register in WarlordSystem: that map is runtime-only and
                // is never saved, so without this a reloaded warlord's minions
                // lose their minion protections (they could attack the warlord
                // and each other, and targeting checks would not skip them).
                WarlordSystem.registerMinion(uuid, this.getUUID());
            }
        }
        
        // Betrayers are tracked in WarlordSystem, no local load needed
        
        // Load raid status
        this.setRaidBoss(input.getBooleanOr("IsRaidBoss", this.isRaidBoss()));
    }
    
    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        
        // Update boss bar safely
        try {
            if (this.bossBar != null && this.tickCount > 2) {
                this.bossBar.setProgress(this.getHealth() / this.getMaxHealth());
            }
        } catch (Exception e) {
            // Silently ignore errors during initialization
        }
    }
    
    @Override
    public boolean isPersistenceRequired() {
        return true; // Boss never despawns
    }
    
    @Override
    public boolean fireImmune() {
        return true; // Boss is fire immune
    }
    
    @Override
    public void die(DamageSource damageSource) {
        super.die(damageSource);
        
        // OPTIMIZED: Stagger minion deaths to prevent lag spike (5 per tick, cascading death effect)
        if (!this.level().isClientSide() && this.level() instanceof ServerLevel serverWorld) {
            List<UUID> minionList = new ArrayList<>(minionUuids);
            
            // Schedule staggered minion deaths on the tick scheduler. The old
            // code deferred with server.execute(...) and then slept on the
            // server thread for delayTicks * 50ms per delayed minion, which
            // froze the whole server for about 1.5 seconds when a warlord
            // with 20 minions died. The visible cascade is unchanged.
            for (int i = 0; i < minionList.size(); i++) {
                final UUID minionUuid = minionList.get(i);
                final int delay = i / 5; // 5 minions per tick (0.25 seconds total for 20 minions)

                TickScheduler.schedule(serverWorld.getServer(), delay, () -> killMinion(serverWorld, minionUuid));
            }
            
            minionUuids.clear();
            
            // Victory message
            serverWorld.players().forEach(player -> {
                player.sendSystemMessage(Component.literal("The Mob Warlord has been defeated! Their army crumbles!")
                    .withStyle(style -> style.withColor(ChatFormatting.GOLD).withBold(true)));
            });
        }
    }
    
    /**
     * Kills a minion with particles.
     */
    private void killMinion(ServerLevel serverWorld, UUID minionUuid) {
        Entity entity = serverWorld.getEntity(minionUuid);
        if (entity instanceof LivingEntity minion && minion.isAlive()) {
            minion.hurtServer(serverWorld, minion.damageSources().magic(), Float.MAX_VALUE);
            
            // Death particles
            try {
                serverWorld.sendParticles(ParticleTypes.POOF,
                    minion.getX(), minion.getY() + 1.0, minion.getZ(),
                    10, 0.3, 0.3, 0.3, 0.05);
            } catch (Exception e) {
                // Silently ignore particle errors
            }
        }
        
        // Clean up from WarlordSystem
        WarlordSystem.unregisterMinion(minionUuid);
    }
    
    /**
     * Custom AI goal for protecting minions.
     * The Warlord will target anyone who attacks its minions.
     */
    private static class ProtectMinionsGoal extends Goal {
        private final MobWarlordEntity warlord;
        private LivingEntity attacker;
        private int checkCooldown;
        
        public ProtectMinionsGoal(MobWarlordEntity warlord) {
            this.warlord = warlord;
            this.checkCooldown = 0;
        }
        
        @Override
        public boolean canUse() {
            // Only check every 20 ticks (1 second) for performance with large modpacks
            if (checkCooldown > 0) {
                checkCooldown--;
                return false;
            }
            checkCooldown = 20;
            
            // Don't override if warlord is already targeting someone attacking it
            if (this.warlord.getLastHurtByMob() != null) {
                return false;
            }
            
            // Check if any minions are being attacked
            if (!(this.warlord.level() instanceof ServerLevel serverWorld)) return false;
            
            // Performance optimization: limit how many minions we check at once
            int checkedCount = 0;
            int maxChecks = 5; // Only check 5 minions per check cycle
            
            for (UUID minionUuid : this.warlord.minionUuids) {
                if (checkedCount++ >= maxChecks) break; // Stop after checking max minions
                
                try {
                    Entity entity = serverWorld.getEntity(minionUuid);
                    if (entity instanceof Mob minion && minion.isAlive()) {
                        LivingEntity minionAttacker = minion.getLastHurtByMob();
                        
                        // Found someone attacking our minion!
                        if (minionAttacker != null && minionAttacker.isAlive() && minionAttacker != this.warlord) {
                            // Don't attack other minions
                            if (minionAttacker instanceof Mob && this.warlord.isMinionOf((Mob) minionAttacker)) {
                                continue;
                            }
                            
                            this.attacker = minionAttacker;
                            return true;
                        }
                    }
                } catch (Exception e) {
                    // Skip problematic minions
                    continue;
                }
            }
            
            return false;
        }
        
        @Override
        public void start() {
            this.warlord.setTarget(this.attacker);
            
            // Play angry sound
            this.warlord.playSound(SoundEvents.RAVAGER_ROAR, 1.0f, 0.8f);
            
            // Spawn angry particles
            if (this.warlord.level() instanceof ServerLevel serverWorld && this.warlord.tickCount > 60) {
                try {
                    serverWorld.sendParticles(ParticleTypes.ANGRY_VILLAGER,
                        this.warlord.getX(), this.warlord.getY() + 2.5, this.warlord.getZ(),
                        5, 0.5, 0.5, 0.5, 0.0);
                } catch (Exception e) {
                    // Ignore particle errors
                }
            }
        }
        
        @Override
        public boolean canContinueToUse() {
            return this.attacker != null && this.attacker.isAlive();
        }
        
        @Override
        public void stop() {
            this.attacker = null;
        }
    }
    
    /**
     * Custom AI goal for the Warlord's combat behavior.
     */
    private static class MobWarlordAttackGoal extends Goal {
        private final MobWarlordEntity warlord;
        private LivingEntity target;
        private int updateCountdownTicks;
        
        public MobWarlordAttackGoal(MobWarlordEntity warlord) {
            this.warlord = warlord;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }
        
        @Override
        public boolean canUse() {
            LivingEntity target = this.warlord.getTarget();
            if (target != null && target.isAlive()) {
                // CRITICAL: Never target our own minions
                if (target instanceof Mob targetMob && this.warlord.isMinionOf(targetMob)) {
                    this.warlord.setTarget(null);
                    return false;
                }
                
                this.target = target;
                return true;
            }
            return false;
        }
        
        @Override
        public void start() {
            this.updateCountdownTicks = 0;
        }
        
        @Override
        public void stop() {
            this.target = null;
        }
        
        @Override
        public void tick() {
            if (this.target == null) return;
            
            this.warlord.getLookControl().setLookAt(this.target, 30.0f, 30.0f);
            double distance = this.warlord.distanceToSqr(this.target);
            
            --this.updateCountdownTicks;
            
            if (distance <= 6.0) {
                // Melee range
                if (this.updateCountdownTicks <= 0) {
                    this.updateCountdownTicks = 20;
                    this.warlord.performMeleeAttack(this.target);
                }
            } else if (distance <= 256.0) {
                // Ranged attack
                if (this.updateCountdownTicks <= 0) {
                    this.updateCountdownTicks = 40;
                    this.warlord.performRangedAttack(this.target);
                }
                
                // Move closer if too far
                if (distance > 64.0) {
                    this.warlord.getNavigation().moveTo(this.target, 1.0);
                }
            }
        }
    }
    
    /**
     * Smart AI for creeper minions that avoids exploding near allies.
     * Creepers will only explode if there are 2 or fewer allies nearby.
     */
    private static class SmartCreeperGoal extends Goal {
        private final net.minecraft.world.entity.monster.Creeper creeper;
        private final MobWarlordEntity warlord;
        private int checkCooldown = 0;
        
        public SmartCreeperGoal(net.minecraft.world.entity.monster.Creeper creeper, MobWarlordEntity warlord) {
            this.creeper = creeper;
            this.warlord = warlord;
        }
        
        @Override
        public boolean canUse() {
            // Check every 10 ticks
            if (checkCooldown > 0) {
                checkCooldown--;
                return false;
            }
            checkCooldown = 10;
            
            // Check if creeper is about to explode (fuse time > 0)
            if (this.creeper.getSwellDir() > 0) {
                // Count nearby allies (within explosion radius of 3 blocks)
                if (!(this.creeper.level() instanceof ServerLevel serverWorld)) return false;
                
                List<Mob> nearbyAllies = serverWorld.getEntitiesOfClass(
                    Mob.class,
                    this.creeper.getBoundingBox().inflate(4.0), // Slightly larger than explosion radius
                    mob -> mob != this.creeper && (this.warlord.isMinionOf(mob) || mob == this.warlord)
                );
                
                // If more than 2 allies nearby, cancel explosion
                if (nearbyAllies.size() > 2) {
                    // Force stop fuse (set to not powered/ignited state)
                    try {
                        // We can't directly stop the fuse in the API, but we can make the creeper flee
                        LivingEntity target = this.creeper.getTarget();
                        if (target != null) {
                            // Move away from target to avoid explosion near allies
                            Vec3 awayDirection = this.creeper.position().subtract(target.position()).normalize();
                            Vec3 fleePos = this.creeper.position().add(awayDirection.scale(5.0));
                            this.creeper.getNavigation().moveTo(fleePos.x, fleePos.y, fleePos.z, 1.5);
                            return true;
                        }
                    } catch (Exception e) {
                        // Silently handle errors
                    }
                }
            }
            
            return false;
        }
        
        @Override
        public boolean canContinueToUse() {
            // Continue fleeing for a bit
            return this.creeper.getNavigation().isInProgress();
        }
    }
    
    /**
     * Raid-aware targeting goal that only activates when boss is in raid mode.
     * Used for prioritizing villagers and iron golems during raids.
     */
    private static class RaidAwareTargetGoal<T extends LivingEntity> extends NearestAttackableTargetGoal<T> {
        private final MobWarlordEntity warlord;
        
        public RaidAwareTargetGoal(MobWarlordEntity warlord, Class<T> targetClass, boolean checkVisibility) {
            super(warlord, targetClass, checkVisibility);
            this.warlord = warlord;
        }
        
        @Override
        public boolean canUse() {
            // Only target villagers/golems if in raid mode
            if (!this.warlord.isRaidBoss()) {
                return false;
            }
            
            return super.canUse();
        }
    }
    
    /**
     * Tethering goal that keeps minions within 14 blocks of the Mob Warlord.
     * If a minion strays too far, they will return to the boss regardless of enemy targets.
     * This prevents minions from wandering off and getting separated from their master.
     */
    public static class StayNearWarlordGoal extends Goal {
        private final Mob minion;
        private final MobWarlordEntity warlord;
        private static final double RETURN_DISTANCE = 14.0; // Minions start returning at this distance
        private int checkCooldown = 0;
        
        public StayNearWarlordGoal(Mob minion, MobWarlordEntity warlord) {
            this.minion = minion;
            this.warlord = warlord;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.TARGET));
        }
        
        @Override
        public boolean canUse() {
            // Check every 20 ticks (1 second) for performance
            if (checkCooldown > 0) {
                checkCooldown--;
                return false;
            }
            checkCooldown = 20;
            
            // Check if warlord is still alive
            if (!this.warlord.isAlive()) {
                return false;
            }
            
            // Check if minion is too far from warlord
            double distance = this.minion.distanceToSqr(this.warlord);
            return distance > (RETURN_DISTANCE * RETURN_DISTANCE);
        }
        
        @Override
        public boolean canContinueToUse() {
            // Continue until we're close enough to the warlord
            if (!this.warlord.isAlive()) return false;
            
            double distance = this.minion.distanceToSqr(this.warlord);
            // Stop when within 12 blocks to prevent bouncing
            return distance > (12.0 * 12.0);
        }
        
        @Override
        public void start() {
            // Clear current target to focus on returning
            this.minion.setTarget(null);
            // Navigate back to warlord
            this.minion.getNavigation().moveTo(this.warlord, 1.2); // Slightly faster return speed
        }
        
        @Override
        public void tick() {
            // Keep navigating to warlord's current position
            if (this.minion.tickCount % 10 == 0) { // Update path every 0.5 seconds
                this.minion.getNavigation().moveTo(this.warlord, 1.2);
            }
            
            // Make minion look at the warlord while returning
            this.minion.getLookControl().setLookAt(this.warlord, 30.0f, 30.0f);
        }
        
        @Override
        public void stop() {
            // Stop navigating
            this.minion.getNavigation().stop();
        }
    }
}

