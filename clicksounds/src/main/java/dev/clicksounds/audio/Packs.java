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

/**
 * Clickpacks live in config/clicksounds/packs/&lt;name&gt;/. Inside, each device has its own folder
 * ({@code mouse/}, {@code keyboard/}) holding the genre folders (clicks/, releases/, softclicks/,
 * softreleases/, hardclicks/, hardreleases/).
 *
 * Packs made for Geometry Dash clickbots (genre folders at the pack root, or in player1/) are
 * used as mouse packs as they are.
 */
public final class Packs {
    public static final String DEFAULT = "My clicks";
    public static final String MOUSE = "mouse", KEYBOARD = "keyboard";

    private Packs() {}

    public static Path root() {
        return FabricLoader.getInstance().getConfigDir().resolve("clicksounds");
    }

    public static Path packsDir() {
        return root().resolve("packs");
    }

    public static List<String> list() {
        List<String> names = new ArrayList<>();
        Path d = packsDir();
        if (Files.isDirectory(d)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(d, Files::isDirectory)) {
                for (Path p : ds) names.add(p.getFileName().toString());
            } catch (IOException e) {
                ClickSoundsClient.LOG.warn("Could not list packs", e);
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** Folder the given device's genre folders are read from (may not exist). */
    public static Path deviceDir(String pack, String device) {
        Path p = packsDir().resolve(pack);
        Path own = p.resolve(device);
        if (Files.isDirectory(own) || !device.equals(MOUSE)) return own;
        // Geometry Dash style packs
        Path p1 = p.resolve("player1");
        if (hasGenreFolder(p1)) return p1;
        if (hasGenreFolder(p)) return p;
        return own;
    }

    private static boolean hasGenreFolder(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        for (Genre g : Genre.values()) if (Files.isDirectory(dir.resolve(g.clicksFolder))) return true;
        return false;
    }

    /** Quick "12 clicks, 4 soft, 3 hard" for a pack's device, from file counts (no audio loading). */
    public static String summary(String pack, String device) {
        Path base = deviceDir(pack, device);
        int[] n = new int[Genre.values().length];
        for (Genre g : Genre.values()) {
            Path d = base.resolve(g.clicksFolder);
            if (!Files.isDirectory(d)) continue;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(d, p -> Files.isRegularFile(p) && isAudio(p))) {
                for (Path ignored : ds) n[g.ordinal()]++;
            } catch (IOException ignored) {}
        }
        int c = n[Genre.NORMAL.ordinal()], so = n[Genre.SOFT.ordinal()], h = n[Genre.HARD.ordinal()];
        int keys = 0;
        Path k = base.resolve("keys");
        if (Files.isDirectory(k)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(k, Files::isDirectory)) {
                for (Path key : ds) {
                    boolean any = false;
                    for (Genre g : Genre.values()) {
                        Path d = key.resolve(g.clicksFolder);
                        if (!Files.isDirectory(d)) continue;
                        try (DirectoryStream<Path> f = Files.newDirectoryStream(d, p -> Files.isRegularFile(p) && isAudio(p))) {
                            if (f.iterator().hasNext()) any = true;
                        }
                    }
                    if (any) keys++;
                }
            } catch (IOException ignored) {}
        }
        if (c + so + h == 0 && keys == 0) return "empty";
        StringBuilder b = new StringBuilder();
        if (c + so + h > 0) {
            b.append(c).append(" click").append(c == 1 ? "" : "s");
            if (so > 0) b.append(", ").append(so).append(" soft");
            if (h > 0) b.append(", ").append(h).append(" hard");
        }
        if (keys > 0) b.append(b.length() > 0 ? " + " : "").append(keys).append(keys == 1 ? " own key" : " own keys");
        return b.toString();
    }

    public static boolean isAudio(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".wav") || n.endsWith(".ogg");
    }

    public static String baseName(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    /** Creates "My clicks", "My clicks 2", ... and returns its name. */
    public static String create() {
        String name = DEFAULT;
        for (int i = 2; Files.exists(packsDir().resolve(name)); i++) name = DEFAULT + " " + i;
        try {
            for (Genre g : Genre.values()) {
                Files.createDirectories(packsDir().resolve(name).resolve(MOUSE).resolve(g.clicksFolder));
                Files.createDirectories(packsDir().resolve(name).resolve(KEYBOARD).resolve(g.clicksFolder));
            }
        } catch (IOException e) {
            ClickSoundsClient.LOG.warn("Could not create pack {}", name, e);
        }
        return name;
    }

    /** First start: make sure there's a pack, and move 1.x recordings (config/clicksounds/mouse/*.wav) into it. */
    public static void setup() {
        for (String device : new String[]{MOUSE, KEYBOARD}) {
            Path old = root().resolve(device);
            if (!Files.isDirectory(old)) continue;
            Path dest = packsDir().resolve(DEFAULT).resolve(device);
            int moved = 0;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(old, Files::isRegularFile)) {
                for (Path f : ds) {
                    if (!isAudio(f)) continue;
                    String base = baseName(f), ext = f.getFileName().toString().substring(base.length());
                    boolean up = base.endsWith("_up");
                    Path target = dest.resolve(up ? Genre.NORMAL.releasesFolder : Genre.NORMAL.clicksFolder)
                        .resolve((up ? base.substring(0, base.length() - 3) : base) + ext);
                    Files.createDirectories(target.getParent());
                    if (!Files.exists(target)) Files.move(f, target);
                    moved++;
                }
            } catch (IOException e) {
                ClickSoundsClient.LOG.warn("Could not move old {} sounds into the '{}' pack", device, DEFAULT, e);
            }
            try { Files.deleteIfExists(old); } catch (IOException ignored) {} // only if now empty
            if (moved > 0) ClickSoundsClient.LOG.info("Moved {} old {} sound file(s) into pack '{}'", moved, device, DEFAULT);
        }
        if (list().isEmpty()) create();
    }

    /** The pack to use: the saved one if it still exists, otherwise the first available. */
    public static String resolve(String wanted) {
        List<String> all = list();
        if (all.contains(wanted)) return wanted;
        if (all.isEmpty()) return create();
        return all.contains(DEFAULT) ? DEFAULT : all.get(0);
    }
}
