package dev.markiemod.adminguard.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TimeUtil {

    private static final Pattern PART = Pattern.compile("(\\d+)\\s*([smhdw])", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private TimeUtil() {
    }

    /** Parses "30m", "2h", "1d12h", "1w" into milliseconds. Returns -1 when invalid. */
    public static long parse(String input) {
        if (input == null || input.isBlank()) return -1;
        Matcher m = PART.matcher(input.trim());
        long total = 0;
        int end = 0;
        boolean found = false;
        while (m.find()) {
            if (m.start() != end) return -1;
            end = m.end();
            found = true;
            long n = Long.parseLong(m.group(1));
            total += switch (Character.toLowerCase(m.group(2).charAt(0))) {
                case 's' -> n * 1000L;
                case 'm' -> n * 60_000L;
                case 'h' -> n * 3_600_000L;
                case 'd' -> n * 86_400_000L;
                default -> n * 604_800_000L;
            };
        }
        return found && end == input.trim().length() ? total : -1;
    }

    /** Formats a duration compactly, e.g. "2d 3h" or "45s". */
    public static String format(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "m";
        long h = m / 60;
        if (h < 24) return h + "h" + (m % 60 > 0 ? " " + m % 60 + "m" : "");
        long d = h / 24;
        return d + "d" + (h % 24 > 0 ? " " + h % 24 + "h" : "");
    }

    public static String ago(long time) {
        return format(System.currentTimeMillis() - time) + " ago";
    }

    public static String date(long time) {
        return DATE.format(Instant.ofEpochMilli(time));
    }
}
