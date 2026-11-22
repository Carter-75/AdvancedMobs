package mod.advancedmobs.ability.vindicator;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
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
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.illager.AbstractIllager;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.phys.AABB;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Johnny Rampage (Vindicator ability 2 of 5, transformation).
 *
 * WHAT IT DOES: the nameplate changes to Johnny, and the
 * famous rule takes hold: everything that moves is a target.
 * The vindicator swells, gains STRENGTH, its nameplate reads
 * from clear across the arena, and it hacks indiscriminately
 * at the nearest living thing, friend or foe, its hits
 * sparking with enchanted glints. The rampage ends when the
 * field is empty, or after a full minute of carnage.
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the vindicator's current target within 16 blocks
 *   (distance squared 256).
 * - THE NAME IS THE MECHANISM: start() stores the
 *   vindicator's current custom name (text and visibility) in
 *   the state tag, then sets the custom name to the literal
 *   "Johnny" and forces it visible. Vindicator.setCustomName
 *   is overridden in vanilla (verified against the 26.2 jar
 *   bytecode) to flip the vindicator's private Johnny flag
 *   when the new name's text equals "Johnny", and the
 *   VindicatorJohnnyAttackGoal registered in its goal
 *   selector then does the indiscriminate targeting itself:
 *   the nearest attackable living thing, with vanilla's own
 *   exceptions (it spares other illagers and ghasts). The
 *   ability does NO retargeting of its own and deliberately
 *   does NOT apply this package's enemy rule to the
 *   vindicator's attacks: friend or foe is the design. (The
 *   enemy rule still governs every code-driven area effect in
 *   the package; this ability has none.)
 * - NAME_TAG_DISTANCE (the design's Uses list): a transient
 *   +448 add_value modifier takes the plate from the 64-block
 *   default to the 512 attribute cap, so the whole arena can
 *   read JOHNNY. Applied in start(), removed in end(). If a
 *   given vindicator somehow lacks the attribute instance the
 *   helper tolerates it and the plate just stays at the
 *   default range.
 * - SCALE: +0.35 add_multiplied_base (1.35x size), applied in
 *   start(), removed in end().
 * - STRENGTH I: applied in start() and refreshed every 20
 *   ticks at 30 ticks duration, removed in end().
 * - ENCHANTED_HIT: the caster's landed melee hits are polled
 *   RAW (the last-hurt-mob timestamp, primed in start()): any
 *   landed hit, on any victim at all, enemy, ally, or
 *   bystander, bursts 6 ENCHANTED_HIT particles at the
 *   victim. The raw poll is deliberate: Johnny's victims are
 *   by definition not filtered by the enemy rule.
 * - The end of the rampage: every 20 ticks, if the
 *   vindicator has no current target AND a bounded scan
 *   (radius 14, 4 below to 4 above) finds no living Mob other
 *   than the caster, illagers, and ghasts (vanilla Johnny's
 *   own exceptions, approximately: the goal's exact predicate
 *   is vanilla's business), the rampage is over: everything
 *   nearby is dead, as designed. Hard cap 1200 ticks (60
 *   seconds) regardless.
 * - Sounds: a VINDICATOR_CELEBRATE roar (volume 1.2, pitch
 *   0.8) when the name lands, and a low VINDICATOR_AMBIENT
 *   growl (volume 1.0, pitch 0.7) every 200 ticks while the
 *   rampage runs.
 *
 * WHAT IT LOOKS LIKE: a bigger vindicator under a huge,
 * far-readable JOHNNY nameplate, STRENGTH shimmer on its
 * frame, enchanted glints bursting off every victim it
 * touches, chasing whatever is nearest with no regard for
 * sides.
 *
 * THE JOHNNY FLAG PROBLEM (read this before touching end()):
 * vanilla's setCustomName only ever SETS the Johnny flag; it
 * never clears it (verified in the jar bytecode: the flag is
 * set to true when the name becomes "Johnny" and no path
 * sets it back, and the flag is persisted in the entity's
 * save data). Restoring the old name in end() is therefore
 * NOT enough: without further action the vindicator would
 * stay a Johnny for the rest of its life, attacking its own
 * patrol forever after one activation. end() therefore also
 * clears the private field reflectively (the 26.x jar is
 * unobfuscated, so the field's real name, "isJohnny", is
 * stable). The reflective clear is wrapped so any failure is
 * swallowed: the documented failure mode, if the field ever
 * cannot be reached, is that the vindicator keeps vanilla
 * Johnny behavior permanently, exactly as a name-tagged
 * Johnny would. That failure is stated here, not hidden.
 *
 * TERRAIN: this ability places and breaks no blocks in
 * either mobGriefing mode, so the terrain gate does not
 * apply.
 *
 * PERFORMANCE: per tick the cost is one integer timestamp
 * compare (the hit poll); the STRENGTH refresh and the
 * field-empty scan run every 20 ticks (one bounded query).
 * Particle budget: 6 per landed hit; a vindicator in constant
 * contact lands at most about one hit per 10 ticks, so at
 * most about 720 over the full 60-second cap, stated here so
 * a future pass can trim it if it must.
 *
 * CLEANUP: end() removes the SCALE and NAME_TAG_DISTANCE
 * modifiers and the STRENGTH, restores the stored name and
 * name visibility (or clears the name entirely when the
 * vindicator had none), and clears the Johnny flag as
 * described above. If the vindicator dies mid-rampage the
 * framework termination path runs this same end() on the
 * corpse, which is harmless: a dead Johnny is just dead.
 *
 * State tag keys: "hit_ts" (the primed hit clock timestamp),
 * "had_name" (1 when the vindicator had a custom name),
 * "orig_name" (its text), "orig_visible" (1 when the plate
 * was visible), "quiet" (1 once the field is empty).
 */
public final class JohnnyRampageAbility implements MobAbility {

    static final int MAX_TICKS = 1200;
    private static final Identifier SCALE_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "johnny_rampage_scale");
    private static final Identifier NAME_TAG_ID =
        Identifier.fromNamespaceAndPath("advancedmobs", "johnny_rampage_name_tag");

    @Override
    public String id() {
        return "johnny_rampage";
    }

    @Override
    public String displayName() {
        return "Johnny Rampage";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 256.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob mob = ctx.mob();
        VindicatorAbilitySupport.primeHitClock(ctx);
        ctx.state().putInt("quiet", 0);

        // Remember the name Johnny is about to overwrite.
        Component original = mob.getCustomName();
        ctx.state().putInt("had_name", original != null ? 1 : 0);
        ctx.state().putString("orig_name", original != null ? original.getString() : "");
        ctx.state().putInt("orig_visible", mob.isCustomNameVisible() ? 1 : 0);

        // The name IS the trigger: Vindicator.setCustomName
        // flips the vanilla Johnny flag (see the spec comment).
        mob.setCustomName(Component.literal("Johnny"));
        mob.setCustomNameVisible(true);

        VindicatorAbilitySupport.addModifier(mob, Attributes.SCALE,
            new AttributeModifier(SCALE_ID, 0.35, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        VindicatorAbilitySupport.addModifier(mob, Attributes.NAME_TAG_DISTANCE,
            new AttributeModifier(NAME_TAG_ID, 448.0, AttributeModifier.Operation.ADD_VALUE));
        mob.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 30, 0));

        ServerLevel level = ctx.serverLevel();
        if (level != null) {
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.VINDICATOR_CELEBRATE,
                SoundSource.HOSTILE, 1.2f, 0.8f);
        }
    }

    @Override
    public void tick(AbilityContext ctx) {
        Mob mob = ctx.mob();
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }

        // Raw hit poll: any landed hit sparkles, friend or foe.
        int timestamp = mob.getLastHurtMobTimestamp();
        if (timestamp != ctx.state().getIntOr("hit_ts", -1)) {
            ctx.state().putInt("hit_ts", timestamp);
            LivingEntity victim = mob.getLastHurtMob();
            if (victim != null && victim.isAlive()) {
                level.sendParticles(ParticleTypes.ENCHANTED_HIT, victim.getX(),
                    victim.getY() + victim.getBbHeight() * 0.6, victim.getZ(),
                    6, 0.35, 0.4, 0.35, 0.1);
            }
        }

        int ticks = ctx.ticksActive();
        if (ticks % 200 == 0) {
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.VINDICATOR_AMBIENT,
                SoundSource.HOSTILE, 1.0f, 0.7f);
        }
        if (ticks % 20 != 0) {
            return; // Cheap early-out: upkeep runs on the 20-tick marks only.
        }
        mob.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 30, 0));

        // Is anything left to kill?
        if (ctx.target() == null) {
            AABB box = new AABB(mob.getX() - 14.0, mob.getY() - 4.0, mob.getZ() - 14.0,
                                mob.getX() + 14.0, mob.getY() + 4.0, mob.getZ() + 14.0);
            List<Mob> nearby = level.getEntitiesOfClass(Mob.class, box,
                e -> e != mob && e.isAlive() && !(e instanceof AbstractIllager) && !(e instanceof Ghast));
            if (nearby.isEmpty()) {
                ctx.state().putInt("quiet", 1);
            }
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.state().getIntOr("quiet", 0) == 1 || ctx.ticksActive() >= MAX_TICKS;
    }

    @Override
    public void end(AbilityContext ctx) {
        Mob mob = ctx.mob();
        VindicatorAbilitySupport.removeModifier(mob, Attributes.SCALE, SCALE_ID);
        VindicatorAbilitySupport.removeModifier(mob, Attributes.NAME_TAG_DISTANCE, NAME_TAG_ID);
        mob.removeEffect(MobEffects.STRENGTH);

        // Restore the name Johnny overwrote (or clear it).
        if (ctx.state().getIntOr("had_name", 0) == 1) {
            mob.setCustomName(Component.literal(ctx.state().getStringOr("orig_name", "")));
        } else {
            mob.setCustomName(null);
        }
        mob.setCustomNameVisible(ctx.state().getIntOr("orig_visible", 0) == 1);

        clearJohnnyFlag(mob);
    }

    /**
     * Clears the vindicator's private vanilla Johnny flag,
     * which vanilla itself never clears once set (see the
     * class comment for the full story). Any reflection
     * failure is swallowed on purpose: the documented failure
     * mode is a permanently Johnny vindicator, the same end
     * state as a name-tagged Johnny.
     */
    private static void clearJohnnyFlag(Mob mob) {
        if (!(mob instanceof Vindicator)) {
            return;
        }
        try {
            Field field = Vindicator.class.getDeclaredField("isJohnny");
            field.setAccessible(true);
            field.setBoolean(mob, false);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Documented failure mode; see the class comment.
        }
    }
}
