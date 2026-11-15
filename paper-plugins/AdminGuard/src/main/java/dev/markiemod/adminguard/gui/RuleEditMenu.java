package dev.markiemod.adminguard.gui;

import dev.markiemod.adminguard.AdminGuardPlugin;
import dev.markiemod.adminguard.core.AuditType;
import dev.markiemod.adminguard.core.Mode;
import dev.markiemod.adminguard.core.Rule;
import dev.markiemod.adminguard.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.PatternSyntaxException;

/** Edit one rule. */
public final class RuleEditMenu extends Menu {

    private final AdminGuardPlugin plugin;
    private final Rule rule;

    public RuleEditMenu(AdminGuardPlugin plugin, Player viewer, Rule rule) {
        super(viewer, 4, Text.mm("<gold><bold>AdminGuard</bold></gold> <dark_gray>» Rule " + Text.esc(rule.name)));
        this.plugin = plugin;
        this.rule = rule;
    }

    private void changed(String what) {
        plugin.rules().save();
        plugin.audit().log(viewer.getName(), AuditType.SETTINGS, null, "rule " + rule.name + ": " + what);
    }

    @Override
    protected void draw() {
        set(4, Items.of(rule.mode.icon, rule.mode.color + Text.esc(rule.name), RulesMenu.describe(rule)));

        List<String> modes = new ArrayList<>();
        for (Mode m : Mode.values()) modes.add((m == rule.mode ? "<green>▶ " : "<gray>  ") + m.color + m.display + " <dark_gray>- " + m.description);
        modes.add("");
        modes.add("<yellow>Left/right-click to change");
        set(10, Items.of(rule.mode.icon, "<aqua>Mode: " + rule.mode.color + rule.mode.display, modes), e -> {
            rule.mode = rule.mode.next(e.isRightClick());
            changed("mode " + rule.mode);
            redraw();
        });

        set(11, Items.of(Material.PLAYER_HEAD, "<aqua>Approvals needed: <white>" + rule.approvals,
                "<gray>How many other staff must approve", "<gray>(only for Approval modes).",
                "", "<yellow>Left-click +1 · Right-click -1"), e -> {
            rule.approvals = Math.max(1, Math.min(10, rule.approvals + (e.isRightClick() ? -1 : 1)));
            changed("approvals " + rule.approvals);
            redraw();
        });

        List<String> cmds = new ArrayList<>();
        cmds.add("<gray>Current:");
        for (String c : rule.commands) cmds.add("<white> /" + Text.esc(c));
        if (rule.commands.isEmpty()) cmds.add("<red> none");
        cmds.add("");
        cmds.add("<yellow>Click to replace (comma-separated)");
        set(13, Items.of(Material.COMMAND_BLOCK, "<aqua>Commands", cmds), e ->
                plugin.prompt().ask(viewer, "<yellow>Type the commands, comma-separated (e.g. <white>op, deop, minecraft:op</white>). Use <white>*</white> for any command:", text -> {
                    rule.setCommands(Arrays.asList(text.split(",")));
                    changed("commands " + rule.commands);
                    open();
                }));

        set(14, Items.of(Material.NAME_TAG, "<aqua>Argument pattern",
                "<gray>Only apply when the arguments match", "<gray>this regex (case-insensitive).",
                "<gray>Current: <white>" + (rule.argsRegex.isEmpty() ? "<gray>any arguments" : Text.esc(rule.argsRegex)),
                "", "<gray>Examples: <white>@[ae]</white> <gray>(mass selectors)", "<white>creative|spectator</white> <gray>(gamemode)",
                "", "<yellow>Left-click to set · Right-click to clear"), e -> {
            if (e.isRightClick()) {
                rule.setArgsRegex("");
                changed("pattern cleared");
                redraw();
                return;
            }
            plugin.prompt().ask(viewer, "<yellow>Type the regex the arguments must match:", text -> {
                try {
                    rule.setArgsRegex(text);
                    changed("pattern " + text);
                } catch (PatternSyntaxException ex) {
                    plugin.send(viewer, "<red>That's not a valid regex: " + Text.esc(ex.getDescription()));
                }
                open();
            });
        });

        set(16, Items.toggle(rule.enabled, "Rule enabled"), e -> {
            rule.enabled = !rule.enabled;
            changed(rule.enabled ? "enabled" : "disabled");
            redraw();
        });

        set(27, Items.of(Material.OAK_DOOR, "<yellow>Back to rules"), e -> new RulesMenu(plugin, viewer).open());
        set(35, Items.of(Material.LAVA_BUCKET, "<red>Delete rule", "<gray>The commands will no longer be protected."), e ->
                new ConfirmMenu(plugin, viewer, Text.mm("<dark_red>Delete " + Text.esc(rule.name) + "?"),
                        Items.of(Material.LAVA_BUCKET, "<red>Delete rule " + Text.esc(rule.name) + "?"),
                        () -> {
                            plugin.rules().delete(rule);
                            plugin.audit().log(viewer.getName(), AuditType.SETTINGS, null, "rule " + rule.name + " deleted");
                            new RulesMenu(plugin, viewer).open();
                        }, this::open, false).open());
    }
}
