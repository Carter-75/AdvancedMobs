package mod.advancedmobs.ability.pillager;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Turret Stance (Pillager ability 1 of 5, defense,
 * transformation).
 *
 * WHAT IT DOES: the pillager digs in behind a shield wall of
 * dark oak planks that rises in front of it, roots itself to
 * the spot, and works its crossbow like a mounted gun: tight
 * three-bolt bursts over the wall, on a steady cycle, while
 * the wall itself eats incoming projectiles until it
 * splinters apart. The stance trades all mobility for a
 * firing position, and uprooting at the end takes a moment.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the pillager's current target within 16 blocks (distance
 *   squared 256) and the pillager on the ground.
 * - Duration: 300 ticks (15 seconds), or until the wall
 *   breaks, whichever comes first.
 * - The wall (start()): three block displays of DARK_OAK_PLANKS
 *   at per-axis scale (1.02, 1.5, 1.02), side by side in a row
 *   facing the target. The row's center is 1.35 blocks in
 *   front of the pillager's feet along the horizontal
 *   direction to the target at cast (the wall does not turn
 *   or move afterwards); the side panels sit 1.02 blocks to
 *   each side along the perpendicular. Each panel's center is
 *   0.75 above the pillager's feet, so the wall stands 1.5
 *   tall: chest height on the pillager (eye 1.81), high
 *   enough to read as cover, low enough that his bolts,
 *   spawned at eye height, clear it. The displays are pure
 *   visuals with no hitbox; the soak below is the code
 *   stand-in for collision, the Potion Roulette precedent.
 * - The root (start(), removed in end()): MOVEMENT_SPEED
 *   -1.0 ADD_MULTIPLIED_TOTAL (speed zeroed by attribute, the
 *   Stubborn Hide Brace pattern) and KNOCKBACK_RESISTANCE
 *   +0.6 ADD_VALUE. tick() also stops the navigation and
 *   zeroes horizontal velocity every tick, so pushes and
 *   slides cannot walk the turret off its spot. No NoAi
 *   anywhere: the pillager's own goals keep running (his
 *   vanilla crossbow goal may fire its own slower shots
 *   alongside the code bursts; that is the mounted gun
 *   working, not a bug).
 * - The bursts: on a 40-tick cycle, bolts fire at cycle
 *   phases 0, 4, and 8 (three bolts, 4 ticks apart, then 32
 *   ticks of quiet). Each bolt is a real Arrow owned by the
 *   pillager (PillagerAbilitySupport.fireBolt): base damage
 *   2.0 (the vanilla arrow base), speed 3.0, inaccuracy 1.0
 *   (tight, per the design). Aim is the live target's
 *   mid-body each bolt, or the look angle when the target is
 *   gone. CROSSBOW_SHOOT per bolt, pitch 1.0, 1.1, 1.2 across
 *   the burst.
 * - The soak: every 5 ticks, one bounded Projectile query
 *   around the wall center (radius 1.5, from 0.5 below the
 *   feet to 2.0 above). A projectile counts as hostile when
 *   its owner is an enemy of the pillager by the package
 *   enemy rule, or it has no living owner at all (a dispenser
 *   arrow is nobody's friend). The pillager's own bolts and
 *   his allies' projectiles pass through untouched. Each
 *   soaked projectile is discarded, the wall takes 4 damage,
 *   and 6 dark oak BLOCK_CRUMBLE flecks spray at the wall
 *   center. At most 3 projectiles are soaked per check.
 * - Wall health: 24 points, tracked in the state tag. Six
 *   soaked projectiles break it: the displays are discarded
 *   in a burst of 12 BLOCK_CRUMBLE with a WOOD_BREAK crack,
 *   and the ability ends (the stance without its wall is
 *   just a pillager standing still).
 * - Uproot lag (end()): the two modifiers are removed, any
 *   surviving wall displays are discarded, and the pillager
 *   gains SLOWNESS I for 20 ticks, applied in end() so it
 *   outlives the activation: the design's "uprooting takes a
 *   moment", the Colossal Grief end-applied effect precedent.
 *
 * WHAT IT LOOKS LIKE: three dark oak plank panels standing
 * chest-high in a row with a WOOD_PLACE thud as they rise,
 * the pillager planted behind them, bolts cracking over the
 * top in threes, enemy arrows and fireballs vanishing into
 * sprays of splinters, until the whole row bursts apart in
 * flying plank chunks and the pillager staggers free.
 *
 * TERRAIN: the wall is block displays, not placed blocks, so
 * this ability changes no blocks in either mobGriefing mode
 * and the terrain gate does not apply.
 *
 * PERFORMANCE: per tick the work is one navigation stop and
 * one velocity write; bolts fire on 3 of every 40 ticks (one
 * spawn each); the soak is one bounded projectile query every
 * 5 ticks. Particle budget per activation: soak flecks at
 * most 6 soaks times 6 = 36, the break burst 12, total at
 * most 48. No per-tick allocations; the particle option is a
 * pre-built static final.
 *
 * State tag keys: "wall" (list of the displays' entries),
 * "wall_hp" (the wall's remaining health), "wall_x",
 * "wall_y", "wall_z" (the wall row's center, at feet level),
 * "broken" (1 once the wall has splintered).
 */
public final class TurretStanceAbility implements MobAbility {

    static final int DURATION_TICKS = 300;
    private static final int WALL_HEALTH = 24;
    private static final int SOAK_DAMAGE = 4;
    private static final int BURST_CYCLE = 40;
    private static final double BOLT_BASE_DAMAGE = 2.0;
    private static final Identifier SPEED_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "turret_stance_speed");
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "turret_stance_knockback");
    private static final BlockParticleOption PLANK_CRUMBLE =
        new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, Blocks.DARK_OAK_PLANKS.defaultBlockState());

    @Override
    public String id() {
        return "turret_stance";
    }

    @Override
    public String displayName() {
        return "Turret Stance";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0 && ctx.mob().onGround();
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        PillagerAbilitySupport.addModifier(mob, Attributes.MOVEMENT_SPEED,
            new AttributeModifier(SPEED_ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        PillagerAbilitySupport.addModifier(mob, Attributes.KNOCKBACK_RESISTANCE,
            new AttributeModifier(KNOCKBACK_ID, 0.6, AttributeModifier.Operation.ADD_VALUE));

        // Facing: the horizontal direction to the target at
        // cast, or the look angle when it is somehow gone.
        LivingEntity target = ctx.target();
        Vec3 dir;
        if (target != null) {
            dir = new Vec3(target.getX() - mob.getX(), 0.0, target.getZ() - mob.getZ());
            dir = dir.lengthSqr() > 1.0e-6 ? dir.normalize() : mob.getLookAngle().multiply(1.0, 0.0, 1.0).normalize();
        } else {
            dir = mob.getLookAngle().multiply(1.0, 0.0, 1.0).normalize();
        }
        double centerX = mob.getX() + dir.x * 1.35;
        double centerZ = mob.getZ() + dir.z * 1.35;
        ctx.state().putDouble("wall_x", centerX);
        ctx.state().putDouble("wall_y", mob.getY());
        ctx.state().putDouble("wall_z", centerZ);
        ctx.state().putInt("wall_hp", WALL_HEALTH);
        ctx.state().putInt("broken", 0);

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            double perpX = -dir.z;
            double perpZ = dir.x;
            for (int i = -1; i <= 1; i++) {
                Entity display = PillagerAbilitySupport.createBlockDisplay(level,
                    Blocks.DARK_OAK_PLANKS.defaultBlockState(), 1.02f, 1.5f, 1.02f);
                if (display == null) {
                    continue;
                }
                display.snapTo(centerX + perpX * i * 1.02, mob.getY() + 0.75, centerZ + perpZ * i * 1.02,
                    (float) Math.toDegrees(Math.atan2(-dir.x, dir.z)), 0.0f);
                if (level.addFreshEntity(display)) {
                    PillagerAbilitySupport.trackEntity(ctx.state(), "wall", display);
                }
            }
            level.playSound(null, centerX, mob.getY() + 0.5, centerZ, SoundEvents.WOOD_PLACE,
                SoundSource.HOSTILE, 1.0f, 0.8f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob mob = ctx.mob();
        // The root: hold the spot every tick (navigation stop
        // plus horizontal velocity zeroed, never NoAi).
        mob.getNavigation().stop();
        Vec3 velocity = mob.getDeltaMovement();
        mob.setDeltaMovement(0.0, velocity.y, 0.0);

        int ticks = ctx.ticksActive();
        int phase = (ticks - 1) % BURST_CYCLE;
        if (phase == 0 || phase == 4 || phase == 8) {
            if (PillagerAbilitySupport.fireBolt(ctx, ctx.target(), BOLT_BASE_DAMAGE, 3.0f, 1.0f)) {
                ServerLevel level = ctx.serverLevel();
                if (level != null) {
                    level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.CROSSBOW_SHOOT,
                        SoundSource.HOSTILE, 1.0f, 1.0f + phase * 0.025f);
                }
            }
        }

        if (ticks % 5 == 0 && ctx.state().getIntOr("broken", 0) == 0) {
            soakProjectiles(ctx);
        }
    }

    /** One soak check: hostile projectiles near the wall are eaten, and the wall pays for them. */
    private void soakProjectiles(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        double wallX = ctx.state().getDoubleOr("wall_x", ctx.mob().getX());
        double wallY = ctx.state().getDoubleOr("wall_y", ctx.mob().getY());
        double wallZ = ctx.state().getDoubleOr("wall_z", ctx.mob().getZ());
        AABB box = new AABB(wallX - 1.5, wallY - 0.5, wallZ - 1.5, wallX + 1.5, wallY + 2.0, wallZ + 1.5);
        List<Projectile> projectiles = level.getEntitiesOfClass(Projectile.class, box, p -> isHostileShot(ctx, p));
        int soaked = 0;
        for (Projectile projectile : projectiles) {
            if (soaked >= 3) {
                break;
            }
            projectile.discard();
            soaked++;
            int hp = ctx.state().getIntOr("wall_hp", WALL_HEALTH) - SOAK_DAMAGE;
            ctx.state().putInt("wall_hp", hp);
            level.sendParticles(PLANK_CRUMBLE, wallX, wallY + 0.9, wallZ, 6, 0.7, 0.5, 0.7, 0.1);
            if (hp <= 0) {
                breakWall(ctx, level, wallX, wallY, wallZ);
                return;
            }
        }
    }

    /** Whether a projectile near the wall is one the wall should eat (see the soak spec above). */
    private boolean isHostileShot(AbilityContext ctx, Projectile projectile) {
        Entity owner = projectile.getOwner();
        if (owner == ctx.mob()) {
            return false;
        }
        if (owner instanceof LivingEntity living) {
            return PillagerAbilitySupport.isEnemy(ctx, living);
        }
        return owner == null;
    }

    /** The wall splinters: displays burst and the stance ends. */
    private void breakWall(AbilityContext ctx, ServerLevel level, double wallX, double wallY, double wallZ) {
        ctx.state().putInt("broken", 1);
        for (Entity display : PillagerAbilitySupport.resolveTracked(level, ctx.state(), "wall")) {
            display.discard();
        }
        level.sendParticles(PLANK_CRUMBLE, wallX, wallY + 0.9, wallZ, 12, 0.8, 0.6, 0.8, 0.15);
        level.playSound(null, wallX, wallY + 0.9, wallZ, SoundEvents.WOOD_BREAK,
            SoundSource.HOSTILE, 1.0f, 0.9f);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.state().getIntOr("broken", 0) == 1 || ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        PillagerAbilitySupport.removeModifier(mob, Attributes.MOVEMENT_SPEED, SPEED_ID);
        PillagerAbilitySupport.removeModifier(mob, Attributes.KNOCKBACK_RESISTANCE, KNOCKBACK_ID);
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            for (Entity display : PillagerAbilitySupport.resolveTracked(level, ctx.state(), "wall")) {
                display.discard();
            }
        }
        // The uproot lag: applied here so it outlives the
        // activation by design.
        mob.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 20, 0));
    }
}
