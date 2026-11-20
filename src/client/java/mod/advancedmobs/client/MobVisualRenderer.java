package mod.advancedmobs.client;

import com.mojang.blaze3d.systems.RenderSystem;
import mod.advancedmobs.config.ModConfig;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Handles rendering of visual features: target lines.
 * Updated for Minecraft 1.21.1 rendering API
 */
public class MobVisualRenderer implements WorldRenderEvents.Last {

    private static final ModConfig CONFIG = ModConfig.getInstance();

    @Override
    public void onLast(WorldRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.world == null) return;

        // Performance check: Skip if FPS is too low
        if (client.getCurrentFps() < CONFIG.minFpsForVisuals) return;

        PoseStack matrices = context.matrixStack();
        Camera camera = context.camera();
        Vec3 cameraPos = camera.position();

        // Get all entities in render distance
        Iterable<Entity> entities = client.world.getEntities();

        for (Entity entity : entities) {
            if (!(entity instanceof Mob mob)) continue;

            // Check distance for performance
            double distance = entity.position().distanceTo(cameraPos);
            if (distance > 64.0) continue; // Only render within 64 blocks

            renderForMob(matrices, context, camera, mob, distance);
        }
    }

    private void renderForMob(PoseStack matrices, WorldRenderContext context, Camera camera, Mob mob, double distance) {
        Vec3 mobPos = mob.position();
        Vec3 cameraPos = camera.position();

        // Target lines
        if (CONFIG.showTargetLines) {
            LivingEntity target = mob.getTarget();
            if (target != null) {
                drawTargetLine(matrices, context, camera, mobPos, target.position());
            }
        }

    }

    private void drawTargetLine(PoseStack matrices, WorldRenderContext context, Camera camera, Vec3 start, Vec3 end) {
        matrices.push();
        Vec3 cameraPos = camera.position();

        // Use LINES render layer (correct for 1.21.1)
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
        Matrix4f matrix = matrices.peek().getPositionMatrix();

        // Line from mob to target (red)
        buffer.vertex(matrix, (float)(start.x - cameraPos.x), (float)(start.y - cameraPos.y + 1.0), (float)(start.z - cameraPos.z))
                .color(255, 0, 0, 255);
        buffer.vertex(matrix, (float)(end.x - cameraPos.x), (float)(end.y - cameraPos.y + 1.0), (float)(end.z - cameraPos.z))
                .color(255, 0, 0, 255);

        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        BufferRenderer.drawWithGlobalProgram(buffer.end());
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();

        matrices.pop();
    }

}
