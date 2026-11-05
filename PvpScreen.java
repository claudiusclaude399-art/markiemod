package dev.pvpmod.gui;

import dev.pvpmod.config.PvpConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public abstract class PvpScreen extends Screen {
    protected static final int H = 20;
    protected static final int GAP = 24;
    protected final Screen parent;

    protected PvpScreen(String title, Screen parent) {
        super(Text.literal(title));
        this.parent = parent;
    }

    protected ButtonWidget toggle(int x, int y, int w, String label, BooleanSupplier get, Consumer<Boolean> set) {
        return ButtonWidget.builder(toggleText(label, get.getAsBoolean()), b -> {
            boolean v = !get.getAsBoolean();
            set.accept(v);
            b.setMessage(toggleText(label, v));
        }).dimensions(x, y, w, H).build();
    }

    protected static Text toggleText(String label, boolean on) {
        return Text.literal(label + ": " + (on ? "ON" : "OFF"));
    }

    protected ButtonWidget button(int x, int y, int w, String label, Runnable action) {
        return ButtonWidget.builder(Text.literal(label), b -> action.run()).dimensions(x, y, w, H).build();
    }

    protected void addDone() {
        addDrawableChild(button(this.width / 2 - 100, this.height - 28, 200, "Done", this::close));
    }

    @Override
    public void close() {
        PvpConfig.save();
        if (this.client != null) this.client.setScreen(parent);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 12, 0xFFFFFFFF);
    }
}
