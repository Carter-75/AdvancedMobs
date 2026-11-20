package mod.advancedmobs.client;

import mod.advancedmobs.config.ModConfig;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * Handles rendering of visual features: target lines.
 *
 * 26.2 rendering note: WorldRenderEvents and immediate-mode Tessellator
 * drawing are gone. Lines are now submitted through
 * LevelRenderEvents.CollectSubmits with the vanilla LINES render type:
 * a red line from each mob (within 64 blocks) to its current target,
 * raised 1 block, exactly as the old renderer drew it.
 */
public class MobVisualRenderer implements LevelRenderEvents.CollectSubmits {

    private static final ModConfig CONFIG = ModConfig.getInstance();

    @Override
    public void collectSubmits(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;

        // Performance check: Skip if FPS is too low
        if (client.getFps() < CONFIG.minFpsForVisuals) return;

        if (!CONFIG.showTargetLines) return;

        Vec3 cameraPos = context.levelState().cameraRenderState.pos;

        // Get all entities in render distance
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof Mob mob)) continue;

            // Check distance for performance
            double distance = entity.position().distanceTo(cameraPos);
            if (distance > 64.0) continue; // Only render within 64 blocks

            LivingEntity target = mob.getTarget();
            if (target == null) continue;

            drawTargetLine(context, cameraPos, mob.position(), target.position());
        }
    }

    private void drawTargetLine(LevelRenderContext context, Vec3 cameraPos, Vec3 start, Vec3 end) {
        Vec3 direction = end.subtract(start).normalize();
        float nx = (float) direction.x;
        float ny = (float) direction.y;
        float nz = (float) direction.z;

        // Line from mob to target (red), camera-relative like the old code
        context.submitNodeCollector().submitCustomGeometry(context.poseStack(), RenderTypes.LINES, (pose, consumer) -> {
            consumer.addVertex(pose,
                    (float) (start.x - cameraPos.x),
                    (float) (start.y - cameraPos.y + 1.0),
                    (float) (start.z - cameraPos.z))
                .setColor(255, 0, 0, 255)
                .setNormal(pose, nx, ny, nz);
            consumer.addVertex(pose,
                    (float) (end.x - cameraPos.x),
                    (float) (end.y - cameraPos.y + 1.0),
                    (float) (end.z - cameraPos.z))
                .setColor(255, 0, 0, 255)
                .setNormal(pose, nx, ny, nz);
        });
    }
}
