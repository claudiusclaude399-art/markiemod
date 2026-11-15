package dev.markiemod.griefcheck.gui;

import dev.markiemod.griefcheck.GriefCheckPlugin;
import dev.markiemod.griefcheck.data.Query;
import dev.markiemod.griefcheck.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** /gc — the hub. */
public final class MainMenu extends Menu {

    private final GriefCheckPlugin plugin;
    private long totalRows = -1;

    public MainMenu(GriefCheckPlugin plugin, Player viewer) {
        super(viewer, 3, Text.mm("<dark_red><bold>GriefCheck</bold></dark_red> <dark_gray>» Menu"));
        this.plugin = plugin;
        plugin.database().totalRows().thenAccept(n -> plugin.sync(() -> {
            totalRows = n;
            if (viewer.getOpenInventory().getTopInventory() == inventory) redraw();
        }));
    }

    @Override
    protected void draw() {
        var s = plugin.settings();
        boolean inspecting = plugin.inspector().isInspecting(viewer);
        if (viewer.hasPermission("griefcheck.inspect")) {
            set(10, Items.of(inspecting ? Material.ENDER_EYE : Material.SPYGLASS,
                    (inspecting ? "<green>" : "<yellow>") + "Inspector " + (inspecting ? "ON" : "OFF"),
                    "<gray>Left-click a block to see its history.",
                    "<gray>Right-click a chest to see who took what.",
                    "<gray>Right-click a face to inspect the air in front.",
                    "<gray>Sneak to build normally while inspecting.",
                    "", "<yellow>Click to toggle"), e -> {
                plugin.inspector().toggle(viewer);
                redraw();
            });
        }
        if (viewer.hasPermission("griefcheck.lookup")) {
            set(11, Items.of(Material.COMPASS, "<aqua>Lookup nearby",
                    "<gray>Everything that happened within", "<white>" + s.defaultRadius + " blocks</white><gray> of you.",
                    "", "<yellow>Click to open"), e -> {
                Query q = Query.at(viewer.getLocation(), s.defaultRadius, 0);
                new LookupMenu(plugin, viewer, q, s.defaultTime).open();
            });
            set(12, Items.of(Material.IRON_SWORD, "<red>Who griefed this base?",
                    "<gray>Ranks every player who changed things", "<gray>within <white>" + s.reportRadius + " blocks</white>, by how much",
                    "<gray>of <white>other people's</white> stuff they broke,", "<gray>blew up, burned or stole.",
                    "", "<yellow>Click to open the grief report"),
                    e -> new ReportMenu(plugin, viewer, viewer.getLocation(), s.reportRadius, s.reportTime).open());
        }
        if (viewer.hasPermission("griefcheck.alerts")) {
            int n = plugin.alerts().history().size();
            set(13, Items.of(Material.BELL, "<gold>Grief alerts <gray>(" + n + ")",
                    "<gray>Players who broke or looted lots of", "<gray>other people's things in a short time.",
                    "", "<gray>Threshold: <white>" + s.alertThreshold + "</white> in <white>" + s.alertWindowSeconds + "s",
                    "", "<yellow>Click to view"), e -> new AlertsMenu(plugin, viewer).open());
        }
        if (viewer.hasPermission("griefcheck.trust")) {
            set(14, Items.of(Material.NAME_TAG, "<green>Trusted players",
                    "<gray>Friends you trust won't trigger", "<gray>grief alerts when they change", "<gray>blocks you placed.",
                    "", "<gray>Trusted: <white>" + plugin.alerts().trusted(viewer.getUniqueId()).size(),
                    "", "<yellow>Click to manage"), e -> new TrustMenu(plugin, viewer).open());
        }
        if (viewer.hasPermission("griefcheck.admin")) {
            set(15, Items.of(Material.COMPARATOR, "<light_purple>Settings",
                    "<gray>What to log, alert thresholds,", "<gray>radius defaults, rollback limits...",
                    "", "<yellow>Click to configure"), e -> new SettingsMenu(plugin, viewer).open());
        }
        set(16, Items.of(Material.BOOK, "<white>Info",
                "<gray>Logged actions: <white>" + (totalRows < 0 ? "loading..." : String.format("%,d", totalRows)),
                "<gray>Waiting to save: <white>" + plugin.database().queued(),
                "<gray>Rollbacks running: <white>" + plugin.rollbacks().running(),
                "",
                "<gray>Commands: <white>/gc help"));
    }
}
