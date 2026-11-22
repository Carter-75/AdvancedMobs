package mod.advancedmobs.ability.wither;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/**
 * Soul Harvest Bulwark (Wither ability 2 of 5, transformation,
 * defense).
 *
 * WHAT IT DOES: at half health the wither armors up: a ring of
 * eight wither skeleton skulls (block displays) rises to orbit
 * it, trailing SCULK_SOUL wisps, and the skulls throw
 * themselves in front of incoming projectiles, shattering one
 * by one under focused fire. While the bulwark holds, the
 * wither's armor and blast padding max out and it grows
 * larger, and every enemy it kills feeds a soul counter onto
 * its boss bar, each soul buying a sliver of health back.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the wither's current target within 24 blocks (distance
 *   squared 576), plus the wither at or below half health
 *   (health at most 50 percent of max health). This is the
 *   design's "at half health the wither enters its armored
 *   phase": below half, the framework may pick this ability;
 *   above half, it never fires.
 * - Duration: exactly 600 ticks (30 seconds) from start(), or
 *   until the last skull shatters, whichever comes first.
 * - The armored form (start(), all transient modifiers, ids
 *   under advancedmobs:bulwark_*):
 *     ARMOR                          add_value +20.0 (the
 *       wither's base 4 becomes 24, at the top of what armor
 *       can meaningfully give)
 *     EXPLOSION_KNOCKBACK_RESISTANCE add_value +1.0 (full:
 *       explosions no longer move it at all)
 *     SCALE                          add_value +0.5 (1.5 times
 *       normal size, about 5.3 blocks tall)
 *   All three are removed in end().
 * - The skull ring: 8 block displays showing wither skeleton
 *   skulls at scale 1.0, created in start() and stored by UUID
 *   in the state tag, one slot per skull. Every tick each
 *   surviving skull is snapped to its orbit point: radius 2.4
 *   blocks around the wither, at 2.2 blocks above its feet
 *   plus a bob of 0.3 times the sine of the tick and slot, the
 *   whole ring turning at 0.05 radians per tick. The displays
 *   are pure visuals (no hitbox); the interception below is
 *   what makes them a shield.
 * - Interception, every 2 ticks: one bounded query for
 *   projectiles in a box of radius 4.5 around the wither,
 *   between 1 below and 5 above its feet. A projectile is
 *   hostile when its owner is an enemy (WitherAbilitySupport
 *   rules) or it has no owner; the wither's own skulls and
 *   allies' projectiles are never touched. Each hostile
 *   projectile is discarded, and the first surviving skull
 *   slot shatters for it: the display is discarded with 8
 *   skull BLOCK particles and a GLASS_BREAK crack (volume 0.8,
 *   pitch 0.8; the brittle shatter sound the Rod Aegis uses
 *   for the same job). One skull stops exactly one projectile,
 *   so focused fire strips the ring at one skull per shot.
 * - The soul harvest, every 10 ticks: the wither's war kill
 *   count (MobWarData.getKillCount, the same counter the
 *   progression system maintains) is compared against the
 *   count recorded at start() and at the last check. Each new
 *   kill is one soul: the soul counter rises and the wither
 *   heals 8.0 health per soul, never above its max health.
 *   The counter rides the boss bar: WitherBoss.setCustomName
 *   updates the vanilla boss bar's name, so when the wither
 *   has no custom name of its own, start() sets its name to
 *   "Wither - Souls: 0" and each soul updates it ("Wither -
 *   Souls: N"); end() clears the name back to none. A wither
 *   that already carries a custom name (a player named it)
 *   keeps that name untouched: the counter then lives only in
 *   the state tag and the healing is unchanged. The healing is
 *   health, not a modifier: it is not reversed in end().
 * - Wisps: every 5 ticks, 2 SCULK_SOUL particles rise at
 *   random points on the orbit ring.
 *
 * WHAT IT LOOKS AND SOUNDS LIKE: a WITHER_SPAWN roar (volume
 * 1.2, pitch 0.7) and a 16 particle SCULK_SOUL bloom mark the
 * ring rising. The skulls are full-brightness wither skeleton
 * skulls gliding in a slow orbit. Each interception cracks
 * with GLASS_BREAK and a spray of skull fragments. Each soul
 * harvested plays a soft SCULK_CATALYST_BLOOM chime (volume
 * 0.6, pitch 1.2).
 *
 * PERFORMANCE: per tick the orbit snaps at most 8 displays
 * (UUID resolves plus position writes). Interception is one
 * bounded projectile query every 2 ticks; the harvest is one
 * integer compare every 10 ticks; wisps every 5 ticks. No
 * whole-level scans, no per-tick allocations beyond the orbit
 * math. Particle budget per activation: 16 at start, wisps at
 * most 120 pulses times 2 = 240, shatters at most 8 times 8 =
 * 64, total at most 320 particles.
 *
 * State tag keys: "skulls" (list of 8 compounds, one per ring
 * slot: "id" the display UUID, or an empty compound once that
 * skull has shattered), "alive" (surviving skull count),
 * "angle" (the ring's current orbit angle, radians), "kills"
 * (the war kill count at the last harvest check), "souls"
 * (souls harvested this activation), "named" (1 when this
 * activation owns the wither's custom name for the boss bar).
 */
public final class SoulHarvestBulwarkAbility implements MobAbility {

    static final int DURATION_TICKS = 600;
    private static final int SKULL_COUNT = 8;
    private static final int INTERCEPT_INTERVAL = 2;
    private static final int HARVEST_INTERVAL = 10;
    private static final int WISP_INTERVAL = 5;
    private static final double ORBIT_RADIUS = 2.4;
    private static final double ORBIT_HEIGHT = 2.2;
    private static final double ORBIT_SPEED = 0.05;
    private static final double HEAL_PER_SOUL = 8.0;
    private static final Identifier ARMOR_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "bulwark_armor");
    private static final Identifier BLAST_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "bulwark_blast_padding");
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "bulwark_scale");
    private static final BlockParticleOption SKULL_FRAGMENTS =
        new BlockParticleOption(ParticleTypes.BLOCK, Blocks.WITHER_SKELETON_SKULL.defaultBlockState());

    @Override
    public String id() {
        return "soul_harvest_bulwark";
    }

    @Override
    public String displayName() {
        return "Soul Harvest Bulwark";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null || ctx.mob().distanceToSqr(target) > 576.0) {
            return false;
        }
        Mob mob = ctx.mob();
        return mob.getHealth() <= mob.getMaxHealth() * 0.5f;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();

        // The armored form: armor and blast padding maxed, grown larger.
        WitherAbilitySupport.addModifier(mob, Attributes.ARMOR,
            new AttributeModifier(ARMOR_ID, 20.0, AttributeModifier.Operation.ADD_VALUE));
        WitherAbilitySupport.addModifier(mob, Attributes.EXPLOSION_KNOCKBACK_RESISTANCE,
            new AttributeModifier(BLAST_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        WitherAbilitySupport.addModifier(mob, Attributes.SCALE,
            new AttributeModifier(SCALE_ID, 0.5, AttributeModifier.Operation.ADD_VALUE));

        state.putInt("kills", ctx.data().getKillCount());
        state.putInt("souls", 0);
        state.putDouble("angle", 0.0);

        // The boss bar soul counter (see the class comment): only
        // when the wither has no name of its own to lose.
        if (mob.getCustomName() == null) {
            state.putInt("named", 1);
            mob.setCustomName(Component.literal("Wither - Souls: 0"));
        } else {
            state.putInt("named", 0);
        }

        ListTag skulls = new ListTag();
        ServerLevel level = ctx.serverLevel();
        int alive = 0;
        for (int i = 0; i < SKULL_COUNT; i++) {
            CompoundTag entry = new CompoundTag();
            if (level != null) {
                Entity display = WitherAbilitySupport.createSkullDisplay(level, 1.0f);
                if (display != null) {
                    double angle = i * (Math.PI * 2.0 / SKULL_COUNT);
                    display.snapTo(mob.getX() + Math.cos(angle) * ORBIT_RADIUS,
                        mob.getY() + ORBIT_HEIGHT, mob.getZ() + Math.sin(angle) * ORBIT_RADIUS,
                        0.0f, 0.0f);
                    if (level.addFreshEntity(display)) {
                        entry.store("id", UUIDUtil.CODEC, display.getUUID());
                        alive++;
                    }
                }
            }
            skulls.add(entry);
        }
        state.put("skulls", skulls);
        state.putInt("alive", alive);

        if (level != null) {
            level.sendParticles(ParticleTypes.SCULK_SOUL, mob.getX(), mob.getY() + ORBIT_HEIGHT, mob.getZ(),
                16, 1.8, 0.8, 1.8, 0.06);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 1.2f, 0.7f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        int ticks = ctx.ticksActive();

        // The orbit: the ring turns, and every surviving skull
        // glides to its new point.
        double angle = state.getDoubleOr("angle", 0.0) + ORBIT_SPEED;
        state.putDouble("angle", angle);
        ListTag skulls = state.getListOrEmpty("skulls");
        for (int i = 0; i < skulls.size(); i++) {
            Entity display = resolveDisplay(level, skulls.getCompoundOrEmpty(i));
            if (display == null) {
                continue;
            }
            double slotAngle = angle + i * (Math.PI * 2.0 / SKULL_COUNT);
            double bob = Math.sin(ticks * 0.1 + i * 1.7) * 0.3;
            display.snapTo(mob.getX() + Math.cos(slotAngle) * ORBIT_RADIUS,
                mob.getY() + ORBIT_HEIGHT + bob, mob.getZ() + Math.sin(slotAngle) * ORBIT_RADIUS,
                (float) -Math.toDegrees(slotAngle), 0.0f);
        }

        if (ticks % WISP_INTERVAL == 0) {
            double wispAngle = ctx.random().nextDouble() * Math.PI * 2.0;
            level.sendParticles(ParticleTypes.SCULK_SOUL,
                mob.getX() + Math.cos(wispAngle) * ORBIT_RADIUS, mob.getY() + ORBIT_HEIGHT,
                mob.getZ() + Math.sin(wispAngle) * ORBIT_RADIUS,
                2, 0.25, 0.35, 0.25, 0.03);
        }

        if (ticks % INTERCEPT_INTERVAL == 0) {
            interceptProjectiles(ctx, level, state);
        }

        if (ticks % HARVEST_INTERVAL == 0) {
            harvestSouls(ctx, level, state);
        }
    }

    /**
     * One interception pass (see the class comment): every
     * hostile projectile in the ring's box is discarded, and
     * each one shatters the first surviving skull.
     */
    private void interceptProjectiles(AbilityContext ctx, ServerLevel level, CompoundTag state) {
        if (state.getIntOr("alive", 0) <= 0) {
            return;
        }
        Mob mob = ctx.mob();
        AABB box = new AABB(mob.getX() - 4.5, mob.getY() - 1.0, mob.getZ() - 4.5,
                            mob.getX() + 4.5, mob.getY() + 5.0, mob.getZ() + 4.5);
        List<Projectile> projectiles = level.getEntitiesOfClass(Projectile.class, box,
            p -> p.isAlive() && isHostile(ctx, p));
        ListTag skulls = state.getListOrEmpty("skulls");
        for (Projectile projectile : projectiles) {
            if (state.getIntOr("alive", 0) <= 0) {
                break;
            }
            projectile.discard();
            shatterFirstSkull(level, state, skulls);
        }
    }

    /** Whether a projectile threatens the wither: enemy-owned, or owned by nobody. */
    private boolean isHostile(AbilityContext ctx, Projectile projectile) {
        Entity owner = projectile.getOwner();
        if (owner == null) {
            return true;
        }
        if (owner == ctx.mob()) {
            return false;
        }
        return owner instanceof LivingEntity living && WitherAbilitySupport.isEnemy(ctx, living);
    }

    /** Shatters the first surviving skull slot: its display breaks and the slot empties. */
    private void shatterFirstSkull(ServerLevel level, CompoundTag state, ListTag skulls) {
        for (int i = 0; i < skulls.size(); i++) {
            CompoundTag entry = skulls.getCompoundOrEmpty(i);
            Entity display = resolveDisplay(level, entry);
            if (display == null) {
                continue;
            }
            level.sendParticles(SKULL_FRAGMENTS, display.getX(), display.getY(), display.getZ(),
                8, 0.3, 0.3, 0.3, 0.08);
            level.playSound(null, display.getX(), display.getY(), display.getZ(),
                SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 0.8f, 0.8f);
            display.discard();
            skulls.set(i, new CompoundTag());
            state.putInt("alive", state.getIntOr("alive", 0) - 1);
            return;
        }
    }

    /**
     * One harvest check (see the class comment): new war kills
     * since the last check become souls, each healing the
     * wither and updating the boss bar counter.
     */
    private void harvestSouls(AbilityContext ctx, ServerLevel level, CompoundTag state) {
        int kills = ctx.data().getKillCount();
        int recorded = state.getIntOr("kills", kills);
        if (kills <= recorded) {
            state.putInt("kills", kills);
            return;
        }
        int gained = kills - recorded;
        state.putInt("kills", kills);
        int souls = state.getIntOr("souls", 0) + gained;
        state.putInt("souls", souls);
        Mob mob = ctx.mob();
        mob.heal((float) (HEAL_PER_SOUL * gained));
        if (state.getIntOr("named", 0) != 0) {
            mob.setCustomName(Component.literal("Wither - Souls: " + souls));
        }
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
            SoundEvents.SCULK_CATALYST_BLOOM, SoundSource.HOSTILE, 0.6f, 1.2f);
    }

    /** Resolves a ring slot's display from its state entry, or null when shattered or gone. */
    private Entity resolveDisplay(ServerLevel level, CompoundTag entry) {
        if (!entry.contains("id")) {
            return null;
        }
        UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
        if (id == null) {
            return null;
        }
        Entity entity = level.getEntity(id);
        return entity != null && entity.isAlive() ? entity : null;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS || ctx.state().getIntOr("alive", 0) <= 0;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        WitherAbilitySupport.removeModifier(mob, Attributes.ARMOR, ARMOR_ID);
        WitherAbilitySupport.removeModifier(mob, Attributes.EXPLOSION_KNOCKBACK_RESISTANCE, BLAST_ID);
        WitherAbilitySupport.removeModifier(mob, Attributes.SCALE, SCALE_ID);
        if (mob.getHealth() > mob.getMaxHealth()) {
            mob.setHealth(mob.getMaxHealth());
        }
        // The boss bar counter is this activation's: the name
        // goes back to none. The harvested healing stays.
        if (ctx.state().getIntOr("named", 0) != 0) {
            mob.setCustomName(null);
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag skulls = ctx.state().getListOrEmpty("skulls");
        for (int i = 0; i < skulls.size(); i++) {
            Entity display = resolveDisplay(level, skulls.getCompoundOrEmpty(i));
            if (display != null) {
                display.discard();
            }
        }
    }
}
