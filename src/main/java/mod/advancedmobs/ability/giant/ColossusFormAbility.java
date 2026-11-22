package mod.advancedmobs.ability.giant;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Colossus Form (Giant ability 2 of 5, transformation).
 *
 * WHAT IT DOES: the giant grows half again as large, its
 * silhouette looming over the fight while DUST_PILLAR dust
 * sheds off its shoulders as it moves. In this form its
 * health pool swells by another giant's worth, its reach
 * extends well past its already huge arms, and it simply
 * walks up hills and over walls without jumping: ordinary
 * mobs barely reach its knees. When the duration runs out it
 * shrinks back with all bonuses removed.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the giant's current target within 24 blocks (distance
 *   squared 576). A vanilla giant's base numbers, for scale:
 *   100 health, 50 attack damage, 0.5 movement speed (verified
 *   against the 26.2 jar).
 * - Duration: exactly 400 ticks (20 seconds) from start().
 * - Attribute changes, all transient modifiers added in
 *   start() and removed in end(), ids under
 *   advancedmobs:colossus_form_*:
 *     SCALE                    add_multiplied_base  +0.5  (1.5x size)
 *     MAX_HEALTH               add_value            +100.0 (100 to 200)
 *     STEP_HEIGHT              add_value            +3.0  (0.6 to 3.6, walks
 *                                                          up 3-block walls)
 *     ENTITY_INTERACTION_RANGE add_value            +2.5  (reach extends)
 *   All four go through the null-tolerant package helper: an
 *   attribute the giant somehow lacks is simply skipped.
 * - On start the giant is healed by the +100 max health the
 *   modifier adds, so the extra pool arrives full. On end,
 *   after the MAX_HEALTH modifier is removed, current health
 *   is clamped down to the restored maximum.
 * - No state tag entries are needed: the whole activation is
 *   the four modifiers plus the tick counter, so a resumed
 *   activation after a chunk reload behaves identically.
 *
 * WHAT IT LOOKS LIKE: the model and hitbox grow to 1.5x via
 * the SCALE attribute (a vanilla giant is already about 12
 * blocks tall, so the form stands about 18). Every 4 ticks,
 * while the giant is moving (horizontal speed squared above
 * 0.001), 3 DUST_PILLAR particles of the block underfoot
 * shed at shoulder height (85 percent of its bounding box
 * height), spread across its body width.
 *
 * PERFORMANCE: tick() returns immediately except every 4th
 * tick, where it reads the ground block once and sends 3
 * particles when moving. No entity scans at all. Particle
 * budget per activation: at most 100 pulses times 3, total
 * at most 300 particles.
 *
 * CLEANUP: end() removes all four modifiers by id
 * (removeModifier is a no-op when absent) and clamps health.
 * If the giant dies mid-form the modifiers die with the
 * entity; nothing else was created.
 */
public final class ColossusFormAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "colossus_form_scale");
    private static final Identifier HEALTH_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "colossus_form_health");
    private static final Identifier STEP_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "colossus_form_step");
    private static final Identifier REACH_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "colossus_form_reach");

    @Override
    public String id() {
        return "colossus_form";
    }

    @Override
    public String displayName() {
        return "Colossus Form";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return ctx.target() != null && ctx.mob().distanceToSqr(ctx.target()) <= 576.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        GiantAbilitySupport.addModifier(mob, Attributes.SCALE,
            new AttributeModifier(SCALE_ID, 0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        GiantAbilitySupport.addModifier(mob, Attributes.MAX_HEALTH,
            new AttributeModifier(HEALTH_ID, 100.0, AttributeModifier.Operation.ADD_VALUE));
        GiantAbilitySupport.addModifier(mob, Attributes.STEP_HEIGHT,
            new AttributeModifier(STEP_ID, 3.0, AttributeModifier.Operation.ADD_VALUE));
        GiantAbilitySupport.addModifier(mob, Attributes.ENTITY_INTERACTION_RANGE,
            new AttributeModifier(REACH_ID, 2.5, AttributeModifier.Operation.ADD_VALUE));
        // The +100 max health arrives full.
        mob.setHealth(Math.min(mob.getMaxHealth(), mob.getHealth() + 100.0f));
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.ticksActive() % 4 != 0) {
            return; // Cheap early-out: the dust pulse runs every 4 ticks only.
        }
        Mob mob = ctx.mob();
        if (mob.getDeltaMovement().horizontalDistanceSqr() <= 0.001) {
            return; // Standing still: no dust sheds.
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        BlockState ground = level.getBlockState(mob.blockPosition().below());
        if (ground.isAir()) {
            return;
        }
        double spread = mob.getBbWidth() * 0.5;
        level.sendParticles(new BlockParticleOption(ParticleTypes.DUST_PILLAR, ground),
            mob.getX(), mob.getY() + mob.getBbHeight() * 0.85, mob.getZ(),
            3, spread, 0.4, spread, 0.03);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        GiantAbilitySupport.removeModifier(mob, Attributes.SCALE, SCALE_ID);
        GiantAbilitySupport.removeModifier(mob, Attributes.STEP_HEIGHT, STEP_ID);
        GiantAbilitySupport.removeModifier(mob, Attributes.ENTITY_INTERACTION_RANGE, REACH_ID);
        GiantAbilitySupport.removeModifier(mob, Attributes.MAX_HEALTH, HEALTH_ID);
        // Clamp health to the restored maximum if the giant was above it.
        if (mob.getHealth() > mob.getMaxHealth()) {
            mob.setHealth(mob.getMaxHealth());
        }
    }
}
