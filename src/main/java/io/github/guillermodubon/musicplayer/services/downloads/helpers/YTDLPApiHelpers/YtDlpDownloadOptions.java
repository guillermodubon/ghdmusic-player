package io.github.guillermodubon.musicplayer.services.downloads.helpers.YTDLPApiHelpers;

/**
 * Centralized yt-dlp tuning for audio downloads. Keep options here so download
 * behavior is reproducible and not affected by user-level yt-dlp config files.
 */
public final class YtDlpDownloadOptions {

    public static final int SEARCH_CANDIDATE_LIMIT = 8;
    public static final int RETRIES = 3;
    public static final int FRAGMENT_RETRIES = 4;
    public static final int FILE_ACCESS_RETRIES = 5;
    public static final int SOCKET_TIMEOUT_SECONDS = 25;
    public static final int CONCURRENT_FRAGMENTS = 4;
    public static final int TRIM_FILENAMES_LENGTH = 180;

    public static final String FORMAT = "bestaudio[acodec!=none]/bestaudio/best";
    public static final String FORMAT_SORT = "aext:m4a,abr,asr";
    public static final String MATCH_FILTER = "!is_live & !is_drm";
    public static final String PROGRESS_DELTA_SECONDS = "0.4";

    private YtDlpDownloadOptions() {
    }

    public static int clampCandidateIndex(int candidateIndex) {
        return Math.max(1, Math.min(SEARCH_CANDIDATE_LIMIT, candidateIndex));
    }
}
