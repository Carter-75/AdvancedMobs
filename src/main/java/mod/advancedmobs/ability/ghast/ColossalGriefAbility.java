package mod.advancedmobs.ability.ghast;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
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
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.phys.Vec3;

/**
 * Colossal Grief (Ghast ability 2 of 5, transformation).
 *
 * WHAT IT DOES: the ghast inflates into a true sky-whale, two
 * and a half times its birth size, blotting out the light,
 * tears streaming constantly from its eyes while its crying
 * carries across the whole arena. In this form it periodically
 * lobs siege meteors: real ghast fireballs built with double
 * the vanilla explosion power, so each blast tears a hole
 * twice as wide. The price is speed: the bulk flies 40 percent
 * slower and wallows through its turns. When the form ends it
 * deflates in a rush of cloud and is left weak and slow for a
 * few seconds.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists within 24 blocks
 *   (distance squared 576).
 * - The form (start(), all removed in end() under fixed ids):
 *   SCALE add_multiplied_base +1.5 (two and a half times birth
 *   size; id advancedmobs:colossal_grief_scale), MAX_HEALTH
 *   add_value +20 (the ghast's vanilla 10 health becomes 30;
 *   id advancedmobs:colossal_grief_health) with the ghast
 *   healed by the same 20 on entry so it arrives at its new
 *   full health, and FLYING_SPEED add_multiplied_base -0.4
 *   (40 percent slower flight; id
 *   advancedmobs:colossal_grief_speed; the shared helper
 *   tolerates a missing attribute instance, in which case the
 *   slow simply does not apply).
 * - Siege meteors (tick(), every 100 ticks, first at tick
 *   100): when a live target exists, the ghast launches one
 *   LargeFireball of its own: constructed with explosion
 *   power 2 (vanilla ghast fireballs are power 1, so the
 *   blast radius doubles), spawned at the ghast's mouth (its
 *   center shifted 2.0 blocks toward the target and 0.5
 *   down), aimed at the target's center with the vanilla
 *   acceleration vector shape (direction normalized, scaled
 *   by 0.15), and given an initial velocity of 0.5 along the
 *   same direction. At most 5 meteors per form (ticks 100,
 *   200, 300, 400, 500). A GHAST_SHOOT (volume 1.5, pitch
 *   0.6) marks each launch.
 * - What "its fireballs become siege meteors" can and cannot
 *   mean: a LargeFireball's explosion power is a private
 *   field set only by its constructor, verified against the
 *   real 26.2 jar; there is no setter and no attribute feeds
 *   it, so the fireballs the ghast's vanilla attack goal
 *   fires during the form keep power 1 and cannot be upgraded
 *   in flight. The siege meteors above are therefore the
 *   ability's own extra launches on a fixed schedule, in
 *   addition to the vanilla volleys. Their explosions run
 *   through vanilla LargeFireball behavior, which checks the
 *   mobGriefing game rule itself for block damage, so the
 *   terrain rule holds with no extra code here.
 * - The crying: DRIPPING_WATER tears every 2 ticks (2
 *   particles a pulse at the front of the body below eye
 *   height, the same tear placement Wailing Shockwave uses,
 *   scaled to the bigger body). A GHAST_AMBIENT wail at
 *   volume 3.0 (volume above 1.0 is what carries the sound
 *   across the arena) plays at start and again every 200
 *   ticks.
 * - Duration: 600 ticks (30 seconds), hard finish.
 * - The deflate (end()): the three modifiers come off, the
 *   ghast's health is clamped to its restored maximum (the
 *   Royal Jelly pattern), 24 CLOUD particles rush out of its
 *   body, and the vulnerability lands: WEAKNESS I and
 *   SLOWNESS I for 60 ticks each, applied by end() itself so
 *   they outlive the form and expire on their own. If the
 *   ghast died mid-form the framework termination path runs
 *   this same end(); applying effects to a dead mob is a
 *   harmless no-op.
 *
 * WHAT IT LOOKS LIKE: a ghast the size of a small building,
 * tears raining steadily from its eyes, its deep wails audible
 * from anywhere in the arena, lobbing oversized fireballs
 * whose explosions dwarf its normal shots, then collapsing
 * back down through a burst of cloud, drifting weak and slow.
 *
 * PERFORMANCE: per tick the work is the tear pulse every 2
 * ticks (2 particles), the wail check every 200 ticks, and
 * the meteor check every 100 ticks (one projectile spawn).
 * No entity queries at all. Particle budget per activation:
 * tears at most 300 pulses times 2 = 600, the start burst 20
 * CLOUD, the deflate 24 CLOUD, total at most 644 particles
 * spread over 30 seconds.
 *
 * CLEANUP: end() removes all three attribute modifiers by id,
 * clamps health, and vents the cloud. The meteors already
 * launched are ordinary projectiles and fly on without it.
 *
 * TERRAIN: this ability places no blocks itself; its meteors'
 * explosion block damage is vanilla's own mobGriefing-gated
 * behavior (see the siege meteor note above).
 *
 * State tag keys: none beyond the framework's. The meteor and
 * wail schedules are pure functions of ctx.ticksActive().
 */
public final class ColossalGriefAbility implements MobAbility {

    private static final int DURATION_TICKS = 600;
    private static final int METEOR_INTERVAL = 100;
    private static final int SIEGE_EXPLOSION_POWER = 2;
    private static final double MAX_HEALTH_BONUS = 20.0;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "colossal_grief_scale");
    private static final Identifier HEALTH_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "colossal_grief_health");
    private static final Identifier SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "colossal_grief_speed");

    @Override
    public String id() {
        return "colossal_grief";
    }

    @Override
    public String displayName() {
        return "Colossal Grief";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return ctx.target() != null && ctx.mob().distanceToSqr(ctx.target()) <= 576.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        GhastAbilitySupport.addModifier(mob, Attributes.SCALE,
            new AttributeModifier(SCALE_ID, 1.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        GhastAbilitySupport.addModifier(mob, Attributes.MAX_HEALTH,
            new AttributeModifier(HEALTH_ID, MAX_HEALTH_BONUS, AttributeModifier.Operation.ADD_VALUE));
        mob.setHealth(Math.min(mob.getMaxHealth(), mob.getHealth() + (float) MAX_HEALTH_BONUS));
        GhastAbilitySupport.addModifier(mob, Attributes.FLYING_SPEED,
            new AttributeModifier(SPEED_ID, -0.4, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.sendParticles(ParticleTypes.CLOUD, mob.getX(), mob.getY() + mob.getBbHeight() * 0.5, mob.getZ(),
                20, mob.getBbWidth() * 0.5, mob.getBbHeight() * 0.4, mob.getBbWidth() * 0.5, 0.08);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.GHAST_AMBIENT,
                SoundSource.HOSTILE, 3.0f, 0.7f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        Mob mob = ctx.mob();

        if (ticks % 2 == 0) {
            // The tears (see the class comment).
            Vec3 look = mob.getLookAngle();
            double eyeX = mob.getX() + look.x * mob.getBbWidth() * 0.5;
            double eyeZ = mob.getZ() + look.z * mob.getBbWidth() * 0.5;
            level.sendParticles(ParticleTypes.DRIPPING_WATER,
                eyeX, mob.getY() + mob.getBbHeight() * 0.55, eyeZ,
                2, mob.getBbWidth() * 0.25, 0.1, mob.getBbWidth() * 0.25, 0.0);
        }
        if (ticks % 200 == 0) {
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.GHAST_AMBIENT,
                SoundSource.HOSTILE, 3.0f, 0.7f);
        }
        if (ticks % METEOR_INTERVAL == 0) {
            launchSiegeMeteor(ctx, level);
        }
    }

    /** Launches one siege meteor at the live target (see the class comment). */
    private static void launchSiegeMeteor(AbilityContext ctx, ServerLevel level) {
        Mob mob = ctx.mob();
        LivingEntity target = ctx.target();
        if (target == null) {
            return;
        }
        Vec3 from = new Vec3(mob.getX(), mob.getY() + mob.getBbHeight() * 0.4, mob.getZ());
        Vec3 to = new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ());
        Vec3 direction = to.subtract(from);
        if (direction.lengthSqr() < 1.0e-4) {
            return;
        }
        direction = direction.normalize();
        LargeFireball meteor = new LargeFireball(level, mob, direction.scale(0.15), SIEGE_EXPLOSION_POWER);
        Vec3 mouth = from.add(direction.scale(2.0)).add(0.0, -0.5, 0.0);
        meteor.snapTo(mouth.x, mouth.y, mouth.z, 0.0f, 0.0f);
        meteor.setDeltaMovement(direction.scale(0.5));
        if (level.addFreshEntity(meteor)) {
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.GHAST_SHOOT,
                SoundSource.HOSTILE, 1.5f, 0.6f);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        GhastAbilitySupport.removeModifier(mob, Attributes.SCALE, SCALE_ID);
        GhastAbilitySupport.removeModifier(mob, Attributes.FLYING_SPEED, SPEED_ID);
        GhastAbilitySupport.removeModifier(mob, Attributes.MAX_HEALTH, HEALTH_ID);
        if (mob.getHealth() > mob.getMaxHealth()) {
            mob.setHealth(mob.getMaxHealth());
        }
        // The deflated vulnerability (see the class comment):
        // applied here so it outlives the form and expires on
        // its own.
        mob.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60, 0));
        mob.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 0));
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.sendParticles(ParticleTypes.CLOUD, mob.getX(), mob.getY() + mob.getBbHeight() * 0.5, mob.getZ(),
                24, mob.getBbWidth() * 0.5, mob.getBbHeight() * 0.4, mob.getBbWidth() * 0.5, 0.1);
        }
    }
}
