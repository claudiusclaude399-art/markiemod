package dev.markiemod.adminguard.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** One protected-command rule from config.yml. */
public final class Rule {
    public final String name;
    public boolean enabled = true;
    public List<String> commands = new ArrayList<>();
    public String argsRegex = "";
    public Mode mode = Mode.CONFIRM;
    public int approvals = 1;
    private Pattern pattern;

    public Rule(String name) {
        this.name = name;
    }

    public void setArgsRegex(String regex) throws PatternSyntaxException {
        argsRegex = regex == null ? "" : regex;
        pattern = argsRegex.isEmpty() ? null : Pattern.compile(argsRegex, Pattern.CASE_INSENSITIVE);
    }

    public void setCommands(List<String> list) {
        commands = new ArrayList<>();
        for (String c : list) {
            String s = c.trim().toLowerCase(Locale.ROOT);
            if (s.startsWith("/")) s = s.substring(1);
            if (!s.isEmpty()) commands.add(s);
        }
    }

    /**
     * @param labels the typed label plus the real command name and its aliases (lowercase, no namespace)
     * @param args   everything after the label
     */
    public boolean matches(List<String> labels, String args) {
        if (!enabled) return false;
        boolean cmd = commands.contains("*");
        if (!cmd) {
            for (String l : labels) {
                if (commands.contains(l)) {
                    cmd = true;
                    break;
                }
            }
        }
        if (!cmd) return false;
        return pattern == null || pattern.matcher(args).find();
    }
}
