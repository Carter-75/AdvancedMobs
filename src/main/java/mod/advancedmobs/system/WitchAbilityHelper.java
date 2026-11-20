package mod.advancedmobs.system;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import mod.advancedmobs.data.MobWarData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Helper that translates Witch-specific special ability JSON into runtime behavior.
 */
public final class WitchAbilityHelper {

    private static final float BASE_THROW_SPEED = 0.75f;
    private static final float BASE_ACCURACY = 8.0f;

    private WitchAbilityHelper() {
    }

    public static ThrowStats resolveThrowStats(Mob witch, MobWarData data) {
        JsonObject abilities = getAbilitiesSection(witch);
        if (abilities == null) {
            return new ThrowStats(1.0f, BASE_ACCURACY);
        }

        CompoundTag skillData = data.getSkillData();
        int level = skillData.getIntOr("ability_potion_throw_speed", 0);
        if (level <= 0 || !abilities.has("potion_throw_speed")) {
            return new ThrowStats(1.0f, BASE_ACCURACY);
        }

        JsonArray levels = abilities.getAsJsonArray("potion_throw_speed");
        if (level > levels.size()) {
            return new ThrowStats(1.0f, BASE_ACCURACY);
        }

        JsonObject levelData = levels.get(level - 1).getAsJsonObject();
        float multiplier = levelData.has("throw_speed_multiplier")
            ? levelData.get("throw_speed_multiplier").getAsFloat() : 1.0f;
        float accuracy = levelData.has("accuracy")
            ? levelData.get("accuracy").getAsFloat() : BASE_ACCURACY;
        return new ThrowStats(Math.max(0.5f, multiplier), Math.max(0.1f, accuracy));
    }

    public static ItemStack resolvePotionStack(Mob witch, MobWarData data, ItemStack fallback, RandomSource random) {
        JsonObject abilities = getAbilitiesSection(witch);
        if (abilities == null) {
            return fallback.copy();
        }

        int level = data.getSkillData().getIntOr("ability_extra_potion_bag", 0);
        if (level <= 0 || !abilities.has("extra_potion_bag")) {
            return fallback.copy();
        }

        JsonArray levels = abilities.getAsJsonArray("extra_potion_bag");
        if (level > levels.size()) {
            return fallback.copy();
        }

        JsonObject levelData = levels.get(level - 1).getAsJsonObject();
        if (!levelData.has("unlocked_potions")) {
            return fallback.copy();
        }

        JsonArray unlocked = levelData.getAsJsonArray("unlocked_potions");
        List<ItemStack> abilityPotions = new ArrayList<>();
        for (JsonElement element : unlocked) {
            ItemStack abilityPotion = createAbilityPotion(element.getAsString());
            if (!abilityPotion.isEmpty()) {
                abilityPotions.add(abilityPotion);
            }
        }

        if (abilityPotions.isEmpty()) {
            return fallback.copy();
        }

        double chance = Math.min(0.35 + 0.15 * (level - 1), 0.9);
        if (random.nextDouble() >= chance) {
            return fallback.copy();
        }

        return abilityPotions.get(random.nextInt(abilityPotions.size())).copy();
    }

    public static void configureTrajectory(AbstractThrownPotion potion, Mob witch, LivingEntity target,
                                           ThrowStats stats, float yawOffsetDegrees) {
        Vec3 aim = target.getEyePosition().subtract(witch.getEyePosition());
        if (yawOffsetDegrees != 0.0f) {
            float radians = (float) Math.toRadians(yawOffsetDegrees);
            aim = aim.yRot(radians);
        }

        double magnitude = aim.length();
        potion.shoot(aim.x, aim.y + magnitude * 0.2, aim.z,
            BASE_THROW_SPEED * stats.speedMultiplier(), Math.max(0.1f, stats.accuracy()));
    }

    public static void spawnAdditionalShots(ServerLevel world, Mob witch, ItemStack potionStack,
                                            LivingEntity target, ThrowStats stats, int extraProjectiles) {
        if (extraProjectiles <= 0) {
            return;
        }

        float spreadDegrees = 6.0f;
        for (int i = 0; i < extraProjectiles; i++) {
            float offset = ((i / 2) + 1) * spreadDegrees * (i % 2 == 0 ? 1 : -1);
            AbstractThrownPotion extra = new net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion(world, witch, potionStack.copy());
            extra.setItem(potionStack.copy());
            configureTrajectory(extra, witch, target, stats, offset);
            world.addFreshEntity(extra);
        }
    }

    private static ItemStack createAbilityPotion(String descriptor) {
        PotionDescriptor parsed = PotionDescriptor.from(descriptor);
        if (parsed == null) {
            return ItemStack.EMPTY;
        }

        MobEffectInstance effectInstance = parsed.toStatusEffect();
        if (effectInstance == null) {
            return ItemStack.EMPTY;
        }

        ItemStack potion = new ItemStack(Items.SPLASH_POTION);
        int color = effectInstance.getEffect().value().getColor();
        potion.set(DataComponents.POTION_CONTENTS, new PotionContents(
            Optional.empty(), Optional.of(color), List.of(effectInstance), Optional.empty()
        ));
        return potion;
    }

    private static JsonObject getAbilitiesSection(Mob witch) {
        JsonObject config = ScalingSystem.getConfigForMob(witch);
        if (config == null || !config.has("tree")) {
            return null;
        }
        JsonObject tree = config.getAsJsonObject("tree");
        if (!tree.has("special_abilities")) {
            return null;
        }
        return tree.getAsJsonObject("special_abilities");
    }

    private static class PotionDescriptor {
        private final String effectKey;
        private final int level;
        private final int durationSeconds;

        private PotionDescriptor(String effectKey, int level, int durationSeconds) {
            this.effectKey = effectKey;
            this.level = level;
            this.durationSeconds = durationSeconds;
        }

        static PotionDescriptor from(String raw) {
            if (raw == null || raw.isEmpty()) {
                return null;
            }
            String value = raw.toLowerCase(Locale.ROOT);
            int durationSeconds = -1;
            if (value.endsWith("s")) {
                int lastUnderscore = value.lastIndexOf('_');
                if (lastUnderscore > 0) {
                    String durationPart = value.substring(lastUnderscore + 1, value.length() - 1);
                    if (isNumber(durationPart)) {
                        durationSeconds = Integer.parseInt(durationPart);
                        value = value.substring(0, lastUnderscore);
                    }
                }
            }

            int lastUnderscore = value.lastIndexOf('_');
            int level = 1;
            if (lastUnderscore > 0) {
                String levelPart = value.substring(lastUnderscore + 1);
                if (isNumber(levelPart)) {
                    level = Integer.parseInt(levelPart);
                    value = value.substring(0, lastUnderscore);
                }
            }

            return new PotionDescriptor(value, Math.max(1, level), durationSeconds);
        }

        MobEffectInstance toStatusEffect() {
            var effect = switch (effectKey) {
                case "instant_damage", "damage" -> MobEffects.INSTANT_DAMAGE;
                case "instant_health", "healing" -> MobEffects.INSTANT_HEALTH;
                case "poison" -> MobEffects.POISON;
                case "wither" -> MobEffects.WITHER;
                case "weakness" -> MobEffects.WEAKNESS;
                case "slowness" -> MobEffects.SLOWNESS;
                default -> null;
            };

            if (effect == null) {
                return null;
            }

            if (effect.equals(MobEffects.INSTANT_DAMAGE) || effect.equals(MobEffects.INSTANT_HEALTH)) {
                return new MobEffectInstance(effect, 1, Math.max(0, level - 1));
            }

            int duration = durationSeconds > 0 ? durationSeconds * 20 : 200;
            return new MobEffectInstance(effect, duration, Math.max(0, level - 1));
        }
    }

    private static boolean isNumber(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (char c : value.toCharArray()) {
            if (!Character.isDigit(c)) {
                return false;
            }
        }
        return true;
    }

    public record ThrowStats(float speedMultiplier, float accuracy) {
    }
}
