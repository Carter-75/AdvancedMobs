package mod.advancedmobs.ability.sniffer;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.List;
import java.util.UUID;

/**
 * Ancient Seed Mortar (Sniffer ability 1 of 5, attack).
 *
 * WHAT IT DOES: the sniffer roots itself, digs its great
 * snout into the earth, and unearths something ancient:
 * three pitcher pods, lobbed one after another in high
 * arcs over the fight. Each pod bursts where it falls in
 * a spray of spores and a small explosion flash, hurting
 * everything hostile caught in the burst.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the sniffer's current target within 20 blocks
 *   (distance squared 400). The mortar outranges every
 *   other sniffer ability because the pods fly.
 * - The dig (ticks 1 to 20): the sniffer is planted via
 *   per-tick navigation stops (never NoAi) and digs:
 *   SNIFFER_DIGGING plays at start (volume 1.2, pitch
 *   0.9), and every 2 ticks 3 BLOCK_CRUMBLE motes of the
 *   ground state spray at the snout.
 * - The launches (ticks 25, 45, and 65; three pods per
 *   activation): each launch records its landing point at
 *   the moment of launch: the target's live position, or,
 *   if the target is gone, a point 8 blocks ahead of the
 *   sniffer's facing at the sniffer's own height. A pod is
 *   an item display of one pitcher pod (scale 1.2, full
 *   brightness, teleport duration 2 for glide) spawned at
 *   the sniffer's head. Its whole flight plan is stored in
 *   the state tag: start point, landing point, and flight
 *   ticks elapsed.
 * - The flight (22 ticks per pod): each tick the pod's
 *   flight counter advances and the display is snapped to
 *   the point lerped from start to landing by progress,
 *   with height added as sin(pi times progress) times the
 *   arc height, where the arc height is 4.0 plus 0.25 per
 *   block of horizontal distance (a 12 block shot arcs
 *   about 7 blocks high). The display's 2-tick teleport
 *   glide smooths the per-tick snaps into the design's
 *   interpolation arc.
 * - The burst (on the pod's 22nd flight tick): 1
 *   EXPLOSION particle and 8 SPORE_BLOSSOM_AIR motes at
 *   the landing point, GENERIC_EXPLODE at volume 0.7,
 *   pitch 1.2, and one bounded query (radius 2.5, 2 below
 *   and 2 above the landing point): every enemy in it
 *   takes 6.0 flat damage through a mob attack source
 *   credited to the sniffer (the sniffer has no
 *   ATTACK_DAMAGE instance; see the provider comment).
 *   The display is discarded. The burst is particles and
 *   code damage only: it is NOT a real explosion and
 *   breaks no blocks.
 * - Duration: hard cap 140 ticks; the ability finishes
 *   earlier once all 3 pods have burst and tick 90 has
 *   passed. end() discards any pod display still flying
 *   (a terminated mortar never leaves a pod hanging in
 *   the air) WITHOUT bursting it.
 *
 * WHAT IT LOOKS LIKE: twenty ticks of frantic digging,
 * dirt spraying off the snout, then green pods sailing
 * overhead one by one, each popping in a flash and a
 * cloud of pale spores where it lands.
 *
 * PERFORMANCE: the dig is one navigation stop per tick
 * and one particle send every 2 ticks. Flight work is at
 * most 3 display resolves and snaps per tick (pods are
 * staggered, so at most 2 fly at once in practice). The
 * only entity query is one bounded box per burst, 3 per
 * activation. Particle budget per activation: dig 10
 * sends times 3 = 30, bursts 3 times 9 = 27, total at
 * most 57.
 *
 * TERRAIN: the pods are display entities and the bursts
 * are particles plus code damage; nothing places or
 * breaks blocks, so the mobGriefing gate does not apply
 * anywhere in this ability, and this comment is the
 * statement of that.
 *
 * State tag keys: "launched" (pods launched so far),
 * "pods" (list of compounds, one per pod ever launched:
 * "id" the display UUID, "sx" "sy" "sz" the launch
 * point, "tx" "ty" "tz" the landing point, "ft" flight
 * ticks elapsed, "done" whether the pod has burst or
 * been discarded).
 */
public final class AncientSeedMortarAbility implements MobAbility {

    private static final int DURATION_TICKS = 140;
    private static final int DIG_TICKS = 20;
    private static final int FIRST_LAUNCH_TICK = 25;
    private static final int LAUNCH_INTERVAL = 20;
    private static final int MAX_PODS = 3;
    private static final int FLIGHT_TICKS = 22;
    private static final double BURST_RADIUS = 2.5;
    private static final float BURST_DAMAGE = 6.0f;

    @Override
    public String id() {
        return "ancient_seed_mortar";
    }

    @Override
    public String displayName() {
        return "Ancient Seed Mortar";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 400.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().putInt("launched", 0);
        ctx.state().put("pods", new ListTag());
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            Mob caster = ctx.mob();
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                SoundEvents.SNIFFER_DIGGING, SoundSource.HOSTILE, 1.2f, 0.9f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();

        if (ticks <= DIG_TICKS) {
            // Planted and digging (navigation stops, never NoAi).
            caster.getNavigation().stop();
            if (ticks % 2 == 0) {
                double snoutX = caster.getX() - Math.sin(Math.toRadians(caster.getYRot())) * 1.2;
                double snoutZ = caster.getZ() + Math.cos(Math.toRadians(caster.getYRot())) * 1.2;
                level.sendParticles(SnifferAbilitySupport.groundCrumble(level, snoutX, caster.getY(), snoutZ),
                    snoutX, caster.getY() + 0.3, snoutZ, 3, 0.3, 0.2, 0.3, 0.05);
            }
            return;
        }

        // Launches at ticks 25, 45, 65 until all three pods are away.
        int launched = ctx.state().getIntOr("launched", 0);
        if (launched < MAX_PODS && ticks >= FIRST_LAUNCH_TICK
            && (ticks - FIRST_LAUNCH_TICK) % LAUNCH_INTERVAL == 0) {
            launchPod(ctx, level);
        }

        advancePods(ctx, level);
    }

    /** Lobs one pod (class comment): records the flight plan in the state tag. */
    private void launchPod(AbilityContext ctx, ServerLevel level) {
        Mob caster = ctx.mob();
        Entity display = SnifferAbilitySupport.createPodDisplay(level, 1.2f);
        if (display == null) {
            // No pod could be created; count the launch as
            // spent so the ability still finishes on time.
            ctx.state().putInt("launched", ctx.state().getIntOr("launched", 0) + 1);
            return;
        }
        double startX = caster.getX();
        double startY = caster.getY() + caster.getBbHeight() + 0.3;
        double startZ = caster.getZ();
        display.snapTo(startX, startY, startZ, 0.0f, 0.0f);
        level.addFreshEntity(display);

        // The landing point is read at the moment of launch.
        double landX;
        double landY;
        double landZ;
        LivingEntity target = ctx.target();
        if (target != null) {
            landX = target.getX();
            landY = target.getY();
            landZ = target.getZ();
        } else {
            double yaw = Math.toRadians(caster.getYRot());
            landX = caster.getX() - Math.sin(yaw) * 8.0;
            landY = caster.getY();
            landZ = caster.getZ() + Math.cos(yaw) * 8.0;
        }

        CompoundTag pod = new CompoundTag();
        pod.store("id", UUIDUtil.CODEC, display.getUUID());
        pod.putDouble("sx", startX);
        pod.putDouble("sy", startY);
        pod.putDouble("sz", startZ);
        pod.putDouble("tx", landX);
        pod.putDouble("ty", landY);
        pod.putDouble("tz", landZ);
        pod.putInt("ft", 0);
        pod.putBoolean("done", false);
        ListTag pods = ctx.state().getListOrEmpty("pods");
        pods.add(pod);
        ctx.state().put("pods", pods);
        ctx.state().putInt("launched", ctx.state().getIntOr("launched", 0) + 1);

        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.SNIFFER_DROP_SEED, SoundSource.HOSTILE, 1.0f, 0.8f);
    }

    /** Advances every flying pod one tick along its arc; bursts pods that land. */
    private void advancePods(AbilityContext ctx, ServerLevel level) {
        ListTag pods = ctx.state().getListOrEmpty("pods");
        for (int i = 0; i < pods.size(); i++) {
            CompoundTag pod = pods.getCompoundOrEmpty(i);
            if (pod.getBooleanOr("done", true)) {
                continue;
            }
            int flight = pod.getIntOr("ft", 0) + 1;
            pod.putInt("ft", flight);
            double sx = pod.getDoubleOr("sx", 0.0);
            double sy = pod.getDoubleOr("sy", 0.0);
            double sz = pod.getDoubleOr("sz", 0.0);
            double tx = pod.getDoubleOr("tx", sx);
            double ty = pod.getDoubleOr("ty", sy);
            double tz = pod.getDoubleOr("tz", sz);

            Entity display = SnifferAbilitySupport.resolveTracked(level, pod);
            if (flight >= FLIGHT_TICKS) {
                pod.putBoolean("done", true);
                if (display != null) {
                    display.discard();
                }
                burst(ctx, level, tx, ty, tz);
                continue;
            }
            if (display == null) {
                // The display is gone (chunk edge, kill
                // command): the pod fizzles without a burst.
                pod.putBoolean("done", true);
                continue;
            }
            double progress = flight / (double) FLIGHT_TICKS;
            double dx = tx - sx;
            double dz = tz - sz;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            double arcHeight = 4.0 + horizontal * 0.25;
            double x = sx + dx * progress;
            double z = sz + dz * progress;
            double y = sy + (ty - sy) * progress + Math.sin(Math.PI * progress) * arcHeight;
            display.snapTo(x, y, z, 0.0f, 0.0f);
        }
        ctx.state().put("pods", pods);
    }

    /** The landing burst (class comment): particles and code damage, never a real explosion. */
    private void burst(AbilityContext ctx, ServerLevel level, double x, double y, double z) {
        level.sendParticles(ParticleTypes.EXPLOSION, x, y + 0.4, z, 1, 0.1, 0.1, 0.1, 0.0);
        level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, x, y + 0.5, z, 8, 0.7, 0.4, 0.7, 0.03);
        level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 0.7f, 1.2f);
        Mob caster = ctx.mob();
        List<LivingEntity> victims = SnifferAbilitySupport.enemiesInBox(ctx, x, y, z, BURST_RADIUS, 2.0, 2.0);
        for (LivingEntity victim : victims) {
            victim.hurtServer(level, level.damageSources().mobAttack(caster), BURST_DAMAGE);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks >= DURATION_TICKS) {
            return true;
        }
        if (ticks < 90 || ctx.state().getIntOr("launched", 0) < MAX_PODS) {
            return false;
        }
        ListTag pods = ctx.state().getListOrEmpty("pods");
        for (int i = 0; i < pods.size(); i++) {
            if (!pods.getCompoundOrEmpty(i).getBooleanOr("done", true)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void end(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        // Discard any pod still in the air, without bursting it.
        ListTag pods = ctx.state().getListOrEmpty("pods");
        for (int i = 0; i < pods.size(); i++) {
            CompoundTag pod = pods.getCompoundOrEmpty(i);
            if (pod.getBooleanOr("done", true)) {
                continue;
            }
            Entity display = SnifferAbilitySupport.resolveTracked(level, pod);
            if (display != null) {
                display.discard();
            }
            pod.putBoolean("done", true);
        }
        ctx.state().put("pods", pods);
    }
}
