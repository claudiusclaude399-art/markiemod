package dev.markiesdetector.analyzer;


import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Saves the blocks around a fight so the 3D replay can show the real map. Only blocks you could see are kept
 * (at least one face touching air or a see-through block), each with its map colour, height and visible faces.
 * The area grows in 8x8 cells as the fight moves around.
 *
 * Stored as 5 ints per block: x, y, z, rgb, info (bits 0-5 visible faces [down, up, north, south, west, east],
 * bits 6-10 height in 1/16, bit 11 water, bit 12 lava).
 */
public final class WorldSnapshot {
    public static final int STRIDE = 5;
    public static final int FACE_DOWN = 1, FACE_UP = 2, FACE_NORTH = 4, FACE_SOUTH = 8, FACE_WEST = 16, FACE_EAST = 32;
    public static final int WATER = 1 << 11, LAVA = 1 << 12;
    static final int CELL = 8, MAX_BLOCKS = 30000;

    private final Set<Long> cells = new HashSet<>();
    private int[] data = new int[4096];
    private int size = 0;

    /** Marks an 8x8 cell as captured; false if it already was. */
    boolean claim(int cx, int cz) {
        return cells.add((((long) cx) << 32) ^ (cz & 0xffffffffL));
    }

    boolean full() { return size / STRIDE >= MAX_BLOCKS; }

    public void add(int x, int y, int z, int rgb, int info) {
        if (size + STRIDE > data.length) data = Arrays.copyOf(data, data.length * 2);
        data[size++] = x;
        data[size++] = y;
        data[size++] = z;
        data[size++] = rgb;
        data[size++] = info;
    }

    public int blocks() { return size / STRIDE; }

    public int[] toArray() { return size == 0 ? null : Arrays.copyOf(data, size); }
}
