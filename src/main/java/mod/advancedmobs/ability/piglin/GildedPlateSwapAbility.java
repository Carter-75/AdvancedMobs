package mod.advancedmobs.ability.piglin;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Gilded Plate Swap (Piglin ability 4 of 5, defense).
 *
 * WHAT IT DOES: mid-fight, the piglin slaps on a full set of
 * gleaming golden armor in a flash of ENCHANT particles. The
 * armor is real equipment in its real armor slots, and the
 * protection is the equipment's own: a golden set is worth 11
 * ARMOR (helmet 2, chestplate 5, leggings 3, boots 1) with no
 * attribute modifiers involved. The gold can be broken piece
 * by piece like any armor, and each broken piece bursts into
 * gold nugget ITEM particles with a sad little squeal. While
 * any of the gold lasts, the piglin's crowd treats it as the
 * richest mob in the room and clusters in close around it.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the piglin's current target within 12 blocks (distance
 *   squared 144).
 * - Duration: at most 600 ticks (30 seconds), ending early on
 *   the first poll after all 4 golden pieces are gone.
 * - The swap (start()): for each armor slot (head, chest,
 *   legs, feet) the piglin's current stack is stored in the
 *   state tag through the support class's codec storage (an
 *   absent key means the slot was empty), then a fresh,
 *   undamaged golden piece goes into the slot. 20 ENCHANT
 *   particles flash around the body and a PIGLIN_CELEBRATE
 *   plays. Drop chances are deliberately NOT touched: if the
 *   piglin dies mid-ability, the framework termination path
 *   runs end() at the head of die(), before death loot is
 *   generated, so the restored original gear is what can drop
 *   and the golden set never leaks into loot.
 * - Breakage poll every 5 ticks: for each slot still flagged
 *   as wearing our gold, if the slot no longer holds that
 *   golden piece the piece is gone. If the slot is EMPTY the
 *   piece broke: 6 ITEM particles of gold nugget burst at the
 *   piglin's body and a PIGLIN_JEALOUS squeal plays at pitch
 *   1.5, the sad little squeal. If the slot holds some other
 *   item, the piglin traded the piece away mid-fight (piglin
 *   gear pickup); no burst, and the piece simply stops being
 *   tracked. The surviving count rides in the state tag as
 *   "gold_left".
 * - Clustering every 20 ticks, only while gold_left is above
 *   0: one bounded query finds cluster-ally piglins (the
 *   support class's CLUSTER ALLY RULE) within 12 blocks of
 *   the caster, nearest first, at most 6; each one farther
 *   than 3.5 blocks gets a navigation order to the caster's
 *   position at speed 1.0, so the crowd closes in and mills
 *   around the golden piglin as a living shield wall.
 *   Navigation steering of brain mobs is best-effort between
 *   pulses, re-issued every pulse, the Gold Rush Frenzy
 *   precedent.
 * - end(): for each armor slot: if the slot still holds our
 *   golden piece, it is removed; if the slot is empty, nothing
 *   is removed; then the stored original stack (when one was
 *   stored) goes back into the slot. One exception, stated
 *   plainly: if the slot holds a FOREIGN item (the piglin
 *   picked up other gear over the gold), the foreign item
 *   stays and the stored original is discarded with the state
 *   tag: the piglin traded up mid-ability and keeps its find.
 *   A 6-mote gold ITEM burst (gold nugget) marks the swap
 *   back. end() is idempotent and runs identically on the
 *   death termination path.
 *
 * WHAT IT LOOKS LIKE: a flash of ENCHANT runes and the piglin
 * is suddenly head-to-toe gold, visibly brighter than anything
 * else in the fight, its crowd drifting in to surround it.
 * Every piece that shatters pops in a spray of gold nuggets
 * with a squeal, until the last piece goes and the crowd
 * loosens back into the fight.
 *
 * PERFORMANCE: tick() returns immediately except on the 5-tick
 * poll (4 slot reads and item checks) and the 20-tick cluster
 * pulse (one bounded Piglin query plus a small sort, at most
 * 6 navigation writes). The codec storage runs only in start()
 * and end(), never per tick. Particle budget per activation:
 * 20 at start, at most 4 breaks times 6 = 24, 6 at end, total
 * at most 50 particles.
 *
 * State tag keys: "saved_head", "saved_chest", "saved_legs",
 * "saved_feet" (the stored original stacks, absent when the
 * slot was empty), "gold_head", "gold_chest", "gold_legs",
 * "gold_feet" (whether our golden piece is still tracked in
 * that slot), "gold_left" (surviving tracked pieces).
 */
public final class GildedPlateSwapAbility implements MobAbility {

    static final int MAX_TICKS = 600;
    private static final int POLL_INTERVAL = 5;
    private static final int CLUSTER_INTERVAL = 20;
    private static final int MAX_CLUSTER = 6;
    private static final ItemParticleOption NUGGET_PARTICLES =
        new ItemParticleOption(ParticleTypes.ITEM, Items.GOLD_NUGGET);

    /** One armor slot and the golden piece that fills it during the ability. */
    private record Plate(EquipmentSlot slot, Item golden, String key) {}

    private static final List<Plate> PLATES = List.of(
        new Plate(EquipmentSlot.HEAD, Items.GOLDEN_HELMET, "head"),
        new Plate(EquipmentSlot.CHEST, Items.GOLDEN_CHESTPLATE, "chest"),
        new Plate(EquipmentSlot.LEGS, Items.GOLDEN_LEGGINGS, "legs"),
        new Plate(EquipmentSlot.FEET, Items.GOLDEN_BOOTS, "feet")
    );

    @Override
    public String id() {
        return "gilded_plate_swap";
    }

    @Override
    public String displayName() {
        return "Gilded Plate Swap";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 144.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        ctx.state().putInt("gold_left", PLATES.size());
        for (Plate plate : PLATES) {
            PiglinAbilitySupport.storeStack(ctx.state(), "saved_" + plate.key(),
                mob.getItemBySlot(plate.slot()), ctx.level().registryAccess());
            ctx.state().putBoolean("gold_" + plate.key(), true);
            mob.setItemSlot(plate.slot(), new ItemStack(plate.golden()));
        }
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.sendParticles(ParticleTypes.ENCHANT, mob.getX(), mob.getY() + 1.2, mob.getZ(),
                20, 0.6, 0.8, 0.6, 0.6);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.PIGLIN_CELEBRATE,
                SoundSource.HOSTILE, 1.0f, 1.0f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        if (ticks % POLL_INTERVAL == 0) {
            pollBreakage(ctx);
        }
        if (ticks % CLUSTER_INTERVAL == 0
            && ctx.state().getIntOr("gold_left", 0) > 0) {
            clusterCrowd(ctx);
        }
    }

    /** The 5-tick breakage poll (see the class comment). */
    private void pollBreakage(AbilityContext ctx) {
        Mob mob = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        int left = 0;
        for (Plate plate : PLATES) {
            if (!ctx.state().getBooleanOr("gold_" + plate.key(), false)) {
                continue;
            }
            ItemStack current = mob.getItemBySlot(plate.slot());
            if (current.is(plate.golden())) {
                left++;
                continue;
            }
            // The piece is gone from its slot.
            ctx.state().putBoolean("gold_" + plate.key(), false);
            if (current.isEmpty() && level != null) {
                // It broke: nugget burst and the sad squeal.
                level.sendParticles(NUGGET_PARTICLES, mob.getX(), mob.getY() + 1.0, mob.getZ(),
                    6, 0.5, 0.6, 0.5, 0.12);
                level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.PIGLIN_JEALOUS,
                    SoundSource.HOSTILE, 0.8f, 1.5f);
            }
        }
        ctx.state().putInt("gold_left", left);
    }

    /** The 20-tick clustering pulse (see the class comment). */
    private void clusterCrowd(AbilityContext ctx) {
        Mob mob = ctx.mob();
        List<Piglin> crowd = PiglinAbilitySupport.clusterPiglinsAround(ctx,
            mob.getX(), mob.getY(), mob.getZ(), 12.0, 3.0, 3.0);
        crowd.sort(java.util.Comparator.comparingDouble(p -> p.distanceToSqr(mob)));
        int steered = 0;
        for (Piglin piglin : crowd) {
            if (steered >= MAX_CLUSTER) {
                break;
            }
            steered++;
            if (piglin.distanceToSqr(mob) > 3.5 * 3.5) {
                piglin.getNavigation().moveTo(mob.getX(), mob.getY(), mob.getZ(), 1.0);
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= MAX_TICKS
            || ctx.state().getIntOr("gold_left", PLATES.size()) <= 0;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        for (Plate plate : PLATES) {
            ItemStack current = mob.getItemBySlot(plate.slot());
            ItemStack saved = PiglinAbilitySupport.loadStack(ctx.state(), "saved_" + plate.key(),
                ctx.level().registryAccess());
            if (current.is(plate.golden())) {
                // Our gold comes off; the original goes back on.
                mob.setItemSlot(plate.slot(), saved);
            } else if (current.isEmpty()) {
                // The piece broke (or was never tracked): restore.
                mob.setItemSlot(plate.slot(), saved);
            }
            // Foreign item in the slot: the piglin keeps its find
            // and the stored original is discarded (class comment).
        }
        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.sendParticles(NUGGET_PARTICLES, mob.getX(), mob.getY() + 1.0, mob.getZ(),
                6, 0.5, 0.6, 0.5, 0.1);
        }
    }
}
