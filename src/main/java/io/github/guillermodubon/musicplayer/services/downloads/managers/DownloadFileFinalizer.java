package io.github.guillermodubon.musicplayer.services.downloads.managers;

import io.github.guillermodubon.musicplayer.services.downloads.context.DownloadTaskContext;
import io.github.guillermodubon.musicplayer.services.downloads.helpers.DownloadFileNameHelper;
import io.github.guillermodubon.musicplayer.services.downloads.logging.DownloadLog;
import io.github.guillermodubon.musicplayer.services.downloads.services.DownloadMetadataService;
import io.github.guillermodubon.musicplayer.services.downloads.services.DownloadMetadataNormalizer;
import io.github.guillermodubon.musicplayer.services.downloads.services.DownloadPipelineExecutors;
import io.github.guillermodubon.musicplayer.services.downloads.services.DownloadPostProcessorService;
import io.github.guillermodubon.musicplayer.services.downloads.preferences.DownloadAudioPreset;
import io.github.guillermodubon.musicplayer.models.DeezerApiMetaData;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class DownloadFileFinalizer {

    private final DownloadMetadataService metadataService;
    private final DownloadPostProcessorService postProcessorService;



    public DownloadFileFinalizer() {
        this(new DownloadMetadataService(), new DownloadPostProcessorService());
    }

    public DownloadFileFinalizer(DownloadMetadataService metadataService,
                                 DownloadPostProcessorService postProcessorService) {
        this.metadataService = metadataService;
        this.postProcessorService = postProcessorService;
    }

    public File resolveFinalTarget(DownloadTaskContext context, String desiredBase) {
        if (context == null || context.getTargetDir() == null || desiredBase == null) {
            return null;
        }

        DownloadAudioPreset preset = context.getAudioPreset();
        return new File(context.getTargetDir(), desiredBase + "." + preset.getFileExtension());
    }

    public boolean alreadyExists(DownloadTaskContext context, String desiredBase) {
        File finalTarget = resolveFinalTarget(context, desiredBase);
        boolean exists = finalTarget != null && finalTarget.exists() && finalTarget.isFile();

        if (exists) {
            DownloadLog.info(
                    "DownloadFileFinalizer",
                    "Existing final file found: " + DownloadLog.pathOf(finalTarget)
            );
        }

        return exists;
    }

    /**
     * Only finalizes the physical file.
     *
     * Important:
     * This method must NOT mark the DownloadTask as completed.
     * Completion now belongs to DownloadTask after:
     * - playable publication
     * - metadata fetch
     * - DB/cache/manifest post-processing
     * - UI/playback sync triggered by post-processing
     */
    public File finalizeDownloadedFile(DownloadTaskContext context, String desiredBase, File createdTmp) {
        if (context == null || desiredBase == null || createdTmp == null || !createdTmp.exists()) {
            DownloadLog.warn(
                    "DownloadFileFinalizer",
                    "Cannot finalize file because context/base/tmp file is invalid."
            );
            return null;
        }

        File desired = resolveFinalTarget(context, desiredBase);

        if (desired == null) {
            DownloadLog.warn("DownloadFileFinalizer", "Could not resolve final target.");
            return null;
        }

        File finalFile = DownloadFileNameHelper.resolveUniqueFile(desired);

        DownloadLog.info(
                "DownloadFileFinalizer",
                "Finalizing temporary file " + DownloadLog.pathOf(createdTmp)
                        + " -> " + DownloadLog.pathOf(finalFile)
        );

        boolean ok = DownloadFileNameHelper.attemptRenameOrCopy(createdTmp, finalFile);

        if (!ok) {
            DownloadLog.warn(
                    "DownloadFileFinalizer",
                    "Rename/copy failed for " + DownloadLog.pathOf(createdTmp)
            );

            // A copy can leave a partial destination even when it reports an
            // error. Remove that destination here; DownloadTask removes the
            // source temporary artifact as part of its failure cleanup.
            try {
                if (finalFile.exists() && !finalFile.delete()) {
                    DownloadLog.warn(
                            "DownloadFileFinalizer",
                            "Could not delete partial final file: " + DownloadLog.pathOf(finalFile)
                    );
                }
            } catch (Exception ex) {
                DownloadLog.error(
                        "DownloadFileFinalizer",
                        "Could not delete partial final file: " + DownloadLog.pathOf(finalFile),
                        ex
                );
            }

            return null;
        }

        if (!isUsableAudioFile(finalFile)) {
            DownloadLog.warn(
                    "DownloadFileFinalizer",
                    "Final file is not usable after rename/copy: " + DownloadLog.pathOf(finalFile)
            );

            // Do not leave a zero-byte or otherwise invalid audio artifact
            // behind when the physical finalization appeared to succeed.
            try {
                if (finalFile.exists() && !finalFile.delete()) {
                    DownloadLog.warn(
                            "DownloadFileFinalizer",
                            "Could not delete unusable final file: " + DownloadLog.pathOf(finalFile)
                    );
                }
            } catch (Exception ex) {
                DownloadLog.error(
                        "DownloadFileFinalizer",
                        "Could not delete unusable final file: " + DownloadLog.pathOf(finalFile),
                        ex
                );
            }

            return null;
        }

        return finalFile;
    }

    /**
     * Legacy fire-and-forget method.
     *
     * Kept for backwards compatibility. New DownloadTask flow must await
     * processMetadataAsync(...) before setting task COMPLETED.
     */
    public void fetchAndProcessMetadataAsync(String desiredBase, File finalFile) {
        fetchAndProcessMetadataAsync(null, desiredBase, finalFile);
    }

    /**
     * Legacy fire-and-forget method.
     *
     * This starts the complete integration pipeline but intentionally does not
     * mark a DownloadTask as completed.
     */
    public void fetchAndProcessMetadataAsync(DownloadTaskContext context, String desiredBase, File finalFile) {
        fetchAndProcessMetadataFuture(context, desiredBase, finalFile);
    }

    /**
     * Preferred method if the caller wants one full future representing the
     * durable integration and final playable publication of a downloaded song.
     */
    public CompletableFuture<Void> fetchAndProcessMetadataFuture(DownloadTaskContext context,
                                                                 String desiredBase,
                                                                 File finalFile) {
        if (desiredBase == null || desiredBase.isBlank() || finalFile == null) {
            return CompletableFuture.completedFuture(null);
        }

        return processMetadataAsync(context, desiredBase, finalFile);
    }

    /**
     * Runs metadata resolution and the full durable integration phase. The
     * returned metadata can be published later, once the caller reaches 100%.
     */
    public CompletableFuture<DeezerApiMetaData> prepareMetadataAsync(DownloadTaskContext context,
                                                                       String desiredBase,
                                                                       File finalFile) {
        if (desiredBase == null || desiredBase.isBlank() || finalFile == null) {
            return CompletableFuture.completedFuture(null);
        }

        if (!isUsableAudioFile(finalFile)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Cannot process metadata for invalid file: " + DownloadLog.pathOf(finalFile))
            );
        }

        long startedAt = System.nanoTime();

        DownloadLog.info(
                "DownloadFileFinalizer",
                "Starting metadata/post-processing for " + DownloadLog.pathOf(finalFile)
        );

        return metadataService
                .fetchAsync(context, desiredBase)
                .handleAsync((fetchedMetadata, fetchError) -> {
                    DeezerApiMetaData metadataHint =
                            context == null
                                    ? null
                                    : context.getMetadataHint();

                    /*
                     * Downloads initiated from SongItemVisual already know the exact song,
                     * album, and artists. Their metadataHint should take precedence
                     * when it contains valid album artists.
                     */
                    if (context != null
                            && context.getSourceSong() != null
                            && hasUsableAlbumArtists(metadataHint)) {
                        return enrichAlbumArtistData(
                                metadataHint,
                                fetchedMetadata
                        );
                    }

                    if (fetchError != null) {
                        DownloadLog.warn(
                                "DownloadFileFinalizer",
                                "Metadata fetch failed. Trying metadata hint fallback for "
                                        + DownloadLog.pathOf(finalFile)
                        );

                        if (metadataHint != null) {
                            return metadataHint;
                        }

                        throw new CompletionException(
                                fetchError
                        );
                    }

                    if (hasUsableAlbumArtists(fetchedMetadata)) {
                        return fetchedMetadata;
                    }

                    if (hasUsableAlbumArtists(metadataHint)) {
                        return metadataHint;
                    }

                    return fetchedMetadata != null
                            ? fetchedMetadata
                            : metadataHint;

                }, DownloadPipelineExecutors.completion())
                .thenComposeAsync(meta -> {
                    try {
                        var normalized = DownloadMetadataNormalizer.normalize(
                                meta,
                                context,
                                desiredBase,
                                finalFile
                        );

                        return postProcessorService.prepareAsync(normalized, finalFile)
                                .thenApply(ignored -> normalized);
                    } catch (Exception ex) {
                        return CompletableFuture.failedFuture(ex);
                    }
                }, DownloadPipelineExecutors.completion())
                .whenComplete((ignored, error) -> {
                    if (error == null) {
                        DownloadLog.info(
                                "DownloadFileFinalizer",
                                "Metadata preparation completed in " + elapsedMillis(startedAt) + " ms for "
                                        + DownloadLog.pathOf(finalFile)
                        );
                    } else {
                        DownloadLog.error(
                                "DownloadFileFinalizer",
                                "Metadata preparation pipeline failed for "
                                        + DownloadLog.pathOf(finalFile),
                                unwrap(error)
                        );
                    }
                });
    }

    /**
     * Backwards-compatible full pipeline: preparation followed by final UI
     * publication. DownloadTask uses the two phases separately.
     */
    public CompletableFuture<Void> processMetadataAsync(
            DownloadTaskContext context,
            String desiredBase,
            File finalFile
    ) {
        return prepareMetadataAsync(
                context,
                desiredBase,
                finalFile
        ).thenCompose(meta ->
                postProcessorService.publishFinalAsync(
                        context,
                        meta,
                        finalFile
                )
        );
    }

    private boolean isUsableAudioFile(File file) {
        return file != null
                && file.exists()
                && file.isFile()
                && file.length() > 0L;
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof CompletionException && error.getCause() != null) {
            return error.getCause();
        }

        return error;
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    public CompletableFuture<Void> publishPreparedDownloadAsync(
            DownloadTaskContext context,
            DeezerApiMetaData meta,
            File finalFile
    ) {
        return postProcessorService.publishFinalAsync(
                context,
                meta,
                finalFile
        );
    }


    private boolean hasUsableAlbumArtists(
            DeezerApiMetaData metadata
    ) {
        if (metadata == null
                || metadata.getAlbumArtistNames() == null
                || metadata.getAlbumArtistNames().isEmpty()) {
            return false;
        }

        return metadata.getAlbumArtistNames()
                .stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(name -> !name.isBlank())
                .map(name -> name.toLowerCase(
                        java.util.Locale.ROOT
                ))
                .anyMatch(name ->
                        !name.equals("unknown")
                                && !name.equals("unknown artist")
                                && !name.equals("desconocido")
                );
    }

    /**
     * A source Song is authoritative for the downloaded track, but its album
     * model may have been created before all album owners were resolved. Merge
     * the owners returned by Deezer instead of discarding them in favour of the
     * source hint. Names, IDs and portraits remain positionally aligned.
     */
    private DeezerApiMetaData enrichAlbumArtistData(
            DeezerApiMetaData metadataHint,
            DeezerApiMetaData fetchedMetadata
    ) {
        if (metadataHint == null || fetchedMetadata == null) {
            return metadataHint;
        }

        List<String> mergedNames = metadataHint.getAlbumArtistNames() == null
                ? new ArrayList<>()
                : new ArrayList<>(metadataHint.getAlbumArtistNames());
        List<Long> mergedIds = alignIds(
                mergedNames,
                metadataHint.getAlbumArtistIds()
        );
        List<List<byte[]>> mergedPortraits = alignPortraits(
                mergedNames,
                metadataHint.getAlbumArtistsPortraitBytes()
        );

        List<String> fetchedNames = fetchedMetadata.getAlbumArtistNames();
        if (fetchedNames != null) {
            List<Long> fetchedIds = fetchedMetadata.getAlbumArtistIds();
            List<List<byte[]>> fetchedPortraits = fetchedMetadata.getAlbumArtistsPortraitBytes();

            for (int index = 0; index < fetchedNames.size(); index++) {
                String fetchedName = fetchedNames.get(index);
                if (!isRealArtistName(fetchedName)) {
                    continue;
                }

                long fetchedId = idAt(fetchedIds, index);
                int existingIndex = findArtistIndex(
                        mergedNames,
                        mergedIds,
                        fetchedName,
                        fetchedId
                );

                List<byte[]> fetchedPortrait = portraitAt(fetchedPortraits, index);
                if (existingIndex < 0) {
                    mergedNames.add(fetchedName.trim());
                    mergedIds.add(fetchedId);
                    mergedPortraits.add(copyImages(fetchedPortrait));
                    continue;
                }

                if (mergedIds.get(existingIndex) <= 0 && fetchedId > 0) {
                    mergedIds.set(existingIndex, fetchedId);
                }

                if (!hasUsableImageBytes(mergedPortraits.get(existingIndex))
                        && hasUsableImageBytes(fetchedPortrait)) {
                    mergedPortraits.set(existingIndex, copyImages(fetchedPortrait));
                }
            }
        }

        metadataHint.setAlbumArtistNames(mergedNames);
        metadataHint.setAlbumArtistIds(mergedIds);
        metadataHint.setAlbumArtistsPortraitBytes(mergedPortraits);

        return enrichContributorArtwork(metadataHint, fetchedMetadata);
    }

    private List<Long> alignIds(List<String> names, List<Long> ids) {
        List<Long> aligned = new ArrayList<>(names.size());
        for (int index = 0; index < names.size(); index++) {
            aligned.add(idAt(ids, index));
        }
        return aligned;
    }

    private List<List<byte[]>> alignPortraits(
            List<String> names,
            List<List<byte[]>> portraits
    ) {
        List<List<byte[]>> aligned = new ArrayList<>(names.size());
        for (int index = 0; index < names.size(); index++) {
            aligned.add(copyImages(portraitAt(portraits, index)));
        }
        return aligned;
    }

    private long idAt(List<Long> ids, int index) {
        if (ids == null || index < 0 || index >= ids.size() || ids.get(index) == null) {
            return 0L;
        }
        return Math.max(0L, ids.get(index));
    }

    private List<byte[]> portraitAt(List<List<byte[]>> portraits, int index) {
        if (portraits == null || index < 0 || index >= portraits.size()) {
            return List.of();
        }
        List<byte[]> portrait = portraits.get(index);
        return portrait == null ? List.of() : portrait;
    }

    private List<byte[]> copyImages(List<byte[]> images) {
        return images == null ? new ArrayList<>() : new ArrayList<>(images);
    }

    private int findArtistIndex(
            List<String> names,
            List<Long> ids,
            String candidateName,
            long candidateId
    ) {
        if (candidateName == null || candidateName.isBlank()) {
            return -1;
        }

        for (int index = 0; index < names.size(); index++) {
            long existingId = idAt(ids, index);
            if (candidateId > 0 && existingId > 0) {
                if (candidateId == existingId) return index;
                continue;
            }

            String existingName = names.get(index);
            if (existingName != null
                    && existingName.trim().equalsIgnoreCase(candidateName.trim())) {
                return index;
            }
        }

        return -1;
    }

    private boolean isRealArtistName(String name) {
        if (name == null || name.isBlank()) return false;
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        return !normalized.equals("unknown")
                && !normalized.equals("unknown artist")
                && !normalized.equals("desconocido")
                && !normalized.equals("various artists")
                && !normalized.equals("varios artistas");
    }

    /**
     * Keep the exact metadata supplied by the source song, but fill the
     * contributor portraits obtained from Deezer when a batch hint did not
     * carry them. The persistence layer maps these lists by contributor order.
     */
    private DeezerApiMetaData enrichContributorArtwork(
            DeezerApiMetaData metadataHint,
            DeezerApiMetaData fetchedMetadata
    ) {
        if (metadataHint == null
                || fetchedMetadata == null
                || !hasUsableContributorArtwork(fetchedMetadata)) {
            return metadataHint;
        }

        java.util.List<String> hintNames = metadataHint.getSongContributorNames();
        java.util.List<java.util.List<byte[]>> hintPortraits =
                metadataHint.getSongContributorsPortraitBytes();
        java.util.List<String> fetchedNames = fetchedMetadata.getSongContributorNames();
        java.util.List<java.util.List<byte[]>> fetchedPortraits =
                fetchedMetadata.getSongContributorsPortraitBytes();

        if (hintNames == null || hintNames.isEmpty()) {
            return metadataHint;
        }

        if (fetchedNames == null || fetchedNames.isEmpty()
                || fetchedPortraits == null) {
            return metadataHint;
        }

        java.util.List<java.util.List<byte[]>> merged = new java.util.ArrayList<>();
        for (int index = 0; index < hintNames.size(); index++) {
            java.util.List<byte[]> current = hintPortraits != null
                    && index < hintPortraits.size()
                    ? hintPortraits.get(index)
                    : null;

            if (!hasUsableImageBytes(current)) {
                int fetchedIndex = findContributorIndex(
                        hintNames.get(index),
                        fetchedNames
                );
                if (fetchedIndex >= 0 && fetchedIndex < fetchedPortraits.size()) {
                    java.util.List<byte[]> fetched = fetchedPortraits.get(fetchedIndex);
                    if (hasUsableImageBytes(fetched)) {
                        current = new java.util.ArrayList<>(fetched);
                    }
                }
            }

            merged.add(current == null
                    ? new java.util.ArrayList<>()
                    : new java.util.ArrayList<>(current));
        }

        metadataHint.setSongContributorsPortraitBytes(merged);
        return metadataHint;
    }

    private boolean hasUsableContributorArtwork(DeezerApiMetaData metadata) {
        if (metadata == null
                || metadata.getSongContributorNames() == null
                || metadata.getSongContributorNames().isEmpty()
                || metadata.getSongContributorsPortraitBytes() == null) {
            return false;
        }

        java.util.List<String> names = metadata.getSongContributorNames();
        java.util.List<java.util.List<byte[]>> portraits =
                metadata.getSongContributorsPortraitBytes();

        boolean foundArtwork = false;
        for (int index = 0; index < names.size(); index++) {
            String name = names.get(index);
            if (name == null || name.isBlank()) continue;
            if (index < portraits.size()
                    && hasUsableImageBytes(portraits.get(index))) {
                foundArtwork = true;
            }
        }

        return foundArtwork;
    }

    private boolean hasUsableImageBytes(java.util.List<byte[]> images) {
        if (images == null || images.isEmpty()) return false;
        return images.stream()
                .anyMatch(image -> image != null && image.length > 0);
    }

    private int findContributorIndex(
            String name,
            java.util.List<String> candidates
    ) {
        if (name == null || candidates == null) return -1;

        for (int index = 0; index < candidates.size(); index++) {
            String candidate = candidates.get(index);
            if (candidate != null && candidate.trim().equalsIgnoreCase(name.trim())) {
                return index;
            }
        }

        return -1;
    }

}
