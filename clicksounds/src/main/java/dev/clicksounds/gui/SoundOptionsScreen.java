package dev.clicksounds.gui;

import dev.clicksounds.ClickConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import static dev.clicksounds.gui.ClickSoundsScreen.*;

/** Release / variation toggles and when soft and hard clicks kick in. */
public class SoundOptionsScreen extends Screen {
    private final Screen parent;
    private int top;

    public SoundOptionsScreen(Screen parent) {
        super(Text.literal("Sound options"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = this.width / 2 - 155, right = this.width / 2 + 5, w = 150;
        int y = top = Math.max(40, this.height / 2 - 70);
        addDrawableChild(toggle(left, y, w, "Release sounds", () -> ClickConfig.releaseSounds, v -> ClickConfig.releaseSounds = v,
            "Also play a release sound when you let go of a button or key, like a real click."));
        addDrawableChild(toggle(right, y, w, "Extra variation", () -> ClickConfig.variation, v -> ClickConfig.variation = v,
            "Tiny random pitch and volume change on every play, so it never sounds like a loop."));
        y += 34;
        addDrawableChild(toggle(left, y, w, "Soft clicks", () -> ClickConfig.softClicks, v -> ClickConfig.softClicks = v,
            "When you click fast (spam), use the pack's soft clicks, if it has any."));
        addDrawableChild(new MsSlider(right, y, w, 20, "Faster than", 20, 500, ClickConfig.softMs, v -> ClickConfig.softMs = v));
        y += 24;
        addDrawableChild(toggle(left, y, w, "Hard clicks", () -> ClickConfig.hardClicks, v -> ClickConfig.hardClicks = v,
            "For the first click after a pause, use the pack's hard clicks, if it has any."));
        addDrawableChild(new MsSlider(right, y, w, 20, "Pause over", 200, 5000, ClickConfig.hardMs, v -> ClickConfig.hardMs = v));
        y += 34;
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close()).dimensions(this.width / 2 - 100, y, 200, 20).build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int cx = this.width / 2;
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, top - 24, WHITE);
        ctx.drawCenteredTextWithShadow(this.textRenderer, "Packs without soft / hard clicks just use normal ones.",
            cx, top + 22, DARK_GRAY);
        ctx.drawCenteredTextWithShadow(this.textRenderer, "Time between two presses decides:  soft < normal < hard", cx, top + 82, DARK_GRAY);
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
