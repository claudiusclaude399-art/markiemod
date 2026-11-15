package dev.markiemod.griefcheck.gui;

import dev.markiemod.griefcheck.GriefCheckPlugin;
import dev.markiemod.griefcheck.util.Text;
import dev.markiemod.griefcheck.util.TimeUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.ArrayList;
import java.util.List;

/** In-game editor for config.yml. Every change is saved straight away. */
public final class SettingsMenu extends Menu {

    private static final String[] TIME_PRESETS = {"1h", "6h", "12h", "1d", "3d", "7d", "14d", "30d"};

    private final GriefCheckPlugin plugin;

    public SettingsMenu(GriefCheckPlugin plugin, Player viewer) {
        super(viewer, 6, Text.mm("<dark_red><bold>GriefCheck</bold></dark_red> <dark_gray>» Settings"));
        this.plugin = plugin;
    }

    private void toggle(int slot, String path, String name, String... desc) {
        boolean on = plugin.getConfig().getBoolean(path);
        set(slot, Items.toggle(on, name, desc), e -> {
            plugin.setConfig(path, !on);
            redraw();
        });
    }

    private void number(int slot, Material mat, String path, String name, int step, int min, int max, String... desc) {
        int value = plugin.getConfig().getInt(path);
        List<String> lore = new ArrayList<>(List.of(desc));
        lore.add("");
        lore.add("<gray>Current: <aqua><bold>" + value);
        lore.add("<yellow>Left-click +" + step + " · Right-click -" + step);
        lore.add("<yellow>Shift-click ×10 step");
        set(slot, Items.of(mat, "<aqua>" + name, lore), e -> {
            int s = e.isShiftClick() ? step * 10 : step;
            int v = Math.max(min, Math.min(max, value + (e.isRightClick() ? -s : s)));
            plugin.setConfig(path, v);
            redraw();
        });
    }

    private void time(int slot, String path, String name, String... desc) {
        String value = plugin.getConfig().getString(path, "3d");
        List<String> lore = new ArrayList<>(List.of(desc));
        lore.add("");
        lore.add("<gray>Current: <aqua><bold>" + TimeUtil.format(TimeUtil.parse(value)));
        lore.add("<yellow>Left/right-click to change");
        set(slot, Items.of(Material.CLOCK, "<aqua>" + name, lore), e -> {
            int i = 0;
            for (int j = 0; j < TIME_PRESETS.length; j++) if (TIME_PRESETS[j].equalsIgnoreCase(value)) i = j;
            plugin.setConfig(path, TIME_PRESETS[Math.floorMod(i + (e.isRightClick() ? -1 : 1), TIME_PRESETS.length)]);
            redraw();
        });
    }

    @Override
    protected void draw() {
        set(4, Items.of(Material.WRITABLE_BOOK, "<light_purple>GriefCheck settings",
                "<gray>Changes are saved to config.yml", "<gray>and apply immediately."));

        // What to log
        set(9, Items.of(Material.OAK_SIGN, "<white><bold>Logging", "<gray>Which actions are recorded →"));
        toggle(10, "logging.block-break", "Block breaking");
        toggle(11, "logging.block-place", "Block placing");
        toggle(12, "logging.containers", "Chest transactions", "<gray>Items taken from / put into containers");
        toggle(13, "logging.explosions", "Explosions", "<gray>TNT (credited to whoever lit it), creepers, beds...");
        toggle(14, "logging.fire", "Fire", "<gray>Blocks burned and who lit fires");
        toggle(15, "logging.buckets", "Buckets", "<gray>Water/lava placed or picked up");
        toggle(16, "logging.entity-kills", "Animal kills", "<gray>Pets, villagers, animals, item frames...");
        toggle(17, "logging.mob-block-changes", "Mob griefing", "<gray>Endermen, withers, ravagers...");

        // Alerts
        set(18, Items.of(Material.BELL, "<white><bold>Grief alerts", "<gray>Warn staff about suspicious players →"));
        toggle(19, "alerts.enabled", "Grief alerts", "<gray>Notify staff when a player breaks", "<gray>lots of other people's blocks fast");
        number(20, Material.REDSTONE, "alerts.threshold", "Alert threshold", 1, 1, 10_000,
                "<gray>Foreign blocks broken/looted", "<gray>before an alert fires");
        number(21, Material.CLOCK, "alerts.window-seconds", "Alert time window (s)", 10, 5, 86_400,
                "<gray>...within this many seconds");
        number(22, Material.REPEATER, "alerts.cooldown-seconds", "Alert cooldown (s)", 30, 0, 86_400,
                "<gray>Wait before alerting about", "<gray>the same player again");
        toggle(23, "alerts.count-container-takes", "Count chest theft", "<gray>Taking from others' chests counts");
        toggle(24, "alerts.sound", "Alert sound");
        boolean worldExcluded = plugin.getConfig().getStringList("logging.excluded-worlds").contains(viewer.getWorld().getName());
        set(26, Items.of(worldExcluded ? Material.BARRIER : Material.GRASS_BLOCK,
                (worldExcluded ? "<red>" : "<green>") + "Log this world: " + (worldExcluded ? "NO" : "YES"),
                "<gray>World: <white>" + Text.esc(viewer.getWorld().getName()), "", "<yellow>Click to toggle"), this::toggleWorld);

        // Lookups / rollbacks
        set(27, Items.of(Material.SPYGLASS, "<white><bold>Lookups & rollbacks", "<gray>Defaults and limits →"));
        number(28, Material.COMPASS, "lookup.default-radius", "Lookup radius", 1, 1, 500, "<gray>Default for 'Lookup nearby'");
        time(29, "lookup.default-time", "Lookup time", "<gray>Default time range for lookups");
        number(30, Material.IRON_SWORD, "report.default-radius", "Grief report radius", 5, 1, 500, "<gray>Default for the grief report");
        time(31, "report.default-time", "Grief report time", "<gray>Default time range for the report");
        number(32, Material.TNT, "rollback.max-radius", "Max rollback radius", 10, 1, 10_000,
                "<gray>Bigger rollbacks need a player");
        number(33, Material.HOPPER, "rollback.blocks-per-tick", "Rollback speed", 50, 10, 10_000,
                "<gray>Blocks changed per tick", "<gray>(lower = less lag)");
        number(34, Material.PAPER, "inspector.lines-per-page", "Inspector lines", 1, 3, 30, "<gray>Lines per page in chat");
        number(35, Material.CHEST_MINECART, "retention-days", "Keep logs (days)", 5, 0, 3650,
                "<gray>Older entries are deleted on", "<gray>startup. 0 = keep forever.");

        set(45, Items.of(Material.OAK_DOOR, "<yellow>Back to menu"), e -> new MainMenu(plugin, viewer).open());
        set(53, Items.of(Material.SUNFLOWER, "<yellow>Reload config.yml", "<gray>Use after editing the file by hand"), e -> {
            plugin.reloadSettings();
            plugin.msg(viewer, "reloaded");
            redraw();
        });
    }

    private void toggleWorld(InventoryClickEvent e) {
        List<String> worlds = new ArrayList<>(plugin.getConfig().getStringList("logging.excluded-worlds"));
        String w = viewer.getWorld().getName();
        if (!worlds.remove(w)) worlds.add(w);
        plugin.setConfig("logging.excluded-worlds", worlds);
        redraw();
    }
}
