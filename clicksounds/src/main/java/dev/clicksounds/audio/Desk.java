package dev.clicksounds.audio;

import dev.clicksounds.ClickConfig;

import java.util.Map;

/**
 * Where things sit on a desk, as stereo pan (-1 left ... 1 right): the keyboard on the left with
 * every key at its own spot (A further left than L, Enter right of P), the mouse on the right.
 */
public final class Desk {
    private Desk() {}

    private static final String[] ROWS = {"`1234567890-=", "qwertyuiop[]|", "asdfghjkl;'", "zxcvbnm,./"};
    /** Minecraft's names for symbol keys -> the character used in ROWS. */
    private static final Map<String, Character> SYMBOLS = Map.ofEntries(
        Map.entry("grave.accent", '`'), Map.entry("minus", '-'), Map.entry("equal", '='),
        Map.entry("left.bracket", '['), Map.entry("right.bracket", ']'), Map.entry("backslash", '|'),
        Map.entry("semicolon", ';'), Map.entry("apostrophe", '\''), Map.entry("comma", ','),
        Map.entry("period", '.'), Map.entry("slash", '/'));
    private static final float[] ROW_SHIFT = {0f, 0.04f, 0.06f, 0.09f};

    /** Keys that aren't letters/digits: their x position across the keyboard (0 = left edge, 1 = right edge). */
    private static final Map<String, Float> SPECIAL = Map.ofEntries(
        Map.entry("escape", 0.02f), Map.entry("tab", 0.03f), Map.entry("caps.lock", 0.03f),
        Map.entry("left.shift", 0.05f), Map.entry("left.control", 0.03f), Map.entry("left.win", 0.1f),
        Map.entry("left.alt", 0.16f), Map.entry("space", 0.42f), Map.entry("right.alt", 0.62f),
        Map.entry("right.win", 0.67f), Map.entry("menu", 0.7f), Map.entry("right.control", 0.73f),
        Map.entry("right.shift", 0.7f), Map.entry("enter", 0.74f), Map.entry("backspace", 0.74f),
        Map.entry("insert", 0.8f), Map.entry("home", 0.83f), Map.entry("page.up", 0.86f),
        Map.entry("delete", 0.8f), Map.entry("end", 0.83f), Map.entry("page.down", 0.86f),
        Map.entry("up", 0.83f), Map.entry("left", 0.8f), Map.entry("down", 0.83f), Map.entry("right", 0.86f),
        Map.entry("print.screen", 0.8f), Map.entry("scroll.lock", 0.83f), Map.entry("pause", 0.86f),
        Map.entry("num.lock", 0.9f));

    /** x position (0..1) of a key across a full-size keyboard. */
    static float keyX(String key) {
        if (key == null) return 0.4f;
        Float s = SPECIAL.get(key);
        if (s != null) return s;
        Character sym = SYMBOLS.get(key);
        if (key.length() == 1 || sym != null) {
            char c = sym != null ? sym : key.charAt(0);
            for (int r = 0; r < ROWS.length; r++) {
                int i = ROWS[r].indexOf(c);
                if (i >= 0) return ROW_SHIFT[r] + i * 0.75f / 13f; // the main block covers ~75% of the width
            }
        }
        if (key.startsWith("f") && key.length() <= 3 && key.substring(1).chars().allMatch(Character::isDigit)) {
            int n = Integer.parseInt(key.substring(1));
            return 0.08f + (Math.min(12, n) - 1) * 0.055f;
        }
        if (key.startsWith("keypad.")) return 0.94f;
        return 0.4f;
    }

    /** Pan for a key press. */
    public static float key(String key) {
        if (!ClickConfig.deskStereo) return 0f;
        // keyboard spans from far left (-0.9) to just right of centre (+0.1)
        return ClickConfig.stereoWidth * (-0.9f + keyX(key));
    }

    /** Pan for the mouse (buttons and wheel). */
    public static float mouse() {
        return ClickConfig.deskStereo ? ClickConfig.stereoWidth * 0.7f : 0f;
    }
}
