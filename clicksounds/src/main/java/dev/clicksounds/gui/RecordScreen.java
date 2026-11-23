package dev.clicksounds.gui;

import dev.clicksounds.ClickConfig;
import dev.clicksounds.ClickSoundsClient;
import dev.clicksounds.audio.AudioEngine;
import dev.clicksounds.audio.ClickExtractor;
import dev.clicksounds.audio.MicRecorder;
import dev.clicksounds.audio.SampleBank;
import dev.clicksounds.audio.Take;
import dev.clicksounds.audio.Wav;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static dev.clicksounds.gui.ClickSoundsScreen.*;

/**
 * Records either a full set of 10 takes ({@code slot == -1}) or redoes a single take.
 * Each take is one press + release; the screen marks both moments in the mic stream and
 * the extractor cuts the two sounds out.
 */
public class RecordScreen extends Screen {
    public interface StatusSink { void set(String text, int color); }

    private enum Phase { READY, HOLDING, WAITING }

    private static final float MAX_HOLD = 1.5f;   // s; longer holds just record the press
    private static final float PRE = 0.25f;       // s of audio kept before the press mark
    private static final float POST = 0.40f;      // s of audio waited for after the last mark
    private static final float SCOPE_SECONDS = 2.0f;
    private static final int THUMB_W = 28, THUMB_H = 16;

    private final Screen parent;
    private final boolean mouse;
    private final int slot;
    private final SampleBank bank;
    private final StatusSink onDone;

    private MicRecorder mic;
    private String micError;
    private final List<Take> takes = new ArrayList<>();
    private final Set<Integer> heldKeys = new HashSet<>();
    private Phase phase = Phase.READY;
    private int heldId;
    private long pressMark, releaseMark, waitEnd;
    private String status = "";
    private int statusColor = GRAY;

    // live wave decorations, in absolute mic frames
    private record Mark(long frame, boolean release) {}
    private record Region(long start, long end) {}
    private final List<Mark> marks = new ArrayList<>();
    private final List<Region> regions = new ArrayList<>();
    private final List<Waveforms.TakeShape> thumbs = new ArrayList<>();
    private float scopePeak = 0.05f;
    private long lastRejected = -1;

    public RecordScreen(Screen parent, boolean mouse, int slot, StatusSink onDone) {
        super(Text.literal(slot < 0
            ? (mouse ? "Recording 10 mouse clicks" : "Recording 10 key presses")
            : (mouse ? "Redo mouse take " : "Redo keyboard take ") + (slot + 1)));
        this.parent = parent;
        this.mouse = mouse;
        this.slot = slot;
        this.bank = mouse ? ClickSoundsClient.MOUSE : ClickSoundsClient.KEYBOARD;
        this.onDone = onDone;
    }

    private int needed() {
        return slot < 0 ? SampleBank.TAKES : 1;
    }

    @Override
    protected void init() {
        if (mic == null && micError == null) {
            MicRecorder m = new MicRecorder();
            String err = m.start(ClickConfig.micDevice);
            if (err == null) mic = m;
            else micError = err;
        }
        ClickSoundsClient.recording = mic != null;
        if (mic == null) {
            addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> cancel(null))
                .dimensions(this.width / 2 - 100, this.height / 2 + 30, 200, 20).build());
        }
    }

    // ---- input ----

    private void onPress(int id) {
        if (mic == null) return;
        if (phase != Phase.READY) {
            if (phase == Phase.WAITING) setStatus("A bit slower - wait for each one to register", YELLOW);
            return;
        }
        pressMark = mic.now();
        marks.add(new Mark(pressMark, false));
        heldId = id;
        phase = Phase.HOLDING;
    }

    private void onRelease(int id) {
        if (mic == null || phase != Phase.HOLDING || id != heldId) return;
        releaseMark = mic.now();
        marks.add(new Mark(releaseMark, true));
        waitEnd = releaseMark + (long) (mic.rate() * POST);
        phase = Phase.WAITING;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (mic == null) return super.mouseClicked(click, doubled);
        if (mouse) onPress(click.button());
        return true;
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (mic == null) return super.mouseReleased(click);
        if (mouse) onRelease(click.button());
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
            cancel(mic == null ? null : "Recording cancelled - nothing was changed");
            return true;
        }
        // heldKeys filters out key-repeat while a key is held down
        if (!mouse && heldKeys.add(input.key())) onPress(input.key());
        return true;
    }

    @Override
    public boolean keyReleased(KeyInput input) {
        heldKeys.remove(input.key());
        if (!mouse) onRelease(input.key());
        return true;
    }

    // ---- processing ----

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
        long now = mic.now();
        if (phase == Phase.HOLDING && now - pressMark > rate * MAX_HOLD) {
            releaseMark = -1; // held too long: keep just the press
            waitEnd = pressMark + (long) (rate * POST);
            phase = Phase.WAITING;
        }
        if (phase != Phase.WAITING || now < waitEnd) return;

        long from = pressMark - (long) (rate * PRE);
        float[] seg = mic.extract(from, (int) (waitEnd - from));
        boolean hadRelease = releaseMark >= 0;
        ClickExtractor.Result res = ClickExtractor.extract(seg, from, pressMark, releaseMark, rate);
        phase = Phase.READY;

        if (res == null) {
            lastRejected = pressMark;
            setStatus("Didn't hear that one - try again (mic closer / louder?)", YELLOW);
            return;
        }
        Take t = res.take();
        regions.add(new Region(from + res.start(), from + res.end()));
        Take take = new Take(Wav.resample(t.press(), rate, AudioEngine.RATE),
            t.release() == null ? null : Wav.resample(t.release(), rate, AudioEngine.RATE));
        takes.add(take);
        thumbs.add(new Waveforms.TakeShape(take, THUMB_W - 2));
        if (hadRelease && t.release() == null) setStatus("Got the press (didn't hear the release)", GREEN);
        else setStatus("Got it!", GREEN);

        if (takes.size() >= needed()) finish();
    }

    private void finish() {
        stopMic();
        String what = mouse ? "mouse" : "keyboard";
        TakesScreen review = parent instanceof TakesScreen ts ? ts : new TakesScreen(parent, mouse);
        try {
            if (slot < 0) {
                bank.replaceAll(takes);
                review.setStatus("Saved 10 " + what + " takes! Click a wave to hear it.", GREEN);
            } else {
                bank.replaceTake(slot, takes.get(0));
                int idx = Math.min(slot, bank.size() - 1);
                review.setStatus("Saved " + what + " take " + (idx + 1), GREEN);
                // let them hear the new take right away (the mic is already off)
                bank.preview(idx, Math.max(0.3f, mouse ? ClickConfig.mouseVolume : ClickConfig.keyboardVolume), 150);
            }
            report("Saved " + what + " takes", GREEN);
        } catch (Exception e) {
            ClickSoundsClient.LOG.error("Saving sounds failed", e);
            review.setStatus("Couldn't save: " + e.getMessage(), RED);
        }
        client.setScreen(review);
    }

    private void cancel(String message) {
        stopMic();
        if (message != null) report(message, GRAY);
        else if (micError != null) report(micError, RED);
        client.setScreen(parent);
    }

    private void report(String text, int color) {
        if (onDone != null) onDone.set(text, color);
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

    // ---- drawing ----

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int y = this.height / 2 - 60;
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, Math.max(6, y - 26), WHITE);

        if (mic == null) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, micError == null ? "" : micError, cx, y + 10, RED);
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                "Check the Mic setting, or that Java/the launcher may use the microphone.", cx, y + 24, GRAY);
            return;
        }

        ctx.drawCenteredTextWithShadow(this.textRenderer,
            mouse ? "Click your mouse like you normally do - press and let go"
                  : "Press W, A, S or D like you play - press and let go", cx, y, WHITE);
        ctx.drawCenteredTextWithShadow(this.textRenderer, needed() == 1 ? "One take"
            : "Take " + Math.min(takes.size() + 1, needed()) + " of " + needed(), cx, y + 14, YELLOW);

        // progress: a mini wave per captured take
        int n = needed(), spacing = 3, total = n * (THUMB_W + spacing) - spacing;
        int bx = cx - total / 2, by = y + 30;
        for (int i = 0; i < n; i++) {
            int x0 = bx + i * (THUMB_W + spacing);
            boolean pending = i == takes.size() && phase != Phase.READY;
            int border = i < takes.size() ? 0xFF55AA55 : pending ? YELLOW : 0xFF444444;
            ctx.fill(x0, by, x0 + THUMB_W, by + THUMB_H, 0xFF0E0E12);
            ctx.drawStrokedRectangle(x0, by, THUMB_W, THUMB_H, border);
            if (i < thumbs.size()) {
                Waveforms.TakeShape sh = thumbs.get(i);
                float pk = sh.take.peak();
                sh.draw(ctx, x0 + 1, by + 1, THUMB_H - 2, pk > 0 ? 0.95f / pk : 1f, -1);
            }
        }

        int my = drawScope(ctx, cx, by + THUMB_H + 10);
        String micName = ClickConfig.micDevice.isEmpty() ? "default mic" : ClickConfig.micDevice;
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.textRenderer.trimToWidth(micName, 300), cx, my + 3, DARK_GRAY);
        my += 3;

        ctx.drawCenteredTextWithShadow(this.textRenderer, status, cx, my + 14, statusColor);
        ctx.drawCenteredTextWithShadow(this.textRenderer, "Keep it quiet otherwise.  Esc = cancel", cx, my + 28, DARK_GRAY);
    }

    /** Live scrolling sound wave of the mic, with your clicks marked and captured takes highlighted. @return bottom y */
    private int drawScope(DrawContext ctx, int cx, int y) {
        int w = Math.min(300, this.width - 20), h = 44, x = cx - w / 2;
        float rate = mic.rate();
        long now = mic.now();
        int frames = (int) (rate * SCOPE_SECONDS);
        long from = now - frames;

        ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF333333);
        ctx.fill(x, y, x + w, y + h, 0xFF0A0A0D);

        for (Region r : regions) {
            int a = (int) ((r.start() - from) * w / frames), b = (int) ((r.end() - from) * w / frames);
            if (b < 0 || a > w) continue;
            ctx.fill(x + Math.max(0, a), y, x + Math.min(w, Math.max(a + 1, b)), y + h, 0x3055FF55);
        }
        if (lastRejected >= 0) {
            int a = (int) ((lastRejected - from) * w / frames);
            if (a >= 0 && a <= w) ctx.fill(x + a, y, x + Math.min(w, a + 2), y + h, 0x60FF5555);
        }

        float[] env = mic.envelope(now, frames, w);
        float visible = 0f;
        for (float v : env) visible = Math.max(visible, v);
        scopePeak = Math.max(0.02f, Math.max(visible, scopePeak * 0.985f)); // auto-zoom, slow release
        ctx.fill(x, y + h / 2, x + w, y + h / 2 + 1, 0xFF222222);
        Waveforms.bars(ctx, env, x, y + 1, h - 2, 0.9f / scopePeak, 0xFF7CE07C);

        for (Mark m : marks) {
            int a = (int) ((m.frame() - from) * w / frames);
            if (a < 0 || a >= w) continue;
            int col = m.release() ? Waveforms.RELEASE : Waveforms.PRESS;
            ctx.fill(x + a, y, x + a + 1, y + 4, col);
            ctx.fill(x + a, y + h - 4, x + a + 1, y + h, col);
        }
        marks.removeIf(m -> m.frame() < from - rate);
        regions.removeIf(r -> r.end() < from - rate);

        float lvl = (float) Math.min(1.0, Math.sqrt(mic.level()));
        if (lvl > 0.95f) ctx.drawTextWithShadow(this.textRenderer, "TOO LOUD", x + w - this.textRenderer.getWidth("TOO LOUD") - 3, y + 3, RED);
        return y + h + 1;
    }

    @Override
    public void removed() {
        stopMic();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // handled in keyPressed so it can report back
    }
}
