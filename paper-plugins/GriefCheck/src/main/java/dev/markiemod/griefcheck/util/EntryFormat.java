package dev.markiemod.griefcheck.util;

import dev.markiemod.griefcheck.data.Action;
import dev.markiemod.griefcheck.data.LogEntry;
import dev.markiemod.griefcheck.gui.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Turns log entries into chat lines and GUI icons. */
public final class EntryFormat {

    private EntryFormat() {
    }

    public static String pretty(String material) {
        if (material == null) return "?";
        String s = material.toLowerCase(Locale.ROOT).replace('_', ' ');
        return s;
    }

    public static String actorColor(LogEntry e) {
        return e.isPlayer() ? "<white>" : "<gray>";
    }

    public static String describe(LogEntry e) {
        String what = (e.action == Action.TAKE || e.action == Action.PUT)
                ? e.amount + "x " + pretty(e.material)
                : pretty(e.material);
        return actorColor(e) + Text.esc(e.actor) + "</" + (e.isPlayer() ? "white" : "gray") + "> "
                + e.action.color + e.action.verb + " <yellow>" + Text.esc(what) + "</yellow>"
                + (e.data != null && e.action == Action.KILL ? " <dark_gray>(" + Text.esc(e.data) + ")" : "");
    }

    public static Component chatLine(LogEntry e) {
        Component hover = Text.mm("<gray>" + TimeUtil.date(e.time) + "\n<gray>" + Text.esc(e.world) + " " + e.x + ", " + e.y + ", " + e.z
                + (e.rolledBack ? "\n<red>Rolled back" : ""));
        return Text.mm((e.rolledBack ? "<st>" : "") + "<dark_gray>" + TimeUtil.ago(e.time) + "</dark_gray> " + describe(e))
                .hoverEvent(HoverEvent.showText(hover));
    }

    public static Material icon(LogEntry e) {
        if (e.action == Action.KILL) {
            Material egg = Material.matchMaterial(e.material + "_SPAWN_EGG");
            if (egg != null) return egg;
            Material m = Material.matchMaterial(e.material);
            return m != null && m.isItem() ? m : Material.BONE;
        }
        if ("WATER".equals(e.material)) return Material.WATER_BUCKET;
        if ("LAVA".equals(e.material)) return Material.LAVA_BUCKET;
        if ("FIRE".equals(e.material)) return Material.FLINT_AND_STEEL;
        Material m = e.material == null ? null : Material.matchMaterial(e.material);
        if (m != null && m.isItem() && !m.isAir()) return m;
        return e.action.icon;
    }

    public static ItemStack item(LogEntry e, boolean canTeleport) {
        ItemStack base;
        if ((e.action == Action.TAKE || e.action == Action.PUT) && e.item != null) {
            try {
                base = ItemStack.deserializeBytes(e.item);
            } catch (Exception ex) {
                base = new ItemStack(icon(e));
            }
        } else {
            base = new ItemStack(icon(e));
        }
        if (e.amount > 1) base.setAmount(Math.min(base.getMaxStackSize(), Math.min(99, e.amount)));
        List<String> lore = new ArrayList<>();
        lore.add("<gray>By: " + actorColor(e) + Text.esc(e.actor));
        lore.add("<gray>When: <white>" + TimeUtil.ago(e.time));
        lore.add("<dark_gray>" + TimeUtil.date(e.time));
        lore.add("<gray>Where: <white>" + Text.esc(e.world) + " " + e.x + ", " + e.y + ", " + e.z);
        if (e.amount > 0) lore.add("<gray>Amount: <white>" + e.amount);
        if (e.data != null && e.action == Action.KILL) lore.add("<gray>Info: <white>" + Text.esc(e.data));
        if (e.rolledBack) lore.add("<red>✔ Rolled back");
        if (canTeleport) {
            lore.add("");
            lore.add("<yellow>Click to teleport");
        }
        String title = e.action.color + capital(e.action.verb) + " <white>" + Text.esc(pretty(e.material));
        return Items.name(base, title, lore);
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
