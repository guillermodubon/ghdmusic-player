package io.github.guillermodubon.musicplayer.models.lyrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Original LRC text plus a parsed, time-ordered representation for playback
 * views. Persisting the raw value prevents precision from being lost.
 */
public final class SyncedLyrics {

    private static final Pattern TIMESTAMP = Pattern.compile(
            "\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?]"
    );

    private final String rawText;
    private final List<LyricLine> lines;

    public SyncedLyrics(String rawText) {
        this.rawText = rawText == null ? "" : rawText;
        this.lines = List.copyOf(parse(this.rawText));
    }

    public String rawText() {
        return rawText;
    }

    public List<LyricLine> lines() {
        return lines;
    }

    public boolean isAvailable() {
        return !lines.isEmpty();
    }

    private static List<LyricLine> parse(String rawText) {
        if (rawText == null || rawText.isBlank()) return List.of();

        List<LyricLine> parsed = new ArrayList<>();
        for (String rawLine : rawText.replace("\r", "").split("\n")) {
            Matcher matcher = TIMESTAMP.matcher(rawLine);
            int contentStart = 0;
            List<Long> timestamps = new ArrayList<>();
            while (matcher.find()) {
                contentStart = matcher.end();
                timestamps.add(toMillis(matcher.group(1), matcher.group(2), matcher.group(3)));
            }
            if (timestamps.isEmpty()) continue;

            String text = rawLine.substring(Math.min(contentStart, rawLine.length())).trim();
            for (Long timestamp : timestamps) {
                parsed.add(new LyricLine(timestamp, text));
            }
        }

        parsed.sort(Comparator.comparingLong(LyricLine::timestampMillis));
        return parsed;
    }

    private static long toMillis(String minutes, String seconds, String fraction) {
        try {
            long millis = (Long.parseLong(minutes) * 60L + Long.parseLong(seconds)) * 1_000L;
            if (fraction == null || fraction.isBlank()) return millis;
            String padded = (fraction + "000").substring(0, 3);
            return millis + Long.parseLong(padded);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
