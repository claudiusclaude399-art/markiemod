package dev.markiemod.griefcheck.gui;

import dev.markiemod.griefcheck.GriefCheckPlugin;
import dev.markiemod.griefcheck.data.Query;

/** Guards against accidental server-wide rollbacks. */
public final class RollbackChecks {

    private RollbackChecks() {
    }

    /** @return an error message, or null if the rollback is allowed */
    public static String check(GriefCheckPlugin plugin, Query q) {
        int max = plugin.settings().maxRollbackRadius;
        if (q.radius < 0 && q.actor == null) return "A server-wide rollback needs a player (u:<name>).";
        if (q.radius > max && q.actor == null) return "Radius is above the limit of " + max + " (set a player or a smaller radius).";
        if (q.since <= 0 && q.actor == null) return "Pick a time limit (t:) or a player for the rollback.";
        return null;
    }
}
