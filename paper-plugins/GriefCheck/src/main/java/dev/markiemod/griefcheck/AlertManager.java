package dev.markiemod.griefcheck;

import dev.markiemod.griefcheck.data.Action;
import dev.markiemod.griefcheck.data.ForeignAction;
import dev.markiemod.griefcheck.util.Text;
import dev.markiemod.griefcheck.util.TimeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watches for players breaking, blowing up or looting things that other players placed, and warns staff
 * when someone does a lot of it in a short time. Players can trust friends so shared bases don't alert.
 */
public final class AlertManager {

    public record Alert(long time, String actor, UUID actorUuid, Set<String> victims, String world, int x, int y, int z,
                        int count) {
    }

    private final GriefCheckPlugin plugin;
    private final Map<UUID, Deque<ForeignAction>> recent = new HashMap<>();
    private final Map<UUID, Long> lastAlert = new HashMap<>();
    private final Deque<Alert> history = new ArrayDeque<>();
    private Map<UUID, Map<UUID, String>> trust = new ConcurrentHashMap<>();

    public AlertManager(GriefCheckPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadTrust() {
        plugin.database().loadTrust().thenAccept(t -> trust = t);
    }

    public boolean trusts(UUID owner, UUID other) {
        Map<UUID, String> set = trust.get(owner);
        return set != null && set.containsKey(other);
    }

    public Map<UUID, String> trusted(UUID owner) {
        return Collections.unmodifiableMap(trust.getOrDefault(owner, Map.of()));
    }

    public void setTrust(UUID owner, UUID other, String name, boolean add) {
        if (add) trust.computeIfAbsent(owner, k -> new ConcurrentHashMap<>()).put(other, name);
        else {
            Map<UUID, String> set = trust.get(owner);
            if (set != null) set.remove(other);
        }
        plugin.database().setTrust(owner, other, name, add);
    }

    public List<Alert> history() {
        return new ArrayList<>(history);
    }

    /** Called on the main thread for every foreign break/explode/take. */
    public void handle(ForeignAction fa) {
        Settings s = plugin.settings();
        if (!s.alertsEnabled) return;
        if (fa.action() == Action.TAKE && !s.alertContainers) return;
        if (trusts(fa.owner(), fa.actor())) return;
        Player actor = plugin.getServer().getPlayer(fa.actor());
        if (actor != null && actor.hasPermission("griefcheck.alerts.exempt")) return;

        long now = System.currentTimeMillis();
        Deque<ForeignAction> list = recent.computeIfAbsent(fa.actor(), k -> new ArrayDeque<>());
        list.addLast(fa);
        long cutoff = now - s.alertWindowSeconds * 1000L;
        while (!list.isEmpty() && list.peekFirst().time() < cutoff) list.pollFirst();
        if (list.size() < s.alertThreshold) return;
        if (now - lastAlert.getOrDefault(fa.actor(), 0L) < s.alertCooldownSeconds * 1000L) return;
        lastAlert.put(fa.actor(), now);

        Set<String> victims = new LinkedHashSet<>();
        for (ForeignAction a : list) victims.add(a.ownerName());
        Alert alert = new Alert(now, fa.actorName(), fa.actor(), victims, fa.world(), fa.x(), fa.y(), fa.z(), list.size());
        history.addFirst(alert);
        while (history.size() > 100) history.pollLast();
        list.clear();
        broadcast(alert);
    }

    private void broadcast(Alert a) {
        String victims = String.join(", ", a.victims());
        String where = a.world() + " " + a.x() + ", " + a.y() + ", " + a.z();
        String window = TimeUtil.format(plugin.settings().alertWindowSeconds * 1000L);
        String raw = plugin.getConfig().getString("messages.alert",
                "<red>⚠ <white><player></white> broke/looted <yellow><count></yellow> things owned by <white><victims></white> in <window> at <gray><where></gray>");
        Component msg = plugin.prefix().append(Text.mm(raw,
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("player", a.actor()),
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("count", String.valueOf(a.count())),
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("victims", victims),
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("window", window),
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("where", where)))
                .append(Component.space())
                .append(Text.mm("<aqua>[Teleport]")
                        .hoverEvent(HoverEvent.showText(Text.mm("<gray>Teleport to the spot")))
                        .clickEvent(ClickEvent.runCommand("/gc tp " + a.world() + " " + a.x() + " " + a.y() + " " + a.z())))
                .append(Component.space())
                .append(Text.mm("<gold>[Lookup]")
                        .hoverEvent(HoverEvent.showText(Text.mm("<gray>See everything " + Text.esc(a.actor()) + " did in the last hour")))
                        .clickEvent(ClickEvent.runCommand("/gc lookup u:" + a.actor() + " t:1h r:#global")))
                .append(Component.space())
                .append(Text.mm("<red>[Report]")
                        .hoverEvent(HoverEvent.showText(Text.mm("<gray>Open the grief report for this area")))
                        .clickEvent(ClickEvent.runCommand("/gc report @" + a.world() + "," + a.x() + "," + a.y() + "," + a.z())));
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (!p.hasPermission("griefcheck.alerts")) continue;
            p.sendMessage(msg);
            if (plugin.settings().alertSound) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.6f);
        }
        plugin.getLogger().warning("[Alert] " + a.actor() + " broke/looted " + a.count() + " things owned by " + victims + " at " + where);
    }
}
