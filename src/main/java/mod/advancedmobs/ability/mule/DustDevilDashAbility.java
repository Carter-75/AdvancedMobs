package mod.advancedmobs.ability.mule;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Dust Devil Dash (Mule ability 5 of 5, mobility).
 *
 * WHAT IT DOES: the mule kicks up a spinning column of
 * dust and launches into a frictionless dash the full
 * length of the arena: ground friction and air drag both
 * cut to zero, so nothing bleeds its speed. Enemies it
 * clips on the way through are spun aside with knockback
 * and a brief slow, left coughing in the dust devil's wake.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the mule's current target within 16 blocks (distance
 *   squared 256) and the mule standing on the ground (a
 *   dash starts from the floor, not mid-jump).
 * - The physics (all transient modifiers added in start(),
 *   removed in end(), ids under advancedmobs:dust_devil_*):
 *     FRICTION_MODIFIER    add_value -1.0 (1.0 to 0.0: the
 *                            design's friction line at
 *                            zero, ground contact applies no
 *                            friction at all to the mule)
 *     AIR_DRAG_MODIFIER    add_value -1.0 (1.0 to 0.0: the
 *                            design's air drag line at
 *                            zero, the dash loses nothing
 *                            to the air either)
 *     MOVEMENT_SPEED       add_multiplied_base +0.30 (the
 *                            design's speed line; the dash
 *                            itself is code-driven, this
 *                            keeps the mule's own locomotion
 *                            brisk if the dash is cut short)
 *     ATTACK_KNOCKBACK     add_value +2.0 (the design's
 *                            knockback line; the clip shove
 *                            below is code velocity and
 *                            never depends on it)
 * - The dash itself is code-driven velocity, written every
 *   tick of the dash phase: horizontal velocity is the
 *   stored dash direction times 0.8 blocks per tick (16
 *   blocks per second), the vertical component is left
 *   alone (0 while grounded, the live fall velocity while
 *   airborne over a ledge), flagged hurtMarked so it
 *   syncs. The direction is locked in start(), aimed at
 *   the target's position, and never steers. Navigation is
 *   stopped in start() so the mule's own goals do not fight
 *   the dash, and MULE_ANGRY (volume 1.0) plays as it
 *   launches.
 * - The dash phase runs at most 40 ticks (32 blocks at
 *   full speed). It ends early when the way ahead is
 *   blocked: every tick the mule's bounding box is probed
 *   0.5 blocks ahead along the dash direction (one
 *   collision check); a blocked probe ends the dash on the
 *   spot. Either way the mule then skids for 10 ticks with
 *   its horizontal velocity damped to a fifth, and the
 *   activation finishes when the skid ends (hard cap 70
 *   ticks overall).
 * - The dust devil (every 2 ticks of the dash phase): 3
 *   DUST_PILLAR particles of the ground state spiral up
 *   around the mule: one each at heights +0.2, +0.9, and
 *   +1.6 blocks, at radius 0.7 on an angle that advances
 *   0.6 radians per send, so the column visibly spins (the
 *   design's dust pillar column).
 * - The clip (every 2 ticks of the dash phase, one tight
 *   query): each enemy within radius 2.0 of the mule that
 *   has not yet been clipped this dash (a hit list in the
 *   state tag, capped at 16) is spun aside: no damage, a
 *   shove of 1.1 horizontal along the dash's perpendicular
 *   (the side the enemy is standing on decides the sign,
 *   so victims spin off the dash line like tops) and 0.3
 *   up, hurtMarked, plus SLOWNESS I for 60 ticks (the
 *   design's brief slow), and a 4 DUST_PILLAR burst at the
 *   victim. Once per enemy per dash, by the hit list.
 * - end(): all four modifiers are removed and the dash
 *   velocity is damped to a fifth if the mule is somehow
 *   still at dash speed (a terminated dash never leaves
 *   the mule sliding). The hit list lives in the state tag
 *   and dies with the activation.
 *
 * WHAT IT LOOKS LIKE: a bray, a spinning column of dust
 * pillars wrapping the mule, and then it is simply GONE
 * down the arena in a straight line, enemies cartwheeling
 * sideways out of its path slowed and spinning, until it
 * piles into a wall or runs out of arena and skids to a
 * stop in its own dust.
 *
 * PERFORMANCE: per dash tick the cost is one collision
 * probe, one velocity write, and on even ticks one tight
 * radius-2.0 query plus 3 column particles; the skid phase
 * does one velocity write per tick. Modifiers are resolved
 * at start() and removed at end(). Particle budget per
 * activation, hard caps by construction: the column at
 * most 20 sends of 3 = 60, clips at most 16 victims times
 * 4 = 64, total at most 124 particles.
 *
 * TERRAIN: this ability places or breaks no blocks (the
 * dust is particles of the ground state, not placed
 * blocks), so the mobGriefing gate does not apply.
 *
 * State tag keys: "dx", "dz" (dash direction, normalized),
 * "dash_end" (the tick the dash phase ended, 0 while still
 * dashing), "clipped" (list of compounds, one per clipped
 * enemy: "id", the enemy's UUID).
 */
public final class DustDevilDashAbility implements MobAbility {

    private static final int MAX_TICKS = 70;
    private static final int DASH_TICKS = 40;
    private static final int SKID_TICKS = 10;
    private static final double DASH_SPEED = 0.8;
    private static final int CLIP_SLOW_TICKS = 60;
    private static final Identifier FRICTION_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "dust_devil_dash_friction");
    private static final Identifier DRAG_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "dust_devil_dash_drag");
    private static final Identifier SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "dust_devil_dash_speed");
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "dust_devil_dash_knockback");

    @Override
    public String id() {
        return "dust_devil_dash";
    }

    @Override
    public String displayName() {
        return "Dust Devil Dash";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().onGround()
            && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        // The physics: nothing bleeds this dash's speed.
        MuleAbilitySupport.addModifier(mob, Attributes.FRICTION_MODIFIER,
            new AttributeModifier(FRICTION_ID, -1.0, AttributeModifier.Operation.ADD_VALUE));
        MuleAbilitySupport.addModifier(mob, Attributes.AIR_DRAG_MODIFIER,
            new AttributeModifier(DRAG_ID, -1.0, AttributeModifier.Operation.ADD_VALUE));
        MuleAbilitySupport.addModifier(mob, Attributes.MOVEMENT_SPEED,
            new AttributeModifier(SPEED_ID, 0.30, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        MuleAbilitySupport.addModifier(mob, Attributes.ATTACK_KNOCKBACK,
            new AttributeModifier(KNOCKBACK_ID, 2.0, AttributeModifier.Operation.ADD_VALUE));
        mob.getNavigation().stop();

        LivingEntity target = ctx.target();
        double dx = target != null ? target.getX() - mob.getX() : mob.getLookAngle().x;
        double dz = target != null ? target.getZ() - mob.getZ() : mob.getLookAngle().z;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.001) {
            dx = 1.0;
            dz = 0.0;
            length = 1.0;
        }
        CompoundTag state = ctx.state();
        state.putDouble("dx", dx / length);
        state.putDouble("dz", dz / length);
        state.putInt("dash_end", 0);
        state.put("clipped", new ListTag());

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.MULE_ANGRY,
                SoundSource.HOSTILE, 1.0f, 1.0f);
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
        int dashEnd = state.getIntOr("dash_end", 0);

        if (dashEnd > 0) {
            // The skid: velocity damped, the dash is spent.
            Vec3 current = mob.getDeltaMovement();
            mob.setDeltaMovement(current.x * 0.2, current.y, current.z * 0.2);
            mob.hurtMarked = true;
            return;
        }

        double dx = state.getDoubleOr("dx", 1.0);
        double dz = state.getDoubleOr("dz", 0.0);

        // Wall work: a blocked probe ahead ends the dash.
        AABB ahead = mob.getBoundingBox().move(dx * 0.5, 0.0, dz * 0.5);
        if (ticks >= DASH_TICKS || !level.noCollision(mob, ahead)) {
            state.putInt("dash_end", ticks);
            Vec3 current = mob.getDeltaMovement();
            mob.setDeltaMovement(current.x * 0.2, current.y, current.z * 0.2);
            mob.hurtMarked = true;
            return;
        }

        // The dash: velocity written straight along the
        // stored direction, vertical motion left to gravity.
        double vy = mob.onGround() ? 0.0 : mob.getDeltaMovement().y;
        mob.setDeltaMovement(dx * DASH_SPEED, vy, dz * DASH_SPEED);
        mob.hurtMarked = true;

        if (ticks % 2 == 0) {
            dustColumn(ctx, level);
            clip(ctx, level);
        }
    }

    /** The spinning dust column around the dashing mule: 3 DUST_PILLAR motes at rising heights on a rotating angle. */
    private void dustColumn(AbilityContext ctx, ServerLevel level) {
        Mob mob = ctx.mob();
        double angle = ctx.ticksActive() * 0.6;
        double px = mob.getX() + Math.cos(angle) * 0.7;
        double pz = mob.getZ() + Math.sin(angle) * 0.7;
        var pillar = MuleAbilitySupport.groundDustPillar(level, mob.getX(), mob.getY(), mob.getZ());
        level.sendParticles(pillar, px, mob.getY() + 0.2, pz, 1, 0.05, 0.05, 0.05, 0.02);
        level.sendParticles(pillar, px, mob.getY() + 0.9, pz, 1, 0.05, 0.05, 0.05, 0.02);
        level.sendParticles(pillar, px, mob.getY() + 1.6, pz, 1, 0.05, 0.05, 0.05, 0.02);
    }

    /** The clip pulse: enemies on the dash line are spun aside once each, with a brief slow. */
    private void clip(AbilityContext ctx, ServerLevel level) {
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        double dx = state.getDoubleOr("dx", 1.0);
        double dz = state.getDoubleOr("dz", 0.0);
        ListTag clipped = state.getListOrEmpty("clipped");
        for (LivingEntity enemy : MuleAbilitySupport.enemiesInBox(ctx,
                mob.getX(), mob.getY(), mob.getZ(), 2.0, 1.0, 2.0)) {
            UUID id = enemy.getUUID();
            boolean already = false;
            for (int i = 0; i < clipped.size(); i++) {
                UUID entryId = clipped.getCompoundOrEmpty(i).contains("id")
                    ? clipped.getCompoundOrEmpty(i).read("id", UUIDUtil.CODEC).orElse(null) : null;
                if (id.equals(entryId)) {
                    already = true;
                    break;
                }
            }
            if (already || clipped.size() >= 16) {
                continue;
            }
            // Spin aside: the shove runs along the dash's
            // perpendicular, to the side the enemy stands on.
            double relX = enemy.getX() - mob.getX();
            double relZ = enemy.getZ() - mob.getZ();
            double side = relX * -dz + relZ * dx >= 0.0 ? 1.0 : -1.0;
            Vec3 current = enemy.getDeltaMovement();
            enemy.setDeltaMovement(-dz * 1.1 * side, Math.max(current.y, 0.3), dx * 1.1 * side);
            enemy.hurtMarked = true;
            enemy.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, CLIP_SLOW_TICKS, 0));
            level.sendParticles(
                MuleAbilitySupport.groundDustPillar(level, enemy.getX(), enemy.getY(), enemy.getZ()),
                enemy.getX(), enemy.getY() + 0.5, enemy.getZ(),
                4, 0.3, 0.4, 0.3, 0.05);
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, id);
            clipped.add(entry);
        }
        state.put("clipped", clipped);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        int dashEnd = ctx.state().getIntOr("dash_end", 0);
        return ctx.ticksActive() >= MAX_TICKS
            || (dashEnd > 0 && ctx.ticksActive() >= dashEnd + SKID_TICKS);
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        MuleAbilitySupport.removeModifier(mob, Attributes.FRICTION_MODIFIER, FRICTION_ID);
        MuleAbilitySupport.removeModifier(mob, Attributes.AIR_DRAG_MODIFIER, DRAG_ID);
        MuleAbilitySupport.removeModifier(mob, Attributes.MOVEMENT_SPEED, SPEED_ID);
        MuleAbilitySupport.removeModifier(mob, Attributes.ATTACK_KNOCKBACK, KNOCKBACK_ID);
        // A terminated dash never leaves the mule sliding.
        Vec3 current = mob.getDeltaMovement();
        mob.setDeltaMovement(current.x * 0.2, current.y, current.z * 0.2);
        mob.hurtMarked = true;
    }
}
