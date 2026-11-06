package dev.markie.lyrics.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.markie.lyrics.config.LyricsConfig;
import dev.markie.lyrics.spotify.NowPlaying;
import dev.markie.lyrics.spotify.SpotifyClient;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds synced lyrics for the current song using LRCLIB (https://lrclib.net), a free public lyrics database.
 * (Spotify's own lyrics aren't available through its public API.)
 */
public final class LyricsManager {
    public enum State { NONE, LOADING, SYNCED, NOT_FOUND, ERROR }

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    private static final String UA = "MarkieLyrics/1.0 (Minecraft mod; https://github.com/claudiusclaude399-art/markiemod)";
    private static final Map<String, List<LyricLine>> CACHE = new ConcurrentHashMap<>();

    private static volatile String trackId;
    private static volatile List<LyricLine> lines = List.of();
    private static volatile State state = State.NONE;

    private LyricsManager() {}

    public static State state() { return state; }
    public static List<LyricLine> lines() { return lines; }

    public static void onTrackChanged(NowPlaying np) {
        trackId = np.id();
        if (DemoSong.ID.equals(np.id())) {
            lines = DemoSong.LINES;
            state = State.SYNCED;
            return;
        }
        List<LyricLine> cached = CACHE.get(np.id());
        if (cached != null) {
            lines = cached;
            state = cached.isEmpty() ? State.NOT_FOUND : State.SYNCED;
            return;
        }
        lines = List.of();
        state = State.LOADING;
        final String id = np.id();
        CompletableFuture.runAsync(() -> {
            try {
                List<LyricLine> found = fetch(np);
                CACHE.put(id, found);
                if (id.equals(trackId)) {
                    lines = found;
                    state = found.isEmpty() ? State.NOT_FOUND : State.SYNCED;
                }
            } catch (Exception e) {
                if (id.equals(trackId)) state = State.ERROR;
            }
        });
    }

    private static long smoothPos = -1, lastNanos;
    private static String smoothId;

    /**
     * Song position with the user's offset applied. Spotify's progress arrives once a second and can be a
     * few hundred ms off, so instead of jumping to each new value we run our own clock and nudge it gently
     * towards Spotify's. Only big differences (seeking, new song) snap straight away.
     */
    public static long position() {
        NowPlaying np = SpotifyClient.current();
        if (np == null) { smoothPos = -1; return 0; }
        long raw = np.positionNow() - LyricsConfig.get().offsetMs;
        long now = System.nanoTime();
        if (smoothPos < 0 || !np.id().equals(smoothId) || !np.playing() || Math.abs(raw - smoothPos) > 1200) {
            smoothPos = raw;
        } else {
            long dt = (now - lastNanos) / 1_000_000L;
            long next = smoothPos + dt + Math.round((raw - smoothPos - dt) * 0.05);
            smoothPos = Math.max(smoothPos, next);   // never run backwards
        }
        smoothId = np.id();
        lastNanos = now;
        return smoothPos;
    }

    /** Index of the line that should be showing right now, or -1. */
    public static int currentIndex(long pos) {
        List<LyricLine> l = lines;
        int lo = 0, hi = l.size() - 1, best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (l.get(mid).startMs() <= pos) { best = mid; lo = mid + 1; } else hi = mid - 1;
        }
        if (best >= 0) {
            LyricLine line = l.get(best);
            // hide a line during a long instrumental gap
            long visibleUntil = Math.min(line.endMs(), line.startMs() + Math.max(4500, 650L * line.words().size() + 2500));
            if (pos > visibleUntil) return -1;
        }
        return best;
    }

    private static List<LyricLine> fetch(NowPlaying np) throws Exception {
        String artist = np.artist().split(",")[0].trim();
        String title = cleanTitle(np.title());

        // 1) exact match by title/artist/album/duration
        String url = "https://lrclib.net/api/get?track_name=" + enc(title) + "&artist_name=" + enc(artist)
            + "&album_name=" + enc(np.album()) + "&duration=" + Math.round(np.durationMs() / 1000.0);
        HttpResponse<String> res = get(url);
        if (res.statusCode() == 200) {
            JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
            String synced = syncedOf(o);
            if (synced != null) return LrcParser.parse(synced);
        }

        // 2) fuzzy search, pick the closest duration that has synced lyrics
        res = get("https://lrclib.net/api/search?track_name=" + enc(title) + "&artist_name=" + enc(artist));
        if (res.statusCode() == 200) {
            JsonArray arr = JsonParser.parseString(res.body()).getAsJsonArray();
            String best = null;
            double bestDiff = Double.MAX_VALUE;
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                String synced = syncedOf(o);
                if (synced == null) continue;
                double dur = o.has("duration") && !o.get("duration").isJsonNull() ? o.get("duration").getAsDouble() : 0;
                double diff = Math.abs(dur - np.durationMs() / 1000.0);
                if (diff < bestDiff) { bestDiff = diff; best = synced; }
            }
            if (best != null && bestDiff < 8) return LrcParser.parse(best);
        }
        return List.of();
    }

    private static String syncedOf(JsonObject o) {
        JsonElement s = o.get("syncedLyrics");
        if (s == null || s.isJsonNull()) return null;
        String v = s.getAsString();
        return v.isBlank() ? null : v;
    }

    /** "Song - Remastered 2011" / "Song (feat. X)" -> "Song" for better matches. */
    private static String cleanTitle(String t) {
        String s = t.replaceAll("(?i)\\s*[-–]\\s*(remaster(ed)?|live|radio edit|mono|stereo|single version|\\d{4} remaster).*$", "");
        s = s.replaceAll("(?i)\\s*[(\\[](feat\\.?|ft\\.?|with) [^)\\]]*[)\\]]", "");
        return s.trim().isEmpty() ? t : s.trim();
    }

    private static HttpResponse<String> get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(12))
            .header("User-Agent", UA)
            .GET().build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
