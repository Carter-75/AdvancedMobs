package mod.advancedmobs.ability.spider;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.UUID;

/**
 * Egg Sac Shield (Spider ability 4 of 5, defense).
 *
 * WHAT IT DOES: a grossly swollen egg sac grows over the
 * spider's back, two layered block displays, white and veined,
 * that draw the eye and the blows alike. While it holds, the
 * sac is a deep pool of ABSORPTION soaking damage meant for the
 * spider. When the pool finally runs dry the sac ruptures,
 * spraying ITEM_COBWEB and silk DUST across its attackers and
 * slowing them in the mess.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus the
 *   spider's current target within 10 blocks (distance squared
 *   100). The shield is raised for a fight already on top of
 *   the spider.
 * - The pool (start()): a transient MAX_ABSORPTION modifier of
 *   +16.0 (8 hearts, Identifier advancedmobs:egg_sac_absorption)
 *   is added, then the spider's absorption amount is set to
 *   16.0. Note: this replaces any absorption the spider already
 *   had; spiders do not carry absorption from any other system
 *   in this mod, so nothing is lost in practice. The modifier
 *   is removed in end() and the amount is clamped to the
 *   unmodified maximum there.
 * - The look (start(), then every tick): 2 block displays ride
 *   the spider's back: an outer sac of white wool at scale 1.2
 *   and a veined inner sac of bone block at scale 0.85, both at
 *   full brightness, re-snapped every tick to the spider's
 *   position at 0.55 blocks above its feet (a spider's back).
 *   Displays are weightless props: they take no damage and
 *   block nothing; the absorption pool is the shield.
 * - The rupture check, every 5 ticks: when the spider's
 *   absorption amount reaches 0 while the ability is active,
 *   the sac ruptures (state "ruptured" set, and the ability
 *   finishes on that same pass): both displays are discarded,
 *   24 ITEM_COBWEB and 12 silk DUST (color 0xEDEAE0, scale 1.0)
 *   spray at the spider, a WOOL_BREAK plays at volume 1.0, and
 *   every enemy within radius 3.5 of the spider (box reach 1.5
 *   below, 2.0 above) gets SLOWNESS II (amplifier 1) for 60
 *   ticks.
 * - Duration: 240 ticks (12 seconds) if the pool survives. On a
 *   natural expiry the sac simply deflates: the displays are
 *   discarded in end() with no spray and no slow. Only a pool
 *   drained by damage ruptures.
 *
 * WHAT IT LOOKS LIKE: a pale swollen sac swelling over the
 * spider's back, veined bone showing through the white silk,
 * riding every skitter and turn, until the blows pop it in a
 * spray of cobweb flecks and white silk dust that leaves its
 * attackers wading slow through the mess.
 *
 * PERFORMANCE: the per-tick work is 2 snapTo calls for the
 * displays, nothing else. The rupture check runs every 5 ticks
 * as one attribute read. The rupture's single bounded entity
 * query runs at most once per activation. No registry or config
 * lookups in the hot path: the modifier and the particle
 * options are static finals resolved at class load. Particle
 * budget per activation: rupture 24 + 12 = 36, total at most 36
 * particles (a quiet expiry emits none).
 *
 * CLEANUP: end() removes the MAX_ABSORPTION modifier, clamps
 * the absorption amount to the unmodified maximum, and discards
 * any display still riding (after a rupture they are already
 * gone; discard is idempotent). Runs identically on a natural
 * finish and on framework termination.
 *
 * State tag keys: "sacs" (list of compounds, one per display:
 * "id" its UUID), "ruptured" (1 once the pool ran dry and the
 * spray fired).
 */
public final class EggSacShieldAbility implements MobAbility {

    static final int DURATION_TICKS = 240;
    private static final int RUPTURE_CHECK_INTERVAL = 5;
    private static final double ABSORPTION_POOL = 16.0;
    private static final float OUTER_SCALE = 1.2f;
    private static final float INNER_SCALE = 0.85f;
    private static final double BACK_HEIGHT = 0.55;
    private static final Identifier ABSORPTION_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "egg_sac_absorption");
    private static final DustParticleOptions SILK_DUST = new DustParticleOptions(0xEDEAE0, 1.0f);

    @Override
    public String id() {
        return "egg_sac_shield";
    }

    @Override
    public String displayName() {
        return "Egg Sac Shield";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 100.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        CompoundTag state = ctx.state();

        // The pool: raise the cap, then fill it.
        AttributeInstance maxAbsorption = mob.getAttribute(Attributes.MAX_ABSORPTION);
        if (maxAbsorption != null) {
            maxAbsorption.addOrUpdateTransientModifier(
                new AttributeModifier(ABSORPTION_ID, ABSORPTION_POOL, AttributeModifier.Operation.ADD_VALUE));
        }
        mob.setAbsorptionAmount((float) ABSORPTION_POOL);

        // The look: two layered sacs on the spider's back.
        ListTag sacs = new ListTag();
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            Entity outer = SpiderAbilitySupport.createBrightBlockDisplay(level,
                Blocks.WOOL.white().defaultBlockState(), OUTER_SCALE);
            if (outer != null) {
                positionSac(ctx, outer);
                level.addFreshEntity(outer);
                sacs.add(entry(outer));
            }
            Entity inner = SpiderAbilitySupport.createBrightBlockDisplay(level,
                Blocks.BONE_BLOCK.defaultBlockState(), INNER_SCALE);
            if (inner != null) {
                positionSac(ctx, inner);
                level.addFreshEntity(inner);
                sacs.add(entry(inner));
            }
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.WOOL_PLACE, SoundSource.HOSTILE, 0.8f, 0.8f);
        }
        state.put("sacs", sacs);
    }

    /** A state list entry holding one display's UUID. */
    private static CompoundTag entry(Entity display) {
        CompoundTag entry = new CompoundTag();
        entry.store("id", UUIDUtil.CODEC, display.getUUID());
        return entry;
    }

    /** Places one sac display on the spider's back. */
    private static void positionSac(AbilityContext ctx, Entity sac) {
        Mob mob = ctx.mob();
        sac.snapTo(mob.getX(), mob.getY() + BACK_HEIGHT, mob.getZ(), mob.getYRot(), 0.0f);
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        CompoundTag state = ctx.state();
        ListTag sacs = state.getListOrEmpty("sacs");

        // The sacs ride the spider's back.
        for (int i = 0; i < sacs.size(); i++) {
            Entity sac = resolveSac(level, sacs.getCompoundOrEmpty(i));
            if (sac != null) {
                positionSac(ctx, sac);
            }
        }

        // The rupture check: the pool ran dry under fire.
        if (ctx.ticksActive() % RUPTURE_CHECK_INTERVAL == 0
            && state.getIntOr("ruptured", 0) == 0
            && ctx.mob().getAbsorptionAmount() <= 0.0f) {
            rupture(ctx, level, sacs);
            state.putInt("ruptured", 1);
        }
    }

    /** The rupture: the sacs pop, the spray flies, attackers slow in the mess. */
    private static void rupture(AbilityContext ctx, ServerLevel level, ListTag sacs) {
        Mob mob = ctx.mob();
        for (int i = 0; i < sacs.size(); i++) {
            Entity sac = resolveSac(level, sacs.getCompoundOrEmpty(i));
            if (sac != null) {
                sac.discard();
            }
        }
        level.sendParticles(ParticleTypes.ITEM_COBWEB, mob.getX(), mob.getY() + 0.7, mob.getZ(),
            24, 0.7, 0.5, 0.7, 0.15);
        level.sendParticles(SILK_DUST, mob.getX(), mob.getY() + 0.7, mob.getZ(),
            12, 0.7, 0.5, 0.7, 0.05);
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
            SoundEvents.WOOL_BREAK, SoundSource.HOSTILE, 1.0f, 0.9f);
        List<LivingEntity> victims = SpiderAbilitySupport.enemiesAroundSelf(ctx, 3.5, 1.5, 2.0);
        for (LivingEntity victim : victims) {
            victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 1));
        }
    }

    /** Resolves a tracked sac display from its list entry, or null when gone. */
    private static Entity resolveSac(ServerLevel level, CompoundTag entry) {
        if (!entry.contains("id")) {
            return null;
        }
        UUID id = entry.read("id", UUIDUtil.CODEC).orElse(null);
        return id != null ? level.getEntity(id) : null;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS
            || ctx.state().getIntOr("ruptured", 0) == 1;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        AttributeInstance maxAbsorption = mob.getAttribute(Attributes.MAX_ABSORPTION);
        if (maxAbsorption != null) {
            maxAbsorption.removeModifier(ABSORPTION_ID);
        }
        // Whatever is left of the pool above the unmodified cap
        // goes with the sac.
        if (mob.getAbsorptionAmount() > mob.getMaxAbsorption()) {
            mob.setAbsorptionAmount(mob.getMaxAbsorption());
        }

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag sacs = ctx.state().getListOrEmpty("sacs");
        for (int i = 0; i < sacs.size(); i++) {
            Entity sac = resolveSac(level, sacs.getCompoundOrEmpty(i));
            if (sac != null) {
                sac.discard();
            }
        }
    }
}
