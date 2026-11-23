package dev.clicksounds.gui;

import dev.clicksounds.ClickConfig;
import dev.clicksounds.ClickSoundsClient;
import dev.clicksounds.audio.Desk;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import static dev.clicksounds.gui.ClickSoundsScreen.*;

/** Release / variation, when soft and hard clicks kick in, desk stereo, scroll and menu sounds. */
public class SoundOptionsScreen extends Screen {
    private final Screen parent;
    private int top;
    private long lastPreview;

    public SoundOptionsScreen(Screen parent) {
        super(Text.literal("Sound options"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = this.width / 2 - 155, right = this.width / 2 + 5, w = 150, gap = 24;
        int y = top = Math.max(32, this.height / 2 - 96);
        addDrawableChild(toggle(left, y, w, "Release sounds", () -> ClickConfig.releaseSounds, v -> ClickConfig.releaseSounds = v,
            "Also play a release sound when you let go of a button or key, like a real click."));
        addDrawableChild(toggle(right, y, w, "Extra variation", () -> ClickConfig.variation, v -> ClickConfig.variation = v,
            "Tiny random pitch and volume change on every play, so it never sounds like a loop."));
        y += gap;
        addDrawableChild(toggle(left, y, w, "Soft clicks", () -> ClickConfig.softClicks, v -> ClickConfig.softClicks = v,
            "When you click fast (spam), use the pack's soft clicks, if it has any. Packs without them just use normal clicks."));
        addDrawableChild(new MsSlider(right, y, w, 20, "Faster than", 20, 500, ClickConfig.softMs, v -> ClickConfig.softMs = v));
        y += gap;
        addDrawableChild(toggle(left, y, w, "Hard clicks", () -> ClickConfig.hardClicks, v -> ClickConfig.hardClicks = v,
            "For the first click after a pause, use the pack's hard clicks, if it has any. Packs without them just use normal clicks."));
        addDrawableChild(new MsSlider(right, y, w, 20, "Pause over", 200, 5000, ClickConfig.hardMs, v -> ClickConfig.hardMs = v));
        y += gap + 6;

        ButtonWidget desk = toggle(left, y, w, "Desk stereo", () -> ClickConfig.deskStereo, v -> { ClickConfig.deskStereo = v; previewDesk(); },
            "Like a real desk: the keyboard on the left (every key at its own spot, so A is further left than L), the mouse on the right.");
        addDrawableChild(desk);
        addDrawableChild(new SliderWidget(right, y, w, 20, Text.empty(), ClickConfig.stereoWidth) {
            { updateMessage(); }
            @Override protected void updateMessage() { setMessage(Text.literal("Desk width: " + Math.round(value * 100) + "%")); }
            @Override protected void applyValue() {
                ClickConfig.stereoWidth = (float) value;
                long now = System.currentTimeMillis();
                if (now - lastPreview > 400) { lastPreview = now; previewDesk(); }
            }
        }).setTooltip(Tooltip.of(Text.literal("How far left / right the keyboard and mouse are. Plays a key, then a click, to try it.")));
        y += gap;
        addDrawableChild(toggle(left, y, w, "Scroll sounds", () -> ClickConfig.scrollSounds, v -> ClickConfig.scrollSounds = v,
            "A sound for every scroll wheel notch, if the mouse pack has scroll sounds (record them under Mouse clickpack > Scroll)."));
        addDrawableChild(toggle(right, y, w, "Sounds in menus", () -> ClickConfig.inMenus, v -> ClickConfig.inMenus = v,
            "Also play sounds while a menu or the chat is open (typing in chat, clicking in your inventory)."));
        y += gap + 6;
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close()).dimensions(this.width / 2 - 100, y, 200, 20).build());
    }

    /** A key on the left, then a mouse click on the right. */
    private void previewDesk() {
        ClickSoundsClient.KEYBOARD.previewRandom("a", Math.max(0.3f, ClickConfig.keyboardVolume), false, ClickConfig.releaseSounds, Desk.key("a"), 0);
        ClickSoundsClient.MOUSE.previewRandom("left", Math.max(0.3f, ClickConfig.mouseVolume), false, ClickConfig.releaseSounds, Desk.mouse(), 260);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int cx = this.width / 2;
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, top - 20, WHITE);
        ctx.drawCenteredTextWithShadow(this.textRenderer, "Hover a setting to see what it does", cx, top + 7 * 24 + 18, DARK_GRAY);
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
