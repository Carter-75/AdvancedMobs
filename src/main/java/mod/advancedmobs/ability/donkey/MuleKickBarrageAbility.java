package mod.advancedmobs.ability.donkey;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Mule Kick Barrage (Donkey ability 1 of 5, attack).
 *
 * WHAT IT DOES: the donkey goes into a kicking frenzy. It
 * runs its target down, spins its rear onto the nearest
 * enemy, and lets fly with both hind hooves: a clean kick
 * deals heavy damage and launches the victim in a high arc
 * whose landing hurts twice as much as it should. Then it
 * pivots straight onto the next enemy and kicks again,
 * every 12 ticks, for as long as the barrage lasts.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the donkey on the ground with its current target within
 *   14 blocks (distance squared 196).
 * - The kit (start(), removed exactly in end()): two
 *   transient attribute modifiers under fixed ids:
 *   ATTACK_KNOCKBACK +3.0 ADD_VALUE (the design's
 *   attack_knockback line) and ATTACK_SPEED +0.5
 *   ADD_MULTIPLIED_BASE (the design's attack_speed line;
 *   documented as INERT on the donkey, whose attribute
 *   chain has no ATTACK_SPEED instance, so the flurry rate
 *   below is code timing, the Grand Hex Form precedent).
 * - Duration: exactly 200 ticks (10 seconds), hard finish.
 * - The chase (tick(), every 5 ticks): if the framework
 *   target is more than 2.6 blocks away, the donkey's
 *   navigation is driven onto it at speed multiplier 1.2,
 *   so the barrage keeps closing distance between kicks.
 * - The kick (tick(), searched every 2 ticks once the kick
 *   clock is ready; the clock starts ready, then runs every
 *   12 ticks): the nearest enemy (package rules) inside a
 *   radius-2.6 box is picked. The donkey spins to face
 *   directly AWAY from that enemy (its yaw is set to the
 *   bearing of the enemy plus 180 degrees, head and body,
 *   the design's /rotate line carried by code facing), so
 *   the kick always lands rear-first. The victim takes 8.0
 *   damage as a mob attack credited to the donkey (a plain
 *   donkey has no ATTACK_DAMAGE instance; 8.0 is this
 *   ability's fixed hoof damage). The launch: velocity set
 *   to 2.2 blocks per tick horizontally along the direction
 *   from the donkey to the victim and 0.7 up, hurtMarked
 *   set. The victim also gains a transient
 *   FALL_DAMAGE_MULTIPLIER modifier of +1.0
 *   ADD_MULTIPLIED_TOTAL under a fixed id (fall damage
 *   doubled), recorded in the state tag's victim list.
 * - The landing watch (tick(), every 5 ticks): each
 *   recorded victim back on the ground has its fall
 *   multiplier removed and is pruned, so the curse lasts
 *   exactly until the landing it was meant to worsen (the
 *   Llama Mule Kick pattern). Victims that die or unload
 *   are pruned silently; a transient modifier dies with
 *   its entity.
 * - end(): both kit modifiers are removed, and the fall
 *   multiplier is stripped from any victim that never
 *   landed inside the barrage.
 * - Sound and sight (per kick): DONKEY_ANGRY at volume
 *   1.0, pitch 0.9; 6 SWEEP_ATTACK and 6 CRIT particles on
 *   the victim, and 8 DUST_PILLAR of the ground block
 *   under the victim.
 *
 * WHAT IT LOOKS LIKE: an angry braying donkey spinning like
 * a top in a crowd, hooves flashing with sweep arcs and
 * crit sparks, enemies sailing off its rear one after
 * another in high, painful arcs.
 *
 * PERFORMANCE: the chase is one navigation call every 5
 * ticks; the kick search is one bounded radius-2.6 query
 * every 2 ticks at most, and only while the kick clock is
 * ready; the landing watch resolves at most the recorded
 * victims every 5 ticks. Particle budget per activation:
 * at most 17 kicks times 20 particles = 340.
 *
 * TERRAIN: this ability changes no blocks (DUST_PILLAR is
 * a particle, not a placed block); the mobGriefing gate
 * does not apply.
 *
 * State tag keys: "victims" (list of compounds, one per
 * launched victim: "id", the victim's UUID), "next_kick"
 * (first tick another kick may fire).
 */
public final class MuleKickBarrageAbility implements MobAbility {

    private static final int DURATION_TICKS = 200;
    private static final int KICK_INTERVAL_TICKS = 12;
    private static final int KICK_SEARCH_INTERVAL = 2;
    private static final int CHASE_INTERVAL = 5;
    private static final int PRUNE_INTERVAL = 5;
    private static final double KICK_RADIUS = 2.6;
    private static final float KICK_DAMAGE = 8.0f;
    private static final double LAUNCH_HORIZONTAL = 2.2;
    private static final double LAUNCH_VERTICAL = 0.7;
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "mule_kick_barrage_knockback");
    private static final Identifier SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "mule_kick_barrage_speed");
    private static final Identifier FALL_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "mule_kick_barrage_fall");

    @Override
    public String id() {
        return "mule_kick_barrage";
    }

    @Override
    public String displayName() {
        return "Mule Kick Barrage";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().onGround()
            && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().put("victims", new ListTag());
        ctx.state().putInt("next_kick", 0);
        Mob caster = ctx.mob();
        // The kit: heavier knockback on any vanilla contact,
        // and the design's attack speed line (inert, see the
        // class comment; the flurry is the 12-tick clock).
        DonkeyAbilitySupport.addModifier(caster, Attributes.ATTACK_KNOCKBACK,
            new AttributeModifier(KNOCKBACK_ID, 3.0, AttributeModifier.Operation.ADD_VALUE));
        DonkeyAbilitySupport.addModifier(caster, Attributes.ATTACK_SPEED,
            new AttributeModifier(SPEED_ID, 0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        Mob caster = ctx.mob();

        // The chase: keep closing on the target between kicks.
        if (ticks % CHASE_INTERVAL == 0) {
            LivingEntity target = ctx.target();
            if (target != null && caster.distanceToSqr(target) > KICK_RADIUS * KICK_RADIUS) {
                caster.getNavigation().moveTo(target, 1.2);
            }
        }

        // The kick: spin the rear onto the nearest enemy and fire.
        if (ticks >= ctx.state().getIntOr("next_kick", 0) && ticks % KICK_SEARCH_INTERVAL == 0) {
            ServerLevel level = ctx.serverLevel();
            if (level != null) {
                tryKick(ctx, level, ticks);
            }
        }

        // The landing watch: strip the fall curse as victims land.
        if (ticks % PRUNE_INTERVAL == 0) {
            ServerLevel level = ctx.serverLevel();
            if (level != null) {
                pruneVictims(ctx, level);
            }
        }
    }

    /** One kick, if an enemy is in reach: spin, damage, launch, curse the landing. */
    private void tryKick(AbilityContext ctx, ServerLevel level, int ticks) {
        Mob caster = ctx.mob();
        List<LivingEntity> inReach = DonkeyAbilitySupport.enemiesAroundSelf(ctx, KICK_RADIUS, 2.0, 2.0);
        LivingEntity victim = null;
        double bestSqr = Double.MAX_VALUE;
        for (LivingEntity candidate : inReach) {
            double sqr = caster.distanceToSqr(candidate);
            if (sqr < bestSqr) {
                bestSqr = sqr;
                victim = candidate;
            }
        }
        if (victim == null) {
            return; // Nobody in reach: the clock stays ready.
        }
        ctx.state().putInt("next_kick", ticks + KICK_INTERVAL_TICKS);

        // The spin: face directly away from the victim, so
        // the hooves are what it meets.
        double dx = victim.getX() - caster.getX();
        double dz = victim.getZ() - caster.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0e-4) {
            dx = 1.0;
            dz = 0.0;
            length = 1.0;
        }
        float yawToVictim = (float) Math.toDegrees(Math.atan2(-(dx / length), dz / length));
        float rearYaw = yawToVictim + 180.0f;
        caster.setYRot(rearYaw);
        caster.setYHeadRot(rearYaw);

        // The hooves: heavy damage, credited to the donkey.
        victim.hurtServer(level, level.damageSources().mobAttack(caster), KICK_DAMAGE);

        // The launch: off the donkey's rear and high up.
        victim.setDeltaMovement(dx / length * LAUNCH_HORIZONTAL, LAUNCH_VERTICAL,
            dz / length * LAUNCH_HORIZONTAL);
        victim.hurtMarked = true;

        // The landing does half the work: fall damage doubled until touchdown.
        DonkeyAbilitySupport.addModifier(victim, Attributes.FALL_DAMAGE_MULTIPLIER,
            new AttributeModifier(FALL_ID, 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        DonkeyAbilitySupport.trackEntity(ctx.state(), "victims", victim);

        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.DONKEY_ANGRY,
            SoundSource.HOSTILE, 1.0f, 0.9f);
        level.sendParticles(ParticleTypes.SWEEP_ATTACK, victim.getX(), victim.getY() + 0.8, victim.getZ(),
            6, 0.3, 0.4, 0.3, 0.1);
        level.sendParticles(ParticleTypes.CRIT, victim.getX(), victim.getY() + 0.8, victim.getZ(),
            6, 0.3, 0.4, 0.3, 0.2);
        BlockState ground = level.getBlockState(victim.blockPosition().below());
        BlockState particleState = ground.isAir() ? Blocks.DIRT.defaultBlockState() : ground;
        level.sendParticles(new BlockParticleOption(ParticleTypes.DUST_PILLAR, particleState),
            victim.getX(), victim.getY() + 0.2, victim.getZ(), 8, 0.4, 0.3, 0.4, 0.1);
    }

    /** Removes the fall curse from every recorded victim that has landed; prunes the gone. */
    private void pruneVictims(AbilityContext ctx, ServerLevel level) {
        ListTag victims = ctx.state().getListOrEmpty("victims");
        if (victims.isEmpty()) {
            return;
        }
        ListTag remaining = new ListTag();
        for (int i = 0; i < victims.size(); i++) {
            CompoundTag entry = victims.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id == null || !(level.getEntity(id) instanceof LivingEntity victim) || !victim.isAlive()) {
                continue; // Gone: the transient modifier went with it.
            }
            if (victim.onGround()) {
                DonkeyAbilitySupport.removeModifier(victim, Attributes.FALL_DAMAGE_MULTIPLIER, FALL_ID);
                continue;
            }
            remaining.add(entry);
        }
        ctx.state().put("victims", remaining);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        DonkeyAbilitySupport.removeModifier(caster, Attributes.ATTACK_KNOCKBACK, KNOCKBACK_ID);
        DonkeyAbilitySupport.removeModifier(caster, Attributes.ATTACK_SPEED, SPEED_ID);
        // Remove any fall curses still out: victims that
        // never landed inside the barrage.
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag victims = ctx.state().getListOrEmpty("victims");
        for (int i = 0; i < victims.size(); i++) {
            CompoundTag entry = victims.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id != null && level.getEntity(id) instanceof LivingEntity victim) {
                DonkeyAbilitySupport.removeModifier(victim, Attributes.FALL_DAMAGE_MULTIPLIER, FALL_ID);
            }
        }
    }
}
