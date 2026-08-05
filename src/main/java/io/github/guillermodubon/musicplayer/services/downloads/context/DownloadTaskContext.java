package io.github.guillermodubon.musicplayer.services.downloads.context;

import javafx.scene.image.Image;
import io.github.guillermodubon.musicplayer.models.DeezerApiMetaData;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import io.github.guillermodubon.musicplayer.models.Playlist;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.downloads.preferences.DownloadAudioPreset;
import io.github.guillermodubon.musicplayer.services.downloads.preferences.DownloadPreferences;

public class DownloadTaskContext {

    private final String query;
    private final File targetDir;
    private final String cleanSongName;
    private final String downloadToken;
    private List<String> searchQueries = List.of();

    private String artistForFile;
    private String fetchedTitle;
    private boolean sourceIsSongItem;
    private int maxAttempts = 3;
    private DeezerApiMetaData metadataHint;
    private Image coverImage;
    /* Captured per task so changing the preference never mutates an active task. */
    private DownloadAudioPreset audioPreset;

    /*
     * Publication is the last UI-facing step of a download. It must be
     * revocable when the task is cancelled or fails while a JavaFX callback
     * is still queued.
     */
    private volatile boolean downloadPublicationAllowed = true;

    /*
     * Original remote/source song used to create this download.
     *
     * This is required so the post-download integration can replace
     * the exact SongItemVisual/Song model with the resulting local Song.
     */
    private Song sourceSong;

    /*
     * Optional source collection context.
     *
     * Useful for albums/playlists/bulk sessions so PlaybackManager can update
     * the active flow/queue without forcing the user to restart playback.
     */
    private Long sourceCollectionId;
    private String sourceCollectionTitle;
    private String sourceCollectionType;

    private String bulkSessionId;
    private String bulkSessionTitle;
    private int bulkSongIndex = -1;
    private int bulkTotalSongs;

    /*
     * The exact model of the PlayerMenuController from which the download originated.
     *
     * Neither the controller nor a Node is stored, to avoid retaining JavaFX
     * components. Only the fully hydrated content model is kept.
     */
    private Playlist sourcePlaylistModel;


    public Playlist getSourcePlaylistModel() {
        return sourcePlaylistModel;
    }

    public void setSourcePlaylistModel(
            Playlist sourcePlaylistModel
    ) {
        this.sourcePlaylistModel = sourcePlaylistModel;
    }

    public DownloadTaskContext(String query, File targetDir, String cleanSongName) {
        this(query, targetDir, cleanSongName, createDownloadToken());
    }

    private DownloadTaskContext(String query, File targetDir, String cleanSongName, String downloadToken) {
        this.query = query;
        this.targetDir = targetDir;
        this.cleanSongName = cleanSongName;
        this.downloadToken = downloadToken == null || downloadToken.isBlank()
                ? createDownloadToken()
                : downloadToken;
        this.audioPreset = DownloadPreferences.loadAudioPreset();
    }

    public String getQuery() {
        return query;
    }

    /**
     * Ordered search alternatives. The first query is always the primary one;
     * later entries are used only after its search candidates have failed.
     */
    public List<String> getSearchQueries() {
        if (!searchQueries.isEmpty()) {
            return searchQueries;
        }

        return List.of(query == null ? "" : query);
    }

    public void setSearchQueries(Collection<String> queries) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();

        if (query != null && !query.isBlank()) {
            ordered.add(query.trim());
        }

        if (queries != null) {
            for (String candidate : queries) {
                if (candidate != null && !candidate.isBlank()) {
                    ordered.add(candidate.trim());
                }
            }
        }

        searchQueries = List.copyOf(new ArrayList<>(ordered));
    }

    public File getTargetDir() {
        return targetDir;
    }

    public String getCleanSongName() {
        return cleanSongName;
    }

    public String getDownloadToken() {
        return downloadToken;
    }

    public boolean isDownloadPublicationAllowed() {
        return downloadPublicationAllowed;
    }

    public void revokeDownloadPublication() {
        downloadPublicationAllowed = false;
    }

    public String getArtistForFile() {
        return artistForFile;
    }

    public void setArtistForFile(String artistForFile) {
        this.artistForFile = artistForFile;
    }

    public String getFetchedTitle() {
        return fetchedTitle;
    }

    public void setFetchedTitle(String fetchedTitle) {
        this.fetchedTitle = fetchedTitle;
    }

    public Image getCoverImage() {
        return coverImage;
    }

    public void setCoverImage(Image coverImage) {
        this.coverImage = coverImage;
    }

    public DownloadAudioPreset getAudioPreset() {
        return audioPreset == null
                ? DownloadAudioPreset.BEST_AVAILABLE
                : audioPreset;
    }

    public void setAudioPreset(DownloadAudioPreset audioPreset) {
        this.audioPreset = audioPreset == null
                ? DownloadAudioPreset.BEST_AVAILABLE
                : audioPreset;
    }

    public boolean isSourceIsSongItem() {
        return sourceIsSongItem;
    }

    public void setSourceIsSongItem(boolean sourceIsSongItem) {
        this.sourceIsSongItem = sourceIsSongItem;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public DeezerApiMetaData getMetadataHint() {
        return metadataHint;
    }

    public void setMetadataHint(DeezerApiMetaData metadataHint) {
        this.metadataHint = metadataHint;
    }

    public Song getSourceSong() {
        return sourceSong;
    }

    public void setSourceSong(Song sourceSong) {
        this.sourceSong = sourceSong;
    }

    public Long getSourceCollectionId() {
        return sourceCollectionId;
    }

    public void setSourceCollectionId(Long sourceCollectionId) {
        this.sourceCollectionId = sourceCollectionId;
    }


    public void setSourceCollectionTitle(String sourceCollectionTitle) {
        this.sourceCollectionTitle = sourceCollectionTitle;
    }

    public String getSourceCollectionType() {
        return sourceCollectionType;
    }

    public void setSourceCollectionType(String sourceCollectionType) {
        this.sourceCollectionType = sourceCollectionType;
    }

    public String getBulkSessionId() {
        return bulkSessionId;
    }

    public void setBulkSessionId(String bulkSessionId) {
        this.bulkSessionId = bulkSessionId;
    }

    public String getBulkSessionTitle() {
        return bulkSessionTitle;
    }

    public void setBulkSessionTitle(String bulkSessionTitle) {
        this.bulkSessionTitle = bulkSessionTitle;
    }

    public void setBulkSongIndex(int bulkSongIndex) {
        this.bulkSongIndex = bulkSongIndex;
    }

    public int getBulkTotalSongs() {
        return bulkTotalSongs;
    }

    public void setBulkTotalSongs(int bulkTotalSongs) {
        this.bulkTotalSongs = bulkTotalSongs;
    }

    public boolean isBulkDownload() {
        return bulkSessionId != null && !bulkSessionId.isBlank();
    }

    public DownloadTaskContext copy() {
        DownloadTaskContext copy = new DownloadTaskContext(query, targetDir, cleanSongName);

        copy.setArtistForFile(artistForFile);
        copy.setSearchQueries(searchQueries);
        copy.setFetchedTitle(fetchedTitle);
        copy.setSourceIsSongItem(sourceIsSongItem);
        copy.setMaxAttempts(maxAttempts);
        copy.setMetadataHint(metadataHint);
        copy.setCoverImage(coverImage);
        copy.setAudioPreset(audioPreset);

        copy.setSourceSong(sourceSong);
        copy.setSourceCollectionId(sourceCollectionId);
        copy.setSourceCollectionTitle(sourceCollectionTitle);
        copy.setSourceCollectionType(sourceCollectionType);

        copy.setBulkSessionId(bulkSessionId);
        copy.setBulkSessionTitle(bulkSessionTitle);
        copy.setBulkSongIndex(bulkSongIndex);
        copy.setBulkTotalSongs(bulkTotalSongs);

        copy.setSourcePlaylistModel(sourcePlaylistModel);

        return copy;
    }

    private static String createDownloadToken() {
        long now = System.nanoTime();
        long random = ThreadLocalRandom.current().nextLong();

        return "t" + Long.toUnsignedString(now, 36)
                + Long.toUnsignedString(random, 36).replace("-", "");
    }
}
