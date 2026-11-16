package dev.markiesdetector.glass;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;

/**
 * Base for the glass screens: immediate-mode widgets, spring animations, mouse handling and the
 * animated "aurora" wallpaper drawn over the blurred world.
 */
public abstract class GlassScreen extends Screen {
    protected final Screen parent;
    protected float mx, my, dt;
    /** Seconds since this screen opened. */
    protected float age = 0f;
    private long lastNanos = 0;
    protected final Spring appear = new Spring(0f, 120f, 17f);
    private final Map<String, Spring> springs = new HashMap<>();

    // pending input, consumed by widgets during render
    private boolean clickPending = false;
    protected float clickX, clickY;
    protected int clickButton;
    protected boolean mouseDown = false;
    protected float scrollPending = 0f;
    protected boolean closing = false;
    /** While a modal is open only widgets drawn with {@code inModal} set receive clicks. */
    protected boolean modalOpen = false, inModal = false;

    protected GlassScreen(String title, Screen parent) {
        super(Text.literal(title));
        this.parent = parent;
    }

    // ------------------------------------------------------------------ lifecycle
    protected abstract void draw(DrawContext ctx);

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.renderBackground(ctx, mouseX, mouseY, delta);
        Glass.begin(ctx);
        float a = appear.get();
        float t = Glass.time;
        float w = this.width, h = this.height, R = Math.max(w, h) * 0.55f;
        Glass.glow(w * (0.20f + 0.08f * (float) Math.sin(t * 0.21f)), h * (0.25f + 0.10f * (float) Math.cos(t * 0.17f)), R, Col.a(Col.INDIGO, 0.22f * a));
        Glass.glow(w * (0.82f + 0.06f * (float) Math.cos(t * 0.19f)), h * (0.35f + 0.12f * (float) Math.sin(t * 0.23f)), R * 0.9f, Col.a(Col.PINK, 0.14f * a));
        Glass.glow(w * (0.55f + 0.10f * (float) Math.sin(t * 0.13f)), h * (0.95f + 0.05f * (float) Math.cos(t * 0.29f)), R, Col.a(Col.TEAL, 0.14f * a));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        long now = System.nanoTime();
        dt = lastNanos == 0 ? 1f / 60f : Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        Glass.tick();
        age += dt;
        mx = mouseX;
        my = mouseY;
        mouseDown = GLFW.glfwGetMouseButton(this.client.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        appear.to(closing ? 0f : 1f).update(dt);
        for (Spring s : springs.values()) s.update(dt);

        Glass.begin(ctx);
        Glass.alpha = Math.max(0f, Math.min(1f, appear.get()));
        draw(ctx);
        Glass.alpha = 1f;

        clickPending = false;
        scrollPending = 0f;
        if (closing && appear.get() < 0.04f) finishClose();
    }

    @Override
    public void close() {
        if (!closing) closing = true;
    }

    protected void finishClose() {
        onClosed();
        if (this.client != null) this.client.setScreen(parent);
    }

    protected void onClosed() {}

    /** Switch to another screen immediately. */
    protected void go(Screen s) {
        if (this.client != null) this.client.setScreen(s);
    }

    // ------------------------------------------------------------------ input
    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        clickPending = true;
        clickX = (float) click.x();
        clickY = (float) click.y();
        clickButton = click.button();
        onMousePress(clickX, clickY, clickButton);
        return true;
    }

    @Override
    public boolean mouseReleased(Click click) {
        onMouseRelease((float) click.x(), (float) click.y());
        return true;
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        onDrag((float) click.x(), (float) click.y(), (float) dx, (float) dy);
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double h, double v) {
        scrollPending += (float) v;
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (onKey(input.key())) return true;
        return super.keyPressed(input);
    }

    protected boolean onKey(int key) { return false; }

    protected void onMousePress(float x, float y, int button) {}

    protected void onMouseRelease(float x, float y) {}

    protected void onDrag(float x, float y, float dx, float dy) {}

    protected float takeScroll() {
        float s = scrollPending;
        scrollPending = 0f;
        return s;
    }

    // ------------------------------------------------------------------ widget helpers
    protected Spring spring(String id, float init) {
        return springs.computeIfAbsent(id, k -> new Spring(init));
    }

    protected Spring spring(String id, float init, float k, float d) {
        return springs.computeIfAbsent(id, key -> new Spring(init, k, d));
    }

    protected boolean hover(float x, float y, float w, float h) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }

    /** True once if a pending click landed in the rectangle (the click is consumed). */
    protected boolean clicked(float x, float y, float w, float h) {
        if (modalOpen && !inModal) return false;
        if (clickPending && clickButton == 0 && clickX >= x && clickY >= y && clickX < x + w && clickY < y + h) {
            clickPending = false;
            return true;
        }
        return false;
    }

    protected boolean hasClick() { return clickPending; }

    protected void consumeClick() { clickPending = false; }

    /** Glass button with a spring hover lift. */
    protected boolean button(String id, String label, float x, float y, float w, float h, int tint, float tintAlpha) {
        Spring hs = spring("btn:" + id, 0f);
        boolean hov = hover(x, y, w, h);
        hs.to(hov ? (mouseDown ? 0.4f : 1f) : 0f);
        float k = hs.get();
        float grow = k * 1.2f;
        Glass.button(x - grow, y - grow * 0.5f, w + grow * 2, h + grow, h / 2f, tint, tintAlpha, k);
        GFont.drawCentered(label, x + w / 2f, y + (h - GFont.lineHeight(8)) / 2f + 0.5f, 8, Col.LABEL, true);
        return clicked(x, y, w, h);
    }

    protected boolean button(String id, String label, float x, float y, float w, float h) {
        return button(id, label, x, y, w, h, 0x2C2C2E, 0.35f);
    }

    /** iOS toggle switch. */
    protected boolean toggle(String id, float x, float y, boolean on) {
        float w = 30, h = 17;
        Spring s = spring("sw:" + id, on ? 1f : 0f, 260f, 22f);
        s.to(on ? 1f : 0f);
        float k = s.get();
        int track = Col.lerp(Col.a(0x787880, 0.36f), Col.a(Col.GREEN, 1f), Math.max(0, Math.min(1, k)));
        Glass.round(x, y, w, h, h / 2f, track);
        Glass.rim(x, y, w, h, h / 2f, Col.a(0xFFFFFF, 0.25f), Col.a(0xFFFFFF, 0.06f));
        float kx = x + 2 + (w - h) * k;
        Glass.shadow(kx, y + 2, h - 4, h - 4, (h - 4) / 2f, 3, Col.a(0, 0.35f));
        Glass.dot(kx + (h - 4) / 2f, y + h / 2f, (h - 4) / 2f, 0xFFFFFFFF);
        return clicked(x, y, w, h);
    }

    /** Segmented control with a sliding glass pill. Returns the selected index. */
    protected int segmented(String id, String[] items, int selected, float x, float y, float w, float h) {
        Glass.round(x, y, w, h, h / 2f, Col.a(0x767680, 0.24f));
        Glass.rim(x, y, w, h, h / 2f, Col.a(0xFFFFFF, 0.18f), Col.a(0xFFFFFF, 0.05f));
        float sw = w / items.length;
        Spring pos = spring("seg:" + id, selected, 210f, 21f);
        pos.to(selected);
        float px = x + 2 + pos.get() * sw;
        Glass.button(px, y + 2, sw - 4, h - 4, (h - 4) / 2f, 0x636366, 0.55f, 0.3f);
        int result = selected;
        for (int i = 0; i < items.length; i++) {
            float ix = x + i * sw;
            boolean on = Math.abs(pos.get() - i) < 0.5f;
            boolean hov = hover(ix, y, sw, h);
            int col = on ? Col.LABEL : (hov ? 0xE6FFFFFF : Col.LABEL2);
            GFont.drawCentered(items[i], ix + sw / 2f, y + (h - GFont.lineHeight(8)) / 2f + 0.5f, 8, col, on);
            if (clicked(ix, y, sw, h)) result = i;
        }
        return result;
    }

    /** Horizontal slider 0..1; returns new value while dragging. */
    protected float slider(String id, float x, float y, float w, float value) {
        float h = 4;
        Glass.round(x, y - h / 2f, w, h, h / 2f, Col.a(0x787880, 0.36f));
        Glass.round(x, y - h / 2f, w * value, h, h / 2f, Col.a(Col.BLUE, 1f));
        float kx = x + w * value;
        Glass.shadow(kx - 7, y - 7, 14, 14, 7, 3, Col.a(0, 0.35f));
        Glass.dot(kx, y, 7, 0xFFFFFFFF);
        Spring drag = spring("sl:" + id, 0f);
        if (clicked(x - 8, y - 9, w + 16, 18)) drag.snap(1f);
        if (!mouseDown) drag.snap(0f);
        if (drag.get() > 0.5f) return Math.max(0f, Math.min(1f, (mx - x) / w));
        return value;
    }

    /** "‹ Back" style chevron + label. */
    protected boolean backButton(String label, float x, float y) {
        float w = GFont.width(label, 9, true) + 22, h = 20;
        Spring hs = spring("back", 0f);
        hs.to(hover(x, y, w, h) ? 1f : 0f);
        Glass.button(x, y, w, h, h / 2f, 0x2C2C2E, 0.30f, hs.get());
        float cx = x + 10, cy = y + h / 2f;
        Glass.line(cx + 2.5f, cy - 4, cx - 1.5f, cy, 1.6f, 0xFFFFFFFF);
        Glass.line(cx - 1.5f, cy, cx + 2.5f, cy + 4, 1.6f, 0xFFFFFFFF);
        GFont.draw(label, x + 17, y + (h - GFont.lineHeight(9)) / 2f + 0.5f, 9, Col.LABEL, true);
        return clicked(x, y, w, h);
    }

    /** Small rounded label chip. Returns its width. */
    protected float chip(String text, float x, float y, int rgb) {
        float w = GFont.width(text, 7, true) + 10, h = 12;
        Glass.round(x, y, w, h, h / 2f, Col.a(rgb, 0.22f));
        Glass.rim(x, y, w, h, h / 2f, Col.a(rgb, 0.55f), Col.a(rgb, 0.20f));
        GFont.draw(text, x + 5, y + 2.2f, 7, Col.a(Col.lerp(0xFFFFFFFF, 0xFF000000 | rgb, 0.45f) & 0xFFFFFF, 1f), true);
        return w;
    }
}
