package dev.clicksounds.audio;

/**
 * Finds the click inside a ~0.65 s chunk of mic audio recorded around the moment you
 * clicked, and cuts it out tightly: starts right at the attack, ends once it has died
 * away, with tiny fades so it never pops.
 */
public final class ClickExtractor {
    private ClickExtractor() {}

    /** @return the click (mono, at {@code rate}), or null if nothing clearly louder than background noise was heard */
    public static float[] extract(float[] seg, float rate) {
        // remove any DC offset the mic has
        double mean = 0;
        for (float v : seg) mean += v;
        mean /= seg.length;
        float[] x = new float[seg.length];
        for (int i = 0; i < seg.length; i++) x[i] = (float) (seg[i] - mean);

        float peak = Wav.peak(x);
        float noise = quietestRms(x, (int) (rate * 0.03f));
        if (peak < 0.003f || peak < noise * 6f) return null;

        // the press is the first transient that gets near the loudest point
        // (picks the press rather than the release if both were caught)
        int first = 0;
        while (first < x.length && Math.abs(x[first]) < peak * 0.5f) first++;

        // walk back to where the attack actually begins
        int win = Math.max(1, (int) (rate * 0.001f));
        int limit = Math.max(0, first - (int) (rate * 0.025f));
        float floor = Math.max(peak * 0.06f, noise * 2f);
        int onset = first;
        while (onset - win > limit && maxAbs(x, onset - win, onset) > floor) onset -= win;
        int start = Math.max(0, onset - (int) (rate * 0.003f));

        // up to 220 ms long, trimmed once it has decayed into the noise
        int maxEnd = Math.min(x.length, start + (int) (rate * 0.22f));
        int lastLoud = first;
        float tail = Math.max(peak * 0.03f, noise * 2f);
        for (int i = first; i < maxEnd; i++) if (Math.abs(x[i]) > tail) lastLoud = i;
        int end = Math.min(maxEnd, lastLoud + (int) (rate * 0.025f));
        end = Math.max(end, Math.min(maxEnd, first + (int) (rate * 0.04f)));

        float[] out = new float[end - start];
        System.arraycopy(x, start, out, 0, out.length);

        int fadeIn = Math.min(out.length / 4, (int) (rate * 0.002f));
        for (int i = 0; i < fadeIn; i++) out[i] *= i / (float) fadeIn;
        int fadeOut = Math.min(out.length / 3, (int) (rate * 0.04f));
        for (int i = 0; i < fadeOut; i++) {
            float g = (float) (0.5 + 0.5 * Math.cos(Math.PI * (i + 1) / fadeOut));
            out[out.length - fadeOut + i] *= g;
        }
        return out;
    }

    private static float maxAbs(float[] x, int from, int to) {
        float m = 0f;
        for (int i = Math.max(0, from); i < Math.min(x.length, to); i++) m = Math.max(m, Math.abs(x[i]));
        return m;
    }

    private static float quietestRms(float[] x, int win) {
        if (x.length <= win) return 0f;
        double best = Double.MAX_VALUE;
        for (int s = 0; s + win <= x.length; s += win / 2) {
            double sum = 0;
            for (int i = s; i < s + win; i++) sum += x[i] * x[i];
            best = Math.min(best, sum / win);
        }
        return (float) Math.sqrt(best);
    }
}
