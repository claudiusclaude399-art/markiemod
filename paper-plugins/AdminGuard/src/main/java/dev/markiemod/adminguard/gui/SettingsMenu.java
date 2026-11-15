package dev.markiemod.adminguard.gui;

import dev.markiemod.adminguard.AdminGuardPlugin;
import dev.markiemod.adminguard.core.AuditType;
import dev.markiemod.adminguard.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** In-game editor for the settings section of config.yml. Every change goes to the audit log. */
public final class SettingsMenu extends Menu {

    private final AdminGuardPlugin plugin;

    public SettingsMenu(AdminGuardPlugin plugin, Player viewer) {
        super(viewer, 6, Text.mm("<gold><bold>AdminGuard</bold></gold> <dark_gray>» Settings"));
        this.plugin = plugin;
    }

    private void toggle(int slot, String path, String name, String... desc) {
        boolean on = plugin.getConfig().getBoolean(path);
        set(slot, Items.toggle(on, name, desc), e -> {
            plugin.setSetting(viewer, path, !on);
            redraw();
        });
    }

    private void number(int slot, Material mat, String path, String name, int step, int min, int max, String unit, String... desc) {
        int value = plugin.getConfig().getInt(path);
        List<String> lore = new ArrayList<>(List.of(desc));
        lore.add("");
        lore.add("<gray>Current: <aqua><bold>" + value + "</bold> " + unit);
        lore.add("<yellow>Left-click +" + step + " · Right-click -" + step);
        lore.add("<yellow>Shift-click ×10 step");
        set(slot, Items.of(mat, "<aqua>" + name, lore), e -> {
            int s = e.isShiftClick() ? step * 10 : step;
            int v = Math.max(min, Math.min(max, value + (e.isRightClick() ? -s : s)));
            if (v != value) plugin.setSetting(viewer, path, v);
            redraw();
        });
    }

    @Override
    protected void draw() {
        set(4, Items.of(Material.WRITABLE_BOOK, "<light_purple>AdminGuard settings",
                "<gray>Changes save to config.yml right away", "<gray>and are recorded in the audit log."));

        set(9, Items.of(Material.TRIPWIRE_HOOK, "<white><bold>PIN & sessions", "<gray>→"));
        number(10, Material.CLOCK, "settings.session-minutes", "Verified session length", 1, 0, 1440, "min",
                "<gray>After a correct PIN, don't ask again", "<gray>for this long. 0 = ask every time.");
        number(11, Material.NAME_TAG, "settings.pin-min-length", "Minimum PIN length", 1, 4, 12, "digits");
        number(12, Material.TRIPWIRE_HOOK, "settings.max-pin-attempts", "Wrong PINs before lockout", 1, 1, 20, "tries");
        number(13, Material.IRON_BARS, "settings.lockout-minutes", "Lockout length", 5, 1, 1440, "min",
                "<gray>Locked players can't run protected", "<gray>commands and staff are alerted.");
        toggle(14, "settings.end-session-on-quit", "End session on logout");
        toggle(15, "keypad.shuffle", "Shuffle keypad digits", "<gray>Random digit positions every time,", "<gray>so screen recordings don't leak PINs.");
        toggle(16, "settings.require-pin-for-settings", "PIN for these settings", "<gray>Ask for a PIN before rules/settings", "<gray>can be changed in-game.");

        set(18, Items.of(Material.PLAYER_HEAD, "<white><bold>Approvals", "<gray>→"));
        number(19, Material.CLOCK, "settings.request-timeout-seconds", "Request timeout", 30, 10, 86_400, "s",
                "<gray>Unapproved requests expire after this.");
        toggle(20, "settings.require-pin-for-approvals", "PIN to approve", "<gray>Approvers must enter their own PIN.");
        toggle(21, "settings.allow-self-approval", "Allow self-approval", "<red>Not recommended: lets one admin", "<red>approve their own commands.");
        toggle(22, "settings.allow-console-approval", "Console can approve", "<gray>/ag approve <id> from the console.");
        number(23, Material.REPEATER, "settings.confirm-delay-ms", "Confirm button delay", 250, 0, 5000, "ms",
                "<gray>Confirm can't be clicked until this", "<gray>long after the screen opens.");

        set(27, Items.of(Material.SHIELD, "<white><bold>Protection", "<gray>→"));
        number(28, Material.COBWEB, "settings.max-protected-per-minute", "Rate limit", 1, 0, 600, "/min",
                "<gray>Max protected commands per player", "<gray>per minute. 0 = unlimited.");
        toggle(29, "settings.skip-if-no-permission", "Ignore commands they can't use",
                "<gray>Don't prompt for commands the player", "<gray>has no permission for anyway.");
        toggle(30, "settings.block-command-blocks", "Block in command blocks",
                "<gray>Command blocks can't run commands that", "<gray>need a PIN, approval or are blocked.");
        toggle(31, "settings.log-console", "Log console use", "<gray>Write protected commands run from", "<gray>the console/RCON to the audit log.");

        set(36, Items.of(Material.BELL, "<white><bold>Notifications", "<gray>→"));
        boolean hook = plugin.getConfig().getBoolean("webhook.enabled");
        String url = plugin.getConfig().getString("webhook.url", "");
        toggle(37, "webhook.enabled", "Discord webhook", "<gray>Post audit events to Discord.",
                "<gray>URL: " + (url == null || url.isBlank() ? "<red>not set" : "<green>set"));
        set(38, Items.of(Material.WRITABLE_BOOK, "<aqua>Set webhook URL", "<gray>Paste a Discord webhook URL in chat.",
                "<gray>Status: " + (url == null || url.isBlank() ? "<red>not set" : "<green>set"),
                "", "<yellow>Left-click to set · Right-click to test"), e -> {
            if (e.isRightClick()) {
                plugin.webhook().send("✅ AdminGuard webhook test from **" + viewer.getName() + "**");
                plugin.send(viewer, hook ? "<green>Test message sent." : "<yellow>Sent a test (the webhook is currently disabled).");
                return;
            }
            plugin.prompt().ask(viewer, "<yellow>Paste the webhook URL (starts with https://):", text -> {
                if (!text.startsWith("https://")) {
                    plugin.send(viewer, "<red>That doesn't look like a webhook URL.");
                } else {
                    plugin.setSetting(viewer, "webhook.url", text);
                    plugin.send(viewer, "<green>Webhook URL saved.");
                }
                open();
            });
        });
        set(40, Items.of(Material.PAPER, "<gray>Event lists",
                "<gray>Which events go to staff chat and the", "<gray>webhook is set in config.yml:",
                "<white>notifications.staff-chat", "<white>webhook.events"));

        set(45, Items.of(Material.OAK_DOOR, "<yellow>Back to menu"), e -> new MainMenu(plugin, viewer).open());
        set(53, Items.of(Material.SUNFLOWER, "<yellow>Reload config.yml", "<gray>Use after editing files by hand"), e -> {
            plugin.reloadAll();
            plugin.audit().log(viewer.getName(), AuditType.SETTINGS, null, "config reloaded");
            plugin.msg(viewer, "reloaded");
            redraw();
        });
    }
}
