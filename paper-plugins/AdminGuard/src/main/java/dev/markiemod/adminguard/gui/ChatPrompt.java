package dev.markiemod.adminguard.gui;

import dev.markiemod.adminguard.AdminGuardPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Asks a player to type a value in chat (used by GUIs for text input). */
public final class ChatPrompt implements Listener {

    private record Pending(Consumer<String> callback, long expires) {
    }

    private final AdminGuardPlugin plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public ChatPrompt(AdminGuardPlugin plugin) {
        this.plugin = plugin;
    }

    /** Closes the player's GUI and waits for their next chat message. "cancel" aborts. */
    public void ask(Player player, String question, Consumer<String> callback) {
        player.closeInventory();
        pending.put(player.getUniqueId(), new Pending(callback, System.currentTimeMillis() + 60_000));
        plugin.send(player, question);
        plugin.send(player, "<gray>Type <white>cancel</white> to abort.");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Pending p = pending.remove(event.getPlayer().getUniqueId());
        if (p == null) return;
        event.setCancelled(true);
        if (System.currentTimeMillis() > p.expires()) {
            plugin.send(event.getPlayer(), "<red>That prompt timed out.");
            return;
        }
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (text.equalsIgnoreCase("cancel")) {
            plugin.send(event.getPlayer(), "<gray>Cancelled.");
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> p.callback().accept(text));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }
}
