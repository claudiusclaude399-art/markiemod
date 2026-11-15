package dev.markiemod.griefcheck.gui;

import dev.markiemod.griefcheck.GriefCheckPlugin;
import dev.markiemod.griefcheck.data.Action;
import dev.markiemod.griefcheck.data.Query;
import dev.markiemod.griefcheck.data.ReportRow;
import dev.markiemod.griefcheck.util.Text;
import dev.markiemod.griefcheck.util.TimeUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** "Who griefed this base?" — every player active in an area, most suspicious first. */
public final class ReportMenu extends Menu {

    private static final Map<String, Material> SOURCE_ICONS = Map.of(
            "#creeper", Material.CREEPER_HEAD,
            "#tnt", Material.TNT,
            "#fire", Material.CAMPFIRE,
            "#enderman", Material.ENDER_PEARL,
            "#wither", Material.WITHER_SKELETON_SKULL,
            "#wither_skull", Material.WITHER_SKELETON_SKULL,
            "#ender_dragon", Material.DRAGON_HEAD,
            "#ravager", Material.SADDLE,
            "#zombie", Material.ZOMBIE_HEAD);

    private final GriefCheckPlugin plugin;
    private Location center;
    private int radius;
    private long span;
    private List<ReportRow> rows;
    private int generation;

    public ReportMenu(GriefCheckPlugin plugin, Player viewer, Location center, int radius, long span) {
        super(viewer, 6, Text.mm("<dark_red><bold>GriefCheck</bold></dark_red> <dark_gray>» Grief report"));
        this.plugin = plugin;
        this.center = center.clone();
        this.radius = radius;
        this.span = span;
        load();
    }

    private Query query() {
        return Query.at(center, radius, span > 0 ? System.currentTimeMillis() - span : 0);
    }

    private void load() {
        int gen = ++generation;
        rows = null;
        plugin.database().report(query()).thenAccept(list -> plugin.sync(() -> {
            if (gen != generation) return;
            rows = list;
            redraw();
        }));
    }

    private static String scoreColor(int score) {
        return score >= 60 ? "<red>" : score >= 15 ? "<gold>" : "<green>";
    }

    private ItemStack icon(ReportRow r) {
        List<String> lore = new ArrayList<>();
        int score = r.score();
        lore.add("<gray>Suspicion score: " + scoreColor(score) + "<bold>" + score);
        lore.add("");
        lore.add("<gray>Broke blocks placed by others: " + (r.foreignBreaks() > 0 ? "<red>" : "<white>") + r.foreignBreaks());
        lore.add("<gray>Items taken from others' chests: " + (r.foreignTakes() > 0 ? "<red>" : "<white>") + r.foreignTakes());
        lore.add("<gray>Blocks blown up: <white>" + r.explosions() + "  <gray>Fire: <white>" + r.fire());
        lore.add("<gray>Animals / entities killed: <white>" + r.kills());
        lore.add("");
        lore.add("<dark_gray>Total broken " + r.breaks() + " · placed " + r.places() + " · items taken " + r.takes());
        lore.add("<gray>Last active: <white>" + TimeUtil.ago(r.last()));
        if (r.uuid() != null && plugin.getServer().getPlayer(r.uuid()) != null) lore.add("<green>● Online now");
        lore.add("");
        lore.add("<yellow>Left-click: see what they did here");
        if (viewer.hasPermission("griefcheck.rollback")) lore.add("<red>Right-click: roll back their changes here");
        String name = scoreColor(score) + Text.esc(r.actor());
        if (r.uuid() != null) return Items.head(plugin.getServer().getOfflinePlayer(r.uuid()), name, lore);
        Material m = SOURCE_ICONS.getOrDefault(r.actor().toLowerCase(Locale.ROOT), Material.PAPER);
        return Items.of(m, name, lore);
    }

    @Override
    protected void draw() {
        if (rows == null) set(22, Items.of(Material.CLOCK, "<gray>Loading..."));
        else if (rows.isEmpty()) set(22, Items.of(Material.BARRIER, "<green>Nobody changed anything here",
                "<gray>Try a bigger radius or longer time."));
        else {
            for (int i = 0; i < rows.size() && i < 45; i++) {
                ReportRow r = rows.get(i);
                set(i, icon(r), e -> {
                    Query q = query();
                    q.actor = r.actor();
                    if (e.isRightClick() && viewer.hasPermission("griefcheck.rollback")) {
                        confirm(q, r);
                    } else {
                        new LookupMenu(plugin, viewer, q, span).open();
                    }
                });
            }
        }
        set(45, Items.of(Material.OAK_DOOR, "<yellow>Back to menu"), e -> new MainMenu(plugin, viewer).open());
        set(47, Items.of(Material.CLOCK, "<aqua>Time: <white>" + LookupMenu.spanName(span), "", "<yellow>Left/right-click to change"), e -> {
            span = LookupMenu.next(LookupMenu.TIMES, span, e.isRightClick());
            load();
            redraw();
        });
        set(48, Items.of(Material.MAP, "<aqua>Radius: <white>" + radius + " blocks",
                "", "<yellow>Left-click +5 · Right-click -5", "<yellow>Shift-click ±25"), e -> {
            int step = e.isShiftClick() ? 25 : 5;
            radius = Math.max(1, Math.min(500, radius + (e.isRightClick() ? -step : step)));
            load();
            redraw();
        });
        set(49, Items.of(Material.BOOK, "<white>How this works",
                "<gray>Everyone who changed something within", "<white>" + radius + "</white><gray> blocks of <white>"
                        + center.getBlockX() + ", " + center.getBlockY() + ", " + center.getBlockZ(),
                "<gray>is ranked by a suspicion score.", "",
                "<gray>Breaking, blowing up or looting things", "<gray>that <white>someone else placed</white> counts most.",
                "<gray>Players with high scores are your", "<gray>likely griefers."));
        set(50, Items.of(Material.COMPASS, "<aqua>Re-center on me", "<gray>Use your current position"), e -> {
            center = viewer.getLocation();
            load();
            redraw();
        });
        set(51, Items.of(Material.SPYGLASS, "<aqua>Full lookup of this area", "<gray>Every single action, newest first"), e -> {
            Query q = query();
            q.actions = EnumSet.allOf(Action.class);
            new LookupMenu(plugin, viewer, q, span).open();
        });
        set(53, Items.of(Material.SUNFLOWER, "<yellow>Refresh"), e -> {
            load();
            redraw();
        });
    }

    private void confirm(Query q, ReportRow r) {
        String problem = RollbackChecks.check(plugin, q);
        if (problem != null) {
            plugin.send(viewer, "<red>" + Text.esc(problem));
            return;
        }
        new ConfirmMenu(viewer, Text.mm("<dark_red>Roll back " + Text.esc(r.actor()) + "?"),
                Items.of(Material.TNT, "<red>Undo everything " + Text.esc(r.actor()) + " did here?",
                        "<gray>Radius: <white>" + radius, "<gray>Time: <white>" + LookupMenu.spanName(span),
                        "", "<gray>Broken blocks come back, placed blocks", "<gray>are removed and stolen items return."),
                () -> plugin.rollbacks().run(viewer, q, true), this::open).open();
    }
}
