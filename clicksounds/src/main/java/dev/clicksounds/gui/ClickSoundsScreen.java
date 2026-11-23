package dev.clicksounds.gui;

import dev.clicksounds.ClickConfig;
import dev.clicksounds.ClickSoundsClient;
import dev.clicksounds.audio.AudioEngine;
import dev.clicksounds.audio.ClickExtractor;
import dev.clicksounds.audio.MicRecorder;
import dev.clicksounds.audio.SampleBank;
import dev.clicksounds.audio.Wav;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ClickSoundsScreen extends Screen {
    private enum Mode { IDLE, MOUSE, KEYBOARD }

    private static final int WHITE = 0xFFFFFFFF, GRAY = 0xFFAAAAAA, GREEN = 0xFF55FF55,
        RED = 0xFFFF5555, YELLOW = 0xFFFFFF55;

    private final Screen parent;
    private final List<ClickableWidget> menuWidgets = new ArrayList<>();

    private Mode mode = Mode.IDLE;
    private MicRecorder mic;
    private final List<float[]> takes = new ArrayList<>();
    private final Set<Integer> heldKeys = new HashSet<>();
    private long pendingMark = -1, pendingEnd = -1;
    private String status = "";
    private int statusColor = GRAY;

    public ClickSoundsScreen(Screen parent) {
        super(Text.literal("Click Sounds"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        menuWidgets.clear();
        int left = this.width / 2 - 155, right = this.width / 2 + 5, w = 150, gap = 24;
        int y = Math.max(40, this.height / 2 - 86);

        add(ButtonWidget.builder(Text.literal("Record 10 mouse clicks"), b -> startRecording(Mode.MOUSE))
            .dimensions(left, y, w, 20).build());
        add(ButtonWidget.builder(Text.literal("Record 10 key presses"), b -> startRecording(Mode.KEYBOARD))
            .dimensions(right, y, w, 20).build());
        y += gap;

        add(ButtonWidget.builder(Text.literal("Test click"), b -> ClickSoundsClient.MOUSE.playRandom(ClickConfig.mouseVolume, ClickConfig.variation))
            .dimensions(left, y, 73, 20).build());
        add(ButtonWidget.builder(Text.literal("Delete"), b -> { ClickSoundsClient.MOUSE.clear(); setStatus("Mouse sounds deleted", YELLOW); })
            .dimensions(left + 77, y, 73, 20).build());
        add(ButtonWidget.builder(Text.literal("Test key"), b -> ClickSoundsClient.KEYBOARD.playRandom(ClickConfig.keyboardVolume, ClickConfig.variation))
            .dimensions(right, y, 73, 20).build());
        add(ButtonWidget.builder(Text.literal("Delete"), b -> { ClickSoundsClient.KEYBOARD.clear(); setStatus("Keyboard sounds deleted", YELLOW); })
            .dimensions(right + 77, y, 73, 20).build());
        y += gap;

        add(ButtonWidget.builder(onOff("Mouse sounds", ClickConfig.mouseEnabled), b -> {
            ClickConfig.mouseEnabled = !ClickConfig.mouseEnabled;
            b.setMessage(onOff("Mouse sounds", ClickConfig.mouseEnabled));
            ClickConfig.save();
        }).dimensions(left, y, w, 20).build());
        add(ButtonWidget.builder(onOff("Keyboard sounds", ClickConfig.keyboardEnabled), b -> {
            ClickConfig.keyboardEnabled = !ClickConfig.keyboardEnabled;
            b.setMessage(onOff("Keyboard sounds", ClickConfig.keyboardEnabled));
            ClickConfig.save();
        }).dimensions(right, y, w, 20).build());
        y += gap;

        add(new VolumeSlider(left, y, w, 20, "Mouse volume", ClickConfig.mouseVolume, v -> ClickConfig.mouseVolume = v));
        add(new VolumeSlider(right, y, w, 20, "Keyboard volume", ClickConfig.keyboardVolume, v -> ClickConfig.keyboardVolume = v));
        y += gap;

        add(ButtonWidget.builder(onOff("Extra variation", ClickConfig.variation), b -> {
            ClickConfig.variation = !ClickConfig.variation;
            b.setMessage(onOff("Extra variation", ClickConfig.variation));
            ClickConfig.save();
        }).dimensions(left, y, w, 20).build());
        add(ButtonWidget.builder(Text.literal("Open sounds folder"), b -> openFolder())
            .dimensions(right, y, w, 20).build());
        y += gap + 6;

        add(ButtonWidget.builder(Text.literal("Done"), b -> close())
            .dimensions(this.width / 2 - 100, y, 200, 20).build());

        setMenuVisible(mode == Mode.IDLE);
    }

    private void add(ClickableWidget w) {
        menuWidgets.add(addDrawableChild(w));
    }

    private static Text onOff(String label, boolean on) {
        return Text.literal(label + ": " + (on ? "ON" : "OFF"));
    }

    private void setMenuVisible(boolean visible) {
        for (ClickableWidget w : menuWidgets) {
            w.visible = visible;
            w.active = visible;
        }
    }

    private void setStatus(String s, int color) {
        status = s;
        statusColor = color;
    }

    private void openFolder() {
        try {
            Files.createDirectories(ClickSoundsClient.MOUSE.dir());
            Files.createDirectories(ClickSoundsClient.KEYBOARD.dir());
            Util.getOperatingSystem().open(ClickSoundsClient.MOUSE.dir().getParent().toFile());
        } catch (Exception e) {
            setStatus("Couldn't open folder: " + e.getMessage(), RED);
        }
    }

    // ---- recording ----

    private void startRecording(Mode m) {
        stopMic();
        mic = new MicRecorder();
        String err = mic.start();
        if (err != null) {
            mic = null;
            setStatus(err, RED);
            return;
        }
        mode = m;
        takes.clear();
        heldKeys.clear();
        pendingMark = pendingEnd = -1;
        ClickSoundsClient.recording = true;
        setMenuVisible(false);
        setStatus("", GRAY);
    }

    private void stopRecording() {
        stopMic();
        mode = Mode.IDLE;
        pendingMark = pendingEnd = -1;
        setMenuVisible(true);
    }

    private void stopMic() {
        if (mic != null) mic.stop();
        mic = null;
        ClickSoundsClient.recording = false;
    }

    /** The player just clicked / pressed a key: remember where in the mic stream that was. */
    private void markTake() {
        if (mic == null) return;
        if (pendingMark >= 0) {
            setStatus("A bit slower - wait for each one to register", YELLOW);
            return;
        }
        pendingMark = mic.now();
        pendingEnd = pendingMark + (long) (mic.rate() * 0.40f);
    }

    @Override
    public void tick() {
        if (mic == null) return;
        if (mic.error() != null) {
            setStatus(mic.error(), RED);
            stopRecording();
            return;
        }
        if (pendingMark < 0 || mic.now() < pendingEnd) return;

        // The real sound reaches us a little after the event (mic buffering), so look
        // 250 ms before to 400 ms after the mark and let the extractor find the attack.
        float rate = mic.rate();
        long from = pendingMark - (long) (rate * 0.25f);
        float[] seg = mic.extract(from, (int) (pendingEnd - from));
        pendingMark = pendingEnd = -1;

        float[] click = ClickExtractor.extract(seg, rate);
        if (click == null) {
            setStatus("Didn't hear that one - try again (mic closer / louder?)", YELLOW);
            return;
        }
        takes.add(Wav.resample(click, rate, AudioEngine.RATE));
        setStatus("Got it!", GREEN);

        if (takes.size() >= SampleBank.TAKES) {
            SampleBank bank = mode == Mode.MOUSE ? ClickSoundsClient.MOUSE : ClickSoundsClient.KEYBOARD;
            String what = mode == Mode.MOUSE ? "mouse clicks" : "key presses";
            stopRecording();
            try {
                bank.replace(takes);
                setStatus("Saved 10 " + what + "! Hit Test to hear them.", GREEN);
            } catch (Exception e) {
                ClickSoundsClient.LOG.error("Saving sounds failed", e);
                setStatus("Couldn't save: " + e.getMessage(), RED);
            }
        }
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (mode == Mode.MOUSE) { markTake(); return true; }
        if (mode == Mode.KEYBOARD) return true;
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (mode != Mode.IDLE) {
            if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
                stopRecording();
                setStatus("Recording cancelled - nothing was changed", GRAY);
                return true;
            }
            // heldKeys filters out key-repeat while a key is held down
            if (mode == Mode.KEYBOARD && heldKeys.add(input.key())) markTake();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean keyReleased(KeyInput input) {
        heldKeys.remove(input.key());
        return super.keyReleased(input);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int cx = this.width / 2;
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, 15, WHITE);

        if (mode == Mode.IDLE) {
            int top = Math.max(40, this.height / 2 - 86);
            ctx.drawCenteredTextWithShadow(this.textRenderer, count(ClickSoundsClient.MOUSE.size(), "mouse"),
                cx - 80, top - 12, GRAY);
            ctx.drawCenteredTextWithShadow(this.textRenderer, count(ClickSoundsClient.KEYBOARD.size(), "key"),
                cx + 80, top - 12, GRAY);
            ctx.drawCenteredTextWithShadow(this.textRenderer, status, cx, this.height - 24, statusColor);
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                "Key sounds play for every key, mouse sounds for every button.", cx, this.height - 12, 0xFF777777);
            return;
        }

        int y = this.height / 2 - 50;
        boolean mouse = mode == Mode.MOUSE;
        ctx.drawCenteredTextWithShadow(this.textRenderer,
            mouse ? "Click your mouse, one click at a time" : "Press W, A, S, D (one at a time, like you play)",
            cx, y, WHITE);
        ctx.drawCenteredTextWithShadow(this.textRenderer,
            "Take " + Math.min(takes.size() + 1, SampleBank.TAKES) + " of " + SampleBank.TAKES, cx, y + 14, YELLOW);

        // progress boxes
        int box = 12, spacing = 4, total = SampleBank.TAKES * (box + spacing) - spacing;
        int bx = cx - total / 2, by = y + 32;
        for (int i = 0; i < SampleBank.TAKES; i++) {
            int x0 = bx + i * (box + spacing);
            int col = i < takes.size() ? GREEN : (i == takes.size() && pendingMark >= 0 ? YELLOW : 0xFF444444);
            ctx.fill(x0, by, x0 + box, by + box, col);
        }

        // mic level meter
        int mw = 160, mx = cx - mw / 2, my = by + 26;
        float lvl = mic == null ? 0f : (float) Math.min(1.0, Math.sqrt(mic.level()) );
        ctx.fill(mx - 1, my - 1, mx + mw + 1, my + 7, 0xFF000000);
        ctx.fill(mx, my, mx + (int) (mw * lvl), my + 6, lvl > 0.95f ? RED : GREEN);
        ctx.drawCenteredTextWithShadow(this.textRenderer, "mic", cx, my + 10, GRAY);

        ctx.drawCenteredTextWithShadow(this.textRenderer, status, cx, my + 28, statusColor);
        ctx.drawCenteredTextWithShadow(this.textRenderer, "Keep it quiet otherwise.  Esc = cancel", cx, my + 42, GRAY);
    }

    private static String count(int n, String what) {
        return n == 0 ? "No " + what + " sounds yet" : n + " " + what + " sound" + (n == 1 ? "" : "s") + " saved";
    }

    @Override
    public void removed() {
        stopMic();
        ClickConfig.save();
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }
}
