package dev.clicksounds;

import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Tiny properties-file config: config/clicksounds.properties */
public final class ClickConfig {
    public static boolean mouseEnabled = true;
    public static boolean keyboardEnabled = true;
    /** 0..2 (100% = 1.0). */
    public static float mouseVolume = 1.0f;
    public static float keyboardVolume = 1.0f;
    /** Small random pitch/volume changes on every play, on top of the 10 random takes. */
    public static boolean variation = true;
    /** Also play the recorded "let go" sound when a button / key is released. */
    public static boolean releaseSounds = true;
    /** Device names; empty = system default. */
    public static String micDevice = "";
    public static String outputDevice = "";
    /** Active clickpack (folder name in config/clicksounds/packs) per device. */
    public static String mousePack = "My clicks";
    public static String keyboardPack = "My clicks";
    /** Optional genres: soft clicks when clicking fast, hard clicks after a pause. */
    public static boolean softClicks = true;
    public static boolean hardClicks = true;
    /** Presses closer together than this (ms) use soft clicks. */
    public static int softMs = 120;
    /** Presses after a pause longer than this (ms) use hard clicks. */
    public static int hardMs = 800;

    private ClickConfig() {}

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("clicksounds.properties");
    }

    public static void load() {
        Path f = file();
        if (!Files.exists(f)) { save(); return; }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(f)) {
            p.load(r);
            mouseEnabled = Boolean.parseBoolean(p.getProperty("mouseEnabled", "true"));
            keyboardEnabled = Boolean.parseBoolean(p.getProperty("keyboardEnabled", "true"));
            mouseVolume = clamp(Float.parseFloat(p.getProperty("mouseVolume", "1.0")));
            keyboardVolume = clamp(Float.parseFloat(p.getProperty("keyboardVolume", "1.0")));
            variation = Boolean.parseBoolean(p.getProperty("variation", "true"));
            releaseSounds = Boolean.parseBoolean(p.getProperty("releaseSounds", "true"));
            micDevice = p.getProperty("micDevice", "");
            outputDevice = p.getProperty("outputDevice", "");
            mousePack = p.getProperty("mousePack", "My clicks");
            keyboardPack = p.getProperty("keyboardPack", "My clicks");
            softClicks = Boolean.parseBoolean(p.getProperty("softClicks", "true"));
            hardClicks = Boolean.parseBoolean(p.getProperty("hardClicks", "true"));
            softMs = clampInt(p.getProperty("softMs", "120"), 20, 500, 120);
            hardMs = clampInt(p.getProperty("hardMs", "800"), 200, 5000, 800);
        } catch (Exception e) {
            ClickSoundsClient.LOG.warn("Could not read {}, using defaults", f, e);
        }
    }

    public static void save() {
        Properties p = new Properties();
        p.setProperty("mouseEnabled", Boolean.toString(mouseEnabled));
        p.setProperty("keyboardEnabled", Boolean.toString(keyboardEnabled));
        p.setProperty("mouseVolume", Float.toString(mouseVolume));
        p.setProperty("keyboardVolume", Float.toString(keyboardVolume));
        p.setProperty("variation", Boolean.toString(variation));
        p.setProperty("releaseSounds", Boolean.toString(releaseSounds));
        p.setProperty("micDevice", micDevice);
        p.setProperty("outputDevice", outputDevice);
        p.setProperty("mousePack", mousePack);
        p.setProperty("keyboardPack", keyboardPack);
        p.setProperty("softClicks", Boolean.toString(softClicks));
        p.setProperty("hardClicks", Boolean.toString(hardClicks));
        p.setProperty("softMs", Integer.toString(softMs));
        p.setProperty("hardMs", Integer.toString(hardMs));
        try {
            Files.createDirectories(file().getParent());
            try (Writer w = Files.newBufferedWriter(file())) {
                p.store(w, "Click Sounds settings (volumes: 0.0 - 2.0)");
            }
        } catch (Exception e) {
            ClickSoundsClient.LOG.warn("Could not save {}", file(), e);
        }
    }

    private static int clampInt(String s, int min, int max, int def) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(s.trim()))); } catch (Exception e) { return def; }
    }

    private static float clamp(float v) {
        return Float.isNaN(v) ? 1f : Math.max(0f, Math.min(2f, v));
    }
}
