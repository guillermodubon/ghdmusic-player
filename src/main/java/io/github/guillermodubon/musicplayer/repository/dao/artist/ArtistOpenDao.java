package io.github.guillermodubon.musicplayer.repository.dao.artist;

import io.github.guillermodubon.musicplayer.models.Artist;

import java.sql.SQLException;
import java.util.Optional;

/** Persistence boundary for the artist data needed while opening an artist page. */
public interface ArtistOpenDao {

    Optional<Artist> findByIdIncludingAggregate(long artistId) throws SQLException;

    Optional<Artist> findByNameIgnoreCase(String name) throws SQLException;

    boolean hasPreferredPortrait(long artistId) throws SQLException;
}
