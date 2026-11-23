# UMW yarn(1.21.1) -> official(26.2) porting map

Authoritative oracle (verify anything not listed here):
```
& "C:\Java\jdk-25.0.3+9\bin\javap.exe" -cp "$env:USERPROFILE\umw-mc26\common.jar;$env:USERPROFILE\umw-mc26\client.jar" <fully.qualified.Class>
```
Find a class file:
```
# list class paths inside common.jar / client.jar via System.IO.Compression
```

## Package/class renames (yarn -> official)
| yarn | official 26.2 |
|---|---|
| net.minecraft.entity.Entity | net.minecraft.world.entity.Entity |
| net.minecraft.entity.LivingEntity | net.minecraft.world.entity.LivingEntity |
| net.minecraft.entity.mob.MobEntity | net.minecraft.world.entity.Mob |
| net.minecraft.entity.mob.HostileEntity | net.minecraft.world.entity.monster.Monster |
| net.minecraft.entity.mob.Angerable | net.minecraft.world.entity.NeutralMob |
| net.minecraft.entity.player.PlayerEntity | net.minecraft.world.entity.player.Player |
| net.minecraft.entity.EntityType | net.minecraft.world.entity.EntityType |
| net.minecraft.entity.SpawnGroup | net.minecraft.world.entity.MobCategory (MONSTER, etc.) |
| net.minecraft.entity.SpawnGroupData -> net.minecraft.world.entity.SpawnGroupData |
| net.minecraft.entity.ai.goal.Goal | net.minecraft.world.entity.ai.goal.Goal |
| net.minecraft.entity.ai.goal.GoalSelector | net.minecraft.world.entity.ai.goal.GoalSelector |
| net.minecraft.entity.ai.goal.TrackTargetGoal | net.minecraft.world.entity.ai.goal.target.TargetGoal |
| net.minecraft.entity.attribute.EntityAttributes | net.minecraft.world.entity.ai.attributes.Attributes |
| net.minecraft.entity.attribute.EntityAttributeInstance | net.minecraft.world.entity.ai.attributes.AttributeInstance |
| net.minecraft.entity.attribute.EntityAttributeModifier | net.minecraft.world.entity.ai.attributes.AttributeModifier |
| net.minecraft.entity.attribute.DefaultAttributeContainer | net.minecraft.world.entity.ai.attributes.AttributeSupplier |
| net.minecraft.entity.damage.DamageSource | net.minecraft.world.damagesource.DamageSource |
| net.minecraft.entity.projectile.PersistentProjectileEntity | net.minecraft.world.entity.projectile.arrow.AbstractArrow |
| net.minecraft.entity.projectile.ExplosiveProjectileEntity | net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile |
| net.minecraft.entity.projectile.ProjectileEntity | net.minecraft.world.entity.projectile.Projectile |
| net.minecraft.item.Item | net.minecraft.world.item.Item |
| net.minecraft.item.ItemStack | net.minecraft.world.item.ItemStack |
| net.minecraft.item.SpawnEggItem | net.minecraft.world.item.SpawnEggItem (CTOR now (Item.Properties) ONLY - no colors) |
| net.minecraft.item.ItemGroups | net.minecraft.world.item.CreativeModeTabs |
| net.minecraft.text.Text | net.minecraft.network.chat.Component |
| net.minecraft.util.Formatting | net.minecraft.ChatFormatting |
| net.minecraft.util.Identifier | net.minecraft.resources.Identifier  (KEPT name Identifier!) |
| net.minecraft.registry.Registries | net.minecraft.core.registries.BuiltInRegistries |
| net.minecraft.registry.Registry (static register) | net.minecraft.core.Registry.register |
| net.minecraft.server.world.ServerWorld | net.minecraft.server.level.ServerLevel |
| net.minecraft.world.World | net.minecraft.world.level.Level |
| net.minecraft.util.math.Vec3d | net.minecraft.world.phys.Vec3 |
| net.minecraft.util.math.BlockPos | net.minecraft.core.BlockPos |
| net.minecraft.world.GameRules | net.minecraft.world.level.gamerules.GameRules (REWRITTEN - see notes) |

## Method renames (common)
| yarn | official |
|---|---|
| entity.getUuid() | entity.getUUID() |
| entity.getWorld() | entity.level() |
| entity.getCommandTags() | entity.getTags() |
| entity.addCommandTag(s) | entity.addTag(s) |
| entity.getType().getTranslationKey() | entity.getType().getDescriptionId() (verify) |
| mob.getTarget()/setTarget() | same |
| mob.getAttributeInstance(EntityAttributes.GENERIC_FOLLOW_RANGE) | mob.getAttribute(Attributes.FOLLOW_RANGE) (verify method name getAttribute vs getAttributeInstance) |
| EntityAttributes.GENERIC_FOLLOW_RANGE | Attributes.FOLLOW_RANGE (Holder<Attribute>, no GENERIC_ prefix) |
| EntityAttributes.GENERIC_MAX_HEALTH | Attributes.MAX_HEALTH |
| EntityAttributes.GENERIC_ATTACK_DAMAGE | Attributes.ATTACK_DAMAGE |
| EntityAttributes.GENERIC_MOVEMENT_SPEED | Attributes.MOVEMENT_SPEED |
| world.getTime() | level().getGameTime() (verify) |
| Text.literal(s) | Component.literal(s) |
| Text.translatable(k) | Component.translatable(k) |
| Identifier.of(ns,path) | Identifier.fromNamespaceAndPath / Identifier.of (verify - likely Identifier.withDefaultNamespace or Identifier.parse; use javap) |
| Registry.register(Registries.X, id, obj) | Registry.register(BuiltInRegistries.X, id, obj) |

## BIG REWRITES (do NOT mechanical-map; handled by Phoenix)
1. **Damage mixin**: LivingEntity.damage(DamageSource,float) -> `hurtServer(ServerLevel, DamageSource, float)` returns boolean. Retarget mixins hooking `damage`.
2. **GameRules**: fully registry-driven now (GameRule<T>, GameRuleType, bootstrap(Registry)). No BooleanRule/Category inner classes, no change-callback. DECISION: DROP the 2 custom gamerules entirely; ModConfig is single source of truth. Remove GameRulesAccessor + rule registration.
3. **SpawnEggItem**: no color constructor. Colors now via item model/data. Use `new SpawnEggItem(new Item.Properties())` and register a spawn egg model/tint if needed.
4. **Fabric API renames**: ItemGroupEvents -> CreativeModeTabEvents (verify class), ItemGroups -> CreativeModeTabs.
5. **Networking CustomPayload**: verify PacketCodec/CustomPayload.Id API unchanged in 26.2.

## Fabric API (CONFIRMED)
- ItemGroupEvents -> `net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents` (module fabric-creative-tab-api-v1). Methods: `CreativeModeTabEvents.modifyEntriesEvent(tabKey)` -> verify; register ModifyOutput.
- ItemGroups.SPAWN_EGGS -> `net.minecraft.world.item.CreativeModeTabs.SPAWN_EGGS`
- Lifecycle/networking/command/entity events packages unchanged; official names inside lambdas.
- Fabric also ships fabric-game-rule-api-v1 (NOT used - we drop custom gamerules).

## CONFIRMED extra names
- world.getTime() -> level().getGameTime()  (getGameTime is default on LevelAccessor)
- Identifier.of(ns,path) -> Identifier.fromNamespaceAndPath(ns,path); Identifier.of(str) -> Identifier.parse(str)
- mob.getAttributeInstance(X) -> mob.getAttribute(Holder<Attribute>); direct double: mob.getAttributeValue(Holder)
- getType().getTranslationKey() -> getType().getDescriptionId()

## Rules for porting
- Verify EVERY uncertain name with javap before writing. Do not guess.
- Keep logic identical unless fixing a real bug. This is a port pass first.
- Preserve all mod behavior/complexity.
