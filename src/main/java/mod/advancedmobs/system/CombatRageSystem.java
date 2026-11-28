package mod.advancedmobs.system;

import mod.advancedmobs.config.ModConfig;
import mod.advancedmobs.data.MobWarData;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Temporary fight rage. Every mob uses the same config curve: the longer
 * this streak of combat lasts, the harder it hits and the faster it moves.
 * After the wave start, each mob also takes a growing percent of its own
 * max health. Equal fighters therefore share the same clock, and a fight
 * that stops dealing real damage still ends. The streak is forgotten a few
 * seconds after combat stops.
 */
public final class CombatRageSystem {

    private static final Identifier SPEED_ID = Identifier.fromNamespaceAndPath("advancedmobs", "combat_rage_speed");
    private static final long HURT_MEMORY_TICKS = 200L;

    private CombatRageSystem() {}

    public static void tick(Mob mob, MobWarData data) {
        if (mob == null || data == null || !(mob.level() instanceof ServerLevel level)) {
            return;
        }
        ModConfig config = ModConfig.getInstance();
        if (!config.combatRageEnabled || !config.modEnabled) {
            clear(mob, data);
            return;
        }
        long now = level.getGameTime();
        if (!isFighting(mob, data, now)) {
            long decayTicks = secondsToTicks(config.combatRageDecaySeconds);
            if (data.getRageAnchorTick() >= 0L
                    && (data.getRageLastCombatTick() < 0L || now - data.getRageLastCombatTick() > decayTicks)) {
                clear(mob, data);
            }
            return;
        }
        data.touchRage(now);
        double seconds = (now - data.getRageAnchorTick()) / 20.0;
        double intensity = intensity(seconds, config);
        applySpeed(mob, intensity, config);
        if (intensity > 0.25 && (now + mob.getId()) % 20L == 0L) {
            level.sendParticles(ParticleTypes.ANGRY_VILLAGER,
                mob.getX(), mob.getY() + mob.getBbHeight() * 0.8, mob.getZ(),
                1, 0.3, 0.2, 0.3, 0.0);
        }
        if (seconds >= config.combatRageWaveStartSeconds) {
            long interval = secondsToTicks(config.combatRageWaveIntervalSeconds);
            if (data.getRageLastWaveTick() < 0L || now - data.getRageLastWaveTick() >= interval) {
                fireWave(mob, level, data, now, config);
            }
        }
    }

    /**
     * Outgoing damage multiplier for the current streak. 1 when rage is off
     * or the mob is not in a streak. A small per-hit jitter keeps equal mobs
     * from trading identical numbers.
     */
    public static float damageMultiplier(Mob attacker) {
        ModConfig config = ModConfig.getInstance();
        if (!config.combatRageEnabled || !(attacker instanceof mod.advancedmobs.data.IMobWarDataHolder holder)) {
            return 1.0f;
        }
        MobWarData data = holder.getMobWarData();
        if (data == null || data.getRageAnchorTick() < 0L) {
            return 1.0f;
        }
        long now = attacker.level().getGameTime();
        double seconds = (now - data.getRageAnchorTick()) / 20.0;
        double intensity = intensity(seconds, config);
        if (intensity <= 0.0) {
            return 1.0f;
        }
        double span = Math.max(0.0, config.combatRageMaxDamageMultiplier - 1.0);
        double jitter = 1.0 + (waveJitter(attacker, data.getRageWaveIndex() + (int) (now / 7L)) - 1.0) * 0.35;
        return (float) ((1.0 + span * intensity) * jitter);
    }

    public static String describe(Mob mob, MobWarData data) {
        ModConfig config = ModConfig.getInstance();
        if (!config.combatRageEnabled || data.getRageAnchorTick() < 0L) {
            return "Rage: calm";
        }
        double seconds = (mob.level().getGameTime() - data.getRageAnchorTick()) / 20.0;
        int percent = (int) Math.round(intensity(seconds, config) * 100.0);
        return "Rage: " + percent + "% (" + (int) seconds + "s, wave " + data.getRageWaveIndex() + ")";
    }

    private static void fireWave(Mob mob, ServerLevel level, MobWarData data, long now, ModConfig config) {
        int index = data.getRageWaveIndex();
        data.markRageWave(now);
        double fraction = config.combatRageWaveBaseFraction + config.combatRageWaveGrowthFraction * index;
        fraction = Math.min(config.combatRageWaveFractionCap, Math.max(0.0, fraction));
        fraction *= waveJitter(mob, index);
        float amount = (float) (mob.getMaxHealth() * fraction);
        if (amount <= 0.0f || !mob.isAlive()) {
            return;
        }
        level.sendParticles(ParticleTypes.SONIC_BOOM,
            mob.getX(), mob.getY() + mob.getBbHeight() * 0.5, mob.getZ(),
            1, 0.0, 0.0, 0.0, 0.0);
        level.sendParticles(ParticleTypes.FLAME,
            mob.getX(), mob.getY() + mob.getBbHeight() * 0.4, mob.getZ(),
            12, 0.4, 0.3, 0.4, 0.02);
        float before = mob.getHealth();
        mob.invulnerableTime = 0;
        mob.hurtServer(level, level.damageSources().magic(), amount);
        if (mob.isAlive() && mob.getHealth() > before - amount + 0.5f) {
            float left = before - amount;
            if (left <= 0.0f) {
                mob.setHealth(0.01f);
                mob.invulnerableTime = 0;
                mob.hurtServer(level, level.damageSources().magic(), 1000.0f);
            } else {
                mob.setHealth(left);
            }
        }
    }

    private static void applySpeed(Mob mob, double intensity, ModConfig config) {
        AttributeInstance speed = mob.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        if (intensity <= 0.0 || config.combatRageMaxSpeedBonus <= 0.0) {
            speed.removeModifier(SPEED_ID);
            return;
        }
        double bonus = config.combatRageMaxSpeedBonus * intensity;
        speed.addOrUpdateTransientModifier(new AttributeModifier(
            SPEED_ID, bonus, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
    }

    private static void clear(Mob mob, MobWarData data) {
        data.clearRage();
        AttributeInstance speed = mob.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(SPEED_ID);
        }
    }

    private static boolean isFighting(Mob mob, MobWarData data, long now) {
        LivingEntity target = mob.getTarget();
        if (target != null && target.isAlive()) {
            return true;
        }
        long hurt = data.getLastHurtTick();
        return hurt >= 0L && now - hurt <= HURT_MEMORY_TICKS;
    }

    /** 0 before the grace, then a smooth rise to 1 across the ramp. */
    private static double intensity(double seconds, ModConfig config) {
        double ramp = Math.max(0.001, config.combatRageRampSeconds);
        double t = (seconds - config.combatRageGraceSeconds) / ramp;
        if (t <= 0.0) return 0.0;
        if (t >= 1.0) return 1.0;
        return t * t * (3.0 - 2.0 * t);
    }

    /** Deterministic 1±jitter so waves are uneven without favoring a mob. */
    private static double waveJitter(Mob mob, int salt) {
        double span = ModConfig.getInstance().combatRageWaveJitter;
        int hash = mob.getUUID().hashCode() ^ (salt * 0x9E3779B9);
        double unit = ((hash & 0xFFFF) / 65535.0) * 2.0 - 1.0;
        return 1.0 + unit * span;
    }

    private static long secondsToTicks(double seconds) {
        return Math.max(1L, Math.round(seconds * 20.0));
    }
}
