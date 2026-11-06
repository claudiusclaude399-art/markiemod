package dev.markie.lyrics.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Everything the player can change. Saved to config/markielyrics.json. */
public class LyricsConfig {
    public enum Where { SKY, FLOATING, SCREEN }
    public enum Reveal { WORD, LINE }

    // Spotify
    public String clientId = "";
    public String refreshToken = "";
    public transient String accessToken = "";
    public transient long accessExpiresAt = 0;

    // Lyrics
    public boolean showLyrics = true;
    public Where where = Where.SKY;
    public Reveal reveal = Reveal.WORD;
    public boolean followView = false;
    public boolean curved = true;
    public boolean throughWalls = true;
    public int offsetMs = 0;          // shift lyrics earlier (-) or later (+)

    // Look
    public int theme = 0;
    public int font = 0;
    public float size = 1.0f;         // 0.5 .. 2.0
    public float distance = 1.0f;     // 0.5 .. 2.0 (multiplier)
    public boolean glow = true;
    public boolean shadow = false;

    // HUD
    public boolean hud = true;
    public boolean hudBars = true;
    public boolean hudProgress = true;
    public int hudY = 4;

    // Sounds
    public boolean uiSounds = true;
    public float uiVolume = 0.8f;
    public boolean lineChime = false;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static LyricsConfig INSTANCE = new LyricsConfig();

    public static LyricsConfig get() { return INSTANCE; }

    private static Path path() { return FabricLoader.getInstance().getConfigDir().resolve("markielyrics.json"); }

    public static void load() {
        try {
            Path p = path();
            if (Files.exists(p)) {
                try (Reader r = Files.newBufferedReader(p)) {
                    LyricsConfig c = GSON.fromJson(r, LyricsConfig.class);
                    if (c != null) INSTANCE = c;
                }
            }
        } catch (Exception e) {
            System.err.println("[MarkieLyrics] Could not read config: " + e);
        }
        INSTANCE.sanitize();
    }

    public static synchronized void save() {
        try {
            Path p = path();
            Files.createDirectories(p.getParent());
            try (Writer w = Files.newBufferedWriter(p)) { GSON.toJson(INSTANCE, w); }
        } catch (Exception e) {
            System.err.println("[MarkieLyrics] Could not save config: " + e);
        }
    }

    private void sanitize() {
        if (clientId == null) clientId = "";
        if (refreshToken == null) refreshToken = "";
        if (accessToken == null) accessToken = "";
        if (where == null) where = Where.SKY;
        if (reveal == null) reveal = Reveal.WORD;
        size = clamp(size, 0.5f, 2f);
        distance = clamp(distance, 0.5f, 2f);
        uiVolume = clamp(uiVolume, 0f, 1f);
        if (theme < 0 || theme >= dev.markie.lyrics.gui.Themes.ALL.length) theme = 0;
        if (font < 0 || font >= dev.markie.lyrics.gui.Fonts.ALL.length) font = 0;
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
