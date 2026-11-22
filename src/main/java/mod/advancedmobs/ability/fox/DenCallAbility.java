package mod.advancedmobs.ability.fox;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.fox.Fox;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Den Call (Fox ability 5 of 5, summon).
 *
 * WHAT IT DOES: the fox yips for her litter and the den
 * answers: three baby foxes pour in, each already
 * gripping a little weapon in its mouth, and swarm the
 * mother's target as a pack. While any of her pups live,
 * the mother fights bolder.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the fox's current target within 16 blocks
 *   (distance squared 256).
 * - The call (start()): FOX_AMBIENT at volume 1.5, pitch
 *   1.4 (the yip), and 6 HEART motes over the mother (the
 *   design's heart particle: the litter is loved). Three
 *   pups are summoned in a ring at radius 1.5 around the
 *   mother (angles 90, 210, and 330 degrees), each
 *   landing height from one bounded surface scan. Each
 *   pup is a baby fox (age -24000) spawned with
 *   MOB_SUMMONED and finalizeSpawn, and each is fitted
 *   with its weapon through its equipment before it is
 *   added to the world: the fox mouth slot is the
 *   MAINHAND equipment slot, and the three pups carry an
 *   IRON_SWORD, a STONE_AXE, and an IRON_AXE in rotation
 *   (the design's "/item replace mouth slot equipment":
 *   little swords and axes). The weapons are real items:
 *   a pup that dies drops its weapon the way a vanilla
 *   fox drops what it carries, and that is intended, the
 *   litter's gear is loot. Each pup is allied with the
 *   mother in both directions in MobWarData and
 *   registered in SummonerTracker, is tracked in the
 *   state tag, and is ordered onto the mother's target.
 *   4 POOF motes burst at each arrival. A pup's own bite
 *   is code-driven (below), so the weapon is carried and
 *   shown, and its vanilla melee bonus is not relied on:
 *   a fox's vanilla AI in this mod's combat does not
 *   swing on its own.
 * - The mother's boldness (tick(), every 20 ticks): while
 *   at least one pup is confirmed alive, the mother gains
 *   STRENGTH I refreshed to 40 ticks (the design's
 *   strength effect on the mother; STRENGTH rides her
 *   real ATTACK_DAMAGE attribute, so her own bites hit
 *   harder while the litter stands). The refresh only
 *   happens while a pup lives: the moment all pups are
 *   gone the effect stops being refreshed and fades on
 *   its own clock, and the ability ends (below).
 * - The swarm (tick(), babysit every 5 ticks): each
 *   living pup is retargeted onto the mother's live
 *   target and navigation-driven at it at speed 1.4;
 *   within 1.5 blocks (distance squared 2.25) it bites
 *   for 1.5 damage through a mob attack source credited
 *   to that pup, at most once per pup per 15 ticks (the
 *   per-pup clock is stored in the state tag by pup list
 *   index). That is the pack targeting of the design,
 *   run in code: the whole litter reads the mother's one
 *   target every babysit, so the pack turns as one.
 * - Pup deaths: the first babysit that finds a pup dead
 *   or gone bursts 4 POOF motes at the mother for that
 *   pup, once per pup (a per-pup flag in the state tag).
 * - Duration: exactly 400 ticks (20 seconds), ending
 *   early when all three pups are confirmed dead or gone
 *   (the babysit sets the "pups_gone" flag).
 * - end(): every surviving pup is dismissed in 4 POOF
 *   motes and discarded, and the ally records are removed
 *   in both directions. The mother's STRENGTH is NOT
 *   stripped: its last refresh rides its own 40 tick
 *   clock out, the way the Cub Guard's guard effects do;
 *   the boldness was earned while the pups stood.
 *   Idempotent.
 *
 * WHAT IT LOOKS LIKE: a yip, a drift of hearts, and
 * three kit-sized foxes tumbling out of the brush with
 * blades gripped in their teeth, swarming the mother's
 * target in a yapping pack while she bites like a fox
 * twice her size. As the pups fall the fight goes out of
 * her; when the last one drops, she is just a fox again
 * within two seconds.
 *
 * PERFORMANCE: start() is 3 spawns, 3 surface scans of
 * at most 12 block reads, and 3 bursts. tick() returns
 * immediately except every 5th tick (babysit: resolves
 * at most 3 UUIDs, at most 3 distance checks, and at
 * most 3 damage calls) and every 20th tick (the boldness
 * refresh: 1 effect apply, and only when the babysit has
 * confirmed a living pup). Particle budget per
 * activation: hearts 6, arrivals 3 times 4 = 12, pup
 * deaths at most 3 times 4 = 12, end at most 3 times 4 =
 * 12, total at most 42 particles.
 *
 * TERRAIN: this ability changes no blocks; the
 * mobGriefing gate does not apply.
 *
 * State tag keys: "pups" (list of compounds, one per
 * pup: "id", its UUID), "bite_0", "bite_1" and "bite_2"
 * (the next tick each pup may bite again, by pup list
 * index), "pup_gone_0", "pup_gone_1" and "pup_gone_2" (1
 * once that pup's death burst has fired), "any_pup_alive"
 * (1 while the last babysit found a living pup; gates
 * the boldness refresh), "pups_gone" (1 once all pups
 * are confirmed dead or gone).
 */
public final class DenCallAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int BABYSIT_INTERVAL = 5;
    private static final int BOLDNESS_INTERVAL = 20;
    private static final int BOLDNESS_EFFECT_TICKS = 40;
    private static final int PUP_COUNT = 3;
    private static final double SPAWN_RADIUS = 1.5;
    private static final double BITE_RANGE_SQR = 2.25; // 1.5 blocks
    private static final double BITE_DAMAGE = 1.5;
    private static final int BITE_COOLDOWN_TICKS = 15;
    private static final Item[] WEAPONS = {Items.IRON_SWORD, Items.STONE_AXE, Items.IRON_AXE};

    @Override
    public String id() {
        return "den_call";
    }

    @Override
    public String displayName() {
        return "Den Call";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            ctx.state().put("pups", new ListTag());
            return;
        }
        Mob caster = ctx.mob();
        LivingEntity target = ctx.target();
        ctx.state().putInt("any_pup_alive", 1);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.FOX_AMBIENT, SoundSource.HOSTILE, 1.5f, 1.4f);
        level.sendParticles(ParticleTypes.HEART, caster.getX(), caster.getY() + 0.6, caster.getZ(),
            6, 0.4, 0.3, 0.4, 0.05);

        for (int i = 0; i < PUP_COUNT; i++) {
            Fox pup = EntityTypes.FOX.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (pup == null) {
                continue;
            }
            double angle = Math.toRadians(90.0 + 120.0 * i);
            double spawnX = caster.getX() + Math.cos(angle) * SPAWN_RADIUS;
            double spawnZ = caster.getZ() + Math.sin(angle) * SPAWN_RADIUS;
            double spawnY = FoxAbilitySupport.findGroundY(level, spawnX, caster.getY() + 1.0, spawnZ);
            pup.snapTo(spawnX, spawnY, spawnZ, ctx.random().nextFloat() * 360.0f, 0.0f);
            pup.setAge(-24000); // A baby: the design's juvenile pup.
            // The mouth weapon, fitted through the pup's equipment (MAINHAND is the mouth).
            pup.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(WEAPONS[i % WEAPONS.length]));
            pup.finalizeSpawn(level, level.getCurrentDifficultyAt(pup.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
            if (!level.addFreshEntity(pup)) {
                continue;
            }
            // Team assignment, both directions, plus the summoner registry.
            ctx.data().addAlly(pup.getUUID());
            MobWarData.get(pup).addAlly(caster.getUUID());
            SummonerTracker.registerSummoned(pup.getUUID(), caster.getUUID());
            if (target != null) {
                pup.setTarget(target);
            }
            level.sendParticles(ParticleTypes.POOF, spawnX, spawnY + 0.4, spawnZ,
                4, 0.3, 0.3, 0.3, 0.05);
            FoxAbilitySupport.trackEntity(ctx.state(), "pups", pup);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks % BABYSIT_INTERVAL == 0) {
            babysit(ctx, ticks);
        }
        if (ticks % BOLDNESS_INTERVAL == 0 && ctx.state().getIntOr("any_pup_alive", 0) == 1) {
            // The mother's boldness: she fights harder while any pup stands.
            ctx.mob().addEffect(new MobEffectInstance(MobEffects.STRENGTH, BOLDNESS_EFFECT_TICKS, 0));
        }
    }

    /** Drives the litter onto the mother's target as one pack, every 5 ticks. */
    private void babysit(AbilityContext ctx, int ticks) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        LivingEntity desired = ctx.target();
        ListTag pups = ctx.state().getListOrEmpty("pups");
        boolean anyAlive = false;
        for (int i = 0; i < pups.size(); i++) {
            if (!(FoxAbilitySupport.resolveTracked(level, pups.getCompoundOrEmpty(i)) instanceof Fox pup)
                || !pup.isAlive()) {
                // A fallen pup gets one dust burst at the mother, once.
                String goneKey = "pup_gone_" + i;
                if (ctx.state().getIntOr(goneKey, 0) == 0) {
                    ctx.state().putInt(goneKey, 1);
                    level.sendParticles(ParticleTypes.POOF, caster.getX(), caster.getY() + 0.4, caster.getZ(),
                        4, 0.3, 0.3, 0.3, 0.05);
                }
                continue;
            }
            anyAlive = true;
            if (desired == null) {
                if (pup.getTarget() != null) {
                    pup.setTarget(null);
                }
                continue;
            }
            // The pack turns as one: every pup reads the mother's one target.
            if (pup.getTarget() != desired) {
                pup.setTarget(desired);
            }
            if (pup.distanceToSqr(desired) > BITE_RANGE_SQR) {
                pup.getNavigation().moveTo(desired, 1.4);
                continue;
            }
            String biteKey = "bite_" + i;
            if (ticks >= ctx.state().getIntOr(biteKey, 0)) {
                ctx.state().putInt(biteKey, ticks + BITE_COOLDOWN_TICKS);
                desired.hurtServer(level, level.damageSources().mobAttack(pup), (float) BITE_DAMAGE);
            }
        }
        ctx.state().putInt("any_pup_alive", anyAlive ? 1 : 0);
        if (!anyAlive && ticks >= BABYSIT_INTERVAL) {
            ctx.state().putInt("pups_gone", 1);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS || ctx.state().getIntOr("pups_gone", 0) == 1;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        // The litter stands down: survivors dismissed, ally records cleaned both sides.
        ListTag pups = ctx.state().getListOrEmpty("pups");
        for (int i = 0; i < pups.size(); i++) {
            if (!(FoxAbilitySupport.resolveTracked(level, pups.getCompoundOrEmpty(i)) instanceof Fox pup)
                || !pup.isAlive()) {
                continue;
            }
            ctx.data().removeAlly(pup.getUUID());
            MobWarData.get(pup).removeAlly(caster.getUUID());
            level.sendParticles(ParticleTypes.POOF, pup.getX(), pup.getY() + 0.4, pup.getZ(),
                4, 0.3, 0.3, 0.3, 0.05);
            pup.discard();
        }
    }
}
