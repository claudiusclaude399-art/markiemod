package dev.clicksounds.audio;

import dev.clicksounds.ClickSoundsClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One set of recorded takes (mouse or keyboard), stored as WAVs in
 * config/clicksounds/&lt;name&gt;/. Any .wav you drop in that folder is used too.
 */
public final class SampleBank {
    public static final int TAKES = 10;

    public final String name;
    private volatile List<float[]> samples = List.of();
    /** Brings the loudest take up to a sensible level; keeps the takes' natural loudness differences. */
    private volatile float normGain = 1f;
    private int last = -1;

    public SampleBank(String name) {
        this.name = name;
    }

    public Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("clicksounds").resolve(name);
    }

    public int size() {
        return samples.size();
    }

    public void load() {
        List<float[]> loaded = new ArrayList<>();
        Path d = dir();
        if (Files.isDirectory(d)) {
            List<Path> files = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(d)) {
                for (Path p : ds) {
                    if (p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".wav")) files.add(p);
                }
            } catch (IOException e) {
                ClickSoundsClient.LOG.warn("Could not list {}", d, e);
            }
            files.sort(null);
            for (Path p : files) {
                try {
                    float[] s = Wav.read(p);
                    if (s.length > 0) loaded.add(s);
                } catch (Exception e) {
                    ClickSoundsClient.LOG.warn("Skipping {} (not a readable PCM WAV)", p.getFileName(), e);
                }
            }
        }
        set(loaded);
        ClickSoundsClient.LOG.info("Loaded {} {} sound(s)", loaded.size(), name);
    }

    /** Replaces every saved take with a fresh recording. */
    public void replace(List<float[]> takes) throws IOException {
        deleteFiles();
        Path d = dir();
        Files.createDirectories(d);
        for (int i = 0; i < takes.size(); i++) {
            Wav.write(d.resolve(String.format(Locale.ROOT, "%s_%02d.wav", name, i + 1)), takes.get(i), AudioEngine.RATE);
        }
        set(new ArrayList<>(takes));
    }

    public void clear() {
        try { deleteFiles(); } catch (IOException e) { ClickSoundsClient.LOG.warn("Could not delete {}", dir(), e); }
        set(List.of());
    }

    private void deleteFiles() throws IOException {
        Path d = dir();
        if (!Files.isDirectory(d)) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(d, "*.{wav,WAV}")) {
            for (Path p : ds) Files.deleteIfExists(p);
        }
    }

    private void set(List<float[]> list) {
        float max = 0f;
        for (float[] s : list) max = Math.max(max, Wav.peak(s));
        normGain = max > 0f ? Math.min(12f, 0.6f / max) : 1f;
        samples = List.copyOf(list);
        last = -1;
    }

    /** Plays a random take, never the same one twice in a row. */
    public void playRandom(float volume, boolean variation) {
        List<float[]> s = samples;
        if (s.isEmpty() || volume <= 0f) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int i = r.nextInt(s.size());
        if (s.size() > 1 && i == last) i = (i + 1 + r.nextInt(s.size() - 1)) % s.size();
        last = i;
        float gain = volume * normGain;
        float pitch = 1f;
        if (variation) {
            gain *= 0.85f + r.nextFloat() * 0.15f;
            pitch = 0.96f + r.nextFloat() * 0.08f;
        }
        AudioEngine.play(s.get(i), gain, pitch);
    }
}
