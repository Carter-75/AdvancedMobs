package mod.advancedmobs.ability.evoker;

import mod.advancedmobs.ability.AbilityContext;
import mod.advancedmobs.ability.MobAbility;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * Fang Colosseum (Evoker ability 1 of 5, terrain, attack).
 *
 * WHAT IT DOES: the evoker raises both arms and the ground
 * itself becomes the arena. Four concentric rings of real
 * evoker fangs erupt in sequence from the outside inward
 * around the spot where he cast, each ring announced a
 * heartbeat earlier by a circle of dark dust settling on the
 * ground where the jaws will close. Anything fighting him
 * has to keep moving in rhythm, inward or outward, because
 * standing in a ring when it snaps means taking the bite.
 * The last and smallest ring closes at 2.5 blocks around his
 * own casting position, and he stands untouched at the eye
 * of it: a vanilla fang never bites its owner (verified
 * against the 26.2 jar, see EvokerAbilitySupport).
 *
 * MECHANICS (the spec; fix bugs against these numbers):
 * - Trigger (canTrigger): the framework's combat gate, plus
 *   the evoker's current target within 20 blocks (distance
 *   squared 400).
 * - The arena (start()): the center is the evoker's position
 *   at the moment of the cast, stored in the state tag. The
 *   rings stay centered there even if he walks away
 *   afterward; the design's "final ring closes around the
 *   evoker's own position" is his position when he raised
 *   his arms. EVOKER_PREPARE_ATTACK plays at the cast.
 * - The rings: radii 8.5, 6.5, 4.5, and 2.5 blocks, with 28,
 *   22, 16, and 10 fangs respectively (76 fangs total, spaced
 *   roughly 1.9 blocks apart around each circle). Ring spawn
 *   ticks: 20, 44, 68, and 92 (24 ticks apart, outside
 *   first). EVOKER_CAST_SPELL plays at each ring spawn.
 * - The telegraph: at 10 ticks and again at 5 ticks before
 *   each ring spawns, one DUST mote (dark violet 0x3D2B52,
 *   scale 1.2) is dropped at every fang position of that
 *   ring, at ground level plus 0.15: the circle of dust is
 *   the heartbeat warning the design describes.
 * - The fangs: each is a real vanilla EvokerFangs owned by
 *   the evoker, spawned with a warmup of 8 ticks, so its
 *   bite lands 16 ticks after the ring spawns. A fang's bite
 *   is entirely vanilla: 6.0 magic damage (indirectMagic,
 *   credited to the evoker) to every living entity in its
 *   bite box except the evoker and his allies. Each fang's Y
 *   is the ground of its own column, found by the shared
 *   scan from 3 above the center's block Y down 8; a column
 *   with no ground in range (a cliff edge, a pit) simply gets
 *   no fang, in both the telegraph and the ring itself, so
 *   the dust never promises a bite that cannot come.
 * - Duration: hard finish at tick 120, after the last ring
 *   has bitten (tick 92 spawn + 16 warmup = 108) with a
 *   short tail.
 * - end(): nothing to undo. The fangs are short-lived
 *   vanilla entities that expire on their own inside 22
 *   ticks of spawning; any still standing when the ability
 *   ends finish their bite and vanish normally.
 *
 * WHAT IT LOOKS LIKE: the evoker's arms go up with the
 * prepare-attack chant, a circle of dark violet dust sighs
 * onto the ground far out, and then ring after ring of pale
 * jaws bursts out of the earth closing inward with the
 * fangs' own snapping sounds, until the last ring snaps shut
 * in a tight circle around the caster and stops just short
 * of his own robes.
 *
 * PERFORMANCE: tick() returns immediately except on the 12
 * scheduled ticks (4 rings times a spawn and two telegraph
 * draws). A telegraph draw or ring spawn walks that ring's
 * fang positions (at most 28) with one bounded column scan
 * each (at most 12 block reads): no entity queries anywhere,
 * no per-tick work at all. Particle budget per activation:
 * telegraphs 76 fangs times 2 draws = 152 DUST, plus 6 POOF
 * at the cast, total at most 158 particles (the fangs' own
 * vanilla spike particles are the entities' own, not sent by
 * this ability). The dust option is a pre-built static
 * final.
 *
 * TERRAIN: the fangs erupt through the ground but change no
 * blocks; they are entities with a fixed short life. The
 * mobGriefing gate does not apply.
 *
 * State tag keys: "cx", "cy", "cz" (the arena center, the
 * evoker's position at cast), "rings_done" (how many rings
 * have spawned, 0 to 4).
 */
public final class FangColosseumAbility implements MobAbility {

    private static final double[] RING_RADII = {8.5, 6.5, 4.5, 2.5};
    private static final int[] RING_COUNTS = {28, 22, 16, 10};
    private static final int[] RING_TICKS = {20, 44, 68, 92};
    private static final int FANG_WARMUP_TICKS = 8;
    private static final int FINISH_TICK = 120;
    private static final DustParticleOptions TELEGRAPH_DUST = new DustParticleOptions(0x3D2B52, 1.2f);

    @Override
    public String id() {
        return "fang_colosseum";
    }

    @Override
    public String displayName() {
        return "Fang Colosseum";
    }

    @Override
    public boolean canTrigger(AbilityContext ctx) {
        LivingEntity target = ctx.target();
        return target != null && ctx.mob().distanceToSqr(target) <= 400.0;
    }

    @Override
    public void start(AbilityContext ctx) {
        Mob caster = ctx.mob();
        ctx.state().putDouble("cx", caster.getX());
        ctx.state().putDouble("cy", caster.getY());
        ctx.state().putDouble("cz", caster.getZ());
        ctx.state().putInt("rings_done", 0);

        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.EVOKER_PREPARE_ATTACK, SoundSource.HOSTILE, 1.2f, 1.0f);
        level.sendParticles(ParticleTypes.POOF, caster.getX(), caster.getY() + 1.2, caster.getZ(),
            6, 0.4, 0.5, 0.4, 0.05);
    }

    @Override
    public void tick(AbilityContext ctx) {
        int ticks = ctx.ticksActive();
        int ringsDone = ctx.state().getIntOr("rings_done", 0);
        if (ringsDone >= RING_TICKS.length) {
            return; // Cheap early-out: every ring has erupted.
        }
        int spawnTick = RING_TICKS[ringsDone];
        if (ticks == spawnTick - 10 || ticks == spawnTick - 5) {
            drawTelegraph(ctx, ringsDone);
        } else if (ticks == spawnTick) {
            spawnRing(ctx, ringsDone);
            ctx.state().putInt("rings_done", ringsDone + 1);
        }
    }

    /**
     * The fang positions of one ring: one {x, y, z} per fang
     * whose column has ground, shared by the telegraph and
     * the spawn so the dust always matches the bite.
     */
    private static double[][] ringPositions(AbilityContext ctx, int ring) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return new double[0][];
        }
        double cx = ctx.state().getDoubleOr("cx", ctx.mob().getX());
        double cy = ctx.state().getDoubleOr("cy", ctx.mob().getY());
        double cz = ctx.state().getDoubleOr("cz", ctx.mob().getZ());
        int count = RING_COUNTS[ring];
        double radius = RING_RADII[ring];
        int fromY = (int) Math.floor(cy) + 3;
        double[][] positions = new double[count][];
        int found = 0;
        for (int i = 0; i < count; i++) {
            double angle = (Math.PI * 2.0 * i) / count;
            double x = cx + Math.cos(angle) * radius;
            double z = cz + Math.sin(angle) * radius;
            double groundY = EvokerAbilitySupport.findGroundY(level,
                (int) Math.floor(x), (int) Math.floor(z), fromY, 8);
            if (Double.isNaN(groundY)) {
                continue; // No ground in this column: no fang, no dust.
            }
            positions[found++] = new double[]{x, groundY, z};
        }
        double[][] trimmed = new double[found][];
        System.arraycopy(positions, 0, trimmed, 0, found);
        return trimmed;
    }

    /** The telegraph: one dust mote at each fang position of the ring. */
    private static void drawTelegraph(AbilityContext ctx, int ring) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        for (double[] pos : ringPositions(ctx, ring)) {
            level.sendParticles(TELEGRAPH_DUST, pos[0], pos[1] + 0.15, pos[2],
                1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /** The eruption: one real fang per position of the ring. */
    private static void spawnRing(AbilityContext ctx, int ring) {
        ServerLevel level = ctx.serverLevel();
        if (level == null) {
            return;
        }
        Mob caster = ctx.mob();
        level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
            SoundEvents.EVOKER_CAST_SPELL, SoundSource.HOSTILE, 1.0f, 0.9f);
        for (double[] pos : ringPositions(ctx, ring)) {
            EvokerAbilitySupport.spawnFang(ctx, pos[0], pos[1], pos[2], FANG_WARMUP_TICKS);
        }
    }

    @Override
    public boolean isFinished(AbilityContext ctx) {
        return ctx.ticksActive() >= FINISH_TICK;
    }

    @Override
    public void end(AbilityContext ctx) {
        // Nothing to undo: the fangs are short-lived vanilla
        // entities and expire on their own (see the class
        // comment).
    }
}
