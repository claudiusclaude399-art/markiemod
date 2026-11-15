package dev.markiemod.adminguard.core;

import dev.markiemod.adminguard.AdminGuardPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.PatternSyntaxException;

/** Loads rules from config.yml, saves GUI edits back, and decides what a typed command needs. */
public final class RuleManager {

    /** AdminGuard's own command is never guarded, otherwise you could lock yourself out of approving. */
    private static final Set<String> SELF = Set.of("ag", "adminguard", "aguard");

    private final AdminGuardPlugin plugin;
    private final Map<String, Rule> rules = new LinkedHashMap<>();

    public RuleManager(AdminGuardPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        rules.clear();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("rules");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            ConfigurationSection r = sec.getConfigurationSection(key);
            if (r == null) continue;
            Rule rule = new Rule(key);
            rule.enabled = r.getBoolean("enabled", true);
            rule.setCommands(r.getStringList("commands"));
            try {
                rule.setArgsRegex(r.getString("args-regex", ""));
            } catch (PatternSyntaxException e) {
                plugin.getLogger().warning("Rule '" + key + "' has an invalid args-regex, ignoring the regex: " + e.getMessage());
            }
            try {
                rule.mode = Mode.valueOf(r.getString("mode", "CONFIRM").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Rule '" + key + "' has an unknown mode, using CONFIRM.");
            }
            rule.approvals = Math.max(1, r.getInt("approvals", 1));
            rules.put(key.toLowerCase(Locale.ROOT), rule);
        }
    }

    public void save() {
        plugin.getConfig().set("rules", null);
        plugin.getConfig().createSection("rules"); // keep an empty section so jar defaults don't come back
        for (Rule r : rules.values()) {
            String p = "rules." + r.name + ".";
            plugin.getConfig().set(p + "enabled", r.enabled);
            plugin.getConfig().set(p + "commands", r.commands);
            plugin.getConfig().set(p + "args-regex", r.argsRegex);
            plugin.getConfig().set(p + "mode", r.mode.name());
            plugin.getConfig().set(p + "approvals", r.approvals);
        }
        plugin.saveConfig();
    }

    public List<Rule> all() {
        return new ArrayList<>(rules.values());
    }

    public Rule get(String name) {
        return rules.get(name.toLowerCase(Locale.ROOT));
    }

    public Rule create(String name) {
        Rule r = new Rule(name);
        rules.put(name.toLowerCase(Locale.ROOT), r);
        save();
        return r;
    }

    public void delete(Rule r) {
        rules.remove(r.name.toLowerCase(Locale.ROOT));
        save();
    }

    private static String stripNamespace(String label) {
        int i = label.indexOf(':');
        return i >= 0 ? label.substring(i + 1) : label;
    }

    /**
     * Checks a command line (with or without the leading slash).
     *
     * @param sender used to skip commands the sender couldn't run anyway; may be null
     * @return null if no rule applies
     */
    public Decision evaluate(String commandLine, CommandSender sender) {
        return evaluate(commandLine, sender, 0);
    }

    private Decision evaluate(String commandLine, CommandSender sender, int depth) {
        String line = commandLine.startsWith("/") ? commandLine.substring(1) : commandLine;
        line = line.trim();
        if (line.isEmpty() || depth > 8) return null;
        int space = line.indexOf(' ');
        String typed = (space < 0 ? line : line.substring(0, space)).toLowerCase(Locale.ROOT);
        String args = space < 0 ? "" : line.substring(space + 1);
        String label = stripNamespace(typed);
        if (SELF.contains(label)) return null;

        List<String> labels = new ArrayList<>();
        labels.add(label);
        Command cmd = Bukkit.getCommandMap().getCommand(typed);
        if (cmd != null) {
            labels.add(stripNamespace(cmd.getName().toLowerCase(Locale.ROOT)));
            for (String a : cmd.getAliases()) labels.add(stripNamespace(a.toLowerCase(Locale.ROOT)));
            if (sender != null && plugin.getConfig().getBoolean("settings.skip-if-no-permission", true)
                    && !cmd.testPermissionSilent(sender)) {
                return null; // they can't run it anyway; the server will refuse it
            }
        }

        List<Rule> matched = new ArrayList<>();
        for (Rule r : rules.values()) if (r.matches(labels, args)) matched.add(r);

        Decision nested = null;
        // "/execute ... run <command>" would otherwise smuggle protected commands past the guard.
        if (labels.contains("execute")) {
            int run = (" " + args.toLowerCase(Locale.ROOT) + " ").indexOf(" run ");
            if (run >= 0 && run + 4 <= args.length()) nested = evaluate(args.substring(Math.min(args.length(), run + 4)), sender, depth + 1);
        }

        Mode mode = null;
        int approvals = 1;
        for (Rule r : matched) {
            if (mode == null || r.mode.ordinal() > mode.ordinal()) mode = r.mode;
            approvals = Math.max(approvals, r.approvals);
        }
        if (nested != null) {
            if (mode == null || nested.mode().ordinal() > mode.ordinal()) mode = nested.mode();
            approvals = Math.max(approvals, nested.approvals());
            matched.addAll(nested.rules());
        }
        return mode == null ? null : new Decision(mode, approvals, matched);
    }
}
