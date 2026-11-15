package dev.markiemod.adminguard.gui;

import dev.markiemod.adminguard.AdminGuardPlugin;
import dev.markiemod.adminguard.core.AuditType;
import dev.markiemod.adminguard.util.Text;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/** Manage who may hold OP. */
public final class OpWhitelistMenu extends Menu {

    private final AdminGuardPlugin plugin;

    public OpWhitelistMenu(AdminGuardPlugin plugin, Player viewer) {
        super(viewer, 6, Text.mm("<gold><bold>AdminGuard</bold></gold> <dark_gray>» OP whitelist"));
        this.plugin = plugin;
    }

    private OfflinePlayer resolve(String entry) {
        try {
            return plugin.getServer().getOfflinePlayer(UUID.fromString(entry));
        } catch (IllegalArgumentException e) {
            return plugin.getServer().getOfflinePlayerIfCached(entry);
        }
    }

    @Override
    protected void draw() {
        List<String> list = plugin.opGuard().list();
        for (int i = 0; i < list.size() && i < 36; i++) {
            String entry = list.get(i);
            OfflinePlayer op = resolve(entry);
            List<String> lore = List.of(op != null && op.isOp() ? "<green>Currently OP" : "<gray>Not OP right now", "", "<red>Click to remove");
            var item = op != null ? Items.head(op, "<white>" + Text.esc(entry), lore) : Items.of(Material.SKELETON_SKULL, "<white>" + Text.esc(entry), lore);
            set(i, item, e -> {
                if (list.size() == 1 && plugin.getConfig().getBoolean("op-whitelist.enabled")) {
                    plugin.send(viewer, "<red>Turn the whitelist off before removing the last player.");
                    return;
                }
                plugin.opGuard().remove(entry);
                plugin.audit().log(viewer.getName(), AuditType.SETTINGS, null, "op-whitelist removed " + entry);
                redraw();
            });
        }
        boolean on = plugin.getConfig().getBoolean("op-whitelist.enabled");
        set(45, Items.of(Material.OAK_DOOR, "<yellow>Back to menu"), e -> new MainMenu(plugin, viewer).open());
        set(47, Items.toggle(on, "Enforce OP whitelist", "<gray>Anyone with OP who isn't listed", "<gray>is de-opped within 2 seconds."), e -> {
            if (!on && plugin.opGuard().list().isEmpty()) {
                plugin.send(viewer, "<red>Add at least one player (yourself!) first.");
                return;
            }
            if (!on && !plugin.opGuard().allowed(viewer) && viewer.isOp()) {
                plugin.send(viewer, "<red>You aren't on the list - you'd de-op yourself. Add yourself first.");
                return;
            }
            plugin.setSetting(viewer, "op-whitelist.enabled", !on);
            plugin.opGuard().check();
            redraw();
        });
        boolean kick = plugin.getConfig().getBoolean("op-whitelist.kick");
        set(48, Items.toggle(kick, "Kick unlisted OPs", "<gray>Also kick them, not just de-op."), e -> {
            plugin.setSetting(viewer, "op-whitelist.kick", !kick);
            redraw();
        });
        set(50, Items.of(Material.EMERALD, "<green>Add player", "<gray>Type a name in chat"), e ->
                plugin.prompt().ask(viewer, "<yellow>Type the player to allow OP:", name -> {
                    OfflinePlayer p = plugin.getServer().getOfflinePlayerIfCached(name.split(" ")[0]);
                    if (p == null) plugin.send(viewer, "<red>That player has never joined.");
                    else {
                        plugin.opGuard().add(p);
                        plugin.audit().log(viewer.getName(), AuditType.SETTINGS, null, "op-whitelist added " + p.getName());
                    }
                    open();
                }));
        set(51, Items.of(Material.NETHER_STAR, "<aqua>Add all current OPs", "<gray>Quick setup: whitelist everyone", "<gray>who is OP right now."), e -> {
            for (OfflinePlayer op : plugin.getServer().getOperators()) {
                if (!plugin.opGuard().allowed(op)) plugin.opGuard().add(op);
            }
            plugin.audit().log(viewer.getName(), AuditType.SETTINGS, null, "op-whitelist added all current ops");
            redraw();
        });
    }
}
