package dev.markiemod.adminguard.gui;

import dev.markiemod.adminguard.AdminGuardPlugin;
import dev.markiemod.adminguard.core.Request;
import dev.markiemod.adminguard.util.Text;
import dev.markiemod.adminguard.util.TimeUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Approve or deny commands waiting on a second staff member. */
public final class RequestsMenu extends Menu {

    private final AdminGuardPlugin plugin;

    public RequestsMenu(AdminGuardPlugin plugin, Player viewer) {
        super(viewer, 6, Text.mm("<gold><bold>AdminGuard</bold></gold> <dark_gray>» Approvals"));
        this.plugin = plugin;
    }

    @Override
    protected void draw() {
        List<Request> list = plugin.requests().pending();
        long timeout = plugin.getConfig().getInt("settings.request-timeout-seconds", 120) * 1000L;
        for (int i = 0; i < list.size() && i < 45; i++) {
            Request r = list.get(i);
            boolean own = r.uuid.equals(viewer.getUniqueId());
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Command: <white>" + Text.esc(r.command));
            lore.add("<gray>Rule: <white>" + Text.esc(r.decision.ruleNames()) + " <dark_gray>(" + r.decision.mode().display + ")");
            lore.add("<gray>Approvals: <white>" + r.approvals.size() + "/" + r.needed()
                    + (r.approvals.isEmpty() ? "" : " <dark_gray>(" + Text.esc(String.join(", ", r.approvals.values())) + ")"));
            lore.add("<gray>Expires in: <white>" + TimeUtil.format(timeout - (System.currentTimeMillis() - r.created)));
            lore.add("");
            if (own) lore.add("<red>Click to cancel your request");
            else {
                lore.add("<green>Left-click to APPROVE");
                lore.add("<red>Right-click to DENY");
            }
            set(i, Items.head(plugin.getServer().getOfflinePlayer(r.uuid), "<gold>#" + r.id + " <white>" + Text.esc(r.name), lore), e -> {
                if (own) {
                    plugin.requests().deny(viewer, r.id, null);
                    redraw();
                } else if (e.isRightClick()) {
                    plugin.prompt().ask(viewer, "<yellow>Why are you denying <white>" + Text.esc(r.command) + "</white>? <gray>(type - for no reason)",
                            reason -> plugin.requests().deny(viewer, r.id, reason.equals("-") ? null : reason));
                } else {
                    plugin.requests().approve(viewer, r.id);
                    if (viewer.getOpenInventory().getTopInventory() == inventory) redraw();
                }
            });
        }
        if (list.isEmpty()) set(22, Items.of(Material.LIME_DYE, "<green>Nothing waiting", "<gray>No commands need approval right now."));
        set(45, Items.of(Material.OAK_DOOR, "<yellow>Back to menu"), e -> new MainMenu(plugin, viewer).open());
        set(53, Items.of(Material.SUNFLOWER, "<yellow>Refresh"), e -> redraw());
    }
}
