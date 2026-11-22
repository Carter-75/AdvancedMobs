package mod.advancedmobs.ability.skeleton;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Executioner Arrow (Skeleton ability 1 of 5, attack).
 *
 * WHAT IT DOES: the skeleton draws one enormous arrow. A glowing
 * item display warhead hangs in front of its bow and grows step by
 * step as the draw stretches, shedding ENCHANT glyphs and CRIT
 * sparkles. After a 2 second draw it looses a single real arrow with
 * triple the punch of an ordinary skeleton shot: heavy projectile
 * damage and enough impact speed to throw the victim backward off
 * its feet.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists and is between 4 and 26
 *   blocks away (distance squared 16 to 676).
 * - Duration: the draw lasts 40 ticks (2 seconds). The shot fires
 *   during tick 40; the ability finishes at tick 46.
 * - The warhead (visual only, no hitbox, deals no damage itself):
 *   an item display showing a plain arrow, created through the NBT
 *   spawn path (SkeletonAbilitySupport.createItemDisplay) at full
 *   brightness, context "fixed". Display transformations cannot be
 *   edited after spawn in 26.2 (the setters are private), so growth
 *   is stepped: the display starts at scale 1.0 and is discarded and
 *   recreated one step larger every 5 ticks, 7 steps of +0.45, for
 *   a final scale of 4.15 at the loose. Between recreations the
 *   display is repositioned every tick to the aim point, 1.1 blocks
 *   in front of the skeleton's eyes along its line to the target,
 *   yawed to face the target, so it tracks the skeleton's aim while
 *   the skeleton strafes or turns. If the target dies mid-draw, the
 *   aim falls back to the skeleton's look direction.
 * - The shot (tick 40): one vanilla Arrow owned by the skeleton,
 *   pickup disallowed, crit flag set, base damage 4.0 (an ordinary
 *   skeleton arrow is 2.0; vanilla then scales arrow damage by
 *   impact velocity, so a clean hit at the shot's speed of 3.0
 *   lands in the 12 to 18 range before armor). It is fired from the
 *   warhead's position at the live target's mid-body (60 percent of
 *   its height), or along the skeleton's look direction when no
 *   target is alive. The victim's throw comes from the arrow's
 *   impact knockback in vanilla, which scales with the arrow's
 *   velocity at the moment of the hit: at speed 3.0 the shot flies
 *   nearly twice as fast as an ordinary skeleton arrow (about 1.6),
 *   so it knocks the victim back hard enough to leave its feet.
 * - ATTACK_KNOCKBACK: for the whole activation the skeleton carries
 *   an ATTACK_KNOCKBACK add_value modifier of +1.5 (modifier id
 *   advancedmobs:executioner_arrow_knockback, removed in end()).
 *   That is the design's listed attribute: it makes any melee-range
 *   blow the skeleton lands during the draw hit with real throw,
 *   and the fired arrow's own throw is delivered by its speed.
 * - The draw does NOT suppress the skeleton's ordinary bow goal:
 *   it may loose normal arrows during the 2 seconds. The executioner
 *   shot is the extra, oversized one at tick 40.
 *
 * WHAT IT LOOKS AND SOUNDS LIKE: a bright arrowhead-and-shaft
 * display swelling in front of the bow, 2 ENCHANT glyphs and 1 CRIT
 * sparkle around it every 2 ticks (denser as it grows, since the
 * spread follows the current scale), then a deep ARROW_SHOOT sound
 * (pitch 0.6) and an 8 CRIT burst as the real arrow leaves.
 *
 * PERFORMANCE: per tick the work is one UUID resolve and one
 * snapTo for the display, plus a 3-particle burst every 2nd tick.
 * The display is recreated at most 7 times per activation (bounded
 * spawns, each one NBT build). No entity scans, no registry lookups
 * in the tick path: the arrow item tag and particle types are
 * static. Particle budget per activation: at most 20 bursts times
 * 3 = 60 during the draw, plus 8 at the loose, total at most 68.
 *
 * CLEANUP: end() removes the knockback modifier and discards the
 * warhead display if it still exists (it is already gone after a
 * successful loose). If the caster dies mid-draw, the framework
 * termination path runs this same end(), so the display never
 * outlives the ability or its caster. The fired arrow is a normal
 * projectile and is not tracked.
 *
 * State tag keys: "display" (warhead display UUID), "step" (the
 * current growth step, 0 to 7), "loosed" (whether the shot fired).
 */
public final class ExecutionerArrowAbility implements MobAbility {

    private static final int LOOSE_TICK = 40;
    private static final int END_TICK = 46;
    private static final int GROW_INTERVAL = 5;
    private static final int MAX_STEP = 7;
    private static final float BASE_SCALE = 1.0f;
    private static final float SCALE_PER_STEP = 0.45f;
    private static final double ARROW_BASE_DAMAGE = 4.0;
    private static final float ARROW_SPEED = 3.0f;
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "executioner_arrow_knockback");
    private static final CompoundTag ARROW_ITEM_TAG = arrowItemTag();

    private static CompoundTag arrowItemTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:arrow");
        tag.putInt("count", 1);
        return tag;
    }

    @Override
    public String id() {
        return "executioner_arrow";
    }

    @Override
    public String displayName() {
        return "Executioner Arrow";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null) {
            return false;
        }
        double distanceSqr = ctx.mob().distanceToSqr(target);
        return distanceSqr >= 16.0 && distanceSqr <= 676.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        state.putInt("step", 0);
        state.putBoolean("loosed", false);

        // The design's ATTACK_KNOCKBACK: the draw carries real throw.
        AttributeInstance knockback = mob.getAttribute(Attributes.ATTACK_KNOCKBACK);
        if (knockback != null) {
            knockback.addOrUpdateTransientModifier(
                new AttributeModifier(KNOCKBACK_ID, 1.5, AttributeModifier.Operation.ADD_VALUE));
        }

        spawnWarhead(ctx, 0);
    }

    @Override
    public void tick(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        if (state.getBooleanOr("loosed", false)) {
            return; // The shot is away; nothing left but the finish tick.
        }
        int ticks = ctx.ticksActive();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }

        // Growth: one step larger every 5 ticks, by recreation (see
        // the class comment for why growth is stepped, not tweened).
        int step = state.getIntOr("step", 0);
        if (ticks % GROW_INTERVAL == 0 && step < MAX_STEP && ticks < LOOSE_TICK) {
            step++;
            state.putInt("step", step);
            spawnWarhead(ctx, step);
        }

        // Track the aim every tick, and shimmer while the draw holds.
        Entity display = resolveDisplay(ctx);
        if (display != null) {
            Vec3 aim = aimPoint(ctx);
            Vec3 dir = aimDirection(ctx);
            display.snapTo(aim.x, aim.y, aim.z, yawOf(dir), 0.0f);
            if (ticks % 2 == 0) {
                double spread = 0.25 + 0.12 * step;
                level.sendParticles(ParticleTypes.ENCHANT, aim.x, aim.y, aim.z, 2, spread, spread, spread, 0.4);
                level.sendParticles(ParticleTypes.CRIT, aim.x, aim.y, aim.z, 1, spread, spread, spread, 0.1);
            }
        }

        if (ticks >= LOOSE_TICK) {
            loose(ctx);
            state.putBoolean("loosed", true);
        }
    }

    /** The point the warhead hangs at: 1.1 blocks ahead of the skeleton's eyes, toward the target. */
    private Vec3 aimPoint(AbilityContext ctx) {
        Mob mob = ctx.mob();
        Vec3 eye = mob.getEyePosition();
        return eye.add(aimDirection(ctx).scale(1.1));
    }

    /** Unit direction from the skeleton's eyes to its target's mid-body, or its look direction. */
    private Vec3 aimDirection(AbilityContext ctx) {
        Mob mob = ctx.mob();
        LivingEntity target = ctx.target();
        if (target != null) {
            Vec3 to = new Vec3(target.getX() - mob.getX(),
                               (target.getY() + target.getBbHeight() * 0.6) - mob.getEyeY(),
                               target.getZ() - mob.getZ());
            if (to.lengthSqr() > 1.0e-6) {
                return to.normalize();
            }
        }
        return mob.getLookAngle();
    }

    /** Minecraft yaw (degrees) facing along the given direction. */
    private static float yawOf(Vec3 dir) {
        return (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
    }

    /** Discards any current warhead and spawns a fresh one at the given growth step's scale. */
    private void spawnWarhead(AbilityContext ctx, int step) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        discardDisplay(ctx);
        float scale = BASE_SCALE + SCALE_PER_STEP * step;
        Entity display = SkeletonAbilitySupport.createItemDisplay(level, ARROW_ITEM_TAG, scale, "fixed");
        if (display == null) {
            return;
        }
        Vec3 aim = aimPoint(ctx);
        display.snapTo(aim.x, aim.y, aim.z, yawOf(aimDirection(ctx)), 0.0f);
        level.addFreshEntity(display);
        ctx.state().store("display", UUIDUtil.CODEC, display.getUUID());
    }

    /** The loose: fire the one enormous arrow, discard the warhead, play the shot. */
    private void loose(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        Vec3 from = aimPoint(ctx);
        Vec3 dir = aimDirection(ctx);

        Arrow arrow = new Arrow(level, mob, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        arrow.setBaseDamage(ARROW_BASE_DAMAGE);
        arrow.setCritArrow(true);
        arrow.snapTo(from.x, from.y, from.z, yawOf(dir), 0.0f);
        arrow.shoot(dir.x, dir.y, dir.z, ARROW_SPEED, 0.0f);
        level.addFreshEntity(arrow);

        discardDisplay(ctx);
        level.sendParticles(ParticleTypes.CRIT, from.x, from.y, from.z, 8, 0.3, 0.3, 0.3, 0.25);
        level.playSound(null, from.x, from.y, from.z, SoundEvents.ARROW_SHOOT, SoundSource.HOSTILE, 1.2f, 0.6f);
    }

    /** Resolves the warhead display from the state tag, or null when there is none. */
    private Entity resolveDisplay(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null || !ctx.state().contains("display")) {
            return null;
        }
        UUID id = ctx.state().read("display", UUIDUtil.CODEC).orElse(null);
        return id != null ? level.getEntity(id) : null;
    }

    /** Discards the warhead display if it still exists. Idempotent. */
    private void discardDisplay(AbilityContext ctx) {
        Entity display = resolveDisplay(ctx);
        if (display != null) {
            display.discard();
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= END_TICK;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        AttributeInstance knockback = mob.getAttribute(Attributes.ATTACK_KNOCKBACK);
        if (knockback != null) {
            knockback.removeModifier(KNOCKBACK_ID);
        }
        discardDisplay(ctx);
    }
}
