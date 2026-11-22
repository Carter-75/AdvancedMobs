package mod.advancedmobs.ability.piglinbrute;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * Blackstone Bulwark Form (Piglin Brute ability 2 of 5,
 * transformation).
 *
 * WHAT IT DOES: the brute grows into a fortress: half again
 * as tall, with pauldrons of polished blackstone riding on
 * its shoulders, offset out from the body and gleaming at
 * full brightness. Its toughness and knockback resistance
 * climb to siege levels, and its axe swings sweep wider with
 * every blow: anything standing near a mob the brute hits
 * catches a share of the swing. When the form ends the
 * pauldrons shatter into debris.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the brute's current target within 12 blocks (distance
 *   squared 144). A siege form for a fight already joined,
 *   not an opener at range.
 * - Duration: exactly 400 ticks (20 seconds).
 * - The growth (start(), all removed in end()): SCALE
 *   add_multiplied_base +0.5 (1.5 times size, half again as
 *   tall), ARMOR_TOUGHNESS add_value +8 (0 to 8, real damage
 *   reduction against heavy hits), KNOCKBACK_RESISTANCE
 *   add_value +0.7 (siege levels: ordinary hits barely move
 *   it), SWEEPING_DAMAGE_RATIO add_value +0.5 (the design's
 *   sweep dial; see the sweep note below).
 * - The pauldrons: two block displays of polished blackstone
 *   at scale 0.9, full brightness (block and sky light 15),
 *   spawned in start() and tracked in the state tag. Every
 *   tick each one is teleported (teleport duration 2, so it
 *   glides) to a shoulder anchor: 0.68 blocks to the brute's
 *   left or right of its center line, at 80 percent of its
 *   (grown) bounding box height, the anchors computed from
 *   the brute's live yaw so the pauldrons swing around with
 *   it as it turns. They are positioned per tick rather than
 *   mounted as vanilla passengers because a passenger's model
 *   offset does not inherit the vehicle's yaw in 26.2: a real
 *   passenger pauldron would stay facing one world direction
 *   while the brute turns under it. Per-tick positioning is
 *   the package's way of making them ride the shoulders in
 *   fact. The displays are pure visuals: no hitbox, no
 *   collision, no health, and the brute's real protection is
 *   the attribute set above, not the stone.
 * - The sweep: landed melee hits are polled through the bite
 *   clock (PiglinBruteAbilitySupport.primeHitClock and
 *   consumeLandedHit, primed in start() so pre-activation
 *   hits never count). Each fresh hit the brute lands on an
 *   enemy ALSO deals 3.5 points of typed mob-attack damage
 *   to every OTHER enemy within 2.4 blocks of the victim
 *   (one bounded query per landed hit, never per tick): the
 *   wide fortress swing, about half the brute's base axe
 *   damage of 7. A burst of 6 blackstone BLOCK_CRUMBLE marks
 *   each swept victim. The SWEEPING_DAMAGE_RATIO modifier
 *   above is applied per the design's Uses list; in 26.2 mob
 *   melee cadence and arcs are goal-driven and do not read
 *   that attribute the way a player's swing does, so the
 *   code sweep here is what actually widens the blows, and
 *   the modifier takes further effect with no code change
 *   if a future version routes mob swings through it (the
 *   Frenzy Gore ATTACK_SPEED precedent).
 * - Gleam: every 20 ticks, 3 ENCHANT particles drift at each
 *   pauldron anchor: the full-brightness stone catching the
 *   light.
 *
 * WHAT IT LOOKS LIKE: a deep PIGLIN_BRUTE_ANGRY (volume 1.0,
 * pitch 0.7) as the brute swells, the two gleaming blackstone
 * pauldrons riding its shoulders and turning with it, enchant
 * glints off the stone, blackstone chips flying off swept
 * victims, and at the end a BASALT_BREAK (volume 1.0) as both
 * pauldrons shatter into 12 BLOCK_CRUMBLE each.
 *
 * TERRAIN: this ability places and breaks no blocks in
 * either mobGriefing mode; the pauldrons are display
 * entities, not blocks, so the terrain gate does not apply.
 *
 * PERFORMANCE: per tick the work is 2 display resolves and
 * teleports plus one integer timestamp compare; the sweep
 * query runs only on ticks where a hit actually landed (a
 * brute lands a hit every couple of seconds at most), and
 * the gleam is a 6-particle send every 20 ticks. Particle
 * budget per activation: gleam 20 sends times 6 = 120,
 * sweeps generously 10 landed hits times (at most 4 victims
 * times 6) = 240, shatter 24, total at most 384.
 *
 * CLEANUP: end() removes all four modifiers and discards
 * both pauldrons with their shatter burst. If the brute dies
 * mid-form the framework termination path runs this same
 * end(), so the fortress never outlives it.
 *
 * State tag keys: "pauldrons" (list of compounds, one per
 * display: "id", the display's UUID), "hit_ts" (the bite
 * clock, owned by PiglinBruteAbilitySupport).
 */
public final class BlackstoneBulwarkFormAbility implements MobAbility {

    static final int DURATION_TICKS = 400;
    private static final float PAULDRON_SCALE = 0.9f;
    private static final double SHOULDER_OFFSET = 0.68;
    private static final double SHOULDER_HEIGHT_FRACTION = 0.80;
    private static final double SWEEP_RADIUS = 2.4;
    private static final float SWEEP_DAMAGE = 3.5f;
    private static final BlockParticleOption BLACKSTONE_CRUMBLE =
        new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, Blocks.POLISHED_BLACKSTONE.defaultBlockState());
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "blackstone_bulwark_scale");
    private static final Identifier TOUGHNESS_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "blackstone_bulwark_toughness");
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "blackstone_bulwark_knockback");
    private static final Identifier SWEEP_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "blackstone_bulwark_sweep");

    @Override
    public String id() {
        return "blackstone_bulwark_form";
    }

    @Override
    public String displayName() {
        return "Blackstone Bulwark Form";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        // The fortress growth (see the spec comment).
        PiglinBruteAbilitySupport.addModifier(mob, Attributes.SCALE,
            new AttributeModifier(SCALE_ID, 0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        PiglinBruteAbilitySupport.addModifier(mob, Attributes.ARMOR_TOUGHNESS,
            new AttributeModifier(TOUGHNESS_ID, 8.0, AttributeModifier.Operation.ADD_VALUE));
        PiglinBruteAbilitySupport.addModifier(mob, Attributes.KNOCKBACK_RESISTANCE,
            new AttributeModifier(KNOCKBACK_ID, 0.7, AttributeModifier.Operation.ADD_VALUE));
        PiglinBruteAbilitySupport.addModifier(mob, Attributes.SWEEPING_DAMAGE_RATIO,
            new AttributeModifier(SWEEP_ID, 0.5, AttributeModifier.Operation.ADD_VALUE));
        PiglinBruteAbilitySupport.primeHitClock(ctx);

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            ctx.state().put("pauldrons", new ListTag());
            return;
        }
        // The pauldrons ride in on the shoulders.
        for (int i = 0; i < 2; i++) {
            Entity display = PiglinBruteAbilitySupport.createBlockDisplay(level,
                Blocks.POLISHED_BLACKSTONE.defaultBlockState(), PAULDRON_SCALE);
            if (display == null) {
                continue;
            }
            double[] anchor = shoulderAnchor(mob, i == 0);
            display.snapTo(anchor[0], anchor[1], anchor[2], mob.getYRot(), 0.0f);
            if (level.addFreshEntity(display)) {
                PiglinBruteAbilitySupport.trackEntity(ctx.state(), "pauldrons", display);
            }
        }
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.PIGLIN_BRUTE_ANGRY,
            SoundSource.HOSTILE, 1.0f, 0.7f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob mob = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        // The pauldrons ride the shoulders, turning with the brute.
        ListTag pauldrons = ctx.state().getListOrEmpty("pauldrons");
        for (int i = 0; i < pauldrons.size(); i++) {
            Entity display = PiglinBruteAbilitySupport.resolveTracked(level, pauldrons.getCompoundOrEmpty(i));
            if (display == null) {
                continue;
            }
            double[] anchor = shoulderAnchor(mob, i == 0);
            display.teleportTo(anchor[0], anchor[1], anchor[2]);
            if (ctx.ticksActive() % 20 == 0) {
                level.sendParticles(ParticleTypes.ENCHANT, anchor[0], anchor[1], anchor[2],
                    3, 0.4, 0.3, 0.4, 0.4);
            }
        }
        // The wide swing: a landed hit sweeps the victim's neighbors.
        LivingEntity victim = PiglinBruteAbilitySupport.consumeLandedHit(ctx);
        if (victim != null) {
            runSweep(ctx, level, victim);
        }
    }

    /** The code sweep: the victim's enemy neighbors catch a share of the blow. */
    private void runSweep(AbilityContext ctx, ServerLevel level, LivingEntity victim) {
        Mob mob = ctx.mob();
        List<LivingEntity> bystanders = PiglinBruteAbilitySupport.enemiesInBox(ctx,
            victim.getX(), victim.getY(), victim.getZ(), SWEEP_RADIUS, 2.0, 2.0);
        for (LivingEntity bystander : bystanders) {
            if (bystander == victim) {
                continue;
            }
            bystander.hurtServer(level, level.damageSources().mobAttack(mob), SWEEP_DAMAGE);
            level.sendParticles(BLACKSTONE_CRUMBLE, bystander.getX(), bystander.getY() + 1.0,
                bystander.getZ(), 6, 0.4, 0.5, 0.4, 0.15);
        }
    }

    /**
     * A shoulder anchor point: lateral offset to the brute's
     * left (first) or right (second) of its center line, at 80
     * percent of its bounding box height, from its live yaw.
     * Facing south (yaw 0) the left hand points east, which the
     * (lookZ, -lookX) perpendicular reproduces; see the support
     * patterns in the other packages.
     */
    private static double[] shoulderAnchor(Mob mob, boolean left) {
        double yawRad = Math.toRadians(mob.getYRot());
        double lookX = -Math.sin(yawRad);
        double lookZ = Math.cos(yawRad);
        double sideX = left ? lookZ : -lookZ;
        double sideZ = left ? -lookX : lookX;
        return new double[] {
            mob.getX() + sideX * SHOULDER_OFFSET,
            mob.getY() + mob.getBbHeight() * SHOULDER_HEIGHT_FRACTION,
            mob.getZ() + sideZ * SHOULDER_OFFSET
        };
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        PiglinBruteAbilitySupport.removeModifier(mob, Attributes.SCALE, SCALE_ID);
        PiglinBruteAbilitySupport.removeModifier(mob, Attributes.ARMOR_TOUGHNESS, TOUGHNESS_ID);
        PiglinBruteAbilitySupport.removeModifier(mob, Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_ID);
        PiglinBruteAbilitySupport.removeModifier(mob, Attributes.SWEEPING_DAMAGE_RATIO, SWEEP_ID);

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        // The pauldrons shatter into debris as the form ends.
        ListTag pauldrons = ctx.state().getListOrEmpty("pauldrons");
        boolean shattered = false;
        for (int i = 0; i < pauldrons.size(); i++) {
            Entity display = PiglinBruteAbilitySupport.resolveTracked(level, pauldrons.getCompoundOrEmpty(i));
            if (display == null) {
                continue;
            }
            level.sendParticles(BLACKSTONE_CRUMBLE, display.getX(), display.getY(), display.getZ(),
                12, 0.4, 0.4, 0.4, 0.2);
            display.discard();
            shattered = true;
        }
        if (shattered) {
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.BASALT_BREAK,
                SoundSource.HOSTILE, 1.0f, 0.8f);
        }
    }
}
