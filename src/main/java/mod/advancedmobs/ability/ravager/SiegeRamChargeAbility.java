package mod.advancedmobs.ability.ravager;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Siege Ram Charge (Ravager ability 1 of 5, mobility and attack).
 *
 * WHAT IT DOES: the ravager lowers its horned head and charges,
 * and this time nothing stops it: blocks in its path crack and
 * break as it plows through, debris fountaining from the
 * breach, until the fortification has a ravager-shaped hole
 * through it and the raid has a clear lane. It only stops at
 * bedrock (or anything else it cannot smash), exhaustion, or a
 * very long fall. Afterwards it stands winded, catching its
 * breath, before the framework's cooldown even starts.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the ravager's current target within 18 blocks (distance
 *   squared 324) and the ravager on the ground. The charge
 *   aims at the target's position at start(); after that the
 *   line is fixed, so a target that sidesteps dodges the
 *   charge but not the hole it leaves.
 * - Charge phase (phase 0), at most 60 ticks: the direction
 *   is recorded in start() (toward the target, or the
 *   ravager's look direction if the target is dead center).
 *   Each tick the navigation is stopped and the ravager's
 *   horizontal velocity is set to the recorded direction
 *   times the current stride speed (vertical velocity is
 *   left alone, so gravity and steps behave normally). The
 *   stride ramps: ticks 1 to 10 at 0.40 blocks per tick,
 *   ticks 11 to 20 at 0.65, ticks 21 on at 0.95. The phase
 *   ends early when the ravager has travelled 30 blocks
 *   from the start point, hits a wall it cannot smash
 *   (horizontalCollision, or a charge stopper in the front
 *   column, see below), or drops more than 5 blocks below
 *   the start height (the very long fall). On the ending
 *   tick the phase flips to winded and the end tick is
 *   recorded 40 ticks out.
 * - Smashing (terrain, gated below): on each charge tick,
 *   when mobGriefing is on and fewer than 60 blocks have
 *   been smashed, the front column is worked: the cells 1.3
 *   blocks ahead of the ravager's center along the charge
 *   direction at feet level, feet +1, and feet +2 (a ravager
 *   is 2.2 blocks tall and just under 2 wide, so three high
 *   is a ravager-shaped hole), plus its own column at feet
 *   +2 (the lintel it is ducking under). Each cell passing
 *   the shared isDiggable check (never air, fluids, block
 *   entities, or unbreakable blocks) is smashed: set to air
 *   with no drops, 6 BLOCK particles and 3 BLOCK_CRUMBLE
 *   particles of the broken state at the cell center, and
 *   the block's own break sound at volume 0.8. Before
 *   smashing, the front column is checked for a charge
 *   stopper (a dry solid block the charge may not smash: a
 *   block entity or an unbreakable block, bedrock being the
 *   canonical case): if one is present the charge ends on
 *   the spot with a RAVAGER_STUNNED bellow and a dust burst,
 *   exactly the design's "it only stops at bedrock".
 * - Charge hits: every charge tick, enemies (RavagerAbility
 *   Support rules) within radius 2.2 of the ravager (1.0
 *   below to 2.5 above its feet) that this charge has not
 *   already hit take 10.0 points of typed mob-attack damage
 *   credited to the ravager and are hurled along the charge
 *   direction: delta movement set to 2.2 horizontal along
 *   the line and 0.6 up, hurtMarked set so the knockback
 *   syncs. Each enemy is hit at most once per charge
 *   (tracked in the state tag's "hit" compound). The ravager
 *   also carries an ATTACK_KNOCKBACK add_value modifier of
 *   +3.0 for the whole activation (id advancedmobs:
 *   siege_ram_charge_knockback, removed in end()): the
 *   design's absurd levels, so any ordinary bite it lands
 *   mid-charge launches its victim.
 * - Winded phase (phase 1), 40 ticks: at the flip the
 *   ravager gains SLOWNESS amplifier 0 for 40 ticks (the
 *   exhaustion; a ravager that somehow had SLOWNESS from
 *   another source loses it when this one is refreshed out,
 *   an accepted edge case shared with every fixed-duration
 *   effect in this system). tick() does nothing in this
 *   phase; vanilla AI walks the beast at its slowed pace.
 *   The activation finishes at the recorded winded end tick.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6 and the MobAbility contract):
 * the smashed blocks are PERMANENT and gated by
 * AbilityContext.mobGriefingAllowed(), re-checked every
 * charge tick in case the rule is toggled mid-charge:
 * - mobGriefing ON: the charge smashes as above and the hole
 *   STAYS. end() touches no blocks.
 * - mobGriefing OFF: no block is ever smashed and the
 *   stopper check is skipped (there is nothing to stop for);
 *   the charge, its hits and knockback, and the hoof
 *   particles all run exactly the same, and walls stop the
 *   charge only through ordinary collision.
 *
 * WHAT IT LOOKS LIKE: a RAVAGER_ROAR at volume 1.2 and pitch
 * 0.85 as the head drops, then every charge tick 3 DUST_PILLAR
 * and 3 BLOCK particles of dirt bursting at the hooves, BLOCK
 * debris fountaining from every smashed cell with its own
 * break sound, and a RAVAGER_STUNNED bellow with a 12-particle
 * dust burst if it meets bedrock. The winded ravager shows
 * vanilla SLOWNESS sparkles while it catches its breath.
 *
 * PERFORMANCE: the charge phase per tick does one navigation
 * stop, one velocity write, one bounded entity query (4.4 by
 * 3.5 by 4.4 box), at most 7 block reads plus the capped
 * smashes, and 6 particle sends plus 9 per smash. The winded
 * phase does nothing per tick. Particle budget per
 * activation: at most 60 charge ticks times 6 = 360 hoof
 * particles, plus at most 60 capped smashes times 9 = 540,
 * plus the 12-particle stopper burst: at most 912.
 *
 * CLEANUP: end() removes the ATTACK_KNOCKBACK modifier. The
 * smashed blocks, when smashed, are permanent terrain and
 * are deliberately NOT touched. The winded SLOWNESS is a
 * timed effect and expires on its own clock.
 *
 * State tag keys: "phase" (0 charging, 1 winded), "dir_x"
 * and "dir_z" (the charge direction, unit length), "sx",
 * "sy", "sz" (charge start point), "winded_end" (the tick
 * count at which the winded phase finishes), "hit" (compound:
 * victim UUID string to 1, everyone this charge has already
 * hit), "breaks" (blocks smashed this charge).
 */
public final class SiegeRamChargeAbility implements MobAbility {

    private static final int CHARGE_MAX_TICKS = 60;
    private static final double CHARGE_MAX_DISTANCE_SQR = 30.0 * 30.0;
    private static final double FALL_STOP_DROP = 5.0;
    private static final int WINDED_TICKS = 40;
    private static final int MAX_BREAKS = 60;
    private static final float CHARGE_DAMAGE = 10.0f;
    private static final double HIT_KNOCKBACK_HORIZONTAL = 2.2;
    private static final double HIT_KNOCKBACK_VERTICAL = 0.6;
    private static final Identifier KNOCKBACK_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "siege_ram_charge_knockback");
    private static final BlockParticleOption DIRT_PILLAR =
        new BlockParticleOption(ParticleTypes.DUST_PILLAR, Blocks.DIRT.defaultBlockState());
    private static final BlockParticleOption DIRT_DEBRIS =
        new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DIRT.defaultBlockState());

    @Override
    public String id() {
        return "siege_ram_charge";
    }

    @Override
    public String displayName() {
        return "Siege Ram Charge";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().onGround()
            && ctx.mob().distanceToSqr(target) <= 324.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();

        // The charge line is fixed here: toward the target's
        // position right now, or along the look direction when
        // the target is dead center on top of the ravager.
        double dx = 0.0;
        double dz = 0.0;
        LivingEntity target = ctx.target();
        if (target != null) {
            dx = target.getX() - mob.getX();
            dz = target.getZ() - mob.getZ();
        }
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0e-4) {
            Vec3 look = mob.getLookAngle();
            dx = look.x;
            dz = look.z;
            length = Math.sqrt(dx * dx + dz * dz);
            if (length < 1.0e-4) {
                dx = 1.0;
                length = 1.0;
            }
        }
        state.putInt("phase", 0);
        state.putDouble("dir_x", dx / length);
        state.putDouble("dir_z", dz / length);
        state.putDouble("sx", mob.getX());
        state.putDouble("sy", mob.getY());
        state.putDouble("sz", mob.getZ());
        state.putInt("winded_end", Integer.MAX_VALUE);
        state.put("hit", new CompoundTag());
        state.putInt("breaks", 0);

        // The absurd knockback: the ravager's own bites launch
        // for the whole activation (removed in end()).
        RavagerAbilitySupport.addModifier(mob, Attributes.ATTACK_KNOCKBACK,
            new AttributeModifier(KNOCKBACK_ID, 3.0, AttributeModifier.Operation.ADD_VALUE));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.RAVAGER_ROAR,
                SoundSource.HOSTILE, 1.2f, 0.85f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        if (ctx.state().getIntOr("phase", 0) == 0) {
            tickCharge(ctx);
        }
        // Winded phase: nothing per tick. The exhaustion effect
        // runs on its own clock and isFinished() ends the
        // activation at the recorded tick.
    }

    /** One charge tick: stride, stopper check, smashing, hit checks, hoof debris. */
    private void tickCharge(AbilityContext ctx) {
        Mob mob = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        CompoundTag state = ctx.state();
        int ticks = ctx.ticksActive();
        double dirX = state.getDoubleOr("dir_x", 1.0);
        double dirZ = state.getDoubleOr("dir_z", 0.0);
        boolean stopped = false;

        // Stride: hold the recorded line at the ramped speed.
        double speed = ticks <= 10 ? 0.40 : (ticks <= 20 ? 0.65 : 0.95);
        mob.getNavigation().stop();
        Vec3 current = mob.getDeltaMovement();
        mob.setDeltaMovement(dirX * speed, current.y, dirZ * speed);
        mob.hurtMarked = true;

        if (level != null) {
            int feetY = (int) Math.floor(mob.getY());
            int frontX = (int) Math.floor(mob.getX() + dirX * 1.3);
            int frontZ = (int) Math.floor(mob.getZ() + dirZ * 1.3);
            int ownX = (int) Math.floor(mob.getX());
            int ownZ = (int) Math.floor(mob.getZ());

            if (ctx.mobGriefingAllowed()) {
                // Bedrock check first: a dry solid block in the
                // front column that the charge may not smash
                // stops the charge on the spot.
                for (int dy = 0; dy <= 2 && !stopped; dy++) {
                    if (RavagerAbilitySupport.isChargeStopper(level, new BlockPos(frontX, feetY + dy, frontZ))) {
                        stopped = true;
                    }
                }
                if (stopped) {
                    level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.RAVAGER_STUNNED,
                        SoundSource.HOSTILE, 1.2f, 0.9f);
                    level.sendParticles(DIRT_DEBRIS, mob.getX() + dirX * 1.5, mob.getY() + 1.0, mob.getZ() + dirZ * 1.5,
                        12, 0.6, 0.6, 0.6, 0.2);
                } else if (state.getIntOr("breaks", 0) < MAX_BREAKS) {
                    int breaks = state.getIntOr("breaks", 0);
                    breaks += smashCell(level, new BlockPos(frontX, feetY, frontZ), breaks);
                    breaks += smashCell(level, new BlockPos(frontX, feetY + 1, frontZ), breaks);
                    breaks += smashCell(level, new BlockPos(frontX, feetY + 2, frontZ), breaks);
                    breaks += smashCell(level, new BlockPos(ownX, feetY + 2, ownZ), breaks);
                    state.putInt("breaks", breaks);
                }
            }

            if (!stopped) {
                // Hit checks: everyone in the plow box not yet
                // hit by this charge takes the heavy hit and is
                // hurled along the line.
                CompoundTag hit = state.getCompoundOrEmpty("hit");
                List<LivingEntity> victims = RavagerAbilitySupport.enemiesAroundSelf(ctx, 2.2, 1.0, 2.5);
                for (LivingEntity victim : victims) {
                    String key = victim.getUUID().toString();
                    if (hit.contains(key)) {
                        continue;
                    }
                    hit.putInt(key, 1);
                    victim.hurtServer(level, level.damageSources().mobAttack(mob), CHARGE_DAMAGE);
                    victim.setDeltaMovement(dirX * HIT_KNOCKBACK_HORIZONTAL, HIT_KNOCKBACK_VERTICAL,
                        dirZ * HIT_KNOCKBACK_HORIZONTAL);
                    victim.hurtMarked = true;
                }
                state.put("hit", hit);

                // Hoof debris: dirt pillars and chunks bursting up.
                level.sendParticles(DIRT_PILLAR, mob.getX(), mob.getY() + 0.2, mob.getZ(),
                    3, 0.8, 0.3, 0.8, 0.10);
                level.sendParticles(DIRT_DEBRIS, mob.getX(), mob.getY() + 0.2, mob.getZ(),
                    3, 0.8, 0.3, 0.8, 0.15);
            }
        }

        // Charge end conditions: time, wall, distance, the
        // long fall, or the bedrock stop above.
        double dx = mob.getX() - state.getDoubleOr("sx", mob.getX());
        double dz = mob.getZ() - state.getDoubleOr("sz", mob.getZ());
        if (stopped || ticks >= CHARGE_MAX_TICKS || mob.horizontalCollision
            || dx * dx + dz * dz >= CHARGE_MAX_DISTANCE_SQR
            || mob.getY() < state.getDoubleOr("sy", mob.getY()) - FALL_STOP_DROP) {
            state.putInt("phase", 1);
            state.putInt("winded_end", ticks + WINDED_TICKS);
            // Exhaustion: the beast stands winded while the
            // activation runs out.
            mob.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, WINDED_TICKS, 0));
        }
    }

    /**
     * Smashes one cell if it passes the shared safety check:
     * sets it to air (no drops), sprays its debris, and plays
     * its own break sound. Returns 1 when a block was smashed,
     * 0 otherwise. The caller has already checked the terrain
     * gate; breaksSoFar is the running count for this tick, so
     * the per-activation cap is exact.
     */
    private int smashCell(ServerLevel level, BlockPos pos, int breaksSoFar) {
        if (breaksSoFar >= MAX_BREAKS) {
            return 0;
        }
        if (!RavagerAbilitySupport.isDiggable(level, pos)) {
            return 0;
        }
        BlockState state = level.getBlockState(pos);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.5;
        double cz = pos.getZ() + 0.5;
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
            cx, cy, cz, 6, 0.3, 0.3, 0.3, 0.1);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, state),
            cx, cy, cz, 3, 0.3, 0.3, 0.3, 0.05);
        level.playSound(null, cx, cy, cz, state.getSoundType().getBreakSound(),
            SoundSource.HOSTILE, 0.8f, 1.0f);
        return 1;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        return state.getIntOr("phase", 0) == 1
            && ctx.ticksActive() >= state.getIntOr("winded_end", Integer.MAX_VALUE);
    }

    @Override
    public void end(AbilityContext ctx) {
        // Only the knockback modifier is undone. The smashed
        // blocks, when smashed, are permanent terrain and are
        // deliberately NOT touched here (terrain rule, see the
        // class comment). The winded SLOWNESS expires on its
        // own clock.
        RavagerAbilitySupport.removeModifier(ctx.mob(), Attributes.ATTACK_KNOCKBACK, KNOCKBACK_ID);
    }
}
