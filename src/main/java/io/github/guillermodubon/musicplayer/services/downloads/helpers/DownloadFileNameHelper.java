package io.github.guillermodubon.musicplayer.services.downloads.helpers;

import java.io.File;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DownloadFileNameHelper {

    private static final String TMP_PREFIX = "dl_tmp_";

    private DownloadFileNameHelper() {}

    public static String cleanTitle(String title) {
        if (title == null) return "";
        String[] patterns = {
                "official audio",
                "audio",
                "visualizer",
                "official music video",
                "official visualizer",
                "official video"
        };

        for (String term : patterns) {
            title = title.replaceAll("(?i)\\(.*?\\b" + Pattern.quote(term) + "\\b.*?\\)", "");
        }
        for (String term : patterns) {
            title = title.replaceAll("(?i)\\b" + Pattern.quote(term) + "\\b", "");
        }
        return title.trim();
    }

    public static String sanitizeFileName(String in) {
        if (in == null) return "";
        String s = in.replaceAll("[\\\\/:*?\"<>|]", "").trim();
        s = s.replaceAll("\\s+", " ");
        return s;
    }

    public static String sanitizeSearchQuery(String raw) {
        if (raw == null) return "";
        String s = Normalizer.normalize(raw, Normalizer.Form.NFC).trim();

        s = s.replaceAll("\\p{Cntrl}", " ");
        s = s.replaceAll("[\"'´`]", " ");
        // Arguments are passed directly to ProcessBuilder, never through a
        // shell. Keep every printable Unicode character in search terms.
        s = Normalizer.normalize(raw, Normalizer.Form.NFC)
                .replaceAll("\\p{Cntrl}", " ");
        s = s.replaceAll("\\s+", " ").trim();

        final int MAX = 200;
        if (s.codePointCount(0, s.length()) > MAX) {
            s = s.substring(0, s.offsetByCodePoints(0, MAX)).trim();
        }

        return s;
    }

    public static boolean looksLikeUrl(String raw) {
        if (raw == null) return false;
        String r = raw.trim().toLowerCase(Locale.ROOT);
        return r.startsWith("http://") || r.startsWith("https://") || r.matches("^[a-z0-9.-]+\\.[a-z]{2,6}(/.*)?$");
    }


    public static File[] findTmpFiles(File dir, String token) {
        if (dir == null || !dir.isDirectory()) return new File[0];

        String expectedPrefix = TMP_PREFIX;
        if (token != null && !token.isBlank()) {
            expectedPrefix += sanitizeToken(token) + "_";
        }
        final String prefix = expectedPrefix;

        File[] files = dir.listFiles((d, name) -> {
            if (name == null) return false;
            if (!name.startsWith(prefix)) return false;
            String lower = name.toLowerCase(Locale.ROOT);
            return lower.endsWith(".mp3") || lower.endsWith(".mp3.part") || lower.endsWith(".part")
                    || lower.endsWith(".ytdl") || lower.endsWith(".tmp") || lower.endsWith(".m4a")
                    || lower.endsWith(".webm") || lower.endsWith(".aac") || lower.endsWith(".mp4")
                    || lower.endsWith(".opus") || lower.endsWith(".ogg") || lower.endsWith(".flac")
                    || lower.endsWith(".wav");
        });

        if (files == null || files.length == 0) return new File[0];

        Arrays.sort(files,
                Comparator.comparingLong(File::length).reversed()
                        .thenComparingLong(File::lastModified).reversed());

        return files;
    }

    public static File findLatestTmpFile(File dir, String token) {
        File[] files = findTmpFiles(dir, token);
        return files.length == 0 ? null : files[0];
    }

    public static String sanitizeToken(String token) {
        if (token == null || token.isBlank()) return "task";
        String cleaned = token.replaceAll("[^A-Za-z0-9_-]", "");
        return cleaned.isBlank() ? "task" : cleaned;
    }

    public static String computeDesiredBaseName(String artistForFile, String fetchedTitle, String cleanSongName) {
        String artistPart = null;
        if (artistForFile != null && !artistForFile.isBlank()) {
            artistPart = artistForFile.split(",")[0].trim();
        }

        String displayTitle = fetchedTitle;
        if (displayTitle == null || displayTitle.isBlank()) {
            displayTitle = cleanSongName == null ? "" : cleanSongName;
        }

        String base = (artistPart == null || artistPart.isBlank()) ? displayTitle : (artistPart + " " + displayTitle);
        return sanitizeFileName(base);
    }

    public static File resolveUniqueFile(File file) {
        if (file == null) return null;
        if (!file.exists()) return file;

        String name = file.getName();
        String base = name;
        String ext = "";

        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }

        int i = 1;
        File parent = file.getParentFile();
        while (true) {
            File candidate = new File(parent, base + " (" + i + ")" + ext);
            if (!candidate.exists()) return candidate;
            i++;
        }
    }

    public static boolean attemptRenameOrCopy(File src, File dest) {
        try {
            if (src.renameTo(dest)) return true;
        } catch (Exception ignored) {
        }

        try {
            java.nio.file.Files.copy(src.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try { src.delete(); } catch (Exception ignored) {}
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    public static int parsePercent(String ytLine) {
        if (ytLine == null) return 0;
        try {
            Matcher m = Pattern.compile("(\\d{1,3}(?:[.,]\\d+)?)%").matcher(ytLine);
            if (m.find()) {
                String num = m.group(1).replace(',', '.');
                double v = Double.parseDouble(num);
                int iv = (int) Math.round(v);
                return Math.max(0, Math.min(100, iv));
            }
        } catch (Exception ignored) {
        }
        try {
            String num = ytLine.length() >= 10 ? ytLine.substring(10).trim().split("%")[0].trim() : ytLine.trim().split("%")[0].trim();
            return (int) Double.parseDouble(num);
        } catch (Exception e) {
            return 0;
        }
    }
}
