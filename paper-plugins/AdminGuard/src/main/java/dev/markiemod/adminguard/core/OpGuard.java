package dev.markiemod.adminguard.core;

import dev.markiemod.adminguard.AdminGuardPlugin;
import dev.markiemod.adminguard.util.Text;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.ArrayList;
import java.util.List;

/** Only players on the OP whitelist may be operators. Anyone else who gets OP is de-opped immediately. */
public final class OpGuard implements Listener {

    private final AdminGuardPlugin plugin;
    private boolean warnedEmpty;

    public OpGuard(AdminGuardPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::check, 40L, 40L);
    }

    public List<String> list() {
        return new ArrayList<>(plugin.getConfig().getStringList("op-whitelist.players"));
    }

    public boolean allowed(OfflinePlayer p) {
        for (String s : list()) {
            if (s.equalsIgnoreCase(p.getUniqueId().toString())) return true;
            if (p.getName() != null && s.equalsIgnoreCase(p.getName())) return true;
        }
        return false;
    }

    public void add(OfflinePlayer p) {
        List<String> l = list();
        String name = p.getName() == null ? p.getUniqueId().toString() : p.getName();
        l.removeIf(s -> s.equalsIgnoreCase(name) || s.equalsIgnoreCase(p.getUniqueId().toString()));
        l.add(name);
        plugin.getConfig().set("op-whitelist.players", l);
        plugin.saveConfig();
    }

    public void remove(String entry) {
        List<String> l = list();
        l.removeIf(s -> s.equalsIgnoreCase(entry));
        plugin.getConfig().set("op-whitelist.players", l);
        plugin.saveConfig();
    }

    public void check() {
        if (!plugin.getConfig().getBoolean("op-whitelist.enabled", false)) return;
        if (list().isEmpty()) {
            if (!warnedEmpty) plugin.getLogger().warning("op-whitelist is enabled but empty, so it is not enforced. Add yourself first.");
            warnedEmpty = true;
            return;
        }
        warnedEmpty = false;
        for (OfflinePlayer op : plugin.getServer().getOperators()) {
            if (allowed(op)) continue;
            op.setOp(false);
            String name = op.getName() == null ? op.getUniqueId().toString() : op.getName();
            plugin.audit().log(name, AuditType.OP_REVOKED, null, "not on the OP whitelist");
            Player online = op.getPlayer();
            if (online != null) {
                if (plugin.getConfig().getBoolean("op-whitelist.kick", false)) {
                    online.kick(Text.mm(plugin.getConfig().getString("messages.op-kick", "<red>You are not allowed to be an operator.")));
                } else {
                    plugin.msg(online, "op-revoked");
                }
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (e.getPlayer().isOp()) plugin.getServer().getScheduler().runTask(plugin, this::check);
    }

}
