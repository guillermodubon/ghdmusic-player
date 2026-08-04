package io.github.guillermodubon.musicplayer.services.downloads.logging;

import io.github.guillermodubon.musicplayer.services.downloads.context.DownloadTaskContext;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class DownloadLog {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("musicplayer.download.debug", "true")
    );
    private static final boolean PROCESS_OUTPUT_ENABLED = Boolean.parseBoolean(
            System.getProperty("musicplayer.download.processOutput", "true")
    );
    private static final Map<String, Integer> LAST_PROGRESS = new ConcurrentHashMap<>();

    private DownloadLog() {
    }

    public static void info(String component, String message) {
        print("INFO", component, message, null);
    }

    public static void warn(String component, String message) {
        print("WARN", component, message, null);
    }

    public static void error(String component, String message, Throwable error) {
        print("ERROR", component, message, error);
    }

    public static void processOutput(String component, String line) {
        if (!PROCESS_OUTPUT_ENABLED || line == null || line.isBlank()) return;
        print("PROCESS", component, line, null);
    }

    public static void command(String component, List<String> command, File workingDirectory) {
        if (!ENABLED) return;
        String rendered = command == null
                ? "<empty>"
                : command.stream().map(DownloadLog::quoteArgument).reduce((a, b) -> a + " " + b).orElse("<empty>");
        info(component, "Executing command: " + rendered
                + " | workingDir=" + pathOf(workingDirectory));
    }

    public static void progress(DownloadTaskContext context, int percent) {
        if (!ENABLED) return;
        int normalized = Math.max(0, Math.min(100, percent));
        String key = taskLabel(context);
        Integer previous = LAST_PROGRESS.put(key, normalized);
        if (previous == null || normalized == 100 || Math.abs(normalized - previous) >= 5) {
            info("Progress", key + " -> " + normalized + "%");
        }
        if (normalized >= 100) {
            LAST_PROGRESS.remove(key);
        }
    }

    public static String taskLabel(DownloadTaskContext context) {
        if (context == null) return "task=<null>";
        return "query=\"" + clean(context.getQuery()) + "\""
                + ", target=\"" + pathOf(context.getTargetDir()) + "\"";
    }

    public static String pathOf(File file) {
        return file == null ? "<null>" : file.getAbsolutePath();
    }

    private static void print(String level, String component, String message, Throwable error) {
        if (!ENABLED) return;
        String timestamp = LocalDateTime.now().format(TIME_FORMAT);
        String thread = Thread.currentThread().getName();
        String safeComponent = component == null || component.isBlank() ? "Downloads" : component;
        String safeMessage = clean(message);
        String output = "[DOWNLOAD][" + level + "][" + timestamp + "][" + thread + "]["
                + safeComponent + "] " + safeMessage;

        synchronized (DownloadLog.class) {
            if ("ERROR".equals(level) || "WARN".equals(level)) {
                System.err.println(output);
                if (error != null) error.printStackTrace(System.err);
            } else {
                System.out.println(output);
                if (error != null) error.printStackTrace(System.out);
            }
        }
    }

    private static String quoteArgument(String argument) {
        if (argument == null) return "\"\"";
        if (argument.isBlank() || argument.chars().anyMatch(Character::isWhitespace)) {
            return "\"" + argument.replace("\"", "\\\"") + "\"";
        }
        return argument;
    }

    private static String clean(String value) {
        if (value == null) return "<null>";
        String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.length() > 600 ? normalized.substring(0, 600) + "..." : normalized;
    }
}
