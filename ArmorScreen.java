package dev.pvpmod.gui;

import dev.pvpmod.config.PvpConfig;
import net.minecraft.client.gui.screen.Screen;

import java.util.List;

public class ArmorScreen extends PvpScreen {
    private record ArmorSet(String label, List<String> ids) {}

    private static List<String> pieces(String material) {
        return List.of("minecraft:" + material + "_helmet", "minecraft:" + material + "_chestplate",
                       "minecraft:" + material + "_leggings", "minecraft:" + material + "_boots");
    }

    private static final List<ArmorSet> SETS = List.of(
        new ArmorSet("Leather", pieces("leather")),
        new ArmorSet("Chainmail", pieces("chainmail")),
        new ArmorSet("Iron", pieces("iron")),
        new ArmorSet("Gold", pieces("golden")),
        new ArmorSet("Diamond", pieces("diamond")),
        new ArmorSet("Netherite", pieces("netherite")),
        new ArmorSet("Turtle shell", List.of("minecraft:turtle_helmet")),
        new ArmorSet("Elytra", List.of("minecraft:elytra"))
    );

    public ArmorScreen(Screen parent) { super("Armor Colours (click a set)", parent); }

    @Override
    protected void init() {
        PvpConfig c = PvpConfig.get();
        int x1 = this.width / 2 - 152;
        int x2 = this.width / 2 + 2;
        for (int i = 0; i < SETS.size(); i++) {
            ArmorSet set = SETS.get(i);
            boolean custom = set.ids().stream().anyMatch(c.itemColors::containsKey);
            int x = (i % 2 == 0) ? x1 : x2;
            int y = 40 + (i / 2) * GAP;
            addDrawableChild(button(x, y, 150, set.label() + (custom ? "  *" : ""),
                () -> this.client.setScreen(new ItemDetailScreen(this, set.label(), set.ids()))));
        }
        addDone();
    }
}
