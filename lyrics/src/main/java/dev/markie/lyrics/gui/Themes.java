package dev.markie.lyrics.gui;

/** Colour looks for the lyrics. Words fade from colour A to colour B across a line. */
public final class Themes {
    public record Theme(String name, int a, int b, int glow) {}

    public static final Theme[] ALL = {
        new Theme("Lavender", 0xC9A8FF, 0xF1E6FF, 0x7B4FD6),
        new Theme("Dreamy",   0xFFA6D9, 0xA8C8FF, 0xB05CC8),
        new Theme("Sunset",   0xFFC27A, 0xFF7AA2, 0xD9534F),
        new Theme("Ice",      0xD6F7FF, 0x86CFFF, 0x3D8BD9),
        new Theme("Royal",    0x9B6BFF, 0xFFD36B, 0x5A2DB8),
        new Theme("Mint",     0xB5FFD9, 0x5EE6C0, 0x1F9E84),
        new Theme("Peach",    0xFFF0D6, 0xFFC4A3, 0xD98A5E),
        new Theme("Neon",     0x3DFFE0, 0xFF4DF0, 0x8A2BE2),
        new Theme("Cloud",    0xFFFFFF, 0xE4E4F0, 0x8888AA),
        new Theme("Cherry",   0xFF5C7A, 0xFFB3C1, 0xA0203C),
    };

    private Themes() {}

    public static Theme current() {
        return ALL[Math.floorMod(dev.markie.lyrics.config.LyricsConfig.get().theme, ALL.length)];
    }
}
