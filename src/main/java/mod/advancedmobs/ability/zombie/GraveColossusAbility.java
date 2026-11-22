package mod.advancedmobs.ability.zombie;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Grave Colossus (Zombie ability 1 of 5, transformation).
 *
 * WHAT IT DOES: the zombie swells into a hulking colossus, twice its normal
 * height, wreathed in soul wisps and grave dust. While big it is tougher,
 * hits harder, and steps clean over two-block ledges; its footsteps shake
 * debris loose from the ground. When the duration runs out it shrinks back
 * to normal size with all bonuses removed.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the zombie's
 *   current target within 24 blocks (distance squared 576).
 * - Duration: exactly 200 ticks (10 seconds) from start().
 * - Attribute changes, all transient modifiers added in start() and removed
 *   in end(), ids under advancedmobs:grave_colossus_*:
 *     SCALE            add_value            +1.0  (total scale 2.0, double height)
 *     MAX_HEALTH       add_value            +20.0 (a base zombie goes 20 to 40)
 *     ATTACK_DAMAGE    add_multiplied_base  +0.5  (+50 percent melee damage)
 *     STEP_HEIGHT      add_value            +1.5  (0.6 to 2.1, walks up 2-block ledges)
 * - On start the zombie is healed by the +20 max health the modifier adds,
 *   so the extra pool arrives full. On end, after the MAX_HEALTH modifier
 *   is removed, current health is clamped down to the restored maximum.
 * - No state tag entries are needed: the whole activation is the four
 *   modifiers plus the tick counter, so a resumed activation after a chunk
 *   reload behaves identically (modifiers are part of the entity's saved
 *   attribute state while transient modifiers last; see CLEANUP note).
 *
 * WHAT IT LOOKS LIKE: the model and hitbox double via the SCALE attribute.
 * Every 4 ticks a wisp pulse at the body: 3 SOUL particles (the rising
 * soul-sand-valley wisps) and 2 gravestone-gray DUST motes (color 0.45,
 * 0.45, 0.48, scale 1.3). Every 5 ticks, while the zombie is moving on the
 * ground (horizontal speed squared above 0.004), 3 BLOCK_CRUMBLE particles
 * of the block under its feet shake loose at ground level.
 *
 * PERFORMANCE: tick() returns immediately except on the two pulse ticks
 * (ticksActive mod 4 and mod 5). No entity scans at all. No allocations in
 * the pulse path beyond the two pre-built particle option objects (static
 * finals below). Particle budget per activation, hard caps by
 * construction: at most 50 wisp pulses times 5 particles = 250, plus at
 * most 40 footstep pulses times 3 = 120, total at most 370 particles.
 *
 * CLEANUP: end() removes all four modifiers by id (removeModifier is a
 * no-op when absent) and clamps health. If the zombie dies mid-ability the
 * modifiers die with the entity; nothing else was created, so there is no
 * leftover state in the world.
 */
public final class GraveColossusAbility implements MobAbility {

    static final int DURATION_TICKS = 200;

    private static final Identifier SCALE_ID = Identifier.fromNamespaceAndPath("advancedmobs", "grave_colossus_scale");
    private static final Identifier HEALTH_ID = Identifier.fromNamespaceAndPath("advancedmobs", "grave_colossus_health");
    private static final Identifier DAMAGE_ID = Identifier.fromNamespaceAndPath("advancedmobs", "grave_colossus_damage");
    private static final Identifier STEP_ID = Identifier.fromNamespaceAndPath("advancedmobs", "grave_colossus_step");

    // 26.2 DustParticleOptions takes an RGB int color: 0.45, 0.45, 0.48 as 0x73737A.
    private static final DustParticleOptions GRAVE_DUST = new DustParticleOptions(0x73737A, 1.3f);

    @Override
    public String id() {
        return "grave_colossus";
    }

    @Override
    public String displayName() {
        return "Grave Colossus";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return ctx.target() != null && ctx.mob().distanceToSqr(ctx.target()) <= 576.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        addModifier(mob, Attributes.SCALE, new AttributeModifier(SCALE_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        addModifier(mob, Attributes.MAX_HEALTH, new AttributeModifier(HEALTH_ID, 20.0, AttributeModifier.Operation.ADD_VALUE));
        addModifier(mob, Attributes.ATTACK_DAMAGE, new AttributeModifier(DAMAGE_ID, 0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        addModifier(mob, Attributes.STEP_HEIGHT, new AttributeModifier(STEP_ID, 1.5, AttributeModifier.Operation.ADD_VALUE));
        // The +20 max health arrives full.
        mob.setHealth(Math.min(mob.getMaxHealth(), mob.getHealth() + 20.0f));
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        if (ticks % 4 == 0) {
            // Wisp pulse: soul wisps rising off the shoulders, gray grave dust.
            level.sendParticles(ParticleTypes.SOUL, mob.getX(), mob.getY() + 1.6, mob.getZ(),
                3, 0.7, 1.0, 0.7, 0.03);
            level.sendParticles(GRAVE_DUST, mob.getX(), mob.getY() + 2.0, mob.getZ(),
                2, 0.8, 0.8, 0.8, 0.01);
        }
        if (ticks % 5 == 0 && mob.onGround()
            && mob.getDeltaMovement().horizontalDistanceSqr() > 0.004) {
            // Footstep debris: crumble particles of the block underfoot.
            BlockState ground = level.getBlockState(mob.blockPosition().below());
            if (!ground.isAir()) {
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, ground),
                    mob.getX(), mob.getY() + 0.1, mob.getZ(), 3, 0.9, 0.05, 0.9, 0.02);
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        removeModifier(mob, Attributes.SCALE, SCALE_ID);
        removeModifier(mob, Attributes.ATTACK_DAMAGE, DAMAGE_ID);
        removeModifier(mob, Attributes.STEP_HEIGHT, STEP_ID);
        removeModifier(mob, Attributes.MAX_HEALTH, HEALTH_ID);
        // Clamp health to the restored maximum if the zombie was above it.
        if (mob.getHealth() > mob.getMaxHealth()) {
            mob.setHealth(mob.getMaxHealth());
        }
    }

    private static void addModifier(Mob mob, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
                                    AttributeModifier modifier) {
        AttributeInstance instance = mob.getAttribute(attribute);
        if (instance != null) {
            instance.addOrUpdateTransientModifier(modifier);
        }
    }

    private static void removeModifier(Mob mob, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
                                       Identifier id) {
        AttributeInstance instance = mob.getAttribute(attribute);
        if (instance != null) {
            instance.removeModifier(id);
        }
    }
}
