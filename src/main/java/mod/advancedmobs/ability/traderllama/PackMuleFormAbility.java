package mod.advancedmobs.ability.traderllama;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.equine.Llama;

import java.util.List;
import java.util.UUID;

/**
 * Pack Mule Form (Trader Llama ability 4 of 5, transformation).
 *
 * WHAT IT DOES: the trader llama bulks up, a chest settles
 * onto its back as a prop (the saddlebags of the design,
 * carried by the chest and the bigger frame, since no
 * server-side retexture hook exists in 26.2), and in this
 * form it is far harder to move or kill. It can also carry
 * a second mob as a rider: a smaller ally hops up and
 * rides it like a living mount, fighting from the saddle.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the trader llama's current target within 16 blocks
 *   (distance squared 256), and the caster really is a
 *   Llama.
 * - The form (start()): three transient attribute
 *   modifiers under fixed ids, all removed exactly in
 *   end():
 *     SCALE                 add_multiplied_base  +0.35 (1.35x bulk)
 *     KNOCKBACK_RESISTANCE  add_value            +0.9 (nearly immovable)
 *     MAX_HEALTH            add_value            +20.0, arriving full:
 *   current health rises by the same 20 on entry (clamped
 *   to the new max), and after the modifier is removed in
 *   end() the health is clamped back down to the restored
 *   max (the Colossus Form pattern). LLAMA_SWAG plays at
 *   volume 1.0, pitch 0.9 as the gear settles, with 6 POOF
 *   particles around the caster.
 * - The chest (start(), then tick() every tick): one chest
 *   block DISPLAY at scale 0.95, created through the
 *   package's NBT display factory (full brightness,
 *   teleport duration 2 so the per-tick snaps glide). Each
 *   tick it is snapped to the caster's back: the caster's
 *   position, raised to 0.78 of its (scaled) bounding box
 *   height, facing the caster's yaw. A display is a pure
 *   visual: no hitbox, no collision, no block change.
 *   end() discards it. Its UUID is in the state tag.
 * - The rider (start(), then tick() every 20 ticks): when
 *   the caster carries no passenger, one bounded query
 *   (radius 8, vertical reach 3) finds allied Mobs (the
 *   package's ally rule) that are SMALLER than the caster
 *   (bounding box width strictly less than the caster's
 *   current width), not riding anything and carrying
 *   nothing themselves. The nearest hops up: it is placed
 *   directly above the caster's back and startRiding is
 *   called with force; if the mount still refuses, the
 *   ally simply lands beside the caster and fights afoot
 *   (the hop is the only side effect). A successful mount
 *   puffs 4 POOF particles. The rider keeps its own AI and
 *   fights from the saddle with its own attacks; nothing
 *   steers it. If the rider dies or dismounts, the next
 *   20-tick check may recruit a replacement the same way.
 * - Duration: 400 ticks (20 seconds), hard finish.
 * - end(): the rider is put down (ejectPassengers: the
 *   ally is a real mob, not a summon, so it stays in the
 *   world and fights on afoot), the chest display is
 *   discarded, and the three modifiers are removed with
 *   the health clamp described above. If the caster dies
 *   first, the framework termination path runs this same
 *   end(): the passenger is ejected by the death handling
 *   regardless, the display never lingers, and the
 *   modifiers die with the mob.
 *
 * WHAT IT LOOKS LIKE: the llama swells by a third, a
 * wooden chest rides its back like saddlebags, and it
 * plants itself like furniture. A smaller ally scrambles
 * up onto the chest and rides the walking stronghold into
 * the fight.
 *
 * PERFORMANCE: start() is 3 modifier writes, 1 display
 * spawn, and at most one recruitment (one bounded query
 * plus one mount attempt). tick() is one UUID resolve and
 * one display snap per tick, plus the recruitment check
 * every 20 ticks only while the saddle is empty. Particle
 * budget per activation: 6 at start plus 4 per successful
 * mount (at most one mount per 20 ticks, so at most 80),
 * total at most 86 particles.
 *
 * TERRAIN: this ability changes no blocks (the chest is a
 * display entity, a pure visual); the mobGriefing gate
 * does not apply.
 *
 * State tag keys: "chest" (the chest display's UUID,
 * stored directly in the tag).
 */
public final class PackMuleFormAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int RECRUIT_INTERVAL = 20;
    private static final double CHEST_SCALE = 0.95;
    private static final double RECRUIT_RADIUS = 8.0;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "pack_mule_scale");
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "pack_mule_knockback");
    private static final Identifier HEALTH_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "pack_mule_health");

    @Override
    public String id() {
        return "pack_mule_form";
    }

    @Override
    public String displayName() {
        return "Pack Mule Form";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob() instanceof Llama
            && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();

        // The bulk: bigger, planted, and harder to kill.
        TraderLlamaAbilitySupport.addModifier(caster, Attributes.SCALE,
            new AttributeModifier(SCALE_ID, 0.35, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        TraderLlamaAbilitySupport.addModifier(caster, Attributes.KNOCKBACK_RESISTANCE,
            new AttributeModifier(KNOCKBACK_ID, 0.9, AttributeModifier.Operation.ADD_VALUE));
        TraderLlamaAbilitySupport.addModifier(caster, Attributes.MAX_HEALTH,
            new AttributeModifier(HEALTH_ID, 20.0, AttributeModifier.Operation.ADD_VALUE));
        caster.setHealth(Math.min(caster.getMaxHealth(), caster.getHealth() + 20.0f));

        if (level == null) {
            return;
        }
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.LLAMA_SWAG,
            SoundSource.HOSTILE, 1.0f, 0.9f);
        level.sendParticles(ParticleTypes.POOF, caster.getX(), caster.getY() + 0.8, caster.getZ(),
            6, 0.5, 0.5, 0.5, 0.05);

        // The chest settles onto the back.
        Entity chest = TraderLlamaAbilitySupport.createChestDisplay(level, (float) CHEST_SCALE);
        if (chest != null) {
            snapChest(caster, chest);
            if (level.addFreshEntity(chest)) {
                ctx.state().store("chest", UUIDUtil.CODEC, chest.getUUID());
            }
        }

        // A smaller ally hops up right away, if one is near.
        recruitRider(ctx, level);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();

        // The chest rides the back, snapped every tick.
        UUID chestId = ctx.state().read("chest", UUIDUtil.CODEC).orElse(null);
        if (chestId != null) {
            Entity chest = level.getEntity(chestId);
            if (chest != null) {
                snapChest(caster, chest);
            }
        }

        // Keep the saddle filled while the form lasts.
        if (ctx.ticksActive() % RECRUIT_INTERVAL == 0 && caster.getPassengers().isEmpty()) {
            recruitRider(ctx, level);
        }
    }

    /** Snaps the chest display onto the caster's back, facing the caster's way. */
    private void snapChest(Mob caster, Entity chest) {
        chest.snapTo(caster.getX(), caster.getY() + caster.getBbHeight() * 0.78, caster.getZ(),
            caster.getYRot(), 0.0f);
    }

    /**
     * Recruits the nearest smaller ally as the rider (see
     * the class comment): one bounded query, one mount
     * attempt. Does nothing while a passenger is aboard.
     */
    private void recruitRider(AbilityContext ctx, ServerLevel level) {
        Mob caster = ctx.mob();
        if (!caster.getPassengers().isEmpty()) {
            return;
        }
        List<LivingEntity> allies = TraderLlamaAbilitySupport.alliesInBox(ctx,
            caster.getX(), caster.getY(), caster.getZ(), RECRUIT_RADIUS, 3.0, 3.0);
        Mob nearest = null;
        double nearestSqr = Double.MAX_VALUE;
        for (LivingEntity candidate : allies) {
            if (candidate instanceof Mob mob
                && mob.getBbWidth() < caster.getBbWidth()
                && mob.getVehicle() == null
                && mob.getPassengers().isEmpty()) {
                double distanceSqr = caster.distanceToSqr(mob);
                if (distanceSqr < nearestSqr) {
                    nearestSqr = distanceSqr;
                    nearest = mob;
                }
            }
        }
        if (nearest == null) {
            return;
        }
        // The hop: placed on the back, then the mount call with force.
        nearest.teleportTo(caster.getX(), caster.getY() + caster.getBbHeight() + 0.5, caster.getZ());
        if (nearest.startRiding(caster, true, true)) {
            level.sendParticles(ParticleTypes.POOF, caster.getX(), caster.getY() + caster.getBbHeight(),
                caster.getZ(), 4, 0.4, 0.2, 0.4, 0.05);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();

        // The rider is put down; it is a real ally and stays in the world.
        if (!caster.getPassengers().isEmpty()) {
            caster.ejectPassengers();
        }

        // The chest comes off.
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            UUID chestId = ctx.state().read("chest", UUIDUtil.CODEC).orElse(null);
            if (chestId != null) {
                Entity chest = level.getEntity(chestId);
                if (chest != null) {
                    chest.discard();
                }
            }
        }

        // The bulk comes off, health clamped to the restored max.
        TraderLlamaAbilitySupport.removeModifier(caster, Attributes.SCALE, SCALE_ID);
        TraderLlamaAbilitySupport.removeModifier(caster, Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_ID);
        TraderLlamaAbilitySupport.removeModifier(caster, Attributes.MAX_HEALTH, HEALTH_ID);
        if (caster.getHealth() > caster.getMaxHealth()) {
            caster.setHealth(caster.getMaxHealth());
        }
    }
}
