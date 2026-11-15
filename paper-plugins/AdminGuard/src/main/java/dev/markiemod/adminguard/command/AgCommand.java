package dev.markiemod.adminguard.command;

import dev.markiemod.adminguard.AdminGuardPlugin;
import dev.markiemod.adminguard.core.AuditType;
import dev.markiemod.adminguard.core.Decision;
import dev.markiemod.adminguard.core.Request;
import dev.markiemod.adminguard.gui.AuditMenu;
import dev.markiemod.adminguard.gui.MainMenu;
import dev.markiemod.adminguard.gui.OpWhitelistMenu;
import dev.markiemod.adminguard.gui.RequestsMenu;
import dev.markiemod.adminguard.gui.RulesMenu;
import dev.markiemod.adminguard.gui.SettingsMenu;
import dev.markiemod.adminguard.util.Text;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class AgCommand implements TabExecutor {

    private static final List<String> SUBS = List.of("menu", "setpin", "lock", "approve", "deny", "requests", "audit",
            "rules", "settings", "opwhitelist", "check", "resetpin", "unlock", "reload", "help");

    private final AdminGuardPlugin plugin;

    public AgCommand(AdminGuardPlugin plugin) {
        this.plugin = plugin;
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

    private static Integer id(String s) {
        try {
            return Integer.parseInt(s.replace("#", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "menu", "gui" -> {
                Player p = player(sender);
                if (p != null && perm(p, "adminguard.use")) new MainMenu(plugin, p).open();
            }
            case "setpin", "pin" -> {
                Player p = player(sender);
                if (p != null && perm(p, "adminguard.use")) plugin.verifier().setPin(p);
            }
            case "lock", "logout" -> {
                Player p = player(sender);
                if (p == null) return true;
                plugin.verifier().endSession(p.getUniqueId());
                plugin.msg(p, "session-ended");
            }
            case "approve", "accept", "a" -> {
                if (args.length < 2 || id(args[1]) == null) {
                    plugin.send(sender, "<gray>Usage: <white>/ag approve <id>");
                    return true;
                }
                plugin.requests().approve(sender, id(args[1]));
            }
            case "deny", "reject", "d" -> {
                if (args.length < 2 || id(args[1]) == null) {
                    plugin.send(sender, "<gray>Usage: <white>/ag deny <id> [reason]");
                    return true;
                }
                String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : null;
                plugin.requests().deny(sender, id(args[1]), reason);
            }
            case "requests", "pending" -> {
                if (!perm(sender, "adminguard.approve")) return true;
                if (sender instanceof Player p) {
                    new RequestsMenu(plugin, p).open();
                    return true;
                }
                List<Request> list = plugin.requests().pending();
                if (list.isEmpty()) plugin.send(sender, "<gray>No pending requests.");
                for (Request r : list) {
                    plugin.send(sender, "<gold>#" + r.id + " <white>" + Text.esc(r.name) + "</white> <gray>" + Text.esc(r.command)
                            + " <dark_gray>(" + r.approvals.size() + "/" + r.needed() + ")");
                }
            }
            case "audit", "log" -> {
                Player p = player(sender);
                if (p != null && perm(p, "adminguard.audit")) new AuditMenu(plugin, p).open();
            }
            case "rules" -> {
                Player p = player(sender);
                if (p != null) plugin.manage(p, () -> new RulesMenu(plugin, p).open());
            }
            case "settings", "config" -> {
                Player p = player(sender);
                if (p != null) plugin.manage(p, () -> new SettingsMenu(plugin, p).open());
            }
            case "opwhitelist", "ops" -> {
                Player p = player(sender);
                if (p != null) plugin.manage(p, () -> new OpWhitelistMenu(plugin, p).open());
            }
            case "check", "test" -> {
                if (!perm(sender, "adminguard.admin")) return true;
                if (args.length < 2) {
                    plugin.send(sender, "<gray>Usage: <white>/ag check <command...></white> <gray>— shows which rule would apply.");
                    return true;
                }
                String line = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                Decision d = plugin.rules().evaluate(line, null);
                if (d == null) plugin.send(sender, "<green>/" + Text.esc(line.replaceFirst("^/", "")) + " <gray>is not protected.");
                else plugin.send(sender, "<white>/" + Text.esc(line.replaceFirst("^/", "")) + " <gray>→ " + d.mode().color + d.mode().display
                        + (d.mode().needsApproval() ? " <gray>(" + d.approvals() + " approval(s))" : "")
                        + " <dark_gray>rule: " + Text.esc(d.ruleNames()));
            }
            case "resetpin", "unlock" -> {
                if (!perm(sender, "adminguard.admin")) return true;
                if (args.length < 2) {
                    plugin.send(sender, "<gray>Usage: <white>/ag " + sub + " <player>");
                    return true;
                }
                OfflinePlayer target = plugin.getServer().getOfflinePlayerIfCached(args[1]);
                if (target == null) {
                    plugin.msg(sender, "unknown-player", "player", args[1]);
                    return true;
                }
                Runnable action = () -> {
                    if (sub.equals("resetpin")) {
                        plugin.pins().remove(target.getUniqueId());
                        plugin.verifier().endSession(target.getUniqueId());
                        plugin.audit().log(sender.getName(), AuditType.PIN_CHANGED, null, "reset PIN of " + target.getName());
                        plugin.send(sender, "<green>PIN of " + Text.esc(target.getName()) + " was reset. They can set a new one with /ag setpin.");
                    } else {
                        plugin.verifier().unlock(target.getUniqueId());
                        plugin.audit().log(sender.getName(), AuditType.SETTINGS, null, "unlocked " + target.getName());
                        plugin.send(sender, "<green>" + Text.esc(target.getName()) + " is no longer locked out.");
                    }
                };
                if (sender instanceof Player p) plugin.manage(p, () -> {
                    p.closeInventory();
                    action.run();
                });
                else action.run();
            }
            case "reload" -> {
                if (!perm(sender, "adminguard.admin")) return true;
                if (sender instanceof Player p) {
                    plugin.manage(p, () -> {
                        p.closeInventory();
                        plugin.reloadAll();
                        plugin.audit().log(p.getName(), AuditType.SETTINGS, null, "config reloaded");
                        plugin.msg(p, "reloaded");
                    });
                } else {
                    plugin.reloadAll();
                    plugin.audit().log(sender.getName(), AuditType.SETTINGS, null, "config reloaded");
                    plugin.msg(sender, "reloaded");
                }
            }
            default -> help(sender);
        }
        return true;
    }

    private void help(CommandSender s) {
        s.sendMessage(Text.mm("<dark_gray>------ <gold><bold>AdminGuard</bold></gold> ------"));
        line(s, "/ag", "open the menu");
        line(s, "/ag setpin", "set or change your PIN");
        line(s, "/ag lock", "end your verified session");
        line(s, "/ag approve <id> | deny <id> [reason]", "handle requests");
        line(s, "/ag requests | audit", "pending approvals / audit log");
        line(s, "/ag rules | settings | opwhitelist", "configure (admins)");
        line(s, "/ag check <command>", "see which rule a command hits");
        line(s, "/ag resetpin <player> | unlock <player>", "help a staff member");
        line(s, "/ag reload", "reload config.yml");
    }

    private static void line(CommandSender s, String cmd, String desc) {
        s.sendMessage(Text.mm("<yellow>" + Text.esc(cmd) + " <dark_gray>- <gray>" + desc));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : SUBS) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("approve") || sub.equals("deny")) {
                for (Request r : plugin.requests().pending()) out.add(String.valueOf(r.id));
            } else if (sub.equals("resetpin") || sub.equals("unlock")) {
                for (Player p : plugin.getServer().getOnlinePlayers()) out.add(p.getName());
            }
        }
        return out;
    }
}
