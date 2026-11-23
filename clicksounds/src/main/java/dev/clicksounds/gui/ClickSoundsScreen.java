package dev.clicksounds.gui;

import dev.clicksounds.ClickConfig;
import dev.clicksounds.ClickSoundsClient;
import dev.clicksounds.audio.AudioEngine;
import dev.clicksounds.audio.Devices;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Main menu: record, listen, volumes, devices. */
public class ClickSoundsScreen extends Screen {
    static final int WHITE = 0xFFFFFFFF, GRAY = 0xFFAAAAAA, DARK_GRAY = 0xFF777777, GREEN = 0xFF55FF55,
        RED = 0xFFFF5555, YELLOW = 0xFFFFFF55;

    private final Screen parent;
    private String status = "";
    private int statusColor = GRAY;
    private int top;

    public ClickSoundsScreen(Screen parent) {
        super(Text.literal("Click Sounds"));
        this.parent = parent;
    }

    public void setStatus(String s, int color) {
        status = s;
        statusColor = color;
    }

    @Override
    protected void init() {
        int left = this.width / 2 - 155, right = this.width / 2 + 5, w = 150, gap = 24;
        int y = top = Math.max(38, this.height / 2 - 92);

        addDrawableChild(ButtonWidget.builder(Text.literal("Record 10 mouse clicks"),
            b -> client.setScreen(new RecordScreen(this, true, -1, this::setStatus)))
            .dimensions(left, y, w, 20).tooltip(Tooltip.of(Text.literal(
                "Records press + release of 10 clicks. Replaces your current mouse sounds when done.")))
            .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Record 10 key presses"),
            b -> client.setScreen(new RecordScreen(this, false, -1, this::setStatus)))
            .dimensions(right, y, w, 20).tooltip(Tooltip.of(Text.literal(
                "Press W, A, S, D 10 times. These are used for every key on the keyboard.")))
            .build());
        y += gap;

        addDrawableChild(previewButton(left, y, true));
        addDrawableChild(ButtonWidget.builder(Text.literal("Mouse takes + waves..."),
            b -> client.setScreen(new TakesScreen(this, true))).dimensions(left + 24, y, w - 24, 20)
            .tooltip(Tooltip.of(Text.literal("See the sound wave of each take, play them, redo bad ones."))).build());
        addDrawableChild(previewButton(right, y, false));
        addDrawableChild(ButtonWidget.builder(Text.literal("Key takes + waves..."),
            b -> client.setScreen(new TakesScreen(this, false))).dimensions(right + 24, y, w - 24, 20)
            .tooltip(Tooltip.of(Text.literal("See the sound wave of each take, play them, redo bad ones."))).build());
        y += gap;

        addDrawableChild(toggle(left, y, w, "Mouse sounds", () -> ClickConfig.mouseEnabled, v -> ClickConfig.mouseEnabled = v, null));
        addDrawableChild(toggle(right, y, w, "Keyboard sounds", () -> ClickConfig.keyboardEnabled, v -> ClickConfig.keyboardEnabled = v, null));
        y += gap;

        addDrawableChild(new VolumeSlider(left, y, w, 20, "Mouse volume", ClickConfig.mouseVolume,
            v -> ClickConfig.mouseVolume = v, () -> ClickSoundsClient.MOUSE.previewRandom(ClickConfig.mouseVolume, ClickConfig.variation, ClickConfig.releaseSounds)));
        addDrawableChild(new VolumeSlider(right, y, w, 20, "Keyboard volume", ClickConfig.keyboardVolume,
            v -> ClickConfig.keyboardVolume = v, () -> ClickSoundsClient.KEYBOARD.previewRandom(ClickConfig.keyboardVolume, ClickConfig.variation, ClickConfig.releaseSounds)));
        y += gap;

        addDrawableChild(toggle(left, y, w, "Release sounds", () -> ClickConfig.releaseSounds, v -> ClickConfig.releaseSounds = v,
            "Also play the 'let go' sound when you release a button or key, like a real click."));
        addDrawableChild(toggle(right, y, w, "Extra variation", () -> ClickConfig.variation, v -> ClickConfig.variation = v,
            "Tiny random pitch and volume change on every play, so it never sounds like a loop."));
        y += gap;

        addDrawableChild(deviceButton(left, y, w, "Mic", Devices.microphones(), () -> ClickConfig.micDevice,
            v -> ClickConfig.micDevice = v, "Microphone used for recording. Click to switch."));
        addDrawableChild(deviceButton(right, y, w, "Output", Devices.outputs(), () -> ClickConfig.outputDevice,
            v -> { ClickConfig.outputDevice = v; AudioEngine.reopen(); },
            "Where the click sounds play. Pick a virtual audio cable to send them only to OBS / Discord."));
        y += gap;

        addDrawableChild(ButtonWidget.builder(Text.literal("Open sounds folder"), b -> openFolder())
            .dimensions(left, y, w, 20).tooltip(Tooltip.of(Text.literal(
                "No mic? Drop your own .wav files in the mouse / keyboard folders and restart."))).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close()).dimensions(right, y, w, 20).build());
    }

    /** Plays a random take exactly like a real click would (random take + variation + release). */
    private static ButtonWidget previewButton(int x, int y, boolean mouse) {
        dev.clicksounds.audio.SampleBank bank = mouse ? ClickSoundsClient.MOUSE : ClickSoundsClient.KEYBOARD;
        ButtonWidget b = ButtonWidget.builder(Text.literal("\u25B6"), btn -> {
            float vol = Math.max(0.3f, mouse ? ClickConfig.mouseVolume : ClickConfig.keyboardVolume);
            bank.previewRandom(vol, ClickConfig.variation, ClickConfig.releaseSounds);
        }).dimensions(x, y, 20, 20).tooltip(Tooltip.of(Text.literal(
            "Preview: plays a random " + (mouse ? "click" : "key press") + " the way it sounds in game"))).build();
        b.active = bank.size() > 0;
        return b;
    }

    interface BoolGetter { boolean get(); }
    interface BoolSetter { void set(boolean v); }

    private static ButtonWidget toggle(int x, int y, int w, String label, BoolGetter get, BoolSetter set, String tip) {
        ButtonWidget.Builder b = ButtonWidget.builder(onOff(label, get.get()), btn -> {
            set.set(!get.get());
            btn.setMessage(onOff(label, get.get()));
            ClickConfig.save();
        }).dimensions(x, y, w, 20);
        if (tip != null) b.tooltip(Tooltip.of(Text.literal(tip)));
        return b.build();
    }

    private static Text onOff(String label, boolean on) {
        return Text.literal(label + ": " + (on ? "ON" : "OFF"));
    }

    private static ButtonWidget deviceButton(int x, int y, int w, String label, List<String> found,
                                             java.util.function.Supplier<String> get,
                                             java.util.function.Consumer<String> set, String tip) {
        List<String> options = new ArrayList<>();
        options.add("");
        options.addAll(found);
        if (!options.contains(get.get())) options.add(get.get()); // saved device currently unplugged
        return ButtonWidget.builder(deviceLabel(label, get.get()), btn -> {
            int i = options.indexOf(get.get());
            set.accept(options.get((i + 1) % options.size()));
            btn.setMessage(deviceLabel(label, get.get()));
            ClickConfig.save();
        }).dimensions(x, y, w, 20).tooltip(Tooltip.of(Text.literal(tip))).build();
    }

    private static Text deviceLabel(String label, String device) {
        return Text.literal(label + ": " + (device.isEmpty() ? "Default" : device));
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

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int cx = this.width / 2;
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, Math.max(4, top - 30), WHITE);
        ctx.drawCenteredTextWithShadow(this.textRenderer, summary(ClickSoundsClient.MOUSE, "mouse"), cx - 80, top - 12, GRAY);
        ctx.drawCenteredTextWithShadow(this.textRenderer, summary(ClickSoundsClient.KEYBOARD, "key"), cx + 80, top - 12, GRAY);
        int bottom = top + 7 * 24;
        ctx.drawCenteredTextWithShadow(this.textRenderer, status, cx, Math.min(this.height - 22, bottom + 2), statusColor);
        ctx.drawCenteredTextWithShadow(this.textRenderer,
            "Key sounds play for every key, mouse sounds for every button.", cx, Math.min(this.height - 11, bottom + 14), DARK_GRAY);
    }

    static String summary(dev.clicksounds.audio.SampleBank bank, String what) {
        int n = bank.size();
        if (n == 0) return "No " + what + " sounds yet";
        return n + " " + what + " take" + (n == 1 ? "" : "s") + (bank.hasReleases() ? " (press + release)" : "");
    }

    @Override
    public void removed() {
        ClickConfig.save();
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }
}
