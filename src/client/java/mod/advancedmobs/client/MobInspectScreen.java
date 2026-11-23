package mod.advancedmobs.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Right-click inspect panel: a fixed panel docked to the right edge of
 * the screen showing everything the server knows about one mob
 * (progression, upgrades, abilities, effects, equipment and enchants,
 * target, allies and warlord status - built by MobInspectService).
 *
 * The world keeps running (isPauseScreen is false), the mouse is free
 * while the panel is open, it closes with the X button in its corner or
 * with Escape, and it re-requests a fresh snapshot once per second so
 * health/target/cooldowns stay live. Scroll with the mouse wheel.
 */
public class MobInspectScreen extends Screen {

    private static final int PANEL_WIDTH = 264;
    private static final int PANEL_MARGIN = 8;
    private static final int LINE_HEIGHT = 11;
    private static final int REFRESH_TICKS = 20;

    private final int entityId;
    private int refreshCounter = 0;
    private int scrollOffset = 0;

    public MobInspectScreen(int entityId) {
        super(Component.literal("Mob Inspect"));
        this.entityId = entityId;
    }

    @Override
    protected void init() {
        int panelLeft = panelLeft();
        addRenderableWidget(Button.builder(Component.literal("X"), button -> onClose())
            .bounds(this.width - PANEL_MARGIN - 20, PANEL_MARGIN + 4, 18, 18)
            .build());
        UmwClientState.requestInspect(entityId);
        refreshCounter = 0;
    }

    @Override
    public void tick() {
        if (++refreshCounter >= REFRESH_TICKS) {
            refreshCounter = 0;
            UmwClientState.requestInspect(entityId);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scrollOffset = Math.max(0, scrollOffset - (int) Math.signum(scrollY) * 3);
        return true;
    }

    private int panelLeft() {
        return this.width - PANEL_WIDTH - PANEL_MARGIN;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int left = panelLeft();
        int top = PANEL_MARGIN;
        int bottom = this.height - PANEL_MARGIN;

        // Docked panel background with a subtle accent border.
        graphics.fill(left, top, left + PANEL_WIDTH, bottom, 0xE0101018);
        graphics.fill(left, top, left + 2, bottom, 0xFFB02A30);
        graphics.fill(left, bottom - 1, left + PANEL_WIDTH, bottom, 0xFF3A3A46);

        CompoundTag snapshot = UmwClientState.INSPECT_SNAPSHOTS.get(entityId);
        if (snapshot == null) {
            graphics.text(this.font, "Loading mob data...", left + 10, top + 12, 0xFFCCCCCC);
            super.extractRenderState(graphics, mouseX, mouseY, partialTick);
            return;
        }

        String name = snapshot.getStringOr("name", "Mob");
        float health = snapshot.getFloatOr("health", 0f);
        float maxHealth = Math.max(1f, snapshot.getFloatOr("maxHealth", 1f));
        graphics.text(this.font, name, left + 10, top + 8, 0xFFFFFFFF);
        graphics.text(this.font,
            "HP " + (int) Math.ceil(health) + "/" + (int) Math.ceil(maxHealth)
                + "  Armor " + snapshot.getIntOr("armor", 0),
            left + 10, top + 20, 0xFFFF8888);

        // HP bar under the header.
        int barLeft = left + 10;
        int barRight = left + PANEL_WIDTH - 10;
        int barTop = top + 33;
        graphics.fill(barLeft, barTop, barRight, barTop + 5, 0xFF3A0D0D);
        int filledWidth = (int) ((barRight - barLeft) * Math.min(1.0, health / maxHealth));
        graphics.fill(barLeft, barTop, barLeft + filledWidth, barTop + 5, 0xFFE03030);

        // Sections, scrolled.
        List<String[]> sections = flattenSections(snapshot);
        int contentTop = top + 46;
        int y = contentTop - scrollOffset * LINE_HEIGHT;
        int totalLines = 0;
        for (String[] section : sections) {
            // section[0] is the title, the rest are lines.
            totalLines += section.length;
            for (int i = 0; i < section.length; i++) {
                if (y >= contentTop && y + LINE_HEIGHT <= bottom - 6) {
                    boolean isTitle = i == 0;
                    graphics.text(this.font, section[i], left + 10, y,
                        isTitle ? 0xFFFFC857 : 0xFFD8D8D8);
                }
                y += LINE_HEIGHT;
            }
            y += 4;
            totalLines += 0;
        }
        int maxScroll = Math.max(0, totalLines - Math.max(1, (bottom - contentTop - 6) / LINE_HEIGHT));
        if (scrollOffset > maxScroll) {
            scrollOffset = maxScroll;
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    /** Flattens the snapshot's sections tag into [title, line, line, ...] arrays. */
    static List<String[]> flattenSections(CompoundTag snapshot) {
        List<String[]> sections = new ArrayList<>();
        ListTag sectionTags = snapshot.getListOrEmpty("sections");
        for (int i = 0; i < sectionTags.size(); i++) {
            CompoundTag section = sectionTags.getCompoundOrEmpty(i);
            ListTag lines = section.getListOrEmpty("lines");
            String[] flat = new String[lines.size() + 1];
            flat[0] = "-- " + section.getStringOr("title", "Info") + " --";
            for (int j = 0; j < lines.size(); j++) {
                flat[j + 1] = lines.getStringOr(j, "");
            }
            sections.add(flat);
        }
        return sections;
    }
}
