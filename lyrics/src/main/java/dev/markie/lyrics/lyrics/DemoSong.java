package dev.markie.lyrics.lyrics;

import dev.markie.lyrics.spotify.NowPlaying;

import java.util.List;

/** A short made-up song used by the "Preview" switch, so you can try every look without Spotify. */
public final class DemoSong {
    public static final String ID = "markielyrics:demo";
    public static final long DURATION = 26_000;

    private static final String LRC = """
        [00:00.80] Welcome to the sky tonight
        [00:04.40] Every word is made of light
        [00:08.00] Turn around and watch them glow
        [00:11.60] Lyrics floating as you go
        [00:15.20] Pick a colour, pick a font
        [00:18.80] Write the sky the way you want
        [00:22.40] This is your world's song
        """;

    public static final List<LyricLine> LINES = LrcParser.parse(LRC);

    private DemoSong() {}

    public static NowPlaying nowPlaying(long startedAt, boolean playing, long pausedAt) {
        long now = System.currentTimeMillis();
        long pos = ((playing ? now : pausedAt) - startedAt) % DURATION;
        return new NowPlaying(ID, "Song of the Sky (preview)", "Markie Lyrics", "Demo", null, DURATION, pos, playing, now);
    }
}
