package dev.markiemod.griefcheck.data;

import java.util.UUID;

/** Per-player summary used by the grief report. */
public record ReportRow(String actor, UUID uuid, int breaks, int foreignBreaks, int places, int takes,
                        int foreignTakes, int explosions, int fire, int kills, long last) {

    /** Higher = more suspicious. Destroying or stealing other people's stuff weighs most. */
    public int score() {
        return foreignBreaks * 3 + foreignTakes * 3 + explosions * 2 + fire * 2 + kills * 2 + breaks / 10;
    }
}
