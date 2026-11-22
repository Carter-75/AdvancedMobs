package mod.advancedmobs.ability.shulker;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Shulker;

/**
 * Chorus Split (Shulker ability 4 of 5, summon, decoy).
 *
 * WHAT IT DOES: struck hard enough, the shulker splits its
 * defense: it teleports away in a burst of REVERSE_PORTAL
 * particles and leaves a twin behind in its exact spot, a
 * summoned shulker with a sliver of health that keeps firing
 * at full enthusiasm. Enemies who chase the wrong shell
 * waste their burst on a decoy that pops into END_ROD
 * sparks, while the real shulker reopens somewhere safe and
 * starts again.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the shulker's current target within 16 blocks (distance
 *   squared 256), plus the shulker at or below 50 percent of
 *   its max health: the design's "struck hard enough",
 *   expressed as the health gate the framework can see (the
 *   Gelatinous Split precedent). The split is one activation:
 *   it can happen once per framework firing, and the
 *   framework cooldown governs how often a fight sees it.
 * - The decoy (start()): one vanilla shulker, created with
 *   EntitySpawnReason.MOB_SUMMONED and finalizeSpawn at the
 *   caster's exact position, then its MAX_HEALTH base value
 *   is set to 6.0 (a sliver of the vanilla 30, the design's
 *   summon sheet, set after finalizeSpawn the Turret Brood
 *   way) and it is healed to that full 6. Team assignment,
 *   both directions plus the registry: the caster's data
 *   gains the decoy as an ally, the decoy's data gains the
 *   caster, and the pair is registered in SummonerTracker
 *   (which also tags the decoy umw_summoned for the natural
 *   spawn blocker). The decoy is ordered onto the caster's
 *   current target at spawn, and its own vanilla attack goal
 *   does the firing: "full enthusiasm" is literal.
 * - The escape (start()): the caster teleports away from
 *   its target. Candidate columns are tried in order:
 *   distances 10, 8, and 12 blocks, at the direction directly
 *   away from the target, then 45 and 90 degrees off that
 *   direction on both sides (15 candidates). Each column is
 *   scanned from 4 above to 8 below the caster's height for
 *   a floor spot with a collision-free 1x1x1 space (the
 *   shared findFloorSpot). The first spot found wins: the
 *   caster snaps to it with REVERSE_PORTAL bursts at both
 *   ends (20 motes leaving, 12 arriving) and the
 *   SHULKER_TELEPORT sound. If no candidate offers a spot,
 *   the caster simply stays where it is; the decoy is still
 *   left behind and the split still counts.
 * - Babysitting every 5 ticks: the decoy's position is
 *   recorded (its pop point). A living decoy whose target is
 *   empty or no longer an enemy is re-ordered onto the
 *   caster's live target.
 * - The pop: the first babysit that finds the decoy dead or
 *   gone plays its end at the recorded position: 16 END_ROD
 *   motes and SHULKER_DEATH at volume 0.8. The activation
 *   then finishes 20 ticks later.
 * - Duration: at most 300 ticks (15 seconds) from start(),
 *   or 20 ticks after the pop, whichever comes first.
 * - end(): the alliance records are removed on both sides,
 *   and a decoy still alive is discarded in a 12-mote
 *   END_ROD burst: the twin is the ability's construct and
 *   does not outlive it. If the caster dies first, the
 *   framework termination path runs this same end(), so the
 *   decoy never outlives its summoner either.
 *   SummonerTracker records are deliberately not
 *   unregistered here (the Scarab Brood pattern: that
 *   registry cleans up on the decoy's own removal).
 *
 * WHAT IT LOOKS LIKE: a REVERSE_PORTAL detonation where the
 * shulker stood, the shell apparently still sitting there
 * (the decoy, already opening and firing), and a second
 * REVERSE_PORTAL shimmer up to a dozen blocks away where
 * the real shulker settles onto new ground. A killed decoy
 * pops in a spray of END_ROD sparks instead of a corpse's
 * usual loot drop (summoned mobs drop nothing extra here;
 * the pop is particles and sound).
 *
 * PERFORMANCE: start() does at most 15 bounded column scans
 * (9 block reads each) and one spawn. tick() returns
 * immediately except every 5th tick, where it resolves one
 * UUID and reads or writes one target. Particle budget per
 * activation: teleport bursts 32, pop 16, end burst 12,
 * total at most 60 sent particles.
 *
 * State tag keys: "decoy" (list with one compound: "id", the
 * decoy's UUID), "decoy_x"/"decoy_y"/"decoy_z" (the decoy's
 * last recorded position), "popped" (whether the pop has
 * played), "end_at" (the tick the activation finishes after
 * the pop; absent until the pop).
 */
public final class ChorusSplitAbility implements MobAbility {

    static final int DURATION_TICKS = 300;
    private static final int BABYSIT_INTERVAL = 5;
    private static final double DECOY_MAX_HEALTH = 6.0;
    private static final int[] ESCAPE_DISTANCES = {10, 8, 12};
    private static final double[] ESCAPE_ANGLE_OFFSETS = {0.0, 0.785, -0.785, 1.571, -1.571};

    @Override
    public String id() {
        return "chorus_split";
    }

    @Override
    public String displayName() {
        return "Chorus Split";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null || ctx.mob().distanceToSqr(target) > 256.0) {
            return false;
        }
        Mob caster = ctx.mob();
        return caster.getHealth() <= caster.getMaxHealth() * 0.5f;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        LivingEntity target = ctx.target();

        // The caster's spot, recorded before anything moves:
        // the decoy's home and the pop point fallback.
        ctx.state().putDouble("decoy_x", caster.getX());
        ctx.state().putDouble("decoy_y", caster.getY());
        ctx.state().putDouble("decoy_z", caster.getZ());

        // The twin left behind.
        Shulker decoy = EntityTypes.SHULKER.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (decoy != null) {
            decoy.snapTo(caster.getX(), caster.getY(), caster.getZ(), caster.getYRot(), 0.0f);
            decoy.finalizeSpawn(level, level.getCurrentDifficultyAt(decoy.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
            decoy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(DECOY_MAX_HEALTH);
            decoy.setHealth((float) DECOY_MAX_HEALTH);
            if (level.addFreshEntity(decoy)) {
                ctx.data().addAlly(decoy.getUUID());
                MobWarData.get(decoy).addAlly(caster.getUUID());
                SummonerTracker.registerSummoned(decoy.getUUID(), caster.getUUID());
                if (target != null) {
                    decoy.setTarget(target);
                }
                ShulkerAbilitySupport.trackEntity(ctx.state(), "decoy", decoy);
            }
        }

        // The escape: away from the target, first open floor wins.
        if (target != null) {
            double awayAngle = Math.atan2(caster.getZ() - target.getZ(), caster.getX() - target.getX());
            double[] spot = null;
            outer:
            for (int distance : ESCAPE_DISTANCES) {
                for (double offset : ESCAPE_ANGLE_OFFSETS) {
                    double angle = awayAngle + offset;
                    int blockX = (int) Math.floor(caster.getX() + Math.cos(angle) * distance);
                    int blockZ = (int) Math.floor(caster.getZ() + Math.sin(angle) * distance);
                    spot = ShulkerAbilitySupport.findFloorSpot(level, caster, blockX, blockZ,
                        caster.blockPosition().getY() + 4, caster.blockPosition().getY() - 8);
                    if (spot != null) {
                        break outer;
                    }
                }
            }
            if (spot != null) {
                level.sendParticles(ParticleTypes.REVERSE_PORTAL,
                    caster.getX(), caster.getY() + 0.8, caster.getZ(),
                    20, 0.5, 0.6, 0.5, 0.1);
                caster.snapTo(spot[0], spot[1], spot[2], caster.getYRot(), 0.0f);
                level.sendParticles(ParticleTypes.REVERSE_PORTAL,
                    spot[0], spot[1] + 0.8, spot[2],
                    12, 0.5, 0.6, 0.5, 0.1);
                level.playSound(null, spot[0], spot[1], spot[2],
                    SoundEvents.SHULKER_TELEPORT, SoundSource.HOSTILE, 1.2f, 1.0f);
            }
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % BABYSIT_INTERVAL != 0) {
            return; // Cheap early-out: babysitting every 5 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null || ctx.state().getBooleanOr("popped", false)) {
            return;
        }
        var decoyList = ctx.state().getListOrEmpty("decoy");
        if (decoyList.isEmpty()) {
            return;
        }
        Shulker decoy = ShulkerAbilitySupport.resolveTracked(level, decoyList.getCompoundOrEmpty(0))
            instanceof Shulker found ? found : null;
        if (decoy != null && decoy.isAlive()) {
            ctx.state().putDouble("decoy_x", decoy.getX());
            ctx.state().putDouble("decoy_y", decoy.getY());
            ctx.state().putDouble("decoy_z", decoy.getZ());
            LivingEntity decoyTarget = decoy.getTarget();
            LivingEntity desired = ctx.target();
            if ((decoyTarget == null || !ShulkerAbilitySupport.isEnemy(ctx, decoyTarget))
                && desired != null && desired != decoy) {
                decoy.setTarget(desired);
            }
            return;
        }

        // The pop: the decoy is dead or gone.
        ctx.state().putBoolean("popped", true);
        ctx.state().putInt("end_at", ctx.ticksActive() + 20);
        double popX = ctx.state().getDoubleOr("decoy_x", ctx.mob().getX());
        double popY = ctx.state().getDoubleOr("decoy_y", ctx.mob().getY());
        double popZ = ctx.state().getDoubleOr("decoy_z", ctx.mob().getZ());
        level.sendParticles(ParticleTypes.END_ROD, popX, popY + 0.6, popZ,
            16, 0.5, 0.5, 0.5, 0.08);
        level.playSound(null, popX, popY, popZ,
            SoundEvents.SHULKER_DEATH, SoundSource.HOSTILE, 0.8f, 1.1f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        if (ctx.state().contains("end_at")) {
            return ctx.ticksActive() >= ctx.state().getIntOr("end_at", DURATION_TICKS);
        }
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        var decoyList = ctx.state().getListOrEmpty("decoy");
        for (int i = 0; i < decoyList.size(); i++) {
            var entry = decoyList.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            java.util.UUID decoyId = entry.read("id", net.minecraft.core.UUIDUtil.CODEC).orElse(null);
            if (decoyId == null) {
                continue;
            }
            ctx.data().removeAlly(decoyId);
            if (ShulkerAbilitySupport.resolveTracked(level, entry) instanceof Shulker decoy) {
                MobWarData.get(decoy).removeAlly(caster.getUUID());
                if (decoy.isAlive()) {
                    level.sendParticles(ParticleTypes.END_ROD, decoy.getX(), decoy.getY() + 0.6, decoy.getZ(),
                        12, 0.5, 0.5, 0.5, 0.08);
                    decoy.discard();
                }
            }
        }
    }
}
