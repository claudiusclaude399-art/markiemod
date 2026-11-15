package dev.markiemod.griefcheck.gui;

import dev.markiemod.griefcheck.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;

/** Small helpers for building GUI items with MiniMessage names and lore. */
public final class Items {

    private Items() {
    }

    public static ItemStack of(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material.isItem() ? material : Material.BARRIER);
        return name(item, name, lore);
    }

    public static ItemStack of(Material material, String name, String... lore) {
        return of(material, name, List.of(lore));
    }

    public static ItemStack name(ItemStack item, String name, List<String> lore) {
        item.editMeta(meta -> {
            meta.displayName(Text.mm(name));
            List<Component> lines = new ArrayList<>();
            for (String line : lore) lines.add(Text.mm(line));
            meta.lore(lines);
            meta.addItemFlags(ItemFlag.values());
        });
        return item;
    }

    public static ItemStack head(OfflinePlayer player, String name, List<String> lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        item.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(player));
        return name(item, name, lore);
    }

    public static ItemStack toggle(boolean on, String name, String... description) {
        List<String> lore = new ArrayList<>(List.of(description));
        lore.add("");
        lore.add(on ? "<green>● Enabled" : "<red>● Disabled");
        lore.add("<gray>Click to toggle");
        return of(on ? Material.LIME_DYE : Material.GRAY_DYE, (on ? "<green>" : "<red>") + name, lore);
    }

    public static ItemStack filler() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        item.editMeta(meta -> meta.setHideTooltip(true));
        return item;
    }

    public static ItemStack back() {
        return of(Material.ARROW, "<yellow>Back");
    }

    public static void click(Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.2f);
    }
}
