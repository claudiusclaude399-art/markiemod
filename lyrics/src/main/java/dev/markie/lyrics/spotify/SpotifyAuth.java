package dev.markie.lyrics.spotify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import dev.markie.lyrics.config.LyricsConfig;
import net.minecraft.util.Util;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Spotify login using the Authorization Code + PKCE flow, so no client secret is needed.
 * A tiny local web server on 127.0.0.1 catches the redirect after the player logs in.
 */
public final class SpotifyAuth {
    public static final int PORT = 43821;
    public static final String REDIRECT_URI = "http://127.0.0.1:" + PORT + "/callback";
    private static final String SCOPES = "user-read-currently-playing user-read-playback-state user-modify-playback-state";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public enum State { IDLE, WAITING_FOR_BROWSER, CONNECTED, FAILED }
    public static volatile State state = State.IDLE;
    public static volatile String message = "";

    private static HttpServer server;

    private SpotifyAuth() {}

    public static boolean isLoggedIn() {
        return !LyricsConfig.get().refreshToken.isEmpty();
    }

    /** Opens the browser for login. Result arrives asynchronously; watch {@link #state}. */
    public static synchronized void startLogin() {
        LyricsConfig cfg = LyricsConfig.get();
        String clientId = cfg.clientId.trim();
        if (clientId.isEmpty()) {
            fail("Paste your Spotify Client ID first.");
            return;
        }
        stopServer();
        try {
            String verifier = randomString(64);
            String challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            String stateToken = randomString(16);

            server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
            server.createContext("/callback", ex -> {
                Map<String, String> q = query(ex.getRequestURI().getRawQuery());
                boolean ok = stateToken.equals(q.get("state")) && q.containsKey("code");
                String html = ok
                    ? page("Connected!", "You can close this tab and go back to Minecraft.")
                    : page("Login failed", q.getOrDefault("error", "Something went wrong.") + " — try again from the game.");
                byte[] body = html.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
                ex.close();
                if (ok) {
                    CompletableFuture.runAsync(() -> exchangeCode(clientId, q.get("code"), verifier));
                } else {
                    fail("Spotify said: " + q.getOrDefault("error", "unknown error"));
                }
                CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS).execute(SpotifyAuth::stopServer);
            });
            server.start();

            String url = "https://accounts.spotify.com/authorize"
                + "?response_type=code"
                + "&client_id=" + enc(clientId)
                + "&scope=" + enc(SCOPES)
                + "&redirect_uri=" + enc(REDIRECT_URI)
                + "&state=" + enc(stateToken)
                + "&code_challenge_method=S256"
                + "&code_challenge=" + enc(challenge);
            state = State.WAITING_FOR_BROWSER;
            message = "Finish logging in in your browser…";
            Util.getOperatingSystem().open(URI.create(url));
        } catch (Exception e) {
            stopServer();
            fail("Couldn't start login: " + e.getMessage());
        }
    }

    public static void logout() {
        LyricsConfig cfg = LyricsConfig.get();
        cfg.refreshToken = "";
        cfg.accessToken = "";
        cfg.accessExpiresAt = 0;
        LyricsConfig.save();
        state = State.IDLE;
        message = "Logged out.";
    }

    private static void exchangeCode(String clientId, String code, String verifier) {
        Map<String, String> form = new HashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", REDIRECT_URI);
        form.put("client_id", clientId);
        form.put("code_verifier", verifier);
        if (tokenRequest(form)) {
            state = State.CONNECTED;
            message = "Connected to Spotify!";
            dev.markie.lyrics.Sounds.playLater(dev.markie.lyrics.Sounds.CONNECT);
        }
    }

    /** Returns a valid access token, refreshing it if needed. Null if not logged in / refresh failed. Blocking. */
    public static synchronized String accessToken() {
        LyricsConfig cfg = LyricsConfig.get();
        if (cfg.refreshToken.isEmpty()) return null;
        if (!cfg.accessToken.isEmpty() && System.currentTimeMillis() < cfg.accessExpiresAt - 30_000) return cfg.accessToken;
        Map<String, String> form = new HashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", cfg.refreshToken);
        form.put("client_id", cfg.clientId.trim());
        return tokenRequest(form) ? cfg.accessToken : null;
    }

    public static synchronized void invalidateAccessToken() {
        LyricsConfig.get().accessToken = "";
    }

    private static boolean tokenRequest(Map<String, String> form) {
        try {
            StringBuilder sb = new StringBuilder();
            form.forEach((k, v) -> { if (sb.length() > 0) sb.append('&'); sb.append(enc(k)).append('=').append(enc(v)); });
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://accounts.spotify.com/api/token"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(sb.toString()))
                .build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            JsonObject json = JsonParser.parseString(res.body()).getAsJsonObject();
            if (res.statusCode() != 200 || !json.has("access_token")) {
                String err = json.has("error_description") ? json.get("error_description").getAsString()
                    : json.has("error") ? json.get("error").getAsString() : ("HTTP " + res.statusCode());
                if (res.statusCode() == 400 && "refresh_token".equals(form.get("grant_type"))) {
                    // refresh token revoked or expired: force a new login
                    LyricsConfig.get().refreshToken = "";
                    LyricsConfig.save();
                }
                fail("Spotify login error: " + err);
                return false;
            }
            LyricsConfig cfg = LyricsConfig.get();
            cfg.accessToken = json.get("access_token").getAsString();
            cfg.accessExpiresAt = System.currentTimeMillis() + json.get("expires_in").getAsLong() * 1000L;
            if (json.has("refresh_token")) cfg.refreshToken = json.get("refresh_token").getAsString();
            LyricsConfig.save();
            return true;
        } catch (Exception e) {
            fail("Couldn't reach Spotify: " + e.getMessage());
            return false;
        }
    }

    private static void fail(String msg) {
        state = State.FAILED;
        message = msg;
        dev.markie.lyrics.Sounds.playLater(dev.markie.lyrics.Sounds.ERROR);
    }

    private static synchronized void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private static String page(String title, String text) {
        return "<!doctype html><html><head><meta charset=utf-8><title>Markie Lyrics</title><style>"
            + "body{margin:0;height:100vh;display:flex;align-items:center;justify-content:center;"
            + "background:radial-gradient(circle at 30% 20%,#4b2a7a,#141020 70%);color:#eee;font-family:system-ui,sans-serif}"
            + ".c{background:#1c1726cc;padding:40px 56px;border-radius:18px;border:1px solid #8a5cf655;text-align:center}"
            + "h1{margin:0 0 8px;color:#c4a4ff}</style></head><body><div class=c><h1>" + title + "</h1><p>" + text
            + "</p></div></body></html>";
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String part : raw.split("&")) {
            int i = part.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8),
                             URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static String randomString(int len) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    private static String base64Url(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
