package dev.clicksounds.audio;

import dev.clicksounds.ClickSoundsClient;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.util.ArrayList;
import java.util.List;

/**
 * Low-latency software mixer. Keeps one output line open on the system's default
 * sound device and mixes any number of overlapping click sounds into it, so fast
 * clicking / typing never cuts a sound off. Plays outside Minecraft's sound engine,
 * so OBS / Discord desktop audio picks it up like any other app.
 */
public final class AudioEngine {
    public static final float RATE = 44100f;
    private static final int CHUNK = 256;          // frames mixed per write (~6 ms)
    private static final int LINE_BUFFER = 1024;   // frames buffered in the device (~23 ms)
    private static final int MAX_VOICES = 32;

    private static final List<Voice> voices = new ArrayList<>();
    private static Thread thread;

    private AudioEngine() {}

    private static final class Voice {
        final float[] data;
        final float gain;
        final double step;
        double pos;

        Voice(float[] data, float gain, double step) {
            this.data = data;
            this.gain = gain;
            this.step = step;
        }
    }

    public static synchronized void start() {
        if (thread != null) return;
        thread = new Thread(AudioEngine::run, "ClickSounds-Audio");
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    /** @param pitch 1.0 = original speed */
    public static void play(float[] data, float gain, float pitch) {
        if (data == null || data.length == 0 || gain <= 0f) return;
        synchronized (voices) {
            if (voices.size() >= MAX_VOICES) voices.remove(0);
            voices.add(new Voice(data, gain, pitch));
        }
    }

    private static SourceDataLine open(int channels) {
        AudioFormat fmt = new AudioFormat(RATE, 16, channels, true, false);
        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
            if (!AudioSystem.isLineSupported(info)) return null;
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
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
        float[] mix = new float[CHUNK];
        boolean warned = false;
        while (true) {
            SourceDataLine line = open(1);
            int channels = 1;
            if (line == null) { line = open(2); channels = 2; }
            if (line == null) {
                if (!warned) ClickSoundsClient.LOG.warn("No audio output device available, will keep retrying quietly");
                warned = true;
                sleep(5000);
                continue;
            }
            warned = false;
            ClickSoundsClient.LOG.info("Audio output open ({} ch, {} frame buffer)", channels,
                line.getBufferSize() / line.getFormat().getFrameSize());
            byte[] out = new byte[CHUNK * channels * 2];
            try {
                while (true) {
                    java.util.Arrays.fill(mix, 0f);
                    synchronized (voices) {
                        for (int v = voices.size() - 1; v >= 0; v--) {
                            Voice voice = voices.get(v);
                            float[] d = voice.data;
                            for (int i = 0; i < CHUNK; i++) {
                                int idx = (int) voice.pos;
                                if (idx >= d.length - 1) { voice.pos = d.length; break; }
                                float frac = (float) (voice.pos - idx);
                                mix[i] += (d[idx] + (d[idx + 1] - d[idx]) * frac) * voice.gain;
                                voice.pos += voice.step;
                            }
                            if (voice.pos >= d.length - 1) voices.remove(v);
                        }
                    }
                    int o = 0;
                    for (int i = 0; i < CHUNK; i++) {
                        float s = mix[i];
                        // soft clip so lots of overlapping clicks don't crackle
                        if (s > 1f || s < -1f) s = (float) Math.tanh(s);
                        short v = (short) Math.round(s * 32767f);
                        for (int c = 0; c < channels; c++) {
                            out[o++] = (byte) v;
                            out[o++] = (byte) (v >> 8);
                        }
                    }
                    line.write(out, 0, out.length); // blocks, which paces this loop
                }
            } catch (Exception e) {
                ClickSoundsClient.LOG.warn("Audio output failed, reopening", e);
                try { line.close(); } catch (Exception ignored) {}
                sleep(1000);
            }
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
