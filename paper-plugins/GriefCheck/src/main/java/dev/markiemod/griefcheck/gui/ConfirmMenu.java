package dev.markiemod.griefcheck.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Generic yes/no screen. */
public final class ConfirmMenu extends Menu {

    private final ItemStack info;
    private final Runnable onYes;
    private final Runnable onNo;

    public ConfirmMenu(Player viewer, Component title, ItemStack info, Runnable onYes, Runnable onNo) {
        super(viewer, 3, title);
        this.info = info;
        this.onYes = onYes;
        this.onNo = onNo;
    }

    @Override
    protected void draw() {
        ItemStack yes = Items.of(Material.LIME_STAINED_GLASS_PANE, "<green><bold>CONFIRM", "<gray>Click to go ahead");
        ItemStack no = Items.of(Material.RED_STAINED_GLASS_PANE, "<red><bold>CANCEL", "<gray>Click to go back");
        for (int slot : new int[]{9, 10, 11, 0, 1, 2, 18, 19, 20}) set(slot, yes, e -> {
            viewer.closeInventory();
            onYes.run();
        });
        for (int slot : new int[]{15, 16, 17, 6, 7, 8, 24, 25, 26}) set(slot, no, e -> {
            if (onNo != null) onNo.run();
            else viewer.closeInventory();
        });
        set(13, info);
    }
}
