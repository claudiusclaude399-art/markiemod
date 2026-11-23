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
        try {
            Files.createDirectories(file().getParent());
            try (Writer w = Files.newBufferedWriter(file())) {
                p.store(w, "Click Sounds settings (volumes: 0.0 - 2.0)");
            }
        } catch (Exception e) {
            ClickSoundsClient.LOG.warn("Could not save {}", file(), e);
        }
    }

    private static float clamp(float v) {
        return Float.isNaN(v) ? 1f : Math.max(0f, Math.min(2f, v));
    }
}
