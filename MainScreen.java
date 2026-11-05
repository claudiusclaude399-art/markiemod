package dev.pvpmod.gui;

import net.minecraft.client.gui.screen.Screen;

public class MainScreen extends PvpScreen {
    public MainScreen(Screen parent) { super("PvP Mod", parent); }

    @Override
    protected void init() {
        int x = this.width / 2 - 100;
        int y = this.height / 2 - 72;
        addDrawableChild(button(x, y,           200, "Items (size, colour, texture)", () -> client.setScreen(new ItemsScreen(this))));
        addDrawableChild(button(x, y + GAP,     200, "Armor Colours",                 () -> client.setScreen(new ArmorScreen(this))));
        addDrawableChild(button(x, y + GAP * 2, 200, "End Crystal",                   () -> client.setScreen(new CrystalScreen(this))));
        addDrawableChild(button(x, y + GAP * 3, 200, "Totem Pop",                     () -> client.setScreen(new TotemScreen(this))));
        addDrawableChild(button(x, y + GAP * 4, 200, "Particles",                     () -> client.setScreen(new ParticlesScreen(this))));
        addDone();
    }
}
