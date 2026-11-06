package dev.markie.lyrics.lyrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses LRC text ("[01:23.45] words"), including optional per-word "<01:23.45>" tags. */
public final class LrcParser {
    private static final Pattern LINE_TAG = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern WORD_TAG = Pattern.compile("<(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?>");

    private LrcParser() {}

    public static List<LyricLine> parse(String lrc) {
        record Raw(long t, String text) {}
        List<Raw> raws = new ArrayList<>();
        for (String rawLine : lrc.split("\\r?\\n")) {
            Matcher m = LINE_TAG.matcher(rawLine);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (m.find() && m.start() == end) {
                times.add(toMs(m.group(1), m.group(2), m.group(3)));
                end = m.end();
            }
            if (times.isEmpty()) continue;
            String text = rawLine.substring(end).trim();
            for (long t : times) raws.add(new Raw(t, text));
        }
        raws.sort(Comparator.comparingLong(Raw::t));

        List<LyricLine> out = new ArrayList<>();
        for (int i = 0; i < raws.size(); i++) {
            Raw r = raws.get(i);
            long next = i + 1 < raws.size() ? raws.get(i + 1).t : r.t + 6000;
            String clean = WORD_TAG.matcher(r.text).replaceAll("").replaceAll("\\s+", " ").trim();
            if (clean.isEmpty() || clean.equals("♪")) continue;   // instrumental gap
            out.add(new LyricLine(r.t, next, clean, words(r.text, r.t, next)));
        }
        return out;
    }

    /** Build word timings: real ones if the LRC has &lt;mm:ss&gt; tags, otherwise a smart estimate. */
    private static List<LyricLine.Word> words(String text, long start, long next) {
        List<LyricLine.Word> words = new ArrayList<>();
        Matcher m = WORD_TAG.matcher(text);
        if (m.find()) {
            // enhanced LRC
            m.reset();
            long t = start;
            int last = 0;
            while (m.find()) {
                addWords(words, text.substring(last, m.start()), t);
                t = toMs(m.group(1), m.group(2), m.group(3));
                last = m.end();
            }
            addWords(words, text.substring(last), t);
            if (!words.isEmpty()) return words;
        }

        String[] parts = text.trim().split("\\s+");
        int totalChars = 0;
        for (String p : parts) totalChars += p.length() + 1;
        // a sung line usually takes ~300ms per word, but never more than the gap to the next line
        long span = Math.min((long) ((next - start) * 0.85), 320L * parts.length + 250);
        span = Math.max(span, 120L * parts.length);
        int before = 0;
        for (String p : parts) {
            if (p.isEmpty()) continue;
            words.add(new LyricLine.Word(p, start + span * before / Math.max(1, totalChars)));
            before += p.length() + 1;
        }
        return words;
    }

    private static void addWords(List<LyricLine.Word> out, String chunk, long t) {
        for (String w : chunk.trim().split("\\s+")) if (!w.isEmpty()) out.add(new LyricLine.Word(w, t));
    }

    private static long toMs(String min, String sec, String frac) {
        long ms = Long.parseLong(min) * 60_000L + Long.parseLong(sec) * 1000L;
        if (frac != null) {
            int f = Integer.parseInt(frac);
            ms += switch (frac.length()) { case 1 -> f * 100L; case 2 -> f * 10L; default -> f; };
        }
        return ms;
    }
}
