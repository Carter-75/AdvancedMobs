package mod.advancedmobs.ability.illusioner;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Phantom Archer Ring (Illusioner ability 4 of 5, summon,
 * construct).
 *
 * WHAT IT DOES: a ring of bows materializes in the air as
 * item displays, floating at head height in a slow orbit
 * around the illusioner, each bow drawing and loosing real
 * arrows at enemies on its own rhythm while the illusioner
 * turns INVISIBLE and watches its own artillery work. The
 * bows are fragile constructs and shatter when struck, and
 * without them the ring is just a hiding illusioner. It is
 * a siege engine built from a magic trick.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the illusioner's current target within 20 blocks
 *   (distance squared 400).
 * - The ring (start()): 6 item displays, each showing a
 *   plain bow at scale 1.0, full brightness, display context
 *   "fixed" (the bows float flat and do not turn; the orbit
 *   carries them), created through the shared display
 *   factory and placed on a circle of radius 2.2 around the
 *   caster at height plus 2.1 blocks (head height). Each bow
 *   carries a phase of slot times 7 ticks for its firing
 *   rhythm. The caster gains INVISIBILITY for 320 ticks,
 *   particles and icon hidden, removed in end().
 * - The orbit (tick(), every tick): each unbroken bow is
 *   teleported to its live orbit point, the caster's current
 *   position plus its slot angle (slot times 60 degrees plus
 *   the ring's slow turn of 0.02 radians per tick, a full
 *   revolution in about 314 ticks) at radius 2.2 and height
 *   plus 2.1. The displays were created with a teleport
 *   duration of 2 ticks, so the per-tick teleports glide
 *   instead of snapping: the transformation interpolation
 *   of the design, carried by the teleport smoothing the
 *   display system provides.
 * - The shooting (tick()): bow in slot i fires when
 *   (ticksActive + i * 7) mod 35 == 0, so the six rhythms
 *   interleave into a shot about every 6 ticks with no two
 *   bows ever firing on the same tick. A shot is one real
 *   Arrow owned by the caster, fired from the bow's current
 *   position at the framework target's mid-body: base
 *   damage 2.0, speed 2.6, inaccuracy 2.0. No living target,
 *   or a target beyond 24 blocks, and the bow holds its
 *   shot that cycle. An ARROW_SHOOT (volume 0.6, pitch 1.1)
 *   plays at the bow.
 * - The shattering (tick(), every 5 ticks): displays have
 *   no hitbox in vanilla (CAPABILITIES.md), so proximity
 *   stands in for being struck, the Potion Roulette and
 *   Turret Stance precedent: a bow shatters when a hostile
 *   or ownerless projectile is within 1.0 blocks of it (one
 *   bounded Projectile query), or an enemy body is within
 *   1.2 blocks of it (one bounded enemy query). The bow's
 *   own side's projectiles never count. A shatter bursts 8
 *   ITEM particles of bow, plays a GLASS_BREAK, discards
 *   the display, and marks the slot broken; a broken bow
 *   neither orbits nor shoots again.
 * - The tell (tick(), every 10 ticks): 2 ENCHANT motes at
 *   one rotating unbroken bow, so the ring glitters as it
 *   turns and a watching enemy can count the bows.
 * - Duration: 300 ticks (15 seconds), hard finish.
 * - end(): each surviving bow is discarded in 4 POOF, and
 *   the caster's INVISIBILITY is removed. If the caster
 *   dies first, the framework termination path runs this
 *   same end(), so the ring never outlives its maker.
 *
 * WHAT IT LOOKS LIKE: six bows hang in the air in a turning
 * crown, glittering faintly, plucking arrows out of
 * nothing one after another in a slow round, while the
 * illusioner who made them is nowhere to be seen. Swat a
 * bow and it shatters like glass.
 *
 * PERFORMANCE: per tick the orbit is at most 6 teleports
 * and at most 1 arrow spawn (the interleaved rhythms);
 * every 5 ticks at most 6 small projectile queries and 6
 * small enemy queries run for the shatter checks; every 10
 * ticks 2 particles. No whole-level scans. Particle budget
 * per activation: shatters at most 6 times 8 = 48, tell
 * motes at most 30 times 2 = 60, end() puffs at most 6
 * times 4 = 24, total at most 132 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply. The bows are display entities, not blocks.
 *
 * State tag keys: "bows" (list of compounds, one per bow:
 * "id" the display's UUID, "slot" 0 to 5, "broken" 1 once
 * the bow has shattered).
 */
public final class PhantomArcherRingAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int BOW_COUNT = 6;
    private static final int FIRE_PERIOD = 35;
    private static final int DEFENSE_INTERVAL = 5;
    private static final double ORBIT_RADIUS = 2.2;
    private static final double ORBIT_HEIGHT = 2.1;
    private static final double ORBIT_TURN = 0.02;

    /** The shatter shards: ITEM particles of a bow. */
    private static final ItemParticleOption BOW_SHARDS =
        new ItemParticleOption(ParticleTypes.ITEM, Items.BOW);

    @Override
    public String id() {
        return "phantom_archer_ring";
    }

    @Override
    public String displayName() {
        return "Phantom Archer Ring";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 400.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();
        ListTag bows = new ListTag();
        ctx.state().put("bows", bows);
        if (level == null) {
            return;
        }
        for (int slot = 0; slot < BOW_COUNT; slot++) {
            Entity display = IllusionerAbilitySupport.createItemDisplay(level, new ItemStack(Items.BOW), 1.0f);
            if (display == null) {
                continue;
            }
            double angle = slot * Math.PI / 3.0;
            display.snapTo(caster.getX() + Math.cos(angle) * ORBIT_RADIUS,
                caster.getY() + ORBIT_HEIGHT,
                caster.getZ() + Math.sin(angle) * ORBIT_RADIUS, 0.0f, 0.0f);
            if (!level.addFreshEntity(display)) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, display.getUUID());
            entry.putInt("slot", slot);
            entry.putInt("broken", 0);
            bows.add(entry);
        }
        ctx.state().put("bows", bows);
        caster.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, DURATION_TICKS + 20, 0, false, false, false));
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.ILLUSIONER_CAST_SPELL, SoundSource.HOSTILE, 1.1f, 1.0f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        int ticks = ctx.ticksActive();
        boolean defenseTick = ticks % DEFENSE_INTERVAL == 0;
        LivingEntity target = ctx.target();
        ListTag bows = ctx.state().getListOrEmpty("bows");
        for (int i = 0; i < bows.size(); i++) {
            CompoundTag entry = bows.getCompoundOrEmpty(i);
            if (entry.getIntOr("broken", 0) == 1) {
                continue;
            }
            Entity display = IllusionerAbilitySupport.resolveTracked(level, entry);
            if (display == null) {
                // A bow that no longer resolves was removed
                // by something outside the ability: treat it
                // as shattered, silently (no position to
                // burst at, and the shatter pass below owns
                // the noisy version).
                entry.putInt("broken", 1);
                bows.set(i, entry);
                continue;
            }
            int slot = entry.getIntOr("slot", 0);
            double angle = slot * Math.PI / 3.0 + ticks * ORBIT_TURN;
            double bowX = caster.getX() + Math.cos(angle) * ORBIT_RADIUS;
            double bowY = caster.getY() + ORBIT_HEIGHT;
            double bowZ = caster.getZ() + Math.sin(angle) * ORBIT_RADIUS;
            display.teleportTo(bowX, bowY, bowZ);
            // The bow's own rhythm (see the class comment).
            if (ticks > 0 && (ticks + slot * 7) % FIRE_PERIOD == 0
                && target != null && caster.distanceToSqr(target) <= 576.0) {
                IllusionerAbilitySupport.fireArrow(ctx,
                    new Vec3(bowX, bowY, bowZ), target, 2.0, 2.6f, 2.0f);
                level.playSound(null, bowX, bowY, bowZ,
                    SoundEvents.ARROW_SHOOT, SoundSource.HOSTILE, 0.6f, 1.1f);
            }
            if (ticks % 10 == 0 && slot == (ticks / 10) % BOW_COUNT) {
                level.sendParticles(ParticleTypes.ENCHANT, bowX, bowY, bowZ,
                    2, 0.15, 0.15, 0.15, 0.05);
            }
            if (defenseTick && isStruck(ctx, level, display)) {
                shatterBow(level, entry, display);
            }
            bows.set(i, entry);
        }
        ctx.state().put("bows", bows);
    }

    /**
     * Whether anything hostile has reached the bow at its
     * current position (see the shattering note in the class
     * comment): a hostile or ownerless projectile within 1.0
     * blocks, or an enemy body within 1.2 blocks.
     */
    private static boolean isStruck(AbilityContext ctx, ServerLevel level, Entity display) {
        AABB near = new AABB(display.getX() - 1.0, display.getY() - 1.0, display.getZ() - 1.0,
                             display.getX() + 1.0, display.getY() + 1.0, display.getZ() + 1.0);
        List<Projectile> projectiles = level.getEntitiesOfClass(Projectile.class, near, projectile -> {
            Entity owner = projectile.getOwner();
            if (owner == null) {
                return true;
            }
            if (owner == ctx.mob()) {
                return false;
            }
            return !(owner instanceof LivingEntity living) || !ctx.data().isAlly(living.getUUID());
        });
        if (!projectiles.isEmpty()) {
            return true;
        }
        return !IllusionerAbilitySupport.enemiesInBox(ctx,
            display.getX(), display.getY(), display.getZ(), 1.2, 1.2, 1.2).isEmpty();
    }

    /** The shatter burst for one struck bow (see the class comment). */
    private static void shatterBow(ServerLevel level, CompoundTag entry, Entity display) {
        entry.putInt("broken", 1);
        level.sendParticles(BOW_SHARDS, display.getX(), display.getY(), display.getZ(),
            8, 0.25, 0.25, 0.25, 0.08);
        level.playSound(null, display.getX(), display.getY(), display.getZ(),
            SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 0.9f, 1.3f);
        display.discard();
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.removeEffect(MobEffects.INVISIBILITY);
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag bows = ctx.state().getListOrEmpty("bows");
        for (int i = 0; i < bows.size(); i++) {
            CompoundTag entry = bows.getCompoundOrEmpty(i);
            if (entry.getIntOr("broken", 0) == 1) {
                continue;
            }
            Entity found = IllusionerAbilitySupport.resolveTracked(level, entry);
            if (found != null) {
                level.sendParticles(ParticleTypes.POOF, found.getX(), found.getY(), found.getZ(),
                    4, 0.2, 0.2, 0.2, 0.04);
                found.discard();
            }
        }
    }
}
