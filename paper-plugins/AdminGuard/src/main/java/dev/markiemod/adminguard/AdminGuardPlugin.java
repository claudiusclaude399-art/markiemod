package dev.markiemod.adminguard;

import dev.markiemod.adminguard.command.AgCommand;
import dev.markiemod.adminguard.core.AuditLog;
import dev.markiemod.adminguard.core.AuditType;
import dev.markiemod.adminguard.core.CommandGuard;
import dev.markiemod.adminguard.core.OpGuard;
import dev.markiemod.adminguard.core.PinStore;
import dev.markiemod.adminguard.core.RequestManager;
import dev.markiemod.adminguard.core.RuleManager;
import dev.markiemod.adminguard.core.Verifier;
import dev.markiemod.adminguard.core.Webhook;
import dev.markiemod.adminguard.gui.ChatPrompt;
import dev.markiemod.adminguard.gui.MenuListener;
import dev.markiemod.adminguard.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;

public final class AdminGuardPlugin extends JavaPlugin {

    private RuleManager rules;
    private PinStore pins;
    private AuditLog audit;
    private Webhook webhook;
    private Verifier verifier;
    private RequestManager requests;
    private CommandGuard guard;
    private OpGuard opGuard;
    private ChatPrompt prompt;

    @Override
    public void onEnable() {
        // Settings missing from an older config.yml fall back to the defaults inside the jar.
        saveDefaultConfig();
        webhook = new Webhook(this);
        audit = new AuditLog(this);
        rules = new RuleManager(this);
        rules.load();
        pins = new PinStore(new File(getDataFolder(), "pins.yml"), getLogger());
        verifier = new Verifier(this);
        requests = new RequestManager(this);
        guard = new CommandGuard(this);
        opGuard = new OpGuard(this);
        prompt = new ChatPrompt(this);

        var pm = getServer().getPluginManager();
        pm.registerEvents(new MenuListener(), this);
        pm.registerEvents(prompt, this);
        pm.registerEvents(guard, this);
        pm.registerEvents(opGuard, this);

        PluginCommand cmd = getCommand("adminguard");
        if (cmd != null) {
            AgCommand executor = new AgCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }
        getLogger().info("AdminGuard enabled with " + rules.all().size() + " rules. Staff: set a PIN with /ag setpin.");
    }

    public void reloadAll() {
        reloadConfig();
        rules.load();
        pins.reload();
    }

    /** May this player change rules and settings in-game? */
    public boolean canManage(Player p) {
        if (!p.hasPermission("adminguard.admin")) return false;
        List<String> owners = getConfig().getStringList("owners");
        if (owners.isEmpty()) return true;
        for (String o : owners) {
            if (o.equalsIgnoreCase(p.getName()) || o.equalsIgnoreCase(p.getUniqueId().toString())) return true;
        }
        return false;
    }

    /** Checks permission (and PIN if configured) before letting a player into a settings screen. */
    public void manage(Player p, Runnable then) {
        if (!canManage(p)) {
            msg(p, "not-owner");
            return;
        }
        if (getConfig().getBoolean("settings.require-pin-for-settings", true)) {
            verifier.requirePin(p, "Change AdminGuard settings", false, then, null);
        } else {
            then.run();
        }
    }

    /** Saves a setting changed in the GUI and writes it to the audit log. */
    public void setSetting(Player who, String path, Object value) {
        Object old = getConfig().get(path);
        getConfig().set(path, value);
        saveConfig();
        String shown = path.equals("webhook.url") ? "(hidden)" : String.valueOf(value);
        audit.log(who.getName(), AuditType.SETTINGS, null, path + ": " + (path.equals("webhook.url") ? "(hidden)" : old) + " → " + shown);
    }

    public Component prefix() {
        return Text.mm(getConfig().getString("messages.prefix", "<dark_gray>[<gold>AdminGuard</gold>]</dark_gray> "));
    }

    public void send(CommandSender to, String miniMessage, TagResolver... resolvers) {
        to.sendMessage(prefix().append(Text.mm(miniMessage, resolvers)));
    }

    public void msg(CommandSender to, String key, String... placeholders) {
        String raw = getConfig().getString("messages." + key);
        if (raw == null || raw.isEmpty()) return;
        TagResolver.Builder b = TagResolver.builder();
        for (int i = 0; i + 1 < placeholders.length; i += 2) b.resolver(Placeholder.unparsed(placeholders[i], placeholders[i + 1]));
        send(to, raw, b.build());
    }

    public RuleManager rules() {
        return rules;
    }

    public PinStore pins() {
        return pins;
    }

    public AuditLog audit() {
        return audit;
    }

    public Webhook webhook() {
        return webhook;
    }

    public Verifier verifier() {
        return verifier;
    }

    public RequestManager requests() {
        return requests;
    }

    public CommandGuard guard() {
        return guard;
    }

    public OpGuard opGuard() {
        return opGuard;
    }

    public ChatPrompt prompt() {
        return prompt;
    }
}
