package mod.advancedmobs.ability.tropicalfish;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.fish.TropicalFish;
import net.minecraft.world.item.DyeColor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Mirage School (Tropical Fish ability 4 of 5,
 * summon / defense).
 *
 * WHAT IT DOES: three shimmering copies of the fish
 * blink into being around it, glowing bright, each
 * wearing different colors and trailing its own
 * colored dust. Enemies waste their attacks on the
 * mirages: the copies are one-hit fragile and pop
 * into a burst of colored particles when struck.
 * The real fish, unglowing and dull by comparison,
 * slips around behind the target and strikes from
 * the blind side while every eye is on the show.
 *
 * MECHANICS (the spec; fix bugs against these
 * numbers):
 * - Trigger (canTrigger): the framework's combat
 *   gate, plus the fish's current target within 16
 *   blocks (distance squared 256), plus the fish in
 *   water.
 * - Duration: 300 ticks (15 seconds), ending early
 *   the moment the last decoy has popped (the
 *   babysit keeps the popped count current in the
 *   state tag and isFinished reads it).
 * - The copies (start()): 3 tropical fish spawned
 *   on a radius 1.5 ring around the caster with
 *   EntitySpawnReason.MOB_SUMMONED and finalizeSpawn
 *   like every other summon in this mod. After
 *   finalizeSpawn, each decoy's variant is forced
 *   through the 26.2 data components (the design's
 *   differently colored copies): base colors red,
 *   blue, yellow and pattern colors white, orange,
 *   cyan by decoy index, set with Entity.setComponent
 *   on TROPICAL_FISH_BASE_COLOR and
 *   TROPICAL_FISH_PATTERN_COLOR. Each decoy gains
 *   GLOWING for 320 ticks (the design's glowing
 *   line) and its MAX_HEALTH base value is set to
 *   1.0 with health 1.0 (a permanent base change on
 *   a brand new mob, the summon sheet precedent):
 *   any solid hit pops it, which is the point. Team
 *   assignment, per decoy, both directions plus the
 *   registry: the caster's data gains the decoy as
 *   an ally, the decoy's data gains the caster, and
 *   the pair is registered in SummonerTracker (which
 *   also tags the decoy umw_summoned for the natural
 *   spawn blocker). FISH_SWIM at volume 1.0, pitch
 *   1.4, and 8 POOF at the caster as they shimmer in.
 * - The milling (babysit, every 5 ticks): each
 *   living decoy is steered at 0.5 blocks per tick
 *   toward an anchor on a radius 2.0 ring around
 *   the target's live position (slot angle 120
 *   degrees apart plus the activation ticks times
 *   0.05 radians, at the target's height); with no
 *   living target the ring centers on the caster.
 *   The decoys never attack: they have no appetite
 *   for fighting (the design's line), all they do
 *   is shine and swim. Each decoy's last known
 *   position is written into its tracked entry on
 *   every babysit.
 * - The trails (every 4 ticks): 1 DUST mote of the
 *   decoy's own color at each living decoy (red,
 *   blue, gold by index, the design's different
 *   colored dust line; the options are pre-built
 *   static finals).
 * - The waste (retarget, every 20 ticks): one
 *   bounded query (radius 12.0 around the caster,
 *   3.0 vertical both ways): every enemy Mob in it
 *   whose current target is the CASTER is
 *   retargeted onto a random living decoy (the
 *   Sheep mirage pattern; players are never
 *   retargeted, they can think for themselves).
 * - The pop (babysit detection): a decoy that no
 *   longer resolves, or resolves dead, and is not
 *   yet marked popped bursts at its last known
 *   position: 10 DUST motes of its own color, and
 *   its popped flag and the activation's popped
 *   count are recorded, and the caster's ally
 *   record for it is removed. A decoy that merely
 *   unloaded with its chunk resolves again later
 *   and is never popped early (level.getEntity
 *   returns null for an unloaded entity too, so a
 *   decoy lost that way WOULD be miscounted; the
 *   school fights in open water around its caster,
 *   which keeps the decoys in loaded chunks in
 *   practice, and the count only ends the ability
 *   early, never corrupts state).
 * - The real fish (every tick): the caster is
 *   steered at 0.6 blocks per tick toward a flank
 *   anchor 2.2 blocks BEHIND the target (the
 *   target's position minus its look direction
 *   times 2.2, at its height). Within 1.6 blocks of
 *   the target, at most once per 20 ticks, the
 *   strike lands: 2.0 flat damage as a mob attack
 *   credited to the caster (the tropical fish has
 *   no ATTACK_DAMAGE instance; see the provider
 *   comment) and 3 CRIT particles at the target.
 * - end(): every surviving decoy pops where it
 *   floats (8 DUST of its color) and is discarded,
 *   and the alliance records are removed on both
 *   sides for every decoy. The school is a
 *   temporary summon and does not outlive the
 *   activation (the Bait Ball and Alley Cat
 *   precedents). If the caster dies first, the
 *   framework termination path runs this same
 *   end(). SummonerTracker records are deliberately
 *   not unregistered, matching the other summons in
 *   this mod.
 *
 * WHAT IT LOOKS LIKE: three glowing tropical fish
 * in clashing colors wheeling around the enemy in
 * ribbons of red, blue, and gold dust, popping one
 * by one into colored sparks as blows land on them,
 * while the plain little original keeps appearing
 * at the enemy's back for one more nip.
 *
 * PERFORMANCE: start() does 3 spawns, once. The
 * babysit runs every 5 ticks resolving at most 3
 * UUIDs with at most 3 velocity writes; the trails
 * resolve at most 3 UUIDs every 4 ticks; the
 * retarget is one bounded query every 20 ticks; the
 * caster's steering is one write per tick. Particle
 * budget per activation: 8 at start, trails at most
 * 75 emissions times 3 = 225 (fewer as decoys pop),
 * pops 3 times 10 = 30, end bursts at most 3 times
 * 8 = 24, total at most 287.
 *
 * TERRAIN: this ability changes no blocks; the gate
 * does not apply.
 *
 * State tag keys: "school" (list of compounds, one
 * per decoy: "id", "trail" color index, "x", "y",
 * "z" last known position, "popped"), "popped"
 * (decoys popped so far), "total" (decoys actually
 * spawned), "strike" (the activation tick of the
 * caster's last landed strike).
 */
public final class MirageSchoolAbility implements MobAbility {

    private static final int DURATION_TICKS = 300;
    private static final int SCHOOL_SIZE = 3;
    private static final int EFFECT_TICKS = 320;
    private static final int BABYSIT_INTERVAL = 5;
    private static final int TRAIL_INTERVAL = 4;
    private static final int RETARGET_INTERVAL = 20;
    private static final double RING_RADIUS = 2.0;
    private static final double STEER_SPEED = 0.5;
    private static final double CASTER_SPEED = 0.6;
    private static final double STRIKE_RANGE_SQR = 1.6 * 1.6;
    private static final int STRIKE_COOLDOWN_TICKS = 20;
    private static final float STRIKE_DAMAGE = 2.0f;

    /** Decoy trail colors by index (RGB ints): red, blue, gold. Built once. */
    private static final DustParticleOptions[] TRAIL_COLORS = {
        new DustParticleOptions(0xFF4040, 1.2f),
        new DustParticleOptions(0x4040FF, 1.2f),
        new DustParticleOptions(0xFFD24A, 1.2f)
    };

    /** Decoy variant base colors by index, matching TRAIL_COLORS in spirit. */
    private static final DyeColor[] BASE_COLORS = {DyeColor.RED, DyeColor.BLUE, DyeColor.YELLOW};

    /** Decoy variant pattern colors by index. */
    private static final DyeColor[] PATTERN_COLORS = {DyeColor.WHITE, DyeColor.ORANGE, DyeColor.CYAN};

    @Override
    public String id() {
        return "mirage_school";
    }

    @Override
    public String displayName() {
        return "Mirage School";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().isInWater()
            && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        state.put("school", new ListTag());
        state.putInt("popped", 0);
        state.putInt("total", 0);
        state.putInt("strike", -STRIKE_COOLDOWN_TICKS);
        if (level == null) {
            return;
        }

        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.FISH_SWIM, SoundSource.HOSTILE, 1.0f, 1.4f);
        level.sendParticles(ParticleTypes.POOF, caster.getX(), caster.getY() + 0.3, caster.getZ(),
            8, 0.6, 0.4, 0.6, 0.05);

        int spawned = 0;
        for (int i = 0; i < SCHOOL_SIZE; i++) {
            TropicalFish decoy = EntityTypes.TROPICAL_FISH.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (decoy == null) {
                continue;
            }
            double angle = i * Math.PI * 2.0 / SCHOOL_SIZE;
            decoy.snapTo(caster.getX() + Math.cos(angle) * 1.5, caster.getY(),
                caster.getZ() + Math.sin(angle) * 1.5, ctx.random().nextFloat() * 360.0f, 0.0f);
            decoy.finalizeSpawn(level, level.getCurrentDifficultyAt(decoy.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
            // The forced colors: each mirage wears its own variant.
            decoy.setComponent(DataComponents.TROPICAL_FISH_BASE_COLOR, BASE_COLORS[i]);
            decoy.setComponent(DataComponents.TROPICAL_FISH_PATTERN_COLOR, PATTERN_COLORS[i]);
            // One-hit fragile: the pop is the design, not a bug.
            var maxHealth = decoy.getAttribute(Attributes.MAX_HEALTH);
            if (maxHealth != null) {
                maxHealth.setBaseValue(1.0);
            }
            decoy.setHealth(1.0f);
            if (!level.addFreshEntity(decoy)) {
                continue;
            }
            decoy.addEffect(new MobEffectInstance(MobEffects.GLOWING, EFFECT_TICKS, 0, false, false, false));
            // Team assignment, both directions, plus the summoner registry.
            ctx.data().addAlly(decoy.getUUID());
            MobWarData.get(decoy).addAlly(caster.getUUID());
            SummonerTracker.registerSummoned(decoy.getUUID(), caster.getUUID());
            TropicalFishAbilitySupport.trackEntity(state, "school", decoy);
            ListTag school = state.getListOrEmpty("school");
            CompoundTag entry = school.getCompoundOrEmpty(school.size() - 1);
            entry.putInt("trail", i);
            entry.putDouble("x", decoy.getX());
            entry.putDouble("y", decoy.getY());
            entry.putDouble("z", decoy.getZ());
            entry.putBoolean("popped", false);
            state.put("school", school);
            spawned++;
        }
        state.putInt("total", spawned);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();

        // The real fish slips behind its target and nips.
        LivingEntity target = ctx.target();
        if (target != null) {
            double lookX = target.getLookAngle().x;
            double lookZ = target.getLookAngle().z;
            TropicalFishAbilitySupport.steerToward(caster,
                target.getX() - lookX * 2.2, target.getY(), target.getZ() - lookZ * 2.2, CASTER_SPEED);
            if (caster.distanceToSqr(target) <= STRIKE_RANGE_SQR
                && ticks - state.getIntOr("strike", -STRIKE_COOLDOWN_TICKS) >= STRIKE_COOLDOWN_TICKS) {
                state.putInt("strike", ticks);
                target.hurtServer(level, level.damageSources().mobAttack(caster), STRIKE_DAMAGE);
                level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY() + 0.4, target.getZ(),
                    3, 0.3, 0.3, 0.3, 0.05);
            }
        }

        if (ticks % BABYSIT_INTERVAL == 0) {
            runBabysit(ctx, level, ticks);
        }
        if (ticks % TRAIL_INTERVAL == 0) {
            runTrails(ctx, level);
        }
        if (ticks % RETARGET_INTERVAL == 0) {
            runRetarget(ctx, level);
        }
    }

    /** One babysit pass: steer the living decoys, record their positions, detect and burst the popped. */
    private void runBabysit(AbilityContext ctx, ServerLevel level, int ticks) {
        Mob caster = ctx.mob();
        CompoundTag state = ctx.state();
        LivingEntity target = ctx.target();
        double centerX = target != null ? target.getX() : caster.getX();
        double centerY = target != null ? target.getY() : caster.getY();
        double centerZ = target != null ? target.getZ() : caster.getZ();

        ListTag school = state.getListOrEmpty("school");
        int popped = state.getIntOr("popped", 0);
        for (int i = 0; i < school.size(); i++) {
            CompoundTag entry = school.getCompoundOrEmpty(i);
            if (entry.getBooleanOr("popped", false)) {
                continue;
            }
            Entity found = TropicalFishAbilitySupport.resolveTracked(level, entry);
            if (!(found instanceof TropicalFish decoy) || !decoy.isAlive()) {
                // The pop: burst at the last known position, once.
                entry.putBoolean("popped", true);
                popped++;
                UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
                if (id != null) {
                    ctx.data().removeAlly(id);
                }
                DustParticleOptions color = TRAIL_COLORS[entry.getIntOr("trail", 0) % TRAIL_COLORS.length];
                level.sendParticles(color,
                    entry.getDoubleOr("x", caster.getX()), entry.getDoubleOr("y", caster.getY()),
                    entry.getDoubleOr("z", caster.getZ()), 10, 0.4, 0.3, 0.4, 0.05);
                continue;
            }
            entry.putDouble("x", decoy.getX());
            entry.putDouble("y", decoy.getY());
            entry.putDouble("z", decoy.getZ());
            double angle = i * Math.PI * 2.0 / SCHOOL_SIZE + ticks * 0.05;
            TropicalFishAbilitySupport.steerToward(decoy,
                centerX + Math.cos(angle) * RING_RADIUS, centerY,
                centerZ + Math.sin(angle) * RING_RADIUS, STEER_SPEED);
        }
        state.put("school", school);
        state.putInt("popped", popped);
    }

    /** One trail pass: each living decoy sheds a mote of its own color. */
    private void runTrails(AbilityContext ctx, ServerLevel level) {
        ListTag school = ctx.state().getListOrEmpty("school");
        for (int i = 0; i < school.size(); i++) {
            CompoundTag entry = school.getCompoundOrEmpty(i);
            if (entry.getBooleanOr("popped", false)) {
                continue;
            }
            Entity found = TropicalFishAbilitySupport.resolveTracked(level, entry);
            if (found instanceof TropicalFish decoy && decoy.isAlive()) {
                DustParticleOptions color = TRAIL_COLORS[entry.getIntOr("trail", 0) % TRAIL_COLORS.length];
                level.sendParticles(color, decoy.getX(), decoy.getY() + 0.2, decoy.getZ(),
                    1, 0.2, 0.15, 0.2, 0.02);
            }
        }
    }

    /** One retarget pass: enemies locked onto the real fish are handed a mirage instead. */
    private void runRetarget(AbilityContext ctx, ServerLevel level) {
        Mob caster = ctx.mob();
        List<TropicalFish> living = new ArrayList<>();
        ListTag school = ctx.state().getListOrEmpty("school");
        for (int i = 0; i < school.size(); i++) {
            CompoundTag entry = school.getCompoundOrEmpty(i);
            if (entry.getBooleanOr("popped", false)) {
                continue;
            }
            Entity found = TropicalFishAbilitySupport.resolveTracked(level, entry);
            if (found instanceof TropicalFish decoy && decoy.isAlive()) {
                living.add(decoy);
            }
        }
        if (living.isEmpty()) {
            return;
        }
        List<LivingEntity> enemies = TropicalFishAbilitySupport.enemiesAroundSelf(ctx, 12.0, 3.0, 3.0);
        for (LivingEntity enemy : enemies) {
            if (enemy instanceof Mob enemyMob && enemyMob.getTarget() == caster) {
                enemyMob.setTarget(living.get(ctx.random().nextInt(living.size())));
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        CompoundTag state = ctx.state();
        int total = state.getIntOr("total", 0);
        if (total > 0 && state.getIntOr("popped", 0) >= total) {
            return true;
        }
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag school = ctx.state().getListOrEmpty("school");
        for (int i = 0; i < school.size(); i++) {
            CompoundTag entry = school.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id == null) {
                continue;
            }
            // The alliance ends with the ability, whichever side is still here.
            ctx.data().removeAlly(id);
            Entity found = level.getEntity(id);
            if (found instanceof TropicalFish decoy) {
                MobWarData.get(decoy).removeAlly(caster.getUUID());
                if (decoy.isAlive()) {
                    DustParticleOptions color = TRAIL_COLORS[entry.getIntOr("trail", 0) % TRAIL_COLORS.length];
                    level.sendParticles(color, decoy.getX(), decoy.getY() + 0.2, decoy.getZ(),
                        8, 0.35, 0.25, 0.35, 0.05);
                    decoy.discard();
                }
            }
        }
    }
}
