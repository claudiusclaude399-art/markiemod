package dev.markiemod.adminguard.core;

import java.util.List;

/** Result of checking a command against every rule: the strictest matching rule wins. */
public record Decision(Mode mode, int approvals, List<Rule> rules) {

    public String ruleNames() {
        StringBuilder sb = new StringBuilder();
        for (Rule r : rules) sb.append(sb.isEmpty() ? "" : ", ").append(r.name);
        return sb.toString();
    }
}
