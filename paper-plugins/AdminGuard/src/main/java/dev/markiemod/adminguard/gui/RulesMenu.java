package dev.markiemod.adminguard.gui;

import dev.markiemod.adminguard.AdminGuardPlugin;
import dev.markiemod.adminguard.core.AuditType;
import dev.markiemod.adminguard.core.Rule;
import dev.markiemod.adminguard.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** List of protected-command rules. */
public final class RulesMenu extends Menu {

    private final AdminGuardPlugin plugin;

    public RulesMenu(AdminGuardPlugin plugin, Player viewer) {
        super(viewer, 6, Text.mm("<gold><bold>AdminGuard</bold></gold> <dark_gray>» Protected commands"));
        this.plugin = plugin;
    }

    static List<String> describe(Rule r) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Mode: " + r.mode.color + r.mode.display);
        lore.add("<dark_gray>" + r.mode.description);
        if (r.mode.needsApproval()) lore.add("<gray>Approvals needed: <white>" + r.approvals);
        lore.add("<gray>Commands: <white>" + (r.commands.isEmpty() ? "<red>none" : Text.esc(String.join(", ", shorten(r.commands)))));
        if (!r.argsRegex.isEmpty()) lore.add("<gray>Only when arguments match: <white>" + Text.esc(r.argsRegex));
        lore.add(r.enabled ? "<green>● Enabled" : "<red>● Disabled");
        return lore;
    }

    private static List<String> shorten(List<String> cmds) {
        if (cmds.size() <= 8) return cmds.stream().map(c -> c.equals("*") ? "* (any command)" : "/" + c).toList();
        List<String> out = new ArrayList<>(cmds.subList(0, 8).stream().map(c -> "/" + c).toList());
        out.add("+" + (cmds.size() - 8) + " more");
        return out;
    }

    @Override
    protected void draw() {
        List<Rule> rules = plugin.rules().all();
        for (int i = 0; i < rules.size() && i < 45; i++) {
            Rule r = rules.get(i);
            List<String> lore = describe(r);
            lore.add("");
            lore.add("<yellow>Left-click to edit");
            lore.add("<yellow>Right-click to " + (r.enabled ? "disable" : "enable"));
            ItemStack item = Items.of(r.enabled ? r.mode.icon : Material.GRAY_WOOL, (r.enabled ? r.mode.color : "<gray>") + Text.esc(r.name), lore);
            set(i, item, e -> {
                if (e.isRightClick()) {
                    r.enabled = !r.enabled;
                    plugin.rules().save();
                    plugin.audit().log(viewer.getName(), AuditType.SETTINGS, null, "rule " + r.name + " " + (r.enabled ? "enabled" : "disabled"));
                    redraw();
                } else {
                    new RuleEditMenu(plugin, viewer, r).open();
                }
            });
        }
        set(45, Items.of(Material.OAK_DOOR, "<yellow>Back to menu"), e -> new MainMenu(plugin, viewer).open());
        set(49, Items.of(Material.EMERALD, "<green>New rule", "<gray>Protect another command"), e ->
                plugin.prompt().ask(viewer, "<yellow>Name the new rule (one word, e.g. <white>worldedit</white>):", name -> {
                    String n = name.split(" ")[0].replaceAll("[^A-Za-z0-9_-]", "");
                    if (n.isEmpty() || plugin.rules().get(n) != null) {
                        plugin.send(viewer, "<red>That name is empty or already used.");
                        open();
                        return;
                    }
                    Rule r = plugin.rules().create(n);
                    plugin.audit().log(viewer.getName(), AuditType.SETTINGS, null, "rule " + n + " created");
                    new RuleEditMenu(plugin, viewer, r).open();
                }));
        set(53, Items.of(Material.BOOK, "<white>How rules work",
                "<gray>When a command matches several rules,", "<gray>the <white>strictest</white> one applies.",
                "", "<gray>Use <white>*</white> as a command to match every", "<gray>command (pair it with an argument",
                "<gray>pattern like <white>@[ae]</white> for mass selectors).",
                "", "<gray>Wrapped commands like <white>/execute ... run op</white>", "<gray>are checked too."));
    }
}
