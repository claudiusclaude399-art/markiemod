package dev.markie.lyrics.music;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayList;
import java.util.List;

/**
 * Optional: listens to a recording device and finds the beat in real sound. To hear what the PC is playing,
 * pick a loopback device: "Stereo Mix" on many Windows PCs, or e.g. BlackHole / VB-Cable.
 * Bass energy (a simple low-pass filter) is compared with its recent average; a jump marks a beat.
 */
public final class AudioListener {
    public record Reading(float level, long beats) {}

    private static volatile Thread thread;
    private static volatile String deviceName;
    private static volatile boolean stop;
    private static volatile float level;
    private static volatile long beats;
    private static volatile String status = "Off";

    private AudioListener() {}

    public static boolean running() { return thread != null && thread.isAlive(); }
    public static String status() { return status; }
    public static Reading read() { return new Reading(level, beats); }

    /** Names of devices that can record. */
    public static List<String> devices() {
        List<String> out = new ArrayList<>();
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format());
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                try {
                    if (AudioSystem.getMixer(mi).isLineSupported(info)) out.add(mi.getName());
                } catch (Exception ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static synchronized void ensure(String name) {
        if (running() && name.equals(deviceName)) return;
        stop();
        deviceName = name;
        stop = false;
        Thread t = new Thread(() -> listen(name), "MarkieLyrics-Audio");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    public static synchronized void stop() {
        stop = true;
        thread = null;
        level = 0;
    }

    private static AudioFormat format() { return new AudioFormat(44100f, 16, 1, true, false); }

    private static void listen(String name) {
        TargetDataLine line = null;
        try {
            AudioFormat fmt = format();
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
            Mixer.Info chosen = null;
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                if (!name.isEmpty() && mi.getName().equals(name) && AudioSystem.getMixer(mi).isLineSupported(info)) chosen = mi;
            }
            line = chosen != null ? (TargetDataLine) AudioSystem.getMixer(chosen).getLine(info) : (TargetDataLine) AudioSystem.getLine(info);
            line.open(fmt, 4096);
            line.start();
            status = "Listening to " + (chosen != null ? chosen.getName() : "the default input");
            byte[] buf = new byte[1024];   // 512 samples ~ 11.6 ms
            double lp = 0, avg = 0, peak = 0.02;
            long lastBeat = 0;
            while (!stop) {
                int n = line.read(buf, 0, buf.length);
                if (n <= 0) continue;
                double sum = 0, bassSum = 0;
                for (int i = 0; i + 1 < n; i += 2) {
                    double v = ((buf[i + 1] << 8) | (buf[i] & 0xFF)) / 32768.0;
                    lp += (v - lp) * 0.02;              // ~140 Hz low-pass: the kick drum and bass
                    sum += v * v;
                    bassSum += lp * lp;
                }
                int samples = n / 2;
                double rms = Math.sqrt(sum / samples), bass = Math.sqrt(bassSum / samples);
                peak = Math.max(peak * 0.9995, rms);    // auto gain
                level = (float) Math.min(1.0, rms / peak);
                avg += (bass - avg) * 0.05;
                long now = System.currentTimeMillis();
                if (bass > avg * 1.5 && bass > peak * 0.08 && now - lastBeat > 240) {
                    beats++;
                    lastBeat = now;
                }
            }
        } catch (Throwable e) {
            status = "Can't open the audio device: " + e.getMessage();
        } finally {
            if (line != null) { line.stop(); line.close(); }
            if (!stop) status = "Audio device closed";
        }
    }
}
