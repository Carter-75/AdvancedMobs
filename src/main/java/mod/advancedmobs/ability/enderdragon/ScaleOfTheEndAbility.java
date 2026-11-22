package mod.advancedmobs.ability.enderdragon;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Scale of the End (Ender Dragon ability 4 of 5,
 * transformation).
 *
 * WHAT IT DOES: below a third of her health the dragon
 * enrages into her final stand form: she grows half again as
 * large, ENCHANT runes stream continuously off her wings,
 * her knockback climbs, her breath attacks come twice as
 * often, and every perch shakes the island with a ground
 * slam pulse. The form does not end until she does: once
 * enraged she stays enraged, even if the crystals heal her
 * back above a third.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   her health at or below one third of her maximum, plus
 *   her current target within 128 blocks (distance squared
 *   16384). Because the form persists once started, this
 *   trigger effectively gates on the first moment she drops
 *   to a third; after that the framework cooldown and the
 *   one-active-ability rule decide when it can re-fire, and
 *   re-firing simply refreshes the same modifiers.
 * - The growth and the climb (start(), removed in end()):
 *   SCALE +0.5 add_multiplied_base (1.5 times her size; the
 *   dragon carries the SCALE attribute, verified against the
 *   real 26.2 jar, so this one is real), ATTACK_KNOCKBACK
 *   +1.0 add_value (also a real attribute on her), and
 *   ATTACK_DAMAGE +6.0 add_value. The honest limit on that
 *   third one, verified against the jar: the dragon has NO
 *   ATTACK_DAMAGE attribute instance in 26.2 (her supplier
 *   is Mob.createMobAttributes plus MAX_HEALTH and
 *   CAMERA_DISTANCE, and createLivingAttributes does not
 *   carry ATTACK_DAMAGE), and her contact damage is dealt
 *   by fixed amounts in her own aiStep code, not read from
 *   any attribute. The modifier is therefore applied through
 *   the null-tolerant helper and is inert on a vanilla
 *   dragon; it costs nothing, it is removed in end() like
 *   the others, and the form's extra melee pressure is
 *   carried by the parts that do work: the 1.5 times body
 *   (a bigger contact box sweeping more of the arena), the
 *   knockback climb, the doubled breath, and the perch slam
 *   below. No stronger claim is made in game.
 * - The eyes and wing runes: the design calls for a custom
 *   render state for blazing eyes and streaming runes. This
 *   framework has no client render hook, so the visible tell
 *   is the rune stream itself: every 2 ticks, 6 ENCHANT
 *   particles shed from random points inside her bounding
 *   box, plus the deeper roar at the form's start
 *   (ENDER_DRAGON_GROWL, volume 3.0, pitch 0.65) and a
 *   16-particle DRAGON_BREATH burst around her. Documented
 *   approximation, the same standing the Ghast package's
 *   Mirror Ghasts note gives its shimmer.
 * - Breath twice as often: every 90 ticks, if she has a
 *   living target, she spits one extra vanilla dragon
 *   fireball at the target's center (the support launcher;
 *   the fireball's impact and breath cloud are vanilla),
 *   with an ENDER_DRAGON_SHOOT sound (volume 2.0). Her
 *   vanilla phases keep breathing on their own schedule;
 *   this extra shot on a 4.5 second beat is the doubling.
 * - The perch slam: checked every 10 ticks; while her
 *   current phase instance reports isSitting() and at least
 *   40 ticks have passed since the last slam, the island
 *   shakes: every enemy (EnderDragonAbilitySupport rules)
 *   within radius 11 of her, between 6 below and 4 above her
 *   feet, takes 5.0 points of typed mob-attack damage and
 *   is thrown: velocity set to 0.5 outward horizontally and
 *   0.8 up, hurtMarked set. The slam shows as 2 EXPLOSION
 *   particles and 12 BLOCK_CRUMBLE particles carrying the
 *   end stone state at her feet, with an ENDER_DRAGON_GROWL
 *   at volume 2.0, pitch 0.5. The slam changes no blocks:
 *   it is a shockwave, not a terrain effect, so the terrain
 *   rule does not gate it.
 * - Duration: the form's hard cap is 12000 ticks (10
 *   minutes), a safety bound only. By design it ends when
 *   she dies (the framework's termination path runs end()
 *   and strips the modifiers) or when the fight otherwise
 *   removes her; a dragon fight that somehow runs past the
 *   cap simply lets the form lapse and re-fire later.
 *
 * MOVEMENT INTERACTION: none. The ability never moves,
 * teleports, steers, or re-phases the dragon. The perch slam
 * only reads her phase (isSitting) to know she is on the
 * fountain; her phase controller keeps full ownership of
 * her movement for the whole form.
 *
 * PERFORMANCE: the per-tick cost is two modulo checks. The
 * rune pulse (every 2 ticks) is 6 particle sends with
 * positions computed from her bounding box and the context
 * random: no queries, no registry lookups, no allocations
 * beyond the particle calls. The fireball beat (every 90
 * ticks) is one projectile spawn. The slam check (every 10
 * ticks) is one phase read, and the slam itself is one
 * bounded radius 11 query. Particle budget: runes 6 per 2
 * ticks is 60 per second, 3600 per minute, so the absolute
 * ceiling over the 10-minute hard cap is about 36000 rune
 * particles; in practice the form lasts only the remainder
 * of a fight that is already two thirds over, a small
 * fraction of that. Fireballs add no sent particles of
 * their own (vanilla trails), slams add at most 14 particles
 * each and only while perched.
 *
 * State tag keys: "last_slam" (the tick of the most recent
 * perch slam, absent until the first).
 */
public final class ScaleOfTheEndAbility implements MobAbility {

    static final int HARD_CAP_TICKS = 12000;
    private static final int FIREBALL_INTERVAL = 90;
    private static final int SLAM_INTERVAL = 40;
    private static final double SLAM_RADIUS = 11.0;
    private static final float SLAM_DAMAGE = 5.0f;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "scale_of_the_end_scale");
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "scale_of_the_end_knockback");
    private static final Identifier DAMAGE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "scale_of_the_end_damage");
    private static final BlockParticleOption END_STONE_CRUMBLE =
        new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, Blocks.END_STONE.defaultBlockState());

    @Override
    public String id() {
        return "scale_of_the_end";
    }

    @Override
    public String displayName() {
        return "Scale of the End";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        Mob dragon = ctx.mob();
        if (dragon.getHealth() > dragon.getMaxHealth() / 3.0f) {
            return false;
        }
        LivingEntity target = ctx.target();
        return target != null && dragon.distanceToSqr(target) <= 16384.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob dragon = ctx.mob();
        EnderDragonAbilitySupport.addModifier(dragon, Attributes.SCALE,
            new AttributeModifier(SCALE_ID, 0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        EnderDragonAbilitySupport.addModifier(dragon, Attributes.ATTACK_KNOCKBACK,
            new AttributeModifier(KNOCKBACK_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        EnderDragonAbilitySupport.addModifier(dragon, Attributes.ATTACK_DAMAGE,
            new AttributeModifier(DAMAGE_ID, 6.0, AttributeModifier.Operation.ADD_VALUE));
        if (level != null) {
            level.playSound(null, dragon.getX(), dragon.getY(), dragon.getZ(),
                SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 3.0f, 0.65f);
            level.sendParticles(EnderDragonAbilitySupport.BREATH_PARTICLE,
                dragon.getX(), dragon.getY() + 3.0, dragon.getZ(),
                16, 3.0, 2.0, 3.0, 0.1);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        Mob dragon = ctx.mob();

        if (ticks % 2 == 0) {
            // The rune stream: ENCHANT motes shedding off her
            // body and wings.
            double halfWidth = dragon.getBbWidth() / 2.0;
            double x = dragon.getX() + (ctx.random().nextDouble() * 2.0 - 1.0) * halfWidth;
            double y = dragon.getY() + ctx.random().nextDouble() * dragon.getBbHeight();
            double z = dragon.getZ() + (ctx.random().nextDouble() * 2.0 - 1.0) * halfWidth;
            level.sendParticles(ParticleTypes.ENCHANT, x, y, z,
                6, 0.6, 0.4, 0.6, 0.4);
        }
        if (ticks % FIREBALL_INTERVAL == 0) {
            // The doubled breath: one extra fireball at her
            // target's center.
            LivingEntity target = ctx.target();
            if (target != null) {
                Vec3 aim = new Vec3(target.getX(),
                    target.getY() + target.getBbHeight() * 0.5, target.getZ());
                if (EnderDragonAbilitySupport.launchDragonFireball(ctx, level, aim) != null) {
                    level.playSound(null, dragon.getX(), dragon.getY(), dragon.getZ(),
                        SoundEvents.ENDER_DRAGON_SHOOT, SoundSource.HOSTILE, 2.0f, 1.0f);
                }
            }
        }
        if (ticks % 10 == 0 && dragon instanceof EnderDragon enderDragon
            && enderDragon.getPhaseManager().getCurrentPhase().isSitting()
            && ticks - ctx.state().getIntOr("last_slam", -10000) >= SLAM_INTERVAL) {
            perchSlam(ctx, level, ticks);
        }
    }

    /** One perch slam shockwave (see the class comment). */
    private void perchSlam(AbilityContext ctx, ServerLevel level, int ticks) {
        Mob dragon = ctx.mob();
        ctx.state().putInt("last_slam", ticks);
        List<LivingEntity> victims = EnderDragonAbilitySupport.enemiesAroundSelf(
            ctx, SLAM_RADIUS, 6.0, 4.0);
        for (LivingEntity victim : victims) {
            victim.hurtServer(level, level.damageSources().mobAttack(dragon), SLAM_DAMAGE);
            Vec3 away = new Vec3(victim.getX() - dragon.getX(), 0.0, victim.getZ() - dragon.getZ());
            if (away.lengthSqr() < 1.0e-4) {
                away = new Vec3(1.0, 0.0, 0.0);
            }
            away = away.normalize();
            victim.setDeltaMovement(away.x * 0.5, 0.8, away.z * 0.5);
            victim.hurtMarked = true;
        }
        level.sendParticles(ParticleTypes.EXPLOSION,
            dragon.getX(), dragon.getY() + 0.5, dragon.getZ(), 2, 1.5, 0.3, 1.5, 0.0);
        level.sendParticles(END_STONE_CRUMBLE,
            dragon.getX(), dragon.getY() + 0.5, dragon.getZ(), 12, 4.0, 0.5, 4.0, 0.15);
        level.playSound(null, dragon.getX(), dragon.getY(), dragon.getZ(),
            SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 2.0f, 0.5f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= HARD_CAP_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        // The form's three modifiers come off, exactly
        // reversing start(). On a death termination this also
        // runs, which is harmless: a dead dragon keeps nothing.
        Mob dragon = ctx.mob();
        EnderDragonAbilitySupport.removeModifier(dragon, Attributes.SCALE, SCALE_ID);
        EnderDragonAbilitySupport.removeModifier(dragon, Attributes.ATTACK_KNOCKBACK, KNOCKBACK_ID);
        EnderDragonAbilitySupport.removeModifier(dragon, Attributes.ATTACK_DAMAGE, DAMAGE_ID);
    }
}
