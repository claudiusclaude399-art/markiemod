package dev.markiemod.griefcheck.data;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Filters for a lookup / report / rollback. */
public final class Query {
    public String world;
    public int x, y, z;
    /** -1 means the whole server (no location filter). 0 means exactly this block. */
    public int radius = 10;
    /** Epoch millis; only actions at or after this time. 0 = all time. */
    public long since;
    /** Player or #source name, null = anyone. */
    public String actor;
    public Set<Action> actions = EnumSet.allOf(Action.class);

    public static Query at(Location loc, int radius, long since) {
        Query q = new Query();
        q.world = loc.getWorld().getName();
        q.x = loc.getBlockX();
        q.y = loc.getBlockY();
        q.z = loc.getBlockZ();
        q.radius = radius;
        q.since = since;
        return q;
    }

    public Query copy() {
        Query q = new Query();
        q.world = world;
        q.x = x;
        q.y = y;
        q.z = z;
        q.radius = radius;
        q.since = since;
        q.actor = actor;
        q.actions = actions.isEmpty() ? EnumSet.noneOf(Action.class) : EnumSet.copyOf(actions);
        return q;
    }

    /** Builds the WHERE clause (table alias "a") and fills params. */
    String where(List<Object> params) {
        List<String> parts = new ArrayList<>();
        if (since > 0) {
            parts.add("a.time >= ?");
            params.add(since);
        }
        if (radius >= 0 && world != null) {
            parts.add("a.world = ?");
            params.add(world);
            parts.add("a.x BETWEEN ? AND ?");
            params.add(x - radius);
            params.add(x + radius);
            parts.add("a.z BETWEEN ? AND ?");
            params.add(z - radius);
            params.add(z + radius);
            if (radius == 0) {
                parts.add("a.y = ?");
                params.add(y);
            }
        }
        if (actor != null) {
            parts.add("a.actor = ? COLLATE NOCASE");
            params.add(actor);
        }
        if (actions.size() < Action.values().length) {
            if (actions.isEmpty()) {
                parts.add("0");
            } else {
                StringBuilder sb = new StringBuilder("a.action IN (");
                boolean first = true;
                for (Action a : actions) {
                    sb.append(first ? "" : ",").append(a.id);
                    first = false;
                }
                parts.add(sb.append(')').toString());
            }
        }
        return parts.isEmpty() ? "1" : String.join(" AND ", parts);
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(actor == null ? "anyone" : actor);
        sb.append(radius < 0 ? ", everywhere" : radius == 0 ? ", this block" : ", radius " + radius);
        return sb.toString();
    }
}
