package io.github.guillermodubon.musicplayer.services.downloads.preferences;

import java.util.Arrays;

/**
 * Supported audio download combinations exposed to the user.
 *
 * The first preset intentionally mirrors the command that was previously
 * hard-coded by the downloader: MP3 conversion with yt-dlp's best quality
 * setting.
 */
public enum DownloadAudioPreset {

    BEST_AVAILABLE(
            "best-available",
            "mp3",
            "0",
            "MP3 · Best quality (Recommended)"
    ),
    MP3_320(
            "mp3-320",
            "mp3",
            "320K",
            "MP3 · 320 kbps"
    ),
    MP3_192(
            "mp3-192",
            "mp3",
            "192K",
            "MP3 · 192 kbps"
    ),
    FLAC_LOSSLESS(
            "flac-lossless",
            "flac",
            "0",
            "FLAC · Lossless"
    ),
    WAV_LOSSLESS(
            "wav-lossless",
            "wav",
            "0",
            "WAV · Lossless"
    );

    private final String id;
    private final String audioFormat;
    private final String audioQuality;
    private final String label;

    DownloadAudioPreset(
            String id,
            String audioFormat,
            String audioQuality,
            String label
    ) {
        this.id = id;
        this.audioFormat = audioFormat;
        this.audioQuality = audioQuality;
        this.label = label;
    }

    public String getId() {
        return id;
    }

    public String getAudioFormat() {
        return audioFormat;
    }

    public String getAudioQuality() {
        return audioQuality;
    }

    /** The converted file uses the selected yt-dlp audio format extension. */
    public String getFileExtension() {
        return audioFormat;
    }

    @Override
    public String toString() {
        return label;
    }

    public static DownloadAudioPreset fromId(String id) {
        if (id == null || id.isBlank()) {
            return BEST_AVAILABLE;
        }

        return Arrays.stream(values())
                .filter(preset -> preset.id.equalsIgnoreCase(id.trim()))
                .findFirst()
                .orElse(BEST_AVAILABLE);
    }
}
