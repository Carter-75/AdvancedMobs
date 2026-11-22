package mod.advancedmobs.ability.panda;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.panda.Panda;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Bamboo Feast (Panda ability 2 of 5, defense and heal).
 *
 * WHAT IT DOES: the panda sits down in the middle of the
 * fight, pulls out a bamboo stalk held in its paws, and
 * munches it with happy sparkles and green crumbs. While it
 * eats it rapidly regenerates and stays saturated, and
 * enemies tend to leave it alone for the first bite before
 * realizing their mistake.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the panda's current target within 16 blocks (distance
 *   squared 256).
 * - Duration: exactly 100 ticks (5 seconds) from start().
 * - The seat (start()): navigation is stopped every tick
 *   for the whole feast (never NoAi). When the caster is a
 *   Panda, its vanilla sit and eat poses are set (sit(true),
 *   eat(true)): the seated munching look is the panda's own
 *   render state. Both are cleared in end().
 * - The stalk (start()): one item display showing a bamboo
 *   item (scale 1.0, thirdperson_righthand context, full
 *   brightness, teleport duration 2) is created through
 *   PandaAbilitySupport and added to the level; its UUID is
 *   stored in the state tag ("display"). Every tick it is
 *   snapped to the panda's paws: the panda's position plus
 *   its horizontal look direction times 0.8, at feet plus
 *   1.0. A display has no hitbox and changes no blocks; it
 *   is discarded in end(). (Code-snapped rather than a true
 *   passenger, per the Obsidian Crust precedent: a display
 *   passenger's offset does not inherit vehicle facing, so
 *   snapping is what keeps the stalk in the paws as the
 *   panda turns.)
 * - The meal (start(), removed in end()): REGENERATION at
 *   amplifier 1 (Regeneration II) and SATURATION at
 *   amplifier 0, both for 120 ticks (they would outlive the
 *   feast by a second if left alone, so end() removes them
 *   exactly: the feast's healing is the ability's own).
 *   Regeneration II heals 1 health every 25 ticks, so a
 *   full feast restores about 4 health plus whatever the
 *   panda's upgrades add through other systems.
 * - The first bite (ticks 1 to 40, a pass every 10 ticks):
 *   one bounded query at radius 8.0; every enemy Mob whose
 *   current target is the panda has its target cleared
 *   (setTarget(null)). Enemies tend to leave the panda
 *   alone while the first bites land, then their own AI
 *   re-acquires it: the design's "before realizing their
 *   mistake". Players are never retargeted (their target is
 *   their own business), and after tick 40 no clearing
 *   happens at all.
 * - end(): the display is discarded, the sit and eat poses
 *   are cleared, and both meal effects are removed.
 *
 * WHAT IT LOOKS LIKE: a panda plopped down on its haunches
 * mid fight, a fat bamboo stalk in its paws, green
 * HAPPY_VILLAGER sparkles and CRIT crumbs puffing with
 * every bite, the vanilla PANDA_EAT munching sound every
 * second, while the things fighting it wander off confused
 * for the first two seconds.
 *
 * PERFORMANCE: per tick one navigation stop, one UUID
 * resolve, and one display snap. The sparkle emission runs
 * every 4 ticks, the eat sound every 20 ticks, the
 * leave-alone pass is one bounded query every 10 ticks and
 * only for the first 40 ticks. Particle budget per
 * activation: 25 emissions times 3 = 75 HAPPY_VILLAGER
 * plus 25 times 2 = 50 CRIT, total at most 125 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply. The bamboo is a display entity, not a placed
 * block or a real item.
 *
 * State tag keys: "display" (the bamboo display's UUID).
 */
public final class BambooFeastAbility implements MobAbility {

    private static final int DURATION_TICKS = 100;
    private static final int EFFECT_TICKS = 120;
    private static final int LEAVE_ALONE_TICKS = 40;
    private static final double LEAVE_ALONE_RADIUS = 8.0;

    @Override
    public String id() {
        return "bamboo_feast";
    }

    @Override
    public String displayName() {
        return "Bamboo Feast";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.getNavigation().stop();
        if (caster instanceof Panda panda) {
            panda.sit(true);
            panda.eat(true);
        }
        caster.addEffect(new MobEffectInstance(MobEffects.REGENERATION, EFFECT_TICKS, 1));
        caster.addEffect(new MobEffectInstance(MobEffects.SATURATION, EFFECT_TICKS, 0));

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Entity display = PandaAbilitySupport.createBambooDisplay(level, 1.0f);
        if (display != null) {
            snapDisplay(caster, display);
            if (level.addFreshEntity(display)) {
                ctx.state().store("display", UUIDUtil.CODEC, display.getUUID());
            }
        }
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.PANDA_EAT, SoundSource.HOSTILE, 1.0f, 1.0f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.getNavigation().stop();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();

        // The stalk stays in the paws.
        Entity display = resolveDisplay(ctx, level);
        if (display != null) {
            snapDisplay(caster, display);
            if (ticks % 4 == 0) {
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                    display.getX(), display.getY(), display.getZ(),
                    3, 0.15, 0.15, 0.15, 0.02);
                level.sendParticles(ParticleTypes.CRIT,
                    display.getX(), display.getY(), display.getZ(),
                    2, 0.15, 0.1, 0.15, 0.05);
            }
        }
        if (ticks % 20 == 0) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.PANDA_EAT, SoundSource.HOSTILE, 1.0f, 1.0f);
        }

        // The first bite: enemies leave the eating panda alone.
        if (ticks <= LEAVE_ALONE_TICKS && ticks % 10 == 0) {
            List<LivingEntity> nearby = PandaAbilitySupport.enemiesAroundSelf(
                ctx, LEAVE_ALONE_RADIUS, 3.0, 3.0);
            for (LivingEntity enemy : nearby) {
                if (enemy instanceof Mob enemyMob && enemyMob.getTarget() == caster) {
                    enemyMob.setTarget(null);
                }
            }
        }
    }

    /** Snaps the bamboo display to the panda's paws: ahead of the body, at paw height. */
    private static void snapDisplay(Mob caster, Entity display) {
        Vec3 look = caster.getLookAngle();
        double length = Math.sqrt(look.x * look.x + look.z * look.z);
        double aheadX = length < 0.001 ? 0.0 : look.x / length;
        double aheadZ = length < 0.001 ? 1.0 : look.z / length;
        display.snapTo(caster.getX() + aheadX * 0.8, caster.getY() + 1.0, caster.getZ() + aheadZ * 0.8,
            caster.getYRot(), 0.0f);
    }

    /** Resolves the tracked bamboo display from the state tag, or null when gone. */
    private static Entity resolveDisplay(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        UUID id = state.contains("display") ? state.read("display", UUIDUtil.CODEC).orElse(null) : null;
        return id == null ? null : level.getEntity(id);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.removeEffect(MobEffects.REGENERATION);
        caster.removeEffect(MobEffects.SATURATION);
        if (caster instanceof Panda panda) {
            panda.sit(false);
            panda.eat(false);
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Entity display = resolveDisplay(ctx, level);
        if (display != null) {
            display.discard();
        }
    }
}
