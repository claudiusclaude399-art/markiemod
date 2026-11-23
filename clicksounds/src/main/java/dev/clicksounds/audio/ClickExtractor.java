package dev.clicksounds.audio;

/**
 * Finds the press (and release) inside a chunk of mic audio recorded around the moment you
 * clicked, and cuts each out tightly: starts right at the attack, ends once it has died away,
 * with tiny fades so it never pops.
 *
 * The mic delivers audio a bit late (driver buffering), so the press is searched for in a wide
 * window around the press time. The delay found there is then reused to find the release
 * precisely, since the gap between press and release is known exactly.
 */
public final class ClickExtractor {
    private ClickExtractor() {}

    /**
     * @param seg        mic audio, first sample = frame {@code segStart}
     * @param pressMark  mic frame when the press happened
     * @param releaseMark mic frame when the release happened, or -1 if unknown
     * @return the take, or null if no click clearly louder than the background noise was heard
     */
    public static Take extract(float[] seg, long segStart, long pressMark, long releaseMark, float rate) {
        float[] x = removeDc(seg);
        float noise = quietestRms(x, (int) (rate * 0.03f));
        int ms = Math.max(1, (int) (rate / 1000f));

        int pm = (int) (pressMark - segStart);
        int pressOn = onset(x, Math.max(0, pm - 250 * ms), Math.min(x.length, pm + 350 * ms), 0, noise, rate);
        if (pressOn < 0) return null;

        int relOn = -1;
        if (releaseMark >= 0) {
            int lag = Math.max(-50 * ms, Math.min(300 * ms, pressOn - pm));
            int expected = (int) (releaseMark - segStart) + lag;
            int from = Math.max(pressOn + 15 * ms, expected - 40 * ms);
            int to = Math.min(x.length, expected + 80 * ms);
            if (to - from > 10 * ms) relOn = onset(x, from, to, from, noise, rate);
        }

        int pressEnd = Math.min(x.length, pressOn + 220 * ms);
        if (relOn >= 0) pressEnd = Math.min(pressEnd, relOn - ms);
        float[] press = cut(x, pressOn, pressEnd, noise, rate);
        float[] release = relOn >= 0 ? cut(x, relOn, Math.min(x.length, relOn + 180 * ms), noise, rate) : null;
        return new Take(press, release);
    }

    /** Start of the first strong transient in [from, to), with 3 ms of pre-roll; -1 if nothing there. */
    static int onset(float[] x, int from, int to, int lowerBound, float noise, float rate) {
        float peak = maxAbs(x, from, to);
        if (peak < 0.003f || peak < noise * 6f) return -1;
        // the first transient that gets reasonably close to the loudest point
        // (picks the press rather than a louder release if both are in range)
        int first = from;
        while (first < to && Math.abs(x[first]) < peak * 0.35f) first++;
        // walk back to where the attack actually begins
        int win = Math.max(1, (int) (rate * 0.001f));
        int limit = Math.max(lowerBound, first - (int) (rate * 0.025f));
        float floor = Math.max(peak * 0.06f, noise * 2f);
        int on = first;
        while (on - win > limit && maxAbs(x, on - win, on) > floor) on -= win;
        return Math.max(lowerBound, on - (int) (rate * 0.003f));
    }

    /** Copies [start, maxEnd), trimmed once it has decayed into the noise, with short fades. */
    static float[] cut(float[] x, int start, int maxEnd, float noise, float rate) {
        float peak = maxAbs(x, start, maxEnd);
        // last 5 ms window that is still clearly louder than the background hiss
        int win = Math.max(1, (int) (rate * 0.005f));
        float tail = Math.max(peak * 0.03f, noise * 2.5f);
        int lastLoud = start;
        for (int w = start; w < maxEnd; w += win) {
            int we = Math.min(maxEnd, w + win);
            double sum = 0;
            for (int i = w; i < we; i++) sum += x[i] * x[i];
            if (Math.sqrt(sum / Math.max(1, we - w)) > tail) lastLoud = we;
        }
        int end = Math.min(maxEnd, lastLoud + (int) (rate * 0.02f));
        end = Math.max(end, Math.min(maxEnd, start + (int) (rate * 0.03f)));

        float[] out = new float[Math.max(1, end - start)];
        System.arraycopy(x, start, out, 0, Math.min(out.length, x.length - start));

        int fadeIn = Math.min(out.length / 4, (int) (rate * 0.002f));
        for (int i = 0; i < fadeIn; i++) out[i] *= i / (float) fadeIn;
        int fadeOut = Math.min(out.length / 3, (int) (rate * 0.04f));
        for (int i = 0; i < fadeOut; i++) {
            float g = (float) (0.5 + 0.5 * Math.cos(Math.PI * (i + 1) / fadeOut));
            out[out.length - fadeOut + i] *= g;
        }
        return out;
    }

    private static float[] removeDc(float[] seg) {
        double mean = 0;
        for (float v : seg) mean += v;
        mean /= Math.max(1, seg.length);
        float[] x = new float[seg.length];
        for (int i = 0; i < seg.length; i++) x[i] = (float) (seg[i] - mean);
        return x;
    }

    private static float maxAbs(float[] x, int from, int to) {
        float m = 0f;
        for (int i = Math.max(0, from); i < Math.min(x.length, to); i++) m = Math.max(m, Math.abs(x[i]));
        return m;
    }

    private static float quietestRms(float[] x, int win) {
        if (x.length <= win || win < 2) return 0f;
        double best = Double.MAX_VALUE;
        for (int s = 0; s + win <= x.length; s += win / 2) {
            double sum = 0;
            for (int i = s; i < s + win; i++) sum += x[i] * x[i];
            if (sum > 0) best = Math.min(best, sum / win); // all-zero = before the mic started, ignore
        }
        return best == Double.MAX_VALUE ? 0f : (float) Math.sqrt(best);
    }
}
