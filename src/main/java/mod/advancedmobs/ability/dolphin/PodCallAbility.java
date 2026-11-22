package mod.advancedmobs.ability.dolphin;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import mod.advancedmobs.data.MobWarData;
import mod.advancedmobs.util.SummonerTracker;
import net.minecraft.core.UUIDUtil;
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
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.dolphin.Dolphin;

import java.util.UUID;

/**
 * Pod Call (Dolphin ability 5 of 5, summon).
 *
 * WHAT IT DOES: the dolphin whistles in a spray of
 * BUBBLE_POP particles and two more dolphins porpoise in
 * from the deep to join it. The pod swims in formation
 * under DOLPHINS_GRACE and SPEED, and the three take turns
 * ramming the same target in a rolling cycle, each hit
 * knocking it off balance, so the target never gets a
 * steady moment while the pod is on it.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the dolphin's current target within 20 blocks (distance
 *   squared 400).
 * - The call (start()): DOLPHIN_AMBIENT_WATER at volume
 *   1.5, pitch 1.1 (the whistle) and 12 BUBBLE_POP
 *   particles at the caller.
 * - The pod (start()): 2 dolphins, spawned at horizontal
 *   offsets (2.5, 0) and (-2.5, 0) from the caller, created
 *   with EntitySpawnReason.MOB_SUMMONED and finalizeSpawn
 *   like every other summon in this mod. Each is a plain
 *   vanilla dolphin (base 10 health, 3 damage in 26.2,
 *   verified against the jar) plus a transient
 *   FOLLOW_RANGE modifier of +16 ADD_VALUE under a fixed id
 *   (the design's FOLLOW_RANGE line). Team assignment, per
 *   dolphin, both directions plus the registry: the
 *   caster's data gains the dolphin as an ally, the
 *   dolphin's data gains the caster, and the pair is
 *   registered in SummonerTracker (which also tags the
 *   dolphin umw_summoned for the natural spawn blocker).
 *   Each is ordered onto the caster's current target at
 *   spawn.
 * - Duration: 400 ticks (20 seconds), hard finish.
 * - The pod buff (tick(), every 20 ticks): the caster and
 *   every living pod dolphin gain DOLPHINS_GRACE I and
 *   SPEED I (amplifier 0), refreshed to 40 ticks (the
 *   design's two effect lines: grace is the swim speed in
 *   the water, SPEED rides alongside it), with a 6 mote
 *   BUBBLE_POP ring at the caster. The effects are ordinary
 *   instances on their own clocks; end() does not strip
 *   them, they expire naturally just after the ability
 *   ends.
 * - Babysitting (tick(), every 5 ticks): each living pod
 *   dolphin whose target is not the caster's current target
 *   is re-ordered onto it, keeping the pod on the caller's
 *   fight.
 * - The rolling ram cycle: every 30 ticks a 15 tick ram
 *   window opens for the next swimmer in rotation (the
 *   caster, then pod dolphin 1, then pod dolphin 2, then
 *   back to the caster; a swimmer that is dead when its
 *   turn comes is skipped). During its window the rammer
 *   is steered straight at the target's live chest at 1.3
 *   blocks per tick (code velocity, the package steering
 *   helper; the caster's own turns are steered the same
 *   way). The first tick the rammer comes within 1.8
 *   blocks of the target, its ram lands exactly once for
 *   the window: damage equal to the rammer's live
 *   ATTACK_DAMAGE as a mob attack credited to the rammer,
 *   SLOWNESS I for 40 ticks on the victim (the off-balance
 *   wobble the cycle keeps refreshing swimmer after
 *   swimmer), a sideways knock of 0.8 blocks per tick
 *   perpendicular to the ram line plus 0.3 up, hurtMarked,
 *   8 SPLASH and 4 DAMAGE_INDICATOR particles, and
 *   DOLPHIN_ATTACK at volume 1.0. A window that never
 *   connects simply runs out.
 * - end(): each surviving pod dolphin is discarded in 6
 *   BUBBLE_POP particles and the alliance records are
 *   removed on both sides for every tracked dolphin. If
 *   the caster dies first, the framework termination path
 *   runs this same end(), so the pod never outlives its
 *   caller. SummonerTracker records are deliberately not
 *   unregistered, matching the other summons in this mod:
 *   that registry cleans up on the dolphin's own removal.
 *
 * WHAT IT LOOKS LIKE: a whistle and a spray of popping
 * bubbles, two dolphins sliding in from the flanks, and
 * then a metronome of rams: one dolphin at a time streaks
 * in, connects with a splash, and peels off while the next
 * lines up.
 *
 * PERFORMANCE: start() does 2 spawns, once. tick() does at
 * most one steering write per tick (only during a ram
 * window); babysitting every 5 ticks resolves at most 2
 * UUIDs; the buff pass every 20 ticks touches at most 3
 * entities. No bounded area queries anywhere. Particle
 * budget per activation: 12 at start, at most 20 buff
 * rings times 6 = 120, at most 13 ram windows times 12 =
 * 156, end() puffs at most 2 times 6 = 12, total at most
 * 300 particles.
 *
 * TERRAIN: this ability changes no blocks; the gate does
 * not apply.
 *
 * State tag keys: "pod" (list of compounds, one per
 * summoned dolphin: "id", the dolphin's UUID), "turn"
 * (the rotation counter: how many ram windows have
 * opened), "rammer" (who holds the current window: 0 for
 * the caster, i + 1 for pod entry i, -1 for none),
 * "ram_left" (ticks left in the current window), "ram_hit"
 * (1 once the current window's ram has landed).
 */
public final class PodCallAbility implements MobAbility {

    private static final int DURATION_TICKS = 400;
    private static final int POD_COUNT = 2;
    private static final int BABYSIT_INTERVAL = 5;
    private static final int BUFF_INTERVAL = 20;
    private static final int WINDOW_INTERVAL = 30;
    private static final int WINDOW_TICKS = 15;
    private static final double RAM_SPEED = 1.3;
    private static final double RAM_RANGE = 1.8;
    private static final Identifier FOLLOW_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "pod_call_follow");
    private static final double[][] OFFSETS = {
        {2.5, 0.0}, {-2.5, 0.0}
    };

    @Override
    public String id() {
        return "pod_call";
    }

    @Override
    public String displayName() {
        return "Pod Call";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 400.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        Mob caster = ctx.mob();
        ListTag pod = new ListTag();
        ctx.state().put("pod", pod);
        ctx.state().putInt("turn", 0);
        ctx.state().putInt("rammer", -1);
        ctx.state().putInt("ram_left", 0);
        ctx.state().putInt("ram_hit", 0);
        if (level == null) {
            return;
        }

        // The whistle: a bright call and a spray of popping bubbles.
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.DOLPHIN_AMBIENT_WATER, SoundSource.HOSTILE, 1.5f, 1.1f);
        level.sendParticles(ParticleTypes.BUBBLE_POP, caster.getX(), caster.getY() + 0.5, caster.getZ(),
            12, 0.6, 0.4, 0.6, 0.05);

        LivingEntity target = ctx.target();
        for (int i = 0; i < POD_COUNT; i++) {
            Dolphin mate = EntityTypes.DOLPHIN.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (mate == null) {
                continue;
            }
            double[] offset = OFFSETS[i];
            mate.snapTo(caster.getX() + offset[0], caster.getY(), caster.getZ() + offset[1],
                ctx.random().nextFloat() * 360.0f, 0.0f);
            mate.finalizeSpawn(level, level.getCurrentDifficultyAt(mate.blockPosition()),
                EntitySpawnReason.MOB_SUMMONED, null);
            DolphinAbilitySupport.addModifier(mate, Attributes.FOLLOW_RANGE,
                new AttributeModifier(FOLLOW_ID, 16.0, AttributeModifier.Operation.ADD_VALUE));
            if (!level.addFreshEntity(mate)) {
                continue;
            }
            // Team assignment, both directions, plus the summoner registry.
            ctx.data().addAlly(mate.getUUID());
            MobWarData.get(mate).addAlly(caster.getUUID());
            SummonerTracker.registerSummoned(mate.getUUID(), caster.getUUID());
            if (target != null) {
                mate.setTarget(target);
            }
            DolphinAbilitySupport.trackEntity(ctx.state(), "pod", mate);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        int ticks = ctx.ticksActive();
        LivingEntity target = ctx.target();

        // Open the next ram window on the 30 tick beat.
        if (ticks % WINDOW_INTERVAL == 0) {
            openWindow(ctx, level);
        }
        // Steer the current rammer (one steering write per tick at most).
        if (ctx.state().getIntOr("ram_left", 0) > 0) {
            runWindow(ctx, level, target);
        }

        if (ticks % BABYSIT_INTERVAL == 0) {
            babysit(ctx, level, target);
        }
        if (ticks % BUFF_INTERVAL == 0) {
            buffPod(ctx, level);
        }
    }

    /** Opens a ram window for the next living swimmer in rotation. See the class comment. */
    private void openWindow(AbilityContext ctx, ServerLevel level) {
        int turn = ctx.state().getIntOr("turn", 0);
        ctx.state().putInt("turn", turn + 1);
        // Rotation slots: 0 the caster, 1 and 2 the pod entries.
        for (int step = 0; step <= POD_COUNT; step++) {
            int slot = (turn + step) % (POD_COUNT + 1);
            if (resolveSlot(ctx, level, slot) != null) {
                ctx.state().putInt("rammer", slot);
                ctx.state().putInt("ram_left", WINDOW_TICKS);
                ctx.state().putInt("ram_hit", 0);
                // Consume the skipped slots so the rotation
                // does not drift onto a dead swimmer's turn.
                ctx.state().putInt("turn", turn + step + 1);
                return;
            }
        }
        ctx.state().putInt("rammer", -1);
        ctx.state().putInt("ram_left", 0);
    }

    /** One tick of the current ram window: steer the rammer, land its ram once. */
    private void runWindow(AbilityContext ctx, ServerLevel level, LivingEntity target) {
        ctx.state().putInt("ram_left", ctx.state().getIntOr("ram_left", 0) - 1);
        if (target == null) {
            return;
        }
        LivingEntity rammer = resolveSlot(ctx, level, ctx.state().getIntOr("rammer", -1));
        if (rammer == null) {
            ctx.state().putInt("ram_left", 0);
            return;
        }
        double dist = DolphinAbilitySupport.steerToward(rammer,
            target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(), RAM_SPEED);
        if (dist <= RAM_RANGE && ctx.state().getIntOr("ram_hit", 0) == 0) {
            ctx.state().putInt("ram_hit", 1);
            ramStrike(ctx, level, rammer, target);
        }
    }

    /** The window's ram lands: damage, the off-balance wobble, and the sideways knock. */
    private void ramStrike(AbilityContext ctx, ServerLevel level, LivingEntity rammer, LivingEntity target) {
        Mob attacker = rammer instanceof Mob mob ? mob : ctx.mob();
        double damage = rammer.getAttributeValue(Attributes.ATTACK_DAMAGE);
        target.hurtServer(level, level.damageSources().mobAttack(attacker), (float) damage);
        target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 0));

        // Sideways, perpendicular to the ram line.
        double px = -(target.getZ() - rammer.getZ());
        double pz = target.getX() - rammer.getX();
        double len = Math.sqrt(px * px + pz * pz);
        if (len < 0.001) {
            px = 1.0;
            len = 1.0;
        }
        target.setDeltaMovement(px / len * 0.8, 0.3, pz / len * 0.8);
        target.hurtMarked = true;

        level.sendParticles(ParticleTypes.SPLASH, target.getX(), target.getY() + 0.5, target.getZ(),
            8, 0.35, 0.35, 0.35, 0.1);
        level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, target.getX(), target.getY() + 0.6, target.getZ(),
            4, 0.25, 0.25, 0.25, 0.1);
        level.playSound(null, target.getX(), target.getY(), target.getZ(),
            SoundEvents.DOLPHIN_ATTACK, SoundSource.HOSTILE, 1.0f, 1.0f);
    }

    /** Re-orders pod dolphins onto the caster's current target. Every 5 ticks. */
    private void babysit(AbilityContext ctx, ServerLevel level, LivingEntity target) {
        ListTag pod = ctx.state().getListOrEmpty("pod");
        for (int i = 0; i < pod.size(); i++) {
            Entity found = DolphinAbilitySupport.resolveTracked(level, pod.getCompoundOrEmpty(i));
            if (found instanceof Dolphin mate && mate.isAlive()
                && target != null && mate.getTarget() != target) {
                mate.setTarget(target);
            }
        }
    }

    /** Refreshes the pod's grace and speed. Every 20 ticks. See the class comment. */
    private void buffPod(AbilityContext ctx, ServerLevel level) {
        Mob caster = ctx.mob();
        caster.addEffect(new MobEffectInstance(MobEffects.DOLPHINS_GRACE, 40, 0));
        caster.addEffect(new MobEffectInstance(MobEffects.SPEED, 40, 0));
        level.sendParticles(ParticleTypes.BUBBLE_POP, caster.getX(), caster.getY() + 0.5, caster.getZ(),
            6, 0.7, 0.4, 0.7, 0.05);
        ListTag pod = ctx.state().getListOrEmpty("pod");
        for (int i = 0; i < pod.size(); i++) {
            Entity found = DolphinAbilitySupport.resolveTracked(level, pod.getCompoundOrEmpty(i));
            if (found instanceof Dolphin mate && mate.isAlive()) {
                mate.addEffect(new MobEffectInstance(MobEffects.DOLPHINS_GRACE, 40, 0));
                mate.addEffect(new MobEffectInstance(MobEffects.SPEED, 40, 0));
            }
        }
    }

    /** Resolves a rotation slot: 0 the caster, i + 1 pod entry i. Null when that swimmer is gone. */
    private LivingEntity resolveSlot(AbilityContext ctx, ServerLevel level, int slot) {
        if (slot == 0) {
            return ctx.mob();
        }
        if (slot < 0) {
            return null;
        }
        ListTag pod = ctx.state().getListOrEmpty("pod");
        if (slot - 1 >= pod.size()) {
            return null;
        }
        Entity found = DolphinAbilitySupport.resolveTracked(level, pod.getCompoundOrEmpty(slot - 1));
        return found instanceof Dolphin mate && mate.isAlive() ? mate : null;
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= DURATION_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        ListTag pod = ctx.state().getListOrEmpty("pod");
        for (int i = 0; i < pod.size(); i++) {
            CompoundTag entry = pod.getCompoundOrEmpty(i);
            UUID id = entry.contains("id") ? entry.read("id", UUIDUtil.CODEC).orElse(null) : null;
            if (id == null) {
                continue;
            }
            // The alliance ends with the ability, whichever side is still here.
            ctx.data().removeAlly(id);
            Entity found = level.getEntity(id);
            if (found instanceof Dolphin mate) {
                MobWarData.get(mate).removeAlly(caster.getUUID());
                if (mate.isAlive()) {
                    level.sendParticles(ParticleTypes.BUBBLE_POP, mate.getX(), mate.getY() + 0.4, mate.getZ(),
                        6, 0.3, 0.3, 0.3, 0.05);
                    mate.discard();
                }
            }
        }
    }
}
