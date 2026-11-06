package dev.markie.lyrics.spotify;

/** Snapshot of what Spotify is playing. {@code sampledAt} is System.currentTimeMillis() when progress was read. */
public record NowPlaying(String id, String title, String artist, String album, String coverUrl,
                         long durationMs, long progressMs, boolean playing, long sampledAt) {

    /** Current position, extrapolated from the last poll so lyrics stay smooth between polls. */
    public long positionNow() {
        long p = progressMs;
        if (playing) p += System.currentTimeMillis() - sampledAt;
        // durationMs 0 = unknown (some players don't report it)
        return durationMs <= 0 ? Math.max(0, p) : Math.max(0, Math.min(p, durationMs));
    }
}
