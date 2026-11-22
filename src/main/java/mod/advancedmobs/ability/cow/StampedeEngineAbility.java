package mod.advancedmobs.ability.cow;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Stampede Engine (Cow ability 1 of 5, mobility / attack).
 *
 * WHAT IT DOES: the cow drops its head, bellows, and takes
 * off at a dead run straight at its target with dust
 * pouring off its hooves. Unlike a one-shot charge, the
 * stampede keeps going for its whole duration: every enemy
 * the cow runs through takes trample damage and a heavy
 * knockback, each one only once, so a cow that barrels
 * down a line of enemies chains straight through the whole
 * crowd.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the cow on the ground and its current target within 16
 *   blocks (distance squared 256).
 * - Base numbers: a plain cow has 10 health and 0.2
 *   movement speed and NO ATTACK_DAMAGE attribute instance
 *   (verified against the jar: AbstractCow.createAttributes
 *   adds only MAX_HEALTH 10.0 and MOVEMENT_SPEED 0.2), so
 *   the trample damage below is a flat code value dealt
 *   with a mob attack source credited to the cow.
 * - Duration: exactly 100 ticks (5 seconds) from start().
 * - The engine (start(), all removed in end()):
 *   MOVEMENT_SPEED add_multiplied_base +1.0 (id
 *   advancedmobs:stampede_speed; the cow's 0.2 base
 *   becomes 0.4 before the navigation multiplier below),
 *   ATTACK_KNOCKBACK add_value +2.0 (id
 *   advancedmobs:stampede_knockback; the design's
 *   attack_knockback line, so any vanilla contact the cow
 *   makes mid-run also hits harder), and SPEED I for 120
 *   ticks (the design's speed effect line; it outlives the
 *   run slightly on paper but end() removes it, so it
 *   never does).
 * - Steering: the cow's own navigation does the running
 *   (never NoAi, never code velocity): every 5 ticks the
 *   navigation is re-pathed to the target's live position
 *   at speed multiplier 2.0. If the target dies or is lost
 *   mid-stampede, the cow keeps charging to the last
 *   recorded target position (the state tag's tx/ty/tz,
 *   refreshed every tick the target lives) and the run
 *   simply plays out its 100 ticks.
 * - The trample (every tick, one bounded radius-2.2 query,
 *   1.0 below to 2.0 above): each enemy NOT already in the
 *   hit list takes 5.0 damage as a mob attack credited to
 *   the cow, plus the launch: 1.4 horizontal along the
 *   cow's horizontal look direction and 0.45 up,
 *   hurtMarked set. The victim's UUID then joins the hit
 *   list and it cannot be trampled again this activation:
 *   the chain comes from hitting NEW enemies as the cow
 *   plows forward, not from re-hitting the same one. The
 *   hit list is capped at 32 entries; a stampede that has
 *   already flattened 32 enemies tramples no more (the run
 *   and the dust continue), which bounds both the state
 *   tag and the per-tick membership checks.
 * - Sound: the cow's classic variant ambient sound plays
 *   at start at volume 1.5, pitch 0.8 (the bellow).
 *
 * WHAT IT LOOKS LIKE: a bellow, then a cow at full gallop
 * trailing 2 tan DUST motes at its hooves every tick and 2
 * dirt BLOCK chunks every 2 ticks, enemies flying sideways
 * off its horns as it passes through them.
 *
 * PERFORMANCE: per tick one bounded radius-2.2 entity
 * query, one hit-list membership pass over at most 32
 * stored UUIDs per candidate, and the dust emissions; the
 * re-path runs every 5 ticks only. Particle budget per
 * activation: dust 100 times 2 = 200, dirt 50 times 2 =
 * 100, total at most 300 particles. The dust and block
 * options are pre-built static finals; the modifiers are
 * built at start() and removed exactly in end().
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply. DUST and BLOCK are particles, not placed
 * blocks.
 *
 * State tag keys: "tx", "ty", "tz" (last known target
 * position), "hit" (list of compounds, one per trampled
 * enemy: "id", the enemy's UUID).
 */
public final class StampedeEngineAbility implements MobAbility {

    private static final int DURATION_TICKS = 100;
    private static final int REPATH_INTERVAL = 5;
    private static final int HIT_LIST_CAP = 32;
    private static final double NAV_SPEED = 2.0;
    private static final double HIT_RADIUS = 2.2;
    private static final double TRAMPLE_DAMAGE = 5.0;
    private static final double LAUNCH_HORIZONTAL = 1.4;
    private static final double LAUNCH_VERTICAL = 0.45;
    private static final DustParticleOptions HOOF_DUST = new DustParticleOptions(0xB49B72, 1.2f);
    private static final BlockParticleOption DIRT_BLOCK =
        new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DIRT.defaultBlockState());
    private static final Identifier SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "stampede_speed");
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "stampede_knockback");

    @Override
    public String id() {
        return "stampede_engine";
    }

    @Override
    public String displayName() {
        return "Stampede Engine";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().onGround()
            && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ctx.state().put("hit", new ListTag());
        LivingEntity target = ctx.target();
        if (target != null) {
            recordTarget(ctx, target);
        }

        CowAbilitySupport.addModifier(caster, Attributes.MOVEMENT_SPEED,
            new AttributeModifier(SPEED_ID, 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        CowAbilitySupport.addModifier(caster, Attributes.ATTACK_KNOCKBACK,
            new AttributeModifier(KNOCKBACK_ID, 2.0, AttributeModifier.Operation.ADD_VALUE));
        caster.addEffect(new MobEffectInstance(MobEffects.SPEED, 120, 0));

        ServerLevel level = ctx.serverLevel();
        if (level != null && CowAbilitySupport.cowSound() != null) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                CowAbilitySupport.cowSound(), SoundSource.HOSTILE, 1.5f, 0.8f);
        }
        if (target != null) {
            caster.getNavigation().moveTo(target, NAV_SPEED);
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

        LivingEntity target = ctx.target();
        if (target != null) {
            recordTarget(ctx, target);
        }
        if (ticks % REPATH_INTERVAL == 0) {
            // Re-path at the target's live position, or at
            // the last recorded one when the target is gone.
            caster.getNavigation().moveTo(
                ctx.state().getDoubleOr("tx", caster.getX()),
                ctx.state().getDoubleOr("ty", caster.getY()),
                ctx.state().getDoubleOr("tz", caster.getZ()), NAV_SPEED);
        }

        // Hoof dust with every stride of the run.
        level.sendParticles(HOOF_DUST,
            caster.getX(), caster.getY() + 0.2, caster.getZ(),
            2, 0.5, 0.12, 0.5, 0.08);
        if (ticks % 2 == 0) {
            level.sendParticles(DIRT_BLOCK,
                caster.getX(), caster.getY() + 0.15, caster.getZ(),
                2, 0.5, 0.1, 0.5, 0.1);
        }

        tramplePass(ctx, level);
    }

    /** The trample: every not-yet-hit enemy in the plow box takes the hit, once each. */
    private void tramplePass(AbilityContext ctx, ServerLevel level) {
        Mob caster = ctx.mob();
        ListTag hit = ctx.state().getListOrEmpty("hit");
        if (hit.size() >= HIT_LIST_CAP) {
            return;
        }
        Vec3 look = caster.getLookAngle();
        double lx = look.x;
        double lz = look.z;
        double length = Math.sqrt(lx * lx + lz * lz);
        if (length < 0.001) {
            lx = 0.0;
            lz = 1.0;
            length = 1.0;
        }
        lx /= length;
        lz /= length;

        List<LivingEntity> victims = CowAbilitySupport.enemiesInBox(
            ctx, caster.getX(), caster.getY(), caster.getZ(), HIT_RADIUS, 1.0, 2.0);
        for (LivingEntity victim : victims) {
            if (hit.size() >= HIT_LIST_CAP) {
                break;
            }
            if (alreadyHit(hit, victim.getUUID())) {
                continue;
            }
            victim.hurtServer(level, level.damageSources().mobAttack(caster), (float) TRAMPLE_DAMAGE);
            victim.setDeltaMovement(lx * LAUNCH_HORIZONTAL, LAUNCH_VERTICAL, lz * LAUNCH_HORIZONTAL);
            victim.hurtMarked = true;
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, victim.getUUID());
            hit.add(entry);
        }
        ctx.state().put("hit", hit);
    }

    /** Whether the given UUID is already in the trample hit list. */
    private boolean alreadyHit(ListTag hit, UUID id) {
        for (int i = 0; i < hit.size(); i++) {
            UUID stored = hit.getCompoundOrEmpty(i).read("id", UUIDUtil.CODEC).orElse(null);
            if (id.equals(stored)) {
                return true;
            }
        }
        return false;
    }

    /** Records the target's live position for steering after it dies or is lost. */
    private void recordTarget(AbilityContext ctx, LivingEntity target) {
        ctx.state().putDouble("tx", target.getX());
        ctx.state().putDouble("ty", target.getY());
        ctx.state().putDouble("tz", target.getZ());
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        CowAbilitySupport.removeModifier(caster, Attributes.MOVEMENT_SPEED, SPEED_ID);
        CowAbilitySupport.removeModifier(caster, Attributes.ATTACK_KNOCKBACK, KNOCKBACK_ID);
        caster.removeEffect(MobEffects.SPEED);
    }
}
