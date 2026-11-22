package mod.advancedmobs.ability.evoker;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.TeamColor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Crowned Vex Vanguard (Evoker ability 4 of 5, summon).
 *
 * WHAT IT DOES: the evoker summons his honor guard properly
 * this time. Four vexes wearing tiny golden helmets, each
 * one named, each one glowing with his court's gold, take
 * up an orbiting guard ring around him instead of charging
 * off the way wild vexes do. They hold the ring, savage
 * anything that crosses it, and only chase a wounded enemy
 * that tries to flee the circle. Approaching the evoker
 * becomes a fight in itself.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the evoker's current target within 20 blocks (distance
 *   squared 400).
 * - The guard (start()): 4 vexes, spawned at ring slots of
 *   radius 3.0 around the evoker (angles 0, 90, 180, 270
 *   degrees), created with EntitySpawnReason.MOB_SUMMONED
 *   and finalizeSpawn like every other summon in this mod
 *   (which arms each with the vanilla iron sword; a vex's
 *   damage is its own, unmodified). Each guard then gets
 *   its crown and its name: a golden helmet in the HEAD
 *   slot with its drop chance set to 0, and a custom name
 *   from the court roll (Aurel, Gilt, Regent, Sovereign, in
 *   slot order), name visible. Team assignment, per guard,
 *   both directions plus the registry: the caster's data
 *   gains the guard as an ally, the guard's data gains the
 *   caster, and the pair is registered in SummonerTracker
 *   (which also tags the guard umw_summoned for the natural
 *   spawn blocker). Each guard joins the scoreboard team
 *   "advancedmobs_evoker_court" (created on first use, team
 *   color GOLD) so its GLOWING outline burns gold, the
 *   design's "glowing with his team color" (26.2 colors
 *   glow outlines by scoreboard team; the Revenant
 *   Champions pattern). GLOWING is applied for 30 ticks at
 *   spawn and refreshed every 20 ticks for the whole
 *   activation. EVOKER_PREPARE_SUMMON plays at the summon.
 * - The ring (tick(), every 2 ticks): guards WITHOUT a
 *   target are steered by their move control to their orbit
 *   slot: radius 3.0 around the evoker's live position, at
 *   his height plus 0.3, the slot angle advancing 0.03
 *   radians per tick so the whole ring slowly wheels around
 *   him, at speed 1.3. Guards WITH a target are left to
 *   vanilla vex flight entirely; steering a fighting vex
 *   would fight its own AI.
 * - The savaging (tick(), every 5 ticks): one bounded query
 *   finds the nearest enemy within 4.5 blocks of the evoker
 *   (an enemy crossing the ring). Every guard without a
 *   target is ordered onto it. A guard's existing target is
 *   then audited: it keeps the target while the target is
 *   within 9 blocks of the evoker, OR while the target is
 *   wounded (health at or below 30 percent of its maximum)
 *   and within 17 blocks of the evoker, which is the
 *   design's one allowed pursuit: a fleeing wounded enemy.
 *   A target outside both leashes is cleared, and the guard
 *   falls back to the ring on the next steering pass.
 * - The pop (tick(), every 5 ticks, same pass): a tracked
 *   guard that no longer resolves, or resolves dead, is
 *   marked popped and bursts once at its last recorded
 *   position: 6 POOF and a VEX_HURT at pitch 1.4, and the
 *   caster's ally record for it is removed. A guard that
 *   unloads with its chunk reads as gone the same way (the
 *   Mirage Split edge case, shared by every tracked summon
 *   in this mod).
 * - Duration: 600 ticks (30 seconds), hard finish.
 * - end(): each surviving guard is discarded in 6 POOF,
 *   the alliance records are removed on both sides, each
 *   guard is removed from the court team, and the team is
 *   removed only when it stands empty (it is shared
 *   infrastructure while anyone uses it, the Revenant
 *   Champions pattern). If the evoker dies first, the
 *   framework termination path runs this same end(), so the
 *   guard never outlives its summoner. SummonerTracker
 *   records are deliberately not unregistered, matching the
 *   other summons in this mod.
 *
 * WHAT IT LOOKS LIKE: a chant, and four little crowned
 * horrors take their stations around the evoker, gold
 * helmets catching the light, gold outlines burning through
 * walls, the whole ring slowly wheeling as he moves. Cross
 * the circle and all four are on you at once; run wounded
 * and they follow just long enough to finish it.
 *
 * PERFORMANCE: tick() steering runs every 2nd tick and
 * resolves at most 4 UUIDs with at most 4 move-control
 * writes; the savage audit runs every 5th tick with ONE
 * bounded enemy query plus at most 4 target reads; the glow
 * refresh runs every 20th tick. No whole-level scans.
 * Particle budget per activation: 8 at start, pops at most
 * 4 times 6 = 24, end() puffs at most 4 times 6 = 24, total
 * at most 56 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "guards" (list of compounds, one per
 * guard: "id" the guard's UUID, "slot" its ring slot 0 to
 * 3, "x", "y", "z" its last recorded position, "popped" 1
 * once its pop burst ran).
 */
public final class CrownedVexVanguardAbility implements MobAbility {

    private static final int DURATION_TICKS = 600;
    private static final int STEER_INTERVAL = 2;
    private static final int AUDIT_INTERVAL = 5;
    private static final int GLOW_INTERVAL = 20;
    private static final double RING_RADIUS = 3.0;
    private static final double ORBIT_SPEED = 0.03;
    private static final double CROSSING_RADIUS = 4.5;
    private static final double LEASH_RADIUS = 9.0;
    private static final double PURSUIT_RADIUS = 17.0;
    private static final float WOUNDED_FRACTION = 0.30f;
    private static final String TEAM_NAME = "advancedmobs_evoker_court";
    private static final String[] NAMES = {"Aurel", "Gilt", "Regent", "Sovereign"};

    @Override
    public String id() {
        return "crowned_vex_vanguard";
    }

    @Override
    public String displayName() {
        return "Crowned Vex Vanguard";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 400.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();
        ListTag guards = new ListTag();
        ctx.state().put("guards", guards);
        if (level == null) {
            return;
        }

        Scoreboard scoreboard = level.getScoreboard();
        PlayerTeam team = scoreboard.getPlayerTeam(TEAM_NAME);
        if (team == null) {
            team = scoreboard.addPlayerTeam(TEAM_NAME);
            team.setColor(Optional.of(TeamColor.GOLD));
        }

        for (int slot = 0; slot < NAMES.length; slot++) {
            Vex guard = EntityTypes.VEX.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (guard == null) {
                continue;
            }
            double angle = slot * (Math.PI / 2.0);
            guard.snapTo(caster.getX() + Math.cos(angle) * RING_RADIUS, caster.getY() + 0.3,
                caster.getZ() + Math.sin(angle) * RING_RADIUS, caster.getYRot(), 0.0f);
            guard.finalizeSpawn(level, level.getCurrentDifficultyAt(guard.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
            // The crown and the name (see the class comment).
            guard.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET));
            guard.setDropChance(EquipmentSlot.HEAD, 0.0f);
            guard.setCustomName(Component.literal(NAMES[slot]));
            guard.setCustomNameVisible(true);
            if (!level.addFreshEntity(guard)) {
                continue;
            }
            ctx.data().addAlly(guard.getUUID());
            MobWarData.get(guard).addAlly(caster.getUUID());
            SummonerTracker.registerSummoned(guard.getUUID(), caster.getUUID());
            scoreboard.addPlayerToTeam(guard.getScoreboardName(), team);
            guard.addEffect(new MobEffectInstance(MobEffects.GLOWING, 30, 0, false, false, false));

            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, guard.getUUID());
            entry.putInt("slot", slot);
            entry.putDouble("x", guard.getX());
            entry.putDouble("y", guard.getY());
            entry.putDouble("z", guard.getZ());
            entry.putInt("popped", 0);
            guards.add(entry);
        }
        ctx.state().put("guards", guards);
        level.sendParticles(ParticleTypes.POOF, caster.getX(), caster.getY() + 1.0, caster.getZ(),
            8, 0.8, 0.6, 0.8, 0.05);
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.EVOKER_PREPARE_SUMMON, SoundSource.HOSTILE, 1.2f, 1.0f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        if (ticks % AUDIT_INTERVAL == 0) {
            audit(ctx, level);
        }
        if (ticks % STEER_INTERVAL == 0) {
            steer(ctx, level, ticks);
        }
        if (ticks % GLOW_INTERVAL == 0) {
            refreshGlow(ctx, level);
        }
    }

    /** The savage audit: ring crossings are ordered onto idle guards; leashes are enforced. */
    private static void audit(AbilityContext ctx, ServerLevel level) {
        Mob caster = ctx.mob();
        // One query for the whole pass: the nearest enemy
        // crossing the ring right now.
        List<LivingEntity> crossers = EvokerAbilitySupport.enemiesInBox(ctx,
            caster.getX(), caster.getY(), caster.getZ(), CROSSING_RADIUS, 2.0, 2.0);
        LivingEntity nearest = null;
        double nearestSqr = Double.MAX_VALUE;
        for (LivingEntity crosser : crossers) {
            double distSqr = crosser.distanceToSqr(caster);
            if (distSqr < nearestSqr) {
                nearestSqr = distSqr;
                nearest = crosser;
            }
        }

        ListTag guards = ctx.state().getListOrEmpty("guards");
        for (int i = 0; i < guards.size(); i++) {
            CompoundTag entry = guards.getCompoundOrEmpty(i);
            if (entry.getIntOr("popped", 0) == 1) {
                continue;
            }
            Entity found = EvokerAbilitySupport.resolveTracked(level, entry);
            if (found instanceof Vex guard && guard.isAlive()) {
                entry.putDouble("x", guard.getX());
                entry.putDouble("y", guard.getY());
                entry.putDouble("z", guard.getZ());
                LivingEntity guardTarget = guard.getTarget();
                if (guardTarget != null && guardTarget.isAlive()) {
                    // The leashes (see the class comment): near
                    // the evoker always, far only for the wounded.
                    double fromCaster = guardTarget.distanceToSqr(caster);
                    boolean near = fromCaster <= LEASH_RADIUS * LEASH_RADIUS;
                    boolean pursuit = guardTarget.getHealth() <= guardTarget.getMaxHealth() * WOUNDED_FRACTION
                        && fromCaster <= PURSUIT_RADIUS * PURSUIT_RADIUS;
                    if (!near && !pursuit) {
                        guard.setTarget(null);
                    }
                } else if (nearest != null) {
                    guard.setTarget(nearest);
                }
            } else {
                popGuard(ctx, level, entry);
            }
            guards.set(i, entry);
        }
        ctx.state().put("guards", guards);
    }

    /** The ring: idle guards are steered to their wheeling orbit slots. */
    private static void steer(AbilityContext ctx, ServerLevel level, int ticks) {
        Mob caster = ctx.mob();
        double wheel = ticks * ORBIT_SPEED;
        ListTag guards = ctx.state().getListOrEmpty("guards");
        for (int i = 0; i < guards.size(); i++) {
            CompoundTag entry = guards.getCompoundOrEmpty(i);
            if (entry.getIntOr("popped", 0) == 1) {
                continue;
            }
            Entity found = EvokerAbilitySupport.resolveTracked(level, entry);
            if (found instanceof Vex guard && guard.isAlive() && guard.getTarget() == null) {
                int slot = entry.getIntOr("slot", 0);
                double angle = wheel + slot * (Math.PI / 2.0);
                guard.getMoveControl().setWantedPosition(
                    caster.getX() + Math.cos(angle) * RING_RADIUS,
                    caster.getY() + 0.3,
                    caster.getZ() + Math.sin(angle) * RING_RADIUS, 1.3);
            }
        }
    }

    /** The gold outline, refreshed for every living guard. */
    private static void refreshGlow(AbilityContext ctx, ServerLevel level) {
        ListTag guards = ctx.state().getListOrEmpty("guards");
        for (int i = 0; i < guards.size(); i++) {
            CompoundTag entry = guards.getCompoundOrEmpty(i);
            if (entry.getIntOr("popped", 0) == 1) {
                continue;
            }
            Entity found = EvokerAbilitySupport.resolveTracked(level, entry);
            if (found instanceof Vex guard && guard.isAlive()) {
                guard.addEffect(new MobEffectInstance(MobEffects.GLOWING, 30, 0, false, false, false));
            }
        }
    }

    /** The pop burst for one destroyed guard (see the class comment). Runs once per guard. */
    private static void popGuard(AbilityContext ctx, ServerLevel level, CompoundTag entry) {
        entry.putInt("popped", 1);
        UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
        if (id != null) {
            ctx.data().removeAlly(id);
        }
        double x = entry.getDoubleOr("x", ctx.mob().getX());
        double y = entry.getDoubleOr("y", ctx.mob().getY());
        double z = entry.getDoubleOr("z", ctx.mob().getZ());
        level.sendParticles(ParticleTypes.POOF, x, y + 0.4, z, 6, 0.25, 0.3, 0.25, 0.05);
        level.playSound(null, x, y, z, SoundEvents.VEX_HURT, SoundSource.HOSTILE, 0.8f, 1.4f);
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
        Scoreboard scoreboard = level.getScoreboard();
        ListTag guards = ctx.state().getListOrEmpty("guards");
        for (int i = 0; i < guards.size(); i++) {
            CompoundTag entry = guards.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id == null) {
                continue;
            }
            ctx.data().removeAlly(id);
            Entity found = level.getEntity(id);
            if (found instanceof Vex guard) {
                MobWarData.get(guard).removeAlly(caster.getUUID());
                scoreboard.removePlayerFromTeam(guard.getScoreboardName());
                if (guard.isAlive()) {
                    level.sendParticles(ParticleTypes.POOF, guard.getX(), guard.getY() + 0.4, guard.getZ(),
                        6, 0.25, 0.3, 0.25, 0.05);
                    guard.discard();
                }
            }
        }
        // The team is shared infrastructure while anyone uses
        // it: it is removed only once it stands empty.
        PlayerTeam team = scoreboard.getPlayerTeam(TEAM_NAME);
        if (team != null && team.getPlayers().isEmpty()) {
            scoreboard.removePlayerTeam(team);
        }
    }
}
