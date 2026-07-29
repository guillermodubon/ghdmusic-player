package io.github.guillermodubon.musicplayer.repository.dao.artwork;

import java.sql.SQLException;
import java.util.Optional;

/** Reads the highest-quality persisted artwork for an album. */
public interface AlbumImageDao {

    Optional<byte[]> findBestImageData(long albumId) throws SQLException;
}
