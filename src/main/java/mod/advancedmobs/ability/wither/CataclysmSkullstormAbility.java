package mod.advancedmobs.ability.wither;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.UUID;

/**
 * Cataclysm Skullstorm (Wither ability 1 of 5, attack).
 *
 * WHAT IT DOES: the wither rears all three heads and pours out
 * a rolling storm of real wither skulls, blue charged skulls
 * mixed among the black, each trailing SOUL wisps as the
 * barrage walks across the arena. The heads fire in sequence,
 * so the volley arrives in waves rather than one burst, and
 * the storm is dense enough that cover and distance are the
 * only real answers.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the wither's current target within 36 blocks (distance
 *   squared 1296). The wither is a flying boss; the storm is
 *   its long-range opener.
 * - The barrage: one skull every 6 ticks, 21 skulls total,
 *   fired on ticks 1, 7, 13, ... 121. Every third skull
 *   (firing indices 2, 5, 8, ...: 7 of the 21) is a blue
 *   charged skull (WitherSkull.setDangerous(true)); the rest
 *   are black. Each skull is aimed at the target's position at
 *   the moment it fires, with a random spread of plus or minus
 *   1.2 blocks horizontally and 0.5 vertically (see
 *   WitherAbilitySupport.launchSkull), so a target that keeps
 *   moving gets walked on instead of pinned. If the target
 *   dies or is lost mid-storm, the remaining skulls are not
 *   fired (there is nothing to aim at) and the ability winds
 *   down on the tracked skulls alone.
 * - The skulls themselves are vanilla projectiles owned by the
 *   wither: a direct entity hit deals the vanilla skull damage
 *   and applies WITHER (Wither II from a charged skull), and
 *   every impact explodes with power 1, breaking blocks only
 *   when the vanilla mobGriefing rule allows it (the skull
 *   checks the rule itself). This ability adds exactly one
 *   thing on top of a skull's own impact: the withering burst
 *   below.
 * - Tracking and the withering burst: every fired skull is
 *   recorded in the state tag with its last known position.
 *   Every 2 ticks each tracked skull is resolved: while it
 *   lives, its position is refreshed and it trails 1 SOUL
 *   particle (only on every second poll, so one wisp every 4
 *   ticks of flight). When a skull is gone (it impacted, or
 *   was removed), the burst fires at its last known position:
 *   1 EXPLOSION particle and 4 SOUL particles, and every enemy
 *   (WitherAbilitySupport rules) within radius 2.5 of that
 *   point, between 2 below and 2 above it, gains WITHER for
 *   100 ticks (5 seconds), amplifier 1 if the lost skull was
 *   charged, 0 if it was black. This is what "impacts leave
 *   enemies withering" means even when the skull hits the
 *   ground beside them instead of hitting them. A skull still
 *   tracked after 30 polls (60 ticks of flight) is dropped
 *   from tracking with no burst: it has left the fight.
 * - Duration: firing ends at tick 121; the ability finishes
 *   when the tracking list empties after that, with a hard
 *   finish at tick 181 (just over 9 seconds) so a stray skull
 *   can never hold the activation open.
 *
 * WHAT IT LOOKS AND SOUNDS LIKE: a WITHER_AMBIENT roar
 * (volume 1.5, pitch 0.8) opens the storm. Every skull launch
 * plays WITHER_SHOOT (volume 1.0, pitch wandering between 0.9
 * and 1.1) with a 3 SOUL wisp burst at the muzzle. In flight
 * each skull trails SOUL wisps on top of its vanilla smoke.
 * Impacts flash EXPLOSION and SOUL at the burst point.
 *
 * PERFORMANCE: firing is one projectile spawn every 6 ticks.
 * Tracking polls every 2 ticks resolve at most 21 UUIDs (the
 * whole barrage) and run at most one radius 2.5 entity query
 * per skull lost that poll. No per-tick scans, no allocations
 * in the poll path beyond the rebuilt tracking list. Particle
 * budget per activation: 63 at the muzzles, at most 15 trail
 * wisps per skull times 21 = 315, at most 5 per burst times
 * 21 = 105, plus the opening roar's share: total at most 490
 * particles.
 *
 * State tag keys: "fired" (skulls launched so far), "skulls"
 * (list of compounds, one per skull still in flight: "id" the
 * UUID, "x" "y" "z" the last known position, "d" 1 when the
 * skull is charged, "p" the poll count).
 */
public final class CataclysmSkullstormAbility implements MobAbility {

    private static final int FIRE_INTERVAL = 6;
    private static final int TOTAL_SKULLS = 21;
    private static final int LAST_FIRE_TICK = 121;
    private static final int HARD_FINISH_TICKS = 181;
    private static final int POLL_INTERVAL = 2;
    private static final int MAX_POLLS = 30;
    private static final double TRIGGER_RANGE_SQR = 1296.0;

    @Override
    public String id() {
        return "cataclysm_skullstorm";
    }

    @Override
    public String displayName() {
        return "Cataclysm Skullstorm";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= TRIGGER_RANGE_SQR;
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().putInt("fired", 0);
        ctx.state().put("skulls", new ListTag());
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            Mob mob = ctx.mob();
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.WITHER_AMBIENT, SoundSource.HOSTILE, 1.5f, 0.8f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        CompoundTag state = ctx.state();

        // Firing: one skull every 6 ticks until the barrage is spent.
        int fired = state.getIntOr("fired", 0);
        if (fired < TOTAL_SKULLS && ticks % FIRE_INTERVAL == 1) {
            LivingEntity target = ctx.target();
            if (target != null) {
                boolean dangerous = fired % 3 == 2;
                Entity skull = WitherAbilitySupport.launchSkull(ctx, level, target, 1.2, dangerous);
                if (skull != null) {
                    CompoundTag entry = new CompoundTag();
                    entry.store("id", UUIDUtil.CODEC, skull.getUUID());
                    entry.putDouble("x", skull.getX());
                    entry.putDouble("y", skull.getY());
                    entry.putDouble("z", skull.getZ());
                    entry.putInt("d", dangerous ? 1 : 0);
                    entry.putInt("p", 0);
                    state.getListOrEmpty("skulls").add(entry);
                    Mob mob = ctx.mob();
                    level.sendParticles(ParticleTypes.SOUL, skull.getX(), skull.getY(), skull.getZ(),
                        3, 0.3, 0.3, 0.3, 0.04);
                    level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                        SoundEvents.WITHER_SHOOT, SoundSource.HOSTILE, 1.0f,
                        0.9f + ctx.random().nextFloat() * 0.2f);
                }
                state.putInt("fired", fired + 1);
            }
        }

        if (ticks % POLL_INTERVAL != 0) {
            return; // Cheap early-out: skull tracking polls every 2 ticks only.
        }
        pollSkulls(ctx, level, state);
    }

    /**
     * One tracking poll (see the class comment): live skulls
     * refresh their recorded position and trail wisps, lost
     * skulls fire their withering burst, and skulls tracked too
     * long are dropped silently.
     */
    private void pollSkulls(AbilityContext ctx, ServerLevel level, CompoundTag state) {
        ListTag tracked = state.getListOrEmpty("skulls");
        if (tracked.isEmpty()) {
            return;
        }
        ListTag survivors = new ListTag();
        for (int i = 0; i < tracked.size(); i++) {
            CompoundTag entry = tracked.getCompoundOrEmpty(i);
            if (!entry.contains("id")) {
                continue;
            }
            UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
            Entity skull = id != null ? level.getEntity(id) : null;
            int polls = entry.getIntOr("p", 0) + 1;
            if (skull != null && skull.isAlive()) {
                entry.putDouble("x", skull.getX());
                entry.putDouble("y", skull.getY());
                entry.putDouble("z", skull.getZ());
                entry.putInt("p", polls);
                if (polls <= MAX_POLLS) {
                    survivors.add(entry);
                    if (polls % 2 == 0) {
                        level.sendParticles(ParticleTypes.SOUL, skull.getX(), skull.getY(), skull.getZ(),
                            1, 0.15, 0.15, 0.15, 0.02);
                    }
                }
                continue;
            }
            // The skull is gone: its impact burst goes off at the
            // last position it was seen.
            double x = entry.getDoubleOr("x", 0.0);
            double y = entry.getDoubleOr("y", 0.0);
            double z = entry.getDoubleOr("z", 0.0);
            level.sendParticles(ParticleTypes.EXPLOSION, x, y, z, 1, 0.1, 0.1, 0.1, 0.0);
            level.sendParticles(ParticleTypes.SOUL, x, y, z, 4, 0.5, 0.5, 0.5, 0.05);
            int amplifier = entry.getIntOr("d", 0) != 0 ? 1 : 0;
            for (LivingEntity victim : WitherAbilitySupport.enemiesInBox(ctx, x, y, z, 2.5, 2.0, 2.0)) {
                victim.addEffect(new MobEffectInstance(MobEffects.WITHER, 100, amplifier));
            }
        }
        state.put("skulls", survivors);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks >= HARD_FINISH_TICKS) {
            return true;
        }
        // Done once the barrage is fully fired (or the target was
        // lost for good) and no skull is still being tracked.
        return ticks > LAST_FIRE_TICK && ctx.state().getListOrEmpty("skulls").isEmpty();
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to clean up: the skulls are independent vanilla
        // projectiles that resolve on their own, and the tracking
        // list dies with the state tag.
    }
}
