package dev.markie.lyrics;

import dev.markie.lyrics.config.LyricsConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

/**
 * The mod's own UI sounds (assets/markielyrics/sounds). They're only played on the client,
 * so they don't need registering and work on any server.
 */
public final class Sounds {
    public static final SoundEvent HOVER = of("ui.hover");
    public static final SoundEvent CLICK = of("ui.click");
    public static final SoundEvent TOGGLE_ON = of("ui.toggle_on");
    public static final SoundEvent TOGGLE_OFF = of("ui.toggle_off");
    public static final SoundEvent TAB = of("ui.tab");
    public static final SoundEvent OPEN = of("ui.open");
    public static final SoundEvent CLOSE = of("ui.close");
    public static final SoundEvent SLIDER = of("ui.slider");
    public static final SoundEvent CONNECT = of("ui.connect");
    public static final SoundEvent ERROR = of("ui.error");
    public static final SoundEvent LINE = of("lyric.line");

    private Sounds() {}

    private static SoundEvent of(String path) { return SoundEvent.of(Identifier.of("markielyrics", path)); }

    public static void play(SoundEvent sound) { play(sound, 1f); }

    public static void play(SoundEvent sound, float pitch) {
        LyricsConfig cfg = LyricsConfig.get();
        if (!cfg.uiSounds && sound != LINE) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getSoundManager() == null) return;
        mc.getSoundManager().play(PositionedSoundInstance.ui(sound, pitch, cfg.uiVolume));
    }

    /** Safe to call from any thread. */
    public static void playLater(SoundEvent sound) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null) mc.execute(() -> play(sound));
    }
}
