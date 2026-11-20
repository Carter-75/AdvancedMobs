package mod.advancedmobs;

import mod.advancedmobs.command.KitCommand;
import mod.advancedmobs.command.MobWarCommand;
import mod.advancedmobs.config.ModConfig;
import mod.advancedmobs.entity.MobWarlordEntity;
import mod.advancedmobs.goal.StalemateBreakerGoal;
import mod.advancedmobs.goal.UniversalTargetGoal;
import mod.advancedmobs.mixin.GameRulesAccessor;
import mod.advancedmobs.mixin.MobEntityAccessor;
import mod.advancedmobs.net.UmwRequiredClientPayload;
import mod.advancedmobs.net.UmwServerEnchantCompat;
// Evolution system handled globally via MobDataMixin + ScalingSystem
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
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
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
import net.minecraft.world.level.gamerules.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;

import java.util.UUID;
import java.util.function.BiConsumer;
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
		StreamCodec.of((payload, buf) -> {}, buf -> new CustomPacketPayload() {
			@Override
			public Id<? extends CustomPacketPayload> getId() {
				return UMW_SAFETYNET_ID;
			}
		});

	public static final String MODID = "advancedmobs";
	public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

	public static final GameRules.Key<GameRules.BooleanRule> IGNORE_SAME_SPECIES_RULE = registerBooleanRule(
		"universalmobwarIgnoreSameSpecies",
		GameRules.Category.MOBS,
		true,
		(server, rule) -> {
			ModConfig config = ModConfig.getInstance();
			if (config.ignoreSameSpecies != rule.get()) {
				config.ignoreSameSpecies = rule.get();
				ModConfig.save();
			}
		}
	);

	public static final GameRules.Key<GameRules.BooleanRule> NEUTRAL_MOBS_AGGRESSIVE_RULE = registerBooleanRule(
		"universalmobwarNeutralMobsAggressive",
		GameRules.Category.MOBS,
		false,
		(server, rule) -> {
			ModConfig config = ModConfig.getInstance();
			if (config.neutralMobsAlwaysAggressive != rule.get()) {
				config.neutralMobsAlwaysAggressive = rule.get();
				ModConfig.save();
			}
		}
	);

	// Mob Warlord Boss Entity (uses Monster for Iris Shaders compatibility)
	public static final EntityType<MobWarlordEntity> MOB_WARLORD = Registry.register(
		BuiltInRegistries.ENTITY_TYPE,
		Identifier.fromNamespaceAndPath(MODID, "mob_warlord"),
		EntityType.Builder.create(MobWarlordEntity::new, MobCategory.MONSTER)
			.dimensions(1.2f, 3.6f) // Large boss size (1.2m wide x 3.6m tall)
			.maxTrackingRange(64)
			// Minecraft 1.21.1 requires the entity id here; the no-arg build()
			// call only exists in 1.21.2 and later, so this never compiled
			// against the mod's declared 1.21.1 target.
			.build(MODID + ":mob_warlord")
	);
	
	// Mob Warlord Spawn Egg (same colors as witch egg)
	public static final Item MOB_WARLORD_SPAWN_EGG = Registry.register(
		BuiltInRegistries.ITEM,
		Identifier.fromNamespaceAndPath(MODID, "mob_warlord_spawn_egg"),
		new SpawnEggItem(MOB_WARLORD, 0x334E4C, 0x51A03E, new Item.Settings())
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

		UmwServerEnchantCompat.init();
		ServerLifecycleEvents.SERVER_STARTED.register(AdvancedMobsMod::syncGameRulesWithConfig);
		
		// Register Skill Tree Events (Projectiles, etc.)
		// SkillTreeEvents.register();
		
		// Register Mob Warlord attributes
		FabricDefaultAttributeRegistry.register(MOB_WARLORD, MobWarlordEntity.createMobWarlordAttributes());
		
		// Add spawn egg to creative inventory. In 1.21.1 the CreativeModeTabs key
		// fields are private in the shipped jar, so the spawn eggs group key
		// is built from its id instead of referencing CreativeModeTabs.SPAWN_EGGS.
		ItemGroupEvents.modifyEntriesEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("spawn_eggs"))).register(content -> {
			content.add(MOB_WARLORD_SPAWN_EGG);
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

				handler.player.sendMessage(Component.literal(""), false);
				handler.player.sendMessage(
					Component.literal("═══════════════════════════════════════════════════").withStyle(style -> style.withColor(ChatFormatting.GOLD).withBold(true)),
					false
				);
				handler.player.sendMessage(
					Component.literal("    ADVANCED MOBS").withStyle(style -> style.withColor(ChatFormatting.RED).withBold(true))
						,
					false
				);
				handler.player.sendMessage(
					Component.literal("═══════════════════════════════════════════════════").withStyle(style -> style.withColor(ChatFormatting.GOLD).withBold(true)),
					false
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
						NaturalSpawnLimiter.onMobLoaded(serverWorld, mob.getTags().contains("umw_natural_spawned"));
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
					boolean alreadyHasGoal = targetSelector.getGoals().stream()
						.anyMatch(goal -> goal.getGoal() instanceof UniversalTargetGoal);

					if (!alreadyHasGoal) {
						Supplier<ModConfig> configSupplier = ModConfig::getInstance;
						// Add targeting goal
						targetSelector.add(2, new UniversalTargetGoal(
							mob,
							() -> configSupplier.get().isTargetingActive(),
							() -> configSupplier.get().ignoreSameSpecies,
							() -> configSupplier.get().targetPlayers,
							() -> configSupplier.get().isAllianceActive(),
							() -> configSupplier.get().getRangeMultiplier()
						));
						
						// Add stalemate breaker goal (Priority 0 to ensure it always runs)
						goalSelector.add(0, new StalemateBreakerGoal(mob));
					}
				}
			);
		});

		ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
			runSafely(
				"ENTITY_UNLOAD for " + entity.getType().getDescriptionId(),
				() -> {
					if (world instanceof ServerLevel serverWorld && entity instanceof Mob mob) {
						NaturalSpawnLimiter.onMobUnloaded(serverWorld, mob.getTags().contains("umw_natural_spawned"));
						// Drop runtime-only per-mob state so the static maps keyed
						// by mob UUID stop growing without bound as mobs come and
						// go. The unregister and clear methods existed but had no
						// callers before this.
						UUID mobUuid = mob.getUUID();
						ScalingSystem.cleanupMobState(mobUuid);
						SummonerTracker.unregisterSummoned(mobUuid);
						TargetingSystem.clearMobData(mobUuid);
					}
				}
			);
		});

		ServerLifecycleEvents.SERVER_STOPPED.register(server -> TickScheduler.clear());

		// OPTIMIZATION: Register cache cleanup for entity query system (every 5 seconds)
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			runSafely("END_SERVER_TICK tick scheduler", () -> TickScheduler.tick(server));
			runSafely("END_SERVER_TICK extra shot queue", () -> ScalingSystem.processExtraShotQueue(server));

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


	private static GameRules.Key<GameRules.BooleanRule> registerBooleanRule(
		String name,
		GameRules.Category category,
		boolean defaultValue,
		BiConsumer<MinecraftServer, GameRules.BooleanRule> changeCallback
	) {
		return GameRulesAccessor.GameRulesInvoker.invokeRegister(
			name,
			category,
			GameRulesAccessor.BooleanRuleInvoker.invokeCreate(defaultValue, changeCallback)
		);
	}

	private static void syncGameRulesWithConfig(MinecraftServer server) {
		ModConfig config = ModConfig.getInstance();
		GameRules.BooleanRule ignoreSameRule = server.getGameRules().get(IGNORE_SAME_SPECIES_RULE);
		if (ignoreSameRule.get() != config.ignoreSameSpecies) {
			ignoreSameRule.set(config.ignoreSameSpecies, server);
		}
		GameRules.BooleanRule neutralAggressiveRule = server.getGameRules().get(NEUTRAL_MOBS_AGGRESSIVE_RULE);
		if (neutralAggressiveRule.get() != config.neutralMobsAlwaysAggressive) {
			neutralAggressiveRule.set(config.neutralMobsAlwaysAggressive, server);
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

