package mod.advancedmobs.ability.llama;

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

import java.util.UUID;

/**
 * Mule Kick (Llama ability 4 of 5, attack).
 *
 * WHAT IT DOES: any enemy that sneaks up behind the llama
 * gets both hind hooves in a single explosive kick,
 * DUST_PILLAR erupting from the impact point. The kick
 * deals heavy damage and launches the victim backward and
 * upward so hard that the landing does half the work: the
 * victim's fall damage is doubled until it lands.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the llama's current target within 3.0 blocks
 *   (distance squared 9) AND behind it: the horizontal dot
 *   product of the llama's look direction with the
 *   direction to the target is at most -0.3, so the kick
 *   only fires on a genuine sneak-up, never face to face.
 * - The kick (start(), fires once, immediately): the
 *   victim takes 10.0 damage as a mob attack credited to
 *   the llama (a plain llama has no melee attack of its
 *   own; 10.0 is this ability's fixed hoof damage, about a
 *   third of a plain llama's health pool traded back).
 *   For the kick moment the llama carries a transient
 *   ATTACK_KNOCKBACK modifier of +3.0 ADD_VALUE under a
 *   fixed id (the design's ATTACK_KNOCKBACK line; removed
 *   in end(), and null-tolerant if the llama's attribute
 *   set lacks the instance, because the launch below is
 *   code velocity either way and never depends on it).
 * - The launch (start()): the victim's velocity is set to
 *   1.5 blocks per tick horizontally along the direction
 *   from the llama to the victim (which, given the behind
 *   trigger, throws it further backward off the llama's
 *   rear) and 0.85 up, with hurtMarked set so the client
 *   syncs it. The victim also gains a transient
 *   FALL_DAMAGE_MULTIPLIER modifier of +1.0
 *   ADD_MULTIPLIED_TOTAL under a fixed id (fall damage
 *   doubled), recorded in the state tag's victim list.
 * - The landing watch (tick(), every 5 ticks): each
 *   recorded victim that is back on the ground has its
 *   fall multiplier removed and is pruned from the list,
 *   so the curse lasts exactly until the landing it was
 *   meant to worsen. Victims that die or unload are pruned
 *   silently; a transient modifier dies with its entity.
 * - Duration: the kick is instant, but the activation
 *   lingers to a hard finish at tick 100 so the landing
 *   watch can run; end() removes the fall multiplier from
 *   any victim that never landed inside the window and
 *   removes the llama's ATTACK_KNOCKBACK modifier.
 * - Sound and sight (start()): LLAMA_ANGRY at volume 1.2,
 *   pitch 0.8, 10 DUST_PILLAR of the ground block under
 *   the victim, and 8 DAMAGE_INDICATOR particles on the
 *   victim.
 *
 * WHAT IT LOOKS LIKE: a crack of angry llama noise, a
 * pillar of dust bursting off the ground, and the attacker
 * sailing backward in a high arc to a landing that hurts
 * twice as much as it should.
 *
 * PERFORMANCE: start() is one damage call, one velocity
 * write, and two modifier writes, once. tick() returns
 * immediately except every 5th tick, where it resolves at
 * most the recorded victims (in practice exactly one) and
 * reads onGround. There are no entity queries anywhere in
 * the ability. Particle budget per activation: 10 plus 8 =
 * 18 particles, all at start.
 *
 * TERRAIN: this ability changes no blocks (DUST_PILLAR is
 * a particle, not a placed block); the mobGriefing gate
 * does not apply.
 *
 * State tag keys: "victims" (list of compounds, one per
 * launched victim: "id", the victim's UUID).
 */
public final class MuleKickAbility implements MobAbility {

    private static final int MAX_TICKS = 100;
    private static final int PRUNE_INTERVAL = 5;
    private static final float KICK_DAMAGE = 10.0f;
    private static final double LAUNCH_HORIZONTAL = 1.5;
    private static final double LAUNCH_VERTICAL = 0.85;
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "mule_kick_knockback");
    private static final Identifier FALL_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "mule_kick_fall");

    @Override
    public String id() {
        return "mule_kick";
    }

    @Override
    public String displayName() {
        return "Mule Kick";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null || ctx.mob().distanceToSqr(target) > 9.0) {
            return false;
        }
        // Behind check: the target must be in the rear arc.
        Mob caster = ctx.mob();
        Vec3 look = caster.getLookAngle();
        double dx = target.getX() - caster.getX();
        double dz = target.getZ() - caster.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0e-4) {
            return false;
        }
        double dot = (look.x * dx + look.z * dz) / length;
        return dot <= -0.3;
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().put("victims", new ListTag());
        ServerLevel level = ctx.serverLevel();
        LivingEntity victim = ctx.target();
        if (level == null || victim == null) {
            return;
        }
        Mob caster = ctx.mob();

        // The hooves: heavy damage, credited to the llama.
        LlamaAbilitySupport.addModifier(caster, Attributes.ATTACK_KNOCKBACK,
            new AttributeModifier(KNOCKBACK_ID, 3.0, AttributeModifier.Operation.ADD_VALUE));
        victim.hurtServer(level, level.damageSources().mobAttack(caster), KICK_DAMAGE);

        // The launch: backward off the llama's rear and high up.
        Vec3 away = new Vec3(victim.getX() - caster.getX(), 0.0, victim.getZ() - caster.getZ());
        away = away.lengthSqr() < 1.0e-4 ? new Vec3(0.0, 0.0, 1.0) : away.normalize();
        victim.setDeltaMovement(away.x * LAUNCH_HORIZONTAL, LAUNCH_VERTICAL, away.z * LAUNCH_HORIZONTAL);
        victim.hurtMarked = true;

        // The landing does half the work: fall damage doubled until touchdown.
        LlamaAbilitySupport.addModifier(victim, Attributes.FALL_DAMAGE_MULTIPLIER,
            new AttributeModifier(FALL_ID, 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        CompoundTag entry = new CompoundTag();
        entry.store("id", UUIDUtil.CODEC, victim.getUUID());
        ListTag victims = new ListTag();
        victims.add(entry);
        ctx.state().put("victims", victims);

        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.LLAMA_ANGRY,
            SoundSource.HOSTILE, 1.2f, 0.8f);
        BlockState ground = level.getBlockState(victim.blockPosition().below());
        BlockState particleState = ground.isAir() ? Blocks.DIRT.defaultBlockState() : ground;
        level.sendParticles(new BlockParticleOption(ParticleTypes.DUST_PILLAR, particleState),
            victim.getX(), victim.getY() + 0.2, victim.getZ(), 10, 0.4, 0.3, 0.4, 0.1);
        level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, victim.getX(), victim.getY() + 0.8, victim.getZ(),
            8, 0.3, 0.4, 0.3, 0.1);
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % PRUNE_INTERVAL != 0) {
            return; // Cheap early-out: the landing watch runs every 5 ticks only.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
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
                LlamaAbilitySupport.removeModifier(victim, Attributes.FALL_DAMAGE_MULTIPLIER, FALL_ID);
                continue;
            }
            remaining.add(entry);
        }
        ctx.state().put("victims", remaining);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= MAX_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        LlamaAbilitySupport.removeModifier(ctx.mob(), Attributes.ATTACK_KNOCKBACK, KNOCKBACK_ID);
        // Remove any fall multipliers still out: victims that
        // never landed inside the linger window.
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag victims = ctx.state().getListOrEmpty("victims");
        for (int i = 0; i < victims.size(); i++) {
            CompoundTag entry = victims.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id != null && level.getEntity(id) instanceof LivingEntity victim) {
                LlamaAbilitySupport.removeModifier(victim, Attributes.FALL_DAMAGE_MULTIPLIER, FALL_ID);
            }
        }
    }
}
