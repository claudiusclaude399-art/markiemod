package dev.markiemod.adminguard.core;

import dev.markiemod.adminguard.AdminGuardPlugin;
import dev.markiemod.adminguard.util.Text;
import dev.markiemod.adminguard.util.TimeUtil;
import org.bukkit.entity.Player;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Append-only audit trail (audit.log, one tab-separated line per event). The newest entries are kept in
 * memory for the GUI; every entry can also be sent to staff chat and a Discord webhook.
 */
public final class AuditLog {

    public record Entry(long time, String actor, AuditType type, String command, String detail) {
    }

    private static final int MEMORY = 2000;

    private final AdminGuardPlugin plugin;
    private final File file;
    private final Deque<Entry> recent = new ArrayDeque<>();

    public AuditLog(AdminGuardPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "audit.log");
        load();
    }

    private void load() {
        if (!file.exists()) return;
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            for (int i = Math.max(0, lines.size() - MEMORY); i < lines.size(); i++) {
                String[] p = lines.get(i).split("\t", -1);
                if (p.length < 5) continue;
                try {
                    recent.addFirst(new Entry(Long.parseLong(p[0]), p[1], AuditType.valueOf(p[2]), unescape(p[3]), unescape(p[4])));
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read audit.log: " + e.getMessage());
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n");
    }

    private static String unescape(String s) {
        return s.replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\");
    }

    public List<Entry> recent() {
        return new ArrayList<>(recent);
    }

    public void log(String actor, AuditType type, String command, String detail) {
        Entry e = new Entry(System.currentTimeMillis(), actor, type, command == null ? "" : command, detail == null ? "" : detail);
        recent.addFirst(e);
        while (recent.size() > MEMORY) recent.pollLast();
        String line = e.time() + "\t" + escape(actor) + "\t" + type.name() + "\t" + escape(e.command()) + "\t" + escape(e.detail())
                + System.lineSeparator();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> write(line));

        plugin.getLogger().info("[Audit] " + type + " " + actor + (e.command().isEmpty() ? "" : " " + e.command())
                + (e.detail().isEmpty() ? "" : " (" + e.detail() + ")"));

        if (plugin.getConfig().getStringList("notifications.staff-chat").stream().anyMatch(t -> t.equalsIgnoreCase(type.name()))) {
            String msg = plugin.getConfig().getString("messages.staff-notify",
                    "<dark_gray>[<gold>Audit</gold>]</dark_gray> <type_color><type></type_color> <white><player></white> <gray><command></gray> <dark_gray><detail>");
            msg = msg.replace("<type_color>", type.color).replace("</type_color>", "");
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                if (p.hasPermission("adminguard.notify")) {
                    p.sendMessage(Text.mm(msg,
                            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("type", type.pretty()),
                            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("player", actor),
                            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("command", e.command()),
                            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("detail", e.detail())));
                }
            }
        }
        if (plugin.getConfig().getBoolean("webhook.enabled")
                && plugin.getConfig().getStringList("webhook.events").stream().anyMatch(t -> t.equalsIgnoreCase(type.name()))) {
            plugin.webhook().send("**" + type.pretty() + "** · `" + actor + "`"
                    + (e.command().isEmpty() ? "" : " · `" + e.command().replace("`", "'") + "`")
                    + (e.detail().isEmpty() ? "" : "\n" + e.detail()) + "\n-# " + TimeUtil.date(e.time()));
        }
    }

    private synchronized void write(String line) {
        try (BufferedWriter w = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(line);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not write audit.log: " + ex.getMessage());
        }
    }
}
