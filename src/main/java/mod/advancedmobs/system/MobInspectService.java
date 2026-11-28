package mod.advancedmobs.system;

import com.google.gson.JsonObject;
import mod.advancedmobs.ability.AbilityRegistry;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.ability.MobAbilityProvider;
import mod.advancedmobs.config.ModConfig;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.entity.MobWarlordEntity;
import mod.advancedmobs.net.MobGodModePayload;
import mod.advancedmobs.net.MobInspectRequestPayload;
import mod.advancedmobs.net.MobInspectSyncPayload;
import mod.advancedmobs.net.MobPlateSyncPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server side of the mob inspect system (health plates, right-click
 * inspect panel, spectator overlay).
 *
 * This class builds plain-text snapshots of everything worth knowing
 * about a mob (progression level/XP, purchased upgrades, abilities,
 * active effects, equipment with enchantments, current target, allies,
 * warlord status, combat stats) from MobWarData and the live entity, and
 * serves them to clients through MobInspectSyncPayload. Health,
 * equipment and effects are already synced by vanilla, but level, XP,
 * abilities and alliances live only in server-side MobWarData, so this
 * snapshot is the single bridge for all three client features.
 *
 * It also pushes lightweight plate data (level/XP) for nearby mobs once
 * per second, which the client combines with vanilla-synced health to
 * draw health plates above mobs.
 */
public final class MobInspectService {

    /** Furthest distance a non-spectating player may inspect from. */
    private static final double MAX_INSPECT_DISTANCE = 96.0;
    /** Radius for periodic health-plate data pushes. */
    private static final double PLATE_SYNC_RADIUS = 24.0;
    /** Hard cap on plate entries per push, for bandwidth safety. */
    private static final int MAX_PLATE_ENTRIES = 64;
    /** Minimum ticks between accepted inspect requests per player. */
    private static final long REQUEST_COOLDOWN_TICKS = 5L;

    private static final Map<UUID, Long> LAST_REQUEST_TICK = new HashMap<>();
    private static long serverTickCounter = 0L;

    private MobInspectService() {}

    /** Registers the request receiver and the periodic plate push. Server only. */
    public static void registerServer() {
        ServerPlayNetworking.registerGlobalReceiver(MobInspectRequestPayload.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!player.isCreative() && !player.isSpectator()) {
                return;
            }
            long now = player.level().getGameTime();
            Long last = LAST_REQUEST_TICK.get(player.getUUID());
            if (last != null && now - last < REQUEST_COOLDOWN_TICKS) {
                return; // Refresh spam: silently drop.
            }
            LAST_REQUEST_TICK.put(player.getUUID(), now);

            Entity entity = player.level().getEntity(payload.entityId());
            if (!(entity instanceof Mob mob) || !mob.isAlive()) {
                return;
            }
            boolean spectatingThis = player.getCamera() == mob;
            if (!spectatingThis && player.distanceToSqr(mob) > MAX_INSPECT_DISTANCE * MAX_INSPECT_DISTANCE) {
                return;
            }
            ServerPlayNetworking.send(player, new MobInspectSyncPayload(mob.getId(), buildSnapshot(player, mob)));
        });

        ServerPlayNetworking.registerGlobalReceiver(MobGodModePayload.ID, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!player.isCreative() && !player.isSpectator()) {
                return;
            }
            Entity entity = player.level().getEntity(payload.entityId());
            if (!(entity instanceof Mob mob) || !mob.isAlive()) {
                return;
            }
            boolean spectatingThis = player.getCamera() == mob;
            if (!spectatingThis && player.distanceToSqr(mob) > MAX_INSPECT_DISTANCE * MAX_INSPECT_DISTANCE) {
                return;
            }
            MobWarData data = MobWarData.get(mob);
            ScalingSystem.applyGodMode(mob, data);
            ServerPlayNetworking.send(player, new MobInspectSyncPayload(mob.getId(), buildSnapshot(player, mob)));
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            serverTickCounter++;
            if (serverTickCounter % 20L != 0L) {
                return;
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                pushPlateData(player);
            }
        });
    }

    private static void pushPlateData(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        List<Mob> mobs = level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(PLATE_SYNC_RADIUS),
            mob -> mob.isAlive());
        if (mobs.isEmpty()) {
            return;
        }
        ListTag entries = new ListTag();
        for (Mob mob : mobs) {
            if (entries.size() >= MAX_PLATE_ENTRIES) {
                break;
            }
            MobWarData data = MobWarData.get(mob);
            CompoundTag entry = new CompoundTag();
            entry.putInt("id", mob.getId());
            entry.putInt("level", data.getLevel());
            entry.putInt("xp", (int) Math.floor(data.getSkillPoints()));
            entry.putString("name", mob.getName().getString());
            entries.add(entry);
        }
        CompoundTag root = new CompoundTag();
        root.put("entries", entries);
        ServerPlayNetworking.send(player, new MobPlateSyncPayload(root));
    }

    /**
     * Builds the full inspect snapshot for one mob. All display strings
     * are resolved server-side (names, enchantments, durations), and the
     * client renders the sections generically.
     */
    public static CompoundTag buildSnapshot(ServerPlayer requester, Mob mob) {
        MobWarData data = MobWarData.get(mob);
        ModConfig config = ModConfig.getInstance();
        JsonObject mobConfig = ScalingSystem.getConfigForMob(mob);
        String configName = mobConfig != null && mobConfig.has("mob_name")
            ? mobConfig.get("mob_name").getAsString() : "";

        CompoundTag root = new CompoundTag();
        root.putString("name", mob.getName().getString());
        root.putString("type", mob.getType().getDescriptionId());
        root.putString("configName", configName);
        root.putString("uuid", mob.getUUID().toString());
        root.putFloat("health", mob.getHealth());
        root.putFloat("maxHealth", mob.getMaxHealth());
        root.putFloat("absorption", mob.getAbsorptionAmount());
        root.putInt("armor", mob.getArmorValue());
        root.putDouble("attackDamage", mob.getAttributeValue(Attributes.ATTACK_DAMAGE));
        root.putDouble("speed", mob.getAttributeValue(Attributes.MOVEMENT_SPEED));
        root.putBoolean("godMode", data.isGodMode());

        ListTag sections = new ListTag();

        // --- Progression ---
        ListTag progression = new ListTag();
        progression.add(StringTag.valueOf("Level: " + data.getLevel() + " / " + MobWarData.getMaxLevel()));
        progression.add(StringTag.valueOf("Kills: " + data.getKillCount()));
        progression.add(StringTag.valueOf("XP (lifetime points): " + formatPoints(data.getSkillPoints())));
        progression.add(StringTag.valueOf("Points spent: " + formatPoints(data.getSpentPoints())));
        progression.add(StringTag.valueOf("Points available: "
            + formatPoints(Math.max(0.0, data.getSkillPoints() - data.getSpentPoints()))));
        progression.add(StringTag.valueOf("Ability power: "
            + (int) Math.round(AbilitySystem.powerFraction(data, config) * 100.0) + "%"));
        progression.add(StringTag.valueOf("Damage dealt: " + formatPoints(data.getDamageDealt())));
        progression.add(StringTag.valueOf("Damage taken: " + formatPoints(data.getDamageTaken())));
        progression.add(StringTag.valueOf(CombatRageSystem.describe(mob, data)));
        progression.add(StringTag.valueOf(data.isGodMode() ? "God mode: on" : "God mode: off"));
        sections.add(section("Progression", progression));

        // --- Purchased upgrades (tiers and effect levels from skill data) ---
        ListTag upgrades = new ListTag();
        var skillData = data.getSkillData();
        if (skillData != null) {
            addTierLine(upgrades, skillData, "weapon_tier", "Weapon tier");
            addTierLine(upgrades, skillData, "has_shield", "Shield");
            addTierLine(upgrades, skillData, "helmet_tier", "Helmet tier");
            addTierLine(upgrades, skillData, "chestplate_tier", "Chestplate tier");
            addTierLine(upgrades, skillData, "leggings_tier", "Leggings tier");
            addTierLine(upgrades, skillData, "boots_tier", "Boots tier");
            addTierLine(upgrades, skillData, ScalingSystem.ABILITY_SLOTS_KEY, "Ability slots");
            for (String key : skillData.keySet()) {
                if (key.startsWith("effect_")) {
                    int level = skillData.getIntOr(key, 0);
                    if (level > 0) {
                        upgrades.add(StringTag.valueOf(prettify(key.substring("effect_".length())) + " Lv " + level));
                    }
                }
            }
        }
        if (upgrades.isEmpty()) {
            upgrades.add(StringTag.valueOf("None yet"));
        }
        sections.add(section("Upgrades", upgrades));

        // --- Abilities ---
        ListTag abilities = new ListTag();
        if (!configName.isEmpty()) {
            MobAbilityProvider provider = AbilityRegistry.getProvider(configName);
            if (provider != null) {
                Map<String, String> displayNames = new HashMap<>();
                for (MobAbility ability : provider.abilities()) {
                    displayNames.put(ability.id(), ability.displayName());
                }
                if (data.hasAbilities()) {
                    List<String> accessible = data.getAbilityAccessibleIds();
                    if (accessible.isEmpty()) {
                        abilities.add(StringTag.valueOf("Access granted, no slots unlocked yet"));
                    }
                    for (String id : accessible) {
                        abilities.add(StringTag.valueOf(displayNames.getOrDefault(id, prettify(id))));
                    }
                } else {
                    abilities.add(StringTag.valueOf("No ability access yet"));
                }
                String active = data.getAbilityActiveId();
                if (active != null && !active.isEmpty()) {
                    abilities.add(StringTag.valueOf("ACTIVE NOW: " + displayNames.getOrDefault(active, prettify(active))));
                }
                long cooldownTicks = data.getAbilityCooldownEnd() - mob.level().getGameTime();
                if (cooldownTicks > 0 && (active == null || active.isEmpty())) {
                    abilities.add(StringTag.valueOf("Cooldown: " + (cooldownTicks / 20L) + "s"));
                }
            }
        }
        if (abilities.isEmpty()) {
            abilities.add(StringTag.valueOf("No abilities for this mob type"));
        }
        sections.add(section("Abilities", abilities));

        // --- Active effects ---
        ListTag effects = new ListTag();
        for (MobEffectInstance effect : mob.getActiveEffects()) {
            String line = effect.getEffect().value().getDisplayName().getString()
                + " " + (effect.getAmplifier() + 1);
            if (effect.isInfiniteDuration()) {
                line += " (permanent)";
            } else {
                line += " (" + (effect.getDuration() / 20) + "s)";
            }
            effects.add(StringTag.valueOf(line));
        }
        if (effects.isEmpty()) {
            effects.add(StringTag.valueOf("None"));
        }
        sections.add(section("Effects", effects));

        // --- Equipment (with enchantments) ---
        ListTag equipment = new ListTag();
        addEquipmentLine(equipment, mob, EquipmentSlot.MAINHAND, "Main Hand");
        addEquipmentLine(equipment, mob, EquipmentSlot.OFFHAND, "Off Hand");
        addEquipmentLine(equipment, mob, EquipmentSlot.HEAD, "Helmet");
        addEquipmentLine(equipment, mob, EquipmentSlot.CHEST, "Chestplate");
        addEquipmentLine(equipment, mob, EquipmentSlot.LEGS, "Leggings");
        addEquipmentLine(equipment, mob, EquipmentSlot.FEET, "Boots");
        sections.add(section("Equipment", equipment));

        // --- Target ---
        ListTag target = new ListTag();
        LivingEntity currentTarget = mob.getTarget();
        Entity resolved = currentTarget;
        if (resolved == null && data.getCurrentTarget() != null && mob.level() instanceof ServerLevel serverLevel) {
            resolved = serverLevel.getEntity(data.getCurrentTarget());
        }
        if (resolved instanceof LivingEntity living) {
            target.add(StringTag.valueOf(living.getName().getString()));
            target.add(StringTag.valueOf("HP: " + formatPoints(living.getHealth()) + " / " + formatPoints(living.getMaxHealth())));
            target.add(StringTag.valueOf("Type: " + living.getType().getDescriptionId()));
        } else {
            target.add(StringTag.valueOf("None"));
        }
        sections.add(section("Target", target));

        // --- Alliance & warlord ---
        ListTag alliance = new ListTag();
        if (data.getAllies().isEmpty()) {
            alliance.add(StringTag.valueOf("Allies: none"));
        } else {
            alliance.add(StringTag.valueOf("Allies (" + data.getAllies().size() + "):"));
            if (mob.level() instanceof ServerLevel serverLevel) {
                for (UUID allyUuid : data.getAllies()) {
                    Entity ally = serverLevel.getEntity(allyUuid);
                    if (ally instanceof LivingEntity living) {
                        alliance.add(StringTag.valueOf("- " + living.getName().getString()
                            + " (" + formatPoints(living.getHealth()) + "/" + formatPoints(living.getMaxHealth()) + " HP)"));
                    } else {
                        alliance.add(StringTag.valueOf("- " + allyUuid.toString().substring(0, 8) + " (not loaded)"));
                    }
                }
            }
        }
        if (mob instanceof MobWarlordEntity) {
            alliance.add(StringTag.valueOf("THIS IS A MOB WARLORD"));
            alliance.add(StringTag.valueOf("Minions: " + WarlordSystem.getMinionCount(mob.getUUID())));
        } else if (WarlordSystem.isMinion(mob)) {
            UUID master = WarlordSystem.getMasterUuid(mob.getUUID());
            String masterName = master != null ? master.toString().substring(0, 8) : "?";
            if (master != null && mob.level() instanceof ServerLevel serverLevel) {
                Entity masterEntity = serverLevel.getEntity(master);
                if (masterEntity != null) {
                    masterName = masterEntity.getName().getString();
                }
            }
            alliance.add(StringTag.valueOf("Warlord minion of: " + masterName));
        }
        sections.add(section("Alliance", alliance));

        root.put("sections", sections);
        return root;
    }

    private static CompoundTag section(String title, ListTag lines) {
        CompoundTag section = new CompoundTag();
        section.putString("title", title);
        section.put("lines", lines);
        return section;
    }

    private static void addTierLine(ListTag lines, net.minecraft.nbt.CompoundTag skillData, String key, String label) {
        int level = skillData.getIntOr(key, 0);
        lines.add(StringTag.valueOf(label + ": " + level));
    }

    private static void addEquipmentLine(ListTag lines, Mob mob, EquipmentSlot slot, String label) {
        ItemStack stack = mob.getItemBySlot(slot);
        if (stack.isEmpty()) {
            lines.add(StringTag.valueOf(label + ": (empty)"));
            return;
        }
        StringBuilder line = new StringBuilder(label).append(": ").append(stack.getHoverName().getString());
        if (stack.isDamageableItem()) {
            int max = stack.getMaxDamage();
            if (max > 0) {
                line.append(" (").append(Math.round(100.0f * (max - stack.getDamageValue()) / max)).append("% durability)");
            }
        }
        lines.add(StringTag.valueOf(line.toString()));
        ItemEnchantments enchantments = stack.getEnchantments();
        for (it.unimi.dsi.fastutil.objects.Object2IntMap.Entry<Holder<Enchantment>> entry : enchantments.entrySet()) {
            lines.add(StringTag.valueOf("  " + entry.getKey().value().description().getString() + " " + entry.getIntValue()));
        }
    }

    static String prettify(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String[] parts = raw.replace('_', ' ').split(" ");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                out.append(part.substring(1));
            }
        }
        return out.toString();
    }

    private static String formatPoints(double value) {
        if (value == Math.floor(value)) {
            return Long.toString(Math.round(value));
        }
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
