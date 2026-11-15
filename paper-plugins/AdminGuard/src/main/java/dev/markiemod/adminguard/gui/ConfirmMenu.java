package dev.markiemod.adminguard.gui;

import dev.markiemod.adminguard.AdminGuardPlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;

/** Yes/no screen. With cancelOnClose, closing the inventory counts as "no". */
public final class ConfirmMenu extends Menu {

    private final AdminGuardPlugin plugin;
    private final ItemStack info;
    private final Runnable onYes;
    private final Runnable onNo;
    private final boolean cancelOnClose;
    private final long openedAt = System.currentTimeMillis();
    private boolean decided;

    public ConfirmMenu(AdminGuardPlugin plugin, Player viewer, Component title, ItemStack info, Runnable onYes, Runnable onNo,
                       boolean cancelOnClose) {
        super(viewer, 3, title);
        this.plugin = plugin;
        this.info = info;
        this.onYes = onYes;
        this.onNo = onNo;
        this.cancelOnClose = cancelOnClose;
    }

    @Override
    protected void draw() {
        ItemStack yes = Items.of(Material.LIME_STAINED_GLASS_PANE, "<green><bold>CONFIRM", "<gray>Click to go ahead");
        ItemStack no = Items.of(Material.RED_STAINED_GLASS_PANE, "<red><bold>CANCEL", "<gray>Click to cancel");
        long delay = plugin.getConfig().getLong("settings.confirm-delay-ms", 750);
        for (int slot : new int[]{0, 1, 2, 9, 10, 11, 18, 19, 20}) set(slot, yes, e -> {
            if (decided || System.currentTimeMillis() - openedAt < delay) return; // stop panic double-clicks
            decided = true;
            viewer.closeInventory();
            plugin.getServer().getScheduler().runTask(plugin, onYes);
        });
        for (int slot : new int[]{6, 7, 8, 15, 16, 17, 24, 25, 26}) set(slot, no, e -> {
            if (decided) return;
            decided = true;
            viewer.closeInventory();
            if (onNo != null) plugin.getServer().getScheduler().runTask(plugin, onNo);
        });
        set(13, info);
    }

    @Override
    public void onClose(InventoryCloseEvent.Reason reason) {
        if (decided || !cancelOnClose) return;
        decided = true;
        if (onNo != null) plugin.getServer().getScheduler().runTask(plugin, onNo);
    }
}
