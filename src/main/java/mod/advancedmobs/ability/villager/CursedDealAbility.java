package mod.advancedmobs.ability.villager;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Items;

/**
 * Cursed Deal (Villager ability 3 of 5, control).
 *
 * WHAT IT DOES: the villager offers its enemy a trade too good to
 * refuse. A sheet of contract paperwork flutters across the fight
 * trailing angry huffs, and if it reaches the enemy, the deal is
 * signed: the enemy is saddled with weakness and unluck while the
 * villager pockets a cushion of absorption hearts and walks away
 * whistling.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   villager's current target within 12 blocks (distance squared
 *   144).
 * - The pocketed hearts (in start()): the caster gains
 *   ABSORPTION II (8 absorption points) for 260 ticks, removed
 *   exactly in end(). VILLAGER_TRADE plays at start at volume
 *   1.2, pitch 0.9 (the sales pitch), and again at ticks 60, 120,
 *   and 180 at pitch 1.0 (the whistling, in code as repeat trade
 *   chatter; there is no whistle sound in 26.2).
 * - The paperwork (ticks 1 to 8): one paper item display (scale
 *   0.6, "fixed" context) is created in start() at the caster's
 *   chest and lerped in a straight line to the target's recorded
 *   chest position over 8 ticks (its start and end points are in
 *   the state tag; the target position refreshes every tick it
 *   lives, and the lerp endpoint follows the refreshed position,
 *   so the contract homes loosely). 2 ANGRY_VILLAGER particles
 *   huff at the caster every 2 ticks during the flight (the
 *   design's scribbling huffs). The display is snapped per tick;
 *   no hit checks happen in this phase.
 * - The signing (at tick 8): the display is discarded in a burst
 *   of 6 ANGRY_VILLAGER plus 4 paper ITEM particles. If the
 *   caster's live target is an enemy (package rule) within 3.0
 *   blocks of the burst point, the deal is signed: the victim
 *   gains WEAKNESS I and UNLUCK I, each for 300 ticks (15
 *   seconds). Both effects ride their own clocks past the end of
 *   the ability (documented: a signed contract is not unsigned
 *   when the salesman leaves). If the target has escaped beyond
 *   3.0 blocks, the deal falls through: no curse, and the rest of
 *   the activation is only the huffing and the hearts.
 * - The huffing (ticks 9 to 240): every 20 ticks, 3
 *   ANGRY_VILLAGER particles at the cursed victim if the deal
 *   was signed (the contract smoldering on them), or at the
 *   caster if it was not (still annoyed about the paperwork).
 * - Duration: exactly 240 ticks (12 seconds). The framework
 *   cooldown starts at end(), as always.
 *
 * WHAT IT LOOKS LIKE: a villager producing a glowing cushion of
 * golden hearts, a single sheet of paper fluttering flat across
 * the fight in a trail of angry puffs, and, on a signing, the
 * victim sagging under grey weakness motes while the villager
 * chatters happily behind its hearts.
 *
 * PERFORMANCE: the flight is one display snap and at most 2
 * particles per tick for 8 ticks; the signing is one distance
 * check; the huffing is 3 particles every 20 ticks; the trade
 * chatter is one sound every 60 ticks. No entity queries
 * anywhere. Particle budget per activation: flight huffs 8,
 * signing burst 10, huffing at most 12 pulses times 3 = 36,
 * total at most 54 particles.
 *
 * TERRAIN: this ability changes no blocks (the paperwork is an
 * item display, a pure visual); the mobGriefing gate does not
 * apply.
 *
 * CLEANUP: end() removes the caster's ABSORPTION effect and
 * discards the paper display if it somehow still exists (it is
 * discarded at the signing on every normal path; this covers
 * termination mid-flight). The victim's curse effects are NOT
 * removed: they are the deal, and they expire on their own
 * clocks.
 *
 * State tag keys: "paper" (the display's UUID, stored directly),
 * "sx"/"sy"/"sz" (flight start), "bx"/"by"/"bz" (the current
 * burst point), "signed" (whether the curse landed), "victim"
 * (the cursed enemy's UUID when signed).
 */
public final class CursedDealAbility implements MobAbility {

    static final int DURATION_TICKS = 240;
    private static final int FLIGHT_TICKS = 8;
    private static final int ABSORPTION_TICKS = 260;
    private static final int CURSE_TICKS = 300;
    private static final double SIGN_RADIUS = 3.0;
    private static final int HUFF_INTERVAL = 20;
    private static final int CHATTER_INTERVAL = 60;

    @Override
    public String id() {
        return "cursed_deal";
    }

    @Override
    public String displayName() {
        return "Cursed Deal";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return ctx.target() != null && ctx.mob().distanceToSqr(ctx.target()) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        state.putBoolean("signed", false);

        // The pocketed hearts and the pitch.
        caster.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, ABSORPTION_TICKS, 1));
        if (level == null) {
            return;
        }
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.VILLAGER_TRADE,
            SoundSource.HOSTILE, 1.2f, 0.9f);

        // The paperwork leaves the caster's chest.
        double sx = caster.getX();
        double sy = caster.getY() + 1.3;
        double sz = caster.getZ();
        state.putDouble("sx", sx);
        state.putDouble("sy", sy);
        state.putDouble("sz", sz);
        Entity paper = VillagerAbilitySupport.createItemDisplay(level, Items.PAPER, 0.6f);
        if (paper != null) {
            paper.snapTo(sx, sy, sz);
            level.addFreshEntity(paper);
            state.store("paper", UUIDUtil.CODEC, paper.getUUID());
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        int ticks = ctx.ticksActive();

        if (ticks <= FLIGHT_TICKS) {
            tickFlight(ctx, level, ticks);
            if (ticks == FLIGHT_TICKS) {
                signDeal(ctx, level);
            }
            return;
        }

        if (ticks % CHATTER_INTERVAL == 0) {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(), SoundEvents.VILLAGER_TRADE,
                SoundSource.HOSTILE, 0.8f, 1.0f);
        }
        if (ticks % HUFF_INTERVAL == 0) {
            double hx = caster.getX(), hy = caster.getY() + 1.4, hz = caster.getZ();
            if (state.getBooleanOr("signed", false)) {
                Entity victim = VillagerAbilitySupport.resolveStored(level, state, "victim");
                if (victim != null) {
                    hx = victim.getX();
                    hy = victim.getY() + 1.4;
                    hz = victim.getZ();
                }
            }
            level.sendParticles(ParticleTypes.ANGRY_VILLAGER, hx, hy, hz, 3, 0.3, 0.3, 0.3, 0.02);
        }
    }

    /** The flight: lerp the paperwork from the caster toward the target's chest. */
    private void tickFlight(AbilityContext ctx, ServerLevel level, int ticks) {
        CompoundTag state = ctx.state();
        Entity paper = VillagerAbilitySupport.resolveStored(level, state, "paper");
        LivingEntity target = ctx.target();
        Mob caster = ctx.mob();
        double ex = target != null ? target.getX() : state.getDoubleOr("sx", caster.getX());
        double ey = target != null ? target.getY() + target.getBbHeight() * 0.6 : state.getDoubleOr("sy", caster.getY());
        double ez = target != null ? target.getZ() : state.getDoubleOr("sz", caster.getZ());
        double progress = ticks / (double) FLIGHT_TICKS;
        double x = state.getDoubleOr("sx", caster.getX()) + (ex - state.getDoubleOr("sx", caster.getX())) * progress;
        double y = state.getDoubleOr("sy", caster.getY()) + (ey - state.getDoubleOr("sy", caster.getY())) * progress;
        double z = state.getDoubleOr("sz", caster.getZ()) + (ez - state.getDoubleOr("sz", caster.getZ())) * progress;
        if (paper != null) {
            paper.snapTo(x, y, z);
        }
        state.putDouble("bx", x);
        state.putDouble("by", y);
        state.putDouble("bz", z);
        if (ticks % 2 == 0) {
            level.sendParticles(ParticleTypes.ANGRY_VILLAGER, caster.getX(), caster.getY() + 1.5, caster.getZ(),
                2, 0.25, 0.25, 0.25, 0.02);
        }
    }

    /** The signing at the end of the flight (see the class comment). */
    private void signDeal(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        Entity paper = VillagerAbilitySupport.resolveStored(level, state, "paper");
        double bx = state.getDoubleOr("bx", ctx.mob().getX());
        double by = state.getDoubleOr("by", ctx.mob().getY());
        double bz = state.getDoubleOr("bz", ctx.mob().getZ());
        if (paper != null) {
            paper.discard();
        }
        level.sendParticles(ParticleTypes.ANGRY_VILLAGER, bx, by, bz, 6, 0.3, 0.3, 0.3, 0.03);
        level.sendParticles(VillagerAbilitySupport.PAPER_ITEM, bx, by, bz, 4, 0.25, 0.25, 0.25, 0.05);

        LivingEntity target = ctx.target();
        if (target != null && VillagerAbilitySupport.isEnemy(ctx, target)
            && target.distanceToSqr(bx, by, bz) <= SIGN_RADIUS * SIGN_RADIUS) {
            target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, CURSE_TICKS, 0));
            target.addEffect(new MobEffectInstance(MobEffects.UNLUCK, CURSE_TICKS, 0));
            state.putBoolean("signed", true);
            state.store("victim", UUIDUtil.CODEC, target.getUUID());
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // The hearts were rented, not bought: they go back.
        ctx.mob().removeEffect(MobEffects.ABSORPTION);
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Entity paper = VillagerAbilitySupport.resolveStored(level, ctx.state(), "paper");
        if (paper != null) {
            paper.discard();
        }
    }
}
