package dev.markiemod.griefcheck.gui;

import dev.markiemod.griefcheck.AlertManager;
import dev.markiemod.griefcheck.GriefCheckPlugin;
import dev.markiemod.griefcheck.util.Text;
import dev.markiemod.griefcheck.util.TimeUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** Recent grief alerts (kept in memory since the last restart). */
public final class AlertsMenu extends Menu {

    private final GriefCheckPlugin plugin;

    public AlertsMenu(GriefCheckPlugin plugin, Player viewer) {
        super(viewer, 6, Text.mm("<dark_red><bold>GriefCheck</bold></dark_red> <dark_gray>» Alerts"));
        this.plugin = plugin;
    }

    @Override
    protected void draw() {
        List<AlertManager.Alert> alerts = plugin.alerts().history();
        if (alerts.isEmpty()) set(22, Items.of(Material.LIME_DYE, "<green>No alerts", "<gray>Nobody has triggered a grief alert", "<gray>since the server started."));
        for (int i = 0; i < alerts.size() && i < 45; i++) {
            AlertManager.Alert a = alerts.get(i);
            ItemStack head = Items.head(plugin.getServer().getOfflinePlayer(a.actorUuid()), "<red>" + Text.esc(a.actor()),
                    List.of("<gray>Broke/looted <yellow>" + a.count() + "</yellow> things owned by",
                            "<white>" + Text.esc(String.join(", ", a.victims())),
                            "<gray>When: <white>" + TimeUtil.ago(a.time()),
                            "<gray>Where: <white>" + Text.esc(a.world()) + " " + a.x() + ", " + a.y() + ", " + a.z(),
                            "",
                            "<yellow>Left-click: grief report for this area",
                            viewer.hasPermission("griefcheck.teleport") ? "<yellow>Right-click: teleport there" : ""));
            set(i, head, e -> {
                World w = plugin.getServer().getWorld(a.world());
                if (w == null) return;
                Location loc = new Location(w, a.x() + 0.5, a.y() + 1, a.z() + 0.5);
                if (e.isRightClick() && viewer.hasPermission("griefcheck.teleport")) {
                    viewer.closeInventory();
                    viewer.teleportAsync(loc);
                } else {
                    new ReportMenu(plugin, viewer, loc, plugin.settings().reportRadius, plugin.settings().reportTime).open();
                }
            });
        }
        set(49, Items.of(Material.OAK_DOOR, "<yellow>Back to menu"), e -> new MainMenu(plugin, viewer).open());
    }
}
