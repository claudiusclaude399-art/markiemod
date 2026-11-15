package dev.markiemod.griefcheck.data;

import java.util.UUID;

/** One recorded action. Mutable only while queued (id/foreign info are filled in by the database thread). */
public final class LogEntry {
    public long id;
    public final long time;
    public final String actor;
    public final UUID actorUuid; // null for non-players such as #creeper
    public final String world;
    public final int x, y, z;
    public final Action action;
    public final String material;
    public final String data;     // block data after a place, before a break
    public final String oldData;  // block that was replaced by a place
    public final int amount;
    public final byte[] item;     // serialized item for container actions
    public boolean rolledBack;

    public LogEntry(long time, String actor, UUID actorUuid, String world, int x, int y, int z, Action action,
                    String material, String data, String oldData, int amount, byte[] item) {
        this.time = time;
        this.actor = actor;
        this.actorUuid = actorUuid;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.action = action;
        this.material = material;
        this.data = data;
        this.oldData = oldData;
        this.amount = amount;
        this.item = item;
    }

    public boolean isPlayer() {
        return actorUuid != null;
    }
}
