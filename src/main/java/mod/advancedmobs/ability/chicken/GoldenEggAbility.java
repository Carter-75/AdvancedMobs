package mod.advancedmobs.ability.chicken;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
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
import net.minecraft.world.entity.animal.chicken.Chicken;

import java.util.UUID;

/**
 * Golden Egg (Chicken ability 5 of 5, defense / summon).
 *
 * WHAT IT DOES: the hen settles and lays a single
 * glowing egg that shines at full brightness with
 * enchantment sparkles orbiting it, daring anyone to
 * come and take it. A few seconds later the egg hatches
 * in a burst of fireworks, and out steps a golden chick:
 * small, fast, and mean, fighting at her mother's side
 * for the rest of the battle's minute.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate,
 *   plus the chicken's current target within 14 blocks
 *   (distance squared 196).
 * - The laying (start()): one item display is created
 *   through the support factory (an egg item at scale
 *   1.4, full brightness, Glowing with a gold
 *   glow_color_override, the design's shining egg) and
 *   placed at the hen's feet, raised 0.15 blocks so it
 *   sits on the ground instead of in it. The display's
 *   UUID and position are recorded in the state tag.
 *   CHICKEN_EGG plays (volume 1.0, pitch 1.0): the
 *   lay-egg pop. The display is a pure visual with no
 *   hitbox; nothing can attack the egg itself, and the
 *   defense the design promises is the chick it becomes.
 * - The incubation (ticks 1 to 59): every 2 ticks, 2
 *   ENCHANT particles (the design's orbiting sparkles)
 *   are placed on a slow orbit around the egg: radius
 *   0.55, angle advancing 0.35 radians per emission,
 *   height bobbing 0.1 on a sine of the tick count. No
 *   entity queries run; the orbit is pure arithmetic on
 *   the recorded position.
 * - The hatch (tick 60, the design's short delay carried
 *   as a code timer): the display is discarded, 12
 *   FIREWORK and 8 ENCHANT particles burst at the egg's
 *   position (the design's firework burst), and the
 *   golden chick is summoned there: a baby chicken (age
 *   -24000 set after finalizeSpawn) with PERMANENT base
 *   values set after finalizeSpawn (the summon sheet
 *   precedent): SCALE base 0.6 (a little bigger than a
 *   swarm chick), MAX_HEALTH base 6.0 with health set to
 *   6.0 (half again a normal chick's 4.0), and
 *   MOVEMENT_SPEED base 0.35 (the design's small fast
 *   chicken; an adult hen walks at 0.25). It is named
 *   "Golden Chick" with the name visible. Team
 *   assignment, both directions plus the registry: the
 *   caster's data gains the chick as an ally, the
 *   chick's data gains the caster, and the pair is
 *   registered in SummonerTracker. The chick is ordered
 *   onto the caster's current target.
 * - The golden sheen (babysit, every 20 ticks): the
 *   chick's GLOWING is refreshed to 40 ticks (the golden
 *   outline; 26.2 colors a glowing outline by team and
 *   the chick has none, so it reads white-gold, the
 *   documented approximation every glowing summon in
 *   this mod carries) along with SPEED I for 40 ticks.
 * - Babysitting (tick(), every 5 ticks, after the
 *   hatch): the chick's position is recorded, a chick
 *   whose target is not the caster's current target is
 *   re-ordered onto it, and its navigation is driven at
 *   the target at speed 1.4 whenever the target is more
 *   than 1.3 blocks away (a passive animal's AI never
 *   chases a set target on its own, the Chick Swarm
 *   precedent).
 * - The boosted attack (same babysit pass): the chick
 *   within 1.3 blocks of the target strikes it for 3.0
 *   mob-attack damage credited to the CHICK, at most
 *   once per 10 ticks, with 3 CRIT particles at the
 *   victim's feet. That is the design's boosted attack:
 *   three times a swarm chick's peck at twice the rate.
 *   It is a flat code value because a chicken has no
 *   ATTACK_DAMAGE attribute instance in 26.2 (verified
 *   against the jar; see the support class comment), so
 *   there is no attack attribute on the chick to boost.
 * - The chick's death (same pass): a hatched chick that
 *   no longer resolves, or resolves dead, bursts exactly
 *   once at its last recorded position (8 FIREWORK), the
 *   caster's ally record for it is removed, and the
 *   ability ends early: the egg's whole payload is gone.
 * - Duration: 400 ticks total (the 60 tick incubation
 *   plus up to 340 ticks of chick), hard finish, or the
 *   early finish on the chick's death above.
 * - end(): if the egg display still exists (the ability
 *   ended during incubation, or the caster died under
 *   it), it is discarded silently. If the chick is still
 *   alive it is discarded in an 8 FIREWORK puff and the
 *   alliance records are removed on both sides. If the
 *   caster dies first, the framework termination path
 *   runs this same end(), so neither the egg nor the
 *   chick outlives the hen. SummonerTracker records are
 *   deliberately not unregistered, matching the other
 *   summons in this mod.
 *
 * WHAT IT LOOKS LIKE: a pop, and a golden egg sits
 * shining on the ground at the hen's feet, enchantment
 * runes circling it while the fight rages around it;
 * then a crack of fireworks and a tiny gold-lit chick
 * bursts out and throws itself at the nearest enemy,
 * pecking three times as hard as any chick has a right
 * to.
 *
 * PERFORMANCE: incubation is 2 particles every 2 ticks
 * and one UUID resolve per tick for the display; the
 * babysit runs every 5 ticks resolving at most 1 UUID,
 * and the sheen refresh every 20 ticks touches 1 entity;
 * no entity scans anywhere. Particle budget per
 * activation: incubation at most 30 times 2 = 60, hatch
 * burst 20, chick death or dismissal at most 8, pecks at
 * most 34 times 3 = 102, total at most 190 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply (the egg is a display entity, not a placed
 * block).
 *
 * State tag keys: "phase" (0 incubating, 1 hatched),
 * "egg" (the display's UUID), "ex", "ey", "ez" (the
 * egg's recorded position), "chick" (the chick's UUID),
 * "cx", "cy", "cz" (the chick's last recorded position),
 * "peck" (the activation tick of the chick's last
 * strike), "gone" (1 once the chick's death burst ran),
 * "done" (1 to finish early).
 */
public final class GoldenEggAbility implements MobAbility {

    private static final int HATCH_TICK = 60;
    private static final int DURATION_TICKS = 400;
    private static final int BABYSIT_INTERVAL = 5;
    private static final double DRIVE_SPEED = 1.4;
    private static final double STRIKE_RANGE = 1.3;
    private static final float STRIKE_DAMAGE = 3.0f;
    private static final int STRIKE_INTERVAL_TICKS = 10;

    @Override
    public String id() {
        return "golden_egg";
    }

    @Override
    public String displayName() {
        return "Golden Egg";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 196.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        state.putInt("phase", 0);
        state.putInt("gone", 0);
        state.putInt("done", 0);
        state.putInt("peck", -STRIKE_INTERVAL_TICKS);
        state.putDouble("ex", caster.getX());
        state.putDouble("ey", caster.getY());
        state.putDouble("ez", caster.getZ());
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Entity display = ChickenAbilitySupport.createEggDisplay(level, 1.4f);
        if (display != null) {
            display.snapTo(caster.getX(), caster.getY() + 0.15, caster.getZ(), 0.0f, 0.0f);
            if (level.addFreshEntity(display)) {
                state.store("egg", UUIDUtil.CODEC, display.getUUID());
                state.putDouble("ex", display.getX());
                state.putDouble("ey", display.getY());
                state.putDouble("ez", display.getZ());
            }
        }
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.CHICKEN_EGG,
            SoundSource.HOSTILE, 1.0f, 1.0f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        int ticks = ctx.ticksActive();
        if (state.getIntOr("phase", 0) == 0) {
            if (ticks >= HATCH_TICK) {
                hatch(ctx, level);
                return;
            }
            if (ticks % 2 == 0) {
                // The orbiting sparkles (see the class
                // comment): pure arithmetic, no queries.
                double angle = ticks * 0.35;
                double ex = state.getDoubleOr("ex", ctx.mob().getX());
                double ey = state.getDoubleOr("ey", ctx.mob().getY());
                double ez = state.getDoubleOr("ez", ctx.mob().getZ());
                level.sendParticles(ParticleTypes.ENCHANT,
                    ex + Math.cos(angle) * 0.55, ey + 0.45 + Math.sin(ticks * 0.2) * 0.1,
                    ez + Math.sin(angle) * 0.55, 2, 0.05, 0.05, 0.05, 0.02);
            }
            return;
        }
        babysit(ctx, level, ticks);
    }

    /** The hatch: firework burst and the golden chick (see the class comment). Runs exactly once. */
    private static void hatch(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        state.putInt("phase", 1);
        Mob caster = ctx.mob();
        double ex = state.getDoubleOr("ex", caster.getX());
        double ey = state.getDoubleOr("ey", caster.getY());
        double ez = state.getDoubleOr("ez", caster.getZ());
        if (state.contains("egg")) {
            UUID eggId = state.read("egg", UUIDUtil.CODEC).orElse(null);
            Entity display = eggId != null ? level.getEntity(eggId) : null;
            if (display != null) {
                display.discard();
            }
        }
        level.sendParticles(ParticleTypes.FIREWORK, ex, ey + 0.4, ez, 12, 0.3, 0.3, 0.3, 0.1);
        level.sendParticles(ParticleTypes.ENCHANT, ex, ey + 0.4, ez, 8, 0.3, 0.3, 0.3, 0.05);

        Chicken chick = EntityTypes.CHICKEN.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (chick == null) {
            state.putInt("done", 1);
            return;
        }
        chick.snapTo(ex, ey, ez, ctx.random().nextFloat() * 360.0f, 0.0f);
        chick.finalizeSpawn(level, level.getCurrentDifficultyAt(chick.blockPosition()),
            EntitySpawnReason.MOB_SUMMONED, null);
        // The golden sheet (see the class comment):
        // permanent base values, set after finalizeSpawn.
        chick.setAge(-24000);
        AttributeInstance scale = chick.getAttribute(Attributes.SCALE);
        if (scale != null) {
            scale.setBaseValue(0.6);
        }
        AttributeInstance health = chick.getAttribute(Attributes.MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(6.0);
        }
        chick.setHealth(6.0f);
        AttributeInstance speed = chick.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.setBaseValue(0.35);
        }
        chick.setCustomName(Component.literal("Golden Chick"));
        chick.setCustomNameVisible(true);
        if (!level.addFreshEntity(chick)) {
            state.putInt("done", 1);
            return;
        }
        ctx.data().addAlly(chick.getUUID());
        MobWarData.get(chick).addAlly(caster.getUUID());
        SummonerTracker.registerSummoned(chick.getUUID(), caster.getUUID());
        LivingEntity target = ctx.target();
        if (target != null) {
            chick.setTarget(target);
        }
        state.store("chick", UUIDUtil.CODEC, chick.getUUID());
        state.putDouble("cx", chick.getX());
        state.putDouble("cy", chick.getY());
        state.putDouble("cz", chick.getZ());
    }

    /** The post-hatch babysit: steering, the golden sheen, and the boosted pecks (see the class comment). */
    private static void babysit(AbilityContext ctx, ServerLevel level, int ticks) {
        CompoundTag state = ctx.state();
        if (state.getIntOr("gone", 0) == 1) {
            return;
        }
        LivingEntity found = ChickenAbilitySupport.resolveByUuid(level, state, "chick");
        if (!(found instanceof Chicken chick) || !chick.isAlive()) {
            // The chick is gone: its death burst runs once
            // and the ability ends with its payload.
            state.putInt("gone", 1);
            state.putInt("done", 1);
            if (state.contains("chick")) {
                UUID id = state.read("chick", UUIDUtil.CODEC).orElse(null);
                if (id != null) {
                    ctx.data().removeAlly(id);
                }
            }
            level.sendParticles(ParticleTypes.FIREWORK,
                state.getDoubleOr("cx", ctx.mob().getX()), state.getDoubleOr("cy", ctx.mob().getY()) + 0.3,
                state.getDoubleOr("cz", ctx.mob().getZ()), 8, 0.25, 0.25, 0.25, 0.08);
            return;
        }
        if (ticks % BABYSIT_INTERVAL != 0) {
            return;
        }
        state.putDouble("cx", chick.getX());
        state.putDouble("cy", chick.getY());
        state.putDouble("cz", chick.getZ());
        if (ticks % 20 == 0) {
            // The golden sheen (see the class comment).
            chick.addEffect(new MobEffectInstance(MobEffects.GLOWING, 40, 0));
            chick.addEffect(new MobEffectInstance(MobEffects.SPEED, 40, 0));
        }
        LivingEntity target = ctx.target();
        if (target == null) {
            return;
        }
        if (chick.getTarget() != target) {
            chick.setTarget(target);
        }
        double distSqr = chick.distanceToSqr(target);
        if (distSqr > STRIKE_RANGE * STRIKE_RANGE) {
            chick.getNavigation().moveTo(target, DRIVE_SPEED);
        } else if (ticks - state.getIntOr("peck", -STRIKE_INTERVAL_TICKS) >= STRIKE_INTERVAL_TICKS) {
            state.putInt("peck", ticks);
            target.hurtServer(level, level.damageSources().mobAttack(chick), STRIKE_DAMAGE);
            level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY() + 0.3, target.getZ(),
                3, 0.25, 0.2, 0.25, 0.08);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.state().getIntOr("done", 0) == 1 || ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        // An unhatched egg is discarded silently (see the
        // class comment).
        if (state.contains("egg")) {
            UUID eggId = state.read("egg", UUIDUtil.CODEC).orElse(null);
            Entity display = eggId != null ? level.getEntity(eggId) : null;
            if (display != null) {
                display.discard();
            }
        }
        if (state.contains("chick")) {
            UUID id = state.read("chick", UUIDUtil.CODEC).orElse(null);
            if (id != null) {
                ctx.data().removeAlly(id);
                Entity found = level.getEntity(id);
                if (found instanceof Chicken chick) {
                    MobWarData.get(chick).removeAlly(caster.getUUID());
                    if (chick.isAlive()) {
                        level.sendParticles(ParticleTypes.FIREWORK, chick.getX(), chick.getY() + 0.3,
                            chick.getZ(), 8, 0.25, 0.25, 0.25, 0.08);
                        chick.discard();
                    }
                }
            }
        }
    }
}
