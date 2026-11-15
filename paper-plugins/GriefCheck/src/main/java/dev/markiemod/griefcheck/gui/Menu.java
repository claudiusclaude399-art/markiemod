package dev.markiemod.griefcheck.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Base class for every chest GUI. Subclasses draw items in {@link #draw()} and attach click actions. */
public abstract class Menu implements InventoryHolder {

    protected final Inventory inventory;
    protected final Player viewer;
    private final Map<Integer, Consumer<InventoryClickEvent>> actions = new HashMap<>();

    protected Menu(Player viewer, int rows, Component title) {
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    /** Fill the inventory. Called on open and on every refresh. */
    protected abstract void draw();

    public void open() {
        redraw();
        viewer.openInventory(inventory);
    }

    /** Clears and redraws in place; the player sees the change without the GUI closing. */
    public void redraw() {
        inventory.clear();
        actions.clear();
        draw();
        fillEmpty();
    }

    protected void set(int slot, ItemStack item, Consumer<InventoryClickEvent> action) {
        inventory.setItem(slot, item);
        if (action != null) actions.put(slot, action);
        else actions.remove(slot);
    }

    protected void set(int slot, ItemStack item) {
        set(slot, item, null);
    }

    protected void fillEmpty() {
        ItemStack filler = Items.filler();
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, filler);
        }
    }

    protected int rows() {
        return inventory.getSize() / 9;
    }

    void handleClick(InventoryClickEvent event) {
        Consumer<InventoryClickEvent> action = actions.get(event.getRawSlot());
        if (action != null) {
            Items.click(viewer);
            action.accept(event);
        }
    }

    /** Called when the inventory closes for any reason. */
    public void onClose(InventoryCloseEvent.Reason reason) {
    }
}
