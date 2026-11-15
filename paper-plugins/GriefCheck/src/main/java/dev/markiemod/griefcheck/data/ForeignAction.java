package dev.markiemod.griefcheck.data;

import java.util.UUID;

/** A player broke/blew up/took from something that another player placed. */
public record ForeignAction(UUID actor, String actorName, UUID owner, String ownerName, String world,
                            int x, int y, int z, Action action, long time) {
}
