package mod.advancedmobs.ability.snowgolem;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
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
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.UUID;

/**
 * Blizzard Barrage (Snow Golem ability 1 of 5, attack).
 *
 * WHAT IT DOES: the golem goes full snow globe: for five
 * seconds it machine guns real snowballs in a wide cone at
 * its target, SNOWFLAKE filling the air. Every snowball
 * that connects chips a little damage and stacks another
 * layer of SLOWNESS on the victim; a target hit enough
 * times ends up at SLOWNESS IV and frosts over completely,
 * taking vanilla freeze damage while the golem's allies
 * close in to shatter it.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the golem's current target within 16 blocks (distance
 *   squared 256).
 * - The barrage: ticks 1 to 100, one snowball every 2 ticks
 *   (50 snowballs total). Each is a real vanilla Snowball
 *   owned by the golem, fired from its eye position at
 *   velocity 1.6 with inaccuracy 5.0 (the wide cone), aimed
 *   at the target's chest (its feet plus 55 percent of its
 *   height). While the target lives its position is
 *   re-recorded at every shot; if it dies mid-barrage the
 *   remaining shots aim at the last recorded position. A
 *   SNOW_GOLEM_SHOOT (volume 0.5, pitch 0.9 to 1.1) plays
 *   per shot. The vanilla snowball's own hit does its
 *   vanilla 0 damage (3 against blazes); the chip below is
 *   this ability's addition, delivered in code because a
 *   snowball's impact damage is hard-coded in vanilla.
 * - Impact tracking: every fired snowball is tracked in the
 *   state tag with its last known position. Each tick, a
 *   tracked snowball that is still alive updates its
 *   record; one that is gone has impacted, and its impact
 *   runs at the last recorded position: one bounded enemy
 *   query in radius 1.7 (1.5 below to 2 above), and every
 *   enemy caught takes 1.0 chip damage as a mob attack
 *   credited to the golem and gains one hit stack (below).
 *   Fizzle guard (the Magma Mortar pattern): a snowball
 *   that vanishes within its first 2 ticks while still
 *   within 2.0 blocks of the golem clipped the golem or the
 *   ceiling above it and runs no impact.
 * - Hit stacks: per victim, stored in the state tag as a
 *   count plus the tick of the last hit. Each impact hit
 *   refreshes the victim's SLOWNESS to 60 ticks at an
 *   amplifier that grows with the count: 1 to 2 hits
 *   amplifier 0, 3 to 4 hits amplifier 1, 5 to 6 hits
 *   amplifier 2, 7 or more hits amplifier 3 (SLOWNESS IV,
 *   60 percent slower: nearly frozen in place). At 8 or
 *   more hits the victim is also frosted over in code:
 *   its frozen ticks are set to its full freeze requirement
 *   (Entity.setTicksFrozen), so it wears the vanilla frost
 *   overlay and takes vanilla freeze damage while fully
 *   frozen (CAPABILITIES.md: a fully frozen entity takes
 *   ongoing damage); the frost is re-applied on each
 *   further hit because frozen ticks decay on their own
 *   outside powder snow. Stacks expire 120 ticks after the
 *   victim's last hit (pruned on the 10-tick sweep); the
 *   SLOWNESS itself rides its own 60-tick clock after the
 *   last refresh, so end() has no victim cleanup.
 * - Finish: the activation ends when the firing phase is
 *   over (tick 100) and every tracked snowball has
 *   resolved, with a hard cap at tick 160 (any snowball
 *   still flying then is simply untracked; it lands as an
 *   ordinary vanilla snowball).
 *
 * TERRAIN RULE (Carter, Nov 21, 2026, binding; see
 * IMPLEMENTATION-RULES.md rule 6): this ability places and
 * breaks no blocks, so the mobGriefing gate does not apply:
 * snowballs are vanilla projectiles that never change
 * blocks, and everything else is code damage, effects,
 * frozen ticks, and particles.
 *
 * WHAT IT LOOKS LIKE: a rattling SNOW_GOLEM_SHOOT every
 * other tick, a cone of snowballs streaking at the target,
 * 4 SNOWFLAKE swirling around the golem every 2 ticks for
 * the whole barrage (the snow globe), and 4 SNOWFLAKE plus
 * 2 ITEM_SNOWBALL bursting at every impact. Documented
 * limit: the design's spinning head has no server-side
 * hook in 26.2 (the golem's head follows its body, and
 * steering the body would fight its AI), so the look is
 * carried by the cone of fire and the flake storm.
 *
 * PERFORMANCE: per tick at most one spawn (on shot ticks),
 * one pass over the tracked snowballs (at most 50 UUID
 * resolves and position writes, shrinking as they land),
 * and 4 particles every 2 ticks; an impact is one bounded
 * query, and impacts are at most 50. The stack sweep runs
 * every 10 ticks over a list that holds at most the
 * enemies one barrage can catch. Particle budget per
 * activation: globe 50 emissions times 4 = 200, impacts at
 * most 50 times 6 = 300, total at most 500 particles.
 *
 * State tag keys: "balls" (list of compounds, one per live
 * snowball: "id" UUID, "x"/"y"/"z" last position, "born"
 * the firing tick), "hits" (list of compounds, one per
 * victim: "id" UUID, "count" stacks, "last" tick of the
 * last hit), "tx"/"ty"/"tz" (the last recorded aim point).
 */
public final class BlizzardBarrageAbility implements MobAbility {

    static final int FIRE_TICKS = 100;
    private static final int HARD_CAP_TICKS = 160;
    private static final int FIRE_INTERVAL = 2;
    private static final float SNOWBALL_SPEED = 1.6f;
    private static final float SNOWBALL_INACCURACY = 5.0f;
    private static final double IMPACT_RADIUS = 1.7;
    private static final float CHIP_DAMAGE = 1.0f;
    private static final int STACK_EXPIRY_TICKS = 120;
    private static final int FREEZE_STACKS = 8;

    @Override
    public String id() {
        return "blizzard_barrage";
    }

    @Override
    public String displayName() {
        return "Blizzard Barrage";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        state.put("balls", new ListTag());
        state.put("hits", new ListTag());
        LivingEntity target = ctx.target();
        if (target != null) {
            recordAim(state, target);
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
        CompoundTag state = ctx.state();

        // The snow globe: flakes swirl around the golem for
        // the whole firing phase.
        if (ticks <= FIRE_TICKS && ticks % 2 == 0) {
            level.sendParticles(ParticleTypes.SNOWFLAKE, caster.getX(), caster.getY() + 1.2, caster.getZ(),
                4, 1.2, 0.8, 1.2, 0.03);
        }

        if (ticks <= FIRE_TICKS && ticks % FIRE_INTERVAL == 0) {
            fireSnowball(ctx, level, ticks);
        }
        trackSnowballs(ctx, level, ticks);

        if (ticks % 10 == 0) {
            pruneStacks(state, ticks);
        }
    }

    /** Fires one snowball of the barrage and records it for impact tracking. */
    private static void fireSnowball(AbilityContext ctx, ServerLevel level, int ticks) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        LivingEntity target = ctx.target();
        if (target != null) {
            recordAim(state, target);
        }
        double aimX = state.getDoubleOr("tx", caster.getX());
        double aimY = state.getDoubleOr("ty", caster.getY() + 1.0);
        double aimZ = state.getDoubleOr("tz", caster.getZ());

        Snowball snowball = new Snowball(level, caster, new ItemStack(Items.SNOWBALL));
        snowball.shoot(aimX - snowball.getX(), aimY - snowball.getY(), aimZ - snowball.getZ(),
            SNOWBALL_SPEED, SNOWBALL_INACCURACY);
        if (!level.addFreshEntity(snowball)) {
            return;
        }
        CompoundTag entry = new CompoundTag();
        entry.store("id", UUIDUtil.CODEC, snowball.getUUID());
        entry.putDouble("x", snowball.getX());
        entry.putDouble("y", snowball.getY());
        entry.putDouble("z", snowball.getZ());
        entry.putInt("born", ticks);
        state.getListOrEmpty("balls").add(entry);

        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.SNOW_GOLEM_SHOOT, SoundSource.HOSTILE, 0.5f,
            0.9f + ctx.random().nextFloat() * 0.2f);
    }

    /** Records the aim point: the target's chest. */
    private static void recordAim(CompoundTag state, LivingEntity target) {
        state.putDouble("tx", target.getX());
        state.putDouble("ty", target.getY() + target.getBbHeight() * 0.55);
        state.putDouble("tz", target.getZ());
    }

    /**
     * The tracking pass: live snowballs refresh their last
     * position; gone snowballs run their impact at the last
     * recorded position (subject to the fizzle guard) and
     * leave the list.
     */
    private static void trackSnowballs(AbilityContext ctx, ServerLevel level, int ticks) {
        CompoundTag state = ctx.state();
        ListTag balls = state.getListOrEmpty("balls");
        if (balls.isEmpty()) {
            return;
        }
        Mob caster = ctx.mob();
        ListTag remaining = new ListTag();
        for (int i = 0; i < balls.size(); i++) {
            CompoundTag entry = balls.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            Entity entity = id != null ? level.getEntity(id) : null;
            if (entity != null && entity.isAlive()) {
                entry.putDouble("x", entity.getX());
                entry.putDouble("y", entity.getY());
                entry.putDouble("z", entity.getZ());
                remaining.add(entry);
                continue;
            }
            double x = entry.getDoubleOr("x", caster.getX());
            double y = entry.getDoubleOr("y", caster.getY());
            double z = entry.getDoubleOr("z", caster.getZ());
            int born = entry.getIntOr("born", 0);
            double dx = x - caster.getX();
            double dz = z - caster.getZ();
            boolean fizzle = ticks - born <= 2 && dx * dx + dz * dz <= 4.0;
            if (!fizzle) {
                runImpact(ctx, level, x, y, z, ticks);
            }
        }
        state.put("balls", remaining);
    }

    /** One snowball's impact: chip damage and one hit stack for every enemy caught. */
    private static void runImpact(AbilityContext ctx, ServerLevel level, double x, double y, double z, int ticks) {
        level.sendParticles(ParticleTypes.SNOWFLAKE, x, y, z, 4, 0.4, 0.4, 0.4, 0.05);
        level.sendParticles(ParticleTypes.ITEM_SNOWBALL, x, y, z, 2, 0.3, 0.3, 0.3, 0.1);
        Mob caster = ctx.mob();
        List<LivingEntity> victims = SnowGolemAbilitySupport.enemiesInBox(ctx, x, y, z, IMPACT_RADIUS, 1.5, 2.0);
        for (LivingEntity victim : victims) {
            victim.hurtServer(level, level.damageSources().mobAttack(caster), CHIP_DAMAGE);
            addStack(ctx, victim, ticks);
        }
    }

    /** Adds one hit stack to the victim and refreshes its SLOWNESS (and frost) to match. */
    private static void addStack(AbilityContext ctx, LivingEntity victim, int ticks) {
        CompoundTag state = ctx.state();
        ListTag hits = state.getListOrEmpty("hits");
        UUID victimId = victim.getUUID();
        int count = 1;
        boolean found = false;
        for (int i = 0; i < hits.size(); i++) {
            CompoundTag entry = hits.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (victimId.equals(id)) {
                count = entry.getIntOr("count", 0) + 1;
                entry.putInt("count", count);
                entry.putInt("last", ticks);
                found = true;
                break;
            }
        }
        if (!found) {
            CompoundTag entry = new CompoundTag();
            entry.store("id", UUIDUtil.CODEC, victimId);
            entry.putInt("count", count);
            entry.putInt("last", ticks);
            hits.add(entry);
        }
        int amplifier = count >= 7 ? 3 : count >= 5 ? 2 : count >= 3 ? 1 : 0;
        victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, amplifier));
        if (count >= FREEZE_STACKS && victim.canFreeze()) {
            victim.setTicksFrozen(victim.getTicksRequiredToFreeze());
        }
    }

    /** Drops stack records whose last hit is older than the expiry window. */
    private static void pruneStacks(CompoundTag state, int ticks) {
        ListTag hits = state.getListOrEmpty("hits");
        if (hits.isEmpty()) {
            return;
        }
        ListTag kept = new ListTag();
        for (int i = 0; i < hits.size(); i++) {
            CompoundTag entry = hits.getCompoundOrEmpty(i);
            if (ticks - entry.getIntOr("last", 0) <= STACK_EXPIRY_TICKS) {
                kept.add(entry);
            }
        }
        state.put("hits", kept);
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks >= HARD_CAP_TICKS) {
            return true;
        }
        return ticks > FIRE_TICKS && ctx.state().getListOrEmpty("balls").isEmpty();
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to undo: snowballs still flying land as
        // ordinary vanilla snowballs, victim SLOWNESS rides
        // its own clock, and frost decays on its own outside
        // powder snow. The state tag is discarded by the
        // framework.
    }
}
