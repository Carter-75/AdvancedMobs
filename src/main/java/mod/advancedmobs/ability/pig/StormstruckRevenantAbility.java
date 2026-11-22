package mod.advancedmobs.ability.pig;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Stormstruck Revenant (Pig ability 3 of 5,
 * transformation).
 *
 * WHAT IT DOES: the storm takes the pig. A lightning bolt
 * crashes down on it, and out of the flash rises a hulking
 * zombified piglin warrior, bigger than life, golden sword
 * in hand, already swinging at whatever the pig was
 * fighting. It is the vanilla rule that lightning turns
 * pigs into zombified piglins, turned into a second phase
 * for the fight: the pig is gone, the revenant is the
 * fight now.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the pig's current target within 16 blocks
 *   (distance squared 256), AND at least one storm
 *   condition: the pig is at or below 40 percent of its
 *   max health, OR the level is thundering
 *   (Level.isThundering). In clear calm weather at full
 *   health the storm never comes.
 * - The transformation happens entirely in start(), in
 *   this order:
 *   1. A real LightningBolt entity is spawned at the
 *      pig's position (the design's /summon lightning_bolt
 *      line). Its vanilla strike effects (a flash, the
 *      thunder crack, possible fire and strike damage
 *      around the point) are vanilla's own. The bolt is
 *      an entity, not a block change, so the terrain rule's
 *      gate does not apply to it.
 *   2. The revenant is spawned at the pig's position with
 *      the pig's facing: a ZombifiedPiglin created with
 *      EntitySpawnReason.MOB_SUMMONED and finalizeSpawn,
 *      then given its warrior sheet as PERMANENT base
 *      values on the new mob (not transient modifiers;
 *      there is no end() reversal because the revenant is
 *      not an effect, it is a new mob): SCALE base value
 *      1.4 (the design's scale line, a hulking frame),
 *      MAX_HEALTH base value 40.0 and health set to the
 *      full 40, ATTACK_DAMAGE base value 8.0. It carries
 *      a golden sword in its main hand (the zombified
 *      piglin's signature weapon, set after finalizeSpawn
 *      so it replaces any random spawn gear).
 *   3. Team assignment, both directions plus the
 *      registry: the pig's data gains the revenant as an
 *      ally, the revenant's data gains the pig, and the
 *      pair is registered in SummonerTracker (which also
 *      tags the revenant umw_summoned for the natural
 *      spawn blocker). The revenant is ordered onto the
 *      pig's current target, so the fight continues
 *      seamlessly.
 *   4. The flash burst: 20 FLAME plus 12 ELECTRIC_SPARK
 *      particles at the transformation point.
 *   5. The pig is discarded. This is deliberate and is
 *      the transformation: the pig does not hide or flee,
 *      it BECOMES the revenant. Discarding in the same
 *      start() call also means the pig is gone before the
 *      spawned bolt's first tick, so vanilla's own
 *      lightning conversion (which would make a plain,
 *      unscaled zombified piglin) never competes with the
 *      controlled warrior above.
 * - Duration: none of its own. Discarding the caster runs
 *   the framework's termination path, which calls end()
 *   exactly once and clears the activation with no
 *   cooldown. isFinished() also returns true from the
 *   first tick as a backstop, in case a future change ever
 *   leaves the pig alive: the ability is one-shot either
 *   way.
 * - end(): deliberately does NOTHING to the revenant. The
 *   revenant is a permanent new combatant, the pig's
 *   second phase, and it stays in the world fighting on
 *   its own war data after the ability is gone (the
 *   Noxious Split and Gelatinous Split precedent: split
 *   and transformed selves are permanent teammates, not
 *   duration summons). The pig's own MobWarData (its
 *   earned points and upgrades) goes with the pig; the
 *   revenant starts its own record with a warrior's base
 *   sheet, which is the price of the transformation and
 *   is documented here so it is not mistaken for a bug.
 * - Failure handling: if the revenant cannot be created
 *   (entity factory returns null), the pig is NOT
 *   discarded and no bolt is spawned: the ability simply
 *   finishes with nothing changed, rather than deleting
 *   the pig for nothing.
 *
 * WHAT IT LOOKS LIKE: a crack of thunder out of a clear
 * fight, a pillar of lightning on the pig, flame and
 * electric sparks, and a seven foot zombified piglin
 * standing in the scorch mark with a golden sword, already
 * turning on the pig's enemy.
 *
 * PERFORMANCE: everything happens once in start(): one
 * bolt spawn, one mob spawn, a handful of attribute base
 * writes, 32 particles. tick() and end() do no world work
 * at all. Particle budget per activation: 32.
 *
 * TERRAIN: the lightning bolt is an entity; any fire it
 * starts is vanilla lightning behavior, not an ability
 * block change. No ability block changes happen, so the
 * mobGriefing gate does not apply. See the class comment
 * of PigAbilitySupport.
 *
 * State tag keys: "revenant" (the revenant's UUID stored
 * as a string for debugging; nothing reads it back, the
 * revenant is autonomous once spawned).
 */
public final class StormstruckRevenantAbility implements MobAbility {

    private static final double LOW_HEALTH_FRACTION = 0.40;
    private static final double REVENANT_SCALE = 1.4;
    private static final double REVENANT_MAX_HEALTH = 40.0;
    private static final double REVENANT_ATTACK_DAMAGE = 8.0;

    @Override
    public String id() {
        return "stormstruck_revenant";
    }

    @Override
    public String displayName() {
        return "Stormstruck Revenant";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null || ctx.mob().distanceToSqr(target) > 256.0) {
            return false;
        }
        Mob caster = ctx.mob();
        boolean lowHealth = caster.getHealth() <= caster.getMaxHealth() * LOW_HEALTH_FRACTION;
        return lowHealth || ctx.level().isThundering();
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();

        // Create the revenant FIRST: if it cannot be made,
        // the pig keeps its life and nothing happens.
        ZombifiedPiglin revenant = EntityTypes.ZOMBIFIED_PIGLIN.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (revenant == null) {
            return;
        }
        revenant.snapTo(caster.getX(), caster.getY(), caster.getZ(), caster.getYRot(), 0.0f);
        revenant.finalizeSpawn(level, level.getCurrentDifficultyAt(revenant.blockPosition()),
            EntitySpawnReason.MOB_SUMMONED, null);

        // The warrior sheet, as permanent base values on
        // the new mob (see the class comment).
        if (revenant.getAttribute(Attributes.SCALE) != null) {
            revenant.getAttribute(Attributes.SCALE).setBaseValue(REVENANT_SCALE);
        }
        if (revenant.getAttribute(Attributes.MAX_HEALTH) != null) {
            revenant.getAttribute(Attributes.MAX_HEALTH).setBaseValue(REVENANT_MAX_HEALTH);
        }
        if (revenant.getAttribute(Attributes.ATTACK_DAMAGE) != null) {
            revenant.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(REVENANT_ATTACK_DAMAGE);
        }
        revenant.setHealth((float) REVENANT_MAX_HEALTH);
        revenant.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.GOLDEN_SWORD));

        if (!level.addFreshEntity(revenant)) {
            return;
        }

        // Team assignment, both directions, plus the
        // summoner registry, and the fight handover.
        ctx.data().addAlly(revenant.getUUID());
        MobWarData.get(revenant).addAlly(caster.getUUID());
        SummonerTracker.registerSummoned(revenant.getUUID(), caster.getUUID());
        LivingEntity target = ctx.target();
        if (target != null) {
            revenant.setTarget(target);
        }
        CompoundTag state = ctx.state();
        state.putString("revenant", revenant.getUUID().toString());

        // The bolt: the storm itself, at the pig's spot.
        LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (bolt != null) {
            bolt.snapTo(caster.getX(), caster.getY(), caster.getZ(), caster.getYRot(), 0.0f);
            level.addFreshEntity(bolt);
        }

        // The flash burst at the transformation point.
        level.sendParticles(ParticleTypes.FLAME,
            caster.getX(), caster.getY() + 1.0, caster.getZ(),
            20, 0.5, 0.8, 0.5, 0.1);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
            caster.getX(), caster.getY() + 1.0, caster.getZ(),
            12, 0.5, 0.8, 0.5, 0.15);

        // The transformation itself: the pig becomes the
        // revenant and does not come back.
        caster.discard();
    }

    @Override
    public void tick(AbilityContext ctx) {
        // One-shot: the work is all in start(). The caster
        // is normally already discarded, so this never runs.
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return true;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Deliberately empty: the revenant is permanent and
        // autonomous. See the class comment.
    }
}
