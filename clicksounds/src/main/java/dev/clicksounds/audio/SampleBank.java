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

    /** Scope of the shared sounds (keyboard/clicks/ ...): every key without its own sounds uses these. */
    public static final String ALL = "";
    /** Virtual scope: every per-key take mixed, for keys without their own sounds when there are no shared ones. */
    public static final String MIXED = "*";
    /** Mouse only: scroll wheel notch sounds (mouse/scroll/...). Never used for clicks. */
    public static final String SCROLL = "scroll";

    public final String device;
    private volatile String pack = Packs.DEFAULT;

    /** One set of genre folders: the shared one, or one key's (keyboard/keys/w/...). */
    private record Scope(EnumMap<Genre, List<Take>> takes, EnumMap<Genre, List<float[]>> releases) {
        int size(Genre g) { return takes.get(g).size(); }
        boolean hasClicks() { for (Genre g : Genre.values()) if (size(g) > 0) return true; return false; }
    }
    private static final Scope EMPTY = new Scope(emptyTakes(), emptyReleases());

    /** Immutable snapshot, swapped whole when a pack is (re)loaded. */
    private record Data(Map<String, Scope> scopes, float normGain) {}
    private volatile Data data = new Data(Map.of(), 1f);

    private record Held(String scope, Genre genre, int take, float gain, float pitch, float pan) {}
    private final Map<Integer, Held> held = new HashMap<>();
    private final Map<String, Integer> last = new HashMap<>();
    /** Time of the previous press, per family (clicks/keys vs. scroll), for soft / hard clicks. */
    private final Map<String, Long> lastPress = new HashMap<>();
    private Genre lastGenre = Genre.NORMAL;
    private String lastScope = ALL;

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

    private Scope scope(String name) {
        return data.scopes().getOrDefault(name == null ? ALL : name, EMPTY);
    }


    public String pack() { return pack; }
    public List<Take> takes(Genre g) { return takes(ALL, g); }
    public List<Take> takes(String scope, Genre g) { return scope(scope).takes().get(g); }
    public int size(Genre g) { return size(ALL, g); }
    public int size(String scope, Genre g) { return takes(scope, g).size(); }
    public int releaseCount(Genre g) { return releaseCount(ALL, g); }
    public int releaseCount(String scope, Genre g) { return scope(scope).releases().get(g).size(); }
    public boolean isEmpty() { for (Scope sc : data.scopes().values()) if (sc.hasClicks()) return false; return true; }
    public Genre lastGenre() { return lastGenre; }
    public String lastScope() { return lastScope; }

    /** Keys that have their own sounds in this pack (folder names, e.g. "w", "space"). */
    public List<String> ownKeys() {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, Scope> e : data.scopes().entrySet())
            if (!e.getKey().equals(ALL) && !e.getKey().equals(MIXED) && !e.getKey().equals(SCROLL) && e.getValue().hasClicks()) keys.add(e.getKey());
        List<String> order = List.of("w", "a", "s", "d", "left", "right", "middle", "back", "forward");
        keys.sort((a, b) -> { // W A S D / mouse buttons in their natural order first, then alphabetically
            int ia = order.indexOf(a), ib = order.indexOf(b);
            if (ia >= 0 || ib >= 0) return ia < 0 ? 1 : ib < 0 ? -1 : Integer.compare(ia, ib);
            return a.compareTo(b);
        });
        return keys;
    }

    /** Loudest point across everything in the pack, for scaling waveforms consistently. */
    public float peak() {
        float max = 0f;
        for (Map.Entry<String, Scope> e : data.scopes().entrySet()) {
            if (e.getKey().equals(MIXED)) continue;
            for (List<Take> l : e.getValue().takes().values()) for (Take t : l) max = Math.max(max, t.peak());
            for (List<float[]> l : e.getValue().releases().values()) for (float[] r : l) max = Math.max(max, Wav.peak(r));
        }
        return max;
    }

    public boolean hasScroll() {
        return scope(SCROLL).hasClicks();
    }

    /** e.g. "10 clicks, 10 soft, 4 hard + own W A S D" / "10 clicks + own Left Right + scroll" */
    public String summary() {
        String base = summary(ALL);
        List<String> keys = ownKeys();
        StringBuilder b = new StringBuilder(base.equals("empty") && (!keys.isEmpty() || hasScroll()) ? "" : base);
        if (!keys.isEmpty()) {
            b.append(b.length() > 0 ? " + own" : "own");
            for (String k : keys) b.append(' ').append(keyLabel(k));
        }
        if (hasScroll()) b.append(b.length() > 0 ? " + scroll" : "scroll");
        return b.toString();
    }

    public String summary(String scope) {
        int n = size(scope, Genre.NORMAL), s = size(scope, Genre.SOFT), h = size(scope, Genre.HARD);
        if (n + s + h == 0) return "empty";
        StringBuilder b = new StringBuilder(n + " click" + (n == 1 ? "" : "s"));
        if (s > 0) b.append(", ").append(s).append(" soft");
        if (h > 0) b.append(", ").append(h).append(" hard");
        return b.toString();
    }

    /** "w" -> "W", "left.shift" -> "Left Shift" */
    public static String keyLabel(String key) {
        if (key == null || key.isEmpty()) return "All keys";
        if (key.length() == 1) return key.toUpperCase(Locale.ROOT);
        StringBuilder b = new StringBuilder();
        for (String part : key.split("\\.")) {
            if (part.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return b.toString();
    }


    public Path dir() {
        return Packs.deviceDir(pack, device);
    }

    public void load(String packName) {
        pack = Packs.resolve(packName);
        reload();
    }

    /** Subfolder holding per-key (keyboard) or per-button (mouse) sounds. */
    private String ownFolder() {
        return device.equals(Packs.MOUSE) ? "buttons" : "keys";
    }

    /** Folder holding a scope's genre folders. */
    public Path dir(String scope) {
        if (scope == null || scope.isEmpty()) return dir();
        if (scope.equals(SCROLL) && device.equals(Packs.MOUSE)) return dir().resolve(SCROLL);
        return dir().resolve(ownFolder()).resolve(scope);
    }

    /** Where a scope lives inside the device folder, for showing the user ("buttons/left/"). */
    public String folderOf(String scope) {
        if (scope == null || scope.isEmpty()) return "";
        if (scope.equals(SCROLL) && device.equals(Packs.MOUSE)) return SCROLL + "/";
        return ownFolder() + "/" + scope + "/";
    }

    public void reload() {
        Path base = dir();
        Map<String, Scope> scopes = new HashMap<>();
        int[] files = {0};
        scopes.put(ALL, readScope(base, files));
        if (device.equals(Packs.MOUSE)) scopes.put(SCROLL, readScope(base.resolve(SCROLL), files));
        Path keys = base.resolve(ownFolder());
        if (Files.isDirectory(keys)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(keys, Files::isDirectory)) {
                for (Path k : ds) scopes.put(k.getFileName().toString().toLowerCase(Locale.ROOT), readScope(k, files));
            } catch (IOException e) {
                ClickSoundsClient.LOG.warn("Could not list {}", keys, e);
            }
        }
        // every per-key take together, for the other keys when there are no shared sounds
        EnumMap<Genre, List<Take>> mixT = emptyTakes();
        EnumMap<Genre, List<float[]>> mixR = emptyReleases();
        for (Genre g : Genre.values()) {
            List<Take> t = new ArrayList<>();
            List<float[]> r = new ArrayList<>();
            for (Map.Entry<String, Scope> e : scopes.entrySet()) {
                if (e.getKey().equals(ALL) || e.getKey().equals(SCROLL)) continue;
                t.addAll(e.getValue().takes().get(g));
                r.addAll(e.getValue().releases().get(g));
            }
            mixT.put(g, Collections.unmodifiableList(t));
            mixR.put(g, Collections.unmodifiableList(r));
        }
        scopes.put(MIXED, new Scope(mixT, mixR));

        data = new Data(Map.copyOf(scopes), 1f);
        float max = peak();
        // one gain for the whole pack: soft clicks stay softer than hard ones
        data = new Data(data.scopes(), max > 0f ? Math.min(12f, 0.6f / max) : 1f);
        held.clear();
        last.clear();
        lastPress.clear();
        ClickSoundsClient.LOG.info("Loaded {} pack '{}': {} ({} files)", device, pack, summary(), files[0]);
    }

    private static Scope readScope(Path base, int[] files) {
        EnumMap<Genre, List<Take>> takes = emptyTakes();
        EnumMap<Genre, List<float[]>> releases = emptyReleases();
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
                files[0]++;
            }
            List<Take> list = new ArrayList<>();
            for (Map.Entry<String, Path> e : clickFiles.entrySet()) {
                float[] p = read(e.getValue());
                if (p == null) continue;
                list.add(new Take(Packs.baseName(e.getValue()), p, rel.get(e.getKey())));
                files[0]++;
            }
            takes.put(g, Collections.unmodifiableList(list));
            releases.put(g, Collections.unmodifiableList(relList));
        }
        return new Scope(takes, releases);
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

    /** Replaces every take of one genre (of the shared sounds, or of one key) with a fresh recording (01.wav ... 10.wav). */
    public void replaceAll(String scope, Genre g, List<Take> fresh) throws IOException {
        Path base = dir(scope);
        deleteAudio(base.resolve(g.clicksFolder), null);
        deleteAudio(base.resolve(g.releasesFolder), null);
        for (int i = 0; i < fresh.size(); i++) write(base, g, String.format(Locale.ROOT, "%02d", i + 1), fresh.get(i));
        reload();
    }

    /** Replaces one take (keeping its file name), or adds it if {@code index} is past the end. */
    public void replaceTake(String scope, Genre g, int index, Take take) throws IOException {
        Path base = dir(scope);
        List<Take> list = takes(scope, g);
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

    public void clear(String scope, Genre g) {
        try {
            deleteAudio(dir(scope).resolve(g.clicksFolder), null);
            deleteAudio(dir(scope).resolve(g.releasesFolder), null);
        } catch (IOException e) {
            ClickSoundsClient.LOG.warn("Could not delete {} {} {}", pack, scope, g, e);
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


    /** Which sounds a key uses: its own if it has any, else the shared ones, else all per-key ones mixed. */
    private String resolveScope(String key) {
        if (SCROLL.equals(key)) return SCROLL; // the wheel only ever uses scroll sounds
        if (key != null && !key.isEmpty() && scope(key).hasClicks()) return key;
        if (scope(ALL).hasClicks()) return ALL;
        if (scope(MIXED).hasClicks()) return MIXED;
        return ALL;
    }

    /** Which genre a press right now should use, from the time since the previous press. */
    private Genre pickGenre(String sc, long now) {
        Long prev = lastPress.get(family(sc));
        double gapMs = prev == null ? Double.MAX_VALUE : (now - prev) / 1e6;
        Genre g = Genre.NORMAL;
        if (ClickConfig.softClicks && gapMs < ClickConfig.softMs && size(sc, Genre.SOFT) > 0) g = Genre.SOFT;
        else if (ClickConfig.hardClicks && gapMs > ClickConfig.hardMs && size(sc, Genre.HARD) > 0) g = Genre.HARD;
        return playable(sc, g);
    }

    /** The genre itself if it has clicks, otherwise normal, otherwise whatever has clicks. */
    private Genre playable(String sc, Genre g) {
        if (size(sc, g) > 0) return g;
        if (size(sc, Genre.NORMAL) > 0) return Genre.NORMAL;
        for (Genre o : Genre.values()) if (size(sc, o) > 0) return o;
        return g;
    }

    private int pickIndex(String sc, Genre g) {
        int n = size(sc, g);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int i = r.nextInt(n);
        String k = sc + "/" + g;
        Integer prev = last.get(k);
        if (n > 1 && prev != null && i == prev) i = (i + 1 + r.nextInt(n - 1)) % n; // never the same twice in a row
        last.put(k, i);
        return i;
    }

    private static String family(String scope) {
        return SCROLL.equals(scope) ? SCROLL : "";
    }

    /** A mouse button went down. */
    public void press(int id, float volume, boolean variation) {
        press(id, null, volume, variation, 0f);
    }

    public void press(int id, String key, float volume, boolean variation) {
        press(id, key, volume, variation, 0f);
    }

    /**
     * A button / key went down: play a click of the right genre.
     * @param key key folder name ("w", "space", ...) so keys with their own sounds use them; null = shared
     */
    public void press(int id, String key, float volume, boolean variation, float pan) {
        long now = System.nanoTime();
        String sc = resolveScope(key);
        Genre g = pickGenre(sc, now);
        lastPress.put(family(sc), now);
        if (size(sc, g) == 0 || volume <= 0f) return;
        lastGenre = g;
        lastScope = sc;
        int i = pickIndex(sc, g);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        float gain = volume * data.normGain(), pitch = 1f;
        if (variation) {
            gain *= 0.85f + r.nextFloat() * 0.15f;
            pitch = 0.96f + r.nextFloat() * 0.08f;
        }
        held.put(id, new Held(sc, g, i, gain, pitch, pan));
        AudioEngine.play(takes(sc, g).get(i).press(), gain, pitch, 0, pan);
    }

    /** The same button / key came back up: its paired release, or a random one of that genre. */
    public void release(int id, boolean playSound) {
        Held h = held.remove(id);
        if (!playSound || h == null) return;
        float[] rel = releaseFor(h.scope(), h.genre(), h.take());
        if (rel != null) AudioEngine.play(rel, h.gain(), h.pitch(), 0, h.pan());
    }

    private float[] releaseFor(String sc, Genre g, int takeIndex) {
        List<Take> list = takes(sc, g);
        if (takeIndex < list.size() && list.get(takeIndex).release() != null) return list.get(takeIndex).release();
        List<List<float[]>> pools = SCROLL.equals(sc)
            ? List.of(scope(sc).releases().get(g), scope(sc).releases().get(Genre.NORMAL))
            : List.of(scope(sc).releases().get(g), scope(sc).releases().get(Genre.NORMAL),
                scope(ALL).releases().get(g), scope(ALL).releases().get(Genre.NORMAL));
        for (List<float[]> pool : pools) {
            if (!pool.isEmpty()) return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        }
        return null;
    }


    /** Preview one take, starting {@code delayMs} from now. Remembers when, so waveforms can draw a playhead. */
    public void preview(String sc, Genre g, int index, float volume, int delayMs) {
        List<Take> list = takes(sc, g);
        if (index < 0 || index >= list.size()) return;
        Take t = list.get(index);
        float gain = volume * data.normGain();
        AudioEngine.play(t.press(), gain, 1f, delayMs);
        AudioEngine.play(t.release(), gain, 1f, delayMs + Math.round(t.releaseOffset() * 1000f / AudioEngine.RATE));
        previewStarts.put(t, System.nanoTime() + delayMs * 1_000_000L);
    }

    /** Plays every take of a genre one after another. @return total length in ms */
    public int previewAll(String sc, Genre g, float volume) {
        List<Take> list = takes(sc, g);
        int at = 0;
        for (int i = 0; i < list.size(); i++) {
            preview(sc, g, i, volume, at);
            at += Math.round(list.get(i).lengthMs()) + 250;
        }
        return at;
    }

    /** Preview a random normal click the way it sounds in game (variation, release after a natural gap). */
    public void previewRandom(float volume, boolean variation, boolean withRelease) {
        previewRandom(null, volume, variation, withRelease, 0f, 0);
    }

    /** Same, for one key / button's sounds, at a stereo position, {@code delayMs} from now. */
    public void previewRandom(String key, float volume, boolean variation, boolean withRelease, float pan, int delayMs) {
        String sc = resolveScope(key);
        Genre g = playable(sc, Genre.NORMAL);
        if (size(sc, g) == 0 || volume <= 0f) return;
        int i = pickIndex(sc, g);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        float gain = volume * data.normGain(), pitch = 1f;
        if (variation) {
            gain *= 0.85f + r.nextFloat() * 0.15f;
            pitch = 0.96f + r.nextFloat() * 0.08f;
        }
        Take t = takes(sc, g).get(i);
        AudioEngine.play(t.press(), gain, pitch, delayMs, pan);
        if (withRelease) {
            int ms = Math.round(t.releaseOffset() * 1000f / AudioEngine.RATE);
            AudioEngine.play(releaseFor(sc, g, i), gain, pitch, delayMs + ms, pan);
        }
        previewStarts.put(t, System.nanoTime() + delayMs * 1_000_000L);
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
