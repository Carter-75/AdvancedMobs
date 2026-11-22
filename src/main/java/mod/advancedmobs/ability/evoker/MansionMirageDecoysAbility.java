package mod.advancedmobs.ability.evoker;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.illager.Evoker;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Mansion Mirage Decoys (Evoker ability 5 of 5, defense,
 * summon).
 *
 * WHAT IT DOES: the evoker claps, and two more evokers step
 * out of him: duplicates with a single point of health and
 * no spells, posed mid-cast at either side of where he
 * stood, while the real one slips away invisible to a new
 * position across the room. The decoys hold their dramatic
 * poses and soak the first hits aimed at the caster,
 * popping into smoke and spell sparkles when struck. The
 * trick fails against enemies who watch which evoker
 * actually casts, because the decoys never do.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the evoker's current target within 18 blocks (distance
 *   squared 324).
 * - The decoys (start()): 2 evokers, spawned 2.2 blocks to
 *   either side of the caster along the horizontal axis
 *   perpendicular to his facing, at his own height, created
 *   with EntitySpawnReason.MOB_SUMMONED and finalizeSpawn
 *   like every other summon in this mod. Each decoy's
 *   MAX_HEALTH base is then set to 1.0 with health 1.0 (the
 *   design's summon sheet: a single point of health). Team
 *   assignment, per decoy, both
 *   directions plus the registry: the caster's data gains
 *   the decoy as an ally, the decoy's data gains the
 *   caster, and the pair is registered in SummonerTracker
 *   (which also tags the decoy umw_summoned for the natural
 *   spawn blocker). No limited life is set: the decoys live
 *   exactly as long as the ability does.
 * - THE NO-AI QUESTION, documented plainly: the design's
 *   summon sheet says "data set to no AI". The standing
 *   contract of this system (MobAbility's class comment)
 *   forbids relying on NoAi: abilities tick from the AI
 *   step, and a NoAi mob falls out of every system built on
 *   it. The decoys are therefore held as statues WITHOUT
 *   NoAi, by the babysit below: navigation stopped, target
 *   cleared, and horizontal velocity zeroed, every 5 ticks.
 *   A decoy's own goals get at most 5 ticks of slack at a
 *   time and never a target to cast at, so it never casts,
 *   which is the design's "no spells" in practice. This is
 *   a deliberate, documented substitution of mechanism, not
 *   of behavior.
 * - THE POSE, documented plainly: the design poses the
 *   decoys mid-cast, arms raised. In 26.2 the vanilla
 *   switch for that pose, SpellcasterIllager
 *   .setIsCastingSpell, takes the nested IllagerSpell enum,
 *   which is declared protected inside SpellcasterIllager
 *   (verified against the jar): no code outside the illager
 *   hierarchy can name the type, so the pose cannot be set
 *   from here. The mid-cast read is carried instead by 1
 *   ENCHANT mote sent at each decoy's raised-hand height
 *   (its Y plus 1.6) on every babysit pass: a statue
 *   trailing spell sparkles at hand height, frozen mid-gesture
 *   it never completes. The behavior (a posed decoy that
 *   never casts) is exact; only the arm angle is
 *   approximated, on the Mirror Ghasts shimmer precedent.
 * - The slip away (start(), after the decoys): the caster
 *   tries to reposition directly away from his target:
 *   distances 8, 6, and 10 blocks, at angle offsets 0, plus
 *   or minus 0.5, and plus or minus 1.0 radians around the
 *   exact away direction (15 tries, in that order), each
 *   spot found by the shared floor scan (columns scanned
 *   from 4 above his block Y down to 8 below, his own
 *   bounding box required collision-free). The first spot
 *   that fits takes him: 12 PORTAL motes burst at the old
 *   position, he teleports, and 12 more burst at the new
 *   one. If no spot fits he simply stays where he is. Either
 *   way he gains INVISIBILITY for 100 ticks (5 seconds),
 *   particles and icon hidden: the slip itself.
 * - The babysit (tick(), every 5 ticks): each living decoy
 *   is held (navigation stopped, target cleared, horizontal
 *   velocity zeroed with the vertical component kept, so a
 *   decoy knocked into the air still falls), its position
 *   is recorded, and its mid-cast sparkle is sent (see THE
 *   POSE above). A decoy that no longer resolves, or resolves
 *   dead, has been struck down: it pops exactly once at its
 *   last recorded position with 10 POOF and 8 ENCHANT and
 *   an EVOKER_HURT at pitch 1.4, and the caster's ally
 *   record for it is removed. A decoy that unloads with its
 *   chunk reads as gone the same way (the Mirage Split edge
 *   case, shared by every tracked summon in this mod).
 * - Duration: 300 ticks (15 seconds), hard finish.
 * - end(): each surviving decoy is discarded in 8 POOF,
 *   the alliance records are removed on both sides for
 *   every tracked decoy, and the caster's INVISIBILITY is
 *   removed if it is somehow still running (it normally
 *   expires on its own at tick 100). If the caster dies
 *   first, the framework termination path runs this same
 *   end(), so the decoys never outlive the trick.
 *   SummonerTracker records are deliberately not
 *   unregistered, matching the other summons in this mod.
 *
 * WHAT IT LOOKS LIKE: a clap and a puff of portal motes,
 * and suddenly three evokers stand in a row with their arms
 * raised, two of them frozen in the pose like statues at a
 * waxworks, while the third is simply not there anymore.
 * Hits on the statues end in smoke and sparkles; the real
 * one reappears across the room five seconds later,
 * mid-fight and already casting again.
 *
 * PERFORMANCE: start() does 2 spawns and at most 15 bounded
 * column scans (at most 13 block reads each) for the
 * reposition. tick() returns immediately except every 5th
 * tick, where it resolves at most 2 UUIDs and writes at
 * most 2 navigation stops, 2 target clears, and 2 velocity
 * writes: no entity scans anywhere. Particle budget per
 * activation: 24 at start, babysit sparkles at most 60
 * passes times 2 decoys = 120, pops at most 2 times 18 =
 * 36, end() puffs at most 2 times 8 = 16, total at most 196
 * particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "decoys" (list of compounds, one per
 * decoy: "id" the decoy's UUID, "x", "y", "z" its last
 * recorded position, "popped" 1 once its pop burst ran).
 */
public final class MansionMirageDecoysAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int BABYSIT_INTERVAL = 5;
    private static final int INVISIBILITY_TICKS = 100;
    private static final double SIDE_OFFSET = 2.2;
    private static final double[] SLIP_DISTANCES = {8.0, 6.0, 10.0};
    private static final double[] SLIP_ANGLE_OFFSETS = {0.0, 0.5, -0.5, 1.0, -1.0};

    @Override
    public String id() {
        return "mansion_mirage_decoys";
    }

    @Override
    public String displayName() {
        return "Mansion Mirage Decoys";
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
        ListTag decoys = new ListTag();
        ctx.state().put("decoys", decoys);
        if (level == null) {
            return;
        }

        // The shoulder axis: perpendicular to the caster's facing.
        double yawRad = Math.toRadians(caster.getYRot());
        double perpX = Math.cos(yawRad);
        double perpZ = Math.sin(yawRad);
        int[] sides = {-1, 1};
        for (int side : sides) {
            Evoker decoy = EntityTypes.EVOKER.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (decoy == null) {
                continue;
            }
            decoy.snapTo(caster.getX() + perpX * SIDE_OFFSET * side, caster.getY(),
                caster.getZ() + perpZ * SIDE_OFFSET * side, caster.getYRot(), 0.0f);
            decoy.finalizeSpawn(level, level.getCurrentDifficultyAt(decoy.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
            // The decoy's sheet (see the class comment): base
            // values, set after finalizeSpawn so nothing
            // re-rolls them.
            AttributeInstance health = decoy.getAttribute(Attributes.MAX_HEALTH);
            if (health != null) {
                health.setBaseValue(1.0);
            }
            decoy.setHealth(1.0f);
            if (!level.addFreshEntity(decoy)) {
                continue;
            }
            ctx.data().addAlly(decoy.getUUID());
            MobWarData.get(decoy).addAlly(caster.getUUID());
            SummonerTracker.registerSummoned(decoy.getUUID(), caster.getUUID());

            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, decoy.getUUID());
            entry.putDouble("x", decoy.getX());
            entry.putDouble("y", decoy.getY());
            entry.putDouble("z", decoy.getZ());
            entry.putInt("popped", 0);
            decoys.add(entry);
        }
        ctx.state().put("decoys", decoys);

        // The slip away (see the class comment): reposition
        // away from the target if any tried spot fits, then
        // vanish either way.
        slipAway(ctx, level);
        caster.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, INVISIBILITY_TICKS, 0, false, false, false));
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.EVOKER_CAST_SPELL, SoundSource.HOSTILE, 1.2f, 1.2f);
    }

    /** The repositioning half of start(): tries the away spots in order and teleports to the first that fits. */
    private static void slipAway(AbilityContext ctx, ServerLevel level) {
        Mob caster = ctx.mob();
        LivingEntity target = ctx.target();
        if (target == null) {
            return;
        }
        double awayX = caster.getX() - target.getX();
        double awayZ = caster.getZ() - target.getZ();
        double len = Math.sqrt(awayX * awayX + awayZ * awayZ);
        if (len < 0.001) {
            awayX = 1.0;
            awayZ = 0.0;
            len = 1.0;
        }
        awayX /= len;
        awayZ /= len;
        double baseAngle = Math.atan2(awayZ, awayX);
        int scanTop = (int) Math.floor(caster.getY()) + 4;
        int scanBottom = (int) Math.floor(caster.getY()) - 8;
        for (double distance : SLIP_DISTANCES) {
            for (double offset : SLIP_ANGLE_OFFSETS) {
                double angle = baseAngle + offset;
                int blockX = (int) Math.floor(caster.getX() + Math.cos(angle) * distance);
                int blockZ = (int) Math.floor(caster.getZ() + Math.sin(angle) * distance);
                double[] spot = EvokerAbilitySupport.findFloorSpot(level, caster, blockX, blockZ, scanTop, scanBottom);
                if (spot != null) {
                    level.sendParticles(ParticleTypes.PORTAL, caster.getX(), caster.getY() + 1.0, caster.getZ(),
                        12, 0.4, 0.6, 0.4, 0.10);
                    caster.teleportTo(spot[0], spot[1], spot[2]);
                    level.sendParticles(ParticleTypes.PORTAL, spot[0], spot[1] + 1.0, spot[2],
                        12, 0.4, 0.6, 0.4, 0.10);
                    return;
                }
            }
        }
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
        ListTag decoys = ctx.state().getListOrEmpty("decoys");
        for (int i = 0; i < decoys.size(); i++) {
            CompoundTag entry = decoys.getCompoundOrEmpty(i);
            if (entry.getIntOr("popped", 0) == 1) {
                continue;
            }
            Entity found = EvokerAbilitySupport.resolveTracked(level, entry);
            if (found instanceof Evoker decoy && decoy.isAlive()) {
                entry.putDouble("x", decoy.getX());
                entry.putDouble("y", decoy.getY());
                entry.putDouble("z", decoy.getZ());
                // The statue hold (see the class comment):
                // no NoAi, just a firm hand every 5 ticks.
                decoy.getNavigation().stop();
                decoy.setTarget(null);
                Vec3 velocity = decoy.getDeltaMovement();
                decoy.setDeltaMovement(0.0, velocity.y, 0.0);
                // The mid-cast sparkle (see THE POSE in the
                // class comment).
                level.sendParticles(ParticleTypes.ENCHANT, decoy.getX(), decoy.getY() + 1.6, decoy.getZ(),
                    1, 0.15, 0.10, 0.15, 0.05);
            } else {
                popDecoy(ctx, level, entry);
            }
            decoys.set(i, entry);
        }
        ctx.state().put("decoys", decoys);
    }

    /** The pop burst for one struck decoy (see the class comment). Runs once per decoy. */
    private static void popDecoy(AbilityContext ctx, ServerLevel level, CompoundTag entry) {
        entry.putInt("popped", 1);
        UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
        if (id != null) {
            ctx.data().removeAlly(id);
        }
        double x = entry.getDoubleOr("x", ctx.mob().getX());
        double y = entry.getDoubleOr("y", ctx.mob().getY());
        double z = entry.getDoubleOr("z", ctx.mob().getZ());
        level.sendParticles(ParticleTypes.POOF, x, y + 1.0, z, 10, 0.3, 0.6, 0.3, 0.05);
        level.sendParticles(ParticleTypes.ENCHANT, x, y + 1.0, z, 8, 0.4, 0.6, 0.4, 0.10);
        level.playSound(null, x, y, z, SoundEvents.EVOKER_HURT, SoundSource.HOSTILE, 0.9f, 1.4f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.removeEffect(MobEffects.INVISIBILITY);
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
            if (found instanceof Evoker decoy) {
                MobWarData.get(decoy).removeAlly(caster.getUUID());
                if (decoy.isAlive()) {
                    level.sendParticles(ParticleTypes.POOF, decoy.getX(), decoy.getY() + 1.0, decoy.getZ(),
                        8, 0.3, 0.6, 0.3, 0.05);
                    decoy.discard();
                }
            }
        }
    }
}
