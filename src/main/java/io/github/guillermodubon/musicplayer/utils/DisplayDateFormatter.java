package io.github.guillermodubon.musicplayer.utils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DisplayDateFormatter {

    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Pattern ISO_DATE_FRAGMENT = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd/MM/yyyy")
    );

    private DisplayDateFormatter() {
    }

    public static String toDayMonthYear(String value) {
        if (value == null || value.isBlank()) return "";
        String trimmed = value.trim();

        for (DateTimeFormatter formatter : DATE_FORMATS) {
            String parsed = tryFormat(trimmed, formatter);
            if (!parsed.isBlank()) return parsed;
        }

        // Supports historical playlist values such as "Creation date: 2024-03-17".
        Matcher dateFragment = ISO_DATE_FRAGMENT.matcher(trimmed);
        if (dateFragment.find()) {
            String parsed = tryFormat(dateFragment.group(), DateTimeFormatter.ISO_LOCAL_DATE);
            if (!parsed.isBlank()) return parsed;
        }

        int separator = firstDateTimeSeparator(trimmed);
        if (separator > 0) {
            String dateOnly = trimmed.substring(0, separator).trim();
            for (DateTimeFormatter formatter : DATE_FORMATS) {
                String parsed = tryFormat(dateOnly, formatter);
                if (!parsed.isBlank()) return parsed;
            }
            return dateOnly;
        }

        return trimmed;
    }

    private static String tryFormat(String value, DateTimeFormatter formatter) {
        try {
            if (formatter == DateTimeFormatter.ISO_OFFSET_DATE_TIME) {
                return OffsetDateTime.parse(value, formatter).toLocalDate().format(DISPLAY_FORMAT);
            }
            if (formatter == DateTimeFormatter.ISO_LOCAL_DATE_TIME
                    || formatter.toString().contains("HourOfDay")) {
                return LocalDateTime.parse(value, formatter).toLocalDate().format(DISPLAY_FORMAT);
            }
            return LocalDate.parse(value, formatter).format(DISPLAY_FORMAT);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static int firstDateTimeSeparator(String value) {
        int space = value.indexOf(' ');
        int t = value.indexOf('T');
        if (space < 0) return t;
        if (t < 0) return space;
        return Math.min(space, t);
    }
}
