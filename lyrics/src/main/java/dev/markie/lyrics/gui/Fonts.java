package dev.markie.lyrics.gui;

import net.minecraft.text.StyleSpriteSource;
import net.minecraft.util.Identifier;

/** Fonts the lyrics can use (bundled Google Fonts, plus Minecraft's own). */
public final class Fonts {
    public record FontChoice(String name, StyleSpriteSource source) {}

    public static final FontChoice[] ALL = {
        mod("Poppins Bold", "poppins_bold"),
        mod("Poppins", "poppins"),
        mod("Montserrat", "montserrat"),
        mod("Fredoka", "fredoka"),
        mod("Bebas Neue", "bebas"),
        mod("Pacifico", "pacifico"),
        mod("Caveat", "caveat"),
        new FontChoice("Minecraft", StyleSpriteSource.DEFAULT),
    };

    public static final StyleSpriteSource UI = ALL[0].source();

    private Fonts() {}

    private static FontChoice mod(String name, String id) {
        return new FontChoice(name, new StyleSpriteSource.Font(Identifier.of("markielyrics", id)));
    }

    public static StyleSpriteSource current() {
        return ALL[Math.floorMod(dev.markie.lyrics.config.LyricsConfig.get().font, ALL.length)].source();
    }
}
