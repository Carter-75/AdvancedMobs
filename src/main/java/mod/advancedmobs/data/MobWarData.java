package mod.advancedmobs.data;

import net.minecraft.world.entity.Mob;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.*;

/**
 * Tracks mob evolution data, alliance relationships, and combat statistics.
 * This data is stored per-mob using entity NBT data.
 */
public class MobWarData {
    
    // Evolution system
    private int killCount = 0;
    private int level = 0;
    private static final int MAX_LEVEL = 100;
    private static final int KILLS_PER_LEVEL = 3;
    
    // Alliance system - stores UUIDs of allies
    private final Set<UUID> allies = new HashSet<>();
    private UUID currentTarget = null;
    private long lastTargetChangeTime = 0;
    
    // Combat stats
    private double damageDealt = 0;
    private double damageTaken = 0;

    // Skill Tree Data
    private double skillPoints = 0;
    private double spentPoints = 0;
    private CompoundTag skillData = new CompoundTag();

    // New ability system state (AbilitySystem). Persisted under am_ability_
    // keys; the legacy ability_ keys from the removed ability set are inert
    // and intentionally not reused.
    // abilityEvalCount: the accessible-count bracket (1 to 5) this mob was
    // last evaluated at; 0 means never evaluated. See AbilitySystem.
    private int abilityEvalCount = 0;
    // abilityHas: whether the mob passed its access roll (or is the Ender
    // Dragon, which is guaranteed access). Once true it never goes back.
    private boolean abilityHas = false;
    // abilityAccessible: ids of the mob's registered abilities this mob may
    // use, a random subset whose size follows its power bracket.
    private final List<String> abilityAccessible = new ArrayList<>();
    // abilityActiveId: id of the currently running ability, "" when idle.
    private String abilityActiveId = "";
    // abilityActiveState: per-activation scratch tag of the active ability.
    private CompoundTag abilityActiveState = new CompoundTag();
    // abilityCooldownEnd: absolute game time at which the post-ability
    // cooldown ends. The cooldown starts when an ability ends, never when
    // it starts.
    private long abilityCooldownEnd = 0L;

    public MobWarData() {}

    // Skill Tree Methods
    public double getSkillPoints() { return skillPoints; }
    public void setSkillPoints(double points) { this.skillPoints = points; }
    public double getSpentPoints() { return spentPoints; }
    public void setSpentPoints(double points) { this.spentPoints = points; }
    public CompoundTag getSkillData() { return skillData; }
    public void setSkillData(CompoundTag data) { this.skillData = data; }

    // New ability system accessors (see AbilitySystem for the rules)
    public int getAbilityEvalCount() { return abilityEvalCount; }
    public void setAbilityEvalCount(int count) { this.abilityEvalCount = count; }
    public boolean hasAbilities() { return abilityHas; }
    public void setHasAbilities(boolean has) { this.abilityHas = has; }
    public List<String> getAbilityAccessibleIds() { return new ArrayList<>(abilityAccessible); }
    public void setAbilityAccessibleIds(List<String> ids) {
        this.abilityAccessible.clear();
        if (ids != null) this.abilityAccessible.addAll(ids);
    }
    public String getAbilityActiveId() { return abilityActiveId; }
    public void setAbilityActiveId(String id) { this.abilityActiveId = id != null ? id : ""; }
    public CompoundTag getAbilityActiveState() { return abilityActiveState; }
    public void setAbilityActiveState(CompoundTag state) { this.abilityActiveState = state != null ? state : new CompoundTag(); }
    public long getAbilityCooldownEnd() { return abilityCooldownEnd; }
    public void setAbilityCooldownEnd(long gameTime) { this.abilityCooldownEnd = gameTime; }
    
    public PowerProfile getPowerProfile() {
        if (skillData == null || skillData.isEmpty()) return null;
        return PowerProfile.fromNbt(skillData);
    }
    
    // Evolution methods
    public void addKill() {
        killCount++;
        updateLevel();
    }
    
    private void updateLevel() {
        int newLevel = Math.min(killCount / KILLS_PER_LEVEL, MAX_LEVEL);
        if (newLevel > level) {
            level = newLevel;
        }
    }
    
    public int getLevel() {
        return level;
    }
    
    public int getKillCount() {
        return killCount;
    }
    
    public static int getMaxLevel() {
        return MAX_LEVEL;
    }
    
    // Stat bonuses based on level
    public double getHealthBonus() {
        return level * 0.5; // +0.5 hearts per level
    }
    
    public double getDamageBonus() {
        return level * 0.1; // +10% damage per level
    }
    
    public double getSpeedBonus() {
        return level * 0.005; // +0.5% speed per level
    }
    
    public double getArmorBonus() {
        return level * 0.1; // +0.1 armor per level
    }
    
    public double getKnockbackResistanceBonus() {
        return Math.min(level * 0.01, 1.0); // +1% per level, max 100%
    }
    
    // Alliance methods
    public void addAlly(UUID uuid) {
        allies.add(uuid);
    }
    
    public void removeAlly(UUID uuid) {
        allies.remove(uuid);
    }
    
    public boolean isAlly(UUID uuid) {
        return allies.contains(uuid);
    }
    
    public Set<UUID> getAllies() {
        return new HashSet<>(allies);
    }
    
    public void clearAllies() {
        allies.clear();
    }
    
    // Target tracking
    public void setCurrentTarget(UUID target) {
        if (currentTarget == null || !currentTarget.equals(target)) {
            currentTarget = target;
            lastTargetChangeTime = System.currentTimeMillis();
        }
    }
    
    public UUID getCurrentTarget() {
        return currentTarget;
    }
    
    public long getTimeSinceTargetChange() {
        return System.currentTimeMillis() - lastTargetChangeTime;
    }
    
    public void clearTarget() {
        currentTarget = null;
    }
    
    // Combat stats
    public void addDamageDealt(double damage) {
        damageDealt += damage;
    }
    
    public void addDamageTaken(double damage) {
        damageTaken += damage;
    }
    
    public double getDamageDealt() {
        return damageDealt;
    }
    
    public double getDamageTaken() {
        return damageTaken;
    }
    
    // NBT serialization
    public CompoundTag writeNbt() {
        CompoundTag nbt = new CompoundTag();
        
        nbt.putInt("killCount", killCount);
        nbt.putInt("level", level);
        nbt.putDouble("damageDealt", damageDealt);
        nbt.putDouble("damageTaken", damageTaken);
        
        // Skill Tree Persistence
        nbt.putDouble("skillPoints", skillPoints);
        nbt.putDouble("spentPoints", spentPoints);
        nbt.put("skillData", skillData);

        // New ability system persistence (am_ability_ keys)
        nbt.putInt("am_ability_eval_count", abilityEvalCount);
        nbt.putBoolean("am_ability_has", abilityHas);
        ListTag accessibleTag = new ListTag();
        for (String id : abilityAccessible) {
            accessibleTag.add(net.minecraft.nbt.StringTag.valueOf(id));
        }
        nbt.put("am_ability_accessible", accessibleTag);
        nbt.putString("am_ability_active", abilityActiveId);
        nbt.put("am_ability_state", abilityActiveState);
        nbt.putLong("am_ability_cooldown_end", abilityCooldownEnd);
        
        if (currentTarget != null) {
            nbt.store("currentTarget", net.minecraft.core.UUIDUtil.CODEC, currentTarget);
            nbt.putLong("lastTargetChangeTime", lastTargetChangeTime);
        }
        
        // Store allies as long array (2 longs per UUID)
        if (!allies.isEmpty()) {
            long[] allyArray = new long[allies.size() * 2];
            int i = 0;
            for (UUID uuid : allies) {
                allyArray[i++] = uuid.getMostSignificantBits();
                allyArray[i++] = uuid.getLeastSignificantBits();
            }
            nbt.putLongArray("allies", allyArray);
        }
        
        return nbt;
    }
    
    public void readNbt(CompoundTag nbt) {
        killCount = nbt.getIntOr("killCount", 0);
        level = nbt.getIntOr("level", 0);
        damageDealt = nbt.getDoubleOr("damageDealt", 0.0);
        damageTaken = nbt.getDoubleOr("damageTaken", 0.0);
        
        // Skill Tree Persistence
        if (nbt.contains("skillPoints")) skillPoints = nbt.getDoubleOr("skillPoints", 0.0);
        if (nbt.contains("spentPoints")) spentPoints = nbt.getDoubleOr("spentPoints", 0.0);
        if (nbt.contains("skillData")) skillData = nbt.getCompoundOrEmpty("skillData");

        // New ability system persistence (am_ability_ keys). Absent on data
        // saved before the ability system existed; defaults are the fresh
        // state (never evaluated, no abilities, idle, no cooldown).
        abilityEvalCount = nbt.getIntOr("am_ability_eval_count", 0);
        abilityHas = nbt.getBooleanOr("am_ability_has", false);
        abilityAccessible.clear();
        if (nbt.contains("am_ability_accessible")) {
            ListTag accessibleTag = nbt.getListOrEmpty("am_ability_accessible");
            for (int i = 0; i < accessibleTag.size(); i++) {
                String id = accessibleTag.getStringOr(i, "");
                if (!id.isEmpty()) abilityAccessible.add(id);
            }
        }
        abilityActiveId = nbt.getStringOr("am_ability_active", "");
        if (nbt.contains("am_ability_state")) abilityActiveState = nbt.getCompoundOrEmpty("am_ability_state");
        abilityCooldownEnd = nbt.getLongOr("am_ability_cooldown_end", 0L);
        
        if (nbt.contains("currentTarget")) {
            currentTarget = nbt.read("currentTarget", net.minecraft.core.UUIDUtil.CODEC).orElse(null);
            lastTargetChangeTime = nbt.getLongOr("lastTargetChangeTime", 0L);
        }
        
        if (nbt.contains("allies")) {
            long[] allyArray = nbt.getLongArray("allies").orElse(new long[0]);
            allies.clear();
            // The i + 1 bound guards against a corrupt odd-length array.
            for (int i = 0; i + 1 < allyArray.length; i += 2) {
                UUID uuid = new UUID(allyArray[i], allyArray[i + 1]);
                allies.add(uuid);
            }
        }
    }
    
    // Static helper to get or create data for a mob
    private static final String NBT_KEY = "UniversalMobWarData";
    
    public static MobWarData get(Mob mob) {
        if (mob instanceof IMobWarDataHolder holder) {
            return holder.getMobWarData();
        }
        
        // Fallback (Should not happen with Mixin)
        net.minecraft.world.level.storage.TagValueOutput output =
            net.minecraft.world.level.storage.TagValueOutput.createWithoutContext(net.minecraft.util.ProblemReporter.DISCARDING);
        mob.saveWithoutId(output);
        CompoundTag nbt = output.buildResult();
        MobWarData data = new MobWarData();
        
        if (nbt.contains(NBT_KEY)) {
            data.readNbt(nbt.getCompoundOrEmpty(NBT_KEY));
        }
        
        return data;
    }
    
    public static void save(Mob mob, MobWarData data) {
        if (mob instanceof IMobWarDataHolder holder) {
            holder.setMobWarData(data);
            return;
        }
        
        // Fallback
        net.minecraft.world.level.storage.TagValueOutput output =
            net.minecraft.world.level.storage.TagValueOutput.createWithoutContext(net.minecraft.util.ProblemReporter.DISCARDING);
        mob.saveWithoutId(output);
        CompoundTag nbt = output.buildResult();
        nbt.put(NBT_KEY, data.writeNbt());
        mob.load(net.minecraft.world.level.storage.TagValueInput.create(
            net.minecraft.util.ProblemReporter.DISCARDING, mob.level().registryAccess(), nbt));
    }
}

