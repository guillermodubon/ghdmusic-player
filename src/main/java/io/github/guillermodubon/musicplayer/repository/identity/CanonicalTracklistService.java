package io.github.guillermodubon.musicplayer.repository.identity;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Persists verified Deezer album tracks without using provider IDs as local keys. */
public final class CanonicalTracklistService {

    private final CanonicalMediaService canonicalMedia;

    public CanonicalTracklistService() {
        this(new CanonicalMediaService());
    }

    public CanonicalTracklistService(CanonicalMediaService canonicalMedia) {
        this.canonicalMedia = Objects.requireNonNull(canonicalMedia, "canonicalMedia");
    }

    public void persist(Connection connection,
                        ExternalMediaId albumIdentity,
                        CanonicalMediaService.AlbumMetadata album,
                        Collection<TrackMetadata> tracks) throws SQLException {
        if (tracks == null || tracks.isEmpty()) return;
        List<String> albumArtists = album == null ? List.of() : album.artistNames();
        for (TrackMetadata track : tracks) {
            if (track == null || track.trackId() <= 0 || track.title() == null || track.title().isBlank()) continue;
            CanonicalizationResult<CanonicalMediaService.CanonicalSong> result =
                    canonicalMedia.ensureCanonicalSong(
                            connection,
                            ExternalMediaId.deezer(track.trackId()),
                            albumIdentity,
                            new CanonicalMediaService.SongMetadata(
                                    track.title(),
                                    track.trackOrder(),
                                    track.durationSeconds(),
                                    albumArtists,
                                    album,
                                    false,
                                    null
                            )
                    );
            if (!result.isSuccess()) {
                throw new SQLException("Tracklist identity conflict: " + result.conflict().reason());
            }
        }
    }

    public record TrackMetadata(long trackId, String title, int trackOrder, int durationSeconds) {
    }
}
