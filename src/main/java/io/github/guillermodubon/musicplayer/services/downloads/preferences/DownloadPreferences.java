package io.github.guillermodubon.musicplayer.services.downloads.preferences;

import java.io.File;
import java.util.prefs.Preferences;

public final class DownloadPreferences {

    private static final Preferences PREFS = Preferences.userNodeForPackage(DownloadPreferences.class);
    private static final String KEY_DOWNLOAD_PATH = "downloadPath";

    private DownloadPreferences() {}

    public static void saveDownloadDirectory(File dir) {
        if (dir == null) return;
        PREFS.put(KEY_DOWNLOAD_PATH, dir.getAbsolutePath());
    }

    public static File loadDownloadDirectory() {
        String saved = PREFS.get(KEY_DOWNLOAD_PATH, null);

        if (saved != null && !saved.isBlank()) {
            File candidate = new File(saved);
            if (candidate.exists() && candidate.isDirectory()) {
                return candidate;
            }
        }

        return getDefaultDownloadsDirectory();
    }

    public static File getDefaultDownloadsDirectory() {
        return new File(System.getProperty("user.home"), "Downloads");
    }
}
