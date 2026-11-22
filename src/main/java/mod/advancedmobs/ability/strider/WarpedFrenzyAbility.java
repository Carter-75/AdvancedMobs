package mod.advancedmobs.ability.strider;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Warped Frenzy (Strider ability 2 of 5, transformation).
 *
 * WHAT IT DOES: the strider catches the scent of warped
 * fungus and loses its mind a little: WARPED_SPORE puffs from
 * its head, it shivers with excitement (STRIDER_HAPPY chirps),
 * and for 12 seconds it moves at absurd speed on any surface,
 * lava or land. Instead of stumbling into enemies, it RAMS
 * them at full tilt: real damage and a hard shove, over and
 * over, for as long as the frenzy lasts.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): a live target within 14 blocks
 *   (distance squared 196).
 * - The speed: a transient MOVEMENT_SPEED modifier (id
 *   advancedmobs:strider_frenzy_speed) of +2.0
 *   add_multiplied_base in start(): base 0.175 becomes about
 *   0.53, faster than a sprinting player, on lava and land
 *   alike. On top of that, SPEED I for 260 ticks (removed in
 *   end()). A transient ATTACK_KNOCKBACK modifier (id
 *   advancedmobs:strider_frenzy_knockback) of +2.0 add_value
 *   rides along for the design's stated line: if the strider's
 *   own contact ever deals knockback through the attribute,
 *   it hits harder; the ram below is code-driven and never
 *   depends on it.
 * - The ram: every tick, one bounded enemy query in a tight
 *   box (radius 1.6, 1 below to 2 above the strider). Each
 *   enemy is rammed at most once per 20 ticks (tracked in the
 *   state tag by UUID with the tick it may be rammed again):
 *   6.0 damage as a mob attack credited to the strider
 *   (hurtServer with damageSources().mobAttack; a strider has
 *   no ATTACK_DAMAGE attribute instance in 26.2, verified
 *   against the jar, so the damage is a code number, never a
 *   melee swing), plus a code shove directly away from the
 *   strider at 1.2 horizontal and 0.4 up, hurtMarked so the
 *   knockback syncs. 4 CRIT and 4 WARPED_SPORE burst at the
 *   victim on each ram.
 * - Duration: exactly 240 ticks (12 seconds) from start().
 *
 * WHAT IT LOOKS LIKE: 12 WARPED_SPORE bursting from the head
 * and a happy chirp at the start, then 3 WARPED_SPORE puffing
 * from the head every 2 ticks for the whole frenzy while the
 * strider blurs across the ground, slamming into anything in
 * reach with crit bursts.
 *
 * PERFORMANCE: the ram query is a tight radius-1.6 box once
 * per tick (the smallest box that covers contact range);
 * the per-enemy cooldown list holds at most the handful of
 * enemies one frenzy can touch and is swept by the same pass
 * that reads it. Spores are 3 particles every 2 ticks.
 * Particle budget per activation: opening 12, head puffs 120
 * times 3 = 360, rams at most 12 per enemy and realistically
 * a few dozen total, hard total at most about 460 particles.
 *
 * CLEANUP: end() removes both attribute modifiers and the
 * SPEED effect. Nothing else persists.
 *
 * State tag keys: "rammed" (list of compounds, one per
 * rammed enemy: "id" the enemy's UUID, "until" the
 * activation tick it may be rammed again).
 */
public final class WarpedFrenzyAbility implements MobAbility {

    static final int DURATION_TICKS = 240;
    private static final int RAM_COOLDOWN_TICKS = 20;
    private static final float RAM_DAMAGE = 6.0f;
    private static final Identifier SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "strider_frenzy_speed");
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "strider_frenzy_knockback");

    @Override
    public String id() {
        return "warped_frenzy";
    }

    @Override
    public String displayName() {
        return "Warped Frenzy";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        ctx.state().put("rammed", new ListTag());
        StriderAbilitySupport.addModifier(mob, Attributes.MOVEMENT_SPEED,
            new AttributeModifier(SPEED_ID, 2.0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        StriderAbilitySupport.addModifier(mob, Attributes.ATTACK_KNOCKBACK,
            new AttributeModifier(KNOCKBACK_ID, 2.0, AttributeModifier.Operation.ADD_VALUE));
        mob.addEffect(new MobEffectInstance(MobEffects.SPEED, DURATION_TICKS + 20, 0, false, false, false));
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.sendParticles(ParticleTypes.WARPED_SPORE,
                mob.getX(), mob.getY() + mob.getBbHeight(), mob.getZ(),
                12, 0.4, 0.3, 0.4, 0.05);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.STRIDER_HAPPY,
                SoundSource.HOSTILE, 1.0f, 1.1f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        int ticks = ctx.ticksActive();
        if (ticks % 2 == 0) {
            level.sendParticles(ParticleTypes.WARPED_SPORE,
                mob.getX(), mob.getY() + mob.getBbHeight(), mob.getZ(),
                3, 0.3, 0.2, 0.3, 0.03);
        }
        ramPass(ctx, level, mob, ticks);
    }

    /**
     * The ram pass: every enemy in contact range whose
     * per-enemy cooldown has run out takes the hit and the
     * shove, and is recorded against its next ram tick.
     */
    private static void ramPass(AbilityContext ctx, ServerLevel level, Mob mob, int ticks) {
        List<LivingEntity> enemies = StriderAbilitySupport.enemiesAroundSelf(ctx, 1.6, 1.0, 2.0);
        if (enemies.isEmpty()) {
            return;
        }
        CompoundTag state = ctx.state();
        ListTag rammed = state.getListOrEmpty("rammed");
        for (LivingEntity victim : enemies) {
            if (!mayRam(rammed, victim.getUUID(), ticks)) {
                continue;
            }
            victim.hurtServer(level, level.damageSources().mobAttack(mob), RAM_DAMAGE);
            Vec3 away = victim.position().subtract(mob.position());
            double length = Math.sqrt(away.x * away.x + away.z * away.z);
            double nx = length > 0.001 ? away.x / length : 1.0;
            double nz = length > 0.001 ? away.z / length : 0.0;
            victim.setDeltaMovement(nx * 1.2, 0.4, nz * 1.2);
            victim.hurtMarked = true;
            level.sendParticles(ParticleTypes.CRIT, victim.getX(), victim.getY() + 0.8, victim.getZ(),
                4, 0.3, 0.3, 0.3, 0.1);
            level.sendParticles(ParticleTypes.WARPED_SPORE, victim.getX(), victim.getY() + 0.8, victim.getZ(),
                4, 0.3, 0.3, 0.3, 0.03);
            recordRam(rammed, victim.getUUID(), ticks + RAM_COOLDOWN_TICKS);
        }
        state.put("rammed", rammed);
    }

    /** Whether the given enemy's ram cooldown has run out (and prunes dead entries). */
    private static boolean mayRam(ListTag rammed, UUID id, int ticks) {
        for (int i = 0; i < rammed.size(); i++) {
            CompoundTag entry = rammed.getCompoundOrEmpty(i);
            UUID entryId = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id.equals(entryId)) {
                return ticks >= entry.getIntOr("until", 0);
            }
        }
        return true;
    }

    /** Records or refreshes an enemy's next ram tick in the list. */
    private static void recordRam(ListTag rammed, UUID id, int until) {
        for (int i = 0; i < rammed.size(); i++) {
            CompoundTag entry = rammed.getCompoundOrEmpty(i);
            UUID entryId = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id.equals(entryId)) {
                entry.putInt("until", until);
                return;
            }
        }
        CompoundTag entry = new CompoundTag();
        entry.store("id", UUIDUtil.CODEC, id);
        entry.putInt("until", until);
        rammed.add(entry);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        StriderAbilitySupport.removeModifier(mob, Attributes.MOVEMENT_SPEED, SPEED_ID);
        StriderAbilitySupport.removeModifier(mob, Attributes.ATTACK_KNOCKBACK, KNOCKBACK_ID);
        mob.removeEffect(MobEffects.SPEED);
    }
}
