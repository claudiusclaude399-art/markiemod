package dev.markiemod.griefcheck.gui;

import dev.markiemod.griefcheck.GriefCheckPlugin;
import dev.markiemod.griefcheck.data.Action;
import dev.markiemod.griefcheck.data.LogEntry;
import dev.markiemod.griefcheck.data.Query;
import dev.markiemod.griefcheck.util.EntryFormat;
import dev.markiemod.griefcheck.util.Text;
import dev.markiemod.griefcheck.util.TimeUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Paged, filterable list of logged actions. */
public final class LookupMenu extends Menu {

    static final long[] TIMES = {3_600_000L, 6 * 3_600_000L, 86_400_000L, 3 * 86_400_000L, 7 * 86_400_000L, 30 * 86_400_000L, 0};
    static final int[] RADII = {0, 5, 10, 20, 50, 100, -1};

    private record Filter(String name, Set<Action> actions) {
    }

    private static final List<Filter> FILTERS = List.of(
            new Filter("Everything", EnumSet.allOf(Action.class)),
            new Filter("Blocks broken", EnumSet.of(Action.BREAK, Action.EXPLODE, Action.BURN)),
            new Filter("Blocks placed", EnumSet.of(Action.PLACE)),
            new Filter("Chest items taken", EnumSet.of(Action.TAKE)),
            new Filter("Chest items (in & out)", EnumSet.of(Action.TAKE, Action.PUT)),
            new Filter("Explosions & fire", EnumSet.of(Action.EXPLODE, Action.BURN, Action.IGNITE)),
            new Filter("Animal / entity kills", EnumSet.of(Action.KILL)));

    private final GriefCheckPlugin plugin;
    private final Query query;
    private long span;
    private int page;
    private int filter;
    private int total = -1;
    private List<LogEntry> rows = new ArrayList<>();
    private int generation;

    public LookupMenu(GriefCheckPlugin plugin, Player viewer, Query query, long span) {
        super(viewer, 6, Text.mm("<dark_red><bold>GriefCheck</bold></dark_red> <dark_gray>» Lookup"));
        this.plugin = plugin;
        this.query = query;
        this.span = span;
        for (int i = 0; i < FILTERS.size(); i++) if (FILTERS.get(i).actions().equals(query.actions)) filter = i;
        load();
    }

    static String spanName(long span) {
        return span <= 0 ? "All time" : "Last " + TimeUtil.format(span);
    }

    static String radiusName(int r) {
        return r < 0 ? "Whole server" : r == 0 ? "This block" : r + " blocks";
    }

    static long next(long[] values, long current, boolean back) {
        int i = 0;
        for (int j = 0; j < values.length; j++) if (values[j] == current) i = j;
        return values[Math.floorMod(i + (back ? -1 : 1), values.length)];
    }

    static int next(int[] values, int current, boolean back) {
        int i = 0;
        for (int j = 0; j < values.length; j++) if (values[j] == current) i = j;
        return values[Math.floorMod(i + (back ? -1 : 1), values.length)];
    }

    private void load() {
        int gen = ++generation;
        query.since = span > 0 ? System.currentTimeMillis() - span : 0;
        total = -1;
        int offset = page * 45;
        plugin.database().count(query).thenCompose(count -> plugin.database().lookup(query, 45, offset)
                .thenAccept(list -> plugin.sync(() -> {
                    if (gen != generation) return;
                    total = count;
                    rows = list;
                    redraw();
                })));
    }

    @Override
    protected void draw() {
        boolean tp = viewer.hasPermission("griefcheck.teleport");
        if (total < 0) {
            set(22, Items.of(Material.CLOCK, "<gray>Loading..."));
        } else if (rows.isEmpty()) {
            set(22, Items.of(Material.BARRIER, "<red>Nothing found", "<gray>Try a bigger radius or longer time."));
        }
        for (int i = 0; i < rows.size() && i < 45; i++) {
            LogEntry e = rows.get(i);
            set(i, EntryFormat.item(e, tp), tp ? ev -> teleport(e) : null);
        }
        int pages = Math.max(1, (Math.max(total, 0) + 44) / 45);

        if (page > 0) set(45, Items.of(Material.ARROW, "<yellow>« Previous page"), e -> {
            page--;
            load();
        });
        else set(45, Items.of(Material.OAK_DOOR, "<yellow>Back to menu"), e -> new MainMenu(plugin, viewer).open());

        Filter f = FILTERS.get(filter);
        List<String> fl = new ArrayList<>();
        for (int i = 0; i < FILTERS.size(); i++) fl.add((i == filter ? "<green>▶ " : "<gray>  ") + FILTERS.get(i).name());
        fl.add("");
        fl.add("<yellow>Left/right-click to change");
        set(46, Items.of(Material.HOPPER, "<aqua>Show: <white>" + f.name(), fl), e -> {
            filter = Math.floorMod(filter + (e.isRightClick() ? -1 : 1), FILTERS.size());
            query.actions = EnumSet.copyOf(FILTERS.get(filter).actions());
            page = 0;
            load();
        });
        set(47, Items.of(Material.CLOCK, "<aqua>Time: <white>" + spanName(span), "", "<yellow>Left/right-click to change"), e -> {
            span = next(TIMES, span, e.isRightClick());
            page = 0;
            load();
        });
        set(48, Items.of(Material.MAP, "<aqua>Area: <white>" + radiusName(query.radius),
                "<gray>Centered on <white>" + query.x + ", " + query.y + ", " + query.z,
                "", "<yellow>Left/right-click to change", "<yellow>Shift-click to re-center on you"), e -> {
            if (e.isShiftClick()) {
                Location l = viewer.getLocation();
                query.world = l.getWorld().getName();
                query.x = l.getBlockX();
                query.y = l.getBlockY();
                query.z = l.getBlockZ();
            } else {
                query.radius = next(RADII, query.radius, e.isRightClick());
            }
            page = 0;
            load();
        });
        set(49, Items.of(Material.PAPER, "<white>Page " + (page + 1) + "/" + pages,
                "<gray>Results: <white>" + (total < 0 ? "..." : total),
                "<gray>Player: <white>" + (query.actor == null ? "anyone" : Text.esc(query.actor)),
                "<gray>Area: <white>" + radiusName(query.radius),
                "<gray>Time: <white>" + spanName(span)));
        set(50, Items.of(Material.PLAYER_HEAD, "<aqua>Player: <white>" + (query.actor == null ? "anyone" : Text.esc(query.actor)),
                "<gray>Only show one player (or #creeper, #tnt...)", "", "<yellow>Left-click to type a name", "<yellow>Right-click to clear"), e -> {
            if (e.isRightClick()) {
                query.actor = null;
                page = 0;
                load();
                return;
            }
            plugin.prompt().ask(viewer, "<yellow>Type the player name to filter by:", name -> {
                query.actor = name.split(" ")[0];
                page = 0;
                load();
                open();
            });
        });
        if (viewer.hasPermission("griefcheck.rollback")) {
            set(51, Items.of(Material.TNT, "<red>Roll back these results",
                    "<gray>Undo every block change and chest", "<gray>transaction matching these filters.",
                    "", "<yellow>Left-click: roll back", "<yellow>Right-click: restore (undo a rollback)"),
                    e -> confirmRollback(e.getClick() != ClickType.RIGHT));
        }
        set(52, Items.of(Material.SUNFLOWER, "<yellow>Refresh"), e -> load());
        if (page + 1 < pages) set(53, Items.of(Material.ARROW, "<yellow>Next page »"), e -> {
            page++;
            load();
        });
    }

    private void teleport(LogEntry e) {
        World w = plugin.getServer().getWorld(e.world);
        if (w == null) return;
        viewer.closeInventory();
        viewer.teleportAsync(new Location(w, e.x + 0.5, e.y + 1, e.z + 0.5, viewer.getYaw(), viewer.getPitch()));
    }

    private void confirmRollback(boolean rollback) {
        Query q = query.copy();
        q.since = span > 0 ? System.currentTimeMillis() - span : 0;
        String problem = RollbackChecks.check(plugin, q);
        if (problem != null) {
            plugin.send(viewer, "<red>" + Text.esc(problem));
            return;
        }
        String verb = rollback ? "Roll back" : "Restore";
        new ConfirmMenu(viewer, Text.mm("<dark_red>" + verb + "?"),
                Items.of(rollback ? Material.TNT : Material.TOTEM_OF_UNDYING, "<red>" + verb + " " + (total < 0 ? "?" : total) + " entries?",
                        "<gray>Player: <white>" + (q.actor == null ? "anyone" : Text.esc(q.actor)),
                        "<gray>Area: <white>" + radiusName(q.radius),
                        "<gray>Time: <white>" + spanName(span),
                        "<gray>Shown: <white>" + FILTERS.get(filter).name(),
                        "", "<dark_gray>Kills and ignites can't be undone."),
                () -> plugin.rollbacks().run(viewer, q, rollback),
                this::open).open();
    }
}
