package mod.advancedmobs.ability.piglin;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Barter Barrage (Piglin ability 2 of 5, attack).
 *
 * WHAT IT DOES: the piglin upends its whole trading stock as
 * ammunition and hurls whatever the barter tables gave it at
 * its target, one throw every 6 ticks for 9 seconds. Every
 * throw is rolled fresh on the barrage table below, so no two
 * barrages match: fire charges, spectral arrows, snowballs,
 * splash potions, junk gravel, and the rare ender pearl that
 * teleports the piglin itself across the fight for a
 * point-blank follow-up. Enemies never know whether the next
 * throw is a joke or a death sentence.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   piglin's current target within 20 blocks (distance squared
 *   400).
 * - Duration: exactly 180 ticks (9 seconds). Throws land on
 *   ticks 6, 12, ..., 180: exactly 30 throws.
 * - Aim: every throw re-reads the framework target and stores
 *   its center (feet plus half its height) in the state tag;
 *   the throw aims from the piglin's eye at that stored point.
 *   If the target dies mid-barrage, the remaining throws aim
 *   at the last stored point, so the barrage finishes into
 *   empty ground rather than freezing.
 * - The roll (random 0 to 99, rolled per throw):
 *     0 to 21 (22 percent) FIRE CHARGE: a real SmallFireball,
 *       the entity a thrown fire charge becomes, launched
 *       straight at the target center at speed 1.6 with its
 *       self-propulsion zeroed (the Magma Mortar pattern: the
 *       constructor's vector is the velocity, accelerationPower
 *       set to 0 so vanilla drag is the only curve). Vanilla
 *       behavior does the rest: 5.0 damage and 5 seconds of
 *       ignite on a direct hit, and on a block hit it places
 *       one fire block only when mobGriefing is on, per its
 *       own rule check, so the terrain rule is honored by the
 *       projectile itself.
 *     22 to 39 (18 percent) SPECTRAL ARROW: a real Arrow
 *       carrying a spectral arrow, base damage 2.0, speed 2.4,
 *       inaccuracy 2.0, pickup disallowed. A hit glows the
 *       victim through walls, the barter table's own arrow.
 *     40 to 57 (18 percent) SNOWBALL: a real Snowball, speed
 *       1.7 with a slight upward aim bias against its gravity,
 *       inaccuracy 2.0. Vanilla snowballs deal 0 damage to
 *       almost everything: the pure joke roll, good only for
 *       knockback and humiliation.
 *     58 to 73 (16 percent) SPLASH POTION OF FIRE RESISTANCE:
 *       a real ThrownSplashPotion, speed 1.3, inaccuracy 2.0.
 *       The barter table's own splash: thrown at an enemy it
 *       mostly does nothing (or worse, fireproofs them), and
 *       if the piglin's own side stands in the splash it
 *       genuinely helps them. A joke with a silver lining.
 *     74 to 83 (10 percent) SPLASH POTION OF HARMING: a real
 *       ThrownSplashPotion carrying HARMING (instant damage I,
 *       6 magic damage at the center of the splash, less at
 *       the edge), speed 1.3, inaccuracy 2.0. The "and worse"
 *       roll: the death sentence.
 *     84 to 91 (8 percent) ENDER PEARL: no projectile. The
 *       piglin itself teleports to a point 2.0 blocks short of
 *       the target along the line between them, at the target's
 *       feet height, if that spot (or the spot 1 block up or
 *       1 block down) is collision-free for the piglin's body;
 *       12 PORTAL particles at each end and an ENDERMAN_TELEPORT
 *       sound. If no spot is free, the pearl fizzles into 8
 *       PORTAL particles at the piglin and the throw is spent.
 *       The follow-up is the piglin's own melee from its new
 *       position, and the barrage's remaining throws aim from
 *       there, because every throw re-reads positions live.
 *     92 to 99 (8 percent) JUNK GRAVEL: a plain ItemEntity of
 *       gravel tossed from the hand with the aim direction at
 *       0.85 speed plus 0.28 up, pickup delay 20 ticks. It
 *       deals no damage and lands as ordinary dropped loot:
 *       the piglin literally threw its pocket gravel.
 * - Every throw also puffs 3 ITEM particles of the thrown
 *   stack at the hand (the design's streaking goods), using
 *   the stack-faithful ItemStackTemplate form of the 26.2
 *   ItemParticleOption.
 * - end() cleans up nothing: thrown projectiles and the tossed
 *   gravel belong to the world once they leave the hand, and
 *   the pearl teleport is instant. Stated plainly so nobody
 *   goes looking for a despawn that does not exist.
 *
 * WHAT IT LOOKS LIKE: a shrieking piglin (PIGLIN_ANGRY at the
 * start) emptying its satchel in a streak of ITEM particles,
 * fire charges and spectral arrows snapping across the fight
 * between snowballs and potion bottles, and every so often the
 * piglin simply vanishing in PORTAL motes and reappearing in
 * its target's face.
 *
 * PERFORMANCE: tick() returns immediately except every 6th
 * tick, which builds exactly one projectile (or one teleport
 * check of at most 3 collision tests) and one 3-particle puff.
 * No entity queries anywhere in the ability; the aim point is
 * three doubles in the state tag. Particle budget per
 * activation: 30 throws times 3 = 90 ITEM motes, plus at most
 * 32 per pearl roll (12 out, 12 in, or 8 for a fizzle), stated
 * as at most 90 plus 32 per pearl.
 *
 * State tag keys: "tx", "ty", "tz" (the last known target
 * center the barrage is aiming at), "tfy" (the target's feet
 * height at that same moment, the pearl landing height).
 */
public final class BarterBarrageAbility implements MobAbility {

    static final int DURATION_TICKS = 180;
    private static final int THROW_INTERVAL = 6;

    @Override
    public String id() {
        return "barter_barrage";
    }

    @Override
    public String displayName() {
        return "Barter Barrage";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 400.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target != null) {
            storeAim(ctx, target);
        }
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            Mob mob = ctx.mob();
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.PIGLIN_ANGRY,
                SoundSource.HOSTILE, 1.0f, 1.0f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % THROW_INTERVAL != 0) {
            return; // Cheap early-out: one throw every 6 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        LivingEntity target = ctx.target();
        if (target != null) {
            storeAim(ctx, target);
        }
        Vec3 aim = new Vec3(ctx.state().getDoubleOr("tx", mob.getX()),
                            ctx.state().getDoubleOr("ty", mob.getEyeY()),
                            ctx.state().getDoubleOr("tz", mob.getZ()));
        Vec3 eye = new Vec3(mob.getX(), mob.getEyeY(), mob.getZ());
        Vec3 dir = aim.subtract(eye);
        if (dir.lengthSqr() < 0.01) {
            dir = mob.getLookAngle();
        } else {
            dir = dir.normalize();
        }
        Vec3 hand = eye.add(dir.scale(0.4)).add(0.0, -0.2, 0.0);

        int roll = ctx.random().nextInt(100);
        if (roll < 22) {
            throwFireCharge(ctx, level, hand, dir);
        } else if (roll < 40) {
            throwSpectralArrow(ctx, level, hand, dir);
        } else if (roll < 58) {
            throwSnowball(ctx, level, hand, dir);
        } else if (roll < 74) {
            throwSplashPotion(ctx, level, hand, dir, splashStack(false));
        } else if (roll < 84) {
            throwSplashPotion(ctx, level, hand, dir, splashStack(true));
        } else if (roll < 92) {
            throwEnderPearl(ctx, level, aim, dir);
        } else {
            throwJunk(ctx, level, hand, dir);
        }
    }

    /** Records the target's center as the barrage's aim point. */
    private void storeAim(AbilityContext ctx, LivingEntity target) {
        ctx.state().putDouble("tx", target.getX());
        ctx.state().putDouble("ty", target.getY() + target.getBbHeight() * 0.5);
        ctx.state().putDouble("tz", target.getZ());
        ctx.state().putDouble("tfy", target.getY());
    }

    /** The hand puff: 3 ITEM particles of the thrown stack (see the class comment). */
    private void handPuff(ServerLevel level, Vec3 hand, ItemStack stack) {
        level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, ItemStackTemplate.fromStack(stack)),
            hand.x, hand.y, hand.z, 3, 0.15, 0.15, 0.15, 0.12);
    }

    private void throwFireCharge(AbilityContext ctx, ServerLevel level, Vec3 hand, Vec3 dir) {
        Mob mob = ctx.mob();
        SmallFireball shell = new SmallFireball(level, mob, dir.scale(1.6));
        shell.snapTo(hand.x, hand.y, hand.z, 0.0f, 0.0f);
        // No self-propulsion: straight flight on the launch
        // velocity, drag is the only curve (Magma Mortar pattern).
        shell.accelerationPower = 0.0;
        shell.setDeltaMovement(dir.scale(1.6));
        level.addFreshEntity(shell);
        handPuff(level, hand, new ItemStack(Items.FIRE_CHARGE));
        level.playSound(null, hand.x, hand.y, hand.z, SoundEvents.FIRECHARGE_USE,
            SoundSource.HOSTILE, 0.8f, 1.1f);
    }

    private void throwSpectralArrow(AbilityContext ctx, ServerLevel level, Vec3 hand, Vec3 dir) {
        Mob mob = ctx.mob();
        Arrow arrow = new Arrow(level, mob, new ItemStack(Items.SPECTRAL_ARROW), new ItemStack(Items.BOW));
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        arrow.setBaseDamage(2.0);
        arrow.snapTo(hand.x, hand.y, hand.z, yawOf(dir), pitchOf(dir));
        arrow.shoot(dir.x, dir.y, dir.z, 2.4f, 2.0f);
        level.addFreshEntity(arrow);
        handPuff(level, hand, new ItemStack(Items.SPECTRAL_ARROW));
        level.playSound(null, hand.x, hand.y, hand.z, SoundEvents.ARROW_SHOOT,
            SoundSource.HOSTILE, 0.7f, 1.1f);
    }

    private void throwSnowball(AbilityContext ctx, ServerLevel level, Vec3 hand, Vec3 dir) {
        Mob mob = ctx.mob();
        Snowball snowball = new Snowball(level, mob, new ItemStack(Items.SNOWBALL));
        snowball.setPos(hand.x, hand.y, hand.z);
        snowball.shoot(dir.x, dir.y + 0.12, dir.z, 1.7f, 2.0f);
        level.addFreshEntity(snowball);
        handPuff(level, hand, new ItemStack(Items.SNOWBALL));
    }

    /** Builds a splash potion stack: harming when deadly, fire resistance when not. */
    private ItemStack splashStack(boolean deadly) {
        ItemStack stack = new ItemStack(Items.SPLASH_POTION);
        stack.set(DataComponents.POTION_CONTENTS,
            new PotionContents(deadly ? Potions.HARMING : Potions.FIRE_RESISTANCE));
        return stack;
    }

    private void throwSplashPotion(AbilityContext ctx, ServerLevel level, Vec3 hand, Vec3 dir,
                                   ItemStack stack) {
        Mob mob = ctx.mob();
        ThrownSplashPotion potion = new ThrownSplashPotion(level, mob, stack);
        potion.setPos(hand.x, hand.y, hand.z);
        potion.shoot(dir.x, dir.y + 0.15, dir.z, 1.3f, 2.0f);
        level.addFreshEntity(potion);
        handPuff(level, hand, stack);
    }

    private void throwEnderPearl(AbilityContext ctx, ServerLevel level, Vec3 aim, Vec3 dir) {
        Mob mob = ctx.mob();
        handPuff(level, new Vec3(mob.getX(), mob.getEyeY(), mob.getZ()), new ItemStack(Items.ENDER_PEARL));
        // Land 2.0 blocks short of the target along the aim line,
        // at the target's feet height, first spot that fits.
        double destX = aim.x - dir.x * 2.0;
        double destZ = aim.z - dir.z * 2.0;
        double feetY = ctx.state().getDoubleOr("tfy", aim.y);
        double[] heights = {feetY, feetY + 1.0, feetY - 1.0};
        for (double destY : heights) {
            AABB box = mob.getBoundingBox().move(destX - mob.getX(), destY - mob.getY(), destZ - mob.getZ());
            if (level.noCollision(mob, box)) {
                level.sendParticles(ParticleTypes.PORTAL, mob.getX(), mob.getY() + 1.0, mob.getZ(),
                    12, 0.4, 0.6, 0.4, 0.3);
                mob.teleportTo(destX, destY, destZ);
                level.sendParticles(ParticleTypes.PORTAL, destX, destY + 1.0, destZ,
                    12, 0.4, 0.6, 0.4, 0.3);
                level.playSound(null, destX, destY, destZ, SoundEvents.ENDERMAN_TELEPORT,
                    SoundSource.HOSTILE, 1.0f, 1.0f);
                return;
            }
        }
        // No room at the target's side: the pearl fizzles.
        level.sendParticles(ParticleTypes.PORTAL, mob.getX(), mob.getY() + 1.0, mob.getZ(),
            8, 0.4, 0.6, 0.4, 0.3);
    }

    private void throwJunk(AbilityContext ctx, ServerLevel level, Vec3 hand, Vec3 dir) {
        ItemStack stack = new ItemStack(Items.GRAVEL);
        ItemEntity junk = new ItemEntity(level, hand.x, hand.y, hand.z, stack);
        junk.setDeltaMovement(dir.scale(0.85).add(0.0, 0.28, 0.0));
        junk.setPickUpDelay(20);
        level.addFreshEntity(junk);
        handPuff(level, hand, stack);
    }

    /** Yaw in degrees for a direction, for cosmetic projectile orientation. */
    private static float yawOf(Vec3 dir) {
        return (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
    }

    /** Pitch in degrees for a direction, for cosmetic projectile orientation. */
    private static float pitchOf(Vec3 dir) {
        double horizontal = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        return (float) Math.toDegrees(Math.atan2(-dir.y, horizontal));
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to clean up: every throw belongs to the world
        // once it leaves the hand (see the class comment).
    }
}
