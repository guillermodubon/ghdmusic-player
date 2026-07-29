package io.github.guillermodubon.musicplayer.utils;

import java.nio.file.Path;


public class SongDataHelper {

    //Function to clean a String(Song name), removing the extension
    public static String removeFileExtension(String fileName) {
        if (fileName == null) return "";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            return fileName.substring(0, dotIndex);
        }
        return fileName;
    }

    //Function to verify if the scanned filed is an audio file
    public static boolean isAudioFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        return fileName.endsWith(".mp3") || fileName.endsWith(".wav") || fileName.endsWith(".flac");
    }


    public static String sanitizeForFileKey(String input) {
        if (input == null) return "";
        // Remove invalid characters from Windows filenames: \ / : * ? " < > |
        String s = input.replaceAll("[\\\\/:*?\"<>|]", "");
        // Normalize spaces
        s = s.replaceAll("\\s+", " ").trim();
        // Limit to a reasonable length
        if (s.length() > 200) s = s.substring(0, 200).trim();
        return s;
    }

    public static String fallbackKey(String input) {
        if (input == null) return "";
        String s = sanitizeForFileKey(input);
        // remove residual punctuation such as . , ; ! ? (we already removed ?) — here we remove anything that is not alphanumeric or a space
        s = s.replaceAll("[^\\p{Alnum}\\s-]", "");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

}
