package dev.clicksounds.audio;

import dev.clicksounds.ClickConfig;
import dev.clicksounds.ClickSoundsClient;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The sounds of one device (mouse or keyboard) from the active clickpack: for each genre, the
 * clicks (paired with the release of the same file name, if there is one) and all its releases.
 */
public final class SampleBank {
    public static final int TAKES = 10;

    public final String device;
    private volatile String pack = Packs.DEFAULT;

    /** Immutable snapshot, swapped whole when a pack is (re)loaded. */
    private record Data(EnumMap<Genre, List<Take>> takes, EnumMap<Genre, List<float[]>> releases, float normGain) {}
    private volatile Data data = new Data(emptyTakes(), emptyReleases(), 1f);

    private record Held(Genre genre, int take, float gain, float pitch) {}
    private final Map<Integer, Held> held = new HashMap<>();
    private final EnumMap<Genre, Integer> last = new EnumMap<>(Genre.class);
    private long lastPressNanos;
    private Genre lastGenre = Genre.NORMAL;

    private static final Map<Take, Long> previewStarts = new IdentityHashMap<>();

    public SampleBank(String device) {
        this.device = device;
    }

    private static EnumMap<Genre, List<Take>> emptyTakes() {
        EnumMap<Genre, List<Take>> m = new EnumMap<>(Genre.class);
        for (Genre g : Genre.values()) m.put(g, List.of());
        return m;
    }

    private static EnumMap<Genre, List<float[]>> emptyReleases() {
        EnumMap<Genre, List<float[]>> m = new EnumMap<>(Genre.class);
        for (Genre g : Genre.values()) m.put(g, List.of());
        return m;
    }

    // ---- info ----

    public String pack() { return pack; }
    public List<Take> takes(Genre g) { return data.takes().get(g); }
    public int size(Genre g) { return takes(g).size(); }
    public int releaseCount(Genre g) { return data.releases().get(g).size(); }
    public boolean isEmpty() { for (Genre g : Genre.values()) if (size(g) > 0) return false; return true; }
    public Genre lastGenre() { return lastGenre; }

    /** Loudest point across every genre, for scaling waveforms consistently. */
    public float peak() {
        float max = 0f;
        for (List<Take> l : data.takes().values()) for (Take t : l) max = Math.max(max, t.peak());
        for (List<float[]> l : data.releases().values()) for (float[] r : l) max = Math.max(max, Wav.peak(r));
        return max;
    }

    /** e.g. "10 clicks, 10 soft, 4 hard" */
    public String summary() {
        int n = size(Genre.NORMAL), s = size(Genre.SOFT), h = size(Genre.HARD);
        if (n + s + h == 0) return "empty";
        StringBuilder b = new StringBuilder(n + " click" + (n == 1 ? "" : "s"));
        if (s > 0) b.append(", ").append(s).append(" soft");
        if (h > 0) b.append(", ").append(h).append(" hard");
        return b.toString();
    }

    // ---- files ----

    public Path dir() {
        return Packs.deviceDir(pack, device);
    }

    public void load(String packName) {
        pack = Packs.resolve(packName);
        reload();
    }

    public void reload() {
        Path base = dir();
        EnumMap<Genre, List<Take>> takes = emptyTakes();
        EnumMap<Genre, List<float[]>> releases = emptyReleases();
        int files = 0;
        for (Genre g : Genre.values()) {
            TreeMap<String, Path> clickFiles = audioFiles(base.resolve(g.clicksFolder));
            TreeMap<String, Path> releaseFiles = audioFiles(base.resolve(g.releasesFolder));
            Map<String, float[]> rel = new HashMap<>();
            List<float[]> relList = new ArrayList<>();
            for (Map.Entry<String, Path> e : releaseFiles.entrySet()) {
                float[] r = read(e.getValue());
                if (r == null) continue;
                rel.put(e.getKey(), r);
                relList.add(r);
                files++;
            }
            List<Take> list = new ArrayList<>();
            for (Map.Entry<String, Path> e : clickFiles.entrySet()) {
                float[] p = read(e.getValue());
                if (p == null) continue;
                list.add(new Take(Packs.baseName(e.getValue()), p, rel.get(e.getKey())));
                files++;
            }
            takes.put(g, Collections.unmodifiableList(list));
            releases.put(g, Collections.unmodifiableList(relList));
        }
        float max = 0f;
        for (List<Take> l : takes.values()) for (Take t : l) max = Math.max(max, t.peak());
        for (List<float[]> l : releases.values()) for (float[] r : l) max = Math.max(max, Wav.peak(r));
        // one gain for the whole pack: soft clicks stay softer than hard ones
        data = new Data(takes, releases, max > 0f ? Math.min(12f, 0.6f / max) : 1f);
        held.clear();
        last.clear();
        ClickSoundsClient.LOG.info("Loaded {} pack '{}': {} ({} files)", device, pack, summary(), files);
    }

    /** Audio files in a folder, keyed by lower-case name without extension, sorted naturally (2 before 10). */
    private static TreeMap<String, Path> audioFiles(Path dir) {
        TreeMap<String, Path> out = new TreeMap<>(SampleBank::natural);
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, p -> Files.isRegularFile(p) && Packs.isAudio(p))) {
            for (Path p : ds) out.putIfAbsent(Packs.baseName(p).toLowerCase(Locale.ROOT), p);
        } catch (IOException e) {
            ClickSoundsClient.LOG.warn("Could not list {}", dir, e);
        }
        return out;
    }

    private static int natural(String a, String b) {
        String na = a.replaceAll("\\D", ""), nb = b.replaceAll("\\D", "");
        if (!na.isEmpty() && !nb.isEmpty() && na.length() < 10 && nb.length() < 10) {
            int c = Long.compare(Long.parseLong(na), Long.parseLong(nb));
            if (c != 0) return c;
        }
        return a.compareTo(b);
    }

    private static float[] read(Path p) {
        try {
            float[] s = Wav.read(p);
            return s.length > 0 ? s : null;
        } catch (Exception e) {
            ClickSoundsClient.LOG.warn("Skipping {} (not a readable .wav/.ogg)", p, e);
            return null;
        }
    }

    /** Replaces every take of one genre with a fresh recording (files 01.wav ... 10.wav). */
    public void replaceAll(Genre g, List<Take> fresh) throws IOException {
        Path base = dir();
        deleteAudio(base.resolve(g.clicksFolder), null);
        deleteAudio(base.resolve(g.releasesFolder), null);
        for (int i = 0; i < fresh.size(); i++) write(base, g, String.format(Locale.ROOT, "%02d", i + 1), fresh.get(i));
        reload();
    }

    /** Replaces one take (keeping its file name), or adds it if {@code index} is past the end. */
    public void replaceTake(Genre g, int index, Take take) throws IOException {
        Path base = dir();
        List<Take> list = takes(g);
        String name;
        if (index >= 0 && index < list.size()) {
            name = list.get(index).name();
            deleteAudio(base.resolve(g.clicksFolder), name);
            deleteAudio(base.resolve(g.releasesFolder), name);
        } else {
            int n = list.size() + 1;
            while (Files.exists(base.resolve(g.clicksFolder).resolve(String.format(Locale.ROOT, "%02d.wav", n)))) n++;
            name = String.format(Locale.ROOT, "%02d", n);
        }
        write(base, g, name, take);
        reload();
    }

    public void clear(Genre g) {
        try {
            deleteAudio(dir().resolve(g.clicksFolder), null);
            deleteAudio(dir().resolve(g.releasesFolder), null);
        } catch (IOException e) {
            ClickSoundsClient.LOG.warn("Could not delete {} {}", pack, g, e);
        }
        reload();
    }

    private static void write(Path base, Genre g, String name, Take t) throws IOException {
        Files.createDirectories(base.resolve(g.clicksFolder));
        Wav.write(base.resolve(g.clicksFolder).resolve(name + ".wav"), t.press(), AudioEngine.RATE);
        if (t.release() != null) {
            Files.createDirectories(base.resolve(g.releasesFolder));
            Wav.write(base.resolve(g.releasesFolder).resolve(name + ".wav"), t.release(), AudioEngine.RATE);
        }
    }

    /** Deletes audio files in a folder; only those called {@code name} (any extension) if given. */
    private static void deleteAudio(Path dir, String name) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, p -> Files.isRegularFile(p) && Packs.isAudio(p))) {
            for (Path p : ds) if (name == null || Packs.baseName(p).equalsIgnoreCase(name)) Files.deleteIfExists(p);
        }
    }

    // ---- playback ----

    /** Which genre a press right now should use, from the time since the previous press. */
    private Genre pickGenre(long now) {
        double gapMs = lastPressNanos == 0 ? Double.MAX_VALUE : (now - lastPressNanos) / 1e6;
        Genre g = Genre.NORMAL;
        if (ClickConfig.softClicks && gapMs < ClickConfig.softMs && size(Genre.SOFT) > 0) g = Genre.SOFT;
        else if (ClickConfig.hardClicks && gapMs > ClickConfig.hardMs && size(Genre.HARD) > 0) g = Genre.HARD;
        return playable(g);
    }

    /** The genre itself if it has clicks, otherwise normal, otherwise whatever has clicks. */
    private Genre playable(Genre g) {
        if (size(g) > 0) return g;
        if (size(Genre.NORMAL) > 0) return Genre.NORMAL;
        for (Genre o : Genre.values()) if (size(o) > 0) return o;
        return g;
    }

    private int pickIndex(Genre g) {
        int n = size(g);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int i = r.nextInt(n);
        Integer prev = last.get(g);
        if (n > 1 && prev != null && i == prev) i = (i + 1 + r.nextInt(n - 1)) % n; // never the same twice in a row
        last.put(g, i);
        return i;
    }

    /** A button / key went down: play a click of the right genre. */
    public void press(int id, float volume, boolean variation) {
        long now = System.nanoTime();
        Genre g = pickGenre(now);
        lastPressNanos = now;
        if (size(g) == 0 || volume <= 0f) return;
        lastGenre = g;
        int i = pickIndex(g);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        float gain = volume * data.normGain(), pitch = 1f;
        if (variation) {
            gain *= 0.85f + r.nextFloat() * 0.15f;
            pitch = 0.96f + r.nextFloat() * 0.08f;
        }
        held.put(id, new Held(g, i, gain, pitch));
        AudioEngine.play(takes(g).get(i).press(), gain, pitch);
    }

    /** The same button / key came back up: its paired release, or a random one of that genre. */
    public void release(int id, boolean playSound) {
        Held h = held.remove(id);
        if (!playSound || h == null) return;
        float[] rel = releaseFor(h.genre(), h.take());
        if (rel != null) AudioEngine.play(rel, h.gain(), h.pitch());
    }

    private float[] releaseFor(Genre g, int takeIndex) {
        List<Take> list = takes(g);
        if (takeIndex < list.size() && list.get(takeIndex).release() != null) return list.get(takeIndex).release();
        List<float[]> pool = data.releases().get(g);
        if (pool.isEmpty()) pool = data.releases().get(Genre.NORMAL);
        return pool.isEmpty() ? null : pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    // ---- previews ----

    /** Preview one take, starting {@code delayMs} from now. Remembers when, so waveforms can draw a playhead. */
    public void preview(Genre g, int index, float volume, int delayMs) {
        List<Take> list = takes(g);
        if (index < 0 || index >= list.size()) return;
        Take t = list.get(index);
        float gain = volume * data.normGain();
        AudioEngine.play(t.press(), gain, 1f, delayMs);
        AudioEngine.play(t.release(), gain, 1f, delayMs + Math.round(t.releaseOffset() * 1000f / AudioEngine.RATE));
        previewStarts.put(t, System.nanoTime() + delayMs * 1_000_000L);
    }

    /** Plays every take of a genre one after another. @return total length in ms */
    public int previewAll(Genre g, float volume) {
        List<Take> list = takes(g);
        int at = 0;
        for (int i = 0; i < list.size(); i++) {
            preview(g, i, volume, at);
            at += Math.round(list.get(i).lengthMs()) + 250;
        }
        return at;
    }

    /** Preview a random normal click the way it sounds in game (variation, release after a natural gap). */
    public void previewRandom(float volume, boolean variation, boolean withRelease) {
        Genre g = playable(Genre.NORMAL);
        if (size(g) == 0 || volume <= 0f) return;
        int i = pickIndex(g);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        float gain = volume * data.normGain(), pitch = 1f;
        if (variation) {
            gain *= 0.85f + r.nextFloat() * 0.15f;
            pitch = 0.96f + r.nextFloat() * 0.08f;
        }
        Take t = takes(g).get(i);
        AudioEngine.play(t.press(), gain, pitch);
        if (withRelease) {
            int ms = Math.round(t.releaseOffset() * 1000f / AudioEngine.RATE);
            AudioEngine.play(releaseFor(g, i), gain, pitch, ms);
        }
        previewStarts.put(t, System.nanoTime());
    }

    /** How far into its preview a take is, in frames; -1 if it isn't playing. */
    public static long previewPosition(Take t) {
        Long start = previewStarts.get(t);
        if (start == null) return -1;
        long frames = (long) ((System.nanoTime() - start) / 1e9 * AudioEngine.RATE);
        if (frames >= t.timelineLength()) { previewStarts.remove(t); return -1; }
        return frames < 0 ? -1 : frames;
    }
}
