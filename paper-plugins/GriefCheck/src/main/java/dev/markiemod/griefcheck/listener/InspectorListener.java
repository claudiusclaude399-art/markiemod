package dev.markiemod.griefcheck.listener;

import dev.markiemod.griefcheck.GriefCheckPlugin;
import dev.markiemod.griefcheck.data.Action;
import dev.markiemod.griefcheck.data.LogEntry;
import dev.markiemod.griefcheck.data.Query;
import dev.markiemod.griefcheck.util.EntryFormat;
import dev.markiemod.griefcheck.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Inspector mode: left-click a block to see its history, right-click a container to see who took what,
 * right-click any other block (or place a block) to see the history of the space in front of it.
 */
public final class InspectorListener implements Listener {

    private final GriefCheckPlugin plugin;
    private final Set<UUID> inspecting = new HashSet<>();
    private final Map<UUID, Query> lastQuery = new HashMap<>();
    private final Map<UUID, Long> lastClick = new HashMap<>();

    public InspectorListener(GriefCheckPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isInspecting(Player p) {
        return inspecting.contains(p.getUniqueId());
    }

    public void toggle(Player p) {
        if (inspecting.remove(p.getUniqueId())) plugin.msg(p, "inspector-off");
        else {
            inspecting.add(p.getUniqueId());
            plugin.msg(p, "inspector-on");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!inspecting.contains(p.getUniqueId()) || e.getClickedBlock() == null) return;
        if (e.getHand() != EquipmentSlot.HAND) {
            if (!p.isSneaking()) e.setCancelled(true); // don't let the off hand place blocks while inspecting
            return;
        }
        Block block = e.getClickedBlock();
        switch (e.getAction()) {
            case LEFT_CLICK_BLOCK -> {
                e.setCancelled(true);
                show(p, block.getLocation(), EnumSet.allOf(Action.class), "block");
            }
            case RIGHT_CLICK_BLOCK -> {
                if (p.isSneaking() && e.getItem() != null && e.getItem().getType().isBlock()) return; // let them build
                e.setCancelled(true);
                if (block.getState(false) instanceof Container c) {
                    Location loc = LogListener.containerLocation(c.getInventory().getHolder(false));
                    show(p, loc == null ? block.getLocation() : loc, EnumSet.of(Action.TAKE, Action.PUT), "container");
                } else {
                    show(p, block.getRelative(e.getBlockFace()).getLocation(), EnumSet.allOf(Action.class), "block");
                }
            }
            default -> {
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent e) {
        if (!inspecting.contains(e.getPlayer().getUniqueId())) return;
        if (e.getPlayer().isSneaking()) return;
        e.setCancelled(true);
        show(e.getPlayer(), e.getBlock().getLocation(), EnumSet.allOf(Action.class), "block");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent e) {
        if (inspecting.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        inspecting.remove(e.getPlayer().getUniqueId());
        lastQuery.remove(e.getPlayer().getUniqueId());
        lastClick.remove(e.getPlayer().getUniqueId());
    }

    private void show(Player p, Location loc, Set<Action> actions, String kind) {
        long now = System.currentTimeMillis();
        if (now - lastClick.getOrDefault(p.getUniqueId(), 0L) < 150) return; // clicks fire twice sometimes
        lastClick.put(p.getUniqueId(), now);
        Query q = Query.at(loc, 0, 0);
        q.actions = actions;
        lastQuery.put(p.getUniqueId(), q);
        page(p, 1);
    }

    /** Shows a page of the player's last inspector lookup (used by the clickable arrows: /gc page n). */
    public void page(Player p, int page) {
        Query q = lastQuery.get(p.getUniqueId());
        if (q == null) {
            plugin.msg(p, "no-inspection");
            return;
        }
        int per = plugin.settings().inspectorLines;
        int target = Math.max(1, page);
        plugin.database().count(q).thenCompose(total -> plugin.database().lookup(q, per, (target - 1) * per)
                .thenAccept(rows -> plugin.sync(() -> print(p, q, rows, target, Math.max(1, (total + per - 1) / per), total))));
    }

    private void print(Player p, Query q, List<LogEntry> rows, int page, int pages, int total) {
        String header = plugin.getConfig().getString("messages.inspector-header",
                "<dark_gray>----- <red>GriefCheck</red> <gray><world> <x>, <y>, <z></gray> -----");
        p.sendMessage(Text.mm(header
                .replace("<world>", Text.esc(q.world)).replace("<x>", String.valueOf(q.x))
                .replace("<y>", String.valueOf(q.y)).replace("<z>", String.valueOf(q.z))));
        if (rows.isEmpty()) {
            plugin.msg(p, "no-results");
            return;
        }
        for (LogEntry e : rows) p.sendMessage(Text.mm(" ").append(EntryFormat.chatLine(e)));
        if (pages > 1) {
            Component nav = Text.mm("<gray>Page " + page + "/" + pages + " <dark_gray>(" + total + ")</dark_gray> ");
            if (page > 1) nav = nav.append(Text.mm("<aqua>[« Prev]").clickEvent(ClickEvent.runCommand("/gc page " + (page - 1))));
            if (page < pages) nav = nav.append(Text.mm(" <aqua>[Next »]").clickEvent(ClickEvent.runCommand("/gc page " + (page + 1))));
            p.sendMessage(nav);
        }
    }
}
