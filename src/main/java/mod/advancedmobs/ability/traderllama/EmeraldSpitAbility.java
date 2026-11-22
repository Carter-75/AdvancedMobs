package mod.advancedmobs.ability.traderllama;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.equine.Llama;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.LlamaSpit;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Emerald Spit (Trader Llama ability 2 of 5, attack).
 *
 * WHAT IT DOES: the trader llama hocks up a single spit
 * glob studded with emerald shards. It bursts in a spray of
 * emerald ITEM particles, hits harder than ordinary spit,
 * and scatters real emerald chips across the ground. Any
 * allied villager type mob nearby scrambles over to a chip,
 * picks it up, and heals slightly on the prize.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the trader llama's current target within 18 blocks
 *   (distance squared 324), and the caster really is a Llama
 *   (the vanilla spit projectile needs one as its owner).
 * - Telegraph (ticks 1 to 10): the llama's navigation is
 *   stopped each tick (never NoAi) and 3 green DUST motes
 *   (0x2FBF71, scale 1.0) puff at its nostrils every 2 ticks.
 * - The glob (tick 12): one real LlamaSpit projectile,
 *   created with the vanilla LlamaSpit(Level, Llama)
 *   constructor (so the llama is its owner and vanilla runs
 *   its flight and its 1.0 contact damage), placed at the
 *   llama's eye position pushed 0.6 blocks along its look
 *   direction, and shot at speed 1.6 with inaccuracy 0.5
 *   (a deliberate, aimed shot, tighter than a volley glob)
 *   toward the target's live chest position. If the target
 *   is gone by tick 12, the glob is aimed at the last
 *   recorded target position (the state tag's tx/ty/tz,
 *   refreshed every tick the target lives). LLAMA_SPIT
 *   plays at volume 1.0, pitch 0.8. The glob's UUID and
 *   last known position are tracked in the state tag.
 * - The burst (the tick the tracked glob resolves: hit
 *   something or expired): at its last recorded position,
 *   12 ITEM particles of emerald and 8 SPIT particles spray
 *   out, and every enemy within 2.0 blocks (vertical reach
 *   1.5) takes 4.0 mob-attack damage credited to the caster
 *   (on top of the glob's own vanilla 1.0 if it connected
 *   directly: the design's "hits harder than ordinary
 *   spit"). Then the chips scatter.
 * - The chips (burst tick): 3 real emerald ItemEntities at
 *   the burst position, each a single emerald with a pickup
 *   delay of 20 ticks and a small random scatter velocity
 *   (horizontal 0.12 to 0.25 in a random direction, up 0.3).
 *   Their UUIDs are tracked in the state tag.
 * - The scramble (tick(), every 10 ticks, once the burst
 *   has happened): for each tracked chip still in the
 *   world, one bounded query (radius 10, vertical reach 3)
 *   finds allied villager type mobs (Villager,
 *   WanderingTrader, ZombieVillager: the package's villager
 *   ally rule). The nearest one is steered at speed 1.2
 *   toward the chip through its navigation (the scramble);
 *   when a collector is within 1.6 blocks of its chip, the
 *   chip is consumed: the ItemEntity is discarded, the
 *   collector gains INSTANT_HEALTH amplifier 0 (the vanilla
 *   instant heal of 4 health), and 6 HEART particles puff
 *   over it. A chip that leaves the world any other way (a
 *   player picks it up, it despawns) is simply pruned from
 *   tracking: the prize is gone, nobody heals.
 * - Duration: finishes at tick 40 at the earliest once the
 *   glob has burst and every chip has been collected or
 *   lost; hard finish at tick 400 whatever remains. Chips
 *   still on the ground at the hard finish stay there as
 *   ordinary emerald items (they are real items, not
 *   props); tracking just stops.
 * - end(): nothing to undo. The glob is a vanilla
 *   projectile that finishes its own flight, the burst
 *   damage is instant, the heals ride the instant effect,
 *   and leftover chips are ordinary items.
 *
 * WHAT IT LOOKS LIKE: a llama chewing with green dust at
 * its nostrils, one fat glob arcing out with a wet spit
 * sound, bursting in a glitter of emerald shards, green
 * chips bouncing across the ground, and the nearest allied
 * villager hurrying over to snatch one up with a puff of
 * hearts.
 *
 * PERFORMANCE: the telegraph is one navigation stop plus 3
 * particles every 2 ticks; the shot tick is one spawn; the
 * glob pass resolves at most 1 UUID per tick and runs one
 * bounded radius-2.0 query only on the burst tick; the
 * scramble runs at most 3 bounded queries every 10 ticks
 * (one per surviving chip). Particle budget per activation:
 * telegraph 5 pulses times 3 = 15, burst 20, collection
 * hearts at most 3 times 6 = 18, total at most 53
 * particles.
 *
 * TERRAIN: this ability changes no blocks (the glob is a
 * vanilla projectile whose hits never alter blocks, and
 * the chips are item entities); the mobGriefing gate does
 * not apply.
 *
 * State tag keys: "fired" (glob fired flag), "burst"
 * (burst resolved flag), "tx"/"ty"/"tz" (last known target
 * position), "glob" (list with one compound: "id", "x",
 * "y", "z"), "chips" (list of compounds, one per scattered
 * emerald chip: "id").
 */
public final class EmeraldSpitAbility implements MobAbility {

    private static final int TELEGRAPH_TICKS = 10;
    private static final int SHOT_TICK = 12;
    private static final int EARLY_FINISH_TICK = 40;
    private static final int HARD_FINISH_TICK = 400;
    private static final int COLLECT_INTERVAL = 10;
    private static final int CHIP_COUNT = 3;
    private static final double SPIT_SPEED = 1.6;
    private static final double BURST_RADIUS = 2.0;
    private static final float BURST_DAMAGE = 4.0f;
    private static final double COLLECT_RADIUS = 10.0;
    private static final double COLLECT_REACH = 1.6;
    private static final DustParticleOptions NOSTRIL_DUST = new DustParticleOptions(0x2FBF71, 1.0f);
    private static final ItemParticleOption EMERALD_ITEM =
        new ItemParticleOption(ParticleTypes.ITEM, Items.EMERALD);

    @Override
    public String id() {
        return "emerald_spit";
    }

    @Override
    public String displayName() {
        return "Emerald Spit";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob() instanceof Llama
            && ctx.mob().distanceToSqr(target) <= 324.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ctx.state().putInt("fired", 0);
        ctx.state().putInt("burst", 0);
        ctx.state().put("glob", new ListTag());
        ctx.state().put("chips", new ListTag());
        LivingEntity target = ctx.target();
        if (target != null) {
            recordTarget(ctx, target);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        int ticks = ctx.ticksActive();

        LivingEntity target = ctx.target();
        if (target != null) {
            recordTarget(ctx, target);
        }

        if (ticks <= TELEGRAPH_TICKS) {
            // The jaw works: hold position through navigation, never NoAi.
            caster.getNavigation().stop();
            if (ticks % 2 == 0) {
                Vec3 look = caster.getLookAngle();
                level.sendParticles(NOSTRIL_DUST,
                    caster.getX() + look.x * 0.7, caster.getEyeY() - 0.1, caster.getZ() + look.z * 0.7,
                    3, 0.08, 0.08, 0.08, 0.02);
            }
            return;
        }

        if (ctx.state().getIntOr("fired", 0) == 0 && ticks >= SHOT_TICK) {
            fireGlob(ctx, level);
            ctx.state().putInt("fired", 1);
        }

        if (ctx.state().getIntOr("burst", 0) == 0) {
            trackGlob(ctx, level);
        } else if (ticks % COLLECT_INTERVAL == 0) {
            runScramble(ctx, level);
        }
    }

    /** Fires the one emerald glob at the recorded target position. */
    private void fireGlob(AbilityContext ctx, ServerLevel level) {
        if (!(ctx.mob() instanceof Llama llama)) {
            return;
        }
        double tx = ctx.state().getDoubleOr("tx", llama.getX());
        double ty = ctx.state().getDoubleOr("ty", llama.getEyeY());
        double tz = ctx.state().getDoubleOr("tz", llama.getZ());

        Vec3 origin = llama.getEyePosition().add(llama.getLookAngle().scale(0.6));
        double dx = tx - origin.x;
        double dy = (ty + 0.5) - origin.y;
        double dz = tz - origin.z;

        LlamaSpit spit = new LlamaSpit(level, llama);
        spit.setPos(origin.x, origin.y, origin.z);
        spit.shoot(dx, dy, dz, (float) SPIT_SPEED, 0.5f);
        if (level.addFreshEntity(spit)) {
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, spit.getUUID());
            entry.putDouble("x", spit.getX());
            entry.putDouble("y", spit.getY());
            entry.putDouble("z", spit.getZ());
            ListTag glob = new ListTag();
            glob.add(entry);
            ctx.state().put("glob", glob);
        }
        level.playSound(null, llama.getX(), llama.getY(), llama.getZ(), SoundEvents.LLAMA_SPIT,
            SoundSource.HOSTILE, 1.0f, 0.8f);
    }

    /**
     * Refreshes the live glob's recorded position; when it
     * resolves, runs the burst at its last known position
     * (see the class comment) and scatters the chips.
     */
    private void trackGlob(AbilityContext ctx, ServerLevel level) {
        ListTag tracked = ctx.state().getListOrEmpty("glob");
        if (tracked.isEmpty()) {
            // The glob never entered the world (spawn refused):
            // treat the shot as spent so the ability can finish.
            if (ctx.state().getIntOr("fired", 0) == 1) {
                ctx.state().putInt("burst", 1);
            }
            return;
        }
        CompoundTag entry = tracked.getCompoundOrEmpty(0);
        Entity entity = TraderLlamaAbilitySupport.resolveTracked(level, entry);
        if (entity != null && entity.isAlive()) {
            entry.putDouble("x", entity.getX());
            entry.putDouble("y", entity.getY());
            entry.putDouble("z", entity.getZ());
            ctx.state().put("glob", tracked);
            return;
        }
        double x = entry.getDoubleOr("x", 0.0);
        double y = entry.getDoubleOr("y", 0.0);
        double z = entry.getDoubleOr("z", 0.0);
        runBurst(ctx, level, x, y, z);
        ctx.state().put("glob", new ListTag());
        ctx.state().putInt("burst", 1);
    }

    /** The emerald burst: particles, the harder hit, and the scattered chips. */
    private void runBurst(AbilityContext ctx, ServerLevel level, double x, double y, double z) {
        Mob caster = ctx.mob();
        level.sendParticles(EMERALD_ITEM, x, y, z, 12, 0.3, 0.3, 0.3, 0.15);
        level.sendParticles(ParticleTypes.SPIT, x, y, z, 8, 0.25, 0.25, 0.25, 0.05);
        List<LivingEntity> caught = TraderLlamaAbilitySupport.enemiesInBox(ctx, x, y, z,
            BURST_RADIUS, 1.5, 1.5);
        for (LivingEntity victim : caught) {
            victim.hurtServer(level, level.damageSources().mobAttack(caster), BURST_DAMAGE);
        }
        // The chips: real emeralds, scattered with a bounce.
        ListTag chips = new ListTag();
        for (int i = 0; i < CHIP_COUNT; i++) {
            ItemEntity chip = new ItemEntity(level, x, y + 0.2, z, new ItemStack(Items.EMERALD));
            double angle = ctx.random().nextDouble() * Math.PI * 2.0;
            double speed = 0.12 + ctx.random().nextDouble() * 0.13;
            chip.setDeltaMovement(Math.cos(angle) * speed, 0.3, Math.sin(angle) * speed);
            chip.setPickUpDelay(20);
            if (level.addFreshEntity(chip)) {
                CompoundTag entry = new CompoundTag();
                entry.store("id", UUIDUtil.CODEC, chip.getUUID());
                chips.add(entry);
            }
        }
        ctx.state().put("chips", chips);
    }

    /**
     * The scramble pass: steers the nearest allied villager
     * type mob toward each surviving chip and pays out the
     * heal when one arrives (see the class comment). Chips
     * that left the world are pruned in the same pass.
     */
    private void runScramble(AbilityContext ctx, ServerLevel level) {
        ListTag chips = ctx.state().getListOrEmpty("chips");
        if (chips.isEmpty()) {
            return;
        }
        ListTag remaining = new ListTag();
        for (int i = 0; i < chips.size(); i++) {
            CompoundTag entry = chips.getCompoundOrEmpty(i);
            Entity found = TraderLlamaAbilitySupport.resolveTracked(level, entry);
            if (!(found instanceof ItemEntity chip) || !chip.isAlive()) {
                continue; // Picked up by someone else or gone: the prize is lost.
            }
            List<LivingEntity> collectors = TraderLlamaAbilitySupport.alliesInBox(ctx,
                chip.getX(), chip.getY(), chip.getZ(), COLLECT_RADIUS, 3.0, 3.0);
            Mob nearest = null;
            double nearestSqr = Double.MAX_VALUE;
            for (LivingEntity candidate : collectors) {
                if (candidate instanceof Mob mob
                    && TraderLlamaAbilitySupport.isVillagerAlly(ctx, candidate)) {
                    double distanceSqr = mob.distanceToSqr(chip);
                    if (distanceSqr < nearestSqr) {
                        nearestSqr = distanceSqr;
                        nearest = mob;
                    }
                }
            }
            if (nearest == null) {
                remaining.add(entry);
                continue;
            }
            if (nearestSqr <= COLLECT_REACH * COLLECT_REACH) {
                // The prize is claimed: a small heal and a puff of hearts.
                chip.discard();
                nearest.addEffect(new MobEffectInstance(MobEffects.INSTANT_HEALTH, 1, 0));
                level.sendParticles(ParticleTypes.HEART, nearest.getX(), nearest.getY() + 1.0, nearest.getZ(),
                    6, 0.4, 0.3, 0.4, 0.05);
                continue;
            }
            // The scramble: hurry toward the chip.
            nearest.getNavigation().moveTo(chip.getX(), chip.getY(), chip.getZ(), 1.2);
            remaining.add(entry);
        }
        ctx.state().put("chips", remaining);
    }

    /** Records the target's live position for aiming after it dies or is lost. */
    private void recordTarget(AbilityContext ctx, LivingEntity target) {
        ctx.state().putDouble("tx", target.getX());
        ctx.state().putDouble("ty", target.getY());
        ctx.state().putDouble("tz", target.getZ());
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks >= HARD_FINISH_TICK) {
            return true;
        }
        return ticks >= EARLY_FINISH_TICK
            && ctx.state().getIntOr("burst", 0) == 1
            && ctx.state().getListOrEmpty("chips").isEmpty();
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to undo: the glob finishes its own flight,
        // the burst damage is instant, the heals are instant,
        // and leftover chips are ordinary emerald items.
    }
}
