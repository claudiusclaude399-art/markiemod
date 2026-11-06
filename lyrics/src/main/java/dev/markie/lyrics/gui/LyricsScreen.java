package dev.markie.lyrics.gui;

import dev.markie.lyrics.Sounds;
import dev.markie.lyrics.config.LyricsConfig;
import dev.markie.lyrics.lyrics.LyricsManager;
import dev.markie.lyrics.render.CoverArt;
import dev.markie.lyrics.spotify.NowPlaying;
import dev.markie.lyrics.spotify.SpotifyAuth;
import dev.markie.lyrics.spotify.SpotifyClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.joml.Matrix3x2fStack;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * The settings menu. It's drawn "immediate mode": every frame we lay out the current tab and remember
 * the clickable areas, which keeps animations (hover glow, sliding toggles, tab highlight) simple.
 */
public class LyricsScreen extends Screen {
    private enum Tab {
        OVERVIEW("Overview", "⌂", "Your music, in your world."),
        LYRICS("Lyrics", "♫", "Spotify lyrics in 3D in the sky."),
        LOOK("Look", "✦", "Colours, fonts and size."),
        HUD("HUD", "▣", "The now-playing pill on your screen."),
        SPOTIFY("Spotify", "◉", "Connect your Spotify account."),
        SOUNDS("Sounds", "♪", "Clicks, chimes and volume.");

        final String title, icon, subtitle;
        Tab(String title, String icon, String subtitle) { this.title = title; this.icon = icon; this.subtitle = subtitle; }
    }

    private record Hit(int x, int y, int w, int h, String id, Runnable click, DoubleConsumer drag, boolean clipped) {}

    private static Tab lastTab = Tab.LYRICS;

    private final Screen parent;
    private Tab tab = lastTab;
    private final List<Hit> hits = new ArrayList<>();
    private final Map<String, Float> anim = new HashMap<>();
    private final Map<String, float[]> springs = new HashMap<>();
    private int mainX, mainW;
    private String hoveredId, lastHoveredId;
    private Hit dragging;
    private long lastSliderSound;

    private float openAnim = 0f;
    private boolean closing = false;
    private boolean playedOpen = false;
    private float tabHighlightY = -1;
    private float tabFade = 1f;
    private float scroll = 0, scrollTarget = 0, maxScroll = 0;
    private long lastFrame = System.nanoTime();

    // layout (recomputed each frame)
    private int px, py, pw, ph, sideW, contentX, contentY, contentW, contentH;
    private double mx, my;

    private TextFieldWidget clientIdField;

    public LyricsScreen(Screen parent) {
        super(Text.literal("Markie Lyrics"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (!playedOpen) {
            playedOpen = true;
            Sounds.play(Sounds.OPEN);
        }
        clientIdField = new TextFieldWidget(textRenderer, 0, 0, 160, 16, Text.literal("Client ID"));
        clientIdField.setMaxLength(64);
        clientIdField.setText(LyricsConfig.get().clientId);
        clientIdField.setPlaceholder(Text.literal("paste your Client ID here").withColor(0xFF9890A6));
        clientIdField.setChangedListener(s -> {
            LyricsConfig.get().clientId = s.trim();
            LyricsConfig.save();
        });
        clientIdField.visible = false;
        clientIdField.setDrawsBackground(false);
        clientIdField.setEditableColor(0xFFECE8F4);
        addDrawableChild(clientIdField);
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void close() {
        if (closing) return;
        closing = true;
        LyricsConfig.save();
        Sounds.play(Sounds.CLOSE);
    }

    private void reallyClose() {
        lastTab = tab;
        if (client != null) client.setScreen(parent);
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        long nowNs = System.nanoTime();
        float dt = Math.min(0.1f, (nowNs - lastFrame) / 1e9f);
        lastFrame = nowNs;

        openAnim = Ui.approach(openAnim, closing ? 0f : 1f, closing ? 22f : 13f, dt);
        if (closing && openAnim < 0.03f) { reallyClose(); return; }
        tabFade = Ui.approach(tabFade, 1f, 12f, dt);
        scroll = Ui.approach(scroll, scrollTarget, 18f, dt);

        pw = Math.min(430, width - 24);
        ph = Math.min(264, height - 24);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        sideW = 112;
        mainX = px + sideW + 6;
        mainW = pw - sideW - 6;
        contentX = mainX + 10;
        contentY = py + 44;
        contentW = mainW - 22;
        contentH = ph - 44 - 8;

        float ease = easeOutBack(openAnim);
        float scale = 0.9f + 0.1f * ease;
        float alpha = Math.min(1f, openAnim * 1.4f);
        float cxp = width / 2f, cyp = height / 2f;
        mx = cxp + (mouseX - cxp) / scale;
        my = cyp + (mouseY - cyp) / scale - (1f - openAnim) * 10f;

        hits.clear();
        hoveredId = null;

        Matrix3x2fStack m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(cxp, cyp + (1f - openAnim) * 10f);
        m.scale(scale, scale);
        m.translate(-cxp, -cyp);

        // two floating sheets of glass
        int tint = Themes.current().glow();
        Glass.panel(ctx, px, py, sideW, ph, 14, tint, alpha);
        Glass.panel(ctx, mainX, py, mainW, ph, 14, tint, alpha);
        // specular light that follows the mouse across the glass
        ctx.enableScissor(px + 2, py + 2, px + pw - 2, py + ph - 2);
        Glass.sheen(ctx, (int) mx, (int) my, 240, Glass.argb(0.07f * alpha, 0xFFFFFF));
        Glass.sheen(ctx, px + pw / 4, py - 30, 320, Glass.argb(0.05f * alpha, 0xFFFFFF));
        ctx.disableScissor();
        drawSidebar(ctx, alpha, dt);
        drawHeader(ctx, alpha);

        // content
        ctx.enableScissor(contentX - 2, contentY, contentX + contentW + 2, contentY + contentH);
        int y0 = contentY - Math.round(scroll) + Math.round((1f - tabFade) * 8f);
        float ca = alpha * tabFade;
        int end = switch (tab) {
            case OVERVIEW -> overview(ctx, y0, ca, dt);
            case LYRICS -> lyrics(ctx, y0, ca, dt);
            case LOOK -> look(ctx, y0, ca, dt);
            case HUD -> hud(ctx, y0, ca, dt);
            case SPOTIFY -> spotify(ctx, y0, ca, dt);
            case SOUNDS -> sounds(ctx, y0, ca, dt);
        };
        ctx.disableScissor();
        maxScroll = Math.max(0, (end + Math.round(scroll)) - (contentY + contentH) + 6);
        if (scrollTarget > maxScroll) scrollTarget = maxScroll;
        if (maxScroll > 0) {
            // scroll bar
            int barH = Math.max(16, Math.round(contentH * contentH / (float) (contentH + maxScroll)));
            int barY = contentY + Math.round((contentH - barH) * (scroll / maxScroll));
            Glass.solid(ctx, mainX + mainW - 6, barY, 3, barH, 1, Ui.alpha(0x50FFFFFF, alpha));
        }
        m.popMatrix();

        // vanilla widgets (the Client ID box) only once the panel is fully open
        boolean showField = tab == Tab.SPOTIFY && openAnim > 0.98f && !closing
            && clientIdField.getY() >= contentY && clientIdField.getY() + 16 <= contentY + contentH;
        clientIdField.visible = showField;
        if (!showField && clientIdField.isFocused()) clientIdField.setFocused(false);
        super.render(ctx, mouseX, mouseY, delta);

        if (hoveredId != null && !hoveredId.equals(lastHoveredId)) Sounds.play(Sounds.HOVER, 1.0f + (float) Math.random() * 0.1f);
        lastHoveredId = hoveredId;
    }

    private void drawSidebar(DrawContext ctx, float alpha, float dt) {
        // logo: a little glass drop with a note in it
        int lx = px + 9, ly = py + 10;
        Glass.tinted(ctx, lx, ly, 20, 20, 7, Themes.current().a(), 0.75f, alpha);
        ctx.drawText(textRenderer, Text.literal("♫"), lx + 6, ly + 6, Ui.alpha(0xFFFFFFFF, alpha), false);
        Ui.text(ctx, Ui.big("Markie"), lx + 25, ly + 1, Ui.alpha(Ui.TEXT, alpha));
        Ui.text(ctx, Ui.t("lyrics in the sky"), lx + 25, ly + 12, Ui.alpha(Ui.MUTED, alpha));

        int ty = py + 42, rowH = 19, tabW = sideW - 12, tabX = px + 6;
        // liquid lens: springs to the selected tab and stretches while it travels
        float[] sp = spring("tablens", ty + tab.ordinal() * rowH, dt);
        float stretch = Math.min(0.6f, Math.abs(sp[1]) / 420f);
        int lensH = Math.round(17 * (1 + stretch));
        int lensW = Math.round(tabW * (1 - stretch * 0.18f));
        int lensY = Math.round(sp[0] + 8.5f - lensH / 2f);
        int lensX = tabX + (tabW - lensW) / 2;
        Glass.tinted(ctx, lensX, lensY, lensW, lensH, lensH / 2, Themes.current().glow(), 0.55f, alpha);

        for (Tab t : Tab.values()) {
            int y = ty + t.ordinal() * rowH;
            String id = "tab." + t.name();
            boolean hov = hover(tabX, y, tabW, 17, false);
            if (hov) hoveredId = id;
            float h = anim(id, hov && t != tab ? 1f : 0f, 14f, dt);
            if (h > 0.01f) Glass.card(ctx, tabX, y, tabW, 17, 8, h * 0.5f, alpha * h);
            int col = t == tab ? 0xFFFFFFFF : Ui.mix(Ui.MUTED, Ui.TEXT, h);
            ctx.drawText(textRenderer, Text.literal(t.icon), tabX + 7 + Math.round(h * 1.5f), y + 5, Ui.alpha(col, alpha), false);
            Ui.text(ctx, Ui.t(t.title), tabX + 20 + Math.round(h * 1.5f), y + 4, Ui.alpha(col, alpha));
            hits.add(new Hit(tabX, y, tabW, 17, id, () -> switchTab(t), null, false));
        }

        Ui.text(ctx, Ui.t("Markie Lyrics"), px + 11, py + ph - 23, Ui.alpha(Ui.MUTED, alpha));
        Ui.text(ctx, Ui.t("v1.2 · Right Shift"), px + 11, py + ph - 14, Ui.alpha(0xFF8E879C, alpha));
    }

    private void drawHeader(DrawContext ctx, float alpha) {
        int hx = contentX, hy = py + 10;
        Ui.text(ctx, Ui.big(tab.title), hx, hy, Ui.alpha(Ui.TEXT, alpha));
        Ui.text(ctx, Ui.t(tab.subtitle), hx, hy + 13, Ui.alpha(Ui.MUTED, alpha));

        // connection chip
        boolean connected = SpotifyAuth.isLoggedIn();
        Text t = Ui.t(connected ? "Spotify connected" : "Not connected");
        int w = textRenderer.getWidth(t) + 18;
        int x = mainX + mainW - w - 10;
        String id = "chip";
        boolean hov = hover(x, hy + 1, w, 15, false);
        if (hov) hoveredId = id;
        if (connected) Glass.tinted(ctx, x, hy + 1, w, 15, 7, 0x4ADE80, hov ? 0.4f : 0.28f, alpha);
        else Glass.card(ctx, x, hy + 1, w, 15, 7, hov ? 1f : 0.3f, alpha);
        float pulse = connected ? 0.6f + 0.4f * (float) Math.sin(System.currentTimeMillis() / 300.0) : 1f;
        Glass.solid(ctx, x + 6, hy + 6, 5, 5, 2, Ui.alpha(connected ? Ui.GREEN : 0xFFB4ADC2, alpha * pulse));
        Ui.text(ctx, t, x + 14, hy + 5, Ui.alpha(connected ? 0xFFD9FBE6 : Ui.TEXT, alpha));
        hits.add(new Hit(x, hy + 1, w, 15, id, () -> switchTab(Tab.SPOTIFY), null, false));
        ctx.fill(contentX, py + 38, mainX + mainW - 10, py + 39, Ui.alpha(0x22FFFFFF, alpha));
    }

    private void switchTab(Tab t) {
        if (t == tab) return;
        tab = t;
        lastTab = t;
        tabFade = 0f;
        scroll = scrollTarget = 0;
        Sounds.play(Sounds.TAB);
    }

    // ------------------------------------------------------------------ tabs

    private int overview(DrawContext ctx, int y, float a, float dt) {
        y = nowPlayingCard(ctx, y, a, dt);
        y = section(ctx, "Quick settings", y, a);
        LyricsConfig c = LyricsConfig.get();
        y = toggle(ctx, y, a, dt, "Show lyrics", "Spotify lyrics while you play", () -> c.showLyrics, v -> c.showLyrics = v);
        y = segmented(ctx, y, a, dt, "Where", whereDesc(c.where), new String[]{"Sky", "Floating", "Screen"},
            c.where.ordinal(), i -> c.where = LyricsConfig.Where.values()[i]);
        y = toggle(ctx, y, a, dt, "Now-playing HUD", "The song pill at the top of your screen", () -> c.hud, v -> c.hud = v);
        y = toggle(ctx, y, a, dt, "Preview with demo song", "Try every look without Spotify (turns off when you restart)",
            SpotifyClient::isDemo, SpotifyClient::setDemo);
        y = section(ctx, "Tips", y, a);
        y = paragraph(ctx, y, a, "Press Right Shift (or type /lyrics) to open this menu. You can change the key in Controls.");
        y = paragraph(ctx, y, a, "Lyrics come from LRCLIB, a free lyrics database. Not every song has synced lyrics.");
        if (!SpotifyAuth.isLoggedIn()) {
            y = button(ctx, y + 2, a, dt, "Connect Spotify →", true, () -> switchTab(Tab.SPOTIFY));
        }
        return y;
    }

    private int lyrics(DrawContext ctx, int y, float a, float dt) {
        LyricsConfig c = LyricsConfig.get();
        y = nowPlayingCard(ctx, y, a, dt);
        y = section(ctx, "Lyrics", y, a);
        y = toggle(ctx, y, a, dt, "Show lyrics", "Spotify lyrics while you play", () -> c.showLyrics, v -> c.showLyrics = v);
        y = segmented(ctx, y, a, dt, "Where", whereDesc(c.where), new String[]{"Sky", "Floating", "Screen"},
            c.where.ordinal(), i -> c.where = LyricsConfig.Where.values()[i]);
        y = segmented(ctx, y, a, dt, "Show", c.reveal == LyricsConfig.Reveal.WORD ? "Words pop in as they're sung" : "The whole line appears at once",
            new String[]{"Word by word", "Whole line"}, c.reveal.ordinal(), i -> c.reveal = LyricsConfig.Reveal.values()[i]);
        y = toggle(ctx, y, a, dt, "Follow view", "On: lyrics move with you when you turn. Off: they stay where they appeared.", () -> c.followView, v -> c.followView = v);
        y = toggle(ctx, y, a, dt, "Curved lines", "Bend each line into a soft arc", () -> c.curved, v -> c.curved = v);
        y = toggle(ctx, y, a, dt, "Show through blocks", "Lyrics are never hidden behind hills or trees", () -> c.throughWalls, v -> c.throughWalls = v);
        y = position(ctx, y, a, dt);
        y = section(ctx, "Timing", y, a);
        y = slider(ctx, y, a, "Timing offset", "Lyrics early or late? Nudge them here.", (c.offsetMs > 0 ? "+" : "") + c.offsetMs + " ms",
            (c.offsetMs + 2000) / 4000f, f -> c.offsetMs = (int) (Math.round((f * 4000 - 2000) / 50.0) * 50));
        return y;
    }

    /** Distance / height / spread controls; only the ones that matter for the current mode are shown. */
    private int position(DrawContext ctx, int y, float a, float dt) {
        LyricsConfig c = LyricsConfig.get();
        y = section(ctx, "Position", y, a);
        switch (c.where) {
            case SKY -> {
                int fog = client == null ? 0 : Math.round(Math.max(16f, client.options.getClampedViewDistance() * 16f * 0.6f));
                boolean capped = c.fitFog && fog > 0 && fog < c.skyDistance;
                y = slider(ctx, y, a, "Distance", capped ? "Limited to " + fog + " blocks by your render distance" : "How far away the words are written",
                    Math.round(c.skyDistance) + " blocks", (c.skyDistance - 16f) / 112f,
                    f -> c.skyDistance = Math.round(16f + (float) f * 112f));
                y = slider(ctx, y, a, "Height", "How high above the horizon lines appear",
                    Math.round(c.skyHeight) + "°", c.skyHeight / 70f, f -> c.skyHeight = Math.round((float) f * 70f));
                y = toggle(ctx, y, a, dt, "Stay in front of fog", "Pull lyrics closer if your render distance is low",
                    () -> c.fitFog, v -> c.fitFog = v);
            }
            case FLOATING -> {
                y = slider(ctx, y, a, "Distance", "How far in front of you the words float",
                    String.format("%.1f blocks", c.floatDistance), (c.floatDistance - 2f) / 22f,
                    f -> c.floatDistance = Math.round((2f + (float) f * 22f) * 2f) / 2f);
                y = slider(ctx, y, a, "Height", "Above (+) or below (-) where you look",
                    (c.floatHeight > 0 ? "+" : "") + Math.round(c.floatHeight) + "°", (c.floatHeight + 20f) / 50f,
                    f -> c.floatHeight = Math.round(-20f + (float) f * 50f));
            }
            case SCREEN -> y = slider(ctx, y, a, "Height on screen", "Where the subtitles sit",
                Math.round(c.screenY * 100) + "%", (c.screenY - 0.05f) / 0.9f,
                f -> c.screenY = Math.round((0.05f + (float) f * 0.9f) * 100f) / 100f);
        }
        if (c.where != LyricsConfig.Where.SCREEN) {
            y = slider(ctx, y, a, "Spread", "How far left and right new lines can land",
                Math.round(c.spread * 100) + "%", c.spread / 2f, f -> c.spread = Math.round((float) f * 40f) / 20f);
            y = toggle(ctx, y, a, dt, "Shrink with distance", "On: farther lyrics look smaller. Off: same size at any distance",
                () -> c.scaleWithDistance, v -> c.scaleWithDistance = v);
            y = paragraph(ctx, y, a, "Tip: bind \"Lyrics closer\" / \"Lyrics farther\" in Controls to change the distance while you play.");
        }
        return y;
    }

    private int look(DrawContext ctx, int y, float a, float dt) {
        LyricsConfig c = LyricsConfig.get();
        y = section(ctx, "Colour", y, a);
        int cols = 3, gap = 4;
        int cw = (contentW - gap * (cols - 1)) / cols, chh = 22;
        for (int i = 0; i < Themes.ALL.length; i++) {
            Themes.Theme th = Themes.ALL[i];
            int x = contentX + (i % cols) * (cw + gap);
            int yy = y + (i / cols) * (chh + gap);
            String id = "theme." + i;
            boolean hov = hover(x, yy, cw, chh, true);
            if (hov) hoveredId = id;
            float h = anim(id, hov ? 1f : 0f, 14f, dt);
            boolean sel = c.theme == i;
            if (sel) Glass.tinted(ctx, x, yy, cw, chh, 9, th.glow(), 0.45f, a);
            else Glass.card(ctx, x, yy, cw, chh, 9, h, a);
            // name drawn letter by letter in the theme's gradient
            Text name = Ui.in(th.name(), Fonts.UI);
            int tw = textRenderer.getWidth(name);
            int tx = x + (cw - tw) / 2;
            String s = th.name();
            for (int k = 0; k < s.length(); k++) {
                int col = 0xFF000000 | dev.markie.lyrics.render.LyricsAnimator.lerpRgb(th.a(), th.b(), s.length() <= 1 ? 0 : k / (float) (s.length() - 1));
                Text ch = Ui.in(String.valueOf(s.charAt(k)), Fonts.UI);
                Ui.text(ctx, ch, tx, yy + 5, Ui.alpha(col, a));
                tx += textRenderer.getWidth(ch);
            }
            // little swatch line
            int sx = x + cw / 2 - 8;
            ctx.fill(sx, yy + chh - 5, sx + 8, yy + chh - 4, Ui.alpha(0xFF000000 | th.a(), a));
            ctx.fill(sx + 8, yy + chh - 5, sx + 16, yy + chh - 4, Ui.alpha(0xFF000000 | th.b(), a));
            final int idx = i;
            hits.add(new Hit(x, yy, cw, chh, id, () -> { c.theme = idx; Sounds.play(Sounds.CLICK, 1.1f); }, null, true));
        }
        y += ((Themes.ALL.length + cols - 1) / cols) * (chh + gap) + 4;

        y = section(ctx, "Font", y, a);
        cols = 2;
        cw = (contentW - gap) / cols;
        chh = 20;
        for (int i = 0; i < Fonts.ALL.length; i++) {
            Fonts.FontChoice f = Fonts.ALL[i];
            int x = contentX + (i % cols) * (cw + gap);
            int yy = y + (i / cols) * (chh + gap);
            String id = "font." + i;
            boolean hov = hover(x, yy, cw, chh, true);
            if (hov) hoveredId = id;
            float h = anim(id, hov ? 1f : 0f, 14f, dt);
            boolean sel = c.font == i;
            if (sel) Glass.tinted(ctx, x, yy, cw, chh, 10, Themes.current().glow(), 0.5f, a);
            else Glass.card(ctx, x, yy, cw, chh, 10, h, a);
            Text name = Ui.in(f.name(), f.source());
            Ui.textCentered(ctx, name, x + cw / 2, yy + (chh - 9) / 2, Ui.alpha(sel ? 0xFFFFFFFF : Ui.TEXT, a));
            final int idx = i;
            hits.add(new Hit(x, yy, cw, chh, id, () -> { c.font = idx; Sounds.play(Sounds.CLICK, 1.1f); }, null, true));
        }
        y += ((Fonts.ALL.length + cols - 1) / cols) * (chh + gap) + 4;

        y = section(ctx, "Size", y, a);
        y = slider(ctx, y, a, "Lyrics size", "How big the words are", Math.round(c.size * 100) + "%",
            (c.size - 0.5f) / 1.5f, f -> c.size = Math.round((0.5f + (float) f * 1.5f) * 20f) / 20f);
        y = toggle(ctx, y, a, dt, "Glow", "Soft glow around the words", () -> c.glow, v -> c.glow = v);
        y = toggle(ctx, y, a, dt, "Shadow", "Classic Minecraft text shadow", () -> c.shadow, v -> c.shadow = v);
        return y;
    }

    private int hud(DrawContext ctx, int y, float a, float dt) {
        LyricsConfig c = LyricsConfig.get();
        y = section(ctx, "Now playing", y, a);
        y = toggle(ctx, y, a, dt, "Now-playing pill", "Song name at the top of your screen", () -> c.hud, v -> c.hud = v);
        y = toggle(ctx, y, a, dt, "Equaliser bars", "Little bars that bounce while music plays", () -> c.hudBars, v -> c.hudBars = v);
        y = toggle(ctx, y, a, dt, "Progress line", "Thin bar showing how far into the song you are", () -> c.hudProgress, v -> c.hudProgress = v);
        y = slider(ctx, y, a, "Height", "Move the pill up or down", c.hudY + " px",
            c.hudY / 120f, f -> c.hudY = (int) Math.round(f * 120));
        return y;
    }

    private int spotify(DrawContext ctx, int y, float a, float dt) {
        LyricsConfig c = LyricsConfig.get();
        boolean connected = SpotifyAuth.isLoggedIn();
        y = section(ctx, "Account", y, a);

        // status card
        int ch = 30;
        Glass.card(ctx, contentX, y, contentW, ch, 10, 0.2f, a);
        int dot = connected ? Ui.GREEN : SpotifyAuth.state == SpotifyAuth.State.FAILED ? 0xFFFF6B6B : 0xFFE8C66A;
        Glass.solid(ctx, contentX + 8, y + 8, 6, 6, 3, Ui.alpha(dot, a));
        Ui.text(ctx, Ui.t(connected ? "Connected" : "Not connected"), contentX + 19, y + 6, Ui.alpha(Ui.TEXT, a));
        String sub = SpotifyAuth.message.isEmpty() ? (connected ? SpotifyClient.status() : "Follow the steps below") : SpotifyAuth.message;
        if (connected && SpotifyAuth.state != SpotifyAuth.State.FAILED) sub = SpotifyClient.status();
        Ui.text(ctx, Ui.t(Ui.ellipsize(null, sub, contentW - 100, Ui.FONT)), contentX + 19, y + 17, Ui.alpha(Ui.MUTED, a));
        int bw = 70;
        if (connected) {
            smallButton(ctx, contentX + contentW - bw - 6, y + 7, bw, 16, a, dt, "Log out", false, SpotifyAuth::logout);
        } else {
            boolean waiting = SpotifyAuth.state == SpotifyAuth.State.WAITING_FOR_BROWSER;
            smallButton(ctx, contentX + contentW - bw - 6, y + 7, bw, 16, a, dt, waiting ? "Retry" : "Connect", true, () -> {
                LyricsConfig.save();
                SpotifyAuth.startLogin();
            });
        }
        y += ch + 6;

        y = section(ctx, "Client ID", y, a);
        int fy = y;
        clientIdField.setX(contentX + 6);
        clientIdField.setY(fy + 5);
        clientIdField.setWidth(contentW - 12);
        boolean focused = clientIdField.isFocused();
        Glass.well(ctx, contentX, fy, contentW, 18, 9, a);
        if (focused) Glass.tinted(ctx, contentX, fy, contentW, 18, 9, Themes.current().glow(), 0.15f, a);
        y += 23;

        y = section(ctx, "How to connect (one time)", y, a);
        y = step(ctx, y, a, "1", "Open the Spotify Developer Dashboard and log in.");
        y = button(ctx, y, a, dt, "Open developer.spotify.com", false,
            () -> Util.getOperatingSystem().open(URI.create("https://developer.spotify.com/dashboard")));
        y = step(ctx, y, a, "2", "Click \"Create app\". Any name works. Tick \"Web API\".");
        y = step(ctx, y, a, "3", "Add this Redirect URI (click to copy):");
        y = button(ctx, y, a, dt, SpotifyAuth.REDIRECT_URI, false, () -> {
            if (client != null) client.keyboard.setClipboard(SpotifyAuth.REDIRECT_URI);
            Sounds.play(Sounds.TOGGLE_ON);
        });
        y = step(ctx, y, a, "4", "Save, copy the app's Client ID, paste it above, then press Connect.");
        y = paragraph(ctx, y, a, "Your login is stored only in config/markielyrics.json on this computer. Play/pause/skip buttons need Spotify Premium.");
        return y;
    }

    private int sounds(DrawContext ctx, int y, float a, float dt) {
        LyricsConfig c = LyricsConfig.get();
        y = section(ctx, "Sounds", y, a);
        y = toggle(ctx, y, a, dt, "Menu sounds", "Soft clicks and chimes in this menu", () -> c.uiSounds, v -> c.uiSounds = v);
        y = slider(ctx, y, a, "Volume", "How loud the menu sounds are", Math.round(c.uiVolume * 100) + "%",
            c.uiVolume, f -> c.uiVolume = Math.round(f * 20) / 20f);
        y = toggle(ctx, y, a, dt, "Line chime", "A tiny sparkle when a new lyric line appears", () -> c.lineChime, v -> c.lineChime = v);
        y = button(ctx, y + 2, a, dt, "Play test chime", false, () -> Sounds.play(Sounds.CONNECT));
        return y;
    }

    private static String whereDesc(LyricsConfig.Where w) {
        return switch (w) {
            case SKY -> "Written big into the sky above you";
            case FLOATING -> "Floats right in front of you (best for PvP)";
            case SCREEN -> "On your screen, like subtitles";
        };
    }

    // ------------------------------------------------------------------ widgets

    private int nowPlayingCard(DrawContext ctx, int y, float a, float dt) {
        NowPlaying np = SpotifyClient.current();
        int h = 44;
        int x = contentX, w = contentW;
        Glass.card(ctx, x, y, w, h, 12, 0.25f, a);
        if (np == null) {
            coverPlaceholder(ctx, x + 6, y + 6, 32, a * 0.5f);
            Ui.text(ctx, Ui.t(SpotifyAuth.isLoggedIn() ? "Nothing playing" : "Spotify not connected"), x + 45, y + 10, Ui.alpha(Ui.TEXT, a));
            Ui.text(ctx, Ui.t(SpotifyAuth.isLoggedIn() ? "Start a song in Spotify" : "Open the Spotify tab to connect"), x + 45, y + 22, Ui.alpha(Ui.MUTED, a));
            return y + h + 6;
        }
        Identifier cover = CoverArt.texture();
        if (cover != null) {
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, cover, x + 6, y + 6, 0, 0, 32, 32, 32, 32, 32, 32, Ui.alpha(0xFFFFFFFF, a));
        } else {
            coverPlaceholder(ctx, x + 6, y + 6, 32, a);
        }
        int tx = x + 45;
        int buttonsW = 62;
        int maxText = w - 45 - buttonsW - 6;
        Ui.text(ctx, Ui.t(Ui.ellipsize(null, np.title(), maxText, Ui.FONT)), tx, y + 6, Ui.alpha(Ui.TEXT, a));
        Ui.text(ctx, Ui.t(Ui.ellipsize(null, np.artist(), maxText, Ui.FONT)), tx, y + 16, Ui.alpha(Ui.MUTED, a));

        // lyrics badge
        LyricsManager.State st = LyricsManager.state();
        String badge = switch (st) {
            case SYNCED -> "• Lyrics synced";
            case LOADING -> "• Finding lyrics…";
            case NOT_FOUND -> "• No synced lyrics";
            case ERROR -> "• Lyrics offline";
            case NONE -> "• —";
        };
        int bc = st == LyricsManager.State.SYNCED ? Ui.GREEN : st == LyricsManager.State.LOADING ? 0xFFE8C66A : 0xFFFF8A8A;
        Text bt = Ui.t(badge);
        int bw = textRenderer.getWidth(bt) + 8;
        Glass.tinted(ctx, tx, y + 26, bw, 11, 5, bc & 0xFFFFFF, 0.22f, a);
        Ui.text(ctx, bt, tx + 4, y + 28, Ui.alpha(bc, a));
        Ui.text(ctx, Ui.t(Ui.time(np.positionNow()) + " / " + Ui.time(np.durationMs())), tx + bw + 6, y + 28, Ui.alpha(0xFFB4ADC2, a));

        // progress bar
        float prog = np.durationMs() <= 0 ? 0 : np.positionNow() / (float) np.durationMs();
        int barX = tx, barW = w - 45 - 8, barY = y + h - 4;
        Glass.solid(ctx, barX, barY - 1, barW, 2, 1, Ui.alpha(0x30FFFFFF, a));
        Glass.solid(ctx, barX, barY - 1, Math.max(2, Math.round(barW * prog)), 2, 1, Ui.alpha(0xFF000000 | Themes.current().a(), a));

        // media buttons
        int bx = x + w - buttonsW - 4, by = y + 8;
        mediaButton(ctx, bx, by, a, dt, "prev", false, SpotifyClient::previous);
        mediaButton(ctx, bx + 21, by, a, dt, np.playing() ? "pause" : "play", true, SpotifyClient::togglePlay);
        mediaButton(ctx, bx + 42, by, a, dt, "next", false, SpotifyClient::next);
        return y + h + 6;
    }

    private void coverPlaceholder(DrawContext ctx, int x, int y, int s, float a) {
        Themes.Theme th = Themes.current();
        for (int i = 0; i < s; i++) {
            int col = 0xFF000000 | dev.markie.lyrics.render.LyricsAnimator.lerpRgb(th.glow(), th.a(), i / (float) s);
            int inset = i < 3 ? 3 - i : i > s - 4 ? i - (s - 4) : 0;
            ctx.fill(x + inset, y + i, x + s - inset, y + i + 1, Ui.alpha(col, a));
        }
        Glass.card(ctx, x, y, s, s, 6, 0.5f, a);
        Matrix3x2fStack m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(x + s / 2f, y + s / 2f);
        m.scale(2f, 2f);
        ctx.drawText(textRenderer, Text.literal("♫"), -3, -4, Ui.alpha(0xEEFFFFFF, a), false);
        m.popMatrix();
    }

    private void mediaButton(DrawContext ctx, int x, int y, float a, float dt, String kind, boolean primary, Runnable action) {
        int s = 18;
        String id = "media." + (primary ? "main" : kind);
        boolean hov = hover(x, y, s, s, true);
        if (hov) hoveredId = id;
        float h = anim(id, hov ? 1f : 0f, 16f, dt);
        if (primary) Glass.tinted(ctx, x, y, s, s, s / 2, Themes.current().glow(), 0.6f + 0.25f * h, a);
        else Glass.card(ctx, x, y, s, s, s / 2, 0.3f + 0.7f * h, a);
        int col = Ui.alpha(0xFFFFFFFF, a);
        int cx = x + s / 2, cy = y + s / 2;
        switch (kind) {
            case "play" -> triangle(ctx, cx - 2, cy, 5, true, col);
            case "pause" -> { ctx.fill(cx - 3, cy - 4, cx - 1, cy + 4, col); ctx.fill(cx + 1, cy - 4, cx + 3, cy + 4, col); }
            case "next" -> { triangle(ctx, cx - 3, cy, 4, true, col); ctx.fill(cx + 2, cy - 4, cx + 3, cy + 4, col); }
            case "prev" -> { triangle(ctx, cx + 3, cy, 4, false, col); ctx.fill(cx - 3, cy - 4, cx - 2, cy + 4, col); }
            default -> {}
        }
        hits.add(new Hit(x, y, s, s, id, () -> {
            Sounds.play(Sounds.CLICK, primary ? 1f : 1.15f);
            action.run();
        }, null, true));
    }

    /** Small filled triangle pointing right (or left) with its flat side at x. */
    private static void triangle(DrawContext ctx, int x, int cy, int half, boolean right, int col) {
        for (int i = 0; i <= half; i++) {
            int len = half + 1 - i;
            if (right) {
                ctx.fill(x, cy - len, x + 1, cy + len, col);
                x++;
            } else {
                ctx.fill(x - 1, cy - len, x, cy + len, col);
                x--;
            }
        }
    }

    private int section(DrawContext ctx, String title, int y, float a) {
        y += 2;
        Text t = Ui.t(title.toUpperCase());
        Ui.text(ctx, t, contentX + 3, y, Ui.alpha(0xE6D9D2EA, a));
        int lx = contentX + 8 + textRenderer.getWidth(t);
        ctx.fill(lx, y + 5, contentX + contentW, y + 6, Ui.alpha(0x1CFFFFFF, a));
        return y + 13;
    }

    private int paragraph(DrawContext ctx, int y, float a, String text) {
        List<net.minecraft.text.OrderedText> lines = textRenderer.wrapLines(Ui.t(text), contentW - 4);
        for (net.minecraft.text.OrderedText line : lines) {
            ctx.drawText(textRenderer, line, contentX + 2, y, Ui.alpha(Ui.MUTED, a), false);
            y += 10;
        }
        return y + 3;
    }

    private int step(DrawContext ctx, int y, float a, String num, String text) {
        Glass.tinted(ctx, contentX, y, 12, 12, 6, Themes.current().glow(), 0.6f, a);
        Ui.textCentered(ctx, Ui.t(num), contentX + 6, y + 2, Ui.alpha(0xFFFFFFFF, a));
        List<net.minecraft.text.OrderedText> lines = textRenderer.wrapLines(Ui.t(text), contentW - 20);
        int yy = y + 2;
        for (net.minecraft.text.OrderedText line : lines) {
            ctx.drawText(textRenderer, line, contentX + 17, yy, Ui.alpha(Ui.TEXT, a), false);
            yy += 10;
        }
        return Math.max(y + 15, yy + 3);
    }

    /** A row with a label, a little description and a sliding switch on the right. */
    private int toggle(DrawContext ctx, int y, float a, float dt, String label, String desc, BooleanSupplier get, Consumer<Boolean> set) {
        int h = rowHeight(desc);
        String id = "toggle." + label;
        boolean hov = hover(contentX, y, contentW, h, true);
        if (hov) hoveredId = id;
        rowBackground(ctx, y, h, a, anim(id + ".h", hov ? 1f : 0f, 14f, dt));
        rowText(ctx, y, a, label, desc, contentW - 40);

        boolean on = get.getAsBoolean();
        float[] sp = spring(id, on ? 1f : 0f, dt);
        float k = Math.max(0, Math.min(1, sp[0]));
        int sw = 26, sh = 14;
        int sx = contentX + contentW - sw - 8, sy = y + (h - sh) / 2;
        Glass.well(ctx, sx, sy, sw, sh, 7, a);
        if (k > 0.01f) Glass.tinted(ctx, sx, sy, sw, sh, 7, 0x34C77B, 0.85f * k, a * Math.min(1, k * 1.5f));
        // the knob stretches like a drop of liquid while it slides
        int kw = 10 + Math.round(Math.min(6f, Math.abs(sp[1]) * 1.4f));
        int travel = sw - 4 - kw;
        int knob = sx + 2 + Math.round(Math.max(0, Math.min(1, sp[0])) * travel);
        Glass.bead(ctx, knob, sy + 2, kw, sh - 4, a);
        hits.add(new Hit(contentX, y, contentW, h, id, () -> {
            boolean v = !get.getAsBoolean();
            set.accept(v);
            Sounds.play(v ? Sounds.TOGGLE_ON : Sounds.TOGGLE_OFF);
            LyricsConfig.save();
        }, null, true));
        return y + h + 3;
    }

    /** A row with a segmented control (one option highlighted, with a sliding highlight). */
    private int segmented(DrawContext ctx, int y, float a, float dt, String label, String desc, String[] options, int selected, Consumer<Integer> set) {
        int h = rowHeight(desc);
        String id = "seg." + label;
        int segW = 0;
        int[] widths = new int[options.length];
        for (int i = 0; i < options.length; i++) {
            widths[i] = textRenderer.getWidth(Ui.t(options[i])) + 12;
            segW += widths[i];
        }
        boolean hovRow = hover(contentX, y, contentW, h, true);
        rowBackground(ctx, y, h, a, anim(id + ".h", hovRow ? 1f : 0f, 14f, dt));
        rowText(ctx, y, a, label, desc, contentW - segW - 20);

        int sx = contentX + contentW - segW - 8, sy = y + (h - 14) / 2;
        Glass.well(ctx, sx - 2, sy - 2, segW + 4, 18, 9, a);
        int selX = sx;
        for (int i = 0; i < selected; i++) selX += widths[i];
        float[] hx = spring(id + ".x", selX, dt);
        float[] hw = spring(id + ".w", widths[selected], dt);
        float st = Math.min(0.35f, Math.abs(hx[1]) / 600f);
        int lw = Math.round(hw[0] * (1 + st)), lh = Math.round(14 * (1 - st * 0.4f));
        Glass.tinted(ctx, Math.round(hx[0] + hw[0] / 2f - lw / 2f), sy + (14 - lh) / 2, lw, lh, lh / 2, Themes.current().glow(), 0.6f, a);
        int x = sx;
        for (int i = 0; i < options.length; i++) {
            String oid = id + "." + i;
            boolean hov = hover(x, sy, widths[i], 14, true);
            if (hov) hoveredId = oid;
            int col = i == selected ? 0xFFFFFFFF : hov ? Ui.TEXT : Ui.MUTED;
            Ui.textCentered(ctx, Ui.t(options[i]), x + widths[i] / 2, sy + 3, Ui.alpha(col, a));
            final int idx = i;
            hits.add(new Hit(x, sy, widths[i], 14, oid, () -> {
                if (idx != selected) {
                    set.accept(idx);
                    Sounds.play(Sounds.CLICK, 0.95f + idx * 0.08f);
                    LyricsConfig.save();
                }
            }, null, true));
            x += widths[i];
        }
        return y + h + 3;
    }

    private int slider(DrawContext ctx, int y, float a, String label, String desc, String valueText, float frac, DoubleConsumer set) {
        int h = rowHeight(desc);
        String id = "slider." + label;
        int trackW = 90;
        boolean hov = hover(contentX, y, contentW, h, true) || (dragging != null && id.equals(dragging.id));
        rowBackground(ctx, y, h, a, anim(id + ".h", hov ? 1f : 0f, 14f, 0.016f));
        rowText(ctx, y, a, label, desc, contentW - trackW - 60);

        int tx = contentX + contentW - trackW - 10, ty = y + h / 2;
        Text vt = Ui.t(valueText);
        Ui.text(ctx, vt, tx - textRenderer.getWidth(vt) - 8, ty - 4, Ui.alpha(0xFFB9A6E0, a));
        float f = Math.max(0, Math.min(1, frac));
        float shown = anim(id, f, 20f, 0.016f);
        Glass.well(ctx, tx, ty - 2, trackW, 4, 2, a);
        Glass.solid(ctx, tx, ty - 2, Math.max(4, Math.round(trackW * shown)), 4, 2, Ui.alpha(0xFF000000 | Themes.current().a(), a));
        int kx = tx + Math.round(trackW * shown);
        if (hov) Glass.sheen(ctx, kx, ty, 28, Glass.argb(0.35f * a, Themes.current().a()));
        int kw = hov ? 14 : 12;
        Glass.bead(ctx, kx - kw / 2, ty - 5, kw, 10, a);
        hits.add(new Hit(tx - 4, y, trackW + 8, h, id, null, v -> {
            double nf = Math.max(0, Math.min(1, (v - tx) / trackW));
            set.accept(nf);
        }, true));
        return y + h + 3;
    }

    private int button(DrawContext ctx, int y, float a, float dt, String label, boolean primary, Runnable action) {
        Text t = Ui.t(label);
        int w = Math.min(contentW, textRenderer.getWidth(t) + 20);
        smallButton(ctx, contentX, y, w, 16, a, dt, label, primary, action);
        return y + 20;
    }

    private void smallButton(DrawContext ctx, int x, int y, int w, int h, float a, float dt, String label, boolean primary, Runnable action) {
        String id = "btn." + label;
        boolean hov = hover(x, y, w, h, true);
        if (hov) hoveredId = id;
        float k = anim(id, hov ? 1f : 0f, 16f, dt);
        if (primary) Glass.tinted(ctx, x, y, w, h, h / 2, Themes.current().glow(), 0.6f + 0.25f * k, a);
        else Glass.card(ctx, x, y, w, h, h / 2, 0.3f + 0.7f * k, a);
        Ui.textCentered(ctx, Ui.t(label), x + w / 2, y + (h - 8) / 2, Ui.alpha(0xFFFFFFFF, a));
        hits.add(new Hit(x, y, w, h, id, () -> { Sounds.play(Sounds.CLICK); action.run(); }, null, true));
    }

    private int rowHeight(String desc) {
        return desc == null || desc.isEmpty() ? 18 : 26;
    }

    private void rowBackground(DrawContext ctx, int y, int h, float a, float hover) {
        Glass.card(ctx, contentX, y, contentW, h, 10, hover, a);
    }

    private void rowText(DrawContext ctx, int y, float a, String label, String desc, int maxW) {
        if (desc == null || desc.isEmpty()) {
            Ui.text(ctx, Ui.t(label), contentX + 8, y + 5, Ui.alpha(Ui.TEXT, a));
        } else {
            Ui.text(ctx, Ui.t(label), contentX + 8, y + 4, Ui.alpha(Ui.TEXT, a));
            Ui.text(ctx, Ui.t(Ui.ellipsize(null, desc, maxW, Ui.FONT)), contentX + 8, y + 14, Ui.alpha(0xFFADA6BB, a));
        }
    }

    // ------------------------------------------------------------------ input

    private boolean hover(int x, int y, int w, int h, boolean clipped) {
        if (closing) return false;
        if (clipped && (my < contentY || my >= contentY + contentH)) return false;
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private float anim(String id, float target, float speed, float dt) {
        Float cur = anim.get(id);
        float v = cur == null ? target : Ui.approach(cur, target, speed, dt);
        anim.put(id, v);
        return v;
    }

    /** Slightly under-damped spring: returns {position, velocity}. Gives the wobbly "liquid" motion. */
    private float[] spring(String id, float target, float dt) {
        float[] st = springs.computeIfAbsent(id, k -> new float[]{target, 0});
        float k = 240f, d = 2f * (float) Math.sqrt(k) * 0.62f;
        int n = 4;
        float h = dt / n;
        for (int i = 0; i < n; i++) {
            float acc = k * (target - st[0]) - d * st[1];
            st[1] += acc * h;
            st[0] += st[1] * h;
        }
        return st;
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (client == null || client.world == null) {
            super.renderBackground(ctx, mouseX, mouseY, delta);
            return;
        }
        // frosted world behind the glass: blur it, then only a light tint (no heavy darkening)
        applyBlur(ctx);
        float a = Math.min(1f, openAnim * 1.4f);
        int tint = Themes.current().glow();
        ctx.fillGradient(0, 0, width, height, Glass.argb(0.10f * a, tint), Glass.argb(0.28f * a, 0x05040A));
    }

    private Hit hitAt(double x, double y) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (h.clipped && (y < contentY || y >= contentY + contentH)) continue;
            if (x >= h.x && x < h.x + h.w && y >= h.y && y < h.y + h.h) return h;
        }
        return null;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (closing) return true;
        if (clientIdField.visible && clientIdField.isMouseOver(click.x(), click.y())) {
            return super.mouseClicked(click, doubled);
        }
        clientIdField.setFocused(false);
        setFocused(null);
        if (click.button() != 0) return false;
        Hit h = hitAt(mx, my);
        if (h == null) {
            return super.mouseClicked(click, doubled);
        }
        if (h.drag != null) {
            dragging = h;
            h.drag.accept(mx);
            Sounds.play(Sounds.SLIDER);
            return true;
        }
        if (h.click != null) h.click.run();
        return true;
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        if (dragging != null) {
            // mx is refreshed every frame in render(); use the latest mouse position transformed the same way
            float scale = 0.9f + 0.1f * easeOutBack(openAnim);
            double x = width / 2f + (click.x() - width / 2f) / scale;
            dragging.drag.accept(x);
            long now = System.currentTimeMillis();
            if (now - lastSliderSound > 45) {
                lastSliderSound = now;
                Sounds.play(Sounds.SLIDER, 0.9f + (float) Math.random() * 0.2f);
            }
            return true;
        }
        return super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (dragging != null) {
            dragging = null;
            LyricsConfig.save();
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        scrollTarget = (float) Math.max(0, Math.min(maxScroll, scrollTarget - vertical * 22));
        return true;
    }

    private static float easeOutBack(float t) {
        float c1 = 1.3f, c3 = c1 + 1;
        float u = t - 1;
        return 1 + c3 * u * u * u + c1 * u * u;
    }
}
