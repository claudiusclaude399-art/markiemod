package dev.clicksounds.audio;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;

/** Mono float <-> 16-bit WAV, plus resampling helpers. */
public final class Wav {
    private Wav() {}

    public static void write(Path file, float[] data, float rate) throws IOException {
        byte[] bytes = new byte[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            short v = (short) Math.round(Math.max(-1f, Math.min(1f, data[i])) * 32767f);
            bytes[i * 2] = (byte) v;
            bytes[i * 2 + 1] = (byte) (v >> 8);
        }
        AudioFormat fmt = new AudioFormat(rate, 16, 1, true, false);
        try (AudioInputStream in = new AudioInputStream(new ByteArrayInputStream(bytes), fmt, data.length)) {
            AudioSystem.write(in, AudioFileFormat.Type.WAVE, file.toFile());
        }
    }

    /** Reads any PCM WAV (mono/stereo, any rate) as mono float at {@link AudioEngine#RATE}. */
    public static float[] read(Path file) throws Exception {
        try (AudioInputStream raw = AudioSystem.getAudioInputStream(file.toFile())) {
            AudioFormat src = raw.getFormat();
            int ch = src.getChannels();
            AudioFormat pcm = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, src.getSampleRate(), 16, ch,
                ch * 2, src.getSampleRate(), false);
            try (AudioInputStream in = AudioSystem.getAudioInputStream(pcm, raw)) {
                int maxFrames = (int) (src.getSampleRate() * 2); // nobody needs a 2s+ click
                byte[] bytes = in.readNBytes(maxFrames * ch * 2);
                float[] mono = toMono(bytes, bytes.length / (ch * 2), ch);
                return resample(mono, src.getSampleRate(), AudioEngine.RATE);
            }
        }
    }

    /** 16-bit little-endian interleaved -> mono float. */
    public static float[] toMono(byte[] bytes, int frames, int channels) {
        float[] out = new float[frames];
        int p = 0;
        for (int f = 0; f < frames; f++) {
            float sum = 0f;
            for (int c = 0; c < channels; c++) {
                short s = (short) ((bytes[p] & 0xFF) | (bytes[p + 1] << 8));
                sum += s / 32768f;
                p += 2;
            }
            out[f] = sum / channels;
        }
        return out;
    }

    public static float[] resample(float[] in, float fromRate, float toRate) {
        if (Math.abs(fromRate - toRate) < 1f || in.length < 2) return in;
        int n = (int) ((long) in.length * toRate / fromRate);
        float[] out = new float[n];
        double step = fromRate / (double) toRate;
        for (int i = 0; i < n; i++) {
            double pos = i * step;
            int idx = (int) pos;
            if (idx >= in.length - 1) { out[i] = in[in.length - 1]; continue; }
            float frac = (float) (pos - idx);
            out[i] = in[idx] + (in[idx + 1] - in[idx]) * frac;
        }
        return out;
    }

    public static float peak(float[] d) {
        float p = 0f;
        for (float v : d) p = Math.max(p, Math.abs(v));
        return p;
    }
}
