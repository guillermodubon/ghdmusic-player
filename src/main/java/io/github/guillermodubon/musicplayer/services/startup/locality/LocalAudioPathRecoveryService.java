package io.github.guillermodubon.musicplayer.services.startup.locality;

import io.github.guillermodubon.musicplayer.models.ManifestEntry;
import io.github.guillermodubon.musicplayer.models.Song;
import io.github.guillermodubon.musicplayer.services.scanning.ScannedAudioFile;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;
import io.github.guillermodubon.musicplayer.utils.SongAudioIdentity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Repairs persisted local paths after a user moves a media file inside one of
 * the configured library folders. The service deliberately matches file
 * evidence, never a song title by itself, so unrelated tracks cannot borrow
 * another recording's audio.
 */
public final class LocalAudioPathRecoveryService {

    private static final long RESCAN_COOLDOWN_MILLIS = 1_500L;

    private final StartUpService owner;
    private final Object scanLock = new Object();
    private volatile AudioFileIndex index = AudioFileIndex.empty();
    /** Timestamp of the last on-demand recovery scan, not of the startup scan. */
    private volatile long lastOnDemandRecoveryScanAt;

    public LocalAudioPathRecoveryService(StartUpService owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    /** Reuses the startup scan so reconciliation never walks the disk twice. */
    public void replaceScanIndex(Collection<ScannedAudioFile> scannedFiles) {
        index = AudioFileIndex.from(scannedFiles);
    }

    /**
     * Repairs only rows already known as local whose old absolute path no
     * longer exists. This runs inside the startup transaction before models
     * are hydrated, so local cells render correctly on the first screen.
     */
    public int reconcilePersistedLocalPaths(Connection connection,
                                             Map<String, ManifestEntry> manifest) throws SQLException {
        if (connection == null || index.isEmpty()) return 0;

        Map<Long, ManifestEvidence> evidenceBySongId = evidenceBySongId(manifest);
        Map<Long, String> repaired = new HashMap<>();

        try (PreparedStatement rows = connection.prepareStatement("""
                SELECT SongID, FilePath
                  FROM Song
                 WHERE IsLocal = 1
                   AND trim(COALESCE(FilePath, '')) <> ''
                """);
             ResultSet result = rows.executeQuery()) {
            while (result.next()) {
                long songId = result.getLong("SongID");
                String oldPath = result.getString("FilePath");
                if (isReadable(oldPath)) continue;

                findMovedFile(songId, oldPath, evidenceBySongId)
                        .map(file -> file.path().toString())
                        .ifPresent(path -> repaired.put(songId, path));
            }
        }

        if (repaired.isEmpty()) return 0;

        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE Song SET IsLocal = 1, FilePath = ? WHERE SongID = ?")) {
            for (Map.Entry<Long, String> repair : repaired.entrySet()) {
                update.setString(1, repair.getValue());
                update.setLong(2, repair.getKey());
                update.addBatch();
            }
            update.executeBatch();
        }
        return repaired.size();
    }

    /**
     * Playback-only fallback. A full scan is performed only after the cached
     * scan cannot prove the new location, and scans are coalesced by a short
     * cooldown to keep repeated presses from creating disk-I/O storms.
     */
    public Optional<String> recoverMovedPath(Song requestedSong) {
        if (requestedSong == null) return Optional.empty();

        Map<Long, ManifestEvidence> evidenceBySongId = evidenceBySongId(owner.getManifestService().load());
        Optional<RecoveredPath> current = recoverFromCurrentIndex(requestedSong, evidenceBySongId);
        if (current.isPresent()) {
            return publishRecovery(current.get()).map(path -> path.toString());
        }

        synchronized (scanLock) {
            current = recoverFromCurrentIndex(requestedSong, evidenceBySongId);
            if (current.isPresent()) {
                return publishRecovery(current.get()).map(path -> path.toString());
            }

            long now = System.currentTimeMillis();
            if (now - lastOnDemandRecoveryScanAt < RESCAN_COOLDOWN_MILLIS) {
                return Optional.empty();
            }

            replaceScanIndex(owner.scannerService().scanLocalAudioFiles());
            lastOnDemandRecoveryScanAt = now;
            current = recoverFromCurrentIndex(requestedSong, evidenceBySongId);
            return current.flatMap(this::publishRecovery).map(path -> path.toString());
        }
    }

    private Optional<RecoveredPath> recoverFromCurrentIndex(
            Song requestedSong,
            Map<Long, ManifestEvidence> evidenceBySongId
    ) {
        if (isReadable(requestedSong.getFilePath())) {
            return Optional.of(new RecoveredPath(requestedSong, Path.of(requestedSong.getFilePath())));
        }

        Optional<Song> persistedLocalPeer = findPersistedLocalPeer(requestedSong);
        if (persistedLocalPeer.isPresent()) {
            Song peer = persistedLocalPeer.get();
            Optional<ScannedAudioFile> peerFile = findMovedFile(
                    peer.getSongID(), peer.getFilePath(), evidenceBySongId
            );
            if (peerFile.isPresent()) {
                return Optional.of(new RecoveredPath(peer, peerFile.get().path()));
            }
        }

        return findMovedFile(
                requestedSong.getSongID(),
                requestedSong.getFilePath(),
                evidenceBySongId
        ).map(file -> new RecoveredPath(requestedSong, file.path()));
    }

    private Optional<Path> publishRecovery(RecoveredPath recovery) {
        if (recovery == null || !isReadable(recovery.path().toString())) {
            return Optional.empty();
        }
        owner.applyRecoveredLocalPath(recovery.persistedSong(), recovery.path().toString());
        return Optional.of(recovery.path());
    }

    private Optional<Song> findPersistedLocalPeer(Song requestedSong) {
        Optional<String> requestedIdentity = SongAudioIdentity.keyFor(requestedSong);
        if (requestedIdentity.isEmpty()) return Optional.empty();

        List<Song> snapshot;
        synchronized (owner.getSongs()) {
            snapshot = new ArrayList<>(owner.getSongs());
        }
        return snapshot.stream()
                .filter(Objects::nonNull)
                .filter(Song::isLocal)
                .filter(song -> song.getFilePath() != null && !song.getFilePath().isBlank())
                .filter(song -> requestedIdentity.equals(SongAudioIdentity.keyFor(song)))
                .sorted(Comparator.comparingLong(Song::getSongID))
                .findFirst();
    }

    private Optional<ScannedAudioFile> findMovedFile(long songId,
                                                      String oldPath,
                                                      Map<Long, ManifestEvidence> evidenceBySongId) {
        if (index.isEmpty()) return Optional.empty();

        ManifestEvidence evidence = evidenceBySongId.get(songId);
        List<String> names = new ArrayList<>(2);
        String oldName = baseName(oldPath);
        if (!oldName.isBlank()) names.add(oldName);
        if (evidence != null && !evidence.fileName().isBlank()) names.add(evidence.fileName());

        for (String name : names) {
            List<ScannedAudioFile> candidates = index.byFileName(name);
            Optional<ScannedAudioFile> selected = selectCandidate(candidates, evidence);
            if (selected.isPresent()) return selected;
        }
        return Optional.empty();
    }

    private Optional<ScannedAudioFile> selectCandidate(List<ScannedAudioFile> candidates,
                                                        ManifestEvidence evidence) {
        if (candidates == null || candidates.isEmpty()) return Optional.empty();
        List<ScannedAudioFile> readable = candidates.stream()
                .filter(ScannedAudioFile::isReadable)
                .toList();
        if (readable.size() == 1) return Optional.of(readable.getFirst());
        if (readable.isEmpty()) return Optional.empty();

        if (evidence != null && evidence.fileSize() > 0L && evidence.lastModified() > 0L) {
            List<ScannedAudioFile> exact = readable.stream()
                    .filter(file -> file.fileSize() == evidence.fileSize())
                    .filter(file -> file.lastModified() == evidence.lastModified())
                    .toList();
            if (exact.size() == 1) return Optional.of(exact.getFirst());
        }
        if (evidence != null && evidence.fileSize() > 0L) {
            List<ScannedAudioFile> sizeMatches = readable.stream()
                    .filter(file -> file.fileSize() == evidence.fileSize())
                    .toList();
            if (sizeMatches.size() == 1) return Optional.of(sizeMatches.getFirst());
        }
        if (evidence != null && evidence.lastModified() > 0L) {
            List<ScannedAudioFile> timestampMatches = readable.stream()
                    .filter(file -> file.lastModified() == evidence.lastModified())
                    .toList();
            if (timestampMatches.size() == 1) return Optional.of(timestampMatches.getFirst());
        }
        return Optional.empty();
    }

    private Map<Long, ManifestEvidence> evidenceBySongId(Map<String, ManifestEntry> manifest) {
        if (manifest == null || manifest.isEmpty()) return Map.of();
        Map<Long, ManifestEvidence> evidence = new HashMap<>();
        for (Map.Entry<String, ManifestEntry> entry : manifest.entrySet()) {
            ManifestEntry value = entry.getValue();
            if (value == null || value.getDeezerId() <= 0) continue;
            String fileName = value.getFileName();
            if (fileName == null || fileName.isBlank()) fileName = entry.getKey();
            evidence.putIfAbsent(value.getDeezerId(), new ManifestEvidence(
                    baseName(fileName), value.getLastModified(), value.getFileSize()
            ));
        }
        return evidence;
    }

    private static boolean isReadable(String path) {
        if (path == null || path.isBlank()) return false;
        try {
            Path candidate = Path.of(path);
            return Files.isRegularFile(candidate) && Files.isReadable(candidate) && Files.size(candidate) > 0L;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String baseName(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            Path path = Path.of(value);
            Path fileName = path.getFileName();
            value = fileName == null ? value : fileName.toString();
        } catch (Exception ignored) {
        }
        int extension = value.lastIndexOf('.');
        if (extension > 0) value = value.substring(0, extension);
        return value.replace('_', ' ')
                .replace('-', ' ')
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private record ManifestEvidence(String fileName, long lastModified, long fileSize) {
    }

    private record RecoveredPath(Song persistedSong, Path path) {
    }

    private record AudioFileIndex(Map<String, List<ScannedAudioFile>> byFileName) {
        static AudioFileIndex empty() {
            return new AudioFileIndex(Map.of());
        }

        static AudioFileIndex from(Collection<ScannedAudioFile> files) {
            if (files == null || files.isEmpty()) return empty();
            Map<String, List<ScannedAudioFile>> index = new HashMap<>();
            for (ScannedAudioFile file : files) {
                if (file == null || file.fileName() == null) continue;
                String key = baseName(file.fileName());
                if (!key.isBlank()) index.computeIfAbsent(key, ignored -> new ArrayList<>()).add(file);
            }
            index.replaceAll((key, value) -> List.copyOf(value));
            return new AudioFileIndex(Map.copyOf(index));
        }

        List<ScannedAudioFile> byFileName(String value) {
            return byFileName.getOrDefault(baseName(value), List.of());
        }

        boolean isEmpty() {
            return byFileName.isEmpty();
        }
    }
}
