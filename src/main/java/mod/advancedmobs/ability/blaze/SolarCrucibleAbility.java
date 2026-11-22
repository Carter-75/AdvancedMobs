package mod.advancedmobs.ability.blaze;

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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/**
 * Solar Crucible (Blaze ability 1 of 5, transformation).
 *
 * WHAT IT DOES: the blaze swells to one and a half times its
 * size under a constant storm of flame, a ring of fire
 * orbiting its body, its rods glowing white hot. In this form
 * it hits harder, shrugs off knockback, and every few seconds
 * it sheds a burst of embers that ignites every enemy close
 * to it. The form lasts until the blaze has fired ten
 * volleys; then it vents a pillar of smoke and shrinks back
 * down.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists within 16 blocks
 *   (distance squared 256).
 * - The form (start(), all removed in end() under fixed ids):
 *   SCALE add_multiplied_base +0.5 (one and a half times birth
 *   size; modifier id advancedmobs:solar_crucible_scale),
 *   ATTACK_DAMAGE add_value +4.0 (modifier id
 *   advancedmobs:solar_crucible_damage), and
 *   KNOCKBACK_RESISTANCE add_value +0.5 (half of all knockback
 *   ignored, the attribute's range being 0 to 1; modifier id
 *   advancedmobs:solar_crucible_knockback).
 * - What "hits harder" can and cannot mean: ATTACK_DAMAGE is
 *   the blaze's attack attribute, so its melee contact hit
 *   (a blaze that drifts into its target does hit in melee)
 *   goes from the vanilla 6.0 to 10.0. The blaze's fireballs
 *   are NOT raised: vanilla SmallFireball impact damage is a
 *   fixed 5.0 inside the projectile class, verified against
 *   the real 26.2 jar, and it ignores the owner's attributes
 *   entirely, so no attribute can raise it. The form's extra
 *   ranged pressure is the ember bursts below plus the fact
 *   that a bigger, knockback-proof blaze holds its firing
 *   position and keeps its volleys on schedule.
 * - Volley counting (tick(), every 2 ticks): one bounded
 *   query for SmallFireball entities in a box of radius 3.0
 *   around the blaze. A fireball whose owner is the blaze and
 *   whose UUID is not in the seen list is a fresh shot and
 *   increments the shot counter; the seen list is then
 *   replaced by the fireballs currently in the box, so a
 *   fireball is counted exactly once, on the first poll after
 *   it leaves the blaze (fireballs fly straight away and
 *   never re-enter the box). A vanilla blaze volley is a
 *   burst of 3 fireballs, so the form ends at 30 counted
 *   shots: ten volleys. Fireballs the blaze fired before the
 *   form began can be caught by the first poll and counted;
 *   at most one stray volley, accepted.
 * - Ember bursts (tick(), every 80 ticks): every enemy in
 *   radius 4.0 of the blaze (1.5 below to 2.5 above it) is
 *   ignited for 3 seconds (igniteForSeconds; the burn itself
 *   is vanilla fire ticks, about 3 damage over the burn on an
 *   unarmored enemy), with a burst of 12 FLAME at the blaze's
 *   body and a BLAZE_SHOOT (volume 0.7, pitch 0.7).
 * - Duration: until 30 shots are counted, with a hard finish
 *   at 1200 ticks (60 seconds) so a blaze that cannot fire
 *   (no line of sight for a full minute) still comes down.
 * - The vent (end()): after the modifiers come off, 10
 *   LARGE_SMOKE particles vent upward out of the blaze's body
 *   and a BLAZE_BURN (volume 0.8, pitch 1.2) sighs out: the
 *   crucible cooling as it shrinks.
 *
 * WHAT IT LOOKS LIKE: a blaze half again as tall, wrapped in
 * a two-particle storm of FLAME and SMALL_FLAME every 2 ticks
 * (one of each, at random points in its body), with a ring of
 * 4 FLAME orbiting at its mid-height every 4 ticks, turning
 * with the activation clock. The ember bursts are the storm
 * flaring outward: 12 FLAME in a wide spread with the deep
 * shoot sound.
 *
 * PERFORMANCE: per tick the work is the storm pulse every 2
 * ticks (2 particle spawns), the ring pulse every 4 ticks
 * (4 spawns), the fireball poll every 2 ticks (one bounded
 * query for a single entity class in a radius 3.0 box, plus
 * UUID compares against a list that never holds more than the
 * fireballs in that box), and the ember burst every 80 ticks
 * (one bounded enemy query). No whole-level scans, no
 * allocations in the tick path beyond the query results and
 * the rebuilt seen list every 2 ticks. Particle budget per
 * activation, capped by the 1200 tick hard finish: storm at
 * most 600 pulses times 2 = 1200, ring at most 300 pulses
 * times 4 = 1200, ember bursts at most 15 times 12 = 180,
 * the start burst 16 and the vent 10, total at most 2606
 * particles spread over up to 60 seconds (a typical ten
 * volley form runs about 40 seconds and uses about two
 * thirds of that).
 *
 * CLEANUP: end() removes all three attribute modifiers by id
 * and vents the smoke. Nothing else persists: the storm, the
 * ring, and the shot counter live only in particles and the
 * state tag.
 *
 * State tag keys: "shots" (fireballs counted this form),
 * "seen" (list of compounds, one per fireball currently in
 * the poll box: "id", the fireball's UUID).
 */
public final class SolarCrucibleAbility implements MobAbility {

    private static final int HARD_FINISH_TICKS = 1200;
    private static final int SHOTS_TO_FINISH = 30;
    private static final int EMBER_INTERVAL = 80;
    private static final double EMBER_RADIUS = 4.0;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "solar_crucible_scale");
    private static final Identifier DAMAGE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "solar_crucible_damage");
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "solar_crucible_knockback");

    @Override
    public String id() {
        return "solar_crucible";
    }

    @Override
    public String displayName() {
        return "Solar Crucible";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return ctx.target() != null && ctx.mob().distanceToSqr(ctx.target()) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        state.putInt("shots", 0);
        state.put("seen", new ListTag());
        BlazeAbilitySupport.addModifier(mob, Attributes.SCALE,
            new AttributeModifier(SCALE_ID, 0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        BlazeAbilitySupport.addModifier(mob, Attributes.ATTACK_DAMAGE,
            new AttributeModifier(DAMAGE_ID, 4.0, AttributeModifier.Operation.ADD_VALUE));
        BlazeAbilitySupport.addModifier(mob, Attributes.KNOCKBACK_RESISTANCE,
            new AttributeModifier(KNOCKBACK_ID, 0.5, AttributeModifier.Operation.ADD_VALUE));
        if (level != null) {
            level.sendParticles(ParticleTypes.FLAME, mob.getX(), mob.getY() + 1.0, mob.getZ(),
                16, mob.getBbWidth() * 0.6, 0.8, mob.getBbWidth() * 0.6, 0.06);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.BLAZE_BURN,
                SoundSource.HOSTILE, 1.0f, 0.8f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        Mob mob = ctx.mob();

        if (ticks % 2 == 0) {
            // The storm: one FLAME and one SMALL_FLAME at random
            // points in the blaze's body.
            double spread = mob.getBbWidth() * 0.5;
            double height = mob.getBbHeight();
            level.sendParticles(ParticleTypes.FLAME,
                mob.getX() + (ctx.random().nextDouble() * 2.0 - 1.0) * spread,
                mob.getY() + ctx.random().nextDouble() * height,
                mob.getZ() + (ctx.random().nextDouble() * 2.0 - 1.0) * spread,
                1, 0.0, 0.02, 0.0, 0.0);
            level.sendParticles(ParticleTypes.SMALL_FLAME,
                mob.getX() + (ctx.random().nextDouble() * 2.0 - 1.0) * spread,
                mob.getY() + ctx.random().nextDouble() * height,
                mob.getZ() + (ctx.random().nextDouble() * 2.0 - 1.0) * spread,
                1, 0.0, 0.02, 0.0, 0.0);
            countShots(ctx, level);
        }
        if (ticks % 4 == 0) {
            // The orbiting ring of fire: 4 FLAME at the blaze's
            // mid-height, turning with the activation clock.
            double radius = mob.getBbWidth() / 2.0 + 0.7;
            double y = mob.getY() + mob.getBbHeight() * 0.5;
            double turn = ticks * 0.11;
            for (int i = 0; i < 4; i++) {
                double angle = turn + i * (Math.PI / 2.0);
                level.sendParticles(ParticleTypes.FLAME,
                    mob.getX() + Math.cos(angle) * radius, y, mob.getZ() + Math.sin(angle) * radius,
                    1, 0.0, 0.01, 0.0, 0.0);
            }
        }
        if (ticks % EMBER_INTERVAL == 0) {
            shedEmbers(ctx, level);
        }
    }

    /**
     * The volley poll: counts fresh fireballs the blaze fired
     * since the last poll (see the class comment for the
     * counting rule) and refreshes the seen list to the
     * fireballs currently in the poll box.
     */
    private static void countShots(AbilityContext ctx, ServerLevel level) {
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        AABB box = new AABB(mob.getX() - 3.0, mob.getY() - 1.0, mob.getZ() - 3.0,
                            mob.getX() + 3.0, mob.getY() + mob.getBbHeight() + 2.0, mob.getZ() + 3.0);
        List<SmallFireball> fireballs = level.getEntitiesOfClass(SmallFireball.class, box);
        ListTag seen = state.getListOrEmpty("seen");
        ListTag nowSeen = new ListTag();
        int shots = state.getIntOr("shots", 0);
        for (SmallFireball fireball : fireballs) {
            if (fireball.getOwner() != mob) {
                continue;
            }
            if (!wasSeen(seen, fireball.getUUID())) {
                shots++;
            }
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, fireball.getUUID());
            nowSeen.add(entry);
        }
        state.putInt("shots", shots);
        state.put("seen", nowSeen);
    }

    /** Whether the UUID is in the seen list from the previous poll. */
    private static boolean wasSeen(ListTag seen, UUID id) {
        for (int i = 0; i < seen.size(); i++) {
            CompoundTag entry = seen.getCompoundOrEmpty(i);
            if (entry.contains("id") && id.equals(entry.read("id", UUIDUtil.CODEC).orElse(null))) {
                return true;
            }
        }
        return false;
    }

    /** The ember burst: every enemy near the blaze is lit (see the class comment). */
    private static void shedEmbers(AbilityContext ctx, ServerLevel level) {
        Mob mob = ctx.mob();
        List<LivingEntity> victims = BlazeAbilitySupport.enemiesInBox(ctx,
            mob.getX(), mob.getY() + 1.0, mob.getZ(), EMBER_RADIUS, 1.5, 2.5);
        for (LivingEntity victim : victims) {
            victim.igniteForSeconds(3.0f);
        }
        level.sendParticles(ParticleTypes.FLAME, mob.getX(), mob.getY() + 1.0, mob.getZ(),
            12, mob.getBbWidth() * 0.8, 0.7, mob.getBbWidth() * 0.8, 0.1);
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.BLAZE_SHOOT,
            SoundSource.HOSTILE, 0.7f, 0.7f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= HARD_FINISH_TICKS
            || ctx.state().getIntOr("shots", 0) >= SHOTS_TO_FINISH;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        BlazeAbilitySupport.removeModifier(mob, Attributes.SCALE, SCALE_ID);
        BlazeAbilitySupport.removeModifier(mob, Attributes.ATTACK_DAMAGE, DAMAGE_ID);
        BlazeAbilitySupport.removeModifier(mob, Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_ID);
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            // The vent: the crucible cools and the blaze shrinks
            // back down through a pillar of smoke.
            level.sendParticles(ParticleTypes.LARGE_SMOKE, mob.getX(), mob.getY() + 1.2, mob.getZ(),
                10, 0.4, 0.8, 0.4, 0.05);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.BLAZE_BURN,
                SoundSource.HOSTILE, 0.8f, 1.2f);
        }
    }
}
