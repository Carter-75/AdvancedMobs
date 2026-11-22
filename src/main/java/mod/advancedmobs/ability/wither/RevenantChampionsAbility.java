package mod.advancedmobs.ability.wither;

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
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.TeamColor;

import java.util.Optional;
import java.util.UUID;

/**
 * Revenant Champions (Wither ability 5 of 5, summon).
 *
 * WHAT IT DOES: three wither skeletons claw up out of the
 * soil as the wither's named honor guard, glowing with its
 * team color and geared for their roles: a blade, a bow, and
 * a blade-and-shield aegis. While any champion stands, a beam
 * of SOUL wisps links them to the wither and the wither takes
 * reduced damage; when the last champion falls, the wither
 * screams and fires one desperate skull volley at its enemy.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the wither's current target within 24 blocks (distance
 *   squared 576).
 * - Duration: exactly 600 ticks (30 seconds) from start(), or
 *   30 ticks after the last champion falls and the desperate
 *   volley is spent, whichever comes first.
 * - The champions (start()): three vanilla wither skeletons,
 *   created with EntitySpawnReason.MOB_SUMMONED and
 *   finalizeSpawn like every other summon in this mod, at
 *   offsets 1.8 blocks left, 1.8 right, and 2.0 behind the
 *   wither (or at the wither's own position when an offset
 *   spot is not collision-free). Gear and names:
 *     slot 0, "Revenant Blade": a stone sword.
 *     slot 1, "Revenant Bow": a bow (a wither skeleton holding
 *       a bow uses its skeleton ranged AI, per vanilla
 *       AbstractSkeleton weapon reassessment).
 *     slot 2, "Revenant Aegis": a stone sword and a shield in
 *       the off hand.
 *   Names are set as custom names and shown. Both equipment
 *   slots have drop chance 0, so the guard's gear is never
 *   farmed from their corpses. Each champion carries STRENGTH
 *   amplifier 0 and HEALTH_BOOST amplifier 0 for 620 ticks
 *   (applied before the first heal check, and the health boost
 *   arrives full: each champion is healed to its new max of
 *   24). Their fighting beyond that is vanilla wither
 *   skeleton behavior, including the wither-on-hit sword.
 * - The team color: the champions join a scoreboard team
 *   named "advancedmobs_revenants", colored DARK_PURPLE (26.2
 *   colors teams with the TeamColor enum), created on first
 *   use and reused after. Their GLOWING, refreshed every 20
 *   ticks for 30 ticks, renders in that purple. The wither
 *   itself does not join the team (nothing about its own
 *   targeting or glow should change); the team exists to
 *   carry the champions' glow color. end() takes the
 *   champions back off the team and removes the team itself
 *   when it has no members left, so a second wither running
 *   its own guard at the same time keeps its color.
 * - Team assignment, per champion, both directions plus the
 *   registry: the caster's data gains the champion as an ally,
 *   the champion's data gains the caster, and the pair is
 *   registered in SummonerTracker (which also tags the
 *   champion umw_summoned for the natural spawn blocker, the
 *   same flow Fallen Captain uses). Each champion is ordered
 *   onto the caster's current target at spawn.
 * - Babysitting every 5 ticks: a living champion whose target
 *   is empty or is the caster is re-ordered onto the caster's
 *   live target.
 * - The link: while at least one champion lives, checked every
 *   10 ticks, the wither carries RESISTANCE amplifier 0 (20
 *   percent damage reduction, the design's "damage reduction
 *   in code": the framework has no damage hook an ability can
 *   sit on, so the reduction rides the vanilla resistance the
 *   code refreshes only while a champion stands, and drops
 *   the moment none do). The beam itself, every 10 ticks on
 *   the same pass: 2 SOUL particles strung between the
 *   wither's chest and each living champion's chest (at the
 *   one-third and two-thirds points of the line).
 * - The fall of the guard: the first pass that finds no
 *   champion alive records the tick and fires the scream
 *   (WITHER_AMBIENT, volume 2.0, pitch 0.6) at once. The
 *   desperate volley follows: 5 skulls, one every 4 ticks,
 *   the 2nd and 4th of them charged (blue), launched at the
 *   wither's live target through the shared skull launcher
 *   with a tighter 0.8 spread. If the wither has no target,
 *   the volley waits (it is not wasted into the ground) and
 *   the ability still ends on its 30 tick fuse or the hard
 *   duration.
 * - end(): the alliance records are removed on both sides for
 *   every champion, the champions leave the scoreboard team
 *   (and the team is removed when empty), the wither's
 *   RESISTANCE is removed, and each champion still alive is
 *   discarded in a puff of 8 SOUL particles: the guard is the
 *   ability's, and it does not outlive it. Champions that died
 *   in the fight are simply gone. If the caster dies first,
 *   the framework termination path runs this same end(), so
 *   the guard never outlives its summoner either.
 *   SummonerTracker records are deliberately not unregistered
 *   here, matching Fallen Captain: that registry cleans up on
 *   the champion's own removal.
 *
 * WHAT IT LOOKS AND SOUNDS LIKE: the guard rises with a
 * WITHER_SKELETON_AMBIENT bellow (volume 1.2, pitch 0.6) and
 * 12 SOUL particles per champion at their arrival spots.
 * Purple-glowing named skeletons, SOUL wisps streaming to the
 * wither every half second while the link holds. The last
 * fall is answered by the wither's scream and five skulls.
 *
 * PERFORMANCE: babysitting resolves at most 3 UUIDs every 5
 * ticks; the link pass every 10 ticks resolves the same 3 and
 * runs no entity query; the glow refresh runs every 20 ticks.
 * The volley is one projectile spawn every 4 ticks while it
 * lasts. No entity scans anywhere. Particle budget per
 * activation: 36 at start, beams at most 60 passes times 3
 * champions times 2 = 360, end puffs at most 3 times 8 = 24,
 * total at most 420 particles.
 *
 * State tag keys: "champions" (list of compounds, one per
 * champion: "id" the champion's UUID), "dead_tick" (the tick
 * the last champion was seen fallen, or -1 while any lives),
 * "volley_left" (skulls still owed by the desperate volley),
 * "screamed" (1 once the scream and volley have begun).
 */
public final class RevenantChampionsAbility implements MobAbility {

    static final int DURATION_TICKS = 600;
    private static final int BABYSIT_INTERVAL = 5;
    private static final int LINK_INTERVAL = 10;
    private static final int GLOW_INTERVAL = 20;
    private static final int VOLLEY_INTERVAL = 4;
    private static final int VOLLEY_SKULLS = 5;
    private static final int END_AFTER_FALL_TICKS = 30;
    private static final String TEAM_NAME = "advancedmobs_revenants";
    private static final String[] NAMES = {"Revenant Blade", "Revenant Bow", "Revenant Aegis"};
    private static final double[][] OFFSETS = {
        {-1.8, 0.3}, {1.8, 0.3}, {0.0, -2.0}
    };

    @Override
    public String id() {
        return "revenant_champions";
    }

    @Override
    public String displayName() {
        return "Revenant Champions";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 576.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        CompoundTag state = ctx.state();
        state.put("champions", new ListTag());
        state.putInt("dead_tick", -1);
        state.putInt("volley_left", 0);
        state.putInt("screamed", 0);
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        LivingEntity target = ctx.target();
        ListTag champions = state.getListOrEmpty("champions");
        for (int i = 0; i < NAMES.length; i++) {
            WitherSkeleton champion = spawnChampion(ctx, level, i);
            if (champion == null) {
                continue;
            }
            // Team assignment, both directions, plus the registry.
            ctx.data().addAlly(champion.getUUID());
            MobWarData.get(champion).addAlly(caster.getUUID());
            SummonerTracker.registerSummoned(champion.getUUID(), caster.getUUID());
            if (target != null) {
                champion.setTarget(target);
            }
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, champion.getUUID());
            champions.add(entry);
            level.sendParticles(ParticleTypes.SOUL, champion.getX(), champion.getY() + 1.2, champion.getZ(),
                12, 0.6, 0.9, 0.6, 0.06);
        }
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.WITHER_SKELETON_AMBIENT, SoundSource.HOSTILE, 1.2f, 0.6f);
    }

    /**
     * Creates one geared, named champion (see the class comment
     * for the three loadouts), or null when the spawn failed.
     */
    private WitherSkeleton spawnChampion(AbilityContext ctx, ServerLevel level, int slot) {
        Mob caster = ctx.mob();
        WitherSkeleton champion = EntityTypes.WITHER_SKELETON.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (champion == null) {
            return null;
        }
        double spawnX = caster.getX() + OFFSETS[slot][0];
        double spawnZ = caster.getZ() + OFFSETS[slot][1];
        if (!isFreeSpot(level, champion, spawnX, caster.getY(), spawnZ)) {
            spawnX = caster.getX();
            spawnZ = caster.getZ();
        }
        champion.snapTo(spawnX, caster.getY(), spawnZ, ctx.random().nextFloat() * 360.0f, 0.0f);
        champion.finalizeSpawn(level, level.getCurrentDifficultyAt(champion.blockPosition()),
            EntitySpawnReason.MOB_SUMMONED, null);
        if (!level.addFreshEntity(champion)) {
            return null;
        }

        // Gear and name, after finalizeSpawn so the fortress
        // loadout does not overwrite the guard's.
        switch (slot) {
            case 1 -> champion.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
            case 2 -> {
                champion.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_SWORD));
                champion.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            }
            default -> champion.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_SWORD));
        }
        champion.setDropChance(EquipmentSlot.MAINHAND, 0.0f);
        champion.setDropChance(EquipmentSlot.OFFHAND, 0.0f);
        champion.setCustomName(Component.literal(NAMES[slot]));
        champion.setCustomNameVisible(true);

        // The guard's blessing: strength and a deeper health
        // pool, the extra health arriving full.
        champion.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 620, 0));
        champion.addEffect(new MobEffectInstance(MobEffects.HEALTH_BOOST, 620, 0));
        champion.setHealth(champion.getMaxHealth());
        champion.addEffect(new MobEffectInstance(MobEffects.GLOWING, 30, 0, false, false, false));

        // The team color for the glow (see the class comment).
        Scoreboard scoreboard = level.getScoreboard();
        PlayerTeam team = scoreboard.getPlayerTeam(TEAM_NAME);
        if (team == null) {
            team = scoreboard.addPlayerTeam(TEAM_NAME);
            team.setColor(Optional.of(TeamColor.DARK_PURPLE));
        }
        scoreboard.addPlayerToTeam(champion.getScoreboardName(), team);
        return champion;
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        CompoundTag state = ctx.state();

        if (ticks % BABYSIT_INTERVAL == 0) {
            babysit(ctx, level, state);
        }
        if (ticks % LINK_INTERVAL == 0) {
            linkPass(ctx, level, state, ticks);
        }
        if (ticks % GLOW_INTERVAL == 0) {
            refreshGlow(ctx, level, state);
        }
        fireVolley(ctx, level, state, ticks);
    }

    /** Babysitting: keep every living champion on the caster's fight. */
    private void babysit(AbilityContext ctx, ServerLevel level, CompoundTag state) {
        LivingEntity desired = ctx.target();
        for (WitherSkeleton champion : livingChampions(level, state)) {
            LivingEntity championTarget = champion.getTarget();
            if ((championTarget == null || championTarget == ctx.mob()) && desired != null && desired != champion) {
                champion.setTarget(desired);
            }
        }
    }

    /**
     * The link pass (see the class comment): while a champion
     * stands, the wither is braced and the soul beams show it;
     * the pass that finds the guard fallen starts the scream
     * and the desperate volley.
     */
    private void linkPass(AbilityContext ctx, ServerLevel level, CompoundTag state, int ticks) {
        java.util.List<WitherSkeleton> living = livingChampions(level, state);
        Mob mob = ctx.mob();
        if (!living.isEmpty()) {
            mob.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 20, 0, false, false, false));
            double cx = mob.getX();
            double cy = mob.getY() + mob.getBbHeight() * 0.6;
            double cz = mob.getZ();
            for (WitherSkeleton champion : living) {
                double hx = champion.getX();
                double hy = champion.getY() + champion.getBbHeight() * 0.6;
                double hz = champion.getZ();
                level.sendParticles(ParticleTypes.SOUL,
                    cx + (hx - cx) / 3.0, cy + (hy - cy) / 3.0, cz + (hz - cz) / 3.0,
                    1, 0.1, 0.1, 0.1, 0.02);
                level.sendParticles(ParticleTypes.SOUL,
                    cx + (hx - cx) * 2.0 / 3.0, cy + (hy - cy) * 2.0 / 3.0, cz + (hz - cz) * 2.0 / 3.0,
                    1, 0.1, 0.1, 0.1, 0.02);
            }
            return;
        }
        if (state.getIntOr("screamed", 0) == 0 && state.getIntOr("dead_tick", -1) < 0) {
            // The last champion has fallen: the scream, and the
            // volley is owed.
            state.putInt("screamed", 1);
            state.putInt("dead_tick", ticks);
            state.putInt("volley_left", VOLLEY_SKULLS);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.WITHER_AMBIENT, SoundSource.HOSTILE, 2.0f, 0.6f);
        }
    }

    /** The glow refresh: champions keep their purple outline while they stand. */
    private void refreshGlow(AbilityContext ctx, ServerLevel level, CompoundTag state) {
        for (WitherSkeleton champion : livingChampions(level, state)) {
            champion.addEffect(new MobEffectInstance(MobEffects.GLOWING, 30, 0, false, false, false));
        }
    }

    /** The desperate volley: one skull every 4 ticks while skulls are owed and a target exists. */
    private void fireVolley(AbilityContext ctx, ServerLevel level, CompoundTag state, int ticks) {
        int left = state.getIntOr("volley_left", 0);
        if (left <= 0 || ticks % VOLLEY_INTERVAL != 0) {
            return;
        }
        LivingEntity target = ctx.target();
        if (target == null) {
            return; // The volley waits for a target rather than wasting into the ground.
        }
        int firedIndex = VOLLEY_SKULLS - left;
        boolean dangerous = firedIndex == 1 || firedIndex == 3;
        if (WitherAbilitySupport.launchSkull(ctx, level, target, 0.8, dangerous) != null) {
            Mob mob = ctx.mob();
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.WITHER_SHOOT, SoundSource.HOSTILE, 1.2f, 0.9f);
        }
        state.putInt("volley_left", left - 1);
    }

    /** All champions from the state tag that still exist and live, in spawn order. */
    private java.util.List<WitherSkeleton> livingChampions(ServerLevel level, CompoundTag state) {
        java.util.List<WitherSkeleton> living = new java.util.ArrayList<>();
        ListTag champions = state.getListOrEmpty("champions");
        for (int i = 0; i < champions.size(); i++) {
            CompoundTag entry = champions.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (id != null && level.getEntity(id) instanceof WitherSkeleton champion && champion.isAlive()) {
                living.add(champion);
            }
        }
        return living;
    }

    /** Whether the champion's bounding box at the given feet position collides with nothing. */
    private boolean isFreeSpot(ServerLevel level, WitherSkeleton champion, double x, double y, double z) {
        double halfWidth = champion.getBbWidth() / 2.0;
        AABB box = new AABB(x - halfWidth, y, z - halfWidth, x + halfWidth, y + champion.getBbHeight(), z + halfWidth);
        return level.noCollision(champion, box);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        if (ctx.ticksActive() >= DURATION_TICKS) {
            return true;
        }
        CompoundTag state = ctx.state();
        int deadTick = state.getIntOr("dead_tick", -1);
        return deadTick >= 0 && state.getIntOr("volley_left", 0) <= 0
            && ctx.ticksActive() >= deadTick + END_AFTER_FALL_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        caster.removeEffect(MobEffects.RESISTANCE);
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Scoreboard scoreboard = level.getScoreboard();
        ListTag champions = ctx.state().getListOrEmpty("champions");
        for (int i = 0; i < champions.size(); i++) {
            CompoundTag entry = champions.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID championId = entry.read("id", UUIDUtil.CODEC).orElse(null);
            if (championId == null) {
                continue;
            }
            // The alliance ends with the ability, whichever side
            // is still here.
            ctx.data().removeAlly(championId);
            Entity entity = level.getEntity(championId);
            if (entity instanceof WitherSkeleton champion) {
                MobWarData.get(champion).removeAlly(caster.getUUID());
                scoreboard.removePlayerFromTeam(champion.getScoreboardName());
                if (champion.isAlive()) {
                    // The guard sinks back into the soil it rose from.
                    level.sendParticles(ParticleTypes.SOUL, champion.getX(), champion.getY() + 1.0, champion.getZ(),
                        8, 0.5, 0.8, 0.5, 0.05);
                    champion.discard();
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
