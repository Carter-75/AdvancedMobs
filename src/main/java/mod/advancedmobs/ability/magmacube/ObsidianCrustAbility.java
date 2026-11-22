package mod.advancedmobs.ability.magmacube;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.UUID;

/**
 * Obsidian Crust (Magma Cube ability 3 of 5, defense).
 *
 * WHAT IT DOES: the magma cube plates itself in magma block
 * displays that orbit its body, LAVA dripping between the
 * plates, and every hit it takes locks another plate into the
 * shell and thickens it: its ARMOR climbs stack by stack and
 * its EXPLOSION_KNOCKBACK_RESISTANCE rises to match, so
 * sustained focus fire plates the cube faster than it wounds
 * it. A fresh crust starts thin; a cube that has been beaten
 * on for the full duration is a walking bunker.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): target exists within 12 blocks
 *   (distance squared 144).
 * - Duration: exactly 300 ticks (15 seconds) from start().
 * - Stacks: the ability polls the cube's health every 4 ticks
 *   against the last health it recorded. A drop since the last
 *   poll is a hit and adds 1 stack, up to 10 stacks (several
 *   hits inside one 4 tick window plate as one stack; the
 *   crust answers sustained fire, not single-tick burst
 *   bookkeeping). A health rise (healing, a split resize)
 *   simply re-baselines the poll. The recorded health starts
 *   at the cube's health in start(), so damage taken before
 *   the crust grew never plates it.
 * - The hardening, refreshed on every stack gain with
 *   addOrUpdate under fixed ids: ARMOR add_value of +2 per
 *   stack, +20 at the cap (modifier id
 *   advancedmobs:obsidian_crust_armor; 20 points is a full
 *   armor bar on top of the cube's own), and
 *   EXPLOSION_KNOCKBACK_RESISTANCE add_value of +0.05 per
 *   stack, +0.5 at the cap (modifier id
 *   advancedmobs:obsidian_crust_blast_padding; half of all
 *   explosion knockback ignored, the attribute's range being
 *   0 to 1). Both are removed in end().
 * - The plates: magma block displays at scale 0.6, full
 *   brightness, gliding with a 2 tick teleport duration. 3
 *   plates form the shell at start(); a 4th locks in at 3
 *   stacks, a 5th at 6 stacks, a 6th at 9 stacks. Every tick
 *   each plate is repositioned onto a slowly rotating bearing
 *   (its index spread evenly around the circle, the whole ring
 *   turning 0.04 radians per tick) at radius (half the cube's
 *   width plus 0.5) and height 45 percent of the cube's height:
 *   the shell orbits and travels with the cube. The plates are
 *   pure visuals: displays have no hitbox and block nothing;
 *   the defense is the attributes above. A stack gain pops 3
 *   LAVA particles at the cube and a LAVA_POP (volume 0.6,
 *   pitch 0.7) as the new plate locks.
 *
 * WHAT IT LOOKS LIKE: three glowing magma plates sliding
 * around the cube's body in a slow orbit, LAVA drips (2 every
 * 20 ticks) weeping between them, and the shell visibly
 * growing (a new plate, a drip burst, a deep pop) every time a
 * hit lands, up to a six plate shell on a cube that has taken
 * a beating.
 *
 * PERFORMANCE: per tick the work is at most 6 UUID resolves
 * and 6 snapTo writes for the plates; the health poll runs
 * every 4 ticks (one float read and compare), the drip pulse
 * every 20 ticks. No entity scans, no allocations in the tick
 * path beyond the resolved references. Particle budget per
 * activation: stack gains at most 10 times 3 = 30 LAVA, drips
 * 15 pulses times 2 = 30, total at most 60 particles. The 6
 * displays are the only extra entities, alive only for the
 * ability's 15 seconds.
 *
 * CLEANUP: end() removes both attribute modifiers and discards
 * every plate display that still resolves. If the caster dies
 * mid-crust, the framework termination path runs this same
 * end(), so the shell never outlives the ability or its
 * caster.
 *
 * State tag keys: "plates" (list of compounds, one per plate:
 * "id", the display's UUID), "stacks" (crust stacks, 0 to 10),
 * "last_health" (the health poll baseline, stored as a double).
 */
public final class ObsidianCrustAbility implements MobAbility {

    static final int DURATION_TICKS = 300;
    private static final int MAX_STACKS = 10;
    private static final int HEALTH_POLL_INTERVAL = 4;
    private static final double ARMOR_PER_STACK = 2.0;
    private static final double BLAST_PADDING_PER_STACK = 0.05;
    private static final float PLATE_SCALE = 0.6f;
    private static final double ORBIT_SPEED = 0.04;
    private static final Identifier ARMOR_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "obsidian_crust_armor");
    private static final Identifier BLAST_PADDING_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "obsidian_crust_blast_padding");

    @Override
    public String id() {
        return "obsidian_crust";
    }

    @Override
    public String displayName() {
        return "Obsidian Crust";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        return ctx.target() != null && ctx.mob().distanceToSqr(ctx.target()) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();
        state.putInt("stacks", 0);
        state.putDouble("last_health", mob.getHealth());
        ListTag plates = new ListTag();
        state.put("plates", plates);
        if (level != null) {
            for (int i = 0; i < 3; i++) {
                addPlate(ctx, level);
            }
            positionPlates(ctx, level);
            level.sendParticles(ParticleTypes.LAVA, mob.getX(), mob.getY() + 0.5, mob.getZ(),
                6, mob.getBbWidth() * 0.5, 0.4, mob.getBbWidth() * 0.5, 0.05);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.LAVA_POP,
                SoundSource.HOSTILE, 0.8f, 0.6f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        CompoundTag state = ctx.state();
        Mob mob = ctx.mob();

        positionPlates(ctx, level);

        if (ticks % HEALTH_POLL_INTERVAL == 0) {
            pollHealth(ctx, level);
        }
        if (ticks % 20 == 0) {
            level.sendParticles(ParticleTypes.LAVA, mob.getX(), mob.getY() + 0.4, mob.getZ(),
                2, mob.getBbWidth() * 0.5, 0.4, mob.getBbWidth() * 0.5, 0.03);
        }
    }

    /**
     * The health poll: a drop against the baseline is a hit and
     * plates the crust by one stack (see the class comment).
     * Re-baselines on every poll, hit or heal.
     */
    private static void pollHealth(AbilityContext ctx, ServerLevel level) {
        CompoundTag state = ctx.state();
        Mob mob = ctx.mob();
        float health = mob.getHealth();
        double lastHealth = state.getDoubleOr("last_health", health);
        state.putDouble("last_health", health);
        int stacks = state.getIntOr("stacks", 0);
        if (health >= lastHealth - 0.01 || stacks >= MAX_STACKS) {
            return;
        }
        stacks++;
        state.putInt("stacks", stacks);
        MagmaCubeAbilitySupport.addModifier(mob, Attributes.ARMOR,
            new AttributeModifier(ARMOR_ID, ARMOR_PER_STACK * stacks, AttributeModifier.Operation.ADD_VALUE));
        MagmaCubeAbilitySupport.addModifier(mob, Attributes.EXPLOSION_KNOCKBACK_RESISTANCE,
            new AttributeModifier(BLAST_PADDING_ID, BLAST_PADDING_PER_STACK * stacks,
                AttributeModifier.Operation.ADD_VALUE));
        // The shell grows: a new plate locks in at 3, 6, and 9 stacks.
        if (stacks == 3 || stacks == 6 || stacks == 9) {
            addPlate(ctx, level);
        }
        level.sendParticles(ParticleTypes.LAVA, mob.getX(), mob.getY() + 0.5, mob.getZ(),
            3, mob.getBbWidth() * 0.5, 0.4, mob.getBbWidth() * 0.5, 0.06);
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.LAVA_POP,
            SoundSource.HOSTILE, 0.6f, 0.7f);
    }

    /** Creates one crust plate display and records it in the state tag. */
    private static void addPlate(AbilityContext ctx, ServerLevel level) {
        Entity plate = MagmaCubeAbilitySupport.createCrustDisplay(level, PLATE_SCALE);
        if (plate == null) {
            return;
        }
        level.addFreshEntity(plate);
        CompoundTag entry = new CompoundTag();
        entry.store("id", UUIDUtil.CODEC, plate.getUUID());
        ListTag plates = ctx.state().getListOrEmpty("plates");
        plates.add(entry);
        ctx.state().put("plates", plates);
    }

    /** Places every plate on its rotating bearing around the cube. */
    private void positionPlates(AbilityContext ctx, ServerLevel level) {
        Mob mob = ctx.mob();
        ListTag plates = ctx.state().getListOrEmpty("plates");
        int count = plates.size();
        if (count == 0) {
            return;
        }
        double radius = mob.getBbWidth() / 2.0 + 0.5;
        double y = mob.getY() + mob.getBbHeight() * 0.45;
        double turn = ctx.ticksActive() * ORBIT_SPEED;
        for (int i = 0; i < count; i++) {
            Entity plate = resolvePlate(level, plates.getCompoundOrEmpty(i));
            if (plate == null) {
                continue;
            }
            double angle = turn + i * (Math.PI * 2.0 / count);
            plate.snapTo(mob.getX() + Math.cos(angle) * radius, y, mob.getZ() + Math.sin(angle) * radius,
                (float) Math.toDegrees(-angle), 0.0f);
        }
    }

    /** Resolves a tracked plate display from its list entry, or null when gone. */
    private Entity resolvePlate(ServerLevel level, CompoundTag entry) {
        if (!entry.contains("id")) {
            return null;
        }
        UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
        return id != null ? level.getEntity(id) : null;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        MagmaCubeAbilitySupport.removeModifier(mob, Attributes.ARMOR, ARMOR_ID);
        MagmaCubeAbilitySupport.removeModifier(mob, Attributes.EXPLOSION_KNOCKBACK_RESISTANCE, BLAST_PADDING_ID);
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag plates = ctx.state().getListOrEmpty("plates");
        for (int i = 0; i < plates.size(); i++) {
            Entity plate = resolvePlate(level, plates.getCompoundOrEmpty(i));
            if (plate != null) {
                plate.discard();
            }
        }
    }
}
