package dev.pvpmod.gui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.resource.Resource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Pixel editor for one item texture, with animation frames.
 * "Apply" writes a resource pack folder (resourcepacks/PvPMod Textures) and reloads resources.
 */
public class TextureEditorScreen extends PvpScreen {
    private enum Tool { PENCIL, ERASER, FILL, PICKER }
    private record Snap(int frame, int[] px) {}

    private static final String PACK_META =
        "{\"pack\":{\"description\":\"PvP Mod item textures\",\"pack_format\":75,\"min_format\":75,\"max_format\":999}}";

    private final String path;
    private final ItemStack stack;
    private String folder = "item";
    private int size = 16;
    private final List<int[]> frames = new ArrayList<>();
    private final Deque<Snap> undo = new ArrayDeque<>();
    private int frame = 0, frametime = 4;
    private int r = 255, g = 0, b = 0;
    private boolean interpolate = false, loaded = false, armed = false, wasDown = false;
    private Tool tool = Tool.PENCIL;
    private String status = "";
    private int gx, gy, cell, px0;
    private final List<int[]> swatches = new ArrayList<>();

    public TextureEditorScreen(Screen parent, String itemId) {
        super("Texture: " + Registries.ITEM.get(Identifier.of(itemId)).getName().getString(), parent);
        this.path = Identifier.of(itemId).getPath();
        this.stack = new ItemStack(Registries.ITEM.get(Identifier.of(itemId)));
    }

    // ------------------------------------------------------------ files
    private static Path packRoot() {
        return FabricLoader.getInstance().getGameDir().resolve("resourcepacks").resolve("PvPMod Textures");
    }

    private Path texPath(String f) {
        return packRoot().resolve("assets/minecraft/textures/" + f + "/" + path + ".png");
    }

    private static Path metaOf(Path png) {
        return png.resolveSibling(png.getFileName() + ".mcmeta");
    }

    private void load() {
        loaded = true;
        BufferedImage img = null;
        try {
            for (String f : new String[]{"item", "block"}) {
                Path p = texPath(f);
                if (Files.exists(p)) {
                    img = ImageIO.read(p.toFile());
                    folder = f;
                    readMeta(p);
                    break;
                }
            }
            if (img == null) {
                for (String f : new String[]{"item", "block"}) {
                    Optional<Resource> res = this.client.getResourceManager()
                        .getResource(Identifier.of("minecraft", "textures/" + f + "/" + path + ".png"));
                    if (res.isPresent()) {
                        try (InputStream in = res.get().getInputStream()) {
                            img = ImageIO.read(in);
                        }
                        folder = f;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            status = "Could not read texture: " + e.getMessage();
        }

        if (img == null || img.getWidth() < 1 || img.getWidth() > 32) {
            if (img != null) status = "Texture is bigger than 32px, starting blank";
            else if (status.isEmpty()) status = "No flat texture found, starting blank";
            size = 16;
            frames.add(new int[size * size]);
            return;
        }
        size = img.getWidth();
        int n = Math.max(1, img.getHeight() / size);
        for (int f = 0; f < n; f++) {
            int[] px = new int[size * size];
            for (int y = 0; y < size; y++)
                for (int x = 0; x < size; x++)
                    px[y * size + x] = img.getRGB(x, y + f * size);
            frames.add(px);
        }
        if (status.isEmpty() && folder.equals("block")) status = "Block texture: also changes the placed block";
    }

    private void readMeta(Path png) {
        try {
            Path m = metaOf(png);
            if (!Files.exists(m)) return;
            JsonObject o = JsonParser.parseString(Files.readString(m)).getAsJsonObject();
            JsonObject a = o.getAsJsonObject("animation");
            if (a == null) return;
            if (a.has("frametime")) frametime = Math.max(1, Math.min(40, a.get("frametime").getAsInt()));
            interpolate = a.has("interpolate") && a.get("interpolate").getAsBoolean();
        } catch (Exception ignored) {
        }
    }

    private void apply() {
        try {
            Path root = packRoot();
            Files.createDirectories(root);
            Path packMeta = root.resolve("pack.mcmeta");
            if (!Files.exists(packMeta)) Files.writeString(packMeta, PACK_META);

            Path png = texPath(folder);
            Files.createDirectories(png.getParent());
            int n = frames.size();
            BufferedImage img = new BufferedImage(size, size * n, BufferedImage.TYPE_INT_ARGB);
            for (int f = 0; f < n; f++) {
                int[] px = frames.get(f);
                for (int y = 0; y < size; y++)
                    for (int x = 0; x < size; x++)
                        img.setRGB(x, y + f * size, px[y * size + x]);
            }
            ImageIO.write(img, "png", png.toFile());

            Path meta = metaOf(png);
            if (n > 1) {
                Files.writeString(meta, "{\"animation\":{\"frametime\":" + frametime
                    + ",\"interpolate\":" + interpolate + "}}");
            } else {
                Files.deleteIfExists(meta);
            }
            status = "Saved, reloading resources...";
            this.client.reloadResources();
        } catch (Exception e) {
            status = "Could not save: " + e.getMessage();
        }
    }

    private void revert() {
        try {
            Path png = texPath(folder);
            Files.deleteIfExists(png);
            Files.deleteIfExists(metaOf(png));
            status = "Reverted, reloading resources...";
            this.client.reloadResources().thenRun(() -> this.client.execute(this::reloadFromGame));
        } catch (Exception e) {
            status = "Could not revert: " + e.getMessage();
        }
    }

    private void reloadFromGame() {
        loaded = false;
        frames.clear();
        undo.clear();
        frame = 0;
        load();
    }

    // ------------------------------------------------------------ layout
    @Override
    protected void init() {
        if (!loaded) load();
        int cx = this.width / 2;
        gx = cx - 215;
        gy = 28;
        cell = size <= 16 ? 12 : 6;
        px0 = cx - 3;

        // tools
        String[] names = {"Pen", "Erase", "Fill", "Pick"};
        Tool[] tools = Tool.values();
        for (int i = 0; i < tools.length; i++) {
            Tool t = tools[i];
            String label = tool == t ? "[" + names[i] + "]" : names[i];
            addDrawableChild(button(px0 + i * 55, 28, 53, label, () -> { tool = t; clearAndInit(); }));
        }

        // palette
        swatches.clear();
        for (int col = 0; col < ColorUtil.PAL_COLS; col++) {
            for (int row = 0; row < ColorUtil.PAL_ROWS; row++) {
                int rgb = ColorUtil.palette(col, row);
                int x = px0 + col * 11, y = 52 + row * 11;
                swatches.add(new int[]{x, y, rgb});
                addDrawableChild(ButtonWidget.builder(Text.empty(), bt -> setColor(rgb))
                    .dimensions(x, y, 10, 10).build());
            }
        }

        // exact colour
        addDrawableChild(new IntSlider(px0, 112, 70, H, "R", 0, 255, r, v -> r = v));
        addDrawableChild(new IntSlider(px0 + 72, 112, 70, H, "G", 0, 255, g, v -> g = v));
        addDrawableChild(new IntSlider(px0 + 144, 112, 70, H, "B", 0, 255, b, v -> b = v));

        // frames
        addDrawableChild(button(px0, 136, 24, "<", () -> frame = (frame + frames.size() - 1) % frames.size()));
        addDrawableChild(button(px0 + 194, 136, 24, ">", () -> frame = (frame + 1) % frames.size()));
        addDrawableChild(button(px0, 158, 106, "+ Add frame", this::addFrame));
        addDrawableChild(button(px0 + 112, 158, 106, "- Delete frame", this::deleteFrame));
        addDrawableChild(new IntSlider(px0, 180, 106, H, "Frame time", 1, 40, frametime, v -> frametime = v));
        addDrawableChild(toggle(px0 + 112, 180, 106, "Blend", () -> interpolate, v -> interpolate = v));

        // edit actions
        addDrawableChild(button(px0, 202, 70, "Undo", this::undoLast));
        addDrawableChild(button(px0 + 72, 202, 70, "Clear", this::clearFrame));
        addDrawableChild(button(px0 + 144, 202, 70, "Revert", this::revert));

        // bottom
        addDrawableChild(button(px0, this.height - 28, 106, "Apply + reload", this::apply));
        addDrawableChild(button(px0 + 112, this.height - 28, 106, "Done", this::close));
    }

    private void setColor(int rgb) {
        r = (rgb >> 16) & 255;
        g = (rgb >> 8) & 255;
        b = rgb & 255;
        clearAndInit();
    }

    private int color() {
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // ------------------------------------------------------------ editing
    private void pushUndo() {
        undo.push(new Snap(frame, frames.get(frame).clone()));
        if (undo.size() > 40) undo.removeLast();
    }

    private void undoLast() {
        Snap s = undo.poll();
        if (s != null && s.frame() < frames.size()) {
            frames.set(s.frame(), s.px());
            frame = s.frame();
        }
    }

    private void clearFrame() {
        pushUndo();
        java.util.Arrays.fill(frames.get(frame), 0);
    }

    private void addFrame() {
        frames.add(frame + 1, frames.get(frame).clone());
        frame++;
        undo.clear();
    }

    private void deleteFrame() {
        if (frames.size() > 1) {
            frames.remove(frame);
            frame = Math.min(frame, frames.size() - 1);
            undo.clear();
        }
    }

    private void flood(int[] px, int sx, int sy, int newColor) {
        int target = px[sy * size + sx];
        if (target == newColor) return;
        Deque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[]{sx, sy});
        while (!stack.isEmpty()) {
            int[] p = stack.pop();
            int x = p[0], y = p[1];
            if (x < 0 || y < 0 || x >= size || y >= size) continue;
            if (px[y * size + x] != target) continue;
            px[y * size + x] = newColor;
            stack.push(new int[]{x + 1, y});
            stack.push(new int[]{x - 1, y});
            stack.push(new int[]{x, y + 1});
            stack.push(new int[]{x, y - 1});
        }
    }

    /** Painting is done by polling the mouse each frame, so dragging works. */
    private void handlePaint(int mx, int my) {
        long win = this.client.getWindow().getHandle();
        boolean left = GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean right = GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        boolean down = left || right;
        if (!down) { armed = true; wasDown = false; return; }
        if (!armed) return;                      // mouse was already held when the screen opened
        boolean press = !wasDown;
        wasDown = true;

        boolean inside = mx >= gx && my >= gy && mx < gx + size * cell && my < gy + size * cell;
        if (!inside) return;
        int x = (mx - gx) / cell, y = (my - gy) / cell;
        int[] px = frames.get(frame);
        Tool t = right ? Tool.ERASER : tool;
        if (press) pushUndo();
        switch (t) {
            case PENCIL -> px[y * size + x] = color();
            case ERASER -> px[y * size + x] = 0;
            case FILL -> { if (press) flood(px, x, y, color()); }
            case PICKER -> {
                if (press) {
                    int c = px[y * size + x];
                    if ((c >>> 24) != 0) {
                        r = (c >> 16) & 255; g = (c >> 8) & 255; b = c & 255;
                        tool = Tool.PENCIL;
                        clearAndInit();
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ drawing
    private void drawPixels(DrawContext ctx, int[] px, int ox, int oy, int c, boolean checker) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int col = px[y * size + x];
                int a = col >>> 24;
                int x1 = ox + x * c, y1 = oy + y * c;
                if (a == 255) { ctx.fill(x1, y1, x1 + c, y1 + c, col); continue; }
                int bg = checker ? ((((x + y) & 1) == 0) ? 0xFF3A3A3A : 0xFF4A4A4A) : 0xFF202020;
                ctx.fill(x1, y1, x1 + c, y1 + c, bg);
                if (a != 0) ctx.fill(x1, y1, x1 + c, y1 + c, col);
            }
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        handlePaint(mouseX, mouseY);
        super.render(ctx, mouseX, mouseY, delta);

        // canvas
        drawPixels(ctx, frames.get(frame), gx, gy, cell, true);
        int cw = size * cell;
        ctx.fill(gx - 1, gy - 1, gx + cw + 1, gy, 0xFFFFFFFF);
        ctx.fill(gx - 1, gy + cw, gx + cw + 1, gy + cw + 1, 0xFFFFFFFF);
        ctx.fill(gx - 1, gy, gx, gy + cw, 0xFFFFFFFF);
        ctx.fill(gx + cw, gy, gx + cw + 1, gy + cw, 0xFFFFFFFF);

        // palette colours drawn over the buttons
        for (int[] s : swatches) ctx.fill(s[0], s[1], s[0] + 10, s[1] + 10, 0xFF000000 | s[2]);

        // animated preview + in-game item
        int af = frames.size() > 1 ? (int) ((System.currentTimeMillis() / (frametime * 50L)) % frames.size()) : 0;
        int ps = Math.max(1, 48 / size);
        drawPixels(ctx, frames.get(af), px0 + 108, 52, ps, false);
        ctx.drawTextWithShadow(this.textRenderer, Text.literal("Preview"), px0 + 108, 102, 0xFFAAAAAA);

        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(px0 + 164 + 24f, 52 + 24f);
        m.scale(3f, 3f);
        ctx.drawItem(stack, -8, -8);
        m.popMatrix();
        ctx.drawTextWithShadow(this.textRenderer, Text.literal("In game"), px0 + 164, 102, 0xFFAAAAAA);

        // frame label between the < > buttons
        Text label = Text.literal("Frame " + (frame + 1) + "/" + frames.size());
        int lw = this.textRenderer.getWidth(label);
        ctx.drawTextWithShadow(this.textRenderer, label, px0 + 109 - lw / 2, 142, 0xFFFFFFFF);

        // info lines under the canvas
        ctx.drawTextWithShadow(this.textRenderer, Text.literal("Colour"), gx, gy + cw + 6, 0xFFAAAAAA);
        ctx.fill(gx + 40, gy + cw + 4, gx + 54, gy + cw + 16, 0xFFFFFFFF);
        ctx.fill(gx + 41, gy + cw + 5, gx + 53, gy + cw + 15, color());
        if (!status.isEmpty())
            ctx.drawTextWithShadow(this.textRenderer, Text.literal(status), gx, gy + cw + 20, 0xFFFFFF55);
        ctx.drawTextWithShadow(this.textRenderer,
            Text.literal("First time: Options > Resource Packs > enable 'PvPMod Textures'"),
            gx, gy + cw + 32, 0xFF888888);
    }
}
