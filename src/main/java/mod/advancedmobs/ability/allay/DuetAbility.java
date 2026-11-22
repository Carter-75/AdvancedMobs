package mod.advancedmobs.ability.allay;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.UUID;

/**
 * Duet (Allay ability 3 of 5, summon).
 *
 * WHAT IT DOES: the allay splits its song into harmony and
 * a second allay fades in beside it in a swirl of enchant
 * particles, carrying a matching note block prop shown as an
 * item display overhead. The twins mirror each other's
 * movements around the fight, so the enemy is pecked from
 * twice as many angles, and every so often the pair swap the
 * enemy's attention between them mid-fight.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the allay's current target within 16 blocks (distance
 *   squared 256).
 * - The twin (start()): 12 ENCHANT particles swirl at the
 *   caster and an ALLAY_ITEM_GIVEN sound plays (volume 1.0,
 *   pitch 1.2), then one allay is summoned 1.0 block to the
 *   caster's side and 0.5 up, created with
 *   EntitySpawnReason.MOB_SUMMONED and finalizeSpawn like
 *   every other summon in this mod. It is a plain vanilla
 *   allay (20 health, ATTACK_DAMAGE 2.0). Team assignment,
 *   both directions plus the registry: the caster's data
 *   gains the twin as an ally, the twin's data gains the
 *   caster, and the pair is registered in SummonerTracker
 *   (which also tags the twin umw_summoned for the natural
 *   spawn blocker). Its prop, an item display of a note
 *   block at scale 0.8, is snapped every tick to the point
 *   above the twin (feet plus bounding box height plus 0.6),
 *   gliding on its 2-tick teleport duration: the matching
 *   carried prop of the design.
 * - Duration: 400 ticks (20 seconds), hard finish.
 * - The mirror (every tick): the twin steers by velocity
 *   write toward the caster's position reflected through
 *   the target's live position (point reflection: twice the
 *   target minus the caster, at the caster's height), at a
 *   capped speed of 0.5 blocks per tick, and its prop is
 *   snapped overhead. With no live target the twin steers to
 *   a hover 2 blocks beside the caster instead. The caster
 *   itself flies its own vanilla goals; only the twin is
 *   steered.
 * - Babysitting (every 5 ticks): the twin is re-ordered onto
 *   the caster's current target when it is tracking anything
 *   else, keeping the pair on the same fight.
 * - The pecks (every 20 ticks): allays have no vanilla
 *   attack goal, so the doubled angles are code strikes:
 *   each twin within 2.8 blocks of the target deals the
 *   target damage equal to its own live ATTACK_DAMAGE
 *   attribute value (2.0 for a plain allay) through
 *   mobAttack(that twin). At most 2 strikes per pass, one
 *   per twin.
 * - The attention swap (every 40 ticks): one bounded query
 *   at radius 12 around the caster; every enemy Mob (never a
 *   player) currently targeting the caster is re-ordered
 *   onto the twin, and every one targeting the twin is
 *   re-ordered onto the caster: the design's split attention,
 *   traded back and forth.
 * - The harmony trail (every 4 ticks): 1 NOTE particle at
 *   the twin.
 * - end(): the twin is discarded in 6 POOF particles, its
 *   prop display is discarded, and the alliance records are
 *   removed on both sides. If the caster dies first, the
 *   framework termination path runs this same end(), so the
 *   twin never outlives its singer. SummonerTracker records
 *   are deliberately not unregistered, matching the other
 *   summons in this mod: that registry cleans up on the
 *   twin's own removal.
 *
 * WHAT IT LOOKS LIKE: a swirl of enchant motes, a second
 * allay shimmering in with a little note block floating over
 * its head, and the pair wheeling around the victim in
 * mirror image, trading its attention and pecking from both
 * sides.
 *
 * PERFORMANCE: start() does 1 spawn and 1 display creation,
 * once. tick() does one steering write and one snap per
 * tick, one UUID resolve per tick for the twin and one for
 * the prop; the babysit pass runs every 5 ticks, the peck
 * pass is two distance checks every 20 ticks, and the swap
 * is one bounded query every 40 ticks. Particle budget per
 * activation: 12 at start, trail 100, end() puff 6, total
 * at most 118 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "twin" (the summoned allay's UUID),
 * "prop" (its item display's UUID).
 */
public final class DuetAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int BABYSIT_INTERVAL = 5;
    private static final int PECK_INTERVAL = 20;
    private static final int SWAP_INTERVAL = 40;
    private static final int TRAIL_INTERVAL = 4;
    private static final double MIRROR_SPEED = 0.5;
    private static final double PECK_RANGE = 2.8;
    private static final double SWAP_RADIUS = 12.0;

    @Override
    public String id() {
        return "duet";
    }

    @Override
    public String displayName() {
        return "Duet";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();
        if (level == null) {
            return;
        }

        // The harmony fades in: enchant swirl and the allay's giving voice.
        level.sendParticles(ParticleTypes.ENCHANT, caster.getX(), caster.getY() + 0.7, caster.getZ(),
            12, 0.5, 0.5, 0.5, 0.4);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.ALLAY_ITEM_GIVEN, SoundSource.HOSTILE, 1.0f, 1.2f);

        Allay twin = EntityTypes.ALLAY.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (twin == null) {
            return;
        }
        twin.snapTo(caster.getX() + 1.0, caster.getY() + 0.5, caster.getZ(),
            ctx.random().nextFloat() * 360.0f, 0.0f);
        twin.finalizeSpawn(level, level.getCurrentDifficultyAt(twin.blockPosition()),
            EntitySpawnReason.MOB_SUMMONED, null);
        if (!level.addFreshEntity(twin)) {
            return;
        }
        // Team assignment, both directions, plus the summoner registry.
        ctx.data().addAlly(twin.getUUID());
        MobWarData.get(twin).addAlly(caster.getUUID());
        SummonerTracker.registerSummoned(twin.getUUID(), caster.getUUID());
        LivingEntity target = ctx.target();
        if (target != null) {
            twin.setTarget(target);
        }
        ctx.state().store("twin", UUIDUtil.CODEC, twin.getUUID());

        // The twin's matching carried prop: a note block item display.
        Entity prop = AllayAbilitySupport.createItemDisplay(level, Items.NOTE_BLOCK, 0.8f);
        if (prop != null) {
            prop.snapTo(twin.getX(), twin.getY() + twin.getBbHeight() + 0.6, twin.getZ());
            level.addFreshEntity(prop);
            ctx.state().store("prop", UUIDUtil.CODEC, prop.getUUID());
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        Mob caster = ctx.mob();
        Entity found = AllayAbilitySupport.resolveStored(level, state, "twin");
        if (!(found instanceof Allay twin) || !twin.isAlive()) {
            return; // The twin is gone; the remaining ticks are silent.
        }
        int ticks = ctx.ticksActive();
        LivingEntity target = ctx.target();

        // The mirror: reflect the caster through the target.
        if (target != null) {
            AllayAbilitySupport.steerEntity(twin,
                2.0 * target.getX() - caster.getX(),
                caster.getY(),
                2.0 * target.getZ() - caster.getZ(),
                MIRROR_SPEED);
        } else {
            AllayAbilitySupport.steerEntity(twin,
                caster.getX() + 2.0, caster.getY() + 0.5, caster.getZ(), MIRROR_SPEED);
        }
        Entity prop = AllayAbilitySupport.resolveStored(level, state, "prop");
        if (prop != null) {
            prop.snapTo(twin.getX(), twin.getY() + twin.getBbHeight() + 0.6, twin.getZ());
        }

        // The harmony trail.
        if (ticks % TRAIL_INTERVAL == 0) {
            level.sendParticles(ParticleTypes.NOTE, twin.getX(), twin.getY() + 0.9, twin.getZ(),
                1, 0.2, 0.15, 0.2, 0.0);
        }

        // Babysitting: keep the twin on the caster's fight.
        if (ticks % BABYSIT_INTERVAL == 0 && target != null && twin.getTarget() != target) {
            twin.setTarget(target);
        }

        // The pecks: code strikes from both twins at their own attack value.
        if (ticks % PECK_INTERVAL == 0 && target != null && target.isAlive()) {
            if (twin.distanceToSqr(target) <= PECK_RANGE * PECK_RANGE) {
                target.hurtServer(level, level.damageSources().mobAttack(twin),
                    (float) twin.getAttributeValue(Attributes.ATTACK_DAMAGE));
            }
            if (caster.distanceToSqr(target) <= PECK_RANGE * PECK_RANGE) {
                target.hurtServer(level, level.damageSources().mobAttack(caster),
                    (float) caster.getAttributeValue(Attributes.ATTACK_DAMAGE));
            }
        }

        // The attention swap: enemy mobs trade twins.
        if (ticks % SWAP_INTERVAL == 0) {
            List<LivingEntity> nearby = AllayAbilitySupport.enemiesInBox(ctx,
                caster.getX(), caster.getY(), caster.getZ(), SWAP_RADIUS, 4.0, 4.0);
            for (LivingEntity enemy : nearby) {
                if (enemy instanceof Mob enemyMob) {
                    if (enemyMob.getTarget() == caster) {
                        enemyMob.setTarget(twin);
                    } else if (enemyMob.getTarget() == twin) {
                        enemyMob.setTarget(caster);
                    }
                }
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        Entity prop = AllayAbilitySupport.resolveStored(level, state, "prop");
        if (prop != null) {
            prop.discard();
        }
        if (!state.contains("twin")) {
            return;
        }
        UUID id = state.read("twin", UUIDUtil.CODEC).orElse(null);
        if (id == null) {
            return;
        }
        // The alliance ends with the ability, whichever side is still here.
        ctx.data().removeAlly(id);
        Entity found = level.getEntity(id);
        if (found instanceof Allay twin) {
            MobWarData.get(twin).removeAlly(caster.getUUID());
            if (twin.isAlive()) {
                level.sendParticles(ParticleTypes.POOF, twin.getX(), twin.getY() + 0.4, twin.getZ(),
                    6, 0.3, 0.3, 0.3, 0.05);
                twin.discard();
            }
        }
    }
}
