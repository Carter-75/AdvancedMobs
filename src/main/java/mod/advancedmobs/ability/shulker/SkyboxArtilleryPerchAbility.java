package mod.advancedmobs.ability.shulker;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Skybox Artillery Perch (Shulker ability 5 of 5, mobility,
 * terrain).
 *
 * WHAT IT DOES: the shulker teleports onto a small floating
 * platform of purpur slabs that materializes high above the
 * fight, far beyond melee reach, and from that skybox its
 * FOLLOW_RANGE stretches across the whole arena while it
 * rains homing bullets down at leisure. When the perch ends
 * it drops back into the fight with SLOW_FALLING to cushion
 * the landing. Shooting the platform out from under it ends
 * the barrage and the perch together.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the shulker's current target within 20 blocks (distance
 *   squared 400).
 * - Duration: exactly 300 ticks (15 seconds) from start(),
 *   or early when the support check finds the platform gone.
 * - The platform (start(), only when mobGriefing is on): a
 *   3x3 of PURPUR SLAB (bottom slabs) at 11 blocks above the
 *   shulker's block position, centered on its column. Each
 *   cell is placed only through the shared safety check (air
 *   or replaceable, dry, no block entity, not unbreakable);
 *   at most 9 slabs. If at least 1 slab was placed, the
 *   shulker teleports onto the platform center (feet on the
 *   slab top, platform Y + 0.5) with the SHULKER_TELEPORT
 *   sound, and the activation is "perched". If no slab could
 *   be placed (a ceiling overhead), the shulker stays on the
 *   ground and the rest of the ability still runs.
 * - FOLLOW_RANGE: +24 ADD_VALUE for the whole activation
 *   (the arena-wide reach of the design), applied in start()
 *   and removed in end().
 * - The rain: the perched shulker's own vanilla attack goal
 *   does most of the firing from its new vantage. On top of
 *   that, every 30 ticks the ability fires one extra real
 *   ShulkerBullet at the framework target when it is within
 *   32 blocks (distance squared 1024): the stretched-range
 *   artillery the design promises, guaranteed even when the
 *   vanilla goal is mid-cooldown. SHULKER_SHOOT sounds each
 *   extra shot.
 * - Support check every 10 ticks (perched only): the block
 *   in the shulker's own cell (the slab it sits on) must not
 *   be air. If it is air, the platform was shot or mined out
 *   from under it: the activation finishes at once, and the
 *   drop cushion in end() applies.
 * - The drop (end()): the FOLLOW_RANGE modifier is removed.
 *   If the shulker is perched and the block beneath it is
 *   air (the natural end leaves it sitting on its slab; in
 *   that case it first steps off: it is snapped 2 blocks to
 *   the side of the platform center at the same height so
 *   the drop clears the platform edge), it gains
 *   SLOW_FALLING for 120 ticks, applied in end() so the
 *   cushion outlives the activation (the Colossal Grief
 *   end-applied effect precedent), and falls back into the
 *   fight. If it is somehow already supported, no cushion is
 *   applied and nothing moves.
 *
 * TERRAIN RULE (Carter, Nov 21, 2026): the design text calls
 * the platform "always temporary" and has it "crumble away".
 * The standing terrain rule supersedes both lines: ability
 * terrain is PERMANENT and there is no restore machinery
 * (the Gravemud Mire, Magma Ring Rising, and Graveyard
 * Bloom precedent). The purpur slabs stay in the sky as a
 * permanent scar; only the shulker leaves, by the cushioned
 * drop above. With mobGriefing OFF, no slabs are placed and
 * no teleport happens: the FOLLOW_RANGE stretch and the
 * extra artillery shots still run from the ground (the
 * Monument Rising off-mode precedent).
 *
 * WHAT IT LOOKS LIKE: a neat 3x3 purpur slab raft blinking
 * into the sky with BLOCK_CRUMBLE puffs off each placed slab
 * and a teleport shimmer, the shulker enthroned on top
 * shell-open, END_ROD sparkles drifting off the platform
 * edge every 10 ticks (3 motes), bullets arcing down. The
 * end is a slow, smug SLOW_FALLING descent beside the raft.
 *
 * PERFORMANCE: all block work is one-shot in start() (at
 * most 9 cell reads and writes). tick() returns immediately
 * except every 10th tick (support check: one block read;
 * sparkle: 3 particles) and every 30th tick (one range
 * check and one bullet spawn). Particle budget per
 * activation: build puffs at most 9 x 2 = 18, sparkles
 * 30 x 3 = 90, total at most 108 sent particles.
 *
 * State tag keys: "perched" (whether the teleport onto a
 * placed platform happened), "plat_y" (the platform's block
 * Y, for the end-of-perch step-off).
 */
public final class SkyboxArtilleryPerchAbility implements MobAbility {

    static final int DURATION_TICKS = 300;
    private static final int PLATFORM_HEIGHT = 11;
    private static final int ARTILLERY_INTERVAL = 30;
    private static final Identifier FOLLOW_RANGE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "skybox_artillery_perch_follow_range");
    private static final BlockParticleOption PURPUR_CRUMBLE =
        new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, Blocks.PURPUR_SLAB.defaultBlockState());

    @Override
    public String id() {
        return "skybox_artillery_perch";
    }

    @Override
    public String displayName() {
        return "Skybox Artillery Perch";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 400.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ShulkerAbilitySupport.addModifier(caster, Attributes.FOLLOW_RANGE,
            new AttributeModifier(FOLLOW_RANGE_ID, 24.0, AttributeModifier.Operation.ADD_VALUE));

        boolean perched = false;
        ServerLevel level = ctx.serverLevel();
        if (level != null && ctx.mobGriefingAllowed()) {
            BlockPos base = caster.blockPosition();
            int platformY = base.getY() + PLATFORM_HEIGHT;
            ctx.state().putInt("plat_y", platformY);
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            int placed = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    pos.set(base.getX() + dx, platformY, base.getZ() + dz);
                    if (!ShulkerAbilitySupport.canPlaceAbilityBlock(level, pos)) {
                        continue;
                    }
                    if (level.setBlock(pos, Blocks.PURPUR_SLAB.defaultBlockState(), Block.UPDATE_ALL)) {
                        placed++;
                        level.sendParticles(PURPUR_CRUMBLE,
                            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                            2, 0.3, 0.2, 0.3, 0.05);
                    }
                }
            }
            if (placed > 0) {
                double centerX = base.getX() + 0.5;
                double centerZ = base.getZ() + 0.5;
                caster.snapTo(centerX, platformY + 0.5, centerZ, caster.getYRot(), 0.0f);
                level.playSound(null, centerX, platformY + 0.5, centerZ,
                    SoundEvents.SHULKER_TELEPORT, SoundSource.HOSTILE, 1.2f, 1.0f);
                perched = true;
            }
        }
        ctx.state().putBoolean("perched", perched);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        int tick = ctx.ticksActive();
        boolean perched = ctx.state().getBooleanOr("perched", false);

        if (tick % 10 == 0) {
            if (perched) {
                // Platform sparkle off the raft's edge.
                level.sendParticles(ParticleTypes.END_ROD, caster.getX(), caster.getY() + 0.4, caster.getZ(),
                    3, 1.2, 0.3, 1.2, 0.02);
                // Support check: the slab in the shulker's own
                // cell. Air means the perch was shot out; the
                // early finish is read by isFinished() from
                // the "dropped" flag end() cushions against.
                if (level.getBlockState(caster.blockPosition()).isAir()) {
                    ctx.state().putBoolean("dropped", true);
                }
            }
        }

        if (tick % ARTILLERY_INTERVAL == 0) {
            LivingEntity target = ctx.target();
            if (target != null && caster.distanceToSqr(target) <= 1024.0) {
                if (ShulkerAbilitySupport.fireBullet(ctx, target,
                    caster.getX(), caster.getY() + 0.8, caster.getZ())) {
                    level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                        SoundEvents.SHULKER_SHOOT, SoundSource.HOSTILE, 1.5f, 1.0f);
                }
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.state().getBooleanOr("dropped", false)
            || ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ShulkerAbilitySupport.removeModifier(caster, Attributes.FOLLOW_RANGE, FOLLOW_RANGE_ID);

        if (!ctx.state().getBooleanOr("perched", false)) {
            return;
        }
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        // Still enthroned at the natural end: step off the
        // raft's edge so the drop clears it. A shot-out perch
        // ("dropped") is already falling room, no step needed.
        if (!ctx.state().getBooleanOr("dropped", false)
            && !level.getBlockState(caster.blockPosition()).isAir()) {
            caster.snapTo(caster.getX() + 2.0, caster.getY(), caster.getZ(), caster.getYRot(), 0.0f);
        }
        // The cushioned drop, whenever the shulker now has air
        // beneath it. Applied here so it outlives the ability.
        if (level.getBlockState(caster.blockPosition()).isAir()
            && level.getBlockState(caster.blockPosition().below()).isAir()) {
            caster.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 120, 0));
            caster.setDeltaMovement(caster.getDeltaMovement().multiply(1.0, 0.0, 1.0));
        }
    }
}
