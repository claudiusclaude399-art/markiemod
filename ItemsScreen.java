package dev.pvpmod.gui;

import dev.pvpmod.config.PvpConfig;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

public class ItemsScreen extends PvpScreen {
    // Add or remove ids here to change which items are in the list.
    private static final String[] ITEMS = {
        "minecraft:totem_of_undying", "minecraft:end_crystal", "minecraft:obsidian",
        "minecraft:respawn_anchor", "minecraft:glowstone", "minecraft:golden_apple",
        "minecraft:enchanted_golden_apple", "minecraft:ender_pearl", "minecraft:shield",
        "minecraft:diamond_sword", "minecraft:netherite_sword", "minecraft:diamond_axe",
        "minecraft:netherite_axe", "minecraft:mace", "minecraft:bow", "minecraft:crossbow",
        "minecraft:trident", "minecraft:splash_potion", "minecraft:potion",
        "minecraft:experience_bottle", "minecraft:firework_rocket", "minecraft:elytra",
        "minecraft:wind_charge", "minecraft:cobweb", "minecraft:lava_bucket", "minecraft:water_bucket"
    };
    private int page = 0;

    public ItemsScreen(Screen parent) { super("Items (click one to edit)", parent); }

    private static String nameOf(String id) {
        return Registries.ITEM.get(Identifier.of(id)).getName().getString();
    }

    @Override
    protected void init() {
        PvpConfig c = PvpConfig.get();
        int x = this.width / 2 - 100;
        int y = 32;
        addDrawableChild(new FloatSlider(x, y, 200, H, "All held items", 0.25f, 2f, c.handScale, v -> c.handScale = v));
        y += GAP + 6;

        int perPage = Math.max(3, (this.height - y - 84) / GAP);
        int pages = Math.max(1, (ITEMS.length + perPage - 1) / perPage);
        page = Math.max(0, Math.min(page, pages - 1));

        for (int i = page * perPage; i < Math.min(ITEMS.length, (page + 1) * perPage); i++) {
            String id = ITEMS[i];
            boolean custom = c.itemScales.containsKey(id) || c.itemColors.containsKey(id);
            addDrawableChild(button(x, y, 200, nameOf(id) + (custom ? "  *" : ""),
                () -> this.client.setScreen(new ItemDetailScreen(this, id))));
            y += GAP;
        }

        int ny = this.height - 80;
        addDrawableChild(button(x, ny, 98, "< Prev", () -> { page--; clearAndInit(); }));
        addDrawableChild(button(x + 102, ny, 98, "Next >", () -> { page++; clearAndInit(); }));
        addDrawableChild(button(x, this.height - 54, 200, "Item in my hand...", this::openHeld));
        addDone();
    }

    private void openHeld() {
        if (this.client == null || this.client.player == null) return;
        ItemStack held = this.client.player.getMainHandStack();
        if (held.isEmpty()) return;
        String id = Registries.ITEM.getId(held.getItem()).toString();
        this.client.setScreen(new ItemDetailScreen(this, id));
    }
}
