package dev.markie.lyrics.spotify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.markie.lyrics.render.CoverArt;
import net.minecraft.util.Util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Reads what the Spotify app on this computer is playing, without the Web API, so it works without
 * Spotify Premium and without a developer app.
 * <ul>
 *   <li>Windows: the system media controls (what the volume pop-up shows), via one hidden PowerShell process</li>
 *   <li>macOS: asks the Spotify app with AppleScript</li>
 *   <li>Linux: playerctl (MPRIS)</li>
 * </ul>
 */
public final class LocalPlayer {
    private static volatile NowPlaying latest;
    private static volatile String status = "Not started";
    private static volatile boolean started = false;
    private static volatile long lastData = 0;
    private static Process winProcess;
    private static String lastCoverKey;
    // fallback clock for players that don't report a position (some Spotify versions on Windows)
    private static String clockId;
    private static long clockPos, clockAt;
    private static boolean clockPlaying;

    private LocalPlayer() {}

    public static NowPlaying latest() { return latest; }
    public static String status() { return status; }

    /** True if we heard from the local player recently (used by Auto mode). */
    public static boolean alive() { return started && System.currentTimeMillis() - lastData < 5000; }

    public static synchronized void ensureStarted() {
        if (started) return;
        started = true;
        Thread t = new Thread(LocalPlayer::run, "MarkieLyrics-LocalPlayer");
        t.setDaemon(true);
        t.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { if (winProcess != null) winProcess.destroyForcibly(); }));
    }

    private static void run() {
        try {
            switch (Util.getOperatingSystem()) {
                case WINDOWS -> runWindows();
                case OSX -> pollLoop(LocalPlayer::readMac);
                case LINUX -> pollLoop(LocalPlayer::readLinux);
                default -> status = "Not supported on this system";
            }
        } catch (Exception e) {
            status = "Couldn't read the Spotify app: " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------ Windows

    private static final String WIN_SCRIPT = String.join("\n",
        "$ErrorActionPreference = 'SilentlyContinue'",
        "Add-Type -AssemblyName System.Runtime.WindowsRuntime",
        "$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | ? { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]",
        "function Await($op, [Type]$t) { $task = $asTaskGeneric.MakeGenericMethod($t).Invoke($null, @($op)); $task.Wait(-1) | Out-Null; $task.Result }",
        "[Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime] | Out-Null",
        "[Windows.Storage.Streams.DataReader,Windows.Storage.Streams,ContentType=WindowsRuntime] | Out-Null",
        "$mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])",
        "$lastKey = ''",
        "while ($true) {",
        "  $o = @{ ok = $true }",
        "  try {",
        "    $s = $mgr.GetSessions() | ? { $_.SourceAppUserModelId -like '*Spotify*' } | Select-Object -First 1",
        "    if ($s) {",
        "      $p = Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])",
        "      $t = $s.GetTimelineProperties()",
        "      $pb = $s.GetPlaybackInfo()",
        "      $o.title = $p.Title; $o.artist = $p.Artist; $o.album = $p.AlbumTitle",
        "      $o.pos = [int64]$t.Position.TotalMilliseconds; $o.dur = [int64]$t.EndTime.TotalMilliseconds",
        "      $o.upd = [int64]$t.LastUpdatedTime.ToUnixTimeMilliseconds(); $o.now = [int64][DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()",
        "      $o.playing = ($pb.PlaybackStatus.ToString() -eq 'Playing')",
        "      $key = \"$($p.Title)|$($p.Artist)\"",
        "      if ($key -ne $lastKey -and $p.Thumbnail) {",
        "        try {",
        "          $st = Await ($p.Thumbnail.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])",
        "          $rd = New-Object Windows.Storage.Streams.DataReader($st.GetInputStreamAt(0))",
        "          $n = Await ($rd.LoadAsync([uint32]$st.Size)) ([uint32])",
        "          $bytes = New-Object byte[] $n",
        "          $rd.ReadBytes($bytes)",
        "          $o.thumb = [Convert]::ToBase64String($bytes)",
        "          $lastKey = $key",
        "        } catch { }",
        "      }",
        "    } else { $o.none = $true }",
        "  } catch { $o.err = $_.Exception.Message }",
        "  [Console]::Out.WriteLine(($o | ConvertTo-Json -Compress))",
        "  [Console]::Out.Flush()",
        "  Start-Sleep -Milliseconds 700",
        "}");

    private static void runWindows() throws Exception {
        Path script = Files.createTempFile("markielyrics-media", ".ps1");
        Files.writeString(script, WIN_SCRIPT, StandardCharsets.UTF_8);
        script.toFile().deleteOnExit();
        while (true) {
            status = "Looking for the Spotify app…";
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-WindowStyle", "Hidden", "-File", script.toString());
            pb.redirectErrorStream(true);
            winProcess = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(winProcess.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (!line.startsWith("{")) continue;
                    handleWindows(line);
                }
            }
            // the helper ended (shouldn't happen): wait a bit and restart it
            status = "Spotify helper stopped, restarting…";
            Thread.sleep(3000);
        }
    }

    private static void handleWindows(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        lastData = System.currentTimeMillis();
        if (o.has("err")) { status = "Windows media error: " + o.get("err").getAsString(); return; }
        if (o.has("none") || !o.has("title")) { latest = null; status = "Open the Spotify app and play a song"; return; }
        String title = o.get("title").getAsString(), artist = str(o, "artist"), album = str(o, "album");
        if (title.isBlank()) { latest = null; status = "Nothing playing in Spotify"; return; }
        long pos = o.get("pos").getAsLong(), dur = o.get("dur").getAsLong();
        boolean playing = o.get("playing").getAsBoolean();
        // Windows only refreshes the position every few seconds, so add the time since its last update
        long upd = o.has("upd") ? o.get("upd").getAsLong() : 0, now = o.has("now") ? o.get("now").getAsLong() : 0;
        if (playing && upd > 0 && now > upd && now - upd < 60_000) pos += now - upd;
        String id = "local:" + title + "|" + artist;
        boolean noTimeline = dur <= 0 && pos <= 0;
        long nowMs = System.currentTimeMillis();
        if (!id.equals(clockId)) { clockId = id; clockPos = 0; clockAt = nowMs; clockPlaying = playing; }
        else {
            if (clockPlaying) clockPos += nowMs - clockAt;
            clockAt = nowMs;
            clockPlaying = playing;
        }
        if (noTimeline) pos = clockPos;   // timing is estimated from when the song started
        if (o.has("thumb")) {
            try { CoverArt.requestBytes(id, Base64.getDecoder().decode(o.get("thumb").getAsString())); } catch (Exception ignored) {}
            lastCoverKey = id;
        } else if (!id.equals(lastCoverKey) && latest != null && !id.equals(latest.id())) {
            CoverArt.request(null);
        }
        latest = new NowPlaying(id, title, artist, album, null, Math.max(0, dur), dur > 0 ? Math.min(pos, dur) : pos, playing, System.currentTimeMillis());
        status = (playing ? "Playing" : "Paused") + " (Spotify app on this PC" + (noTimeline ? ", timing estimated" : "") + ")";
    }

    // ------------------------------------------------------------------ macOS / Linux

    private interface Reader { void read() throws Exception; }

    private static void pollLoop(Reader reader) throws Exception {
        while (true) {
            try { reader.read(); } catch (Exception e) { status = "Couldn't read Spotify: " + e.getMessage(); }
            Thread.sleep(900);
        }
    }

    private static void readMac() throws Exception {
        String script = "if application \"Spotify\" is running then\n"
            + "tell application \"Spotify\"\n"
            + "if player state is stopped then return \"STOPPED\"\n"
            + "set d to \"\t\"\n"
            + "return (name of current track) & d & (artist of current track) & d & (album of current track) & d & "
            + "(duration of current track) & d & (player position as string) & d & (player state as string) & d & "
            + "(artwork url of current track) & d & (id of current track)\n"
            + "end tell\n"
            + "else\nreturn \"NOTRUNNING\"\nend if";
        String out = exec(new String[]{"osascript", "-e", script});
        lastData = System.currentTimeMillis();
        if (out == null || out.startsWith("NOTRUNNING")) { latest = null; status = "Open the Spotify app and play a song"; return; }
        if (out.startsWith("STOPPED")) { latest = null; status = "Nothing playing in Spotify"; return; }
        String[] f = out.split("\t", -1);
        if (f.length < 8) { status = "Unexpected answer from Spotify"; return; }
        long dur = parseLong(f[3]);
        long pos = Math.round(parseDouble(f[4]) * 1000);
        boolean playing = f[5].trim().equalsIgnoreCase("playing");
        publish("mac:" + f[7].trim(), f[0], f[1], f[2], f[6].trim(), dur, pos, playing, "Spotify app on this Mac");
    }

    private static void readLinux() throws Exception {
        String out = exec(new String[]{"playerctl", "-p", "spotify", "metadata", "--format",
            "{{title}}\t{{artist}}\t{{album}}\t{{mpris:length}}\t{{position}}\t{{status}}\t{{mpris:artUrl}}\t{{mpris:trackid}}"});
        lastData = System.currentTimeMillis();
        if (out == null || out.isBlank()) { latest = null; status = "Open Spotify (and install playerctl)"; return; }
        String[] f = out.split("\t", -1);
        if (f.length < 8) { status = "Unexpected answer from playerctl"; return; }
        long dur = parseLong(f[3]) / 1000, pos = parseLong(f[4]) / 1000;
        boolean playing = f[5].trim().equalsIgnoreCase("Playing");
        String art = f[6].trim().replace("https://open.spotify.com/image/", "https://i.scdn.co/image/");
        publish("linux:" + f[7].trim(), f[0], f[1], f[2], art, dur, pos, playing, "Spotify app on this computer");
    }

    private static void publish(String id, String title, String artist, String album, String art, long dur, long pos, boolean playing, String where) {
        if (title.isBlank()) { latest = null; status = "Nothing playing in Spotify"; return; }
        latest = new NowPlaying(id, title.trim(), artist.trim(), album.trim(), art.isEmpty() ? null : art, dur, Math.min(pos, dur), playing,
            System.currentTimeMillis());
        status = (playing ? "Playing" : "Paused") + " (" + where + ")";
    }

    // ------------------------------------------------------------------ controls

    /** "toggle", "next" or "previous". Works without Premium, since it talks to the app directly. */
    public static void command(String what) {
        Thread t = new Thread(() -> {
            try {
                switch (Util.getOperatingSystem()) {
                    case WINDOWS -> {
                        String call = switch (what) { case "next" -> "TrySkipNextAsync"; case "previous" -> "TrySkipPreviousAsync"; default -> "TryTogglePlayPauseAsync"; };
                        String ps = "[Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime] | Out-Null;"
                            + "Add-Type -AssemblyName System.Runtime.WindowsRuntime;"
                            + "$g=([System.WindowsRuntimeSystemExtensions].GetMethods() | ? { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0];"
                            + "$t=$g.MakeGenericMethod([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]).Invoke($null,@([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()));$t.Wait(-1)|Out-Null;"
                            + "$s=$t.Result.GetSessions() | ? { $_.SourceAppUserModelId -like '*Spotify*' } | Select-Object -First 1;"
                            + "if($s){ $s." + call + "() | Out-Null }";
                        exec(new String[]{"powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", ps});
                    }
                    case OSX -> exec(new String[]{"osascript", "-e", "tell application \"Spotify\" to "
                        + switch (what) { case "next" -> "next track"; case "previous" -> "previous track"; default -> "playpause"; }});
                    case LINUX -> exec(new String[]{"playerctl", "-p", "spotify",
                        switch (what) { case "next" -> "next"; case "previous" -> "previous"; default -> "play-pause"; }});
                    default -> {}
                }
            } catch (Exception ignored) {
            }
        }, "MarkieLyrics-LocalControl");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ helpers

    private static String exec(String[] cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(5, TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }
        if (p.exitValue() != 0) return null;
        return new String(out, StandardCharsets.UTF_8).trim();
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static long parseLong(String s) {
        try { return Math.round(Double.parseDouble(s.trim().replace(',', '.'))); } catch (Exception e) { return 0; }
    }

    private static double parseDouble(String s) {
        try { return Double.parseDouble(s.trim().replace(',', '.')); } catch (Exception e) { return 0; }
    }
}
