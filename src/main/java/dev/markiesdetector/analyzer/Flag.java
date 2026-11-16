package dev.markiesdetector.analyzer;

/** One suspicious moment: which check, how bad (0..1), the measured value and where it happened. */
public class Flag {
    public int t;
    public CheckType c;
    public float sev;
    public double v;
    public String d;
    public double x, y, z;

    public Flag() {}

    public Flag(int t, CheckType c, float sev, double v, String detail, PState where) {
        this.t = t;
        this.c = c;
        this.sev = Math.max(0f, Math.min(1f, sev));
        this.v = v;
        this.d = detail;
        if (where != null) { x = where.x; y = where.y; z = where.z; }
    }
}
