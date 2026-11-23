package dev.clicksounds.gui;

import dev.clicksounds.ClickConfig;
import dev.clicksounds.ClickSoundsClient;
import dev.clicksounds.audio.AudioEngine;
import dev.clicksounds.audio.ClickExtractor;
import dev.clicksounds.audio.Genre;
import dev.clicksounds.audio.MicRecorder;
import dev.clicksounds.audio.SampleBank;
import dev.clicksounds.audio.Take;
import dev.clicksounds.audio.Wav;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static dev.clicksounds.gui.ClickSoundsScreen.*;

/**
 * Auto record: just click, scroll and type normally. Every press / release is caught and sorted
 * by itself into the right category: which button or key, and soft / normal / hard from how fast
 * you were going (the same rule playback uses). Save fills the categories that got takes.
 *
 * First it learns the mic's delay from a few slow, separate clicks; after that it knows where
 * each sound lands in the audio, so even spam clicks milliseconds apart are cut out cleanly.
 */
public class AutoRecordScreen extends Screen {
    private enum Grouping {
        SEPARATE("each separately"), WASD("W A S D separately, rest shared"), TOGETHER("all together");
        final String label;
        Grouping(String label) { this.label = label; }
    }

    private static final float POST = 0.40f;        // s waited after an input's last mark before cutting it
    private static final float ISOLATION = 0.40f;   // s of quiet needed around a click to learn the mic delay
    private static final float MAX_HOLD = 1.5f;
    private static final int LAG_SAMPLES = 9;

    private final Screen parent;
    private MicRecorder mic;
    private String micError;
    private boolean paused;
    private Grouping mouseGrouping = Grouping.SEPARATE, keyGrouping = Grouping.WASD;
    private boolean sortBySpeed = true;

    /** One input event waiting for its audio. */
    private static final class Pending {
        final boolean mouse, scroll;
        final int id;
        final String scope;
        final Genre genre;
        final long press;
        long release = -1;
        boolean released;

        Pending(boolean mouse, boolean scroll, int id, String scope, Genre genre, long press) {
            this.mouse = mouse; this.scroll = scroll; this.id = id; this.scope = scope; this.genre = genre; this.press = press;
        }

        long last() { return release >= 0 ? release : press; }
    }

    private final List<Pending> pending = new ArrayList<>();
    private final Map<Integer, Pending> heldMouse = new HashMap<>(), heldKeys = new HashMap<>();
    /** Every mark (press and release) so far, to know where the next sound begins. */
    private final List<Long> allMarks = new ArrayList<>();
    private final Map<String, Long> lastPress = new HashMap<>();
    private final List<Long> lags = new ArrayList<>();
    private long lag = -1;

    /** Category key -> takes. Key = "m|scope|GENRE" or "k|scope|GENRE"; insertion order = first seen. */
    private final Map<String, List<Take>> captured = new LinkedHashMap<>();
    private final LiveScope scope = new LiveScope(2.5f);
    private int caught, missed, full;
    private String status = "";
    private int statusColor = GRAY;

    public AutoRecordScreen(Screen parent) {
        super(Text.literal("Auto record"));
        this.parent = parent;
    }


    @Override
    protected void init() {
        if (mic == null && micError == null) {
            MicRecorder m = new MicRecorder();
            String err = m.start(ClickConfig.micDevice);
            if (err == null) mic = m; else micError = err;
        }
        ClickSoundsClient.recording = mic != null;
        int cx = this.width / 2, y = 28;
        if (mic == null) {
            addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> close()).dimensions(cx - 100, this.height / 2 + 30, 200, 20).build());
            return;
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("Buttons: " + (mouseGrouping == Grouping.TOGETHER ? "together" : "separate")), b -> {
            mouseGrouping = mouseGrouping == Grouping.TOGETHER ? Grouping.SEPARATE : Grouping.TOGETHER;
            clearAndInit();
        }).dimensions(cx - 155, y, 100, 20).tooltip(Tooltip.of(Text.literal("Mouse buttons " + (mouseGrouping == Grouping.TOGETHER
            ? "all go into the shared mouse sounds" : "each get their own sounds (Left, Right, Middle ...)")
            + "\nThe scroll wheel always gets its own."))).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Keys: " + switch (keyGrouping) {
            case SEPARATE -> "each own"; case WASD -> "WASD own"; case TOGETHER -> "together"; }), b -> {
            keyGrouping = Grouping.values()[(keyGrouping.ordinal() + 1) % Grouping.values().length];
            clearAndInit();
        }).dimensions(cx - 50, y, 100, 20).tooltip(Tooltip.of(Text.literal("Keys: " + keyGrouping.label))).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Speed sort: " + (sortBySpeed ? "ON" : "OFF")), b -> {
            sortBySpeed = !sortBySpeed;
            clearAndInit();
        }).dimensions(cx + 55, y, 100, 20).tooltip(Tooltip.of(Text.literal(sortBySpeed
            ? "Fast presses (< " + ClickConfig.softMs + " ms apart) become soft clicks, presses after a pause (> "
                + ClickConfig.hardMs + " ms) hard clicks, the rest normal clicks"
            : "Everything goes into normal clicks"))).build());

        int by = Math.min(this.height - 24, 214);
        int total = totalTakes();
        ButtonWidget save = ButtonWidget.builder(Text.literal("Save " + total + " take" + (total == 1 ? "" : "s")), b -> save())
            .dimensions(cx - 155, by, 100, 20).tooltip(Tooltip.of(Text.literal(
                "Replaces only the categories you recorded, in mouse pack '" + ClickSoundsClient.MOUSE.pack()
                    + "' / keyboard pack '" + ClickSoundsClient.KEYBOARD.pack() + "'"))).build();
        save.active = total > 0;
        addDrawableChild(save);
        addDrawableChild(ButtonWidget.builder(Text.literal(paused ? "Resume" : "Pause"), b -> { paused = !paused; clearAndInit(); })
            .dimensions(cx - 50, by, 100, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> { report("Auto record cancelled, nothing was changed", GRAY); close(); })
            .dimensions(cx + 55, by, 100, 20).build());
    }

    private int totalTakes() {
        int n = 0;
        for (List<Take> l : captured.values()) n += l.size();
        return n;
    }


    private boolean overWidget(double x, double y) {
        for (Element e : children()) if (e.isMouseOver(x, y)) return true;
        return false;
    }

    private Genre genreFor(String family) {
        long now = mic.now();
        Long prev = lastPress.put(family, now);
        if (!sortBySpeed) return Genre.NORMAL;
        double gapMs = prev == null ? Double.MAX_VALUE : (now - prev) * 1000.0 / mic.rate();
        return gapMs < ClickConfig.softMs ? Genre.SOFT : gapMs > ClickConfig.hardMs ? Genre.HARD : Genre.NORMAL;
    }

    private void onPress(boolean mouse, int id, String name) {
        String sc;
        if (mouse) sc = mouseGrouping == Grouping.TOGETHER ? SampleBank.ALL : name;
        else sc = switch (keyGrouping) {
            case SEPARATE -> name == null ? SampleBank.ALL : name;
            case WASD -> name != null && "wasd".contains(name) && name.length() == 1 ? name : SampleBank.ALL;
            case TOGETHER -> SampleBank.ALL;
        };
        Pending p = new Pending(mouse, false, id, sc, genreFor(mouse ? "mouse" : "keys"), mic.now());
        pending.add(p);
        allMarks.add(p.press);
        (mouse ? heldMouse : heldKeys).put(id, p);
        scope.mark(p.press, Waveforms.PRESS);
    }

    private void onRelease(boolean mouse, int id) {
        Pending p = (mouse ? heldMouse : heldKeys).remove(id);
        if (p == null) return;
        p.release = mic.now();
        p.released = true;
        allMarks.add(p.release);
        scope.mark(p.release, Waveforms.RELEASE);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (mic == null || overWidget(click.x(), click.y())) return super.mouseClicked(click, doubled);
        if (!paused) onPress(true, click.button(), ClickSoundsClient.buttonName(click.button()));
        return true;
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (mic != null && heldMouse.containsKey(click.button())) {
            onRelease(true, click.button());
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (mic == null || paused || vertical == 0) return true;
        Pending p = new Pending(true, true, -1, SampleBank.SCROLL, genreFor("scroll"), mic.now());
        p.released = true;
        pending.add(p);
        allMarks.add(p.press);
        scope.mark(p.press, Waveforms.PRESS);
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
            report("Auto record cancelled, nothing was changed", GRAY);
            close();
            return true;
        }
        if (mic == null || paused) return true;
        if (!heldKeys.containsKey(input.key())) onPress(false, input.key(), ClickSoundsClient.keyName(input)); // ignores key-repeat
        return true;
    }

    @Override
    public boolean keyReleased(KeyInput input) {
        if (mic != null) onRelease(false, input.key());
        return true;
    }


    @Override
    public void tick() {
        if (mic == null) return;
        if (mic.error() != null) {
            micError = mic.error();
            stopMic();
            clearAndInit();
            return;
        }
        float rate = mic.rate();
        long now = mic.now(), post = (long) (rate * POST);
        while (!pending.isEmpty()) {
            Pending p = pending.get(0);
            if (!p.released) {
                if (now - p.press < rate * MAX_HOLD) break; // still held: wait (keeps order)
                p.released = true; // held for ages: just the press
                (p.mouse ? heldMouse : heldKeys).remove(p.id);
            }
            if (now < p.last() + post) break;
            pending.remove(0);
            process(p, rate);
        }
        allMarks.removeIf(m -> m < now - (long) (rate * 3)); // keep the list short
    }

    private void process(Pending p, float rate) {
        long iso = (long) (rate * ISOLATION);
        long next = -1, prev = -1;
        for (long m : allMarks) {
            if (m > p.press && m != p.release && (next < 0 || m < next)) next = m;
            if (m < p.press && (prev < 0 || m > prev)) prev = m;
        }
        ClickExtractor.Result res;
        long from;
        boolean isolated = (prev < 0 || p.press - prev > iso) && (next < 0 || next - p.last() > iso);
        if (isolated) {
            // a clean, lone click: the regular extractor finds it, and it tells us the mic delay
            from = p.press - (long) (rate * 0.25f);
            float[] seg = mic.extract(from, (int) (p.last() + (long) (rate * POST) - from));
            res = ClickExtractor.extract(seg, from, p.press, p.release, rate);
            if (res != null) learnLag(from + res.start() + (long) (rate * 0.003f) - p.press);
        } else if (lag < 0) {
            missed++;
            setStatus("Still learning your mic's delay. Do a few slow, separate clicks first.", YELLOW);
            return;
        } else {
            from = p.press + lag - (long) (rate * 0.05f);
            long to = p.last() + lag + (long) (rate * 0.25f);
            float[] seg = mic.extract(from, (int) (to - from));
            res = ClickExtractor.extractAt(seg, from, p.press + lag, p.release >= 0 ? p.release + lag : -1,
                next >= 0 ? next + lag : -1, rate);
        }
        if (res == null) {
            missed++;
            return;
        }
        Take t = res.take();
        if (p.scroll) t = new Take(t.press(), null); // a wheel notch has no release
        String key = (p.mouse ? "m|" : "k|") + p.scope + "|" + p.genre.name();
        List<Take> list = captured.computeIfAbsent(key, k -> new ArrayList<>());
        if (list.size() >= SampleBank.TAKES) {
            full++;
            return;
        }
        list.add(new Take(Wav.resample(t.press(), rate, AudioEngine.RATE),
            t.release() == null ? null : Wav.resample(t.release(), rate, AudioEngine.RATE)));
        caught++;
        scope.region(from + res.start(), from + res.end(), genreColor(p.genre));
        if (lag >= 0) setStatus("", GRAY);
        clearAndInit(); // refresh the Save button's count
    }

    private void learnLag(long sample) {
        long max = (long) (mic.rate() * 0.3f);
        if (sample < -(long) (mic.rate() * 0.05f) || sample > max) return;
        lags.add(sample);
        if (lags.size() > LAG_SAMPLES) lags.remove(0);
        long[] sorted = lags.stream().mapToLong(Long::longValue).sorted().toArray();
        lag = sorted[sorted.length / 2]; // median: one odd measurement can't throw it off
    }

    private static int genreColor(Genre g) {
        return switch (g) {
            case SOFT -> 0x3055AAFF;
            case NORMAL -> 0x3055FF55;
            case HARD -> 0x40FF7755;
        };
    }

    private void save() {
        stopMic();
        int cats = 0, takes = 0;
        StringBuilder err = new StringBuilder();
        for (Map.Entry<String, List<Take>> e : captured.entrySet()) {
            String[] k = e.getKey().split("\\|", -1);
            SampleBank bank = k[0].equals("m") ? ClickSoundsClient.MOUSE : ClickSoundsClient.KEYBOARD;
            try {
                bank.replaceAll(k[1], Genre.valueOf(k[2]), e.getValue());
                cats++;
                takes += e.getValue().size();
            } catch (Exception ex) {
                ClickSoundsClient.LOG.error("Saving {} failed", e.getKey(), ex);
                err.append(ex.getMessage());
            }
        }
        if (err.length() > 0) report("Saved " + takes + " takes, but some failed: " + err, RED);
        else report("Saved " + takes + " takes into " + cats + " categor" + (cats == 1 ? "y" : "ies") + ". Open a clickpack to see them.", GREEN);
        close();
    }

    private void report(String s, int color) {
        if (parent instanceof ClickSoundsScreen cs) cs.setStatus(s, color);
    }

    private void setStatus(String s, int color) {
        status = s;
        statusColor = color;
    }

    private void stopMic() {
        if (mic != null) mic.stop();
        mic = null;
        ClickSoundsClient.recording = false;
    }


    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int cx = this.width / 2;
        ctx.drawCenteredTextWithShadow(this.textRenderer, "Auto record: just click, scroll and type normally", cx, 4, WHITE);
        if (mic == null) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, micError == null ? "" : micError, cx, this.height / 2, RED);
            return;
        }
        String sub = paused ? "Paused. Press Resume to keep going."
            : lag < 0 ? "Learning your mic's delay: click a few times slowly, with a pause in between"
            : String.format(Locale.ROOT, "Mic delay %.0f ms  |  %d caught, %d missed%s", lag * 1000f / mic.rate(), caught, missed,
                full > 0 ? ", " + full + " extra (category full)" : "");
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.textRenderer.trimToWidth(sub, this.width - 8), cx, 16,
            paused ? YELLOW : lag < 0 ? YELLOW : GRAY);

        int w = Math.min(310, this.width - 10);
        int y = scope.draw(ctx, this.textRenderer, mic, cx - w / 2, 52, w, 34);

        // categories table
        y += 6;
        int x0 = cx - 155, colW = 70;
        String[] heads = {"", "Clicks", "Soft", "Hard"};
        ctx.drawTextWithShadow(this.textRenderer, "Category", x0, y, DARK_GRAY);
        for (int c = 1; c < 4; c++) ctx.drawTextWithShadow(this.textRenderer, heads[c], x0 + 100 + (c - 1) * colW, y, DARK_GRAY);
        y += 12;
        List<String> rows = new ArrayList<>();
        for (String k : captured.keySet()) {
            String row = k.substring(0, k.lastIndexOf('|'));
            if (!rows.contains(row)) rows.add(row);
        }
        int maxRows = Math.max(1, (Math.min(this.height - 24, 214) - 4 - y) / 12);
        if (rows.isEmpty()) ctx.drawTextWithShadow(this.textRenderer, "Nothing yet, go ahead and click", x0, y, DARK_GRAY);
        for (int i = 0; i < Math.min(rows.size(), maxRows); i++) {
            String row = rows.get(i);
            String[] k = row.split("\\|", -1);
            boolean mouse = k[0].equals("m");
            String label = (mouse ? "Mouse " : "Key ") + (k[1].isEmpty() ? (mouse ? "(all)" : "(all keys)") : SampleBank.keyLabel(k[1]));
            ctx.drawTextWithShadow(this.textRenderer, this.textRenderer.trimToWidth(label, 96), x0, y, WHITE);
            Genre[] gs = {Genre.NORMAL, Genre.SOFT, Genre.HARD};
            for (int c = 0; c < 3; c++) {
                List<Take> l = captured.get(row + "|" + gs[c].name());
                int n = l == null ? 0 : l.size();
                int bx = x0 + 100 + c * colW, bw = 40;
                ctx.fill(bx, y + 1, bx + bw, y + 7, 0xFF222222);
                if (n > 0) ctx.fill(bx, y + 1, bx + bw * n / SampleBank.TAKES, y + 7, n >= SampleBank.TAKES ? GREEN : 0xFF5FB4FF);
                ctx.drawTextWithShadow(this.textRenderer, n + "", bx + bw + 4, y, n >= SampleBank.TAKES ? GREEN : n > 0 ? WHITE : 0xFF555555);
            }
            y += 12;
        }
        if (rows.size() > maxRows)
            ctx.drawTextWithShadow(this.textRenderer, "+" + (rows.size() - maxRows) + " more", x0, y, DARK_GRAY);
        if (!status.isEmpty())
            ctx.drawCenteredTextWithShadow(this.textRenderer, status, cx, Math.min(this.height - 24, 214) - 11, statusColor);
    }

    @Override
    public void removed() {
        stopMic();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // Esc is handled in keyPressed
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }
}
