package mod.advancedmobs.ability.illusioner;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ColorParticleOption;
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
import net.minecraft.world.entity.monster.illager.Illusioner;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.UUID;

/**
 * Shattered Image Nova (Illusioner ability 5 of 5, attack).
 *
 * WHAT IT DOES: the mirrors are weapons even in death:
 * four duplicates walk straight into the enemy line, and
 * when any duplicate is destroyed it does not fade, it
 * shatters, bursting into BLOCK_CRUMBLE shards of pale
 * glass and a FLASH of light that damages and slows every
 * enemy nearby. A clever enemy learns to kill mirrors from
 * range, but a surrounded illusioner will happily let the
 * novas chain through a crowd. The real illusioner takes
 * no harm from its own shattering images: the mirrors are
 * its allies, and the nova only ever touches enemies.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the illusioner's current target within 18 blocks
 *   (distance squared 324).
 * - The mirrors (start()): 4 illusioners on the shared
 *   mirror sheet (see IllusionerAbilitySupport: MAX_HEALTH
 *   base 1.0, ATTACK_DAMAGE base 0.0, allied both directions
 *   plus SummonerTracker, no target of their own), spawned
 *   on a circle of radius 2.0 around the caster at slot
 *   angles 45, 135, 225, and 315 degrees relative to its
 *   facing, with a 12-PORTAL burst and an
 *   ILLUSIONER_PREPARE_MIRROR. One hit of anything kills a
 *   mirror: that fragility is the trigger of the nova.
 * - The advance (tick(), every 5 ticks): each living
 *   mirror is steered by its move control straight at the
 *   framework target's live position at speed 1.3 (or back
 *   toward the caster when no target exists), its own
 *   target cleared, and its position recorded in the same
 *   pass. The mirrors walk the enemy line down; an enemy
 *   who ignores them gets four glass bombs standing in its
 *   face, and an enemy who shoots them eats the novas at
 *   whatever range it chose.
 * - The nova (tick(), every 5 ticks, same pass): a tracked
 *   mirror that no longer resolves, or resolves dead, has
 *   been destroyed: it detonates exactly once at its last
 *   recorded position. The burst is 12 BLOCK_CRUMBLE of
 *   light blue stained glass and 1 FLASH (ColorParticleOption
 *   ARGB 0xFFDFF6FF, a pale cyan-white), a GLASS_BREAK
 *   (volume 1.2, pitch 1.1) under an ILLUSIONER_HURT, and
 *   one bounded query: every enemy within 3.0 blocks
 *   (2.0 vertical) takes 5.0 mob-attack damage credited to
 *   the caster and gains SLOWNESS amplifier 0 for 60 ticks.
 *   The caster and its other mirrors are allies and are
 *   never touched, which is what lets novas chain safely
 *   through the caster's own crowd. The caster's ally
 *   record for the mirror is removed as it detonates. A
 *   mirror that unloads with its chunk reads as destroyed
 *   the same way and detonates at its last recorded
 *   position (the tracked-summon edge case, shared with
 *   every summon in this mod).
 * - Duration: 240 ticks (12 seconds), hard finish.
 * - end(): each surviving mirror is discarded in 6 POOF
 *   WITHOUT detonating: the nova answers destruction by an
 *   enemy during the fight, not the ability's own cleanup,
 *   so a mirror that survives the whole activation simply
 *   winks out. The alliance records are removed on both
 *   sides for every tracked mirror. If the caster dies
 *   first, the framework termination path runs this same
 *   end(), and the survivors wink out with him.
 *   SummonerTracker records are deliberately not
 *   unregistered, matching the other summons in this mod.
 *
 * WHAT IT LOOKS LIKE: four pale illusioners march out of a
 * portal swirl and walk, unhurried and unarmed in effect,
 * straight at the enemy; the first arrow that finds one
 * turns it into a burst of blue glass and white light, and
 * everything standing close staggers away slowed.
 *
 * PERFORMANCE: start() does 4 spawns, once. tick() returns
 * immediately except every 5th tick, where it resolves at
 * most 4 UUIDs and writes at most 4 move-control wanted
 * positions; a detonation adds one bounded radius-3.0
 * query, at most 4 per activation. No other entity scans.
 * Particle budget per activation: 12 at start, novas at
 * most 4 times 13 = 52, end() puffs at most 4 times 6 =
 * 24, total at most 88 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply. The glass is a particle, not a placed block.
 *
 * State tag keys: "mirrors" (list of compounds, one per
 * mirror: "id" the mirror's UUID, "x", "y", "z" its last
 * recorded position, "popped" 1 once its nova ran).
 */
public final class ShatteredImageNovaAbility implements MobAbility {

    private static final int DURATION_TICKS = 240;
    private static final int BABYSIT_INTERVAL = 5;
    private static final int MIRROR_COUNT = 4;
    private static final double SPAWN_RADIUS = 2.0;
    private static final double NOVA_RADIUS = 3.0;
    private static final float NOVA_DAMAGE = 5.0f;

    /** The pale glass of the shatter: BLOCK_CRUMBLE of light blue stained glass. */
    private static final BlockParticleOption GLASS_SHARDS =
        new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, Blocks.STAINED_GLASS.lightBlue().defaultBlockState());

    /** The nova flash: FLASH in pale cyan-white (ARGB). */
    private static final ColorParticleOption NOVA_FLASH =
        ColorParticleOption.create(ParticleTypes.FLASH, 0xFFDFF6FF);

    @Override
    public String id() {
        return "shattered_image_nova";
    }

    @Override
    public String displayName() {
        return "Shattered Image Nova";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 324.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();
        ListTag mirrors = new ListTag();
        ctx.state().put("mirrors", mirrors);
        if (level == null) {
            return;
        }
        double yawRad = Math.toRadians(caster.getYRot());
        for (int slot = 0; slot < MIRROR_COUNT; slot++) {
            double angle = yawRad + Math.PI / 4.0 + slot * Math.PI / 2.0;
            Illusioner mirror = IllusionerAbilitySupport.summonMirror(ctx,
                caster.getX() + Math.cos(angle) * SPAWN_RADIUS, caster.getY(),
                caster.getZ() + Math.sin(angle) * SPAWN_RADIUS, caster.getYRot());
            if (mirror == null) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, mirror.getUUID());
            entry.putDouble("x", mirror.getX());
            entry.putDouble("y", mirror.getY());
            entry.putDouble("z", mirror.getZ());
            entry.putInt("popped", 0);
            mirrors.add(entry);
        }
        ctx.state().put("mirrors", mirrors);
        level.sendParticles(ParticleTypes.PORTAL, caster.getX(), caster.getY() + 1.2, caster.getZ(),
            12, 0.5, 0.7, 0.5, 0.15);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.ILLUSIONER_PREPARE_MIRROR, SoundSource.HOSTILE, 1.2f, 1.0f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % BABYSIT_INTERVAL != 0) {
            return; // Cheap early-out: babysitting runs every 5 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        LivingEntity target = ctx.target();
        double marchX = target != null ? target.getX() : caster.getX();
        double marchY = target != null ? target.getY() : caster.getY();
        double marchZ = target != null ? target.getZ() : caster.getZ();
        ListTag mirrors = ctx.state().getListOrEmpty("mirrors");
        for (int i = 0; i < mirrors.size(); i++) {
            CompoundTag entry = mirrors.getCompoundOrEmpty(i);
            if (entry.getIntOr("popped", 0) == 1) {
                continue;
            }
            Entity found = IllusionerAbilitySupport.resolveTracked(level, entry);
            if (found instanceof Illusioner mirror && mirror.isAlive()) {
                entry.putDouble("x", mirror.getX());
                entry.putDouble("y", mirror.getY());
                entry.putDouble("z", mirror.getZ());
                // The advance (see the class comment): walk
                // the mirror into the enemy line.
                mirror.getMoveControl().setWantedPosition(marchX, marchY, marchZ, 1.3);
                mirror.setTarget(null);
            } else {
                detonate(ctx, level, entry);
            }
            mirrors.set(i, entry);
        }
        ctx.state().put("mirrors", mirrors);
    }

    /** The nova for one destroyed mirror (see the class comment). Runs once per mirror. */
    private static void detonate(AbilityContext ctx, ServerLevel level, CompoundTag entry) {
        entry.putInt("popped", 1);
        UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
        if (id != null) {
            ctx.data().removeAlly(id);
        }
        Mob caster = ctx.mob();
        double x = entry.getDoubleOr("x", caster.getX());
        double y = entry.getDoubleOr("y", caster.getY());
        double z = entry.getDoubleOr("z", caster.getZ());
        level.sendParticles(GLASS_SHARDS, x, y + 1.0, z, 12, 0.4, 0.6, 0.4, 0.15);
        level.sendParticles(NOVA_FLASH, x, y + 1.0, z, 1, 0.0, 0.0, 0.0, 0.0);
        level.playSound(null, x, y, z, SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 1.2f, 1.1f);
        level.playSound(null, x, y, z, SoundEvents.ILLUSIONER_HURT, SoundSource.HOSTILE, 0.9f, 1.4f);
        List<LivingEntity> victims = IllusionerAbilitySupport.enemiesInBox(ctx, x, y, z, NOVA_RADIUS, 2.0, 2.0);
        for (LivingEntity victim : victims) {
            victim.hurtServer(level, level.damageSources().mobAttack(caster), NOVA_DAMAGE);
            victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 0));
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        ListTag mirrors = ctx.state().getListOrEmpty("mirrors");
        for (int i = 0; i < mirrors.size(); i++) {
            CompoundTag entry = mirrors.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id == null) {
                continue;
            }
            ctx.data().removeAlly(id);
            Entity found = level.getEntity(id);
            if (found instanceof Illusioner mirror) {
                MobWarData.get(mirror).removeAlly(caster.getUUID());
                if (mirror.isAlive()) {
                    // No detonation on cleanup (see the class
                    // comment): the survivor just winks out.
                    level.sendParticles(ParticleTypes.POOF, mirror.getX(), mirror.getY() + 1.2, mirror.getZ(),
                        6, 0.3, 0.7, 0.3, 0.05);
                    mirror.discard();
                }
            }
        }
    }
}
