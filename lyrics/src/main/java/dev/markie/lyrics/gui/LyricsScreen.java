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
        clientIdField.setPlaceholder(Text.literal("paste your Client ID here").withColor(0xFF6E667A));
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

        pw = Math.min(420, width - 24);
        ph = Math.min(260, height - 24);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        sideW = 106;
        contentX = px + sideW + 12;
        contentY = py + 44;
        contentW = pw - sideW - 24;
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

        // glow behind the panel
        Ui.round(ctx, px - 3, py - 3, pw + 6, ph + 6, 12, Ui.alpha(0x308B5CF6, alpha));
        Ui.roundOutlined(ctx, px, py, pw, ph, 9, Ui.alpha(Ui.BG, alpha), Ui.alpha(0x40FFFFFF, alpha));
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
            Ui.round(ctx, px + pw - 6, barY, 3, barH, 1, Ui.alpha(0x40FFFFFF, alpha));
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
        Ui.round(ctx, px + 1, py + 1, sideW, ph - 2, 8, Ui.alpha(Ui.SIDEBAR, alpha));
        ctx.fill(px + sideW, py + 8, px + sideW + 1, py + ph - 8, Ui.alpha(0x18FFFFFF, alpha));

        // logo
        int lx = px + 9, ly = py + 10;
        Ui.round(ctx, lx, ly, 18, 18, 5, Ui.alpha(Ui.ACCENT, alpha));
        Ui.round(ctx, lx + 2, ly + 2, 14, 7, 3, Ui.alpha(0x30FFFFFF, alpha));
        ctx.drawText(textRenderer, Text.literal("♫"), lx + 5, ly + 5, Ui.alpha(0xFFFFFFFF, alpha), false);
        Ui.text(ctx, Ui.big("Markie"), lx + 23, ly + 1, Ui.alpha(Ui.TEXT, alpha));
        Ui.text(ctx, Ui.t("lyrics in the sky"), lx + 23, ly + 12, Ui.alpha(Ui.MUTED, alpha));

        int ty = py + 40;
        Tab[] tabs = Tab.values();
        float target = ty + tab.ordinal() * 19;
        if (tabHighlightY < 0) tabHighlightY = target;
        tabHighlightY = Ui.approach(tabHighlightY, target, 16f, dt);
        Ui.round(ctx, px + 6, Math.round(tabHighlightY), sideW - 11, 17, 5, Ui.alpha(Ui.ACCENT_SOFT, alpha));
        ctx.fill(px + 3, Math.round(tabHighlightY) + 4, px + 4, Math.round(tabHighlightY) + 13, Ui.alpha(0xFFC4A4FF, alpha));
        for (Tab t : tabs) {
            int y = ty + t.ordinal() * 19;
            String id = "tab." + t.name();
            boolean hov = hover(px + 6, y, sideW - 11, 17, false);
            if (hov) hoveredId = id;
            float h = anim(id, hov && t != tab ? 1f : 0f, 14f, dt);
            if (h > 0.01f) Ui.round(ctx, px + 6, y, sideW - 11, 17, 5, Ui.alpha(0x14FFFFFF, alpha * h));
            int col = t == tab ? 0xFFFFFFFF : Ui.mix(Ui.MUTED, Ui.TEXT, h);
            ctx.drawText(textRenderer, Text.literal(t.icon), px + 12 + Math.round(h * 1.5f), y + 5, Ui.alpha(col, alpha), false);
            Ui.text(ctx, Ui.t(t.title), px + 25 + Math.round(h * 1.5f), y + 4, Ui.alpha(col, alpha));
            hits.add(new Hit(px + 6, y, sideW - 11, 17, id, () -> switchTab(t), null, false));
        }

        Ui.text(ctx, Ui.t("Markie Lyrics"), px + 10, py + ph - 22, Ui.alpha(Ui.MUTED, alpha));
        Ui.text(ctx, Ui.t("v1.0 · Right Shift"), px + 10, py + ph - 13, Ui.alpha(0xFF5E5868, alpha));
    }

    private void drawHeader(DrawContext ctx, float alpha) {
        int hx = contentX, hy = py + 10;
        Ui.text(ctx, Ui.big(tab.title), hx, hy, Ui.alpha(Ui.TEXT, alpha));
        Ui.text(ctx, Ui.t(tab.subtitle), hx, hy + 13, Ui.alpha(Ui.MUTED, alpha));

        // connection chip
        boolean connected = SpotifyAuth.isLoggedIn();
        String label = connected ? "Spotify connected" : "Not connected";
        Text t = Ui.t(label);
        int w = textRenderer.getWidth(t) + 16;
        int x = px + pw - w - 10;
        Ui.round(ctx, x, hy + 2, w, 14, 7, Ui.alpha(connected ? 0x2A4ADE80 : 0x22FFFFFF, alpha));
        float pulse = connected ? 0.6f + 0.4f * (float) Math.sin(System.currentTimeMillis() / 300.0) : 1f;
        Ui.round(ctx, x + 5, hy + 7, 4, 4, 2, Ui.alpha(connected ? Ui.GREEN : 0xFF8C8499, alpha * pulse));
        Ui.text(ctx, t, x + 12, hy + 5, Ui.alpha(connected ? 0xFFB9F5CF : Ui.MUTED, alpha));
        String id = "chip";
        if (hover(x, hy + 2, w, 14, false)) hoveredId = id;
        hits.add(new Hit(x, hy + 2, w, 14, id, () -> switchTab(Tab.SPOTIFY), null, false));
        ctx.fill(contentX, py + 38, px + pw - 12, py + 39, Ui.alpha(0x14FFFFFF, alpha));
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
        y = slider(ctx, y, a, "Timing offset", "Lyrics early or late? Nudge them here.", (c.offsetMs > 0 ? "+" : "") + c.offsetMs + " ms",
            (c.offsetMs + 2000) / 4000f, f -> c.offsetMs = (int) (Math.round((f * 4000 - 2000) / 50.0) * 50));
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
            Ui.roundOutlined(ctx, x, yy, cw, chh, 5, Ui.alpha(Ui.mix(Ui.CARD, Ui.CARD_HOVER, h), a),
                Ui.alpha(sel ? Ui.ACCENT : Ui.STROKE, a));
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
            int fill = sel ? Ui.ACCENT_SOFT : Ui.mix(Ui.CARD, Ui.CARD_HOVER, h);
            Ui.roundOutlined(ctx, x, yy, cw, chh, 5, Ui.alpha(fill, a), Ui.alpha(sel ? 0xFFA884FF : Ui.STROKE, a));
            Text name = Ui.in(f.name(), f.source());
            Ui.textCentered(ctx, name, x + cw / 2, yy + (chh - 9) / 2, Ui.alpha(sel ? 0xFFFFFFFF : Ui.TEXT, a));
            final int idx = i;
            hits.add(new Hit(x, yy, cw, chh, id, () -> { c.font = idx; Sounds.play(Sounds.CLICK, 1.1f); }, null, true));
        }
        y += ((Fonts.ALL.length + cols - 1) / cols) * (chh + gap) + 4;

        y = section(ctx, "Size", y, a);
        y = slider(ctx, y, a, "Lyrics size", "How big the words are", Math.round(c.size * 100) + "%",
            (c.size - 0.5f) / 1.5f, f -> c.size = Math.round((0.5f + (float) f * 1.5f) * 20f) / 20f);
        y = slider(ctx, y, a, "Distance", "How far away the words float", Math.round(c.distance * 100) + "%",
            (c.distance - 0.5f) / 1.5f, f -> c.distance = Math.round((0.5f + (float) f * 1.5f) * 20f) / 20f);
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
        Ui.roundOutlined(ctx, contentX, y, contentW, ch, 6, Ui.alpha(Ui.CARD, a), Ui.alpha(Ui.STROKE, a));
        int dot = connected ? Ui.GREEN : SpotifyAuth.state == SpotifyAuth.State.FAILED ? 0xFFFF6B6B : 0xFFE8C66A;
        Ui.round(ctx, contentX + 8, y + 8, 6, 6, 3, Ui.alpha(dot, a));
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
        Ui.roundOutlined(ctx, contentX, fy, contentW, 18, 5, Ui.alpha(0xFF15121B, a), Ui.alpha(focused ? Ui.ACCENT : Ui.STROKE, a));
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
        Ui.roundOutlined(ctx, x, y, w, h, 7, Ui.alpha(Ui.CARD, a), Ui.alpha(Ui.STROKE, a));
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
        Ui.round(ctx, tx, y + 26, bw, 11, 3, Ui.alpha((bc & 0xFFFFFF) | 0x26000000, a));
        Ui.text(ctx, bt, tx + 4, y + 28, Ui.alpha(bc, a));
        Ui.text(ctx, Ui.t(Ui.time(np.positionNow()) + " / " + Ui.time(np.durationMs())), tx + bw + 6, y + 28, Ui.alpha(0xFF6E667A, a));

        // progress bar
        float prog = np.durationMs() <= 0 ? 0 : np.positionNow() / (float) np.durationMs();
        int barX = tx, barW = w - 45 - 8, barY = y + h - 4;
        ctx.fill(barX, barY, barX + barW, barY + 1, Ui.alpha(0x22FFFFFF, a));
        ctx.fill(barX, barY, barX + Math.round(barW * prog), barY + 1, Ui.alpha(Ui.ACCENT, a));

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
        int fill = primary ? Ui.mix(Ui.ACCENT_SOFT, Ui.ACCENT, h) : Ui.mix(0xFF2E2838, 0xFF3A3346, h);
        Ui.round(ctx, x, y, s, s, 5, Ui.alpha(fill, a));
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
        ctx.drawText(textRenderer, Text.literal("›"), contentX, y, Ui.alpha(Ui.ACCENT, a), false);
        Text t = Ui.t(title);
        Ui.text(ctx, t, contentX + 7, y, Ui.alpha(0xFFB9A6E0, a));
        int lx = contentX + 12 + textRenderer.getWidth(t);
        ctx.fill(lx, y + 5, contentX + contentW, y + 6, Ui.alpha(0x14FFFFFF, a));
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
        Ui.round(ctx, contentX, y, 12, 12, 6, Ui.alpha(Ui.ACCENT_SOFT, a));
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
        float k = anim(id, on ? 1f : 0f, 16f, dt);
        int sw = 24, sh = 12;
        int sx = contentX + contentW - sw - 8, sy = y + (h - sh) / 2;
        Ui.round(ctx, sx, sy, sw, sh, 6, Ui.alpha(Ui.mix(0xFF3A3346, Ui.ACCENT, k), a));
        int knob = sx + 2 + Math.round(k * (sw - 12));
        Ui.round(ctx, knob, sy + 2, 8, 8, 4, Ui.alpha(0xFFFFFFFF, a));
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
        Ui.round(ctx, sx - 2, sy - 2, segW + 4, 18, 5, Ui.alpha(0xFF15121B, a));
        int selX = sx;
        for (int i = 0; i < selected; i++) selX += widths[i];
        float hx = anim(id + ".x", selX, 18f, dt);
        float hw = anim(id + ".w", widths[selected], 18f, dt);
        Ui.round(ctx, Math.round(hx), sy, Math.round(hw), 14, 4, Ui.alpha(Ui.ACCENT_SOFT, a));
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
        ctx.fill(tx, ty - 1, tx + trackW, ty + 1, Ui.alpha(0xFF3A3346, a));
        ctx.fill(tx, ty - 1, tx + Math.round(trackW * shown), ty + 1, Ui.alpha(Ui.ACCENT, a));
        int kx = tx + Math.round(trackW * shown);
        Ui.round(ctx, kx - 4, ty - 4, 8, 8, 4, Ui.alpha(0xFFFFFFFF, a));
        if (hov) Ui.round(ctx, kx - 6, ty - 6, 12, 12, 6, Ui.alpha(0x308B5CF6, a));
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
        int fill = primary ? Ui.mix(Ui.ACCENT_SOFT, Ui.ACCENT, k) : Ui.mix(0xFF2E2838, 0xFF3A3346, k);
        Ui.roundOutlined(ctx, x, y, w, h, 4, Ui.alpha(fill, a), Ui.alpha(primary ? 0xFFA884FF : Ui.STROKE, a));
        Ui.textCentered(ctx, Ui.t(label), x + w / 2, y + (h - 8) / 2, Ui.alpha(0xFFFFFFFF, a));
        hits.add(new Hit(x, y, w, h, id, () -> { Sounds.play(Sounds.CLICK); action.run(); }, null, true));
    }

    private int rowHeight(String desc) {
        return desc == null || desc.isEmpty() ? 18 : 26;
    }

    private void rowBackground(DrawContext ctx, int y, int h, float a, float hover) {
        Ui.round(ctx, contentX, y, contentW, h, 5, Ui.alpha(Ui.mix(Ui.CARD, Ui.CARD_HOVER, hover), a));
    }

    private void rowText(DrawContext ctx, int y, float a, String label, String desc, int maxW) {
        if (desc == null || desc.isEmpty()) {
            Ui.text(ctx, Ui.t(label), contentX + 8, y + 5, Ui.alpha(Ui.TEXT, a));
        } else {
            Ui.text(ctx, Ui.t(label), contentX + 8, y + 4, Ui.alpha(Ui.TEXT, a));
            Ui.text(ctx, Ui.t(Ui.ellipsize(null, desc, maxW, Ui.FONT)), contentX + 8, y + 14, Ui.alpha(0xFF7D7589, a));
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
