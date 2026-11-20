package mod.advancedmobs.mixin;

import mod.advancedmobs.AdvancedMobsMod;
import mod.advancedmobs.config.ModConfig;
import mod.advancedmobs.data.IMobWarDataHolder;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.system.ScalingSystem;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Core mixin that handles MobWarData persistence for ALL mobs.
 * 
 * SCALING SYSTEM INTEGRATION:
 * This mixin calls ScalingSystem.processMobTick() which:
 *   1. Loads upgrade config from mob_configs/[mobname].json
 *   2. Calculates points from world age (daily_scaling)
 *   3. Spends points on upgrades (80% buy / 20% save)
 *   4. Applies effects (potions, equipment, abilities)
 * 
 * To add scaling for a new mob:
 *   Just create mob_configs/[mobname].json - no mixin needed!
 */
@Mixin(Mob.class)
public abstract class MobDataMixin extends LivingEntity implements IMobWarDataHolder {

    @Unique
    private MobWarData advancedMobsData = new MobWarData();

    protected MobDataMixin(EntityType<? extends LivingEntity> entityType, Level world) {
        super(entityType, world);
    }

    @Override
    public MobWarData getMobWarData() {
        return advancedMobsData;
    }

    @Override
    public void setMobWarData(MobWarData data) {
        this.advancedMobsData = data;
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void onWriteNbt(net.minecraft.world.level.storage.ValueOutput output, CallbackInfo ci) {
        AdvancedMobsMod.runSafely("MobDataMixin#addAdditionalSaveData", () -> {
            if (advancedMobsData != null) {
                output.store("UniversalMobWarData", CompoundTag.CODEC, advancedMobsData.writeNbt());
            }
        });
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void onReadNbt(net.minecraft.world.level.storage.ValueInput input, CallbackInfo ci) {
        AdvancedMobsMod.runSafely("MobDataMixin#readAdditionalSaveData", () -> {
            input.read("UniversalMobWarData", CompoundTag.CODEC).ifPresent(dataNbt -> {
                advancedMobsData = new MobWarData();
                advancedMobsData.readNbt(dataNbt);
            });
        });
    }
    
    /**
     * SCALING SYSTEM HOOK - Called every tick for ALL mobs
     * ScalingSystem handles everything:
     *   - Checks if mob has JSON config
     *   - Calculates points from world age
     *   - Spends points on upgrades
     *   - Applies effects
    * 
    * Mobs without a JSON config fall back to auto-detected potion scaling so passive/neutral/hostile
    * behaviour still lines up with the targeting system.
     */
    @Inject(method = "serverAiStep", at = @At("HEAD"))
    private void advancedmobs$onMobTick(CallbackInfo ci) {
        AdvancedMobsMod.runSafely("MobDataMixin#onMobTick", () -> {
            if (!ModConfig.getInstance().modEnabled) {
                return;
            }
            Mob self = (Mob)(Object)this;
            ScalingSystem.monitorEquipmentState(self, advancedMobsData);
            ScalingSystem.processMobTick(self, self.level(), advancedMobsData);
        });
    }

    // Strip ALL equipment from every mob immediately on spawn (AFTER vanilla equipment)
    // Using @At("RETURN") ensures we strip equipment AFTER Minecraft adds it in initEquipment()
    @Inject(method = "finalizeSpawn", at = @At("RETURN"))
    private void advancedmobs$stripAllEquipmentOnSpawn(
        net.minecraft.world.level.ServerLevelAccessor world,
        net.minecraft.world.DifficultyInstance difficulty,
        net.minecraft.world.entity.EntitySpawnReason spawnReason,
        net.minecraft.world.entity.SpawnGroupData entityData,
        org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<net.minecraft.world.entity.SpawnGroupData> cir
    ) {
        AdvancedMobsMod.runSafely("MobDataMixin#stripAllEquipmentOnSpawn", () -> {
            if (!ModConfig.getInstance().modEnabled) {
                return; // Leave vanilla gear untouched when mod is disabled
            }
            Mob self = (Mob)(Object)this;
            
            // Tag spawn eggs so they aren't blocked by natural spawn blocker
            if (spawnReason == net.minecraft.world.entity.EntitySpawnReason.SPAWN_ITEM_USE) {
                self.addTag("umw_player_spawned");
            }

            // Tag spawner spawns so they're excluded from natural spawn caps.
            if (spawnReason == net.minecraft.world.entity.EntitySpawnReason.SPAWNER) {
                self.addTag("umw_spawner_spawned");
            }

            // Tag natural spawns so we can apply a cap only to natural spawning.
            if (spawnReason == net.minecraft.world.entity.EntitySpawnReason.NATURAL) {
                self.addTag("umw_natural_spawned");
            }
            
            // Remove armor (head, chest, legs, boots)
            self.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.item.ItemStack.EMPTY);
            self.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.item.ItemStack.EMPTY);
            self.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.item.ItemStack.EMPTY);
            self.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET, net.minecraft.world.item.ItemStack.EMPTY);
            // Remove weapons/tools
            self.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, net.minecraft.world.item.ItemStack.EMPTY);
            self.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, net.minecraft.world.item.ItemStack.EMPTY);

            ScalingSystem.handleSpawnBootstrap(self, spawnReason, advancedMobsData);
        });
    }
}
