package dev.markiemod.adminguard.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** A protected command waiting for PIN / confirmation / approval. */
public final class Request {
    public final int id;
    public final UUID uuid;
    public final String name;
    public final String command;
    public final Decision decision;
    public final long created = System.currentTimeMillis();
    public boolean pinDone;
    public boolean confirmed;
    public boolean announced;
    public boolean finished;
    /** approver name by id (UUID or "console") */
    public final Map<String, String> approvals = new LinkedHashMap<>();

    public Request(int id, UUID uuid, String name, String command, Decision decision) {
        this.id = id;
        this.uuid = uuid;
        this.name = name;
        this.command = command;
        this.decision = decision;
    }

    public int needed() {
        return decision.mode().needsApproval() ? decision.approvals() : 0;
    }
}
