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
                for (Path p : ds) if (!p.getFileName().toString().startsWith(".")) names.add(p.getFileName().toString());
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
        boolean mouse = device.equals(MOUSE);
        Path k = base.resolve(mouse ? "buttons" : "keys");
        if (Files.isDirectory(k)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(k, Files::isDirectory)) {
                for (Path key : ds) if (hasClicks(key)) keys++;
            } catch (IOException ignored) {}
        }
        boolean scroll = mouse && hasClicks(base.resolve("scroll"));
        if (c + so + h == 0 && keys == 0 && !scroll) return "empty";
        StringBuilder b = new StringBuilder();
        if (c + so + h > 0) {
            b.append(c).append(" click").append(c == 1 ? "" : "s");
            if (so > 0) b.append(", ").append(so).append(" soft");
            if (h > 0) b.append(", ").append(h).append(" hard");
        }
        String unit = mouse ? " own button" : " own key";
        if (keys > 0) b.append(b.length() > 0 ? " + " : "").append(keys).append(unit).append(keys == 1 ? "" : "s");
        if (scroll) b.append(b.length() > 0 ? " + " : "").append("scroll");
        return b.toString();
    }

    private static boolean hasClicks(Path scopeDir) {
        for (Genre g : Genre.values()) {
            Path d = scopeDir.resolve(g.clicksFolder);
            if (!Files.isDirectory(d)) continue;
            try (DirectoryStream<Path> f = Files.newDirectoryStream(d, p -> Files.isRegularFile(p) && isAudio(p))) {
                if (f.iterator().hasNext()) return true;
            } catch (IOException ignored) {}
        }
        return false;
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
        importZips();
        if (list().isEmpty()) create();
    }


    private static final int MAX_ENTRIES = 5000;
    private static final long MAX_FILE = 50L << 20, MAX_TOTAL = 400L << 20;

    /**
     * Unpacks every clickpack .zip dropped into the packs folder into a pack folder of the same
     * name (once: if that folder already exists the zip is left alone). Only .wav / .ogg files are
     * taken; a single wrapping top folder inside the zip is skipped.
     * @return names of the packs that were imported
     */
    public static List<String> importZips() {
        List<String> imported = new ArrayList<>();
        Path dir = packsDir();
        if (!Files.isDirectory(dir)) return imported;
        List<Path> zips = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, p -> Files.isRegularFile(p)
                && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))) {
            for (Path z : ds) zips.add(z);
        } catch (IOException e) {
            return imported;
        }
        for (Path zip : zips) {
            String name = baseName(zip).trim();
            Path target = dir.resolve(name);
            if (name.isEmpty() || Files.exists(target)) continue;
            try {
                int n = unzip(zip, target, java.nio.charset.StandardCharsets.UTF_8);
                if (n == 0) {
                    ClickSoundsClient.LOG.warn("{} has no .wav/.ogg files, so it's probably not a clickpack", zip.getFileName());
                    deleteTree(target);
                    continue;
                }
                imported.add(name);
                ClickSoundsClient.LOG.info("Imported clickpack '{}' ({} sounds) from {}", name, n, zip.getFileName());
                archive(zip);
            } catch (Exception first) {
                // older zips store names in the DOS code page
                try {
                    deleteTree(target);
                    int n = unzip(zip, target, java.nio.charset.Charset.forName("IBM437"));
                    if (n > 0) { imported.add(name); archive(zip); } else deleteTree(target);
                } catch (Exception e) {
                    ClickSoundsClient.LOG.warn("Could not import {}", zip.getFileName(), e);
                    try { deleteTree(target); } catch (IOException ignored) {}
                }
            }
        }
        return imported;
    }

    /** Moves an imported zip to packs/.imported/, so renaming or deleting the pack doesn't bring it back. */
    private static void archive(Path zip) {
        try {
            Path dir = packsDir().resolve(".imported");
            Files.createDirectories(dir);
            Files.move(zip, dir.resolve(zip.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            ClickSoundsClient.LOG.warn("Could not move {} out of the packs folder", zip.getFileName(), e);
        }
    }

    private static int unzip(Path zip, Path target, java.nio.charset.Charset cs) throws IOException {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip.toFile(), cs)) {
            List<java.util.zip.ZipEntry> entries = new ArrayList<>();
            var en = zf.entries();
            while (en.hasMoreElements()) {
                var e = en.nextElement();
                String n = e.getName().replace('\\', '/');
                if (e.isDirectory() || n.startsWith("__MACOSX/") || n.contains("/.") || n.startsWith(".")) continue;
                String lower = n.toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".wav") && !lower.endsWith(".ogg")) continue;
                entries.add(e);
                if (entries.size() > MAX_ENTRIES) throw new IOException("too many files");
            }
            // strip one wrapping folder if everything is inside the same top folder
            String strip = null;
            for (var e : entries) {
                String n = e.getName().replace('\\', '/');
                int slash = n.indexOf('/');
                String top = slash < 0 ? "" : n.substring(0, slash + 1);
                if (strip == null) strip = top;
                else if (!strip.equals(top)) { strip = ""; break; }
            }
            if (strip == null) strip = "";
            // ...unless that folder is itself one of the genre / device folders
            String bare = strip.isEmpty() ? "" : strip.substring(0, strip.length() - 1).toLowerCase(Locale.ROOT);
            for (Genre g : Genre.values()) if (bare.equals(g.clicksFolder) || bare.equals(g.releasesFolder)) strip = "";
            if (bare.equals(MOUSE) || bare.equals(KEYBOARD) || bare.equals("player1") || bare.equals("player2")) strip = "";

            Path root = target.toAbsolutePath().normalize();
            long total = 0;
            int count = 0;
            for (var e : entries) {
                String n = e.getName().replace('\\', '/').substring(strip.length());
                Path out = root.resolve(n).normalize();
                if (!out.startsWith(root) || out.equals(root)) continue; // no escaping the pack folder
                if (e.getSize() > MAX_FILE) continue;
                Files.createDirectories(out.getParent());
                try (var in = zf.getInputStream(e)) {
                    byte[] data = in.readNBytes((int) MAX_FILE + 1);
                    if (data.length > MAX_FILE) continue;
                    total += data.length;
                    if (total > MAX_TOTAL) throw new IOException("clickpack too big");
                    Files.write(out, data);
                    count++;
                }
            }
            return count;
        }
    }

    /** Zips a pack (its sound files) into config/clicksounds/exported/&lt;pack&gt;.zip, ready to share. */
    public static Path export(String pack) throws IOException {
        Path src = packsDir().resolve(pack);
        Path outDir = root().resolve("exported");
        Files.createDirectories(outDir);
        Path out = outDir.resolve(pack + ".zip");
        Path tmp = outDir.resolve(pack + ".zip.tmp");
        try (var zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(tmp));
             var walk = Files.walk(src)) {
            for (Path p : (Iterable<Path>) walk.sorted()::iterator) {
                if (!Files.isRegularFile(p) || !isAudio(p)) continue;
                String rel = src.relativize(p).toString().replace('\\', '/');
                zos.putNextEntry(new java.util.zip.ZipEntry(pack + "/" + rel));
                Files.copy(p, zos);
                zos.closeEntry();
            }
        }
        Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return out;
    }

    /** @return null if renamed, otherwise why not */
    public static String rename(String from, String to) {
        String name = to == null ? "" : to.trim();
        if (name.isEmpty()) return "Type a name";
        if (name.length() > 48) return "That name is too long";
        if (name.startsWith(".") || name.matches(".*[\\\\/:*?\"<>|].*")) return "Names can't contain \\ / : * ? \" < > |";
        if (name.equals(from)) return null;
        Path a = packsDir().resolve(from), b = packsDir().resolve(name);
        if (Files.exists(b) && !name.equalsIgnoreCase(from)) return "There is already a pack called '" + name + "'";
        try {
            if (name.equalsIgnoreCase(from)) { // case-only rename on case-insensitive file systems
                Path tmp = packsDir().resolve(from + ".renaming");
                Files.move(a, tmp);
                Files.move(tmp, b);
            } else Files.move(a, b);
            return null;
        } catch (IOException e) {
            return "Couldn't rename: " + e.getMessage();
        }
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            for (Path q : (Iterable<Path>) walk.sorted(java.util.Comparator.reverseOrder())::iterator) Files.deleteIfExists(q);
        }
    }

    /** The pack to use: the saved one if it still exists, otherwise the first available. */
    public static String resolve(String wanted) {
        List<String> all = list();
        if (all.contains(wanted)) return wanted;
        if (all.isEmpty()) return create();
        return all.contains(DEFAULT) ? DEFAULT : all.get(0);
    }
}
