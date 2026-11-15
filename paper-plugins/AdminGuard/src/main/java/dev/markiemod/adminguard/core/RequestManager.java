package dev.markiemod.adminguard.core;

import dev.markiemod.adminguard.AdminGuardPlugin;
import dev.markiemod.adminguard.gui.ConfirmMenu;
import dev.markiemod.adminguard.gui.Items;
import dev.markiemod.adminguard.util.Text;
import dev.markiemod.adminguard.util.TimeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Drives a protected command through PIN → confirm → approval → execution. */
public final class RequestManager {

    private final AdminGuardPlugin plugin;
    private final Map<Integer, Request> pending = new LinkedHashMap<>();
    private final Map<UUID, Deque<Long>> recent = new HashMap<>();
    private int nextId = 1;

    public RequestManager(AdminGuardPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::expire, 20L, 20L);
    }

    public List<Request> pending() {
        List<Request> out = new ArrayList<>();
        for (Request r : pending.values()) if (r.announced && !r.finished) out.add(r);
        return out;
    }

    public Request get(int id) {
        return pending.get(id);
    }

    /** Entry point from the command listener. The original command has already been cancelled. */
    public void handle(Player p, String command, Decision d) {
        UUID id = p.getUniqueId();
        if (d.mode() == Mode.BLOCK) {
            plugin.msg(p, "blocked", "command", command);
            plugin.audit().log(p.getName(), AuditType.BLOCKED, command, "rule: " + d.ruleNames());
            return;
        }
        if (plugin.verifier().isLocked(id)) {
            plugin.msg(p, "locked-out", "time", TimeUtil.format(plugin.verifier().lockRemaining(id)));
            plugin.audit().log(p.getName(), AuditType.DENIED, command, "locked out");
            return;
        }
        int perMinute = plugin.getConfig().getInt("settings.max-protected-per-minute", 15);
        if (perMinute > 0) {
            Deque<Long> times = recent.computeIfAbsent(id, k -> new ArrayDeque<>());
            long now = System.currentTimeMillis();
            while (!times.isEmpty() && times.peekFirst() < now - 60_000L) times.pollFirst();
            if (times.size() >= perMinute) {
                plugin.msg(p, "rate-limited", "limit", String.valueOf(perMinute));
                plugin.audit().log(p.getName(), AuditType.RATE_LIMITED, command, perMinute + "/min limit");
                return;
            }
            times.addLast(now);
        }
        long open = pending.values().stream().filter(r -> r.uuid.equals(id) && !r.finished).count();
        if (open >= 3) {
            plugin.msg(p, "too-many-requests");
            return;
        }
        Request r = new Request(nextId++, id, p.getName(), command, d);
        pending.put(r.id, r);
        step(r);
    }

    private void step(Request r) {
        if (r.finished) return;
        Player p = plugin.getServer().getPlayer(r.uuid);
        if (p == null) {
            finish(r, AuditType.EXPIRED, "player left");
            return;
        }
        Mode mode = r.decision.mode();
        if (mode.needsPin() && !r.pinDone) {
            plugin.verifier().requirePin(p, r.command, false, () -> {
                r.pinDone = true;
                step(r);
            }, () -> finish(r, AuditType.CANCELLED, "PIN not entered"));
            return;
        }
        if (mode == Mode.CONFIRM && !r.confirmed) {
            new ConfirmMenu(plugin, p, Text.mm("<dark_red>Run this command?"),
                    Items.of(Material.COMMAND_BLOCK, "<gold>" + Text.esc(r.command),
                            "<gray>This command is protected by", "<gray>rule: <white>" + Text.esc(r.decision.ruleNames()),
                            "", "<gray>Double-check it before confirming."),
                    () -> {
                        r.confirmed = true;
                        step(r);
                    },
                    () -> finish(r, AuditType.CANCELLED, "not confirmed"), true).open();
            return;
        }
        if (r.approvals.size() < r.needed()) {
            if (!r.announced) announce(r, p);
            return;
        }
        execute(r, p);
    }

    private void announce(Request r, Player p) {
        r.announced = true;
        p.closeInventory();
        plugin.audit().log(r.name, AuditType.REQUESTED, r.command, "#" + r.id + " needs " + r.needed() + " approval(s)");
        int online = 0;
        Component msg = plugin.prefix().append(Text.mm("<gold>" + Text.esc(r.name) + "</gold> <gray>wants to run</gray> <white>"
                        + Text.esc(r.command) + "</white> <dark_gray>(#" + r.id + ")</dark_gray> "))
                .append(Text.mm("<green><bold>[✔ Approve]")
                        .hoverEvent(HoverEvent.showText(Text.mm("<green>Approve request #" + r.id)))
                        .clickEvent(ClickEvent.runCommand("/ag approve " + r.id)))
                .append(Component.space())
                .append(Text.mm("<red><bold>[✖ Deny]")
                        .hoverEvent(HoverEvent.showText(Text.mm("<red>Deny request #" + r.id)))
                        .clickEvent(ClickEvent.runCommand("/ag deny " + r.id)));
        boolean self = plugin.getConfig().getBoolean("settings.allow-self-approval", false);
        for (Player staff : plugin.getServer().getOnlinePlayers()) {
            if (!staff.hasPermission("adminguard.approve")) continue;
            if (staff.getUniqueId().equals(r.uuid) && !self) continue;
            online++;
            staff.sendMessage(msg);
            staff.playSound(staff.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1.2f);
            staff.showTitle(Title.title(Text.mm("<gold>Approval needed"), Text.mm("<gray>" + Text.esc(r.name) + ": <white>" + Text.esc(r.command)),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(400))));
        }
        int timeout = plugin.getConfig().getInt("settings.request-timeout-seconds", 120);
        plugin.msg(p, "waiting-approval", "id", String.valueOf(r.id), "count", String.valueOf(r.needed()),
                "time", TimeUtil.format(timeout * 1000L));
        if (online == 0) plugin.msg(p, "no-approvers-online", "id", String.valueOf(r.id));
    }

    private String approverKey(CommandSender s) {
        return s instanceof Player p ? p.getUniqueId().toString() : "console";
    }

    /** Called by /ag approve and the requests GUI. */
    public void approve(CommandSender sender, int id) {
        Request r = pending.get(id);
        if (r == null || r.finished || !r.announced) {
            plugin.msg(sender, "no-such-request", "id", String.valueOf(id));
            return;
        }
        if (sender instanceof Player p) {
            if (!p.hasPermission("adminguard.approve")) {
                plugin.msg(p, "no-permission");
                return;
            }
            if (p.getUniqueId().equals(r.uuid) && !plugin.getConfig().getBoolean("settings.allow-self-approval", false)) {
                plugin.msg(p, "cannot-self-approve");
                return;
            }
            if (r.approvals.containsKey(approverKey(p))) {
                plugin.msg(p, "already-approved");
                return;
            }
            if (plugin.getConfig().getBoolean("settings.require-pin-for-approvals", true)) {
                plugin.verifier().requirePin(p, "Approve #" + r.id + ": " + r.command, false, () -> {
                    p.closeInventory();
                    doApprove(p, r);
                }, null);
                return;
            }
        } else if (!plugin.getConfig().getBoolean("settings.allow-console-approval", true)) {
            plugin.msg(sender, "no-permission");
            return;
        }
        doApprove(sender, r);
    }

    private void doApprove(CommandSender sender, Request r) {
        if (r.finished) return;
        r.approvals.put(approverKey(sender), sender.getName());
        plugin.audit().log(sender.getName(), AuditType.APPROVED, r.command, "#" + r.id + " from " + r.name
                + " (" + r.approvals.size() + "/" + r.needed() + ")");
        plugin.msg(sender, "approved", "id", String.valueOf(r.id), "player", r.name);
        Player p = plugin.getServer().getPlayer(r.uuid);
        if (p != null) plugin.msg(p, "your-request-approved", "id", String.valueOf(r.id), "player", sender.getName(),
                "count", r.approvals.size() + "/" + r.needed());
        step(r);
    }

    public void deny(CommandSender sender, int id, String reason) {
        Request r = pending.get(id);
        if (r == null || r.finished) {
            plugin.msg(sender, "no-such-request", "id", String.valueOf(id));
            return;
        }
        boolean own = sender instanceof Player p && p.getUniqueId().equals(r.uuid);
        if (!own && !sender.hasPermission("adminguard.approve")) {
            plugin.msg(sender, "no-permission");
            return;
        }
        String why = reason == null || reason.isBlank() ? "" : reason;
        finish(r, own ? AuditType.CANCELLED : AuditType.DENIED, "by " + sender.getName() + (why.isEmpty() ? "" : ": " + why));
        Player p = plugin.getServer().getPlayer(r.uuid);
        if (p != null && !own) plugin.msg(p, "your-request-denied", "id", String.valueOf(r.id), "player", sender.getName(),
                "reason", why.isEmpty() ? "no reason given" : why);
        plugin.msg(sender, own ? "request-cancelled" : "denied", "id", String.valueOf(r.id));
    }

    private void execute(Request r, Player p) {
        r.finished = true;
        pending.remove(r.id);
        p.closeInventory();
        String cmd = r.command.startsWith("/") ? r.command.substring(1) : r.command;
        plugin.guard().allowOnce(p.getUniqueId(), r.command);
        boolean ok;
        try {
            ok = p.performCommand(cmd);
        } finally {
            plugin.guard().clearPass(p.getUniqueId());
        }
        String approvedBy = r.approvals.isEmpty() ? "" : " · approved by " + String.join(", ", r.approvals.values());
        plugin.audit().log(r.name, AuditType.EXECUTED, r.command, r.decision.mode().display + approvedBy + (ok ? "" : " · command failed"));
    }

    private void finish(Request r, AuditType type, String detail) {
        if (r.finished) return;
        r.finished = true;
        pending.remove(r.id);
        plugin.audit().log(r.name, type, r.command, "#" + r.id + " " + detail);
    }

    private void expire() {
        long timeout = plugin.getConfig().getInt("settings.request-timeout-seconds", 120) * 1000L;
        Iterator<Request> it = new ArrayList<>(pending.values()).iterator();
        long now = System.currentTimeMillis();
        while (it.hasNext()) {
            Request r = it.next();
            if (r.finished || now - r.created < timeout) continue;
            Player p = plugin.getServer().getPlayer(r.uuid);
            if (!r.announced && p != null && p.getOpenInventory().getTopInventory().getHolder(false) instanceof dev.markiemod.adminguard.gui.Menu) {
                p.closeInventory();
            }
            finish(r, AuditType.EXPIRED, "timed out");
            if (p != null) plugin.msg(p, "request-expired", "id", String.valueOf(r.id));
        }
    }
}
