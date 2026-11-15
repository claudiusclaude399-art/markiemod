package dev.markiemod.griefcheck.command;

import dev.markiemod.griefcheck.GriefCheckPlugin;
import dev.markiemod.griefcheck.data.Action;
import dev.markiemod.griefcheck.data.LogEntry;
import dev.markiemod.griefcheck.data.Query;
import dev.markiemod.griefcheck.gui.AlertsMenu;
import dev.markiemod.griefcheck.gui.LookupMenu;
import dev.markiemod.griefcheck.gui.MainMenu;
import dev.markiemod.griefcheck.gui.ReportMenu;
import dev.markiemod.griefcheck.gui.RollbackChecks;
import dev.markiemod.griefcheck.gui.SettingsMenu;
import dev.markiemod.griefcheck.gui.TrustMenu;
import dev.markiemod.griefcheck.util.EntryFormat;
import dev.markiemod.griefcheck.util.Text;
import dev.markiemod.griefcheck.util.TimeUtil;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

public final class GcCommand implements TabExecutor {

    private static final List<String> SUBS = List.of("menu", "inspect", "lookup", "report", "rollback", "restore",
            "alerts", "trust", "untrust", "settings", "purge", "reload", "help");

    private final GriefCheckPlugin plugin;

    public GcCommand(GriefCheckPlugin plugin) {
        this.plugin = plugin;
    }

    /** Parsed u: t: r: a: @world,x,y,z parameters. */
    private record Params(Query query, long span, boolean hasTime, String error) {
    }

    private Params parse(CommandSender sender, String[] args, int from, int defaultRadius, long defaultSpan) {
        Location loc = sender instanceof Player p ? p.getLocation() : null;
        String actor = null;
        Integer radius = null;
        long span = defaultSpan;
        boolean hasTime = false;
        EnumSet<Action> actions = EnumSet.allOf(Action.class);
        boolean actionsSet = false;
        for (int i = from; i < args.length; i++) {
            String a = args[i];
            String low = a.toLowerCase(Locale.ROOT);
            if (low.startsWith("u:") || low.startsWith("user:") || low.startsWith("p:")) {
                actor = a.substring(a.indexOf(':') + 1);
            } else if (low.startsWith("t:") || low.startsWith("time:")) {
                long t = TimeUtil.parse(a.substring(a.indexOf(':') + 1));
                if (t <= 0) return new Params(null, 0, false, "Bad time '" + a + "'. Use e.g. t:30m, t:2h, t:3d.");
                span = t;
                hasTime = true;
            } else if (low.startsWith("r:") || low.startsWith("radius:")) {
                String v = low.substring(low.indexOf(':') + 1);
                if (v.equals("#global") || v.equals("global") || v.equals("all")) radius = -1;
                else {
                    try {
                        radius = Math.max(0, Integer.parseInt(v));
                    } catch (NumberFormatException e) {
                        return new Params(null, 0, false, "Bad radius '" + a + "'. Use e.g. r:20 or r:#global.");
                    }
                }
            } else if (low.startsWith("a:") || low.startsWith("action:")) {
                if (!actionsSet) actions.clear();
                actionsSet = true;
                for (String part : low.substring(low.indexOf(':') + 1).split(",")) {
                    Action act = Action.parse(part);
                    if (act == null) return new Params(null, 0, false, "Unknown action '" + part
                            + "'. Use break, place, take, put, explode, burn, ignite, kill.");
                    actions.add(act);
                    if (act == Action.BREAK) {
                        actions.add(Action.EXPLODE);
                        actions.add(Action.BURN);
                    }
                }
            } else if (a.startsWith("@")) {
                String[] p = a.substring(1).split(",");
                World w = p.length == 4 ? plugin.getServer().getWorld(p[0]) : null;
                if (w == null) return new Params(null, 0, false, "Bad location '" + a + "'. Use @world,x,y,z.");
                try {
                    loc = new Location(w, Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
                } catch (NumberFormatException e) {
                    return new Params(null, 0, false, "Bad location '" + a + "'.");
                }
            } else if (actor == null && !a.contains(":")) {
                actor = a; // allow "/gc lookup Steve"
            } else {
                return new Params(null, 0, false, "Unknown parameter '" + a + "'.");
            }
        }
        int r = radius != null ? radius : (actor != null ? -1 : defaultRadius);
        if (loc == null && r >= 0) {
            if (actor == null) return new Params(null, 0, false, "From the console, give a player (u:) or a location (@world,x,y,z).");
            r = -1;
        }
        Query q = new Query();
        if (loc != null) {
            q = Query.at(loc, r, 0);
        } else {
            q.radius = -1;
        }
        q.actor = actor;
        q.actions = actions;
        q.since = span > 0 ? System.currentTimeMillis() - span : 0;
        return new Params(q, span, hasTime, null);
    }

    private boolean perm(CommandSender s, String perm) {
        if (s.hasPermission(perm)) return true;
        plugin.msg(s, "no-permission");
        return false;
    }

    private Player player(CommandSender s) {
        if (s instanceof Player p) return p;
        plugin.msg(s, "players-only");
        return null;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "menu", "gui" -> {
                Player p = player(sender);
                if (p != null && perm(p, "griefcheck.use")) new MainMenu(plugin, p).open();
            }
            case "inspect", "i", "inspector" -> {
                Player p = player(sender);
                if (p != null && perm(p, "griefcheck.inspect")) plugin.inspector().toggle(p);
            }
            case "page" -> {
                Player p = player(sender);
                if (p != null && args.length > 1 && perm(p, "griefcheck.inspect")) {
                    try {
                        plugin.inspector().page(p, Integer.parseInt(args[1]));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            case "lookup", "l" -> {
                if (!perm(sender, "griefcheck.lookup")) return true;
                Params params = parse(sender, args, 1, plugin.settings().defaultRadius, plugin.settings().defaultTime);
                if (params.error() != null) {
                    plugin.send(sender, "<red>" + Text.esc(params.error()));
                    return true;
                }
                if (sender instanceof Player p) new LookupMenu(plugin, p, params.query(), params.span()).open();
                else consoleLookup(sender, params.query());
            }
            case "report", "r", "who" -> {
                Player p = player(sender);
                if (p == null || !perm(p, "griefcheck.lookup")) return true;
                Params params = parse(sender, args, 1, plugin.settings().reportRadius, plugin.settings().reportTime);
                if (params.error() != null) {
                    plugin.send(sender, "<red>" + Text.esc(params.error()));
                    return true;
                }
                Query q = params.query();
                World w = q.world == null ? p.getWorld() : plugin.getServer().getWorld(q.world);
                Location center = new Location(w, q.x, q.y, q.z);
                new ReportMenu(plugin, p, center, q.radius < 0 ? plugin.settings().reportRadius : q.radius, params.span()).open();
            }
            case "rollback", "rb", "restore", "rs" -> {
                if (!perm(sender, "griefcheck.rollback")) return true;
                boolean rollback = sub.equals("rollback") || sub.equals("rb");
                if (args.length < 2) {
                    plugin.send(sender, "<gray>Usage: <white>/gc " + sub + " u:<player> t:<time> r:<radius> a:<action>");
                    return true;
                }
                Params params = parse(sender, args, 1, plugin.settings().defaultRadius, 0);
                if (params.error() != null) {
                    plugin.send(sender, "<red>" + Text.esc(params.error()));
                    return true;
                }
                String problem = RollbackChecks.check(plugin, params.query());
                if (problem != null) {
                    plugin.send(sender, "<red>" + Text.esc(problem));
                    return true;
                }
                plugin.rollbacks().run(sender, params.query(), rollback);
            }
            case "alerts" -> {
                Player p = player(sender);
                if (p != null && perm(p, "griefcheck.alerts")) new AlertsMenu(plugin, p).open();
            }
            case "trust", "untrust" -> {
                Player p = player(sender);
                if (p == null || !perm(p, "griefcheck.trust")) return true;
                if (args.length < 2) {
                    new TrustMenu(plugin, p).open();
                    return true;
                }
                if (sub.equals("trust")) TrustMenu.trust(plugin, p, args[1]);
                else {
                    OfflinePlayer t = plugin.getServer().getOfflinePlayerIfCached(args[1]);
                    if (t == null) {
                        plugin.msg(p, "unknown-player", "player", args[1]);
                        return true;
                    }
                    plugin.alerts().setTrust(p.getUniqueId(), t.getUniqueId(), args[1], false);
                    plugin.msg(p, "untrusted", "player", args[1]);
                }
            }
            case "settings", "config" -> {
                Player p = player(sender);
                if (p != null && perm(p, "griefcheck.admin")) new SettingsMenu(plugin, p).open();
            }
            case "purge" -> {
                if (!perm(sender, "griefcheck.admin")) return true;
                long t = args.length > 1 ? TimeUtil.parse(args[1].replaceFirst("(?i)^t:", "")) : -1;
                if (t < 86_400_000L) {
                    plugin.send(sender, "<gray>Usage: <white>/gc purge <time></white> <gray>(at least 1d) — deletes logs older than that.");
                    return true;
                }
                plugin.database().purge(System.currentTimeMillis() - t)
                        .thenAccept(n -> plugin.sync(() -> plugin.msg(sender, "purged", "count", String.valueOf(n))));
            }
            case "tp" -> {
                Player p = player(sender);
                if (p == null || !perm(p, "griefcheck.teleport") || args.length < 5) return true;
                World w = plugin.getServer().getWorld(args[1]);
                try {
                    if (w != null) p.teleportAsync(new Location(w, Integer.parseInt(args[2]) + 0.5, Integer.parseInt(args[3]) + 1,
                            Integer.parseInt(args[4]) + 0.5, p.getYaw(), p.getPitch()));
                } catch (NumberFormatException ignored) {
                }
            }
            case "reload" -> {
                if (!perm(sender, "griefcheck.admin")) return true;
                plugin.reloadSettings();
                plugin.msg(sender, "reloaded");
            }
            default -> help(sender);
        }
        return true;
    }

    private void consoleLookup(CommandSender sender, Query q) {
        plugin.database().lookup(q, 20, 0).thenAccept(rows -> plugin.sync(() -> {
            if (rows.isEmpty()) plugin.msg(sender, "no-results");
            for (LogEntry e : rows) {
                sender.sendMessage(EntryFormat.chatLine(e).append(Text.mm(" <dark_gray>@ " + Text.esc(e.world) + " "
                        + e.x + "," + e.y + "," + e.z)));
            }
        }));
    }

    private void help(CommandSender s) {
        s.sendMessage(Text.mm("<dark_gray>------ <red><bold>GriefCheck</bold></red> ------"));
        line(s, "/gc", "open the menu");
        line(s, "/gc inspect", "toggle the block inspector");
        line(s, "/gc lookup [u:player] [t:3d] [r:10] [a:break]", "search the logs");
        line(s, "/gc report [r:30] [t:7d]", "who griefed this base?");
        line(s, "/gc rollback u:player t:1d [r:20]", "undo a player's changes");
        line(s, "/gc restore u:player t:1d [r:20]", "undo a rollback");
        line(s, "/gc trust <player>", "stop alerts for a friend");
        line(s, "/gc alerts", "recent grief alerts");
        line(s, "/gc settings", "configure everything");
        line(s, "/gc purge <time>", "delete old logs");
        s.sendMessage(Text.mm("<gray>Actions: break, place, take, put, explode, burn, ignite, kill. Use r:#global for everywhere."));
    }

    private static void line(CommandSender s, String cmd, String desc) {
        s.sendMessage(Text.mm("<yellow>" + Text.esc(cmd) + " <dark_gray>- <gray>" + desc));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : SUBS) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            return out;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String cur = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (sub.equals("trust") || sub.equals("untrust")) {
            for (Player p : plugin.getServer().getOnlinePlayers()) if (p.getName().toLowerCase(Locale.ROOT).startsWith(cur)) out.add(p.getName());
            return out;
        }
        if (List.of("lookup", "l", "rollback", "rb", "restore", "rs", "report", "r").contains(sub)) {
            if (cur.startsWith("u:")) {
                for (Player p : plugin.getServer().getOnlinePlayers()) out.add("u:" + p.getName());
            } else if (cur.startsWith("t:")) {
                for (String t : List.of("30m", "1h", "6h", "1d", "3d", "7d", "30d")) out.add("t:" + t);
            } else if (cur.startsWith("r:")) {
                for (String r : List.of("5", "10", "20", "50", "100", "#global")) out.add("r:" + r);
            } else if (cur.startsWith("a:")) {
                for (Action a : Action.values()) out.add("a:" + a.key());
            } else {
                out.addAll(List.of("u:", "t:", "r:", "a:"));
            }
            out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(cur));
        }
        return out;
    }
}
