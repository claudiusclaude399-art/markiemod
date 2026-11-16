package dev.markiesdetector.analyzer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.markiesdetector.config.PvpConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Reads and writes fights in .minecraft/markiesdetector/fights (one .json.gz per fight + a small .meta.json). */
public final class FightStore {
    private FightStore() {}

    private static final Gson GSON = new GsonBuilder().serializeSpecialFloatingPointValues().create();
    private static final List<FightRecord.FightMeta> METAS = Collections.synchronizedList(new ArrayList<>());
    private static volatile boolean loaded = false;
    /** Fights saved this session, so they open instantly even while still being written. */
    private static final java.util.Map<String, FightRecord> RECENT = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());

    public static Path dir() {
        return FabricLoader.getInstance().getGameDir().resolve("markiesdetector").resolve("fights");
    }

    /** Newest first. */
    public static List<FightRecord.FightMeta> list() {
        if (!loaded) loadIndex();
        synchronized (METAS) {
            return new ArrayList<>(METAS);
        }
    }

    public static synchronized void loadIndex() {
        loaded = true;
        List<FightRecord.FightMeta> found = new ArrayList<>();
        migrateOldFights();
        try {
            Files.createDirectories(dir());
            try (Stream<Path> s = Files.list(dir())) {
                s.filter(p -> p.getFileName().toString().endsWith(".meta.json")).forEach(p -> {
                    try (Reader r = Files.newBufferedReader(p)) {
                        FightRecord.FightMeta m = GSON.fromJson(r, FightRecord.FightMeta.class);
                        if (m != null && m.id != null) found.add(m);
                    } catch (Exception e) {
                        System.err.println("[MarkiesDetector] Bad fight index " + p + ": " + e);
                    }
                });
            }
        } catch (Exception e) {
            System.err.println("[MarkiesDetector] Could not list fights: " + e);
        }
        found.sort((a, b) -> Long.compare(b.start, a.start));
        synchronized (METAS) {
            METAS.clear();
            METAS.addAll(found);
        }
    }

    /** First launch after the rename: copy fights saved under the old pvpmod folder. */
    private static void migrateOldFights() {
        Path old = FabricLoader.getInstance().getGameDir().resolve("pvpmod").resolve("fights");
        if (Files.exists(dir()) || !Files.isDirectory(old)) return;
        try {
            Files.createDirectories(dir());
            try (Stream<Path> s = Files.list(old)) {
                for (Path p : (Iterable<Path>) s::iterator) Files.copy(p, dir().resolve(p.getFileName()));
            }
        } catch (Exception e) {
            System.err.println("[MarkiesDetector] Could not copy old fights: " + e);
        }
    }

    /** Saves in the background and adds the fight to the list straight away. */
    public static void save(FightRecord r, FightRecord.FightMeta meta) {
        if (!loaded) loadIndex();
        synchronized (METAS) {
            METAS.removeIf(m -> m.id.equals(meta.id));
            METAS.add(0, meta);
        }
        RECENT.put(r.id, r);
        synchronized (RECENT) {
            while (RECENT.size() > 4) RECENT.remove(RECENT.keySet().iterator().next());
        }
        CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(dir());
                try (Writer w = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(dir().resolve(r.id + ".json.gz"))), StandardCharsets.UTF_8)) {
                    GSON.toJson(r, w);
                }
                Files.writeString(dir().resolve(r.id + ".meta.json"), GSON.toJson(meta));
                prune();
            } catch (Exception e) {
                System.err.println("[MarkiesDetector] Could not save fight: " + e);
            }
        });
    }

    public static FightRecord load(String id) {
        FightRecord cached = RECENT.get(id);
        if (cached != null) return cached;
        try (Reader r = new InputStreamReader(new GZIPInputStream(Files.newInputStream(dir().resolve(id + ".json.gz"))), StandardCharsets.UTF_8)) {
            FightRecord rec = GSON.fromJson(r, FightRecord.class);
            if (rec != null) {
                if (rec.frames == null) rec.frames = new ArrayList<>();
                if (rec.events == null) rec.events = new ArrayList<>();
                rec.events.removeIf(e -> e == null || e.k == null);
            }
            return rec;
        } catch (Exception e) {
            System.err.println("[MarkiesDetector] Could not load fight " + id + ": " + e);
            return null;
        }
    }

    public static void delete(String id) {
        RECENT.remove(id);
        synchronized (METAS) {
            METAS.removeIf(m -> m.id.equals(id));
        }
        try {
            Files.deleteIfExists(dir().resolve(id + ".json.gz"));
            Files.deleteIfExists(dir().resolve(id + ".meta.json"));
        } catch (Exception e) {
            System.err.println("[MarkiesDetector] Could not delete fight " + id + ": " + e);
        }
    }

    private static void prune() {
        int max = Math.max(5, PvpConfig.get().analyzerMaxFights);
        List<FightRecord.FightMeta> all = list();
        for (int i = max; i < all.size(); i++) delete(all.get(i).id);
    }
}
