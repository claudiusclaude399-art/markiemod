package dev.markiemod.griefcheck.gui;

import dev.markiemod.griefcheck.GriefCheckPlugin;
import dev.markiemod.griefcheck.util.Text;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Manage the players who may change your builds without setting off grief alerts. */
public final class TrustMenu extends Menu {

    private final GriefCheckPlugin plugin;

    public TrustMenu(GriefCheckPlugin plugin, Player viewer) {
        super(viewer, 6, Text.mm("<dark_red><bold>GriefCheck</bold></dark_red> <dark_gray>» Trusted"));
        this.plugin = plugin;
    }

    @Override
    protected void draw() {
        List<Map.Entry<UUID, String>> list = new ArrayList<>(plugin.alerts().trusted(viewer.getUniqueId()).entrySet());
        for (int i = 0; i < list.size() && i < 45; i++) {
            Map.Entry<UUID, String> t = list.get(i);
            set(i, Items.head(plugin.getServer().getOfflinePlayer(t.getKey()), "<green>" + Text.esc(t.getValue()),
                    List.of("<gray>Can change your builds without", "<gray>triggering grief alerts.", "", "<red>Click to remove")), e -> {
                plugin.alerts().setTrust(viewer.getUniqueId(), t.getKey(), t.getValue(), false);
                plugin.msg(viewer, "untrusted", "player", t.getValue());
                redraw();
            });
        }
        if (list.isEmpty()) set(22, Items.of(Material.PAPER, "<gray>You don't trust anyone yet"));
        set(45, Items.of(Material.OAK_DOOR, "<yellow>Back to menu"), e -> new MainMenu(plugin, viewer).open());
        set(49, Items.of(Material.EMERALD, "<green>Trust a player", "<gray>Type their name in chat"), e ->
                plugin.prompt().ask(viewer, "<yellow>Type the name of the player to trust:", name -> {
                    trust(plugin, viewer, name.split(" ")[0]);
                    open();
                }));
    }

    public static void trust(GriefCheckPlugin plugin, Player owner, String name) {
        OfflinePlayer target = plugin.getServer().getOfflinePlayerIfCached(name);
        if (target == null || target.getName() == null) {
            plugin.msg(owner, "unknown-player", "player", name);
            return;
        }
        if (target.getUniqueId().equals(owner.getUniqueId())) return;
        plugin.alerts().setTrust(owner.getUniqueId(), target.getUniqueId(), target.getName(), true);
        plugin.msg(owner, "trusted", "player", target.getName());
    }
}
