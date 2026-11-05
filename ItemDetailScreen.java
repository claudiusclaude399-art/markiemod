package dev.pvpmod.gui;

import dev.pvpmod.config.PvpConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Colour picker + preview (+ size slider and texture editor for normal items).
 * Armor mode edits the colour of a whole armor set at once.
 */
public class ItemDetailScreen extends PvpScreen {
    private static final int SQ_COLS = 12, SQ_ROWS = 7, SQ = 10, PAL = 10, BOX = 96;

    private record Swatch(int x, int y, int kind, int col, int row) {}

    private final List<String> ids;
    private final boolean armorMode;
    private final List<ItemStack> stacks = new ArrayList<>();
    private float hue = 0f;
    private final List<Swatch> swatches = new ArrayList<>();
    private int px, py, sx, sy, bx, by;

    public ItemDetailScreen(Screen parent, String itemId) {
        super(nameOf(itemId), parent);
        this.ids = List.of(itemId);
        this.armorMode = false;
        this.stacks.add(stackOf(itemId));
    }

    public ItemDetailScreen(Screen parent, String title, List<String> ids) {
        super(title + " armor colour", parent);
        this.ids = ids;
        this.armorMode = true;
        for (String i : ids) this.stacks.add(stackOf(i));
    }

    private static String nameOf(String id) {
        return Registries.ITEM.get(Identifier.of(id)).getName().getString();
    }

    private static ItemStack stackOf(String id) {
        return new ItemStack(Registries.ITEM.get(Identifier.of(id)));
    }

    private int colorOf(Swatch s) {
        if (s.kind() == 0) return ColorUtil.palette(s.col(), s.row());
        float sat = s.col() / (float) (SQ_COLS - 1);
        float val = 1f - s.row() * 0.85f / (SQ_ROWS - 1);
        return ColorUtil.hsv(hue, sat, val);
    }

    private void pick(Swatch s) {
        int rgb = colorOf(s);
        for (String id : ids) PvpConfig.get().itemColors.put(id, rgb);
        if (s.kind() == 0) {
            hue = ColorUtil.hueOf(rgb);
            clearAndInit();
        }
    }

    @Override
    protected void init() {
        PvpConfig c = PvpConfig.get();
        String id = ids.get(0);
        Integer existing = c.itemColors.get(id);
        if (existing != null && hue == 0f) hue = ColorUtil.hueOf(existing);

        swatches.clear();
        int cx = this.width / 2;
        px = cx - 215; py = 52;
        sx = cx - 80;  sy = 52;
        bx = cx + 60;  by = 52;

        for (int col = 0; col < ColorUtil.PAL_COLS; col++)
            for (int row = 0; row < ColorUtil.PAL_ROWS; row++)
                addSwatch(new Swatch(px + col * (PAL + 1), py + row * (PAL + 1), 0, col, row), PAL);

        for (int col = 0; col < SQ_COLS; col++)
            for (int row = 0; row < SQ_ROWS; row++)
                addSwatch(new Swatch(sx + col * (SQ + 1), sy + row * (SQ + 1), 1, col, row), SQ);

        int sqW = SQ_COLS * (SQ + 1) - 1;
        int y = sy + SQ_ROWS * (SQ + 1) + 6;
        addDrawableChild(new FloatSlider(sx, y, sqW, H, "Hue", 0f, 359f, hue, v -> hue = v));

        int y2 = y + GAP + 8;
        if (armorMode) {
            addDrawableChild(button(cx - 100, y2, 200, "Reset colour", () -> { for (String i : ids) c.itemColors.remove(i); }));
        } else {
            addDrawableChild(new FloatSlider(cx - 100, y2, 200, H, "Size", 0.25f, 2.5f,
                c.itemScales.getOrDefault(id, 1f), v -> c.itemScales.put(id, v)));
            addDrawableChild(button(cx - 100, y2 + GAP, 98, "Reset colour", () -> c.itemColors.remove(id)));
            addDrawableChild(button(cx + 2, y2 + GAP, 98, "Reset size", () -> { c.itemScales.remove(id); clearAndInit(); }));
            addDrawableChild(button(cx - 100, y2 + GAP * 2, 200, "Edit texture...",
                () -> this.client.setScreen(new TextureEditorScreen(this, id))));
        }
        addDone();
    }

    private void addSwatch(Swatch s, int size) {
        swatches.add(s);
        addDrawableChild(ButtonWidget.builder(Text.empty(), b -> pick(s))
            .dimensions(s.x(), s.y(), size, size).build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        PvpConfig c = PvpConfig.get();
        String id = ids.get(0);

        for (Swatch s : swatches) {
            int size = s.kind() == 0 ? PAL : SQ;
            ctx.fill(s.x(), s.y(), s.x() + size, s.y() + size, 0xFF000000 | colorOf(s));
        }

        ctx.drawTextWithShadow(this.textRenderer, Text.literal("Colour picker"), px, py - 12, 0xFFAAAAAA);
        ctx.drawTextWithShadow(this.textRenderer, Text.literal("Preview"), bx, by - 12, 0xFFAAAAAA);

        Integer col = c.itemColors.get(id);
        int backdrop = col == null ? 0xFF202020 : 0xFF000000 | mix(col, 0x202020);
        ctx.fill(bx - 3, by - 3, bx + BOX + 3, by + BOX + 3, 0xFFFFFFFF);
        ctx.fill(bx, by, bx + BOX, by + BOX, backdrop);

        var m = ctx.getMatrices();
        if (!armorMode) {
            float scale = 2.0f * c.itemScales.getOrDefault(id, 1f);
            m.pushMatrix();
            m.translate(bx + BOX / 2f, by + BOX / 2f);
            m.scale(scale, scale);
            ctx.drawItem(stacks.get(0), -8, -8);
            m.popMatrix();
        } else {
            float scale = stacks.size() == 1 ? 3f : 2f;
            int[][] offs = stacks.size() == 1 ? new int[][]{{0, 0}} : new int[][]{{-20, -20}, {20, -20}, {-20, 20}, {20, 20}};
            for (int i = 0; i < Math.min(stacks.size(), offs.length); i++) {
                m.pushMatrix();
                m.translate(bx + BOX / 2f + offs[i][0], by + BOX / 2f + offs[i][1]);
                m.scale(scale, scale);
                ctx.drawItem(stacks.get(i), -8, -8);
                m.popMatrix();
            }
        }

        String label = col == null ? "Colour: default" : String.format("Colour: #%06X", col & 0xFFFFFF);
        ctx.drawTextWithShadow(this.textRenderer, Text.literal(label), bx, by + BOX + 8, 0xFFFFFFFF);
        if (armorMode) {
            ctx.drawTextWithShadow(this.textRenderer, Text.literal("Shown on worn armor"), bx, by + BOX + 20, 0xFFAAAAAA);
        }
    }

    private static int mix(int a, int b) {
        int r = (((a >> 16) & 255) + ((b >> 16) & 255)) / 2;
        int g = (((a >> 8) & 255) + ((b >> 8) & 255)) / 2;
        int bl = ((a & 255) + (b & 255)) / 2;
        return (r << 16) | (g << 8) | bl;
    }
}
