package dev.markiemod.adminguard.gui;

import dev.markiemod.adminguard.AdminGuardPlugin;
import dev.markiemod.adminguard.core.AuditLog;
import dev.markiemod.adminguard.core.AuditType;
import dev.markiemod.adminguard.util.Text;
import dev.markiemod.adminguard.util.TimeUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Paged audit trail with type and player filters. */
public final class AuditMenu extends Menu {

    private final AdminGuardPlugin plugin;
    private int page;
    private int typeFilter = -1; // -1 = all
    private String playerFilter;

    public AuditMenu(AdminGuardPlugin plugin, Player viewer) {
        super(viewer, 6, Text.mm("<gold><bold>AdminGuard</bold></gold> <dark_gray>» Audit log"));
        this.plugin = plugin;
    }

    private List<AuditLog.Entry> filtered() {
        List<AuditLog.Entry> out = new ArrayList<>();
        for (AuditLog.Entry e : plugin.audit().recent()) {
            if (typeFilter >= 0 && e.type() != AuditType.values()[typeFilter]) continue;
            if (playerFilter != null && !e.actor().equalsIgnoreCase(playerFilter)) continue;
            out.add(e);
        }
        return out;
    }

    @Override
    protected void draw() {
        List<AuditLog.Entry> list = filtered();
        int pages = Math.max(1, (list.size() + 44) / 45);
        page = Math.min(page, pages - 1);
        for (int i = 0; i < 45; i++) {
            int idx = page * 45 + i;
            if (idx >= list.size()) break;
            AuditLog.Entry e = list.get(idx);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Who: <white>" + Text.esc(e.actor()));
            if (!e.command().isEmpty()) lore.add("<gray>Command: <white>" + Text.esc(e.command()));
            if (!e.detail().isEmpty()) lore.add("<gray>Info: <white>" + Text.esc(e.detail()));
            lore.add("<gray>When: <white>" + TimeUtil.ago(e.time()));
            lore.add("<dark_gray>" + TimeUtil.date(e.time()));
            set(i, Items.of(e.type().icon, e.type().color + e.type().pretty() + " <dark_gray>· <white>" + Text.esc(e.actor()), lore));
        }
        if (list.isEmpty()) set(22, Items.of(Material.PAPER, "<gray>No entries"));

        if (page > 0) set(45, Items.of(Material.ARROW, "<yellow>« Previous page"), e -> {
            page--;
            redraw();
        });
        else set(45, Items.of(Material.OAK_DOOR, "<yellow>Back to menu"), e -> new MainMenu(plugin, viewer).open());

        List<String> tl = new ArrayList<>();
        tl.add((typeFilter < 0 ? "<green>▶ " : "<gray>  ") + "Everything");
        for (AuditType t : AuditType.values()) tl.add((typeFilter == t.ordinal() ? "<green>▶ " : "<gray>  ") + t.pretty());
        tl.add("");
        tl.add("<yellow>Left/right-click to change");
        String current = typeFilter < 0 ? "Everything" : AuditType.values()[typeFilter].pretty();
        set(47, Items.of(Material.HOPPER, "<aqua>Show: <white>" + current, tl), e -> {
            int n = AuditType.values().length + 1;
            typeFilter = Math.floorMod(typeFilter + 1 + (e.isRightClick() ? -1 : 1), n) - 1;
            page = 0;
            redraw();
        });
        ItemStack info = Items.of(Material.PAPER, "<white>Page " + (page + 1) + "/" + pages, "<gray>Entries: <white>" + list.size(),
                "<dark_gray>Full history: plugins/AdminGuard/audit.log");
        set(49, info);
        set(51, Items.of(Material.PLAYER_HEAD, "<aqua>Player: <white>" + (playerFilter == null ? "anyone" : Text.esc(playerFilter)),
                "", "<yellow>Left-click to type a name", "<yellow>Right-click to clear"), e -> {
            if (e.isRightClick()) {
                playerFilter = null;
                page = 0;
                redraw();
                return;
            }
            plugin.prompt().ask(viewer, "<yellow>Type the player name to filter by:", name -> {
                playerFilter = name.split(" ")[0];
                page = 0;
                open();
            });
        });
        if (page + 1 < pages) set(53, Items.of(Material.ARROW, "<yellow>Next page »"), e -> {
            page++;
            redraw();
        });
    }
}
