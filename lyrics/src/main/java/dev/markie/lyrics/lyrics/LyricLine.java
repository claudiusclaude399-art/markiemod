package dev.markie.lyrics.lyrics;

import java.util.List;

/** One lyric line. Words carry their own start times so they can pop in one by one. */
public record LyricLine(long startMs, long endMs, String text, List<Word> words) {
    public record Word(String text, long startMs) {}
}
