package dev.clicksounds.gui;

import dev.clicksounds.ClickConfig;
import dev.clicksounds.ClickSoundsClient;
import dev.clicksounds.audio.Packs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import static dev.clicksounds.gui.ClickSoundsScreen.*;

/** Type a new name for a clickpack (renames its folder). */
public class RenamePackScreen extends Screen {
    private final PackPickerScreen parent;
    private final String pack;
    private TextFieldWidget field;
    private String error = "";

    public RenamePackScreen(PackPickerScreen parent, String pack) {
        super(Text.literal("Rename clickpack"));
        this.parent = parent;
        this.pack = pack;
    }

    @Override
    protected void init() {
        int cx = this.width / 2, y = this.height / 2 - 20;
        field = new TextFieldWidget(this.textRenderer, cx - 100, y, 200, 20, Text.literal("Name"));
        field.setMaxLength(48);
        field.setText(field.getText().isEmpty() ? pack : field.getText());
        field.setChangedListener(t -> error = "");
        addDrawableChild(field);
        setInitialFocus(field);
        addDrawableChild(ButtonWidget.builder(Text.literal("Rename"), b -> apply()).dimensions(cx - 100, y + 28, 98, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> close()).dimensions(cx + 2, y + 28, 98, 20).build());
    }

    private void apply() {
        String to = field.getText().trim();
        String err = Packs.rename(pack, to);
        if (err != null) {
            error = err;
            return;
        }
        if (pack.equals(ClickConfig.mousePack)) ClickConfig.mousePack = to;
        if (pack.equals(ClickConfig.keyboardPack)) ClickConfig.keyboardPack = to;
        ClickSoundsClient.loadPacks();
        parent.setStatus("Renamed '" + pack + "' to '" + to + "'", GREEN);
        close();
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER) {
            apply();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int cx = this.width / 2, y = this.height / 2 - 20;
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, y - 30, WHITE);
        ctx.drawCenteredTextWithShadow(this.textRenderer, "'" + pack + "'", cx, y - 16, GRAY);
        ctx.drawCenteredTextWithShadow(this.textRenderer, error, cx, y + 56, RED);
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }
}
