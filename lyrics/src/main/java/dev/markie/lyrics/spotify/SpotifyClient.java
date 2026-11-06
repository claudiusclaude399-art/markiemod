package dev.markie.lyrics.spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.markie.lyrics.lyrics.LyricsManager;
import dev.markie.lyrics.render.CoverArt;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Polls Spotify's "currently playing" endpoint about once a second on a background thread. */
public final class SpotifyClient {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final ScheduledExecutorService EXEC = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "MarkieLyrics-Spotify");
        t.setDaemon(true);
        return t;
    });

    private static volatile NowPlaying current;
    private static volatile String status = "Not connected";
    private static volatile long backoffUntil = 0;

    // preview mode: a built-in demo song instead of Spotify
    private static volatile boolean demo = false;
    private static volatile long demoStart, demoPausedAt;
    private static volatile boolean demoPlaying = true;

    private SpotifyClient() {}

    public static NowPlaying current() { return current; }
    public static String status() { return status; }

    public static boolean isDemo() { return demo; }

    public static void setDemo(boolean on) {
        demo = on;
        demoStart = System.currentTimeMillis();
        demoPlaying = true;
        EXEC.execute(SpotifyClient::poll);
    }

    public static void start() {
        EXEC.scheduleWithFixedDelay(SpotifyClient::poll, 1, 1000, TimeUnit.MILLISECONDS);
    }

    private static void poll() {
        try {
            if (demo) {
                setCurrent(dev.markie.lyrics.lyrics.DemoSong.nowPlaying(demoStart, demoPlaying, demoPausedAt));
                status = "Previewing the demo song";
                return;
            }
            if (current != null && dev.markie.lyrics.lyrics.DemoSong.ID.equals(current.id())) setCurrent(null);
            if (System.currentTimeMillis() < backoffUntil) return;
            if (!SpotifyAuth.isLoggedIn()) {
                current = null;
                status = "Not connected";
                return;
            }
            String token = SpotifyAuth.accessToken();
            if (token == null) {
                status = "Login expired";
                return;
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.spotify.com/v1/me/player/currently-playing?additional_types=track"))
                .timeout(Duration.ofSeconds(8))
                .header("Authorization", "Bearer " + token)
                .GET().build();
            long sent = System.currentTimeMillis();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            long now = System.currentTimeMillis();
            int code = res.statusCode();
            if (code == 204 || (code == 200 && res.body().isBlank())) {
                setCurrent(null);
                status = "Nothing playing";
                return;
            }
            if (code == 401) { SpotifyAuth.invalidateAccessToken(); return; }
            if (code == 429) {
                long wait = res.headers().firstValueAsLong("Retry-After").orElse(5);
                backoffUntil = now + wait * 1000L;
                status = "Rate limited, waiting " + wait + "s";
                return;
            }
            if (code != 200) { status = "Spotify error " + code; return; }

            JsonObject json = JsonParser.parseString(res.body()).getAsJsonObject();
            JsonElement itemEl = json.get("item");
            if (itemEl == null || itemEl.isJsonNull()) {
                setCurrent(null);
                status = "Playing something without lyrics (ad / podcast?)";
                return;
            }
            JsonObject item = itemEl.getAsJsonObject();
            String id = str(item, "id", str(item, "uri", str(item, "name", "?")));
            String title = str(item, "name", "Unknown");
            StringBuilder artists = new StringBuilder();
            if (item.has("artists")) for (JsonElement a : item.getAsJsonArray("artists")) {
                if (artists.length() > 0) artists.append(", ");
                artists.append(str(a.getAsJsonObject(), "name", ""));
            }
            String album = "";
            String cover = null;
            if (item.has("album") && item.get("album").isJsonObject()) {
                JsonObject al = item.getAsJsonObject("album");
                album = str(al, "name", "");
                if (al.has("images")) {
                    JsonArray imgs = al.getAsJsonArray("images");
                    // images are largest first; pick the smallest that is still >= 64px
                    for (JsonElement e : imgs) {
                        JsonObject o = e.getAsJsonObject();
                        int w = o.has("width") && !o.get("width").isJsonNull() ? o.get("width").getAsInt() : 640;
                        if (cover == null || w >= 64) cover = str(o, "url", cover);
                    }
                }
            }
            long duration = item.has("duration_ms") ? item.get("duration_ms").getAsLong() : 0;
            long progress = json.has("progress_ms") && !json.get("progress_ms").isJsonNull() ? json.get("progress_ms").getAsLong() : 0;
            boolean playing = json.has("is_playing") && json.get("is_playing").getAsBoolean();
            // assume progress was measured half-way through the request
            long sampledAt = sent + (now - sent) / 2;

            setCurrent(new NowPlaying(id, title, artists.toString(), album, cover, duration, progress, playing, sampledAt));
            status = playing ? "Playing" : "Paused";
        } catch (Exception e) {
            status = "Can't reach Spotify (" + e.getClass().getSimpleName() + ")";
        }
    }

    private static void setCurrent(NowPlaying np) {
        NowPlaying old = current;
        current = np;
        String oldId = old == null ? null : old.id();
        String newId = np == null ? null : np.id();
        if (newId != null && !newId.equals(oldId)) {
            LyricsManager.onTrackChanged(np);
            CoverArt.request(np.coverUrl());
        } else if (newId == null && oldId != null) {
            CoverArt.request(null);
        }
    }

    // ---- playback controls (need Spotify Premium) ----

    public static void next()     { if (demo) { demoStart = System.currentTimeMillis(); EXEC.execute(SpotifyClient::poll); } else command("POST", "next"); }
    public static void previous() { if (demo) { demoStart = System.currentTimeMillis(); EXEC.execute(SpotifyClient::poll); } else command("POST", "previous"); }
    public static void togglePlay() {
        if (demo) {
            long now = System.currentTimeMillis();
            if (demoPlaying) demoPausedAt = now; else demoStart += now - demoPausedAt;
            demoPlaying = !demoPlaying;
            EXEC.execute(SpotifyClient::poll);
            return;
        }
        NowPlaying np = current;
        boolean playing = np != null && np.playing();
        command("PUT", playing ? "pause" : "play");
        if (np != null) {
            // flip locally right away so the UI feels instant
            current = new NowPlaying(np.id(), np.title(), np.artist(), np.album(), np.coverUrl(), np.durationMs(),
                np.positionNow(), !playing, System.currentTimeMillis());
        }
    }

    private static void command(String method, String path) {
        EXEC.execute(() -> {
            try {
                String token = SpotifyAuth.accessToken();
                if (token == null) return;
                HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.spotify.com/v1/me/player/" + path))
                    .timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Bearer " + token)
                    .method(method, HttpRequest.BodyPublishers.noBody())
                    .build();
                HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 403) status = "Playback controls need Spotify Premium";
                else if (res.statusCode() == 404) status = "No active Spotify device";
                Thread.sleep(250);
                poll();
            } catch (Exception ignored) {
            }
        });
    }

    private static String str(JsonObject o, String key, String def) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? def : e.getAsString();
    }
}
