package io.github.guillermodubon.musicplayer.utils;

/** Utility methods for file names independent of the storage or playback layer. */
public final class FileNameUtils {

    private FileNameUtils() {
    }

    public static String withoutExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int extensionStart = fileName.lastIndexOf('.');
        return extensionStart > 0 ? fileName.substring(0, extensionStart) : fileName;
    }
}
