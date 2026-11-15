package dev.markiemod.adminguard.core;

import dev.markiemod.adminguard.AdminGuardPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Posts audit events to a Discord (or compatible) webhook, off the main thread. */
public final class Webhook {

    private final AdminGuardPlugin plugin;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public Webhook(AdminGuardPlugin plugin) {
        this.plugin = plugin;
    }

    static String json(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    public void send(String content) {
        String url = plugin.getConfig().getString("webhook.url", "");
        if (url == null || url.isBlank()) return;
        String name = plugin.getConfig().getString("webhook.username", "AdminGuard");
        if (content.length() > 1900) content = content.substring(0, 1900) + "…";
        String body = "{\"username\":" + json(name) + ",\"content\":" + json(content)
                + ",\"allowed_mentions\":{\"parse\":[]}}";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            client.sendAsync(req, HttpResponse.BodyHandlers.discarding()).thenAccept(res -> {
                if (res.statusCode() >= 300) plugin.getLogger().warning("Webhook returned HTTP " + res.statusCode());
            }).exceptionally(t -> {
                plugin.getLogger().warning("Webhook failed: " + t.getMessage());
                return null;
            });
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid webhook URL: " + e.getMessage());
        }
    }
}
