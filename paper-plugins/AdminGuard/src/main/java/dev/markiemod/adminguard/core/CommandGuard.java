package dev.markiemod.adminguard.core;

import dev.markiemod.adminguard.AdminGuardPlugin;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.CommandMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Catches protected commands before any plugin or the server sees them. */
public final class CommandGuard implements Listener {

    private final AdminGuardPlugin plugin;
    private final Map<UUID, String> passes = new HashMap<>();
    private final Map<String, Long> commandBlockSpam = new HashMap<>();

    public CommandGuard(AdminGuardPlugin plugin) {
        this.plugin = plugin;
    }

    /** Lets exactly this command through once (used when running an approved command). */
    public void allowOnce(UUID uuid, String command) {
        passes.put(uuid, command);
    }

    public void clearPass(UUID uuid) {
        passes.remove(uuid);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        String message = e.getMessage();
        String pass = passes.get(p.getUniqueId());
        if (pass != null && pass.equals(message)) {
            passes.remove(p.getUniqueId());
            return;
        }
        Decision d = plugin.rules().evaluate(message, p);
        if (d == null) return;
        if (p.hasPermission("adminguard.bypass")) {
            plugin.audit().log(p.getName(), AuditType.BYPASSED, message, "rule: " + d.ruleNames());
            return;
        }
        if (d.mode() == Mode.LOG) {
            plugin.audit().log(p.getName(), AuditType.LOGGED, message, "rule: " + d.ruleNames());
            return;
        }
        e.setCancelled(true);
        plugin.requests().handle(p, message, d);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent e) {
        CommandSender sender = e.getSender();
        String command = e.getCommand();
        boolean isBlock = sender instanceof BlockCommandSender || sender instanceof CommandMinecart;
        if (isBlock) {
            if (!plugin.getConfig().getBoolean("settings.block-command-blocks", true)) return;
            Decision d = plugin.rules().evaluate(command, null);
            if (d == null || d.mode() == Mode.LOG || d.mode() == Mode.CONFIRM) return;
            e.setCancelled(true);
            String where = sender instanceof BlockCommandSender b
                    ? b.getBlock().getWorld().getName() + " " + b.getBlock().getX() + "," + b.getBlock().getY() + "," + b.getBlock().getZ()
                    : "command minecart";
            long now = System.currentTimeMillis();
            Long last = commandBlockSpam.get(where + command);
            if (last == null || now - last > 60_000L) {
                commandBlockSpam.put(where + command, now);
                plugin.audit().log("CommandBlock", AuditType.COMMAND_BLOCK, "/" + command, "blocked at " + where);
            }
            return;
        }
        if (plugin.getConfig().getBoolean("settings.log-console", true)) {
            Decision d = plugin.rules().evaluate(command, null);
            if (d != null) plugin.audit().log(sender.getName(), AuditType.CONSOLE, "/" + command, "rule: " + d.ruleNames());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        passes.remove(e.getPlayer().getUniqueId());
        if (plugin.getConfig().getBoolean("settings.end-session-on-quit", true)) plugin.verifier().endSession(e.getPlayer().getUniqueId());
    }
}
