package dev.markie.lyrics.music;

import dev.markie.lyrics.config.LyricsConfig;
import dev.markie.lyrics.lyrics.LyricLine;
import dev.markie.lyrics.lyrics.LyricsManager;
import dev.markie.lyrics.spotify.NowPlaying;
import dev.markie.lyrics.spotify.SpotifyClient;

import java.util.ArrayList;
import java.util.List;

/**
 * The "heartbeat" everything reacts to.
 * <p>
 * Spotify doesn't share audio data any more, so by default the beat comes from the lyrics: the tempo is
 * estimated from when words are sung, then a steady beat grid is locked to the vocals. It keeps running
 * through instrumental parts and follows pauses and seeks. Optionally {@link AudioListener} listens to a
 * real audio device instead (e.g. "Stereo Mix") and detects bass hits.
 * <p>
 * Outputs (all 0..1): {@link #beat()} a sharp pulse on each beat, {@link #bar()} a stronger pulse every
 * 4 beats, {@link #energy()} how busy the music is right now, {@link #level()} a smoothed loudness.
 */
public final class MusicPulse {
    private static List<LyricLine> analysed;
    private static long periodMs = 500, phaseMs = 0;  // beat grid: beats at phase + k * period
    private static boolean haveGrid = false;

    private static float beat, bar, energy, level, kick;
    private static long lastFrameNs;
    private static double musicTime;
    private static long lastBeatIndex = Long.MIN_VALUE;
    private static long beatCounter;

    private MusicPulse() {}

    public static float beat() { return beat; }
    public static float bar() { return bar; }
    public static float energy() { return energy; }
    public static float level() { return level; }
    /** Seconds of "music time": runs faster when the music is busy, stops when paused (for drifting things). */
    public static double musicTime() { return musicTime; }
    public static long beats() { return beatCounter; }
    public static float bpm() { return haveGrid ? 60000f / periodMs : 0f; }

    /** Overall strength multiplier from the settings (0 when reacting is off). */
    public static float strength() {
        LyricsConfig c = LyricsConfig.get();
        return c.react ? c.reactStrength : 0f;
    }

    /** Call every frame (cheap; repeated calls in the same frame do nothing). */
    public static void update() {
        long now = System.nanoTime();
        float dt = lastFrameNs == 0 ? 0 : Math.min(0.1f, (now - lastFrameNs) / 1e9f);
        if (dt <= 0.0005f && lastFrameNs != 0) return;
        lastFrameNs = now;
        dev.markie.lyrics.render.EnvColors.update(dt);

        LyricsConfig c = LyricsConfig.get();
        NowPlaying np = SpotifyClient.current();
        boolean playing = np != null && np.playing() && c.react;
        float targetEnergy = 0f, hit = 0f;

        if (playing && c.listen == LyricsConfig.Listen.AUDIO && AudioListener.running()) {
            // real sound
            AudioListener.Reading r = AudioListener.read();
            targetEnergy = r.level();
            if (r.beats() > beatCounter) { hit = 1f; beatCounter = r.beats(); }
            level += (r.level() - level) * (1f - (float) Math.exp(-12f * dt));
        } else if (playing) {
            List<LyricLine> lines = LyricsManager.lines();
            if (lines != analysed) analyse(lines);
            long pos = LyricsManager.position();
            long bi = Math.floorDiv(pos - phaseMs, periodMs);
            if (bi != lastBeatIndex) {
                if (lastBeatIndex != Long.MIN_VALUE && bi == lastBeatIndex + 1) { hit = 1f; beatCounter++; }
                lastBeatIndex = bi;
            }
            targetEnergy = vocalDensity(lines, pos);
            level += (targetEnergy * 0.8f + beat * 0.2f - level) * (1f - (float) Math.exp(-6f * dt));
            if (LyricsManager.state() != LyricsManager.State.SYNCED) targetEnergy = 0.45f;
        } else {
            lastBeatIndex = Long.MIN_VALUE;
            level += (0f - level) * (1f - (float) Math.exp(-3f * dt));
        }
        if (c.listen == LyricsConfig.Listen.AUDIO && c.react) AudioListener.ensure(c.audioDevice);
        else AudioListener.stop();

        if (hit > 0) {
            beat = 1f;
            if (beatCounter % 4 == 0) bar = 1f;
        }
        beat *= (float) Math.exp(-dt / 0.16f);
        bar *= (float) Math.exp(-dt / 0.35f);
        kick *= (float) Math.exp(-dt / 0.1f);
        energy += (targetEnergy - energy) * (1f - (float) Math.exp(-1.5f * dt));
        if (playing) musicTime += dt * (0.6 + 1.2 * energy);
    }

    /** How busy the vocals are around now: words per second in a 3 s window, mapped to 0..1. */
    private static float vocalDensity(List<LyricLine> lines, long pos) {
        if (lines == null || lines.isEmpty()) return 0.45f;
        int words = 0;
        for (LyricLine l : lines) {
            if (l.startMs() > pos + 1500) break;
            if (l.endMs() < pos - 1500) continue;
            for (LyricLine.Word w : l.words()) if (Math.abs(w.startMs() - pos) < 1500) words++;
        }
        return Math.min(1f, 0.3f + words / 9f);
    }

    /**
     * Estimate tempo and beat phase from the word start times: for each candidate period (60..180 BPM) add up
     * the onsets as unit vectors on a circle; the period whose onsets line up best wins, and the angle of the
     * sum gives the phase. Tempos near 90..130 BPM get a gentle preference, like most pop music.
     */
    private static void analyse(List<LyricLine> lines) {
        analysed = lines;
        List<Long> onsets = new ArrayList<>();
        if (lines != null) for (LyricLine l : lines) for (LyricLine.Word w : l.words()) onsets.add(w.startMs());
        if (onsets.size() < 8) { periodMs = 500; phaseMs = onsets.isEmpty() ? 0 : onsets.get(0); haveGrid = !onsets.isEmpty(); return; }
        double bestScore = -1;
        long bestP = 500;
        double bestAngle = 0;
        for (long p = 333; p <= 1000; p += 4) {
            double sx = 0, sy = 0;
            for (long t : onsets) {
                double a = 2 * Math.PI * (t % p) / p;
                sx += Math.cos(a);
                sy += Math.sin(a);
            }
            double mag = Math.sqrt(sx * sx + sy * sy) / onsets.size();
            double bpm = 60000.0 / p;
            double pref = Math.exp(-Math.pow((bpm - 110) / 45.0, 2));   // prefer 65..155 BPM, peak ~110
            double score = mag * (0.6 + 0.4 * pref);
            if (score > bestScore) { bestScore = score; bestP = p; bestAngle = Math.atan2(sy, sx); }
        }
        periodMs = bestP;
        double ph = bestAngle / (2 * Math.PI) * bestP;
        phaseMs = Math.floorMod(Math.round(ph), bestP);
        haveGrid = true;
    }
}
