package mod.advancedmobs.client;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.List;

/**
 * Spectator overlay: vanilla middle-click spectating puts your camera
 * inside a mob but shows none of its war-system state. While the camera
 * entity is a mob, this HUD element draws the mob's inspect snapshot
 * docked to the RIGHT edge of the screen (same side as the right-click
 * inspect panel). Snapshots are requested once per second by the client
 * tick handler in AdvancedMobsModClient. The mouse wheel scrolls this
 * panel while spectating (see SpectateScrollMixin, which hands scroll
 * events to handleScroll and cancels the spectator fly-speed change).
 */
public class UmwSpectateHud implements HudElement {

    private static final int PANEL_WIDTH = 250;
    private static final int PANEL_MARGIN = 8;
    private static final int LINE_HEIGHT = 11;

    private static int scrollOffset = 0;
    private static int lastMobId = -1;

    /**
     * Called from the mouse-scroll mixin. Returns true when the scroll
     * was consumed by this panel (spectating a mob, no screen open), so
     * the caller can cancel the vanilla spectator fly-speed scroll.
     */
    public static boolean handleScroll(double yOffset) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.gui.screen() != null) {
            return false;
        }
        if (UmwClientState.spectatedMobId() < 0) {
            return false;
        }
        scrollOffset = Math.max(0, scrollOffset - (int) Math.signum(yOffset) * 3);
        return true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        int mobId = UmwClientState.spectatedMobId();
        if (mobId < 0) {
            scrollOffset = 0;
            lastMobId = -1;
            return;
        }
        if (mobId != lastMobId) {
            lastMobId = mobId;
            scrollOffset = 0;
        }

        int panelRight = graphics.guiWidth() - PANEL_MARGIN;
        int panelLeft = panelRight - PANEL_WIDTH;
        int panelTop = PANEL_MARGIN;

        CompoundTag snapshot = UmwClientState.INSPECT_SNAPSHOTS.get(mobId);
        if (snapshot == null) {
            graphics.text(client.font, "Spectating: loading mob data...", panelLeft + 7, panelTop + 5, 0xFFCCCCCC);
            return;
        }

        String name = snapshot.getStringOr("name", "Mob");
        float health = snapshot.getFloatOr("health", 0f);
        float maxHealth = Math.max(1f, snapshot.getFloatOr("maxHealth", 1f));

        List<String> allLines = new ArrayList<>();
        allLines.add(name);
        allLines.add("HP " + (int) Math.ceil(health) + "/" + (int) Math.ceil(maxHealth)
            + "  Armor " + snapshot.getIntOr("armor", 0));
        for (String[] section : MobInspectScreen.flattenSections(snapshot)) {
            for (String line : section) {
                allLines.add(line);
            }
        }

        int visibleRows = Math.max(4, (graphics.guiHeight() - panelTop * 2 - 10) / LINE_HEIGHT);
        int maxScroll = Math.max(0, allLines.size() - visibleRows);
        if (scrollOffset > maxScroll) {
            scrollOffset = maxScroll;
        }
        List<String> visible = allLines.subList(scrollOffset, Math.min(allLines.size(), scrollOffset + visibleRows));

        int panelHeight = visible.size() * LINE_HEIGHT + 10;
        int panelBottom = panelTop + panelHeight;
        graphics.fill(panelLeft, panelTop, panelRight, panelBottom, 0xC0101018);
        graphics.fill(panelRight - 2, panelTop, panelRight, panelBottom, 0xFFB02A30);
        for (int i = 0; i < visible.size(); i++) {
            String line = visible.get(i);
            int color = i + scrollOffset == 0 ? 0xFFFFFFFF : line.startsWith("--") ? 0xFFFFC857 : 0xFFD8D8D8;
            graphics.text(client.font, line, panelLeft + 7, panelTop + 5 + i * LINE_HEIGHT, color);
        }
        if (maxScroll > 0) {
            graphics.text(client.font, "scroll " + scrollOffset + "/" + maxScroll,
                panelLeft + 7, panelBottom - 1, 0xFF888888);
        }
    }
}
