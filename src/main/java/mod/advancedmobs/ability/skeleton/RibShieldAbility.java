package mod.advancedmobs.ability.skeleton;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;

import java.util.UUID;

/**
 * Rib Shield (Skeleton ability 3 of 5, defense).
 *
 * WHAT IT DOES: a cage of six bone blocks assembles around the
 * skeleton as orbiting block displays, ribs rotating slowly around
 * it while bone-white dust drifts between them. While the shield
 * turns, the skeleton is hardened (RESISTANCE and bonus ARMOR), and
 * hits that land knock visible chips off the ribs instead of
 * reading as clean hits on the skeleton.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists within 16 blocks (distance
 *   squared 256).
 * - Duration: exactly 240 ticks (12 seconds) from start().
 * - The ribs: 6 block displays of bone block, scale 0.55, each
 *   tilted 0.45 radians about its local Z axis (alternating sign
 *   per rib, so the cage reads as crossed ribs, not a fence), at
 *   orbit radius 1.3 around the skeleton. They are pure visuals:
 *   displays have no hitbox and block nothing themselves; the
 *   defense is the effect and attribute below. Their positions are
 *   rewritten every tick from the state tag alone: rib i sits at
 *   angle (ticks * 0.05 + i * 60 degrees) on the orbit circle,
 *   height 0.9 above the skeleton's feet plus a slow bob of
 *   0.15 * sin(ticks * 0.1 + i). One full revolution takes about
 *   126 ticks (about 6.3 seconds): a slow rotation, per the design.
 * - The hardening, for the duration: RESISTANCE I (amplifier 0,
 *   260 ticks, one application at start) and an ARMOR add_value
 *   modifier of +4 (modifier id advancedmobs:rib_shield_armor,
 *   removed in end(); the RESISTANCE is removed in end() too,
 *   which also clears any resistance the skeleton already had,
 *   the same accepted trade Grave Eruption documents for its
 *   invisibility).
 * - The chips: every 5 ticks the ability compares the skeleton's
 *   health with the last check. A drop of 1.0 or more since then
 *   means a hit landed: 6 BLOCK_CRUMBLE particles of bone block
 *   burst at a random rib's position, the visible chip knocked off
 *   the ribs. Bursts are at most one per 10 ticks, so a focus-fire
 *   pounding cannot turn the shield into a particle fountain; the
 *   health snapshot updates on every check either way.
 *
 * WHAT IT LOOKS LIKE: a slowly turning cage of tilted bone blocks
 * around the skeleton, 2 bone-white DUST motes (RGB 0xE8E0C8)
 * drifting through the cage every 20 ticks, and bone BLOCK_CRUMBLE
 * chips bursting off the ribs when hits land.
 *
 * PERFORMANCE: per tick the work is 6 UUID resolves and 6 snapTo
 * writes for the ribs, nothing else; the chip check runs every 5
 * ticks (two float reads), the dust every 20 ticks. No entity
 * scans, no allocations in the tick path beyond the resolved
 * references. Particle budget per activation: dust at most 12
 * pulses times 2 = 24, chips at most 24 bursts times 6 = 144,
 * total at most 168 particles. The 6 displays are the only extra
 * entities, alive only for the ability's 12 seconds.
 *
 * CLEANUP: end() removes the armor modifier and the resistance and
 * discards every rib display that still resolves. If the caster
 * dies mid-shield, the framework termination path runs this same
 * end(), so the cage never outlives the ability or its caster.
 *
 * State tag keys: "ribs" (list of compounds, one per rib: "id",
 * the display's UUID), "last_hp" (health at the last chip check),
 * "last_burst" (tick of the last chip burst).
 */
public final class RibShieldAbility implements MobAbility {

    static final int DURATION_TICKS = 240;
    private static final int RIB_COUNT = 6;
    private static final double ORBIT_RADIUS = 1.3;
    private static final double ORBIT_SPEED = 0.05;
    private static final float RIB_SCALE = 0.55f;
    private static final int CHIP_INTERVAL = 5;
    private static final int BURST_COOLDOWN = 10;
    private static final Identifier ARMOR_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "rib_shield_armor");
    private static final DustParticleOptions BONE_DUST = new DustParticleOptions(0xE8E0C8, 1.1f);
    private static final BlockParticleOption BONE_CRUMBLE =
        new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, Blocks.BONE_BLOCK.defaultBlockState());

    @Override
    public String id() {
        return "rib_shield";
    }

    @Override
    public String displayName() {
        return "Rib Shield";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        state.putFloat("last_hp", mob.getHealth());
        state.putInt("last_burst", -BURST_COOLDOWN);

        // The hardening.
        mob.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, DURATION_TICKS + 20, 0));
        AttributeInstance armor = mob.getAttribute(Attributes.ARMOR);
        if (armor != null) {
            armor.addOrUpdateTransientModifier(
                new AttributeModifier(ARMOR_ID, 4.0, AttributeModifier.Operation.ADD_VALUE));
        }

        // The cage assembles.
        ListTag ribs = new ListTag();
        if (level != null) {
            for (int i = 0; i < RIB_COUNT; i++) {
                // Tilt about the local Z axis by +/- 0.45 radians,
                // alternating per rib, as a quaternion (x, y, z, w).
                double tilt = (i % 2 == 0 ? 0.45 : -0.45) / 2.0;
                Entity rib = SkeletonAbilitySupport.createBlockDisplay(level,
                    Blocks.BONE_BLOCK.defaultBlockState(), RIB_SCALE,
                    0.0f, 0.0f, (float) Math.sin(tilt), (float) Math.cos(tilt));
                if (rib == null) {
                    continue;
                }
                positionRib(ctx, rib, i, 0);
                level.addFreshEntity(rib);
                CompoundTag entry = new CompoundTag();
                entry.store("id", UUIDUtil.CODEC, rib.getUUID());
                ribs.add(entry);
            }
        }
        state.put("ribs", ribs);
    }

    /** Places one rib on its orbit position for the given tick count. */
    private void positionRib(AbilityContext ctx, Entity rib, int index, int ticks) {
        Mob mob = ctx.mob();
        double angle = ticks * ORBIT_SPEED + index * (Math.PI * 2.0 / RIB_COUNT);
        double x = mob.getX() + Math.cos(angle) * ORBIT_RADIUS;
        double z = mob.getZ() + Math.sin(angle) * ORBIT_RADIUS;
        double y = mob.getY() + 0.9 + 0.15 * Math.sin(ticks * 0.1 + index);
        rib.snapTo(x, y, z, (float) Math.toDegrees(-angle), 0.0f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        CompoundTag state = ctx.state();
        ListTag ribs = state.getListOrEmpty("ribs");

        // The cage turns: every rib is repositioned every tick.
        for (int i = 0; i < ribs.size(); i++) {
            Entity rib = resolveRib(level, ribs.getCompoundOrEmpty(i));
            if (rib != null) {
                positionRib(ctx, rib, i, ticks);
            }
        }

        // The chip check: a landed hit knocks bone off the ribs.
        if (ticks % CHIP_INTERVAL == 0) {
            Mob mob = ctx.mob();
            float lastHp = state.getFloatOr("last_hp", mob.getHealth());
            float now = mob.getHealth();
            if (lastHp - now >= 1.0f && ticks - state.getIntOr("last_burst", 0) >= BURST_COOLDOWN
                && ribs.size() > 0) {
                Entity rib = resolveRib(level, ribs.getCompoundOrEmpty(ctx.random().nextInt(ribs.size())));
                if (rib != null) {
                    level.sendParticles(BONE_CRUMBLE, rib.getX(), rib.getY(), rib.getZ(),
                        6, 0.3, 0.3, 0.3, 0.12);
                    state.putInt("last_burst", ticks);
                }
            }
            state.putFloat("last_hp", now);
        }

        if (ticks % 20 == 0) {
            Mob mob = ctx.mob();
            level.sendParticles(BONE_DUST, mob.getX(), mob.getY() + 1.0, mob.getZ(),
                2, 0.9, 0.6, 0.9, 0.03);
        }
    }

    /** Resolves a tracked rib display from its list entry, or null when gone. */
    private Entity resolveRib(ServerLevel level, CompoundTag entry) {
        if (!entry.contains("id")) {
            return null;
        }
        UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
        return id != null ? level.getEntity(id) : null;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        AttributeInstance armor = mob.getAttribute(Attributes.ARMOR);
        if (armor != null) {
            armor.removeModifier(ARMOR_ID);
        }
        mob.removeEffect(MobEffects.RESISTANCE);

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag ribs = ctx.state().getListOrEmpty("ribs");
        for (int i = 0; i < ribs.size(); i++) {
            Entity rib = resolveRib(level, ribs.getCompoundOrEmpty(i));
            if (rib != null) {
                rib.discard();
            }
        }
    }
}
