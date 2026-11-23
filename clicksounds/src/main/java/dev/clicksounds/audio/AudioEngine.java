package dev.clicksounds.audio;

import dev.clicksounds.ClickConfig;
import dev.clicksounds.ClickSoundsClient;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Low-latency software mixer. Keeps one output line open (default device, or the one picked
 * in the menu) and mixes any number of overlapping click sounds into it, so fast clicking /
 * typing never cuts a sound off. Plays outside Minecraft's sound engine, so OBS / Discord
 * desktop audio picks it up like any other app.
 */
public final class AudioEngine {
    public static final float RATE = 44100f;
    private static final int CHUNK = 256;          // frames mixed per write (~6 ms)
    private static final int LINE_BUFFER = 1024;   // frames buffered in the device (~23 ms)
    private static final int MAX_VOICES = 48;

    private static final List<Voice> voices = new ArrayList<>();
    private static volatile boolean reopen;
    private static Thread thread;

    private AudioEngine() {}

    private static final class Voice {
        final float[] data;
        final float gainL, gainR;
        final double step;
        double pos; // negative = still waiting to start

        Voice(float[] data, float gain, float pan, double step, double pos) {
            this.data = data;
            // balance pan: centre keeps full level on both sides, so mono and stereo sound equally loud
            this.gainL = gain * Math.min(1f, 1f - pan);
            this.gainR = gain * Math.min(1f, 1f + pan);
            this.step = step;
            this.pos = pos;
        }
    }

    public static synchronized void start() {
        if (thread != null) return;
        thread = new Thread(AudioEngine::run, "ClickSounds-Audio");
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    /** Re-open the output (after the output device setting changed). */
    public static void reopen() {
        reopen = true;
    }

    public static void play(float[] data, float gain, float pitch) {
        play(data, gain, pitch, 0, 0f);
    }

    public static void play(float[] data, float gain, float pitch, int delayMs) {
        play(data, gain, pitch, delayMs, 0f);
    }

    /**
     * @param pitch   1.0 = original speed
     * @param delayMs start this much later
     * @param pan     -1 = left, 0 = centre, 1 = right
     */
    public static void play(float[] data, float gain, float pitch, int delayMs, float pan) {
        if (data == null || data.length == 0 || gain <= 0f) return;
        double startPos = -(delayMs / 1000.0) * RATE * pitch;
        float p = Math.max(-1f, Math.min(1f, pan));
        synchronized (voices) {
            if (voices.size() >= MAX_VOICES) voices.remove(0);
            voices.add(new Voice(data, gain, p, pitch, startPos));
        }
    }

    private static SourceDataLine open(int channels) {
        AudioFormat fmt = new AudioFormat(RATE, 16, channels, true, false);
        try {
            SourceDataLine line = (SourceDataLine) Devices.get(ClickConfig.outputDevice,
                new DataLine.Info(SourceDataLine.class, fmt));
            try {
                line.open(fmt, LINE_BUFFER * fmt.getFrameSize());
            } catch (Exception small) {
                line.open(fmt); // driver didn't like a tiny buffer, use its default
            }
            line.start();
            return line;
        } catch (Exception e) {
            return null;
        }
    }

    private static void run() {
        float[] mixL = new float[CHUNK], mixR = new float[CHUNK];
        boolean warned = false;
        while (true) {
            reopen = false;
            SourceDataLine line = open(2); // stereo, for desk positioning
            int channels = 2;
            if (line == null) { line = open(1); channels = 1; }
            if (line == null) {
                if (!warned) ClickSoundsClient.LOG.warn("No audio output device available, will keep retrying quietly");
                warned = true;
                sleep(5000);
                continue;
            }
            warned = false;
            ClickSoundsClient.LOG.info("Audio output open on '{}' ({} ch, {} frame buffer)",
                ClickConfig.outputDevice.isEmpty() ? "default" : ClickConfig.outputDevice, channels,
                line.getBufferSize() / line.getFormat().getFrameSize());
            byte[] out = new byte[CHUNK * channels * 2];
            try {
                while (!reopen) {
                    Arrays.fill(mixL, 0f);
                    Arrays.fill(mixR, 0f);
                    synchronized (voices) {
                        for (int v = voices.size() - 1; v >= 0; v--) {
                            Voice voice = voices.get(v);
                            float[] d = voice.data;
                            for (int i = 0; i < CHUNK; i++) {
                                if (voice.pos < 0) { voice.pos += voice.step; continue; }
                                int idx = (int) voice.pos;
                                if (idx >= d.length - 1) { voice.pos = d.length; break; }
                                float frac = (float) (voice.pos - idx);
                                float smp = d[idx] + (d[idx + 1] - d[idx]) * frac;
                                mixL[i] += smp * voice.gainL;
                                mixR[i] += smp * voice.gainR;
                                voice.pos += voice.step;
                            }
                            if (voice.pos >= d.length - 1) voices.remove(v);
                        }
                    }
                    int o = 0;
                    for (int i = 0; i < CHUNK; i++) {
                        if (channels == 2) {
                            o = put(out, o, mixL[i]);
                            o = put(out, o, mixR[i]);
                        } else {
                            o = put(out, o, (mixL[i] + mixR[i]) * 0.5f);
                        }
                    }
                    line.write(out, 0, out.length); // blocks, which paces this loop
                }
                line.drain();
                line.close();
            } catch (Exception e) {
                ClickSoundsClient.LOG.warn("Audio output failed, reopening", e);
                try { line.close(); } catch (Exception ignored) {}
                sleep(1000);
            }
        }
    }

    private static int put(byte[] out, int o, float s) {
        // soft clip so lots of overlapping clicks don't crackle
        if (s > 1f || s < -1f) s = (float) Math.tanh(s);
        short val = (short) Math.round(s * 32767f);
        out[o] = (byte) val;
        out[o + 1] = (byte) (val >> 8);
        return o + 2;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
