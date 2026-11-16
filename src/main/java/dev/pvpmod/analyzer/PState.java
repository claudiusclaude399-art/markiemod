package dev.pvpmod.analyzer;

/** One player's state in one tick. Short field names keep the saved fights small. */
public class PState {
    public static final int GROUND = 1, SPRINT = 2, SNEAK = 4, GLIDE = 8, FLUID = 16, CLIMB = 32,
        SUPPORTED = 64, USING = 128, VEHICLE = 256, CREATIVE = 512, ABSENT = 1024, SLIPPERY = 2048, WATER_BELOW = 4096;

    public double x, y, z;
    public float yw, pt;     // yaw (head), pitch
    public float hp, ab;     // health, absorption
    public float e = 1.62f;  // eye height
    public float h = 1.8f;   // box height
    public float w = 0.6f;   // box width
    public int f;            // flag bits
    public int s;            // swings this tick
    public String m = "", o = "";   // main hand / off hand item ids

    public boolean has(int bit) { return (f & bit) != 0; }

    public double eyeY() { return y + e; }

    public PState copy() {
        PState p = new PState();
        p.x = x; p.y = y; p.z = z; p.yw = yw; p.pt = pt; p.hp = hp; p.ab = ab;
        p.e = e; p.h = h; p.w = w; p.f = f; p.s = s; p.m = m; p.o = o;
        return p;
    }

    /** Linear interpolation between two states (for smooth replay). */
    public static PState lerp(PState a, PState b, float t) {
        if (a == null) return b;
        if (b == null || t <= 0) return a;
        PState p = a.copy();
        p.x = a.x + (b.x - a.x) * t;
        p.y = a.y + (b.y - a.y) * t;
        p.z = a.z + (b.z - a.z) * t;
        p.yw = a.yw + wrap(b.yw - a.yw) * t;
        p.pt = a.pt + (b.pt - a.pt) * t;
        p.hp = a.hp + (b.hp - a.hp) * t;
        return p;
    }

    public static float wrap(float deg) {
        deg %= 360f;
        if (deg >= 180f) deg -= 360f;
        if (deg < -180f) deg += 360f;
        return deg;
    }
}
