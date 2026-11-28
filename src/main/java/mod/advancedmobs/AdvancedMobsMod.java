package mod.advancedmobs;

import mod.advancedmobs.command.KitCommand;
import mod.advancedmobs.command.MobWarCommand;
import mod.advancedmobs.config.ModConfig;
import mod.advancedmobs.entity.MobWarlordEntity;
import mod.advancedmobs.goal.StalemateBreakerGoal;
import mod.advancedmobs.goal.UniversalTargetGoal;
import mod.advancedmobs.mixin.MobEntityAccessor;
import mod.advancedmobs.net.UmwRequiredClientPayload;
import mod.advancedmobs.net.UmwServerEnchantCompat;
// Evolution system handled globally via MobDataMixin + ScalingSystem
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.system.AbilitySystem;
import mod.advancedmobs.system.AllianceSystem;
import mod.advancedmobs.system.NaturalSpawnLimiter;
import mod.advancedmobs.system.EntityCleanupSystem;
import mod.advancedmobs.system.ScalingSystem;
import mod.advancedmobs.util.TargetingUtil;
import mod.advancedmobs.util.OperationScheduler;
import mod.advancedmobs.util.TickScheduler;
import mod.advancedmobs.util.SummonerTracker;
import mod.advancedmobs.system.TargetingSystem;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;
import net.minecraft.world.level.gamerules.GameRules;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;

import java.util.UUID;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;

public class AdvancedMobsMod implements ModInitializer {

	// Codec for an empty payload (for safety net)
	private static final StreamCodec<RegistryFriendlyByteBuf, CustomPacketPayload> EMPTY_PAYLOAD_CODEC =
		StreamCodec.of((buf, payload) -> {}, buf -> new CustomPacketPayload() {
			@Override
			public Type<? extends CustomPacketPayload> type() {
				return UMW_SAFETYNET_ID;
			}
		});

	public static final String MODID = "advancedmobs";
	public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

	// 26.2 gamerule API note: rules are now registered by Identifier, and
	// Identifiers are lowercase only, so the old camelCase rule names
	// (universalmobwarIgnoreSameSpecies, universalmobwarNeutralMobsAggressive)
	// cannot be kept literally. The registered names keep the
	// universalmobwar prefix in snake_case under the mod namespace:
	// /gamerule advancedmobs:universalmobwar_ignore_same_species
	public static final GameRule<Boolean> IGNORE_SAME_SPECIES_RULE = GameRuleBuilder.forBoolean(true)
		.category(GameRuleCategory.MOBS)
		.buildAndRegister(Identifier.fromNamespaceAndPath(MODID, "universalmobwar_ignore_same_species"));

	public static final GameRule<Boolean> NEUTRAL_MOBS_AGGRESSIVE_RULE = GameRuleBuilder.forBoolean(false)
		.category(GameRuleCategory.MOBS)
		.buildAndRegister(Identifier.fromNamespaceAndPath(MODID, "universalmobwar_neutral_mobs_aggressive"));

	// Mob Warlord Boss Entity (uses Monster for Iris Shaders compatibility)
	public static final EntityType<MobWarlordEntity> MOB_WARLORD = Registry.register(
		BuiltInRegistries.ENTITY_TYPE,
		Identifier.fromNamespaceAndPath(MODID, "mob_warlord"),
		EntityType.Builder.of(MobWarlordEntity::new, MobCategory.MONSTER)
			.sized(1.2f, 3.6f) // Large boss size (1.2m wide x 3.6m tall)
			.clientTrackingRange(64)
			.build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(MODID, "mob_warlord")))
	);
	
	// Mob Warlord Spawn Egg (26.2: SpawnEggItem takes only Item.Properties;
	// the entity type is bound through Properties.spawnEgg)
	public static final Item MOB_WARLORD_SPAWN_EGG = Registry.register(
		BuiltInRegistries.ITEM,
		Identifier.fromNamespaceAndPath(MODID, "mob_warlord_spawn_egg"),
		new SpawnEggItem(new Item.Properties().spawnEgg(MOB_WARLORD)
			.setId(ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MODID, "mob_warlord_spawn_egg"))))
	);

	// Safety net payload ID for global receiver
	public static final net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<net.minecraft.network.protocol.common.custom.CustomPacketPayload> UMW_SAFETYNET_ID =
		new net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(MODID, "umw_safetynet"));

	@Override
	public void onInitialize() {
		// Register an empty S2C payload solely so the server can verify the client has this mod.
		// This makes "server has mod, client doesn't" a hard-fail join (as requested).
		try {
			PayloadTypeRegistry.clientboundPlay().register(UmwRequiredClientPayload.ID, UmwRequiredClientPayload.CODEC);
		} catch (IllegalArgumentException alreadyRegistered) {
			// Tolerate double-init in dev / hot reload scenarios.
		}

		// Register the C2S payload type for the safety net (required in 1.21+)
		try {
			PayloadTypeRegistry.serverboundPlay().register(UMW_SAFETYNET_ID, EMPTY_PAYLOAD_CODEC);
		} catch (IllegalArgumentException alreadyRegistered) {
			// Tolerate double-init in dev / hot reload scenarios.
		}

		// Inspect system payloads (health plates, inspect panel, spectator overlay)
		try {
			PayloadTypeRegistry.clientboundPlay().register(mod.advancedmobs.net.MobInspectSyncPayload.ID, mod.advancedmobs.net.MobInspectSyncPayload.CODEC);
			PayloadTypeRegistry.clientboundPlay().register(mod.advancedmobs.net.MobPlateSyncPayload.ID, mod.advancedmobs.net.MobPlateSyncPayload.CODEC);
			PayloadTypeRegistry.serverboundPlay().register(mod.advancedmobs.net.MobInspectRequestPayload.ID, mod.advancedmobs.net.MobInspectRequestPayload.CODEC);
			PayloadTypeRegistry.serverboundPlay().register(mod.advancedmobs.net.MobGodModePayload.ID, mod.advancedmobs.net.MobGodModePayload.CODEC);
		} catch (IllegalArgumentException alreadyRegistered) {
			// Tolerate double-init in dev / hot reload scenarios.
		}
		mod.advancedmobs.system.MobInspectService.registerServer();

		// SAFETY NET: Register a global handler for unknown or malformed payloads to prevent hard disconnects
		ServerPlayNetworking.registerGlobalReceiver(UMW_SAFETYNET_ID, (payload, context) -> {
			runSafely("SAFETY_NET_PAYLOAD", () -> {
				// Just read and ignore any unknown payloads
				// Optionally, log the event for debugging
				LOGGER.warn("[UMW] Ignored unknown or malformed network payload from {}.", context.player().getName().getString());
			});
		});

		// Register and load config
		AutoConfig.register(ModConfig.class, GsonConfigSerializer::new);
		ModConfig config = ModConfig.getInstance();

		// Keep the config in sync when the gamerules are changed in game
		// (same behavior as the old BooleanRule change callbacks).
		GameRuleEvents.changeCallback(IGNORE_SAME_SPECIES_RULE).register((value, server) -> {
			ModConfig cfg = ModConfig.getInstance();
			if (cfg.ignoreSameSpecies != value) {
				cfg.ignoreSameSpecies = value;
				ModConfig.save();
			}
		});
		GameRuleEvents.changeCallback(NEUTRAL_MOBS_AGGRESSIVE_RULE).register((value, server) -> {
			ModConfig cfg = ModConfig.getInstance();
			if (cfg.neutralMobsAlwaysAggressive != value) {
				cfg.neutralMobsAlwaysAggressive = value;
				ModConfig.save();
			}
		});

		// Register the new ability system's per-mob providers (one line per
		// implemented mob in AbilityProviders.registerAll).
		mod.advancedmobs.ability.AbilityProviders.registerAll();

		UmwServerEnchantCompat.init();
		ServerLifecycleEvents.SERVER_STARTED.register(AdvancedMobsMod::syncGameRulesWithConfig);
		
		// Register Skill Tree Events (Projectiles, etc.)
		// SkillTreeEvents.register();
		
		// Register Mob Warlord attributes
		FabricDefaultAttributeRegistry.register(MOB_WARLORD, MobWarlordEntity.createMobWarlordAttributes());
		
		// Add spawn egg to creative inventory. In 1.21.1 the CreativeModeTabs key
		// fields are private in the shipped jar, so the spawn eggs group key
		// is built from its id instead of referencing CreativeModeTabs.SPAWN_EGGS.
		CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("spawn_eggs"))).register(output -> {
			output.accept(MOB_WARLORD_SPAWN_EGG);
		});
		
		// Register commands
		CommandRegistrationCallback.EVENT.register(MobWarCommand::register);
		CommandRegistrationCallback.EVENT.register(KitCommand::register);

		// Send welcome message to players when they join
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			runSafely("JOIN welcome message", () -> {
				// If the client doesn't have this mod installed, they won't advertise our payload,
				// and we reject the connection with a clear message.
				if (!ServerPlayNetworking.canSend(handler.player, UmwRequiredClientPayload.ID)) {
					handler.disconnect(Component.literal("Advanced Mobs is required to join this server."));
					return;
				}

				handler.player.sendSystemMessage(Component.literal(""));
				handler.player.sendSystemMessage(
					Component.literal("═══════════════════════════════════════════════════").withStyle(style -> style.withColor(ChatFormatting.GOLD).withBold(true))
				);
				handler.player.sendSystemMessage(
					Component.literal("    ADVANCED MOBS").withStyle(style -> style.withColor(ChatFormatting.RED).withBold(true))
				);
				handler.player.sendSystemMessage(
					Component.literal("═══════════════════════════════════════════════════").withStyle(style -> style.withColor(ChatFormatting.GOLD).withBold(true))
				);
			});
		});

		// Attach our targeting goal to every Mob when it loads into a server world.
		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			runSafely(
				"ENTITY_LOAD for " + entity.getType().getDescriptionId(),
				() -> {
					// Track naturally spawned mobs for spawn limiting.
					if (world instanceof ServerLevel serverWorld && entity instanceof Mob mob) {
						NaturalSpawnLimiter.onMobLoaded(serverWorld, mob.entityTags().contains("umw_natural_spawned"));
					}

					if (!(entity instanceof Mob mob)) return;

					// Evolution System now handled globally via MobDataMixin + ScalingSystem
					// Each mob automatically inherits progression logic from its JSON config

					// Check if the mod is enabled
					if (!ModConfig.getInstance().isTargetingActive()) return;

					// Check if this mob type is excluded
					String mobId = mob.getType().getDescriptionId();
					if (ModConfig.getInstance().isMobExcluded(mobId)) return;

					GoalSelector targetSelector = ((MobEntityAccessor) mob).getTargetSelector();
					GoalSelector goalSelector = ((MobEntityAccessor) mob).getGoalSelector();

					// Check if our goal is already added to prevent duplicates on chunk reload
					boolean alreadyHasGoal = targetSelector.getAvailableGoals().stream()
						.anyMatch(goal -> goal.getGoal() instanceof UniversalTargetGoal);

					if (!alreadyHasGoal) {
						Supplier<ModConfig> configSupplier = ModConfig::getInstance;
						// Add targeting goal
						targetSelector.addGoal(2, new UniversalTargetGoal(
							mob,
							() -> configSupplier.get().isTargetingActive(),
							() -> configSupplier.get().ignoreSameSpecies,
							() -> configSupplier.get().targetPlayers,
							() -> configSupplier.get().isAllianceActive(),
							() -> configSupplier.get().getRangeMultiplier()
						));
						
						// Add stalemate breaker goal (Priority 0 to ensure it always runs)
						goalSelector.addGoal(0, new StalemateBreakerGoal(mob));
					}
				}
			);
		});

		ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
			runSafely(
				"ENTITY_UNLOAD for " + entity.getType().getDescriptionId(),
				() -> {
					if (world instanceof ServerLevel serverWorld && entity instanceof Mob mob) {
						NaturalSpawnLimiter.onMobUnloaded(serverWorld, mob.entityTags().contains("umw_natural_spawned"));
						// Ability termination (Part C): an unload that is a
						// genuine removal (the entity is removed or dead)
						// ends the active ability through the framework. A
						// plain chunk unload does NOT: the activation
						// persists in MobWarData and resumes on reload, per
						// the MobAbility contract. Deaths are already ended
						// by MobDeathTrackerMixin, so this is the backstop
						// for removals that never pass through die()
						// (discard, /kill, dimension change), and it is
						// idempotent against the death path.
						if (mob.isRemoved() || !mob.isAlive()) {
							AbilitySystem.terminateActiveAbility(mob, serverWorld, MobWarData.get(mob));
						}
						// Drop runtime-only per-mob state so the static maps keyed
						// by mob UUID stop growing without bound as mobs come and
						// go. The unregister and clear methods existed but had no
						// callers before this.
						UUID mobUuid = mob.getUUID();
						ScalingSystem.cleanupMobState(mobUuid);
						// The summoner record is the exception: it is NOT
						// self-healing. Nothing re-registers a summoned mob
						// on chunk reload (finalizeSpawn does not fire), so
						// unregistering on a plain chunk unload permanently
						// erases the "do not attack your summoner" record
						// for a mob that is still alive. Only drop it on a
						// genuine removal, the same gate as ability
						// termination above.
						if (mob.isRemoved() || !mob.isAlive()) {
							SummonerTracker.unregisterSummoned(mobUuid);
						}
						TargetingSystem.clearMobData(mobUuid);
					}
				}
			);
		});

		ServerLifecycleEvents.SERVER_STOPPED.register(server -> TickScheduler.clear());
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> NaturalSpawnLimiter.clearAll());

		// OPTIMIZATION: Register cache cleanup for entity query system (every 5 seconds)
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			runSafely("END_SERVER_TICK tick scheduler", () -> TickScheduler.tick(server));

			if (server.getTickCount() % 100 == 0) { // Every 5 seconds
				runSafely("END_SERVER_TICK cache cleanup", () -> {
					TargetingUtil.cleanupCache();
					OperationScheduler.cleanup(); // Also cleanup operation scheduler
				});
			}

			// PERFORMANCE: Clean up non-player ground projectiles (arrows/tridents) on an interval.
			int intervalSeconds = Math.max(5, ModConfig.getInstance().cleanupNonPlayerGroundProjectilesIntervalSeconds);
			int intervalTicks = intervalSeconds * 20;
			if (server.getTickCount() % intervalTicks == 0) {
				runSafely("END_SERVER_TICK projectile cleanup", () -> EntityCleanupSystem.cleanupNonPlayerGroundProjectiles(server));
			}
			
			// MEMORY LEAK FIX: Clean up dead mob UUIDs from AllianceSystem (every 60 seconds)
			if (server.getTickCount() % 1200 == 0) {
				runSafely("END_SERVER_TICK alliance cleanup", () -> {
					for (ServerLevel world : server.getAllLevels()) {
						AllianceSystem.cleanupDeadMobs(world);
					}
				});
			}
		});

		LOGGER.info("Advanced Mobs initialized successfully!");
	}


	private static void syncGameRulesWithConfig(MinecraftServer server) {
		ModConfig config = ModConfig.getInstance();
		GameRules rules = server.getGameRules();
		if (rules.get(IGNORE_SAME_SPECIES_RULE) != config.ignoreSameSpecies) {
			rules.set(IGNORE_SAME_SPECIES_RULE, config.ignoreSameSpecies, server);
		}
		if (rules.get(NEUTRAL_MOBS_AGGRESSIVE_RULE) != config.neutralMobsAlwaysAggressive) {
			rules.set(NEUTRAL_MOBS_AGGRESSIVE_RULE, config.neutralMobsAlwaysAggressive, server);
		}
	}

	public static void runSafely(String context, Runnable action) {
		if (action == null) {
			return;
		}
		try {
			action.run();
		} catch (Throwable t) {
			String safeContext = context == null ? "Unknown" : context;
			LOGGER.error("[UMW] {} failed", safeContext, t);
		}
	}


}

