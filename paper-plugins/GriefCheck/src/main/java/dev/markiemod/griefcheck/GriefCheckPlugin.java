package dev.markiemod.griefcheck;

import dev.markiemod.griefcheck.command.GcCommand;
import dev.markiemod.griefcheck.data.Database;
import dev.markiemod.griefcheck.gui.ChatPrompt;
import dev.markiemod.griefcheck.gui.MenuListener;
import dev.markiemod.griefcheck.listener.InspectorListener;
import dev.markiemod.griefcheck.listener.LogListener;
import dev.markiemod.griefcheck.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class GriefCheckPlugin extends JavaPlugin {

    private final Settings settings = new Settings();
    private Database database;
    private AlertManager alerts;
    private RollbackManager rollbacks;
    private InspectorListener inspector;
    private LogListener logListener;
    private ChatPrompt prompt;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        settings.load(getConfig());

        database = new Database(getLogger());
        try {
            database.open(new File(getDataFolder(), "griefcheck.db"));
        } catch (Exception e) {
            getLogger().severe("Could not open the GriefCheck database: " + e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        alerts = new AlertManager(this);
        rollbacks = new RollbackManager(this);
        inspector = new InspectorListener(this);
        logListener = new LogListener(this);
        prompt = new ChatPrompt(this);
        database.setForeignListener(list -> sync(() -> list.forEach(alerts::handle)));
        alerts.loadTrust();

        var pm = getServer().getPluginManager();
        pm.registerEvents(new MenuListener(), this);
        pm.registerEvents(prompt, this);
        pm.registerEvents(logListener, this);
        pm.registerEvents(inspector, this);

        PluginCommand cmd = getCommand("griefcheck");
        if (cmd != null) {
            GcCommand executor = new GcCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        if (settings.retentionDays > 0) {
            long cutoff = System.currentTimeMillis() - settings.retentionDays * 86_400_000L;
            database.purge(cutoff).thenAccept(n -> {
                if (n > 0) getLogger().info("Purged " + n + " log entries older than " + settings.retentionDays + " days.");
            });
        }
        getLogger().info("GriefCheck enabled. Use /gc to open the menu.");
    }

    @Override
    public void onDisable() {
        if (logListener != null) logListener.flushOpenContainers();
        if (database != null) database.close();
    }

    public void reloadSettings() {
        reloadConfig();
        settings.load(getConfig());
    }

    /** Saves a changed config value from the settings GUI. */
    public void setConfig(String path, Object value) {
        getConfig().set(path, value);
        saveConfig();
        settings.load(getConfig());
    }

    public void sync(Runnable r) {
        if (getServer().isPrimaryThread()) r.run();
        else getServer().getScheduler().runTask(this, r);
    }

    public Component prefix() {
        return Text.mm(getConfig().getString("messages.prefix", "<dark_gray>[<red>GriefCheck</red>]</dark_gray> "));
    }

    /** Sends a MiniMessage string with the configured prefix. */
    public void send(CommandSender to, String miniMessage, TagResolver... resolvers) {
        to.sendMessage(prefix().append(Text.mm(miniMessage, resolvers)));
    }

    /** Sends a message from the messages section of config.yml. Placeholders are key/value pairs. */
    public void msg(CommandSender to, String key, String... placeholders) {
        String raw = getConfig().getString("messages." + key);
        if (raw == null || raw.isEmpty()) return;
        TagResolver.Builder b = TagResolver.builder();
        for (int i = 0; i + 1 < placeholders.length; i += 2) b.resolver(Placeholder.unparsed(placeholders[i], placeholders[i + 1]));
        send(to, raw, b.build());
    }

    public Settings settings() {
        return settings;
    }

    public Database database() {
        return database;
    }

    public AlertManager alerts() {
        return alerts;
    }

    public RollbackManager rollbacks() {
        return rollbacks;
    }

    public InspectorListener inspector() {
        return inspector;
    }

    public ChatPrompt prompt() {
        return prompt;
    }
}
