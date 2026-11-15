package dev.markiemod.adminguard.gui;

import dev.markiemod.adminguard.AdminGuardPlugin;
import dev.markiemod.adminguard.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/** A number pad for entering PINs. Digits can be shuffled so watching someone's clicks reveals nothing. */
public final class KeypadMenu extends Menu {

    private static final int[] DIGIT_SLOTS = {12, 13, 14, 21, 22, 23, 30, 31, 32, 40};
    private static final int MAX = 12;

    private final AdminGuardPlugin plugin;
    private final String reason;
    private final String error;
    private final Consumer<String> onSubmit;
    private final Runnable onCancel;
    private final List<Integer> layout = new ArrayList<>();
    private final StringBuilder code = new StringBuilder();
    private boolean done;

    public KeypadMenu(AdminGuardPlugin plugin, Player viewer, String title, String reason, String error,
                      Consumer<String> onSubmit, Runnable onCancel) {
        super(viewer, 6, Text.mm("<dark_gray>🔒 " + Text.esc(title)));
        this.plugin = plugin;
        this.reason = reason;
        this.error = error;
        this.onSubmit = onSubmit;
        this.onCancel = onCancel;
        for (int i = 1; i <= 9; i++) layout.add(i);
        layout.add(0);
        if (plugin.getConfig().getBoolean("keypad.shuffle", false)) Collections.shuffle(layout);
    }

    private ItemStack display() {
        String dots = code.isEmpty() ? "<dark_gray>_ _ _ _" : "<white>" + "● ".repeat(code.length()).trim();
        List<String> lore = new ArrayList<>();
        if (reason != null && !reason.isEmpty()) {
            lore.add("<gray>For: <white>" + Text.esc(reason.length() > 60 ? reason.substring(0, 60) + "…" : reason));
        }
        if (error != null) lore.add("<red>" + Text.esc(error));
        return Items.of(Material.NAME_TAG, "<gold>PIN: " + dots, lore);
    }

    @Override
    protected void draw() {
        Material digitMat = Material.matchMaterial(plugin.getConfig().getString("keypad.digit-material", "LIGHT_BLUE_STAINED_GLASS_PANE"));
        if (digitMat == null || !digitMat.isItem()) digitMat = Material.LIGHT_BLUE_STAINED_GLASS_PANE;
        set(4, display());
        for (int i = 0; i < DIGIT_SLOTS.length; i++) {
            int digit = layout.get(i);
            ItemStack item = Items.of(digitMat, "<aqua><bold>" + digit);
            item.setAmount(Math.max(1, digit)); // stack size shows the digit
            set(DIGIT_SLOTS[i], item, e -> {
                if (code.length() < MAX) code.append(digit);
                inventory.setItem(4, display());
            });
        }
        set(39, Items.of(Material.ORANGE_STAINED_GLASS_PANE, "<gold>Clear"), e -> {
            code.setLength(0);
            inventory.setItem(4, display());
        });
        set(41, Items.of(Material.YELLOW_STAINED_GLASS_PANE, "<yellow>⌫ Delete"), e -> {
            if (!code.isEmpty()) code.setLength(code.length() - 1);
            inventory.setItem(4, display());
        });
        set(45, Items.of(Material.RED_CONCRETE, "<red><bold>Cancel"), e -> viewer.closeInventory());
        set(53, Items.of(Material.LIME_CONCRETE, "<green><bold>Enter ✔"), e -> {
            if (code.isEmpty() || done) return;
            done = true;
            String entered = code.toString();
            // Next tick: never run commands or swap inventories from inside a click event.
            plugin.getServer().getScheduler().runTask(plugin, () -> onSubmit.accept(entered));
        });
    }

    @Override
    public void onClose(InventoryCloseEvent.Reason reason) {
        if (done || reason == InventoryCloseEvent.Reason.OPEN_NEW) return;
        done = true;
        if (onCancel != null) plugin.getServer().getScheduler().runTask(plugin, onCancel);
    }
}
