package mod.advancedmobs.ability.stray;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
 * Frozen Carapace (Stray ability 3 of 5, defense).
 *
 * WHAT IT DOES: plates of blue ice grow over the stray's body as
 * block displays at full brightness, locking together with SCRAPE
 * flashes and curls of WHITE_SMOKE. The carapace grants heavy
 * ARMOR and explosion knockback resistance, and melee attackers
 * who strike it are chilled with SLOWNESS by the cold bleeding
 * off the plates.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists within 12 blocks
 *   (distance squared 144).
 * - Duration: exactly 240 ticks (12 seconds) from start().
 * - The plates: 4 block displays of blue ice, scale 0.85, full
 *   brightness (block and sky light 15, set by the display
 *   helper), each tilted 0.35 radians about its local Z axis
 *   (alternating sign per plate, so the shell reads as locked
 *   plates, not a fence). They hover at radius 0.85 around the
 *   stray at the four diagonal bearings (45, 135, 225, 315
 *   degrees), height 1.0 above the stray's feet, and are
 *   repositioned onto the stray every tick: the shell travels
 *   with it. They are pure visuals: displays have no hitbox and
 *   block nothing themselves; the defense is the attributes
 *   below.
 * - The hardening, for the duration: an ARMOR add_value modifier
 *   of +8 (modifier id advancedmobs:frozen_carapace_armor, the
 *   heavy armor the design calls for: 8 points is 4 chestplate
 *   icons, about a third of a full armor bar) and an
 *   EXPLOSION_KNOCKBACK_RESISTANCE add_value modifier of +0.5
 *   (modifier id advancedmobs:frozen_carapace_blast_padding,
 *   half of all explosion knockback ignored, the attribute's
 *   whole range being 0 to 1). Both are removed in end().
 * - The chill: every 5 ticks the ability compares the stray's
 *   last-hurt-by-mob timestamp with the stored one. A new
 *   timestamp means a melee hit landed (ranged attackers also
 *   set the timestamp when their projectile hits; the chill
 *   answers them too, the cold bleeds off the plates along the
 *   hit either way). The attacker, when it is an enemy
 *   (StrayAbilitySupport rules), gets SLOWNESS II (amplifier 1)
 *   for 60 ticks and a 4 particle SCRAPE flash at its position.
 *   Chills are at most one per 20 ticks, so focus fire cannot
 *   turn the shell into a particle fountain or a permanent
 *   slow-lock; the timestamp still updates on every check, so a
 *   hit swallowed by the chill cooldown is simply answered by
 *   the next one. The stored timestamp is initialized in start()
 *   to the stray's current one, so a hit that landed before the
 *   carapace grew is never answered.
 *
 * WHAT IT LOOKS LIKE: four glowing blue ice plates locking
 * around the stray in a burst of 10 SCRAPE sparks and 6
 * WHITE_SMOKE curls with a glassy GLASS_HIT crack (volume 1.0,
 * pitch 0.7), 2 more WHITE_SMOKE curls breathing off the plates
 * every 20 ticks, and a SCRAPE flash on the attacker each time
 * the cold answers a hit.
 *
 * PERFORMANCE: per tick the work is 4 UUID resolves and 4
 * snapTo writes for the plates, nothing else; the chill check
 * runs every 5 ticks (one timestamp read, and only on a new hit
 * one attacker resolve and one effect application), the smoke
 * every 20 ticks. No entity scans, no allocations in the tick
 * path beyond the resolved references. Particle budget per
 * activation: 16 at start, smoke at most 12 pulses times 2 =
 * 24, chills at most 12 times 4 = 48, total at most 88
 * particles. The 4 displays are the only extra entities, alive
 * only for the ability's 12 seconds.
 *
 * CLEANUP: end() removes both attribute modifiers and discards
 * every plate display that still resolves. If the caster dies
 * mid-carapace, the framework termination path runs this same
 * end(), so the shell never outlives the ability or its caster.
 *
 * State tag keys: "plates" (list of compounds, one per plate:
 * "id", the display's UUID), "chill_ts" (the last-hurt-by-mob
 * timestamp already answered), "last_chill" (tick of the last
 * chill).
 */
public final class FrozenCarapaceAbility implements MobAbility {

    static final int DURATION_TICKS = 240;
    private static final int PLATE_COUNT = 4;
    private static final double PLATE_RADIUS = 0.85;
    private static final float PLATE_SCALE = 0.85f;
    private static final int CHILL_INTERVAL = 5;
    private static final int CHILL_COOLDOWN = 20;
    private static final Identifier ARMOR_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "frozen_carapace_armor");
    private static final Identifier BLAST_PADDING_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "frozen_carapace_blast_padding");

    @Override
    public String id() {
        return "frozen_carapace";
    }

    @Override
    public String displayName() {
        return "Frozen Carapace";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        state.putInt("chill_ts", mob.getLastHurtByMobTimestamp());
        state.putInt("last_chill", -CHILL_COOLDOWN);

        // The hardening.
        AttributeInstance armor = mob.getAttribute(Attributes.ARMOR);
        if (armor != null) {
            armor.addOrUpdateTransientModifier(
                new AttributeModifier(ARMOR_ID, 8.0, AttributeModifier.Operation.ADD_VALUE));
        }
        AttributeInstance blastPadding = mob.getAttribute(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE);
        if (blastPadding != null) {
            blastPadding.addOrUpdateTransientModifier(
                new AttributeModifier(BLAST_PADDING_ID, 0.5, AttributeModifier.Operation.ADD_VALUE));
        }

        // The plates lock together.
        ListTag plates = new ListTag();
        if (level != null) {
            for (int i = 0; i < PLATE_COUNT; i++) {
                // Tilt about the local Z axis by +/- 0.35 radians,
                // alternating per plate, as a quaternion (x, y, z, w).
                double tilt = (i % 2 == 0 ? 0.35 : -0.35) / 2.0;
                Entity plate = StrayAbilitySupport.createBrightBlockDisplay(level,
                    Blocks.BLUE_ICE.defaultBlockState(), PLATE_SCALE,
                    0.0f, 0.0f, (float) Math.sin(tilt), (float) Math.cos(tilt));
                if (plate == null) {
                    continue;
                }
                positionPlate(ctx, plate, i);
                level.addFreshEntity(plate);
                CompoundTag entry = new CompoundTag();
                entry.store("id", UUIDUtil.CODEC, plate.getUUID());
                plates.add(entry);
            }
            level.sendParticles(ParticleTypes.SCRAPE, mob.getX(), mob.getY() + 1.0, mob.getZ(),
                10, 0.7, 0.7, 0.7, 0.1);
            level.sendParticles(ParticleTypes.WHITE_SMOKE, mob.getX(), mob.getY() + 1.0, mob.getZ(),
                6, 0.6, 0.6, 0.6, 0.03);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.GLASS_HIT, SoundSource.HOSTILE, 1.0f, 0.7f);
        }
        state.put("plates", plates);
    }

    /** Places one plate on its diagonal bearing around the stray. */
    private void positionPlate(AbilityContext ctx, Entity plate, int index) {
        Mob mob = ctx.mob();
        double angle = Math.PI / 4.0 + index * (Math.PI / 2.0);
        double x = mob.getX() + Math.cos(angle) * PLATE_RADIUS;
        double z = mob.getZ() + Math.sin(angle) * PLATE_RADIUS;
        plate.snapTo(x, mob.getY() + 1.0, z, (float) Math.toDegrees(-angle), 0.0f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        CompoundTag state = ctx.state();
        ListTag plates = state.getListOrEmpty("plates");

        // The shell travels with the stray: every plate is
        // repositioned every tick.
        for (int i = 0; i < plates.size(); i++) {
            Entity plate = resolvePlate(level, plates.getCompoundOrEmpty(i));
            if (plate != null) {
                positionPlate(ctx, plate, i);
            }
        }

        // The chill: a fresh hit on the plates chills the attacker.
        if (ticks % CHILL_INTERVAL == 0) {
            Mob mob = ctx.mob();
            int timestamp = mob.getLastHurtByMobTimestamp();
            if (timestamp != state.getIntOr("chill_ts", -1)) {
                state.putInt("chill_ts", timestamp);
                LivingEntity attacker = mob.getLastHurtByMob();
                if (attacker != null && StrayAbilitySupport.isEnemy(ctx, attacker)
                    && ticks - state.getIntOr("last_chill", 0) >= CHILL_COOLDOWN) {
                    attacker.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 1));
                    level.sendParticles(ParticleTypes.SCRAPE,
                        attacker.getX(), attacker.getY() + 1.0, attacker.getZ(),
                        4, 0.4, 0.5, 0.4, 0.08);
                    state.putInt("last_chill", ticks);
                }
            }
        }

        if (ticks % 20 == 0) {
            Mob mob = ctx.mob();
            level.sendParticles(ParticleTypes.WHITE_SMOKE, mob.getX(), mob.getY() + 1.0, mob.getZ(),
                2, 0.7, 0.6, 0.7, 0.02);
        }
    }

    /** Resolves a tracked plate display from its list entry, or null when gone. */
    private Entity resolvePlate(ServerLevel level, CompoundTag entry) {
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
        AttributeInstance blastPadding = mob.getAttribute(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE);
        if (blastPadding != null) {
            blastPadding.removeModifier(BLAST_PADDING_ID);
        }

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag plates = ctx.state().getListOrEmpty("plates");
        for (int i = 0; i < plates.size(); i++) {
            Entity plate = resolvePlate(level, plates.getCompoundOrEmpty(i));
            if (plate != null) {
                plate.discard();
            }
        }
    }
}
