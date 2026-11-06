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
    public enum ColorMode { THEME, CUSTOM, RAINBOW, ALBUM }
    public enum PopStyle { POP, BOUNCE, ZOOM, SPIN, SLIDE, FADE }
    public enum Vfx { NONE, SPARKLES, STARS, HEARTS, NOTES, GLITTER }
    public enum Exit { FADE_UP, DISSOLVE, BLOW, SHRINK, DROP }
    public enum Atmo { NONE, FIREFLIES, STARDUST, SNOW, PETALS, EMBERS }

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

    // Position
    public float skyDistance = 46f;   // blocks, 16 .. 128
    public float floatDistance = 7f;  // blocks, 2 .. 24
    public float skyHeight = 17f;     // degrees above the horizon, 0 .. 70
    public float floatHeight = 5f;    // degrees above where you look, -20 .. 30
    public float spread = 1f;         // how far left/right lines wander, 0 .. 2
    public float screenY = 0.70f;     // screen mode: 0 = top, 1 = bottom
    public boolean fitFog = true;     // keep sky lyrics inside your render distance
    public boolean scaleWithDistance = false; // true: farther = smaller on screen
    public boolean glow = true;
    public boolean shadow = false;

    // Colours
    public ColorMode colorMode = ColorMode.THEME;
    public int customA = 0xFFB3E6;
    public int customB = 0x9AD0FF;
    public int customGlow = 0xB05CC8;
    public float rainbowSpeed = 0.5f; // 0 .. 2
    public float rainbowSpread = 0.5f;// 0 .. 1
    public float opacity = 1f;        // 0.2 .. 1

    // Effects
    public PopStyle popStyle = PopStyle.POP;
    public Vfx vfx = Vfx.SPARKLES;
    public float vfxAmount = 1f;      // 0.25 .. 2
    public boolean wave = false;
    public boolean shimmer = true;
    public boolean echo = true;
    public boolean perLetter = false;
    public boolean highlight = true;
    public boolean bloom = true;
    public boolean shockwave = true;
    public boolean streak = true;
    public boolean aura = true;
    public Exit exit = Exit.FADE_UP;

    // Atmosphere
    public Atmo atmo = Atmo.FIREFLIES;
    public float atmoDensity = 1f;    // 0.25 .. 2
    public float atmoSize = 1f;       // 0.5 .. 2
    public boolean atmoLyricColors = false;
    public boolean atmoOnlyMusic = true;
    public float vignette = 0.35f;    // 0 .. 1
    public boolean vignetteTint = true;
    public float haze = 0.25f;        // 0 .. 1
    public boolean linePulse = true;
    public boolean cinemaBars = false;

    // Sky
    public boolean skyEnabled = false;
    public int sky = 0;
    public float skyBrightness = 1f;  // 0.3 .. 1.5
    public float skySpin = 0.2f;      // degrees per second, 0 .. 3
    public boolean skySunMoon = false;
    public boolean skyFog = true;
    public boolean skyHideClouds = true;

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
        if (colorMode == null) colorMode = ColorMode.THEME;
        if (popStyle == null) popStyle = PopStyle.POP;
        if (vfx == null) vfx = Vfx.SPARKLES;
        if (exit == null) exit = Exit.FADE_UP;
        if (atmo == null) atmo = Atmo.FIREFLIES;
        atmoDensity = clamp(atmoDensity, 0.25f, 2f);
        atmoSize = clamp(atmoSize, 0.5f, 2f);
        vignette = clamp(vignette, 0f, 1f);
        haze = clamp(haze, 0f, 1f);
        rainbowSpeed = clamp(rainbowSpeed, 0f, 2f);
        rainbowSpread = clamp(rainbowSpread, 0f, 1f);
        opacity = clamp(opacity, 0.2f, 1f);
        vfxAmount = clamp(vfxAmount, 0.25f, 2f);
        skyBrightness = clamp(skyBrightness, 0.3f, 1.5f);
        skySpin = clamp(skySpin, 0f, 3f);
        if (sky < 0 || sky >= dev.markie.lyrics.sky.SkyLibrary.ALL.length) sky = 0;
        if (reveal == null) reveal = Reveal.WORD;
        size = clamp(size, 0.5f, 2f);
        skyDistance = clamp(skyDistance, 16f, 128f);
        floatDistance = clamp(floatDistance, 2f, 24f);
        skyHeight = clamp(skyHeight, 0f, 70f);
        floatHeight = clamp(floatHeight, -20f, 30f);
        spread = clamp(spread, 0f, 2f);
        screenY = clamp(screenY, 0.05f, 0.95f);
        uiVolume = clamp(uiVolume, 0f, 1f);
        if (theme < 0 || theme >= dev.markie.lyrics.gui.Themes.ALL.length) theme = 0;
        if (font < 0 || font >= dev.markie.lyrics.gui.Fonts.ALL.length) font = 0;
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
