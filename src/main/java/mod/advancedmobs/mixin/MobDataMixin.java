package mod.advancedmobs.mixin;

import mod.advancedmobs.AdvancedMobsMod;
import mod.advancedmobs.config.ModConfig;
import mod.advancedmobs.data.IMobWarDataHolder;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.system.ScalingSystem;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
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
@Mixin(MobEntity.class)
public abstract class MobDataMixin extends LivingEntity implements IMobWarDataHolder {

    @Unique
    private MobWarData advancedMobsData = new MobWarData();

    protected MobDataMixin(EntityType<? extends LivingEntity> entityType, World world) {
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

    @Inject(method = "writeCustomDataToNbt", at = @At("TAIL"))
    private void onWriteNbt(NbtCompound nbt, CallbackInfo ci) {
        AdvancedMobsMod.runSafely("MobDataMixin#writeCustomDataToNbt", () -> {
            if (advancedMobsData != null) {
                nbt.put("UniversalMobWarData", advancedMobsData.writeNbt());
            }
        });
    }

    @Inject(method = "readCustomDataFromNbt", at = @At("TAIL"))
    private void onReadNbt(NbtCompound nbt, CallbackInfo ci) {
        AdvancedMobsMod.runSafely("MobDataMixin#readCustomDataFromNbt", () -> {
            if (nbt.contains("UniversalMobWarData")) {
                advancedMobsData = new MobWarData();
                advancedMobsData.readNbt(nbt.getCompound("UniversalMobWarData"));
            }
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
    @Inject(method = "mobTick", at = @At("HEAD"))
    private void advancedmobs$onMobTick(CallbackInfo ci) {
        AdvancedMobsMod.runSafely("MobDataMixin#onMobTick", () -> {
            if (!ModConfig.getInstance().modEnabled) {
                return;
            }
            MobEntity self = (MobEntity)(Object)this;
            ScalingSystem.monitorEquipmentState(self, advancedMobsData);
            ScalingSystem.processMobTick(self, self.getWorld(), advancedMobsData);
        });
    }

    @Inject(method = "tryAttack(Lnet/minecraft/entity/Entity;)Z", at = @At("TAIL"))
    private void advancedmobs$handleMeleeAbilities(Entity target, CallbackInfoReturnable<Boolean> cir) {
        AdvancedMobsMod.runSafely("MobDataMixin#handleMeleeAbilities", () -> {
            if (!cir.getReturnValueZ() || !(target instanceof LivingEntity livingTarget)) {
                return;
            }

            MobEntity self = (MobEntity)(Object)this;
            if (advancedMobsData == null) {
                return;
            }

            ScalingSystem.handleMeleeAttackAbilities(self, advancedMobsData, livingTarget, self.getWorld().getTime());
        });
    }
    
    // Strip ALL equipment from every mob immediately on spawn (AFTER vanilla equipment)
    // Using @At("RETURN") ensures we strip equipment AFTER Minecraft adds it in initEquipment()
    @Inject(method = "initialize", at = @At("RETURN"))
    private void advancedmobs$stripAllEquipmentOnSpawn(
        net.minecraft.world.ServerWorldAccess world,
        net.minecraft.world.LocalDifficulty difficulty,
        net.minecraft.entity.SpawnReason spawnReason,
        net.minecraft.entity.EntityData entityData,
        org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<net.minecraft.entity.EntityData> cir
    ) {
        AdvancedMobsMod.runSafely("MobDataMixin#stripAllEquipmentOnSpawn", () -> {
            if (!ModConfig.getInstance().modEnabled) {
                return; // Leave vanilla gear untouched when mod is disabled
            }
            MobEntity self = (MobEntity)(Object)this;
            
            // Tag spawn eggs so they aren't blocked by natural spawn blocker
            if (spawnReason == net.minecraft.entity.SpawnReason.SPAWN_EGG) {
                self.addCommandTag("umw_player_spawned");
            }

            // Tag spawner spawns so they're excluded from natural spawn caps.
            if (spawnReason == net.minecraft.entity.SpawnReason.SPAWNER) {
                self.addCommandTag("umw_spawner_spawned");
            }

            // Tag natural spawns so we can apply a cap only to natural spawning.
            if (spawnReason == net.minecraft.entity.SpawnReason.NATURAL) {
                self.addCommandTag("umw_natural_spawned");
            }
            
            // Remove armor (head, chest, legs, boots)
            self.equipStack(net.minecraft.entity.EquipmentSlot.HEAD, net.minecraft.item.ItemStack.EMPTY);
            self.equipStack(net.minecraft.entity.EquipmentSlot.CHEST, net.minecraft.item.ItemStack.EMPTY);
            self.equipStack(net.minecraft.entity.EquipmentSlot.LEGS, net.minecraft.item.ItemStack.EMPTY);
            self.equipStack(net.minecraft.entity.EquipmentSlot.FEET, net.minecraft.item.ItemStack.EMPTY);
            // Remove weapons/tools
            self.equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND, net.minecraft.item.ItemStack.EMPTY);
            self.equipStack(net.minecraft.entity.EquipmentSlot.OFFHAND, net.minecraft.item.ItemStack.EMPTY);

            ScalingSystem.handleSpawnBootstrap(self, spawnReason, advancedMobsData);
        });
    }
}
