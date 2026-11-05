package dev.pvpmod;

/** Tiny shared state for the colour tints. */
public final class PvpTint {
    public static final int NONE = -1;
    /** Marker put at index 0 of a tint array so the renderer knows index 1 is our RGB colour. */
    public static final int MAGIC = 0x5056504D;
    /** RGB colour for the item currently being submitted in first person, or NONE. */
    public static int current = NONE;
    /** RGB colour for the armor piece currently being rendered on a player, or NONE. */
    public static int armor = NONE;

    private PvpTint() {}
}
