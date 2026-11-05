package dev.pvpmod.gui;

import dev.pvpmod.config.PvpConfig;
import net.minecraft.client.gui.screen.Screen;

public class TotemScreen extends PvpScreen {
    public TotemScreen(Screen parent) { super("Totem Pop", parent); }

    @Override
    protected void init() {
        PvpConfig c = PvpConfig.get();
        int x = this.width / 2 - 100;
        int y = 32;
        addDrawableChild(toggle(x, y, 200, "Totem on screen", () -> c.totemOverlay, v -> c.totemOverlay = v));
        y += GAP;
        addDrawableChild(toggle(x, y, 200, "Totem particles", () -> c.totemParticles, v -> c.totemParticles = v));
        y += GAP;
        addDrawableChild(new FloatSlider(x, y, 200, H, "Particle size", 0.1f, 3f, c.totemParticleScale, v -> c.totemParticleScale = v));
        y += GAP;
        addDrawableChild(new FloatSlider(x, y, 200, H, "Particle amount", 0f, 1f, c.totemParticleDensity, v -> c.totemParticleDensity = v));
        y += GAP;
        addDrawableChild(toggle(x, y, 200, "Custom colour", () -> c.totemCustomColor, v -> c.totemCustomColor = v));
        y += GAP;
        addDrawableChild(new FloatSlider(x, y, 200, H, "Red", 0f, 1f, c.totemR, v -> c.totemR = v));
        y += GAP;
        addDrawableChild(new FloatSlider(x, y, 200, H, "Green", 0f, 1f, c.totemG, v -> c.totemG = v));
        y += GAP;
        addDrawableChild(new FloatSlider(x, y, 200, H, "Blue", 0f, 1f, c.totemB, v -> c.totemB = v));
        addDone();
    }
}
