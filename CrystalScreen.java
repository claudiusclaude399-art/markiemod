package dev.pvpmod.gui;

import dev.pvpmod.config.PvpConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public class CrystalScreen extends PvpScreen {
    public CrystalScreen(Screen parent) { super("End Crystal", parent); }

    @Override
    protected void init() {
        PvpConfig c = PvpConfig.get();
        int x = this.width / 2 - 100;
        int y = this.height / 2 - 30;
        addDrawableChild(toggle(x, y, 200, "3D crystal (glass layers)", () -> c.crystal3d, v -> c.crystal3d = v));
        addDrawableChild(toggle(x, y + GAP, 200, "Bedrock base", () -> c.crystalBase, v -> c.crystalBase = v));
        addDone();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(this.textRenderer,
            Text.literal("3D OFF = just the core cube, no glass frames"),
            this.width / 2, this.height / 2 - 48, 0xFFAAAAAA);
    }
}
