package mod.advancedmobs.ability.slime;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Royal Jelly (Slime ability 2 of 5, transformation).
 *
 * WHAT IT DOES: the slime swells into a royal mass, its scale
 * climbing in steps toward triple size while a golden crown
 * item display settles above its head at full brightness and
 * ITEM_SLIME motes drip from its bulk. Its MAX_HEALTH balloons
 * with its body, its bounces carry real ATTACK_KNOCKBACK, and
 * every landing is a minor earthquake.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists and is within 16
 *   blocks (distance squared 256).
 * - Duration: exactly 400 ticks (20 seconds) from start().
 * - The growth (all transient modifiers, removed in end()):
 *   SCALE add_value applied in 5 steps of +0.4 at ticks 0
 *   (start), 10, 20, 30, and 40, reaching +2.0, so the slime
 *   stands at 3.0 times its normal size once grown (modifier
 *   id advancedmobs:royal_jelly_scale, refreshed with
 *   addOrUpdateTransientModifier so each step replaces the
 *   last). MAX_HEALTH add_value +24 (id
 *   advancedmobs:royal_jelly_health) applied in full at
 *   start, and the +24 arrives full: the slime is healed by 24
 *   on start, and health is clamped back down in end() after
 *   the modifier is removed. ATTACK_KNOCKBACK add_value +1.0
 *   (id advancedmobs:royal_jelly_knockback): every bounce the
 *   royal mass throws itself into now knocks its victim back
 *   hard.
 * - The crown: 1 item display showing a golden helmet (vanilla
 *   has no crown item; see SlimeAbilitySupport), created in
 *   start() at full brightness with a 2-tick teleport glide.
 *   Every tick it is teleported to 0.3 blocks above the
 *   slime's current bounding box top, so it rides the growth
 *   and the bouncing. Its UUID is tracked in the state tag
 *   ("crown"); a crown that no longer resolves is skipped.
 *   end() discards it, including through the framework
 *   termination path when the slime dies mid-reign.
 * - The drips: every 4 ticks, 2 ITEM_SLIME motes at the body,
 *   spread by the slime's current width.
 * - The landing quakes: the tick code remembers whether the
 *   slime was airborne and how fast it was falling. A landing
 *   (airborne last tick, on ground now) that fell faster than
 *   -0.55 vertical velocity is a quake, at most one per 10
 *   ticks and at most 20 per activation: one bounded enemy
 *   query in radius 2.6 (1.5 below to 1.5 above the feet),
 *   every enemy takes 2.0 FALL damage
 *   (level.damageSources().fall(), no attacker attached) and
 *   a shove of 0.5 outward plus 0.3 up.
 *
 * WHAT IT LOOKS LIKE: a slime swelling step by step to three
 * times its size under a glowing golden crown, green motes
 * dripping off its bulk every few ticks, and every hard
 * landing cracking the ground: 6 DUST_PILLAR and 4
 * BLOCK_CRUMBLE particles of the block underfoot, with a deep
 * SLIME_SQUISH (volume 0.8, pitch 0.6).
 *
 * PERFORMANCE: the per-tick work is the crown teleport (one
 * UUID resolve), the remembered velocity reads, and the
 * 4-tick drip pulse; the growth steps land on 4 ticks total.
 * The only entity query runs at a quake, at most 20 times per
 * activation. No registry or config lookups in the tick path
 * (the crown's item id is resolved once in start(), inside
 * the display NBT). Particle budget per activation, hard caps
 * by construction: drips 100 pulses times 2 = 200, quakes 20
 * times 10 = 200, plus 8 ITEM_SLIME at start, total at most
 * 408 particles. The crown is 1 extra entity for 20 seconds,
 * discarded in end().
 *
 * CLEANUP: end() removes all three modifiers by id, clamps
 * health to the restored maximum, and discards the crown. If
 * the slime dies mid-reign the framework termination path
 * runs this same end(), so the crown never outlives it.
 *
 * State tag keys: "crown" (the crown display's UUID), "air"
 * (1 when the slime was airborne at the last tick read),
 * "prev_vy" (last tick's vertical velocity), "last_quake"
 * (tick of the last landing quake), "quakes" (quakes so far).
 */
public final class RoyalJellyAbility implements MobAbility {

    static final int DURATION_TICKS = 400;
    private static final double MAX_HEALTH_BONUS = 24.0;
    private static final int MAX_QUAKES = 20;
    private static final int QUAKE_INTERVAL = 10;
    private static final double QUAKE_RADIUS = 2.6;
    private static final float QUAKE_DAMAGE = 2.0f;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "royal_jelly_scale");
    private static final Identifier HEALTH_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "royal_jelly_health");
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "royal_jelly_knockback");

    @Override
    public String id() {
        return "royal_jelly";
    }

    @Override
    public String displayName() {
        return "Royal Jelly";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return ctx.target() != null && ctx.mob().distanceToSqr(ctx.target()) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        ctx.state().putInt("air", 0);
        ctx.state().putInt("last_quake", -QUAKE_INTERVAL);
        ctx.state().putInt("quakes", 0);

        // The royal bulk: health and knockback in full at once,
        // the growth itself in steps (see tick()).
        SlimeAbilitySupport.addModifier(mob, Attributes.MAX_HEALTH,
            new AttributeModifier(HEALTH_ID, MAX_HEALTH_BONUS, AttributeModifier.Operation.ADD_VALUE));
        SlimeAbilitySupport.addModifier(mob, Attributes.ATTACK_KNOCKBACK,
            new AttributeModifier(KNOCKBACK_ID, 1.0, AttributeModifier.Operation.ADD_VALUE));
        applyGrowthStep(ctx, 1);
        // The +24 max health arrives full.
        mob.setHealth(Math.min(mob.getMaxHealth(), mob.getHealth() + (float) MAX_HEALTH_BONUS));

        // The crown settles above the swelling mass.
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Entity crown = SlimeAbilitySupport.createCrownDisplay(level);
        if (crown != null) {
            crown.snapTo(mob.getX(), mob.getY() + mob.getBbHeight() + 0.3, mob.getZ());
            if (level.addFreshEntity(crown)) {
                ctx.state().store("crown", UUIDUtil.CODEC, crown.getUUID());
            }
        }
        level.sendParticles(ParticleTypes.ITEM_SLIME, mob.getX(), mob.getY() + 0.5, mob.getZ(),
            8, mob.getBbWidth() * 0.5, 0.5, mob.getBbWidth() * 0.5, 0.08);
    }

    /** Applies growth step 1 to 5: SCALE add_value of 0.4 times the step (step 5 is the full +2.0). */
    private static void applyGrowthStep(AbilityContext ctx, int step) {
        SlimeAbilitySupport.addModifier(ctx.mob(), Attributes.SCALE,
            new AttributeModifier(SCALE_ID, 0.4 * step, AttributeModifier.Operation.ADD_VALUE));
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob mob = ctx.mob();
        int ticks = ctx.ticksActive();

        // The growth: one step every 10 ticks until triple size.
        if (ticks % 10 == 0 && ticks <= 40) {
            applyGrowthStep(ctx, Math.min(5, ticks / 10 + 1));
        }

        // The crown rides 0.3 above the bounding box, growing
        // and bouncing with the slime.
        if (ctx.state().contains("crown")) {
            UUID crownId = ctx.state().read("crown", UUIDUtil.CODEC).orElse(null);
            Entity crown = crownId != null ? level.getEntity(crownId) : null;
            if (crown != null) {
                crown.teleportTo(mob.getX(), mob.getY() + mob.getBbHeight() + 0.3, mob.getZ());
            }
        }

        // The drips: ITEM_SLIME motes off the bulk every 4 ticks.
        if (ticks % 4 == 0) {
            level.sendParticles(ParticleTypes.ITEM_SLIME, mob.getX(), mob.getY() + 0.5, mob.getZ(),
                2, mob.getBbWidth() * 0.45, 0.5, mob.getBbWidth() * 0.45, 0.04);
        }

        // The landing quakes: a hard landing after being airborne.
        boolean onGround = mob.onGround();
        boolean wasAir = ctx.state().getIntOr("air", 0) == 1;
        double prevVy = ctx.state().getDoubleOr("prev_vy", 0.0);
        if (onGround && wasAir && prevVy < -0.55
            && ticks - ctx.state().getIntOr("last_quake", -QUAKE_INTERVAL) >= QUAKE_INTERVAL
            && ctx.state().getIntOr("quakes", 0) < MAX_QUAKES) {
            runQuake(ctx, level);
            ctx.state().putInt("last_quake", ticks);
            ctx.state().putInt("quakes", ctx.state().getIntOr("quakes", 0) + 1);
        }
        ctx.state().putInt("air", onGround ? 0 : 1);
        ctx.state().putDouble("prev_vy", mob.getDeltaMovement().y);
    }

    /** One landing quake: light fall damage and a shove for enemies at the royal mass's feet. */
    private static void runQuake(AbilityContext ctx, ServerLevel level) {
        Mob mob = ctx.mob();
        double cx = mob.getX();
        double cy = mob.getY();
        double cz = mob.getZ();
        List<LivingEntity> victims = SlimeAbilitySupport.enemiesInBox(ctx, cx, cy, cz, QUAKE_RADIUS, 1.5, 1.5);
        for (LivingEntity victim : victims) {
            victim.hurtServer(level, level.damageSources().fall(), QUAKE_DAMAGE);
            double dx = victim.getX() - cx;
            double dz = victim.getZ() - cz;
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length < 0.001) {
                continue;
            }
            Vec3 current = victim.getDeltaMovement();
            victim.setDeltaMovement(current.x + dx / length * 0.5, Math.max(current.y, 0.3),
                current.z + dz / length * 0.5);
            victim.hurtMarked = true;
        }
        // DUST_PILLAR carries a block state: the pillars and the
        // crumble are both made of the ground underfoot (the
        // pillars fall back to dirt over a gap).
        BlockState ground = level.getBlockState(mob.blockPosition().below());
        level.sendParticles(new BlockParticleOption(ParticleTypes.DUST_PILLAR,
                ground.isAir() ? Blocks.DIRT.defaultBlockState() : ground),
            cx, cy + 0.2, cz, 6, 1.2, 0.2, 1.2, 0.1);
        if (!ground.isAir()) {
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, ground),
                cx, cy + 0.1, cz, 4, 1.2, 0.05, 1.2, 0.03);
        }
        level.playSound(null, cx, cy, cz, SoundEvents.SLIME_SQUISH, SoundSource.HOSTILE, 0.8f, 0.6f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        SlimeAbilitySupport.removeModifier(mob, Attributes.SCALE, SCALE_ID);
        SlimeAbilitySupport.removeModifier(mob, Attributes.ATTACK_KNOCKBACK, KNOCKBACK_ID);
        SlimeAbilitySupport.removeModifier(mob, Attributes.MAX_HEALTH, HEALTH_ID);
        // Clamp health to the restored maximum if the reign left it above.
        if (mob.getHealth() > mob.getMaxHealth()) {
            mob.setHealth(mob.getMaxHealth());
        }
        // The crown winks out with the reign.
        ServerLevel level = ctx.serverLevel();
        if (level != null && ctx.state().contains("crown")) {
            UUID crownId = ctx.state().read("crown", UUIDUtil.CODEC).orElse(null);
            Entity crown = crownId != null ? level.getEntity(crownId) : null;
            if (crown != null) {
                crown.discard();
            }
        }
    }
}
