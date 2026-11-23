package dev.clicksounds.audio;

import dev.clicksounds.ClickSoundsClient;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;

/**
 * Listens to the default microphone into a 4 second ring buffer. The recorder screen
 * marks the moment you click, waits a bit, then cuts the click out of the buffer.
 */
public final class MicRecorder {
    private static final int CHUNK = 256;

    private TargetDataLine line;
    private float rate;
    private int channels;
    private float[] ring;
    private volatile long total;   // frames written so far
    private volatile float level;  // recent peak, for the on-screen meter
    private volatile boolean running;
    private volatile String error;
    private Thread thread;

    /** @return null on success, otherwise a message for the player */
    public String start() {
        float[] rates = {44100f, 48000f};
        int[] chans = {1, 2};
        for (float r : rates) {
            for (int c : chans) {
                AudioFormat fmt = new AudioFormat(r, 16, c, true, false);
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
                if (!AudioSystem.isLineSupported(info)) continue;
                try {
                    TargetDataLine l = (TargetDataLine) AudioSystem.getLine(info);
                    try {
                        l.open(fmt, (int) (r * 0.05f) * fmt.getFrameSize()); // ~50 ms buffer
                    } catch (Exception small) {
                        l.open(fmt);
                    }
                    line = l;
                    rate = r;
                    channels = c;
                    break;
                } catch (Exception e) {
                    ClickSoundsClient.LOG.debug("Mic format {} failed", fmt, e);
                }
            }
            if (line != null) break;
        }
        if (line == null) return "No microphone found (or Java isn't allowed to use it)";

        ring = new float[(int) (rate * 4)];
        total = 0;
        running = true;
        line.start();
        thread = new Thread(this::run, "ClickSounds-Mic");
        thread.setDaemon(true);
        thread.start();
        ClickSoundsClient.LOG.info("Mic open: {}", line.getFormat());
        return null;
    }

    private void run() {
        byte[] buf = new byte[CHUNK * channels * 2];
        try {
            while (running) {
                int n = line.read(buf, 0, buf.length);
                int frames = n / (channels * 2);
                if (frames <= 0) continue;
                float[] mono = Wav.toMono(buf, frames, channels);
                long t = total;
                float pk = 0f;
                for (int i = 0; i < frames; i++) {
                    ring[(int) ((t + i) % ring.length)] = mono[i];
                    pk = Math.max(pk, Math.abs(mono[i]));
                }
                total = t + frames;
                level = Math.max(pk, level * 0.85f);
            }
        } catch (Exception e) {
            if (running) {
                error = "Microphone stopped: " + e.getMessage();
                ClickSoundsClient.LOG.warn("Mic read failed", e);
            }
        }
    }

    public void stop() {
        running = false;
        if (line != null) {
            try { line.stop(); line.close(); } catch (Exception ignored) {}
        }
        line = null;
    }

    public long now() { return total; }
    public float rate() { return rate; }
    public float level() { return level; }
    public String error() { return error; }

    /** Copies frames [from, from+len) out of the ring buffer (missing frames are silence). */
    public float[] extract(long from, int len) {
        float[] out = new float[len];
        long end = total;
        long oldest = end - ring.length;
        for (int i = 0; i < len; i++) {
            long f = from + i;
            if (f >= oldest && f < end && f >= 0) out[i] = ring[(int) (f % ring.length)];
        }
        return out;
    }
}
