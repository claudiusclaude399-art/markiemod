package dev.markiemod.adminguard.gui;

import dev.markiemod.adminguard.AdminGuardPlugin;
import dev.markiemod.adminguard.util.Text;
import dev.markiemod.adminguard.util.TimeUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** /ag — the hub. */
public final class MainMenu extends Menu {

    private final AdminGuardPlugin plugin;

    public MainMenu(AdminGuardPlugin plugin, Player viewer) {
        super(viewer, 3, Text.mm("<gold><bold>AdminGuard</bold></gold> <dark_gray>» Menu"));
        this.plugin = plugin;
    }

    @Override
    protected void draw() {
        // Status
        boolean hasPin = plugin.pins().has(viewer.getUniqueId());
        List<String> status = new ArrayList<>();
        status.add("<gray>PIN: " + (hasPin ? "<green>set" : "<red>not set"));
        long session = plugin.verifier().sessionRemaining(viewer);
        status.add("<gray>Verified session: " + (session > 0 ? "<green>" + TimeUtil.format(session) + " left" : "<gray>none"));
        long lock = plugin.verifier().lockRemaining(viewer.getUniqueId());
        if (lock > 0) status.add("<red>Locked out for " + TimeUtil.format(lock));
        status.add("<gray>Bypass permission: " + (viewer.hasPermission("adminguard.bypass") ? "<light_purple>yes" : "<gray>no"));
        if (session > 0) {
            status.add("");
            status.add("<yellow>Click to end your session now");
        }
        set(4, Items.head(viewer, "<gold>" + Text.esc(viewer.getName()), status), e -> {
            plugin.verifier().endSession(viewer.getUniqueId());
            plugin.msg(viewer, "session-ended");
            redraw();
        });

        set(10, Items.of(hasPin ? Material.TRIPWIRE_HOOK : Material.NAME_TAG, "<aqua>" + (hasPin ? "Change my PIN" : "Set my PIN"),
                "<gray>Your PIN unlocks protected commands.", "<gray>It is stored hashed, never in plain text.",
                "", "<yellow>Click to " + (hasPin ? "change" : "set")), e -> plugin.verifier().setPin(viewer));

        if (viewer.hasPermission("adminguard.approve")) {
            int n = plugin.requests().pending().size();
            ItemStack req = Items.of(n > 0 ? Material.WRITABLE_BOOK : Material.BOOK, "<gold>Pending approvals <gray>(" + n + ")",
                    "<gray>Commands waiting for a second", "<gray>staff member to approve them.", "", "<yellow>Click to view");
            if (n > 0) req.setAmount(Math.min(64, n));
            set(11, req, e -> new RequestsMenu(plugin, viewer).open());
        }
        if (viewer.hasPermission("adminguard.audit")) {
            set(12, Items.of(Material.KNOWLEDGE_BOOK, "<yellow>Audit log",
                    "<gray>Every protected command, approval,", "<gray>denial, failed PIN and setting change.",
                    "", "<yellow>Click to view"), e -> new AuditMenu(plugin, viewer).open());
        }
        if (viewer.hasPermission("adminguard.admin")) {
            set(14, Items.of(Material.COMMAND_BLOCK, "<red>Protected commands",
                    "<gray>Rules: <white>" + plugin.rules().all().size(),
                    "<gray>Choose which commands need a", "<gray>confirm, PIN, approval, or are blocked.",
                    "", "<yellow>Click to edit"), e -> plugin.manage(viewer, () -> new RulesMenu(plugin, viewer).open()));
            set(15, Items.of(Material.COMPARATOR, "<light_purple>Settings",
                    "<gray>Sessions, lockouts, rate limits,", "<gray>notifications, Discord webhook...",
                    "", "<yellow>Click to configure"), e -> plugin.manage(viewer, () -> new SettingsMenu(plugin, viewer).open()));
            boolean on = plugin.getConfig().getBoolean("op-whitelist.enabled");
            set(16, Items.of(Material.WITHER_SKELETON_SKULL, "<dark_red>OP whitelist " + (on ? "<green>(on)" : "<gray>(off)"),
                    "<gray>Only listed players may have OP.", "<gray>Anyone else is de-opped instantly.",
                    "", "<yellow>Click to manage"), e -> plugin.manage(viewer, () -> new OpWhitelistMenu(plugin, viewer).open()));
        }
    }

}
