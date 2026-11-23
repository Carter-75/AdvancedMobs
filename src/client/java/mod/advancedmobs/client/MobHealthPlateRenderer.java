package mod.advancedmobs.client;

import com.mojang.blaze3d.vertex.PoseStack;
import mod.advancedmobs.config.ModConfig;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * Health plates above mobs: name + level on one line, a 10-segment HP
 * bar with numeric health on the line below it, for every living mob
 * within ModConfig.healthPlateRange blocks (default 10) of the camera.
 *
 * Health comes from vanilla's client-side entity sync; level and XP come
 * from the server's periodic plate pushes (UmwClientState.PLATES). Text
 * is submitted through SubmitNodeCollector.submitNameTag exactly the way
 * vanilla submits entity name tags, so it billboards and depth-tests
 * like a name tag.
 */
public class MobHealthPlateRenderer implements LevelRenderEvents.CollectSubmits {

    private static final int FULL_BRIGHT = 15728880;
    private static final int BAR_SEGMENTS = 10;

    @Override
    public void collectSubmits(LevelRenderContext context) {
        ModConfig config = ModConfig.getInstance();
        if (!config.showHealthPlates) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }
        if (client.getFps() < config.minFpsForVisuals) {
            return;
        }

        CameraRenderState cameraState = context.levelState().cameraRenderState;
        Vec3 cameraPos = cameraState.pos;
        double range = Math.max(4, config.healthPlateRange);
        Entity cameraEntity = client.getCameraEntity();

        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof Mob mob) || !mob.isAlive()) {
                continue;
            }
            if (mob == cameraEntity) {
                continue; // Never plate the mob you are spectating from inside.
            }
            Vec3 mobPos = mob.position();
            if (mobPos.distanceToSqr(cameraPos) > range * range) {
                continue;
            }
            submitPlate(context, cameraState, cameraPos, mob);
        }
    }

    private void submitPlate(LevelRenderContext context, CameraRenderState cameraState, Vec3 cameraPos, Mob mob) {
        UmwClientState.PlateInfo plate = UmwClientState.PLATES.get(mob.getId());

        MutableComponent nameLine = Component.literal(mob.getName().getString());
        if (plate != null) {
            nameLine.append(Component.literal("  Lv " + plate.level()).withStyle(ChatFormatting.GOLD));
        }

        float maxHealth = Math.max(1.0f, mob.getMaxHealth());
        float health = Math.max(0.0f, mob.getHealth());
        float fraction = Math.min(1.0f, health / maxHealth);
        int filled = (int) Math.round(fraction * BAR_SEGMENTS);
        ChatFormatting barColor = fraction > 0.5f ? ChatFormatting.GREEN
            : fraction > 0.25f ? ChatFormatting.YELLOW : ChatFormatting.RED;
        MutableComponent barLine = Component.empty();
        barLine.append(Component.literal("|".repeat(filled)).withStyle(barColor));
        barLine.append(Component.literal("|".repeat(BAR_SEGMENTS - filled)).withStyle(ChatFormatting.DARK_GRAY));
        barLine.append(Component.literal(" " + (int) Math.ceil(health) + "/" + (int) Math.ceil(maxHealth))
            .withStyle(ChatFormatting.WHITE));

        double height = mob.getBbHeight() + 0.55;
        PoseStack pose = context.poseStack();
        pose.pushPose();
        pose.translate(
            mob.getX() - cameraPos.x,
            mob.getY() - cameraPos.y + height,
            mob.getZ() - cameraPos.z);
        // Name on top, HP bar below it (name tags grow upward, so the
        // bar is submitted with the smaller vertical offset).
        context.submitNodeCollector().submitNameTag(pose, new Vec3(0, 0.28, 0), 0, nameLine, true, FULL_BRIGHT, cameraState);
        context.submitNodeCollector().submitNameTag(pose, Vec3.ZERO, 0, barLine, true, FULL_BRIGHT, cameraState);
        pose.popPose();
    }
}
