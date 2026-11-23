package dev.clicksounds.audio;

import dev.clicksounds.ClickSoundsClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One set of recorded takes (mouse or keyboard), stored as WAVs in config/clicksounds/&lt;name&gt;/.
 * {@code xxx.wav} is a press sound and {@code xxx_up.wav} (optional) its release sound.
 * Any .wav you drop in the folder is used too.
 */
public final class SampleBank {
    public static final int TAKES = 10;
    private static final String UP = "_up";

    public final String name;
    private volatile List<Take> takes = List.of();
    /** Brings the loudest take up to a sensible level; keeps the takes' natural loudness differences. */
    private volatile float normGain = 1f;
    private int last = -1;

    /** Which take each held button / key started with, so its release sound matches. */
    private record Held(int take, float gain, float pitch) {}
    private final Map<Integer, Held> held = new HashMap<>();

    public SampleBank(String name) {
        this.name = name;
    }

    public Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("clicksounds").resolve(name);
    }

    public int size() {
        return takes.size();
    }

    public List<Take> takes() {
        return takes;
    }

    public boolean hasReleases() {
        for (Take t : takes) if (t.release() != null) return true;
        return false;
    }

    // ---- files ----

    public void load() {
        TreeMap<String, Path> press = new TreeMap<>();
        Map<String, Path> release = new HashMap<>();
        Path d = dir();
        if (Files.isDirectory(d)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(d)) {
                for (Path p : ds) {
                    String f = p.getFileName().toString();
                    if (!f.toLowerCase(Locale.ROOT).endsWith(".wav")) continue;
                    String base = f.substring(0, f.length() - 4);
                    if (base.endsWith(UP)) release.put(base.substring(0, base.length() - UP.length()), p);
                    else press.put(base, p);
                }
            } catch (IOException e) {
                ClickSoundsClient.LOG.warn("Could not list {}", d, e);
            }
        }
        List<Take> loaded = new ArrayList<>();
        for (Map.Entry<String, Path> e : press.entrySet()) {
            float[] p = read(e.getValue());
            if (p == null) continue;
            Path r = release.get(e.getKey());
            loaded.add(new Take(p, r == null ? null : read(r)));
        }
        set(loaded);
        ClickSoundsClient.LOG.info("Loaded {} {} take(s)", loaded.size(), name);
    }

    private static float[] read(Path p) {
        try {
            float[] s = Wav.read(p);
            return s.length > 0 ? s : null;
        } catch (Exception e) {
            ClickSoundsClient.LOG.warn("Skipping {} (not a readable PCM WAV)", p.getFileName(), e);
            return null;
        }
    }

    /** Replaces every saved take with a fresh recording. */
    public void replaceAll(List<Take> fresh) throws IOException {
        deleteFiles();
        set(new ArrayList<>(fresh));
        saveAll();
    }

    /** Replaces one take (or adds it if {@code index} is past the end). */
    public void replaceTake(int index, Take take) throws IOException {
        List<Take> list = new ArrayList<>(takes);
        if (index >= 0 && index < list.size()) list.set(index, take);
        else list.add(take);
        deleteFiles();
        set(list);
        saveAll();
    }

    public void clear() {
        try { deleteFiles(); } catch (IOException e) { ClickSoundsClient.LOG.warn("Could not delete {}", dir(), e); }
        set(List.of());
    }

    private void saveAll() throws IOException {
        Path d = dir();
        Files.createDirectories(d);
        List<Take> list = takes;
        for (int i = 0; i < list.size(); i++) {
            String base = String.format(Locale.ROOT, "%s_%02d", name, i + 1);
            Wav.write(d.resolve(base + ".wav"), list.get(i).press(), AudioEngine.RATE);
            if (list.get(i).release() != null) Wav.write(d.resolve(base + UP + ".wav"), list.get(i).release(), AudioEngine.RATE);
        }
    }

    private void deleteFiles() throws IOException {
        Path d = dir();
        if (!Files.isDirectory(d)) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(d, "*.{wav,WAV}")) {
            for (Path p : ds) Files.deleteIfExists(p);
        }
    }

    private void set(List<Take> list) {
        float max = 0f;
        for (Take t : list) max = Math.max(max, t.peak());
        normGain = max > 0f ? Math.min(12f, 0.6f / max) : 1f;
        takes = List.copyOf(list);
        last = -1;
        held.clear();
    }

    // ---- playback ----

    /** A button / key went down: play a random take's press sound (never the same take twice in a row). */
    public void press(int id, float volume, boolean variation) {
        List<Take> list = takes;
        if (list.isEmpty() || volume <= 0f) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int i = r.nextInt(list.size());
        if (list.size() > 1 && i == last) i = (i + 1 + r.nextInt(list.size() - 1)) % list.size();
        last = i;
        float gain = volume * normGain, pitch = 1f;
        if (variation) {
            gain *= 0.85f + r.nextFloat() * 0.15f;
            pitch = 0.96f + r.nextFloat() * 0.08f;
        }
        held.put(id, new Held(i, gain, pitch));
        AudioEngine.play(list.get(i).press(), gain, pitch);
    }

    /** The same button / key came back up: play the release sound of the take its press used. */
    public void release(int id, boolean playSound) {
        Held h = held.remove(id);
        List<Take> list = takes;
        if (!playSound || h == null || h.take() >= list.size()) return;
        AudioEngine.play(list.get(h.take()).release(), h.gain(), h.pitch());
    }

    /** Preview one take: press, then its release a moment later. */
    public void preview(int index, float volume) {
        List<Take> list = takes;
        if (index < 0 || index >= list.size()) return;
        Take t = list.get(index);
        AudioEngine.play(t.press(), volume * normGain, 1f);
        AudioEngine.play(t.release(), volume * normGain, 1f, 110);
    }

    /** Preview a random take. */
    public void previewRandom(float volume) {
        List<Take> list = takes;
        if (!list.isEmpty()) preview(ThreadLocalRandom.current().nextInt(list.size()), volume);
    }
}
