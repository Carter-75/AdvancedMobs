package mod.advancedmobs.ability.sheep;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.DustColorTransitionOptions;
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
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Rainbow Mirage Flock (Sheep ability 4 of 5, control,
 * summon).
 *
 * WHAT IT DOES: the sheep's wool washes through rainbow
 * colors in a swirl of color transitioning dust, and the
 * one sheep becomes three: two plain decoys in the
 * caster's own color step out on either side while the
 * real sheep vanishes and repositions. Enemies who were
 * locked onto the real sheep find their target suddenly
 * a decoy, and the flock shimmers through the spectrum
 * while the fight wastes itself on one point of health
 * at a time.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the sheep's current target within 16 blocks
 *   (distance squared 256).
 * - Duration: exactly 300 ticks (15 seconds) from start().
 * - The split (start()): 12 DUST_COLOR_TRANSITION motes
 *   burst around the caster, cycling the three pre-built
 *   transitions (red to amber, gold to green, blue to
 *   violet), SHEEP_AMBIENT plays at volume 1.2, pitch
 *   1.1, and the caster is marked with the entity tag
 *   "advancedmobs_mirage_original" (the design's "tag to
 *   mark the original"; removed in end()).
 * - The decoys (start()): 2 sheep on the mirror sheet
 *   (see SheepAbilitySupport: MAX_HEALTH base 1.0, the
 *   caster's own wool color read from Sheep.getColor,
 *   white when the caster is somehow not a sheep,
 *   allied both directions plus SummonerTracker),
 *   spawned 2.0 blocks to the caster's left and right on
 *   the axis perpendicular to its look direction, at the
 *   caster's own position when that spot collides. A
 *   decoy has no target and no attack: it is a sheep, and
 *   it reads as the real thing at a glance.
 * - The vanish (start()): the caster gains INVISIBILITY
 *   for 320 ticks with particles and icon hidden, then
 *   repositions: candidate spots 6.0 blocks from its
 *   start position along the direction directly away
 *   from its target, then rotated plus and minus 45 and
 *   plus and minus 90 degrees, are tried in order; the
 *   first spot whose bounding box collides with nothing
 *   and that has a non-air block under it takes the
 *   teleport. When no spot qualifies the sheep stays
 *   where it stands (invisible, but home). Documented
 *   edge: the caster's own war target is untouched, so
 *   the sheep keeps its fight; only its body moves.
 * - The misdirection (start(), then every 40 ticks):
 *   one bounded query at radius 16 (2 below to 4 above)
 *   finds every enemy Mob whose current target is the
 *   caster and sets its target to a living decoy,
 *   alternating between the two. Players are never
 *   retargeted (there is no target to set on a player);
 *   a player has to spot the trick themselves.
 * - The flock (tick(), every 5 ticks): each living decoy
 *   is steered by its move control toward a point 2.5
 *   blocks short of the framework target's live position
 *   at speed 1.0 (or holds near the caster when there is
 *   no target), so the decoys stay in the fight instead
 *   of wandering off to graze; its position is recorded
 *   in the same pass. The rainbow wash runs in the same
 *   pass: 2 DUST_COLOR_TRANSITION motes at each living
 *   decoy, the transition cycling by pass count so the
 *   flock shimmers through the spectrum.
 * - The pop (same pass): a tracked decoy that no longer
 *   resolves, or resolves dead, has been destroyed: at
 *   its last recorded position the ability throws 8 POOF
 *   and 6 DUST_COLOR_TRANSITION, plays SHEEP_HURT at
 *   volume 0.9, pitch 1.5, removes the caster's ally
 *   record for it, and marks it popped so the burst runs
 *   exactly once. A decoy that unloads with its chunk
 *   reads as gone the same way (the shared tracked summon
 *   edge case).
 * - end(): the INVISIBILITY is removed from the caster
 *   and the original tag is removed; each surviving
 *   decoy is discarded in 6 POOF and the alliance
 *   records are removed on both sides. If the caster
 *   dies first, the framework termination path runs this
 *   same end(), so the flock never outlives the trick.
 *   SummonerTracker records are deliberately not
 *   unregistered, matching the other summons in this mod.
 *
 * WHAT IT LOOKS LIKE: a baa, a wash of rainbow dust, and
 * suddenly three identical sheep stand where one did,
 * colors cycling across their wool in slow waves; hits
 * land on sheep that burst into puffs and color, while
 * the real one is nowhere to be seen.
 *
 * PERFORMANCE: start() does 2 spawns, at most 5 collision
 * checks for the reposition, and one bounded query for
 * the misdirection. tick() returns immediately except
 * every 5th tick, where it resolves at most 2 UUIDs,
 * writes at most 2 move-control wanted positions, and
 * emits the wash; the misdirection pass is one bounded
 * query every 40 ticks. Particle budget per activation:
 * 12 at start, wash 60 passes times 2 decoys times 2 =
 * 240, pops at most 2 times 14 = 28, end() puffs at most
 * 2 times 6 = 12, total at most 292 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply. The decoys are summoned entities and the
 * reposition is a teleport.
 *
 * State tag keys: "decoys" (list of compounds, one per
 * decoy: "id" the decoy's UUID, "x", "y", "z" its last
 * recorded position, "popped" 1 once its pop burst ran).
 */
public final class RainbowMirageFlockAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int BABYSIT_INTERVAL = 5;
    private static final int MISDIRECT_INTERVAL = 40;
    private static final int DECOY_COUNT = 2;
    private static final double DECOY_OFFSET = 2.0;
    private static final double REPOSITION_DISTANCE = 6.0;
    private static final String ORIGINAL_TAG = "advancedmobs_mirage_original";
    private static final DustColorTransitionOptions[] WASH = {
        new DustColorTransitionOptions(0xFF4040, 0xFFB340, 1.0f),
        new DustColorTransitionOptions(0xFFE14D, 0x59D95E, 1.0f),
        new DustColorTransitionOptions(0x4DA6FF, 0xB45CFF, 1.0f)
    };

    @Override
    public String id() {
        return "rainbow_mirage_flock";
    }

    @Override
    public String displayName() {
        return "Rainbow Mirage Flock";
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
        ListTag decoys = new ListTag();
        ctx.state().put("decoys", decoys);
        caster.addTag(ORIGINAL_TAG);
        if (level == null) {
            return;
        }

        // The split: rainbow wash and the baa.
        for (int i = 0; i < 4; i++) {
            for (DustColorTransitionOptions option : WASH) {
                level.sendParticles(option, caster.getX(), caster.getY() + 0.9, caster.getZ(),
                    1, 0.5, 0.5, 0.5, 0.05);
            }
        }
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.SHEEP_AMBIENT, SoundSource.HOSTILE, 1.2f, 1.1f);

        // The decoys, one to each side of the look axis.
        DyeColor color = caster instanceof Sheep sheep ? sheep.getColor() : DyeColor.WHITE;
        Vec3 look = caster.getLookAngle();
        double sideX = -look.z;
        double sideZ = look.x;
        double sideLength = Math.sqrt(sideX * sideX + sideZ * sideZ);
        if (sideLength < 0.001) {
            sideX = 1.0;
            sideZ = 0.0;
            sideLength = 1.0;
        }
        sideX /= sideLength;
        sideZ /= sideLength;
        for (int i = 0; i < DECOY_COUNT; i++) {
            double sign = i == 0 ? 1.0 : -1.0;
            double spawnX = caster.getX() + sideX * DECOY_OFFSET * sign;
            double spawnZ = caster.getZ() + sideZ * DECOY_OFFSET * sign;
            if (!level.noCollision(caster, caster.getBoundingBox().move(
                spawnX - caster.getX(), 0.0, spawnZ - caster.getZ()))) {
                spawnX = caster.getX();
                spawnZ = caster.getZ();
            }
            Sheep decoy = SheepAbilitySupport.summonDecoy(ctx,
                spawnX, caster.getY(), spawnZ, caster.getYRot(), color);
            if (decoy == null) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, decoy.getUUID());
            entry.putDouble("x", decoy.getX());
            entry.putDouble("y", decoy.getY());
            entry.putDouble("z", decoy.getZ());
            entry.putInt("popped", 0);
            decoys.add(entry);
        }
        ctx.state().put("decoys", decoys);

        // The vanish and the reposition (see the class
        // comment for the candidate order).
        caster.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 320, 0, false, false, false));
        reposition(ctx, level);

        // The first misdirection.
        misdirect(ctx, level);
    }

    /** Teleports the caster to the first clear spot away from its target (see the class comment). */
    private void reposition(AbilityContext ctx, ServerLevel level) {
        Mob caster = ctx.mob();
        double awayX = 0.0;
        double awayZ = 1.0;
        LivingEntity target = ctx.target();
        if (target != null) {
            awayX = caster.getX() - target.getX();
            awayZ = caster.getZ() - target.getZ();
        }
        double length = Math.sqrt(awayX * awayX + awayZ * awayZ);
        if (length < 0.001) {
            Vec3 look = caster.getLookAngle();
            awayX = -look.x;
            awayZ = -look.z;
            length = Math.sqrt(awayX * awayX + awayZ * awayZ);
            if (length < 0.001) {
                return;
            }
        }
        awayX /= length;
        awayZ /= length;
        double baseAngle = Math.atan2(awayZ, awayX);
        double[] rotations = {0.0, Math.PI / 4.0, -Math.PI / 4.0, Math.PI / 2.0, -Math.PI / 2.0};
        for (double rotation : rotations) {
            double angle = baseAngle + rotation;
            double candidateX = caster.getX() + Math.cos(angle) * REPOSITION_DISTANCE;
            double candidateZ = caster.getZ() + Math.sin(angle) * REPOSITION_DISTANCE;
            AABB moved = caster.getBoundingBox().move(
                candidateX - caster.getX(), 0.0, candidateZ - caster.getZ());
            if (!level.noCollision(caster, moved)) {
                continue;
            }
            BlockPos below = BlockPos.containing(candidateX, caster.getY() - 0.5, candidateZ);
            if (level.getBlockState(below).isAir()) {
                continue;
            }
            caster.teleportTo(candidateX, caster.getY(), candidateZ);
            return;
        }
    }

    /**
     * Points every enemy mob currently targeting the caster
     * at a living decoy instead (see the class comment).
     * One bounded query; runs in start() and every 40 ticks.
     */
    private void misdirect(AbilityContext ctx, ServerLevel level) {
        Mob caster = ctx.mob();
        List<Sheep> living = new java.util.ArrayList<>();
        ListTag decoys = ctx.state().getListOrEmpty("decoys");
        for (int i = 0; i < decoys.size(); i++) {
            CompoundTag entry = decoys.getCompoundOrEmpty(i);
            if (entry.getIntOr("popped", 0) == 1) {
                continue;
            }
            Entity found = SheepAbilitySupport.resolveTracked(level, entry);
            if (found instanceof Sheep decoy && decoy.isAlive()) {
                living.add(decoy);
            }
        }
        if (living.isEmpty()) {
            return;
        }
        AABB box = new AABB(caster.getX() - 16.0, caster.getY() - 2.0, caster.getZ() - 16.0,
                            caster.getX() + 16.0, caster.getY() + 4.0, caster.getZ() + 16.0);
        List<Mob> hunters = level.getEntitiesOfClass(Mob.class, box,
            e -> e != caster && e.getTarget() == caster && SheepAbilitySupport.isEnemy(ctx, e));
        int pick = 0;
        for (Mob hunter : hunters) {
            hunter.setTarget(living.get(pick % living.size()));
            pick++;
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks % BABYSIT_INTERVAL != 0) {
            return; // Cheap early-out: all work rides the 5-tick babysit.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        if (ticks % MISDIRECT_INTERVAL == 0) {
            misdirect(ctx, level);
        }
        LivingEntity target = ctx.target();
        ListTag decoys = ctx.state().getListOrEmpty("decoys");
        int washIndex = ticks / BABYSIT_INTERVAL;
        for (int i = 0; i < decoys.size(); i++) {
            CompoundTag entry = decoys.getCompoundOrEmpty(i);
            if (entry.getIntOr("popped", 0) == 1) {
                continue;
            }
            Entity found = SheepAbilitySupport.resolveTracked(level, entry);
            if (found instanceof Sheep decoy && decoy.isAlive()) {
                entry.putDouble("x", decoy.getX());
                entry.putDouble("y", decoy.getY());
                entry.putDouble("z", decoy.getZ());
                // The flock hold: stay in the fight (see
                // the class comment).
                double wantedX;
                double wantedY;
                double wantedZ;
                if (target != null) {
                    double dx = decoy.getX() - target.getX();
                    double dz = decoy.getZ() - target.getZ();
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    if (dist < 0.001) {
                        dx = 1.0;
                        dz = 0.0;
                        dist = 1.0;
                    }
                    wantedX = target.getX() + dx / dist * 2.5;
                    wantedY = target.getY();
                    wantedZ = target.getZ() + dz / dist * 2.5;
                } else {
                    wantedX = caster.getX();
                    wantedY = caster.getY();
                    wantedZ = caster.getZ();
                }
                decoy.getMoveControl().setWantedPosition(wantedX, wantedY, wantedZ, 1.0);
                // The rainbow wash.
                level.sendParticles(WASH[(washIndex + i) % WASH.length],
                    decoy.getX(), decoy.getY() + 0.9, decoy.getZ(),
                    2, 0.45, 0.45, 0.45, 0.05);
            } else {
                popDecoy(ctx, level, entry);
            }
            decoys.set(i, entry);
        }
        ctx.state().put("decoys", decoys);
    }

    /** The pop burst for one destroyed decoy (see the class comment). Runs once per decoy. */
    private static void popDecoy(AbilityContext ctx, ServerLevel level, CompoundTag entry) {
        entry.putInt("popped", 1);
        UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
        if (id != null) {
            ctx.data().removeAlly(id);
        }
        double x = entry.getDoubleOr("x", ctx.mob().getX());
        double y = entry.getDoubleOr("y", ctx.mob().getY());
        double z = entry.getDoubleOr("z", ctx.mob().getZ());
        level.sendParticles(ParticleTypes.POOF, x, y + 0.8, z, 8, 0.3, 0.5, 0.3, 0.05);
        for (DustColorTransitionOptions option : WASH) {
            level.sendParticles(option, x, y + 0.8, z, 2, 0.35, 0.5, 0.35, 0.06);
        }
        level.playSound(null, x, y, z, SoundEvents.SHEEP_HURT, SoundSource.HOSTILE, 0.9f, 1.5f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.removeEffect(MobEffects.INVISIBILITY);
        caster.removeTag(ORIGINAL_TAG);
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag decoys = ctx.state().getListOrEmpty("decoys");
        for (int i = 0; i < decoys.size(); i++) {
            CompoundTag entry = decoys.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id == null) {
                continue;
            }
            ctx.data().removeAlly(id);
            Entity found = level.getEntity(id);
            if (found instanceof Sheep decoy) {
                MobWarData.get(decoy).removeAlly(caster.getUUID());
                if (decoy.isAlive()) {
                    level.sendParticles(ParticleTypes.POOF, decoy.getX(), decoy.getY() + 0.8, decoy.getZ(),
                        6, 0.3, 0.5, 0.3, 0.05);
                    decoy.discard();
                }
            }
        }
    }
}
