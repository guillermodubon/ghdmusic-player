package io.github.guillermodubon.musicplayer.services.downloads.helpers.YTDLPApiHelpers;

import io.github.guillermodubon.musicplayer.services.downloads.helpers.DownloadFileNameHelper;
import io.github.guillermodubon.musicplayer.services.downloads.preferences.DownloadAudioPreset;
import io.github.guillermodubon.musicplayer.services.downloads.preferences.DownloadPreferences;
import io.github.guillermodubon.musicplayer.services.downloads.provider.YouTubeExecutionPolicy;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class YtDlpCommandBuilder {

    private YtDlpCommandBuilder() {}


    public static String buildOutputTemplate(File targetDir, String downloadToken) {
        String safeToken = DownloadFileNameHelper.sanitizeToken(downloadToken);
        return targetDir.getAbsolutePath() + File.separator + "dl_tmp_" + safeToken + "_%(id)s.%(ext)s";
    }


    public static List<String> buildBaseArgs(String query, File targetDir, int candidateIndex, String downloadToken) {
        return buildBaseArgs(
                query,
                targetDir,
                candidateIndex,
                downloadToken,
                DownloadPreferences.loadAudioPreset()
        );
    }

    public static List<String> buildBaseArgs(
            String query,
            File targetDir,
            int candidateIndex,
            String downloadToken,
            DownloadAudioPreset audioPreset
    ) {
        return buildBaseArgs(query, targetDir, candidateIndex, downloadToken, audioPreset, false,
                YouTubeExecutionPolicy.defaults());
    }

    public static List<String> buildBaseArgs(
            String query,
            File targetDir,
            int candidateIndex,
            String downloadToken,
            DownloadAudioPreset audioPreset,
            boolean bulkDownload,
            YouTubeExecutionPolicy policy
    ) {
        String raw = query == null ? "" : query.trim();
        String sanitizedQuery = DownloadFileNameHelper.sanitizeSearchQuery(raw);
        boolean queryLooksLikeUrl = DownloadFileNameHelper.looksLikeUrl(raw);
        int selectedCandidate = YtDlpDownloadOptions.clampCandidateIndex(candidateIndex);
        DownloadAudioPreset selectedPreset = audioPreset == null
                ? DownloadAudioPreset.BEST_AVAILABLE
                : audioPreset;

        String sourceArg;
        if (queryLooksLikeUrl) {
            sourceArg = raw;
        } else if (sanitizedQuery.isBlank()) {
            sourceArg = "ytsearch" + selectedCandidate + ":" + raw.replaceAll("\\s+", " ");
        } else {
            sourceArg = "ytsearch" + selectedCandidate + ":" + sanitizedQuery;
        }

        List<String> args = new ArrayList<>();
        args.add("--ignore-config");
        args.add("--no-config-locations");
        args.add("--newline");
        args.add("--no-overwrites");
        args.add("--no-warnings");
        args.add("--no-playlist");
        args.add("--encoding");
        args.add("utf-8");
        args.add("--windows-filenames");
        args.add("--trim-filenames");
        args.add(String.valueOf(YtDlpDownloadOptions.TRIM_FILENAMES_LENGTH));
        args.add("--no-mtime");
        args.add("--match-filter");
        args.add(YtDlpDownloadOptions.MATCH_FILTER);
        args.add("-f");
        args.add(YtDlpDownloadOptions.FORMAT);
        args.add("-S");
        args.add(YtDlpDownloadOptions.FORMAT_SORT);
        args.add("-x");
        args.add("--audio-format");
        args.add(selectedPreset.getAudioFormat());
        args.add("--audio-quality");
        args.add(selectedPreset.getAudioQuality());
        args.add("-o");
        args.add(buildOutputTemplate(targetDir, downloadToken));
        args.add("--retries");
        args.add(String.valueOf(bulkDownload
                ? selectedPolicy(policy).bulkHttpRetries()
                : YtDlpDownloadOptions.RETRIES));
        args.add("--fragment-retries");
        args.add(String.valueOf(bulkDownload
                ? selectedPolicy(policy).bulkFragmentRetries()
                : YtDlpDownloadOptions.FRAGMENT_RETRIES));
        args.add("--file-access-retries");
        args.add(String.valueOf(bulkDownload
                ? selectedPolicy(policy).bulkFileAccessRetries()
                : YtDlpDownloadOptions.FILE_ACCESS_RETRIES));
        args.add("--retry-sleep");
        args.add("http:linear=1:4:1");
        args.add("--retry-sleep");
        args.add("fragment:linear=1:3:1");
        args.add("--retry-sleep");
        args.add("file_access:linear=1:3:1");
        args.add("--socket-timeout");
        args.add(String.valueOf(YtDlpDownloadOptions.SOCKET_TIMEOUT_SECONDS));
        args.add("--concurrent-fragments");
        YouTubeExecutionPolicy selectedPolicy = selectedPolicy(policy);
        args.add(String.valueOf(selectedPolicy.maximumConcurrentFragments()));
        if (bulkDownload) {
            args.add("--sleep-requests");
            args.add(String.valueOf(selectedPolicy.requestSleepSeconds()));
            args.add("--sleep-interval");
            args.add(String.valueOf(selectedPolicy.bulkDownloadSleepMinimumSeconds()));
            args.add("--max-sleep-interval");
            args.add(String.valueOf(selectedPolicy.bulkDownloadSleepMaximumSeconds()));
        }
        args.add("--skip-unavailable-fragments");
        args.add("--progress-delta");
        args.add(YtDlpDownloadOptions.PROGRESS_DELTA_SECONDS);
        if (!queryLooksLikeUrl) {
            args.add("--playlist-items");
            args.add(String.valueOf(selectedCandidate));
        }
        args.add(sourceArg);

        return args;
    }

    private static YouTubeExecutionPolicy selectedPolicy(YouTubeExecutionPolicy policy) {
        return policy == null ? YouTubeExecutionPolicy.defaults() : policy;
    }
}
